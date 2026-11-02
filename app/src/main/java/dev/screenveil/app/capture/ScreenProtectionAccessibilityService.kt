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
import dev.screenveil.app.model.ModelProfile
import dev.screenveil.app.model.ModelIds
import dev.screenveil.app.model.ModelFiles
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.model.ScreenModerationDetector
import dev.screenveil.app.model.SpecializedScreenModerationDetector
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
        Thread(runnable, "screenveil-inference").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val redactionExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-redaction").apply { priority = Thread.NORM_PRIORITY }
    }

    private val screenshotInFlight = AtomicBoolean(false)
    private val captureRequestedAfterScreenshot = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val redactionInFlight = AtomicBoolean(false)
    private val captureRequestedWhileBusy = AtomicBoolean(false)
    private val modelInitializationStarted = AtomicBoolean(false)
    private val maskGeneration = AtomicLong(0L)
    private val captureSequence = AtomicLong(0L)
    // Read/written only by main-thread overlay commits.
    private var lastAppliedCaptureSequence = 0L

    @Volatile private var connected = false
    @Volatile private var monitoringRequested = false
    @Volatile private var modelReady = false
    @Volatile private var loadedProfile: ModelProfile? = null
    @Volatile private var activeProfileName: String = "Selected model profile"
    @Volatile private var destroyed = false
    @Volatile private var activeRedactions: List<DetectedMediaRegion> = emptyList()
    @Volatile private var activeMediaRegionCount: Int = 0
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var lastKnownScreenBounds: Rect? = null

    // Engine and Conversation APIs are confined to inferenceExecutor.
    private var detector: ScreenContentDetector? = null
    // WindowManager operations are performed on the main thread. Bitmap preparation is worker-safe.
    private var overlay: OverlayController? = null
    private val preferences by lazy { AppPreferences(this) }

    private var lastScreenshotAt = 0L
    @Volatile private var lastInferenceMs: Long? = null
    // Accessed only on inferenceExecutor, which serializes model initialization and inference.
    private var consecutiveModelFailures = 0

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
    // One reusable retry callback prevents event storms or repeated rate-limit errors from
    // accumulating anonymous delayed captures on the main looper.
    private val retryCapture = Runnable {
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
        if (!monitoringRequested) return

        // Do not scan ScreenVeil's own settings screen or let our own UI generate scan loops.
        if (eventPackage == packageName) {
            clearMasks()
            return
        }
        if (!modelReady) {
            coverMasks("${activeProfileName} is loading or unavailable · application window remains covered")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Keep protection continuously present: the old geometry is not safe to reuse
                // after a window switch, so replace it with an opaque cover until fresh analysis.
                coverMasks("Application changed · covered while checking the new screen")
                scheduleDebouncedCapture(WINDOW_CHANGE_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Scrolling changes the pixel-to-content mapping. An opaque temporary cover is
                // the only fail-closed choice without app-specific scroll/vision tracking.
                coverMasks("Screen moved · covered while refreshing redaction")
                scheduleDebouncedCapture(SCROLL_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Accessibility metadata cannot reliably distinguish text updates from media
                // replacement/reflow. Conservatively invalidate the old geometry on every
                // content-change event and cover until a fresh screen analysis is committed.
                coverMasks("Screen content changed · covered while refreshing redaction")
                scheduleDebouncedCapture(CONTENT_CHANGE_DEBOUNCE_MS)
            }
        }
    }

    override fun onInterrupt() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onInterrupt() }
            return
        }
        if (monitoringRequested && !destroyed) {
            // An accessibility interruption is not the user's request to disable protection.
            // Retain an opaque cover rather than clearing the last masks.
            coverMasks("Accessibility service interrupted · screen covered until inspection resumes")
            if (modelReady) scheduleRetry(RETRY_AFTER_ANALYSIS_FAILURE_MS)
        }
    }

    fun startProtection() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startProtection() }
            return
        }
        if (!connected || destroyed) return
        val requestedProfile = preferences.getModelProfile()
        if (!ModelInstaller.isProfileInstalled(this, requestedProfile)) {
            val missing = requestedProfile.requiredModels
                .filterNot { ModelInstaller.isVerifiedInstalled(this, it) }
                .joinToString { it.displayName }
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = false,
                    modelReady = false,
                    message = "Install and verify all models for ${requestedProfile.title}",
                    error = "Missing model files: $missing"
                )
            )
            return
        }

        activeProfileName = requestedProfile.title
        monitoringRequested = true
        preferences.setProtectionEnabled(true)
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = true,
                modelReady = modelReady,
                message = if (modelReady) "Starting screen inspection…" else "${requestedProfile.title} is loading · external app windows are covered until the first valid analysis"
            )
        )

        if (modelReady && loadedProfile == requestedProfile) {
            beginMonitoring()
        } else {
            // Switching profiles invalidates the previous runtime. Close/recreate it on the single
            // inference executor so Gemma cannot accidentally continue behind a specialist label.
            modelReady = false
            if (currentForegroundPackage != packageName) {
                coverMasks("${requestedProfile.title} is loading · screen covered until protection is ready")
            }
            if (modelInitializationStarted.compareAndSet(false, true)) initializeModel(requestedProfile)
        }
    }

    fun pauseProtection(message: String = "Protection paused") {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { pauseProtection(message) }
            return
        }
        monitoringRequested = false
        captureRequestedWhileBusy.set(false)
        captureRequestedAfterScreenshot.set(false)
        preferences.setProtectionEnabled(false)
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.removeCallbacks(retryCapture)
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        maskGeneration.incrementAndGet()
        overlay?.clear()
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                modelReady = modelReady,
                opaqueCoverActive = false,
                message = message
            )
        )
    }

    private fun initializeModel(profile: ModelProfile) {
        try {
            inferenceExecutor.execute {
                try {
                    runCatching { detector?.close() }
                    detector = null
                    loadedProfile = null
                    val verifiedFiles = LinkedHashMap<String, java.io.File>()
                    profile.requiredModels.forEach { model ->
                        check(ModelInstaller.verifyInstalledBlocking(this, model)) {
                            "${model.displayName} is missing or failed SHA-256 verification. Reinstall it in Settings."
                        }
                        verifiedFiles[model.id] = ModelInstaller.modelFile(this, model)
                    }
                    val files = ModelFiles(verifiedFiles)
                    val created: ScreenContentDetector = when (profile) {
                        ModelProfile.GEMMA_VISION -> ScreenModerationDetector(this)
                        ModelProfile.FAST_SPECIALIZED, ModelProfile.BALANCED_SPECIALIZED -> SpecializedScreenModerationDetector(this)
                    }
                    created.initialize(files)
                    if (destroyed) {
                        created.close()
                        return@execute
                    }
                    detector = created
                    loadedProfile = profile
                    consecutiveModelFailures = 0
                    modelReady = true
                    modelInitializationStarted.set(false)
                    mainHandler.post {
                        if (!destroyed && monitoringRequested) {
                            ProtectionStatus.update(
                                ProtectionStatus.State(
                                    active = true,
                                    modelReady = true,
                                    message = "Protection active · ${profile.title} · ${created.activeBackend}"
                                )
                            )
                            beginMonitoring()
                        }
                    }
                } catch (error: Throwable) {
                    runCatching { detector?.close() }
                    detector = null
                    loadedProfile = null
                    modelReady = false
                    modelInitializationStarted.set(false)
                    val detail = error.message ?: error.javaClass.simpleName
                    mainHandler.post {
                        if (!destroyed && monitoringRequested) {
                            // Do not silently disable protection after a model-load failure. Keep
                            // the opaque window cover in place and let the user open ScreenVeil
                            // to retry or explicitly stop protection.
                            coverMasks("${profile.title} could not be loaded · application window remains covered")
                            ProtectionStatus.update(ProtectionStatus.state.value.copy(error = detail, modelReady = false))
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            modelInitializationStarted.set(false)
            ProtectionStatus.setMessage("Could not start the ${profile.title} worker")
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
        if (currentForegroundPackage != packageName) {
            coverMasks("Protection starting · covering the screen until its first analysis")
        } else {
            clearMasks()
        }
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
        if (!monitoringRequested || !modelReady || destroyed || currentForegroundPackage == packageName) return
        if (!screenshotInFlight.compareAndSet(false, true)) {
            // Coalesce events received while Android is still delivering a screenshot callback.
            // The callback schedules exactly one fresh capture when the current request releases.
            captureRequestedAfterScreenshot.set(true)
            return
        }

        // Every result is tied to the screen epoch at capture request time. Window/scroll/content
        // events advance this token; a late inference can never repaint an obsolete rectangle.
        val captureGeneration = maskGeneration.get()
        val now = SystemClock.elapsedRealtime()
        val wait = MIN_CAPTURE_INTERVAL_MS - (now - lastScreenshotAt)
        if (wait > 0L) {
            screenshotInFlight.set(false)
            scheduleRetry(wait)
            return
        }

        val requestSequence = captureSequence.incrementAndGet()
        val target = try {
            findScreenshotTarget()
        } catch (error: Throwable) {
            finishScreenshotRequest(SCREENSHOT_RATE_LIMIT_RETRY_MS)
            if (captureGeneration != maskGeneration.get() || destroyed) return
            failClosedAndRetry(
                "Could not identify the active application window · display covered while retrying",
                error.message ?: error.javaClass.simpleName,
                captureGeneration
            )
            return
        }
        if (target == null) {
            finishScreenshotRequest()
            ProtectionStatus.update(
                ProtectionStatus.state.value.copy(
                    active = monitoringRequested,
                    modelReady = modelReady,
                    message = "Waiting for a capturable application window",
                    regionsRedacted = activeRedactions.size,
                    lastInferenceMs = lastInferenceMs
                )
            )
            return
        }

        lastKnownScreenBounds = Rect(target.screenBounds)
        lastScreenshotAt = now
        try {
            takeScreenshotOfWindow(
                target.windowId,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer: HardwareBuffer = screenshot.hardwareBuffer
                        try {
                            if (!monitoringRequested || destroyed || captureGeneration != maskGeneration.get()) return
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?: error("Android could not wrap the application-window screenshot")
                            val frame = try {
                                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    ?: error("Could not copy application-window screenshot into a CPU bitmap")
                            } finally {
                                hardwareBitmap.recycle()
                            }
                            dispatchFrame(frame, target.screenBounds, captureGeneration, requestSequence, now)
                        } catch (error: Throwable) {
                            if (captureGeneration == maskGeneration.get() && !destroyed) {
                                failClosedAndRetry(
                                    "Screenshot processing failed · app window covered while retrying",
                                    error.message ?: error.javaClass.simpleName,
                                    captureGeneration
                                )
                            }
                        } finally {
                            // Android explicitly requires closing the HardwareBuffer on every path.
                            try {
                                buffer.close()
                            } finally {
                                finishScreenshotRequest()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        finishScreenshotRequest(SCREENSHOT_RATE_LIMIT_RETRY_MS)
                        if (captureGeneration != maskGeneration.get() || destroyed) return
                        val detail = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android is rate-limiting screenshots; retrying shortly"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app protects its window with FLAG_SECURE; Android does not allow capture"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility screenshot access is unavailable; re-enable ScreenVeil in Settings"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The default display is unavailable"
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "The active app window changed before it could be captured"
                            else -> "Android screenshot failed (code $errorCode)"
                        }
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            // Android's rate-limit response does not indicate a changed frame;
                            // keep the last validated overlay and retry after the API's cooldown.
                            ProtectionStatus.update(
                                ProtectionStatus.state.value.copy(
                                    active = monitoringRequested,
                                    modelReady = modelReady,
                                    message = detail,
                                    regionsRedacted = activeRedactions.size,
                                    lastInferenceMs = lastInferenceMs
                                )
                            )
                        } else {
                            failClosedAndRetry(detail, detail, captureGeneration)
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            finishScreenshotRequest(SCREENSHOT_RATE_LIMIT_RETRY_MS)
            if (captureGeneration == maskGeneration.get() && !destroyed) {
                failClosedAndRetry(
                    "Android screenshot request failed · app window covered while retrying",
                    error.message ?: error.javaClass.simpleName,
                    captureGeneration
                )
            }
        }
    }

    private fun finishScreenshotRequest(retryDelayMs: Long? = null) {
        screenshotInFlight.set(false)
        val shouldRetry = captureRequestedAfterScreenshot.getAndSet(false)
        if ((shouldRetry || retryDelayMs != null) && monitoringRequested && !destroyed) {
            scheduleRetry(retryDelayMs ?: 0L)
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        if (!monitoringRequested || !modelReady || destroyed) return
        mainHandler.removeCallbacks(retryCapture)
        mainHandler.postDelayed(retryCapture, delayMs.coerceAtLeast(0L))
    }

    private fun dispatchFrame(
        frame: Bitmap,
        screenBounds: Rect,
        captureGeneration: Long,
        requestSequence: Long,
        captureRequestedAtMs: Long
    ) {
        if (!monitoringRequested || destroyed || captureGeneration != maskGeneration.get()) {
            if (!frame.isRecycled) frame.recycle()
            return
        }

        val settings = preferences.getSettings()
        if (inferenceInFlight.compareAndSet(false, true)) {
            submitInference(frame, settings, screenBounds, captureGeneration, requestSequence, captureRequestedAtMs)
            return
        }

        // At most one extra capture is requested while inference is busy. This is not a frame
        // queue: repeated events cannot build unbounded work or retain screenshots in memory.
        captureRequestedWhileBusy.set(true)
        val existing = activeRedactions
        if (existing.isNotEmpty() && redactionInFlight.compareAndSet(false, true)) {
            submitRedactionRefresh(frame, existing, settings, captureGeneration, requestSequence, screenBounds, captureRequestedAtMs)
        } else if (!frame.isRecycled) {
            frame.recycle()
        }
    }

    private fun submitInference(
        frame: Bitmap,
        settings: dev.screenveil.app.policy.ModerationSettings,
        screenBounds: Rect,
        captureGeneration: Long,
        requestSequence: Long,
        captureRequestedAtMs: Long
    ) {
        try {
            inferenceExecutor.execute {
                try {
                    if (destroyed || !monitoringRequested || captureGeneration != maskGeneration.get()) return@execute
                    val currentDetector = detector ?: error("Selected model profile is not available")
                    val started = SystemClock.elapsedRealtime()
                    val result = currentDetector.inspect(frame)
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (destroyed || !monitoringRequested || captureGeneration != maskGeneration.get()) return@execute

                    if (!result.parseSucceeded) {
                        recordModelFailure(
                            "${activeProfileName} returned an invalid result · app window covered",
                            result.diagnostic,
                            captureGeneration
                        )
                        return@execute
                    }
                    consecutiveModelFailures = 0

                    val redactions = result.regions.filter {
                        ModerationPolicy.shouldRedact(it.decision, settings.redactUncertain)
                    }
                    val prepared = overlay?.prepare(frame, redactions, settings, screenBounds)
                    postPreparedMasks(
                        captureGeneration,
                        requestSequence,
                        prepared,
                        result.regions.size,
                        redactions.size,
                        elapsed,
                        replacementRedactions = redactions,
                        captureRequestedAtMs = captureRequestedAtMs
                    )
                } catch (error: Throwable) {
                    if (!destroyed && monitoringRequested && captureGeneration == maskGeneration.get()) {
                        recordModelFailure(
                            "${activeProfileName} inference failed · app window covered",
                            error.message ?: error.javaClass.simpleName,
                            captureGeneration
                        )
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    inferenceInFlight.set(false)
                    if (captureRequestedWhileBusy.getAndSet(false) && !destroyed && monitoringRequested) {
                        mainHandler.post {
                            if (!destroyed && monitoringRequested) requestScreenshot()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!frame.isRecycled) frame.recycle()
            inferenceInFlight.set(false)
            captureRequestedWhileBusy.set(false)
        }
    }

    private fun submitRedactionRefresh(
        frame: Bitmap,
        regions: List<DetectedMediaRegion>,
        settings: dev.screenveil.app.policy.ModerationSettings,
        generation: Long,
        requestSequence: Long,
        screenBounds: Rect,
        captureRequestedAtMs: Long
    ) {
        try {
            redactionExecutor.execute {
                try {
                    if (!destroyed && monitoringRequested && generation == maskGeneration.get()) {
                        val prepared = overlay?.prepare(frame, regions, settings, screenBounds)
                        postPreparedMasks(
                            generation,
                            requestSequence,
                            prepared,
                            activeMediaRegionCount,
                            regions.size,
                            lastInferenceMs,
                            replacementRedactions = null,
                            captureRequestedAtMs = captureRequestedAtMs
                        )
                    }
                } catch (error: Throwable) {
                    if (!destroyed && monitoringRequested && generation == maskGeneration.get()) {
                        failClosedAndRetry(
                            "Redaction refresh failed · app window covered while retrying",
                            error.message ?: error.javaClass.simpleName,
                            generation
                        )
                    }
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
        requestSequence: Long,
        prepared: OverlayController.PreparedOverlay?,
        detectedCount: Int,
        redactedCount: Int,
        inferenceMs: Long?,
        replacementRedactions: List<DetectedMediaRegion>?,
        captureRequestedAtMs: Long
    ) {
        mainHandler.post {
            if (!destroyed && monitoringRequested && generation == maskGeneration.get() &&
                requestSequence >= lastAppliedCaptureSequence
            ) {
                val shown = overlay?.showPrepared(prepared) == true
                if (!shown) {
                    val bounds = prepared?.screenBounds ?: lastKnownScreenBounds ?: fullDisplayBounds()
                    if (overlay?.showOpaque(bounds) != true) {
                        ProtectionStatus.update(
                            ProtectionStatus.State(
                                active = true,
                                modelReady = modelReady,
                                opaqueCoverActive = false,
                                message = "CRITICAL: redaction overlay could not be displayed",
                                error = "The detailed overlay and opaque fallback both failed"
                            )
                        )
                    } else {
                        ProtectionStatus.update(
                            ProtectionStatus.State(
                                active = true,
                                modelReady = modelReady,
                                opaqueCoverActive = true,
                                message = "Screen covered because the redaction overlay failed",
                                error = "Detailed redaction could not be displayed; opaque fallback is active"
                            )
                        )
                    }
                    return@post
                }
                lastAppliedCaptureSequence = requestSequence
                if (replacementRedactions != null) {
                    activeRedactions = replacementRedactions
                    activeMediaRegionCount = detectedCount
                    lastInferenceMs = inferenceMs
                }
                ProtectionStatus.update(
                    ProtectionStatus.State(
                        active = true,
                        modelReady = true,
                        opaqueCoverActive = false,
                        message = when {
                            detectedCount == 0 -> "Monitoring · ${activeProfileName} returned no media regions"
                            redactedCount == 0 -> "Monitoring · detected $detectedCount media region(s); none matched the redaction policy"
                            else -> "Monitoring · ${activeProfileName}: redacted $redactedCount of $detectedCount media region(s)"
                        },
                        regionsDetected = detectedCount,
                        regionsRedacted = redactedCount,
                        lastInferenceMs = inferenceMs,
                        lastEndToEndMs = (SystemClock.elapsedRealtime() - captureRequestedAtMs).coerceAtLeast(0L)
                    )
                )
            } else {
                prepared?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * Invalidate all old coordinates and synchronously place an opaque cover on the active app
     * window. This deliberately trades temporary visibility/usability for privacy during reflow.
     */
    private fun coverMasks(message: String) {
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        val generation = maskGeneration.incrementAndGet()
        val applyCover = Runnable {
            if (!destroyed && generation == maskGeneration.get()) {
                val bounds = runCatching { findScreenshotTarget()?.screenBounds }
                    .getOrNull() ?: fullDisplayBounds()
                lastKnownScreenBounds = Rect(bounds)
                val covered = overlay?.showOpaque(bounds) == true
                ProtectionStatus.update(
                    ProtectionStatus.State(
                        active = monitoringRequested,
                        modelReady = modelReady,
                        opaqueCoverActive = covered,
                        message = if (covered) message else "Protection could not display its temporary opaque cover",
                        error = if (covered) null else "Accessibility overlay update failed"
                    )
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) applyCover.run() else mainHandler.post(applyCover)
    }

    /**
     * Repeated model failures stop expensive inference attempts but never remove the opaque
     * fallback. A user can explicitly stop and restart protection to retry model initialization.
     * This circuit breaker is confined to inferenceExecutor.
     */
    private fun recordModelFailure(message: String, detail: String?, generation: Long) {
        consecutiveModelFailures++
        if (consecutiveModelFailures < MAX_CONSECUTIVE_MODEL_FAILURES) {
            failClosedAndRetry(message, detail, generation)
            return
        }

        val failedDetector = detector
        detector = null
        loadedProfile = null
        runCatching { failedDetector?.close() }
        modelReady = false
        modelInitializationStarted.set(false)
        mainHandler.post {
            if (destroyed || !monitoringRequested) return@post
            mainHandler.removeCallbacks(periodicCapture)
            mainHandler.removeCallbacks(debouncedCapture)
            mainHandler.removeCallbacks(retryCapture)
            coverMasks("${activeProfileName} failed repeatedly · screen remains covered. Stop and restart protection to retry.")
            val coveredStatus = ProtectionStatus.state.value
            ProtectionStatus.update(
                coveredStatus.copy(
                    active = true,
                    modelReady = false,
                    error = detail ?: coveredStatus.error ?: "${activeProfileName} failed repeatedly",
                    message = if (coveredStatus.opaqueCoverActive) {
                        "Model recovery required · opaque screen cover retained"
                    } else {
                        "CRITICAL: model recovery required · opaque cover could not be displayed"
                    }
                )
            )
        }
    }

    /** Invalid model/capture results never disable monitoring or remove protection. */
    private fun failClosedAndRetry(message: String, detail: String?, generation: Long) {
        mainHandler.post {
            if (destroyed || !monitoringRequested || generation != maskGeneration.get()) return@post
            val bounds = runCatching { findScreenshotTarget()?.screenBounds }.getOrNull()
                ?: lastKnownScreenBounds ?: fullDisplayBounds()
            lastKnownScreenBounds = Rect(bounds)
            val covered = overlay?.showOpaque(bounds) == true
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = true,
                    modelReady = modelReady,
                    opaqueCoverActive = covered,
                    message = if (covered) message else "CRITICAL: redaction overlay unavailable",
                    error = detail ?: if (covered) null else "Opaque fallback overlay failed",
                    regionsDetected = activeMediaRegionCount,
                    regionsRedacted = if (covered) 1 else activeRedactions.size,
                    lastInferenceMs = lastInferenceMs
                )
            )
            if (covered) scheduleRetry(RETRY_AFTER_ANALYSIS_FAILURE_MS)
        }
    }

    private fun fullDisplayBounds(): Rect {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
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
                            opaqueCoverActive = false,
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
        captureRequestedWhileBusy.set(false)
        captureRequestedAfterScreenshot.set(false)
        modelReady = false
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.removeCallbacks(retryCapture)
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
                loadedProfile = null
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
        private const val RETRY_AFTER_ANALYSIS_FAILURE_MS = 900L
        private const val MAX_CONSECUTIVE_MODEL_FAILURES = 3

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
