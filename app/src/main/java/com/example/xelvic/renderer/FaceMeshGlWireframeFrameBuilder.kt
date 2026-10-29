package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceMeshTessellation

/** Result of preparing one frame for the diagnostic GPU wireframe. */
enum class FaceMeshGlWireframeStatus {
    READY,
    NO_FRAME,
    INVALID_VIEW_DIMENSIONS,
    OUTPUT_BUFFER_TOO_SMALL,
    MISSING_EXACT_TRANSFORM,
    TOPOLOGY_MISMATCH,
    MODEL_IDENTITY_MISSING,
    UNSUPPORTED_LANDMARK_COUNT,
    STALE_FRAME,
    INVALID_COORDINATE
}

/**
 * CPU-side, allocation-free frame preparation for the optional GL wireframe diagnostic.
 * No fallback projection is accepted: points are drawn only when the exact transform captured
 * with the same CameraX frame is available. This class does not consume UVs or render textures.
 */
object FaceMeshGlWireframeFrameBuilder {
    const val MAX_FRAME_AGE_MS = 350L
    private const val MODEL_SHA256_LENGTH = 64

    fun update(
        renderFrame: FaceGeometryRenderFrame?,
        viewWidth: Int,
        viewHeight: Int,
        positionsNdc: FloatArray,
        mappedPoint: FloatArray,
        nowUptimeMs: Long,
        maxFrameAgeMs: Long = MAX_FRAME_AGE_MS
    ): FaceMeshGlWireframeStatus {
        if (renderFrame == null) return FaceMeshGlWireframeStatus.NO_FRAME
        if (viewWidth <= 0 || viewHeight <= 0 || maxFrameAgeMs < 0L) {
            return FaceMeshGlWireframeStatus.INVALID_VIEW_DIMENSIONS
        }
        if (positionsNdc.size < FaceMeshTessellation.LANDMARK_COUNT * 2 || mappedPoint.size < 2) {
            return FaceMeshGlWireframeStatus.OUTPUT_BUFFER_TOO_SMALL
        }

        val snapshot = renderFrame.snapshot
        if (snapshot.topologyId != FaceMeshTessellation.TOPOLOGY_ID) {
            return FaceMeshGlWireframeStatus.TOPOLOGY_MISMATCH
        }
        val modelHash = snapshot.modelAssetSha256
        if (modelHash == null || modelHash.length != MODEL_SHA256_LENGTH ||
            !modelHash.all { it in '0'..'9' || it in 'a'..'f' }
        ) {
            return FaceMeshGlWireframeStatus.MODEL_IDENTITY_MISSING
        }
        if (!FaceMeshTessellation.supportsLandmarkCount(snapshot.landmarks.size) ||
            snapshot.landmarks.size < FaceMeshTessellation.LANDMARK_COUNT
        ) {
            return FaceMeshGlWireframeStatus.UNSUPPORTED_LANDMARK_COUNT
        }
        val imageToPreview = renderFrame.imageToPreview
            ?: return FaceMeshGlWireframeStatus.MISSING_EXACT_TRANSFORM

        val ageMs = nowUptimeMs - snapshot.timestampMs
        if (ageMs < 0L || ageMs > maxFrameAgeMs) {
            return FaceMeshGlWireframeStatus.STALE_FRAME
        }

        for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
            val vertex = snapshot.landmarks[index]
            if (!imageToPreview.mapPixelInto(
                    vertex.x * snapshot.imageWidth,
                    vertex.y * snapshot.imageHeight,
                    mappedPoint
                )
            ) {
                return FaceMeshGlWireframeStatus.INVALID_COORDINATE
            }
            val xNdc = (2f * mappedPoint[0] / viewWidth) - 1f
            val yNdc = 1f - (2f * mappedPoint[1] / viewHeight)
            if (!xNdc.isFinite() || !yNdc.isFinite()) {
                return FaceMeshGlWireframeStatus.INVALID_COORDINATE
            }
            val offset = index * 2
            positionsNdc[offset] = xNdc
            positionsNdc[offset + 1] = yNdc
        }
        return FaceMeshGlWireframeStatus.READY
    }
}
