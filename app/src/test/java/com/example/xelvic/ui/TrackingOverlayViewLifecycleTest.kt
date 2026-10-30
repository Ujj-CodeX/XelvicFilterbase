package com.example.xelvic.ui

import android.app.Activity
import android.widget.FrameLayout
import com.example.xelvic.perf.TrackingPoseHolder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingOverlayViewLifecycleTest {

    @Test
    fun detachedCanvasOverlayReleasesPoseInvalidationCallback() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val holder = TrackingPoseHolder()
        val overlay = TrackingOverlayView(activity).apply { poseHolder = holder }

        root.addView(overlay)
        assertNotNull(holder.onPoseUpdated)

        root.removeView(overlay)
        assertNull(holder.onPoseUpdated)
    }

    @Test
    fun releasingOldOverlayCannotClearReplacementCallback() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val holder = TrackingPoseHolder()
        val first = TrackingOverlayView(activity).apply { poseHolder = holder }
        val second = TrackingOverlayView(activity).apply { poseHolder = holder }

        root.addView(first)
        root.removeView(first)
        root.addView(second)
        val replacementCallback = holder.onPoseUpdated
        assertNotNull(replacementCallback)

        first.poseHolder = null

        assertSame(replacementCallback, holder.onPoseUpdated)
    }
}
