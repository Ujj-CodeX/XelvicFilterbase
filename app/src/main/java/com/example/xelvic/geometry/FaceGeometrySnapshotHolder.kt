package com.example.xelvic.geometry

import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded hand-off between MediaPipe's asynchronous callback and the render loop.
 * It retains only the newest immutable snapshot; it never queues camera frames.
 */
class FaceGeometrySnapshotHolder {

    private val latest = AtomicReference<FaceGeometrySnapshot?>(null)

    @Volatile
    var onSnapshotUpdated: (() -> Unit)? = null

    fun publish(snapshot: FaceGeometrySnapshot?) {
        latest.set(snapshot)
        onSnapshotUpdated?.invoke()
    }

    fun getLatest(): FaceGeometrySnapshot? = latest.get()

    fun clear() = publish(null)
}
