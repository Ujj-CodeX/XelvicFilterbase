package com.example.xelvic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.xelvic.model.AnchorType
import com.example.xelvic.model.InstalledFilter
import com.example.xelvic.perf.PerformanceMonitor
import com.example.xelvic.perf.TrackingPoseHolder
import com.example.xelvic.renderer.StickerRenderer

/**
 * High-performance hardware-accelerated Android View for the tracking overlay.
 * Decoupled from the Jetpack Compose recomposition cycle, drawing on display VSYNC
 * whenever new pose updates arrive from TrackingPoseHolder.
 */
class TrackingOverlayView(context: Context) : View(context) {

    var poseHolder: TrackingPoseHolder? = null
        set(value) {
            field = value
            value?.onPoseUpdated = {
                postInvalidateOnAnimation()
            }
        }

    var selectedFilter: InstalledFilter? = null
    var showDebugOverlay: Boolean = false
    var renderer: StickerRenderer? = null
    var performanceMonitor: PerformanceMonitor? = null

    // Pre-allocated paints to guarantee zero allocations during onDraw()
    private val debugBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val debugLandmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }

    private val debugAnchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val debugTelemetryBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 15, 23, 42) // Dark translucent slate
        style = Paint.Style.FILL
    }

    private val telemetryRect = RectF()

    init {
        // Enable hardware acceleration for overlay drawing
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val holder = poseHolder ?: return
        val currentRenderer = renderer ?: return
        val now = System.currentTimeMillis()

        // 1. Get latest pose with bounded lead-time prediction
        val trackingResult = holder.getLatestPose(now)

        // 2. Render active filter if tracked
        val filter = selectedFilter
        if (filter != null && trackingResult.hasFace) {
            currentRenderer.render(
                canvas = canvas,
                filter = filter,
                trackingResult = trackingResult,
                smoothTracking = true
            )
        }

        // 3. Render developer telemetry overlay if enabled
        if (showDebugOverlay) {
            drawDeveloperHUD(canvas, trackingResult)
        }
    }

    private fun drawDeveloperHUD(canvas: Canvas, trackingResult: com.example.xelvic.model.FaceTrackingResult) {
        val monitor = performanceMonitor

        // Draw telemetry panel at top
        val panelLeft = 24f
        val panelTop = 130f
        val panelRight = width - 24f
        val panelBottom = panelTop + 240f

        telemetryRect.set(panelLeft, panelTop, panelRight, panelBottom)
        canvas.drawRoundRect(telemetryRect, 16f, 16f, debugTelemetryBgPaint)

        var y = panelTop + 38f
        val x = panelLeft + 20f
        val lineSpacing = 38f

        if (monitor != null) {
            val previewRes = "${monitor.previewResolution.width}x${monitor.previewResolution.height}"
            val analysisRes = "${monitor.analysisResolution.width}x${monitor.analysisResolution.height}"
            canvas.drawText("Preview: $previewRes  |  Analysis: $analysisRes", x, y, debugTextPaint)
            y += lineSpacing

            val p50 = monitor.getP50InferenceMs()
            val p95 = monitor.getP95InferenceMs()
            val fps = "%.1f".format(monitor.getTrackingFps())
            canvas.drawText("ML Kit: p50 ${p50}ms, p95 ${p95}ms  |  Rate: ${fps} fps", x, y, debugTextPaint)
            y += lineSpacing

            val profile = monitor.currentProfileName
            val drops = monitor.getTotalFramesSkipped()
            canvas.drawText("Profile: $profile  |  Skipped/Dropped: $drops", x, y, debugTextPaint)
            y += lineSpacing

            val memoryMb = monitor.getUsedHeapMemoryMb()
            val thermal = monitor.getThermalStatusString()
            val powerSave = if (monitor.isPowerSaveMode()) "ON" else "OFF"
            canvas.drawText("Heap: ${memoryMb}MB  |  Thermal: $thermal  |  PwrSave: $powerSave", x, y, debugTextPaint)
            y += lineSpacing
        }

        // If face is currently tracked, draw spatial diagnostics
        if (trackingResult.hasFace) {
            val box = trackingResult.bounds
            canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, 16f, 16f, debugBoxPaint)

            for ((_, pt) in trackingResult.landmarks) {
                canvas.drawCircle(pt.x, pt.y, 7f, debugLandmarkPaint)
            }

            listOf(AnchorType.FOREHEAD_TOP, AnchorType.EYES_CENTER, AnchorType.NOSE).forEach { anchor ->
                trackingResult.resolveAnchor(anchor)?.let { pt ->
                    canvas.drawCircle(pt.x, pt.y, 9f, debugAnchorPaint)
                }
            }

            val orientationText = "Roll: ${"%.1f".format(trackingResult.rollDegrees)}°  Yaw: ${"%.1f".format(trackingResult.yawDegrees)}°"
            canvas.drawText(orientationText, box.left, (box.top - 16f).coerceAtLeast(panelBottom + 40f), debugTextPaint)
        } else {
            canvas.drawText("Tracking: Searching for face…", x, y, debugTextPaint)
        }
    }
}

/**
 * Compose wrapper hosting TrackingOverlayView via AndroidView without triggering recomposition on pose updates.
 */
@Composable
fun StickerOverlayView(
    poseHolder: TrackingPoseHolder,
    selectedFilter: InstalledFilter?,
    showDebugOverlay: Boolean,
    renderer: StickerRenderer,
    performanceMonitor: PerformanceMonitor,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            TrackingOverlayView(context).apply {
                this.poseHolder = poseHolder
                this.renderer = renderer
                this.performanceMonitor = performanceMonitor
                this.selectedFilter = selectedFilter
                this.showDebugOverlay = showDebugOverlay
            }
        },
        update = { view ->
            view.selectedFilter = selectedFilter
            view.showDebugOverlay = showDebugOverlay
            view.invalidate()
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("sticker_overlay_canvas")
    )
}
