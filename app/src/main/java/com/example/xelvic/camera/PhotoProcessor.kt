package com.example.xelvic.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.xelvic.model.FilterRenderer
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.renderer.StickerRenderer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.graphics.ImageFormat

data class PhotoProcessResult(
    val bitmap: Bitmap,
    val faceFound: Boolean,
    val message: String? = null
)

/**
 * Applies the ImageProxy viewport crop in source-image coordinates, then preserves the
 * existing upright rotation and front-camera mirror transform.
 *
 * The source bitmap remains caller-owned. The caller decides whether it can be recycled
 * after receiving a distinct output bitmap.
 */
internal fun createUprightCaptureBitmap(
    sourceBitmap: Bitmap,
    cropRect: Rect,
    rotationDegrees: Int,
    isFrontCamera: Boolean
): Bitmap {
    require(
        rotationDegrees == 0 ||
            rotationDegrees == 90 ||
            rotationDegrees == 180 ||
            rotationDegrees == 270
    ) { "Unsupported image rotation: $rotationDegrees" }

    require(!cropRect.isEmpty) { "Captured image crop rectangle is empty" }
    require(
        cropRect.left >= 0 &&
            cropRect.top >= 0 &&
            cropRect.right <= sourceBitmap.width &&
            cropRect.bottom <= sourceBitmap.height
    ) {
        "Captured image crop rectangle $cropRect is outside decoded bitmap bounds " +
            " (0, 0, " + sourceBitmap.width + ", " + sourceBitmap.height + ")"
    }

    val fullImageRect = Rect(0, 0, sourceBitmap.width, sourceBitmap.height)
    val includesFullImage = cropRect == fullImageRect

    // Materialize the viewport crop first. This keeps subsequent Matrix operations in the
    // cropped image's own coordinate space and avoids mixing source offsets with rotation.
    val croppedBitmap = if (includesFullImage) {
        sourceBitmap
    } else {
        Bitmap.createBitmap(
            sourceBitmap,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height()
        )
    }

    val matrix = Matrix()
    if (rotationDegrees != 0) {
        matrix.postRotate(rotationDegrees.toFloat())
    }
    if (isFrontCamera) {
        matrix.postScale(-1f, 1f)
    }

    // No orientation transform is needed; transfer the cropped bitmap to the caller.
    if (matrix.isIdentity) {
        return croppedBitmap
    }

    try {
        val transformedBitmap = Bitmap.createBitmap(
            croppedBitmap,
            0,
            0,
            croppedBitmap.width,
            croppedBitmap.height,
            matrix,
            true
        )
        if (croppedBitmap !== sourceBitmap && transformedBitmap !== croppedBitmap &&
            !croppedBitmap.isRecycled
        ) {
            croppedBitmap.recycle()
        }
        return transformedBitmap
    } catch (error: Throwable) {
        if (croppedBitmap !== sourceBitmap && !croppedBitmap.isRecycled) {
            croppedBitmap.recycle()
        }
        throw error
    }
}

class PhotoProcessor(
    private val coordinateMapper: CoordinateMapper,
    private val stickerRenderer: StickerRenderer,
    private val faceMeshPhotoProcessor: FaceMeshPhotoProcessor? = null
) {

    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val photoDetector = FaceDetection.getClient(detectorOptions)

    /**
     * Converts a CameraX ImageProxy into an upright, correctly oriented Bitmap.
     */
    fun convertImageProxyToBitmap(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ): Bitmap {

        require(imageProxy.format == ImageFormat.JPEG) {
            "Expected JPEG ImageProxy, received format=${imageProxy.format}"
        }

        val plane = imageProxy.planes.firstOrNull()
            ?: throw IllegalStateException(
                "Captured ImageProxy has no image planes"
            )

        // Read from a duplicate so the original buffer position is untouched.
        val buffer = plane.buffer.duplicate()

        require(buffer.hasRemaining()) {
            "Captured JPEG buffer is empty"
        }

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val rawBitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        ) ?: throw IllegalStateException(
            "Failed to decode captured JPEG image"
        )

        try {
            // CameraX carries the shared PreviewView viewport crop as ImageProxy metadata.
            // Apply it before rotation/mirroring so review and save use the preview field of view.
            val uprightBitmap = createUprightCaptureBitmap(
                sourceBitmap = rawBitmap,
                cropRect = Rect(imageProxy.cropRect),
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                isFrontCamera = isFrontCamera
            )

            // Ownership of a new cropped/transformed bitmap is returned to the caller.
            if (uprightBitmap !== rawBitmap) {
                rawBitmap.recycle()
            }

            return uprightBitmap

        } catch (error: Throwable) {
            // Avoid leaking the decoded source if transformation fails.
            if (!rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }

            throw error
        }
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
        if (filter?.definition?.renderer == FilterRenderer.FACE_MESH_TEXTURE) {
            return suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) {
                    recycleBitmapSafely(uprightBitmap)
                    return@suspendCancellableCoroutine
                }

                val result = try {
                    val meshProcessor = faceMeshPhotoProcessor
                    if (meshProcessor == null) {
                        PhotoProcessResult(
                            bitmap = uprightBitmap,
                            faceFound = false,
                            message = "Mesh photo processor is not configured."
                        )
                    } else {
                        meshProcessor.process(uprightBitmap, filter)
                    }
                } catch (error: Exception) {
                    PhotoProcessResult(
                        bitmap = uprightBitmap,
                        faceFound = false,
                        message = "Mesh photo processing failed: ${error.message}"
                    )
                }

                if (result.bitmap !== uprightBitmap) {
                    recycleBitmapSafely(uprightBitmap)
                }
                continuation.resume(result) { _, undeliveredResult, _ ->
                    recycleBitmapSafely(undeliveredResult.bitmap)
                }
            }
        }

        return suspendCancellableCoroutine { continuation ->

            /*
             * Use the cancellable continuation even when no filter is
             * selected. This allows an undelivered bitmap to be cleaned
             * up if cancellation races with result delivery.
             */
            if (filter == null) {
                continuation.resume(
                    PhotoProcessResult(
                        bitmap = uprightBitmap,
                        faceFound = true
                    )
                ) { _, undeliveredResult, _ ->
                    recycleBitmapSafely(undeliveredResult.bitmap)
                }

                return@suspendCancellableCoroutine
            }

            /*
             * ML Kit may still be using this bitmap asynchronously.
             * Do not recycle it immediately when cancellation occurs.
             * Instead, clean it up when the task completes.
             */
            val inputImage = try {
                InputImage.fromBitmap(uprightBitmap, 0)
            } catch (error: Exception) {
                recycleBitmapSafely(uprightBitmap)
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }

            val detectionTask = try {
                photoDetector.process(inputImage)
            } catch (error: Exception) {
                recycleBitmapSafely(uprightBitmap)
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }

            detectionTask.addOnSuccessListener { faces ->

                // The coroutine may have been cancelled while ML Kit ran.
                // At this point, the detection task has finished using inputImage.
                if (!continuation.isActive) {
                    recycleBitmapSafely(uprightBitmap)
                    return@addOnSuccessListener
                }

                var outputBitmap: Bitmap? = null

                val result = try {
                    if (faces.isEmpty()) {
                        PhotoProcessResult(
                            bitmap = uprightBitmap,
                            faceFound = false,
                            message = "No face detected in photo. Filter could not be anchored."
                        )
                    } else {
                        val primaryFace = faces.maxByOrNull {
                            it.boundingBox.width().toLong() *
                                    it.boundingBox.height().toLong()
                        } ?: throw IllegalStateException(
                            "Face detection returned no usable face"
                        )

                        /*
                         * The captured bitmap is already upright and mirrored
                         * by convertImageProxyToBitmap(), so map it without
                         * applying front-camera mirroring a second time.
                         */
                        val photoTrackingResult = coordinateMapper.mapFaceToImage(
                            face = primaryFace,
                            imageWidth = uprightBitmap.width,
                            imageHeight = uprightBitmap.height,
                            isFrontCamera = false
                        )

                        val composite = uprightBitmap.copy(
                            Bitmap.Config.ARGB_8888,
                            true
                        ) ?: throw IllegalStateException(
                            "Could not allocate processed photograph"
                        )

                        outputBitmap = composite

                        stickerRenderer.render(
                            canvas = Canvas(composite),
                            filter = filter,
                            trackingResult = photoTrackingResult,
                            smoothTracking = false
                        )

                        /*
                         * The detector has finished and the composite is ready.
                         * Transfer output ownership to the result; release input.
                         */
                        recycleBitmapSafely(uprightBitmap)

                        PhotoProcessResult(
                            bitmap = composite,
                            faceFound = true
                        )
                    }
                } catch (error: Exception) {
                    recycleBitmapSafely(outputBitmap)
                    recycleBitmapSafely(uprightBitmap)

                    continuation.resumeWith(Result.failure(error))
                    return@addOnSuccessListener
                }

                /*
                 * A cancellation can race with resume(). The cancellation
                 * handler releases the bitmap if the caller never receives it.
                 */
                continuation.resume(result) { _, undeliveredResult, _ ->
                    recycleBitmapSafely(undeliveredResult.bitmap)
                }
            }.addOnFailureListener { error ->

                if (!continuation.isActive) {
                    recycleBitmapSafely(uprightBitmap)
                    return@addOnFailureListener
                }

                /*
                 * Preserve the existing behavior: show the original photo
                 * with a warning if ML Kit fails, rather than pretending
                 * the filter was applied successfully.
                 */
                val result = PhotoProcessResult(
                    bitmap = uprightBitmap,
                    faceFound = false,
                    message = "Face analysis error: ${error.message}"
                )

                continuation.resume(result) { _, undeliveredResult, _ ->
                    recycleBitmapSafely(undeliveredResult.bitmap)
                }
            }
        }
    }

    fun release() {
        try {
            photoDetector.close()
        } catch (e: Exception) {
            // Ignore close exceptions
        }
        try {
            faceMeshPhotoProcessor?.close()
        } catch (_: Exception) {
            // Best-effort close of the still-image Face Landmarker.
        }
    }
}
