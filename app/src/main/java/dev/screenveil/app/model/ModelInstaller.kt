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
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_SIZE = 1024 * 1024

    fun modelFile(context: Context): File = File(File(context.filesDir, "models"), GemmaModelCatalog.selected.fileName)
    private fun markerFile(context: Context) = File(modelFile(context).parentFile, "${GemmaModelCatalog.selected.id}.verified")

    fun isVerifiedInstalled(context: Context): Boolean {
        val file = modelFile(context)
        val marker = markerFile(context)
        return file.isFile && file.length() >= GemmaModelCatalog.selected.minimumBytes &&
            marker.isFile && marker.readText().trim() == GemmaModelCatalog.selected.sha256
    }

    suspend fun download(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val model = GemmaModelCatalog.selected
        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create private model directory")

        val finalFile = modelFile(context)
        if (isVerifiedInstalled(context)) return@withContext finalFile

        // A previously interrupted/corrupt installation must not consume a second model-sized
        // allocation while a replacement is downloaded. The pinned hash remains authoritative.
        markerFile(context).delete()
        if (finalFile.exists() && !finalFile.delete()) error("Cannot remove the invalid previous model file")
        val partFile = File(dir, "${model.fileName}.part")
        if (partFile.exists() && !partFile.delete()) error("Cannot remove an incomplete previous download")

        val stat = StatFs(dir.absolutePath)
        val required = model.minimumBytes + 300_000_000L
        require(stat.availableBytes >= required) {
            "Not enough free storage. At least ${(required / 1_000_000_000.0).format(1)} GB is required."
        }

        val coroutineContext = currentCoroutineContext()
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ScreenVeil-Android/1.0")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            connection.connect()
            val status = connection.responseCode
            check(status in 200..299) { "Model download failed (HTTP $status)" }
            val total = connection.contentLengthLong
            if (total > 0) {
                require(total >= model.minimumBytes) { "The model server returned a smaller-than-expected file." }
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

            require(downloaded >= model.minimumBytes) { "Downloaded file is incomplete ($downloaded bytes)." }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(model.sha256, ignoreCase = true)) {
                "Model SHA-256 verification failed. The downloaded file was rejected."
            }
            if (finalFile.exists()) finalFile.delete()
            check(partFile.renameTo(finalFile)) { "Could not finalize verified model file." }
            markerFile(context).writeText(model.sha256)
            finalFile
        } catch (error: Throwable) {
            partFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    /** Re-hashes the complete model file. Call only from an IO/worker thread. */
    fun verifyInstalledBlocking(context: Context): Boolean {
        val file = modelFile(context)
        if (!file.isFile || file.length() < GemmaModelCatalog.selected.minimumBytes) {
            markerFile(context).delete()
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
            if (hash.equals(GemmaModelCatalog.selected.sha256, ignoreCase = true)) {
                markerFile(context).writeText(hash)
                true
            } else {
                file.delete()
                markerFile(context).delete()
                false
            }
        } catch (_: Exception) {
            markerFile(context).delete()
            false
        }
    }

    suspend fun verifyInstalled(context: Context): Boolean = withContext(Dispatchers.IO) {
        verifyInstalledBlocking(context)
    }

    private fun Double.format(decimals: Int): String = "%1$.${decimals}f".format(this)
}
