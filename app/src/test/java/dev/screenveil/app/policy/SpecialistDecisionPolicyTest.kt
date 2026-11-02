package dev.screenveil.app.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialistDecisionPolicyTest {
    @Test fun nsfwThresholdIsInclusive() {
        assertTrue(SpecialistDecisionPolicy.isNsfwFlagged(0.9f, 0.9f))
        assertTrue(SpecialistDecisionPolicy.isNsfwFlagged(0.91f, 0.9f))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(0.899f, 0.9f))
    }

    @Test fun nonFiniteValuesNeverProducePositiveClassification() {
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(Float.NaN, 0.8f))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(Float.POSITIVE_INFINITY, 0.8f))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(0.95f, Float.NaN))
        assertFalse(SpecialistDecisionPolicy.isNsfwFlagged(0.95f, Float.POSITIVE_INFINITY))
    }
}
