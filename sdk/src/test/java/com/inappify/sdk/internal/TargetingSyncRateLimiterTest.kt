package com.inappify.sdk.internal

import org.junit.Assert.*
import org.junit.Test

class TargetingSyncRateLimiterTest {
    @Test fun usesRollingWindowRatherThanResettingAllCallsAtMinuteBoundary() {
        var now = 0L
        val limiter = TargetingSyncRateLimiter({ now }, {})
        repeat(5) { now = it * 10_000L; assertNull(limiter.acquire()) }
        now = 59_999
        assertEquals(1L, limiter.acquire())
        now = 60_000
        assertNull(limiter.acquire())
        assertEquals(10_000L, limiter.acquire())
        now = 70_000
        assertNull(limiter.acquire())
        assertEquals(10_000L, limiter.acquire())
    }

    @Test fun backwardsClockDoesNotResetQuotaAndLoggerFailureCannotBypassIt() {
        var now = 100_000L
        val limiter = TargetingSyncRateLimiter({ now }, { error("Broken logger") })
        repeat(5) { assertNull(limiter.acquire()) }
        now = 0
        assertEquals(60_000L, limiter.acquire())
        now = 160_000
        assertNull(limiter.acquire())
    }

    @Test fun clientsHaveIndependentAllowances() {
        val first = TargetingSyncRateLimiter({ 0 }, {})
        val second = TargetingSyncRateLimiter({ 0 }, {})
        repeat(5) { assertNull(first.acquire()) }
        assertEquals(60_000L, first.acquire())
        repeat(5) { assertNull(second.acquire()) }
    }
}
