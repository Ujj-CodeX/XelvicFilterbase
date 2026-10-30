package com.example.xelvic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.xelvic.geometry.FaceGeometryProjection
import com.example.xelvic.geometry.FaceGeometrySnapshotHolder
import com.example.xelvic.geometry.FaceMeshTessellation

/**
 * Development-only geometry overlay.
 *
 * Uses MediaPipe Tasks API connectivity for a 468-vertex face-surface wireframe and displays
 * any remaining landmarks (the current model adds 10 iris points) as dots. No UV texture is
 * rendered in this phase; UV compatibility still needs a separate, version-pinned source.
 */
class FaceGeometryDebugOverlayView(context: Context) : View(context) {

    @Volatile
    var snapshotHolder: FaceGeometrySnapshotHolder? = null
        set(value) {
            if (field === value) return
            field?.onSnapshotUpdated = null
            field = value
            value?.onSnapshotUpdated = { postInvalidateOnAnimation() }
            invalidate()
        }

    @Volatile
    var geometryDebugEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Overlay visibility must not clear controller-owned geometry shared with other renderers.
            invalidate()
        }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.FILL
    }

    private val wireframePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 0.65f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
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

    private val pointRadiusPx = 1.35f * resources.displayMetrics.density

    // Reused render buffers: no Point object or coordinate array is allocated per landmark/frame.
    private var projectedXY = FloatArray(0)
    private var projectedValid = BooleanArray(0)
    private var pointCoordinates = FloatArray(0)
    private var lineCoordinates = FloatArray(0)

    override fun onDetachedFromWindow() {
        snapshotHolder = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!geometryDebugEnabled) return

        val renderFrame = snapshotHolder?.getLatest()
        val snapshot = renderFrame?.snapshot
        if (renderFrame == null || snapshot == null) {
            drawHud(canvas, "MediaPipe geometry: waiting for face…")
            return
        }

        val landmarkCount = snapshot.landmarks.size
        ensureBuffers(landmarkCount)

        var pointFloatCount = 0
        val exactTransform = renderFrame.imageToPreview
        for (index in 0 until landmarkCount) {
            val vertex = snapshot.landmarks[index]
            val offset = index * 2
            val mapped = if (exactTransform != null) {
                exactTransform.mapPixelInto(
                    vertex.x * snapshot.imageWidth,
                    vertex.y * snapshot.imageHeight,
                    projectedXY,
                    offset
                )
            } else {
                FaceGeometryProjection.projectFillCenterInto(
                    vertex = vertex,
                    imageWidth = snapshot.imageWidth,
                    imageHeight = snapshot.imageHeight,
                    viewWidth = width,
                    viewHeight = height,
                    mirrorHorizontally = snapshot.isMirrored,
                    output = projectedXY,
                    offset = offset
                )
            }

            projectedValid[index] = mapped
            if (mapped) {
                pointCoordinates[pointFloatCount++] = projectedXY[offset]
                pointCoordinates[pointFloatCount++] = projectedXY[offset + 1]
            }
        }

        val topologyMatches =
            snapshot.topologyId == FaceMeshTessellation.TOPOLOGY_ID &&
                FaceMeshTessellation.supportsLandmarkCount(landmarkCount)

        var lineFloatCount = 0
        if (topologyMatches) {
            for (edge in 0 until FaceMeshTessellation.EDGE_COUNT) {
                val first = FaceMeshTessellation.edgeVertexIndex(edge * 2)
                val second = FaceMeshTessellation.edgeVertexIndex(edge * 2 + 1)
                if (!projectedValid[first] || !projectedValid[second]) continue

                val firstOffset = first * 2
                val secondOffset = second * 2
                lineCoordinates[lineFloatCount++] = projectedXY[firstOffset]
                lineCoordinates[lineFloatCount++] = projectedXY[firstOffset + 1]
                lineCoordinates[lineFloatCount++] = projectedXY[secondOffset]
                lineCoordinates[lineFloatCount++] = projectedXY[secondOffset + 1]
            }
        }

        if (lineFloatCount > 0) {
            canvas.drawLines(lineCoordinates, 0, lineFloatCount, wireframePaint)
        }
        if (pointFloatCount > 0) {
            canvas.drawPoints(pointCoordinates, 0, pointFloatCount, pointPaint)
        }

        val ageMs = (SystemClock.uptimeMillis() - snapshot.timestampMs).coerceAtLeast(0L)
        val topologyLabel = if (topologyMatches) {
            "${FaceMeshTessellation.EDGE_COUNT} edges"
        } else {
            "topology mismatch · dots only"
        }
        val transformLabel = if (exactTransform != null) "CameraX" else "fallback"
        drawHud(
            canvas,
            "MediaPipe · $landmarkCount pts · $topologyLabel · ${ageMs}ms · $transformLabel"
        )
    }

    private fun ensureBuffers(landmarkCount: Int) {
        val projectedSize = landmarkCount * 2
        if (projectedXY.size < projectedSize) projectedXY = FloatArray(projectedSize)
        if (projectedValid.size < landmarkCount) projectedValid = BooleanArray(landmarkCount)
        if (pointCoordinates.size < projectedSize) pointCoordinates = FloatArray(projectedSize)

        val lineCapacity = FaceMeshTessellation.EDGE_INDEX_COUNT * 2
        if (lineCoordinates.size < lineCapacity) lineCoordinates = FloatArray(lineCapacity)
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
                snapshotHolder = if (enabled) holder else null
                geometryDebugEnabled = enabled
            }
        },
        update = { view ->
            view.snapshotHolder = if (enabled) holder else null
            view.geometryDebugEnabled = enabled
        },
        modifier = modifier.testTag("face_geometry_debug_overlay"),
        onRelease = { view ->
            view.snapshotHolder = null
            view.geometryDebugEnabled = false
        }
    )
}
