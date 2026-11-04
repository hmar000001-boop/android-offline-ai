package dev.screenveil.app.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.screenveil.app.policy.ContentDecision
import dev.screenveil.app.policy.SpecialistDecisionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Balanced/Fast on-device pipeline, independent of Gemma.
 *
 * Google EfficientDet-Lite0 locates people. Yahoo OpenNSFW and the optional ViT classifier
 * estimate general NSFW content. Neither classifier identifies swimwear or clothing categories.
 * People are EXPLICIT (high score), UNCERTAIN (moderate score) or PERSON_LOW_RISK (low score, not a
 * clothing verdict). PERSON_LOW_RISK is covered only when the user turns on strict mode.
 */
class SpecializedScreenModerationDetector(private val context: Context) : ScreenContentDetector, DiagnosticStageAware {
    @Volatile
    override var diagnosticStageObserver: ((stage: String, durationMs: Long, details: String?) -> Unit)? = null
    private var personDetector: ObjectDetector? = null
    private var nsfwSession: OrtSession? = null
    private var vitNsfwSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null

    override var activeBackend: String = "Not loaded"
        private set

    override fun initialize(modelFiles: ModelFiles) {
        check(personDetector == null && nsfwSession == null && vitNsfwSession == null) {
            "Specialized detector is already initialized"
        }
        val personFile = modelFiles.require(ModelIds.PERSON_DETECTOR)
        val nsfwFile = modelFiles.require(ModelIds.OPEN_NSFW)
        val vitNsfwFile = modelFiles.find(ModelIds.VIT_NSFW)

        // Standalone TensorFlow Lite Task Vision library; unlike MediaPipe Tasks, this runtime
        // does not include MediaPipe's documented API-usage metrics collection.
        val personOptions = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(PERSON_SCORE_THRESHOLD)
            .setMaxResults(MAX_PERSON_DETECTIONS)
            .setLabelAllowList(listOf("person"))
            .build()

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        var nsfw: OrtSession? = null
        var vitNsfw: OrtSession? = null
        var person: ObjectDetector? = null
        try {
            nsfw = env.createSession(nsfwFile.absolutePath, options)
            if (vitNsfwFile != null) vitNsfw = env.createSession(vitNsfwFile.absolutePath, options)
            person = ObjectDetector.createFromFileAndOptions(context, personFile.absolutePath, personOptions)
            ortEnvironment = env
            nsfwSession = nsfw
            vitNsfwSession = vitNsfw
            personDetector = person
            activeBackend = if (vitNsfw != null) {
                "TFLite EfficientDet-Lite0 + ONNX OpenNSFW + ViT-Tiny NSFW (local)"
            } else {
                "TFLite EfficientDet-Lite0 + ONNX OpenNSFW (local)"
            }
        } catch (error: Throwable) {
            runCatching { person?.close() }
            runCatching { nsfw?.close() }
            runCatching { vitNsfw?.close() }
            throw error
        } finally {
            runCatching { options.close() }
        }
    }

    override fun inspect(bitmap: Bitmap): DetectionResult {
        val peopleDetector = checkNotNull(personDetector) { "Person detector is not initialized" }
        val nsfw = checkNotNull(nsfwSession) { "OpenNSFW classifier is not initialized" }
        val vitNsfw = vitNsfwSession // Optional in the Fast profile; required by Balanced profile catalog.
        val env = checkNotNull(ortEnvironment) { "ONNX Runtime is not initialized" }
        val diagnosticsEnabled = diagnosticStageObserver != null

        // Do not classify the entire screenshot as one redaction region. A high global NSFW
        // score cannot justify obscuring system/app chrome or unrelated content. Content is
        // evaluated per detected person or per bounded tile below.
        val personDetectionStartedAt = if (diagnosticsEnabled) android.os.SystemClock.elapsedRealtime() else 0L
        val detections = try {
            peopleDetector.detect(TensorImage.fromBitmap(bitmap)).mapNotNull { detection ->
                val category = detection.categories.maxByOrNull { it.score } ?: return@mapNotNull null
                if (category.label != "person" || category.score < PERSON_SCORE_THRESHOLD) return@mapNotNull null
                val box = detection.boundingBox
                val left = floor(box.left.toDouble()).toInt().coerceIn(0, bitmap.width)
                val top = floor(box.top.toDouble()).toInt().coerceIn(0, bitmap.height)
                val right = ceil(box.right.toDouble()).toInt().coerceIn(0, bitmap.width)
                val bottom = ceil(box.bottom.toDouble()).toInt().coerceIn(0, bitmap.height)
                if (right - left < MIN_PERSON_CROP_SIDE || bottom - top < MIN_PERSON_CROP_SIDE) null
                else RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            }.also { found ->
                if (diagnosticsEnabled) recordDiagnosticStage(
                    "Google EfficientDet-Lite0 person detection",
                    android.os.SystemClock.elapsedRealtime() - personDetectionStartedAt,
                    "personBoxes=${found.size}; screenshot=${bitmap.width}x${bitmap.height}"
                )
            }
        } catch (error: Throwable) {
            if (diagnosticsEnabled) recordDiagnosticStage(
                "Google EfficientDet-Lite0 person detection",
                android.os.SystemClock.elapsedRealtime() - personDetectionStartedAt,
                "FAILED ${error.javaClass.simpleName}"
            )
            throw error
        }

        // Never silently drop people from a crowded screen: doing so could leave an unexamined
        // person visible while claiming that the frame was checked. Work is bounded to 24 crops;
        // above that count, stop protection rather than truncating candidates.
        if (detections.size > MAX_PERSON_CROPS) {
            return DetectionResult(
                regions = emptyList(),
                parseSucceeded = true,
                diagnostic = "Person detector returned ${detections.size} candidates; maximum safely classifiable count is $MAX_PERSON_CROPS",
                safetyViolation = true
            )
        }
        val selectedDetections = detections

        if (selectedDetections.isEmpty() && diagnosticsEnabled) {
            recordDiagnosticStage(
                "Yahoo OpenNSFW classification",
                0L,
                "SKIPPED: no person crops; OpenNSFW is evaluated per detected person"
            )
        }
        if (selectedDetections.isEmpty() && vitNsfw != null) {
            val tiledRegions = withDiagnosticStage(
                stage = "Marqo-derived ViT-Tiny screen tile scan",
                details = { "grid=up to 3x3; flaggedTiles=${it.size}" }
            ) {
                scanScreenTilesWithVit(bitmap, vitNsfw, env)
            }
            return DetectionResult(
                regions = tiledRegions,
                parseSucceeded = true,
                diagnostic = "Balanced profile · no person boxes; ViT scanned up to $MAX_SCREEN_TILES overlapping screen tiles (${tiledRegions.size} flagged)"
            )
        }
        if (selectedDetections.isEmpty() && vitNsfw == null && diagnosticsEnabled) {
            recordDiagnosticStage(
                "Marqo-derived ViT-Tiny screen tile scan",
                0L,
                "SKIPPED: this profile does not load the optional ViT model"
            )
        }

        val regions = ArrayList<DetectedMediaRegion>(selectedDetections.size)
        for (bounds in selectedDetections) {
            val left = bounds.left.toInt()
            val top = bounds.top.toInt()
            val width = bounds.width().toInt()
            val height = bounds.height().toInt()
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            val decision = try {
                val cropNumber = regions.size + 1
                val openNsfwScore = withDiagnosticStage(
                    stage = "Yahoo OpenNSFW classification",
                    details = { "personCrop=$cropNumber" }
                ) {
                    classifyOpenNsfw(crop, nsfw, env)
                }
                val vitNsfwScore = if (vitNsfw != null) {
                    withDiagnosticStage(
                        stage = "Marqo-derived ViT-Tiny classification",
                        details = { "personCrop=$cropNumber" }
                    ) {
                        classifyVitNsfw(crop, vitNsfw, env)
                    }
                } else 0f
                val ensembleScore = max(openNsfwScore, vitNsfwScore)
                when {
                    SpecialistDecisionPolicy.isNsfwFlagged(ensembleScore, PERSON_EXPLICIT_THRESHOLD) ->
                        ContentDecision.EXPLICIT
                    SpecialistDecisionPolicy.isNsfwFlagged(ensembleScore, PERSON_UNCERTAIN_THRESHOLD) ->
                        ContentDecision.UNCERTAIN
                    // Both classifiers scored the person low. There is no clothing/swimwear model, so
                    // this is not an ALLOW verdict: it is PERSON_LOW_RISK, covered only when the user
                    // enables "Cover every detected person". Covering every person by default made
                    // portraits, video calls and avatars blur the whole screen.
                    else -> ContentDecision.PERSON_LOW_RISK
                }
            } finally {
                crop.recycle()
            }
            val normalizedBox = DetectionBoxNormalizer.fromPixelBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            ) ?: error("Person detector returned an invalid box after clipping to the screenshot")
            regions += DetectedMediaRegion(
                box = normalizedBox,
                decision = decision,
                mediaType = "image"
            )
        }
        return DetectionResult(
            regions = regions,
            parseSucceeded = true,
            diagnostic = buildString {
                append("Offline NSFW ensemble · ${regions.size} person region(s); low-scoring people are PERSON_LOW_RISK (not a clothing verdict) and are covered only in strict mode.")
                append(" Whole-screen scoring is disabled to prevent global redaction.")
            }
        )
    }


    /**
     * Measures only when a one-shot trace is attached. Diagnostic callbacks are best-effort and
     * must never change the classifier result or exception path.
     */
    private fun <T> withDiagnosticStage(
        stage: String,
        details: (T) -> String,
        operation: () -> T
    ): T {
        if (diagnosticStageObserver == null) return operation()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val result = try {
            operation()
        } catch (error: Throwable) {
            runCatching {
                recordDiagnosticStage(
                    stage,
                    android.os.SystemClock.elapsedRealtime() - startedAt,
                    "FAILED ${error.javaClass.simpleName}"
                )
            }
            throw error
        }
        // Details formatting is also diagnostic work. A bug in it must never turn a successful
        // inference into a classifier failure.
        runCatching {
            recordDiagnosticStage(
                stage,
                android.os.SystemClock.elapsedRealtime() - startedAt,
                details(result)
            )
        }
        return result
    }

    private fun classifyOpenNsfw(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): Float {
        val input = OpenNsfwImagePreprocessor.toNhwc(bitmap)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 224, 224, 3))
        try {
            val inputName = session.inputNames.firstOrNull() ?: error("OpenNSFW ONNX model has no input")
            val outputName = session.outputNames.firstOrNull() ?: error("OpenNSFW ONNX model has no output")
            val scores = session.run(mapOf(inputName to tensor)).use { result ->
                flattenFloatValues(result.get(outputName).orElseThrow { IllegalStateException("ONNX output not found: $outputName") }.value)
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
                flattenFloatValues(result.get(outputName).orElseThrow { IllegalStateException("ONNX output not found: $outputName") }.value)
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
                // Round outward so a tile's edge pixels are never left uncovered.
                val tileBox = DetectionBoxNormalizer.fromPixelBounds(
                    left = left.toFloat(),
                    top = top.toFloat(),
                    right = (left + tileWidth).toFloat(),
                    bottom = (top + tileHeight).toFloat(),
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
                if (tileBox != null) {
                    results += DetectedMediaRegion(
                        box = tileBox,
                        decision = ContentDecision.EXPLICIT,
                        mediaType = "image"
                    )
                }
            }
        }
        return results
    }

    override fun close() {
        runCatching { personDetector?.close() }
        runCatching { nsfwSession?.close() }
        runCatching { vitNsfwSession?.close() }
        personDetector = null
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
        private const val PERSON_EXPLICIT_THRESHOLD = 0.82f
        // Uncalibrated: a person whose ensemble NSFW score is at least this is covered as UNCERTAIN.
        private const val PERSON_UNCERTAIN_THRESHOLD = 0.25f
        private const val SCREEN_TILE_EXPLICIT_THRESHOLD = 0.90f
        private const val MAX_SCREEN_TILES = 9
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
