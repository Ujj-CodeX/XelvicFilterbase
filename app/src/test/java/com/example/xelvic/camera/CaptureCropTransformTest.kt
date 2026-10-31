package com.example.xelvic.camera

import android.graphics.Bitmap
import android.graphics.Color
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

        for (rotation in rotations) {
            for (isFrontCamera in cameraFacingOptions) {
                val source = createMarkedSourceBitmap()
                var output: Bitmap? = null
                var expected: Bitmap? = null

                try {
                    output = createUprightCaptureBitmap(
                        sourceBitmap = source,
                        cropRect = crop,
                        rotationDegrees = rotation,
                        isFrontCamera = isFrontCamera
                    )
                    expected = createExpectedOutput(
                        sourceBitmap = source,
                        cropRect = crop,
                        rotationDegrees = rotation,
                        mirrorHorizontally = isFrontCamera
                    )

                    assertEquals(
                        "width rotation=$rotation front=$isFrontCamera",
                        expected.width,
                        output.width
                    )
                    assertEquals(
                        "height rotation=$rotation front=$isFrontCamera",
                        expected.height,
                        output.height
                    )
                    assertBitmapPixelsEqual(
                        expected = expected,
                        actual = output,
                        context = "rotation=$rotation front=$isFrontCamera"
                    )
                    assertFalse(
                        "The helper must not recycle its caller-owned source bitmap",
                        source.isRecycled
                    )
                } finally {
                    if (expected != null && !expected.isRecycled) {
                        expected.recycle()
                    }
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

        // A solid crop plus four distinct corner markers makes crop leakage,
        // rotation direction, and front-camera mirroring visible in a pixel comparison.
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
     * Build the expected result independently, mapping each cropped source pixel
     * to its exact destination coordinate. No Android Matrix APIs are used here.
     */
    private fun createExpectedOutput(
        sourceBitmap: Bitmap,
        cropRect: Rect,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): Bitmap {
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        val outputWidth = if (rotationDegrees % 180 == 0) cropWidth else cropHeight
        val outputHeight = if (rotationDegrees % 180 == 0) cropHeight else cropWidth
        val expected = Bitmap.createBitmap(
            outputWidth,
            outputHeight,
            Bitmap.Config.ARGB_8888
        )

        try {
            for (sourceY in 0 until cropHeight) {
                for (sourceX in 0 until cropWidth) {
                    val rotated = when (rotationDegrees) {
                        0 -> sourceX to sourceY
                        90 -> (cropHeight - 1 - sourceY) to sourceX
                        180 -> (cropWidth - 1 - sourceX) to (cropHeight - 1 - sourceY)
                        270 -> sourceY to (cropWidth - 1 - sourceX)
                        else -> throw IllegalArgumentException(
                            "Unsupported test rotation: $rotationDegrees"
                        )
                    }

                    val outputX = if (mirrorHorizontally) {
                        outputWidth - 1 - rotated.first
                    } else {
                        rotated.first
                    }

                    expected.setPixel(
                        outputX,
                        rotated.second,
                        sourceBitmap.getPixel(
                            cropRect.left + sourceX,
                            cropRect.top + sourceY
                        )
                    )
                }
            }
            return expected
        } catch (error: Throwable) {
            expected.recycle()
            throw error
        }
    }

    private fun assertBitmapPixelsEqual(
        expected: Bitmap,
        actual: Bitmap,
        context: String
    ) {
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expectedColor = expected.getPixel(x, y)
                val actualColor = actual.getPixel(x, y)
                val message = "Pixel mismatch " + context + " at (" + x + "," + y + "): " +
                    "expected=0x" + expectedColor.toUInt().toString(16) +
                    ", actual=0x" + actualColor.toUInt().toString(16)
                assertEquals(message, expectedColor, actualColor)
            }
        }
    }

    private companion object {
        const val OUTSIDE_CROP_COLOR = 0xFFFF00FF.toInt()
        const val INSIDE_CROP_COLOR = 0xFF202020.toInt()
        const val MARKER_SIZE = 40
    }
}
