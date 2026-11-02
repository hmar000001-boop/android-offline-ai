package dev.screenveil.app.overlay

import dev.screenveil.app.model.NormalizedBox
import kotlin.math.ceil
import kotlin.math.floor

/** Integer rectangles in source-frame and destination-window pixel coordinates. */
data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class MappedRegion(val source: PixelBounds, val destination: PixelBounds)

/**
 * Maps model coordinates (normalized to the screenshot) first to source pixels and then to the
 * captured application window's display-pixel bounds. Keeping this pure makes the transform
 * independently testable and avoids mixing screenshot pixels with absolute screen coordinates.
 */
object MaskGeometry {
    fun map(
        box: NormalizedBox,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int
    ): MappedRegion? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) {
            return null
        }

        val leftN = box.left.coerceIn(0, 1000)
        val topN = box.top.coerceIn(0, 1000)
        val rightN = box.right.coerceIn(0, 1000)
        val bottomN = box.bottom.coerceIn(0, 1000)
        if (rightN <= leftN || bottomN <= topN) return null

        val sourceLeft = floor(leftN * sourceWidth / 1000.0).toInt().coerceIn(0, sourceWidth - 1)
        val sourceTop = floor(topN * sourceHeight / 1000.0).toInt().coerceIn(0, sourceHeight - 1)
        val sourceRight = ceil(rightN * sourceWidth / 1000.0).toInt().coerceIn(sourceLeft + 1, sourceWidth)
        val sourceBottom = ceil(bottomN * sourceHeight / 1000.0).toInt().coerceIn(sourceTop + 1, sourceHeight)

        val destLeft = floor(sourceLeft * destinationWidth / sourceWidth.toDouble())
            .toInt().coerceIn(0, destinationWidth - 1)
        val destTop = floor(sourceTop * destinationHeight / sourceHeight.toDouble())
            .toInt().coerceIn(0, destinationHeight - 1)
        val destRight = ceil(sourceRight * destinationWidth / sourceWidth.toDouble())
            .toInt().coerceIn(destLeft + 1, destinationWidth)
        val destBottom = ceil(sourceBottom * destinationHeight / sourceHeight.toDouble())
            .toInt().coerceIn(destTop + 1, destinationHeight)

        return MappedRegion(
            source = PixelBounds(sourceLeft, sourceTop, sourceRight, sourceBottom),
            destination = PixelBounds(destLeft, destTop, destRight, destBottom)
        )
    }
}
