package dev.screenveil.app.overlay

import kotlin.math.max

/**
 * Effect strength relative to the redacted region. A fixed pixel radius is almost invisible on a
 * large region, so the radius / block size also scales with the region's longest side.
 */
object RedactionStrength {
    private const val MAX_BLUR_RADIUS = 120

    fun blurRadius(width: Int, height: Int, intensity: Int): Int {
        val base = max(2, intensity.coerceIn(4, 32) / 3)
        val relative = max(width, height) / 40
        return max(base, relative).coerceAtMost(MAX_BLUR_RADIUS)
    }

    fun pixelBlock(width: Int, height: Int, intensity: Int): Int {
        val base = (intensity.coerceIn(4, 32) / 2).coerceIn(3, 16)
        val relative = max(width, height) / 24
        return max(base, relative).coerceIn(3, 64)
    }
}
