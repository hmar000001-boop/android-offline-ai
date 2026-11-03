package dev.screenveil.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.segmenter.ImageSegmenter
import org.tensorflow.lite.task.vision.segmenter.OutputType
import java.io.File
import java.nio.ByteBuffer

/**
 * Produces a frame-aligned binary mask for body-skin and clothing pixels.
 * The mask is deliberately not a moderation classifier: the detector decides whether a region
 * should be redacted; this class only limits the rendered effect to the selected human pixels.
 */
interface HumanBodySegmenter : AutoCloseable {
    fun initialize(context: Context, modelFiles: ModelFiles)
    fun segment(frame: Bitmap): BodySegmentationMask
    override fun close()
}

/**
 * Immutable binary mask aligned to the original screenshot. Values are 0 for transparent and 1
 * for redacted body/clothing pixels. It is expanded by a small radius in model-mask coordinates
 * to reduce leakage around the coarse 256x256 model boundary.
 */
class BodySegmentationMask private constructor(
    val width: Int,
    val height: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    private val selected: ByteArray,
    private val contentLeft: Int,
    private val contentTop: Int,
    private val contentWidth: Int,
    private val contentHeight: Int
) {
    val selectedPixels: Int = selected.count { it.toInt() != 0 }

    init {
        require(width > 0 && height > 0 && frameWidth > 0 && frameHeight > 0)
        require(selected.size == width * height)
        require(contentLeft >= 0 && contentTop >= 0 && contentWidth > 0 && contentHeight > 0)
        require(contentLeft + contentWidth <= width && contentTop + contentHeight <= height)
    }

    fun protectsFramePixel(x: Int, y: Int): Boolean {
        if (x !in 0 until frameWidth || y !in 0 until frameHeight) return false
        // Map the original screenshot into the exact non-letterboxed model input rectangle.
        val maskX = contentLeft + ((x.toLong() * contentWidth) / frameWidth).toInt()
        val maskY = contentTop + ((y.toLong() * contentHeight) / frameHeight).toInt()
        if (maskX !in contentLeft until contentLeft + contentWidth ||
            maskY !in contentTop until contentTop + contentHeight
        ) return false
        return selected[maskY * width + maskX].toInt() != 0
    }

    companion object {
        const val BACKGROUND = 0
        const val HAIR = 1
        const val BODY_SKIN = 2
        const val FACE_SKIN = 3
        const val CLOTHES = 4
        const val ACCESSORIES = 5

        /** Builds the production mask from the model's uint8 category image. */
        fun fromCategoryMask(
            width: Int,
            height: Int,
            frameWidth: Int,
            frameHeight: Int,
            categoryBuffer: ByteBuffer,
            dilationRadius: Int = 2,
            contentLeft: Int = 0,
            contentTop: Int = 0,
            contentWidth: Int = width,
            contentHeight: Int = height
        ): BodySegmentationMask {
            require(width in 1..1024 && height in 1..1024) { "Invalid segmentation output dimensions" }
            require(frameWidth > 0 && frameHeight > 0) { "Invalid source frame dimensions" }
            val required = width * height
            val input = categoryBuffer.duplicate().apply { rewind() }
            require(input.remaining() >= required) {
                "Segmentation category mask is truncated: expected $required bytes, found ${input.remaining()}"
            }
            val raw = ByteArray(required)
            input.get(raw)
            require(contentLeft >= 0 && contentTop >= 0 && contentWidth > 0 && contentHeight > 0 &&
                contentLeft + contentWidth <= width && contentTop + contentHeight <= height
            ) { "Invalid segmentation letterbox content rectangle" }
            val selected = ByteArray(required)
            for (index in raw.indices) {
                val x = index % width
                val y = index / width
                val insideContent = x in contentLeft until contentLeft + contentWidth &&
                    y in contentTop until contentTop + contentHeight
                val category = raw[index].toInt() and 0xff
                if (insideContent && (category == BODY_SKIN || category == CLOTHES)) selected[index] = 1
            }
            val dilated = dilate(selected, width, height, dilationRadius.coerceIn(0, 4))
            // Dilation must never bleed into the model's padding bars.
            for (y in 0 until height) for (x in 0 until width) {
                if (x !in contentLeft until contentLeft + contentWidth ||
                    y !in contentTop until contentTop + contentHeight
                ) dilated[y * width + x] = 0
            }
            return BodySegmentationMask(
                width, height, frameWidth, frameHeight, dilated,
                contentLeft, contentTop, contentWidth, contentHeight
            )
        }

        /** Deterministic all-body mask used only by instrumentation safety tests. */
        fun syntheticFullFrame(frameWidth: Int, frameHeight: Int): BodySegmentationMask {
            val width = 64
            val height = 64
            val categories = ByteArray(width * height) { BODY_SKIN.toByte() }
            return BodySegmentationMask(width, height, frameWidth, frameHeight, categories, 0, 0, width, height)
        }

        /** Deterministic synthetic body mask used only with injected debug test detectors. */
        fun synthetic(frameWidth: Int, frameHeight: Int): BodySegmentationMask {
            val width = 64
            val height = 64
            val categories = ByteArray(width * height)
            val centerX = width / 2.0
            val centerY = height / 2.0
            val radiusX = width * 0.19
            val radiusY = height * 0.38
            for (y in 0 until height) for (x in 0 until width) {
                val nx = (x - centerX) / radiusX
                val ny = (y - centerY) / radiusY
                if (nx * nx + ny * ny <= 1.0) categories[y * width + x] = BODY_SKIN.toByte()
            }
            return BodySegmentationMask(
                width, height, frameWidth, frameHeight,
                dilate(categories.map { if (it.toInt() != 0) 1 else 0 }.toByteArray(), width, height, 2),
                0, 0, width, height
            )
        }

        private fun dilate(input: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
            if (radius == 0) return input
            val output = input.copyOf()
            for (y in 0 until height) for (x in 0 until width) {
                if (input[y * width + x].toInt() == 0) continue
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > radius * radius) continue
                    val px = x + dx
                    val py = y + dy
                    if (px in 0 until width && py in 0 until height) output[py * width + px] = 1
                }
            }
            return output
        }
    }
}

/** Google MediaPipe SelfieMulticlass 256x256, executed locally through TFLite Task Vision. */
class TfliteHumanBodySegmenter : HumanBodySegmenter {
    private companion object { const val MODEL_INPUT_SIZE = 256 }
    private var segmenter: ImageSegmenter? = null

    override fun initialize(context: Context, modelFiles: ModelFiles) {
        check(segmenter == null) { "Human segmenter is already initialized" }
        val file: File = modelFiles.require(ModelIds.BODY_SEGMENTER)
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setOutputType(OutputType.CATEGORY_MASK)
            .setNumThreads(2)
            .build()
        segmenter = ImageSegmenter.createFromFileAndOptions(file, options)
    }

    override fun segment(frame: Bitmap): BodySegmentationMask {
        check(!frame.isRecycled && frame.width > 0 && frame.height > 0) { "Cannot segment an invalid screenshot" }
        val active = checkNotNull(segmenter) { "Human segmenter is not initialized" }
        // The model has a square 256x256 input. Explicitly letterbox the full screenshot instead
        // of relying on undocumented internal crop/resize defaults. The non-padding rectangle is
        // retained and used when mapping category pixels back to screenshot coordinates.
        val inputSize = MODEL_INPUT_SIZE
        val scale = minOf(inputSize.toDouble() / frame.width, inputSize.toDouble() / frame.height)
        val contentWidth = (frame.width * scale).toInt().coerceIn(1, inputSize)
        val contentHeight = (frame.height * scale).toInt().coerceIn(1, inputSize)
        val contentLeft = (inputSize - contentWidth) / 2
        val contentTop = (inputSize - contentHeight) / 2
        val scaled = Bitmap.createScaledBitmap(frame, contentWidth, contentHeight, true)
        val modelInput = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        try {
            Canvas(modelInput).apply {
                drawColor(Color.BLACK)
                drawBitmap(
                    scaled,
                    null,
                    android.graphics.Rect(contentLeft, contentTop, contentLeft + contentWidth, contentTop + contentHeight),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
            val results = active.segment(TensorImage.fromBitmap(modelInput))
            check(results.size == 1) { "Expected one selfie segmentation mask, got ${results.size}" }
            val categoryMask = results.single().masks.singleOrNull()
                ?: error("Selfie segmentation did not return exactly one category mask")
            check(categoryMask.width == inputSize && categoryMask.height == inputSize) {
                "Unexpected category mask dimensions ${categoryMask.width}x${categoryMask.height}; expected ${inputSize}x${inputSize}"
            }
            return BodySegmentationMask.fromCategoryMask(
                width = categoryMask.width,
                height = categoryMask.height,
                frameWidth = frame.width,
                frameHeight = frame.height,
                categoryBuffer = categoryMask.buffer,
                dilationRadius = 2,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentWidth = contentWidth,
                contentHeight = contentHeight
            )
        } finally {
            if (!scaled.isRecycled && scaled !== frame) scaled.recycle()
            if (!modelInput.isRecycled) modelInput.recycle()
        }
    }

    override fun close() {
        runCatching { segmenter?.close() }
        segmenter = null
    }
}

/** Used only for the synthetic instrumentation detector; production never instantiates this class. */
class SyntheticHumanBodySegmenter : HumanBodySegmenter {
    private var frameWidth = 0
    private var frameHeight = 0

    override fun initialize(context: Context, modelFiles: ModelFiles) = Unit

    override fun segment(frame: Bitmap): BodySegmentationMask =
        BodySegmentationMask.synthetic(frame.width, frame.height)

    override fun close() = Unit
}
