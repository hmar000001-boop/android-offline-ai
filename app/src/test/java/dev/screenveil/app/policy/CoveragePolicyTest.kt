package dev.screenveil.app.policy

import dev.screenveil.app.model.DetectedMediaRegion
import dev.screenveil.app.model.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun decisionCategoryNeverExemptsARegionFromThePixelCoverageGate() {
        listOf(ContentDecision.UNCERTAIN, ContentDecision.SUGGESTIVE, ContentDecision.EXPLICIT, ContentDecision.SWIMWEAR)
            .forEach { decision ->
                // Detector boxes are retained for segmentation; actual pixel area is enforced later.
                assertEquals(1, CoveragePolicy.limit(listOf(region(full, decision))).kept.size)
                assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
                    CoveragePolicy.requireRegionCoverage(0.36)
                }
            }
    }

    @Test fun oneRegionCanCoverAtMostThirtyFivePercent() {
        CoveragePolicy.requireRegionCoverage(0.35)
        assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
            CoveragePolicy.requireRegionCoverage(0.350001)
        }
    }

    @Test fun combinedMasksCanCoverAtMostFortyFivePercent() {
        CoveragePolicy.requireTotalCoverage(0.45)
        assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
            CoveragePolicy.requireTotalCoverage(0.450001)
        }
    }

    @Test fun nonFiniteCoverageIsRejected() {
        assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
            CoveragePolicy.requireTotalCoverage(Double.NaN)
        }
        assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
            CoveragePolicy.requireRegionCoverage(Double.POSITIVE_INFINITY)
        }
    }

    @Test fun moreThanTwentyFourRegionsStopsInsteadOfSilentlyDroppingRegions() {
        val regions = (0..CoveragePolicy.MAX_REGIONS_PER_FRAME).map {
            region(NormalizedBox(10, 10, 20, 20), ContentDecision.EXPLICIT)
        }
        assertThrows(CoveragePolicy.CoverageLimitExceededException::class.java) {
            CoveragePolicy.limit(regions)
        }
    }

    @Test fun fiveMinuteTestSessionIsTheConfiguredMaximum() {
        assertEquals(300_000L, CoveragePolicy.MAX_PROTECTION_SESSION_MS)
    }
}
