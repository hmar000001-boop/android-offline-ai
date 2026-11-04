package dev.screenveil.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Runs the exact pinned uint8 EfficientDet-Lite0 detection artifact with LiteRT CPU on device. */
@RunWith(AndroidJUnit4::class)
class EfficientDetLiteRtInstrumentedTest {
    @Test
    fun rawTfliteDetectionPostProcessBoxOrderMapsYminXminYmaxXmaxCorrectly() {
        val box = EfficientDetBoxDecoder.toPixelBounds(
            output = floatArrayOf(0.10f, 0.20f, 0.90f, 0.70f),
            offset = 0,
            imageWidth = 1_000,
            imageHeight = 500,
            minimumSide = 1
        )
        assertTrue("Expected a valid decoded box", box != null)
        val decoded = checkNotNull(box)
        assertEquals(200f, decoded.left, 0f)
        assertEquals(50f, decoded.top, 0f)
        assertEquals(700f, decoded.right, 0f)
        assertEquals(450f, decoded.bottom, 0f)
    }

    @Test
    fun pinnedEfficientDetLite0RunsAndMatchesFourOutputDetectionPostProcessContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val appContext = instrumentation.targetContext
        val modelFile = File(appContext.cacheDir, "screenveil-test-efficientdet-lite0-detection.tflite")
        var runtime: LocalLiteRtCpuModel? = null
        try {
            testContext.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("Pinned EfficientDet model asset is missing or empty", modelFile.isFile && modelFile.length() > 0L)
            val activeRuntime = LocalLiteRtCpuModel(
                modelFile,
                expectedInputShape = intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3),
                expectedOutputShapes = listOf(
                    intArrayOf(1, 25, 4), intArrayOf(1, 25), intArrayOf(1, 25), intArrayOf(1)
                ),
                expectedInputDataType = DataType.UINT8
            )
            runtime = activeRuntime

            // Raw neutral-grey RGB uint8 input. This exercises native model load, CPU inference,
            // reusable direct buffers and the four-output postprocessed detection contract.
            val outputs = activeRuntime.runUInt8(ByteArray(INPUT_SIZE * INPUT_SIZE * 3) { 127 })
            assertEquals(4, outputs.size)
            assertTrue("Detection boxes must be [N,4]", outputs[0].isNotEmpty() && outputs[0].size % 4 == 0)
            val capacity = outputs[0].size / 4
            assertEquals("Pinned EfficientDet-Lite0 model maximum is 25 detections", 25, capacity)
            assertEquals(capacity, outputs[1].size)
            assertEquals(capacity, outputs[2].size)
            assertEquals(1, outputs[3].size)

            val count = outputs[3][0]
            assertTrue("Detection count must be finite", count.isFinite())
            assertTrue("Detection count must be integral", abs(count - count.roundToInt()) < 0.01f)
            assertTrue("Detection count must fit the output capacity", count in 0f..capacity.toFloat())
            val actualCount = count.roundToInt()
            for (index in 0 until actualCount) {
                val classId = outputs[1][index]
                val score = outputs[2][index]
                assertTrue("Class IDs must be integral and within one-based COCO category ID range", classId.isFinite() &&
                    classId in 1f..90f && abs(classId - classId.roundToInt()) < 0.01f)
                assertTrue("Detection scores must be finite probabilities", score.isFinite() && score in 0f..1f)
                val offset = index * 4
                val top = outputs[0][offset]
                val left = outputs[0][offset + 1]
                val bottom = outputs[0][offset + 2]
                val right = outputs[0][offset + 3]
                assertTrue("Raw TFLite boxes must be finite normalized ymin/xmin/ymax/xmax", listOf(
                    top, left, bottom, right
                ).all { it.isFinite() && it in 0f..1f } && top <= bottom && left <= right)
            }
        } finally {
            runtime?.close()
            modelFile.delete()
        }
    }

    private companion object {
        const val INPUT_SIZE = 320
        const val MODEL_ASSET = "ci-models/efficientdet_lite0_detection.tflite"
    }
}
