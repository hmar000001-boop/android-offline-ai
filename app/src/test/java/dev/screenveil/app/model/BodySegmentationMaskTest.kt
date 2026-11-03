package dev.screenveil.app.model

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodySegmentationMaskTest {
    @Test fun selectsOnlyBodySkinAndClothingCategories() {
        val categories = byteArrayOf(
            BodySegmentationMask.BACKGROUND.toByte(),
            BodySegmentationMask.HAIR.toByte(),
            BodySegmentationMask.BODY_SKIN.toByte(),
            BodySegmentationMask.FACE_SKIN.toByte(),
            BodySegmentationMask.CLOTHES.toByte(),
            BodySegmentationMask.ACCESSORIES.toByte()
        )
        val mask = BodySegmentationMask.fromCategoryMask(
            width = 3,
            height = 2,
            frameWidth = 3,
            frameHeight = 2,
            categoryBuffer = ByteBuffer.wrap(categories),
            dilationRadius = 0
        )
        assertEquals(2, mask.selectedPixels)
        assertFalse(mask.protectsFramePixel(0, 0))
        assertFalse(mask.protectsFramePixel(1, 0))
        assertTrue(mask.protectsFramePixel(2, 0))
        assertFalse(mask.protectsFramePixel(0, 1))
        assertTrue(mask.protectsFramePixel(1, 1))
        assertFalse(mask.protectsFramePixel(2, 1))
    }

    @Test fun maskCoordinatesScaleToOriginalScreenshot() {
        val mask = BodySegmentationMask.fromCategoryMask(
            width = 2,
            height = 2,
            frameWidth = 100,
            frameHeight = 100,
            categoryBuffer = ByteBuffer.wrap(byteArrayOf(0, 4, 0, 2)),
            dilationRadius = 0
        )
        assertFalse(mask.protectsFramePixel(10, 10))
        assertTrue(mask.protectsFramePixel(75, 10))
        assertTrue(mask.protectsFramePixel(75, 75))
        assertFalse(mask.protectsFramePixel(10, 75))
        assertFalse(mask.protectsFramePixel(100, 100))
    }

    @Test fun letterboxMappingIgnoresPaddingAndMapsTheFullTallFrame() {
        // A 100x200 portrait screenshot is fitted into a 4x4 square as a centered 2x4 region.
        // Deliberately mark padding columns as body-skin: the mask builder must discard them.
        val categories = ByteArray(16) { index ->
            val x = index % 4
            if (x == 0 || x == 1 || x == 3) BodySegmentationMask.BODY_SKIN.toByte()
            else BodySegmentationMask.BACKGROUND.toByte()
        }
        val mask = BodySegmentationMask.fromCategoryMask(
            width = 4,
            height = 4,
            frameWidth = 100,
            frameHeight = 200,
            categoryBuffer = ByteBuffer.wrap(categories),
            dilationRadius = 0,
            contentLeft = 1,
            contentTop = 0,
            contentWidth = 2,
            contentHeight = 4
        )
        assertTrue(mask.protectsFramePixel(10, 50))
        assertFalse(mask.protectsFramePixel(90, 150))
        assertTrue(mask.protectsFramePixel(10, 150))
    }

    @Test fun truncatedCategoryBufferIsRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            BodySegmentationMask.fromCategoryMask(2, 2, 20, 20, ByteBuffer.wrap(byteArrayOf(2, 4)), 0)
        }
    }
}
