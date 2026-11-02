package dev.screenveil.app.policy

import dev.screenveil.app.model.NormalizedBox
import dev.screenveil.app.overlay.MaskGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaskGeometryTest {
    @Test fun mapsFullFrameToFullWindowWhenSizesMatch() {
        val mapped = MaskGeometry.map(NormalizedBox(0, 0, 1000, 1000), 1080, 2400, 1080, 2400)
        assertEquals(0, mapped?.source?.left)
        assertEquals(0, mapped?.source?.top)
        assertEquals(1080, mapped?.source?.right)
        assertEquals(2400, mapped?.source?.bottom)
        assertEquals(0, mapped?.destination?.left)
        assertEquals(0, mapped?.destination?.top)
        assertEquals(1080, mapped?.destination?.right)
        assertEquals(2400, mapped?.destination?.bottom)
    }

    @Test fun scalesSourcePixelsToWindowBoundsIndependentlyOnEachAxis() {
        val mapped = MaskGeometry.map(NormalizedBox(100, 200, 900, 800), 1000, 2000, 500, 800)
        assertEquals(100, mapped?.source?.left)
        assertEquals(400, mapped?.source?.top)
        assertEquals(900, mapped?.source?.right)
        assertEquals(1600, mapped?.source?.bottom)
        assertEquals(50, mapped?.destination?.left)
        assertEquals(160, mapped?.destination?.top)
        assertEquals(450, mapped?.destination?.right)
        assertEquals(640, mapped?.destination?.bottom)
    }

    @Test fun roundsOutwardsSoBoundaryPixelsAreNotLost() {
        val mapped = MaskGeometry.map(NormalizedBox(1, 1, 2, 2), 333, 777, 1000, 2000)
        assertEquals(0, mapped?.source?.left)
        assertEquals(0, mapped?.source?.top)
        assertEquals(1, mapped?.source?.right)
        assertEquals(2, mapped?.source?.bottom)
        assertEquals(0, mapped?.destination?.left)
        assertEquals(0, mapped?.destination?.top)
        assertEquals(4, mapped?.destination?.right)
        assertEquals(6, mapped?.destination?.bottom)
    }

    @Test fun rejectsInvalidSizesAndDegenerateBoxes() {
        assertNull(MaskGeometry.map(NormalizedBox(0, 0, 1000, 1000), 0, 100, 100, 100))
        assertNull(MaskGeometry.map(NormalizedBox(500, 0, 500, 1000), 100, 100, 100, 100))
        assertNull(MaskGeometry.map(NormalizedBox(0, 700, 1000, 600), 100, 100, 100, 100))
    }
}
