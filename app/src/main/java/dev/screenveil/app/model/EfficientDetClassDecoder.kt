package dev.screenveil.app.model

import kotlin.math.abs
import kotlin.math.roundToInt

/** Validates the model's zero-based class index; the first label (index 0) is person. */
internal object EfficientDetClassDecoder {
    const val MODEL_CLASS_COUNT = 90
    const val PERSON_CLASS_INDEX = 0

    fun validateRawClassIndex(rawClassIndex: Float): Int {
        require(
            rawClassIndex.isFinite() &&
                rawClassIndex >= 0f &&
                rawClassIndex < MODEL_CLASS_COUNT.toFloat() &&
                abs(rawClassIndex - rawClassIndex.roundToInt()) < 0.01f
        ) {
            "EfficientDet returned an invalid zero-based class index: $rawClassIndex"
        }
        return rawClassIndex.roundToInt()
    }
}
