package dev.screenveil.app.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.screenveil.app.AppPreferences
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.ModelInstaller
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.model.ScreenModerationDetector
import dev.screenveil.app.overlay.OverlayController
import dev.screenveil.app.policy.ModerationPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ScreenVeil's capture lifecycle is owned by Android's AccessibilityService lifecycle.
 * It uses Android's screenshot API for on-device visual understanding; no MediaProjection
 * session, screen-recording consent dialog, or SYSTEM_ALERT_WINDOW permission is used.
 *
 * The service intentionally does not read text, inspect accessibility nodes, click controls,
 * or perform gestures. Accessibility events are used only as signals to schedule screenshots.
 */
class ScreenProtectionAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-screenshot").apply { priority = Thread.NORM_PRIORITY }
    }
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-gemma").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val redactionExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-redaction").apply { priority = Thread.NORM_PRIORITY }
    }

    private val screenshotInFlight = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val redactionInFlight = AtomicBoolean(false)
    private val modelInitializationStarted = AtomicBoolean(false)
    private val maskGeneration = AtomicLong(0L)

    @Volatile private var connected = false
    @Volatile private var monitoringRequested = false
    @Volatile private var modelReady = false
    @Volatile private var destroyed = false
    @Volatile private var activeRedactions: List<DetectedMediaRegion> = emptyList()
    @Volatile private var activeMediaRegionCount: Int = 0
    @Volatile private var currentForegroundPackage: String? = null

    // Engine and Conversation APIs are confined to inferenceExecutor.
    private var detector: ScreenContentDetector? = null
    // WindowManager operations are performed on the main thread. Bitmap preparation is worker-safe.
    private var overlay: OverlayController? = null
    private val preferences by lazy { AppPreferences(this) }

    private var lastScreenshotAt = 0L
    private var lastInferenceMs: Long? = null
    private var invalidResponses = 0
    private var inferenceFailures = 0

    private val periodicCapture = object : Runnable {
        override fun run() {
            if (!monitoringRequested || destroyed) return
            requestScreenshot()
            val interval = preferences.getSettings().frameIntervalMs.coerceIn(MIN_CAPTURE_INTERVAL_MS, MAX_CAPTURE_INTERVAL_MS)
            mainHandler.postDelayed(this, interval)
        }
    }

    private val debouncedCapture = Runnable {
        if (monitoringRequested && modelReady && !destroyed) requestScreenshot()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        destroyed = false
        currentInstance = this
        overlay = OverlayController(
            context = this,
            windowType = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            requiresDrawOverlaysPermission = false
        )

        // Be explicit about the event scope. We use events only to know when to inspect pixels;
        // no text or node hierarchy is read from other applications.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            // We need window IDs and screen bounds to capture the app window underneath our
            // accessibility overlay. The service never queries AccessibilityNodeInfo or text.
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = false,
                    message = "Android 14 or newer is required for application-window screenshots",
                    error = "Unsupported Android version"
                )
            )
            return
        }

        if (preferences.isProtectionEnabled()) {
            startProtection()
        } else {
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = false,
                    message = "Accessibility service enabled · protection is paused"
                )
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return
        val eventPackage = event.packageName?.toString()
        if (!eventPackage.isNullOrBlank()) currentForegroundPackage = eventPackage
        if (!monitoringRequested || !modelReady) return

        // Do not scan ScreenVeil's own settings screen or let our own UI generate scan loops.
        if (eventPackage == packageName) {
            clearMasks()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // A new foreground window invalidates old screen coordinates immediately.
                clearMasks()
                scheduleDebouncedCapture(WINDOW_CHANGE_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                clearMasks()
                scheduleDebouncedCapture(SCROLL_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleDebouncedCapture(CONTENT_CHANGE_DEBOUNCE_MS)
            }
        }
    }

    override fun onInterrupt() {
        pauseProtection("Android interrupted the accessibility service")
    }

    fun startProtection() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startProtection() }
            return
        }
        if (!connected || destroyed) return
        if (!ModelInstaller.isVerifiedInstalled(this)) {
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = false,
                    modelReady = false,
                    message = "Install and verify Gemma 4 before enabling protection",
                    error = "Gemma model is not installed"
                )
            )
            return
        }

        monitoringRequested = true
        preferences.setProtectionEnabled(true)
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = true,
                modelReady = modelReady,
                message = if (modelReady) "Starting screen inspection…" else "Loading Gemma 4 · screen monitoring will start automatically"
            )
        )

        if (modelReady) {
            beginMonitoring()
        } else if (modelInitializationStarted.compareAndSet(false, true)) {
            initializeModel()
        }
    }

    fun pauseProtection(message: String = "Protection paused") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { pauseProtection(message) }
            return
        }
        monitoringRequested = false
        preferences.setProtectionEnabled(false)
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        maskGeneration.incrementAndGet()
        overlay?.clear()
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                modelReady = modelReady,
                message = message
            )
        )
    }

    private fun initializeModel() {
        try {
            inferenceExecutor.execute {
                try {
                    check(ModelInstaller.verifyInstalledBlocking(this)) {
                        "Gemma model is missing or failed SHA-256 verification. Reinstall it in Settings."
                    }
                    val created = ScreenModerationDetector(this)
                    created.initialize(ModelInstaller.modelFile(this))
                    if (destroyed) {
                        created.close()
                        return@execute
                    }
                    detector = created
                    modelReady = true
                    mainHandler.post {
                        if (!destroyed && monitoringRequested) {
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = true,
                                    modelReady = true,
                                    message = "Protection active · Gemma 4 ${created.activeBackend} backend"
                                )
                            )
                            beginMonitoring()
                        }
                    }
                } catch (error: Throwable) {
                    modelReady = false
                    modelInitializationStarted.set(false)
                    val detail = error.message ?: error.javaClass.simpleName
                    mainHandler.post {
                        if (!destroyed) {
                            monitoringRequested = false
                            preferences.setProtectionEnabled(false)
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = false,
                                    modelReady = false,
                                    message = "Gemma could not be loaded",
                                    error = detail
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            modelInitializationStarted.set(false)
            ProtectionStatus.setMessage("Could not start the Gemma worker")
        }
    }

    private fun beginMonitoring() {
        if (!monitoringRequested || !modelReady || destroyed) return
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = true,
                modelReady = true,
                message = "Monitoring screen changes · all processing stays on this device",
                regionsRedacted = activeRedactions.size,
                lastInferenceMs = lastInferenceMs
            )
        )
        requestScreenshot()
        mainHandler.postDelayed(periodicCapture, preferences.getSettings().frameIntervalMs)
    }

    private fun scheduleDebouncedCapture(delayMs: Long) {
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.postDelayed(debouncedCapture, delayMs)
    }

    private data class ScreenshotTarget(val windowId: Int, val screenBounds: Rect)

    /**
     * Select the active application window and capture that window alone. Android documents
     * takeScreenshotOfWindow() specifically for capturing content underneath accessibility
     * overlays, avoiding feedback from ScreenVeil's own redaction windows.
     */
    private fun findScreenshotTarget(): ScreenshotTarget? {
        val availableWindows = windows
        var selected: AccessibilityWindowInfo? = null
        var selectedBounds: Rect? = null
        try {
            for (window in availableWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                if (selected == null || bounds.width().toLong() * bounds.height() >
                    selectedBounds!!.width().toLong() * selectedBounds!!.height()
                ) {
                    selected = window
                    selectedBounds = bounds
                }
            }
            val winner = selected ?: return null
            return ScreenshotTarget(winner.id, Rect(checkNotNull(selectedBounds)))
        } finally {
            // AccessibilityWindowInfo objects are managed by the framework. Do not retain them
            // beyond this method; only a primitive ID and a copied Rect leave this scope.
            availableWindows.forEach { runCatching { it.recycle() } }
        }
    }

    private fun requestScreenshot() {
        if (!monitoringRequested || !modelReady || destroyed ||
            currentForegroundPackage == packageName ||
            !screenshotInFlight.compareAndSet(false, true)
        ) return

        val now = SystemClock.elapsedRealtime()
        val wait = MIN_CAPTURE_INTERVAL_MS - (now - lastScreenshotAt)
        if (wait > 0L) {
            screenshotInFlight.set(false)
            mainHandler.removeCallbacks(debouncedCapture)
            mainHandler.postDelayed({ if (monitoringRequested) requestScreenshot() }, wait)
            return
        }

        val target = try {
            findScreenshotTarget()
        } catch (error: Throwable) {
            screenshotInFlight.set(false)
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = monitoringRequested,
                    modelReady = modelReady,
                    message = "Could not identify the active application window",
                    error = error.message ?: error.javaClass.simpleName
                )
            )
            return
        }
        if (target == null) {
            screenshotInFlight.set(false)
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = monitoringRequested,
                    modelReady = modelReady,
                    message = "Waiting for a capturable application window",
                    regionsRedacted = activeRedactions.size,
                    lastInferenceMs = lastInferenceMs
                )
            )
            return
        }

        lastScreenshotAt = now
        try {
            takeScreenshotOfWindow(
                target.windowId,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer: HardwareBuffer = screenshot.hardwareBuffer
                        try {
                            if (!monitoringRequested || destroyed) return
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?: error("Android could not wrap the application-window screenshot")
                            val frame = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: error("Could not copy application-window screenshot into a CPU bitmap")
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            dispatchFrame(frame, target.screenBounds)
                        } catch (error: Throwable) {
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = monitoringRequested,
                                    modelReady = modelReady,
                                    message = "Could not process the Android application-window screenshot",
                                    error = error.message ?: error.javaClass.simpleName
                                )
                            )
                        } finally {
                            // Android explicitly requires closing the HardwareBuffer on every path.
                            try {
                                buffer.close()
                            } finally {
                                screenshotInFlight.set(false)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotInFlight.set(false)
                        val detail = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android is rate-limiting screenshots; retrying shortly"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app protects its window with FLAG_SECURE; Android does not allow capture"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility screenshot access is unavailable; re-enable ScreenVeil in Settings"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The default display is unavailable"
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "The active app window changed before it could be captured"
                            else -> "Android screenshot failed (code $errorCode)"
                        }
                        ProtectionStatus.update(
                            ProtectionStatus.State(
                                active = monitoringRequested,
                                modelReady = modelReady,
                                message = detail,
                                error = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) null else detail,
                                regionsRedacted = activeRedactions.size,
                                lastInferenceMs = lastInferenceMs
                            )
                        )
                        if (monitoringRequested) {
                            mainHandler.postDelayed({ requestScreenshot() }, SCREENSHOT_RATE_LIMIT_RETRY_MS)
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            screenshotInFlight.set(false)
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = monitoringRequested,
                    modelReady = modelReady,
                    message = "Android application-window screenshot request failed",
                    error = error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private fun dispatchFrame(frame: Bitmap, screenBounds: Rect) {
        val settings = preferences.getSettings()
        if (inferenceInFlight.compareAndSet(false, true)) {
            submitInference(frame, settings, screenBounds)
            return
        }

        val existing = activeRedactions
        if (existing.isNotEmpty() && redactionInFlight.compareAndSet(false, true)) {
            submitRedactionRefresh(frame, existing, settings, maskGeneration.get(), screenBounds)
        } else if (!frame.isRecycled) {
            frame.recycle()
        }
    }

    private fun submitInference(
        frame: Bitmap,
        settings: dev.screenveil.app.policy.ModerationSettings,
        screenBounds: Rect
    ) {
        try {
            inferenceExecutor.execute {
                try {
                    if (destroyed || !monitoringRequested) return@execute
                    val currentDetector = detector ?: error("Gemma engine is not available")
                    val started = SystemClock.elapsedRealtime()
                    val result = currentDetector.inspect(frame)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (destroyed || !monitoringRequested) return@execute

                    if (!result.parseSucceeded) {
                        invalidResponses++
                        mainHandler.post {
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = true,
                                    modelReady = true,
                                    message = "Gemma structured response was invalid · no new mask was applied",
                                    error = result.diagnostic,
                                    regionsDetected = activeMediaRegionCount,
                                    regionsRedacted = activeRedactions.size,
                                    lastInferenceMs = elapsed
                                )
                            )
                        }
                        if (invalidResponses >= MAX_INVALID_RESPONSES) {
                            mainHandler.post { pauseProtection("Protection paused after repeated invalid Gemma responses") }
                        }
                        return@execute
                    }

                    invalidResponses = 0
                    inferenceFailures = 0
                    val redactions = result.regions.filter {
                        ModerationPolicy.shouldRedact(it.decision, settings.redactUncertain)
                    }
                    activeRedactions = redactions
                    activeMediaRegionCount = result.regions.size
                    lastInferenceMs = elapsed
                    val generation = maskGeneration.incrementAndGet()
                    val prepared = overlay?.prepare(frame, redactions, settings, screenBounds).orEmpty()
                    postPreparedMasks(generation, prepared, result.regions.size, redactions.size, elapsed)
                } catch (error: Throwable) {
                    if (!destroyed && monitoringRequested) {
                        inferenceFailures++
                        mainHandler.post {
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = true,
                                    modelReady = modelReady,
                                    message = "Gemma inference failed · previous valid masks retained",
                                    error = error.message ?: error.javaClass.simpleName,
                                    regionsDetected = activeMediaRegionCount,
                                    regionsRedacted = activeRedactions.size,
                                    lastInferenceMs = lastInferenceMs
                                )
                            )
                        }
                        if (inferenceFailures >= MAX_INFERENCE_FAILURES) {
                            mainHandler.post { pauseProtection("Protection paused after repeated Gemma inference failures") }
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    inferenceInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!frame.isRecycled) frame.recycle()
            inferenceInFlight.set(false)
        }
    }

    private fun submitRedactionRefresh(
        frame: Bitmap,
        regions: List<DetectedMediaRegion>,
        settings: dev.screenveil.app.policy.ModerationSettings,
        generation: Long,
        screenBounds: Rect
    ) {
        try {
            redactionExecutor.execute {
                try {
                    if (!destroyed && monitoringRequested && generation == maskGeneration.get()) {
                        val masks = overlay?.prepare(frame, regions, settings, screenBounds).orEmpty()
                        postPreparedMasks(generation, masks, activeMediaRegionCount, regions.size, lastInferenceMs)
                    }
                } catch (_: Throwable) {
                    // Keep the last valid overlay. The periodic screenshot will retry.
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    redactionInFlight.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!frame.isRecycled) frame.recycle()
            redactionInFlight.set(false)
        }
    }

    private fun postPreparedMasks(
        generation: Long,
        masks: List<OverlayController.PreparedMask>,
        detectedCount: Int,
        redactedCount: Int,
        inferenceMs: Long?
    ) {
        mainHandler.post {
            if (!destroyed && monitoringRequested && generation == maskGeneration.get()) {
                val shown = overlay?.showPrepared(masks) == true
                if (!shown) {
                    masks.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    ProtectionStatus.setMessage("Could not display the accessibility redaction overlay")
                    return@post
                }
                ProtectionStatus.update(
                    ProtectionStatus.State(
                        active = true,
                        modelReady = true,
                        message = when {
                            detectedCount == 0 -> "Monitoring · Gemma returned no media regions"
                            redactedCount == 0 -> "Monitoring · detected $detectedCount media region(s); none matched the redaction policy"
                            else -> "Monitoring · redacted $redactedCount of $detectedCount media region(s)"
                        },
                        regionsDetected = detectedCount,
                        regionsRedacted = redactedCount,
                        lastInferenceMs = inferenceMs
                    )
                )
            } else {
                masks.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            }
        }
    }

    private fun clearMasks() {
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        val generation = maskGeneration.incrementAndGet()
        mainHandler.post {
            if (!destroyed && generation == maskGeneration.get()) {
                overlay?.clear()
                if (monitoringRequested) {
                    ProtectionStatus.update(
                        ProtectionStatus.state.value.copy(
                            message = "Screen changed · checking the new application window",
                            regionsDetected = 0,
                            regionsRedacted = 0
                        )
                    )
                }
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdownService("Accessibility service disabled")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdownService("Accessibility service disconnected")
        super.onDestroy()
    }

    private fun shutdownService(message: String) {
        if (destroyed) return
        destroyed = true
        connected = false
        monitoringRequested = false
        modelReady = false
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        maskGeneration.incrementAndGet()
        overlay?.close()
        overlay = null
        if (currentInstance === this) currentInstance = null

        runCatching {
            inferenceExecutor.execute {
                runCatching { detector?.close() }
                detector = null
            }
        }
        inferenceExecutor.shutdown()
        screenshotExecutor.shutdown()
        redactionExecutor.shutdown()
        ProtectionStatus.update(ProtectionStatus.State(active = false, message = message))
    }

    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 750L
        private const val MAX_CAPTURE_INTERVAL_MS = 5_000L
        private const val WINDOW_CHANGE_DEBOUNCE_MS = 350L
        private const val SCROLL_DEBOUNCE_MS = 450L
        private const val CONTENT_CHANGE_DEBOUNCE_MS = 650L
        private const val SCREENSHOT_RATE_LIMIT_RETRY_MS = 700L
        private const val MAX_INVALID_RESPONSES = 4
        private const val MAX_INFERENCE_FAILURES = 4

        @Volatile private var currentInstance: ScreenProtectionAccessibilityService? = null

        fun requestStart(): Boolean {
            val service = currentInstance ?: return false
            service.mainHandler.post { service.startProtection() }
            return true
        }

        fun requestStop(): Boolean {
            val service = currentInstance ?: return false
            service.mainHandler.post { service.pauseProtection() }
            return true
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ScreenProtectionAccessibilityService::class.java)
                .flattenToString()
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
