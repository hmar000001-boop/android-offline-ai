package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.NormalizedBox

/**
 * Pure safeguards that keep an uncertain or low-trust detection from turning into a whole-window
 * blur. Confirmed categories (swimwear, suggestive, explicit) may legitimately cover a full-screen
 * photo; UNCERTAIN / PERSON_LOW_RISK regions may not.
 */
object CoveragePolicy {
    /** A single unconfirmed region at or above this share of the window is discarded. */
    const val LARGE_UNCONFIRMED_AREA = 0.70

    /** Union coverage at or above this share counts as "the whole screen is covered". */
    const val FULL_COVER_FRACTION = 0.85

    private const val GRID = 100
    private const val CELL = 1000 / GRID

    data class Limited(val kept: List<DetectedMediaRegion>, val dropped: Int)

    fun isConfirmed(decision: ContentDecision): Boolean =
        decision == ContentDecision.SWIMWEAR ||
            decision == ContentDecision.SUGGESTIVE ||
            decision == ContentDecision.EXPLICIT

    fun areaFraction(box: NormalizedBox): Double {
        val width = (box.right.coerceIn(0, 1000) - box.left.coerceIn(0, 1000)).coerceAtLeast(0)
        val height = (box.bottom.coerceIn(0, 1000) - box.top.coerceIn(0, 1000)).coerceAtLeast(0)
        return width * height / 1_000_000.0
    }

    /** Share of the window covered by the union of the boxes, on a 100x100 grid (rounded outward). */
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
            val column1 = (right + CELL - 1) / CELL
            val row0 = top / CELL
            val row1 = (bottom + CELL - 1) / CELL
            for (row in row0 until row1) for (column in column0 until column1) {
                covered[row * GRID + column] = true
            }
        }
        return covered.count { it } / (GRID * GRID).toDouble()
    }

    fun limit(regions: List<DetectedMediaRegion>): Limited {
        var kept = regions.filter {
            isConfirmed(it.decision) || areaFraction(it.box) < LARGE_UNCONFIRMED_AREA
        }
        var dropped = regions.size - kept.size
        while (unionFraction(kept.map { it.box }) >= FULL_COVER_FRACTION) {
            // Confirmed content may legitimately fill the window; only unconfirmed regions are trimmed.
            if (unionFraction(kept.filter { isConfirmed(it.decision) }.map { it.box }) >= FULL_COVER_FRACTION) break
            val victim = kept.filter { !isConfirmed(it.decision) }
                .maxByOrNull { areaFraction(it.box) } ?: break
            kept = kept.toMutableList().also { it.remove(victim) }
            dropped++
        }
        return Limited(kept, dropped)
    }
}
