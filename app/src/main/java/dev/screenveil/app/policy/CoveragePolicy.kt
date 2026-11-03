package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.NormalizedBox

/**
 * Universal safety limits. These apply to every model and every decision category, including
 * EXPLICIT, SUGGESTIVE and SWIMWEAR. The limits are evaluated against final body-pixel masks by
 * OverlayController, not against detector rectangles.
 */
object CoveragePolicy {
    const val MAX_SINGLE_REGION_MASK_FRACTION = 0.35
    const val MAX_TOTAL_MASK_COVERAGE_FRACTION = 0.45
    const val COVERAGE_WATCHDOG_THRESHOLD = 0.35
    const val COVERAGE_WATCHDOG_DWELL_MS = 3_000L
    const val MAX_PROTECTION_SESSION_MS = 5 * 60_000L
    const val INFERENCE_TIMEOUT_MS = 8_000L
    const val SCREENSHOT_TIMEOUT_MS = 5_000L
    const val MAX_REGIONS_PER_FRAME = 24

    private const val GRID = 100
    private const val CELL = 1000 / GRID

    data class Limited(val kept: List<DetectedMediaRegion>, val dropped: Int)

    class CoverageLimitExceededException(message: String) : IllegalStateException(message)
    class BodySegmentationSafetyException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    fun areaFraction(box: NormalizedBox): Double {
        val width = (box.right.coerceIn(0, 1000) - box.left.coerceIn(0, 1000)).coerceAtLeast(0)
        val height = (box.bottom.coerceIn(0, 1000) - box.top.coerceIn(0, 1000)).coerceAtLeast(0)
        return width * height / 1_000_000.0
    }

    /** Share of the window covered by the union of the boxes; retained for diagnostics/tests only. */
    fun unionFraction(boxes: List<NormalizedBox>): Double {
        if (boxes.isEmpty()) return 0.0
        val covered = BooleanArray(GRID * GRID)
        for (box in boxes) {
            val left = box.left.coerceIn(0, 1000)
            val top = box.top.coerceIn(0, 1000)
            val right = box.right.coerceIn(0, 1000)
            val bottom = box.bottom.coerceIn(0, 1000)
            if (right <= left || bottom <= top) continue
            val column0 = left / CELL
            val column1 = ((right + CELL - 1) / CELL).coerceAtMost(GRID)
            val row0 = top / CELL
            val row1 = ((bottom + CELL - 1) / CELL).coerceAtMost(GRID)
            for (row in row0 until row1) for (column in column0 until column1) {
                covered[row * GRID + column] = true
            }
        }
        return covered.count { it } / (GRID * GRID).toDouble()
    }

    /**
     * Does not exempt "confirmed" decisions or shrink boxes. Actual pixel masks are checked later.
     * More than the parser's bounded 24 regions is a safety error; dropping arbitrary regions could
     * silently leave content unredacted.
     */
    fun limit(regions: List<DetectedMediaRegion>): Limited {
        if (regions.size > MAX_REGIONS_PER_FRAME) {
            throw CoverageLimitExceededException(
                "Model returned ${regions.size} redaction regions; maximum is $MAX_REGIONS_PER_FRAME"
            )
        }
        return Limited(regions, 0)
    }

    fun requireRegionCoverage(fraction: Double) {
        if (!fraction.isFinite() || fraction < 0.0 || fraction > MAX_SINGLE_REGION_MASK_FRACTION) {
            throw CoverageLimitExceededException(
                "One body/clothing mask would cover ${percent(fraction)} of the application window; maximum is ${percent(MAX_SINGLE_REGION_MASK_FRACTION)}"
            )
        }
    }

    fun requireTotalCoverage(fraction: Double) {
        if (!fraction.isFinite() || fraction < 0.0 || fraction > MAX_TOTAL_MASK_COVERAGE_FRACTION) {
            throw CoverageLimitExceededException(
                "Combined body/clothing masks would cover ${percent(fraction)} of the application window; maximum is ${percent(MAX_TOTAL_MASK_COVERAGE_FRACTION)}"
            )
        }
    }

    private fun percent(value: Double): String =
        if (!value.isFinite()) "invalid" else "%.1f%%".format(java.util.Locale.US, value * 100.0)
}
