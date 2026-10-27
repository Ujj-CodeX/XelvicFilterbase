package com.example.xelvic.perf

/**
 * Performance operating profiles for tracking analysis.
 */
enum class TrackingProfile(val frameSkipInterval: Int) {
    BALANCED(0),       // Process every frame (highest responsiveness)
    PERFORMANCE(1),    // Process every 2nd frame (moderate load)
    LOW_LOAD(2)        // Process every 3rd frame (power saving / sustained load)
}

/**
 * Dynamically adapts face tracking analysis frequency based on measured runtime inference latency,
 * thermal state, battery-saver mode, and presence of a tracked face.
 * Implements hysteresis and cooldown intervals to prevent rapid profile oscillations.
 */
class AdaptivePerformanceController(
    private val performanceMonitor: PerformanceMonitor
) {
    companion object {
        private const val UPGRADE_COOLDOWN_MS = 3000L
        private const val DOWNGRADE_THRESHOLD_MS = 45L
        private const val CRITICAL_DOWNGRADE_THRESHOLD_MS = 65L
    }

    private var currentProfile = TrackingProfile.BALANCED
    private var lastProfileTransitionTimeMs = 0L
    private var frameCounter = 0
    private var noFaceDurationMs = 0L
    private var lastFrameTimeMs = 0L

    fun getCurrentProfile(): TrackingProfile = currentProfile

    /**
     * Determines whether the current frame should be processed by the face detector
     * or dropped immediately to preserve system responsiveness and preview pacing.
     */
    fun shouldAnalyzeFrame(currentTimeMs: Long, hasTrackedFace: Boolean): Boolean {
        updateProfile(currentTimeMs, hasTrackedFace)

        frameCounter++
        val skipInterval = currentProfile.frameSkipInterval

        // When no face has been seen for > 2 seconds, throttle to reduce idle thermal load
        val effectiveSkip = if (!hasTrackedFace && noFaceDurationMs > 2000L) {
            maxOf(skipInterval, 1)
        } else {
            skipInterval
        }

        val shouldProcess = (frameCounter % (effectiveSkip + 1)) == 0
        if (!shouldProcess) {
            performanceMonitor.recordFrameSkipped()
        }
        return shouldProcess
    }

    private fun updateProfile(now: Long, hasTrackedFace: Boolean) {
        if (lastFrameTimeMs > 0L) {
            val dt = now - lastFrameTimeMs
            if (!hasTrackedFace) {
                noFaceDurationMs += dt
            } else {
                noFaceDurationMs = 0L
            }
        }
        lastFrameTimeMs = now

        // Check hardware constraints (battery saver mode)
        val powerSave = performanceMonitor.isPowerSaveMode()
        val p95 = performanceMonitor.getP95InferenceMs()

        val targetProfile = when {
            powerSave || p95 >= CRITICAL_DOWNGRADE_THRESHOLD_MS -> TrackingProfile.LOW_LOAD
            p95 >= DOWNGRADE_THRESHOLD_MS -> TrackingProfile.PERFORMANCE
            else -> TrackingProfile.BALANCED
        }

        // Downgrade immediately if under stress
        if (targetProfile.ordinal > currentProfile.ordinal) {
            currentProfile = targetProfile
            lastProfileTransitionTimeMs = now
            performanceMonitor.currentProfileName = currentProfile.name
        } else if (targetProfile.ordinal < currentProfile.ordinal) {
            // Require cooldown period before upgrading to prevent rapid flapping
            if (now - lastProfileTransitionTimeMs >= UPGRADE_COOLDOWN_MS) {
                currentProfile = targetProfile
                lastProfileTransitionTimeMs = now
                performanceMonitor.currentProfileName = currentProfile.name
            }
        }
    }
}
