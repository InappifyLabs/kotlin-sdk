package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import org.junit.Assert.*
import org.junit.Test

class PendingPurchaseRecoveryPolicyTest {
    private fun pending(id: String, at: Long) = InappifyPurchaseAttempt(id, id, "offering", InappifyMarket.MYKET,
        InappifyPurchaseAttemptStatus.PENDING, null, null, at)

    @Test fun firstCheckIsDueAtTenMinutesAndDoesNotExpireEvidence() {
        val policy = PendingPurchaseRecoveryPolicy()
        val attempt = pending("product-x", 1_000)
        assertEquals(600_000L, policy.delayMillis(listOf(attempt), 1_000))
        assertEquals(1L, policy.delayMillis(listOf(attempt), 600_999))
        assertEquals(0L, policy.delayMillis(listOf(attempt), 601_000))
        assertEquals(0L, policy.delayMillis(listOf(attempt), 86_400_000))
        assertEquals(InappifyPurchaseAttemptStatus.PENDING, attempt.status)
    }

    @Test fun emptyRecoveryRepeatsAfterTenMinutesAndANewProductCannotPostponeTheOldOne() {
        val policy = PendingPurchaseRecoveryPolicy()
        val attempts = listOf(pending("product-x", 1_000), pending("product-y", 600_900))
        assertEquals(100L, policy.delayMillis(attempts, 600_900))
        policy.checked(601_000)
        assertEquals(600_000L, policy.delayMillis(attempts, 601_000))
        assertEquals(0L, policy.delayMillis(attempts, 1_201_000))
        policy.reset()
        assertEquals(0L, policy.delayMillis(attempts, 1_201_000))
    }

    @Test fun terminalResultsNeedNoTimerAndFutureTimestampsHaveABoundedWait() {
        val policy = PendingPurchaseRecoveryPolicy()
        val cancelled = InappifyPurchaseAttempt("cancelled", "product", "offering", InappifyMarket.MYKET,
            InappifyPurchaseAttemptStatus.FAILED, null, InappifyError(InappifyErrorCode.PURCHASE_CANCELLED, "cancelled"), 1_000)
        assertNull(policy.delayMillis(listOf(cancelled), 1_000))
        assertNull(policy.delayMillis(emptyList(), 1_000))
        policy.checked(9_999_999)
        assertEquals(600_000L, policy.delayMillis(listOf(pending("future", 9_999_999)), 1_000))
    }
}
