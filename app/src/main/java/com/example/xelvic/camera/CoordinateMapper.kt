package com.example.xelvic.camera

import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.example.xelvic.model.FaceTrackingResult
import kotlin.math.max

/**
 * Handles precise mathematical coordinate transformation between
 * CameraX / ML Kit image space and UI PreviewView display space.
 */
class CoordinateMapper {

    /**
     * Maps ML Kit Face detection result to preview coordinate space.
     *
     * @param face The raw ML Kit face detection result.
     * @param imageWidth Width of the image buffer before rotation.
     * @param imageHeight Height of the image buffer before rotation.
     * @param rotationDegrees Rotation metadata from CameraX ImageInfo.
     * @param previewWidth Target UI preview width in pixels.
     * @param previewHeight Target UI preview height in pixels.
     * @param isFrontCamera True if analyzing front-facing camera.
     */
    fun mapFaceToPreview(
        face: Face,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        previewWidth: Int,
        previewHeight: Int,
        isFrontCamera: Boolean,
        sourceTimestampMs: Long = System.currentTimeMillis()
    ): FaceTrackingResult {
        if (previewWidth <= 0 || previewHeight <= 0) {
            return FaceTrackingResult.EMPTY
        }

        // ML Kit provides coordinates in the upright frame coordinates:
        val uprightWidth = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
        val uprightHeight = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight

        if (uprightWidth <= 0 || uprightHeight <= 0) {
            return FaceTrackingResult.EMPTY
        }

        // PreviewView uses ScaleType.FILL_CENTER (aspect fill, centered)
        val scale = max(
            previewWidth.toFloat() / uprightWidth.toFloat(),
            previewHeight.toFloat() / uprightHeight.toFloat()
        )
        val scaledWidth = uprightWidth * scale
        val scaledHeight = uprightHeight * scale
        val dx = (previewWidth - scaledWidth) / 2f
        val dy = (previewHeight - scaledHeight) / 2f

        // Helper point mapper
        fun mapPoint(x: Float, y: Float): PointF {
            var mx = x * scale + dx
            val my = y * scale + dy
            if (isFrontCamera) {
                mx = previewWidth - mx
            }
            return PointF(mx, my)
        }

        // Map Face Bounding Box
        val rawBox = face.boundingBox
        val p1 = mapPoint(rawBox.left.toFloat(), rawBox.top.toFloat())
        val p2 = mapPoint(rawBox.right.toFloat(), rawBox.bottom.toFloat())
        val mappedBounds = RectF(
            minOf(p1.x, p2.x),
            minOf(p1.y, p2.y),
            maxOf(p1.x, p2.x),
            maxOf(p1.y, p2.y)
        )

        // Map Landmarks
        val mappedLandmarks = mutableMapOf<String, PointF>()

        fun extractLandmark(type: Int, key: String) {
            face.getLandmark(type)?.position?.let { pos ->
                mappedLandmarks[key] = mapPoint(pos.x, pos.y)
            }
        }

        extractLandmark(FaceLandmark.LEFT_EYE, "LEFT_EYE")
        extractLandmark(FaceLandmark.RIGHT_EYE, "RIGHT_EYE")
        extractLandmark(FaceLandmark.NOSE_BASE, "NOSE")
        extractLandmark(FaceLandmark.MOUTH_BOTTOM, "MOUTH")
        extractLandmark(FaceLandmark.LEFT_CHEEK, "LEFT_CHEEK")
        extractLandmark(FaceLandmark.RIGHT_CHEEK, "RIGHT_CHEEK")

        // Face Roll Angle (Z-axis rotation)
        // With front-facing camera mirroring, the rotation direction is inverted.
        val roll = if (isFrontCamera) -face.headEulerAngleZ else face.headEulerAngleZ
        val yaw = if (isFrontCamera) -face.headEulerAngleY else face.headEulerAngleY

        return FaceTrackingResult(
            hasFace = true,
            bounds = mappedBounds,
            landmarks = mappedLandmarks,
            rollDegrees = roll,
            yawDegrees = yaw,
            timestampMs = sourceTimestampMs,
            coordinateSpaceWidth = previewWidth,
            coordinateSpaceHeight = previewHeight,
            isFrontCamera = isFrontCamera
        )
    }

    /**
     * Maps ML Kit Face detection result to full-resolution captured photo coordinates.
     */
    fun mapFaceToImage(
        face: Face,
        imageWidth: Int,
        imageHeight: Int,
        isFrontCamera: Boolean
    ): FaceTrackingResult {
        val mappedLandmarks = mutableMapOf<String, PointF>()

        fun extractLandmark(type: Int, key: String) {
            face.getLandmark(type)?.position?.let { pos ->
                val x = if (isFrontCamera) imageWidth - pos.x else pos.x
                mappedLandmarks[key] = PointF(x, pos.y)
            }
        }

        val rawBox = face.boundingBox
        val left = if (isFrontCamera) imageWidth - rawBox.right.toFloat() else rawBox.left.toFloat()
        val right = if (isFrontCamera) imageWidth - rawBox.left.toFloat() else rawBox.right.toFloat()
        val mappedBounds = RectF(minOf(left, right), rawBox.top.toFloat(), maxOf(left, right), rawBox.bottom.toFloat())

        extractLandmark(FaceLandmark.LEFT_EYE, "LEFT_EYE")
        extractLandmark(FaceLandmark.RIGHT_EYE, "RIGHT_EYE")
        extractLandmark(FaceLandmark.NOSE_BASE, "NOSE")
        extractLandmark(FaceLandmark.MOUTH_BOTTOM, "MOUTH")
        extractLandmark(FaceLandmark.LEFT_CHEEK, "LEFT_CHEEK")
        extractLandmark(FaceLandmark.RIGHT_CHEEK, "RIGHT_CHEEK")

        val roll = if (isFrontCamera) -face.headEulerAngleZ else face.headEulerAngleZ

        return FaceTrackingResult(
            hasFace = true,
            bounds = mappedBounds,
            landmarks = mappedLandmarks,
            rollDegrees = roll,
            yawDegrees = face.headEulerAngleY,
            timestampMs = System.currentTimeMillis(),
            coordinateSpaceWidth = imageWidth,
            coordinateSpaceHeight = imageHeight,
            isFrontCamera = isFrontCamera
        )
    }
}
