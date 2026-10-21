package com.inappify.sdk.internal.v2

import org.junit.Assert.*
import org.junit.Test

class RecoveryRefreshPolicyTest {
    @Test fun missingRecoveryRunsAndFreshRecoveryWaitsExactlyFiveMinutes() {
        var time = 100L
        val policy = RecoveryRefreshPolicy { time }
        assertTrue(policy.shouldRecover(0, pending = false, startup = false))
        policy.completed(0, success = true)
        time += 299_999
        assertFalse(policy.shouldRecover(0, pending = false, startup = false))
        time++
        assertTrue(policy.shouldRecover(0, pending = false, startup = false))
    }

    @Test fun pendingWorkOverridesFreshnessButNeverTheThirtySecondFailureBackoff() {
        var time = 0L
        val policy = RecoveryRefreshPolicy { time }
        policy.completed(0, success = true)
        assertTrue(policy.shouldRecover(0, pending = true, startup = false))
        policy.completed(0, success = false)
        time = 29_999
        assertFalse(policy.shouldRecover(0, pending = true, startup = false))
        assertFalse(policy.shouldRecover(0, pending = true, startup = true))
        time++
        assertTrue(policy.shouldRecover(0, pending = true, startup = false))
    }

    @Test fun failedStaleWorkCanRetryAfterThirtySecondsWithoutPendingPurchases() {
        var time = 0L
        val policy = RecoveryRefreshPolicy { time }
        policy.completed(0, success = false)
        time = 30_000
        assertTrue(policy.shouldRecover(0, pending = false, startup = false))
    }

    @Test fun startupKeepsItsForcedPassAndSuccessClearsFailureBackoff() {
        val policy = RecoveryRefreshPolicy { 0L }
        policy.completed(0, success = false)
        policy.completed(0, success = true)
        assertFalse(policy.shouldRecover(0, pending = false, startup = false))
        assertTrue(policy.shouldRecover(0, pending = false, startup = true))
    }

    @Test fun previousCustomerCannotPostponeTheNewCustomersRecovery() {
        val policy = RecoveryRefreshPolicy { 0L }
        policy.reset(1)
        policy.completed(1, success = false)
        policy.reset(2)
        policy.completed(1, success = true)
        policy.completed(1, success = false)
        assertTrue(policy.shouldRecover(2, pending = false, startup = false))
        assertFalse(policy.shouldRecover(1, pending = true, startup = true))
    }

    @Test fun paymentReturnCanWaitForTheRemainingFailureBackoffWithoutResettingIt() {
        var time = 0L
        val policy = RecoveryRefreshPolicy { time }
        policy.completed(0, success = false)
        assertEquals(30_000L, policy.retryDelayMillis(0))
        time = 29_999
        assertEquals(1L, policy.retryDelayMillis(0))
        time++
        assertEquals(0L, policy.retryDelayMillis(0))
        assertEquals(0L, policy.retryDelayMillis(1))
    }
}
