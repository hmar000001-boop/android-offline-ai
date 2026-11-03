package dev.screenveil.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import dev.screenveil.app.BuildConfig
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
    data class PreparedOverlay(
        val bitmap: Bitmap,
        val screenBounds: Rect,
        val redactedRegions: Int,
        /** Share (0..1) of the window covered by the union of the rendered regions. */
        val coverageFraction: Double = 0.0
    )

    private var imageView: ImageView? = null
    @Volatile private var displayedFrameCommitted = false
    @Volatile private var frameCommitGeneration = 0L

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

    /**
     * Must be called on the main thread. Once passed here, callers must not mutate or recycle the
     * prepared bitmap, even when this method returns false: a failure may occur after ImageView has
     * accepted it, and the view/render thread may still hold a reference while it is detached.
     */
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
                if (BuildConfig.DEBUG) trackFrameCommit(newView)
                true
            } catch (_: Throwable) {
                // This bitmap was handed to an ImageView. Even if addView/update failed partway
                // through, the renderer may still hold a reference. Invalidate any queued frame
                // callback, detach the drawable and let GC reclaim the bitmap after HWUI releases it.
                if (imageView === newView) imageView = null
                frameCommitGeneration += 1L
                displayedFrameCommitted = false
                newView.setImageDrawable(null)
                runCatching { windowManager.removeView(newView) }
                false
            }
        }

        return try {
            // Both operations run in one main-thread turn. The single view replaces every region
            // together; no old per-region window can survive a reorder or count change.
            currentView.setImageBitmap(prepared.bitmap)
            windowManager.updateViewLayout(currentView, params)
            if (BuildConfig.DEBUG) trackFrameCommit(currentView)
            true
        } catch (_: Throwable) {
            // setImageBitmap may already have handed the new bitmap to HWUI before a later
            // WindowManager call throws. close() detaches the drawable; do not recycle a bitmap
            // that may still be referenced by a queued hardware-render frame.
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
        frameCommitGeneration += 1L
        displayedFrameCommitted = false
        if (view != null) {
            // Detach the drawable before removing the Window. A frame already submitted to
            // RenderThread can outlive this call, so the displayed Bitmap is intentionally not
            // recycled here; after all Java/HWUI references disappear, normal GC reclaims it.
            view.setImageDrawable(null)
            runCatching { windowManager.removeView(view) }
        }
    }

    open fun close() = clear()

    /** Debug-test observation: proves an overlay frame was submitted, not merely added to WMS. */
    internal fun hasCommittedFrameForTest(): Boolean = displayedFrameCommitted

    private fun trackFrameCommit(view: ImageView) {
        val generation = frameCommitGeneration + 1L
        frameCommitGeneration = generation
        displayedFrameCommitted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && view.isHardwareAccelerated) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                observer.registerFrameCommitCallback {
                    // An old frame can be committed after a newer bitmap has been installed or
                    // after clear(). It must never mark the newer/cleared overlay as visible.
                    if (frameCommitGeneration == generation && imageView === view) {
                        displayedFrameCommitted = true
                    }
                }
                view.invalidate()
                return
            }
        }
        // Software-rendered fallback: wait for two animation callbacks after attachment/update.
        view.postOnAnimation {
            view.postOnAnimation {
                if (frameCommitGeneration == generation && imageView === view) {
                    displayedFrameCommitted = true
                }
            }
        }
    }

    private fun recycle(bitmap: Bitmap?) {
        // Only call for a bitmap that was never supplied to a View (e.g. permission denied).
        if (bitmap != null && !bitmap.isRecycled) runCatching { bitmap.recycle() }
    }
}
