package com.example.xelvic.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.xelvic.model.FaceTrackingResult
import com.example.xelvic.model.FilterLayer
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.model.RotationMode
import com.example.xelvic.model.ScaleMode
import com.example.xelvic.perf.OneEuroFilter
import kotlin.math.roundToInt

/**
 * Generic 2D sticker renderer that composites filter layers based on package manifest definitions.
 * Employs One-Euro filtering for adaptive jitter suppression, shortest-path angle interpolation,
 * and zero-allocation draw calculations.
 */
class StickerRenderer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Reusable destination rectangle to avoid per-frame allocations
    private val dstRect = RectF()

    // Per-layer One-Euro filter sets
    private class LayerOneEuroFilters(
        val xFilter: OneEuroFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f),
        val yFilter: OneEuroFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f),
        val scaleFilter: OneEuroFilter = OneEuroFilter(minCutoff = 1.2f, beta = 0.03f),
        val rotationFilter: OneEuroFilter = OneEuroFilter(minCutoff = 1.2f, beta = 0.04f)
    ) {
        fun reset() {
            xFilter.reset()
            yFilter.reset()
            scaleFilter.reset()
            rotationFilter.reset()
        }
    }

    private val layerFiltersMap = mutableMapOf<String, LayerOneEuroFilters>()

    fun resetSmoothing() {
        layerFiltersMap.values.forEach { it.reset() }
    }

    /**
     * Renders all layers of the active filter onto the provided Canvas.
     *
     * @param canvas Target Android Canvas (Preview overlay or full photo).
     * @param filter The active installed filter package.
     * @param trackingResult Face tracking data.
     * @param smoothTracking True to apply One-Euro adaptive filtering on live video frames.
     */
    fun render(
        canvas: Canvas,
        filter: InstalledFilter,
        trackingResult: FaceTrackingResult,
        smoothTracking: Boolean = false
    ) {
        if (!trackingResult.hasFace) {
            resetSmoothing()
            return
        }

        val layers = filter.definition.layers
        for (layer in layers) {
            val bitmap = filter.getAssetBitmap(layer.asset) ?: continue
            renderLayer(canvas, layer, bitmap, trackingResult, smoothTracking)
        }
    }

    private fun renderLayer(
        canvas: Canvas,
        layer: FilterLayer,
        bitmap: Bitmap,
        tracking: FaceTrackingResult,
        smoothTracking: Boolean
    ) {
        val anchorPoint = tracking.resolveAnchor(layer.anchor) ?: return

        val faceWidth = tracking.bounds.width()
        val faceHeight = tracking.bounds.height()

        // 1. Calculate Target Dimensions
        val baseDimension = when (layer.scaleMode) {
            ScaleMode.FACE_WIDTH -> faceWidth
            ScaleMode.FACE_HEIGHT -> faceHeight
            ScaleMode.EYE_DISTANCE -> tracking.computeEyeDistance()
        }

        val targetWidth = baseDimension * layer.scale
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeight = targetWidth * aspectRatio

        // 2. Offsets (normalized to face bounds)
        val offsetX = layer.offsetX * faceWidth
        val offsetY = layer.offsetY * faceHeight

        val rawTargetX = anchorPoint.x + offsetX
        val rawTargetY = anchorPoint.y + offsetY

        // 3. Rotation (using eye line or head pose)
        val rawRotation = when (layer.rotationMode) {
            RotationMode.FACE_ROLL -> tracking.rollDegrees + layer.rotationDegrees
            RotationMode.EYE_LINE -> tracking.computeEyeLineAngleDegrees() + layer.rotationDegrees
            RotationMode.FIXED -> layer.rotationDegrees
        }

        // 4. One-Euro Adaptive Filtering for real-time live preview
        val finalX: Float
        val finalY: Float
        val finalWidth: Float
        val finalHeight: Float
        val finalRotation: Float

        if (smoothTracking) {
            val filters = layerFiltersMap.getOrPut(layer.id) { LayerOneEuroFilters() }
            val timestamp = tracking.timestampMs

            finalX = filters.xFilter.filter(rawTargetX, timestamp)
            finalY = filters.yFilter.filter(rawTargetY, timestamp)
            finalWidth = filters.scaleFilter.filter(targetWidth, timestamp)
            finalHeight = finalWidth * aspectRatio
            finalRotation = filters.rotationFilter.filterAngle(rawRotation, timestamp)
        } else {
            finalX = rawTargetX
            finalY = rawTargetY
            finalWidth = targetWidth
            finalHeight = targetHeight
            finalRotation = rawRotation
        }

        // 5. Opacity
        paint.alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)

        // 6. Draw on Canvas
        canvas.save()
        canvas.translate(finalX, finalY)
        canvas.rotate(finalRotation)

        // Reuse pre-allocated RectF to eliminate GC churn on 60fps hot path
        dstRect.set(
            -finalWidth / 2f,
            -finalHeight / 2f,
            finalWidth / 2f,
            finalHeight / 2f
        )
        canvas.drawBitmap(bitmap, null, dstRect, paint)

        canvas.restore()
    }
}
