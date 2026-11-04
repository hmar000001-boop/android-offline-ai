package dev.screenveil.app.diagnostics

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, one-shot trace for one screenshot-processing cycle.
 *
 * The trace contains timing, model names, dimensions, aggregate decision counts and geometry only.
 * It never receives a Bitmap, screenshot pixels, recognized text, classifier scores, raw model
 * responses or foreground package names.
 */
class DetectionCycleTrace(
    val cycleId: Long,
    private val profileName: String,
    private val startedAtWallClockMs: Long = System.currentTimeMillis()
) {
    private val startedAtElapsedMs = SystemClock.elapsedRealtime()
    private val entries = ArrayList<String>()
    private var completed = false

    @Synchronized
    fun note(message: String) {
        runCatching {
            if (!completed && entries.size < MAX_ENTRIES) entries += sanitize(message)
        }
    }

    @Synchronized
    fun stage(name: String, durationMs: Long, details: String? = null) {
        runCatching {
            val suffix = details?.takeIf { it.isNotBlank() }?.let { " · ${sanitize(it)}" }.orEmpty()
            note("${sanitize(name)}: ${durationMs.coerceAtLeast(0L)} ms$suffix")
        }
    }

    @Synchronized
    fun finish(outcome: String, healthSummary: String? = null): String? {
        if (completed) return null
        completed = true
        return runCatching {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(startedAtWallClockMs))
            buildString {
                appendLine("SCREENVEIL DETECTION CYCLE")
                appendLine("Cycle ID: $cycleId")
                appendLine("Timestamp: $timestamp")
                appendLine("Profile: ${sanitize(profileName)}")
                appendLine("Elapsed: ${(SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)} ms")
                appendLine("Outcome: ${sanitize(outcome)}")
                appendLine("Privacy: trace payload is metadata/timings only; no screenshot pixels, screen text or foreground app/package names")
                appendLine()
                entries.forEach { appendLine(it) }
                if (!healthSummary.isNullOrBlank()) {
                    appendLine()
                    appendLine("LATEST SAMPLED DEVICE HEALTH (sampling may precede trace completion)")
                    appendLine(healthSummary)
                }
            }.take(MAX_REPORT_CHARS)
        }.getOrNull()
    }

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_ENTRY_CHARS)

    private companion object {
        const val MAX_ENTRIES = 120
        const val MAX_ENTRY_CHARS = 1_200
        const val MAX_REPORT_CHARS = 24_000
    }
}
