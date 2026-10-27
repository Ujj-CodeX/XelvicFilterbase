package com.example

import android.graphics.PointF
import android.graphics.RectF
import com.example.xelvic.model.AnchorType
import com.example.xelvic.model.FaceTrackingResult
import com.example.xelvic.model.FilterDefinition
import com.example.xelvic.model.RotationMode
import com.example.xelvic.model.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun parseFilterDefinitionJson() {
        val manifestJson = """
        {
          "schemaVersion": 1,
          "id": "test_filter",
          "name": "Test Filter",
          "version": "1.0.0",
          "description": "Test description",
          "minimumRuntimeVersion": 1,
          "thumbnail": "thumbnail.png",
          "layers": [
            {
              "id": "main_layer",
              "asset": "assets/test.png",
              "anchor": "FOREHEAD_TOP",
              "scaleMode": "FACE_WIDTH",
              "scale": 0.85,
              "offsetX": 0.05,
              "offsetY": -0.15,
              "rotationMode": "FACE_ROLL",
              "rotationDegrees": 10.0,
              "opacity": 0.9,
              "zIndex": 1
            }
          ]
        }
        """.trimIndent()

        val definition = FilterDefinition.fromJson(manifestJson)
        assertEquals("test_filter", definition.id)
        assertEquals("Test Filter", definition.name)
        assertEquals(1, definition.schemaVersion)
        assertEquals(1, definition.layers.size)

        val layer = definition.layers[0]
        assertEquals("main_layer", layer.id)
        assertEquals("assets/test.png", layer.asset)
        assertEquals(AnchorType.FOREHEAD_TOP, layer.anchor)
        assertEquals(ScaleMode.FACE_WIDTH, layer.scaleMode)
        assertEquals(0.85f, layer.scale, 0.001f)
        assertEquals(0.05f, layer.offsetX, 0.001f)
        assertEquals(-0.15f, layer.offsetY, 0.001f)
        assertEquals(RotationMode.FACE_ROLL, layer.rotationMode)
        assertEquals(10f, layer.rotationDegrees, 0.001f)
        assertEquals(0.9f, layer.opacity, 0.001f)
        assertEquals(1, layer.zIndex)
    }

    @Test
    fun parseCatFaceManifestAndValidateLayers() {
        val catManifestJson = """
        {
          "schemaVersion": 1,
          "id": "cat_face",
          "name": "Cat Face",
          "version": "1.0.0",
          "description": "Playful 2D cat face with ears, nose, and whiskers",
          "minimumRuntimeVersion": 1,
          "thumbnail": "thumbnail.png",
          "layers": [
            {
              "id": "ears",
              "asset": "assets/cat_ears.png",
              "anchor": "FOREHEAD_TOP",
              "scaleMode": "FACE_WIDTH",
              "scale": 0.76,
              "offsetX": 0.0,
              "offsetY": -0.14,
              "rotationMode": "FACE_ROLL",
              "rotationDegrees": 0.0,
              "opacity": 1.0,
              "zIndex": 0
            },
            {
              "id": "nose",
              "asset": "assets/cat_nose.png",
              "anchor": "NOSE",
              "scaleMode": "FACE_WIDTH",
              "scale": 0.12,
              "offsetX": 0.0,
              "offsetY": 0.0,
              "rotationMode": "FACE_ROLL",
              "rotationDegrees": 0.0,
              "opacity": 1.0,
              "zIndex": 1
            },
            {
              "id": "left_whiskers",
              "asset": "assets/cat_whiskers_left.png",
              "anchor": "LEFT_CHEEK",
              "scaleMode": "FACE_WIDTH",
              "scale": 0.34,
              "offsetX": -0.04,
              "offsetY": 0.0,
              "rotationMode": "FACE_ROLL",
              "rotationDegrees": 0.0,
              "opacity": 1.0,
              "zIndex": 2
            },
            {
              "id": "right_whiskers",
              "asset": "assets/cat_whiskers_right.png",
              "anchor": "RIGHT_CHEEK",
              "scaleMode": "FACE_WIDTH",
              "scale": 0.34,
              "offsetX": 0.04,
              "offsetY": 0.0,
              "rotationMode": "FACE_ROLL",
              "rotationDegrees": 0.0,
              "opacity": 1.0,
              "zIndex": 2
            }
          ]
        }
        """.trimIndent()

        val definition = FilterDefinition.fromJson(catManifestJson)
        assertEquals("cat_face", definition.id)
        assertEquals("Cat Face", definition.name)
        assertEquals(4, definition.layers.size)

        // 1. Cat Ears validation: 65% - 80% face width
        val earsLayer = definition.layers.find { it.id == "ears" }
        assertNotNull(earsLayer)
        assertEquals(AnchorType.FOREHEAD_TOP, earsLayer!!.anchor)
        assertEquals(ScaleMode.FACE_WIDTH, earsLayer.scaleMode)
        assertTrue("Ears scale should be between 0.65 and 0.80", earsLayer.scale in 0.65f..0.80f)

        // 2. Cat Nose validation: 8% - 14% face width
        val noseLayer = definition.layers.find { it.id == "nose" }
        assertNotNull(noseLayer)
        assertEquals(AnchorType.NOSE, noseLayer!!.anchor)
        assertEquals(ScaleMode.FACE_WIDTH, noseLayer.scaleMode)
        assertTrue("Nose scale should be between 0.08 and 0.14", noseLayer.scale in 0.08f..0.14f)

        // 3. Whiskers validation: Left and Right cheek anchors
        val leftWhiskers = definition.layers.find { it.id == "left_whiskers" }
        assertNotNull(leftWhiskers)
        assertEquals(AnchorType.LEFT_CHEEK, leftWhiskers!!.anchor)
        assertTrue("Whiskers scale should be around 0.30 - 0.38", leftWhiskers.scale in 0.30f..0.38f)

        val rightWhiskers = definition.layers.find { it.id == "right_whiskers" }
        assertNotNull(rightWhiskers)
        assertEquals(AnchorType.RIGHT_CHEEK, rightWhiskers!!.anchor)
        assertTrue("Whiskers scale should be around 0.30 - 0.38", rightWhiskers.scale in 0.30f..0.38f)
    }

    @Test
    fun verifyFaceRelativeSizingScalesWithDistance() {
        val nearFaceWidth = 400f
        val farFaceWidth = 200f

        val earsScale = 0.76f
        val noseScale = 0.12f

        val nearEarsWidth = nearFaceWidth * earsScale
        val farEarsWidth = farFaceWidth * earsScale

        assertEquals(304f, nearEarsWidth, 0.01f)
        assertEquals(152f, farEarsWidth, 0.01f)
        assertEquals(2.0f, nearEarsWidth / farEarsWidth, 0.01f)

        val nearNoseWidth = nearFaceWidth * noseScale
        val farNoseWidth = farFaceWidth * noseScale
        assertEquals(48f, nearNoseWidth, 0.01f)
        assertEquals(24f, farNoseWidth, 0.01f)
        assertEquals(2.0f, nearNoseWidth / farNoseWidth, 0.01f)
    }

    @Test
    fun resolveForeheadTopAnchor() {
        val bounds = RectF(100f, 100f, 300f, 400f)
        val landmarks = mapOf(
            "LEFT_EYE" to PointF(150f, 200f),
            "RIGHT_EYE" to PointF(250f, 200f),
            "NOSE" to PointF(200f, 260f),
            "LEFT_CHEEK" to PointF(130f, 290f),
            "RIGHT_CHEEK" to PointF(270f, 290f)
        )
        val tracking = FaceTrackingResult(
            hasFace = true,
            bounds = bounds,
            landmarks = landmarks,
            rollDegrees = 5f
        )

        val forehead = tracking.resolveAnchor(AnchorType.FOREHEAD_TOP)
        assertNotNull(forehead)
        assertEquals(200f, forehead!!.x, 0.01f)
        assertTrue("Forehead should be above eye level", forehead.y < 200f)
        assertTrue("Forehead should be below top of bounding box", forehead.y >= bounds.top)

        val eyeCenter = tracking.resolveAnchor(AnchorType.EYES_CENTER)
        assertNotNull(eyeCenter)
        assertEquals(200f, eyeCenter!!.x, 0.01f)
        assertEquals(200f, eyeCenter.y, 0.01f)

        val noseAnchor = tracking.resolveAnchor(AnchorType.NOSE)
        assertNotNull(noseAnchor)
        assertEquals(200f, noseAnchor!!.x, 0.01f)
        assertEquals(260f, noseAnchor.y, 0.01f)

        val leftCheek = tracking.resolveAnchor(AnchorType.LEFT_CHEEK)
        assertNotNull(leftCheek)
        assertEquals(130f, leftCheek!!.x, 0.01f)
        assertEquals(290f, leftCheek.y, 0.01f)

        val rightCheek = tracking.resolveAnchor(AnchorType.RIGHT_CHEEK)
        assertNotNull(rightCheek)
        assertEquals(270f, rightCheek!!.x, 0.01f)
        assertEquals(290f, rightCheek.y, 0.01f)
    }

    @Test
    fun verifyOneEuroFilterJitterSuppressionAndResponsiveness() {
        val filter = com.example.xelvic.perf.OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
        var t = 1000L

        // Initial sample
        val init = filter.filter(100f, t)
        assertEquals(100f, init, 0.001f)

        // Low speed jitter around 100f: input alternates 99f and 101f
        var filteredVal = 100f
        for (i in 1..10) {
            t += 33L
            val raw = if (i % 2 == 0) 101f else 99f
            filteredVal = filter.filter(raw, t)
        }
        // At rest with minCutoff=1Hz, variation should be heavily dampened close to 100f
        assertTrue("Filtered jitter should stay within [99.5, 100.5]", filteredVal in 99.5f..100.5f)

        // High speed transition: jump to 200f
        t += 33L
        val step1 = filter.filter(200f, t)
        assertTrue("Filter should respond quickly to fast movement", step1 > 110f)

        // After several fast frames, it should approach 200f rapidly
        for (i in 1..5) {
            t += 33L
            filteredVal = filter.filter(200f, t)
        }
        assertTrue("Filter should converge near 200f", filteredVal > 185f)
    }

    @Test
    fun verifyOneEuroFilterAngleWraparound() {
        val filter = com.example.xelvic.perf.OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
        var t = 1000L

        // Start at 178 degrees
        val start = filter.filterAngle(178f, t)
        assertEquals(178f, start, 0.001f)

        // Step to -178 degrees (delta across 180° boundary is +4° shortest path, NOT -356°)
        t += 33L
        val smoothed = filter.filterAngle(-178f, t)

        // The smoothed angle should be near 180° / -180°, and NOT swing across 0°
        assertTrue(
            "Smoothed angle ($smoothed) should stay near +/-180 boundary rather than rotating through 0",
            smoothed >= 170f || smoothed <= -170f
        )
    }

    @Test
    fun verifyAdaptivePerformanceControllerHysteresisAndCooldown() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val monitor = com.example.xelvic.perf.PerformanceMonitor(context)
        val controller = com.example.xelvic.perf.AdaptivePerformanceController(monitor)

        var t = 1000L
        assertEquals(com.example.xelvic.perf.TrackingProfile.BALANCED, controller.getCurrentProfile())

        // Under normal latency (20ms), profile remains BALANCED
        monitor.recordInference(20L, t)
        controller.shouldAnalyzeFrame(t, true)
        assertEquals(com.example.xelvic.perf.TrackingProfile.BALANCED, controller.getCurrentProfile())

        // Latency spikes to 50ms (> 45ms threshold)
        for (i in 1..10) {
            monitor.recordInference(50L, t)
        }
        t += 33L
        controller.shouldAnalyzeFrame(t, true)
        // Immediate downgrade to PERFORMANCE
        assertEquals(com.example.xelvic.perf.TrackingProfile.PERFORMANCE, controller.getCurrentProfile())

        // Latency recovers immediately to 15ms (flush 30-capacity rolling window)
        for (i in 1..30) {
            monitor.recordInference(15L, t)
        }
        t += 500L
        controller.shouldAnalyzeFrame(t, true)
        // Profile must NOT upgrade immediately due to 3-second cooldown hysteresis
        assertEquals(
            "Profile should remain PERFORMANCE before 3-second cooldown",
            com.example.xelvic.perf.TrackingProfile.PERFORMANCE,
            controller.getCurrentProfile()
        )

        // Advance beyond 3000ms cooldown
        t += 3500L
        controller.shouldAnalyzeFrame(t, true)
        // Now it upgrades back to BALANCED
        assertEquals(
            "Profile should upgrade to BALANCED after cooldown expires",
            com.example.xelvic.perf.TrackingProfile.BALANCED,
            controller.getCurrentProfile()
        )
    }

    @Test
    fun verifyTrackingPoseHolderBoundedPrediction() {
        val holder = com.example.xelvic.perf.TrackingPoseHolder()
        var callbackFired = false
        holder.onPoseUpdated = { callbackFired = true }

        val initialBounds = RectF(100f, 100f, 300f, 400f)
        val initialResult = FaceTrackingResult(
            hasFace = true,
            bounds = initialBounds,
            landmarks = mapOf("NOSE" to PointF(200f, 250f)),
            timestampMs = 1000L
        )

        holder.updatePose(initialResult)
        assertTrue("Callback should fire on pose update", callbackFired)
        assertEquals(100f, holder.getLatestPose().bounds.left, 0.001f)

        // Pose 2 at t=1033ms moving +100px in X direction over 33ms (~3030 px/s)
        val movedBounds = RectF(200f, 100f, 400f, 400f)
        val movedResult = FaceTrackingResult(
            hasFace = true,
            bounds = movedBounds,
            landmarks = mapOf("NOSE" to PointF(300f, 250f)),
            timestampMs = 1033L
        )
        holder.updatePose(movedResult)

        // Predict 20ms into the future (display VSYNC lead time)
        val predicted = holder.getLatestPose(predictToTimestampMs = 1053L)
        // Bounds should shift forward in positive X direction, bounded by max displacement (25px)
        assertTrue(
            "Predicted bounds (${predicted.bounds.left}) should lead moved bounds (200f)",
            predicted.bounds.left > 200f
        )
        assertTrue(
            "Displacement must be clamped to max 25px",
            predicted.bounds.left <= 225f
        )
        assertTrue(
            "Landmark must also translate forward with bounds",
            predicted.landmarks["NOSE"]!!.x > 300f && predicted.landmarks["NOSE"]!!.x <= 325f
        )
    }
}

