package com.example.xelvic.renderer

import android.graphics.Bitmap
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvAssetFailure
import com.example.xelvic.geometry.FaceMeshUvAssetResult
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.FaceUvCoordinate
import com.example.xelvic.geometry.UvOrigin
import com.example.xelvic.model.FilterDefinition
import com.example.xelvic.model.FilterRenderer
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.model.MeshTextureBlendMode
import com.example.xelvic.model.MeshTextureDefinition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceMeshEffectResolverTest {
    private val modelSha = "a".repeat(64)
    private val uvSha = "b".repeat(64)
    private val layoutId = "face-layout-test-v1"

    private val validMap = FaceMeshUvMap.validate(
        sourceTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        expectedTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        sourceModelAssetSha256 = modelSha,
        activeModelAssetSha256 = modelSha,
        sourceUvAssetSha256 = uvSha,
        expectedUvAssetSha256 = uvSha,
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.5f, 0.5f)
        },
        uvOrigin = UvOrigin.BOTTOM_LEFT,
        uvLayoutId = layoutId
    ).uvMap!!

    private val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun installed(
        renderer: FilterRenderer,
        mesh: MeshTextureDefinition? = null,
        id: String = "test-filter"
    ): InstalledFilter {
        val definition = FilterDefinition(
            schemaVersion = if (renderer == FilterRenderer.CANVAS) 1 else 2,
            id = id,
            name = "Test filter",
            version = "1.0",
            description = "",
            minimumRuntimeVersion = if (renderer == FilterRenderer.CANVAS) 1 else 2,
            thumbnail = "thumbnail.png",
            layers = emptyList(),
            renderer = renderer,
            meshTexture = mesh
        )
        return InstalledFilter(
            id = definition.id,
            definition = definition,
            packageDirectory = File("/tmp/test-filter"),
            thumbnailFile = File("/tmp/test-filter/thumbnail.png")
        )
    }

    private fun meshSpec(
        layout: String = layoutId,
        regionMask: String? = null,
        opacity: Float = 0.75f
    ) = MeshTextureDefinition(
        asset = "textures/face.png",
        uvLayout = layout,
        blendMode = MeshTextureBlendMode.NORMAL,
        opacity = opacity,
        regionMask = regionMask
    )

    @Test
    fun noSelectionProducesNoSelection() {
        val resolver = FaceMeshEffectResolver({ _, _ -> error("must not load UVs") })
        assertEquals(FaceMeshEffectResolution.NoSelection, resolver.resolve(null, modelSha))
    }

    @Test
    fun legacyFilterStaysOnCanvasWithoutLoadingUv() {
        val resolver = FaceMeshEffectResolver({ _, _ -> error("legacy filter must not load UVs") })
        assertEquals(
            FaceMeshEffectResolution.LegacyCanvas,
            resolver.resolve(installed(FilterRenderer.CANVAS), modelSha)
        )
    }

    @Test
    fun missingModelIdentityBlocksMeshEffect() {
        val resolver = FaceMeshEffectResolver({ _, _ -> error("must fail before UV lookup") })
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec()),
            activeModelAssetSha256 = null
        )
        assertEquals(
            FaceMeshEffectResolution.Blocked(FaceMeshEffectBlockReason.ACTIVE_MODEL_IDENTITY_MISSING),
            result
        )
    }

    @Test
    fun unregisteredUvLayoutBlocksMeshEffect() {
        val resolver = FaceMeshEffectResolver({ _, _ ->
            FaceMeshUvAssetResult(assetFailure = FaceMeshUvAssetFailure.UNREGISTERED_LAYOUT)
        })
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec()),
            modelSha
        )
        assertEquals(
            FaceMeshEffectResolution.Blocked(FaceMeshEffectBlockReason.UV_LAYOUT_NOT_REGISTERED),
            result
        )
    }

    @Test
    fun unsupportedRegionMaskIsNotSilentlyIgnored() {
        val resolver = FaceMeshEffectResolver({ _, _ -> error("must fail before UV lookup") })
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec(regionMask = "masks/eyes.png")),
            modelSha
        )
        assertEquals(
            FaceMeshEffectResolution.Blocked(FaceMeshEffectBlockReason.UNSUPPORTED_REGION_MASK),
            result
        )
    }

    @Test
    fun uvLayoutMismatchBlocksEffect() {
        val resolver = FaceMeshEffectResolver({ _, _ -> FaceMeshUvAssetResult(uvMap = validMap) })
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec(layout = "another-layout")),
            modelSha
        )
        assertEquals(
            FaceMeshEffectResolution.Blocked(FaceMeshEffectBlockReason.UV_LAYOUT_MISMATCH),
            result
        )
    }

    @Test
    fun validPinnedLayoutProducesGlEffectWithExpectedOpacity() {
        val resolver = FaceMeshEffectResolver(
            loadUvMap = { requestedLayout, requestedModel ->
                assertEquals(layoutId, requestedLayout)
                assertEquals(modelSha, requestedModel)
                FaceMeshUvAssetResult(uvMap = validMap)
            },
            loadBitmap = { _, _ -> bitmap }
        )
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec()),
            modelSha
        )

        assertTrue(result is FaceMeshEffectResolution.Ready)
        val effect = (result as FaceMeshEffectResolution.Ready).effect
        assertEquals("test-filter", effect.effectId)
        assertEquals(layoutId, effect.uvLayoutId)
        assertEquals(0.75f, effect.opacity, 0.0001f)
        assertSame(bitmap, effect.bitmap)
    }

    @Test
    fun missingTextureAssetBlocksEffect() {
        val resolver = FaceMeshEffectResolver(
            loadUvMap = { _, _ -> FaceMeshUvAssetResult(uvMap = validMap) },
            loadBitmap = { _, _ -> null }
        )
        val result = resolver.resolve(
            installed(FilterRenderer.FACE_MESH_TEXTURE, meshSpec()),
            modelSha
        )
        assertEquals(
            FaceMeshEffectResolution.Blocked(FaceMeshEffectBlockReason.TEXTURE_ASSET_MISSING),
            result
        )
    }
    @Test
    fun bundledDebugMoustacheUsesDiagnosticGlesUvModeWithoutPretendingUvIsPinned() {
        val resolver = FaceMeshEffectResolver(
            loadUvMap = { _, _ -> error("diagnostic fixture must not load canonical UVs") },
            loadBitmap = { _, _ -> bitmap },
            diagnosticFaceSpaceEnabled = true
        )
        val result = resolver.resolve(
            installed(
                renderer = FilterRenderer.FACE_MESH_TEXTURE,
                mesh = meshSpec(layout = DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID),
                id = DIAGNOSTIC_MOUSTACHE_FILTER_ID
            ),
            activeModelAssetSha256 = modelSha
        )

        assertTrue(result is FaceMeshEffectResolution.Ready)
        val effect = (result as FaceMeshEffectResolution.Ready).effect
        assertEquals(DIAGNOSTIC_MOUSTACHE_FILTER_ID, effect.effectId)
        assertEquals(DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID, effect.uvLayoutId)
        assertEquals(FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE, effect.uvMode)
        assertEquals(null, effect.uvMap)
        assertSame(bitmap, effect.bitmap)
    }
}
