package com.example.xelvic.renderer

import android.graphics.Bitmap
import com.example.BuildConfig
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvAssetFailure
import com.example.xelvic.geometry.FaceMeshUvAssetResult
import com.example.xelvic.geometry.FaceMeshUvMapFailure
import com.example.xelvic.geometry.FaceMeshUvRegistry
import com.example.xelvic.model.FilterRenderer
import com.example.xelvic.model.InstalledFilter

/** Why a schema-v2 face-mesh effect was not prepared for the GL renderer. */
enum class FaceMeshEffectBlockReason {
    MISSING_MESH_METADATA,
    ACTIVE_MODEL_IDENTITY_MISSING,
    INVALID_ACTIVE_MODEL_IDENTITY,
    UV_LAYOUT_NOT_REGISTERED,
    UV_ASSET_READ_FAILED,
    UV_ASSET_INVALID,
    UV_LAYOUT_MISMATCH,
    UV_MODEL_MISMATCH,
    UV_TOPOLOGY_MISMATCH,
    UV_FINGERPRINT_MISMATCH,
    UV_VERTEX_COUNT_INVALID,
    TEXTURE_ASSET_MISSING,
    TEXTURE_BITMAP_INVALID,
    UNSUPPORTED_REGION_MASK
}

/** Resolver result keeps legacy Canvas selection distinct from an unavailable mesh effect. */
sealed interface FaceMeshEffectResolution {
    data object NoSelection : FaceMeshEffectResolution
    data object LegacyCanvas : FaceMeshEffectResolution
    data class Ready(val effect: FaceMeshGlEffect) : FaceMeshEffectResolution
    data class Blocked(val reason: FaceMeshEffectBlockReason) : FaceMeshEffectResolution
}

/**
 * Production .xfilter mesh effects are accepted only after the app-owned UV registry validates
 * the exact model/topology pair. Only the bundled moustache fixture in debug builds can use
 * diagnostic face-space UVs to exercise the GLES texture pipeline without claiming canonical UVs.
 */
class FaceMeshEffectResolver(
    private val loadUvMap: (layoutId: String, activeModelAssetSha256: String?) -> FaceMeshUvAssetResult,
    private val loadBitmap: (InstalledFilter, assetPath: String) -> Bitmap? = { filter, path ->
        filter.getAssetBitmap(path)
    },
    private val diagnosticFaceSpaceEnabled: Boolean = BuildConfig.DEBUG
) {
    constructor(registry: FaceMeshUvRegistry) : this(registry::load)

    fun resolve(
        filter: InstalledFilter?,
        activeModelAssetSha256: String?
    ): FaceMeshEffectResolution {
        if (filter == null) return FaceMeshEffectResolution.NoSelection

        val definition = filter.definition
        if (definition.renderer == FilterRenderer.CANVAS) {
            return FaceMeshEffectResolution.LegacyCanvas
        }

        val mesh = definition.meshTexture
            ?: return blocked(FaceMeshEffectBlockReason.MISSING_MESH_METADATA)

        if (mesh.regionMask != null) {
            return blocked(FaceMeshEffectBlockReason.UNSUPPORTED_REGION_MASK)
        }

        if (
            definition.id == DIAGNOSTIC_MOUSTACHE_FILTER_ID &&
            mesh.uvLayout == DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID
        ) {
            if (!diagnosticFaceSpaceEnabled) {
                return blocked(FaceMeshEffectBlockReason.UV_LAYOUT_NOT_REGISTERED)
            }
            val activeModelSha = activeModelAssetSha256
                ?: return blocked(FaceMeshEffectBlockReason.ACTIVE_MODEL_IDENTITY_MISSING)
            if (!activeModelSha.isSha256()) {
                return blocked(FaceMeshEffectBlockReason.INVALID_ACTIVE_MODEL_IDENTITY)
            }
            val bitmap = loadBitmap(filter, mesh.asset)
                ?: return blocked(FaceMeshEffectBlockReason.TEXTURE_ASSET_MISSING)
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                return blocked(FaceMeshEffectBlockReason.TEXTURE_BITMAP_INVALID)
            }
            return FaceMeshEffectResolution.Ready(
                FaceMeshGlEffect(
                    effectId = definition.id,
                    topologyId = FaceMeshTessellation.TOPOLOGY_ID,
                    uvLayoutId = mesh.uvLayout,
                    modelAssetSha256 = activeModelSha,
                    uvMap = null,
                    bitmap = bitmap,
                    opacity = mesh.opacity,
                    requiresDepthOcclusion = false,
                    uvMode = FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE
                )
            )
        }

        val activeModelSha = activeModelAssetSha256
            ?: return blocked(FaceMeshEffectBlockReason.ACTIVE_MODEL_IDENTITY_MISSING)
        if (!activeModelSha.isSha256()) {
            return blocked(FaceMeshEffectBlockReason.INVALID_ACTIVE_MODEL_IDENTITY)
        }

        val uvResult = loadUvMap(mesh.uvLayout, activeModelSha)
        val uvMap = uvResult.uvMap ?: return blocked(mapUvFailure(uvResult))

        if (uvMap.uvLayoutId != mesh.uvLayout) {
            return blocked(FaceMeshEffectBlockReason.UV_LAYOUT_MISMATCH)
        }
        if (uvMap.modelAssetSha256 != activeModelSha) {
            return blocked(FaceMeshEffectBlockReason.UV_MODEL_MISMATCH)
        }
        if (uvMap.topologyId != FaceMeshTessellation.TOPOLOGY_ID) {
            return blocked(FaceMeshEffectBlockReason.UV_TOPOLOGY_MISMATCH)
        }
        if (uvMap.vertexCount != FaceMeshTessellation.LANDMARK_COUNT) {
            return blocked(FaceMeshEffectBlockReason.UV_VERTEX_COUNT_INVALID)
        }

        val bitmap = loadBitmap(filter, mesh.asset)
            ?: return blocked(FaceMeshEffectBlockReason.TEXTURE_ASSET_MISSING)
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return blocked(FaceMeshEffectBlockReason.TEXTURE_BITMAP_INVALID)
        }

        return FaceMeshEffectResolution.Ready(
            FaceMeshGlEffect(
                effectId = definition.id,
                topologyId = uvMap.topologyId,
                uvLayoutId = mesh.uvLayout,
                modelAssetSha256 = activeModelSha,
                uvMap = uvMap,
                bitmap = bitmap,
                opacity = mesh.opacity,
                requiresDepthOcclusion = false,
                uvMode = FaceMeshTextureUvMode.PINNED_CANONICAL
            )
        )
    }

    private fun mapUvFailure(result: FaceMeshUvAssetResult): FaceMeshEffectBlockReason {
        return when (result.assetFailure) {
            FaceMeshUvAssetFailure.UNREGISTERED_LAYOUT ->
                FaceMeshEffectBlockReason.UV_LAYOUT_NOT_REGISTERED
            FaceMeshUvAssetFailure.ASSET_READ_FAILED ->
                FaceMeshEffectBlockReason.UV_ASSET_READ_FAILED
            null -> when (result.validationFailure) {
                FaceMeshUvMapFailure.MODEL_MISMATCH,
                FaceMeshUvMapFailure.ACTIVE_MODEL_IDENTITY_MISSING ->
                    FaceMeshEffectBlockReason.UV_MODEL_MISMATCH
                FaceMeshUvMapFailure.TOPOLOGY_MISMATCH ->
                    FaceMeshEffectBlockReason.UV_TOPOLOGY_MISMATCH
                FaceMeshUvMapFailure.UV_ASSET_FINGERPRINT_MISMATCH ->
                    FaceMeshEffectBlockReason.UV_FINGERPRINT_MISMATCH
                FaceMeshUvMapFailure.INVALID_VERTEX_COUNT ->
                    FaceMeshEffectBlockReason.UV_VERTEX_COUNT_INVALID
                else -> FaceMeshEffectBlockReason.UV_ASSET_INVALID
            }
            else -> FaceMeshEffectBlockReason.UV_ASSET_INVALID
        }
    }

    private fun blocked(reason: FaceMeshEffectBlockReason) =
        FaceMeshEffectResolution.Blocked(reason)

    private fun String.isSha256(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
}
