package com.example.xelvic.geometry

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable image-pixel to PreviewView-pixel transform captured for one camera frame.
 * Matrix values use android.graphics.Matrix's 3x3 value ordering.
 */
class FaceGeometryDisplayTransform(
    matrixValues: List<Float>,
    val session: GeometrySessionToken? = null
) {
    val matrixValues: List<Float> =
        Collections.unmodifiableList(ArrayList(matrixValues))

    init {
        require(this.matrixValues.size == 9) {
            "Display transform must contain 9 matrix values"
        }
        require(this.matrixValues.all { it.isFinite() }) {
            "Display transform matrix values must be finite"
        }
    }

    fun mapPixel(x: Float, y: Float): FaceGeometryProjection.Point? {
        val output = FloatArray(2)
        if (!mapPixelInto(x, y, output)) return null
        return FaceGeometryProjection.Point(output[0], output[1])
    }

    /** Maps into a caller-owned array to keep the live overlay allocation-light. */
    fun mapPixelInto(
        x: Float,
        y: Float,
        output: FloatArray,
        offset: Int = 0
    ): Boolean {
        require(offset >= 0 && offset + 1 < output.size)
        if (!x.isFinite() || !y.isFinite()) return false

        val m = matrixValues
        val denominator = m[6] * x + m[7] * y + m[8]
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-6f) return false

        val mappedX = (m[0] * x + m[1] * y + m[2]) / denominator
        val mappedY = (m[3] * x + m[4] * y + m[5]) / denominator
        if (!mappedX.isFinite() || !mappedY.isFinite()) return false

        output[offset] = mappedX
        output[offset + 1] = mappedY
        return true
    }
}

data class FaceGeometryRenderFrame(
    val snapshot: FaceGeometrySnapshot,
    val imageToPreview: FaceGeometryDisplayTransform?,
    val session: GeometrySessionToken? = imageToPreview?.session
) {
    val isCurrent: Boolean get() = session?.isCurrent != false
}

/** Publication authority issued by one holder. A retired token can never become current again. */
class GeometrySessionToken internal constructor(
    val generation: Long,
    private val current: (GeometrySessionToken) -> Boolean
) {
    val isCurrent: Boolean get() = current(this)
}

/**
 * Bounded hand-off between MediaPipe's asynchronous callback and the render loop.
 * It retains only the newest immutable result and its exact per-frame preview transform.
 */
class FaceGeometrySnapshotHolder {

    private data class State(
        val session: GeometrySessionToken,
        val frame: FaceGeometryRenderFrame?,
        val lastTimestampMs: Long = -1L
    )
    private fun newToken(generation: Long): GeometrySessionToken = GeometrySessionToken(generation) { token ->
        state.get().session === token
    }
    private val state: AtomicReference<State> = AtomicReference(State(newToken(1L), null))
    private val snapshotListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var onSnapshotUpdated: (() -> Unit)? = null

    /** Adds an independent observer without replacing the legacy Canvas invalidation callback. */
    fun addSnapshotListener(listener: () -> Unit): AutoCloseable {
        snapshotListeners.add(listener)
        return AutoCloseable { snapshotListeners.remove(listener) }
    }

    /** Atomically retire the old publication authority and clear its geometry. */
    fun beginSession(): GeometrySessionToken {
        while (true) {
            beginSessionIfCurrent(currentSession())?.let { return it }
        }
    }

    /** A delayed retirement, like a delayed result, must not change a replacement session. */
    fun beginSessionIfCurrent(expected: GeometrySessionToken): GeometrySessionToken? {
        while (true) {
            val previous = state.get()
            if (previous.session !== expected) return null
            val next = State(newToken(expected.generation + 1L), null)
            if (state.compareAndSet(previous, next)) {
                notifyListeners()
                return next.session
            }
        }
    }

    fun currentSession(): GeometrySessionToken = state.get().session

    /**
     * The CAS is the publication/invalidation ordering point. No external listener runs under a
     * lock. A late no-face result is timestamped too, so it cannot erase a newer face result.
     */
    fun publishIfCurrent(
        session: GeometrySessionToken,
        snapshot: FaceGeometrySnapshot?,
        displayTransform: FaceGeometryDisplayTransform?,
        timestampMs: Long
    ): Boolean {
        if (timestampMs < 0L || (snapshot != null && snapshot.timestampMs != timestampMs)) return false
        while (true) {
            val previous = state.get()
            if (previous.session !== session || timestampMs < previous.lastTimestampMs) return false
            if (displayTransform?.session != null && displayTransform.session !== session) return false
            val frame = snapshot?.let { FaceGeometryRenderFrame(it, displayTransform, session) }
            if (state.compareAndSet(previous, State(session, frame, timestampMs))) {
                notifyListeners()
                return true
            }
        }
    }

    /** Compatibility entry point for diagnostics/tests without asynchronous session ownership. */
    fun publish(
        snapshot: FaceGeometrySnapshot?,
        displayTransform: FaceGeometryDisplayTransform? = null
    ) {
        if (snapshot == null) {
            clear()
        } else {
            publishIfCurrent(displayTransform?.session ?: currentSession(), snapshot, displayTransform, snapshot.timestampMs)
        }
    }

    private fun notifyListeners() {
        try {
            onSnapshotUpdated?.invoke()
        } catch (_: Exception) {
            // The Canvas observer must not prevent independent GL/tracking observers from running.
        }
        snapshotListeners.forEach { listener ->
            try {
                listener()
            } catch (_: Exception) {
                // One UI/GL subscriber must not prevent other subscribers from receiving the frame.
            }
        }
    }

    fun getLatest(): FaceGeometryRenderFrame? = state.get().frame

    fun clear() { beginSession() }
}
