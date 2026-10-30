package com.example.xelvic.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceGeometrySnapshotHolderTest {

    private fun snapshot(timestampMs: Long) = FaceGeometrySnapshot(
        timestampMs = timestampMs,
        imageWidth = 100,
        imageHeight = 100,
        rotationDegrees = 0,
        isMirrored = false,
        landmarks = listOf(FaceVertex(0.5f, 0.5f, 0f))
    )

    @Test
    fun nullGeometryWithoutTransformClearsFrameAndNotifiesEverySubscriber() {
        val holder = FaceGeometrySnapshotHolder()
        var firstNotifications = 0
        var secondNotifications = 0
        holder.addSnapshotListener { firstNotifications++ }
        holder.addSnapshotListener { secondNotifications++ }

        holder.publish(snapshot(1L), null)
        holder.publish(null, null)

        assertNull(holder.getLatest())
        assertEquals(2, firstNotifications)
        assertEquals(2, secondNotifications)
    }

    @Test
    fun closedSubscriptionDoesNotReceiveLaterSurfaceFrames() {
        val holder = FaceGeometrySnapshotHolder()
        var notifications = 0
        val subscription = holder.addSnapshotListener { notifications++ }

        holder.publish(snapshot(1L), null)
        subscription.close()
        holder.publish(snapshot(2L), null)
        holder.clear()

        assertEquals(1, notifications)
        assertNull(holder.getLatest())
    }
}
