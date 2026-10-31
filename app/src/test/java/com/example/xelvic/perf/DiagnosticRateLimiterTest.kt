package com.example.xelvic.perf

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticRateLimiterTest {
    @Test fun repeatedFramesAreLimitedAndClockRollbackDoesNotDisableDiagnostics() {
        val limiter = DiagnosticRateLimiter(1_000)
        assertTrue(limiter.shouldReport(10))
        repeat(999) { assertFalse(limiter.shouldReport(11L + it)) }
        assertTrue(limiter.shouldReport(1_010))
        assertTrue(limiter.shouldReport(5))
        assertFalse(limiter.shouldReport(6))
    }

    @Test fun onlyOneThreadCanLogInTheSameSamplingInterval() {
        val limiter = DiagnosticRateLimiter(1_000)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map {
                executor.submit<Boolean> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    limiter.shouldReport(1_000)
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
