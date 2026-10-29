package com.example.xelvic.geometry

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * App-owned allow-list entry for a UV-layout asset.
 *
 * expectedSha256 must come from trusted app configuration/release review, not from a .xfilter
 * manifest. The layout asset itself binds its data to the exact Face Landmarker task SHA-256.
 */
data class PinnedFaceMeshUvLayout(
    val layoutId: String,
    val assetPath: String,
    val expectedSha256: String,
    val topologyId: String = FaceMeshTessellation.TOPOLOGY_ID
) {
    init {
        require(layoutId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
            "Invalid pinned UV layout ID"
        }
        require(isSha256(expectedSha256)) {
            "Pinned UV asset SHA-256 must be lowercase hexadecimal"
        }
        require(topologyId == FaceMeshTessellation.TOPOLOGY_ID) {
            "Unsupported UV topology ID"
        }
        require(isSafeAssetPath(assetPath)) {
            "Pinned UV asset path is unsafe"
        }
    }

    private companion object {
        fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        fun isSafeAssetPath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
            val segments = path.split('/')
            return segments.none { it.isBlank() || it == "." || it == ".." } &&
                !Regex("^[A-Za-z]:").containsMatchIn(path)
        }
    }
}

enum class FaceMeshUvAssetFailure {
    UNREGISTERED_LAYOUT,
    ASSET_READ_FAILED,
    INVALID_JSON,
    UNSUPPORTED_ASSET_SCHEMA,
    MISSING_OR_INVALID_FIELD,
    LAYOUT_ID_MISMATCH,
    INVALID_UV_ORIGIN,
    INVALID_COORDINATES
}

data class FaceMeshUvAssetResult(
    val uvMap: FaceMeshUvMap? = null,
    val assetFailure: FaceMeshUvAssetFailure? = null,
    val validationFailure: FaceMeshUvMapFailure? = null
) {
    val isValid: Boolean
        get() = uvMap != null && assetFailure == null && validationFailure == null
}

/**
 * Parser for app-owned UV asset JSON. It deliberately never trusts a digest supplied by the
 * filter package: the expected digest is provided by [PinnedFaceMeshUvLayout].
 *
 * Asset format (schema 1):
 * {
 *   "schemaVersion": 1,
 *   "uvLayoutId": "pinned-layout-id",
 *   "topologyId": "mediapipe-task-face-468-tessellation-v1",
 *   "modelAssetSha256": "<exact face_landmarker.task SHA-256>",
 *   "uvOrigin": "BOTTOM_LEFT",
 *   "coordinates": [{"u": 0.5, "v": 0.5}, ...] // exactly 468 pairs
 * }
 *
 * The file's SHA-256 is computed over its exact bytes and checked against the pinned registry.
 */
object FaceMeshUvAssetParser {
    fun parse(
        bytes: ByteArray,
        pinnedLayout: PinnedFaceMeshUvLayout,
        activeModelAssetSha256: String?
    ): FaceMeshUvAssetResult {
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return assetFailure(FaceMeshUvAssetFailure.INVALID_JSON)
        }

        val schema = root.opt("schemaVersion") as? Number
        if (schema == null || schema.toInt() != 1 || schema.toDouble() != 1.0) {
            return assetFailure(FaceMeshUvAssetFailure.UNSUPPORTED_ASSET_SCHEMA)
        }

        val layoutId = root.opt("uvLayoutId") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        if (layoutId != pinnedLayout.layoutId) {
            return assetFailure(FaceMeshUvAssetFailure.LAYOUT_ID_MISMATCH)
        }

        val topologyId = root.opt("topologyId") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val modelSha = root.opt("modelAssetSha256") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val originText = root.opt("uvOrigin") as? String
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)
        val uvOrigin = try {
            UvOrigin.valueOf(originText)
        } catch (_: Exception) {
            return assetFailure(FaceMeshUvAssetFailure.INVALID_UV_ORIGIN)
        }
        val jsonCoordinates: JSONArray = root.optJSONArray("coordinates")
            ?: return assetFailure(FaceMeshUvAssetFailure.MISSING_OR_INVALID_FIELD)

        val coordinates = ArrayList<FaceUvCoordinate>(jsonCoordinates.length())
        for (index in 0 until jsonCoordinates.length()) {
            val point = jsonCoordinates.optJSONObject(index)
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            val u = point.opt("u") as? Number
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            val v = point.opt("v") as? Number
                ?: return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            try {
                coordinates += FaceUvCoordinate(u.toFloat(), v.toFloat())
            } catch (_: IllegalArgumentException) {
                return assetFailure(FaceMeshUvAssetFailure.INVALID_COORDINATES)
            }
        }

        val actualAssetSha256 = sha256(bytes)
        val validation = FaceMeshUvMap.validate(
            sourceTopologyId = topologyId,
            expectedTopologyId = pinnedLayout.topologyId,
            sourceModelAssetSha256 = modelSha,
            activeModelAssetSha256 = activeModelAssetSha256,
            sourceUvAssetSha256 = actualAssetSha256,
            expectedUvAssetSha256 = pinnedLayout.expectedSha256,
            coordinates = coordinates,
            uvOrigin = uvOrigin,
            uvLayoutId = layoutId
        )
        return FaceMeshUvAssetResult(
            uvMap = validation.uvMap,
            validationFailure = validation.failure
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun assetFailure(failure: FaceMeshUvAssetFailure) =
        FaceMeshUvAssetResult(assetFailure = failure)
}

/** Loads UV maps only from an app-controlled allow-list, never from arbitrary package paths. */
class FaceMeshUvRegistry(
    private val assetManager: AssetManager,
    pinnedLayouts: Collection<PinnedFaceMeshUvLayout>
) {
    private val layoutsById: Map<String, PinnedFaceMeshUvLayout> =
        pinnedLayouts.associateBy { it.layoutId }.also { map ->
            require(map.size == pinnedLayouts.size) { "Duplicate pinned UV layout ID" }
        }

    fun load(layoutId: String, activeModelAssetSha256: String?): FaceMeshUvAssetResult {
        val pinnedLayout = layoutsById[layoutId]
            ?: return FaceMeshUvAssetResult(assetFailure = FaceMeshUvAssetFailure.UNREGISTERED_LAYOUT)
        return try {
            assetManager.open(pinnedLayout.assetPath).use { input ->
                FaceMeshUvAssetParser.parse(input.readBytes(), pinnedLayout, activeModelAssetSha256)
            }
        } catch (_: Exception) {
            FaceMeshUvAssetResult(assetFailure = FaceMeshUvAssetFailure.ASSET_READ_FAILED)
        }
    }
}
