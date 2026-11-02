package dev.screenveil.app.model

import kotlin.math.ceil
import kotlin.math.floor

/** Converts screenshot-pixel detector bounds to the 0..1000 coordinates used by mask geometry. */
object DetectionBoxNormalizer {
    /**
     * Uses floating-point division (never integer division) and rounds outward so a detector box
     * is not made smaller during normalization. Returns null for invalid dimensions, non-finite
     * coordinates or a box that is empty after clipping to the screenshot.
     */
    fun fromPixelBounds(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        imageWidth: Int,
        imageHeight: Int
    ): NormalizedBox? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null

        val clippedLeft = left.coerceIn(0f, imageWidth.toFloat())
        val clippedTop = top.coerceIn(0f, imageHeight.toFloat())
        val clippedRight = right.coerceIn(0f, imageWidth.toFloat())
        val clippedBottom = bottom.coerceIn(0f, imageHeight.toFloat())
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null

        val normalized = NormalizedBox(
            floor(clippedLeft.toDouble() / imageWidth * 1000.0).toInt().coerceIn(0, 1000),
            floor(clippedTop.toDouble() / imageHeight * 1000.0).toInt().coerceIn(0, 1000),
            ceil(clippedRight.toDouble() / imageWidth * 1000.0).toInt().coerceIn(0, 1000),
            ceil(clippedBottom.toDouble() / imageHeight * 1000.0).toInt().coerceIn(0, 1000)
        )
        return normalized.takeIf { it.right > it.left && it.bottom > it.top }
    }
}
