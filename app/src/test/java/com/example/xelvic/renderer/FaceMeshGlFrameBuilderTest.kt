package com.example.xelvic.renderer

import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceGeometrySnapshot
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import com.example.xelvic.geometry.FaceUvCoordinate
import com.example.xelvic.geometry.FaceVertex
import com.example.xelvic.geometry.UvOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMeshGlFrameBuilderTest {
    private val modelHash = "a".repeat(64)
    private val uvHash = "b".repeat(64)
    private val layoutId = "pinned-test-layout"
    private val identityTransform = FaceGeometryDisplayTransform(
        listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    )
    private val uvMap = FaceMeshUvMap.validate(
        sourceTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        expectedTopologyId = FaceMeshTessellation.TOPOLOGY_ID,
        sourceModelAssetSha256 = modelHash,
        activeModelAssetSha256 = modelHash,
        sourceUvAssetSha256 = uvHash,
        expectedUvAssetSha256 = uvHash,
        coordinates = List(FaceMeshTessellation.LANDMARK_COUNT) {
            FaceUvCoordinate(0.25f, 0.75f)
        },
        uvOrigin = UvOrigin.BOTTOM_LEFT,
        uvLayoutId = layoutId
    ).uvMap!!

    private fun frame(
        count: Int = FaceMeshTessellation.LANDMARK_COUNT,
        modelSha: String? = modelHash,
        topology: String? = FaceMeshTessellation.TOPOLOGY_ID,
        transform: FaceGeometryDisplayTransform? = identityTransform
    ): FaceGeometryRenderFrame {
        val snapshot = FaceGeometrySnapshot(
            timestampMs = 100L,
            imageWidth = 100,
            imageHeight = 100,
            rotationDegrees = 0,
            isMirrored = false,
            landmarks = List(count) { FaceVertex(0.5f, 0.5f, 0f) },
            topologyId = topology,
            modelAssetSha256 = modelSha
        )
        return FaceGeometryRenderFrame(snapshot, transform)
    }

    private fun update(
        renderFrame: FaceGeometryRenderFrame? = frame(),
        map: FaceMeshUvMap = uvMap,
        requiredLayout: String = layoutId,
        requiredModel: String = modelHash,
        requiredTopology: String = FaceMeshTessellation.TOPOLOGY_ID,
        width: Int = 100,
        height: Int = 100,
        positions: FloatArray = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2),
        uvs: FloatArray = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2),
        mapped: FloatArray = FloatArray(2)
    ): Boolean = FaceMeshGlFrameBuilder.update(
        renderFrame = renderFrame,
        uvMap = map,
        requiredUvLayoutId = requiredLayout,
        requiredModelAssetSha256 = requiredModel,
        requiredTopologyId = requiredTopology,
        viewWidth = width,
        viewHeight = height,
        positionsNdc = positions,
        textureCoordinates = uvs,
        mappedPoint = mapped
    )

    @Test
    fun validFrameUsesExactTransformAndConvertsToNdcAndGlUvOrigin() {
        val positions = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
        val uvs = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)

        assertTrue(update(positions = positions, uvs = uvs))
        assertEquals(0f, positions[0], 0.0001f)
        assertEquals(0f, positions[1], 0.0001f)
        assertEquals(0.25f, uvs[0], 0.0001f)
        assertEquals(0.25f, uvs[1], 0.0001f)
    }

    @Test
    fun missingExactTransformIsRejected() {
        assertFalse(update(renderFrame = frame(transform = null)))
    }

    @Test
    fun modelTopologyAndUvLayoutMismatchAreRejected() {
        assertFalse(update(renderFrame = frame(modelSha = "c".repeat(64))))
        assertFalse(update(renderFrame = frame(topology = "different-topology")))
        assertFalse(update(requiredLayout = "different-layout"))
        assertFalse(update(requiredModel = "c".repeat(64)))
    }

    @Test
    fun wrongLandmarkCountDimensionsAndOutputBuffersAreRejected() {
        assertFalse(update(renderFrame = frame(count = 467)))
        assertFalse(update(width = 0))
        assertFalse(update(height = 0))
        assertFalse(update(positions = FloatArray(2)))
        assertFalse(update(uvs = FloatArray(2)))
    }
}
