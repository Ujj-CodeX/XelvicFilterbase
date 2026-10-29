package com.example.xelvic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterDefinitionSchemaV2Test {

    @Test
    fun schemaV1RemainsCanvasAndKeepsItsLayers() {
        val definition = FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 1,
              "minimumRuntimeVersion": 1,
              "id": "legacy_filter",
              "name": "Legacy Filter",
              "version": "1.0.0",
              "thumbnail": "thumbnail.png",
              "layers": [
                {
                  "id": "layer_1",
                  "asset": "assets/overlay.png",
                  "anchor": "FACE_CENTER",
                  "scaleMode": "FACE_WIDTH",
                  "scale": 1.0,
                  "offsetX": 0.0,
                  "offsetY": 0.0,
                  "rotationMode": "FACE_ROLL",
                  "rotationDegrees": 0.0,
                  "opacity": 1.0,
                  "zIndex": 0
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(FilterRenderer.CANVAS, definition.renderer)
        assertNull(definition.meshTexture)
        assertEquals(1, definition.layers.size)
    }

    @Test
    fun schemaV2ParsesMeshMetadataWithoutLegacyLayers() {
        val definition = FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 2,
              "minimumRuntimeVersion": 2,
              "id": "mesh_filter",
              "name": "Mesh Filter",
              "version": "1.0.0",
              "thumbnail": "thumbnail.png",
              "renderer": "FACE_MESH_TEXTURE",
              "meshTexture": {
                "asset": "assets/face_paint.png",
                "uvLayout": "uv-layout-pending",
                "blendMode": "NORMAL",
                "opacity": 0.85,
                "regionMask": "assets/face_mask.png"
              }
            }
            """.trimIndent()
        )

        assertEquals(FilterRenderer.FACE_MESH_TEXTURE, definition.renderer)
        assertTrue(definition.layers.isEmpty())
        assertEquals("assets/face_paint.png", definition.meshTexture!!.asset)
        assertEquals("uv-layout-pending", definition.meshTexture.uvLayout)
        assertEquals(MeshTextureBlendMode.NORMAL, definition.meshTexture.blendMode)
        assertEquals(0.85f, definition.meshTexture.opacity, 0.0001f)
        assertEquals("assets/face_mask.png", definition.meshTexture.regionMask)
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaV2RejectsUnknownBlendMode() {
        val root = JSONObject(
            """
            {
              "schemaVersion": 2,
              "minimumRuntimeVersion": 2,
              "id": "mesh_filter",
              "name": "Mesh Filter",
              "renderer": "FACE_MESH_TEXTURE",
              "meshTexture": {
                "asset": "assets/face_paint.png",
                "uvLayout": "uv-layout-pending",
                "blendMode": "SCREEN",
                "opacity": 1.0
              }
            }
            """.trimIndent()
        )
        FilterDefinition.fromJson(root.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun schemaV1CannotOptIntoMeshRenderer() {
        FilterDefinition.fromJson(
            """
            {
              "schemaVersion": 1,
              "minimumRuntimeVersion": 1,
              "id": "legacy_filter",
              "name": "Legacy Filter",
              "renderer": "FACE_MESH_TEXTURE",
              "layers": []
            }
            """.trimIndent()
        )
    }
}
