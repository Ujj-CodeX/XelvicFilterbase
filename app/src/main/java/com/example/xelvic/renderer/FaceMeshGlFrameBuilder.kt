package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.UvOrigin

/**
 * CPU preparation for the transparent GL overlay. Canonical rendering accepts only a pinned
 * app-owned UV map. The single debug moustache fixture may instead derive face-bounds UVs from
 * the current frame so the GLES texture path can be exercised before a canonical UV asset is verified.
 */
object FaceMeshGlFrameBuilder {
    const val FLOATS_PER_VERTEX = 2

    /** Writes NDC XY and normalized UVs into caller-owned reusable buffers. */
    fun update(
        renderFrame: FaceGeometryRenderFrame?,
        uvMap: FaceMeshUvMap?,
        requiredUvLayoutId: String,
        requiredModelAssetSha256: String,
        requiredTopologyId: String,
        viewWidth: Int,
        viewHeight: Int,
        positionsNdc: FloatArray,
        textureCoordinates: FloatArray,
        mappedPoint: FloatArray,
        uvMode: FaceMeshTextureUvMode = FaceMeshTextureUvMode.PINNED_CANONICAL
    ): Boolean {
        if (renderFrame == null || viewWidth <= 0 || viewHeight <= 0) return false
        if (requiredTopologyId != FaceMeshTessellation.TOPOLOGY_ID) return false
        if (requiredModelAssetSha256.length != 64 ||
            !requiredModelAssetSha256.all { it in '0'..'9' || it in 'a'..'f' }
        ) return false
        if (positionsNdc.size < FaceMeshTessellation.LANDMARK_COUNT * 2 ||
            textureCoordinates.size < FaceMeshTessellation.LANDMARK_COUNT * 2 ||
            mappedPoint.size < 2
        ) return false

        val pinnedMap = when (uvMode) {
            FaceMeshTextureUvMode.PINNED_CANONICAL -> {
                val candidate = uvMap ?: return false
                if (candidate.topologyId != requiredTopologyId ||
                    candidate.vertexCount != FaceMeshTessellation.LANDMARK_COUNT ||
                    requiredUvLayoutId.isBlank() ||
                    candidate.uvLayoutId != requiredUvLayoutId ||
                    candidate.modelAssetSha256 != requiredModelAssetSha256
                ) return false
                candidate
            }
            FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE -> {
                if (uvMap != null ||
                    requiredUvLayoutId != DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID
                ) return false
                null
            }
        }

        val snapshot = renderFrame.snapshot
        val imageToPreview = renderFrame.imageToPreview ?: return false
        if (snapshot.topologyId != requiredTopologyId) return false
        if (snapshot.modelAssetSha256 != requiredModelAssetSha256) return false
        if (!FaceMeshTessellation.supportsLandmarkCount(snapshot.landmarks.size) ||
            snapshot.landmarks.size < FaceMeshTessellation.LANDMARK_COUNT
        ) return false

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        if (uvMode == FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE) {
            for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
                val vertex = snapshot.landmarks[index]
                if (!vertex.x.isFinite() || !vertex.y.isFinite()) return false
                minX = minOf(minX, vertex.x)
                maxX = maxOf(maxX, vertex.x)
                minY = minOf(minY, vertex.y)
                maxY = maxOf(maxY, vertex.y)
            }
            if (maxX - minX <= 1e-5f || maxY - minY <= 1e-5f) return false
        }

        for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
            val vertex = snapshot.landmarks[index]
            if (!imageToPreview.mapPixelInto(
                    vertex.x * snapshot.imageWidth,
                    vertex.y * snapshot.imageHeight,
                    mappedPoint
                )
            ) return false

            val xNdc = (2f * mappedPoint[0] / viewWidth) - 1f
            val yNdc = 1f - (2f * mappedPoint[1] / viewHeight)
            if (!xNdc.isFinite() || !yNdc.isFinite()) return false

            val positionOffset = index * 2
            positionsNdc[positionOffset] = xNdc
            positionsNdc[positionOffset + 1] = yNdc

            if (uvMode == FaceMeshTextureUvMode.PINNED_CANONICAL) {
                val canonicalMap = pinnedMap ?: return false
                val uv = canonicalMap.coordinates[index]
                textureCoordinates[positionOffset] = uv.u
                textureCoordinates[positionOffset + 1] = when (canonicalMap.uvOrigin) {
                    UvOrigin.TOP_LEFT -> uv.v
                    UvOrigin.BOTTOM_LEFT -> 1f - uv.v
                }
            } else {
                // R&D only: the transparent 2D test image follows the current face bounds.
                // This exercises GLES triangle/alpha rendering, not canonical UV registration.
                textureCoordinates[positionOffset] =
                    ((vertex.x - minX) / (maxX - minX)).coerceIn(0f, 1f)
                textureCoordinates[positionOffset + 1] =
                    ((vertex.y - minY) / (maxY - minY)).coerceIn(0f, 1f)
            }
        }
        return true
    }
}
