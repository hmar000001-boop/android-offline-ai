package dev.screenveil.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.screenveil.app.policy.ContentDecision
import dev.screenveil.app.policy.SpecialistDecisionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-model, fully on-device alternative to Gemma.
 *
 * EfficientDet-Lite0 locates people. Electblake EfficientNetV2-S classifies person-centred
 * clothing crops. Yahoo OpenNSFW independently screens the full frame and each person crop.
 * The model outputs are deliberately kept separate: OpenNSFW is not a swimwear classifier.
 */
class SpecializedScreenModerationDetector(
    private val context: Context
) : ScreenContentDetector {
    private var personDetector: ObjectDetector? = null
    private var clothingSession: OrtSession? = null
    private var nsfwSession: OrtSession? = null
    private var vitNsfwSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null

    override var activeBackend: String = "Not loaded"
        private set

    override fun initialize(modelFiles: ModelFiles) {
        check(personDetector == null && clothingSession == null && nsfwSession == null && vitNsfwSession == null) {
            "Specialized detector is already initialized"
        }
        val personFile = modelFiles.require(ModelIds.PERSON_DETECTOR)
        val clothingFile = modelFiles.require(ModelIds.CLOTHING_CLASSIFIER)
        val nsfwFile = modelFiles.require(ModelIds.OPEN_NSFW)
        val vitNsfwFile = modelFiles.find(ModelIds.VIT_NSFW)

        val mapped = FileInputStream(personFile).channel.use { channel ->
            channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.nativeOrder())
        }
        val baseOptions = BaseOptions.builder().setModelAssetBuffer(mapped).build()
        val personOptions = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(PERSON_SCORE_THRESHOLD)
            .setMaxResults(MAX_PERSON_DETECTIONS)
            .setCategoryAllowlist(listOf("person"))
            .build()

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        var clothing: OrtSession? = null
        var nsfw: OrtSession? = null
        var vitNsfw: OrtSession? = null
        var person: ObjectDetector? = null
        try {
            clothing = env.createSession(clothingFile.absolutePath, options)
            nsfw = env.createSession(nsfwFile.absolutePath, options)
            if (vitNsfwFile != null) vitNsfw = env.createSession(vitNsfwFile.absolutePath, options)
            person = ObjectDetector.createFromOptions(context, personOptions)
            ortEnvironment = env
            clothingSession = clothing
            nsfwSession = nsfw
            vitNsfwSession = vitNsfw
            personDetector = person
            activeBackend = if (vitNsfw != null) {
                "MediaPipe EfficientDet-Lite0 + ONNX OpenNSFW + ViT-Tiny NSFW (offline)"
            } else {
                "MediaPipe EfficientDet-Lite0 + ONNX OpenNSFW (offline)"
            }
        } catch (error: Throwable) {
            runCatching { person?.close() }
            runCatching { clothing?.close() }
            runCatching { nsfw?.close() }
            runCatching { vitNsfw?.close() }
            throw error
        } finally {
            runCatching { options.close() }
        }
    }

    override fun inspect(bitmap: Bitmap): DetectionResult {
        val peopleDetector = checkNotNull(personDetector) { "Person detector is not initialized" }
        val clothing = checkNotNull(clothingSession) { "Clothing classifier is not initialized" }
        val nsfw = checkNotNull(nsfwSession) { "OpenNSFW classifier is not initialized" }
        val vitNsfw = vitNsfwSession // Optional in the Fast profile; required by Balanced profile catalog.
        val env = checkNotNull(ortEnvironment) { "ONNX Runtime is not initialized" }

        // Independent image-level classifiers inspect the entire frame. High thresholds reduce
        // interference from text and app chrome; they are starting heuristics, not benchmark claims.
        val fullFrameOpenNsfw = classifyOpenNsfw(bitmap, nsfw, env)
        val fullFrameVitNsfw = vitNsfw?.let { classifyVitNsfw(bitmap, it, env) }
        val fullFrameScore = max(fullFrameOpenNsfw, fullFrameVitNsfw ?: 0f)
        if (fullFrameScore >= FULL_FRAME_EXPLICIT_THRESHOLD) {
            return DetectionResult(
                regions = listOf(fullFrameRegion(ContentDecision.EXPLICIT)),
                parseSucceeded = true,
                diagnostic = "Full-frame NSFW ensemble flagged content (OpenNSFW=${formatScore(fullFrameOpenNsfw)}, ViT=${fullFrameVitNsfw?.let(::formatScore) ?: "not installed"})"
            )
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        val personResult = try {
            peopleDetector.detect(mpImage)
        } finally {
            runCatching { mpImage.close() }
        }
        val detections = personResult.detections()
            .mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                if (category.categoryName() != "person" || category.score() < PERSON_SCORE_THRESHOLD) return@mapNotNull null
                val box = detection.boundingBox()
                val left = floor(box.left.toDouble()).toInt().coerceIn(0, bitmap.width)
                val top = floor(box.top.toDouble()).toInt().coerceIn(0, bitmap.height)
                val right = ceil(box.right.toDouble()).toInt().coerceIn(0, bitmap.width)
                val bottom = ceil(box.bottom.toDouble()).toInt().coerceIn(0, bitmap.height)
                if (right - left < MIN_PERSON_CROP_SIDE || bottom - top < MIN_PERSON_CROP_SIDE) null
                else RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            }

        // Never silently drop detections when the screen is unusually crowded. A full-frame
        // uncertain result is safer than leaving people beyond the adapter limit unexamined.
        if (detections.size > MAX_PERSON_CROPS) {
            return DetectionResult(
                listOf(fullFrameRegion(ContentDecision.UNCERTAIN)),
                true,
                "More than $MAX_PERSON_CROPS people were detected; conservative full-frame mask used"
            )
        }

        if (detections.isEmpty() && vitNsfw != null) {
            val tiledRegions = scanScreenTilesWithVit(bitmap, vitNsfw, env)
            return DetectionResult(
                regions = tiledRegions,
                parseSucceeded = true,
                diagnostic = "Balanced profile · no person boxes; ViT scanned up to $MAX_SCREEN_TILES overlapping screen tiles (${tiledRegions.size} flagged)"
            )
        }

        val regions = ArrayList<DetectedMediaRegion>(detections.size)
        for (bounds in detections) {
            val left = bounds.left.toInt()
            val top = bounds.top.toInt()
            val width = bounds.width().toInt()
            val height = bounds.height().toInt()
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            val decision = try {
                val openNsfwScore = classifyOpenNsfw(crop, nsfw, env)
                val vitNsfwScore = vitNsfw?.let { classifyVitNsfw(crop, it, env) } ?: 0f
                val ensembleScore = max(openNsfwScore, vitNsfwScore)
                if (SpecialistDecisionPolicy.isNsfwFlagged(ensembleScore, PERSON_EXPLICIT_THRESHOLD)) {
                    ContentDecision.EXPLICIT
                } else {
                    classifyClothing(crop, clothing, env)
                }
            } finally {
                crop.recycle()
            }
            regions += DetectedMediaRegion(
                box = NormalizedBox(
                    (bounds.left / bitmap.width * 1000f).toInt().coerceIn(0, 1000),
                    (bounds.top / bitmap.height * 1000f).toInt().coerceIn(0, 1000),
                    (bounds.right / bitmap.width * 1000f).toInt().coerceIn(0, 1000),
                    (bounds.bottom / bitmap.height * 1000f).toInt().coerceIn(0, 1000)
                ),
                decision = decision,
                mediaType = "image"
            )
        }
        return DetectionResult(
            regions = regions,
            parseSucceeded = true,
            diagnostic = "Offline specialist ensemble · ${regions.size} person region(s); full-frame OpenNSFW=${formatScore(fullFrameOpenNsfw)}, ViT=${fullFrameVitNsfw?.let(::formatScore) ?: "not installed"}"
        )
    }

    private fun classifyClothing(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): ContentDecision {
        val input = ClothingImagePreprocessor.toNchw(bitmap)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 384, 256))
        try {
            val inputName = session.inputNames.firstOrNull() ?: error("Clothing ONNX model has no input")
            val outputName = session.outputNames.firstOrNull() ?: error("Clothing ONNX model has no output")
            val scores = session.run(mapOf(inputName to tensor)).use { result ->
                flattenFloatValues(result.get(outputName).value)
            }
            require(scores.size == CLOTHING_LABELS.size) {
                "Unexpected clothing classifier output length: ${scores.size}"
            }
            require(scores.all(Float::isFinite)) { "Clothing classifier returned non-finite logits" }
            val probabilities = softmax(scores)
            val bestIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: return ContentDecision.UNCERTAIN
            val confidence = probabilities[bestIndex]
            return SpecialistDecisionPolicy.clothingDecision(CLOTHING_LABELS[bestIndex], confidence)
        } finally {
            tensor.close()
        }
    }

    private fun classifyOpenNsfw(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): Float {
        val input = OpenNsfwImagePreprocessor.toNhwc(bitmap)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 224, 224, 3))
        try {
            val inputName = session.inputNames.firstOrNull() ?: error("OpenNSFW ONNX model has no input")
            val outputName = session.outputNames.firstOrNull() ?: error("OpenNSFW ONNX model has no output")
            val scores = session.run(mapOf(inputName to tensor)).use { result ->
                flattenFloatValues(result.get(outputName).value)
            }
            require(scores.size == 2) { "Unexpected OpenNSFW output length: ${scores.size}" }
            require(scores.all(Float::isFinite)) { "OpenNSFW returned non-finite scores" }
            // Published model output order is [P(sfw), P(nsfw)].
            return scores[1].coerceIn(0f, 1f)
        } finally {
            tensor.close()
        }
    }

    private fun classifyVitNsfw(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): Float {
        val input = VitNsfwImagePreprocessor.toNchw(bitmap)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, 384, 384))
        try {
            val inputName = session.inputNames.firstOrNull() ?: error("ViT NSFW ONNX model has no input")
            val outputName = session.outputNames.firstOrNull() ?: error("ViT NSFW ONNX model has no output")
            val logits = session.run(mapOf(inputName to tensor)).use { result ->
                flattenFloatValues(result.get(outputName).value)
            }
            require(logits.size == 2) { "Unexpected ViT NSFW output length: ${logits.size}" }
            require(logits.all(Float::isFinite)) { "ViT NSFW classifier returned non-finite logits" }
            // Model card specifies logits [NSFW, safe]; softmax index 0 is the NSFW probability.
            return softmax(logits)[0].also { require(it.isFinite()) { "ViT NSFW probability is non-finite" } }
        } finally {
            tensor.close()
        }
    }

    /**
     * When EfficientDet finds no people, scan a bounded 3x3 overlapping grid with the ViT.
     * This can catch media/cartoons or imperfect person detections, but cannot guarantee discovery
     * of every media region. Each positive tile is masked as a whole tile to avoid a false tight box.
     */
    private fun scanScreenTilesWithVit(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): List<DetectedMediaRegion> {
        val tileWidth = max(1, bitmap.width / 2)
        val tileHeight = max(1, bitmap.height / 2)
        val lefts = listOf(0, (bitmap.width - tileWidth) / 2, bitmap.width - tileWidth).distinct()
        val tops = listOf(0, (bitmap.height - tileHeight) / 2, bitmap.height - tileHeight).distinct()
        val results = ArrayList<DetectedMediaRegion>()
        for (top in tops) for (left in lefts) {
            val crop = Bitmap.createBitmap(bitmap, left, top, tileWidth, tileHeight)
            val score = try {
                classifyVitNsfw(crop, session, env)
            } finally {
                crop.recycle()
            }
            if (SpecialistDecisionPolicy.isNsfwFlagged(score, SCREEN_TILE_EXPLICIT_THRESHOLD)) {
                results += DetectedMediaRegion(
                    box = NormalizedBox(
                        (left.toFloat() / bitmap.width * 1000f).toInt().coerceIn(0, 1000),
                        (top.toFloat() / bitmap.height * 1000f).toInt().coerceIn(0, 1000),
                        ((left + tileWidth).toFloat() / bitmap.width * 1000f).toInt().coerceIn(0, 1000),
                        ((top + tileHeight).toFloat() / bitmap.height * 1000f).toInt().coerceIn(0, 1000)
                    ),
                    decision = ContentDecision.EXPLICIT,
                    mediaType = "image"
                )
            }
        }
        return results
    }

    private fun fullFrameRegion(decision: ContentDecision) = DetectedMediaRegion(
        box = NormalizedBox(0, 0, 1000, 1000),
        decision = decision,
        mediaType = "image"
    )

    override fun close() {
        runCatching { personDetector?.close() }
        runCatching { clothingSession?.close() }
        runCatching { nsfwSession?.close() }
        runCatching { vitNsfwSession?.close() }
        personDetector = null
        clothingSession = null
        nsfwSession = null
        vitNsfwSession = null
        ortEnvironment = null
        activeBackend = "Not loaded"
    }

    private fun formatScore(value: Float) = "%.3f".format(java.util.Locale.US, value)

    companion object {
        private const val PERSON_SCORE_THRESHOLD = 0.30f
        private const val MAX_PERSON_DETECTIONS = 40
        private const val MAX_PERSON_CROPS = 24
        private const val MIN_PERSON_CROP_SIDE = 24
        private const val FULL_FRAME_EXPLICIT_THRESHOLD = 0.94f
        private const val PERSON_EXPLICIT_THRESHOLD = 0.82f
        private const val SCREEN_TILE_EXPLICIT_THRESHOLD = 0.90f
        private const val MAX_SCREEN_TILES = 9
        private val CLOTHING_LABELS = listOf("denim_shorts", "lingerie", "maid", "nurse", "swim_bikini")
    }
}

private object OpenNsfwImagePreprocessor {
    /** Mirrors the published 256-square resize, 16px centre crop, JPEG round-trip, BGR means. */
    fun toNhwc(source: Bitmap): FloatArray {
        val square = Bitmap.createScaledBitmap(source, 256, 256, true)
        val centerCrop = Bitmap.createBitmap(square, 16, 16, 224, 224)
        val bytes = ByteArrayOutputStream()
        try {
            check(centerCrop.compress(Bitmap.CompressFormat.JPEG, 75, bytes)) { "OpenNSFW JPEG preprocessing failed" }
            val roundTripped = BitmapFactory.decodeStream(ByteArrayInputStream(bytes.toByteArray()))
                ?: error("OpenNSFW JPEG preprocessing could not decode its temporary image")
            try {
                val pixels = IntArray(224 * 224)
                roundTripped.getPixels(pixels, 0, 224, 0, 0, 224, 224)
                val output = FloatArray(224 * 224 * 3)
                for (index in pixels.indices) {
                    val pixel = pixels[index]
                    val offset = index * 3
                    output[offset] = ((pixel and 0xff) - 104).toFloat() // B
                    output[offset + 1] = (((pixel shr 8) and 0xff) - 117).toFloat() // G
                    output[offset + 2] = (((pixel shr 16) and 0xff) - 123).toFloat() // R
                }
                return output
            } finally {
                roundTripped.recycle()
            }
        } finally {
            square.recycle()
            centerCrop.recycle()
        }
    }
}

private object ClothingImagePreprocessor {
    private const val OUTPUT_WIDTH = 256
    private const val OUTPUT_HEIGHT = 384
    private const val PAD_VALUE = 127

    /** NCHW RGB, aspect-preserving resize, centre pad, [0,1] then mean/std 0.5 normalization. */
    fun toNchw(source: Bitmap): FloatArray {
        val scale = min(OUTPUT_WIDTH.toFloat() / source.width, OUTPUT_HEIGHT.toFloat() / source.height)
        val width = (source.width * scale).toInt().coerceIn(1, OUTPUT_WIDTH)
        val height = (source.height * scale).toInt().coerceIn(1, OUTPUT_HEIGHT)
        val pixels = resample(source, width, height, scale >= 1f)
        val output = FloatArray(3 * OUTPUT_WIDTH * OUTPUT_HEIGHT)
        val plane = OUTPUT_WIDTH * OUTPUT_HEIGHT
        val padding = ((PAD_VALUE / 255f) - 0.5f) / 0.5f
        output.fill(padding)
        val offsetX = (OUTPUT_WIDTH - width) / 2
        val offsetY = (OUTPUT_HEIGHT - height) / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val position = (offsetY + y) * OUTPUT_WIDTH + offsetX + x
                output[position] = (((color shr 16) and 0xff) / 255f - 0.5f) / 0.5f
                output[plane + position] = (((color shr 8) and 0xff) / 255f - 0.5f) / 0.5f
                output[2 * plane + position] = ((color and 0xff) / 255f - 0.5f) / 0.5f
            }
        }
        return output
    }

    private fun resample(source: Bitmap, targetWidth: Int, targetHeight: Int, upscale: Boolean): IntArray {
        val srcWidth = source.width
        val srcHeight = source.height
        val sourcePixels = IntArray(srcWidth * srcHeight)
        source.getPixels(sourcePixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)
        val destination = IntArray(targetWidth * targetHeight)
        val scaleX = srcWidth.toDouble() / targetWidth
        val scaleY = srcHeight.toDouble() / targetHeight
        for (dy in 0 until targetHeight) {
            for (dx in 0 until targetWidth) {
                destination[dy * targetWidth + dx] = if (upscale) {
                    bicubic(sourcePixels, srcWidth, srcHeight, (dx + 0.5) * scaleX - 0.5, (dy + 0.5) * scaleY - 0.5)
                } else {
                    areaAverage(sourcePixels, srcWidth, srcHeight, dx * scaleX, dy * scaleY, (dx + 1) * scaleX, (dy + 1) * scaleY)
                }
            }
        }
        return destination
    }

    private fun areaAverage(p: IntArray, sw: Int, sh: Int, left: Double, top: Double, right: Double, bottom: Double): Int {
        var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        val x0 = floor(left).toInt().coerceIn(0, sw - 1)
        val x1 = ceil(right).toInt().coerceIn(1, sw)
        val y0 = floor(top).toInt().coerceIn(0, sh - 1)
        val y1 = ceil(bottom).toInt().coerceIn(1, sh)
        for (y in y0 until y1) for (x in x0 until x1) {
            val weight = (min(right, x + 1.0) - max(left, x.toDouble())).coerceAtLeast(0.0) *
                (min(bottom, y + 1.0) - max(top, y.toDouble())).coerceAtLeast(0.0)
            if (weight <= 0.0) continue
            val c = p[y * sw + x]
            a += ((c ushr 24) and 0xff) * weight
            r += ((c shr 16) and 0xff) * weight
            g += ((c shr 8) and 0xff) * weight
            b += (c and 0xff) * weight
            total += weight
        }
        if (total == 0.0) return 0xff7f7f7f.toInt()
        return ((a / total).toInt().coerceIn(0,255) shl 24) or
            ((r / total).toInt().coerceIn(0,255) shl 16) or
            ((g / total).toInt().coerceIn(0,255) shl 8) or
            (b / total).toInt().coerceIn(0,255)
    }

    private fun bicubic(p: IntArray, sw: Int, sh: Int, x: Double, y: Double): Int {
        val xBase = floor(x).toInt()
        val yBase = floor(y).toInt()
        var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (j in -1..2) for (i in -1..2) {
            val weight = cubic(x - (xBase + i)) * cubic(y - (yBase + j))
            val c = p[yBase.plus(j).coerceIn(0, sh - 1) * sw + (xBase + i).coerceIn(0, sw - 1)]
            a += ((c ushr 24) and 0xff) * weight
            r += ((c shr 16) and 0xff) * weight
            g += ((c shr 8) and 0xff) * weight
            b += (c and 0xff) * weight
            total += weight
        }
        if (abs(total) < 1e-9) return 0xff7f7f7f.toInt()
        return ((a / total).toInt().coerceIn(0,255) shl 24) or
            ((r / total).toInt().coerceIn(0,255) shl 16) or
            ((g / total).toInt().coerceIn(0,255) shl 8) or
            (b / total).toInt().coerceIn(0,255)
    }

    private fun cubic(x: Double): Double {
        val a = -0.5
        val t = abs(x)
        return when {
            t <= 1.0 -> (a + 2) * t.pow(3) - (a + 3) * t.pow(2) + 1
            t < 2.0 -> a * t.pow(3) - 5 * a * t.pow(2) + 8 * a * t - 4 * a
            else -> 0.0
        }
    }
}

private object VitNsfwImagePreprocessor {
    private const val SIZE = 384

    /** RGB NCHW float32; direct 384x384 Catmull-Rom resize and (pixel/255 - 0.5) / 0.5. */
    fun toNchw(source: Bitmap): FloatArray {
        val sourcePixels = IntArray(source.width * source.height)
        source.getPixels(sourcePixels, 0, source.width, 0, 0, source.width, source.height)
        val output = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        val scaleX = source.width.toDouble() / SIZE
        val scaleY = source.height.toDouble() / SIZE
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val pixel = bicubic(
                sourcePixels, source.width, source.height,
                (x + 0.5) * scaleX - 0.5,
                (y + 0.5) * scaleY - 0.5
            )
            val position = y * SIZE + x
            output[position] = (((pixel shr 16) and 0xff) / 255f - 0.5f) / 0.5f
            output[plane + position] = (((pixel shr 8) and 0xff) / 255f - 0.5f) / 0.5f
            output[2 * plane + position] = ((pixel and 0xff) / 255f - 0.5f) / 0.5f
        }
        return output
    }

    private fun bicubic(p: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val xBase = floor(x).toInt()
        val yBase = floor(y).toInt()
        var red = 0.0; var green = 0.0; var blue = 0.0; var total = 0.0
        for (j in -1..2) for (i in -1..2) {
            val weight = cubic(x - (xBase + i)) * cubic(y - (yBase + j))
            val pixel = p[(yBase + j).coerceIn(0, height - 1) * width + (xBase + i).coerceIn(0, width - 1)]
            red += ((pixel shr 16) and 0xff) * weight
            green += ((pixel shr 8) and 0xff) * weight
            blue += (pixel and 0xff) * weight
            total += weight
        }
        if (abs(total) < 1e-9) return 0xff7f7f7f.toInt()
        return (0xff shl 24) or
            ((red / total).toInt().coerceIn(0, 255) shl 16) or
            ((green / total).toInt().coerceIn(0, 255) shl 8) or
            (blue / total).toInt().coerceIn(0, 255)
    }

    private fun cubic(x: Double): Double {
        val a = -0.5
        val t = abs(x)
        return when {
            t <= 1.0 -> (a + 2) * t.pow(3) - (a + 3) * t.pow(2) + 1
            t < 2.0 -> a * t.pow(3) - 5 * a * t.pow(2) + 8 * a * t - 4 * a
            else -> 0.0
        }
    }
}

private fun flattenFloatValues(value: Any?): FloatArray {
    val values = ArrayList<Float>()
    fun append(item: Any?) {
        when (item) {
            is Float -> values += item
            is Double -> values += item.toFloat()
            is Number -> values += item.toFloat()
            is Array<*> -> item.forEach(::append)
            is FloatArray -> item.forEach(values::add)
            is DoubleArray -> item.forEach { values += it.toFloat() }
            is IntArray -> item.forEach { values += it.toFloat() }
            else -> error("Unsupported ONNX output value: ${item?.javaClass?.name ?: "null"}")
        }
    }
    append(value)
    return values.toFloatArray()
}

private fun softmax(logits: FloatArray): FloatArray {
    val maxValue = logits.maxOrNull() ?: return FloatArray(0)
    val exp = FloatArray(logits.size) { kotlin.math.exp((logits[it] - maxValue).toDouble()).toFloat() }
    val sum = exp.sum().coerceAtLeast(1e-12f)
    return FloatArray(exp.size) { exp[it] / sum }
}
