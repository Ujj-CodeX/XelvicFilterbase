package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.FaceUvCoordinate
import com.example.xelvic.geometry.UvOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class RenderBackendRouterTest {
    private val modelHash = "a".repeat(64)
    private val uvHash = "b".repeat(64)
    private val capabilities = RenderBackendCapabilities(
        openGlEsMajorVersion = 2,
        maxTextureSize = 4096,
        depthBufferAvailable = true
    )

    private val uvMap = FaceMeshUvMap.validate(
        sourceTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        expectedTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        sourceModelAssetSha256 = modelHash,
        activeModelAssetSha256 = modelHash,
        sourceUvAssetSha256 = uvHash,
        expectedUvAssetSha256 = uvHash,
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.5f, 0.5f)
        },
        uvOrigin = UvOrigin.BOTTOM_LEFT
    ).uvMap!!

    private fun meshRequirements(
        map: FaceMeshUvMap? = uvMap,
        modelSha: String? = modelHash,
        topology: String = FaceMeshTessellation.TOPOLOGY_ID,
        width: Int = 512,
        height: Int = 512,
        requiresDepth: Boolean = false
    ) = FaceMeshTextureRequirements(
        topologyId = topology,
        activeModelAssetSha256 = modelSha,
        uvMap = map,
        textureWidth = width,
        textureHeight = height,
        requiresDepthOcclusion = requiresDepth
    )

    @Test
    fun legacyFiltersAlwaysRemainOnCanvas() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.LEGACY_2D,
            mesh = null,
            capabilities = RenderBackendCapabilities(0, 0, false)
        )

        assertEquals(RenderBackend.CANVAS, result.backend)
        assertEquals(RenderBackendBlockReason.NONE, result.reason)
    }

    @Test
    fun meshFilterFailsClosedWhenUvMapIsAbsent() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(map = null),
            capabilities
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.UV_MAP_NOT_VALIDATED, result.reason)
    }

    @Test
    fun compatiblePinnedMeshAssetsSelectGlBackend() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(),
            capabilities
        )

        assertEquals(RenderBackend.FACE_MESH_GL, result.backend)
        assertEquals(RenderBackendBlockReason.NONE, result.reason)
    }

    @Test
    fun differentModelFingerprintBlocksMeshRendering() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(modelSha = "c".repeat(64)),
            capabilities
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.MODEL_MISMATCH, result.reason)
    }

    @Test
    fun unavailableOpenGlBlocksMeshRendering() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(),
            RenderBackendCapabilities(1, 4096, true)
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.OPENGL_ES_UNAVAILABLE, result.reason)
    }

    @Test
    fun textureMustFitDeviceLimit() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(width = 8192),
            capabilities
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.TEXTURE_EXCEEDS_GPU_LIMIT, result.reason)
    }

    @Test
    fun depthEffectsRequireDepthBufferCapability() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(requiresDepth = true),
            capabilities.copy(depthBufferAvailable = false)
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.DEPTH_BUFFER_UNAVAILABLE, result.reason)
    }

    @Test
    fun invalidDimensionsAreRejected() {
        val result = RenderBackendRouter.select(
            EffectRenderIntent.FACE_MESH_TEXTURE,
            meshRequirements(width = 0),
            capabilities
        )

        assertEquals(RenderBackend.UNAVAILABLE, result.backend)
        assertEquals(RenderBackendBlockReason.INVALID_TEXTURE_DIMENSIONS, result.reason)
    }
}
