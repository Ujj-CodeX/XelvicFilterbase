package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceGeometrySnapshot
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceVertex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMeshGlWireframeFrameBuilderTest {
    private val modelHash = "a".repeat(64)
    private val identity = FaceGeometryDisplayTransform(
        listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    )

    private fun frame(
        topology: String? = FaceMeshTessellation.TOPOLOGY_ID,
        hash: String? = modelHash,
        transform: FaceGeometryDisplayTransform? = identity,
        count: Int = 478,
        timestamp: Long = 100L
    ): FaceGeometryRenderFrame {
        val snapshot = FaceGeometrySnapshot(
            timestampMs = timestamp,
            imageWidth = 100,
            imageHeight = 100,
            rotationDegrees = 0,
            isMirrored = false,
            landmarks = List(count) { FaceVertex(0.5f, 0.5f, 0f) },
            topologyId = topology,
            modelAssetSha256 = hash
        )
        return FaceGeometryRenderFrame(snapshot, transform)
    }

    private fun run(
        frame: FaceGeometryRenderFrame?,
        width: Int = 100,
        height: Int = 100,
        positions: FloatArray = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2),
        now: Long = 110L
    ) = FaceMeshGlWireframeFrameBuilder.update(
        renderFrame = frame,
        viewWidth = width,
        viewHeight = height,
        positionsNdc = positions,
        mappedPoint = FloatArray(2),
        nowUptimeMs = now
    )

    @Test fun validFrameProducesNdcVertices() {
        val positions = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
        assertEquals(FaceMeshGlWireframeStatus.READY, run(frame(), positions = positions))
        assertTrue(positions.all { it == 0f })
    }

    @Test fun missingExactTransformFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.MISSING_EXACT_TRANSFORM,
            run(frame(transform = null))
        )
    }

    @Test fun topologyMismatchFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.TOPOLOGY_MISMATCH,
            run(frame(topology = "unknown-topology"))
        )
    }

    @Test
    fun missingModelIdentityFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.MODEL_IDENTITY_MISSING,
            run(frame(hash = null))
        )
    }

    @Test fun staleFrameIsNotDrawn() {
        assertEquals(FaceMeshGlWireframeStatus.STALE_FRAME, run(frame(), now = 1000L))
    }

    @Test fun unsupportedLandmarkCountFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.UNSUPPORTED_LANDMARK_COUNT,
            run(frame(count = 470))
        )
    }

    @Test fun invalidViewSizeFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.INVALID_VIEW_DIMENSIONS,
            run(frame(), width = 0)
        )
    }

    @Test fun undersizedOutputBufferFailsClosed() {
        assertEquals(
            FaceMeshGlWireframeStatus.OUTPUT_BUFFER_TOO_SMALL,
            run(frame(), positions = FloatArray(10))
        )
    }

    @Test fun absentFrameReturnsNoFrame() {
        assertEquals(FaceMeshGlWireframeStatus.NO_FRAME, run(null))
    }
}
