package com.example.xelvic.geometry

import kotlin.math.max

/**
 * Maps normalized, upright inference-image coordinates into a PreviewView using FILL_CENTER.
 *
 * This is a deliberately explicit prototype mapping. It is valid only when the preview and
 * inference image depict the same ViewPort crop. If runtime measurements disprove that
 * assumption, replace this with a CameraX CoordinateTransform derived from the exact frame.
 *
 * Mirroring is applied exactly once, at the final display boundary.
 */
object FaceGeometryProjection {

    data class Point(val x: Float, val y: Float)

    fun projectFillCenter(
        vertex: FaceVertex,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        mirrorHorizontally: Boolean
    ): Point? {
        if (
            imageWidth <= 0 || imageHeight <= 0 ||
            viewWidth <= 0 || viewHeight <= 0 ||
            !vertex.x.isFinite() || !vertex.y.isFinite()
        ) {
            return null
        }

        val scale = max(
            viewWidth.toFloat() / imageWidth,
            viewHeight.toFloat() / imageHeight
        )
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val left = (viewWidth - displayedWidth) / 2f
        val top = (viewHeight - displayedHeight) / 2f

        val xInView = vertex.x * imageWidth * scale + left
        val yInView = vertex.y * imageHeight * scale + top
        val displayX = if (mirrorHorizontally) viewWidth - xInView else xInView

        return Point(displayX, yInView)
    }
}
