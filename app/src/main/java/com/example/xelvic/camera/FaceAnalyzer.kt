package com.example.xelvic.camera

import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.xelvic.model.FaceTrackingResult
import com.example.xelvic.perf.AdaptivePerformanceController
import com.example.xelvic.perf.PerformanceMonitor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX ImageAnalysis analyzer running on-device ML Kit Face Detection.
 * Includes concurrency guards against queue buildup, adaptive frequency throttling,
 * and performance metrics tracking.
 */
class FaceAnalyzer(
    private val coordinateMapper: CoordinateMapper,
    val performanceMonitor: PerformanceMonitor,
    val adaptiveController: AdaptivePerformanceController,
    private val onFaceTracked: (FaceTrackingResult) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var previewWidth: Int = 0

    @Volatile
    var previewHeight: Int = 0

    @Volatile
    var isFrontCamera: Boolean = true

    // Guard preventing overlapping ML Kit inference Tasks
    private val isAnalyzing = AtomicBoolean(false)

    @Volatile
    private var lastHasTrackedFace: Boolean = false

    // ML Kit Face Detector configured for fast real-time landmark tracking
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // Record current analysis resolution for telemetry
        if (performanceMonitor.analysisResolution.width == 0) {
            performanceMonitor.analysisResolution = Size(imageProxy.width, imageProxy.height)
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val now = SystemClock.elapsedRealtime()

        // 1. Guard against overlapping inferences
        if (!isAnalyzing.compareAndSet(false, true)) {
            performanceMonitor.recordFrameSkipped()
            imageProxy.close()
            return
        }

        // 2. Check adaptive performance controller to throttle when under load
        if (!adaptiveController.shouldAnalyzeFrame(now, lastHasTrackedFace)) {
            isAnalyzing.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val frameTimestampMs = imageProxy.imageInfo.timestamp / 1_000_000L
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val startTime = SystemClock.elapsedRealtime()

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val durationMs = SystemClock.elapsedRealtime() - startTime
                performanceMonitor.recordInference(durationMs, frameTimestampMs)

                if (faces.isNotEmpty()) {
                    lastHasTrackedFace = true
                    // Choose primary face by bounding box area
                    val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val result = coordinateMapper.mapFaceToPreview(
                        face = primaryFace,
                        imageWidth = imageProxy.width,
                        imageHeight = imageProxy.height,
                        rotationDegrees = rotationDegrees,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight,
                        isFrontCamera = isFrontCamera,
                        sourceTimestampMs = frameTimestampMs
                    )
                    onFaceTracked(result)
                } else {
                    lastHasTrackedFace = false
                    onFaceTracked(FaceTrackingResult.EMPTY)
                }
            }
            .addOnFailureListener {
                lastHasTrackedFace = false
                onFaceTracked(FaceTrackingResult.EMPTY)
            }
            .addOnCompleteListener {
                isAnalyzing.set(false)
                // Mandatory CameraX requirement: Close every ImageProxy exactly once
                imageProxy.close()
            }
    }

    fun release() {
        try {
            detector.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        }
    }
}
