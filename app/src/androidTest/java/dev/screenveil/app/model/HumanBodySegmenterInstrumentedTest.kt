package dev.screenveil.app.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the exact pinned Google model on-device in the emulator test APK. */
@RunWith(AndroidJUnit4::class)
class HumanBodySegmenterInstrumentedTest {
    @Test
    fun pinnedGoogleSelfieMulticlassModelLoadsAndReturnsFrameAlignedMask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val modelFile = File(appContext.cacheDir, "screenveil-test-selfie-multiclass.tflite")
        val frame = Bitmap.createBitmap(540, 1170, Bitmap.Config.ARGB_8888)
        val segmenter = TfliteHumanBodySegmenter()
        try {
            // A simple non-photographic human silhouette makes the model input deterministic.
            // The smoke test requires a non-empty body/clothing intersection but does not claim
            // that synthetic-shape quality predicts performance on real phone screenshots.
            Canvas(frame).apply {
                drawColor(Color.rgb(180, 190, 205))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 170, 135) }
                drawCircle(270f, 250f, 75f, paint)
                paint.color = Color.rgb(35, 80, 145)
                drawRoundRect(165f, 330f, 375f, 790f, 55f, 55f, paint)
                paint.color = Color.rgb(215, 170, 135)
                drawRoundRect(115f, 360f, 165f, 760f, 22f, 22f, paint)
                drawRoundRect(375f, 360f, 425f, 760f, 22f, 22f, paint)
                paint.color = Color.rgb(45, 45, 55)
                drawRoundRect(190f, 770f, 255f, 1080f, 25f, 25f, paint)
                drawRoundRect(285f, 770f, 350f, 1080f, 25f, 25f, paint)
            }
            testContext.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Pinned segmentation model asset is missing or empty", modelFile.isFile && modelFile.length() > 0L)
            segmenter.initialize(appContext, ModelFiles(mapOf(ModelIds.BODY_SEGMENTER to modelFile)))
            // Exercise the production path: segment the detected-person crop while keeping
            // returned coordinates aligned to the original 540x1170 screenshot.
            val detectedPerson = Rect(90, 220, 450, 1120)
            val mask = segmenter.segment(frame, detectedPerson)

            assertEquals(256, mask.width)
            assertEquals(256, mask.height)
            assertEquals(frame.width, mask.frameWidth)
            assertEquals(frame.height, mask.frameHeight)
            assertTrue("Mask pixel count must be within the model output bounds", mask.selectedPixels in 0..(256 * 256))
            assertTrue("Pixels outside the segmented detector crop must remain transparent",
                !mask.protectsFramePixel(detectedPerson.left - 1, detectedPerson.centerY()) &&
                    !mask.protectsFramePixel(detectedPerson.right, detectedPerson.centerY()))
            assertTrue("The cropped mask must map into the original detected-person rectangle",
                mask.selectedPixelsInside(detectedPerson.left, detectedPerson.top, detectedPerson.right, detectedPerson.bottom) > 0)
        } finally {
            runCatching { segmenter.close() }
            if (!frame.isRecycled) frame.recycle()
            modelFile.delete()
        }
    }

    private companion object {
        const val MODEL_ASSET = "ci-models/selfie_multiclass_256x256.tflite"
    }
}
