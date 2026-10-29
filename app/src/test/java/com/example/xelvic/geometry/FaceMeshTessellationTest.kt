package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMeshTessellationTest {

    @Test
    fun taskApiTopologyHasPinnedCounts() {
        assertEquals(468, FaceMeshTessellation.LANDMARK_COUNT)
        assertEquals(852, FaceMeshTessellation.TRIANGLE_COUNT)
        assertEquals(1322, FaceMeshTessellation.EDGE_COUNT)
        assertEquals(2556, FaceMeshTessellation.TRIANGLE_INDEX_COUNT)
        assertEquals(2644, FaceMeshTessellation.EDGE_INDEX_COUNT)
    }

    @Test
    fun topologySupportsTheFaceSurfaceAndOptionalIrisLandmarks() {
        assertFalse(FaceMeshTessellation.supportsLandmarkCount(467))
        assertTrue(FaceMeshTessellation.supportsLandmarkCount(468))
        assertFalse(FaceMeshTessellation.supportsLandmarkCount(469))
        assertTrue(FaceMeshTessellation.supportsLandmarkCount(478))
        assertFalse(FaceMeshTessellation.supportsLandmarkCount(479))
    }

    @Test
    fun everyTriangleUsesThreeDistinctSurfaceLandmarks() {
        val indices = FaceMeshTessellation.copyTriangleIndices()

        for (i in indices.indices step 3) {
            val a = indices[i]
            val b = indices[i + 1]
            val c = indices[i + 2]

            assertTrue(a in 0 until FaceMeshTessellation.LANDMARK_COUNT)
            assertTrue(b in 0 until FaceMeshTessellation.LANDMARK_COUNT)
            assertTrue(c in 0 until FaceMeshTessellation.LANDMARK_COUNT)
            assertTrue(a != b && b != c && c != a)
        }
    }

    @Test
    fun uniqueWireframeEdgesCoverAllTriangleEdges() {
        fun key(a: Int, b: Int): Long {
            val low = minOf(a, b)
            val high = maxOf(a, b)
            return (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
        }

        val expected = HashSet<Long>()
        val triangles = FaceMeshTessellation.copyTriangleIndices()
        for (i in triangles.indices step 3) {
            expected += key(triangles[i], triangles[i + 1])
            expected += key(triangles[i + 1], triangles[i + 2])
            expected += key(triangles[i + 2], triangles[i])
        }

        val actual = HashSet<Long>()
        val edges = FaceMeshTessellation.copyEdgeIndices()
        for (i in edges.indices step 2) {
            val a = edges[i]
            val b = edges[i + 1]
            assertTrue(a in 0 until FaceMeshTessellation.LANDMARK_COUNT)
            assertTrue(b in 0 until FaceMeshTessellation.LANDMARK_COUNT)
            assertTrue(a < b)
            actual += key(a, b)
        }

        assertEquals(FaceMeshTessellation.EDGE_COUNT, actual.size)
        assertEquals(expected, actual)
    }

    @Test
    fun copyMethodsDoNotExposeTheSharedStaticArrays() {
        val first = FaceMeshTessellation.copyTriangleIndices()
        val originalFirst = FaceMeshTessellation.triangleVertexIndex(0)
        first[0] = -99

        assertEquals(originalFirst, FaceMeshTessellation.triangleVertexIndex(0))
    }
}
