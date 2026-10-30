package com.example.xelvic.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
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
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.BuildConfig

enum class FaceMeshTextureUvMode {
    PINNED_CANONICAL,
    DIAGNOSTIC_FACE_SPACE
}

enum class FaceMeshGlRenderStatus {
    NOT_MOUNTED,
    WAITING_FOR_SURFACE,
    NO_EFFECT,
    WAITING_FOR_FRAME,
    STALE_FRAME,
    FRAME_REJECTED,
    DRAWN,
    SHADER_PROGRAM_UNAVAILABLE,
    BACKEND_UNAVAILABLE,
    TEXTURE_UPLOAD_FAILED,
    GL_DRAW_FAILED
}

internal const val DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID = "diagnostic_face_space_v1"
internal const val DIAGNOSTIC_MOUSTACHE_FILTER_ID = "moustache_mesh_test"
private const val FACE_MESH_MAX_FRAME_AGE_MS = 250L

/**
 * Immutable render input. The caller retains Bitmap ownership and must keep it alive and
 * unmodified while this effect is active; GL upload/deletion happens only on the GL thread.
 * Production UV maps must come from the app-owned FaceMeshUvRegistry, never from imported packages.
 * The diagnostic UV mode is limited to the bundled debug moustache package.
 */
data class FaceMeshGlEffect(
    val effectId: String,
    val topologyId: String,
    val uvLayoutId: String,
    val modelAssetSha256: String,
    val uvMap: FaceMeshUvMap?,
    val bitmap: Bitmap,
    val opacity: Float = 1f,
    val requiresDepthOcclusion: Boolean = false,
    val uvMode: FaceMeshTextureUvMode = FaceMeshTextureUvMode.PINNED_CANONICAL
) {
    init {
        require(effectId.isNotBlank()) { "Effect ID cannot be blank" }
        require(opacity.isFinite() && opacity in 0f..1f) { "Opacity must be within [0, 1]" }
        when (uvMode) {
            FaceMeshTextureUvMode.PINNED_CANONICAL ->
                require(uvMap != null) { "Canonical texture effects require a validated UV map" }
            FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE -> {
                require(uvMap == null) { "Diagnostic face-space UVs are generated per frame" }
                require(uvLayoutId == DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID) {
                    "Diagnostic face-space mode requires its reserved layout ID"
                }
                require(effectId == DIAGNOSTIC_MOUSTACHE_FILTER_ID) {
                    "Diagnostic face-space mode is reserved for the bundled moustache fixture"
                }
            }
        }
    }
}

/**
 * Transparent mesh-texture overlay placed over PreviewView only while a resolved mesh filter is active.
 * The normal CameraScreen intentionally does not mount diagnostic wireframe surfaces.
 */
class FaceMeshGlOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val meshRenderer = FaceMeshGlRenderer()

    @Volatile
    private var attachedHolder: FaceGeometrySnapshotHolder? = null

    @Volatile
    private var snapshotListenerHandle: AutoCloseable? = null

    fun setSnapshotHolder(next: FaceGeometrySnapshotHolder?) {
        if (attachedHolder === next) {
            publishFrame(next?.getLatest())
            return
        }

        snapshotListenerHandle?.close()
        snapshotListenerHandle = null
        attachedHolder = next

        publishFrame(next?.getLatest())

        if (next != null) {
            snapshotListenerHandle = next.addSnapshotListener {
                if (attachedHolder === next) {
                    publishFrame(next.getLatest())
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        setSnapshotHolder(null)
        setEffect(null)
        setDiagnosticsListener(null)
        // Ensure GL-owned texture deletion runs before GLSurfaceView stops its render thread.
        requestRender()
        super.onDetachedFromWindow()
    }

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
        if (frame != null) {
            scheduleStaleFrameClear(frame.snapshot.timestampMs)
        }
    }

    private fun scheduleStaleFrameClear(frameTimestampMs: Long) {
        postDelayed(
            {
                if (
                    isAttachedToWindow &&
                    meshRenderer.isCurrentFrame(frameTimestampMs)
                ) {
                    requestRender()
                }
            },
            FACE_MESH_MAX_FRAME_AGE_MS + STALE_FRAME_CLEAR_GRACE_MS
        )
    }

    /** Select or clear an effect without recreating the tracking provider. */
    fun setEffect(effect: FaceMeshGlEffect?) {
        meshRenderer.setEffect(effect)
        requestRender()
    }

    /** Last routing/upload result for diagnostics; read it on the UI thread if needed. */
    val lastDecision: RenderBackendDecision
        get() = meshRenderer.lastDecision

    val lastRenderStatus: FaceMeshGlRenderStatus
        get() = meshRenderer.lastRenderStatus

    fun setDiagnosticsListener(
        listener: ((FaceMeshGlRenderStatus, RenderBackendDecision) -> Unit)?
    ) {
        meshRenderer.setDiagnosticsListener(listener)
    }

    private companion object {
        const val STALE_FRAME_CLEAR_GRACE_MS = 16L
    }
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

    @Volatile
    var lastRenderStatus: FaceMeshGlRenderStatus = FaceMeshGlRenderStatus.WAITING_FOR_SURFACE
        private set

    @Volatile
    private var diagnosticsListener:
        ((FaceMeshGlRenderStatus, RenderBackendDecision) -> Unit)? = null

    private var lastPublishedDecision: RenderBackendDecision? = null

    fun setDiagnosticsListener(
        listener: ((FaceMeshGlRenderStatus, RenderBackendDecision) -> Unit)?
    ) {
        diagnosticsListener = listener
        listener?.invoke(lastRenderStatus, lastDecision)
        lastPublishedDecision = lastDecision
    }

    private fun publishRenderStatus(status: FaceMeshGlRenderStatus) {
        val decision = lastDecision
        if (status == lastRenderStatus && decision == lastPublishedDecision) return
        lastRenderStatus = status
        lastPublishedDecision = decision
        diagnosticsListener?.invoke(status, decision)
    }

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

    fun isCurrentFrame(timestampMs: Long): Boolean =
        latestFrame.get()?.snapshot?.timestampMs == timestampMs

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
            publishRenderStatus(FaceMeshGlRenderStatus.SHADER_PROGRAM_UNAVAILABLE)
        } else {
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_FRAME)
        }
        textureId = 0
        attemptedEffect = null
        uploadedEffect = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        if (width <= 0 || height <= 0) {
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_SURFACE)
        } else if (program != 0) {
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_FRAME)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0) {
            publishRenderStatus(FaceMeshGlRenderStatus.SHADER_PROGRAM_UNAVAILABLE)
            return
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_SURFACE)
            return
        }

        val effect = selectedEffect.get()
        if (effect == null) {
            releaseTexture()
            attemptedEffect = null
            latestFrame.set(null)
            lastDecision = RenderBackendDecision(
                RenderBackend.UNAVAILABLE,
                RenderBackendBlockReason.MESH_REQUIREMENTS_MISSING
            )
            publishRenderStatus(FaceMeshGlRenderStatus.NO_EFFECT)
            return
        }

        if (effect !== attemptedEffect) {
            releaseTexture()
            attemptedEffect = effect
            uploadedEffect = null
            uploadEffectIfSupported(effect)
        }
        if (uploadedEffect !== effect || textureId == 0) {
            val status = when {
                lastDecision.reason == RenderBackendBlockReason.TEXTURE_UPLOAD_FAILED ->
                    FaceMeshGlRenderStatus.TEXTURE_UPLOAD_FAILED
                lastDecision.backend == RenderBackend.UNAVAILABLE ->
                    FaceMeshGlRenderStatus.BACKEND_UNAVAILABLE
                else -> FaceMeshGlRenderStatus.TEXTURE_UPLOAD_FAILED
            }
            publishRenderStatus(status)
            return
        }

        val frame = latestFrame.get()
        if (frame == null) {
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_FRAME)
            return
        }
        val frameAgeMs = SystemClock.uptimeMillis() - frame.snapshot.timestampMs
        if (frameAgeMs < 0L || frameAgeMs > MAX_FRAME_AGE_MS) {
            latestFrame.compareAndSet(frame, null)
            publishRenderStatus(FaceMeshGlRenderStatus.STALE_FRAME)
            return
        }

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
            mappedPoint = mappedPoint,
            uvMode = effect.uvMode
        )
        if (!prepared) {
            publishRenderStatus(FaceMeshGlRenderStatus.FRAME_REJECTED)
            return
        }

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
        val drawError = GLES20.glGetError()
        if (drawError == GLES20.GL_NO_ERROR) {
            publishRenderStatus(FaceMeshGlRenderStatus.DRAWN)
        } else {
            Log.e(TAG, "Face mesh draw failed with GL error 0x${Integer.toHexString(drawError)}")
            publishRenderStatus(FaceMeshGlRenderStatus.GL_DRAW_FAILED)
        }
    }

    private fun uploadEffectIfSupported(effect: FaceMeshGlEffect) {
        if (effect.bitmap.isRecycled) {
            lastDecision = blocked(RenderBackendBlockReason.TEXTURE_UPLOAD_FAILED)
            publishRenderStatus(FaceMeshGlRenderStatus.TEXTURE_UPLOAD_FAILED)
            return
        }
        val requirements = FaceMeshTextureRequirements(
            topologyId = effect.topologyId,
            uvLayoutId = effect.uvLayoutId,
            activeModelAssetSha256 = effect.modelAssetSha256,
            uvMap = effect.uvMap,
            textureWidth = effect.bitmap.width,
            textureHeight = effect.bitmap.height,
            requiresDepthOcclusion = effect.requiresDepthOcclusion,
            uvMode = effect.uvMode,
            allowDiagnosticFaceSpace = BuildConfig.DEBUG &&
                effect.effectId == DIAGNOSTIC_MOUSTACHE_FILTER_ID
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
        if (decision.backend != RenderBackend.FACE_MESH_GL) {
            publishRenderStatus(FaceMeshGlRenderStatus.BACKEND_UNAVAILABLE)
            return
        }

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
            publishRenderStatus(FaceMeshGlRenderStatus.WAITING_FOR_FRAME)
        } catch (error: Exception) {
            Log.e(TAG, "Face mesh texture upload failed", error)
            releaseTexture()
            uploadedEffect = null
            lastDecision = blocked(RenderBackendBlockReason.TEXTURE_UPLOAD_FAILED)
            publishRenderStatus(FaceMeshGlRenderStatus.TEXTURE_UPLOAD_FAILED)
        }
    }

    private fun releaseTexture() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        uploadedEffect = null
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
            Log.e(TAG, "Shader program link failed: ${GLES20.glGetProgramInfoLog(result)}")
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
            Log.e(TAG, "Shader compilation failed: ${GLES20.glGetShaderInfoLog(shader)}")
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
        const val TAG = "FaceMeshGlOverlay"
        const val MAX_FRAME_AGE_MS = FACE_MESH_MAX_FRAME_AGE_MS

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
