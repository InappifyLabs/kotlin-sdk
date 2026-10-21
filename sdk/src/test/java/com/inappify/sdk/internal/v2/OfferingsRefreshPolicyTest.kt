package com.inappify.sdk.internal.v2

import org.junit.Assert.*
import org.junit.Test

class OfferingsRefreshPolicyTest {
    @Test fun freshnessExpiresAtFiveMinutesUsingTheMonotonicClock() {
        var time = 0L
        val policy = OfferingsRefreshPolicy { time }
        assertTrue(policy.shouldRefresh())
        policy.fetched()
        repeat(50) { assertFalse(policy.shouldRefresh()) }
        time = 299_999
        assertFalse(policy.shouldRefresh())
        time = 300_000
        assertTrue(policy.shouldRefresh())
        policy.fetched()
        assertFalse(policy.shouldRefresh())
    }

    @Test fun failedPrefetchBacksOffEvenWithoutAnExistingCache() {
        var time = 0L
        val policy = OfferingsRefreshPolicy { time }
        policy.failed()
        assertFalse(policy.shouldRefresh())
        time = 29_999
        assertFalse(policy.shouldRefresh())
        time = 30_000
        assertTrue(policy.shouldRefresh())
        policy.fetched()
        assertFalse(policy.shouldRefresh())
    }

    @Test fun identityAndContextInvalidationDiscardPreviousFreshnessAndBackoff() {
        val policy = OfferingsRefreshPolicy { 0L }
        policy.fetched()
        policy.failed()
        policy.invalidate()
        assertTrue(policy.shouldRefresh())
    }
}
