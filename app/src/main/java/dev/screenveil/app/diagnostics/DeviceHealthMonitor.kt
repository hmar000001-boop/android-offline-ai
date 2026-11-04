package dev.screenveil.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-frequency, on-device health sampling while protection is active. This monitor is diagnostic
 * only: it never changes protection state, deadlines, capture frequency, model policy or overlays.
 */
object DeviceHealthMonitor {
    private const val SAMPLE_INTERVAL_SECONDS = 15L
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "screenveil-device-health").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val mainLooperLastDelayMs = AtomicLong(0L)
    private val mainLooperMaxDelayMs = AtomicLong(0L)
    private val mutableSummary = MutableStateFlow("Device health: waiting for first sample")
    val summary: StateFlow<String> = mutableSummary.asStateFlow()

    @Volatile private var scheduledSample: ScheduledFuture<*>? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var lastCpuSampleAtMs = 0L
    @Volatile private var lastProcessCpuTimeMs = 0L
    @Volatile private var lastThermalHeadroomAtMs = 0L
    @Volatile private var cachedThermalHeadroom = Float.NaN

    @Synchronized
    fun start(context: Context) {
        applicationContext = context.applicationContext
        if (scheduledSample?.isCancelled == false && scheduledSample?.isDone == false) return
        mainLooperLastDelayMs.set(0L)
        mainLooperMaxDelayMs.set(0L)
        lastCpuSampleAtMs = 0L
        lastProcessCpuTimeMs = 0L
        scheduledSample = runCatching {
            executor.scheduleWithFixedDelay(
                { sampleAndRecord() },
                0L,
                SAMPLE_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }.getOrNull()
    }

    @Synchronized
    fun stop() {
        runCatching {
            scheduledSample?.cancel(false)
            scheduledSample = null
        }
    }

    /** Called from a lightweight main-looper heartbeat; no disk or system-service work is done here. */
    fun recordMainLooperDelay(delayMs: Long) {
        val safeDelay = delayMs.coerceAtLeast(0L)
        mainLooperLastDelayMs.set(safeDelay)
        mainLooperMaxDelayMs.accumulateAndGet(safeDelay) { previous, observed -> maxOf(previous, observed) }
    }

    fun currentSummary(): String = mutableSummary.value

    /** Fresh snapshot for the explicit Copy session report action. */
    fun snapshotNow(context: Context): String {
        val snapshot = runCatching { collectSnapshot(context.applicationContext) }.getOrNull()
            ?: return "Device health snapshot unavailable; Android did not provide one or more required system services."
        mutableSummary.value = snapshot.toDisplayString()
        return snapshot.toReportString()
    }

    fun sessionReport(context: Context, eventLog: String, lastCycle: String): String {
        val health = snapshotNow(context)
        return buildString {
            appendLine("SCREENVEIL DEVICE / SESSION REPORT")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(java.util.Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Diagnostics are local. No screenshots, screen text or foreground package names are included.")
            appendLine()
            appendLine("CURRENT DEVICE HEALTH")
            appendLine(health)
            appendLine()
            appendLine("EVENT LOG")
            appendLine(eventLog.ifBlank { "(empty)" })
            if (lastCycle.isNotBlank()) {
                appendLine()
                appendLine("LAST DETAILED DETECTION CYCLE")
                appendLine(lastCycle)
            }
        }.take(MAX_REPORT_CHARS)
    }

    private fun sampleAndRecord() {
        val context = applicationContext ?: return
        runCatching {
            val snapshot = collectSnapshot(context)
            mutableSummary.value = snapshot.toDisplayString()
            DiagnosticLog.record(context, "DEVICE_HEALTH ${snapshot.toCompactLogLine()}")
        }
    }

    private fun collectSnapshot(context: Context): HealthSnapshot {
        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val manager = context.getSystemService(BatteryManager::class.java)
        val capacity = runCatching {
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: BATTERY_VALUE_UNAVAILABLE
        }.getOrDefault(BATTERY_VALUE_UNAVAILABLE)
        val batteryLevel = if (capacity in 0..100) capacity else {
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else BATTERY_VALUE_UNAVAILABLE
        }
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val batteryTempC = if (tempTenths == Int.MIN_VALUE) null else tempTenths / 10.0
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "full"
            status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0 -> "charging/plugged"
            status == BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            else -> "unknown"
        }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.let { thermalStatusName(it.currentThermalStatus) } ?: "unavailable" }
                .getOrDefault("unavailable")
        } else "unsupported"
        val thermalHeadroom = powerManager?.let(::readThermalHeadroom) ?: Float.NaN

        val memoryInfo = ActivityManager.MemoryInfo()
        val memorySnapshotAvailable = runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
                ?: error("ActivityManager is unavailable")
            activityManager.getMemoryInfo(memoryInfo)
            true
        }.getOrDefault(false)
        val processPssMb = runCatching { Debug.getPss() / 1024 }.getOrDefault(-1L)
        val availableMemoryMb = if (memorySnapshotAvailable) {
            memoryInfo.availMem / (1024L * 1024L)
        } else -1L
        val cpuPercent = readProcessCpuPercent()
        val lastLooperDelay = mainLooperLastDelayMs.get()
        val maximumLooperDelay = mainLooperMaxDelayMs.getAndSet(0L)

        return HealthSnapshot(
            sampledAtWallClockMs = System.currentTimeMillis(),
            batteryPercent = batteryLevel.takeIf { it in 0..100 },
            batteryTemperatureC = batteryTempC,
            chargingState = charging,
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom.takeIf { it.isFinite() && it >= 0f },
            processPssMb = processPssMb,
            availableMemoryMb = availableMemoryMb,
            lowMemory = memoryInfo.lowMemory.takeIf { memorySnapshotAvailable },
            processCpuPercent = cpuPercent,
            mainLooperLastDelayMs = lastLooperDelay,
            mainLooperMaxDelayMs = maximumLooperDelay
        )
    }

    @Synchronized
    private fun readThermalHeadroom(powerManager: PowerManager): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val now = SystemClock.elapsedRealtime()
        if (lastThermalHeadroomAtMs > 0L && now - lastThermalHeadroomAtMs < THERMAL_HEADROOM_MIN_INTERVAL_MS) {
            return cachedThermalHeadroom
        }
        lastThermalHeadroomAtMs = now
        cachedThermalHeadroom = runCatching { powerManager.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        return cachedThermalHeadroom
    }

    @Synchronized
    private fun readProcessCpuPercent(): Double? {
        val now = SystemClock.elapsedRealtime()
        val cpuNow = Process.getElapsedCpuTime()
        val previousAt = lastCpuSampleAtMs
        val previousCpu = lastProcessCpuTimeMs
        lastCpuSampleAtMs = now
        lastProcessCpuTimeMs = cpuNow
        if (previousAt <= 0L || now <= previousAt) return null
        return ((cpuNow - previousCpu).coerceAtLeast(0L) * 100.0 / (now - previousAt)).coerceAtLeast(0.0)
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    private data class HealthSnapshot(
        val sampledAtWallClockMs: Long,
        val batteryPercent: Int?,
        val batteryTemperatureC: Double?,
        val chargingState: String,
        val thermalStatus: String,
        val thermalHeadroom: Float?,
        val processPssMb: Long,
        val availableMemoryMb: Long,
        val lowMemory: Boolean?,
        val processCpuPercent: Double?,
        val mainLooperLastDelayMs: Long,
        val mainLooperMaxDelayMs: Long
    ) {
        fun toDisplayString(): String = buildString {
            append("Device health · battery ")
            append(batteryPercent?.let { "$it%" } ?: "n/a")
            append(" · battery temp ")
            append(batteryTemperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "n/a")
            append(" · thermal ").append(thermalStatus)
            append(" · app RAM ").append(if (processPssMb >= 0) "${processPssMb}MB" else "n/a")
            append(" · main-thread delay ").append(mainLooperLastDelayMs).append("ms")
            append(" · sampled ").append(java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(sampledAtWallClockMs)))
        }

        fun toReportString(): String = buildString {
            appendLine("Sampled at: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(java.util.Date(sampledAtWallClockMs))}")
            appendLine("Battery: ${batteryPercent?.let { "$it%" } ?: "unavailable"}")
            appendLine("Battery temperature: ${batteryTemperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "unavailable"}")
            appendLine("Power state: $chargingState")
            appendLine("Android thermal status: $thermalStatus")
            appendLine("Thermal headroom (10-second forecast): ${thermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "unavailable/unsupported"}")
            appendLine("ScreenVeil process PSS: ${if (processPssMb >= 0) "$processPssMb MB" else "unavailable"}")
            appendLine("System available RAM: ${if (availableMemoryMb >= 0L) "$availableMemoryMb MB" else "unavailable"}")
            appendLine("System low-memory flag: ${lowMemory?.toString() ?: "unavailable"}")
            appendLine("ScreenVeil process CPU since previous sample (one-core equivalent; may exceed 100%): ${processCpuPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "warming up"}")
            appendLine("Main-looper delay, latest: ${mainLooperLastDelayMs} ms")
            appendLine("Main-looper delay, max since previous sample: ${mainLooperMaxDelayMs} ms")
        }

        fun toCompactLogLine(): String = buildString {
            append("battery=${batteryPercent ?: "n/a"}%")
            append(" batteryTempC=${batteryTemperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"}")
            append(" power=$chargingState thermal=$thermalStatus")
            append(" thermalHeadroom=${thermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"}")
            append(" pssMb=$processPssMb availableRamMb=$availableMemoryMb lowMemory=${lowMemory ?: "n/a"}")
            append(" processCpuOneCoreEquivalent=${processCpuPercent?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"}%")
            append(" mainLooperLastDelayMs=$mainLooperLastDelayMs mainLooperMaxDelayMs=$mainLooperMaxDelayMs")
        }
    }

    private const val BATTERY_VALUE_UNAVAILABLE = -1
    private const val THERMAL_HEADROOM_MIN_INTERVAL_MS = 5_000L
    private const val MAX_REPORT_CHARS = 48_000
}
