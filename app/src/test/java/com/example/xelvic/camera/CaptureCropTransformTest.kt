package com.example.xelvic.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureCropTransformTest {

    @Test
    fun viewportCropIsAppliedBeforeEveryRotationAndMirrorCombination() {
        val rotations = listOf(0, 90, 180, 270)
        val cameraFacingOptions = listOf(false, true)
        val crop = Rect(100, 50, 700, 450)

        for (rotation in rotations) {
            for (isFrontCamera in cameraFacingOptions) {
                val source = createMarkedSourceBitmap()
                var output: Bitmap? = null

                try {
                    output = createUprightCaptureBitmap(
                        sourceBitmap = source,
                        cropRect = crop,
                        rotationDegrees = rotation,
                        isFrontCamera = isFrontCamera
                    )

                    val expectedWidth = if (rotation % 180 == 0) 600 else 400
                    val expectedHeight = if (rotation % 180 == 0) 400 else 600
                    assertEquals("width rotation=$rotation front=$isFrontCamera", expectedWidth, output.width)
                    assertEquals("height rotation=$rotation front=$isFrontCamera", expectedHeight, output.height)

                    val markerSamples = listOf(
                        Marker(Color.RED, 30, 30),
                        Marker(Color.GREEN, 570, 30),
                        Marker(Color.BLUE, 30, 370),
                        Marker(Color.YELLOW, 570, 370)
                    )

                    for (marker in markerSamples) {
                        val expected = mapSourcePoint(
                            x = marker.x,
                            y = marker.y,
                            cropWidth = 600,
                            cropHeight = 400,
                            rotationDegrees = rotation,
                            mirrorHorizontally = isFrontCamera
                        )
                        assertColorNear(
                            bitmap = output,
                            expectedColor = marker.color,
                            expectedPoint = expected,
                            context = "rotation=$rotation front=$isFrontCamera"
                        )
                    }

                    for (y in 0 until output.height) {
                        for (x in 0 until output.width) {
                            assertFalse(
                                "Pixels outside crop leaked at ($x,$y), rotation=$rotation front=$isFrontCamera",
                                output.getPixel(x, y) == OUTSIDE_CROP_COLOR
                            )
                        }
                    }
                } finally {
                    if (output != null && output !== source && !output.isRecycled) {
                        output.recycle()
                    }
                    if (!source.isRecycled) {
                        source.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun fullFrameWithoutRotationOrMirrorKeepsOriginalBitmap() {
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            val output = createUprightCaptureBitmap(
                sourceBitmap = source,
                cropRect = Rect(0, 0, 80, 60),
                rotationDegrees = 0,
                isFrontCamera = false
            )
            assertSame(source, output)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    @Test
    fun invalidCropIsRejected() {
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            val error = runCatching {
                createUprightCaptureBitmap(
                    sourceBitmap = source,
                    cropRect = Rect(10, 10, 90, 50),
                    rotationDegrees = 0,
                    isFrontCamera = false
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertFalse(source.isRecycled)
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun createMarkedSourceBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(OUTSIDE_CROP_COLOR)

        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL }
        paint.color = INSIDE_CROP_COLOR
        canvas.drawRect(RectF(100f, 50f, 700f, 450f), paint)

        drawMarker(canvas, paint, Color.RED, 110f, 60f)
        drawMarker(canvas, paint, Color.GREEN, 650f, 60f)
        drawMarker(canvas, paint, Color.BLUE, 110f, 410f)
        drawMarker(canvas, paint, Color.YELLOW, 650f, 410f)
        return bitmap
    }

    private fun drawMarker(
        canvas: Canvas,
        paint: Paint,
        color: Int,
        left: Float,
        top: Float
    ) {
        paint.color = color
        canvas.drawRect(left, top, left + 40f, top + 40f, paint)
    }

    private fun mapSourcePoint(
        x: Int,
        y: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): Point {
        // Derive expected coordinates from Android's own Matrix semantics, including the
        // translation needed to move the transformed crop bounds back to the origin.
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
            if (mirrorHorizontally) postScale(-1f, 1f)
        }
        val mappedBounds = RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat())
        matrix.mapRect(mappedBounds)

        val mappedCenter = floatArrayOf(x + 0.5f, y + 0.5f)
        matrix.mapPoints(mappedCenter)
        return Point(
            kotlin.math.floor(mappedCenter[0] - mappedBounds.left).toInt(),
            kotlin.math.floor(mappedCenter[1] - mappedBounds.top).toInt()
        )
    }

    private fun assertColorNear(
        bitmap: Bitmap,
        expectedColor: Int,
        expectedPoint: Point,
        context: String
    ) {
        val radius = 3
        var found = false
        for (y in (expectedPoint.y - radius).coerceAtLeast(0)
            ..(expectedPoint.y + radius).coerceAtMost(bitmap.height - 1)) {
            for (x in (expectedPoint.x - radius).coerceAtLeast(0)
                ..(expectedPoint.x + radius).coerceAtMost(bitmap.width - 1)) {
                if (bitmap.getPixel(x, y) == expectedColor) {
                    found = true
                    break
                }
            }
            if (found) break
        }
        assertTrue(
            "Expected marker colour near expected coordinate; " + context +
                ", expectedPoint=" + expectedPoint +
                ", output=" + bitmap.width + "x" + bitmap.height,
            found
        )
    }

    private data class Marker(
        val color: Int,
        val x: Int,
        val y: Int
    )

    private companion object {
        const val OUTSIDE_CROP_COLOR = 0xFFFF00FF.toInt()
        const val INSIDE_CROP_COLOR = 0xFF202020.toInt()
    }
}
