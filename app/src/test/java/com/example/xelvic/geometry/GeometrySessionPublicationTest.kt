package com.example.xelvic.geometry

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GeometrySessionPublicationTest {
    private fun snapshot(timestamp: Long) = FaceGeometrySnapshot(
        timestamp, 100, 100, 0, false, listOf(FaceVertex(0.5f, 0.5f, 0f))
    )

    @Test fun completionBeforeInvalidationIsClearedAndItsFrameRemainsRetired() {
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        assertTrue(holder.publishIfCurrent(token, snapshot(1), null, 1))
        val oldFrame = holder.getLatest()!!
        holder.beginSession()
        assertNull(holder.getLatest())
        assertFalse(oldFrame.isCurrent)
        assertFalse(holder.publishIfCurrent(token, snapshot(2), null, 2))
    }

    private fun retireWhileCallbackIsReady(reenter: Boolean) {
        val holder = FaceGeometrySnapshotHolder()
        val oldToken = holder.currentSession()
        val callbackReady = CountDownLatch(1)
        val publishAllowed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                assertTrue(oldToken.isCurrent) // exactly the formerly unsafe check/publication gap
                callbackReady.countDown()
                assertTrue(publishAllowed.await(5, TimeUnit.SECONDS))
                holder.publishIfCurrent(oldToken, snapshot(10), null, 10)
            }
            assertTrue(callbackReady.await(5, TimeUnit.SECONDS))
            val replacement = holder.beginSession()
            if (reenter) assertTrue(holder.publishIfCurrent(replacement, snapshot(20), null, 20))
            publishAllowed.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            if (reenter) assertEquals(20L, holder.getLatest()!!.snapshot.timestampMs) else assertNull(holder.getLatest())
        } finally {
            publishAllowed.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun invalidationBeforePublicationRejectsRetiredResult() = retireWhileCallbackIsReady(false)
    @Test fun oldCameraCallbackCannotOverwriteReplacementCamera() = retireWhileCallbackIsReady(true)
    @Test fun detachAndReentryRejectOldPreviewCallback() = retireWhileCallbackIsReady(true)
    @Test fun clearingGeometryWhileInferenceFinishesCannotRepublishIt() = retireWhileCallbackIsReady(false)

    @Test fun completionAndInvalidationAreOrderedEvenWhilePublicationListenerIsBlocked() {
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        val published = CountDownLatch(1)
        val finishListener = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        holder.addSnapshotListener {
            if (Thread.currentThread().name == "publication-before-retirement") {
                published.countDown()
                assertTrue(finishListener.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val completion = executor.submit<Boolean> {
                Thread.currentThread().name = "publication-before-retirement"
                holder.publishIfCurrent(token, snapshot(1), null, 1)
            }
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertEquals(1L, holder.getLatest()!!.snapshot.timestampMs)
            holder.clear() // Must not wait for an external publication listener.
            finishListener.countDown()
            assertTrue(completion.get(5, TimeUnit.SECONDS))
            assertNull(holder.getLatest())
            assertFalse(token.isCurrent)
        } finally {
            finishListener.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun delayedInvalidationCannotRetireAReplacementSession() {
        val holder = FaceGeometrySnapshotHolder()
        val retired = holder.currentSession()
        val replacement = holder.beginSession()
        assertTrue(holder.publishIfCurrent(replacement, snapshot(20), null, 20))
        assertNull(holder.beginSessionIfCurrent(retired))
        assertSame(replacement, holder.currentSession())
        assertEquals(20L, holder.getLatest()!!.snapshot.timestampMs)
    }

    @Test fun concurrentRetirementsNeverResurrectATokenOrReverseItsGeneration() {
        val holder = FaceGeometrySnapshotHolder()
        val original = holder.currentSession()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tokens = (1..4).map {
                executor.submit<List<GeometrySessionToken>> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    List(50) { holder.beginSession() }
                }
            }
            start.countDown()
            val allTokens = tokens.flatMap { it.get(5, TimeUnit.SECONDS) } + original
            val current = holder.currentSession()
            assertEquals(201L, current.generation)
            assertEquals(201, allTokens.map { it.generation }.toSet().size)
            allTokens.filter { it !== current }.forEach { retired ->
                assertFalse(retired.isCurrent)
                assertFalse(holder.publishIfCurrent(retired, snapshot(300), null, 300))
            }
            assertNull(holder.getLatest())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun snapshotAndExactTransformMustBelongToTheSameHolderSessionAndTimestamp() {
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        val foreign = FaceGeometrySnapshotHolder().currentSession()
        val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val foreignTransform = FaceGeometryDisplayTransform(identity, foreign)
        assertFalse(holder.publishIfCurrent(token, snapshot(1), foreignTransform, 1))
        assertFalse(holder.publishIfCurrent(foreign, snapshot(1), null, 1))
        assertFalse(holder.publishIfCurrent(token, snapshot(1), null, 2))
        val transform = FaceGeometryDisplayTransform(identity, token)
        val snapshot = snapshot(3)
        assertTrue(holder.publishIfCurrent(token, snapshot, transform, 3))
        assertSame(snapshot, holder.getLatest()!!.snapshot)
        assertSame(transform, holder.getLatest()!!.imageToPreview)
        assertSame(token, holder.getLatest()!!.session)
    }

    @Test fun failedCanvasListenerDoesNotPreventGeometryRetirementOrOtherSubscribers() {
        val holder = FaceGeometrySnapshotHolder()
        var notifications = 0
        holder.onSnapshotUpdated = { throw IllegalStateException("detached Canvas") }
        holder.addSnapshotListener { notifications++ }
        val retired = holder.currentSession()
        assertTrue(holder.publishIfCurrent(retired, snapshot(1), null, 1))
        holder.clear()
        assertEquals(2, notifications)
        assertNull(holder.getLatest())
        assertFalse(retired.isCurrent)
    }

    @Test fun olderNoFaceResultCannotEraseNewerGeometry() {
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        assertTrue(holder.publishIfCurrent(token, snapshot(20), null, 20))
        assertFalse(holder.publishIfCurrent(token, null, null, 10))
        assertEquals(20L, holder.getLatest()!!.snapshot.timestampMs)
    }

    @Test fun listenerCanInvalidateWithoutDeadlockOrRepublishingRetiredGeometry() {
        val holder = FaceGeometrySnapshotHolder()
        val token = holder.currentSession()
        var invalidate = true
        holder.addSnapshotListener {
            if (invalidate) { invalidate = false; holder.beginSession() }
        }
        assertTrue(holder.publishIfCurrent(token, snapshot(1), null, 1))
        assertNull(holder.getLatest())
        assertFalse(token.isCurrent)
    }
}
