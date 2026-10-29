package com.example.xelvic.geometry

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable image-pixel to PreviewView-pixel transform captured for one camera frame.
 * Matrix values use android.graphics.Matrix's 3x3 value ordering.
 */
class FaceGeometryDisplayTransform(matrixValues: List<Float>) {
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
    val imageToPreview: FaceGeometryDisplayTransform?
)

/**
 * Bounded hand-off between MediaPipe's asynchronous callback and the render loop.
 * It retains only the newest immutable result and its exact per-frame preview transform.
 */
class FaceGeometrySnapshotHolder {

    private val latest = AtomicReference<FaceGeometryRenderFrame?>(null)
    private val snapshotListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    var onSnapshotUpdated: (() -> Unit)? = null

    /** Adds an independent observer without replacing the legacy Canvas invalidation callback. */
    fun addSnapshotListener(listener: () -> Unit): AutoCloseable {
        snapshotListeners.add(listener)
        return AutoCloseable { snapshotListeners.remove(listener) }
    }

    fun publish(
        snapshot: FaceGeometrySnapshot?,
        displayTransform: FaceGeometryDisplayTransform? = null
    ) {
        latest.set(snapshot?.let { FaceGeometryRenderFrame(it, displayTransform) })
        onSnapshotUpdated?.invoke()
        snapshotListeners.forEach { listener ->
            try {
                listener()
            } catch (_: Exception) {
                // One UI/GL subscriber must not prevent other subscribers from receiving the frame.
            }
        }
    }

    fun getLatest(): FaceGeometryRenderFrame? = latest.get()

    fun clear() = publish(null)
}
