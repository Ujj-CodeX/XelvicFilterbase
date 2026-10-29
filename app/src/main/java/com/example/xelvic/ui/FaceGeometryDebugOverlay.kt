package com.example.xelvic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.xelvic.geometry.FaceGeometryProjection
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import android.os.SystemClock

/**
 * Development-only point-cloud overlay. It deliberately draws no triangles until topology
 * and UV compatibility are pinned and verified against the selected model.
 */
class FaceGeometryDebugOverlayView(context: Context) : View(context) {

    @Volatile
    var snapshotHolder: FaceGeometrySnapshotHolder? = null
        set(value) {
            field?.onSnapshotUpdated = null
            field = value
            value?.onSnapshotUpdated = { postInvalidateOnAnimation() }
            invalidate()
        }

    @Volatile
    var geometryDebugEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) snapshotHolder?.clear()
            invalidate()
        }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.FILL
    }

    private val hudBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 8, 15, 24)
        style = Paint.Style.FILL
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val pointRadiusPx = 1.65f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!geometryDebugEnabled) return

        val renderFrame = snapshotHolder?.getLatest()
        val snapshot = renderFrame?.snapshot
        if (renderFrame == null || snapshot == null) {
            drawHud(canvas, "MediaPipe geometry: waiting for face…")
            return
        }

        for (vertex in snapshot.landmarks) {
            // Use the exact transform captured with this inference frame whenever available.
            // The fallback mirrors only at the display boundary and is intentionally approximate.
            val point = if (renderFrame.imageToPreview != null) {
                renderFrame.imageToPreview.mapPixel(
                    vertex.x * snapshot.imageWidth,
                    vertex.y * snapshot.imageHeight
                )
            } else {
                FaceGeometryProjection.projectFillCenter(
                    vertex = vertex,
                    imageWidth = snapshot.imageWidth,
                    imageHeight = snapshot.imageHeight,
                    viewWidth = width,
                    viewHeight = height,
                    mirrorHorizontally = snapshot.isMirrored
                )
            } ?: continue

            // Clipping is handled by Canvas; invalid or off-view coordinates are not clamped.
            canvas.drawCircle(point.x, point.y, pointRadiusPx, pointPaint)
        }

        val ageMs = (SystemClock.uptimeMillis() - snapshot.timestampMs).coerceAtLeast(0L)
        drawHud(
            canvas,
            "MediaPipe · ${snapshot.landmarks.size} points · age ${ageMs}ms · Z is relative"
        )
    }

    private fun drawHud(canvas: Canvas, message: String) {
        val left = 12f * resources.displayMetrics.density
        val top = 12f * resources.displayMetrics.density
        val horizontalPadding = 8f * resources.displayMetrics.density
        val verticalPadding = 6f * resources.displayMetrics.density
        val textWidth = hudTextPaint.measureText(message)

        canvas.drawRoundRect(
            left,
            top,
            left + textWidth + 2f * horizontalPadding,
            top + hudTextPaint.textSize + 2f * verticalPadding,
            8f * resources.displayMetrics.density,
            8f * resources.displayMetrics.density,
            hudBackgroundPaint
        )
        canvas.drawText(
            message,
            left + horizontalPadding,
            top + verticalPadding + hudTextPaint.textSize,
            hudTextPaint
        )
    }
}

@Composable
fun FaceGeometryDebugOverlay(
    holder: FaceGeometrySnapshotHolder,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            FaceGeometryDebugOverlayView(context).apply {
                snapshotHolder = holder
                geometryDebugEnabled = enabled
            }
        },
        update = { view ->
            view.snapshotHolder = holder
            view.geometryDebugEnabled = enabled
        },
        modifier = modifier.testTag("face_geometry_debug_overlay")
    )
}
