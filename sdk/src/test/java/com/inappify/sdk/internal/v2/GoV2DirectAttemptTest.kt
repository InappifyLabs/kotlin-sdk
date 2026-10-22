package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import org.junit.Assert.*
import org.junit.Test

class GoV2DirectAttemptTest {
    private fun row(status: String, purchase: String? = null, delivery: String? = null) = JsonObject().apply {
        addProperty("attemptId", "attempt"); addProperty("productIdentifier", "p"); addProperty("offeringIdentifier", "o")
        addProperty("status", status); purchase?.let { addProperty("purchaseStatus", it) }
        delivery?.let { addProperty("deliveryId", 7); addProperty("deliveryStatus", it) }
    }

    @Test fun onlyAuthoritativePaidAndDeliveredStatesAreFinalSuccess() {
        val paid = GoV2DirectAttempt(row("SUCCEEDED", "DONE")).result("pkg", 123)
        assertTrue(paid.isFinal)
        assertEquals("attempt", paid.purchase!!.attemptId)
        val delivery = GoV2DirectAttempt(row("PENDING", "DONE", "DELIVERY_REQUIRED")).result("pkg", 123)
        assertFalse(delivery.isFinal)
        assertEquals(7L, delivery.purchase!!.deliveryId)
    }

    @Test fun pendingAndPrePaymentRejectionDoNotInventAPaidPurchase() {
        assertFalse(GoV2DirectAttempt(row("PENDING")).result(null, 123).isFinal)
        val rejected = GoV2DirectAttempt(row("FAILED")).result(null, 123)
        assertNull(rejected.purchase); assertNotNull(rejected.error)
        for (row in listOf(row("SUCCEEDED"), row("FAILED", "DONE"), row("SUCCEEDED", "DONE", "DELIVERY_REQUIRED"))) {
            try { GoV2DirectAttempt(row); fail("Contradictory result must be rejected") } catch (_: V2Failure) { }
        }
    }
}
