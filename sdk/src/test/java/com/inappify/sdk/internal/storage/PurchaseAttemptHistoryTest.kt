package com.inappify.sdk.internal.storage

import com.inappify.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PurchaseAttemptHistoryTest {
    private class Storage : PurchaseAttemptStorage {
        var raw = "{\"version\":1,\"attempts\":[]}"
        override suspend fun readPurchaseAttemptHistory() = raw
        override suspend fun mutatePurchaseAttemptHistory(mutation: (String) -> String): Boolean { raw = mutation(raw); return true }
    }
    private val binding = "a".repeat(64)
    private fun attempt(id: String, done: Boolean = true): InappifyPurchaseAttempt {
        val purchase = if (done) InappifyPurchase.storeResult(id, "p", "o", "pkg", InappifyStorePurchaseStatus.COMPLETED, null, null, false) else null
        return InappifyPurchaseAttempt(id, "p", "o", InappifyMarket.BAZAAR,
            if (done) InappifyPurchaseAttemptStatus.SUCCEEDED else InappifyPurchaseAttemptStatus.PENDING,
            purchase, null, 123L)
    }
    @Test fun roundTripPreservesResultsScopeAndDoesNotRegressTerminalOutcomes() = runBlocking {
        val storage = Storage()
        val history = PurchaseAttemptHistory(storage)
        history.put(binding, attempt("one")); history.put(binding, attempt("one", done = false))
        history.put("b".repeat(64), attempt("two"))
        val result = PurchaseAttemptHistory(storage).list(binding).single()
        assertTrue(result.isFinal); assertEquals("one", result.purchase!!.attemptId)
        assertEquals("pkg", result.purchase!!.packageIdentifier)
        assertFalse(result.toString().contains("one"))
    }
    @Test fun terminalRetentionIsBoundedWithoutDroppingPendingAttempts() = runBlocking {
        val history = PurchaseAttemptHistory(Storage())
        history.put(binding, attempt("pending", false))
        repeat(257) { history.put(binding, attempt("done-$it")) }
        val rows = history.list(binding)
        assertEquals(257, rows.size)
        assertTrue(rows.any { it.attemptId == "pending" && !it.isFinal })
        assertFalse(rows.any { it.attemptId == "done-0" })
    }
    @Test fun unavailableOrMalformedHistoryIsNotAnEmptySuccess() = runBlocking {
        val storage = Storage().apply { raw = "{\"version\":2,\"attempts\":[]}" }
        try { PurchaseAttemptHistory(storage).list(binding); fail("Must reject unknown storage version") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun directRecoveryFlagSurvivesOrdinaryUpdatesAndIgnoresLegacySyntheticDeliveries() = runBlocking {
        val storage = Storage()
        val history = PurchaseAttemptHistory(storage)
        history.put(binding, attempt("tracked", false), directTracking = true)
        history.put(binding, attempt("legacy", false))
        history.put(binding, attempt("tracked", false))
        assertEquals(listOf("tracked"), PurchaseAttemptHistory(storage).pendingDirect(binding).map { it.attemptId })
        history.put(binding, attempt("tracked"))
        assertTrue(history.pendingDirect(binding).isEmpty())
    }
    @Test fun stableSchemaPreservesLargeIdsAndBothStoreAndDirectResults() = runBlocking {
        val storage = Storage()
        // A saved record is independent of the names of the Kotlin model fields after shrinking.
        storage.raw = """{"version":1,"attempts":[{"binding":"$binding","attemptId":"saved","productIdentifier":"p",
            "offeringIdentifier":"o","market":"MYKET","status":"SUCCEEDED","updatedAtEpochMillis":123,
            "purchase":{"attemptId":"saved","productIdentifier":"p","offeringIdentifier":"o","market":"MYKET",
            "purchaseStatus":"DONE","packageIdentifier":"pkg","deliveryId":9223372036854775000,
            "verificationRequestId":456,"alreadyProcessed":true,"storePurchaseStatus":"COMPLETED"}}]}"""
        val history = PurchaseAttemptHistory(storage)
        val stored = history.list(binding).single().purchase!!
        assertEquals(9223372036854775000L, stored.deliveryId)
        assertEquals(456L, stored.verificationRequestId)
        assertTrue(stored.alreadyProcessed)
        assertEquals(InappifyMarket.MYKET, stored.market)
        val direct = InappifyPurchase("direct", "p", "o", InappifyMarket.NONE,
            InappifyPurchaseStatus.NEEDTOPAY, "pkg", "https://example.test/payment", "checkout", "pending", "redirect")
        history.put(binding, InappifyPurchaseAttempt("direct", "p", "o", InappifyMarket.NONE,
            InappifyPurchaseAttemptStatus.PENDING, direct, null, 456L))
        history.put(binding, InappifyPurchaseAttempt("cancelled", "p", "o", InappifyMarket.BAZAAR,
            InappifyPurchaseAttemptStatus.FAILED, null, InappifyError(InappifyErrorCode.PURCHASE_CANCELLED,
                "Cancelled", false, mapOf("httpStatus" to 422, "outcomeMayHaveCommitted" to false)), 789L))
        val reread = PurchaseAttemptHistory(storage).list(binding)
        assertEquals(direct, reread.first { it.attemptId == "direct" }.purchase)
        val failure = reread.first { it.attemptId == "cancelled" }.error!!
        assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, failure.code)
        assertEquals(422L, failure.details["httpStatus"])
        assertEquals(false, failure.details["outcomeMayHaveCommitted"])
        assertEquals(stored, reread.first { it.attemptId == "saved" }.purchase)
    }
}
