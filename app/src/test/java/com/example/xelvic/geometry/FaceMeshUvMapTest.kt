package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMeshUvMapTest {
    private val modelHash = "a".repeat(64)
    private val uvHash = "b".repeat(64)
    private val coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) { index ->
        FaceUvCoordinate(index / (FaceMeshTessellation.LANDMARK_COUNT - 1f), 0.5f)
    }

    private fun validate(
        activeModelHash: String? = modelHash,
        sourceModelHash: String = modelHash,
        sourceUvHash: String = uvHash,
        expectedUvHash: String = uvHash,
        topologyId: String = FaceMeshTessellation.TOPOLOGY_ID,
        expectedTopologyId: String = FaceMeshTessellation.TOPOLOGY_ID,
        uvCoordinates: List<FaceUvCoordinate> = coordinates
    ) = FaceMeshUvMap.validate(
        sourceTopologyId = topologyId,
        expectedTopologyId = expectedTopologyId,
        sourceModelAssetSha256 = sourceModelHash,
        activeModelAssetSha256 = activeModelHash,
        sourceUvAssetSha256 = sourceUvHash,
        expectedUvAssetSha256 = expectedUvHash,
        coordinates = uvCoordinates,
        uvOrigin = UvOrigin.BOTTOM_LEFT
    )

    @Test
    fun acceptsOnlyPinnedModelTopologyAndUvAsset() {
        val result = validate()

        assertTrue(result.isValid)
        assertNull(result.failure)
        assertEquals(FaceMeshTessellation.LANDMARK_COUNT, result.uvMap!!.vertexCount)
        assertEquals(FaceMeshTessellation.TOPOLOGY_ID, result.uvMap.topologyId)
        assertEquals(modelHash, result.uvMap.modelAssetSha256)
        assertEquals(uvHash, result.uvMap.uvAssetSha256)
    }

    @Test
    fun rejectsLegacyOrUnknownTopology() {
        val result = validate(topologyId = "legacy-canonical-face-model")

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.TOPOLOGY_MISMATCH, result.failure)
        assertNull(result.uvMap)
    }

    @Test
    fun rejectsUvAssetThatDoesNotMatchPinnedDigest() {
        val result = validate(sourceUvHash = "c".repeat(64))

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.UV_ASSET_FINGERPRINT_MISMATCH, result.failure)
    }

    @Test
    fun rejectsMapForDifferentActiveModelBundle() {
        val result = validate(activeModelHash = "c".repeat(64))

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.MODEL_MISMATCH, result.failure)
    }

    @Test
    fun rejectsMissingRuntimeModelIdentity() {
        val result = validate(activeModelHash = null)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.ACTIVE_MODEL_IDENTITY_MISSING, result.failure)
    }

    @Test
    fun rejectsWrongVertexCount() {
        val result = validate(uvCoordinates = coordinates.dropLast(1))

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.INVALID_VERTEX_COUNT, result.failure)
    }

    @Test
    fun interleavedCopyIsIndependentFromMapStorage() {
        val map = validate().uvMap!!
        val first = map.copyInterleavedCoordinates()
        val original = first[0]
        first[0] = 0.99f

        assertEquals(original, map.coordinates.first().u)
        assertNotNull(map.coordinates)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeUvCoordinate() {
        FaceUvCoordinate(1.01f, 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteUvCoordinate() {
        FaceUvCoordinate(Float.NaN, 0.5f)
    }
}
