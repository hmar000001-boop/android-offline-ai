package dev.screenveil.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogPolicyTest {
    @Test fun onlyAdmittedPinnedArtifactsAreSelectable() {
        val models = SpecializedModelCatalog.all
        val ids = models.map { it.id }.toSet()
        assertEquals(
            setOf(ModelIds.GEMMA, ModelIds.PERSON_DETECTOR, ModelIds.OPEN_NSFW, ModelIds.VIT_NSFW, ModelIds.BODY_SEGMENTER),
            ids
        )
        assertTrue(ModelIds.BODY_SEGMENTER in ids)
        assertFalse(ids.any { it.contains("electblake") || it.contains("clothing") })
    }

    @Test fun everyAdmittedModelUsesHttpsAndPinnedSha256AndBoundedSize() {
        SpecializedModelCatalog.all.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://"))
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.minimumBytes > 0)
            assertTrue(model.maximumBytes >= model.minimumBytes)
        }
    }


    @Test fun personDetectorUsesThePinnedFourOutputUint8TfliteDeployment() {
        val detector = SpecializedModelCatalog.personDetector
        assertEquals("litert-efficientdet-lite0-uint8", detector.id)
        assertEquals("efficientdet_lite0_detection.tflite", detector.fileName)
        assertEquals(
            "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704",
            detector.sha256
        )
        assertEquals(4_561_184L, detector.minimumBytes)
        assertEquals(4_561_184L, detector.maximumBytes)
        assertTrue(detector.downloadUrl.contains("litert-community/efficientdet/resolve/971d935f3679eabbcce7b4d3733f351d403ff2b9/"))
        assertEquals("LiteRT Interpreter CPU (local)", detector.runtime)
    }

    @Test fun specialistProfilesNeverRequireGemma() {
        listOf(ModelProfile.BALANCED_SPECIALIZED, ModelProfile.FAST_SPECIALIZED).forEach { profile ->
            assertFalse(profile.requiredModels.any { it.id == ModelIds.GEMMA })
        }
    }

    @Test fun everyProfileRequiresTheBodySegmentationModel() {
        assertTrue(ModelProfile.BALANCED_SPECIALIZED.requiredModels.any { it.id == ModelIds.BODY_SEGMENTER })
        assertTrue(ModelProfile.FAST_SPECIALIZED.requiredModels.any { it.id == ModelIds.BODY_SEGMENTER })
        assertTrue(ModelProfile.GEMMA_VISION.requiredModels.any { it.id == ModelIds.BODY_SEGMENTER })
    }

    @Test fun optionalGemmaProfileIncludesGemmaAndSegmentationOnly() {
        assertEquals(setOf(ModelIds.GEMMA, ModelIds.BODY_SEGMENTER), ModelProfile.GEMMA_VISION.requiredModels.map { it.id }.toSet())
    }

    @Test fun unvalidatedOpenImagesCandidateIsNotInDownloadAllowList() {
        val models = SpecializedModelCatalog.all
        assertFalse(models.any { it.displayName.contains("Open Images V4") })
        assertFalse(ModelProfile.SWIMWEAR_AWARE.selectable)
        assertTrue(ModelProfile.SWIMWEAR_AWARE.requiredModels.isEmpty())
    }
}
