package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.UvOrigin

/**
 * Allocation-free CPU preparation for the transparent GL overlay.
 *
 * It deliberately requires the exact CameraX image-to-preview transform carried by the same
 * inference frame. The estimated FILL_CENTER fallback is not accepted for texture rendering.
 * UV coordinates are copied only from an app-validated FaceMeshUvMap and the layout ID must
 * match the selected effect's requirement.
 */
object FaceMeshGlFrameBuilder {
    const val FLOATS_PER_VERTEX = 2

    /** Writes NDC XY and normalized UVs into caller-owned reusable buffers. */
    fun update(
        renderFrame: FaceGeometryRenderFrame?,
        uvMap: FaceMeshUvMap,
        requiredUvLayoutId: String,
        requiredModelAssetSha256: String,
        requiredTopologyId: String,
        viewWidth: Int,
        viewHeight: Int,
        positionsNdc: FloatArray,
        textureCoordinates: FloatArray,
        mappedPoint: FloatArray
    ): Boolean {
        if (renderFrame == null || viewWidth <= 0 || viewHeight <= 0) return false
        if (requiredTopologyId != FaceMeshTessellation.TOPOLOGY_ID) return false
        if (uvMap.topologyId != requiredTopologyId) return false
        if (uvMap.vertexCount != FaceMeshTessellation.LANDMARK_COUNT) return false
        if (requiredUvLayoutId.isBlank() || uvMap.uvLayoutId != requiredUvLayoutId) return false
        if (uvMap.modelAssetSha256 != requiredModelAssetSha256) return false
        if (requiredModelAssetSha256.length != 64 ||
            !requiredModelAssetSha256.all { it in '0'..'9' || it in 'a'..'f' }
        ) return false
        if (positionsNdc.size < FaceMeshTessellation.LANDMARK_COUNT * 2 ||
            textureCoordinates.size < FaceMeshTessellation.LANDMARK_COUNT * 2 ||
            mappedPoint.size < 2
        ) return false

        val snapshot = renderFrame.snapshot
        val imageToPreview = renderFrame.imageToPreview ?: return false
        if (snapshot.topologyId != requiredTopologyId) return false
        if (snapshot.modelAssetSha256 != requiredModelAssetSha256) return false
        if (!FaceMeshTessellation.supportsLandmarkCount(snapshot.landmarks.size)) return false
        if (snapshot.landmarks.size < FaceMeshTessellation.LANDMARK_COUNT) return false

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

            val uv = uvMap.coordinates[index]
            textureCoordinates[positionOffset] = uv.u
            textureCoordinates[positionOffset + 1] = when (uvMap.uvOrigin) {
                // GLUtils uploads Android Bitmap row 0 (the top row) at texture v=0.
                UvOrigin.TOP_LEFT -> uv.v
                UvOrigin.BOTTOM_LEFT -> 1f - uv.v
            }
        }
        return true
    }
}
