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
    private class Storage : SessionStateStore, PurchaseAttemptStorage {
        var value: PersistedSession? = null
        var history = "{\"version\":1,\"attempts\":[]}"
        var failHistory = false
        var failOperationSave = false
        var beforeRemove: (() -> Unit)? = null
        override suspend fun readPurchaseAttemptHistory(): String? = if (failHistory) null else history
        override suspend fun mutatePurchaseAttemptHistory(mutation: (String) -> String): Boolean {
            if (failHistory) return false
            history = mutation(history); return true
        }
        var unavailable = false
        val operations = CopyOnWriteArrayList<PendingStoreOperation>()
        override suspend fun load() = value
        override suspend fun save(session: PersistedSession): Boolean { value = session; return true }
        override suspend fun clear(): Boolean { value = null; return true }
        override suspend fun loadPendingStoreOperations() = operations.toList()
        override suspend fun loadPendingStoreRecoveryState() = if (unavailable) null else PendingStoreRecoveryState(operations.toList())
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            if (failOperationSave) return false
            operations.removeAll { it.id == operation.id }; operations += operation; return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean =
            true.also { beforeRemove?.invoke(); operations.removeAll { it.id == operationId } }
    }
    private class Fixture(val market: InappifyMarket? = InappifyMarket.BAZAAR) {
        val signing = SigningFixture()
        var clock = signing.now
        val storage = Storage()
        val sdkTransport = V2Transport()
        val commerce = V2Transport()
        val uiCalls = AtomicInteger()
        val queryCalls = AtomicInteger()
        var lastPurchased: StorePurchase? = null
        var ownedPurchases = emptyList<StorePurchase>()
        @Volatile var customer = signing.subject
        @Volatile var queryGate: Gate? = null
        @Volatile var queueGate: Gate? = null
        @Volatile var failInfo = false
        @Volatile var failCatalog = false
        @Volatile var failAck = false
        @Volatile var processing = false
        @Volatile var deliveries = "[]"
        @Volatile var attemptStates = "[]"
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
                request.path == "purchase-attempts/status" -> commerce.response(jsonObject("""{"status":true,"data":{"attempts":$attemptStates}}"""))
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
        fun client() = GoV2Client(signing.config, GoApi(sdkTransport, { clock }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { clock }, Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerce, { clock }, {}),
            billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
                    val count = uiCalls.incrementAndGet()
                    val receipt = StorePurchase("order-$count", "token-$count", request.developerPayload!!,
                        "com.example.mobile", request.productIdentifier, signing.now, "original-json", "signature")
                    lastPurchased = receipt
                    return StoreBillingResult.Success(receipt)
                }
                override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult {
                    queryCalls.incrementAndGet(); queryGate?.pauseOnce()
                    return StorePurchaseQueryResult.Success(if (productType == StoreProductType.IN_APP) ownedPurchases else emptyList())
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

    @Test fun purchaseResultAndEventSurviveClientRestartWithoutRebillingOrCacheReads() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val f = Fixture(market)
            val updates = mutableListOf<InappifyEvent>()
            f.client().use { sdk ->
                f.configure(sdk)
                sdk.addEventListener { if (it.type == InappifyEventType.PURCHASE_UPDATED) updates += it }
                sdk.addEventListener { if (it.type == InappifyEventType.PURCHASE_UPDATED) error("Host listener failed") }
                val result = sdk.purchase(Activity(), f.request(attempt = "same-attempt")) as InappifyResult.Success
                assertEquals("same-attempt", result.data.attemptId)
                assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, updates.last().purchaseAttempt!!.status)
                assertEquals("same-attempt", updates.last().requestId)
                assertEquals(result.data, updates.last().purchaseAttempt!!.purchase)
                assertTrue(f.storage.operations.isEmpty())
                assertFalse(f.storage.history.contains("token-1"))
                assertFalse(f.storage.history.contains("original-json"))
            }
            f.client().use { restarted ->
                f.configure(restarted)
                val cached = restarted.snapshot
                val persisted = f.storage.value
                f.sdkTransport.requests.clear(); f.commerce.requests.clear()
                val result = restarted.getPurchaseAttempt("same-attempt") as InappifyResult.Success
                assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, result.data!!.status)
                assertEquals(market, result.data!!.market)
                assertEquals("same-attempt", result.data!!.purchase!!.attemptId)
                assertEquals(1, (restarted.getPurchaseAttempts() as InappifyResult.Success).data.size)
                assertSame(cached, restarted.snapshot); assertSame(persisted, f.storage.value)
                assertTrue(f.sdkTransport.requests.isEmpty()); assertTrue(f.commerce.requests.isEmpty())
                assertEquals(1, f.uiCalls.get())
            }
        }
    }

    @Test fun recoveryPersistsOutcomeBeforeCheckpointRemovalAndHistoryOutlivesAnEmptySync() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending()
            val updates = mutableListOf<InappifyPurchaseAttempt>()
            sdk.addEventListener { event -> event.purchaseAttempt?.let(updates::add) }
            f.storage.beforeRemove = { assertTrue(f.storage.history.contains("SUCCEEDED")) }
            assertTrue(sdk.recover() is InappifyResult.Success)
            assertTrue(f.storage.operations.isEmpty())
            assertEquals("paid-attempt", updates.last().attemptId)
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, updates.last().status)
            assertTrue((sdk.syncStorePurchases() as InappifyResult.Success).data.isEmpty())
        }
        f.client().use { restarted ->
            f.configure(restarted)
            val result = restarted.getPurchaseAttempt("paid-attempt") as InappifyResult.Success
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, result.data!!.status)
            assertEquals("original", result.data!!.offeringIdentifier)
            assertEquals("original-package", result.data!!.purchase!!.packageIdentifier)
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun uncertainVerificationRemainsPendingAcrossRestartWithoutInventingFailure() = runBlocking {
        val f = Fixture().apply { processing = true }
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending()
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Failure)
            val value = (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!
            assertFalse(value.isFinal); assertEquals(InappifyPurchaseAttemptStatus.PENDING, value.status)
            assertNotNull(value.error)
        }
        f.client().use { sdk ->
            f.configure(sdk)
            assertEquals(InappifyPurchaseAttemptStatus.PENDING,
                (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!.status)
            f.processing = false
            f.clock += 200_000 // Respect the checkpoint's server-directed retry delay.
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED,
                (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!.status)
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun failedHistoryWriteRetainsPaidCheckpointForRecovery() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.operations += f.pending(); f.storage.failHistory = true
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Failure)
            assertEquals(1, f.storage.operations.size)
            assertTrue(sdk.getPurchaseAttempt("paid-attempt") is InappifyResult.Failure)
            f.storage.failHistory = false
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertTrue(f.storage.operations.isEmpty())
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED,
                (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!.status)
            assertEquals(0, f.uiCalls.get())
        }
    }

    @Test fun authRejectionAndUnrelatedResponsesNeverBecomeDefinitivePurchaseFailures() = runBlocking {
        for (response in listOf(
            401 to """{"status":false,"code":"UNAUTHORIZED"}""",
            200 to """{"status":true,"data":{"status":"COMPLETED","productIdentifier":"unrelated"}}""",
        )) {
            val f = Fixture()
            f.client().use { sdk ->
                f.configure(sdk); f.storage.operations += f.pending()
                f.commerce.handler = { f.commerce.response(jsonObject(response.second), response.first) }
                assertTrue(sdk.syncStorePurchases() is InappifyResult.Failure)
                val tracked = (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!
                assertEquals(InappifyPurchaseAttemptStatus.PENDING, tracked.status)
                assertNull(tracked.purchase)
                assertEquals(1, f.storage.operations.size)
            }
            f.client().use { sdk ->
                f.configure(sdk)
                assertFalse((sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!.isFinal)
                f.commerce.handler = { f.commerce.response(jsonObject("""{"status":true,"data":{"status":"COMPLETED"}}""")) }
                assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
                assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED,
                    (sdk.getPurchaseAttempt("paid-attempt") as InappifyResult.Success).data!!.status)
                assertEquals(0, f.uiCalls.get())
            }
        }
    }

    @Test fun storeReceiptRecoversOriginalAttemptAfterReceiptCheckpointWriteWasInterrupted() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk); f.storage.failOperationSave = true
            assertTrue(sdk.purchase(Activity(), f.request(attempt = "interrupted-attempt")) is InappifyResult.Failure)
            assertTrue(f.storage.operations.isEmpty())
            assertFalse((sdk.getPurchaseAttempt("interrupted-attempt") as InappifyResult.Success).data!!.isFinal)
        }
        f.storage.failOperationSave = false
        f.ownedPurchases = listOf(f.lastPurchased!!)
        f.client().use { sdk ->
            f.configure(sdk)
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            val tracked = (sdk.getPurchaseAttempt("interrupted-attempt") as InappifyResult.Success).data!!
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, tracked.status)
            assertEquals("interrupted-attempt", tracked.purchase!!.attemptId)
            assertEquals("new-offer", tracked.purchase!!.offeringIdentifier)
            assertEquals("new-package", tracked.purchase!!.packageIdentifier)
            assertEquals(1, f.uiCalls.get())
        }
    }

    @Test fun lookupIsScopedToAcceptedCustomerAndUnknownIsNotFailure() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            assertNull((sdk.getPurchaseAttempt("missing") as InappifyResult.Success).data)
            assertTrue(sdk.purchase(Activity(), f.request(attempt = "customer-attempt")) is InappifyResult.Success)
            assertTrue(sdk.logIn("different-customer") is InappifyResult.Success)
            assertNull((sdk.getPurchaseAttempt("customer-attempt") as InappifyResult.Success).data)
            assertTrue((sdk.getPurchaseAttempts() as InappifyResult.Success).data.isEmpty())
            assertTrue(sdk.logIn(f.signing.subject) is InappifyResult.Success)
            assertNotNull((sdk.getPurchaseAttempt("customer-attempt") as InappifyResult.Success).data)
            assertTrue(sdk.getPurchaseAttempt("unsafe attempt") is InappifyResult.Failure)
        }
    }

    @Test fun directPaymentAttemptIsSentAndResolvedAfterRestartWithoutAnotherCheckout() = runBlocking {
        for (type in listOf(InappifyProductType.SUBSCRIPTION, InappifyProductType.NON_CONSUMABLE)) {
            val f = Fixture(null)
            f.client().use { sdk ->
                f.configure(sdk)
                val result = sdk.purchase(f.request(attempt = "direct-attempt", type = type)) as InappifyResult.Success
                assertEquals("direct-attempt", result.data.attemptId)
                assertEquals("direct-attempt", jsonObject(f.commerce.requests.single { it.path == "purchase" }.jsonBody).string("attemptId"))
            }
            f.attemptStates = """[{"attemptId":"direct-attempt","productIdentifier":"product","offeringIdentifier":"new-offer",
                "status":"SUCCEEDED","purchaseStatus":"DONE","url":null,"deliveryId":null,"deliveryStatus":null}]"""
            f.client().use { sdk ->
                f.configure(sdk)
                val updates = mutableListOf<InappifyPurchaseAttempt>()
                sdk.addEventListener { event -> event.purchaseAttempt?.let(updates::add) }
                val synced = sdk.syncStorePurchases() as InappifyResult.Success
                assertTrue(synced.data.isEmpty()) // Existing sync return contract remains consumable-only.
                val outcome = (sdk.getPurchaseAttempt("direct-attempt") as InappifyResult.Success).data!!
                assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, outcome.status)
                assertEquals("new-package", outcome.purchase!!.packageIdentifier)
                assertEquals("direct-attempt", updates.last().attemptId)
                assertEquals(1, f.commerce.requests.count { it.path == "purchase" })
                assertEquals(0, f.uiCalls.get())
            }
        }
    }

    @Test fun directStatusDoesNotAcceptMissingOrUnrelatedResultsAsPaymentFailure() = runBlocking {
        val f = Fixture(null)
        f.client().use { sdk ->
            f.configure(sdk)
            assertTrue(sdk.purchase(f.request(attempt = "direct-attempt")) is InappifyResult.Success)
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertFalse((sdk.getPurchaseAttempt("direct-attempt") as InappifyResult.Success).data!!.isFinal)
            f.attemptStates = """[{"attemptId":"direct-attempt","productIdentifier":"unrelated","offeringIdentifier":"new-offer",
                "status":"SUCCEEDED","purchaseStatus":"DONE"}]"""
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Failure)
            assertFalse((sdk.getPurchaseAttempt("direct-attempt") as InappifyResult.Success).data!!.isFinal)
            assertEquals(1, f.commerce.requests.count { it.path == "purchase" })
        }
    }

    @Test fun directDeliveryRecordsOriginalAttemptWhileKeepingLegacySyncResultIdentity() = runBlocking {
        val f = Fixture(null)
        f.client().use { sdk ->
            f.configure(sdk)
            assertTrue(sdk.purchase(f.request(attempt = "direct-consumable", type = InappifyProductType.CONSUMABLE)) is InappifyResult.Success)
            f.deliveries = """[{"deliveryId":7,"productIdentifier":"product","source":"direct","status":"DELIVERY_REQUIRED",
                "attemptId":"direct-consumable","offeringIdentifier":"new-offer"}]"""
            sdk.syncStorePurchases()
            val pending = (sdk.getPurchaseAttempt("direct-consumable") as InappifyResult.Success).data!!
            assertFalse(pending.isFinal); assertEquals(7L, pending.purchase!!.deliveryId)
            f.commerce.handler = { request -> when (request.path) {
                "consumable-deliveries/7/delivered" -> f.commerce.response(jsonObject("""{"status":true,"data":{
                    "deliveryId":7,"productIdentifier":"product","source":"direct","status":"COMPLETED","alreadyDelivered":true,
                    "attemptId":"direct-consumable","offeringIdentifier":"new-offer"}}"""))
                else -> error("Unexpected request")
            } }
            val acknowledged = sdk.confirmDeliveryV2(7) as InappifyResult.Success
            assertEquals("direct-delivery-7", acknowledged.data.attemptId)
            val complete = (sdk.getPurchaseAttempt("direct-consumable") as InappifyResult.Success).data!!
            assertEquals(InappifyPurchaseAttemptStatus.SUCCEEDED, complete.status)
            assertEquals("direct-consumable", complete.purchase!!.attemptId)
            assertEquals("new-package", complete.purchase!!.packageIdentifier)
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
