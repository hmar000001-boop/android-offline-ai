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
            // A previous R9 run on this pinned artifact and emulator produced active detections;
            // keep the smoke test from passing vacuously without checking any active outputs.
            assertTrue("Pinned EfficientDet smoke input produced no active detections", actualCount > 0)
            assertEquals(
                "The model's first zero-based class index is the COCO person label",
                EfficientDetClassDecoder.PERSON_CLASS_INDEX,
                EfficientDetClassDecoder.validateRawClassIndex(0f)
            )
            for (index in 0 until actualCount) {
                val rawClassId = outputs[1][index]
                val score = outputs[2][index]
                assertTrue(
                    "Raw TFLite class IDs must be integral and zero-based in 0..89; " +
                        "detection=$index value=$rawClassId",
                    rawClassId.isFinite() && rawClassId in 0f..89f &&
                        abs(rawClassId - rawClassId.roundToInt()) < 0.01f
                )
                assertEquals(
                    "Raw class index validation must preserve the model's zero-based index",
                    rawClassId.roundToInt(),
                    EfficientDetClassDecoder.validateRawClassIndex(rawClassId)
                )
                assertTrue(
                    "Detection scores must be finite probabilities; detection=$index value=$score",
                    score.isFinite() && score in 0f..1f
                )
                val offset = index * 4
                val rawBox = outputs[0].copyOfRange(offset, offset + 4)
                assertTrue(
                    "Raw TFLite box contains non-finite coordinate values; " +
                        "detection=$index box=${rawBox.contentToString()}",
                    rawBox.all { it.isFinite() }
                )
                assertTrue(
                    "Raw TFLite box is inverted; expected [ymin,xmin,ymax,xmax]; " +
                        "detection=$index box=${rawBox.contentToString()}",
                    rawBox[0] <= rawBox[2] && rawBox[1] <= rawBox[3]
                )
                // A box may be partly or fully outside the frame. The production decoder must
                // clip intersections and discard non-intersecting/degenerate boxes safely.
                EfficientDetBoxDecoder.toPixelBounds(
                    output = outputs[0], offset = offset, imageWidth = 320, imageHeight = 320, minimumSide = 1
                )
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
