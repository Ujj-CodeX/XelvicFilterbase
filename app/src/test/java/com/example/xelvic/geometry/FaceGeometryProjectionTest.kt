package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceGeometryProjectionTest {

    @Test
    fun fillCenterMapsCenterToViewCenter() {
        val point = FaceGeometryProjection.projectFillCenter(
            vertex = FaceVertex(0.5f, 0.5f, 0f),
            imageWidth = 640,
            imageHeight = 480,
            viewWidth = 360,
            viewHeight = 640,
            mirrorHorizontally = false
        )!!

        assertEquals(180f, point.x, 0.001f)
        assertEquals(320f, point.y, 0.001f)
    }

    @Test
    fun mirroringIsAppliedAtDisplayBoundaryOnly() {
        val normal = FaceGeometryProjection.projectFillCenter(
            vertex = FaceVertex(0.25f, 0.5f, 0f),
            imageWidth = 640,
            imageHeight = 480,
            viewWidth = 800,
            viewHeight = 600,
            mirrorHorizontally = false
        )!!
        val mirrored = FaceGeometryProjection.projectFillCenter(
            vertex = FaceVertex(0.25f, 0.5f, 0f),
            imageWidth = 640,
            imageHeight = 480,
            viewWidth = 800,
            viewHeight = 600,
            mirrorHorizontally = true
        )!!

        assertEquals(200f, normal.x, 0.001f)
        assertEquals(600f, mirrored.x, 0.001f)
        assertEquals(normal.y, mirrored.y, 0.001f)
    }

    @Test
    fun invalidDimensionsReturnNull() {
        assertNull(
            FaceGeometryProjection.projectFillCenter(
                vertex = FaceVertex(0.5f, 0.5f, 0f),
                imageWidth = 0,
                imageHeight = 480,
                viewWidth = 800,
                viewHeight = 600,
                mirrorHorizontally = false
            )
        )
    }

    @Test
    fun coverCropCentersImageWhenAspectRatiosDiffer() {
        val leftEdge = FaceGeometryProjection.projectFillCenter(
            vertex = FaceVertex(0f, 0.5f, 0f),
            imageWidth = 640,
            imageHeight = 480,
            viewWidth = 360,
            viewHeight = 640,
            mirrorHorizontally = false
        )!!

        // FILL_CENTER scales the 4:3 image to cover the 9:16 view and crops both sides.
        assertEquals(-246.6667f, leftEdge.x, 0.01f)
        assertEquals(320f, leftEdge.y, 0.001f)
    }
}
