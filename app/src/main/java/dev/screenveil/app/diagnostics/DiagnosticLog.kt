package dev.screenveil.app.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small on-device event log for field diagnosis. It records state transitions and counts only:
 * never screenshots, detected content, on-screen text or app names. It is never uploaded; the user
 * can copy or clear it from the app.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostics.log"
    private const val MAX_BYTES = 96 * 1024L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-diagnostics").apply { isDaemon = true }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    fun record(context: Context, event: String) {
        val appContext = context.applicationContext
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} $event\n"
        runCatching {
            executor.execute {
                runCatching {
                    val target = file(appContext)
                    if (target.length() > MAX_BYTES) {
                        val tail = target.readText().takeLast((MAX_BYTES / 2).toInt())
                        target.writeText(tail.substringAfter('\n', tail))
                    }
                    target.appendText(line)
                }
            }
        }
    }

    fun read(context: Context, maxChars: Int = 30_000): String =
        runCatching { file(context).takeIf { it.isFile }?.readText()?.takeLast(maxChars).orEmpty() }
            .getOrDefault("")

    fun clear(context: Context) {
        runCatching { executor.execute { runCatching { file(context.applicationContext).delete() } } }
    }
}
