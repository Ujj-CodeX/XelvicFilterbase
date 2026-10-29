package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceGeometryDisplayTransformTest {

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
