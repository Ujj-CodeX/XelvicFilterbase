package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FaceGeometrySnapshotTest {

    @Test
    fun snapshotDefensivelyCopiesLists() {
        val landmarks = mutableListOf(FaceVertex(0.1f, 0.2f, -0.03f))
        val blendshapes = mutableListOf(BlendShapeValue("jawOpen", 0.25f))
        val matrix = MutableList(16) { index -> index.toFloat() }

        val snapshot = FaceGeometrySnapshot(
            timestampMs = 10L,
            imageWidth = 640,
            imageHeight = 480,
            rotationDegrees = 90,
            isMirrored = true,
            landmarks = landmarks,
            blendshapes = blendshapes,
            facialTransformationMatrix = matrix
        )

        landmarks.clear()
        blendshapes.clear()
        matrix[0] = 99f

        assertEquals(1, snapshot.landmarks.size)
        assertEquals("jawOpen", snapshot.blendshapes.single().categoryName)
        assertEquals(0f, snapshot.facialTransformationMatrix!![0], 0f)
    }

    @Test
    fun snapshotExposesUnmodifiableLandmarks() {
        val snapshot = validSnapshot()

        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.landmarks as MutableList<FaceVertex>).add(FaceVertex(0f, 0f, 0f))
        }
    }

    @Test
    fun validSnapshotAcceptsSixteenValueMatrix() {
        val snapshot = validSnapshot()
        assertNotNull(snapshot.facialTransformationMatrix)
        assertEquals(16, snapshot.facialTransformationMatrix!!.size)
    }

    @Test
    fun rejectsMatrixWithWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceGeometrySnapshot(
                timestampMs = 1L,
                imageWidth = 640,
                imageHeight = 480,
                rotationDegrees = 0,
                isMirrored = false,
                landmarks = listOf(FaceVertex(0f, 0f, 0f)),
                facialTransformationMatrix = listOf(1f, 2f, 3f)
            )
        }
    }

    @Test
    fun rejectsUnsupportedRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceGeometrySnapshot(
                timestampMs = 1L,
                imageWidth = 640,
                imageHeight = 480,
                rotationDegrees = 45,
                isMirrored = false,
                landmarks = listOf(FaceVertex(0f, 0f, 0f))
            )
        }
    }

    @Test
    fun rejectsInvalidBlendshapeScore() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceGeometrySnapshot(
                timestampMs = 1L,
                imageWidth = 640,
                imageHeight = 480,
                rotationDegrees = 0,
                isMirrored = false,
                landmarks = listOf(FaceVertex(0f, 0f, 0f)),
                blendshapes = listOf(BlendShapeValue("jawOpen", 1.2f))
            )
        }
    }

    private fun validSnapshot() = FaceGeometrySnapshot(
        timestampMs = 10L,
        imageWidth = 640,
        imageHeight = 480,
        rotationDegrees = 0,
        isMirrored = false,
        landmarks = listOf(FaceVertex(0.5f, 0.5f, -0.03f)),
        blendshapes = listOf(BlendShapeValue("jawOpen", 0.25f)),
        facialTransformationMatrix = MutableList(16) { index -> index.toFloat() }
    )
}
