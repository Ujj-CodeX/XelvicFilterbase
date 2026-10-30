package com.example.xelvic.geometry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceMeshUvAssetParserTest {
    private val layoutId = "test-face-layout-v1"
    private val modelSha = "a".repeat(64)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun document(
        layout: String = layoutId,
        topology: String = FaceMeshTessellation.TOPOLOGY_ID,
        model: String = modelSha,
        count: Int = FaceMeshTessellation.LANDMARK_COUNT,
        uvOrigin: String = "BOTTOM_LEFT",
        u: Double = 0.5,
        v: Double = 0.5
    ): ByteArray {
        val points = JSONArray()
        repeat(count) { points.put(JSONObject().put("u", u).put("v", v)) }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("uvLayoutId", layout)
            .put("topologyId", topology)
            .put("modelAssetSha256", model)
            .put("uvOrigin", uvOrigin)
            .put("coordinates", points)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun pinned(bytes: ByteArray) = PinnedFaceMeshUvLayout(
        layoutId = layoutId,
        assetPath = "uv_layouts/$layoutId.json",
        expectedSha256 = sha256(bytes)
    )

    @Test
    fun acceptsExactPinnedLayoutAndTaskModel() {
        val bytes = document()
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertTrue(result.isValid)
        assertNull(result.assetFailure)
        assertNull(result.validationFailure)
        assertEquals(layoutId, result.uvMap!!.uvLayoutId)
        assertEquals(FaceMeshTessellation.LANDMARK_COUNT, result.uvMap.vertexCount)
    }

    @Test
    fun productionRegistryAcceptsBundledCanonicalLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activeModelSha = context.assets.open("face_landmarker.task").use { input ->
            sha256(input.readBytes())
        }

        val result = AppFaceMeshUvLayouts.createRegistry(context.assets).load(
            AppFaceMeshUvLayouts.CANONICAL_MEDIAPIPE_FACE_468,
            activeModelSha
        )

        assertTrue(result.isValid)
        assertNull(result.assetFailure)
        assertNull(result.validationFailure)
        assertEquals(FaceMeshTessellation.TOPOLOGY_ID, result.uvMap!!.topologyId)
        assertEquals(FaceMeshTessellation.LANDMARK_COUNT, result.uvMap.vertexCount)
        assertEquals(activeModelSha, result.uvMap.modelAssetSha256)
        assertEquals(
            "0ed252df853d7be48694dbaa58892c47769cb4d1d881a3d2785acb12d2437b52",
            result.uvMap.uvAssetSha256
        )
    }

    @Test
    fun rejectsBytesThatDoNotMatchPinnedDigest() {
        val approvedBytes = document()
        val changedBytes = document(v = 0.51)
        val result = FaceMeshUvAssetParser.parse(changedBytes, pinned(approvedBytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.UV_ASSET_FINGERPRINT_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsDifferentActiveTaskModel() {
        val bytes = document()
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), "b".repeat(64))

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.MODEL_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsDifferentLayoutIdentifier() {
        val bytes = document(layout = "other-layout")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(document()), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.LAYOUT_ID_MISMATCH, result.assetFailure)
    }

    @Test
    fun rejectsTopologyMismatch() {
        val bytes = document(topology = "legacy-canonical-face-model")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.TOPOLOGY_MISMATCH, result.validationFailure)
    }

    @Test
    fun rejectsWrongVertexCount() {
        val bytes = document(count = FaceMeshTessellation.LANDMARK_COUNT - 1)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvMapFailure.INVALID_VERTEX_COUNT, result.validationFailure)
    }

    @Test
    fun rejectsOutOfRangeCoordinates() {
        val bytes = document(u = 1.5)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_COORDINATES, result.assetFailure)
    }

    @Test
    fun rejectsUnknownUvOrigin() {
        val bytes = document(uvOrigin = "CENTER")
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(bytes), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_UV_ORIGIN, result.assetFailure)
    }

    @Test
    fun rejectsInvalidJson() {
        val bytes = "not-json".toByteArray(Charsets.UTF_8)
        val result = FaceMeshUvAssetParser.parse(bytes, pinned(document()), modelSha)

        assertFalse(result.isValid)
        assertEquals(FaceMeshUvAssetFailure.INVALID_JSON, result.assetFailure)
    }
}
