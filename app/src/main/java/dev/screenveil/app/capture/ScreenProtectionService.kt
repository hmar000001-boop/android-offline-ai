package dev.screenveil.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.screenveil.app.AppPreferences
import dev.screenveil.app.MainActivity
import dev.screenveil.app.R
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.ModelInstaller
import dev.screenveil.app.model.ScreenContentDetector
import dev.screenveil.app.model.ScreenModerationDetector
import dev.screenveil.app.overlay.OverlayController
import dev.screenveil.app.policy.ModerationPolicy
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one user-authorized MediaProjection session. Capture, Gemma inference and redaction
 * refresh run on separate workers so a slow model response never creates an unbounded frame queue.
 */
class ScreenProtectionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: OverlayController? = null

    // LiteRT-LM Engine and Conversation calls are confined to inferenceExecutor.
    private var detector: ScreenContentDetector? = null
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-gemma").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val redactionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-redaction").apply { priority = Thread.NORM_PRIORITY }
    }

    private val inferenceBusy = AtomicBoolean(false)
    private val redactionBusy = AtomicBoolean(false)
    private val modelReady = AtomicBoolean(false)
    private val maskGeneration = AtomicLong(0L)

    @Volatile private var activeRedactions: List<DetectedMediaRegion> = emptyList()
    @Volatile private var activeRedactionsExpireAt = 0L
    @Volatile private var stopped = false

    // These fields are written only by the ImageReader callback (captureHandler).
    private var lastInferenceSubmittedAt = 0L
    private var lastRedactionRefreshAt = 0L
    private var lastOverlayPermissionCheckAt = 0L
    private var invalidResponses = 0
    private var inferenceFailures = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var dpi = 0

    private val prefs by lazy { AppPreferences(this) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stopProtection("Screen capture permission was revoked") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || (width == captureWidth && height == captureHeight)) return
            captureHandler?.post { resizeCapture(width, height) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(this)
        createNotificationChannel()
        captureThread = HandlerThread("screenveil-capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProtection("Protection stopped")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null || stopped) return START_NOT_STICKY

        try {
            // Android requires the mediaProjection foreground service to be foreground before
            // getMediaProjection() is called.
            startForegroundCompat()
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
            if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                stopProtection("Screen capture authorization was not supplied")
                return START_NOT_STICKY
            }

            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            checkNotNull(projection) { "Android did not create a MediaProjection session" }
            projection?.registerCallback(projectionCallback, captureHandler)

            val metrics = readDisplayMetrics()
            captureWidth = metrics.widthPixels
            captureHeight = metrics.heightPixels
            dpi = metrics.densityDpi
            require(captureWidth > 0 && captureHeight > 0) { "Invalid screen dimensions" }
            createImageReader(captureWidth, captureHeight)
            virtualDisplay = projection?.createVirtualDisplay(
                "ScreenVeilCapture",
                captureWidth,
                captureHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                captureHandler
            )
            checkNotNull(virtualDisplay) { "Android could not create the capture display" }

            startModelInitialization()
            ProtectionStatus.update(
                ProtectionStatus.State(
                    active = true,
                    modelReady = false,
                    message = "Screen capture active · verifying and loading Gemma 4"
                )
            )
        } catch (error: Throwable) {
            stopProtection("Could not start screen capture: ${error.message ?: error.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun startModelInitialization() {
        inferenceExecutor.execute {
            try {
                check(ModelInstaller.verifyInstalledBlocking(this)) {
                    "Gemma model is missing or failed SHA-256 verification. Reinstall it in Settings."
                }
                val newDetector = ScreenModerationDetector(this)
                newDetector.initialize(ModelInstaller.modelFile(this))
                if (stopped) {
                    newDetector.close()
                    return@execute
                }
                detector = newDetector
                modelReady.set(true)
                ProtectionStatus.update(
                    ProtectionStatus.State(
                        active = true,
                        modelReady = true,
                        message = "Protection active · ${newDetector.activeBackend} backend"
                    )
                )
            } catch (error: Throwable) {
                val detail = error.message ?: error.javaClass.simpleName
                ProtectionStatus.update(
                    ProtectionStatus.State(
                        active = false,
                        modelReady = false,
                        message = "Gemma could not be loaded",
                        error = detail
                    )
                )
                mainHandler.post { stopProtection("Gemma could not be loaded: $detail") }
            }
        }
    }

    private fun createImageReader(width: Int, height: Int) {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, captureHandler)
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        if (stopped || width <= 0 || height <= 0) return
        try {
            captureWidth = width
            captureHeight = height
            val oldReader = imageReader
            val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
                reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, captureHandler)
            }
            virtualDisplay?.resize(width, height, dpi)
            virtualDisplay?.setSurface(replacement.surface)
            imageReader = replacement
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
            // Results from the previous dimensions cannot safely be used after a resize.
            activeRedactions = emptyList()
            activeRedactionsExpireAt = 0L
            val generation = maskGeneration.incrementAndGet()
            mainHandler.post {
                if (!stopped && generation == maskGeneration.get()) overlay?.clear()
            }
        } catch (error: Throwable) {
            stopProtection("Screen size changed and capture could not be resized")
        }
    }

    /** Called on captureHandler. Unneeded ImageReader frames are closed without creating Bitmaps. */
    private fun onImageAvailable(reader: ImageReader) {
        if (stopped) return
        val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        val now = SystemClock.elapsedRealtime()
        val settings = prefs.getSettings()

        if (now - lastOverlayPermissionCheckAt >= OVERLAY_PERMISSION_CHECK_INTERVAL_MS) {
            lastOverlayPermissionCheckAt = now
            if (overlay?.hasOverlayPermission() != true) {
                image.close()
                mainHandler.post { stopProtection("Overlay permission is no longer available") }
                return
            }
        }

        // Expire old coordinates so a scroll or screen transition cannot leave a stale mask
        // attached to an unrelated screen area indefinitely.
        if (activeRedactions.isNotEmpty() && now >= activeRedactionsExpireAt) {
            activeRedactions = emptyList()
            activeRedactionsExpireAt = 0L
            val generation = maskGeneration.incrementAndGet()
            mainHandler.post {
                if (!stopped && generation == maskGeneration.get()) overlay?.clear()
            }
        }

        val runInference = modelReady.get() &&
            now - lastInferenceSubmittedAt >= settings.frameIntervalMs &&
            inferenceBusy.compareAndSet(false, true)
        val regions = activeRedactions
        val runRedactionRefresh = !runInference && regions.isNotEmpty() &&
            now - lastRedactionRefreshAt >= REDACTION_REFRESH_INTERVAL_MS &&
            redactionBusy.compareAndSet(false, true)

        if (!runInference && !runRedactionRefresh) {
            image.close()
            return
        }

        val frame = try {
            imageToBitmap(image)
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }
        if (frame == null) {
            if (runInference) inferenceBusy.set(false)
            if (runRedactionRefresh) redactionBusy.set(false)
            return
        }

        if (runInference) {
            lastInferenceSubmittedAt = now
            submitInference(frame, settings)
        } else {
            lastRedactionRefreshAt = now
            submitRedactionRefresh(frame, regions, settings, maskGeneration.get())
        }
    }

    private fun submitInference(frame: Bitmap, settings: dev.screenveil.app.policy.ModerationSettings) {
        try {
            inferenceExecutor.execute {
                try {
                    if (stopped) return@execute
                    val activeDetector = detector ?: return@execute
                    val started = SystemClock.elapsedRealtime()
                    val result = activeDetector.inspect(frame)
                    val duration = SystemClock.elapsedRealtime() - started
                    if (stopped) return@execute

                    if (!result.parseSucceeded) {
                        invalidResponses++
                        ProtectionStatus.update(
                            ProtectionStatus.State(
                                active = true,
                                modelReady = true,
                                message = "Gemma response could not be validated; retaining recent masks",
                                error = result.diagnostic
                            )
                        )
                        if (invalidResponses >= MAX_INVALID_RESPONSES) {
                            mainHandler.post {
                                stopProtection("Repeated invalid model responses; protection stopped safely")
                            }
                        }
                        return@execute
                    }

                    invalidResponses = 0
                    inferenceFailures = 0
                    val redactions = result.regions.filter {
                        ModerationPolicy.shouldRedact(it.decision, settings.redactUncertain)
                    }
                    activeRedactions = redactions
                    activeRedactionsExpireAt = if (redactions.isEmpty()) 0L else
                        SystemClock.elapsedRealtime() + maxOf(MIN_MASK_LIFETIME_MS, settings.frameIntervalMs * 3)
                    val generation = maskGeneration.incrementAndGet()
                    val preparedMasks = overlay?.prepare(frame, redactions, settings).orEmpty()
                    postPreparedMasks(generation, preparedMasks)

                    ProtectionStatus.update(
                        ProtectionStatus.State(
                            active = true,
                            modelReady = true,
                            message = "Protection active · ${redactions.size} region(s) redacted",
                            regionsRedacted = redactions.size,
                            lastInferenceMs = duration
                        )
                    )
                } catch (error: Throwable) {
                    if (!stopped) {
                        inferenceFailures++
                        ProtectionStatus.update(
                            ProtectionStatus.State(
                                active = true,
                                modelReady = detector != null,
                                message = "Inference failed · retaining recent masks",
                                error = error.message ?: error.javaClass.simpleName
                            )
                        )
                        if (inferenceFailures >= MAX_INFERENCE_FAILURES) {
                            mainHandler.post {
                                stopProtection("Repeated inference failures; protection stopped safely")
                            }
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    inferenceBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!frame.isRecycled) frame.recycle()
            inferenceBusy.set(false)
        }
    }

    private fun submitRedactionRefresh(
        frame: Bitmap,
        regions: List<DetectedMediaRegion>,
        settings: dev.screenveil.app.policy.ModerationSettings,
        generation: Long
    ) {
        try {
            redactionExecutor.execute {
                try {
                    if (stopped || generation != maskGeneration.get()) return@execute
                    val prepared = overlay?.prepare(frame, regions, settings).orEmpty()
                    postPreparedMasks(generation, prepared)
                } catch (_: Throwable) {
                    // Keep the last displayed mask; a later frame gets another refresh attempt.
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    redactionBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!frame.isRecycled) frame.recycle()
            redactionBusy.set(false)
        }
    }

    private fun postPreparedMasks(generation: Long, masks: List<OverlayController.PreparedMask>) {
        val posted = mainHandler.post {
            if (!stopped && generation == maskGeneration.get()) {
                val shown = overlay?.showPrepared(masks) == true
                if (!shown) stopProtection("Could not display a redaction overlay; protection stopped")
            } else {
                masks.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            }
        }
        if (!posted) masks.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride > 0 && rowStride >= image.width * pixelStride) { "Unexpected screen image buffer layout" }
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        return try {
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } finally {
            padded.recycle()
        }
    }

    private fun readDisplayMetrics(): DisplayMetrics {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            return DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.configuration.densityDpi
                density = densityDpi / 160f
                scaledDensity = density
                xdpi = resources.displayMetrics.xdpi
                ydpi = resources.displayMetrics.ydpi
            }
        }
        @Suppress("DEPRECATION")
        return DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
    }

    private fun stopProtection(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopProtection(message) }
            return
        }
        if (stopped) return
        stopped = true
        modelReady.set(false)
        activeRedactions = emptyList()
        activeRedactionsExpireAt = 0L
        maskGeneration.incrementAndGet()

        try { virtualDisplay?.release() } catch (_: Throwable) { }
        virtualDisplay = null
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Throwable) { }
        imageReader = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { overlay?.close() } catch (_: Throwable) { }
        } else {
            mainHandler.post { try { overlay?.close() } catch (_: Throwable) { } }
        }

        // Close the LiteRT-LM Engine on its owning thread, after an in-flight inference completes.
        runCatching {
            inferenceExecutor.execute {
                try { detector?.close() } catch (_: Throwable) { }
                detector = null
            }
        }
        inferenceExecutor.shutdown()
        redactionExecutor.shutdown()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        ProtectionStatus.update(ProtectionStatus.State(active = false, message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            42,
            Intent(this, ScreenProtectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this,
            43,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Images are analyzed locally on this device")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (!stopped) stopProtection("Protection stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun Intent.parcelableIntent(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key) as? Intent
        }
    }

    companion object {
        const val ACTION_START = "dev.screenveil.action.START"
        const val ACTION_STOP = "dev.screenveil.action.STOP"
        const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        private const val CHANNEL_ID = "screenveil_protection"
        private const val NOTIFICATION_ID = 7114
        private const val REDACTION_REFRESH_INTERVAL_MS = 250L
        private const val OVERLAY_PERMISSION_CHECK_INTERVAL_MS = 1_000L
        private const val MIN_MASK_LIFETIME_MS = 4_000L
        private const val MAX_INVALID_RESPONSES = 3
        private const val MAX_INFERENCE_FAILURES = 3

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenProtectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenProtectionService::class.java).setAction(ACTION_STOP)
    }
}
