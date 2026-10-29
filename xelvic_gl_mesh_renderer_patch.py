#!/usr/bin/env python3
"""Local-only R3 patch: add an opt-in GLES2 face-mesh texture renderer core.

Run from the XelvicFilterbase project root. Default mode is preflight only; --apply
writes local files and backs up README.md. This script never runs Git commands.
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from datetime import datetime
from pathlib import Path

BUILDER_PATH = Path("app/src/main/java/com/example/xelvic/renderer/FaceMeshGlFrameBuilder.kt")
RENDERER_PATH = Path("app/src/main/java/com/example/xelvic/renderer/FaceMeshGlOverlayView.kt")
TEST_PATH = Path("app/src/test/java/com/example/xelvic/renderer/FaceMeshGlFrameBuilderTest.kt")
README_PATH = Path("README.md")
README_HEADING = "## GLES2 Mesh Renderer Core — R3 Research Spike"

BUILDER_SOURCE = r"""package com.example.xelvic.renderer

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
"""

RENDERER_SOURCE = r"""package com.example.xelvic.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.AttributeSet
import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceMeshUvMap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Immutable render input. The caller retains Bitmap ownership and must keep it alive and
 * unmodified while this effect is active; GL upload/deletion happens only on the GL thread.
 * The UV map must come from FaceMeshUvRegistry, never directly from an imported .xfilter.
 */
data class FaceMeshGlEffect(
    val effectId: String,
    val topologyId: String,
    val uvLayoutId: String,
    val modelAssetSha256: String,
    val uvMap: FaceMeshUvMap,
    val bitmap: Bitmap,
    val opacity: Float = 1f,
    val requiresDepthOcclusion: Boolean = false
) {
    init {
        require(effectId.isNotBlank()) { "Effect ID cannot be blank" }
        require(opacity.isFinite() && opacity in 0f..1f) { "Opacity must be within [0, 1]" }
    }
}

/**
 * Opt-in transparent Path-A overlay: place over PreviewView, below Compose controls.
 * This is an R&D renderer core, not yet wired to CameraScreen or the .xfilter selection flow.
 */
class FaceMeshGlOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val meshRenderer = FaceMeshGlRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // Keep Compose controls above the SurfaceView while allowing it to overlay PreviewView.
        setZOrderMediaOverlay(true)
        setPreserveEGLContextOnPause(true)
        setRenderer(meshRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Publish the latest immutable geometry/transform pair. Safe from a MediaPipe callback. */
    fun publishFrame(frame: FaceGeometryRenderFrame?) {
        meshRenderer.publishFrame(frame)
        requestRender()
    }

    /** Select or clear an effect without recreating the tracking provider. */
    fun setEffect(effect: FaceMeshGlEffect?) {
        meshRenderer.setEffect(effect)
        requestRender()
    }

    /** Last routing/upload result for diagnostics; read it on the UI thread if needed. */
    val lastDecision: RenderBackendDecision
        get() = meshRenderer.lastDecision
}

private class FaceMeshGlRenderer : GLSurfaceView.Renderer {
    private val latestFrame = AtomicReference<FaceGeometryRenderFrame?>(null)
    private val selectedEffect = AtomicReference<FaceMeshGlEffect?>(null)

    @Volatile
    var lastDecision: RenderBackendDecision = RenderBackendDecision(
        RenderBackend.UNAVAILABLE,
        RenderBackendBlockReason.MESH_REQUIREMENTS_MISSING
    )
        private set

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var program = 0
    private var positionHandle = -1
    private var uvHandle = -1
    private var textureHandle = -1
    private var opacityHandle = -1
    private var textureId = 0
    private var attemptedEffect: FaceMeshGlEffect? = null
    private var uploadedEffect: FaceMeshGlEffect? = null
    private var maxTextureSize = 0

    // All CPU-side buffers are allocated once and reused on the GL thread.
    private val positions = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
    private val uvs = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
    private val mappedPoint = FloatArray(2)
    private val positionBuffer: FloatBuffer = directFloatBuffer(positions.size)
    private val uvBuffer: FloatBuffer = directFloatBuffer(uvs.size)
    private val indexBuffer: ShortBuffer = directShortBuffer(
        FaceMeshTessellation.copyTriangleIndices().let { indices ->
            ShortArray(indices.size) { indices[it].toShort() }
        }
    )

    fun publishFrame(frame: FaceGeometryRenderFrame?) {
        latestFrame.set(frame)
    }

    fun setEffect(effect: FaceMeshGlEffect?) {
        selectedEffect.set(effect)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Android decoded bitmaps are normally premultiplied; shader scales RGB and alpha together.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
            opacityHandle = GLES20.glGetUniformLocation(program, "uOpacity")
        }
        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        maxTextureSize = maxSize[0]
        if (program == 0) {
            lastDecision = blocked(RenderBackendBlockReason.SHADER_PROGRAM_UNAVAILABLE)
        }
        textureId = 0
        attemptedEffect = null
        uploadedEffect = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return

        val effect = selectedEffect.get()
        if (effect == null) {
            releaseTexture()
            attemptedEffect = null
            uploadedEffect = null
            lastDecision = RenderBackendDecision(
                RenderBackend.UNAVAILABLE,
                RenderBackendBlockReason.MESH_REQUIREMENTS_MISSING
            )
            return
        }

        if (effect !== attemptedEffect) {
            releaseTexture()
            attemptedEffect = effect
            uploadedEffect = null
            uploadEffectIfSupported(effect)
        }
        if (uploadedEffect !== effect || textureId == 0) return

        val frame = latestFrame.get()
        if (frame == null || SystemClock.uptimeMillis() - frame.snapshot.timestampMs > MAX_FRAME_AGE_MS) return

        val prepared = FaceMeshGlFrameBuilder.update(
            renderFrame = frame,
            uvMap = effect.uvMap,
            requiredUvLayoutId = effect.uvLayoutId,
            requiredModelAssetSha256 = effect.modelAssetSha256,
            requiredTopologyId = effect.topologyId,
            viewWidth = surfaceWidth,
            viewHeight = surfaceHeight,
            positionsNdc = positions,
            textureCoordinates = uvs,
            mappedPoint = mappedPoint
        )
        if (!prepared) return

        positionBuffer.clear()
        positionBuffer.put(positions)
        positionBuffer.position(0)
        uvBuffer.clear()
        uvBuffer.put(uvs)
        uvBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform1f(opacityHandle, effect.opacity)
        indexBuffer.position(0)
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            FaceMeshTessellation.TRIANGLE_INDEX_COUNT,
            GLES20.GL_UNSIGNED_SHORT,
            indexBuffer
        )
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
    }

    private fun uploadEffectIfSupported(effect: FaceMeshGlEffect) {
        if (effect.bitmap.isRecycled) {
            lastDecision = blocked(RenderBackendBlockReason.TEXTURE_UPLOAD_FAILED)
            return
        }
        val requirements = FaceMeshTextureRequirements(
            topologyId = effect.topologyId,
            uvLayoutId = effect.uvLayoutId,
            activeModelAssetSha256 = effect.modelAssetSha256,
            uvMap = effect.uvMap,
            textureWidth = effect.bitmap.width,
            textureHeight = effect.bitmap.height,
            requiresDepthOcclusion = effect.requiresDepthOcclusion
        )
        val decision = RenderBackendRouter.select(
            intent = EffectRenderIntent.FACE_MESH_TEXTURE,
            mesh = requirements,
            capabilities = RenderBackendCapabilities(
                openGlEsMajorVersion = 2,
                maxTextureSize = maxTextureSize,
                depthBufferAvailable = false
            )
        )
        lastDecision = decision
        if (decision.backend != RenderBackend.FACE_MESH_GL) return

        try {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            if (textureId == 0) throw IllegalStateException("glGenTextures returned texture 0")

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, effect.bitmap, 0)
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) {
                throw IllegalStateException("Texture upload failed with GL error 0x${Integer.toHexString(error)}")
            }
            uploadedEffect = effect
        } catch (_: Exception) {
            releaseTexture()
            uploadedEffect = null
            lastDecision = blocked(RenderBackendBlockReason.TEXTURE_UPLOAD_FAILED)
        }
    }

    private fun releaseTexture() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun blocked(reason: RenderBackendBlockReason) =
        RenderBackendDecision(RenderBackend.UNAVAILABLE, reason)

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == null) {
            GLES20.glDeleteShader(vertex)
            return 0
        }
        val result = GLES20.glCreateProgram()
        if (result == 0) {
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return 0
        }
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteProgram(result)
            return 0
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun directShortBuffer(values: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(values.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(values); position(0) }

    private companion object {
        const val MAX_FRAME_AGE_MS = 250L

        val VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
                "attribute vec2 aTexCoord;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "    vTexCoord = aTexCoord;\n" +
                "}\n"

        val FRAGMENT_SHADER =
            "precision mediump float;\n" +
                "uniform sampler2D uTexture;\n" +
                "uniform float uOpacity;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTexCoord) * uOpacity;\n" +
                "}\n"
    }
}
"""

TEST_SOURCE = r"""package com.example.xelvic.renderer

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
"""

README_NOTE = r"""## GLES2 Mesh Renderer Core — R3 Research Spike

R3 introduces an opt-in `FaceMeshGlOverlayView` and `FaceMeshGlFrameBuilder` as a standalone GLES 2.0 proof-of-concept. The view uses a transparent `GLSurfaceView` overlay (Path A), uploads a supplied Bitmap on the GL thread, reuses the static 852-triangle Tasks API tessellation, and updates only per-frame vertex/UV buffers. The frame builder requires the exact CameraX image-to-preview transform attached to that inference result, a validated `FaceMeshUvMap`, matching model SHA-256, topology ID, and UV-layout ID. Stale frames older than 250 ms are not drawn.

**Integration status:** this renderer is not attached to `CameraScreen` and is not yet fed by the `.xfilter` selection path. No production UV layout is registered, so real package effects remain blocked until a model-compatible UV asset is independently verified and pinned. The current renderer does not implement depth pre-pass, self-occlusion, still-photo rendering, or a device-validated performance budget. GL texture upload/deletion remains GL-thread-only; callers must keep the supplied Bitmap alive and unchanged while selected. Test `FaceMeshGlFrameBuilderTest` covers transform requirements, model/topology/layout mismatch, UV-origin conversion, dimensions, and buffer sizing.
"""


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one anchor for {label}; found {count}")
    return source.replace(old, new, 1)


def read_text(root: Path, relative: Path) -> str:
    path = root / relative
    if not path.is_file():
        raise RuntimeError(f"Required file is missing: {relative.as_posix()}")
    return path.read_text(encoding="utf-8")


def build_changes(root: Path) -> dict[Path, str]:
    uv_map = read_text(root, Path("app/src/main/java/com/example/xelvic/geometry/FaceMeshUvMap.kt"))
    parser = read_text(root, Path("app/src/main/java/com/example/xelvic/geometry/FaceMeshUvAssetParser.kt"))
    router = read_text(root, Path("app/src/main/java/com/example/xelvic/renderer/RenderBackendRouter.kt"))
    package_manager = read_text(root, Path("app/src/main/java/com/example/xelvic/packagemanager/FilterPackageManager.kt"))
    readme = read_text(root, README_PATH)

    if "val uvLayoutId: String?" not in uv_map or "uvLayoutId = uvLayoutId" not in uv_map:
        raise RuntimeError("The UV-layout validation patch is missing or incomplete; apply and test xelvic_uv_registry_patch.py first")
    if "object FaceMeshUvAssetParser" not in parser or "class FaceMeshUvRegistry" not in parser:
        raise RuntimeError("FaceMeshUvAssetParser/FaceMeshUvRegistry were not found; apply the prior UV registry patch first")
    if "UV_LAYOUT_MISMATCH" not in router or "uvMap.uvLayoutId != requiredUvLayoutId" not in router:
        raise RuntimeError("RenderBackendRouter does not yet enforce the validated UV-layout ID")
    if "TEXTURE_UPLOAD_FAILED" in router or "SHADER_PROGRAM_UNAVAILABLE" in router:
        raise RuntimeError("Renderer failure-reason enum already contains R3 entries; inspect current edits before rerunning")
    router_new = replace_once(
        router,
        "    INVALID_TEXTURE_DIMENSIONS,\n    GPU_TEXTURE_LIMIT_UNKNOWN,",
        "    INVALID_TEXTURE_DIMENSIONS,\n    TEXTURE_UPLOAD_FAILED,\n    SHADER_PROGRAM_UNAVAILABLE,\n    GPU_TEXTURE_LIMIT_UNKNOWN,",
        "GLES2 renderer diagnostic block reasons",
    )
    if "meshTexture?.let" not in package_manager:
        raise RuntimeError("The schema-v2 package validation patch is missing; apply that patch first")
    if README_HEADING in readme:
        raise RuntimeError("README already contains the R3 renderer note; refusing duplicate application")

    for path in (BUILDER_PATH, RENDERER_PATH, TEST_PATH):
        if (root / path).exists():
            raise RuntimeError(f"Refusing to overwrite existing file: {path.as_posix()}")

    readme_new = readme.rstrip() + "\n\n" + README_NOTE.strip() + "\n"
    return {
        BUILDER_PATH: BUILDER_SOURCE,
        RENDERER_PATH: RENDERER_SOURCE,
        TEST_PATH: TEST_SOURCE,
        Path("app/src/main/java/com/example/xelvic/renderer/RenderBackendRouter.kt"): router_new,
        README_PATH: readme_new,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write files; default is preflight only")
    args = parser.parse_args()
    root = Path.cwd()
    if not (root / "settings.gradle.kts").is_file() or not (root / "app" / "build.gradle.kts").is_file():
        print("ERROR: run from the project root (settings.gradle.kts and app/build.gradle.kts required).", file=sys.stderr)
        return 2
    try:
        changes = build_changes(root)
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    print("R3 GLES2 renderer patch preflight passed. Files planned:")
    for path in changes:
        print(f"  {path.as_posix()}")
    if not args.apply:
        print("\nPreflight only; no files changed. Run with --apply to write local edits. No Git commands are run.")
        return 0

    backup_dir = Path(tempfile.gettempdir()) / ("xelvic-gl-renderer-backup-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup_dir.mkdir(parents=True, exist_ok=False)
    existing = {path: (root / path).read_text(encoding="utf-8") for path in changes if (root / path).exists()}
    (backup_dir / README_PATH).parent.mkdir(parents=True, exist_ok=True)
    (backup_dir / README_PATH).write_text(existing[README_PATH], encoding="utf-8")

    written: list[Path] = []
    try:
        for relative, content in changes.items():
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8", newline="\n")
            written.append(relative)
    except Exception as error:
        for relative in reversed(written):
            destination = root / relative
            if relative in existing:
                destination.write_text(existing[relative], encoding="utf-8", newline="\n")
            else:
                destination.unlink(missing_ok=True)
        print(f"ERROR: write failed; rolled back files changed in this run: {error}", file=sys.stderr)
        return 3

    print(f"\nApplied local working-tree edits. README backup: {backup_dir / README_PATH}")
    print("No Git commands were run; nothing was committed or pushed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
