package com.example.xelvic.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.geometry.MediaPipeFaceGeometryProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashMode {
    OFF,
    ON,
    AUTO
}
@TransformExperimental
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val faceAnalyzer: FaceAnalyzer
) {

    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    private val cameraExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    /** Latest-only geometry handoff for the debug overlay; no frame queue is retained. */
    val faceGeometryHolder = FaceGeometrySnapshotHolder()

    private val _isMediaPipeGeometryEnabled = MutableStateFlow(false)
    val isMediaPipeGeometryEnabled: StateFlow<Boolean> =
        _isMediaPipeGeometryEnabled.asStateFlow()

    private val _isMediaPipeFaceTracked = MutableStateFlow(false)
    val isMediaPipeFaceTracked: StateFlow<Boolean> =
        _isMediaPipeFaceTracked.asStateFlow()

    private val _geometryError = MutableStateFlow<String?>(null)
    val geometryError: StateFlow<String?> = _geometryError.asStateFlow()

    @Volatile
    private var geometryRequested = false

    @Volatile
    private var released = false

    @Volatile
    private var mediaPipeProvider: MediaPipeFaceGeometryProvider? = null

    private val mediaPipeAnalyzer = ImageAnalysis.Analyzer { imageProxy ->
        val activeProvider = mediaPipeProvider
        if (!geometryRequested || activeProvider == null) {
            try {
                imageProxy.close()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to close frame while geometry provider is unavailable", error)
            }
        } else {
            activeProvider.submitFrame(
                imageProxy = imageProxy,
                timestampMs = SystemClock.uptimeMillis(),
                isMirrored = _isFrontCamera.value
            )
        }
    }

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> =
        _isFrontCamera.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> =
        _flashMode.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> =
        _isCameraReady.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> =
        _cameraError.asStateFlow()

    private var currentPreviewView: PreviewView? = null
    private var observedPreviewView: PreviewView? = null

    private var previewLayoutChangeListener:
            View.OnLayoutChangeListener? = null

    private var previewStreamStateObserver:
            Observer<PreviewView.StreamState>? = null

    /**
     * Reads PreviewView.outputTransform only from the UI thread.
     *
     * The analyzer thread consumes the cached value through FaceAnalyzer.
     */
    @TransformExperimental
    private fun cachePreviewTransform(view: PreviewView) {
        if (observedPreviewView !== view) return

        faceAnalyzer.updatePreviewOutputTransform(
            view.outputTransform
        )
    }

    /**
     * Observes PreviewView layout and streaming state.
     * All PreviewView transform reads are routed through
     * cachePreviewTransform().
     */
    @TransformExperimental
    private fun observePreviewTransform(view: PreviewView) {

        // Remove listeners from the previously observed view.
        observedPreviewView?.let { oldView ->

            previewLayoutChangeListener?.let { listener ->
                oldView.removeOnLayoutChangeListener(listener)
            }

            previewStreamStateObserver?.let { observer ->
                oldView.previewStreamState.removeObserver(observer)
            }

            faceAnalyzer.updatePreviewOutputTransform(null)
        }

        observedPreviewView = view

        val layoutListener = View.OnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->

            cachePreviewTransform(view)
        }

        previewLayoutChangeListener = layoutListener
        view.addOnLayoutChangeListener(layoutListener)

        val streamObserver =
            Observer<PreviewView.StreamState> { state ->

                if (state == PreviewView.StreamState.STREAMING) {

                    // Run on the UI thread after the preview starts.
                    view.post {
                        cachePreviewTransform(view)
                    }

                } else {

                    // Invalidate stale transform when preview stops.
                    view.post {
                        if (observedPreviewView === view) {
                            faceAnalyzer.updatePreviewOutputTransform(null)
                        }
                    }
                }
            }

        previewStreamStateObserver = streamObserver

        view.previewStreamState.observe(
            lifecycleOwner,
            streamObserver
        )

        // Initial attempt. Transform may not be ready yet.
        view.post {
            cachePreviewTransform(view)
        }
    }

    /**
     * Initializes CameraX and binds preview, analysis and capture.
     */
    fun bindCamera(previewView: PreviewView) {
        currentPreviewView = previewView

        // Keep the reference if other existing code depends on it.
        faceAnalyzer.previewView = previewView

        observePreviewTransform(previewView)

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    cameraProvider = cameraProviderFuture.get()
                    rebindUseCases(previewView)

                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Failed obtaining CameraProvider",
                        e
                    )

                    _isCameraReady.value = false
                    _cameraError.value =
                        "Failed starting camera: ${e.message}"
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /**
     * Binds all use cases using a common ViewPort.
     */
    private fun rebindUseCases(previewView: PreviewView) {

        val provider = cameraProvider ?: return

        // Wait until PreviewView is attached and has dimensions.
        if (
            !previewView.isAttachedToWindow ||
            previewView.width <= 0 ||
            previewView.height <= 0
        ) {
            previewView.doOnAttach { attachedView ->
                attachedView.doOnLayout {
                    if (currentPreviewView === previewView) {
                        rebindUseCases(previewView)
                    }
                }
            }

            return
        }

        val viewPort = previewView.viewPort

        if (viewPort == null) {
            _isCameraReady.value = false
            _cameraError.value =
                "Camera preview geometry is not ready"

            Log.w(
                TAG,
                "PreviewView ViewPort is not available yet"
            )

            return
        }

        try {
            provider.unbindAll()

            val lensFacing =
                if (_isFrontCamera.value) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            if (!provider.hasCamera(selector)) {
                _isCameraReady.value = false
                _cameraError.value =
                    "Requested camera sensor is not available on this device"

                return
            }

            // 1. Preview use case
            preview = Preview.Builder()
                .build()
                .also { previewUseCase ->
                    previewUseCase.setSurfaceProvider(
                        previewView.surfaceProvider
                    )
                }

            // 2. Image analysis use case
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(480, 640))
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
                )
                .build()
                .also { analysisUseCase ->
                    faceAnalyzer.isFrontCamera =
                        _isFrontCamera.value

                    analysisUseCase.setAnalyzer(
                        cameraExecutor,
                        activeImageAnalyzer()
                    )
                }

            // 3. Image capture use case
            imageCapture = ImageCapture.Builder()
                .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                )
                .setFlashMode(
                    when (_flashMode.value) {
                        FlashMode.OFF ->
                            ImageCapture.FLASH_MODE_OFF

                        FlashMode.ON ->
                            ImageCapture.FLASH_MODE_ON

                        FlashMode.AUTO ->
                            ImageCapture.FLASH_MODE_AUTO
                    }
                )
                .build()

            // All use cases share the same crop and viewport.
            val useCaseGroup = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(checkNotNull(preview))
                .addUseCase(checkNotNull(imageAnalysis))
                .addUseCase(checkNotNull(imageCapture))
                .build()

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                useCaseGroup
            )

            preview?.resolutionInfo?.resolution?.let { resolution ->
                faceAnalyzer.performanceMonitor.previewResolution =
                    resolution
            }

            imageAnalysis?.resolutionInfo?.resolution?.let { resolution ->
                faceAnalyzer.performanceMonitor.analysisResolution =
                    resolution
            }

            _isCameraReady.value = true
            _cameraError.value = null

            // Refresh after use cases are bound.
            viewRefresh(previewView)

            Log.d(TAG, "Camera use cases bound successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Use cases binding failed", e)

            _isCameraReady.value = false
            _cameraError.value =
                "Camera initialization failed: ${e.message}"
        }
    }

    /**
     * Requests a transform refresh on the UI thread.
     */
    private fun viewRefresh(view: PreviewView) {
        view.post {
            cachePreviewTransform(view)
        }
    }

    /**
     * Opt-in R&D mode. It swaps the single ImageAnalysis analyzer rather than running
     * ML Kit and MediaPipe simultaneously. The production ML Kit path remains the default.
     */
    fun setMediaPipeGeometryEnabled(enabled: Boolean) {
        if (released) return

        if (!enabled) {
            geometryRequested = false
            _isMediaPipeGeometryEnabled.value = false
            _isMediaPipeFaceTracked.value = false
            faceGeometryHolder.clear()
            _geometryError.value = null

            // Restore ML Kit immediately so new frames are no longer sent to MediaPipe.
            imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)
            val previousProvider = mediaPipeProvider
            mediaPipeProvider = null
            try {
                cameraExecutor.execute { previousProvider?.close() }
            } catch (error: Exception) {
                previousProvider?.close()
            }
            return
        }

        if (geometryRequested || _isMediaPipeGeometryEnabled.value) return

        geometryRequested = true
        _isMediaPipeGeometryEnabled.value = true
        _geometryError.value = null
        faceGeometryHolder.clear()

        try {
            cameraExecutor.execute {
                var candidate: MediaPipeFaceGeometryProvider? = null
                try {
                    candidate = MediaPipeFaceGeometryProvider(context.applicationContext)
                    candidate.setResultListener { snapshot ->
                        if (geometryRequested && !released) {
                            faceGeometryHolder.publish(snapshot)
                            val hasFace = snapshot != null
                            if (_isMediaPipeFaceTracked.value != hasFace) {
                                _isMediaPipeFaceTracked.value = hasFace
                            }
                        }
                    }
                    candidate.setErrorListener { error ->
                        if (geometryRequested && !released) {
                            fallbackToMlKitOnGeometryError(error)
                        }
                    }

                    if (!geometryRequested || released) {
                        candidate.close()
                        return@execute
                    }

                    mediaPipeProvider = candidate
                    imageAnalysis?.setAnalyzer(cameraExecutor, mediaPipeAnalyzer)
                    Log.i(TAG, "MediaPipe geometry diagnostics enabled")
                } catch (error: Exception) {
                    candidate?.close()
                    geometryRequested = false
                    mediaPipeProvider = null
                    _isMediaPipeGeometryEnabled.value = false
                    _isMediaPipeFaceTracked.value = false
                    _geometryError.value =
                        "Could not start MediaPipe: ${error.message ?: error.javaClass.simpleName}"
                    imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)
                    Log.e(TAG, "Could not start MediaPipe geometry diagnostics", error)
                }
            }
        } catch (error: Exception) {
            geometryRequested = false
            _isMediaPipeGeometryEnabled.value = false
            _geometryError.value = "Camera analysis executor is unavailable"
            Log.e(TAG, "Could not schedule MediaPipe initialization", error)
        }
    }

    private fun activeImageAnalyzer(): ImageAnalysis.Analyzer =
        if (
            geometryRequested &&
            _isMediaPipeGeometryEnabled.value &&
            mediaPipeProvider != null
        ) {
            mediaPipeAnalyzer
        } else {
            faceAnalyzer
        }

    /**
     * On task/inference failure, restore the established ML Kit analysis path rather than
     * leaving the camera without a working tracker.
     */
    private fun fallbackToMlKitOnGeometryError(error: Throwable) {
        if (!geometryRequested || released) return

        geometryRequested = false
        _isMediaPipeGeometryEnabled.value = false
        _isMediaPipeFaceTracked.value = false
        faceGeometryHolder.clear()
        _geometryError.value =
            "MediaPipe failed; returned to ML Kit: ${error.message ?: error.javaClass.simpleName}"

        val providerToClose = mediaPipeProvider
        mediaPipeProvider = null
        imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)

        try {
            cameraExecutor.execute { providerToClose?.close() }
        } catch (_: Exception) {
            providerToClose?.close()
        }
    }

    /**
     * Toggles between front and rear cameras.
     */
    fun toggleCamera() {
        val provider = cameraProvider ?: return

        val newFacing = !_isFrontCamera.value

        val selector = CameraSelector.Builder()
            .requireLensFacing(
                if (newFacing) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            )
            .build()

        try {
            if (!provider.hasCamera(selector)) {
                _cameraError.value =
                    "Requested camera sensor is not available on this device"

                return
            }

            _isFrontCamera.value = newFacing
            faceAnalyzer.isFrontCamera = newFacing
            faceGeometryHolder.clear()
            _isMediaPipeFaceTracked.value = false

            // Drop transform from the previous camera configuration.
            faceAnalyzer.updatePreviewOutputTransform(null)

            currentPreviewView?.let { view ->
                rebindUseCases(view)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed toggling camera", e)

            _cameraError.value =
                "Failed switching camera: ${e.message}"
        }
    }

    /**
     * Cycles flash mode: OFF -> ON -> AUTO -> OFF.
     */
    fun cycleFlashMode() {
        val nextMode = when (_flashMode.value) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }

        _flashMode.value = nextMode

        imageCapture?.flashMode = when (nextMode) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    /**
     * Captures a photograph using CameraX ImageCapture.
     * If the coroutine is cancelled before delivery, close the image.
     */
    suspend fun takePicture(): ImageProxy =
        suspendCancellableCoroutine { continuation ->

            val capture = imageCapture

            if (capture == null) {
                continuation.resumeWithException(
                    IllegalStateException(
                        "ImageCapture is not initialized"
                    )
                )

                return@suspendCancellableCoroutine
            }

            val mainExecutor =
                ContextCompat.getMainExecutor(context)

            capture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {

                    override fun onCaptureSuccess(image: ImageProxy) {
                        continuation.resume(image) {
                                _, undeliveredImage, _ ->
                            undeliveredImage.close()
                        }
                    }

                    override fun onError(
                        exception: ImageCaptureException
                    ) {
                        continuation.resumeWithException(exception)
                    }
                }
            )
        }

    /**
     * Releases listeners, CameraX use cases, executor and face detector.
     */
    fun release() {
        released = true
        geometryRequested = false
        _isMediaPipeGeometryEnabled.value = false
        _isMediaPipeFaceTracked.value = false
        faceGeometryHolder.onSnapshotUpdated = null
        val providerToClose = mediaPipeProvider
        mediaPipeProvider = null
        try {
            providerToClose?.close()
            observedPreviewView?.let { view ->

                previewLayoutChangeListener?.let { listener ->
                    view.removeOnLayoutChangeListener(listener)
                }

                previewStreamStateObserver?.let { observer ->
                    view.previewStreamState.removeObserver(observer)
                }
            }

            observedPreviewView = null
            previewLayoutChangeListener = null
            previewStreamStateObserver = null

            faceAnalyzer.updatePreviewOutputTransform(null)

            cameraProvider?.unbindAll()

            _isCameraReady.value = false
            currentPreviewView = null

            cameraExecutor.shutdown()
            faceAnalyzer.release()

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources", e)
        }
    }
}