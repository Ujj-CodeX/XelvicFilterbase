package com.example.xelvic.renderer

import android.app.Activity
import android.graphics.Bitmap
import android.opengl.GLES20
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import com.example.xelvic.geometry.FaceGeometrySnapshot
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.FaceVertex
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** GL IDs/limits are shadowed: tests prove lifecycle/control flow, not a physical GLES driver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [FaceMeshGlLifecycleTest.GlLifecycleShadow::class])
class FaceMeshGlLifecycleTest {
    private val views = mutableListOf<FaceMeshGlOverlayView>()
    private val bitmaps = mutableListOf<Bitmap>()

    @After fun tearDown() {
        views.forEach { it.releaseOverlay() }
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
        GlLifecycleShadow.deletedTextures.clear()
    }

    private fun effect(): FaceMeshGlEffect {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        return FaceMeshGlEffect(
            DIAGNOSTIC_MOUSTACHE_FILTER_ID, FaceMeshTessellation.TOPOLOGY_ID,
            DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID, "a".repeat(64), null, bitmap,
            uvMode = FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE
        )
    }

    private fun snapshot(timestamp: Long = SystemClock.uptimeMillis()) = FaceGeometrySnapshot(
        timestamp, 400, 600, 0, false,
        List(FaceMeshTessellation.LANDMARK_COUNT) { FaceVertex(0.5f, 0.5f, 0f) },
        topologyId = FaceMeshTessellation.TOPOLOGY_ID, modelAssetSha256 = "a".repeat(64)
    )

    private fun mountedView(): FaceMeshGlOverlayView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val view = FaceMeshGlOverlayView(activity).also(views::add)
        root.addView(view, FrameLayout.LayoutParams(400, 600))
        view.layout(0, 0, 400, 600)
        return view
    }

    private fun renderer(view: FaceMeshGlOverlayView) =
        ReflectionHelpers.getField<FaceMeshGlRenderer>(view, "meshRenderer")

    private fun latest(view: FaceMeshGlOverlayView) =
        ReflectionHelpers.getField<AtomicReference<*>>(renderer(view), "latestFrame").get()

    @Test fun finalReleaseAndDetachReleaseHolderEffectListenersAndRetainedContextPolicy() {
        val view = mountedView()
        val holder = FaceGeometrySnapshotHolder()
        view.setSnapshotHolder(holder)
        view.setEffect(effect())
        holder.publish(snapshot())
        assertNotNull(latest(view))
        view.releaseOverlay()
        (view.parent as FrameLayout).removeView(view)
        assertNull(ReflectionHelpers.getField<Any?>(view, "attachedHolder"))
        assertNull(ReflectionHelpers.getField<Any?>(view, "snapshotListenerHandle"))
        assertNull(latest(view))
        assertNull(ReflectionHelpers.getField<AtomicReference<*>>(renderer(view), "selectedEffect").get())
        assertFalse(view.preserveEGLContextOnPause)
        holder.publish(snapshot(SystemClock.uptimeMillis() + 1))
        assertNull(latest(view))
        view.releaseOverlay() // onRelease after detach is intentionally idempotent.
    }

    @Test fun transientDetachAndReattachResubscribesWithoutLosingEffectOrPauseState() {
        val view = mountedView()
        val root = view.parent as FrameLayout
        val holder = FaceGeometrySnapshotHolder()
        val effect = effect()
        view.setSnapshotHolder(holder)
        view.setEffect(effect)
        holder.publish(snapshot())
        view.setHostResumed(false)
        root.removeView(view)
        assertNull(ReflectionHelpers.getField<Any?>(view, "snapshotListenerHandle"))
        assertNull(latest(view))
        val replacementSnapshot = snapshot(SystemClock.uptimeMillis() + 1)
        holder.publish(replacementSnapshot)
        assertNull(latest(view))
        root.addView(view, FrameLayout.LayoutParams(400, 600))
        assertNotNull(ReflectionHelpers.getField<Any?>(view, "snapshotListenerHandle"))
        assertSame(holder.getLatest(), latest(view))
        assertSame(effect, ReflectionHelpers.getField<AtomicReference<*>>(renderer(view), "selectedEffect").get())
        assertTrue(ReflectionHelpers.getField<Boolean>(view, "glPaused"))
    }

    @Test fun callbackReadyBeforeDetachCannotRepublishIntoTheReleasedSurface() {
        val view = mountedView()
        val holder = FaceGeometrySnapshotHolder()
        view.setSnapshotHolder(holder)
        holder.publish(snapshot())
        val ready = CountDownLatch(1)
        val publishAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val callback = executor.submit {
                ready.countDown()
                assertTrue(publishAllowed.await(5, TimeUnit.SECONDS))
                ReflectionHelpers.callInstanceMethod<Unit>(view, "publishHolderFrameIfCurrent",
                    ClassParameter.from(FaceGeometrySnapshotHolder::class.java, holder))
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            view.releaseOverlay()
            publishAllowed.countDown()
            callback.get(5, TimeUnit.SECONDS)
            assertNull(latest(view))
        } finally {
            publishAllowed.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun queuedDiagnosticsAreRejectedAfterReleaseOrListenerReplacement() {
        val view = mountedView()
        var staleCalls = 0
        var replacementCalls = 0
        view.setDiagnosticsListener { _, _ -> staleCalls++ }
        view.setDiagnosticsListener { _, _ -> replacementCalls++ }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, staleCalls)
        assertEquals(1, replacementCalls)
        view.setDiagnosticsListener { _, _ -> staleCalls++ }
        view.setSurfaceDiagnosticsListener { staleCalls++ }
        view.releaseOverlay()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, staleCalls)
    }

    @Test fun queuedOlderDiagnosticStatusCannotOverwriteTheLatestGlStatus() {
        val view = mountedView()
        val statuses = mutableListOf<FaceMeshGlRenderStatus>()
        view.setDiagnosticsListener { status, _ -> statuses.add(status) }
        val renderer = renderer(view)
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 400, 600)
        renderer.onDrawFrame(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(FaceMeshGlRenderStatus.NO_EFFECT), statuses)
    }

    @Test fun ordinaryPausePreservesSelectedEffectButFinalReleaseClearsIt() {
        val view = mountedView()
        val effect = effect()
        view.setEffect(effect)
        view.setHostResumed(false)
        view.setHostResumed(false)
        assertTrue(view.preserveEGLContextOnPause)
        assertSame(effect, ReflectionHelpers.getField<AtomicReference<*>>(renderer(view), "selectedEffect").get())
        view.setHostResumed(true)
        view.releaseOverlay()
        assertFalse(view.preserveEGLContextOnPause)
        assertNull(ReflectionHelpers.getField<AtomicReference<*>>(renderer(view), "selectedEffect").get())
    }

    @Test fun recreatedContextForgetsOldIdsAndRetriesUploadingTheSameEffect() {
        val renderer = FaceMeshGlRenderer()
        val effect = effect()
        renderer.setEffect(effect)
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 400, 600)
        ReflectionHelpers.setField(renderer, "textureId", 99)
        ReflectionHelpers.setField(renderer, "attemptedEffect", effect)
        ReflectionHelpers.setField(renderer, "uploadedEffect", effect)
        GlLifecycleShadow.deletedTextures.clear()

        renderer.onSurfaceCreated(null, null)
        assertEquals(0, ReflectionHelpers.getField<Int>(renderer, "textureId").toInt())
        assertNull(ReflectionHelpers.getField<Any?>(renderer, "attemptedEffect"))
        assertNull(ReflectionHelpers.getField<Any?>(renderer, "uploadedEffect"))
        assertTrue("Old context IDs must not be deleted in a new context", GlLifecycleShadow.deletedTextures.isEmpty())
        renderer.onSurfaceChanged(null, 400, 600)
        renderer.onDrawFrame(null)
        assertSame(effect, ReflectionHelpers.getField<Any?>(renderer, "attemptedEffect"))
        assertSame(effect, ReflectionHelpers.getField<Any?>(renderer, "uploadedEffect"))
        assertTrue(ReflectionHelpers.getField<Int>(renderer, "textureId") > 0)
        assertFalse("Renderer never owns/recycles the effect bitmap", effect.bitmap.isRecycled)
    }

    @Test fun clearingEffectDeletesCurrentTextureOnlyOnce() {
        val renderer = FaceMeshGlRenderer()
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 400, 600)
        ReflectionHelpers.setField(renderer, "textureId", 42)
        renderer.setEffect(null)
        renderer.onDrawFrame(null)
        renderer.onDrawFrame(null)
        assertEquals(listOf(42), GlLifecycleShadow.deletedTextures)
    }

    @Test fun surfaceDiagnosticsUseActualGlSurfaceDimensionsNotComposeViewSize() {
        val view = mountedView()
        var dimensions: FaceMeshGlSurfaceDiagnostics? = null
        view.setSurfaceDiagnosticsListener { dimensions = it }
        renderer(view).onSurfaceChanged(null, 380, 570)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(380, dimensions!!.surfaceWidth)
        assertEquals(570, dimensions!!.surfaceHeight)
        assertEquals(400, dimensions!!.viewWidth)
        assertEquals(600, dimensions!!.viewHeight)
        assertNotNull(dimensions!!.boundsInWindow)
    }

    @Test fun retiredPreparedFrameIsClearedRatherThanDrawnAfterViewportInvalidation() {
        val renderer = FaceMeshGlRenderer()
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 400, 600)
        val effect = effect()
        renderer.setEffect(effect)
        ReflectionHelpers.setField(renderer, "textureId", 1)
        ReflectionHelpers.setField(renderer, "attemptedEffect", effect)
        ReflectionHelpers.setField(renderer, "uploadedEffect", effect)
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        holder.publishIfCurrent(token, snapshot(), FaceGeometryDisplayTransform(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), token), SystemClock.uptimeMillis())
        renderer.publishFrame(holder.getLatest())
        holder.clear()
        renderer.onDrawFrame(null)
        assertEquals(FaceMeshGlRenderStatus.STALE_FRAME, renderer.lastRenderStatus)
    }

    @Implements(GLES20::class)
    class GlLifecycleShadow {
        companion object {
            val deletedTextures = mutableListOf<Int>()
            private var nextId = 1
            @JvmStatic @Implementation fun glCreateShader(type: Int) = nextId++
            @JvmStatic @Implementation fun glCreateProgram() = nextId++
            @JvmStatic @Implementation fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {
                params[offset] = GLES20.GL_TRUE
            }
            @JvmStatic @Implementation fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
                params[offset] = GLES20.GL_TRUE
            }
            @JvmStatic @Implementation fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) {
                params[offset] = 4096
            }
            @JvmStatic @Implementation fun glGenTextures(count: Int, ids: IntArray, offset: Int) {
                repeat(count) { ids[offset + it] = nextId++ }
            }
            @JvmStatic @Implementation fun glDeleteTextures(count: Int, ids: IntArray, offset: Int) {
                repeat(count) { deletedTextures.add(ids[offset + it]) }
            }
        }
    }
}
