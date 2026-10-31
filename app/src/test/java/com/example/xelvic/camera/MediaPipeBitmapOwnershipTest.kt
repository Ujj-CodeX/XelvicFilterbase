package com.example.xelvic.camera

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPipeBitmapOwnershipTest {

    @Test
    fun closingMpImageRecyclesOnlyTheBitmapPassedToBuilder() {
        val capturedBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        try {
            val inferenceBitmap = requireNotNull(
                capturedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            )
            assertNotSame(capturedBitmap, inferenceBitmap)

            val mpImage = BitmapImageBuilder(inferenceBitmap).build()
            try {
                assertFalse("The captured photo must remain valid while MPImage is open", capturedBitmap.isRecycled)
                assertFalse("The inference copy must remain valid while MPImage is open", inferenceBitmap.isRecycled)
            } finally {
                mpImage.close()
            }

            assertTrue("MPImage.close() must release the bitmap it owns", inferenceBitmap.isRecycled)
            assertFalse("Closing the inference image must not recycle the captured photo", capturedBitmap.isRecycled)
            assertEquals(16, capturedBitmap.width)
            assertEquals(16, capturedBitmap.height)
        } finally {
            if (!capturedBitmap.isRecycled) {
                capturedBitmap.recycle()
            }
        }
    }
}
