package dev.droiddoodle.app.resources

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * A single observation of what the app is costing the device.
 *
 * Sizes are bytes. Android's own APIs are a mixture of kilobytes and bytes, and
 * normalising at the boundary is cheaper than remembering which is which at
 * every call site.
 */
internal data class ResourceSample(
    val uptimeMillis: Long,
    /** Proportional set size: the honest "what is this app costing" number. */
    val pssBytes: Long,
    val nativePssBytes: Long,
    val nativeHeapBytes: Long,
    val jvmHeapUsedBytes: Long,
    val jvmHeapMaxBytes: Long,
    val deviceAvailableBytes: Long,
    val deviceTotalBytes: Long,
    val deviceLowMemory: Boolean,
    /** Percent of one core. 400 on a quad-core means every core is saturated. */
    val cpuPercentOfOneCore: Double,
    val coreCount: Int,
    val thermal: String,
) {
    val cpuPercentOfDevice: Double
        get() = if (coreCount <= 0) 0.0 else cpuPercentOfOneCore / coreCount
}

/**
 * Samples process and device resource use.
 *
 * Why this exists rather than a profiler: intent criterion T2 is a claim about
 * peak memory on a ≤6GB device, and T1 is a latency claim. Both have to be
 * answerable from the device the app actually runs on, by the person running
 * it, without a development machine attached — otherwise "it fits" stays an
 * opinion.
 *
 * Not thread-safe; call from one place.
 */
internal class ResourceSampler(context: Context) {

    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val pid = Process.myPid()

    private var lastCpuMillis = -1L
    private var lastUptimeMillis = -1L

    /** The highest PSS seen this session. T2 is about the peak, not the mean. */
    var peakPssBytes: Long = 0
        private set

    fun sample(): ResourceSample {
        val uptime = SystemClock.uptimeMillis()

        // Process.getElapsedCpuTime reports milliseconds directly, so there is
        // no need to guess the kernel's clock-tick rate the way parsing
        // /proc/self/stat would require.
        val cpuMillis = Process.getElapsedCpuTime()
        val cpuPercent = if (lastCpuMillis < 0 || uptime <= lastUptimeMillis) {
            0.0
        } else {
            (cpuMillis - lastCpuMillis) * 100.0 / (uptime - lastUptimeMillis)
        }
        lastCpuMillis = cpuMillis
        lastUptimeMillis = uptime

        // getProcessMemoryInfo is a binder round trip and is genuinely slow, so
        // the sampling interval is a second rather than a frame.
        val processMemory = runCatching {
            activityManager.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
        }.getOrNull()

        val pssBytes = (processMemory?.totalPss?.toLong() ?: 0L) * 1024
        val nativePssBytes = (processMemory?.nativePss?.toLong() ?: 0L) * 1024
        if (pssBytes > peakPssBytes) peakPssBytes = pssBytes

        val deviceMemory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(deviceMemory)

        val runtime = Runtime.getRuntime()

        return ResourceSample(
            uptimeMillis = uptime,
            pssBytes = pssBytes,
            nativePssBytes = nativePssBytes,
            // The model is mmapped, so this stays small even with 700MB mapped.
            // Native PSS above is the number that moves.
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            jvmHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            jvmHeapMaxBytes = runtime.maxMemory(),
            deviceAvailableBytes = deviceMemory.availMem,
            deviceTotalBytes = deviceMemory.totalMem,
            deviceLowMemory = deviceMemory.lowMemory,
            cpuPercentOfOneCore = cpuPercent,
            coreCount = runtime.availableProcessors(),
            thermal = thermalStatus(),
        )
    }

    /**
     * Thermal state matters more here than it would in most apps: sustained
     * multi-core decode heats a phone, and a throttled device produces latency
     * numbers that say nothing about the model.
     */
    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}
