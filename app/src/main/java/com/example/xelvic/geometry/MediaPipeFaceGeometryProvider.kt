package com.example.xelvic.geometry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.BuildConfig
import com.example.xelvic.perf.DiagnosticRateLimiter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

internal interface AsyncFaceLandmarkerTask : AutoCloseable {
    fun detectAsync(image: MPImage, timestampMs: Long)
}

internal fun interface AsyncFaceLandmarkerTaskFactory {
    fun create(
        context: Context,
        modelAssetPath: String,
        onResult: (FaceLandmarkerResult, MPImage?) -> Unit,
        onError: (RuntimeException) -> Unit
    ): AsyncFaceLandmarkerTask
}

internal interface InferenceWatchdogScheduler : AutoCloseable {
    fun schedule(timeoutMs: Long, action: () -> Unit): AutoCloseable
}

private class ScheduledInferenceWatchdog : InferenceWatchdogScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { action ->
        Thread(action, "MediaPipeInferenceWatchdog").apply { isDaemon = true }
    }

    override fun schedule(timeoutMs: Long, action: () -> Unit): AutoCloseable {
        val future = executor.schedule(action, timeoutMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    override fun close() { executor.shutdownNow() }
}

enum class InferenceWatchdogStatus { IDLE, ARMED, TIMED_OUT, CLOSED }

/** Immutable, sampled ImageProxy/inference metadata for debug device validation only. */
data class MediaPipeFrameDiagnostics(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    val rotationDegrees: Int,
    val inferenceWidth: Int,
    val inferenceHeight: Int,
    val timestampMs: Long,
    val geometrySessionGeneration: Long?
)

/** Detection is bounded; FaceLandmarker graph shutdown/recreation is not. */
class MediaPipeInferenceTimeoutException internal constructor(timestampMs: Long, sessionGeneration: Long?) :
    IllegalStateException(
        "MediaPipe inference $timestampMs (geometry session=$sessionGeneration) did not complete " +
            "within ${MediaPipeFaceGeometryProvider.INFERENCE_COMPLETION_TIMEOUT_MS}ms"
    )

/**
 * MediaPipe Face Landmarker implementation for the existing CameraX YUV_420_888 stream.
 *
 * The incoming ImageProxy is copied to an owned ARGB bitmap and closed immediately after
 * conversion. Only one inference is allowed in flight; incoming frames are dropped while
 * that inference is pending. This bounds memory/latency and avoids CameraX image queue buildup.
 *
 * This is an isolated provider. It intentionally does not replace or mutate FaceAnalyzer.
 */
class MediaPipeFaceGeometryProvider private constructor(
    context: Context,
    private val modelAssetPath: String,
    taskFactory: AsyncFaceLandmarkerTaskFactory,
    private val watchdogScheduler: InferenceWatchdogScheduler,
    private val shutdownExecutor: Executor
) : FaceGeometryProvider {

    constructor(context: Context, modelAssetPath: String = DEFAULT_MODEL_ASSET) : this(
        context, modelAssetPath, DEFAULT_TASK_FACTORY, ScheduledInferenceWatchdog(),
        Executor { action -> Thread(action, "MediaPipeFaceGeometryClose").apply { isDaemon = true }.start() }
    )

    /** Tests replace only native task/scheduling boundaries, never transforms or bitmap ownership. */
    internal constructor(
        context: Context,
        taskFactory: AsyncFaceLandmarkerTaskFactory,
        watchdogScheduler: InferenceWatchdogScheduler,
        shutdownExecutor: Executor
    ) : this(context, DEFAULT_MODEL_ASSET, taskFactory, watchdogScheduler, shutdownExecutor)

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val lastTimestampMs = AtomicLong(-1L)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private val submissionLock = Any()
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownComplete = AtomicBoolean(false)
    private val shutdownFailureObserved = AtomicBoolean(false)
    private val shutdownErrorLogged = AtomicBoolean(false)
    private val _watchdogStatus = AtomicReference(InferenceWatchdogStatus.IDLE)
    val watchdogStatus: InferenceWatchdogStatus get() = _watchdogStatus.get()
    val isInferenceInFlight: Boolean get() = inferenceInFlight.get()
    val isShutdownComplete: Boolean get() = shutdownComplete.get()
    private val droppedFrames = AtomicLong(0L)
    val debugDroppedFrames: Long get() = droppedFrames.get()
    private val frameDiagnostics = AtomicReference<MediaPipeFrameDiagnostics?>(null)
    val debugFrameDiagnostics: MediaPipeFrameDiagnostics? get() = frameDiagnostics.get()
    private val diagnosticLogLimiter = DiagnosticRateLimiter(1_000L)
    private val rejectedFrameSampleLimiter = DiagnosticRateLimiter(1_000L)
    private val resultListener = AtomicReference<(FaceGeometrySnapshot?, FaceGeometryDisplayTransform?) -> Unit>({ _, _ -> })
    private val sessionResultListener =
        AtomicReference<((FaceGeometrySnapshot?, FaceGeometryDisplayTransform?, Long) -> Unit)?>(null)
    private val errorListener = AtomicReference<((Throwable) -> Unit)?>(null)
    private val pendingError = AtomicReference<Throwable?>(null)
    private val errorDelivered = AtomicBoolean(false)
    private val initializationFinished = CountDownLatch(1)

    private val modelAssetSha256: String

    private val landmarker = AtomicReference<AsyncFaceLandmarkerTask?>(null)

    init {
        try {
            requireModelAsset()
            modelAssetSha256 = calculateModelAssetSha256()
            landmarker.set(taskFactory.create(appContext, modelAssetPath, ::handleResult, ::handleError))
        } catch (error: Exception) {
            watchdogScheduler.close()
            throw error
        } finally {
            initializationFinished.countDown()
        }
    }

    override fun setResultListener(
        listener: (FaceGeometrySnapshot?, FaceGeometryDisplayTransform?) -> Unit
    ) {
        resultListener.set(listener)
    }

    fun setSessionResultListener(
        listener: (FaceGeometrySnapshot?, FaceGeometryDisplayTransform?, Long) -> Unit
    ) {
        sessionResultListener.set(listener)
    }

    override fun setErrorListener(listener: ((Throwable) -> Unit)?) {
        errorListener.set(listener)
        deliverPendingError()
    }

    override fun submitFrame(
        imageProxy: ImageProxy,
        timestampMs: Long,
        isMirrored: Boolean,
        displayTransform: FaceGeometryDisplayTransform?
    ) {
        if (closed.get() || displayTransform?.session?.isCurrent == false) {
            recordDroppedFrame()
            closeProxySafely(imageProxy)
            return
        }

        // Keep at most one owned bitmap/inference in flight. CameraX's KEEP_ONLY_LATEST
        // cannot protect against a slow asynchronous consumer after the proxy is handed off.
        if (!inferenceInFlight.compareAndSet(false, true)) {
            recordDroppedFrame()
            closeProxySafely(imageProxy)
            return
        }

        var proxyClosed = false
        var sourceBitmap: Bitmap? = null
        var inferenceBitmap: Bitmap? = null
        var ownedFrame: PendingFrame? = null
        var submittedToNative = false

        try {
            check(imageProxy.format == ImageFormat.YUV_420_888) {
                "Expected YUV_420_888 input; received format=${imageProxy.format}"
            }

            sourceBitmap = yuv420888ToBitmap(imageProxy)
            val sourceBitmapOwned = checkNotNull(sourceBitmap)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val sourceDiagnostics = if (BuildConfig.DEBUG) {
                val crop = imageProxy.cropRect
                MediaPipeFrameDiagnostics(
                    imageProxy.width, imageProxy.height, crop.left, crop.top, crop.right, crop.bottom,
                    rotationDegrees, 0, 0, 0L, displayTransform?.session?.generation
                )
            } else null

            proxyClosed = true
            imageProxy.close()

            val rotatedBitmap = rotateUpright(sourceBitmapOwned, rotationDegrees)
            if (rotatedBitmap !== sourceBitmapOwned) {
                sourceBitmapOwned.recycle()
            }
            sourceBitmap = null
            inferenceBitmap = rotatedBitmap

            val timestamp = monotonicTimestamp(timestampMs)
            sourceDiagnostics?.let {
                frameDiagnostics.set(it.copy(
                    inferenceWidth = rotatedBitmap.width, inferenceHeight = rotatedBitmap.height,
                    timestampMs = timestamp
                ))
            }
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val frame = PendingFrame(
                timestampMs = timestamp,
                imageWidth = rotatedBitmap.width,
                imageHeight = rotatedBitmap.height,
                rotationDegrees = rotationDegrees,
                isMirrored = isMirrored,
                displayTransform = displayTransform,
                bitmap = rotatedBitmap,
                mpImage = mpImage
            )
            ownedFrame = frame
            inferenceBitmap = null // ownership transferred to PendingFrame

            val accepted = synchronized(submissionLock) {
                if (displayTransform?.session?.isCurrent == false || closed.get()) {
                    false
                } else {
                    pendingFrame.set(frame)
                    _watchdogStatus.set(InferenceWatchdogStatus.ARMED)
                    true
                }
            }
            if (!accepted) {
                frame.allowReleaseAfterNativeCompletion() // Never submitted; no native consumer.
                return
            }
            frame.attachWatchdog(watchdogScheduler.schedule(INFERENCE_COMPLETION_TIMEOUT_MS) {
                handleInferenceTimeout(frame)
            })
            // No state lock is held across the native call or any listener it may invoke.
            submittedToNative = true
            checkNotNull(landmarker.get()).detectAsync(frame.mpImage, frame.timestampMs)
            logDiagnosticsIfDue()
        } catch (error: Exception) {
            sourceBitmap?.recycle()
            inferenceBitmap?.recycle()
            if (!submittedToNative) ownedFrame?.allowReleaseAfterNativeCompletion()
            // detectAsync can throw after submission has started. Do not infer that the native
            // consumer has stopped using its input; successful close or exact completion must fence it.
            failAndClose(error)
        } finally {
            ownedFrame?.let { frame ->
                frame.finishSubmission()
                releaseFrameIfSafe(frame)
            } ?: inferenceInFlight.set(false)
            if (!proxyClosed) {
                closeProxySafely(imageProxy)
            }
        }
    }

    /** Analyzer may skip submission while no exact transform exists; still expose real input. */
    internal fun recordTransformRejectedFrame(imageProxy: ImageProxy, sessionGeneration: Long?) {
        if (!BuildConfig.DEBUG) return
        droppedFrames.incrementAndGet()
        val now = SystemClock.uptimeMillis()
        if (rejectedFrameSampleLimiter.shouldReport(now)) {
            val crop = imageProxy.cropRect
            frameDiagnostics.set(MediaPipeFrameDiagnostics(
                imageProxy.width, imageProxy.height, crop.left, crop.top, crop.right, crop.bottom,
                imageProxy.imageInfo.rotationDegrees, 0, 0, now, sessionGeneration
            ))
        }
        logDiagnosticsIfDue()
    }

    private fun recordDroppedFrame() {
        if (!BuildConfig.DEBUG) return
        droppedFrames.incrementAndGet()
        logDiagnosticsIfDue()
    }

    private fun logDiagnosticsIfDue() {
        if (!BuildConfig.DEBUG || !diagnosticLogLimiter.shouldReport(SystemClock.uptimeMillis())) return
        val frame = frameDiagnostics.get()
        Log.d(TAG, "frame=$frame inFlight=${inferenceInFlight.get()} dropped=${droppedFrames.get()} " +
            "watchdog=${_watchdogStatus.get()} shutdownComplete=${shutdownComplete.get()}")
    }

    private fun handleResult(result: FaceLandmarkerResult, callbackImage: MPImage?) {
        try {
            handleGeometryResult(result)
        } finally {
            // MediaPipe 1.1.1 FaceLandmarker$1.convertToTaskInput creates a NEW bitmap-backed
            // MPImage. OutputHandler.run does not close it. It is not the native-owned input.
            try { callbackImage?.close() } catch (error: Exception) {
                Log.w(TAG, "MediaPipe callback image close failed", error)
            }
        }
    }

    private fun handleGeometryResult(result: FaceLandmarkerResult) {
        val frame = synchronized(submissionLock) {
            val pending = pendingFrame.get() ?: return
            // Duplicate/late callbacks cannot consume another inference's input or cancel its timer.
            if (result.timestampMs() != pending.timestampMs || pending.completionStarted) return
            pending.completionStarted = true
            pending
        }
        frame.cancelWatchdog()

        try {
            if (!isFrameCurrent(frame)) return

            val faceLandmarks = result.faceLandmarks().firstOrNull()
            if (faceLandmarks.isNullOrEmpty()) {
                notifyResultIfCurrent(frame, null)
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

            notifyResultIfCurrent(
                frame,
                FaceGeometrySnapshot(
                    timestampMs = frame.timestampMs,
                    imageWidth = frame.imageWidth,
                    imageHeight = frame.imageHeight,
                    rotationDegrees = frame.rotationDegrees,
                    isMirrored = frame.isMirrored,
                    landmarks = landmarks,
                    blendshapes = blendshapes,
                    facialTransformationMatrix = matrix,
                    topologyId = if (
                        FaceMeshTessellation.supportsLandmarkCount(landmarks.size)
                    ) {
                        FaceMeshTessellation.TOPOLOGY_ID
                    } else {
                        null
                    },
                    modelAssetSha256 = modelAssetSha256
                )
            )
        } catch (error: Exception) {
            if (isFrameCurrent(frame)) failAndClose(error)
        } finally {
            frame.allowReleaseAfterNativeCompletion()
            releaseFrameIfSafe(frame)
        }
    }

    private fun handleError(error: RuntimeException) {
        if (shutdownStarted.get()) {
            // TaskRunner.close() reports wait/tearDown errors to ErrorListener and can still
            // return normally. A normal Java return alone is not proof that teardown succeeded.
            shutdownFailureObserved.set(true)
            if (shutdownErrorLogged.compareAndSet(false, true)) {
                Log.w(TAG, "MediaPipe reported a native shutdown failure", error)
            }
            return
        }
        // Error callbacks carry no input timestamp and are not a completion fence. Retain the
        // pending image until the exact result arrives or graph shutdown succeeds.
        failAndClose(error)
    }

    private fun handleInferenceTimeout(frame: PendingFrame) {
        val timedOut = synchronized(submissionLock) {
            if (pendingFrame.get() !== frame || frame.completionStarted || closed.get()) {
                false
            } else {
                closed.set(true)
                _watchdogStatus.set(InferenceWatchdogStatus.TIMED_OUT)
                true
            }
        }
        if (!timedOut) return
        // Stop this task permanently. Never reopen the single-in-flight gate on a stuck landmarker.
        close()
        notifyError(MediaPipeInferenceTimeoutException(frame.timestampMs, frame.displayTransform?.session?.generation))
    }

    private fun failAndClose(error: Throwable) {
        val failed = synchronized(submissionLock) { closed.compareAndSet(false, true) }
        if (!failed) return
        close()
        notifyError(error)
    }

    private fun releaseFrameIfSafe(frame: PendingFrame) {
        if (!frame.releaseIfSafe()) return
        synchronized(submissionLock) {
            if (pendingFrame.compareAndSet(frame, null) || pendingFrame.get() == null) {
                inferenceInFlight.set(false)
                if (!closed.get()) _watchdogStatus.set(InferenceWatchdogStatus.IDLE)
            }
        }
    }

    private fun notifyResultIfCurrent(
        frame: PendingFrame,
        snapshot: FaceGeometrySnapshot?
    ) {
        if (!isFrameCurrent(frame)) return
        try {
            val sessionListener = sessionResultListener.get()
            if (sessionListener != null) {
                sessionListener.invoke(snapshot, frame.displayTransform, frame.timestampMs)
            } else {
                resultListener.get().invoke(snapshot, frame.displayTransform)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Face geometry result listener threw an exception", error)
        }
    }

    private fun isFrameCurrent(frame: PendingFrame): Boolean =
        !closed.get() && frame.displayTransform?.session?.isCurrent != false

    private fun notifyError(error: Throwable) {
        if (pendingError.compareAndSet(null, error)) {
            Log.e(TAG, "Face geometry provider failed", error)
        }
        deliverPendingError()
    }

    /** A native initialization callback can fail before the controller attaches its listener. */
    private fun deliverPendingError() {
        val error = pendingError.get() ?: return
        val listener = errorListener.get() ?: return
        if (!errorDelivered.compareAndSet(false, true)) return
        try {
            listener(error)
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

    /**
     * Reads the model once, at provider initialisation, so UV assets can be tied to the exact
     * task bundle rather than merely to a landmark count or a dependency version.
     */
    private fun calculateModelAssetSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.assets.open(modelAssetPath).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
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

    /**
     * Retires immediately, then drains native work off CameraX's executor. MediaPipe 1.1.1's
     * TaskRunner.close() waitsUntilGraphDone and has no bounded shutdown guarantee. If it blocks
     * or throws, the input remains owned; only an exact late completion can then release it.
     */
    override fun close() {
        val frame = synchronized(submissionLock) {
            closed.set(true)
            if (!shutdownStarted.compareAndSet(false, true)) return
            if (_watchdogStatus.get() != InferenceWatchdogStatus.TIMED_OUT) {
                _watchdogStatus.set(InferenceWatchdogStatus.CLOSED)
            }
            pendingFrame.get()
        }
        frame?.cancelWatchdog()
        watchdogScheduler.close()
        try {
            shutdownExecutor.execute {
                try {
                    // Initialization can itself report an error. Never close a handle before
                    // createFromOptions has returned it, or lose an early error before subscription.
                    initializationFinished.await()
                    val task = landmarker.get() ?: return@execute
                    frame?.awaitSubmissionFinished()
                    task.close()
                    if (!shutdownFailureObserved.get()) {
                        frame?.let {
                            it.allowReleaseAfterNativeCompletion()
                            releaseFrameIfSafe(it)
                        }
                        shutdownComplete.set(true)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "MediaPipe close did not confirm shutdown; retaining any native-owned input", error)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not schedule MediaPipe shutdown; retaining any native-owned input", error)
        }
    }

    private class PendingFrame(
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
        val isMirrored: Boolean,
        val displayTransform: FaceGeometryDisplayTransform?,
        val bitmap: Bitmap,
        val mpImage: MPImage
    ) {
        // completionStarted is protected by the provider's submissionLock.
        var completionStarted = false
        private val submissionFinished = CountDownLatch(1)
        private val nativeCompletionConfirmed = AtomicBoolean(false)
        private val watchdogHandle = AtomicReference<AutoCloseable?>(null)
        private val watchdogCancelled = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        fun attachWatchdog(handle: AutoCloseable) {
            watchdogHandle.set(handle)
            if (watchdogCancelled.get()) cancelWatchdog()
        }

        fun cancelWatchdog() {
            watchdogCancelled.set(true)
            watchdogHandle.getAndSet(null)?.close()
        }
        fun finishSubmission() { submissionFinished.countDown() }
        fun awaitSubmissionFinished() { submissionFinished.await() }
        fun allowReleaseAfterNativeCompletion() { nativeCompletionConfirmed.set(true) }

        fun releaseIfSafe(): Boolean {
            if (!nativeCompletionConfirmed.get() || submissionFinished.count != 0L ||
                !released.compareAndSet(false, true)
            ) return false
            cancelWatchdog()
            try {
                mpImage.close() // BitmapImageContainer owns/recycles the inference bitmap.
            } catch (_: Exception) {
                // Best effort cleanup after native use is confirmed to be over.
            }
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
            } catch (_: Exception) {
                // Best effort cleanup.
            }
            return true
        }
    }

    companion object {
        private const val TAG = "MediaPipeFaceGeometry"
        private const val DEFAULT_MODEL_ASSET = "face_landmarker.task"
        private const val DEFAULT_CONFIDENCE = 0.5f
        // Completion policy is 12x the renderer's 250ms freshness cutoff: allow a generous
        // startup/CPU/scheduling margin, but never leave the single-in-flight gate stuck forever.
        // This is a safety deadline, not a claimed device-specific inference latency guarantee.
        internal const val INFERENCE_COMPLETION_TIMEOUT_MS = 3_000L

        private val DEFAULT_TASK_FACTORY = AsyncFaceLandmarkerTaskFactory { context, asset, onResult, onError ->
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(asset).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(DEFAULT_CONFIDENCE)
                .setMinFacePresenceConfidence(DEFAULT_CONFIDENCE)
                .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener { result, image -> onResult(result, image) }
                .setErrorListener { error -> onError(error) }
                .build()
            val task = FaceLandmarker.createFromOptions(context, options)
            object : AsyncFaceLandmarkerTask {
                override fun detectAsync(image: MPImage, timestampMs: Long) = task.detectAsync(image, timestampMs)
                override fun close() = task.close()
            }
        }
    }
}
