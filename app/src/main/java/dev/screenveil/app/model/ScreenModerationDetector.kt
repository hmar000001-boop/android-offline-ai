package dev.screenveil.app.model

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.screenveil.app.policy.ContentDecision
import org.json.JSONObject
import java.io.File

/**
 * Runs one isolated image inspection per conversation so screen frames do not accumulate
 * conversational history. Engine and Conversation calls are confined to the owning worker thread.
 */
interface ScreenContentDetector : AutoCloseable {
    val activeBackend: String
    fun initialize(modelFile: File)
    fun inspect(bitmap: Bitmap): DetectionResult
    override fun close()
}

class ScreenModerationDetector(private val context: Context) : ScreenContentDetector {
    private var engine: Engine? = null
    override var activeBackend: String = "Not loaded"
        private set

    @OptIn(ExperimentalApi::class)
    override fun initialize(modelFile: File) {
        check(engine == null) { "Model engine is already initialized" }
        require(modelFile.isFile) { "Gemma model file is missing" }

        // Gemma 4 E2B + constrained JSON has an upstream MTP/speculative-decoding defect in
        // LiteRT-LM 0.18.0. Force it off before Engine creation, and again per conversation.
        // See the linked upstream issue in docs/GEMMA_STRUCTURED_OUTPUT.md.
        ExperimentalFlags.enableSpeculativeDecoding = false

        val gpuEngine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            gpuEngine.initialize()
            engine = gpuEngine
            activeBackend = "GPU"
            return
        } catch (_: Throwable) {
            // GPU delegates vary by vendor. Release the failed engine before creating a fresh CPU engine.
            runCatching { gpuEngine.close() }
        }

        val cpuEngine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            cpuEngine.initialize()
            engine = cpuEngine
            activeBackend = "CPU"
        } catch (error: Throwable) {
            runCatching { cpuEngine.close() }
            throw error
        }
    }

    override fun inspect(bitmap: Bitmap): DetectionResult {
        val currentEngine = checkNotNull(engine) { "Gemma is not initialized" }
        val imageFile = File(context.cacheDir, "screenveil-frame-${System.nanoTime()}.jpg")
        val imageForModel = resizedForVision(bitmap, MAX_IMAGE_SIDE)
        try {
            imageFile.outputStream().buffered().use { output ->
                check(imageForModel.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Could not encode screen frame"
                }
            }
            return try {
                sendImage(currentEngine, imageFile)
            } catch (gpuError: Throwable) {
                if (activeBackend != "GPU") throw gpuError
                // A vendor GPU delegate can fail after initialization. Retry once on CPU.
                runCatching { currentEngine.close() }
                engine = null
                val fallback = Engine(
                    EngineConfig(
                        modelPath = ModelInstaller.modelFile(context).absolutePath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                )
                try {
                    fallback.initialize()
                } catch (fallbackInitError: Throwable) {
                    runCatching { fallback.close() }
                    throw fallbackInitError
                }
                engine = fallback
                activeBackend = "CPU (fallback)"
                try {
                    sendImage(fallback, imageFile)
                } catch (fallbackError: Throwable) {
                    runCatching { fallback.close() }
                    engine = null
                    activeBackend = "Not loaded"
                    throw fallbackError
                }
            }
        } finally {
            // The temporary image is private app-cache input for LiteRT-LM and is deleted even
            // when inference throws or is cancelled by service shutdown.
            imageFile.delete()
            if (imageForModel !== bitmap) imageForModel.recycle()
        }
    }

    private fun resizedForVision(source: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSide) return source
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sendImage(target: Engine, imageFile: File): DetectionResult =
        target.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 7),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                enableResponseFormat = true,
                enableSpeculativeDecoding = false
            )
        ).use { conversation ->
            val response = conversation.sendMessage(
                Contents.of(
                    Content.ImageFile(imageFile.absolutePath),
                    Content.Text(PROMPT)
                ),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                responseFormat = ResponseFormat.json(RESPONSE_SCHEMA)
            )
            // Read explicit text content parts; do not depend on Message.toString() formatting.
            val responseText = response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            DetectionResponseParser.parse(responseText)
        }

    override fun close() {
        engine?.close()
        engine = null
        activeBackend = "Not loaded"
    }

    companion object {
        private const val MAX_IMAGE_SIDE = 1280
        private const val JPEG_QUALITY = 88
        private const val MAX_OUTPUT_TOKENS = 320

        /**
         * Deliberately uses a compact JSON Schema supported by LiteRT-LM 0.18.0's
         * ResponseFormat.json. Numeric range and rectangle geometry are revalidated in Kotlin.
         */
        private val RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "regions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "box": { "type": "array", "items": { "type": "integer" } },
                      "media_type": { "type": "string", "enum": ["image", "video", "thumbnail"] },
                      "decision": { "type": "string", "enum": ["allow", "swimwear", "suggestive", "explicit", "uncertain"] }
                    },
                    "required": ["box", "media_type", "decision"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["regions"],
              "additionalProperties": false
            }
        """.trimIndent()

        private val PROMPT = """
            Inspect this screenshot for visible photographic or video media. This includes a photo
            opened by itself in a Gallery/Photos viewer: if a photograph fills nearly the entire
            screen, it IS a media region and must be returned. Also find photos in feeds, galleries,
            browser pages, video players and thumbnails. Ignore text-only cards, app chrome, icons,
            buttons and plain backgrounds.

            For every media item, return the tight rectangle containing the visible image/video
            pixels. If the item is displayed full-screen, use a box close to [0,0,1000,1000]. If
            several media items are visible, return a separate rectangle for each. Do not omit an
            image just because it is the only image on screen or because viewer controls cover part
            of it.

            Classify each media item with exactly one decision:
            - allow: ordinary clothing and media without swimwear, suggestive presentation, explicit
              nudity or sexual activity.
            - swimwear: ANY swimwear, including ordinary beach photographs, bikinis, swimsuits,
              swim trunks, pool scenes and sports swimwear. The user's policy is to redact all of it.
            - suggestive: revealing or sexualized presentation, suggestive pose, or suggestive imagery.
            - explicit: explicit nudity or sexual activity.
            - uncertain: a visible media item whose category cannot be confidently determined.

            Never label swimwear as allow. When uncertain, choose uncertain. If no photographic or
            video media is visible, return an empty regions array.

            Each box is [left, top, right, bottom] as integer coordinates normalized from 0 to 1000
            against the full screenshot. The origin is the top-left. Return tight, ordered boxes with
            left < right and top < bottom. Do not invent regions or text. Return the JSON object
            required by the response schema and nothing else.
        """.trimIndent()
    }
}

data class NormalizedBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class DetectedMediaRegion(
    val box: NormalizedBox,
    val decision: ContentDecision,
    val mediaType: String
)

data class DetectionResult(
    val regions: List<DetectedMediaRegion>,
    val parseSucceeded: Boolean,
    val diagnostic: String? = null
)

/** Strict parser for the schema-constrained LiteRT-LM JSON response. */
object DetectionResponseParser {
    private const val MAX_REGIONS = 8
    private val ROOT_KEYS = setOf("regions")
    private val REGION_KEYS = setOf("box", "media_type", "decision")

    fun parse(raw: String): DetectionResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            return DetectionResult(emptyList(), false, "Model did not return a complete JSON object")
        }
        return try {
            val root = JSONObject(trimmed)
            if (root.keys().asSequence().toSet() != ROOT_KEYS) {
                return DetectionResult(emptyList(), false, "Unexpected or missing top-level JSON fields")
            }
            val array = root.getJSONArray("regions")
            if (array.length() > MAX_REGIONS) {
                return DetectionResult(emptyList(), false, "Too many media regions")
            }

            val parsed = ArrayList<DetectedMediaRegion>(array.length())
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                if (item.keys().asSequence().toSet() != REGION_KEYS) {
                    return DetectionResult(emptyList(), false, "Region $index has unexpected or missing fields")
                }
                val boxArray = item.getJSONArray("box")
                if (boxArray.length() != 4) {
                    return DetectionResult(emptyList(), false, "Region $index box must contain four coordinates")
                }
                val coords = IntArray(4)
                for (position in 0 until 4) {
                    val value = boxArray.get(position)
                    if (value !is Number) {
                        return DetectionResult(emptyList(), false, "Region $index contains a non-numeric coordinate")
                    }
                    val number = value.toDouble()
                    if (!number.isFinite() || number % 1.0 != 0.0 || number < 0.0 || number > 1000.0) {
                        return DetectionResult(emptyList(), false, "Region $index coordinates must be integers from 0 to 1000")
                    }
                    coords[position] = number.toInt()
                }
                val left = coords[0]
                val top = coords[1]
                val right = coords[2]
                val bottom = coords[3]
                if (right <= left || bottom <= top || right - left < 18 || bottom - top < 18) {
                    return DetectionResult(emptyList(), false, "Region $index box is inverted or too small")
                }

                val decision = when (item.getString("decision")) {
                    "allow" -> ContentDecision.ALLOW
                    "swimwear" -> ContentDecision.SWIMWEAR
                    "suggestive" -> ContentDecision.SUGGESTIVE
                    "explicit" -> ContentDecision.EXPLICIT
                    "uncertain" -> ContentDecision.UNCERTAIN
                    else -> return DetectionResult(emptyList(), false, "Region $index has an unknown decision")
                }
                val mediaType = item.getString("media_type")
                if (mediaType !in setOf("image", "video", "thumbnail")) {
                    return DetectionResult(emptyList(), false, "Region $index has an unknown media type")
                }
                parsed += DetectedMediaRegion(NormalizedBox(left, top, right, bottom), decision, mediaType)
            }
            DetectionResult(parsed, true)
        } catch (error: Exception) {
            DetectionResult(emptyList(), false, "Invalid structured model response: ${error.javaClass.simpleName}")
        }
    }
}
