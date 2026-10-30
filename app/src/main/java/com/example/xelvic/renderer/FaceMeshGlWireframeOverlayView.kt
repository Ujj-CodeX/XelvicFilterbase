package com.example.xelvic.renderer

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.AttributeSet
import com.example.xelvic.geometry.FaceGeometryRenderFrame
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.geometry.FaceMeshTessellation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Transparent, opt-in GLES2 wireframe diagnostic. It has no dependency on UV layouts or textures.
 * Place above PreviewView only during MediaPipe diagnostics. Texture-renderer integration remains
 * separately gated on a verified model-compatible UV map.
 */
class FaceMeshGlWireframeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    private val glRenderer = FaceMeshGlWireframeRenderer()
    @Volatile private var attachedHolder: FaceGeometrySnapshotHolder? = null
    @Volatile private var listenerHandle: AutoCloseable? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setPreserveEGLContextOnPause(true)
        setRenderer(glRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOverlayEnabled(enabled: Boolean) {
        glRenderer.setEnabled(enabled)
        // Continuous draws are debug-only and ensure the age gate clears a frozen/stale face.
        renderMode = if (enabled) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    /** Attaches a listener without replacing the Canvas debug overlay's legacy callback. */
    fun setSnapshotHolder(next: FaceGeometrySnapshotHolder?) {
        if (attachedHolder === next) {
            glRenderer.publishFrame(next?.getLatest())
            requestRender()
            return
        }
        listenerHandle?.close()
        listenerHandle = null
        attachedHolder = next
        glRenderer.publishFrame(next?.getLatest())
        if (next != null) {
            listenerHandle = next.addSnapshotListener {
                if (attachedHolder === next) {
                    glRenderer.publishFrame(next.getLatest())
                    requestRender()
                }
            }
        }
        requestRender()
    }

    override fun onDetachedFromWindow() {
        setSnapshotHolder(null)
        glRenderer.setEnabled(false)
        requestRender()
        super.onDetachedFromWindow()
    }
}

private class FaceMeshGlWireframeRenderer : GLSurfaceView.Renderer {
    private val enabled = AtomicBoolean(false)
    private val latestFrame = AtomicReference<FaceGeometryRenderFrame?>(null)
    private val positions = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
    private val mappedPoint = FloatArray(2)
    private val positionBuffer = allocateFloatBuffer(positions.size)
    private val edgeIndices: ShortArray = FaceMeshTessellation.copyEdgeIndices().let { source ->
        ShortArray(source.size) { source[it].toShort() }
    }
    private val edgeBuffer = allocateShortBuffer(edgeIndices)

    @Volatile var lastStatus: FaceMeshGlWireframeStatus = FaceMeshGlWireframeStatus.NO_FRAME
        private set

    private var program = 0
    private var positionHandle = -1
    private var colorHandle = -1
    private var viewWidth = 0
    private var viewHeight = 0

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun publishFrame(frame: FaceGeometryRenderFrame?) {
        latestFrame.set(frame)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!enabled.get() || program == 0 || viewWidth <= 0 || viewHeight <= 0) {
            lastStatus = FaceMeshGlWireframeStatus.NO_FRAME
            return
        }

        lastStatus = FaceMeshGlWireframeFrameBuilder.update(
            renderFrame = latestFrame.get(),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            positionsNdc = positions,
            mappedPoint = mappedPoint,
            nowUptimeMs = SystemClock.uptimeMillis()
        )
        if (lastStatus != FaceMeshGlWireframeStatus.READY) return

        positionBuffer.clear()
        positionBuffer.put(positions)
        positionBuffer.position(0)
        edgeBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        // Magenta deliberately distinguishes the GPU diagnostic from the existing cyan Canvas wireframe.
        GLES20.glUniform4f(colorHandle, 1f, 0.18f, 0.78f, 0.9f)
        GLES20.glLineWidth(1f)
        GLES20.glDrawElements(
            GLES20.GL_LINES,
            edgeIndices.size,
            GLES20.GL_UNSIGNED_SHORT,
            edgeBuffer
        )
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        val candidate = GLES20.glCreateProgram()
        if (candidate == 0) {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            return 0
        }
        GLES20.glAttachShader(candidate, vertexShader)
        GLES20.glAttachShader(candidate, fragmentShader)
        GLES20.glLinkProgram(candidate)
        val status = IntArray(1)
        GLES20.glGetProgramiv(candidate, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(candidate)
            return 0
        }
        return candidate
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """

        private fun allocateFloatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun allocateShortBuffer(source: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(source.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().apply { put(source); position(0) }
    }
}
