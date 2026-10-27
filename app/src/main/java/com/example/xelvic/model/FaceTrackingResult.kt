package com.example.xelvic.model

import android.graphics.PointF
import android.graphics.RectF

/**
 * Immutable model containing detected face boundaries, landmarks, and orientation.
 * All coordinates are normalized or mapped to the target coordinate space (e.g. preview or image).
 */
data class FaceTrackingResult(
    val hasFace: Boolean,
    val bounds: RectF = RectF(),
    val landmarks: Map<String, PointF> = emptyMap(),
    val rollDegrees: Float = 0f,
    val yawDegrees: Float = 0f,
    val timestampMs: Long = 0L,
    val coordinateSpaceWidth: Int = 0,
    val coordinateSpaceHeight: Int = 0,
    val isFrontCamera: Boolean = true
) {
    /**
     * Resolves an anchor point on the face according to AnchorType.
     * Derived anchors like FOREHEAD_TOP and EYES_CENTER are computed mathematically.
     */
    fun resolveAnchor(anchorType: AnchorType): PointF? {
        if (!hasFace) return null

        val leftEye = landmarks["LEFT_EYE"]
        val rightEye = landmarks["RIGHT_EYE"]
        val nose = landmarks["NOSE"]
        val mouth = landmarks["MOUTH"]

        return when (anchorType) {
            AnchorType.FACE_CENTER -> PointF(bounds.centerX(), bounds.centerY())

            AnchorType.EYES_CENTER -> {
                if (leftEye != null && rightEye != null) {
                    PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
                } else {
                    PointF(bounds.centerX(), bounds.top + bounds.height() * 0.35f)
                }
            }

            AnchorType.FOREHEAD_TOP -> {
                // FOREHEAD_TOP is calculated relative to the eyes center and the top bounds of the face.
                // In typical face proportions, the forehead top rests between the eye line and 
                // the upper boundary of the detected skull.
                val eyeCenter = if (leftEye != null && rightEye != null) {
                    PointF((leftEye.x + rightEye.x) / 2f, (leftEye.y + rightEye.y) / 2f)
                } else {
                    PointF(bounds.centerX(), bounds.top + bounds.height() * 0.35f)
                }
                // Offset vertically along face axis:
                val foreheadY = bounds.top + (eyeCenter.y - bounds.top) * 0.25f
                PointF(eyeCenter.x, foreheadY)
            }

            AnchorType.LEFT_EYE -> leftEye ?: PointF(bounds.left + bounds.width() * 0.3f, bounds.top + bounds.height() * 0.35f)
            AnchorType.RIGHT_EYE -> rightEye ?: PointF(bounds.left + bounds.width() * 0.7f, bounds.top + bounds.height() * 0.35f)
            AnchorType.NOSE -> nose ?: PointF(bounds.centerX(), bounds.centerY())
            AnchorType.MOUTH -> mouth ?: PointF(bounds.centerX(), bounds.top + bounds.height() * 0.75f)
            AnchorType.LEFT_CHEEK -> landmarks["LEFT_CHEEK"] ?: PointF(bounds.left + bounds.width() * 0.25f, bounds.centerY())
            AnchorType.RIGHT_CHEEK -> landmarks["RIGHT_CHEEK"] ?: PointF(bounds.left + bounds.width() * 0.75f, bounds.centerY())
        }
    }

    /**
     * Computes the angle of the line connecting both eyes in degrees.
     */
    fun computeEyeLineAngleDegrees(): Float {
        val leftEye = landmarks["LEFT_EYE"]
        val rightEye = landmarks["RIGHT_EYE"]
        return if (leftEye != null && rightEye != null) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        } else {
            rollDegrees
        }
    }

    /**
     * Computes distance between eyes.
     */
    fun computeEyeDistance(): Float {
        val leftEye = landmarks["LEFT_EYE"]
        val rightEye = landmarks["RIGHT_EYE"]
        return if (leftEye != null && rightEye != null) {
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        } else {
            bounds.width() * 0.4f
        }
    }

    companion object {
        val EMPTY = FaceTrackingResult(hasFace = false)
    }
}
