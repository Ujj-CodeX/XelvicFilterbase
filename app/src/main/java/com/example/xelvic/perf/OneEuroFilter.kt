package com.example.xelvic.perf

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ (One-Euro) Filter implementation for real-time tracking signal smoothing.
 * Suppresses high-frequency sensor noise and jitter at rest while adapting cutoff
 * frequency to eliminate latency during rapid motion.
 *
 * Reference: Casiez et al., "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in HCI" (CHI 2012)
 *
 * @param minCutoff Minimum cutoff frequency (Hz). Lower values reduce jitter at rest.
 * @param beta Speed coefficient. Higher values reduce lag during fast motion.
 * @param dCutoff Cutoff frequency for the derivative filter (Hz).
 */
class OneEuroFilter(
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.02f,
    private var dCutoff: Float = 1.0f
) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = -1L
    private var initialized = false

    fun reset() {
        initialized = false
        tPrev = -1L
        xPrev = 0f
        dxPrev = 0f
    }

    /**
     * Filters a scalar value (e.g. position coordinate, scale factor).
     *
     * @param x Raw value.
     * @param timestampMs Timestamp of the current sample in milliseconds.
     * @return Filtered output.
     */
    fun filter(x: Float, timestampMs: Long): Float {
        if (!initialized || tPrev < 0L) {
            initialized = true
            xPrev = x
            dxPrev = 0f
            tPrev = timestampMs
            return x
        }

        val dt = (timestampMs - tPrev) / 1000f
        tPrev = timestampMs

        // Guard against zero, negative, or erratic time deltas
        if (dt <= 0.0001f || dt > 1.0f) {
            xPrev = x
            return x
        }

        // 1. Filter the derivative (velocity)
        val dx = (x - xPrev) / dt
        val aD = alpha(dt, dCutoff)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        dxPrev = dxHat

        // 2. Compute dynamic cutoff frequency: fc = minCutoff + beta * |dxHat|
        val cutoff = minCutoff + beta * abs(dxHat)

        // 3. Filter the signal with the dynamic cutoff
        val a = alpha(dt, cutoff)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat

        return xHat
    }

    /**
     * Filters an angular value in degrees, handling modular wraparound correctly
     * (e.g., smoothly transitioning between +179° and -179° without rotating 358°).
     *
     * @param degrees Angle in degrees (-180°..+180° or 0°..360°).
     * @param timestampMs Sample timestamp in milliseconds.
     * @return Smoothly filtered angle in degrees.
     */
    fun filterAngle(degrees: Float, timestampMs: Long): Float {
        if (!initialized || tPrev < 0L) {
            initialized = true
            xPrev = normalizeAngle(degrees)
            dxPrev = 0f
            tPrev = timestampMs
            return xPrev
        }

        val dt = (timestampMs - tPrev) / 1000f
        tPrev = timestampMs

        if (dt <= 0.0001f || dt > 1.0f) {
            xPrev = normalizeAngle(degrees)
            return xPrev
        }

        // Shortest angular difference between target and current smoothed angle
        val target = normalizeAngle(degrees)
        var delta = normalizeAngle(target - xPrev)

        // Filter angular derivative
        val dx = delta / dt
        val aD = alpha(dt, dCutoff)
        val dxHat = aD * dx + (1f - aD) * dxPrev
        dxPrev = dxHat

        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(dt, cutoff)

        // Update angle along shortest path
        val smoothed = normalizeAngle(xPrev + a * delta)
        xPrev = smoothed
        return smoothed
    }

    private fun alpha(dt: Float, cutoffHz: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(0.001f))
        return 1f / (1f + tau / dt)
    }

    private fun normalizeAngle(degrees: Float): Float {
        var a = degrees % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }
}
