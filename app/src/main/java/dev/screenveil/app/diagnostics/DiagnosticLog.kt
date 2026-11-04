package dev.screenveil.app.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded on-device event and one-shot trace storage. It records metadata and counters only:
 * never screenshots, detected pixels, on-screen text or foreground app/package names. Nothing is
 * uploaded; reports leave the device only after an explicit user copy action.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostics.log"
    private const val LAST_CYCLE_FILE_NAME = "last_detection_cycle.txt"
    private const val PREFERENCES_NAME = "screenveil_diagnostics"
    private const val TRACE_NEXT_CYCLE_KEY = "trace_next_detection_cycle"
    private const val MAX_BYTES = 96 * 1024L
    private const val MAX_CYCLE_BYTES = 24 * 1024L
    private const val READ_TIMEOUT_SECONDS = 3L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screenveil-diagnostics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val mutableTraceArmed = MutableStateFlow(false)
    val traceArmed: StateFlow<Boolean> = mutableTraceArmed.asStateFlow()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
    private fun lastCycleFile(context: Context) = File(context.applicationContext.filesDir, LAST_CYCLE_FILE_NAME)
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(context: Context, event: String) {
        val appContext = context.applicationContext
        val line = "${timestamp()} ${sanitize(event)}\n"
        runCatching {
            executor.execute {
                runCatching {
                    val target = file(appContext)
                    trimIfNeeded(target, MAX_BYTES)
                    target.appendText(line)
                }
            }
        }
    }

    fun armNextDetectionCycle(context: Context) {
        val saved = runCatching {
            preferences(context).edit().putBoolean(TRACE_NEXT_CYCLE_KEY, true).apply()
        }.isSuccess
        mutableTraceArmed.value = saved
        if (saved) record(context, "DETAILED_TRACE armed for next screenshot cycle")
    }

    fun isNextDetectionCycleArmed(context: Context): Boolean {
        val armed = runCatching { preferences(context).getBoolean(TRACE_NEXT_CYCLE_KEY, false) }.getOrDefault(false)
        mutableTraceArmed.value = armed
        return armed
    }

    /** Consume only when an actual screenshot request is about to begin, not during a rate-limit delay. */
    @Synchronized
    fun consumeNextDetectionCycleTrace(context: Context): Boolean {
        // Fail closed: a diagnostics preference failure must never prevent an ordinary capture.
        val prefs = runCatching { preferences(context) }.getOrNull() ?: return false
        val armed = runCatching { prefs.getBoolean(TRACE_NEXT_CYCLE_KEY, false) }.getOrDefault(false)
        if (!armed) {
            mutableTraceArmed.value = false
            return false
        }
        // apply() updates the in-memory preference immediately and persists asynchronously; do
        // not block the AccessibilityService main looper on disk I/O just to arm diagnostics.
        runCatching { prefs.edit().remove(TRACE_NEXT_CYCLE_KEY).apply() }
        mutableTraceArmed.value = false
        return true
    }

    fun saveLastDetectionCycle(context: Context, report: String) {
        val appContext = context.applicationContext
        val bounded = truncateUtf8(report, MAX_CYCLE_BYTES.toInt())
        runCatching {
            executor.execute {
                runCatching {
                    lastCycleFile(appContext).writeText(bounded)
                    val outcome = bounded.lineSequence().firstOrNull { it.startsWith("Outcome:") }.orEmpty()
                    val cycleId = bounded.lineSequence().firstOrNull { it.startsWith("Cycle ID:") }.orEmpty()
                    val target = file(appContext)
                    trimIfNeeded(target, MAX_BYTES)
                    target.appendText("${timestamp()} DETECTION_TRACE completed $cycleId $outcome\n")
                }
            }
        }
    }

    /** Flush prior queued writes before returning text for an explicit clipboard action. */
    fun read(context: Context, maxChars: Int = 30_000): String {
        val appContext = context.applicationContext
        return runCatching {
            executor.submit<String> {
                file(appContext).takeIf { it.isFile }?.readText()?.takeLast(maxChars).orEmpty()
            }.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault("Diagnostic log is unavailable or still being written")
    }

    fun readLastDetectionCycle(context: Context): String {
        val appContext = context.applicationContext
        return runCatching {
            executor.submit<String> {
                lastCycleFile(appContext).takeIf { it.isFile }?.readText()?.let { truncateUtf8(it, MAX_CYCLE_BYTES.toInt()) }.orEmpty()
            }.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault("Last detection cycle is unavailable or still being written")
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        mutableTraceArmed.value = false
        // Disarm synchronously so a capture cannot consume a stale one-shot request while the
        // asynchronous file cleanup is waiting behind earlier diagnostic writes.
        runCatching { preferences(appContext).edit().remove(TRACE_NEXT_CYCLE_KEY).apply() }
        runCatching {
            executor.execute {
                runCatching { file(appContext).delete() }
                runCatching { lastCycleFile(appContext).delete() }
            }
        }
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        var low = 0
        var high = minOf(value.length, maximumBytes)
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val encodedBytes = value.substring(0, middle).toByteArray(Charsets.UTF_8).size
            if (encodedBytes <= maximumBytes) low = middle else high = middle - 1
        }
        return value.substring(0, low)
    }

    private fun trimIfNeeded(target: File, maximumBytes: Long) {
        if (!target.isFile || target.length() <= maximumBytes) return
        val tail = target.readText().takeLast((maximumBytes / 2).toInt())
        target.writeText(tail.substringAfter('\n', tail))
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(1_000)
}
