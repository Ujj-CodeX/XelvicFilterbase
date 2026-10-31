package com.example.xelvic.camera

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewTreeObserver
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.camera.view.TransformExperimental
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.BuildConfig
import com.example.xelvic.geometry.InferenceWatchdogStatus
import com.example.xelvic.geometry.MediaPipeFrameDiagnostics
import com.example.xelvic.perf.DiagnosticRateLimiter
import com.example.xelvic.geometry.FaceGeometryDisplayTransform
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.geometry.MediaPipeFaceGeometryProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FlashMode {
    OFF,
    ON,
    AUTO
}

enum class CameraPreviewStreamStatus {
    UNKNOWN,
    IDLE,
    STREAMING
}

enum class GeometryDisplayTransformStatus {
    DISABLED,
    WAITING_FOR_PREVIEW,
    WAITING_FOR_OUTPUT_TRANSFORM,
    READY,
    FAILED
}

/** Snapshot of CameraX preview and MediaPipe display-transform readiness for diagnostics. */
data class CameraPreviewDiagnostics(
    val previewStreamStatus: CameraPreviewStreamStatus = CameraPreviewStreamStatus.UNKNOWN,
    val previewViewAttached: Boolean = false,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val viewPortAvailable: Boolean = false,
    val useCasesBound: Boolean = false,
    val outputTransformAvailable: Boolean = false,
    val geometryTransformStatus: GeometryDisplayTransformStatus =
        GeometryDisplayTransformStatus.DISABLED,
    val lastTransformFailure: String? = null,
    val configurationGeneration: Long = 0L,
    val previewBoundsInWindow: String? = null
)

/** Read on demand by the debug panel; operational fields already live in provider/holder atomics. */
data class CameraGeometryDiagnostics(
    val frame: MediaPipeFrameDiagnostics?,
    val snapshotTimestampMs: Long?,
    val frameAgeMs: Long?,
    val inferenceInFlight: Boolean,
    val droppedFrames: Long,
    val watchdogStatus: InferenceWatchdogStatus,
    val providerShutdownComplete: Boolean
)

/** Preview properties that change its output mapping or CameraX's shared ViewPort. */
internal data class PreviewConfiguration(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val layoutDirection: Int,
    val scaleType: PreviewView.ScaleType,
    val implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.PERFORMANCE
) {
    /** ViewPort uses aspect ratio, not pixel dimensions. A proportional resize needs no rebind. */
    fun requiresUseCaseRebind(bound: PreviewConfiguration?): Boolean =
        bound == null || rotation != bound.rotation || layoutDirection != bound.layoutDirection ||
            viewPortScaleType(scaleType) != viewPortScaleType(bound.scaleType) ||
            width.toLong() * bound.height != bound.width.toLong() * height

    private fun viewPortScaleType(type: PreviewView.ScaleType): Int = when (type) {
        PreviewView.ScaleType.FILL_START -> 0
        PreviewView.ScaleType.FILL_CENTER -> 1
        PreviewView.ScaleType.FILL_END -> 2
        else -> 3 // CameraX maps all FIT_* variants to ViewPort.FIT.
    }
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

    /** Provider initialization/error lifecycle only; frame publication authority is the holder token. */
    private val geometrySessionGeneration = AtomicLong(0L)

    @Volatile
    private var released = false

    @Volatile
    private var mediaPipeProvider: MediaPipeFaceGeometryProvider? = null

    /** At most one retired native task may be draining; do not accumulate stuck graphs on re-entry. */
    private var retiringMediaPipeProvider: MediaPipeFaceGeometryProvider? = null
    private val geometryProviderLock = Any()
    private val previewDiagnosticLogLimiter = DiagnosticRateLimiter(1_000L)

    /** No high-frequency Compose state: sample only while the debug panel is visible. */
    fun debugGeometryDiagnostics(nowMs: Long = SystemClock.uptimeMillis()): CameraGeometryDiagnostics? {
        if (!BuildConfig.DEBUG) return null
        val provider = synchronized(geometryProviderLock) { mediaPipeProvider ?: retiringMediaPipeProvider }
        val timestamp = faceGeometryHolder.getLatest()?.snapshot?.timestampMs
        return CameraGeometryDiagnostics(
            frame = provider?.debugFrameDiagnostics,
            snapshotTimestampMs = timestamp,
            frameAgeMs = timestamp?.let { nowMs - it },
            inferenceInFlight = provider?.isInferenceInFlight ?: false,
            droppedFrames = provider?.debugDroppedFrames ?: 0L,
            watchdogStatus = provider?.watchdogStatus ?: InferenceWatchdogStatus.IDLE,
            providerShutdownComplete = provider?.isShutdownComplete ?: true
        )
    }

    private fun isGeometrySessionActive(generation: Long): Boolean =
        !released && geometryRequested && geometrySessionGeneration.get() == generation

    private fun detachMediaPipeProvider(): MediaPipeFaceGeometryProvider? =
        synchronized(geometryProviderLock) {
            mediaPipeProvider.also { previous ->
                mediaPipeProvider = null
                if (previous != null) retiringMediaPipeProvider = previous
            }
        }

    private fun isRetiredProviderStillClosing(): Boolean = synchronized(geometryProviderLock) {
        val previous = retiringMediaPipeProvider ?: return@synchronized false
        if (previous.isShutdownComplete) {
            retiringMediaPipeProvider = null
            false
        } else {
            true
        }
    }

    private fun mediaPipeAnalyzer(bindingGeneration: Long) = ImageAnalysis.Analyzer { imageProxy ->
        val activeProvider = mediaPipeProvider
        if (!geometryRequested || activeProvider == null ||
            previewBindingGeneration.get() != bindingGeneration
        ) {
            try {
                imageProxy.close()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to close frame while geometry provider is unavailable", error)
            }
        } else {
            val frameTransform = computeGeometryDisplayTransform(imageProxy)
            val session = frameTransform?.session
            if (session == null || !session.isCurrent ||
                previewBindingGeneration.get() != bindingGeneration
            ) {
                activeProvider.recordTransformRejectedFrame(imageProxy, session?.generation)
                imageProxy.close()
                return@Analyzer
            }
            activeProvider.submitFrame(
                imageProxy = imageProxy,
                timestampMs = SystemClock.uptimeMillis(),
                isMirrored = _isFrontCamera.value,
                displayTransform = frameTransform
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

    private val diagnosticsLock = Any()
    private val _previewDiagnostics = MutableStateFlow(CameraPreviewDiagnostics())
    val previewDiagnostics: StateFlow<CameraPreviewDiagnostics> =
        _previewDiagnostics.asStateFlow()

    private fun updatePreviewDiagnostics(
        update: (CameraPreviewDiagnostics) -> CameraPreviewDiagnostics
    ) {
        synchronized(diagnosticsLock) {
            _previewDiagnostics.value = update(_previewDiagnostics.value)
        }
    }

    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> =
        _cameraError.asStateFlow()

    private var currentPreviewView: PreviewView? = null
    private var observedPreviewView: PreviewView? = null
    private var previewBindingInProgress = false
    private var observedPreviewConfiguration: PreviewConfiguration? = null
    private var boundPreviewConfiguration: PreviewConfiguration? = null
    private var attemptedPreviewConfiguration: PreviewConfiguration? = null
    private var previewConfigurationGeneration = 0L
    private val previewBindingGeneration = AtomicLong(0L)
    private var activePreviewSurfaceRequest: SurfaceRequest? = null
    private var previewSurfaceReady = false
    private var cachedGeometryPreviewViewPortSize: Size? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var previewTreeObserver: ViewTreeObserver? = null
    private var previewAttachListener: View.OnAttachStateChangeListener? = null
    private var previewDisplayListener: DisplayManager.DisplayListener? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private var previewLayoutChangeListener:
            View.OnLayoutChangeListener? = null

    private var previewStreamStateObserver:
            Observer<PreviewView.StreamState>? = null

    @Volatile
    private var cachedGeometryPreviewTransform: OutputTransform? = null

    private val geometryImageTransformFactory =
        ImageProxyTransformFactory().apply {
            setUsingCropRect(false)
            // MediaPipe infers on a physically upright bitmap. Include ImageProxy rotation
            // in its source transform so upright landmarks map into PreviewView exactly once.
            setUsingRotationDegrees(true)
        }

    private val geometryTransformWarningLogged = AtomicBoolean(false)

    /**
     * Reads PreviewView.outputTransform only from the UI thread.
     *
     * The analyzer thread consumes the cached value through FaceAnalyzer.
     */
    private fun previewConfiguration(view: PreviewView): PreviewConfiguration? {
        val rotation = view.display?.rotation ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        return PreviewConfiguration(
            view.width, view.height, rotation, view.layoutDirection, view.scaleType,
            view.implementationMode
        )
    }

    private fun updateMediaPipeTrackingState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateMediaPipeTrackingState() }
            return
        }
        // Read the holder NOW, not a face boolean captured by an old callback. All UI lifecycle
        // mutations are main-thread serialized; delayed bookkeeping cannot undo a new session.
        _isMediaPipeFaceTracked.value = !released && faceGeometryHolder.getLatest() != null
    }

    init {
        faceGeometryHolder.addSnapshotListener { updateMediaPipeTrackingState() }
    }

    /** UI-thread only. Retire geometry before exposing any new preview configuration. */
    private fun invalidatePreviewGeometry() {
        cachedGeometryPreviewTransform = null
        cachedGeometryPreviewViewPortSize = null
        faceGeometryHolder.clear() // Retire publication authority before any external ML Kit listener.
        _isMediaPipeFaceTracked.value = false
        faceAnalyzer.updatePreviewOutputTransform(null)
        faceAnalyzer.invalidatePendingAnalysis()
        previewConfigurationGeneration++
        updatePreviewDiagnostics {
            it.copy(
                outputTransformAvailable = false,
                configurationGeneration = previewConfigurationGeneration,
                geometryTransformStatus = if (geometryRequested) {
                    GeometryDisplayTransformStatus.WAITING_FOR_OUTPUT_TRANSFORM
                } else {
                    GeometryDisplayTransformStatus.DISABLED
                }
            )
        }
    }

    /** Called by layout/display/pre-draw events, not by the analysis frame loop. */
    private fun refreshPreviewConfiguration(view: PreviewView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (currentPreviewView !== view) return
        val configuration = previewConfiguration(view)
        if (configuration != observedPreviewConfiguration) {
            observedPreviewConfiguration = configuration
            attemptedPreviewConfiguration = null
            invalidatePreviewGeometry()
            if (configuration == null || configuration.requiresUseCaseRebind(boundPreviewConfiguration)) {
                _isCameraReady.value = false
            }
        }
        if (configuration != null && cameraProvider != null && !previewBindingInProgress &&
            configuration != attemptedPreviewConfiguration
        ) {
            if (configuration.requiresUseCaseRebind(boundPreviewConfiguration) || !_isCameraReady.value) {
                rebindUseCases(view)
            } else if (configuration.implementationMode != boundPreviewConfiguration?.implementationMode) {
                // CameraX requires resetting only Preview's SurfaceProvider after a mode change,
                // not rebuilding an unchanged shared ViewPort/analysis/capture configuration.
                preview?.let { useCase ->
                    attemptedPreviewConfiguration = configuration
                    activePreviewSurfaceRequest = null
                    previewSurfaceReady = false
                    val bindingGeneration = previewBindingGeneration.get()
                    useCase.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                        onPreviewSurfaceRequested(view, bindingGeneration, request)
                    }
                    boundPreviewConfiguration = configuration
                }
            }
        }
    }

    /** Compose can update scale type without changing the view's dimensions. */
    fun onPreviewViewUpdated(view: PreviewView) {
        refreshPreviewConfiguration(view)
        view.post { cachePreviewTransform(view) }
    }

    @TransformExperimental
    private fun cachePreviewTransform(view: PreviewView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (observedPreviewView !== view) return
        refreshPreviewConfiguration(view)

        val configuration = previewConfiguration(view)
        val outputTransform = if (
            configuration != null && !configuration.requiresUseCaseRebind(boundPreviewConfiguration) &&
            _isCameraReady.value && previewSurfaceReady &&
            view.previewStreamState.value == PreviewView.StreamState.STREAMING
        ) view.outputTransform else null
        val crop = preview?.resolutionInfo?.cropRect
        updateCachedPreviewTransform(outputTransform, crop?.let { Size(it.width(), it.height()) })
        val snapshot = cachedGeometryPreviewTransform
        updatePreviewDiagnostics { current ->
            current.copy(
                previewViewAttached = view.isAttachedToWindow,
                previewWidth = view.width,
                previewHeight = view.height,
                viewPortAvailable = view.viewPort != null,
                outputTransformAvailable = snapshot != null,
                geometryTransformStatus = when {
                    !geometryRequested -> GeometryDisplayTransformStatus.DISABLED
                    snapshot != null -> GeometryDisplayTransformStatus.READY
                    current.previewStreamStatus == CameraPreviewStreamStatus.STREAMING ->
                        GeometryDisplayTransformStatus.WAITING_FOR_OUTPUT_TRANSFORM
                    else -> GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                },
                lastTransformFailure = if (snapshot != null) null else current.lastTransformFailure,
                previewBoundsInWindow = if (BuildConfig.DEBUG) {
                    val location = IntArray(2)
                    view.getLocationInWindow(location)
                    "[${location[0]},${location[1]}..${location[0] + view.width},${location[1] + view.height}]"
                } else null
            )
        }
        if (BuildConfig.DEBUG && previewDiagnosticLogLimiter.shouldReport(SystemClock.uptimeMillis())) {
            val state = _previewDiagnostics.value
            Log.d(TAG, "preview=${state.previewWidth}x${state.previewHeight} bounds=${state.previewBoundsInWindow} " +
                "rotation=${configuration?.rotation} mode=${view.implementationMode} " +
                "stream=${state.previewStreamStatus} transform=${state.geometryTransformStatus} " +
                "configuration=${state.configurationGeneration}")
        }
    }

    /** Copies CameraX's mutable Matrix once per change; only read-only, volatile snapshots escape. */
    private fun updateCachedPreviewTransform(outputTransform: OutputTransform?, viewPortSize: Size?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val next = outputTransform?.takeIf { viewPortSize != null }
        val previous = cachedGeometryPreviewTransform
        val values = FloatArray(9)
        val previousValues = FloatArray(9)
        next?.matrix?.getValues(values)
        previous?.matrix?.getValues(previousValues)
        val changed = (next == null) != (previous == null) ||
            (next != null && (!values.contentEquals(previousValues) ||
                viewPortSize != cachedGeometryPreviewViewPortSize))
        if (!changed) return

        // Null the target before retiring its session. Analyzer capture reads the session first,
        // so it can never pair an old target with the new publication authority.
        invalidatePreviewGeometry()
        val snapshot = next?.let { OutputTransform(Matrix(it.matrix), checkNotNull(viewPortSize)) }
        cachedGeometryPreviewViewPortSize = if (snapshot != null) viewPortSize else null
        cachedGeometryPreviewTransform = snapshot
        faceAnalyzer.updatePreviewOutputTransform(snapshot)
    }

    /** Do not accept PreviewView's retained metadata until the current request has been applied. */
    private fun onPreviewSurfaceRequested(
        view: PreviewView,
        bindingGeneration: Long,
        request: SurfaceRequest
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (released || currentPreviewView !== view ||
            previewBindingGeneration.get() != bindingGeneration
        ) {
            request.willNotProvideSurface()
            return
        }
        activePreviewSurfaceRequest = request
        previewSurfaceReady = false
        invalidatePreviewGeometry()
        // PreviewView owns SurfaceRequest's single transformation-info listener; never replace it.
        view.surfaceProvider.onSurfaceRequested(request)
        request.addRequestCancellationListener(ContextCompat.getMainExecutor(context)) {
            if (activePreviewSurfaceRequest === request && currentPreviewView === view) {
                activePreviewSurfaceRequest = null
                previewSurfaceReady = false
                invalidatePreviewGeometry()
            }
        }
        // CameraX sends initial transformation info before dispatching the request. PreviewView's
        // main-executor listener is queued before this post, which fences off the old metadata.
        view.post {
            if (activePreviewSurfaceRequest === request && currentPreviewView === view &&
                previewBindingGeneration.get() == bindingGeneration
            ) {
                previewSurfaceReady = true
                cachePreviewTransform(view)
            }
        }
    }

    /** Removes every callback and transform associated with one PreviewView instance. */
    private fun stopObservingPreviewTransform(view: PreviewView) {
        if (observedPreviewView !== view) return

        previewLayoutChangeListener?.let(view::removeOnLayoutChangeListener)
        previewPreDrawListener?.let { listener ->
            previewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        previewAttachListener?.let(view::removeOnAttachStateChangeListener)
        previewDisplayListener?.let { displayManager?.unregisterDisplayListener(it) }
        previewStreamStateObserver?.let(view.previewStreamState::removeObserver)

        previewBindingGeneration.incrementAndGet()
        activePreviewSurfaceRequest = null
        previewSurfaceReady = false
        observedPreviewView = null
        previewPreDrawListener = null
        previewTreeObserver = null
        previewAttachListener = null
        previewDisplayListener = null
        observedPreviewConfiguration = null
        boundPreviewConfiguration = null
        attemptedPreviewConfiguration = null
        previewLayoutChangeListener = null
        previewStreamStateObserver = null
        cachedGeometryPreviewTransform = null
        cachedGeometryPreviewViewPortSize = null
        faceAnalyzer.updatePreviewOutputTransform(null)
    }

    /** Register on the attached tree, not a floating observer that becomes dead on first attach. */
    private fun attachPreviewPreDrawObserver(view: PreviewView) {
        if (!view.isAttachedToWindow) return
        val observer = view.viewTreeObserver
        if (previewTreeObserver === observer) return
        previewPreDrawListener?.let { listener ->
            previewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            observer.addOnPreDrawListener(listener)
        }
        previewTreeObserver = observer
    }

    /**
     * Observes PreviewView layout and streaming state.
     * All PreviewView transform reads are routed through
     * cachePreviewTransform().
     */
    @TransformExperimental
    private fun observePreviewTransform(view: PreviewView) {
        if (observedPreviewView !== view) {
            observedPreviewView?.let(::stopObservingPreviewTransform)
            // A transform captured for an old PreviewView must never reach a new surface.
            faceGeometryHolder.clear()
            _isMediaPipeFaceTracked.value = false
        }

        observedPreviewView = view
        updatePreviewDiagnostics { current ->
            val streamStatus = when (view.previewStreamState.value) {
                PreviewView.StreamState.STREAMING -> CameraPreviewStreamStatus.STREAMING
                PreviewView.StreamState.IDLE -> CameraPreviewStreamStatus.IDLE
                else -> CameraPreviewStreamStatus.UNKNOWN
            }
            current.copy(
                previewStreamStatus = streamStatus,
                previewViewAttached = view.isAttachedToWindow,
                previewWidth = view.width,
                previewHeight = view.height,
                viewPortAvailable = view.viewPort != null,
                useCasesBound = false,
                outputTransformAvailable = false,
                geometryTransformStatus = if (geometryRequested) {
                    GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                } else {
                    GeometryDisplayTransformStatus.DISABLED
                },
                lastTransformFailure = null
            )
        }

        val layoutListener = View.OnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->

            refreshPreviewConfiguration(view)
            view.post { cachePreviewTransform(view) }
        }

        previewLayoutChangeListener = layoutListener
        view.addOnLayoutChangeListener(layoutListener)
        // PreviewView owns SurfaceRequest's single transformation-info listener. Its redraws
        // invalidate the UI traversal; observe that boundary without replacing its listener or
        // polling from ImageAnalysis. Comparison suppresses redundant rebinds/invalidation.
        previewPreDrawListener = ViewTreeObserver.OnPreDrawListener {
            cachePreviewTransform(view)
            true
        }
        attachPreviewPreDrawObserver(view)
        previewAttachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                if (currentPreviewView !== view) return
                attachPreviewPreDrawObserver(view)
                refreshPreviewConfiguration(view)
                viewRefresh(view)
            }

            override fun onViewDetachedFromWindow(detached: View) {
                if (currentPreviewView !== view) return
                previewPreDrawListener?.let { listener ->
                    previewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                }
                previewTreeObserver = null
                previewBindingGeneration.incrementAndGet()
                activePreviewSurfaceRequest = null
                previewSurfaceReady = false
                boundPreviewConfiguration = null
                attemptedPreviewConfiguration = null
                _isCameraReady.value = false
                invalidatePreviewGeometry()
            }
        }.also(view::addOnAttachStateChangeListener)
        previewDisplayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (view.display?.displayId == displayId) {
                    refreshPreviewConfiguration(view)
                    view.post { cachePreviewTransform(view) }
                }
            }
        }.also { displayManager?.registerDisplayListener(it, mainHandler) }

        val streamObserver =
            Observer<PreviewView.StreamState> { state ->

                if (state == PreviewView.StreamState.STREAMING) {
                    updatePreviewDiagnostics { current ->
                        current.copy(previewStreamStatus = CameraPreviewStreamStatus.STREAMING)
                    }

                    // Run on the UI thread after the preview starts.
                    view.post {
                        cachePreviewTransform(view)
                    }
                } else {
                    updatePreviewDiagnostics { current ->
                        current.copy(
                            previewStreamStatus = CameraPreviewStreamStatus.IDLE,
                            outputTransformAvailable = false,
                            geometryTransformStatus = if (geometryRequested) {
                                GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                            } else {
                                GeometryDisplayTransformStatus.DISABLED
                            }
                        )
                    }

                    // Invalidate stale geometry synchronously with the stream transition.
                    // A late frame may still complete, but it cannot use this retired target transform.
                    if (observedPreviewView === view) {
                        invalidatePreviewGeometry()
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
        if (released || currentPreviewView === previewView) return

        currentPreviewView?.let(::unbindCamera)
        currentPreviewView = previewView
        updatePreviewDiagnostics { current ->
            current.copy(
                previewViewAttached = previewView.isAttachedToWindow,
                previewWidth = previewView.width,
                previewHeight = previewView.height,
                viewPortAvailable = previewView.viewPort != null
            )
        }

        // Keep the reference if other existing code depends on it.
        faceAnalyzer.previewView = previewView

        observePreviewTransform(previewView)

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    if (released || currentPreviewView !== previewView) {
                        return@addListener
                    }
                    cameraProvider = provider
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
     * Detaches only the currently hosted PreviewView. The controller and selected tracking mode
     * remain reusable when CameraScreen is shown again, but no CameraX use case retains a dead
     * SurfaceProvider in the meantime.
     */
    fun unbindCamera(previewView: PreviewView) {
        if (currentPreviewView !== previewView) return

        currentPreviewView = null
        stopObservingPreviewTransform(previewView)
        if (faceAnalyzer.previewView === previewView) {
            faceAnalyzer.previewView = null
        }
        faceAnalyzer.previewWidth = 0
        faceAnalyzer.previewHeight = 0
        invalidatePreviewGeometry()

        try {
            imageAnalysis?.clearAnalyzer()
        } catch (error: Exception) {
            Log.w(TAG, "Failed clearing analyzer while detaching preview", error)
        }
        try {
            cameraProvider?.unbindAll()
        } catch (error: Exception) {
            Log.w(TAG, "Failed unbinding CameraX use cases while detaching preview", error)
        }

        imageAnalysis = null
        preview = null
        imageCapture = null
        camera = null
        previewBindingInProgress = false
        _isCameraReady.value = false
        _cameraError.value = null
        updatePreviewDiagnostics {
            it.copy(
                previewStreamStatus = CameraPreviewStreamStatus.IDLE,
                previewViewAttached = false,
                previewWidth = 0,
                previewHeight = 0,
                previewBoundsInWindow = null,
                viewPortAvailable = false,
                useCasesBound = false,
                outputTransformAvailable = false,
                geometryTransformStatus = if (geometryRequested) {
                    GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                } else {
                    GeometryDisplayTransformStatus.DISABLED
                },
                lastTransformFailure = null
            )
        }
    }

    /**
     * Binds all use cases using a common ViewPort.
     */
    private fun rebindUseCases(previewView: PreviewView) {

        if (released || currentPreviewView !== previewView || previewBindingInProgress) return
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
            updatePreviewDiagnostics { it.copy(viewPortAvailable = false) }
            _isCameraReady.value = false
            _cameraError.value =
                "Camera preview geometry is not ready"

            Log.w(
                TAG,
                "PreviewView ViewPort is not available yet"
            )

            return
        }

        val configuration = previewConfiguration(previewView) ?: return
        if (_isCameraReady.value && !configuration.requiresUseCaseRebind(boundPreviewConfiguration)) return
        attemptedPreviewConfiguration = configuration
        val bindingGeneration = previewBindingGeneration.incrementAndGet()
        activePreviewSurfaceRequest = null
        previewSurfaceReady = false
        previewBindingInProgress = true
        try {
            _isCameraReady.value = false
            invalidatePreviewGeometry()
            updatePreviewDiagnostics {
                it.copy(viewPortAvailable = true, useCasesBound = false)
            }
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
                updatePreviewDiagnostics { it.copy(useCasesBound = false) }
                _isCameraReady.value = false
                _cameraError.value =
                    "Requested camera sensor is not available on this device"

                return
            }

            // 1. Preview use case
            preview = Preview.Builder()
                .setTargetRotation(configuration.rotation)
                .build()
                .also { previewUseCase ->
                    previewUseCase.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                        onPreviewSurfaceRequested(previewView, bindingGeneration, request)
                    }
                }

            // 2. Image analysis use case
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(configuration.rotation)
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
                        activeImageAnalyzer(bindingGeneration)
                    )
                }

            // 3. Image capture use case
            imageCapture = ImageCapture.Builder()
                .setTargetRotation(configuration.rotation)
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

            boundPreviewConfiguration = configuration
            observedPreviewConfiguration = configuration
            _isCameraReady.value = true
            _cameraError.value = null
            updatePreviewDiagnostics {
                it.copy(
                    previewViewAttached = previewView.isAttachedToWindow,
                    previewWidth = previewView.width,
                    previewHeight = previewView.height,
                    viewPortAvailable = true,
                    useCasesBound = true
                )
            }

            // Refresh after use cases are bound.
            viewRefresh(previewView)

            Log.d(TAG, "Camera use cases bound successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Use cases binding failed", e)

            updatePreviewDiagnostics { it.copy(useCasesBound = false) }
            _isCameraReady.value = false
            _cameraError.value =
                "Camera initialization failed: ${e.message}"
        } finally {
            previewBindingInProgress = false
        }
    }

    /**
     * Captures the mapping from this exact ImageProxy's upright image space to PreviewView.
     * CoordinateTransform requires source and target OutputTransforms from the same ViewPort.
     */
    @TransformExperimental
    private fun computeGeometryDisplayTransform(
        imageProxy: ImageProxy
    ): FaceGeometryDisplayTransform? {
        // Capture authority before the target: invalidation nulls the target before retiring it.
        val session = faceGeometryHolder.currentSession()
        val targetTransform = cachedGeometryPreviewTransform
        if (targetTransform == null) {
            updatePreviewDiagnostics { current ->
                current.copy(
                    outputTransformAvailable = false,
                    geometryTransformStatus = if (
                        current.previewStreamStatus == CameraPreviewStreamStatus.STREAMING
                    ) {
                        GeometryDisplayTransformStatus.WAITING_FOR_OUTPUT_TRANSFORM
                    } else {
                        GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                    }
                )
            }
            return null
        }

        return try {
            val sourceTransform =
                geometryImageTransformFactory.getOutputTransform(imageProxy)
            val cameraXTransform =
                CoordinateTransform(sourceTransform, targetTransform)
            val matrix = Matrix()
            cameraXTransform.transform(matrix)

            val values = FloatArray(9)
            matrix.getValues(values)
            geometryTransformWarningLogged.set(false)
            updatePreviewDiagnostics {
                it.copy(
                    outputTransformAvailable = true,
                    geometryTransformStatus = GeometryDisplayTransformStatus.READY,
                    lastTransformFailure = null
                )
            }
            FaceGeometryDisplayTransform(values.map { it }, session)
        } catch (error: Exception) {
            val failure = "${error.javaClass.simpleName}: ${error.message ?: "transform creation failed"}"
            updatePreviewDiagnostics {
                it.copy(
                    geometryTransformStatus = GeometryDisplayTransformStatus.FAILED,
                    lastTransformFailure = failure
                )
            }
            if (geometryTransformWarningLogged.compareAndSet(false, true)) {
                Log.w(
                    TAG,
                    "Exact MediaPipe-to-preview transform unavailable; this frame will be skipped (no fallback projection is used)",
                    error
                )
            }
            null
        }
    }

    /**
     * Requests a transform refresh on the UI thread.
     */
    private fun viewRefresh(view: PreviewView) {
        view.post {
            if (currentPreviewView === view) {
                cachePreviewTransform(view)
            }
        }
    }

    /**
     * Opt-in R&D mode. It swaps the single ImageAnalysis analyzer rather than running
     * ML Kit and MediaPipe simultaneously. The production ML Kit path remains the default.
     */
    fun setMediaPipeGeometryEnabled(enabled: Boolean) {
        // Serialize mode/init/error transitions on main. Holder publication remains lock-free
        // on MediaPipe's callback thread; native close remains off the camera executor.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setMediaPipeGeometryEnabled(enabled) }
            return
        }
        if (released) return

        if (!enabled) {
            // Invalidate initialization and callbacks before detaching the current provider.
            geometrySessionGeneration.incrementAndGet()
            geometryRequested = false
            _isMediaPipeGeometryEnabled.value = false
            _isMediaPipeFaceTracked.value = false
            faceGeometryHolder.clear()
            _geometryError.value = null
            updatePreviewDiagnostics {
                it.copy(
                    geometryTransformStatus = GeometryDisplayTransformStatus.DISABLED,
                    lastTransformFailure = null
                )
            }

            val previousProvider = detachMediaPipeProvider()
            try {
                // Analyzer replacement is serialized with queued conversion/initialization.
                // Provider.close() retires now and schedules potentially blocking native drain.
                cameraExecutor.execute {
                    imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)
                    previousProvider?.close()
                }
            } catch (error: Exception) {
                previousProvider?.close()
            }
            return
        }

        if (geometryRequested || _isMediaPipeGeometryEnabled.value) return
        if (isRetiredProviderStillClosing()) {
            _geometryError.value =
                "The previous MediaPipe task is still shutting down; ML Kit remains active. " +
                    "Mesh tracking can be retried after shutdown completes."
            return
        }

        val sessionGeneration = geometrySessionGeneration.incrementAndGet()
        geometryRequested = true
        _isMediaPipeGeometryEnabled.value = true
        _geometryError.value = null
        faceGeometryHolder.clear()
        updatePreviewDiagnostics { current ->
            current.copy(
                geometryTransformStatus = when {
                    current.previewStreamStatus != CameraPreviewStreamStatus.STREAMING ->
                        GeometryDisplayTransformStatus.WAITING_FOR_PREVIEW
                    !current.outputTransformAvailable ->
                        GeometryDisplayTransformStatus.WAITING_FOR_OUTPUT_TRANSFORM
                    else -> GeometryDisplayTransformStatus.READY
                },
                lastTransformFailure = null
            )
        }

        try {
            cameraExecutor.execute {
                var candidate: MediaPipeFaceGeometryProvider? = null
                try {
                    if (!isGeometrySessionActive(sessionGeneration)) return@execute
                    if (isRetiredProviderStillClosing()) {
                        fallbackToMlKitOnGeometryError(
                            IllegalStateException("Previous MediaPipe task has not confirmed shutdown"),
                            sessionGeneration
                        )
                        return@execute
                    }
                    candidate = MediaPipeFaceGeometryProvider(context.applicationContext)
                    candidate.setSessionResultListener { snapshot, displayTransform, timestampMs ->
                        if (isGeometrySessionActive(sessionGeneration)) {
                            val session = displayTransform?.session ?: return@setSessionResultListener
                            // The lifecycle check is only an optimization. Publication/retirement
                            // ordering is the holder's CAS, not a check followed by an unsafe set.
                            faceGeometryHolder.publishIfCurrent(session, snapshot, displayTransform, timestampMs)
                        }
                    }
                    candidate.setErrorListener { error ->
                        fallbackToMlKitOnGeometryError(error, sessionGeneration)
                    }

                    val providerToReplace: MediaPipeFaceGeometryProvider?
                    val installed = synchronized(geometryProviderLock) {
                        if (!isGeometrySessionActive(sessionGeneration)) {
                            providerToReplace = null
                            false
                        } else {
                            providerToReplace = mediaPipeProvider
                            mediaPipeProvider = candidate
                            true
                        }
                    }
                    if (!installed) {
                        synchronized(geometryProviderLock) { retiringMediaPipeProvider = candidate }
                        candidate.close()
                        return@execute
                    }
                    providerToReplace?.let { previous ->
                        synchronized(geometryProviderLock) { retiringMediaPipeProvider = previous }
                        previous.close()
                    }

                    // A disable can race after installation. Its queued analyzer restoration runs
                    // after this task, while this final generation check prevents stale analyzer
                    // activation when no restore task could be queued.
                    if (!isGeometrySessionActive(sessionGeneration)) {
                        detachMediaPipeProvider()?.close()
                        imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)
                        return@execute
                    }
                    imageAnalysis?.setAnalyzer(cameraExecutor, activeImageAnalyzer())
                    Log.i(TAG, "MediaPipe geometry diagnostics enabled")
                } catch (error: Exception) {
                    candidate?.close()
                    fallbackToMlKitOnGeometryError(error, sessionGeneration)
                }
            }
        } catch (error: Exception) {
            fallbackToMlKitOnGeometryError(error, sessionGeneration)
        }
    }

    private fun activeImageAnalyzer(
        bindingGeneration: Long = previewBindingGeneration.get()
    ): ImageAnalysis.Analyzer =
        if (
            geometryRequested &&
            _isMediaPipeGeometryEnabled.value &&
            mediaPipeProvider != null
        ) {
            mediaPipeAnalyzer(bindingGeneration)
        } else {
            faceAnalyzer
        }

    /**
     * On task/inference failure, restore the established ML Kit analysis path rather than
     * leaving the camera without a working tracker. Errors from retired sessions are ignored.
     */
    private fun fallbackToMlKitOnGeometryError(
        error: Throwable,
        sessionGeneration: Long
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { fallbackToMlKitOnGeometryError(error, sessionGeneration) }
            return
        }
        if (released || !geometrySessionGeneration.compareAndSet(
                sessionGeneration,
                sessionGeneration + 1L
            )
        ) return

        geometryRequested = false
        _isMediaPipeGeometryEnabled.value = false
        _isMediaPipeFaceTracked.value = false
        faceGeometryHolder.clear()
        faceAnalyzer.invalidatePendingAnalysis()
        _geometryError.value =
            "MediaPipe failed; returned to ML Kit: ${error.message ?: error.javaClass.simpleName}"
        updatePreviewDiagnostics {
            it.copy(
                geometryTransformStatus = GeometryDisplayTransformStatus.DISABLED,
                lastTransformFailure = null
            )
        }

        val providerToClose = detachMediaPipeProvider()
        try {
            cameraExecutor.execute {
                imageAnalysis?.setAnalyzer(cameraExecutor, faceAnalyzer)
                providerToClose?.close()
            }
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
            _isCameraReady.value = false
            // Drop transform and in-flight geometry from the previous camera configuration.
            attemptedPreviewConfiguration = null
            invalidatePreviewGeometry()

            currentPreviewView?.let { view ->
                if (!previewBindingInProgress) {
                    rebindUseCases(view)
                }
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

    /** Releases controller-owned CameraX and MediaPipe resources. */
    fun release() {
        if (released) return

        released = true
        geometrySessionGeneration.incrementAndGet()
        geometryRequested = false
        _isMediaPipeGeometryEnabled.value = false
        _isMediaPipeFaceTracked.value = false
        faceGeometryHolder.clear()
        faceGeometryHolder.onSnapshotUpdated = null

        val providerToClose = detachMediaPipeProvider()

        try {
            // Stop new frame delivery before disposing the live-stream task.
            imageAnalysis?.clearAnalyzer()

            observedPreviewView?.let(::stopObservingPreviewTransform)
            faceAnalyzer.previewView = null
            faceAnalyzer.previewWidth = 0
            faceAnalyzer.previewHeight = 0
            faceAnalyzer.invalidatePendingAnalysis()
            previewBindingInProgress = false

            updatePreviewDiagnostics {
                it.copy(
                    outputTransformAvailable = false,
                    geometryTransformStatus = GeometryDisplayTransformStatus.DISABLED,
                    lastTransformFailure = null
                )
            }

            cameraProvider?.unbindAll()
            imageAnalysis = null
            preview = null
            imageCapture = null
            camera = null

            _isCameraReady.value = false
            currentPreviewView = null
            updatePreviewDiagnostics {
                it.copy(
                    previewStreamStatus = CameraPreviewStreamStatus.IDLE,
                    previewViewAttached = false,
                    previewWidth = 0,
                    previewHeight = 0,
                    previewBoundsInWindow = null,
                    viewPortAvailable = false,
                    useCasesBound = false,
                    outputTransformAvailable = false,
                    geometryTransformStatus = GeometryDisplayTransformStatus.DISABLED,
                    lastTransformFailure = null
                )
            }

            // Serialize FaceLandmarker.close() after frame conversion already queued on CameraX's
            // analysis executor. FaceAnalyzer is ViewModel-owned and is released by MainViewModel.
            if (providerToClose != null) {
                cameraExecutor.execute { providerToClose.close() }
            }
            cameraExecutor.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera resources", e)
            try {
                providerToClose?.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "Error closing MediaPipe provider during final cleanup", closeError)
            }
        }
    }
}