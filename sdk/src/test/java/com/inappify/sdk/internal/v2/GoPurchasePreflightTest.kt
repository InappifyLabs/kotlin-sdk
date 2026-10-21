package com.inappify.sdk.internal.v2

import android.app.Activity
import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoPurchasePreflightTest {
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pauseOnce() { if (entered.complete(Unit)) release.await() }
        suspend fun await() { withTimeout(5_000) { entered.await() } }
    }
    private class Storage : SessionStateStore {
        var value: PersistedSession? = null
        var unavailable = false
        val operations = CopyOnWriteArrayList<PendingStoreOperation>()
        override suspend fun load() = value
        override suspend fun save(session: PersistedSession): Boolean { value = session; return true }
        override suspend fun clear(): Boolean { value = null; return true }
        override suspend fun loadPendingStoreOperations() = operations.toList()
        override suspend fun loadPendingStoreRecoveryState() = if (unavailable) null else PendingStoreRecoveryState(operations.toList())
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            operations.removeAll { it.id == operation.id }; operations += operation; return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean =
            true.also { operations.removeAll { it.id == operationId } }
    }
    private class Fixture(val market: InappifyMarket? = InappifyMarket.BAZAAR) {
        val signing = SigningFixture()
        val storage = Storage()
        val sdkTransport = V2Transport()
        val commerce = V2Transport()
        val uiCalls = AtomicInteger()
        val queryCalls = AtomicInteger()
        @Volatile var customer = signing.subject
        @Volatile var queryGate: Gate? = null
        @Volatile var queueGate: Gate? = null
        @Volatile var failInfo = false
        @Volatile var failCatalog = false
        @Volatile var failAck = false
        @Volatile var processing = false
        @Volatile var deliveries = "[]"
        init {
            sdkTransport.handler = { request -> when (request.path) {
                "configure", "login" -> {
                    customer = jsonObject(request.jsonBody).string("appUserIdentifier")
                    sdkTransport.response(envelope())
                }
                "customerInfo" -> if (failInfo) sdkTransport.response(jsonObject("""{"status":false,"code":"INFO_UNAVAILABLE"}"""), 503)
                    else sdkTransport.response(envelope())
                "offerings" -> if (failCatalog) sdkTransport.response(jsonObject("""{"status":false,"code":"CATALOG_UNAVAILABLE"}"""), 503)
                    else sdkTransport.response(jsonObject("""{"status":true,"offerings":[
                        {"identifier":"original","packages":[{"identifier":"original-package","product":{"identifier":"product"}}]},
                        {"identifier":"new-offer","packages":[{"identifier":"new-package","product":{"identifier":"product"}},
                        {"identifier":"other-package","product":{"identifier":"other"}}]}]}"""))
                else -> error("Unexpected SDK request ${request.path}")
            } }
            commerce.handler = { request -> when {
                request.path == "purchase" -> commerce.response(jsonObject("""{"status":true,"data":{
                    "purchaseStatus":"NEEDTOPAY","url":"https://pay.inappify.com/checkout/fixture"}}"""))
                request.path == "consumable-deliveries/pending" -> {
                    queueGate?.pauseOnce()
                    commerce.response(jsonObject("""{"status":true,"data":{"deliveries":$deliveries}}"""))
                }
                request.path == "consumable-deliveries/7/delivered" -> if (failAck)
                    commerce.response(jsonObject("""{"status":false,"code":"ACK_UNAVAILABLE"}"""), 503)
                    else commerce.response(jsonObject("""{"status":true,"data":{"deliveryId":7,"productIdentifier":"product",
                        "source":"direct","status":"COMPLETED","alreadyDelivered":true}}"""))
                request.path == "store/purchases" -> commerce.response(jsonObject("""{"status":true,"data":{"purchase":{"status":"COMPLETED"}}}"""))
                request.path == "store/verifications/9/status" -> if (processing)
                    commerce.response(jsonObject("""{"status":true,"data":{"status":"PROCESSING","verificationRequestId":9,"retryAfter":120}}"""),
                        headers = mapOf("Retry-After" to "120"))
                    else commerce.response(jsonObject("""{"status":true,"data":{"status":"COMPLETED"}}"""))
                request.path == "store/deliveries/7/consume-result" ->
                    commerce.response(jsonObject("""{"status":true,"data":{"status":"COMPLETED","deliveryId":7}}"""))
                else -> error("Unexpected commerce request ${request.path}")
            } }
        }
        fun client() = GoV2Client(signing.config, GoApi(sdkTransport, { signing.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerce, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
                    val count = uiCalls.incrementAndGet()
                    return StoreBillingResult.Success(StorePurchase("order-$count", "token-$count", request.developerPayload!!,
                        "com.example.mobile", request.productIdentifier, signing.now, "original-json", "signature"))
                }
                override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult {
                    queryCalls.incrementAndGet(); queryGate?.pauseOnce()
                    return StorePurchaseQueryResult.Success(emptyList())
                }
                override fun close() = Unit
            } })
        private fun envelope(): JsonObject = signing.envelope(customer).apply {
            addProperty("storePlatform", when (market) { InappifyMarket.BAZAAR -> 10; InappifyMarket.MYKET -> 11; else -> 2 })
            addProperty("storeInfo", "fixture-rsa")
        }
        suspend fun configure(sdk: GoV2Client) {
            assertTrue(sdk.configure(InappifyOptions("fixture-key", signing.subject)) is InappifyResult.Success)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
        }
        fun request(product: String = "product", attempt: String? = null, offering: String = "new-offer",
            type: InappifyProductType = InappifyProductType.NON_CONSUMABLE) = InappifyPurchaseRequest(product, offering,
            packageIdentifier = if (offering == "original") "original-package" else if (product == "other") "other-package" else "new-package",
            idempotencyKey = attempt, productType = type)
        fun pending(type: PendingStoreProductType = PendingStoreProductType.NON_CONSUMABLE,
            phase: PendingStoreOperationPhase = PendingStoreOperationPhase.VERIFYING) = PendingStoreOperation(
            "paid-attempt", PendingStoreOperationType.PURCHASE, market!!.storeId, "old-session-token", digest(signing.subject),
            goStoreFingerprint(storage.value!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl), "com.example.mobile", 12,
            "product", "original", type, PendingStorePurchaseEvidence("paid-token", packageName = "com.example.mobile",
                developerPayload = """{"nativePackageIdentifier":"original-package"}"""), phase,
            verificationRequestId = if (phase == PendingStoreOperationPhase.VERIFYING) 9 else null,
            deliveryId = if (phase in setOf(PendingStoreOperationPhase.DELIVERY_REQUIRED, PendingStoreOperationPhase.CONSUME_RESULT_REPORTING)) 7 else null,
            deliveryAcknowledged = phase == PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
            consumeResult = if (phase == PendingStoreOperationPhase.CONSUME_RESULT_REPORTING) PendingStoreConsumeResult.SUCCEEDED else null,
            createdAtEpochMillis = signing.now)
        fun delivery(product: String = "product") = """[{"deliveryId":7,"productIdentifier":"$product","source":"direct","status":"DELIVERY_REQUIRED"}]"""
        suspend fun seedDirect(sdk: GoV2Client, product: String = "product") {
            deliveries = delivery(product)
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            deliveries = "[]"
        }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Test fun freshPurchaseKeepsItsRequestsAndDoesNotStartARecoveryPass() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val f = Fixture(market)
            f.client().use { sdk ->
                f.configure(sdk); f.sdkTransport.requests.clear()
                val result = sdk.purchase(Activity(), f.request()) as InappifyResult.Success
                assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
                assertEquals(1, f.uiCalls.get()); assertEquals(0, f.queryCalls.get())
                assertEquals(listOf("store/purchases"), f.commerce.requests.map { it.path })
                assertEquals(listOf("customerInfo"), f.sdkTransport.requests.map { it.path })
            }
        }
    }

    @Test fun sameProductResumesItsOriginalOfferingAndPackageWithoutActivityOrCatalog() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val f = Fixture(market)
            f.client().use { sdk ->
                f.configure(sdk); f.storage.operations += f.pending()
                assertTrue(sdk.enqueueAttributes(mapOf("audience" to "changed")) is InappifyResult.Success)
                assertNull(sdk.snapshot.offerings)
                val result = sdk.purchase(f.request()) as InappifyResult.Success
                assertEquals("paid-attempt", result.data.attemptId)
                assertEquals("original", result.data.offeringIdentifier)
                assertEquals("original-package", result.data.packageIdentifier)
                assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
                assertEquals(0, f.uiCalls.get()); assertTrue(f.storage.operations.isEmpty())
                assertEquals(listOf("store/verifications/9/status"), f.commerce.requests.map { it.path })
            }
        }
    }

    @Test fun deliveryRequiredIsReturnedWithoutGrantingOrConsumingTheProduct() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            val pending = f.pending(PendingStoreProductType.CONSUMABLE, PendingStoreOperationPhase.DELIVERY_REQUIRED)
            f.storage.operations += pending
            var grants = 0
            sdk.setConsumableDeliveryHandler { grants++; InappifyDeliveryResult.DELIVERED }
            val result = sdk.purchase(Activity(), f.request(type = InappifyProductType.CONSUMABLE)) as InappifyResult.Success
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED, result.data.storePurchaseStatus)
            assertEquals(7L, result.data.deliveryId); assertEquals(0, grants)
            assertEquals(listOf(pending), f.storage.operations.toList())
            assertEquals(0, f.uiCalls.get()); assertTrue(f.commerce.requests.isEmpty())
        }
    }

    @Test fun unresolvedVerificationRetainsItsRetryPolicyAndNeverOpensNewBilling() = runBlocking {
        val f = Fixture().apply { processing = true }
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending()
            val result = sdk.purchase(Activity(), f.request()) as InappifyResult.Failure
            assertTrue(result.error.isRetryable); assertEquals(0, f.uiCalls.get())
            assertEquals(1, f.commerce.requests.size)
            assertTrue(f.storage.operations.single().nextRetryAtEpochMillis!! >= f.signing.now + 120_000)
            assertTrue(sdk.purchase(Activity(), f.request()) is InappifyResult.Failure)
            assertEquals(1, f.commerce.requests.size); assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun foreignAndUnrelatedPendingOperationsDoNotBlockTheNewProduct() = runBlocking {
        for (variant in 0..5) {
            val f = Fixture()
            f.client().use { sdk ->
                f.configure(sdk)
                val base = f.pending()
                val foreign = when (variant) {
                    0 -> base.copy(customerIdentifierFingerprint = "another-customer")
                    1 -> base.copy(apiKeyFingerprint = "another-app-or-endpoint")
                    2 -> base.copy(store = InappifyMarket.MYKET.storeId)
                    3 -> base.copy(appId = 999)
                    4 -> base.copy(appIdentifier = "another.package")
                    else -> base.copy(productIdentifier = "other")
                }
                f.storage.operations += foreign
                assertTrue(sdk.purchase(Activity(), f.request()) is InappifyResult.Success)
                assertEquals(1, f.uiCalls.get()); assertEquals(listOf(foreign), f.storage.operations.toList())
                assertEquals(listOf("store/purchases"), f.commerce.requests.map { it.path })
            }
        }
    }

    @Test fun unreadablePendingStorageStillFailsBeforeBilling() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.unavailable = true
            val result = sdk.purchase(Activity(), f.request()) as InappifyResult.Failure
            assertEquals("STORE_RECOVERY_UNAVAILABLE", result.error.details["serverCode"])
            assertEquals(0, f.uiCalls.get()); assertTrue(f.commerce.requests.isEmpty())
        }
    }

    @Test fun purchaseJoinsRecoveryAndReturnsACompletedCheckpointEvenAfterItWasRemoved() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val f = Fixture(market)
            f.client().use { sdk ->
                f.configure(sdk)
                f.storage.operations += f.pending(PendingStoreProductType.CONSUMABLE, PendingStoreOperationPhase.CONSUME_RESULT_REPORTING)
                val gate = Gate().also { f.queryGate = it }
                val recovery = async { sdk.recover() }
                gate.await(); assertTrue(f.storage.operations.isEmpty())
                val purchase = async(start = CoroutineStart.UNDISPATCHED) {
                    sdk.purchase(Activity(), f.request(type = InappifyProductType.CONSUMABLE))
                }
                assertFalse(purchase.isCompleted); assertEquals(0, f.uiCalls.get())
                gate.release.complete(Unit)
                assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
                val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Success
                assertEquals("paid-attempt", result.data.attemptId)
                assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
                assertEquals(0, f.uiCalls.get())
                assertEquals(1, f.commerce.requests.count { it.path == "store/deliveries/7/consume-result" })
                assertFalse(f.commerce.requests.any { it.path == "store/purchases" })
            }
        }
    }

    @Test fun catalogAndCustomerInfoRecoveryFailuresDoNotBlockAnUnrelatedFreshPurchase() = runBlocking {
        for (failInfo in listOf(false, true)) {
            val f = Fixture()
            f.client().use { sdk ->
                f.configure(sdk); f.failInfo = failInfo; f.failCatalog = !failInfo
                val gate = Gate().also { f.queryGate = it }
                val recovery = async { sdk.recover() }
                gate.await()
                val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request(product = "other")) }
                assertEquals(0, f.uiCalls.get()); assertFalse(purchase.isCompleted)
                gate.release.complete(Unit)
                assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Failure)
                assertTrue(withTimeout(5_000) { purchase.await() } is InappifyResult.Success)
                assertEquals(1, f.uiCalls.get())
            }
        }
    }

    @Test fun matchingRecoveryFailureBlocksThatProductWithoutAnotherVerificationOrPayment() = runBlocking {
        val f = Fixture().apply { processing = true }
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending()
            val gate = Gate().also { f.queryGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request()) }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Failure)
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Failure
            assertTrue(result.error.isRetryable); assertEquals(0, f.uiCalls.get())
            assertEquals(1, f.commerce.requests.size)
        }
    }

    @Test fun completedPurchaseIsReturnedEvenIfTheRecoveryCatalogStepFails() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending(); f.failCatalog = true
            val gate = Gate().also { f.queryGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) {
                sdk.purchase(f.request(attempt = "paid-attempt", offering = "original"))
            }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Failure)
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Success
            assertEquals("paid-attempt", result.data.attemptId)
            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(0, f.uiCalls.get()); assertTrue(f.storage.operations.isEmpty())
        }
    }

    @Test fun joiningRecoveryPreservesExplicitAttemptConflictValidation() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending()
            val gate = Gate().also { f.queryGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request(attempt = "paid-attempt")) }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Failure
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", result.error.details["serverCode"])
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun identityChangeWhileWaitingNeverChargesTheNewCustomer() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            val gate = Gate().also { f.queryGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request()) }
            val login = async(start = CoroutineStart.UNDISPATCHED) { sdk.logIn("another-customer") }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { login.await() } is InappifyResult.Success)
            withTimeout(5_000) { recovery.await() }
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Failure
            assertEquals("PURCHASE_CONTEXT_CHANGED", result.error.details["serverCode"])
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun cancelingAPurchaseWaiterDoesNotCancelTheClientsRecovery() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            val gate = Gate().also { f.queryGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request()) }
            purchase.cancelAndJoin(); gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun closingTheClientCancelsRecoveryAndTheWaitingPurchaseBeforeBilling() = runBlocking {
        val f = Fixture()
        val sdk = f.client()
        try {
            f.configure(sdk)
            val gate = Gate().also { f.queryGate = it }
            supervisorScope {
                val recovery = async { sdk.recover() }
                gate.await()
                val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(Activity(), f.request()) }
                sdk.close()
                assertTrue(runCatching { withTimeout(5_000) { recovery.await() } }.exceptionOrNull() is CancellationException)
                assertTrue(runCatching { withTimeout(5_000) { purchase.await() } }.exceptionOrNull() is CancellationException)
            }
            assertEquals(0, f.uiCalls.get()); assertFalse(f.commerce.requests.any { it.path == "store/purchases" })
        } finally { sdk.close() }
    }

    @Test fun directPendingDeliveryIsReturnedWithoutAnotherCheckoutOrHostGrant() = runBlocking {
        val f = Fixture(market = null)
        f.client().use { sdk ->
            f.configure(sdk); f.seedDirect(sdk)
            val queueReads = f.commerce.requests.count { it.path == "consumable-deliveries/pending" }
            val keys = jsonObject(f.storage.value!!.customerInfoJson!!).keySet().toSet()
            val result = sdk.purchase(f.request()) as InappifyResult.Success
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED, result.data.storePurchaseStatus)
            assertEquals(7L, result.data.deliveryId); assertNull(result.data.url)
            assertEquals(queueReads, f.commerce.requests.count { it.path == "consumable-deliveries/pending" })
            assertFalse(f.commerce.requests.any { it.path == "purchase" || it.path.endsWith("/delivered") })
            val document = jsonObject(f.storage.value!!.customerInfoJson!!)
            assertEquals(keys, document.keySet())
            assertFalse(document.getAsJsonObject("directDeliveries").getAsJsonObject("records").getAsJsonObject("7").flag("hostDelivered"))
        }
    }

    @Test fun directPreviouslyGrantedDeliveryResumesOnlyItsAcknowledgement() = runBlocking {
        val f = Fixture(market = null)
        f.client().use { sdk ->
            f.configure(sdk); f.seedDirect(sdk); f.failAck = true
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            f.failAck = false
            val result = sdk.purchase(f.request()) as InappifyResult.Success
            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(7L, result.data.deliveryId)
            assertFalse(f.commerce.requests.any { it.path == "purchase" })
        }
    }

    @Test fun disablingNetworkingStillBlocksPurchaseWhenADirectDeliveryIsPending() = runBlocking {
        val f = Fixture(market = null)
        f.client().use { sdk ->
            f.configure(sdk); f.seedDirect(sdk); sdk.setNetworkEnabled(false)
            val calls = f.commerce.requests.size
            val result = sdk.purchase(f.request()) as InappifyResult.Failure
            assertEquals("V2_DISABLED", result.error.details["serverCode"])
            assertEquals(calls, f.commerce.requests.size)
        }
    }

    @Test fun directCompletedAndOtherProductDeliveriesDoNotBlockFreshCheckout() = runBlocking {
        for (completed in listOf(false, true)) {
            val f = Fixture(market = null)
            f.client().use { sdk ->
                f.configure(sdk); f.seedDirect(sdk, if (completed) "product" else "other")
                if (completed) assertTrue(sdk.confirmDelivery(7) is InappifyResult.Success)
                val result = sdk.purchase(f.request()) as InappifyResult.Success
                assertEquals(InappifyPurchaseStatus.NEEDTOPAY, result.data.purchaseStatus)
                assertEquals(1, f.commerce.requests.count { it.path == "purchase" })
            }
        }
    }

    @Test fun directPurchaseJoinsDeliveryRecoveryAndResultsAreNotCachedForLaterPurchases() = runBlocking {
        val f = Fixture(market = null)
        f.client().use { sdk ->
            f.configure(sdk); f.deliveries = f.delivery()
            var grants = 0
            sdk.setConsumableDeliveryHandler { grants++; InappifyDeliveryResult.DELIVERED }
            val gate = Gate().also { f.queueGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(f.request()) }
            assertFalse(purchase.isCompleted)
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Success
            assertEquals(InappifyStorePurchaseStatus.COMPLETED, result.data.storePurchaseStatus)
            assertEquals(7L, result.data.deliveryId); assertEquals(1, grants)
            assertFalse(f.commerce.requests.any { it.path == "purchase" })
            val fresh = sdk.purchase(f.request()) as InappifyResult.Success
            assertEquals(InappifyPurchaseStatus.NEEDTOPAY, fresh.data.purchaseStatus)
            assertEquals(1, f.commerce.requests.count { it.path == "purchase" })
        }
    }

    @Test fun directAcknowledgementFailureIsReturnedToTheWaitingPurchaseWithoutAnotherCheckout() = runBlocking {
        val f = Fixture(market = null)
        f.client().use { sdk ->
            f.configure(sdk); f.deliveries = f.delivery(); f.failAck = true
            sdk.setConsumableDeliveryHandler { InappifyDeliveryResult.DELIVERED }
            val gate = Gate().also { f.queueGate = it }
            val recovery = async { sdk.recover() }
            gate.await()
            val purchase = async(start = CoroutineStart.UNDISPATCHED) { sdk.purchase(f.request()) }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Failure)
            val result = withTimeout(5_000) { purchase.await() } as InappifyResult.Failure
            assertEquals("ACK_UNAVAILABLE", result.error.details["serverCode"])
            assertEquals(1, f.commerce.requests.count { it.path.endsWith("/delivered") })
            assertFalse(f.commerce.requests.any { it.path == "purchase" })
        }
    }
}
