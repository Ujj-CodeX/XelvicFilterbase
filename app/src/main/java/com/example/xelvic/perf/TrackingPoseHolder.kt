package com.example.xelvic.perf

import android.graphics.PointF
import android.graphics.RectF
import com.example.xelvic.model.FaceTrackingResult
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the latest face tracking pose.
 * Bridges the asynchronous ImageAnalysis tracking loop and the UI overlay rendering loop
 * without triggering Jetpack Compose UI recompositions on the main thread.
 */
class TrackingPoseHolder {

    private val currentPose = AtomicReference(FaceTrackingResult.EMPTY)

    // Pose velocity estimation for bounded display extrapolation
    private var prevX = 0f
    private var prevY = 0f
    private var prevTimestampMs = -1L
    private var velocityX = 0f
    private var velocityY = 0f

    // Invalidation callback to notify the overlay view to redraw
    @Volatile
    var onPoseUpdated: (() -> Unit)? = null

    /**
     * Publishes a new tracking pose from the background tracking loop.
     */
    fun updatePose(result: FaceTrackingResult) {
        val now = result.timestampMs

        if (result.hasFace) {
            val cx = result.bounds.centerX()
            val cy = result.bounds.centerY()

            if (prevTimestampMs > 0L && (now - prevTimestampMs) in 1..250) {
                val dt = (now - prevTimestampMs) / 1000f
                if (dt > 0.001f) {
                    val rawVx = (cx - prevX) / dt
                    val rawVy = (cy - prevY) / dt
                    // EMA filter on velocity to prevent erratic extrapolation
                    velocityX = 0.5f * rawVx + 0.5f * velocityX
                    velocityY = 0.5f * rawVy + 0.5f * velocityY
                }
            } else {
                velocityX = 0f
                velocityY = 0f
            }

            prevX = cx
            prevY = cy
            prevTimestampMs = now
        } else {
            prevTimestampMs = -1L
            velocityX = 0f
            velocityY = 0f
        }

        currentPose.set(result)
        onPoseUpdated?.invoke()
    }

    /**
     * Obtains the latest pose.
     * Optionally applies bounded extrapolation towards target display time.
     */
    fun getLatestPose(predictToTimestampMs: Long = 0L): FaceTrackingResult {
        val raw = currentPose.get()
        if (!raw.hasFace || predictToTimestampMs <= 0L || prevTimestampMs <= 0L) {
            return raw
        }

        val leadTimeMs = (predictToTimestampMs - raw.timestampMs).coerceIn(0L, 35L)
        if (leadTimeMs <= 0L) return raw

        val leadSeconds = leadTimeMs / 1000f
        val maxDisplacementPx = 25f

        val dx = (velocityX * leadSeconds).coerceIn(-maxDisplacementPx, maxDisplacementPx)
        val dy = (velocityY * leadSeconds).coerceIn(-maxDisplacementPx, maxDisplacementPx)

        if (dx == 0f && dy == 0f) return raw

        // Bounded translation of bounds and landmarks
        val predictedBounds = RectF(
            raw.bounds.left + dx,
            raw.bounds.top + dy,
            raw.bounds.right + dx,
            raw.bounds.bottom + dy
        )

        val predictedLandmarks = raw.landmarks.mapValues { (_, pt) ->
            PointF(pt.x + dx, pt.y + dy)
        }

        return raw.copy(
            bounds = predictedBounds,
            landmarks = predictedLandmarks
        )
    }

    fun hasFace(): Boolean = currentPose.get().hasFace
}
