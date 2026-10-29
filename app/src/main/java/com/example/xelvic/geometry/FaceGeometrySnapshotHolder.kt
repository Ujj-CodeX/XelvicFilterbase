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
        if (!x.isFinite() || !y.isFinite()) return null

        val m = matrixValues
        val denominator = m[6] * x + m[7] * y + m[8]
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-6f) return null

        val mappedX = (m[0] * x + m[1] * y + m[2]) / denominator
        val mappedY = (m[3] * x + m[4] * y + m[5]) / denominator
        if (!mappedX.isFinite() || !mappedY.isFinite()) return null

        return FaceGeometryProjection.Point(mappedX, mappedY)
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

    @Volatile
    var onSnapshotUpdated: (() -> Unit)? = null

    fun publish(
        snapshot: FaceGeometrySnapshot?,
        displayTransform: FaceGeometryDisplayTransform? = null
    ) {
        latest.set(snapshot?.let { FaceGeometryRenderFrame(it, displayTransform) })
        onSnapshotUpdated?.invoke()
    }

    fun getLatest(): FaceGeometryRenderFrame? = latest.get()

    fun clear() = publish(null)
}
