package com.example.xelvic.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.example.xelvic.geometry.AppFaceMeshUvLayouts
import com.example.xelvic.geometry.FaceMeshTessellation
import com.example.xelvic.geometry.UvOrigin
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.renderer.DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID
import com.example.xelvic.renderer.DIAGNOSTIC_MOUSTACHE_FILTER_ID
import com.example.xelvic.renderer.FaceMeshEffectResolution
import com.example.xelvic.renderer.FaceMeshEffectResolver
import com.example.xelvic.renderer.FaceMeshTextureUvMode
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.security.MessageDigest

/**
 * Still-photo counterpart of the live FaceMeshGlOverlayView.
 *
 * It runs the same pinned Face Landmarker task on the final upright capture bitmap, then maps the
 * same 468-vertex tessellation and validated texture UVs onto that bitmap using Android Canvas.
 * It deliberately does not reuse live-preview landmarks: those belong to a different frame/crop.
 */
class FaceMeshPhotoProcessor(
    context: Context,
    private val modelAssetPath: String = MODEL_ASSET_PATH
) : AutoCloseable {

    private val appContext = context.applicationContext

    private val modelSha256 by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        calculateModelAssetSha256()
    }

    private val effectResolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FaceMeshEffectResolver(AppFaceMeshUvLayouts.createRegistry(appContext.assets))
    }

    private val landmarkerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelAssetPath)
            .setDelegate(Delegate.CPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(DEFAULT_CONFIDENCE)
            .setMinFacePresenceConfidence(DEFAULT_CONFIDENCE)
            .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
            .build()

        FaceLandmarker.createFromOptions(appContext, options)
    }

    /** Returns the original bitmap with a warning when the effect cannot be safely composited. */
    fun process(bitmap: Bitmap, filter: InstalledFilter): PhotoProcessResult {
        val resolution = effectResolver.resolve(filter, modelSha256)
        val effect = (resolution as? FaceMeshEffectResolution.Ready)?.effect
            ?: return PhotoProcessResult(
                bitmap = bitmap,
                faceFound = false,
                message = "Mesh photo filter is unavailable: ${resolution.describeBlock()}"
            )

        // The input bitmap is borrowed from PhotoProcessor. MediaPipe's BitmapImageContainer
        // recycles the bitmap supplied to it when MPImage.close() is called, so never pass the
        // caller-owned review candidate directly to BitmapImageBuilder.
        val inferenceBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalStateException("Could not allocate MediaPipe inference bitmap")

        val faceLandmarks = try {
            val mpImage = BitmapImageBuilder(inferenceBitmap).build()
            try {
                landmarkerDelegate.value.detect(mpImage).faceLandmarks().firstOrNull()
            } finally {
                mpImage.close()
            }
        } finally {
            // MPImage.close() normally releases this bitmap. Keep this guarded cleanup for
            // failures that occur before MPImage is successfully built or closed.
            if (!inferenceBitmap.isRecycled) {
                inferenceBitmap.recycle()
            }
        }

        if (faceLandmarks.isNullOrEmpty()) {
            return PhotoProcessResult(
                bitmap = bitmap,
                faceFound = false,
                message = "No face detected in photo. Mesh filter could not be anchored."
            )
        }
        if (!FaceMeshTessellation.supportsLandmarkCount(faceLandmarks.size) ||
            faceLandmarks.size < FaceMeshTessellation.LANDMARK_COUNT
        ) {
            return PhotoProcessResult(
                bitmap = bitmap,
                faceFound = false,
                message = "Face Landmarker returned ${faceLandmarks.size} points; mesh topology requires 468 or 478."
            )
        }

        val x = FloatArray(FaceMeshTessellation.LANDMARK_COUNT)
        val y = FloatArray(FaceMeshTessellation.LANDMARK_COUNT)
        for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
            val landmark = faceLandmarks[index]
            val lx = landmark.x()
            val ly = landmark.y()
            if (!lx.isFinite() || !ly.isFinite()) {
                return PhotoProcessResult(
                    bitmap = bitmap,
                    faceFound = false,
                    message = "Face mesh contained invalid landmark coordinates."
                )
            }
            x[index] = lx * bitmap.width
            y[index] = ly * bitmap.height
        }

        val effectBitmap = effect.bitmap
        if (effectBitmap.isRecycled || effectBitmap.width <= 0 || effectBitmap.height <= 0) {
            return PhotoProcessResult(
                bitmap = bitmap,
                faceFound = false,
                message = "Mesh texture bitmap is invalid."
            )
        }

        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Could not allocate mesh-composited photograph")
        try {
            drawTextureMesh(
                canvas = Canvas(output),
                texture = effectBitmap,
                opacity = effect.opacity,
                x = x,
                y = y,
                effect = effect
            )
            return PhotoProcessResult(bitmap = output, faceFound = true)
        } catch (error: Exception) {
            if (!output.isRecycled) output.recycle()
            throw error
        }
    }

    private fun drawTextureMesh(
        canvas: Canvas,
        texture: Bitmap,
        opacity: Float,
        x: FloatArray,
        y: FloatArray,
        effect: com.example.xelvic.renderer.FaceMeshGlEffect
    ) {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        if (effect.uvMode == FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE) {
            require(
                effect.effectId == DIAGNOSTIC_MOUSTACHE_FILTER_ID &&
                    effect.uvLayoutId == DIAGNOSTIC_FACE_SPACE_UV_LAYOUT_ID &&
                    effect.uvMap == null
            ) { "Diagnostic face-space mapping is reserved for the bundled moustache fixture" }
            for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
                val nx = x[index] / canvas.width
                val ny = y[index] / canvas.height
                minX = minOf(minX, nx)
                maxX = maxOf(maxX, nx)
                minY = minOf(minY, ny)
                maxY = maxOf(maxY, ny)
            }
            require(maxX - minX > 1e-5f && maxY - minY > 1e-5f) {
                "Face bounds are degenerate; cannot map diagnostic texture"
            }
        } else {
            val uvMap = requireNotNull(effect.uvMap) { "Canonical mesh filter has no validated UV map" }
            require(uvMap.vertexCount == FaceMeshTessellation.LANDMARK_COUNT) {
                "Canonical UV map does not match the 468-vertex tessellation"
            }
            require(uvMap.topologyId == effect.topologyId &&
                uvMap.modelAssetSha256 == effect.modelAssetSha256 &&
                uvMap.uvLayoutId == effect.uvLayoutId
            ) { "Canonical UV map identity does not match the selected effect" }
        }

        val uv = FloatArray(FaceMeshTessellation.LANDMARK_COUNT * 2)
        for (index in 0 until FaceMeshTessellation.LANDMARK_COUNT) {
            if (effect.uvMode == FaceMeshTextureUvMode.DIAGNOSTIC_FACE_SPACE) {
                val nx = x[index] / canvas.width
                val ny = y[index] / canvas.height
                uv[index * 2] = ((nx - minX) / (maxX - minX)).coerceIn(0f, 1f)
                uv[index * 2 + 1] = ((ny - minY) / (maxY - minY)).coerceIn(0f, 1f)
            } else {
                val canonical = requireNotNull(effect.uvMap).coordinates[index]
                uv[index * 2] = canonical.u
                uv[index * 2 + 1] = when (requireNotNull(effect.uvMap).uvOrigin) {
                    UvOrigin.TOP_LEFT -> canonical.v
                    UvOrigin.BOTTOM_LEFT -> 1f - canonical.v
                }
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        }
        val matrix = Matrix()
        val clip = Path()
        val source = FloatArray(6)
        val destination = FloatArray(6)
        val triangleIndices = FaceMeshTessellation.copyTriangleIndices()

        var offset = 0
        while (offset < triangleIndices.size) {
            val a = triangleIndices[offset]
            val b = triangleIndices[offset + 1]
            val c = triangleIndices[offset + 2]

            source[0] = uv[a * 2] * texture.width
            source[1] = uv[a * 2 + 1] * texture.height
            source[2] = uv[b * 2] * texture.width
            source[3] = uv[b * 2 + 1] * texture.height
            source[4] = uv[c * 2] * texture.width
            source[5] = uv[c * 2 + 1] * texture.height

            destination[0] = x[a]
            destination[1] = y[a]
            destination[2] = x[b]
            destination[3] = y[b]
            destination[4] = x[c]
            destination[5] = y[c]

            if (matrix.setPolyToPoly(source, 0, destination, 0, 3)) {
                clip.reset()
                clip.moveTo(destination[0], destination[1])
                clip.lineTo(destination[2], destination[3])
                clip.lineTo(destination[4], destination[5])
                clip.close()
                val save = canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(texture, matrix, paint)
                canvas.restoreToCount(save)
            }
            offset += 3
        }
    }

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

    override fun close() {
        if (landmarkerDelegate.isInitialized()) {
            try {
                landmarkerDelegate.value.close()
            } catch (_: Exception) {
                // Best-effort resource release during ViewModel teardown.
            }
        }
    }

    private fun FaceMeshEffectResolution.describeBlock(): String = when (this) {
        FaceMeshEffectResolution.NoSelection -> "no filter selected"
        FaceMeshEffectResolution.LegacyCanvas -> "selected filter is a Canvas filter"
        is FaceMeshEffectResolution.Blocked -> reason.name
        is FaceMeshEffectResolution.Ready -> "unknown resolver state"
    }

    private companion object {
        const val MODEL_ASSET_PATH = "face_landmarker.task"
        const val DEFAULT_CONFIDENCE = 0.5f
    }
}
