package dev.screenveil.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.tensorflow.lite.DataType
import dev.screenveil.app.policy.ContentDecision
import dev.screenveil.app.policy.SpecialistDecisionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * Balanced/Fast on-device pipeline, independent of Gemma.
 *
 * Google EfficientDet-Lite0 locates people. Yahoo OpenNSFW and the optional ViT classifier
 * estimate general NSFW content. Neither classifier identifies swimwear or clothing categories.
 * People are EXPLICIT (high score), UNCERTAIN (moderate score) or PERSON_LOW_RISK (low score, not a
 * clothing verdict). PERSON_LOW_RISK is covered only when the user turns on strict mode.
 */
class SpecializedScreenModerationDetector : ScreenContentDetector, DiagnosticStageAware {
    @Volatile
    override var diagnosticStageObserver: ((stage: String, durationMs: Long, details: String?) -> Unit)? = null
    private var personDetector: LocalLiteRtCpuModel? = null
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

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        var nsfw: OrtSession? = null
        var vitNsfw: OrtSession? = null
        var person: LocalLiteRtCpuModel? = null
        try {
            nsfw = env.createSession(nsfwFile.absolutePath, options)
            if (vitNsfwFile != null) vitNsfw = env.createSession(vitNsfwFile.absolutePath, options)
            // The verified model is already stored in private app storage. LiteRT maps the file
            // directly; inference runs with the bundled CPU backend and no Task Vision JNI layer.
            person = LocalLiteRtCpuModel(
                modelFile = personFile,
                expectedInputShape = intArrayOf(1, EFFICIENTDET_INPUT_SIZE, EFFICIENTDET_INPUT_SIZE, 3),
                expectedOutputShapes = listOf(
                    intArrayOf(1, EFFICIENTDET_MODEL_MAX_DETECTIONS, 4),
                    intArrayOf(1, EFFICIENTDET_MODEL_MAX_DETECTIONS),
                    intArrayOf(1, EFFICIENTDET_MODEL_MAX_DETECTIONS),
                    intArrayOf(1)
                ),
                expectedInputDataType = DataType.UINT8
            )
            // Warm up on a synthetic mid-grey RGB image. This is not a screenshot and does not
            // affect user content or the detector's safety policy.
            val warmup = person.runUInt8(ByteArray(EFFICIENTDET_INPUT_SIZE * EFFICIENTDET_INPUT_SIZE * 3) { 127 })
            validateEfficientDetOutputs(warmup)
            ortEnvironment = env
            nsfwSession = nsfw
            vitNsfwSession = vitNsfw
            personDetector = person
            activeBackend = if (vitNsfw != null) {
                "LiteRT CPU EfficientDet-Lite0 + ONNX OpenNSFW + ViT-Tiny NSFW (local)"
            } else {
                "LiteRT CPU EfficientDet-Lite0 + ONNX OpenNSFW (local)"
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
            detectPeople(bitmap, peopleDetector).also { found ->
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

    /**
     * Runs the approved 320x320 EfficientDet-Lite0 uint8 TensorFlow Hub TFLite artifact.
     * Its graph includes TFLite_Detection_PostProcess and returns four tensors:
     * boxes [ymin,xmin,ymax,xmax], zero-based class IDs, scores, then count. The first
     * model class index (0) is person. Boxes are normalized but may extend beyond [0,1];
     * the decoder clips intersections to the frame. The COCO-evaluation +1 offset is not used.
     */
    private fun detectPeople(bitmap: Bitmap, model: LocalLiteRtCpuModel): List<RectF> {
        check(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Cannot run EfficientDet on an invalid screenshot bitmap"
        }
        val resized = Bitmap.createScaledBitmap(
            bitmap, EFFICIENTDET_INPUT_SIZE, EFFICIENTDET_INPUT_SIZE, true
        )
        try {
            val pixels = IntArray(EFFICIENTDET_INPUT_SIZE * EFFICIENTDET_INPUT_SIZE)
            resized.getPixels(pixels, 0, EFFICIENTDET_INPUT_SIZE, 0, 0,
                EFFICIENTDET_INPUT_SIZE, EFFICIENTDET_INPUT_SIZE)
            val input = ByteArray(pixels.size * 3)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val offset = index * 3
                // The pinned TensorFlow Hub TFLite deployment consumes raw RGB uint8 pixels [0,255].
                input[offset] = (pixel ushr 16 and 0xff).toByte()
                input[offset + 1] = (pixel ushr 8 and 0xff).toByte()
                input[offset + 2] = (pixel and 0xff).toByte()
            }
            val outputs = model.runUInt8(input)
            validateEfficientDetOutputs(outputs)
            val boxes = outputs[0]
            val classIds = outputs[1]
            val scores = outputs[2]
            val count = outputs[3][0].roundToInt().coerceIn(0, scores.size)
            val found = ArrayList<RectF>(minOf(count, MAX_PERSON_DETECTIONS))
            for (index in 0 until count) {
                val classIndex = EfficientDetClassDecoder.validateRawClassIndex(classIds[index])
                val score = scores[index]
                require(score.isFinite() && score in 0f..1f) {
                    "EfficientDet returned an invalid confidence score at detection $index"
                }
                if (classIndex != EfficientDetClassDecoder.PERSON_CLASS_INDEX ||
                    score < PERSON_SCORE_THRESHOLD
                ) continue

                val offset = index * 4
                val pixelBounds = EfficientDetBoxDecoder.toPixelBounds(
                    output = boxes,
                    offset = offset,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    minimumSide = MIN_PERSON_CROP_SIDE
                ) ?: continue
                found += RectF(
                    pixelBounds.left, pixelBounds.top, pixelBounds.right, pixelBounds.bottom
                )
                if (found.size >= MAX_PERSON_DETECTIONS) break
            }
            return found
        } finally {
            if (resized !== bitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun validateEfficientDetOutputs(outputs: List<FloatArray>) {
        require(outputs.size == 4) { "EfficientDet must return four output tensors, found ${outputs.size}" }
        require(outputs[0].isNotEmpty() && outputs[0].size % 4 == 0) {
            "EfficientDet boxes tensor has an unexpected element count: ${outputs[0].size}"
        }
        val detectionCount = outputs[0].size / 4
        require(detectionCount == EFFICIENTDET_MODEL_MAX_DETECTIONS &&
            outputs[1].size == detectionCount && outputs[2].size == detectionCount && outputs[3].size == 1
        ) {
            "EfficientDet output tensor lengths do not match the pinned [1,25,4]/[1,25]/[1,25]/[1] contract"
        }
        val count = outputs[3][0]
        require(count.isFinite() && count >= 0f && count <= detectionCount.toFloat() &&
            abs(count - count.roundToInt()) < 0.01f
        ) {
            "EfficientDet returned an invalid detection count"
        }
        val activeCount = count.roundToInt()
        for (index in 0 until activeCount) {
            val offset = index * 4
            EfficientDetBoxDecoder.validateRawBox(outputs[0], offset)
            EfficientDetClassDecoder.validateRawClassIndex(outputs[1][index])
            require(outputs[2][index].isFinite() && outputs[2][index] in 0f..1f) {
                "EfficientDet returned an invalid confidence score at detection $index"
            }
        }
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
        private const val EFFICIENTDET_INPUT_SIZE = 320
        private const val EFFICIENTDET_MODEL_MAX_DETECTIONS = 25
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
