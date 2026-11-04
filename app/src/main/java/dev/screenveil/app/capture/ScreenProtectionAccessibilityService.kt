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
import dev.screenveil.app.BuildConfig
import dev.screenveil.app.diagnostics.DiagnosticLog
import dev.screenveil.app.diagnostics.DetectionCycleTrace
import dev.screenveil.app.diagnostics.DeviceHealthMonitor
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.HumanBodySegmenter
import dev.screenveil.app.model.TfliteHumanBodySegmenter
import dev.screenveil.app.model.SyntheticHumanBodySegmenter
import dev.screenveil.app.model.ModelInstaller
import dev.screenveil.app.model.ModelProfile
import dev.screenveil.app.model.ModelIds
import dev.screenveil.app.model.ModelFiles
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.model.DiagnosticStageAware
import dev.screenveil.app.model.ScreenModerationDetector
import dev.screenveil.app.model.TemporaryFrameFiles
import dev.screenveil.app.model.SpecializedScreenModerationDetector
import dev.screenveil.app.overlay.OverlayController
import dev.screenveil.app.overlay.MaskGeometry
import dev.screenveil.app.policy.CoveragePolicy
import dev.screenveil.app.policy.ModerationPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

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
    private val screenshotInFlight = AtomicBoolean(false)
    private val captureRequestedAfterScreenshot = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val captureRequestedWhileBusy = AtomicBoolean(false)
    private val consecutiveCaptureFailures = AtomicInteger(0)
    private val modelInitializationStarted = AtomicBoolean(false)
    private val maskGeneration = AtomicLong(0L)
    private val captureSequence = AtomicLong(0L)
    private val screenshotRequestSequence = AtomicLong(0L)
    private val activeScreenshotRequestId = AtomicLong(0L)
    private val inferenceRequestSequence = AtomicLong(0L)
    private val activeInferenceRequestId = AtomicLong(0L)
    // Read/written only by main-thread overlay commits.
    private var lastAppliedCaptureSequence = 0L
    // Main-thread-only watchdog state. Timers are independent of inference and screenshot workers.
    private var highCoverageSinceMs = 0L
    private var lastAppliedCoverageFraction = 0.0
    private var protectionSessionStartedAtMs = 0L
    private var screenshotTimeoutRunnable: Runnable? = null
    private var inferenceTimeoutRunnable: Runnable? = null
    private var sessionTimeoutRunnable: Runnable? = null
    private var mainLooperHeartbeatExpectedAtMs = 0L
    private val mainLooperHeartbeat: Runnable = object : Runnable {
        override fun run() {
            if (!monitoringRequested || destroyed) return
            val now = SystemClock.elapsedRealtime()
            DeviceHealthMonitor.recordMainLooperDelay((now - mainLooperHeartbeatExpectedAtMs).coerceAtLeast(0L))
            mainLooperHeartbeatExpectedAtMs = now + MAIN_LOOPER_HEARTBEAT_INTERVAL_MS
            mainHandler.postDelayed(this, MAIN_LOOPER_HEARTBEAT_INTERVAL_MS)
        }
    }
    private val coverageWatchdog: Runnable = object : Runnable {
        override fun run() {
            val threshold = if (BuildConfig.DEBUG) ScreenVeilTestHooks.coverageWatchdogThresholdOverride
                ?: CoveragePolicy.COVERAGE_WATCHDOG_THRESHOLD else CoveragePolicy.COVERAGE_WATCHDOG_THRESHOLD
            val dwellMs = if (BuildConfig.DEBUG) ScreenVeilTestHooks.coverageWatchdogDwellMsOverride
                ?: CoveragePolicy.COVERAGE_WATCHDOG_DWELL_MS else CoveragePolicy.COVERAGE_WATCHDOG_DWELL_MS
            if (!destroyed && monitoringRequested && lastAppliedCoverageFraction >= threshold) {
                val elapsed = SystemClock.elapsedRealtime() - highCoverageSinceMs
                if (highCoverageSinceMs > 0L && elapsed >= dwellMs) {
                    stopForSafety(
                        "Protection automatically stopped because body redaction stayed above the safety coverage threshold for 3 seconds. The screen is visible.",
                        "Coverage watchdog: ${"%.2f".format(java.util.Locale.US, lastAppliedCoverageFraction)} sustained for ${elapsed}ms"
                    )
                } else if (highCoverageSinceMs > 0L) {
                    mainHandler.postDelayed(this, (dwellMs - elapsed).coerceAtLeast(1L))
                }
            }
        }
    }
    private var lastLoggedOverlaySummary = ""
    private var lastLoggedCaptureNote = ""

    @Volatile private var connected = false
    @Volatile private var monitoringRequested = false
    @Volatile private var modelReady = false
    @Volatile private var loadedProfile: ModelProfile? = null
    @Volatile private var activeProfileName: String = "Selected model profile"
    @Volatile private var destroyed = false
    @Volatile private var activeRedactions: List<DetectedMediaRegion> = emptyList()
    @Volatile private var activeMediaRegionCount: Int = 0
    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var lastScreenshotSkipReason: String = "none"
    @Volatile private var lastScreenshotTarget: String = "none"
    @Volatile private var lastScreenshotFailure: String = "none"
    private val screenshotAttempts = AtomicInteger(0)
    private val screenshotSuccesses = AtomicInteger(0)
    private val screenshotFailures = AtomicInteger(0)
    // Main-thread guard: only the first content-change event in a debounce burst clears masks.
    @Volatile private var contentChangeBurstPending = false
    // Debug instrumentation can isolate an injected event burst from unrelated framework events.
    private var suppressPlatformAccessibilityEventsForTest = false

    // Engine and Conversation APIs are confined to inferenceExecutor.
    private var detector: ScreenContentDetector? = null
    private var humanBodySegmenter: HumanBodySegmenter? = null
    @Volatile private var inferenceWorkerPoisoned = false
    @Volatile private var screenshotWorkerPoisoned = false
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
        contentChangeBurstPending = false
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
        // A timeout shuts down its executor permanently. Process-level poison remains set across
        // Accessibility unbind/rebind because an interrupted JNI/native call may still be alive.
        // Only a full Android process restart can guarantee that such a thread is gone.
        currentInstance = this
        TemporaryFrameFiles.removeAbandoned(this)
        DiagnosticLog.record(this, "SERVICE connected sdk=${Build.VERSION.SDK_INT}")
        overlay = if (BuildConfig.DEBUG) {
            ScreenVeilTestHooks.overlayFactory?.invoke(this)
        } else null
        overlay = overlay ?: OverlayController(
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

        // Always clear any persisted ON request before evaluating runtime capability.
        preferences.setProtectionEnabled(false)
        monitoringRequested = false

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

        // A service bind/rebind must never start screen coverage by itself. Require a fresh
        // Start tap in the app, even if an older release persisted an enabled flag.
        overlay?.clear()
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                opaqueCoverActive = false,
                message = "Accessibility service enabled · protection is OFF. Tap Start protection when ready."
            )
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (BuildConfig.DEBUG && suppressPlatformAccessibilityEventsForTest) return
        handleAccessibilityEvent(event)
    }

    /** Shared production event path; debug tests can feed controlled events through this method. */
    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
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
            ProtectionStatus.setMessage("${activeProfileName} is loading or unavailable · screen remains visible; protection will pause on error")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                contentChangeBurstPending = false
                // Old coordinates belong to the previous application window. Remove only our
                // localized masks; never replace them with a full-window obstruction.
                clearMasks()
                scheduleDebouncedCapture(WINDOW_CHANGE_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                contentChangeBurstPending = false
                // Scrolling changes coordinate mapping. Clear stale localized masks while the
                // debounced screenshot is analyzed; do not block the entire application window.
                clearMasks()
                scheduleDebouncedCapture(SCROLL_DEBOUNCE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Reflow may invalidate old rectangles. Clear masks once per event burst, not on
                // every accessibility notification, then debounce one fresh screenshot.
                if (!contentChangeBurstPending) {
                    contentChangeBurstPending = true
                    clearMasks()
                }
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
            // An interruption is a technical failure, not permission to lock the user's screen.
            stopForSafety("Protection paused because Android interrupted the Accessibility service", "Accessibility interruption")
        }
    }

    fun startProtection() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startProtection() }
            return
        }
        if (!connected || destroyed) return
        // Start is idempotent while a session is active. A repeated tap or duplicate intent must
        // never re-arm and extend the five-minute maximum session deadline.
        if (monitoringRequested) return
        if (!ProtectionNotifier.canShowStopNotification(this)) {
            stopForSafety(
                "Protection was not started because the emergency Stop notification is unavailable. Enable ScreenVeil notifications and try again.",
                "POST_NOTIFICATIONS permission or the active notification channel is disabled"
            )
            return
        }
        if (processWorkerPoisoned.get() || inferenceWorkerPoisoned || screenshotWorkerPoisoned) {
            stopForSafety(
                "Protection cannot restart because a capture or inference worker exceeded its hard deadline. Force-stop ScreenVeil from Android App Info, then reopen it and re-enable Accessibility before trying again.",
                "Capture/inference worker is poisoned after timeout; a process restart is required"
            )
            return
        }
        val requestedProfile = preferences.getModelProfile()
        val injectedDetectorFactory = if (BuildConfig.DEBUG) ScreenVeilTestHooks.detectorFactory else null
        if (injectedDetectorFactory == null && !ModelInstaller.hasProfileMarkers(this, requestedProfile)) {
            modelReady = false
            val missing = requestedProfile.requiredModels
                .filterNot { ModelInstaller.hasVerifiedMarker(this, it) }
                .joinToString { it.displayName }
            stopForSafety("Protection was not started because model files are missing. The screen is visible.", "Missing model files: $missing")
            return
        }

        activeProfileName = requestedProfile.title
        monitoringRequested = true
        preferences.setProtectionEnabled(true)
        consecutiveCaptureFailures.set(0)
        highCoverageSinceMs = 0L
        lastAppliedCoverageFraction = 0.0
        armProtectionSessionTimeout()
        ProtectionNotifier.clearPaused(this)
        if (!ProtectionNotifier.showActive(this)) {
            stopForSafety(
                "Protection was stopped because Android did not accept the emergency Stop notification. The screen is visible.",
                "NotificationManager.notify failed for the active Stop notification"
            )
            return
        }
        DiagnosticLog.record(this, "START profile=${requestedProfile.name}")
        // Health monitoring is diagnostic-only; an unavailable metric must never block protection.
        runCatching { DeviceHealthMonitor.start(this) }
        mainHandler.removeCallbacks(mainLooperHeartbeat)
        mainLooperHeartbeatExpectedAtMs = SystemClock.elapsedRealtime() + MAIN_LOOPER_HEARTBEAT_INTERVAL_MS
        mainHandler.postDelayed(mainLooperHeartbeat, MAIN_LOOPER_HEARTBEAT_INTERVAL_MS)
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = true,
                modelReady = modelReady,
                opaqueCoverActive = false,
                message = if (modelReady) "Starting screen inspection…" else "${requestedProfile.title} is loading · screen remains visible until localized analysis is ready"
            )
        )

        if (modelReady && loadedProfile == requestedProfile) {
            beginMonitoring()
        } else {
            // Switching profiles invalidates the previous runtime. Close/recreate it on the single
            // inference executor so Gemma cannot accidentally continue behind a specialist label.
            modelReady = false
            clearMasks()
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
        mainHandler.removeCallbacks(mainLooperHeartbeat)
        // Diagnostics must not delay or prevent the safety-stop path.
        runCatching { DeviceHealthMonitor.stop() }
        contentChangeBurstPending = false
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        resetSafetyTimers()
        maskGeneration.incrementAndGet()
        overlay?.clear()
        ProtectionNotifier.clearActive(this)
        DiagnosticLog.record(this, "PAUSE $message")
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
                var candidateForCleanup: ScreenContentDetector? = null
                var segmenterForCleanup: HumanBodySegmenter? = null
                try {
                    runCatching { detector?.close() }
                    runCatching { humanBodySegmenter?.close() }
                    detector = null
                    humanBodySegmenter = null
                    loadedProfile = null
                    val injectedFactory = if (BuildConfig.DEBUG) ScreenVeilTestHooks.detectorFactory else null
                    val verifiedFiles = LinkedHashMap<String, java.io.File>()
                    if (injectedFactory == null) {
                        check(profile.selectable && profile.requiredModels.isNotEmpty()) {
                            "${profile.title} is not available yet. Choose Balanced, Fast or Gemma instead."
                        }
                        profile.requiredModels.forEach { model ->
                            check(ModelInstaller.verifyInstalledBlocking(this, model)) {
                                "${model.displayName} is missing or failed SHA-256 verification. Reinstall it in Settings."
                            }
                            verifiedFiles[model.id] = ModelInstaller.modelFile(this, model)
                        }
                    }
                    val files = ModelFiles(verifiedFiles)
                    val created: ScreenContentDetector = injectedFactory?.invoke(this, profile) ?: when (profile) {
                        ModelProfile.GEMMA_VISION -> ScreenModerationDetector(this)
                        ModelProfile.FAST_SPECIALIZED, ModelProfile.BALANCED_SPECIALIZED -> SpecializedScreenModerationDetector()
                        ModelProfile.SWIMWEAR_AWARE -> error("Swimwear-aware model has not passed Android artifact approval")
                    }
                    candidateForCleanup = created
                    val modelLoadStartedAt = SystemClock.elapsedRealtime()
                    created.initialize(files)
                    DiagnosticLog.record(
                        this,
                        "MODEL_INITIALIZED profile=${profile.name} backend=${created.activeBackend} " +
                            "loadMs=${SystemClock.elapsedRealtime() - modelLoadStartedAt} " +
                            "artifacts=${profile.requiredModels.joinToString(",") { it.id }}"
                    )
                    val segmenterLoadStartedAt = SystemClock.elapsedRealtime()
                    val createdSegmenter: HumanBodySegmenter = if (injectedFactory != null) {
                        (if (BuildConfig.DEBUG) ScreenVeilTestHooks.bodySegmenterFactory?.invoke(this) else null)
                            ?: SyntheticHumanBodySegmenter()
                    } else {
                        TfliteHumanBodySegmenter()
                    }
                    segmenterForCleanup = createdSegmenter
                    createdSegmenter.initialize(this@ScreenProtectionAccessibilityService, files)
                    DiagnosticLog.record(
                        this,
                        "SEGMENTER_INITIALIZED type=${createdSegmenter.javaClass.simpleName} " +
                            "loadMs=${SystemClock.elapsedRealtime() - segmenterLoadStartedAt}"
                    )
                    if (destroyed) {
                        created.close()
                        createdSegmenter.close()
                        candidateForCleanup = null
                        segmenterForCleanup = null
                        return@execute
                    }
                    detector = created
                    humanBodySegmenter = createdSegmenter
                    candidateForCleanup = null
                    segmenterForCleanup = null
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
                                    message = "Protection active · ${profile.title} · ${created.activeBackend} · body-mask redaction"
                                )
                            )
                            beginMonitoring()
                        }
                    }
                } catch (error: Throwable) {
                    runCatching { candidateForCleanup?.close() }
                    runCatching { segmenterForCleanup?.close() }
                    runCatching { detector?.close() }
                    runCatching { humanBodySegmenter?.close() }
                    detector = null
                    humanBodySegmenter = null
                    loadedProfile = null
                    modelReady = false
                    modelInitializationStarted.set(false)
                    val detail = describeModelInitializationFailure(error)
                    DiagnosticLog.record(
                        this,
                        "MODEL_INITIALIZATION_FAILED profile=${profile.name} " +
                            "type=${error.javaClass.simpleName} causeChain=$detail"
                    )
                    mainHandler.post {
                        if (!destroyed && monitoringRequested) {
                            stopForSafety("Protection paused because ${profile.title} could not be loaded. The screen is visible.", detail)
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            modelInitializationStarted.set(false)
            stopForSafety("Protection paused because the ${profile.title} worker could not start. The screen is visible.", "Inference worker rejected model initialization")
        }
    }

    /** Keeps native/model initialization failures actionable without logging screen or app content. */
    private fun describeModelInitializationFailure(error: Throwable): String {
        val parts = ArrayList<String>(5)
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = error
        while (current != null && parts.size < 5 && seen.add(current)) {
            val message = (current.message ?: "").replace(Regex("/data/(?:user/0|data)/[^\\s:]+"), "<app-private-path>")
                .replace('\n', ' ').replace('\r', ' ').take(180)
            parts += if (message.isBlank()) current.javaClass.simpleName
                else "${current.javaClass.simpleName}: $message"
            current = current.cause
        }
        return parts.joinToString(" <- ").ifBlank { error.javaClass.simpleName }
    }

    private fun armProtectionSessionTimeout() {
        mainHandler.removeCallbacks(sessionTimeoutRunnable ?: Runnable {})
        val startedAt = SystemClock.elapsedRealtime()
        protectionSessionStartedAtMs = startedAt
        val timeout = Runnable {
            if (monitoringRequested && !destroyed && protectionSessionStartedAtMs == startedAt) {
                stopForSafety(
                    "The 5-minute test protection session has expired. Protection is OFF and the redaction overlay has been removed.",
                    "Maximum protection session ${CoveragePolicy.MAX_PROTECTION_SESSION_MS}ms reached"
                )
            }
        }
        sessionTimeoutRunnable = timeout
        val durationMs = if (BuildConfig.DEBUG) ScreenVeilTestHooks.sessionTimeoutMsOverride
            ?: CoveragePolicy.MAX_PROTECTION_SESSION_MS else CoveragePolicy.MAX_PROTECTION_SESSION_MS
        mainHandler.postDelayed(timeout, durationMs)
        DiagnosticLog.record(this, "SESSION_WATCHDOG armed maxMs=$durationMs")
    }

    private fun updateCoverageWatchdog(coverage: Double) {
        lastAppliedCoverageFraction = coverage
        val threshold = if (BuildConfig.DEBUG) ScreenVeilTestHooks.coverageWatchdogThresholdOverride
            ?: CoveragePolicy.COVERAGE_WATCHDOG_THRESHOLD else CoveragePolicy.COVERAGE_WATCHDOG_THRESHOLD
        val dwellMs = if (BuildConfig.DEBUG) ScreenVeilTestHooks.coverageWatchdogDwellMsOverride
            ?: CoveragePolicy.COVERAGE_WATCHDOG_DWELL_MS else CoveragePolicy.COVERAGE_WATCHDOG_DWELL_MS
        if (coverage >= threshold) {
            if (highCoverageSinceMs == 0L) highCoverageSinceMs = SystemClock.elapsedRealtime()
            if (!mainHandler.hasCallbacks(coverageWatchdog)) {
                val elapsed = SystemClock.elapsedRealtime() - highCoverageSinceMs
                val remaining = (dwellMs - elapsed).coerceAtLeast(1L)
                mainHandler.postDelayed(coverageWatchdog, remaining)
                DiagnosticLog.record(this, "COVERAGE_WATCHDOG armed coverage=${"%.2f".format(java.util.Locale.US, coverage)} dwellMs=${remaining}")
            }
        } else {
            highCoverageSinceMs = 0L
            mainHandler.removeCallbacks(coverageWatchdog)
        }
    }

    private fun resetSafetyTimers() {
        mainHandler.removeCallbacks(coverageWatchdog)
        sessionTimeoutRunnable?.let(mainHandler::removeCallbacks)
        sessionTimeoutRunnable = null
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
        inferenceTimeoutRunnable?.let(mainHandler::removeCallbacks)
        inferenceTimeoutRunnable = null
        activeScreenshotRequestId.set(0L)
        activeInferenceRequestId.set(0L)
        screenshotInFlight.set(false)
        highCoverageSinceMs = 0L
        lastAppliedCoverageFraction = 0.0
        protectionSessionStartedAtMs = 0L
    }

    private fun armScreenshotWatchdog(requestId: Long, generation: Long, trace: DetectionCycleTrace?) {
        val timeout = Runnable {
            // A window/scroll event may advance maskGeneration while the capture worker is still
            // blocked. That invalidates its pixels, not its liveness deadline. A still-active
            // request must time out regardless of the current screen generation.
            if (activeScreenshotRequestId.compareAndSet(requestId, 0L) &&
                screenshotInFlight.compareAndSet(true, false) && monitoringRequested && !destroyed
            ) {
                captureRequestedAfterScreenshot.set(false)
                screenshotWorkerPoisoned = true
                processWorkerPoisoned.set(true)
                screenshotExecutor.shutdownNow()
                finishDetectionTrace(trace, "STOPPED: screenshot watchdog expired")
                stopForSafety(
                    "Protection paused because Android did not complete the screenshot request within 5 seconds. The screen is visible.",
                    "Screenshot watchdog expired requestId=$requestId requestGeneration=$generation currentGeneration=${maskGeneration.get()}; app process restart required before retry"
                )
            }
        }
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = timeout
        val durationMs = if (BuildConfig.DEBUG) ScreenVeilTestHooks.screenshotTimeoutMsOverride
            ?: CoveragePolicy.SCREENSHOT_TIMEOUT_MS else CoveragePolicy.SCREENSHOT_TIMEOUT_MS
        mainHandler.postDelayed(timeout, durationMs)
    }

    private fun armInferenceWatchdog(requestId: Long, generation: Long, trace: DetectionCycleTrace?) {
        val timeout = Runnable {
            // A screen-change event invalidates the old frame but cannot make a hung JNI/native
            // inference call healthy. Enforce the deadline by request identity, not mask generation.
            if (activeInferenceRequestId.compareAndSet(requestId, 0L) && inferenceInFlight.get() &&
                monitoringRequested && !destroyed
            ) {
                // A JNI/native inference call may not respond to interruption. Do not attempt to
                // reuse this single-thread executor or leave old work eligible to repaint the UI.
                inferenceWorkerPoisoned = true
                processWorkerPoisoned.set(true)
                modelReady = false
                loadedProfile = null
                inferenceExecutor.shutdownNow()
                finishDetectionTrace(trace, "STOPPED: inference watchdog expired")
                stopForSafety(
                    "Protection paused because model inference exceeded its 8-second safety deadline. The screen is visible.",
                    "Inference watchdog expired requestId=$requestId requestGeneration=$generation currentGeneration=${maskGeneration.get()}; app process restart required before retry"
                )
            }
        }
        inferenceTimeoutRunnable?.let(mainHandler::removeCallbacks)
        inferenceTimeoutRunnable = timeout
        val durationMs = if (BuildConfig.DEBUG) ScreenVeilTestHooks.inferenceTimeoutMsOverride
            ?: CoveragePolicy.INFERENCE_TIMEOUT_MS else CoveragePolicy.INFERENCE_TIMEOUT_MS
        mainHandler.postDelayed(timeout, durationMs)
    }

    private fun finishInferenceWatchdog(requestId: Long) {
        mainHandler.post {
            if (activeInferenceRequestId.compareAndSet(requestId, 0L)) {
                inferenceTimeoutRunnable?.let(mainHandler::removeCallbacks)
                inferenceTimeoutRunnable = null
            }
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
        // No opaque startup cover: the first valid analysis will add only localized masks.
        clearMasks()
        requestScreenshot()
        mainHandler.postDelayed(periodicCapture, preferences.getSettings().frameIntervalMs)
    }

    private fun scheduleDebouncedCapture(delayMs: Long) {
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.postDelayed(debouncedCapture, delayMs)
    }

    private data class ScreenshotTarget(val windowId: Int, val screenBounds: Rect)

    /** `systemPanelOpen` is true while a large, focused system window (shade, lock screen) is showing. */
    private data class WindowScan(val target: ScreenshotTarget?, val systemPanelOpen: Boolean)

    /**
     * Select the active application window and capture that window alone. Android documents
     * takeScreenshotOfWindow() specifically for capturing content underneath accessibility
     * overlays, avoiding feedback from ScreenVeil's own redaction windows.
     */
    private fun findScreenshotTarget(): WindowScan {
        val availableWindows = windows
        var selected: AccessibilityWindowInfo? = null
        var selectedBounds: Rect? = null
        var systemPanelOpen = false
        val displayBounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val displayArea = displayBounds.width().toLong() * displayBounds.height()
        try {
            for (window in availableWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && (window.isActive || window.isFocused)) {
                    // The notification shade / Quick Settings / lock screen must never sit under a
                    // stale mask: it is where the Stop controls live.
                    val systemBounds = Rect()
                    window.getBoundsInScreen(systemBounds)
                    if (displayArea > 0 && systemBounds.width().toLong() * systemBounds.height() * 100 >= displayArea * 40) {
                        systemPanelOpen = true
                    }
                    continue
                }
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
            val winner = selected ?: return WindowScan(null, systemPanelOpen)
            return WindowScan(ScreenshotTarget(winner.id, Rect(checkNotNull(selectedBounds))), systemPanelOpen)
        } finally {
            // AccessibilityWindowInfo objects are managed by the framework. Do not retain them
            // beyond this method; only a primitive ID and a copied Rect leave this scope.
            availableWindows.forEach { runCatching { it.recycle() } }
        }
    }

    private fun requestScreenshot() {
        if (!monitoringRequested || !modelReady || destroyed) {
            lastScreenshotSkipReason = "monitoring=$monitoringRequested modelReady=$modelReady destroyed=$destroyed"
            return
        }
        if (currentForegroundPackage == packageName) {
            lastScreenshotSkipReason = "ScreenVeil itself is the foreground package"
            return
        }
        lastScreenshotSkipReason = "none"
        screenshotAttempts.incrementAndGet()
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

        val screenshotId = screenshotRequestSequence.incrementAndGet()
        val trace = if (DiagnosticLog.consumeNextDetectionCycleTrace(this)) {
            DetectionCycleTrace(screenshotId, activeProfileName).also {
                it.note("Trace mode: one screenshot cycle; profile is already loaded")
                it.note("Android API=${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} ${Build.MODEL}")
            }
        } else null
        activeScreenshotRequestId.set(screenshotId)
        armScreenshotWatchdog(screenshotId, captureGeneration, trace)
        val requestSequence = captureSequence.incrementAndGet()
        val targetScanStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
        val scan = try {
            findScreenshotTarget().also {
                trace?.stage(
                    "Active window selection",
                    SystemClock.elapsedRealtime() - targetScanStartedAt,
                    "activeAppWindow=${it.target != null}; systemPanel=${it.systemPanelOpen}"
                )
            }
        } catch (error: Throwable) {
            lastScreenshotTarget = "window enumeration failed: ${error.message ?: error.javaClass.simpleName}"
            trace?.note("Window selection failed: ${error.javaClass.simpleName}")
            finishDetectionTrace(trace, "FAILED: active window selection")
            finishScreenshotRequest(screenshotId, SCREENSHOT_RATE_LIMIT_RETRY_MS)
            if (captureGeneration != maskGeneration.get() || destroyed) return
            handleCaptureFailure(
                "Could not identify the active application window · retrying without blocking the screen",
                error.message ?: error.javaClass.simpleName,
                captureGeneration
            )
            return
        }
        if (scan.systemPanelOpen) {
            trace?.note("Capture skipped because a large system panel is active; masks are cleared to preserve Stop access")
            finishDetectionTrace(trace, "SKIPPED: system panel open")
            lastScreenshotTarget = "system panel is active; masks paused"
            noteCapture("SYSTEM_PANEL masks cleared")
            finishScreenshotRequest(screenshotId)
            if (activeRedactions.isNotEmpty()) clearMasks()
            return
        }
        val target = scan.target
        if (target == null) {
            trace?.note("No capturable active application window was available")
            finishDetectionTrace(trace, "SKIPPED: no active application window")
            lastScreenshotTarget = "no active application window; foregroundPackage=$currentForegroundPackage"
            noteCapture("NO_ACTIVE_APP_WINDOW")
            finishScreenshotRequest(screenshotId)
            // Nothing to protect and nothing to anchor a mask to: never leave a stale overlay up.
            if (activeRedactions.isNotEmpty()) clearMasks()
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

        lastScreenshotAt = now
        lastScreenshotTarget = "windowId=${target.windowId}, bounds=${target.screenBounds.flattenToString()}, foregroundPackage=$currentForegroundPackage"
        trace?.note(
            "Capture target windowId=${target.windowId}; screenBounds=${target.screenBounds.flattenToString()}; " +
                "windowSize=${target.screenBounds.width()}x${target.screenBounds.height()}; " +
                "displayRotation=${runCatching { getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation }.getOrDefault(-1)}"
        )
        val screenshotStartedAtMs = if (trace != null) SystemClock.elapsedRealtime() else 0L
        val injectedFrameProvider = if (BuildConfig.DEBUG) ScreenVeilTestHooks.frameProvider else null
        if (injectedFrameProvider != null) {
            try {
                screenshotExecutor.execute {
                    try {
                        val injectedStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                        val frame = injectedFrameProvider(Rect(target.screenBounds))
                        trace?.stage("Screenshot provider", SystemClock.elapsedRealtime() - injectedStartedAt, "${frame.width}x${frame.height}")
                        mainHandler.post {
                            try {
                                if (activeScreenshotRequestId.get() == screenshotId && monitoringRequested && !destroyed && captureGeneration == maskGeneration.get()) {
                                    consecutiveCaptureFailures.set(0)
                                    screenshotSuccesses.incrementAndGet()
                                    trace?.note("Screenshot pixels copied to CPU bitmap=${frame.width}x${frame.height}")
                                    dispatchFrame(frame, target.screenBounds, captureGeneration, requestSequence, now, trace)
                                } else {
                                    if (!frame.isRecycled) frame.recycle()
                                    finishDetectionTrace(trace, "DISCARDED: injected screenshot became stale before inference")
                                }
                            } finally {
                                finishScreenshotRequest(screenshotId)
                            }
                        }
                    } catch (error: Throwable) {
                        mainHandler.post {
                            trace?.stage(
                                "Screenshot provider",
                                SystemClock.elapsedRealtime() - screenshotStartedAtMs,
                                "FAILED ${error.javaClass.simpleName}"
                            )
                            trace?.note("Injected screenshot provider failed: ${error.javaClass.simpleName}")
                            finishDetectionTrace(trace, "FAILED: screenshot provider")
                            finishScreenshotRequest(screenshotId, SCREENSHOT_RATE_LIMIT_RETRY_MS)
                            if (captureGeneration == maskGeneration.get() && !destroyed) {
                                screenshotFailures.incrementAndGet()
                                lastScreenshotFailure = "injected frame-provider failure: ${error.message ?: error.javaClass.simpleName}"
                                handleCaptureFailure(
                                    "Injected screenshot provider failed · retrying without blocking the screen",
                                    error.message ?: error.javaClass.simpleName,
                                    captureGeneration
                                )
                            }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                finishDetectionTrace(trace, "FAILED: screenshot executor rejected capture")
                finishScreenshotRequest(screenshotId)
                handleCaptureFailure("Screenshot worker is unavailable", "Screenshot executor rejected capture", captureGeneration)
            }
            return
        }
        try {
            takeScreenshotOfWindow(
                target.windowId,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer: HardwareBuffer = screenshot.hardwareBuffer
                        try {
                            if (activeScreenshotRequestId.get() != screenshotId || !monitoringRequested || destroyed || captureGeneration != maskGeneration.get()) {
                                finishDetectionTrace(trace, "DISCARDED: Android screenshot callback became stale")
                                return
                            }
                            trace?.stage(
                                "Android screenshot callback",
                                SystemClock.elapsedRealtime() - screenshotStartedAtMs,
                                "hardwareBuffer=${buffer.width}x${buffer.height}"
                            )
                            val bitmapCopyStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                            val frame = try {
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: error("Android could not wrap the application-window screenshot")
                                try {
                                    hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        ?: error("Could not copy application-window screenshot into a CPU bitmap")
                                } finally {
                                    hardwareBitmap.recycle()
                                }
                            } catch (copyError: Throwable) {
                                trace?.stage(
                                    "HardwareBuffer to CPU bitmap",
                                    SystemClock.elapsedRealtime() - bitmapCopyStartedAt,
                                    "FAILED ${copyError.javaClass.simpleName}"
                                )
                                throw copyError
                            }
                            trace?.stage("HardwareBuffer to CPU bitmap", SystemClock.elapsedRealtime() - bitmapCopyStartedAt, "${frame.width}x${frame.height}")
                            trace?.note("Frame bounds=${target.screenBounds.flattenToString()}; screenshotSize=${frame.width}x${frame.height}")
                            consecutiveCaptureFailures.set(0)
                            screenshotSuccesses.incrementAndGet()
                            dispatchFrame(frame, target.screenBounds, captureGeneration, requestSequence, now, trace)
                        } catch (error: Throwable) {
                            trace?.note("Screenshot callback processing failed: ${error.javaClass.simpleName}")
                            finishDetectionTrace(trace, "FAILED: screenshot callback processing")
                            if (captureGeneration == maskGeneration.get() && !destroyed) {
                                handleCaptureFailure(
                                    "Screenshot processing failed · retrying without blocking the screen",
                                    error.message ?: error.javaClass.simpleName,
                                    captureGeneration
                                )
                            }
                        } finally {
                            // Android explicitly requires closing the HardwareBuffer on every path.
                            try {
                                buffer.close()
                            } finally {
                                finishScreenshotRequest(screenshotId)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (activeScreenshotRequestId.get() != screenshotId || captureGeneration != maskGeneration.get() || destroyed) {
                            finishDetectionTrace(trace, "DISCARDED: Android screenshot failure callback became stale")
                            finishScreenshotRequest(screenshotId)
                            return
                        }
                        screenshotFailures.incrementAndGet()
                        val detail = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Android is rate-limiting screenshots; retrying shortly"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This app protects its window with FLAG_SECURE; Android does not allow capture"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility screenshot access is unavailable; re-enable ScreenVeil in Settings"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The default display is unavailable"
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "The active app window changed before it could be captured"
                            else -> "Android screenshot failed (code $errorCode)"
                        }
                        lastScreenshotFailure = "callback errorCode=$errorCode: $detail"
                        trace?.note("Screenshot callback failed with Android errorCode=$errorCode")
                        finishDetectionTrace(trace, "FAILED: screenshot callback errorCode=$errorCode")
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            // FLAG_SECURE is an explicit platform protection, not a transient
                            // capture error. Discard any queued capture before releasing the
                            // in-flight latch; otherwise a pending event could launch another
                            // screenshot in the interval before the safety stop reaches main.
                            captureRequestedAfterScreenshot.set(false)
                            finishScreenshotRequest(screenshotId)
                            stopForSafety(
                                "Protection paused because this app protects its window with FLAG_SECURE. The screen is visible.",
                                detail
                            )
                            return
                        }
                        finishScreenshotRequest(screenshotId, SCREENSHOT_RATE_LIMIT_RETRY_MS)
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
                            handleCaptureFailure(detail, detail, captureGeneration)
                        }
                    }
                }
            )
        } catch (error: Throwable) {
            screenshotFailures.incrementAndGet()
            lastScreenshotFailure = "request threw ${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            trace?.note("Screenshot request threw ${error.javaClass.simpleName}")
            finishDetectionTrace(trace, "FAILED: screenshot request threw")
            finishScreenshotRequest(screenshotId, SCREENSHOT_RATE_LIMIT_RETRY_MS)
            if (captureGeneration == maskGeneration.get() && !destroyed) {
                handleCaptureFailure(
                    "Android screenshot request failed · retrying without blocking the screen",
                    error.message ?: error.javaClass.simpleName,
                    captureGeneration
                )
            }
        }
    }

    private fun finishScreenshotRequest(requestId: Long, retryDelayMs: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishScreenshotRequest(requestId, retryDelayMs) }
            return
        }
        // A late callback after the screenshot watchdog must not release or reschedule a newer request.
        if (!activeScreenshotRequestId.compareAndSet(requestId, 0L)) return
        screenshotTimeoutRunnable?.let(mainHandler::removeCallbacks)
        screenshotTimeoutRunnable = null
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
        captureRequestedAtMs: Long,
        trace: DetectionCycleTrace? = null
    ) {
        if (!monitoringRequested || destroyed || captureGeneration != maskGeneration.get()) {
            if (!frame.isRecycled) frame.recycle()
            finishDetectionTrace(trace, "DISCARDED: screenshot became stale before inference")
            return
        }

        val settings = preferences.getSettings()
        if (inferenceInFlight.compareAndSet(false, true)) {
            submitInference(frame, settings, screenBounds, captureGeneration, requestSequence, captureRequestedAtMs, trace)
            return
        }

        // At most one extra capture is requested while inference is busy. This is not a frame
        // queue: repeated events cannot build unbounded work or retain screenshots in memory.
        captureRequestedWhileBusy.set(true)
        finishDetectionTrace(trace, "DROPPED: inference worker was busy; frame was not queued or retained")
        // Do not reuse old segmentation masks against a new screenshot. That could redraw stale
        // body pixels in changed coordinates. Drop this frame and let the one pending capture run
        // after the current inference finishes.
        if (!frame.isRecycled) frame.recycle()
    }

    private fun submitInference(
        frame: Bitmap,
        settings: dev.screenveil.app.policy.ModerationSettings,
        screenBounds: Rect,
        captureGeneration: Long,
        requestSequence: Long,
        captureRequestedAtMs: Long,
        trace: DetectionCycleTrace? = null
    ) {
        val inferenceId = inferenceRequestSequence.incrementAndGet()
        val inferenceQueuedAtMs = if (trace != null) SystemClock.elapsedRealtime() else 0L
        activeInferenceRequestId.set(inferenceId)
        mainHandler.post {
            if (activeInferenceRequestId.get() == inferenceId && monitoringRequested && !destroyed) {
                armInferenceWatchdog(inferenceId, captureGeneration, trace)
            }
        }
        try {
            inferenceExecutor.execute {
                try {
                    if (activeInferenceRequestId.get() != inferenceId || destroyed || !monitoringRequested || captureGeneration != maskGeneration.get()) {
                        finishDetectionTrace(trace, "DISCARDED: inference request became stale before execution")
                        return@execute
                    }
                    trace?.stage("Inference executor queue wait", SystemClock.elapsedRealtime() - inferenceQueuedAtMs)
                    val currentDetector = detector ?: error("Selected model profile is not available")
                    trace?.note("Detector backend=${currentDetector.activeBackend}; segmenter=${humanBodySegmenter?.javaClass?.simpleName ?: "unavailable"}")
                    trace?.note("Model artifacts=${loadedProfile?.requiredModels?.joinToString { it.id } ?: "unavailable"}")
                    trace?.note("Display window bounds=${screenBounds.flattenToString()}; screenshot=${frame.width}x${frame.height}")

                    val inferencePipelineStartedAt = SystemClock.elapsedRealtime()
                    val stageAwareDetector = currentDetector as? DiagnosticStageAware
                    stageAwareDetector?.diagnosticStageObserver = trace?.let { cycle ->
                        { stage, durationMs, details -> cycle.stage(stage, durationMs, details) }
                    }
                    val detectorStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                    val result = try {
                        currentDetector.inspect(frame)
                    } catch (detectorError: Throwable) {
                        trace?.stage(
                            "Detector pipeline total",
                            SystemClock.elapsedRealtime() - detectorStartedAt,
                            "FAILED ${detectorError.javaClass.simpleName}"
                        )
                        throw detectorError
                    } finally {
                        stageAwareDetector?.diagnosticStageObserver = null
                    }
                    val detectorElapsedMs = if (trace != null) SystemClock.elapsedRealtime() - detectorStartedAt else 0L
                    trace?.stage(
                        "Detector pipeline total",
                        detectorElapsedMs,
                        "backend=${currentDetector.activeBackend}; parsed=${result.parseSucceeded}; regions=${result.regions.size}"
                    )
                    trace?.note(
                        "Decision counts=${result.regions.groupingBy { it.decision.name }.eachCount()}; " +
                            "safetyViolation=${result.safetyViolation}"
                    )
                    if (destroyed || !monitoringRequested || captureGeneration != maskGeneration.get()) {
                        finishDetectionTrace(trace, "DISCARDED: screen changed during detector execution")
                        return@execute
                    }

                    if (result.safetyViolation) {
                        throw CoveragePolicy.CoverageLimitExceededException(
                            result.diagnostic ?: "Detector output exceeded a hard safety bound"
                        )
                    }
                    if (!result.parseSucceeded) {
                        trace?.note("Detector returned an invalid/unparsed response")
                        finishDetectionTrace(trace, "FAILED: detector response parsing")
                        recordModelFailure(
                            "${activeProfileName} returned an invalid result · retrying without blocking the screen",
                            result.diagnostic,
                            captureGeneration
                        )
                        return@execute
                    }
                    consecutiveModelFailures = 0

                    val policyStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                    val policyEligible = result.regions.filter {
                        ModerationPolicy.shouldRedact(it.decision, settings.redactUncertain, settings.redactAllPeople)
                    }
                    val limited = try {
                        CoveragePolicy.limit(policyEligible)
                    } catch (policyError: Throwable) {
                        trace?.stage(
                            "Redaction decision policy and region bounds",
                            SystemClock.elapsedRealtime() - policyStartedAt,
                            "FAILED ${policyError.javaClass.simpleName}"
                        )
                        throw policyError
                    }
                    val detectorRegions = limited.kept
                    trace?.stage(
                        "Redaction decision policy and region bounds",
                        SystemClock.elapsedRealtime() - policyStartedAt,
                        "detected=${result.regions.size}; policyEligible=${policyEligible.size}; kept=${detectorRegions.size}; dropped=${limited.dropped}"
                    )
                    val redactions = if (detectorRegions.isEmpty()) {
                        trace?.note("Body segmentation skipped because no region matched the active redaction policy")
                        emptyList()
                    } else {
                        val segmenter = humanBodySegmenter
                            ?: throw CoveragePolicy.BodySegmentationSafetyException("Verified body segmentation model is unavailable")
                        detectorRegions.mapIndexed { index, region ->
                            val source = MaskGeometry.map(
                                region.box,
                                frame.width,
                                frame.height,
                                frame.width,
                                frame.height
                            )?.source ?: throw CoveragePolicy.BodySegmentationSafetyException(
                                "Region ${index + 1} has invalid bounds before body segmentation"
                            )
                            // Keep a little context around the detector box for the segmenter, but
                            // the renderer still intersects the output with the original detection
                            // rectangle. Context can improve segmentation; it cannot enlarge redaction.
                            val padX = maxOf(2, (source.width * 0.10f).toInt())
                            val padY = maxOf(2, (source.height * 0.10f).toInt())
                            val segmentationRegion = Rect(
                                (source.left - padX).coerceAtLeast(0),
                                (source.top - padY).coerceAtLeast(0),
                                (source.right + padX).coerceAtMost(frame.width),
                                (source.bottom + padY).coerceAtMost(frame.height)
                            )
                            val detectionRect = Rect(source.left, source.top, source.right, source.bottom)
                            val segmentationStartedAt = SystemClock.elapsedRealtime()
                            val bodyMask = try {
                                segmenter.segment(frame, segmentationRegion)
                            } catch (error: Throwable) {
                                trace?.stage(
                                    "Google Selfie Multiclass body segmentation region ${index + 1}",
                                    SystemClock.elapsedRealtime() - segmentationStartedAt,
                                    "FAILED ${error.javaClass.simpleName}: ${error.message?.take(180) ?: "no detail"}"
                                )
                                throw CoveragePolicy.BodySegmentationSafetyException(
                                    "Body segmentation failed for region ${index + 1}; rectangular fallback is prohibited",
                                    error
                                )
                            }
                            val intersectingPixels = bodyMask.selectedPixelsInside(detectionRect.left, detectionRect.top, detectionRect.right, detectionRect.bottom)
                            trace?.stage(
                                "Google Selfie Multiclass body segmentation region ${index + 1}",
                                SystemClock.elapsedRealtime() - segmentationStartedAt,
                                "mask=${bodyMask.width}x${bodyMask.height}; selectedPixels=${bodyMask.selectedPixels}; " +
                                    "pixelsInsideDetection=$intersectingPixels; frame=${frame.width}x${frame.height}; " +
                                    "modelRegion=${segmentationRegion.flattenToString()}"
                            )
                            if (intersectingPixels <= 0) {
                                throw CoveragePolicy.BodySegmentationSafetyException(
                                    "Segmentation produced no body/clothing pixels inside region ${index + 1} " +
                                        "(selected=${bodyMask.selectedPixels}, intersecting=0); rectangular fallback is prohibited"
                                )
                            }
                            region.copy(bodyMask = bodyMask)
                        }
                    }

                    val inferenceElapsedMs = SystemClock.elapsedRealtime() - inferencePipelineStartedAt
                    val compositionStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                    val prepared = try {
                        overlay?.prepare(
                            frame,
                            redactions,
                            settings,
                            screenBounds,
                            collectDiagnosticGeometry = trace != null
                        )
                    } catch (compositionError: Throwable) {
                        trace?.stage(
                            "Pixel-mask composition and coverage validation",
                            SystemClock.elapsedRealtime() - compositionStartedAt,
                            "FAILED ${compositionError.javaClass.simpleName}"
                        )
                        throw compositionError
                    }
                    trace?.stage(
                        "Pixel-mask composition and coverage validation",
                        SystemClock.elapsedRealtime() - compositionStartedAt,
                        "prepared=${prepared != null}; redactedRegions=${prepared?.redactedRegions ?: 0}"
                    )
                    if (prepared != null) {
                        trace?.note("Overlay bitmap=${prepared.bitmap.width}x${prepared.bitmap.height}; visiblePixels=${prepared.visiblePixelCount}")
                        trace?.note(
                            "Coverage: total=${formatPercent(prepared.coverageFraction)}; " +
                                "perRegion=${prepared.regionCoverageFractions.joinToString { formatPercent(it) }}; " +
                                "hardCaps=${formatPercent(CoveragePolicy.MAX_SINGLE_REGION_MASK_FRACTION)} per region / " +
                                "${formatPercent(CoveragePolicy.MAX_TOTAL_MASK_COVERAGE_FRACTION)} total"
                        )
                        trace?.note(
                            "Mapped region rectangles relative to application window (not pixel-tight alpha bounds)=" +
                                prepared.redactionBounds.joinToString { it.flattenToString() }
                        )
                    } else {
                        trace?.note("No overlay bitmap prepared; the existing overlay will be cleared")
                    }

                    postPreparedMasks(
                        captureGeneration,
                        requestSequence,
                        prepared,
                        result.regions.size,
                        redactions.size,
                        inferenceElapsedMs,
                        replacementRedactions = redactions,
                        captureRequestedAtMs = captureRequestedAtMs,
                        trace = trace
                    )
                } catch (error: Throwable) {
                    trace?.note("Pipeline error=${error.javaClass.simpleName}; ${error.message ?: "no message"}")
                    val outcome = when (error) {
                        is CoveragePolicy.CoverageLimitExceededException -> "STOPPED: coverage safety limit rejected mask"
                        is CoveragePolicy.BodySegmentationSafetyException -> "STOPPED: body segmentation safety failure"
                        else -> "FAILED: inference or processing error"
                    }
                    finishDetectionTrace(trace, outcome)
                    if (!destroyed && monitoringRequested && captureGeneration == maskGeneration.get()) {
                        when (error) {
                            is CoveragePolicy.CoverageLimitExceededException -> mainHandler.post {
                                stopForSafety(
                                    "Protection paused because the redaction output exceeded the maximum safe coverage. The screen is visible.",
                                    error.message
                                )
                            }
                            is CoveragePolicy.BodySegmentationSafetyException -> mainHandler.post {
                                stopForSafety(
                                    "Protection paused because body segmentation could not produce a safe pixel mask. Rectangular fallback is disabled; the screen is visible.",
                                    error.message
                                )
                            }
                            else -> recordModelFailure(
                                "${activeProfileName} inference failed · retrying without blocking the screen",
                                error.message ?: error.javaClass.simpleName,
                                captureGeneration
                            )
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    finishInferenceWatchdog(inferenceId)
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
            finishDetectionTrace(trace, "FAILED: inference executor rejected frame")
            finishInferenceWatchdog(inferenceId)
            inferenceInFlight.set(false)
            captureRequestedWhileBusy.set(false)
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
        captureRequestedAtMs: Long,
        trace: DetectionCycleTrace? = null
    ) {
        mainHandler.post {
            if (!destroyed && monitoringRequested && generation == maskGeneration.get() &&
                requestSequence >= lastAppliedCaptureSequence
            ) {
                val renderedRegionCount = prepared?.redactedRegions ?: 0
                if (redactedCount > 0 && renderedRegionCount != redactedCount) {
                    trace?.note("Overlay preparation mismatch: requestedRedactions=$redactedCount rendered=$renderedRegionCount")
                    finishDetectionTrace(trace, "STOPPED: redaction region mapping mismatch")
                    stopForSafety(
                        "Protection paused because one or more redaction boxes could not be mapped. The screen is visible.",
                        "Model requested $redactedCount redactions, but only $renderedRegionCount valid local boxes could be rendered"
                    )
                    return@post
                }
                val overlayStartedAt = if (trace != null) SystemClock.elapsedRealtime() else 0L
                val shown = overlay?.showPrepared(prepared) == true
                trace?.stage(
                    "WindowManager overlay add/update",
                    SystemClock.elapsedRealtime() - overlayStartedAt,
                    "accepted=$shown; redactedRegions=$renderedRegionCount"
                )
                if (!shown) {
                    trace?.note("Overlay submission failed; safety stop clears the overlay")
                    finishDetectionTrace(trace, "STOPPED: overlay submission failed")
                    // A renderer can fail after assigning this bitmap to its ImageView (for
                    // example, if updateViewLayout throws). OverlayController detaches any such
                    // view. Do not recycle here: HWUI may still have a submitted frame referring
                    // to the bitmap. If it was never attached, normal GC safely reclaims it.
                    // A failed renderer must stop protection, never replace the app with a
                    // full-window grey cover. The user's device remains operable.
                    stopForSafety("Protection paused because the redaction overlay could not be updated. The screen is visible.", "Overlay rendering failed")
                    return@post
                }
                val coverage = prepared?.coverageFraction ?: 0.0
                trace?.note(
                    "Display bounds=${screenBoundsForTrace(prepared)}; overlay uses one non-touchable transparent window"
                )
                updateCoverageWatchdog(coverage)
                val overlaySummary = "OVERLAY detected=$detectedCount redacted=${prepared?.redactedRegions ?: 0} " +
                    "coverage=${"%.2f".format(java.util.Locale.US, coverage)} profile=$activeProfileName"
                if (overlaySummary != lastLoggedOverlaySummary) {
                    lastLoggedOverlaySummary = overlaySummary
                    DiagnosticLog.record(this, overlaySummary)
                }
                lastAppliedCaptureSequence = requestSequence
                if (replacementRedactions != null) {
                    activeRedactions = replacementRedactions
                    activeMediaRegionCount = detectedCount
                    lastInferenceMs = inferenceMs
                }
                val endToEndMs = (SystemClock.elapsedRealtime() - captureRequestedAtMs).coerceAtLeast(0L)
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
                        regionsRedacted = prepared?.redactedRegions ?: 0,
                        lastInferenceMs = inferenceMs,
                        lastEndToEndMs = endToEndMs
                    )
                )
                trace?.stage("Capture to overlay submission end-to-end", endToEndMs, "result=${if (redactedCount > 0) "REDACTION_APPLIED" else "NO_REDACTION"}")
                finishDetectionTrace(
                    trace,
                    if (redactedCount > 0) "REDACTION_APPLIED" else "NO_REDACTION: no region matched the current policy"
                )
            } else {
                // This result is stale and was never passed to OverlayController, so its bitmap
                // is still an unshared worker result and can be explicitly released here.
                prepared?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                finishDetectionTrace(trace, "DISCARDED: result became stale before overlay commit")
            }
        }
    }

    private fun finishDetectionTrace(trace: DetectionCycleTrace?, outcome: String) {
        if (trace == null) return
        val report = runCatching {
            trace.finish(outcome, DeviceHealthMonitor.currentSummary())
        }.getOrNull() ?: return
        // Persistence is asynchronous and best-effort; it must never affect capture or safety stop.
        runCatching { DiagnosticLog.saveLastDetectionCycle(this, report) }
    }

    private fun formatPercent(fraction: Double): String =
        String.format(java.util.Locale.US, "%.2f%%", fraction * 100.0)

    private fun screenBoundsForTrace(prepared: OverlayController.PreparedOverlay?): String =
        prepared?.screenBounds?.flattenToString() ?: "no redaction bitmap (clear requested)"

    /**
     * Model failures are bounded. Keep the last localized overlay while retrying briefly, then
     * stop protection and clear it. Never retain an opaque whole-window fallback.
     */
    private fun recordModelFailure(message: String, detail: String?, generation: Long) {
        consecutiveModelFailures++
        if (consecutiveModelFailures < MAX_CONSECUTIVE_MODEL_FAILURES) {
            mainHandler.post {
                if (!destroyed && monitoringRequested && generation == maskGeneration.get()) {
                    ProtectionStatus.update(
                        ProtectionStatus.state.value.copy(
                            active = true,
                            modelReady = true,
                            opaqueCoverActive = false,
                            message = "$message · retry ${consecutiveModelFailures}/${MAX_CONSECUTIVE_MODEL_FAILURES}",
                            error = detail
                        )
                    )
                    scheduleRetry(RETRY_AFTER_ANALYSIS_FAILURE_MS)
                }
            }
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
            stopForSafety("Protection paused after repeated model errors. The screen is visible.", detail ?: "${activeProfileName} failed repeatedly")
        }
    }

    /**
     * Capture/processing failures preserve the last localized masks for a few retries. Repeated
     * failures stop protection and clear the overlay. A technical failure never blacks out the UI.
     */
    private fun handleCaptureFailure(message: String, detail: String?, generation: Long) {
        val failures = consecutiveCaptureFailures.incrementAndGet()
        mainHandler.post {
            if (destroyed || !monitoringRequested || generation != maskGeneration.get()) return@post
            if (failures >= MAX_CONSECUTIVE_CAPTURE_FAILURES) {
                stopForSafety("Protection paused after repeated screen-capture errors. The screen is visible.", detail ?: message)
                return@post
            }
            ProtectionStatus.update(
                ProtectionStatus.state.value.copy(
                    active = true,
                    modelReady = modelReady,
                    opaqueCoverActive = false,
                    message = "$message · retry $failures/$MAX_CONSECUTIVE_CAPTURE_FAILURES",
                    error = detail,
                    regionsDetected = activeMediaRegionCount,
                    regionsRedacted = activeRedactions.size,
                    lastInferenceMs = lastInferenceMs
                )
            )
            scheduleRetry(RETRY_AFTER_ANALYSIS_FAILURE_MS)
        }
    }

    /** Central, idempotent emergency stop used by every unrecoverable runtime failure. */
    private fun stopForSafety(message: String, detail: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopForSafety(message, detail) }
            return
        }
        monitoringRequested = false
        captureRequestedWhileBusy.set(false)
        captureRequestedAfterScreenshot.set(false)
        preferences.setProtectionEnabled(false)
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.removeCallbacks(retryCapture)
        mainHandler.removeCallbacks(mainLooperHeartbeat)
        // Diagnostics must not delay or prevent the safety-stop path.
        runCatching { DeviceHealthMonitor.stop() }
        contentChangeBurstPending = false
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        consecutiveCaptureFailures.set(0)
        resetSafetyTimers()
        maskGeneration.incrementAndGet()
        overlay?.clear()
        ProtectionNotifier.clearActive(this)
        ProtectionNotifier.showPaused(this, message)
        DiagnosticLog.record(this, "SAFETY_STOP $message | ${detail ?: "no detail"}")
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                modelReady = modelReady,
                opaqueCoverActive = false,
                message = message,
                error = detail
            )
        )
    }

    /** Logs a capture-stage note once per change so a stuck state does not flood the diagnostic log. */
    private fun noteCapture(note: String) {
        if (note == lastLoggedCaptureNote) return
        lastLoggedCaptureNote = note
        DiagnosticLog.record(this, note)
    }

    private fun clearMasks() {
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        // Reset the dwell clock immediately on the main thread; do not leave a queued watchdog
        // eligible to stop protection after masks were already cleared for a window/scroll change.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateCoverageWatchdog(0.0)
        } else {
            mainHandler.post { updateCoverageWatchdog(0.0) }
        }
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
        val wasProtecting = monitoringRequested
        destroyed = true
        connected = false
        monitoringRequested = false
        resetSafetyTimers()
        ProtectionNotifier.clearActive(this)
        if (wasProtecting) ProtectionNotifier.showPaused(this, "$message - the screen is no longer protected.")
        DiagnosticLog.record(this, "SHUTDOWN $message wasProtecting=$wasProtecting")
        suppressPlatformAccessibilityEventsForTest = false
        runCatching { preferences.setProtectionEnabled(false) }
        captureRequestedWhileBusy.set(false)
        captureRequestedAfterScreenshot.set(false)
        modelReady = false
        mainHandler.removeCallbacks(periodicCapture)
        mainHandler.removeCallbacks(debouncedCapture)
        mainHandler.removeCallbacks(retryCapture)
        mainHandler.removeCallbacks(mainLooperHeartbeat)
        // Diagnostics must not delay or prevent the safety-stop path.
        runCatching { DeviceHealthMonitor.stop() }
        contentChangeBurstPending = false
        activeRedactions = emptyList()
        activeMediaRegionCount = 0
        maskGeneration.incrementAndGet()
        overlay?.close()
        overlay = null
        if (currentInstance === this) currentInstance = null

        runCatching {
            inferenceExecutor.execute {
                runCatching { detector?.close() }
                runCatching { humanBodySegmenter?.close() }
                detector = null
                humanBodySegmenter = null
                loadedProfile = null
            }
        }
        inferenceExecutor.shutdown()
        screenshotExecutor.shutdown()
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
        private const val MAX_CONSECUTIVE_CAPTURE_FAILURES = 5
        private const val MAIN_LOOPER_HEARTBEAT_INTERVAL_MS = 5_000L
        @Volatile private var currentInstance: ScreenProtectionAccessibilityService? = null
        // A stuck JNI/native call may ignore interruption. Keep this latch across service
        // recreation so repeatedly toggling Accessibility cannot create unbounded worker threads.
        private val processWorkerPoisoned = AtomicBoolean(false)

        /** Debug-only reset between isolated instrumentation cases; production clears on process death. */
        internal fun resetWorkerPoisonForTest() {
            if (BuildConfig.DEBUG) processWorkerPoisoned.set(false)
        }

        internal fun isWorkerPoisonedForTest(): Boolean =
            BuildConfig.DEBUG && processWorkerPoisoned.get()

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

        /**
         * Debug instrumentation only: exposes the actual monotonic timestamp at which the
         * current protection-session watchdog was armed. Tests read it on the main thread after
         * draining the queued Start request; this avoids using the caller's pre-dispatch wall-clock
         * timestamp as a proxy for the service-owned session deadline.
         */
        internal fun sessionStartedAtElapsedMsForTest(): Long =
            if (BuildConfig.DEBUG) currentInstance?.protectionSessionStartedAtMs ?: 0L else 0L

        /** Reports whether Android has actually bound the service, not merely enabled its setting. */
        fun isServiceConnected(): Boolean = currentInstance?.let { it.connected && !it.destroyed } == true

        /** Debug instrumentation only: routes a synthetic event through the production event path. */
        internal fun dispatchAccessibilityEventForTest(event: AccessibilityEvent) {
            if (BuildConfig.DEBUG) currentInstance?.handleAccessibilityEvent(event)
        }

        /**
         * Debug instrumentation only: create a deterministic event-test window. Must be called
         * on the main thread after the initial frame has completed. Framework events are ignored
         * while injected events still use the exact same production handler.
         */
        internal fun beginIsolatedAccessibilityEventTest(): Boolean {
            if (!BuildConfig.DEBUG || Looper.myLooper() != Looper.getMainLooper()) return false
            val service = currentInstance ?: return false
            service.suppressPlatformAccessibilityEventsForTest = true
            service.mainHandler.removeCallbacks(service.periodicCapture)
            service.mainHandler.removeCallbacks(service.debouncedCapture)
            service.mainHandler.removeCallbacks(service.retryCapture)
            service.contentChangeBurstPending = false
            return true
        }

        /** Debug instrumentation only: restore normal framework event delivery. */
        internal fun endIsolatedAccessibilityEventTest() {
            if (!BuildConfig.DEBUG || Looper.myLooper() != Looper.getMainLooper()) return
            currentInstance?.suppressPlatformAccessibilityEventsForTest = false
        }

        /** Debug instrumentation only: request a capture without synthesizing a screen-change event. */
        internal fun requestScreenshotForTest() {
            if (BuildConfig.DEBUG) {
                currentInstance?.let { service -> service.mainHandler.post { service.requestScreenshot() } }
            }
        }

        /** Debug instrumentation only: true after the overlay has submitted a rendered frame. */
        internal fun hasOverlayFrameCommittedForTest(): Boolean =
            BuildConfig.DEBUG && currentInstance?.overlay?.hasCommittedFrameForTest() == true

        /** Debug instrumentation only: confirms the previous frame has left every async stage. */
        internal fun isCapturePipelineIdleForTest(): Boolean = BuildConfig.DEBUG &&
            currentInstance?.let { service ->
                !service.screenshotInFlight.get() &&
                    !service.inferenceInFlight.get() &&
                    !service.captureRequestedAfterScreenshot.get() &&
                    !service.captureRequestedWhileBusy.get() &&
                    !service.contentChangeBurstPending &&
                    !service.mainHandler.hasCallbacks(service.periodicCapture) &&
                    !service.mainHandler.hasCallbacks(service.debouncedCapture) &&
                    !service.mainHandler.hasCallbacks(service.retryCapture)
            } == true

        /** Debug instrumentation only: exposes runtime state without exposing mutable internals. */
        internal fun isMonitoringForTest(): Boolean =
            BuildConfig.DEBUG && currentInstance?.monitoringRequested == true

        /** Debug instrumentation only: diagnostics included when a lifecycle assertion times out. */
        internal fun diagnosticsForTest(): String {
            if (!BuildConfig.DEBUG) return "debug diagnostics unavailable"
            val service = currentInstance ?: return "service instance is not connected"
            return "connected=${service.connected}, monitoring=${service.monitoringRequested}, " +
                "modelReady=${service.modelReady}, destroyed=${service.destroyed}, " +
                "foregroundPackage=${service.currentForegroundPackage}, " +
                "skipReason=${service.lastScreenshotSkipReason}, " +
                "target=${service.lastScreenshotTarget}, " +
                "attempts=${service.screenshotAttempts.get()}, successes=${service.screenshotSuccesses.get()}, " +
                "failures=${service.screenshotFailures.get()}, lastFailure=${service.lastScreenshotFailure}, " +
                "captureFailures=${service.consecutiveCaptureFailures.get()}, " +
                "screenshotInFlight=${service.screenshotInFlight.get()}, " +
                "inferenceInFlight=${service.inferenceInFlight.get()}, " +
                "inferenceWorkerPoisoned=${service.inferenceWorkerPoisoned}, " +
                "screenshotWorkerPoisoned=${service.screenshotWorkerPoisoned}, " +
                "processWorkerPoisoned=${processWorkerPoisoned.get()}, " +
                "coverage=${"%.3f".format(java.util.Locale.US, service.lastAppliedCoverageFraction)}, " +
                "sessionElapsedMs=${if (service.protectionSessionStartedAtMs > 0L) SystemClock.elapsedRealtime() - service.protectionSessionStartedAtMs else 0L}, " +
                "maskGeneration=${service.maskGeneration.get()}"
        }

        /** Debug instrumentation only: invoke Android's interruption callback through the service. */
        internal fun interruptForTest() {
            if (BuildConfig.DEBUG) currentInstance?.onInterrupt()
        }

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ScreenProtectionAccessibilityService::class.java)
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry.trim()) == expected
            }
        }
    }
}
