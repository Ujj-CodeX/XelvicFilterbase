package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap

/** The semantic requirements of an effect, independent of a particular rendering surface. */
enum class EffectRenderIntent {
    LEGACY_2D,
    FACE_MESH_TEXTURE
}

enum class RenderBackend {
    CANVAS,
    FACE_MESH_GL,
    UNAVAILABLE
}

enum class RenderBackendBlockReason {
    NONE,
    MESH_REQUIREMENTS_MISSING,
    UV_MAP_NOT_VALIDATED,
    UV_LAYOUT_MISMATCH,
    ACTIVE_MODEL_IDENTITY_MISSING,
    MODEL_MISMATCH,
    TOPOLOGY_MISMATCH,
    OPENGL_ES_UNAVAILABLE,
    INVALID_TEXTURE_DIMENSIONS,
    GPU_TEXTURE_LIMIT_UNKNOWN,
    TEXTURE_EXCEEDS_GPU_LIMIT,
    DEPTH_BUFFER_UNAVAILABLE
}

data class RenderBackendCapabilities(
    val openGlEsMajorVersion: Int,
    val maxTextureSize: Int,
    val depthBufferAvailable: Boolean
)

data class FaceMeshTextureRequirements(
    val topologyId: String,
    val uvLayoutId: String?,
    val activeModelAssetSha256: String?,
    val uvMap: FaceMeshUvMap?,
    val textureWidth: Int,
    val textureHeight: Int,
    val requiresDepthOcclusion: Boolean = false
)

data class RenderBackendDecision(
    val backend: RenderBackend,
    val reason: RenderBackendBlockReason = RenderBackendBlockReason.NONE
)

/**
 * Selects a renderer without silently degrading a face-surface texture into a flat sticker.
 *
 * Legacy v1 .xfilter layers remain on Canvas. A mesh-texture effect is unavailable until its UV
 * asset matches the active model and topology and the device can support its texture requirements.
 */
object RenderBackendRouter {

    fun select(
        intent: EffectRenderIntent,
        mesh: FaceMeshTextureRequirements?,
        capabilities: RenderBackendCapabilities
    ): RenderBackendDecision {
        if (intent == EffectRenderIntent.LEGACY_2D) {
            return RenderBackendDecision(RenderBackend.CANVAS)
        }

        if (mesh == null) return blocked(RenderBackendBlockReason.MESH_REQUIREMENTS_MISSING)

        val uvMap = mesh.uvMap
            ?: return blocked(RenderBackendBlockReason.UV_MAP_NOT_VALIDATED)

        val requiredUvLayoutId = mesh.uvLayoutId?.takeIf { it.isNotBlank() }
            ?: return blocked(RenderBackendBlockReason.UV_LAYOUT_MISMATCH)
        if (uvMap.uvLayoutId != requiredUvLayoutId) {
            return blocked(RenderBackendBlockReason.UV_LAYOUT_MISMATCH)
        }

        if (mesh.activeModelAssetSha256 == null) {
            return blocked(RenderBackendBlockReason.ACTIVE_MODEL_IDENTITY_MISSING)
        }
        if (uvMap.modelAssetSha256 != mesh.activeModelAssetSha256) {
            return blocked(RenderBackendBlockReason.MODEL_MISMATCH)
        }
        if (
            mesh.topologyId != FaceMeshTessellation.TOPOLOGY_ID ||
            uvMap.topologyId != mesh.topologyId ||
            uvMap.vertexCount != FaceMeshTessellation.LANDMARK_COUNT
        ) {
            return blocked(RenderBackendBlockReason.TOPOLOGY_MISMATCH)
        }

        if (capabilities.openGlEsMajorVersion < 2) {
            return blocked(RenderBackendBlockReason.OPENGL_ES_UNAVAILABLE)
        }
        if (mesh.textureWidth <= 0 || mesh.textureHeight <= 0) {
            return blocked(RenderBackendBlockReason.INVALID_TEXTURE_DIMENSIONS)
        }
        if (capabilities.maxTextureSize <= 0) {
            return blocked(RenderBackendBlockReason.GPU_TEXTURE_LIMIT_UNKNOWN)
        }
        if (
            mesh.textureWidth > capabilities.maxTextureSize ||
            mesh.textureHeight > capabilities.maxTextureSize
        ) {
            return blocked(RenderBackendBlockReason.TEXTURE_EXCEEDS_GPU_LIMIT)
        }
        if (mesh.requiresDepthOcclusion && !capabilities.depthBufferAvailable) {
            return blocked(RenderBackendBlockReason.DEPTH_BUFFER_UNAVAILABLE)
        }

        return RenderBackendDecision(RenderBackend.FACE_MESH_GL)
    }

    private fun blocked(reason: RenderBackendBlockReason) =
        RenderBackendDecision(RenderBackend.UNAVAILABLE, reason)
}
