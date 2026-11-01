package dev.screenveil.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.policy.ModerationPolicy
import dev.screenveil.app.policy.ModerationSettings

/** CPU-heavy crop processing happens on a worker; window attachment/update is main-thread only. */
class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val displayedWindows = mutableListOf<DisplayedWindow>()

    data class PreparedMask(val left: Int, val top: Int, val width: Int, val height: Int, val bitmap: Bitmap)

    private data class DisplayedWindow(
        val view: ImageView,
        var bitmap: Bitmap,
        var layoutParams: WindowManager.LayoutParams
    )

    fun prepare(frame: Bitmap, regions: List<DetectedMediaRegion>, settings: ModerationSettings): List<PreparedMask> {
        val masks = ArrayList<PreparedMask>()
        for (region in regions) {
            if (!ModerationPolicy.shouldRedact(region.decision, settings.redactUncertain)) continue
            val left = (region.box.left / 1000f * frame.width).toInt().coerceIn(0, frame.width - 1)
            val top = (region.box.top / 1000f * frame.height).toInt().coerceIn(0, frame.height - 1)
            val right = (region.box.right / 1000f * frame.width).toInt().coerceIn(left + 1, frame.width)
            val bottom = (region.box.bottom / 1000f * frame.height).toInt().coerceIn(top + 1, frame.height)
            val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
            val redacted = try {
                BitmapRedactor.redact(crop, settings.effect, settings.intensity)
            } catch (error: Exception) {
                if (!crop.isRecycled) crop.recycle()
                continue
            }
            if (!crop.isRecycled && redacted !== crop) crop.recycle()
            masks += PreparedMask(left, top, right - left, bottom - top, redacted)
        }
        return masks
    }

    /**
     * Uses one overlay window per redacted media rectangle. Touches outside those rectangles
     * are not obscured by a transparent full-screen overlay. Taps directly on a redacted region
     * may be blocked by Android's untrusted-touch protection, which is preferable to letting a
     * transparent overlay consume the whole display.
     */
    fun showPrepared(masks: List<PreparedMask>): Boolean {
        if (masks.isEmpty()) {
            clear()
            return true
        }
        if (!hasOverlayPermission()) {
            masks.forEach { recycle(it.bitmap) }
            return false
        }

        val transferred = BooleanArray(masks.size)
        try {
            for (index in masks.indices) {
                val mask = masks[index]
                val params = createLayoutParams(mask)
                if (index < displayedWindows.size) {
                    val existing = displayedWindows[index]
                    val previousBitmap = existing.bitmap
                    existing.view.setImageBitmap(mask.bitmap)
                    windowManager.updateViewLayout(existing.view, params)
                    existing.bitmap = mask.bitmap
                    existing.layoutParams = params
                    transferred[index] = true
                    recycle(previousBitmap)
                } else {
                    val image = ImageView(context).apply {
                        setImageBitmap(mask.bitmap)
                        scaleType = ImageView.ScaleType.FIT_XY
                        isClickable = false
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    windowManager.addView(image, params)
                    displayedWindows += DisplayedWindow(image, mask.bitmap, params)
                    transferred[index] = true
                }
            }

            while (displayedWindows.size > masks.size) {
                val removed = displayedWindows.removeAt(displayedWindows.lastIndex)
                runCatching { windowManager.removeView(removed.view) }
                removed.view.setImageDrawable(null)
                recycle(removed.bitmap)
            }
            return true
        } catch (_: Exception) {
            masks.forEachIndexed { index, mask -> if (!transferred[index]) recycle(mask.bitmap) }
            close()
            return false
        }
    }

    fun hasOverlayPermission(): Boolean = android.provider.Settings.canDrawOverlays(context)

    private fun createLayoutParams(mask: PreparedMask) = WindowManager.LayoutParams(
        mask.width.coerceAtLeast(1),
        mask.height.coerceAtLeast(1),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // Prevent ScreenVeil's own mask pixels from feeding back into subsequent captures.
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = mask.left
        y = mask.top
        title = "ScreenVeil redaction"
    }

    fun clear() {
        displayedWindows.forEach { entry ->
            runCatching { windowManager.removeView(entry.view) }
            entry.view.setImageDrawable(null)
            recycle(entry.bitmap)
        }
        displayedWindows.clear()
    }

    fun close() = clear()

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
    }
}
