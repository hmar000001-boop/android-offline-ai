package dev.screenveil.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {
    @Test fun balancedProfileUsesFourSpecialistModelsAndNoGemma() {
        val ids = ModelProfile.BALANCED_SPECIALIZED.requiredModels.map { it.id }.toSet()
        assertEquals(4, ids.size)
        assertTrue(ModelIds.PERSON_DETECTOR in ids)
        assertTrue(ModelIds.CLOTHING_CLASSIFIER in ids)
        assertTrue(ModelIds.OPEN_NSFW in ids)
        assertTrue(ModelIds.VIT_NSFW in ids)
        assertFalse(ModelIds.GEMMA in ids)
    }

    @Test fun fastProfileDoesNotRequireViTOrGemma() {
        val ids = ModelProfile.FAST_SPECIALIZED.requiredModels.map { it.id }.toSet()
        assertEquals(3, ids.size)
        assertFalse(ModelIds.VIT_NSFW in ids)
        assertFalse(ModelIds.GEMMA in ids)
    }

    @Test fun gemmaIsAnIndependentOptionalProfile() {
        assertEquals(listOf(ModelIds.GEMMA), ModelProfile.GEMMA_VISION.requiredModels.map { it.id })
    }

    @Test fun missingOrUnknownStoredProfileDefaultsToBalanced() {
        assertEquals(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.fromStored(null))
        assertEquals(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.fromStored("unknown"))
    }
}
