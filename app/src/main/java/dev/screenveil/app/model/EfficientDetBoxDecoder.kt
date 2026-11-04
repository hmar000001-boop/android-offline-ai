package dev.screenveil.app.model

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Decodes the raw TFLite_Detection_PostProcess tensor used by the pinned EfficientDet-Lite0 model.
 * The Interpreter exposes coordinates in [ymin, xmin, ymax, xmax] order. This differs from the
 * transformed [top, left, right, bottom] representation documented by the MediaPipe Task API.
 */
internal object EfficientDetBoxDecoder {
    fun toPixelBounds(
        output: FloatArray,
        offset: Int,
        imageWidth: Int,
        imageHeight: Int,
        minimumSide: Int
    ): RectF? {
        require(imageWidth > 0 && imageHeight > 0 && minimumSide > 0) {
            "EfficientDet destination bounds and minimum side must be positive"
        }
        require(offset >= 0 && offset <= output.size - 4) {
            "EfficientDet box offset is outside the output tensor"
        }

        val topRatio = output[offset]
        val leftRatio = output[offset + 1]
        val bottomRatio = output[offset + 2]
        val rightRatio = output[offset + 3]
        require(listOf(topRatio, leftRatio, bottomRatio, rightRatio).all { it.isFinite() } &&
            topRatio in 0f..1f && bottomRatio in 0f..1f &&
            leftRatio in 0f..1f && rightRatio in 0f..1f &&
            topRatio <= bottomRatio && leftRatio <= rightRatio
        ) {
            "EfficientDet returned invalid normalized [ymin,xmin,ymax,xmax] coordinates"
        }

        val left = floor(leftRatio * imageWidth).toInt().coerceIn(0, imageWidth)
        val top = floor(topRatio * imageHeight).toInt().coerceIn(0, imageHeight)
        val right = ceil(rightRatio * imageWidth).toInt().coerceIn(0, imageWidth)
        val bottom = ceil(bottomRatio * imageHeight).toInt().coerceIn(0, imageHeight)
        if (right - left < minimumSide || bottom - top < minimumSide) return null
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}
