package com.example.xelvic.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.renderer.StickerRenderer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class PhotoProcessResult(
    val bitmap: Bitmap,
    val faceFound: Boolean,
    val message: String? = null
)

class PhotoProcessor(
    private val coordinateMapper: CoordinateMapper,
    private val stickerRenderer: StickerRenderer
) {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val photoDetector = FaceDetection.getClient(detectorOptions)

    /**
     * Converts a CameraX ImageProxy into an upright, correctly oriented Bitmap.
     */
    fun convertImageProxyToBitmap(imageProxy: ImageProxy, isFrontCamera: Boolean): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
        val matrix = Matrix()

        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees)
        }

        if (isFrontCamera) {
            // Mirror horizontally for front-facing camera photograph
            matrix.postScale(-1f, 1f)
        }

        val uprightBitmap = if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true
            )
            if (transformed != rawBitmap) {
                rawBitmap.recycle()
            }
            transformed
        } else {
            rawBitmap
        }

        return uprightBitmap
    }

    /**
     * Processes full-resolution photograph with the selected filter.
     * Computes anchors in the photograph's own coordinate system and composites the filter.
     */
    suspend fun processPhoto(
        uprightBitmap: Bitmap,
        filter: InstalledFilter?,
        isFrontCamera: Boolean
    ): PhotoProcessResult {
        if (filter == null) {
            return PhotoProcessResult(uprightBitmap, faceFound = true)
        }

        val inputImage = InputImage.fromBitmap(uprightBitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            photoDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!

                        val photoTrackingResult = coordinateMapper.mapFaceToImage(
                            face = primaryFace,
                            imageWidth = uprightBitmap.width,
                            imageHeight = uprightBitmap.height,
                            isFrontCamera = false // Upright bitmap is already mirrored if front camera
                        )

                        val outputBitmap = uprightBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = Canvas(outputBitmap)

                        stickerRenderer.render(
                            canvas = canvas,
                            filter = filter,
                            trackingResult = photoTrackingResult,
                            smoothTracking = false
                        )

                        continuation.resume(
                            PhotoProcessResult(
                                bitmap = outputBitmap,
                                faceFound = true
                            )
                        )
                    } else {
                        continuation.resume(
                            PhotoProcessResult(
                                bitmap = uprightBitmap,
                                faceFound = false,
                                message = "No face detected in photo. Filter could not be anchored."
                            )
                        )
                    }
                }
                .addOnFailureListener { exception ->
                    continuation.resume(
                        PhotoProcessResult(
                            bitmap = uprightBitmap,
                            faceFound = false,
                            message = "Face analysis error: ${exception.message}"
                        )
                    )
                }
        }
    }

    fun release() {
        try {
            photoDetector.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        }
    }
}
