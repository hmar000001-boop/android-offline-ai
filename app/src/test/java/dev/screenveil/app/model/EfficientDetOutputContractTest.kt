package dev.screenveil.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EfficientDetOutputContractTest {
    @Test
    fun clipsValidOutOfFrameNormalizedBoxToVisiblePixels() {
        val box = EfficientDetBoxDecoder.toPixelBounds(
            output = floatArrayOf(-0.10f, 0.20f, 1.10f, 1.20f),
            offset = 0,
            imageWidth = 1_000,
            imageHeight = 500,
            minimumSide = 1
        )
        val decoded = checkNotNull(box)
        assertEquals(200f, decoded.left, 0f)
        assertEquals(0f, decoded.top, 0f)
        assertEquals(1_000f, decoded.right, 0f)
        assertEquals(500f, decoded.bottom, 0f)
    }

    @Test
    fun dropsBoxThatDoesNotIntersectTheVisibleFrame() {
        val box = EfficientDetBoxDecoder.toPixelBounds(
            output = floatArrayOf(1.10f, 0.10f, 1.20f, 0.30f),
            offset = 0,
            imageWidth = 320,
            imageHeight = 320,
            minimumSide = 1
        )
        assertNull(box)
    }

    @Test
    fun rejectsNonFiniteOrInvertedRawBoxes() {
        assertThrows(IllegalArgumentException::class.java) {
            EfficientDetBoxDecoder.validateRawBox(floatArrayOf(0f, 0f, Float.NaN, 1f), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EfficientDetBoxDecoder.validateRawBox(floatArrayOf(0.8f, 0f, 0.2f, 1f), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EfficientDetBoxDecoder.validateRawBox(floatArrayOf(0f, 0.8f, 1f, 0.2f), 0)
        }
    }

    @Test
    fun preservesRawZeroBasedPersonClassIndex() {
        assertEquals(EfficientDetClassDecoder.PERSON_CLASS_INDEX, EfficientDetClassDecoder.validateRawClassIndex(0f))
        assertEquals(1, EfficientDetClassDecoder.validateRawClassIndex(1f))
        assertEquals(89, EfficientDetClassDecoder.validateRawClassIndex(89f))
    }

    @Test
    fun rejectsInvalidRawClassIndices() {
        listOf(-1f, 90f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                EfficientDetClassDecoder.validateRawClassIndex(value)
            }
        }
    }
}
