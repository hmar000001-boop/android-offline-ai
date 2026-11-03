package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoveragePolicyTest {
    private fun region(box: NormalizedBox, decision: ContentDecision) =
        DetectedMediaRegion(box, decision, "image")

    private val full = NormalizedBox(0, 0, 1000, 1000)

    @Test fun unionOfFullBoxIsOneAndOfCentreQuarterIsAQuarter() {
        assertEquals(1.0, CoveragePolicy.unionFraction(listOf(full)), 1e-9)
        assertEquals(0.25, CoveragePolicy.unionFraction(listOf(NormalizedBox(250, 250, 750, 750))), 1e-9)
        assertEquals(0.0, CoveragePolicy.unionFraction(emptyList()), 1e-9)
    }

    @Test fun overlappingBoxesAreNotDoubleCounted() {
        val union = CoveragePolicy.unionFraction(
            listOf(NormalizedBox(0, 0, 500, 1000), NormalizedBox(250, 0, 750, 1000))
        )
        assertEquals(0.75, union, 1e-9)
    }

    @Test fun degenerateBoxesCoverNothing() {
        assertEquals(0.0, CoveragePolicy.unionFraction(listOf(NormalizedBox(500, 0, 500, 1000))), 1e-9)
    }

    @Test fun fullScreenUncertainRegionIsDropped() {
        val limited = CoveragePolicy.limit(listOf(region(full, ContentDecision.UNCERTAIN)))
        assertTrue(limited.kept.isEmpty())
        assertEquals(1, limited.dropped)
    }

    @Test fun fullScreenConfirmedExplicitRegionIsKept() {
        val limited = CoveragePolicy.limit(listOf(region(full, ContentDecision.EXPLICIT)))
        assertEquals(1, limited.kept.size)
        assertEquals(0, limited.dropped)
    }

    @Test fun smallUncertainRegionIsKept() {
        val limited = CoveragePolicy.limit(listOf(region(NormalizedBox(100, 100, 400, 500), ContentDecision.UNCERTAIN)))
        assertEquals(1, limited.kept.size)
    }

    @Test fun manyUncertainRegionsThatTogetherBlanketTheWindowAreTrimmed() {
        val strips = (0 until 5).map { index ->
            region(NormalizedBox(0, index * 200, 1000, index * 200 + 200), ContentDecision.PERSON_LOW_RISK)
        }
        val limited = CoveragePolicy.limit(strips)
        assertTrue(CoveragePolicy.unionFraction(limited.kept.map { it.box }) < CoveragePolicy.FULL_COVER_FRACTION)
        assertTrue(limited.dropped >= 1)
    }

    @Test fun confirmedRegionsAreNeverTrimmedEvenWhenTheyBlanketTheWindow() {
        val strips = (0 until 5).map { index ->
            region(NormalizedBox(0, index * 200, 1000, index * 200 + 200), ContentDecision.SWIMWEAR)
        }
        assertEquals(5, CoveragePolicy.limit(strips).kept.size)
    }
}
