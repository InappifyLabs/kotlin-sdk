package com.inappify.sdk.internal.v2

import android.app.Activity
import com.google.gson.JsonParser
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class GoBillingLifecycleTest(private val nativeMarket: InappifyMarket) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun markets(): List<Array<InappifyMarket>> = listOf(arrayOf(InappifyMarket.BAZAAR), arrayOf(InappifyMarket.MYKET))
    }
    private val fixture = SigningFixture()
    private val sdkTransport = V2Transport()
    private val commerceTransport = V2Transport()
    private val storage = BillingStore()
    private var adapterCloses = 0
    private val nativeRequests = mutableListOf<StorePurchaseRequest>()
    private val started = CompletableDeferred<Unit>()
    private var purchase: suspend () -> StoreBillingResult = { started.complete(Unit); awaitCancellation() }
    private var query: suspend () -> StorePurchaseQueryResult = { started.complete(Unit); awaitCancellation() }
    private var consume: suspend () -> StoreConsumeResult = { started.complete(Unit); awaitCancellation() }

    private fun client(): GoV2Client {
        sdkTransport.handler = { request -> sdkTransport.response(
            if (request.path == "offerings") jsonObject("""{"status":true,"offerings":[{"identifier":"default","packages":[{"identifier":"package","product":{"identifier":"product"}}]}]}""")
            else fixture.envelope().apply { addProperty("storePlatform", if (nativeMarket == InappifyMarket.MYKET) 11 else 10); addProperty("storeInfo", "fixture-public-rsa") }) }
        commerceTransport.handler = { error("Closing native billing must not send a receipt/consume report") }
        return GoV2Client(fixture.config, GoApi(sdkTransport, { fixture.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { fixture.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerceTransport, { fixture.now }, {}),
            billingFactory = StoreBillingAdapterFactory { market, _ ->
                assertEquals(nativeMarket, market)
                object : StoreBillingAdapter {
                private var closed = false
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
                    nativeRequests += request
                    return this@GoBillingLifecycleTest.purchase.invoke()
                }
                override suspend fun queryPurchases(productType: StoreProductType) = this@GoBillingLifecycleTest.query.invoke()
                override suspend fun consume(purchase: StorePurchase) = this@GoBillingLifecycleTest.consume.invoke()
                override fun close() { if (!closed) { closed = true; adapterCloses++ } }
            } })
    }

    private suspend fun configure(sdk: GoV2Client) {
        assertTrue(sdk.configure(InappifyOptions("fixture-public-key", fixture.subject)) is InappifyResult.Success)
        assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
    }

    @Test fun purchasePayloadBindsTheSelectedCustomerAppProductAndPackage() = runBlocking {
        purchase = { StoreBillingResult.Cancelled }
        client().use { sdk ->
            configure(sdk)
            sdk.purchase(Activity(), InappifyPurchaseRequest("product", "default",
                idempotencyKey = "binding-attempt", productType = InappifyProductType.CONSUMABLE))
            val payload = JsonParser.parseString(nativeRequests.single().developerPayload).asJsonObject
            val customer = MessageDigest.getInstance("SHA-256").digest(fixture.subject.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(goPurchaseBinding(12, "com.example.mobile", customer, "product", "default", "package"),
                payload.get("purchaseBinding").asString)
            assertEquals("package", payload.get("nativePackageIdentifier").asString)
            assertTrue(payload.has("recoveryBinding"))
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }

    @Test fun closeCancelsAnOwnedPurchaseScopeWithoutCancellingTheHostParent() = runBlocking {
        val sdk = client()
        configure(sdk)
        val operation = async { sdk.purchase(Activity(), InappifyPurchaseRequest("product", "default",
            idempotencyKey = "fixture-attempt", productType = InappifyProductType.CONSUMABLE)) }
        started.await()
        sdk.close()
        withTimeout(2000) { operation.join() }
        assertTrue(operation.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(1, adapterCloses)
        assertTrue(commerceTransport.requests.isEmpty())
    }

    @Test fun closeCancelsAnOwnedQueryAndClosesItsAdapter() = runBlocking {
        val sdk = client()
        configure(sdk)
        val operation = async { sdk.syncPurchases() }
        started.await()
        sdk.close()
        withTimeout(2000) { operation.join() }
        assertTrue(operation.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(1, adapterCloses)
        assertTrue(commerceTransport.requests.isEmpty())
    }

    @Test fun callerCancellationClosesOnlyItsAdapterAndLeavesTheClientUsable() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val operation = async { sdk.syncPurchases() }
            started.await()
            operation.cancelAndJoin()
            assertEquals(1, adapterCloses)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
        }
    }

    @Test fun closingConsumeRetainsCheckpointInsteadOfPersistingPermanentConsumeFailure() = runBlocking {
        val sdk = client()
        configure(sdk)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(fixture.subject.toByteArray())
            .joinToString("") { "%02x".format(it) }
        storage.operations += PendingStoreOperation("fixture-attempt", PendingStoreOperationType.PURCHASE,
            nativeMarket.storeId, "fixture-session", fingerprint,
            goStoreFingerprint(storage.value!!.apiKeyFingerprint!!, fixture.config.commerceApiBaseUrl),
            "com.example.mobile", 12, "product", "default", PendingStoreProductType.CONSUMABLE,
            PendingStorePurchaseEvidence("fixture-store-token"), PendingStoreOperationPhase.CONSUME_REQUIRED,
            deliveryId = 7, deliveryAcknowledged = true, createdAtEpochMillis = fixture.now)
        val operation = async { sdk.syncPurchases() }
        started.await()
        sdk.close()
        withTimeout(2000) { operation.join() }
        assertTrue(operation.isCancelled)
        assertEquals(1, adapterCloses)
        assertEquals(PendingStoreOperationPhase.CONSUME_REQUIRED, storage.operations.single().phase)
        assertNull(storage.operations.single().consumeResult)
        assertTrue(commerceTransport.requests.isEmpty())
    }

    @Test fun uncertainBillingFailurePreservesAttemptAndDoesNotSuggestANewPurchase() = runBlocking {
        purchase = { StoreBillingResult.Failure(StoreBillingError(
            StoreBillingErrorCode.OPERATION_TIMEOUT, "private-store-value", isRetryable = true)) }
        client().use { sdk ->
            configure(sdk)
            val result = sdk.purchase(Activity(), InappifyPurchaseRequest("product", "default",
                idempotencyKey = "fixture-attempt", productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.TIMEOUT, result.error.code)
            assertEquals("fixture-attempt", result.error.details["attemptId"])
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("OPERATION_TIMEOUT", result.error.details["storeCode"])
            assertFalse(result.error.isRetryable)
            assertFalse(result.error.toString().contains("private-store-value"))
            assertEquals(1, adapterCloses)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }

    @Test fun allAmbiguousStoreFailuresCarryAnUncertainPaymentHint() {
        for (code in listOf(StoreBillingErrorCode.PURCHASE_FAILED, StoreBillingErrorCode.CONNECTION_LOST,
            StoreBillingErrorCode.OPERATION_TIMEOUT, StoreBillingErrorCode.UI_HOST_DESTROYED,
            StoreBillingErrorCode.ADAPTER_CLOSED, StoreBillingErrorCode.INVALID_PURCHASE_STATE,
            StoreBillingErrorCode.INVALID_PURCHASE_DATA, StoreBillingErrorCode.PRODUCT_MISMATCH,
            StoreBillingErrorCode.PACKAGE_MISMATCH)) {
            val error = StoreBillingError(code, "private", isRetryable = true).toGoPurchaseError("attempt")
            assertEquals(code.name, true, error.details["outcomeMayHaveCommitted"])
            assertFalse(code.name, error.isRetryable)
            assertEquals(code.name, error.details["storeCode"])
        }
        val connection = StoreBillingError(StoreBillingErrorCode.CONNECTION_FAILED, "safe", true)
            .toGoPurchaseError("attempt")
        assertEquals(false, connection.details["outcomeMayHaveCommitted"])
        assertTrue(connection.isRetryable)
    }

    @Test fun mismatchedSuccessfulReceiptRetainsTheAttemptAsAnUncertainPayment() = runBlocking {
        purchase = { StoreBillingResult.Success(StorePurchase("fixture-order", "fixture-token", "{}",
            "another.package", "product", fixture.now, "fixture-json", "fixture-signature")) }
        client().use { sdk ->
            configure(sdk)
            val result = sdk.purchase(Activity(), InappifyPurchaseRequest("product", "default",
                idempotencyKey = "fixture-attempt", productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("fixture-attempt", result.error.details["attemptId"])
            assertEquals("STORE_RECEIPT_SCOPE_MISMATCH", result.error.details["serverCode"])
            assertFalse(result.error.isRetryable)
            assertTrue(commerceTransport.requests.isEmpty())
        }
    }

    private class BillingStore : SessionStateStore {
        var value: PersistedSession? = null
        val operations = mutableListOf<PendingStoreOperation>()
        override suspend fun load() = value
        override suspend fun save(session: PersistedSession): Boolean { value = session; return true }
        override suspend fun clear(): Boolean { value = null; return true }
        override suspend fun loadPendingStoreOperations() = operations.toList()
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            operations.removeAll { it.id == operation.id }; operations += operation; return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean =
            true.also { operations.removeAll { it.id == operationId } }
    }
}
