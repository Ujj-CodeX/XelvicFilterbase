package com.example.xelvic.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.xelvic.camera.CameraController
import com.example.xelvic.camera.CoordinateMapper
import com.example.xelvic.camera.FaceAnalyzer
import com.example.xelvic.camera.FaceMeshPhotoProcessor
import com.example.xelvic.camera.PhotoProcessResult
import com.example.xelvic.camera.PhotoProcessor
import com.example.xelvic.model.FaceTrackingResult
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.packagemanager.FilterRepository
import com.example.xelvic.perf.AdaptivePerformanceController
import com.example.xelvic.perf.PerformanceMonitor
import com.example.xelvic.perf.TrackingPoseHolder
import com.example.xelvic.renderer.StickerRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReviewState(
    val bitmap: Bitmap,
    val filter: InstalledFilter?,
    val warningMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val filterRepository = FilterRepository(application)
    val coordinateMapper = CoordinateMapper()
    val stickerRenderer = StickerRenderer()
    private val faceMeshPhotoProcessor = FaceMeshPhotoProcessor(application)
    val photoProcessor = PhotoProcessor(
        coordinateMapper = coordinateMapper,
        stickerRenderer = stickerRenderer,
        faceMeshPhotoProcessor = faceMeshPhotoProcessor
    )

    // Performance monitor and adaptive controller
    val performanceMonitor = PerformanceMonitor(application)
    val adaptiveController = AdaptivePerformanceController(performanceMonitor)

    // Decoupled pose holder for high-frequency tracking updates
    val poseHolder = TrackingPoseHolder()

    // Isolated low-frequency boolean state: only emits when face detection state changes (0 recompositions during continuous tracking)
    private val _isFaceLocked = MutableStateFlow(false)
    val isFaceLocked: StateFlow<Boolean> = _isFaceLocked.asStateFlow()

    private val _faceTrackingResult = MutableStateFlow(FaceTrackingResult.EMPTY)
    val faceTrackingResult: StateFlow<FaceTrackingResult> = _faceTrackingResult.asStateFlow()

    private val _isProcessingCapture = MutableStateFlow(false)
    val isProcessingCapture: StateFlow<Boolean> = _isProcessingCapture.asStateFlow()

    private val _reviewState = MutableStateFlow<ReviewState?>(null)
    val reviewState: StateFlow<ReviewState?> = _reviewState.asStateFlow()

    private val _importErrorMessage = MutableStateFlow<String?>(null)
    val importErrorMessage: StateFlow<String?> = _importErrorMessage.asStateFlow()

    val faceAnalyzer = FaceAnalyzer(
        coordinateMapper = coordinateMapper,
        performanceMonitor = performanceMonitor,
        adaptiveController = adaptiveController
    ) { result ->
        // Update decoupled atomic pose holder without causing Compose tree recomposition
        poseHolder.updatePose(result)
        _faceTrackingResult.value = result

        val hasFace = result.hasFace
        if (_isFaceLocked.value != hasFace) {
            _isFaceLocked.value = hasFace
        }
    }

    /**
     * Executes photo capture and full-resolution filter compositing.
     */
    fun capturePhoto(cameraController: CameraController) {
        if (_isProcessingCapture.value || _reviewState.value != null) return

        _isProcessingCapture.value = true

        viewModelScope.launch {
            try {
                // 1. Take raw ImageProxy from CameraX
                val imageProxy = cameraController.takePicture()
                val isFrontCamera = cameraController.isFrontCamera.value

                // 2. Convert to upright Bitmap and close imageProxy
                val uprightBitmap = try {
                    withContext(Dispatchers.Default) {
                        photoProcessor.convertImageProxyToBitmap(
                            imageProxy,
                            isFrontCamera
                        )
                    }
                } finally {
                    // Close even if coroutine cancellation prevents the
                    // withContext block from starting or completing.
                    imageProxy.close()
                }

                // 3. Process with selected filter
                val currentFilter = filterRepository.selectedFilter.value
                val processResult: PhotoProcessResult = withContext(Dispatchers.Default) {
                    photoProcessor.processPhoto(uprightBitmap, currentFilter, isFrontCamera)
                }

                _reviewState.value = ReviewState(
                    bitmap = processResult.bitmap,
                    filter = currentFilter,
                    warningMessage = processResult.message
                )
            } catch (e: Exception) {
                Log.e(TAG, "Photo capture or processing failed", e)
                _importErrorMessage.value = "Capture failed: ${e.message}"
            } finally {
                _isProcessingCapture.value = false
            }
        }
    }

    /**
     * Retake action: discards uncommitted photo and resumes camera pipeline.
     */
    fun retakePhoto() {
        val currentReview = _reviewState.value
        _reviewState.value = null
        currentReview?.bitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
        stickerRenderer.resetSmoothing()
    }

    /**
     * Imports an external .xfilter package from SAF Uri.
     */
    fun importFilter(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                filterRepository.importFilter(uri)
            }
            result.onSuccess {
                _importErrorMessage.value = null
            }.onFailure { err ->
                _importErrorMessage.value = err.message ?: "Failed to import filter"
            }
        }
    }

    fun clearImportError() {
        _importErrorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        photoProcessor.release()
        faceAnalyzer.release()
    }
}
