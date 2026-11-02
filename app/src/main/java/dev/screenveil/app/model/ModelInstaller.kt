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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelInstaller {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val BUFFER_SIZE = 1024 * 1024

    fun modelFile(context: Context, model: TrustedModel): File =
        File(File(context.filesDir, "models"), model.fileName)

    /** Backwards-compatible Gemma path. */
    fun modelFile(context: Context): File = modelFile(context, GemmaModelCatalog.selected)

    private fun markerFile(context: Context, model: TrustedModel) =
        File(modelFile(context, model).parentFile, "${model.id}.verified")

    fun isVerifiedInstalled(context: Context, model: TrustedModel): Boolean {
        val file = modelFile(context, model)
        val marker = markerFile(context, model)
        return file.isFile && file.length() >= model.minimumBytes &&
            marker.isFile && marker.readText().trim().equals(model.sha256, ignoreCase = true)
    }

    fun isVerifiedInstalled(context: Context): Boolean =
        isVerifiedInstalled(context, GemmaModelCatalog.selected)

    fun isProfileInstalled(context: Context, profile: ModelProfile): Boolean =
        profile.requiredModels.all { isVerifiedInstalled(context, it) }

    fun isProfileModelSetVerified(context: Context, profile: ModelProfile): ModelFiles {
        val resolved = LinkedHashMap<String, File>()
        profile.requiredModels.forEach { model ->
            check(isVerifiedInstalled(context, model)) { "${model.displayName} is not installed or not verified" }
            resolved[model.id] = modelFile(context, model)
        }
        return ModelFiles(resolved)
    }

    suspend fun download(
        context: Context,
        model: TrustedModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create private model directory")
        val finalFile = modelFile(context, model)
        if (isVerifiedInstalled(context, model)) return@withContext finalFile

        markerFile(context, model).delete()
        if (finalFile.exists() && !finalFile.delete()) error("Cannot remove the invalid previous model file")
        val partFile = File(dir, "${model.fileName}.part")
        if (partFile.exists() && !partFile.delete()) error("Cannot remove an incomplete previous download")

        val stat = StatFs(dir.absolutePath)
        val required = model.minimumBytes + 250_000_000L
        require(stat.availableBytes >= required) {
            "Not enough free storage for ${model.displayName}. At least ${(required / 1_000_000_000.0).format(1)} GB is required."
        }

        val coroutineContext = currentCoroutineContext()
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ScreenVeil-Android/1.1")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            connection.connect()
            val status = connection.responseCode
            check(status in 200..299) { "${model.displayName} download failed (HTTP $status)" }
            val total = connection.contentLengthLong
            if (total > 0) require(total >= model.minimumBytes) {
                "${model.displayName} download is smaller than the pinned artifact size."
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
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }

            require(downloaded >= model.minimumBytes) { "${model.displayName} download is incomplete ($downloaded bytes)." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(model.sha256, ignoreCase = true)) {
                "${model.displayName} SHA-256 verification failed. The downloaded file was rejected."
            }
            if (finalFile.exists()) finalFile.delete()
            check(partFile.renameTo(finalFile)) { "Could not finalize verified model file: ${model.fileName}" }
            markerFile(context, model).writeText(model.sha256)
            finalFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Re-hashes a full model artifact. Call only from an IO/worker thread. */
    fun verifyInstalledBlocking(context: Context, model: TrustedModel): Boolean {
        val file = modelFile(context, model)
        if (!file.isFile || file.length() < model.minimumBytes) {
            markerFile(context, model).delete()
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

    suspend fun verifyInstalled(context: Context, model: TrustedModel): Boolean = withContext(Dispatchers.IO) {
        verifyInstalledBlocking(context, model)
    }

    suspend fun verifyInstalled(context: Context): Boolean = verifyInstalled(context, GemmaModelCatalog.selected)

    private fun Double.format(decimals: Int): String = "%1$.${decimals}f".format(this)
}
