package dev.screenveil.app.model

import dev.screenveil.app.overlay.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionBoxNormalizerTest {
    @Test fun convertsPixelCoordinatesUsingFloatingPointDivision() {
        val box = DetectionBoxNormalizer.fromPixelBounds(
            left = 200f, top = 600f, right = 800f, bottom = 1800f,
            imageWidth = 1080, imageHeight = 2400
        )
        assertEquals(NormalizedBox(185, 250, 741, 750), box)
    }

    @Test fun roundsOutwardForFractionalDetectorBounds() {
        val box = DetectionBoxNormalizer.fromPixelBounds(
            left = 200.9f, top = 600.2f, right = 800.1f, bottom = 1800.9f,
            imageWidth = 1080, imageHeight = 2400
        )
        assertEquals(NormalizedBox(186, 250, 741, 751), box)
    }

    @Test fun clipsDetectorBoundsToScreenshotEdges() {
        val box = DetectionBoxNormalizer.fromPixelBounds(
            left = -20f, top = -5f, right = 1200f, bottom = 2600f,
            imageWidth = 1080, imageHeight = 2400
        )
        assertEquals(NormalizedBox(0, 0, 1000, 1000), box)
    }

    @Test fun rejectsInvalidDimensionsNonFiniteAndEmptyBoxes() {
        assertNull(DetectionBoxNormalizer.fromPixelBounds(1f, 1f, 2f, 2f, 0, 10))
        assertNull(DetectionBoxNormalizer.fromPixelBounds(Float.NaN, 1f, 2f, 2f, 10, 10))
        assertNull(DetectionBoxNormalizer.fromPixelBounds(5f, 1f, 5f, 2f, 10, 10))
        assertNull(DetectionBoxNormalizer.fromPixelBounds(11f, 1f, 12f, 2f, 10, 10))
    }

    @Test fun normalizedDetectionStillCoversOriginalRegionAfterWindowMapping() {
        val normalized = DetectionBoxNormalizer.fromPixelBounds(
            left = 200f, top = 600f, right = 800f, bottom = 1800f,
            imageWidth = 1080, imageHeight = 2400
        )!!
        val mapped = MaskGeometry.map(normalized, 1080, 2400, 540, 1200)!!

        assertTrue(mapped.source.left <= 200)
        assertTrue(mapped.source.top <= 600)
        assertTrue(mapped.source.right >= 800)
        assertTrue(mapped.source.bottom >= 1800)
        assertEquals(99, mapped.destination.left)
        assertEquals(300, mapped.destination.top)
        assertEquals(401, mapped.destination.right)
        assertEquals(900, mapped.destination.bottom)
    }
}
