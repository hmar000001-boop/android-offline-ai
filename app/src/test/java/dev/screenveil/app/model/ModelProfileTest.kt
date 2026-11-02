package dev.screenveil.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {
    @Test fun balancedProfileUsesThreeSpecialistModelsAndNoGemma() {
        val ids = ModelProfile.BALANCED_SPECIALIZED.requiredModels.map { it.id }.toSet()
        assertEquals(3, ids.size)
        assertTrue(ModelIds.PERSON_DETECTOR in ids)
        assertTrue(ModelIds.OPEN_NSFW in ids)
        assertTrue(ModelIds.VIT_NSFW in ids)
        assertFalse(ModelIds.GEMMA in ids)
    }

    @Test fun fastProfileUsesOnlyPersonDetectorAndOneNsfwClassifier() {
        val ids = ModelProfile.FAST_SPECIALIZED.requiredModels.map { it.id }.toSet()
        assertEquals(setOf(ModelIds.PERSON_DETECTOR, ModelIds.OPEN_NSFW), ids)
        assertFalse(ModelIds.VIT_NSFW in ids)
        assertFalse(ModelIds.GEMMA in ids)
    }

    @Test fun swimwearAwareProfileIsVisibleButNotSelectableUntilItsTfliteArtifactIsApproved() {
        val profile = ModelProfile.SWIMWEAR_AWARE
        assertFalse(profile.selectable)
        assertTrue(profile.requiredModels.isEmpty())
        assertTrue(profile.availabilityNotice!!.contains("TFLite"))
        assertEquals(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.fromStored(profile.name))
    }

    @Test fun gemmaIsAnIndependentOptionalProfile() {
        assertEquals(listOf(ModelIds.GEMMA), ModelProfile.GEMMA_VISION.requiredModels.map { it.id })
    }

    @Test fun missingOrUnknownStoredProfileDefaultsToBalanced() {
        assertEquals(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.fromStored(null))
        assertEquals(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.fromStored("unknown"))
    }
}
