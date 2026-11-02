package dev.screenveil.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialistDecisionPolicyTest {
    @Test fun bikiniAboveThresholdIsSwimwear() {
        assertEquals(ContentDecision.SWIMWEAR, SpecialistDecisionPolicy.clothingDecision("swim_bikini", 0.52f))
    }
    @Test fun bikiniBelowThresholdIsUncertain() {
        assertEquals(ContentDecision.UNCERTAIN, SpecialistDecisionPolicy.clothingDecision("swim_bikini", 0.519f))
    }
    @Test fun lingerieAboveThresholdIsSuggestive() {
        assertEquals(ContentDecision.SUGGESTIVE, SpecialistDecisionPolicy.clothingDecision("lingerie", 0.58f))
    }
    @Test fun nonTargetClassesNeverProveSafe() {
        listOf("denim_shorts", "maid", "nurse", "unknown").forEach {
            assertEquals(ContentDecision.UNCERTAIN, SpecialistDecisionPolicy.clothingDecision(it, 0.99f))
        }
    }
    @Test fun invalidConfidenceIsUncertain() {
        assertEquals(ContentDecision.UNCERTAIN, SpecialistDecisionPolicy.clothingDecision("swim_bikini", Float.NaN))
        assertEquals(ContentDecision.UNCERTAIN, SpecialistDecisionPolicy.clothingDecision("lingerie", Float.POSITIVE_INFINITY))
    }
    @Test fun nsfwThresholdIsInclusiveAndRejectsNonFiniteScores() {
        assertTrue(SpecialistDecisionPolicy.isNsfwFlagged(0.9f, 0.9f))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(Float.NaN, 0.8f))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(0.95f, Float.NaN))
    }
}
