package com.example.xelvic.geometry

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FlashState
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImmutableImageInfo
import androidx.camera.core.impl.TagBundle
import androidx.test.core.app.ApplicationProvider
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.nio.ByteBuffer
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native task/scheduler fakes; real provider conversion, bitmap/MPImage ownership and publication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class MediaPipeInferenceWatchdogTest {
    private lateinit var provider: MediaPipeFaceGeometryProvider
    private lateinit var task: FakeTask
    private lateinit var scheduler: ManualWatchdog
    private lateinit var shutdown: QueuedExecutor
    private lateinit var holder: FaceGeometrySnapshotHolder
    private val errors = mutableListOf<Throwable>()

    @Before fun setUp() {
        task = FakeTask()
        scheduler = ManualWatchdog()
        shutdown = QueuedExecutor()
        holder = FaceGeometrySnapshotHolder()
        provider = MediaPipeFaceGeometryProvider(
            ApplicationProvider.getApplicationContext(),
            AsyncFaceLandmarkerTaskFactory { _, _, result, error ->
                task.onResult = result
                task.onError = error
                task
            }, scheduler, shutdown
        )
        provider.setSessionResultListener { snapshot, transform, timestamp ->
            holder.publishIfCurrent(checkNotNull(transform?.session), snapshot, transform, timestamp)
        }
        provider.setErrorListener { error -> errors.add(error); holder.clear() }
    }

    @After fun tearDown() {
        task.beforeClose = null
        task.closeFailure = null
        provider.close()
        shutdown.runAll()
    }

    private fun submit(timestamp: Long = 10L, rotation: Int = 90): YuvProxy {
        val proxy = YuvProxy(rotation)
        val transform = FaceGeometryDisplayTransform(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), holder.currentSession()
        )
        provider.submitFrame(proxy, timestamp, false, transform)
        assertEquals("Every accepted or dropped proxy closes exactly once", 1, proxy.closes.get())
        return proxy
    }

    private fun assertImageClosedExactlyOnce(image: MPImage) {
        assertEquals(0, ReflectionHelpers.getField<Int>(image, "referenceCount").toInt())
        assertTrue(BitmapExtractor.extract(image).isRecycled)
    }

    @Test fun debugMetadataUsesActualProxyCropRotationAndUprightBitmapDimensions() {
        submit(rotation = 90)
        val diagnostics = provider.debugFrameDiagnostics!!
        assertEquals(4, diagnostics.sourceWidth)
        assertEquals(2, diagnostics.sourceHeight)
        assertEquals(listOf(0, 0, 4, 2), listOf(
            diagnostics.cropLeft, diagnostics.cropTop, diagnostics.cropRight, diagnostics.cropBottom
        ))
        assertEquals(90, diagnostics.rotationDegrees)
        assertEquals(2, diagnostics.inferenceWidth)
        assertEquals(4, diagnostics.inferenceHeight)
        submit(11)
        submit(12)
        assertEquals(2L, provider.debugDroppedFrames)
        task.complete()
        assertEquals(InferenceWatchdogStatus.IDLE, provider.watchdogStatus)
    }

    @Test fun callbackImageIsClosedForNormalLateAndMismatchedResultsWithoutTouchingPendingInput() {
        submit(10)
        val nativeInput = task.image!!
        fun callbackImage() = BitmapImageBuilder(Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)).build()
        val mismatchedOutput = callbackImage()
        task.onResult(TestResult(9), mismatchedOutput)
        assertImageClosedExactlyOnce(mismatchedOutput)
        assertFalse(BitmapExtractor.extract(nativeInput).isRecycled)
        val normalOutput = callbackImage()
        task.onResult(TestResult(10), normalOutput)
        assertImageClosedExactlyOnce(normalOutput)
        assertImageClosedExactlyOnce(nativeInput)
        val lateOutput = callbackImage()
        task.onResult(TestResult(10), lateOutput)
        assertImageClosedExactlyOnce(lateOutput)
        assertImageClosedExactlyOnce(nativeInput)
    }

    @Test fun unavailableTransformStillExposesInputMetadataWithoutInferenceOrTakingProxyOwnership() {
        val proxy = YuvProxy(270)
        provider.recordTransformRejectedFrame(proxy, null)
        val frame = provider.debugFrameDiagnostics!!
        assertEquals(4, frame.sourceWidth)
        assertEquals(2, frame.sourceHeight)
        assertEquals(270, frame.rotationDegrees)
        assertEquals(0, frame.inferenceWidth)
        assertEquals(0, task.submissions)
        assertEquals(0, proxy.closes.get())
        assertEquals(1L, provider.debugDroppedFrames)
        assertFalse(provider.isInferenceInFlight)
    }

    @Test fun initializationErrorBeforeListenerInstallationIsDeliveredAndClosedExactlyOnce() {
        provider.close()
        shutdown.runAll()
        val earlyError = IllegalStateException("native error during createFromOptions")
        val earlyTask = FakeTask()
        val earlyShutdown = QueuedExecutor()
        val earlyScheduler = ManualWatchdog()
        val earlyProvider = MediaPipeFaceGeometryProvider(
            ApplicationProvider.getApplicationContext(),
            AsyncFaceLandmarkerTaskFactory { _, _, result, error ->
                earlyTask.onResult = result
                earlyTask.onError = error
                error(earlyError)
                earlyTask
            }, earlyScheduler, earlyShutdown
        )
        var notifications = 0
        earlyProvider.setErrorListener { assertSame(earlyError, it); notifications++ }
        earlyProvider.setErrorListener { notifications++ }
        assertEquals(1, notifications)
        assertEquals(1, earlyShutdown.actions.size)
        earlyShutdown.runAll()
        earlyProvider.close()
        assertEquals(1, earlyTask.closes.get())
        assertTrue(earlyProvider.isShutdownComplete)
        assertEquals(InferenceWatchdogStatus.CLOSED, earlyProvider.watchdogStatus)
    }

    @Test fun timeoutIsBoundedAndRetiresTaskWithoutReleasingNativeOwnedInput() {
        submit()
        val image = task.image!!
        assertEquals(3_000L, scheduler.entries.single().timeoutMs)
        assertEquals(2, image.width)
        assertEquals(4, image.height)
        submit(11)
        assertEquals(1, task.submissions)

        scheduler.fire(0)
        assertEquals(InferenceWatchdogStatus.TIMED_OUT, provider.watchdogStatus)
        assertTrue(provider.isInferenceInFlight)
        assertFalse(provider.isShutdownComplete)
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        assertEquals(1, errors.size)
        assertTrue(errors.single() is MediaPipeInferenceTimeoutException)
        submit(12)
        assertEquals("Never resubmit to a timed-out landmarker", 1, task.submissions)
        assertEquals(1, shutdown.actions.size)

        shutdown.runAll()
        assertTrue(provider.isShutdownComplete)
        assertFalse(provider.isInferenceInFlight)
        assertImageClosedExactlyOnce(image)
    }

    @Test fun normalCompletionCancelsWatchdogAndPublishesTheExactTransform() {
        submit()
        val image = task.image!!
        task.complete()
        assertNotNull(holder.getLatest())
        assertEquals(10L, holder.getLatest()!!.snapshot.timestampMs)
        assertEquals(2, holder.getLatest()!!.snapshot.imageWidth)
        assertEquals(4, holder.getLatest()!!.snapshot.imageHeight)
        assertSame(holder.currentSession(), holder.getLatest()!!.imageToPreview!!.session)
        assertTrue(scheduler.entries.single().cancelled)
        assertEquals(InferenceWatchdogStatus.IDLE, provider.watchdogStatus)
        assertFalse(provider.isInferenceInFlight)
        assertImageClosedExactlyOnce(image)
        scheduler.fire(0, evenIfCancelled = true)
        assertTrue(errors.isEmpty())
        assertTrue(shutdown.actions.isEmpty())
    }

    @Test fun lateCompletionAfterTimeoutCannotPublishAndShutdownCannotDoubleRelease() {
        submit()
        val image = task.image!!
        scheduler.fire(0)
        task.complete()
        assertNull(holder.getLatest())
        assertImageClosedExactlyOnce(image)
        provider.close()
        shutdown.runAll()
        task.complete()
        scheduler.fire(0, evenIfCancelled = true)
        assertEquals(1, task.closes.get())
        assertEquals(1, errors.size)
        assertImageClosedExactlyOnce(image)
    }

    @Test fun shutdownBeforeCompletionAndTimeoutRejectsBothLateCallbacks() {
        submit()
        val image = task.image!!
        provider.close()
        provider.close()
        scheduler.fire(0, evenIfCancelled = true)
        assertTrue(errors.isEmpty())
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        task.complete()
        shutdown.runAll()
        assertNull(holder.getLatest())
        assertEquals(InferenceWatchdogStatus.CLOSED, provider.watchdogStatus)
        assertEquals(1, task.closes.get())
        assertImageClosedExactlyOnce(image)
    }

    @Test fun timeoutAndShutdownBarrierAllowsOnlyOneRetirementAndOneResourceRelease() {
        submit()
        val image = task.image!!
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val timeout = executor.submit {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                scheduler.fire(0, evenIfCancelled = true)
            }
            val close = executor.submit {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                provider.close()
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            timeout.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)
            assertEquals(1, shutdown.actions.size)
            assertTrue(errors.size <= 1)
            assertFalse(BitmapExtractor.extract(image).isRecycled)
            shutdown.runAll()
            task.complete()
            assertEquals(1, task.closes.get())
            assertImageClosedExactlyOnce(image)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun completionBeforeShutdownDoesNotReleaseAnImageTwice() {
        submit()
        val image = task.image!!
        task.complete()
        provider.close()
        shutdown.runAll()
        task.complete()
        assertImageClosedExactlyOnce(image)
        assertEquals(1, task.closes.get())
        assertTrue(errors.isEmpty())
    }

    @Test fun retiredGeometrySessionCompletionIsRejectedButStillReleasesItsInput() {
        submit()
        val image = task.image!!
        val retired = holder.currentSession()
        holder.clear()
        task.complete()
        assertFalse(retired.isCurrent)
        assertNull(holder.getLatest())
        assertFalse(provider.isInferenceInFlight)
        assertImageClosedExactlyOnce(image)
        assertTrue(errors.isEmpty())
    }

    @Test fun oldCompletionAndCancelledWatchdogCannotConsumeTheNextPendingFrame() {
        submit(10)
        val oldImage = task.image!!
        task.complete()
        submit(20)
        val newImage = task.image!!
        task.complete(timestamp = 10)
        scheduler.fire(0, evenIfCancelled = true)
        assertTrue(provider.isInferenceInFlight)
        assertFalse(BitmapExtractor.extract(newImage).isRecycled)
        assertTrue(errors.isEmpty())
        task.complete()
        assertEquals(20L, holder.getLatest()!!.snapshot.timestampMs)
        assertImageClosedExactlyOnce(oldImage)
        assertImageClosedExactlyOnce(newImage)
    }

    @Test fun graphErrorIsNotTreatedAsAnInputCompletionFence() {
        submit()
        val image = task.image!!
        task.onError(IllegalStateException("graph failure"))
        assertEquals(1, errors.size)
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        assertTrue(provider.isInferenceInFlight)
        shutdown.runAll()
        assertImageClosedExactlyOnce(image)
    }

    @Test fun nativeCloseErrorListenerCannotBeMistakenForSuccessfulShutdown() {
        submit()
        val image = task.image!!
        task.beforeClose = { task.onError(IllegalStateException("native tearDown failed")) }
        provider.close()
        shutdown.runAll()
        assertFalse(provider.isShutdownComplete)
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        assertTrue(errors.isEmpty())
        task.complete()
        assertImageClosedExactlyOnce(image)
    }

    @Test fun detectAsyncFailureDoesNotReleasePossiblySubmittedInputBeforeShutdown() {
        task.duringDetect = { throw IllegalStateException("detectAsync failed after accepting input") }
        submit()
        val image = task.image!!
        assertEquals(1, errors.size)
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        shutdown.runAll()
        assertImageClosedExactlyOnce(image)
        assertTrue(provider.isShutdownComplete)
    }

    @Test fun oldProviderCompletionCannotPublishAfterCameraSwitchOrPreviewReentry() {
        submit()
        val image = task.image!!
        val replacement = holder.beginSession()
        val newSnapshot = FaceGeometrySnapshot(
            20, 4, 2, 0, false, listOf(FaceVertex(0.1f, 0.2f, 0f))
        )
        assertTrue(holder.publishIfCurrent(replacement, newSnapshot, null, 20))
        task.complete()
        assertSame(newSnapshot, holder.getLatest()!!.snapshot)
        assertImageClosedExactlyOnce(image)
    }

    @Test fun closeFailureRetainsInputUntilAnExactLateCompletion() {
        submit()
        val image = task.image!!
        task.closeFailure = IllegalStateException("shutdown not confirmed")
        provider.close()
        shutdown.runAll()
        assertFalse(provider.isShutdownComplete)
        assertFalse(BitmapExtractor.extract(image).isRecycled)
        assertTrue(provider.isInferenceInFlight)
        task.complete()
        assertImageClosedExactlyOnce(image)
        assertNull(holder.getLatest())
    }

    @Test fun blockedNativeCloseDoesNotBlockTimeoutFallbackOrReleaseItsInputEarly() {
        submit()
        val image = task.image!!
        val closeEntered = CountDownLatch(1)
        val closeAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        task.beforeClose = {
            closeEntered.countDown()
            assertTrue(closeAllowed.await(5, TimeUnit.SECONDS))
        }
        try {
            scheduler.fire(0)
            val cleanup = executor.submit { shutdown.runAll() }
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertFalse(provider.isShutdownComplete)
            assertFalse(BitmapExtractor.extract(image).isRecycled)
            task.complete()
            assertNull(holder.getLatest())
            assertImageClosedExactlyOnce(image)
            closeAllowed.countDown()
            cleanup.get(5, TimeUnit.SECONDS)
            assertImageClosedExactlyOnce(image)
            assertEquals(1, task.closes.get())
        } finally {
            closeAllowed.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun completionThatWinsTheWatchdogRaceCannotThenTimeOut() {
        submit()
        val image = task.image!!
        val listenerEntered = CountDownLatch(1)
        val publicationAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        provider.setSessionResultListener { snapshot, transform, timestamp ->
            listenerEntered.countDown()
            assertTrue(publicationAllowed.await(5, TimeUnit.SECONDS))
            holder.publishIfCurrent(checkNotNull(transform?.session), snapshot, transform, timestamp)
        }
        try {
            val callback = executor.submit { task.complete() }
            assertTrue(listenerEntered.await(5, TimeUnit.SECONDS))
            scheduler.fire(0, evenIfCancelled = true)
            assertTrue(errors.isEmpty())
            holder.clear() // Clear while callback has finished inference but not publication.
            publicationAllowed.countDown()
            callback.get(5, TimeUnit.SECONDS)
            assertNull(holder.getLatest())
            assertImageClosedExactlyOnce(image)
        } finally {
            publicationAllowed.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun shutdownWaitsForSubmissionToReturnEvenIfResultArrivesSynchronously() {
        val detectEntered = CountDownLatch(1)
        val detectAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        task.duringDetect = {
            task.complete()
            detectEntered.countDown()
            assertTrue(detectAllowed.await(5, TimeUnit.SECONDS))
        }
        try {
            val submission = executor.submit { submit() }
            assertTrue(detectEntered.await(5, TimeUnit.SECONDS))
            val image = task.image!!
            assertFalse("detectAsync may still be using its input", BitmapExtractor.extract(image).isRecycled)
            assertTrue(provider.isInferenceInFlight)
            provider.close()
            detectAllowed.countDown()
            submission.get(5, TimeUnit.SECONDS)
            shutdown.runAll()
            assertImageClosedExactlyOnce(image)
            assertFalse(provider.isInferenceInFlight)
        } finally {
            detectAllowed.countDown()
            executor.shutdownNow()
        }
    }

    private class FakeTask : AsyncFaceLandmarkerTask {
        lateinit var onResult: (FaceLandmarkerResult, MPImage?) -> Unit
        lateinit var onError: (RuntimeException) -> Unit
        var image: MPImage? = null
        var timestamp = -1L
        var submissions = 0
        val closes = AtomicInteger()
        var duringDetect: (() -> Unit)? = null
        var beforeClose: (() -> Unit)? = null
        var closeFailure: RuntimeException? = null
        override fun detectAsync(image: MPImage, timestampMs: Long) {
            this.image = image
            timestamp = timestampMs
            submissions++
            duringDetect?.invoke()
        }
        fun complete(timestamp: Long = this.timestamp) = onResult(TestResult(timestamp), null)
        override fun close() {
            closes.incrementAndGet()
            beforeClose?.invoke()
            closeFailure?.let { throw it }
        }
    }

    private class TestResult(private val timestamp: Long) : FaceLandmarkerResult() {
        override fun timestampMs() = timestamp
        override fun faceLandmarks(): List<List<NormalizedLandmark>> =
            listOf(listOf(NormalizedLandmark.create(0.5f, 0.5f, 0f)))
        override fun faceBlendshapes(): Optional<List<List<Category>>> = Optional.empty()
        override fun facialTransformationMatrixes(): Optional<List<FloatArray>> = Optional.empty()
    }

    private class QueuedExecutor : Executor {
        val actions = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { actions.add(command) }
        fun runAll() {
            while (actions.isNotEmpty()) actions.removeAt(0).run()
        }
    }

    private class ManualWatchdog : InferenceWatchdogScheduler {
        data class Entry(val timeoutMs: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val entries = mutableListOf<Entry>()
        override fun schedule(timeoutMs: Long, action: () -> Unit): AutoCloseable {
            val entry = Entry(timeoutMs, action)
            entries.add(entry)
            return AutoCloseable { entry.cancelled = true }
        }
        fun fire(index: Int, evenIfCancelled: Boolean = false) {
            val entry = entries[index]
            if (!entry.cancelled || evenIfCancelled) entry.action()
        }
        override fun close() { entries.forEach { it.cancelled = true } }
    }

    private class YuvProxy(rotation: Int) : ImageProxy {
        val closes = AtomicInteger()
        private val info = ImmutableImageInfo.create(
            TagBundle.emptyBundle(), 10L, rotation, Matrix(), FlashState.UNKNOWN
        )
        override fun getWidth() = 4
        override fun getHeight() = 2
        override fun getCropRect() = Rect(0, 0, 4, 2)
        override fun setCropRect(rect: Rect?) = Unit
        override fun getImageInfo(): ImageInfo = info
        override fun getFormat() = ImageFormat.YUV_420_888
        override fun getImage(): Image? = null
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(
            plane(4, 8, 128), plane(2, 2, 128), plane(2, 2, 128)
        )
        override fun close() { closes.incrementAndGet() }
        private fun plane(rowStride: Int, size: Int, value: Int) = object : ImageProxy.PlaneProxy {
            override fun getRowStride() = rowStride
            override fun getPixelStride() = 1
            override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(ByteArray(size) { value.toByte() })
        }
    }
}
