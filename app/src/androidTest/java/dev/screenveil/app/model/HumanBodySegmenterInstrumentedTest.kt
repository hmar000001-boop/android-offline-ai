package dev.screenveil.app.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
            // A simple non-photographic human silhouette provides deterministic non-empty input.
            // This test validates artifact/runtime/API plumbing, not segmentation accuracy.
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
            val mask = segmenter.segment(frame)

            assertEquals(256, mask.width)
            assertEquals(256, mask.height)
            assertEquals(frame.width, mask.frameWidth)
            assertEquals(frame.height, mask.frameHeight)
            assertTrue("Mask pixel count must be within the model output bounds", mask.selectedPixels in 0..(256 * 256))
            // Exercise the inverse mapping API at corners and centre. A mask may be empty for a
            // difficult synthetic drawing; empty candidate intersections are handled as a safe stop.
            listOf(0 to 0, 270 to 585, 539 to 1169).forEach { (x, y) ->
                mask.protectsFramePixel(x, y)
            }
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
