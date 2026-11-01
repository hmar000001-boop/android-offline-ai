package dev.screenveil.app.model

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dev.screenveil.app.policy.ContentDecision
import org.json.JSONObject
import java.io.File

/**
 * Runs one isolated image inspection per conversation so screen frames do not accumulate
 * conversational history. Every Engine/Conversation call is made by the owning single-thread
 * executor in ScreenProtectionService.
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

    override fun initialize(modelFile: File) {
        check(engine == null) { "Model engine is already initialized" }
        require(modelFile.isFile) { "Gemma model file is missing" }

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
            // LiteRT-LM requires that an Engine whose initialize() threw is not closed.
            // GPU support varies by Android device/vendor, so try the supported CPU backend.
        }

        val cpuEngine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath
            )
        )
        cpuEngine.initialize()
        engine = cpuEngine
        activeBackend = "CPU"
    }

    override fun inspect(bitmap: Bitmap): DetectionResult {
        val currentEngine = checkNotNull(engine) { "Gemma is not initialized" }
        val imageFile = File(context.cacheDir, "screen-frame-${System.nanoTime()}.jpg")
        val imageForModel = resizedForVision(bitmap, 1280)
        try {
            imageFile.outputStream().buffered().use { output ->
                check(imageForModel.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "Could not encode screen frame" }
            }
            return try {
                sendImage(currentEngine, imageFile)
            } catch (gpuError: Throwable) {
                if (activeBackend != "GPU") throw gpuError
                // GPU delegates vary by vendor. A failed GPU inference is retried once on CPU.
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
                fallback.initialize()
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
        target.createConversation(ConversationConfig(maxOutputToken = 512)).use { conversation ->
            val response = conversation.sendMessage(
                Contents.of(
                    Content.ImageFile(imageFile.absolutePath),
                    Content.Text(PROMPT)
                )
            )
            DetectionResponseParser.parse(response.text)
        }

    override fun close() {
        engine?.close()
        engine = null
        activeBackend = "Not loaded"
    }

    companion object {
        private val PROMPT = """
            You are an on-device screen moderation and visual media-region detector.
            Inspect this full-screen screenshot. Find visible media regions only: photographs,
            video frames, video thumbnails, or image posts. Do not return text-only cards, icons,
            app chrome, buttons, or plain backgrounds. For each media region, return the smallest
            rectangle that contains that complete media item (not the entire app card).

            Classify each media region using exactly one decision:
            - allow: no swimwear, no suggestive imagery, no explicit nudity or sexual activity.
            - swimwear: any visible swimwear, including ordinary beach and sports swimwear.
            - suggestive: revealing/sexualized presentation, suggestive pose, or suggestive imagery.
            - explicit: explicit nudity or sexual activity.
            - uncertain: the media is visible but you cannot confidently determine its category.

            The screen owner's policy is to redact ALL swimwear, including ordinary beach scenes.
            Do not decide that ordinary swimwear is safe. If multiple media items are visible,
            return a separate rectangle for each. If no media regions are visible, return an empty
            regions array.

            Return ONLY a JSON object with this exact schema:
            {"regions":[{"box":[left,top,right,bottom],"media_type":"image|video|thumbnail",
            "decision":"allow|swimwear|suggestive|explicit|uncertain","is_swimwear":false}]}

            Coordinates must be integers from 0 to 1000, normalized against the entire supplied
            image: (0,0) is its top-left and (1000,1000) its bottom-right. Box order is
            left, top, right, bottom. Make boxes tight and do not invent regions. Do not include
            markdown, confidence scores, comments, or any text outside the JSON object.
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

object DetectionResponseParser {
    private const val MAX_REGIONS = 8

    /**
     * Fail-closed parsing: a malformed response is not interpreted as an all-safe screen.
     * Coordinates are normalized to 0..1000 and every accepted region must be non-trivial.
     */
    fun parse(raw: String): DetectionResult {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return DetectionResult(emptyList(), false, "No complete JSON object")
        }
        return try {
            val root = JSONObject(raw.substring(start, end + 1))
            val array = root.optJSONArray("regions")
                ?: return DetectionResult(emptyList(), false, "Missing regions array")
            if (array.length() > MAX_REGIONS) {
                return DetectionResult(emptyList(), false, "Too many media regions")
            }

            val parsed = ArrayList<DetectedMediaRegion>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: return DetectionResult(emptyList(), false, "Region $i is not an object")
                val boxArray = item.optJSONArray("box")
                    ?: return DetectionResult(emptyList(), false, "Region $i has no box")
                if (boxArray.length() != 4) {
                    return DetectionResult(emptyList(), false, "Region $i box must contain four coordinates")
                }
                val coords = (0..3).map { boxArray.optDouble(it, Double.NaN) }
                if (coords.any { !it.isFinite() || it < 0.0 || it > 1000.0 }) {
                    return DetectionResult(emptyList(), false, "Region $i coordinates are outside 0..1000")
                }
                val left = coords[0].toInt()
                val top = coords[1].toInt()
                val right = coords[2].toInt()
                val bottom = coords[3].toInt()
                if (right - left < 18 || bottom - top < 18) {
                    return DetectionResult(emptyList(), false, "Region $i box is too small or inverted")
                }

                val decision = when {
                    item.optBoolean("is_swimwear", false) -> ContentDecision.SWIMWEAR
                    else -> when (item.optString("decision", "uncertain").lowercase()) {
                        "allow", "safe" -> ContentDecision.ALLOW
                        "swimwear" -> ContentDecision.SWIMWEAR
                        "suggestive" -> ContentDecision.SUGGESTIVE
                        "explicit" -> ContentDecision.EXPLICIT
                        "uncertain" -> ContentDecision.UNCERTAIN
                        else -> ContentDecision.UNCERTAIN
                    }
                }
                val mediaType = item.optString("media_type", "image").lowercase()
                    .takeIf { it in setOf("image", "video", "thumbnail") }
                    ?: return DetectionResult(emptyList(), false, "Region $i has an unknown media type")
                parsed += DetectedMediaRegion(
                    NormalizedBox(left, top, right, bottom),
                    decision,
                    mediaType
                )
            }
            DetectionResult(parsed, true)
        } catch (error: Exception) {
            DetectionResult(emptyList(), false, "Invalid model JSON: ${error.javaClass.simpleName}")
        }
    }
}
