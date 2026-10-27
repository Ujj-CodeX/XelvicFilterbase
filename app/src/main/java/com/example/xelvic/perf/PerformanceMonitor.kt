package com.example.xelvic.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Size
import java.util.ArrayDeque

/**
 * Lightweight, zero-allocation rolling-window performance metrics collector.
 * Records inference time, frame pacing, tracking update rate, frame drops, and memory usage.
 */
class PerformanceMonitor(private val context: Context) {

    companion object {
        private const val WINDOW_CAPACITY = 30
    }

    private val inferenceDurationsMs = ArrayDeque<Long>(WINDOW_CAPACITY)
    private val frameIntervalsMs = ArrayDeque<Long>(WINDOW_CAPACITY)

    private var lastAnalysisTimestampMs = 0L
    private var totalFramesAnalyzed = 0L
    private var totalFramesSkipped = 0L

    @Volatile
    var previewResolution: Size = Size(0, 0)

    @Volatile
    var analysisResolution: Size = Size(0, 0)

    @Volatile
    var currentProfileName: String = "BALANCED"

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /**
     * Records an inference cycle execution time.
     */
    @Synchronized
    fun recordInference(durationMs: Long, sourceTimestampMs: Long) {
        totalFramesAnalyzed++
        if (inferenceDurationsMs.size >= WINDOW_CAPACITY) {
            inferenceDurationsMs.pollFirst()
        }
        inferenceDurationsMs.addLast(durationMs)

        val now = System.currentTimeMillis()
        if (lastAnalysisTimestampMs > 0L) {
            val interval = now - lastAnalysisTimestampMs
            if (interval in 1..2000) {
                if (frameIntervalsMs.size >= WINDOW_CAPACITY) {
                    frameIntervalsMs.pollFirst()
                }
                frameIntervalsMs.addLast(interval)
            }
        }
        lastAnalysisTimestampMs = now
    }

    @Synchronized
    fun recordFrameSkipped() {
        totalFramesSkipped++
    }

    /**
     * Computes the current median (p50) inference time in milliseconds.
     */
    @Synchronized
    fun getP50InferenceMs(): Long {
        if (inferenceDurationsMs.isEmpty()) return 0L
        val sorted = inferenceDurationsMs.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Computes the 95th percentile (p95) inference time in milliseconds.
     */
    @Synchronized
    fun getP95InferenceMs(): Long {
        if (inferenceDurationsMs.isEmpty()) return 0L
        val sorted = inferenceDurationsMs.sorted()
        val index = (sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)
        return sorted[index]
    }

    /**
     * Computes the current tracking frame rate based on the rolling window.
     */
    @Synchronized
    fun getTrackingFps(): Float {
        if (frameIntervalsMs.size < 3) return 0f
        val avgIntervalMs = frameIntervalsMs.average()
        return if (avgIntervalMs > 0.0) (1000.0 / avgIntervalMs).toFloat() else 0f
    }

    fun getTotalFramesAnalyzed(): Long = totalFramesAnalyzed

    fun getTotalFramesSkipped(): Long = totalFramesSkipped

    /**
     * Samples heap memory used by the application in megabytes.
     */
    fun getUsedHeapMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return used / (1024 * 1024)
    }

    /**
     * Checks if the device is currently in battery-saver mode.
     */
    fun isPowerSaveMode(): Boolean {
        return powerManager?.isPowerSaveMode == true
    }

    /**
     * Obtains thermal status on Android 10+ (API 29+).
     */
    fun getThermalStatusString(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else {
            "N/A"
        }
    }
}
