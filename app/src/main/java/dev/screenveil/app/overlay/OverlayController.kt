package dev.screenveil.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.policy.ModerationPolicy
import dev.screenveil.app.policy.ModerationSettings
import dev.screenveil.app.policy.CoveragePolicy

/**
 * Produces one fully composed bitmap on a worker, then swaps that bitmap into one accessibility
 * overlay window on the main thread. This avoids per-region windows being updated at different
 * times or retaining old coordinates when the number/order of detections changes.
 */
open class OverlayController(
    private val context: Context,
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    private val requiresDrawOverlaysPermission: Boolean = false
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PreparedOverlay(
        val bitmap: Bitmap,
        val screenBounds: Rect,
        val redactedRegions: Int,
        /** Share (0..1) of the window covered by the union of the rendered regions. */
        val coverageFraction: Double = 0.0
    )

    private var imageView: ImageView? = null
    private var displayedBitmap: Bitmap? = null

    /** CPU-heavy crop/effect work is safe on a worker; this method never touches WindowManager. */
    open fun prepare(
        frame: Bitmap,
        regions: List<DetectedMediaRegion>,
        settings: ModerationSettings,
        screenBounds: Rect
    ): PreparedOverlay? {
        if (frame.isRecycled || frame.width <= 0 || frame.height <= 0) return null
        val width = screenBounds.width()
        val height = screenBounds.height()
        require(width > 0 && height > 0) { "The captured application window has invalid bounds" }

        val selected = regions.filter {
            ModerationPolicy.shouldRedact(it.decision, settings.redactUncertain, settings.redactAllPeople)
        }
        if (selected.isEmpty()) return null
        if (selected.size > CoveragePolicy.MAX_REGIONS_PER_FRAME) {
            throw CoveragePolicy.CoverageLimitExceededException("Too many redaction regions: ${selected.size}")
        }

        val composed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        composed.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(composed)
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        var completed = 0

        try {
            for ((index, region) in selected.withIndex()) {
                val bodyMask = region.bodyMask ?: throw CoveragePolicy.BodySegmentationSafetyException(
                    "Region ${index + 1} has no verified body/clothing segmentation mask; refusing rectangular redaction"
                )
                val mapped = MaskGeometry.map(
                    region.box,
                    frame.width,
                    frame.height,
                    width,
                    height
                ) ?: throw CoveragePolicy.BodySegmentationSafetyException(
                    "Region ${index + 1} has invalid geometry; refusing to draw an approximate or full-window mask"
                )
                val source = mapped.source
                val target = mapped.destination
                var crop: Bitmap? = null
                var redacted: Bitmap? = null
                var rasterizedRegion: Bitmap? = null
                try {
                    crop = Bitmap.createBitmap(
                        frame,
                        source.left,
                        source.top,
                        source.width,
                        source.height
                    )
                    redacted = BitmapRedactor.redactMasked(
                        source = crop,
                        effect = settings.effect,
                        intensity = settings.intensity,
                        bodyMask = bodyMask,
                        sourceRect = Rect(source.left, source.top, source.right, source.bottom),
                        frameWidth = frame.width,
                        frameHeight = frame.height
                    )
                    // Rasterize at the exact destination dimensions before enforcing the per-region
                    // limit. This measures the alpha pixels that will actually be drawn rather
                    // than estimating screen area from source-box proportions.
                    rasterizedRegion = if (redacted.width == target.width && redacted.height == target.height) {
                        redacted
                    } else {
                        Bitmap.createScaledBitmap(redacted, target.width, target.height, true)
                    }
                    val regionCoverage = BitmapRedactor.countVisiblePixels(rasterizedRegion).toDouble() /
                        (width.toDouble() * height.toDouble())
                    CoveragePolicy.requireRegionCoverage(regionCoverage)
                    canvas.drawBitmap(
                        rasterizedRegion,
                        null,
                        RectF(
                            target.left.toFloat(),
                            target.top.toFloat(),
                            target.right.toFloat(),
                            target.bottom.toFloat()
                        ),
                        imagePaint
                    )
                } catch (error: Throwable) {
                    if (error is CoveragePolicy.CoverageLimitExceededException ||
                        error is CoveragePolicy.BodySegmentationSafetyException
                    ) throw error
                    throw CoveragePolicy.BodySegmentationSafetyException(
                        "Could not safely render body/clothing mask for region ${index + 1}", error
                    )
                } finally {
                    rasterizedRegion?.let {
                        if (it !== redacted && !it.isRecycled) it.recycle()
                    }
                    redacted?.let { if (!it.isRecycled) it.recycle() }
                    crop?.let { if (!it.isRecycled) it.recycle() }
                }
                completed++
            }

            if (completed != selected.size) {
                throw CoveragePolicy.BodySegmentationSafetyException(
                    "Only $completed of ${selected.size} requested body masks were rendered"
                )
            }
            val visiblePixels = BitmapRedactor.countVisiblePixels(composed)
            val coverage = visiblePixels.toDouble() / (width.toDouble() * height.toDouble())
            CoveragePolicy.requireTotalCoverage(coverage)
            if (visiblePixels == 0) {
                throw CoveragePolicy.BodySegmentationSafetyException("Segmentation generated no visible body/clothing pixels")
            }
            return PreparedOverlay(
                composed,
                Rect(screenBounds),
                completed,
                coverage
            )
        } catch (error: Throwable) {
            if (!composed.isRecycled) composed.recycle()
            throw error
        }
    }

    /** Must be called on the main thread. `prepared` transfers bitmap ownership on success. */
    open fun showPrepared(prepared: PreparedOverlay?): Boolean {
        if (prepared == null) {
            clear()
            return true
        }
        if (requiresDrawOverlaysPermission && !hasOverlayPermission()) {
            recycle(prepared.bitmap)
            return false
        }

        val params = createLayoutParams(prepared.screenBounds)
        val currentView = imageView
        if (currentView == null) {
            val newView = ImageView(context).apply {
                setImageBitmap(prepared.bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = "ScreenVeil redacted content"
            }
            return try {
                windowManager.addView(newView, params)
                imageView = newView
                displayedBitmap = prepared.bitmap
                true
            } catch (_: Throwable) {
                runCatching { windowManager.removeView(newView) }
                newView.setImageDrawable(null)
                recycle(prepared.bitmap)
                false
            }
        }

        val oldBitmap = displayedBitmap
        return try {
            // Both operations run in one main-thread turn. The single view replaces every region
            // together; no old per-region window can survive a reorder or count change.
            currentView.setImageBitmap(prepared.bitmap)
            windowManager.updateViewLayout(currentView, params)
            displayedBitmap = prepared.bitmap
            if (oldBitmap !== prepared.bitmap) recycleAfterViewRelease(oldBitmap)
            true
        } catch (_: Throwable) {
            // Never keep an overlay whose bitmap/geometry relationship is uncertain.
            recycle(prepared.bitmap)
            close()
            false
        }
    }

    fun hasOverlayPermission(): Boolean = !requiresDrawOverlaysPermission ||
        android.provider.Settings.canDrawOverlays(context)

    private fun createLayoutParams(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width().coerceAtLeast(1),
        bounds.height().coerceAtLeast(1),
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // Do not expose ScreenVeil's own redacted bitmap to screenshots of other surfaces.
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        title = "ScreenVeil redaction"
    }

    open fun clear() {
        val view = imageView
        imageView = null
        val oldBitmap = displayedBitmap
        displayedBitmap = null
        if (view != null) {
            runCatching { windowManager.removeView(view) }
            view.setImageDrawable(null)
            recycleAfterViewRelease(oldBitmap)
        } else {
            recycle(oldBitmap)
        }
    }

    open fun close() = clear()

    /** Let the renderer retire its previous frame before releasing the bitmap's native pixels. */
    private fun recycleAfterViewRelease(bitmap: Bitmap?) {
        if (bitmap == null) return
        mainHandler.postDelayed({ recycle(bitmap) }, BITMAP_RETIRE_DELAY_MS)
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    companion object {
        private const val BITMAP_RETIRE_DELAY_MS = 200L
    }
}
