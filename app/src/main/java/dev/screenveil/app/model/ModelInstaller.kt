package dev.screenveil.app.model

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads only the fixed model catalog over HTTPS. Redirects are manually followed and every
 * hop must remain on an explicitly approved model-hosting domain. Inference code has no network
 * client and is not given a model URL.
 */
object ModelInstaller {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val BUFFER_SIZE = 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val REQUIRED_FREE_SPACE_MARGIN = 250_000_000L
    private const val USER_AGENT = "ScreenVeil-Android/1.6.8"

    // GitHub is limited to the exact hosts release downloads use. Wildcards remain only for
    // Hugging Face's own CDN domains; user-content GitHub subdomains are not approved.
    private val approvedDownloadHosts = setOf(
        "storage.googleapis.com",
        "huggingface.co",
        "hf.co",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    private fun isCatalogued(model: ApprovedModelArtifact): Boolean =
        SpecializedModelCatalog.all.any { approved -> approved == model }

    private fun requireCatalogued(model: ApprovedModelArtifact) {
        require(isCatalogued(model)) {
            "Model artifact is not present in ScreenVeil's fixed approval catalog: ${model.id}"
        }
    }

    fun modelFile(context: Context, model: ApprovedModelArtifact): File {
        requireCatalogued(model)
        return File(File(context.filesDir, "models"), model.fileName)
    }

    /** Backwards-compatible Gemma path. */
    fun modelFile(context: Context): File = modelFile(context, GemmaModelCatalog.selected)

    private fun markerFile(context: Context, model: ApprovedModelArtifact) =
        File(modelFile(context, model).parentFile, "${model.id}.verified")

    fun hasVerifiedMarker(context: Context, model: ApprovedModelArtifact): Boolean {
        if (!isCatalogued(model)) return false
        val file = modelFile(context, model)
        val marker = markerFile(context, model)
        return file.isFile && file.length() in model.minimumBytes..model.maximumBytes &&
            marker.isFile && marker.readText().trim().equals(model.sha256, ignoreCase = true)
    }

    fun hasVerifiedMarker(context: Context): Boolean =
        hasVerifiedMarker(context, GemmaModelCatalog.selected)

    fun hasProfileMarkers(context: Context, profile: ModelProfile): Boolean =
        profile.selectable && profile.requiredModels.isNotEmpty() &&
            profile.requiredModels.all { hasVerifiedMarker(context, it) }

    /** Remove retired model artifacts and markers that are incompatible with this catalog revision. */
    fun removeRetiredArtifacts(context: Context) {
        val modelsDir = File(context.filesDir, "models")
        listOf(
            "clothing_type-tf_efficientnetv2_s.in21k_ft_in1k.onnx",
            "electblake-clothing-efficientnetv2s.verified",
            "efficientdet_lite0.tflite",
            "efficientdet_lite0.tflite.part",
            "google-efficientdet-lite0-f32.verified",
            "clothing_type-tf_efficientnetv2_s.in21k_ft_in1k.onnx.part"
        ).forEach { name ->
            val retired = File(modelsDir, name)
            if (retired.exists()) runCatching { retired.delete() }
        }
    }

    suspend fun download(
        context: Context,
        model: ApprovedModelArtifact,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        requireCatalogued(model)
        require(model.downloadUrl.startsWith("https://", ignoreCase = true)) {
            "Only HTTPS model downloads are allowed."
        }
        require(model.minimumBytes > 0 && model.maximumBytes >= model.minimumBytes) {
            "Invalid pinned size bounds for ${model.displayName}."
        }
        require(model.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Invalid pinned SHA-256 for ${model.displayName}."
        }

        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create private model directory")
        val finalFile = modelFile(context, model)
        if (hasVerifiedMarker(context, model)) return@withContext finalFile

        markerFile(context, model).delete()
        if (finalFile.exists() && !finalFile.delete()) error("Cannot remove the invalid previous model file")
        val partFile = File(dir, "${model.fileName}.part")
        if (partFile.exists() && !partFile.delete()) error("Cannot remove an incomplete previous download")

        val stat = StatFs(dir.absolutePath)
        val required = model.maximumBytes + REQUIRED_FREE_SPACE_MARGIN
        require(stat.availableBytes >= required) {
            "Not enough free storage for ${model.displayName}. At least ${(required / 1_000_000_000.0).format(1)} GB is required."
        }

        val coroutineContext = currentCoroutineContext()
        val connection = openApprovedConnection(model.downloadUrl)
        try {
            val status = connection.responseCode
            check(status in 200..299) { "${model.displayName} download failed (HTTP $status)" }
            val total = connection.contentLengthLong
            if (total > 0) {
                require(total in model.minimumBytes..model.maximumBytes) {
                    "${model.displayName} download size ($total bytes) is outside the approved bounds."
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= model.maximumBytes) {
                            "${model.displayName} exceeded its maximum approved size and was rejected."
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }

            require(downloaded in model.minimumBytes..model.maximumBytes) {
                "${model.displayName} download size ($downloaded bytes) is outside the approved bounds."
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(model.sha256, ignoreCase = true)) {
                "${model.displayName} SHA-256 verification failed. The downloaded file was rejected."
            }
            if (finalFile.exists()) finalFile.delete()
            check(partFile.renameTo(finalFile)) { "Could not finalize verified model file: ${model.fileName}" }
            markerFile(context, model).writeText(model.sha256.lowercase())
            finalFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Re-hashes a full model artifact. Call only from an IO/worker thread. */
    fun verifyInstalledBlocking(context: Context, model: ApprovedModelArtifact): Boolean {
        if (!isCatalogued(model)) return false
        val file = modelFile(context, model)
        if (!file.isFile || file.length() !in model.minimumBytes..model.maximumBytes) {
            markerFile(context, model).delete()
            if (file.exists() && file.length() > model.maximumBytes) file.delete()
            return false
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash.equals(model.sha256, ignoreCase = true)) {
                markerFile(context, model).writeText(hash)
                true
            } else {
                file.delete()
                markerFile(context, model).delete()
                false
            }
        } catch (_: Exception) {
            markerFile(context, model).delete()
            false
        }
    }

    fun verifyInstalledBlocking(context: Context): Boolean =
        verifyInstalledBlocking(context, GemmaModelCatalog.selected)

    suspend fun verifyInstalled(context: Context, model: ApprovedModelArtifact): Boolean = withContext(Dispatchers.IO) {
        verifyInstalledBlocking(context, model)
    }

    suspend fun verifyInstalled(context: Context): Boolean = verifyInstalled(context, GemmaModelCatalog.selected)

    private fun openApprovedConnection(initialUrl: String): HttpsURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            require(isApprovedModelUrl(current)) {
                "Model download URL or redirect host is not approved: ${current.host}"
            }
            val connection = (current.openConnection() as? HttpsURLConnection)
                ?: error("Model download did not use HTTPS")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")

            val status = try {
                connection.responseCode
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
            if (status !in REDIRECT_STATUS_CODES) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(redirectIndex < MAX_REDIRECTS) { "Too many redirects while downloading a model." }
            require(!location.isNullOrBlank()) { "Model server returned a redirect without a Location header." }
            current = URL(current, location)
        }
        error("Too many redirects while downloading a model.")
    }

    internal fun isApprovedModelUrl(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        if (url.userInfo != null || (url.port != -1 && url.port != 443)) return false
        val host = url.host.lowercase().trimEnd('.')
        return host in approvedDownloadHosts ||
            host.endsWith(".huggingface.co") ||
            host.endsWith(".hf.co")
    }

    private fun Double.format(decimals: Int): String = "%1$.${decimals}f".format(this)

    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
}
