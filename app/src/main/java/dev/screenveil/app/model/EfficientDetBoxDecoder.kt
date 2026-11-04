package dev.screenveil.app.model

import kotlin.math.ceil
import kotlin.math.floor

/** Immutable pixel-space rectangle that has no Android framework dependency. */
internal data class EfficientDetPixelBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Decodes the raw TFLite_Detection_PostProcess tensor used by the pinned EfficientDet-Lite0 model.
 * The raw tensor uses [ymin, xmin, ymax, xmax] normalized to the model input dimensions.
 * The TFLite op guarantees corner ordering but does not clamp boxes to the image frame; a valid
 * detection may therefore extend below 0 or above 1. Clip only after validating finiteness/order.
 */
internal object EfficientDetBoxDecoder {
    fun validateRawBox(output: FloatArray, offset: Int) {
        require(offset >= 0 && offset <= output.size - 4) {
            "EfficientDet box offset is outside the output tensor"
        }
        val ymin = output[offset]
        val xmin = output[offset + 1]
        val ymax = output[offset + 2]
        val xmax = output[offset + 3]
        require(listOf(ymin, xmin, ymax, xmax).all { it.isFinite() } &&
            ymin <= ymax && xmin <= xmax
        ) {
            "EfficientDet returned non-finite or inverted [ymin,xmin,ymax,xmax] coordinates"
        }
    }

    fun toPixelBounds(
        output: FloatArray,
        offset: Int,
        imageWidth: Int,
        imageHeight: Int,
        minimumSide: Int
    ): EfficientDetPixelBounds? {
        require(imageWidth > 0 && imageHeight > 0 && minimumSide > 0) {
            "EfficientDet destination bounds and minimum side must be positive"
        }
        validateRawBox(output, offset)

        // The output values are normalized to the model input size, but DetectionPostProcess
        // does not clip decoded boxes. Intersect them with the visible screenshot before mapping.
        val topRatio = output[offset].coerceIn(0f, 1f)
        val leftRatio = output[offset + 1].coerceIn(0f, 1f)
        val bottomRatio = output[offset + 2].coerceIn(0f, 1f)
        val rightRatio = output[offset + 3].coerceIn(0f, 1f)

        val left = floor(leftRatio * imageWidth).toInt().coerceIn(0, imageWidth)
        val top = floor(topRatio * imageHeight).toInt().coerceIn(0, imageHeight)
        val right = ceil(rightRatio * imageWidth).toInt().coerceIn(0, imageWidth)
        val bottom = ceil(bottomRatio * imageHeight).toInt().coerceIn(0, imageHeight)
        if (right - left < minimumSide || bottom - top < minimumSide) return null
        return EfficientDetPixelBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}
