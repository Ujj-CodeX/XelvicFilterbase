package com.example.xelvic.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
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
        val markers = listOf(
            Marker(Color.RED, 30, 30),
            Marker(Color.GREEN, 570, 30),
            Marker(Color.BLUE, 30, 380),
            Marker(Color.YELLOW, 570, 380)
        )

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
                    assertEquals(
                        "width rotation=$rotation front=$isFrontCamera",
                        expectedWidth,
                        output.width
                    )
                    assertEquals(
                        "height rotation=$rotation front=$isFrontCamera",
                        expectedHeight,
                        output.height
                    )

                    for (marker in markers) {
                        val expected = mapCropPoint(
                            point = Point(marker.x, marker.y),
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

                    assertFalse(
                        "Pixels outside crop leaked, rotation=$rotation front=$isFrontCamera",
                        containsColor(output, OUTSIDE_CROP_COLOR)
                    )
                    assertFalse(
                        "The helper must not recycle its caller-owned source bitmap",
                        source.isRecycled
                    )
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
    fun invalidCropIsRejectedWithoutRecyclingSource() {
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

        // Fill the viewport crop and its four marker patches directly. Using setPixel
        // keeps the fixture deterministic and independent of Robolectric Canvas drawing.
        for (y in 50 until 450) {
            for (x in 100 until 700) {
                bitmap.setPixel(x, y, INSIDE_CROP_COLOR)
            }
        }

        drawMarkerPixels(bitmap, Color.RED, 110, 60)
        drawMarkerPixels(bitmap, Color.GREEN, 650, 60)
        drawMarkerPixels(bitmap, Color.BLUE, 110, 410)
        drawMarkerPixels(bitmap, Color.YELLOW, 650, 410)
        return bitmap
    }

    private fun drawMarkerPixels(
        bitmap: Bitmap,
        color: Int,
        left: Int,
        top: Int
    ) {
        for (y in top until top + MARKER_SIZE) {
            for (x in left until left + MARKER_SIZE) {
                bitmap.setPixel(x, y, color)
            }
        }
    }

    /**
     * Maps crop-local pixel coordinates through clockwise image rotation, then applies
     * the front-camera horizontal mirror in the final upright output coordinate space.
     */
    private fun mapCropPoint(
        point: Point,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): Point {
        val rotated = when (rotationDegrees) {
            0 -> Point(point.x, point.y)
            90 -> Point(cropHeight - 1 - point.y, point.x)
            180 -> Point(cropWidth - 1 - point.x, cropHeight - 1 - point.y)
            270 -> Point(point.y, cropWidth - 1 - point.x)
            else -> throw IllegalArgumentException("Unsupported rotation: $rotationDegrees")
        }

        val outputWidth = if (rotationDegrees % 180 == 0) cropWidth else cropHeight
        return if (mirrorHorizontally) {
            Point(outputWidth - 1 - rotated.x, rotated.y)
        } else {
            rotated
        }
    }

    private fun assertColorNear(
        bitmap: Bitmap,
        expectedColor: Int,
        expectedPoint: Point,
        context: String
    ) {
        val radius = 2
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

    private fun containsColor(bitmap: Bitmap, color: Int): Boolean {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == color) return true
            }
        }
        return false
    }

    private data class Marker(
        val color: Int,
        val x: Int,
        val y: Int
    )

    private companion object {
        const val OUTSIDE_CROP_COLOR = 0xFFFF00FF.toInt()
        const val INSIDE_CROP_COLOR = 0xFF202020.toInt()
        const val MARKER_SIZE = 40
    }
}
