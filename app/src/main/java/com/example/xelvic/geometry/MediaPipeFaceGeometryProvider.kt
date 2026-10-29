package com.example.xelvic.geometry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaPipe Face Landmarker implementation for the existing CameraX YUV_420_888 stream.
 *
 * The incoming ImageProxy is copied to an owned ARGB bitmap and closed immediately after
 * conversion. Only one inference is allowed in flight; incoming frames are dropped while
 * that inference is pending. This bounds memory/latency and avoids CameraX image queue buildup.
 *
 * This is an isolated provider. It intentionally does not replace or mutate FaceAnalyzer.
 */
class MediaPipeFaceGeometryProvider(
    context: Context,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET
) : FaceGeometryProvider {

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val lastTimestampMs = AtomicLong(-1L)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private val resultListener = AtomicReference<(FaceGeometrySnapshot?) -> Unit>({})
    private val errorListener = AtomicReference<((Throwable) -> Unit)?>(null)

    private val landmarker: FaceLandmarker

    init {
        requireModelAsset()

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(Delegate.CPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(DEFAULT_CONFIDENCE)
            .setMinFacePresenceConfidence(DEFAULT_CONFIDENCE)
            .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { error -> handleError(error) }
            .build()

        landmarker = FaceLandmarker.createFromOptions(appContext, options)
    }

    override fun setResultListener(listener: (FaceGeometrySnapshot?) -> Unit) {
        resultListener.set(listener)
    }

    override fun setErrorListener(listener: ((Throwable) -> Unit)?) {
        errorListener.set(listener)
    }

    override fun submitFrame(
        imageProxy: ImageProxy,
        timestampMs: Long,
        isMirrored: Boolean
    ) {
        if (closed.get()) {
            closeProxySafely(imageProxy)
            return
        }

        // Keep at most one owned bitmap/inference in flight. CameraX's KEEP_ONLY_LATEST
        // cannot protect against a slow asynchronous consumer after the proxy is handed off.
        if (!inferenceInFlight.compareAndSet(false, true)) {
            closeProxySafely(imageProxy)
            return
        }

        var proxyClosed = false
        var sourceBitmap: Bitmap? = null
        var inferenceBitmap: Bitmap? = null

        try {
            check(imageProxy.format == ImageFormat.YUV_420_888) {
                "Expected YUV_420_888 input; received format=${imageProxy.format}"
            }

            sourceBitmap = yuv420888ToBitmap(imageProxy)
            val sourceWidth = imageProxy.width
            val sourceHeight = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            imageProxy.close()
            proxyClosed = true

            inferenceBitmap = rotateUpright(sourceBitmap, rotationDegrees)
            if (inferenceBitmap !== sourceBitmap) {
                sourceBitmap.recycle()
                sourceBitmap = null
            } else {
                sourceBitmap = null // inferenceBitmap now owns this bitmap
            }

            val timestamp = monotonicTimestamp(timestampMs)
            val mpImage = BitmapImageBuilder(inferenceBitmap).build()
            val frame = PendingFrame(
                timestampMs = timestamp,
                imageWidth = inferenceBitmap.width,
                imageHeight = inferenceBitmap.height,
                rotationDegrees = rotationDegrees,
                isMirrored = isMirrored,
                bitmap = inferenceBitmap,
                mpImage = mpImage
            )
            inferenceBitmap = null // ownership transferred to PendingFrame

            pendingFrame.set(frame)
            if (closed.get()) {
                pendingFrame.getAndSet(null)?.release()
                inferenceInFlight.set(false)
                return
            }

            landmarker.detectAsync(frame.mpImage, frame.timestampMs)
        } catch (error: Exception) {
            if (!proxyClosed) closeProxySafely(imageProxy)
            sourceBitmap?.recycle()
            inferenceBitmap?.recycle()
            pendingFrame.getAndSet(null)?.release()
            inferenceInFlight.set(false)
            notifyError(error)
        }
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val frame = pendingFrame.get() ?: return
        // A late callback must never consume a newer frame's resources.
        if (result.timestampMs() != frame.timestampMs) {
            Log.w(TAG, "Ignoring stale result timestamp=${result.timestampMs()}, pending=${frame.timestampMs}")
            return
        }
        if (!pendingFrame.compareAndSet(frame, null)) return

        try {
            if (closed.get()) return

            val faceLandmarks = result.faceLandmarks().firstOrNull()
            if (faceLandmarks.isNullOrEmpty()) {
                notifyResult(null)
                return
            }

            val landmarks = faceLandmarks.map { landmark ->
                FaceVertex(
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z()
                )
            }

            val blendshapes = result.faceBlendshapes()
                .orElse(emptyList())
                .firstOrNull()
                .orEmpty()
                .map { category ->
                    BlendShapeValue(
                        categoryName = category.categoryName(),
                        score = category.score()
                    )
                }

            // MediaPipe returns a flattened, column-major 4x4 float matrix.
            // Preserve order here; renderer-specific interpretation is a later phase.
            val matrix = result.facialTransformationMatrixes()
                .orElse(emptyList())
                .firstOrNull()
                ?.toList()

            notifyResult(
                FaceGeometrySnapshot(
                    timestampMs = frame.timestampMs,
                    imageWidth = frame.imageWidth,
                    imageHeight = frame.imageHeight,
                    rotationDegrees = frame.rotationDegrees,
                    isMirrored = frame.isMirrored,
                    landmarks = landmarks,
                    blendshapes = blendshapes,
                    facialTransformationMatrix = matrix,
                    topologyId = null
                )
            )
        } catch (error: Exception) {
            notifyError(error)
        } finally {
            frame.release()
            inferenceInFlight.set(false)
        }
    }

    private fun handleError(error: RuntimeException) {
        pendingFrame.getAndSet(null)?.release()
        inferenceInFlight.set(false)
        notifyError(error)
    }

    private fun notifyResult(snapshot: FaceGeometrySnapshot?) {
        try {
            resultListener.get().invoke(snapshot)
        } catch (error: Exception) {
            Log.e(TAG, "Face geometry result listener threw an exception", error)
        }
    }

    private fun notifyError(error: Throwable) {
        Log.e(TAG, "Face geometry provider failed", error)
        try {
            errorListener.get()?.invoke(error)
        } catch (listenerError: Exception) {
            Log.e(TAG, "Face geometry error listener threw an exception", listenerError)
        }
    }

    private fun monotonicTimestamp(candidateMs: Long): Long {
        val candidate = candidateMs.coerceAtLeast(0L)
        while (true) {
            val previous = lastTimestampMs.get()
            val next = maxOf(candidate, previous + 1L)
            if (lastTimestampMs.compareAndSet(previous, next)) return next
        }
    }

    private fun requireModelAsset() {
        try {
            appContext.assets.open(modelAssetPath).use { /* confirm the task bundle exists */ }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Missing MediaPipe model asset '$modelAssetPath'. " +
                    "Download face_landmarker.task and place it in app/src/main/assets/.",
                error
            )
        }
    }

    private fun yuv420888ToBitmap(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val planes = imageProxy.planes
        require(planes.size >= 3) { "YUV_420_888 input must have three planes" }

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer.duplicate().apply { position(0) }
        val uBuffer = uPlane.buffer.duplicate().apply { position(0) }
        val vBuffer = vPlane.buffer.duplicate().apply { position(0) }

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val pixels = IntArray(width * height)
        var outputIndex = 0

        for (y in 0 until height) {
            val yRow = y * yRowStride
            val uvRow = (y shr 1)
            for (x in 0 until width) {
                val yValue = (yBuffer.get(yRow + x * yPixelStride).toInt() and 0xFF) - 16
                val uValue = (uBuffer.get(uvRow * uRowStride + (x shr 1) * uPixelStride).toInt() and 0xFF) - 128
                val vValue = (vBuffer.get(uvRow * vRowStride + (x shr 1) * vPixelStride).toInt() and 0xFF) - 128

                val adjustedY = (yValue.coerceAtLeast(0) * 1192)
                val red = ((adjustedY + 1634 * vValue) shr 10).coerceIn(0, 255)
                val green = ((adjustedY - 833 * vValue - 400 * uValue) shr 10).coerceIn(0, 255)
                val blue = ((adjustedY + 2066 * uValue) shr 10).coerceIn(0, 255)

                pixels[outputIndex++] =
                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun rotateUpright(source: Bitmap, rotationDegrees: Int): Bitmap {
        require(
            rotationDegrees == 0 ||
                rotationDegrees == 90 ||
                rotationDegrees == 180 ||
                rotationDegrees == 270
        ) { "Unsupported image rotation: $rotationDegrees" }

        if (rotationDegrees == 0) return source

        val transform = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            transform,
            true
        )
    }

    private fun closeProxySafely(imageProxy: ImageProxy) {
        try {
            imageProxy.close()
        } catch (error: Exception) {
            Log.w(TAG, "ImageProxy close failed", error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        try {
            landmarker.close()
        } catch (error: Exception) {
            Log.w(TAG, "MediaPipe FaceLandmarker close failed", error)
        } finally {
            pendingFrame.getAndSet(null)?.release()
            inferenceInFlight.set(false)
        }
    }

    private class PendingFrame(
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
        val isMirrored: Boolean,
        val bitmap: Bitmap,
        val mpImage: MPImage
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return

            try {
                mpImage.close()
            } catch (_: Exception) {
                // Best effort cleanup after inference completes or provider closes.
            }
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
            } catch (_: Exception) {
                // Best effort cleanup.
            }
        }
    }

    companion object {
        private const val TAG = "MediaPipeFaceGeometry"
        private const val DEFAULT_MODEL_ASSET = "face_landmarker.task"
        private const val DEFAULT_CONFIDENCE = 0.5f
    }
}
