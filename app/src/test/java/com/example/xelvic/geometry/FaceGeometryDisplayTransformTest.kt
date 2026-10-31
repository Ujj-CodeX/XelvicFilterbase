package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceGeometryDisplayTransformTest {

    private val sourceWidth = 640f
    private val sourceHeight = 480f

    private fun assertMapped(
        transform: FaceGeometryDisplayTransform,
        sourceX: Float,
        sourceY: Float,
        expectedX: Float,
        expectedY: Float
    ) {
        val point = transform.mapPixel(sourceX, sourceY)
            ?: throw AssertionError("Expected a valid mapped point for ($sourceX, $sourceY)")

        assertEquals(expectedX, point.x, 0.001f)
        assertEquals(expectedY, point.y, 0.001f)
    }

    @Test
    fun mapsZeroDegreeTransformWithoutChangingCoordinates() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        )

        assertMapped(transform, 0f, 0f, 0f, 0f)
        assertMapped(transform, sourceWidth, 0f, sourceWidth, 0f)
        assertMapped(transform, 0f, sourceHeight, 0f, sourceHeight)
        assertMapped(transform, sourceWidth, sourceHeight, sourceWidth, sourceHeight)
    }

    @Test
    fun mapsNinetyDegreeClockwiseTransformForNonSquareFrame() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                0f, -1f, sourceHeight,
                1f, 0f, 0f,
                0f, 0f, 1f
            )
        )

        assertMapped(transform, 0f, 0f, sourceHeight, 0f)
        assertMapped(transform, sourceWidth, 0f, sourceHeight, sourceWidth)
        assertMapped(transform, 0f, sourceHeight, 0f, 0f)
        assertMapped(transform, sourceWidth, sourceHeight, 0f, sourceWidth)
    }

    @Test
    fun mapsOneHundredEightyDegreeTransformForNonSquareFrame() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                -1f, 0f, sourceWidth,
                0f, -1f, sourceHeight,
                0f, 0f, 1f
            )
        )

        assertMapped(transform, 0f, 0f, sourceWidth, sourceHeight)
        assertMapped(transform, sourceWidth, 0f, 0f, sourceHeight)
        assertMapped(transform, 0f, sourceHeight, sourceWidth, 0f)
        assertMapped(transform, sourceWidth, sourceHeight, 0f, 0f)
    }

    @Test
    fun mapsTwoHundredSeventyDegreeCounterClockwiseTransformForNonSquareFrame() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                0f, 1f, 0f,
                -1f, 0f, sourceWidth,
                0f, 0f, 1f
            )
        )

        assertMapped(transform, 0f, 0f, 0f, sourceWidth)
        assertMapped(transform, sourceWidth, 0f, 0f, 0f)
        assertMapped(transform, 0f, sourceHeight, sourceHeight, sourceWidth)
        assertMapped(transform, sourceWidth, sourceHeight, sourceHeight, 0f)
    }

    @Test
    fun mapsImagePixelsThroughScaleAndTranslation() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                2f, 0f, 10f,
                0f, 3f, 20f,
                0f, 0f, 1f
            )
        )

        val point = transform.mapPixel(5f, 10f)!!

        assertEquals(20f, point.x, 0.001f)
        assertEquals(50f, point.y, 0.001f)
    }

    @Test
    fun rejectsInvalidPerspectiveDenominator() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 0f
            )
        )

        assertNull(transform.mapPixel(12f, 24f))
    }

    @Test
    fun rejectsNonFiniteInput() {
        val transform = FaceGeometryDisplayTransform(
            listOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        )

        assertNull(transform.mapPixel(Float.NaN, 1f))
    }
}
