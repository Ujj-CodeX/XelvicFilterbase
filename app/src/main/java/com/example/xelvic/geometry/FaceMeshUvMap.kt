package com.example.xelvic.geometry

import java.util.Collections

/** Defines which edge of the source UV image represents v = 0. */
enum class UvOrigin {
    TOP_LEFT,
    BOTTOM_LEFT
}

/** Immutable, normalised texture coordinate for one canonical face-surface vertex. */
data class FaceUvCoordinate(
    val u: Float,
    val v: Float
) {
    init {
        require(u.isFinite() && v.isFinite()) { "UV coordinates must be finite" }
        require(u in 0f..1f && v in 0f..1f) { "UV coordinates must be within [0, 1]" }
    }
}

/**
 * UV mapping tied to the exact Face Landmarker task model and connectivity contract.
 *
 * Construction is deliberately gated by [validate]. A landmark count alone is not sufficient
 * evidence that a UV table uses the same vertex order as the active task model.
 */
class FaceMeshUvMap private constructor(
    val topologyId: String,
    val modelAssetSha256: String,
    val uvAssetSha256: String,
    val uvOrigin: UvOrigin,
    coordinates: List<FaceUvCoordinate>
) {
    val coordinates: List<FaceUvCoordinate> =
        Collections.unmodifiableList(ArrayList(coordinates))

    val vertexCount: Int
        get() = coordinates.size

    /** Returns a fresh [u, v, ...] array for a GL-thread-owned buffer. */
    fun copyInterleavedCoordinates(): FloatArray {
        val result = FloatArray(coordinates.size * 2)
        coordinates.forEachIndexed { index, coordinate ->
            result[index * 2] = coordinate.u
            result[index * 2 + 1] = coordinate.v
        }
        return result
    }

    companion object {
        /**
         * Creates a usable map only when its topology, active model identity, and pinned asset
         * digest all agree. The expected UV digest must come from an app-controlled manifest,
         * not from the same untrusted package field that declares the digest.
         */
        fun validate(
            sourceTopologyId: String,
            expectedTopologyId: String,
            sourceModelAssetSha256: String,
            activeModelAssetSha256: String?,
            sourceUvAssetSha256: String,
            expectedUvAssetSha256: String,
            coordinates: List<FaceUvCoordinate>,
            uvOrigin: UvOrigin
        ): FaceMeshUvMapValidation {
            if (expectedTopologyId != FaceMeshTessellation.TOPOLOGY_ID ||
                sourceTopologyId != expectedTopologyId
            ) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.TOPOLOGY_MISMATCH)
            }

            if (!isSha256(sourceModelAssetSha256) ||
                !isSha256(sourceUvAssetSha256) ||
                !isSha256(expectedUvAssetSha256)
            ) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.INVALID_FINGERPRINT)
            }

            if (activeModelAssetSha256 == null || !isSha256(activeModelAssetSha256)) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.ACTIVE_MODEL_IDENTITY_MISSING)
            }

            if (sourceModelAssetSha256 != activeModelAssetSha256) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.MODEL_MISMATCH)
            }

            if (sourceUvAssetSha256 != expectedUvAssetSha256) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.UV_ASSET_FINGERPRINT_MISMATCH)
            }

            if (coordinates.size != FaceMeshTessellation.LANDMARK_COUNT) {
                return FaceMeshUvMapValidation(null, FaceMeshUvMapFailure.INVALID_VERTEX_COUNT)
            }

            // FaceUvCoordinate performs finite/range checks before it can be supplied here.
            return FaceMeshUvMapValidation(
                FaceMeshUvMap(
                    topologyId = sourceTopologyId,
                    modelAssetSha256 = sourceModelAssetSha256,
                    uvAssetSha256 = sourceUvAssetSha256,
                    uvOrigin = uvOrigin,
                    coordinates = coordinates
                ),
                null
            )
        }

        private fun isSha256(value: String): Boolean =
            value.length == 64 && value.all { char -> char in '0'..'9' || char in 'a'..'f' }
    }
}

enum class FaceMeshUvMapFailure {
    TOPOLOGY_MISMATCH,
    INVALID_FINGERPRINT,
    ACTIVE_MODEL_IDENTITY_MISSING,
    MODEL_MISMATCH,
    UV_ASSET_FINGERPRINT_MISMATCH,
    INVALID_VERTEX_COUNT
}

data class FaceMeshUvMapValidation(
    val uvMap: FaceMeshUvMap?,
    val failure: FaceMeshUvMapFailure?
) {
    val isValid: Boolean
        get() = uvMap != null && failure == null
}
