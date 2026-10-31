package com.example.xelvic.perf

import java.util.concurrent.atomic.AtomicLong

/** Non-blocking throttle shared by debug sampling/logging; never used to select a render path. */
internal class DiagnosticRateLimiter(private val intervalMs: Long) {
    init { require(intervalMs > 0L) }
    private val lastReportMs = AtomicLong(Long.MIN_VALUE)

    fun shouldReport(nowMs: Long): Boolean {
        while (true) {
            val previous = lastReportMs.get()
            if (previous != Long.MIN_VALUE && nowMs >= previous && nowMs - previous < intervalMs) return false
            if (lastReportMs.compareAndSet(previous, nowMs)) return true
        }
    }
}
