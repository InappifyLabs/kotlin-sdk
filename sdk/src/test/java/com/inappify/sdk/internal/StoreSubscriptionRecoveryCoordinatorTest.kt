package com.inappify.sdk.internal

import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StoreSubscriptionRecoveryCoordinatorTest {
    @Test
    fun completedRecoveryUsesDedicatedEndpointAndOriginalEvidence() = runBlocking {
        val fixture = Fixture()
        val original = operation()
        val outcome = fixture.coordinator().submit(original, context())

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(0, fixture.store.operations.size)
        val sent = fixture.service.recoveries.single()
        assertEquals(original.productIdentifier, sent.productIdentifier)
        assertEquals(original.evidence.purchaseToken, sent.purchase.token)
        assertEquals(original.evidence.developerPayload, sent.purchase.developerPayload)
        assertEquals(original.evidence.originalJson, sent.purchase.originalJson)
        assertEquals(original.evidence.signature, sent.purchase.signature)
    }

    @Test
    fun processingResumesPollingAfterRestartWithCurrentMatchingSession() = runBlocking {
        val fixture = Fixture()
        fixture.service.next = response(StorePurchaseStatus.PROCESSING)
        val pending = fixture.coordinator().submit(operation(), context(), maxPolls = 0)
        assertTrue(pending is StoreV2Outcome.Deferred)
        assertEquals(PendingStoreOperationPhase.VERIFYING, pending.operation.phase)
        assertEquals(17L, pending.operation.verificationRequestId)

        fixture.service.next = response(StorePurchaseStatus.ALREADY_PROCESSED)
        val outcome = fixture.coordinator().resume(
            fixture.store.operations.values.single(), context(token = "renewed-session"), maxPolls = 1,
        )
        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(1, fixture.service.recoveries.size)
        assertEquals(listOf(17L), fixture.service.polls)
        assertTrue(fixture.store.operations.isEmpty())
    }

    @Test
    fun disabledRecoveryRetainsRegistrationAndPollingWithoutNetwork() = runBlocking {
        for (saved in listOf(operation(), operation().copy(
            phase = PendingStoreOperationPhase.VERIFYING, verificationRequestId = 17L,
        ))) {
            val fixture = Fixture()
            fixture.store.operations[saved.id] = saved
            val result = fixture.coordinator().resume(saved, context(enabled = false))
            assertTrue(result is StoreV2Outcome.Deferred)
            assertEquals(InappifyErrorCode.INVALID_CONFIGURATION, (result as StoreV2Outcome.Deferred).error!!.code)
            assertEquals(saved, fixture.store.operations[saved.id])
            assertTrue(fixture.service.recoveries.isEmpty())
            assertTrue(fixture.service.polls.isEmpty())
        }
    }

    @Test
    fun queuedRecoveryCannotMoveToAnotherCustomer() = runBlocking {
        val fixture = Fixture()
        val saved = operation()
        fixture.store.operations[saved.id] = saved
        val result = fixture.coordinator().resume(saved, context(customer = "another-customer"))
        assertTrue(result is StoreV2Outcome.Failure)
        assertEquals(saved, fixture.store.operations[saved.id])
        assertTrue(fixture.service.recoveries.isEmpty())
    }

    @Test
    fun missingWrongScopeAndConsumableStatesNeverFinalizeSubscription() = runBlocking {
        for (bad in listOf(
            response(source = null),
            response(source = "myket"),
            response(product = null),
            response(product = "other-product"),
            response(StorePurchaseStatus.DELIVERY_REQUIRED, deliveryId = 99L),
            response(StorePurchaseStatus.CONSUME_REQUIRED, deliveryId = 99L),
            response(deliveryId = 99L),
        )) {
            val fixture = Fixture()
            fixture.service.next = bad
            val result = fixture.coordinator().submit(operation(), context())
            assertFalse(result is StoreV2Outcome.Terminal)
            assertFalse(result is StoreV2Outcome.PendingDelivery)
            assertEquals(1, fixture.store.operations.size)
        }
    }

    @Test
    fun transientErrorsRetainEvidenceAndResumeAfterBackoff() = runBlocking {
        for (failure in listOf(
            response(http = 429), response(http = 503),
            StoreServiceResult.Failure(ServiceFailureKind.NETWORK),
            StoreServiceResult.Failure(ServiceFailureKind.TIMEOUT),
        )) {
            val fixture = Fixture()
            fixture.service.next = failure
            val pending = fixture.coordinator().submit(operation(), context())
            assertTrue(pending is StoreV2Outcome.Deferred)
            assertEquals(operation().evidence, pending.operation.evidence)
            assertTrue(pending.operation.nextRetryAtEpochMillis!! > fixture.now)
            fixture.now = pending.operation.nextRetryAtEpochMillis!!
            fixture.service.next = response()

            assertTrue(fixture.coordinator().resume(
                fixture.store.operations.values.single(), context(),
            ) is StoreV2Outcome.Terminal)
            assertEquals(2, fixture.service.recoveries.size)
        }
    }

    @Test
    fun onlyPermanentBusinessRejectionsRequestQuarantine() = runBlocking {
        for (code in listOf("SUBSCRIPTION_NOT_LINKED", "SUBSCRIPTION_OWNER_MISMATCH",
            "SUBSCRIPTION_EVIDENCE_INVALID", "SUBSCRIPTION_BINDING_INVALID")) {
            val fixture = Fixture()
            fixture.service.next = response(http = 422, error = code)
            val result = fixture.coordinator().submit(operation(), context()) as StoreV2Outcome.Rejected
            assertTrue(result.requiresRejectionTombstone)
            // The client must atomically write a tombstone before removing the evidence.
            assertEquals(1, fixture.store.operations.size)
        }
        val fixture = Fixture()
        fixture.service.next = response(http = 401, error = "SUBSCRIPTION_OWNER_MISMATCH")
        val unauthorized = fixture.coordinator().submit(operation(), context()) as StoreV2Outcome.Rejected
        assertFalse(unauthorized.requiresRejectionTombstone)
        assertTrue(unauthorized.error.isRetryable)
        assertEquals(1, fixture.store.operations.size)
    }

    @Test
    fun failedDurableWritePreventsSubmission() = runBlocking {
        val fixture = Fixture()
        fixture.store.writeSucceeds = false
        assertTrue(fixture.coordinator().submit(operation(), context()) is StoreV2Outcome.Failure)
        assertTrue(fixture.service.recoveries.isEmpty())
    }

    private class Fixture {
        val store = MemoryStore()
        val service = RecoveryBackend()
        var now = 1_000_000L
        fun coordinator() = StoreV2Coordinator(
            service, store,
            StoreBillingAdapterFactory { _, _ -> error("Recovery must never consume a subscription") },
            currentTimeMillis = { now }, sleep = { now += it }, jitterMillis = { 0L },
        )
    }

    private class MemoryStore : SessionStateStore {
        val operations = linkedMapOf<String, PendingStoreOperation>()
        var writeSucceeds = true
        override suspend fun load(): PersistedSession? = null
        override suspend fun save(session: PersistedSession) = true
        override suspend fun clear() = true
        override suspend fun loadPendingStoreOperations() = operations.values.toList()
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            if (!writeSucceeds) return false
            operations[operation.id] = operation
            return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean {
            operations.remove(operationId)
            return true
        }
    }

    private class RecoveryBackend : StoreV2Backend {
        var next: StoreServiceResult = response()
        val recoveries = mutableListOf<StoreSubscriptionRecoveryApiRequest>()
        val polls = mutableListOf<Long>()
        override suspend fun recoverStoreSubscription(request: StoreSubscriptionRecoveryApiRequest): StoreServiceResult {
            recoveries += request
            return next
        }
        override suspend fun getStoreVerificationStatus(request: StoreVerificationStatusApiRequest): StoreServiceResult {
            polls += request.verificationRequestId
            return next
        }
        override suspend fun submitStorePurchase(request: StorePurchaseApiRequest): StoreServiceResult =
            error("Unbound recovery cannot use the purchase endpoint")
        override suspend fun markStoreDeliveryDelivered(request: StoreDeliveryApiRequest): StoreServiceResult =
            error("Subscription recovery cannot deliver a consumable")
        override suspend fun reportStoreConsumeResult(request: StoreConsumeResultApiRequest): StoreServiceResult =
            error("Subscription recovery cannot consume")
    }

    private fun operation() = PendingStoreOperation(
        id = "recovery-1", operation = PendingStoreOperationType.RECOVER_SUBSCRIPTION,
        store = "bazar", customerToken = "previous-session", customerIdentifierFingerprint = "customer",
        apiKeyFingerprint = "app-key", appIdentifier = "com.example.app", appId = 5L,
        productIdentifier = "subscription_sku", productType = PendingStoreProductType.SUBSCRIPTION,
        evidence = PendingStorePurchaseEvidence("purchase-token", "order", "com.example.app", "",
            "original-signed-json", "original-signature", 900_000L),
        createdAtEpochMillis = 1_000_000L,
    )

    private fun context(enabled: Boolean = true, customer: String = "customer", token: String = "session") =
        StoreV2Context("key", token, "app-key", customer, "com.example.app", 5L, "IR", "1.0.0", null,
            "market-key", InappifyMarket.BAZAAR, subscriptionRecoveryEnabled = enabled)

    private companion object {
        fun response(
            status: StorePurchaseStatus = StorePurchaseStatus.COMPLETED,
            source: String? = "bazar", product: String? = "subscription_sku",
            deliveryId: Long? = null, http: Int = 200, error: String? = null,
        ): StoreServiceResult = StoreServiceResult.Response(
            http, StoreBackendResponse(http == 200,
                StorePurchaseState(status, null, null, deliveryId, 17L, 0L, null, null, false,
                    source = source, productIdentifier = product),
                false, null, error, null), null,
        )
    }
}
