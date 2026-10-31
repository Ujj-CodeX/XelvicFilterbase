package com.example.xelvic.camera

import androidx.activity.ComponentActivity
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Rational
import android.view.Surface
import android.widget.FrameLayout
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.core.impl.CameraInternal
import androidx.camera.core.impl.Observable
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.OutputTransform
import android.util.Size
import com.example.xelvic.geometry.InferenceWatchdogScheduler
import com.example.xelvic.geometry.AsyncFaceLandmarkerTask
import com.example.xelvic.geometry.AsyncFaceLandmarkerTaskFactory
import com.example.xelvic.geometry.MediaPipeFaceGeometryProvider
import com.example.xelvic.geometry.FaceGeometrySnapshot
import com.google.mediapipe.framework.image.MPImage
import com.example.xelvic.geometry.FaceVertex
import com.example.xelvic.perf.AdaptivePerformanceController
import com.example.xelvic.perf.PerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.lang.reflect.Proxy
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [
    MediaPipeCoordinateTransformTest.UnusedFaceDetectionShadow::class,
    CameraPreviewConfigurationTest.CameraProviderShadow::class
])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(TransformExperimental::class)
class CameraPreviewConfigurationTest {
    private lateinit var controller: CameraController
    private lateinit var analyzer: FaceAnalyzer
    private lateinit var view: PreviewView

    @Before fun setUp() {
        CameraProviderShadow.groups.clear()
        CameraProviderShadow.unbindCalls = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val monitor = PerformanceMonitor(activity)
        analyzer = FaceAnalyzer(CoordinateMapper(), monitor, AdaptivePerformanceController(monitor)) {}
        controller = CameraController(activity, activity, analyzer)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        view = PreviewView(activity)
        root.addView(view)
        view.layout(0, 0, 400, 600)
        ReflectionHelpers.setField(controller, "currentPreviewView", view)
        ReflectionHelpers.setField(controller, "observedPreviewView", view)
        refresh()
    }

    @After fun tearDown() { controller.release(); analyzer.release() }

    private fun refresh() = ReflectionHelpers.callInstanceMethod<Unit>(
        controller, "refreshPreviewConfiguration", ClassParameter.from(PreviewView::class.java, view)
    )

    private fun seedReadyGeometry() {
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(controller, "_isCameraReady").value = true
        ReflectionHelpers.setField(controller, "cachedGeometryPreviewTransform", OutputTransform(Matrix(), Size(400, 600)))
        controller.faceGeometryHolder.publish(FaceGeometrySnapshot(
            1L, 400, 600, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))
        ))
    }

    @Test fun resizeWhileReadyInvalidatesTransformAndOldGeometry() {
        seedReadyGeometry()
        val generation = controller.previewDiagnostics.value.configurationGeneration
        view.layout(0, 0, 600, 400)
        refresh()
        assertFalse(controller.isCameraReady.value)
        assertNull(controller.faceGeometryHolder.getLatest())
        assertNull(ReflectionHelpers.getField<OutputTransform?>(controller, "cachedGeometryPreviewTransform"))
        assertEquals(generation + 1, controller.previewDiagnostics.value.configurationGeneration)
    }

    @Test fun unchangedLayoutDoesNotRepeatedlyInvalidateOrReconfigure() {
        seedReadyGeometry()
        val frame = controller.faceGeometryHolder.getLatest()
        val generation = controller.previewDiagnostics.value.configurationGeneration
        repeat(3) { refresh() }
        assertTrue(controller.isCameraReady.value)
        assertSame(frame, controller.faceGeometryHolder.getLatest())
        assertEquals(generation, controller.previewDiagnostics.value.configurationGeneration)
    }

    @Test fun scaleTypeChangeRetiresExistingViewport() {
        seedReadyGeometry()
        view.scaleType = PreviewView.ScaleType.FIT_CENTER
        refresh()
        assertFalse(controller.isCameraReady.value)
        assertNull(controller.faceGeometryHolder.getLatest())
    }

    @Test fun postedRefreshFromOldPreviewCannotClearReplacementGeometry() {
        seedReadyGeometry()
        ReflectionHelpers.setField(controller, "currentPreviewView", PreviewView(view.context))
        ReflectionHelpers.setField(controller, "observedPreviewView", ReflectionHelpers.getField<PreviewView>(controller, "currentPreviewView"))
        val frame = controller.faceGeometryHolder.getLatest()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "cachePreviewTransform", ClassParameter.from(PreviewView::class.java, view))
        assertSame(frame, controller.faceGeometryHolder.getLatest())
    }

    private fun cache(output: OutputTransform?, size: Size?) = ReflectionHelpers.callInstanceMethod<Unit>(
        controller, "updateCachedPreviewTransform",
        ClassParameter.from(OutputTransform::class.java, output),
        ClassParameter.from(Size::class.java, size)
    )

    @Test fun proportionalResizeInvalidatesGeometryWithoutRebindingTheViewport() {
        val bound = PreviewConfiguration(400, 600, 0, 0, PreviewView.ScaleType.FILL_CENTER)
        ReflectionHelpers.setField(controller, "boundPreviewConfiguration", bound)
        seedReadyGeometry()
        val retired = controller.faceGeometryHolder.currentSession()

        view.layout(0, 0, 800, 1200)
        refresh()

        assertTrue(controller.isCameraReady.value)
        assertNull(controller.faceGeometryHolder.getLatest())
        assertFalse(retired.isCurrent)
        assertFalse(bound.copy(width = 800, height = 1200).requiresUseCaseRebind(bound))
    }

    @Test fun viewportReconfigurationIsRequiredOnlyForCameraXInputsThatChanged() {
        val bound = PreviewConfiguration(400, 600, 0, 0, PreviewView.ScaleType.FILL_CENTER)
        assertFalse(bound.requiresUseCaseRebind(bound))
        assertFalse(bound.copy(width = 800, height = 1200).requiresUseCaseRebind(bound))
        assertTrue(bound.copy(width = 600, height = 400).requiresUseCaseRebind(bound))
        assertTrue(bound.copy(rotation = 2).requiresUseCaseRebind(bound))
        assertTrue(bound.copy(layoutDirection = 1).requiresUseCaseRebind(bound))
        assertTrue(bound.copy(scaleType = PreviewView.ScaleType.FIT_CENTER).requiresUseCaseRebind(bound))
        assertFalse(bound.copy(implementationMode = PreviewView.ImplementationMode.COMPATIBLE)
            .requiresUseCaseRebind(bound))
        val fit = bound.copy(scaleType = PreviewView.ScaleType.FIT_CENTER)
        assertFalse(fit.copy(scaleType = PreviewView.ScaleType.FIT_END).requiresUseCaseRebind(fit))
    }

    @Test fun changedOutputMatrixRetiresSessionButIdenticalTransformDoesNot() {
        val size = Size(400, 600)
        cache(OutputTransform(Matrix(), size), size)
        controller.faceGeometryHolder.publish(FaceGeometrySnapshot(
            1L, 400, 600, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))
        ))
        val frame = controller.faceGeometryHolder.getLatest()!!
        val generation = controller.previewDiagnostics.value.configurationGeneration

        cache(OutputTransform(Matrix(), size), size)
        assertSame(frame, controller.faceGeometryHolder.getLatest())
        assertEquals(generation, controller.previewDiagnostics.value.configurationGeneration)

        val changed = Matrix().apply { setTranslate(20f, 30f) }
        cache(OutputTransform(changed, size), size)
        assertNull(controller.faceGeometryHolder.getLatest())
        assertFalse(frame.isCurrent)
        assertEquals(generation + 1, controller.previewDiagnostics.value.configurationGeneration)
    }

    @Test fun transformUnavailableClearsGeometryAndDoesNotInvalidateAgainUntilItChanges() {
        val size = Size(400, 600)
        cache(OutputTransform(Matrix(), size), size)
        seedReadyGeometry()
        val retired = controller.faceGeometryHolder.currentSession()
        cache(null, null)
        val generation = controller.previewDiagnostics.value.configurationGeneration
        repeat(3) { cache(null, null) }

        assertNull(controller.faceGeometryHolder.getLatest())
        assertFalse(retired.isCurrent)
        assertNull(ReflectionHelpers.getField<OutputTransform?>(controller, "cachedGeometryPreviewTransform"))
        assertEquals(generation, controller.previewDiagnostics.value.configurationGeneration)
    }

    @Test fun viewportMetadataChangeAlsoInvalidatesAnOtherwiseIdenticalMatrix() {
        cache(OutputTransform(Matrix(), Size(400, 600)), Size(400, 600))
        seedReadyGeometry()
        val retired = controller.faceGeometryHolder.currentSession()
        cache(OutputTransform(Matrix(), Size(800, 1200)), Size(800, 1200))
        assertNull(controller.faceGeometryHolder.getLatest())
        assertFalse(retired.isCurrent)
    }

    @Test fun cacheDoesNotExposeTheMutableCameraXMatrixToAnalyzerThreads() {
        val matrix = Matrix()
        cache(OutputTransform(matrix, Size(400, 600)), Size(400, 600))
        val snapshot = ReflectionHelpers.getField<OutputTransform>(controller, "cachedGeometryPreviewTransform")
        matrix.setTranslate(50f, 70f)
        assertNotSame(matrix, snapshot.matrix)
        assertTrue(snapshot.matrix.isIdentity)
    }

    @Test fun retainedPreviewTransformIsUnavailableUntilTheNewSurfaceRequestIsApplied() {
        seedReadyGeometry()
        ReflectionHelpers.setField(controller, "boundPreviewConfiguration",
            PreviewConfiguration(400, 600, 0, 0, view.scaleType))
        ReflectionHelpers.setField(controller, "previewSurfaceReady", false)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "cachePreviewTransform",
            ClassParameter.from(PreviewView::class.java, view))
        assertNull(ReflectionHelpers.getField<OutputTransform?>(controller, "cachedGeometryPreviewTransform"))
        assertNull(controller.faceGeometryHolder.getLatest())
    }

    @Test fun outputTransformCacheRejectsBackgroundThreadReads() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val rejected = executor.submit<Boolean> {
                try {
                    cache(OutputTransform(Matrix(), Size(400, 600)), Size(400, 600))
                    false
                } catch (expected: RuntimeException) {
                    true
                }
            }
            assertTrue(rejected.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun meshReentryIsRejectedUntilRetiredNativeTaskConfirmsShutdown() {
        val shutdown = mutableListOf<Runnable>()
        val provider = MediaPipeFaceGeometryProvider(
            view.context,
            AsyncFaceLandmarkerTaskFactory { _, _, _, _ -> object : AsyncFaceLandmarkerTask {
                override fun detectAsync(image: MPImage, timestampMs: Long) = Unit
                override fun close() = Unit
            } },
            object : InferenceWatchdogScheduler {
                override fun schedule(timeoutMs: Long, action: () -> Unit) = AutoCloseable {}
                override fun close() = Unit
            },
            java.util.concurrent.Executor { shutdown.add(it) }
        )
        ReflectionHelpers.setField(controller, "retiringMediaPipeProvider", provider)
        provider.close()
        controller.setMediaPipeGeometryEnabled(true)
        assertFalse(controller.isMediaPipeGeometryEnabled.value)
        assertTrue(controller.geometryError.value!!.contains("still shutting down"))

        shutdown.single().run()
        assertTrue(provider.isShutdownComplete)
        assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(controller, "isRetiredProviderStillClosing"))
    }

    @Test fun providerFailureRetiresGeometryAndReturnsToTheLegacyAnalyzerOnce() {
        ReflectionHelpers.setField(controller, "geometryRequested", true)
        ReflectionHelpers.getField<MutableStateFlow<Boolean>>(controller, "_isMediaPipeGeometryEnabled").value = true
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicLong>(controller, "geometrySessionGeneration").set(10)
        seedReadyGeometry()
        val retired = controller.faceGeometryHolder.currentSession()
        val error = IllegalStateException("inference timeout")
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "fallbackToMlKitOnGeometryError",
            ClassParameter.from(Throwable::class.java, error),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 10L))
        assertFalse(controller.isMediaPipeGeometryEnabled.value)
        assertFalse(retired.isCurrent)
        assertNull(controller.faceGeometryHolder.getLatest())
        assertTrue(controller.geometryError.value!!.contains("returned to ML Kit"))
        val replacement = controller.faceGeometryHolder.currentSession()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "fallbackToMlKitOnGeometryError",
            ClassParameter.from(Throwable::class.java, error),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 10L))
        assertSame(replacement, controller.faceGeometryHolder.currentSession())
    }

    @Test fun debugFrameAgeIsSampledFromTheHolderWithoutChangingRenderState() {
        seedReadyGeometry()
        val original = controller.faceGeometryHolder.getLatest()
        val debug = controller.debugGeometryDiagnostics(101L)!!
        assertEquals(1L, debug.snapshotTimestampMs)
        assertEquals(100L, debug.frameAgeMs)
        assertSame(original, controller.faceGeometryHolder.getLatest())
        controller.faceGeometryHolder.clear()
        assertNull(controller.debugGeometryDiagnostics(102L)!!.snapshotTimestampMs)
    }

    @Test fun previewDetachBeforeAndroidViewReleaseRetiresGeometryAndObserverHandles() {
        ReflectionHelpers.setField(controller, "observedPreviewView", null)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "observePreviewTransform",
            ClassParameter.from(PreviewView::class.java, view))
        refresh()
        seedReadyGeometry()
        val retired = controller.faceGeometryHolder.currentSession()
        val preDraw = ReflectionHelpers.getField<Any>(controller, "previewPreDrawListener")
        assertNotNull(ReflectionHelpers.getField<Any?>(controller, "previewTreeObserver"))
        val root = view.parent as FrameLayout
        root.removeView(view)
        assertFalse(retired.isCurrent)
        assertNull(controller.faceGeometryHolder.getLatest())
        assertNull(ReflectionHelpers.getField<Any?>(controller, "previewTreeObserver"))
        root.addView(view)
        assertNotNull(ReflectionHelpers.getField<Any?>(controller, "previewTreeObserver"))
        assertSame(preDraw, ReflectionHelpers.getField<Any>(controller, "previewPreDrawListener"))
        controller.unbindCamera(view)
        assertNull(ReflectionHelpers.getField<Any?>(controller, "previewPreDrawListener"))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "previewAttachListener"))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "previewDisplayListener"))
    }

    @Test fun viewportResizeRebindsOnceWithSharedViewportAndCorrectTargetRotations() {
        ReflectionHelpers.setField(controller, "cameraProvider", ReflectionHelpers.newInstance(ProcessCameraProvider::class.java))
        refresh()
        assertEquals(1, CameraProviderShadow.groups.size)
        val group = CameraProviderShadow.groups.single()
        assertEquals(3, group.useCases.size)
        assertNotNull(group.viewPort)
        assertEquals(Rational(400, 600), group.viewPort!!.aspectRatio)
        assertEquals(view.display.rotation, group.viewPort!!.rotation)
        group.useCases.forEach { useCase ->
            val rotation = when (useCase) {
                is Preview -> useCase.targetRotation
                is ImageAnalysis -> useCase.targetRotation
                is androidx.camera.core.ImageCapture -> useCase.targetRotation
                else -> error("Unexpected use case")
            }
            assertEquals(group.viewPort!!.rotation, rotation)
        }
        repeat(5) { refresh() }
        assertEquals(1, CameraProviderShadow.groups.size)
        view.layout(0, 0, 800, 1200)
        refresh()
        assertEquals("Proportional resize must retain the shared ViewPort", 1, CameraProviderShadow.groups.size)
        view.layout(0, 0, 600, 400)
        refresh()
        repeat(5) { refresh() }
        assertEquals(2, CameraProviderShadow.groups.size)
        assertEquals(2, CameraProviderShadow.unbindCalls)
        assertEquals(Rational(600, 400), CameraProviderShadow.groups.last().viewPort!!.aspectRatio)
    }

    @Test fun newSurfaceRequestAppliesItsTransformBeforeBecomingReadyWithoutReplacingCameraXListener() {
        val request = syntheticSurfaceRequest()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onPreviewSurfaceRequested",
            ClassParameter.from(PreviewView::class.java, view),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
            ClassParameter.from(SurfaceRequest::class.java, request))
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "previewSurfaceReady"))
        val listener = ReflectionHelpers.getField<Any?>(request, "mTransformationInfoListener")
        assertNotNull(listener)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(controller, "previewSurfaceReady"))
        assertSame(listener, ReflectionHelpers.getField<Any?>(request, "mTransformationInfoListener"))
        assertNotNull(view.outputTransform)
        request.willNotProvideSurface()
        request.deferrableSurface.close()
    }

    private fun syntheticSurfaceRequest(): SurfaceRequest {
        val state = object : Observable<CameraInternal.State> {
            override fun fetchData() = com.google.common.util.concurrent.Futures.immediateFuture(CameraInternal.State.CLOSED)
            override fun addObserver(executor: java.util.concurrent.Executor, observer: Observable.Observer<in CameraInternal.State>) = Unit
            override fun removeObserver(observer: Observable.Observer<in CameraInternal.State>) = Unit
        }
        val info = Proxy.newProxyInstance(CameraInfoInternal::class.java.classLoader, arrayOf(CameraInfoInternal::class.java)) { _, method, _ ->
            when (method.name) {
                "getLensFacing" -> CameraSelector.LENS_FACING_FRONT
                "getSensorRotationDegrees" -> 0
                "getImplementationType" -> "androidx.camera.camera2.legacy"
                "getSensorRect" -> Rect(0, 0, 400, 600)
                else -> error("Unexpected camera info method: ${method.name}")
            }
        } as CameraInfoInternal
        val camera = Proxy.newProxyInstance(CameraInternal::class.java.classLoader, arrayOf(CameraInternal::class.java)) { _, method, _ ->
            when (method.name) {
                "getCameraInfoInternal", "getCameraInfo" -> info
                "getCameraState" -> state
                else -> error("Unexpected camera method: ${method.name}")
            }
        } as CameraInternal
        return SurfaceRequest(Size(400, 600), camera, {}).apply {
            updateTransformationInfo(SurfaceRequest.TransformationInfo.of(
                Rect(0, 0, 400, 600), 0, Surface.ROTATION_0, true, Matrix(), true
            ))
        }
    }

    @Implements(value = ProcessCameraProvider::class, isInAndroidSdk = false)
    class CameraProviderShadow {
        companion object {
            val groups = mutableListOf<UseCaseGroup>()
            var unbindCalls = 0
        }
        @Implementation fun hasCamera(selector: CameraSelector) = true
        @Implementation fun unbindAll() { unbindCalls++ }
        @Implementation fun bindToLifecycle(owner: androidx.lifecycle.LifecycleOwner, selector: CameraSelector, group: UseCaseGroup): Camera {
            groups.add(group)
            return Proxy.newProxyInstance(Camera::class.java.classLoader, arrayOf(Camera::class.java)) { _, method, _ ->
                error("No camera hardware should be invoked: ${method.name}")
            } as Camera
        }
    }

    @Test fun delayedTrackingNotificationReadsReplacementSessionInsteadOfOldCallbackState() {
        val old = controller.faceGeometryHolder.currentSession()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            executor.submit { controller.faceGeometryHolder.publishIfCurrent(old,
                FaceGeometrySnapshot(10L, 400, 600, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))), null, 10L)
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            controller.faceGeometryHolder.clear()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(controller.isMediaPipeFaceTracked.value)
            assertFalse(old.isCurrent)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun sameViewReconfigurationRejectsTheOldRequestsPostedReadinessAndStaleNewRequests() {
        val request = syntheticSurfaceRequest()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onPreviewSurfaceRequested",
            ClassParameter.from(PreviewView::class.java, view),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
            ClassParameter.from(SurfaceRequest::class.java, request))
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicLong>(controller, "previewBindingGeneration").incrementAndGet()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "previewSurfaceReady"))
        val staleRequest = syntheticSurfaceRequest()
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onPreviewSurfaceRequested",
            ClassParameter.from(PreviewView::class.java, view),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 0L),
            ClassParameter.from(SurfaceRequest::class.java, staleRequest))
        assertNull(ReflectionHelpers.getField<Any?>(staleRequest, "mTransformationInfoListener"))
        request.willNotProvideSurface()
        request.deferrableSurface.close()
        staleRequest.deferrableSurface.close()
    }

    @Test fun implementationModeChangeRefreshesPreviewSurfaceWithoutRebindingAllUseCases() {
        ReflectionHelpers.setField(controller, "cameraProvider", ReflectionHelpers.newInstance(ProcessCameraProvider::class.java))
        refresh()
        val previousAnalysis = ReflectionHelpers.getField<ImageAnalysis>(controller, "imageAnalysis")
        seedReadyGeometry()
        view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        refresh()
        assertEquals(1, CameraProviderShadow.groups.size)
        assertEquals(1, CameraProviderShadow.unbindCalls)
        assertSame(previousAnalysis, ReflectionHelpers.getField<ImageAnalysis>(controller, "imageAnalysis"))
        assertNull(controller.faceGeometryHolder.getLatest())
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "previewSurfaceReady"))
        repeat(3) { refresh() }
        assertEquals(1, CameraProviderShadow.groups.size)
    }

    @Test fun cameraSwitchAndPreviewReentryBothRejectOldSessionResults() {
        ReflectionHelpers.setField(controller, "cameraProvider", ReflectionHelpers.newInstance(ProcessCameraProvider::class.java))
        refresh()
        seedReadyGeometry()
        val oldCamera = controller.faceGeometryHolder.currentSession()
        controller.toggleCamera()
        assertEquals(2, CameraProviderShadow.groups.size)
        assertFalse(oldCamera.isCurrent)
        assertFalse(controller.faceGeometryHolder.publishIfCurrent(oldCamera,
            FaceGeometrySnapshot(10L, 400, 600, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))), null, 10L))
        val oldPreview = controller.faceGeometryHolder.currentSession()
        controller.unbindCamera(view)
        ReflectionHelpers.setField(controller, "currentPreviewView", view)
        ReflectionHelpers.setField(controller, "observedPreviewView", view)
        refresh()
        assertEquals(3, CameraProviderShadow.groups.size)
        assertFalse(oldPreview.isCurrent)
        assertFalse(controller.faceGeometryHolder.publishIfCurrent(oldPreview,
            FaceGeometrySnapshot(11L, 400, 600, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))), null, 11L))
        assertNull(controller.faceGeometryHolder.getLatest())
    }

    @Test fun displayRotationIsPartOfViewportConfiguration() {
        val original = PreviewConfiguration(400, 600, 0, 0, PreviewView.ScaleType.FILL_CENTER)
        assertNotEquals(original, original.copy(rotation = 2))
        assertNotEquals(original, original.copy(layoutDirection = 1))
    }
}
