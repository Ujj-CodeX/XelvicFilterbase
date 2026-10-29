package com.example.xelvic.geometry

import androidx.camera.core.ImageProxy

/**
 * Replaceable on-device face geometry backend.
 *
 * submitFrame transfers ownership of ImageProxy to the provider. Every submitted proxy
 * must be closed exactly once, including rejected frames and conversion failures.
 *
 * Callbacks may arrive on a MediaPipe-managed background thread. Consumers should publish
 * the snapshot atomically and marshal UI/GL work to the appropriate thread.
 */
interface FaceGeometryProvider : AutoCloseable {

    fun setResultListener(
        listener: (FaceGeometrySnapshot?, FaceGeometryDisplayTransform?) -> Unit
    )

    fun setErrorListener(listener: ((Throwable) -> Unit)?)

    fun submitFrame(
        imageProxy: ImageProxy,
        timestampMs: Long,
        isMirrored: Boolean,
        displayTransform: FaceGeometryDisplayTransform?
    )

    override fun close()
}
