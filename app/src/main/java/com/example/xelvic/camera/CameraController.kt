package com.example.xelvic.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashMode {
    OFF,
    ON,
    AUTO
}

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

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()

    private var currentPreviewView: PreviewView? = null

    /**
     * Initializes CameraX and binds preview, analysis, and capture use-cases.
     */
    fun bindCamera(previewView: PreviewView) {
        currentPreviewView = previewView
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                rebindUseCases(previewView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed obtaining CameraProvider", e)
                _cameraError.value = "Failed starting camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun rebindUseCases(previewView: PreviewView) {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()

            val lensFacing = if (_isFrontCamera.value) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            // Check if lens facing is available
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            if (!provider.hasCamera(selector)) {
                _cameraError.value = "Requested camera sensor is not available on this device"
                return
            }

            // 1. Preview
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2. Image Analysis with independent 480p target resolution (optimal for ML Kit)
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(480, 640))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    faceAnalyzer.isFrontCamera = _isFrontCamera.value
                    it.setAnalyzer(cameraExecutor, faceAnalyzer)
                }

            // 3. Image Capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(
                    when (_flashMode.value) {
                        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
                        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
                        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
                    }
                )
                .build()

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageAnalysis,
                imageCapture
            )

            preview?.resolutionInfo?.resolution?.let { res ->
                faceAnalyzer.performanceMonitor.previewResolution = res
            }
            imageAnalysis?.resolutionInfo?.resolution?.let { res ->
                faceAnalyzer.performanceMonitor.analysisResolution = res
            }

            _isCameraReady.value = true
            _cameraError.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Use cases binding failed", e)
            _cameraError.value = "Camera initialization failed: ${e.message}"
        }
    }

    /**
     * Toggles between front and rear cameras.
     */
    fun toggleCamera() {
        val provider = cameraProvider ?: return
        val newFacing = !_isFrontCamera.value
        val selector = CameraSelector.Builder()
            .requireLensFacing(if (newFacing) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
            .build()

        if (provider.hasCamera(selector)) {
            _isFrontCamera.value = newFacing
            faceAnalyzer.isFrontCamera = newFacing
            currentPreviewView?.let { rebindUseCases(it) }
        }
    }

    /**
     * Cycles flash modes (OFF -> ON -> AUTO -> OFF).
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
     */
    suspend fun takePicture(): ImageProxy = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(IllegalStateException("ImageCapture is not initialized"))
            return@suspendCancellableCoroutine
        }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        capture.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    continuation.resume(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            }
        )
    }

    fun release() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            faceAnalyzer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources", e)
        }
    }
}
