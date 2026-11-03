package dev.screenveil.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionStrengthTest {
    @Test fun smallRegionsUseTheConfiguredIntensity() {
        assertEquals(6, RedactionStrength.blurRadius(100, 100, 18))
        assertEquals(9, RedactionStrength.pixelBlock(100, 100, 18))
    }

    @Test fun largeRegionsGetProportionallyStrongerBlur() {
        assertEquals(30, RedactionStrength.blurRadius(1200, 800, 18))
        assertEquals(50, RedactionStrength.pixelBlock(1200, 800, 18))
    }

    @Test fun strengthIsBounded() {
        assertTrue(RedactionStrength.blurRadius(20_000, 20_000, 32) <= 120)
        assertTrue(RedactionStrength.pixelBlock(20_000, 20_000, 32) <= 64)
        assertTrue(RedactionStrength.blurRadius(1, 1, 0) >= 2)
    }
}
