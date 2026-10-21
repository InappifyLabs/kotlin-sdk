package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoAutomaticRecoverySchedulingTest {
    private class Links : PaymentLinkMonitor {
        var closed = false
        private var listener: ((String) -> Unit)? = null
        override fun setListener(listener: (String) -> Unit) { this.listener = listener }
        fun emit(url: String) { listener?.invoke(url) }
        override fun close() { closed = true; listener = null }
    }
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pauseOnce() { if (entered.complete(Unit)) release.await() }
    }
    private class Visibility : AppForegroundMonitor {
        override var isForeground = false
        var closed = false
        private var listener: ((Boolean) -> Unit)? = null
        override fun setListener(listener: (Boolean) -> Unit) { this.listener = listener }
        fun foreground() {
            isForeground = false; listener?.invoke(false)
            isForeground = true; listener?.invoke(true)
        }
        override fun close() { closed = true; listener = null }
    }
    private class Fixture(val market: InappifyMarket? = null, online: Boolean = true) {
        val signing = SigningFixture()
        val saved = MemoryV2Store()
        val operations = CopyOnWriteArrayList<PendingStoreOperation>()
        val storage = object : SessionStateStore by saved {
            override suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> = operations.toList()
            override suspend fun loadPendingStoreRecoveryState() = PendingStoreRecoveryState(operations.toList())
        }
        val transport = V2Transport()
        val visibility = Visibility()
        val connectivity = NetworkConnectionState(online)
        val links = Links()
        val time = AtomicLong(0)
        @Volatile var customer = signing.subject
        @Volatile var gate: Gate? = null
        @Volatile var skipNextInfoGate = false
        @Volatile var failInfo = false
        @Volatile var pendingDelivery = false
        @Volatile var completedDelivery = false
        val infoCalls = AtomicLong(0)
        val queueCalls = AtomicLong(0)
        init {
            transport.handler = { request -> when (request.path) {
                "configure", "login" -> {
                    customer = jsonObject(request.jsonBody).string("appUserIdentifier")
                    transport.response(envelope())
                }
                "customerInfo" -> {
                    infoCalls.incrementAndGet()
                    if (skipNextInfoGate) skipNextInfoGate = false else gate?.pauseOnce()
                    if (failInfo) transport.response(jsonObject("""{"status":false,"code":"FIXTURE_UNAVAILABLE"}"""), 503)
                    else transport.response(envelope())
                }
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[]}"""))
                "consumable-deliveries/pending" -> {
                    queueCalls.incrementAndGet()
                    val status = if (completedDelivery) "COMPLETED" else "DELIVERY_REQUIRED"
                    val deliveries = if (pendingDelivery)
                        """[{"source":"direct","deliveryId":10,"productIdentifier":"coins","status":"$status"}]""" else "[]"
                    transport.response(jsonObject("""{"status":true,"data":{"deliveries":$deliveries}}"""))
                }
                else -> error("Unexpected recovery path ${request.path}")
            } }
        }
        private fun envelope(): JsonObject = signing.envelope(customer).apply {
            addProperty("storePlatform", when (market) { InappifyMarket.BAZAAR -> 10; InappifyMarket.MYKET -> 11; else -> 2 })
            addProperty("storeInfo", "fixture-rsa")
        }
        fun client(automatic: Boolean = true) = GoV2Client(signing.config,
            GoApi(transport, { signing.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = automatic,
            commerceApi = GoApi(transport, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult =
                    error("Automatic recovery must not open purchase UI")
                override suspend fun queryPurchases(productType: StoreProductType) = StorePurchaseQueryResult.Success(emptyList())
                override fun close() = Unit
            } }, foregroundMonitor = visibility, connectivityMonitor = connectivity,
            recoveryRefreshPolicy = RecoveryRefreshPolicy(time::get), paymentLinkMonitor = links)
        fun options() = InappifyOptions("public-key", signing.subject)
        suspend fun startup(sdk: GoV2Client) = coroutineScope {
            val startup = Gate().also { gate = it }
            assertTrue(sdk.configure(options()) is InappifyResult.Success)
            finish(sdk, startup)
        }
        suspend fun finish(sdk: GoV2Client, paused: Gate, success: Boolean = true) = coroutineScope {
            withTimeout(5_000) { paused.entered.await() }
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            paused.release.complete(Unit)
            assertEquals(success, withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
        }
        fun pending(): PendingStoreOperation = PendingStoreOperation("unfinished", PendingStoreOperationType.PURCHASE,
            market!!.storeId, "old-session", digest(signing.subject),
            goStoreFingerprint(saved.value!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl),
            "com.example.mobile", 12, "product", "offering", PendingStoreProductType.NON_CONSUMABLE,
            PendingStorePurchaseEvidence("receipt", null, "com.example.mobile", null, "{}", "signature", signing.now),
            createdAtEpochMillis = signing.now)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun foregroundAndReconnectRespectTheFiveMinuteBoundary() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            f.time.set(299_999)
            f.visibility.foreground()
            f.connectivity.update(false); f.connectivity.update(true)
            delay(150)
            assertEquals(1, f.queueCalls.get().toInt())
            val next = Gate().also { f.gate = it }
            f.time.incrementAndGet()
            f.visibility.foreground()
            f.finish(sdk, next)
            assertEquals(2, f.queueCalls.get().toInt())
            val reconnect = Gate().also { f.gate = it }
            f.time.addAndGet(300_000)
            f.connectivity.update(false); f.connectivity.update(true)
            f.finish(sdk, reconnect)
            assertEquals(3, f.queueCalls.get().toInt())
        }
    }

    @Test fun unfinishedDirectDeliveryRetriesBeforeFiveMinutesAndRepeatedEventsShareOnePass() = runBlocking {
        val f = Fixture().apply { pendingDelivery = true }
        f.client().use { sdk ->
            f.startup(sdk)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            val storedKeys = jsonObject(f.saved.value!!.customerInfoJson!!).keySet().toSet()
            val next = Gate().also { f.gate = it }
            f.visibility.foreground()
            withTimeout(5_000) { next.entered.await() }
            repeat(20) { f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true) }
            f.finish(sdk, next)
            delay(150)
            assertEquals(2, f.queueCalls.get().toInt())
            assertEquals(storedKeys, jsonObject(f.saved.value!!.customerInfoJson!!).keySet())
            assertTrue(sdk.snapshot.isConfigured)
        }
    }

    @Test fun failedRecoveryBacksOffThirtySecondsAndRetriesAtTheBoundary() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            f.time.set(300_000); f.failInfo = true
            val failure = Gate().also { f.gate = it }
            f.visibility.foreground()
            f.finish(sdk, failure, success = false)
            val calls = f.infoCalls.get()
            f.time.addAndGet(29_999)
            repeat(10) { f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true) }
            delay(150)
            assertEquals(calls, f.infoCalls.get())
            f.time.incrementAndGet(); f.failInfo = false
            val retry = Gate().also { f.gate = it }
            f.connectivity.update(false); f.connectivity.update(true)
            f.finish(sdk, retry)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun explicitRecoveryStillBypassesAutomaticFailureBackoff() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            f.failInfo = true
            assertTrue(sdk.recover() is InappifyResult.Failure)
            f.failInfo = false
            assertTrue(sdk.recover() is InappifyResult.Success)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun onlyNativeCheckpointsBoundToThisCustomerAppEndpointAndStoreTriggerEarlyRecovery() = runBlocking {
        val f = Fixture(InappifyMarket.BAZAAR)
        f.client().use { sdk ->
            f.startup(sdk)
            val pending = f.pending()
            for (foreign in listOf(pending.copy(store = InappifyMarket.MYKET.storeId),
                pending.copy(customerIdentifierFingerprint = "another-customer"),
                pending.copy(apiKeyFingerprint = "another-endpoint"), pending.copy(appId = 999),
                pending.copy(appIdentifier = "another.package"))) {
                f.operations.clear(); f.operations += foreign
                f.visibility.foreground()
                f.connectivity.update(false); f.connectivity.update(true)
                delay(100)
                assertEquals(2, f.infoCalls.get().toInt())
            }
            f.operations.clear(); f.operations += pending
            val retry = Gate().also { f.gate = it }
            f.visibility.foreground()
            withTimeout(5_000) { retry.entered.await() }
            assertEquals(3, f.infoCalls.get().toInt())
            // Close while paused before reconciliation, leaving the paid journal untouched.
            sdk.close()
            assertEquals(listOf(pending), f.operations.toList())
        }
    }

    @Test fun reconnectAfterConfigureWhileOfflineRecoversTheAcceptedSession() = runBlocking {
        val f = Fixture(online = false)
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            delay(100)
            assertEquals(0, f.infoCalls.get().toInt())
            val online = Gate().also { f.gate = it }
            f.connectivity.update(true)
            f.finish(sdk, online)
            assertEquals(1, f.queueCalls.get().toInt())
        }
    }

    @Test fun completedDirectDeliveryRecordsDoNotTriggerEarlyRecovery() = runBlocking {
        val f = Fixture().apply { pendingDelivery = true; completedDelivery = true }
        f.client().use { sdk ->
            f.startup(sdk)
            f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true)
            delay(150)
            assertEquals(1, f.queueCalls.get().toInt())
            assertTrue(jsonObject(f.saved.value!!.customerInfoJson!!).has("directDeliveries"))
        }
    }

    @Test fun pendingDirectWorkAndRepeatedConfigureCannotBypassTheFailureBackoff() = runBlocking {
        val f = Fixture().apply { pendingDelivery = true }
        f.client().use { sdk ->
            f.startup(sdk)
            f.failInfo = true
            val failure = Gate().also { f.gate = it }
            f.visibility.foreground()
            f.finish(sdk, failure, success = false)
            val calls = f.infoCalls.get()
            f.time.set(29_999)
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true)
            delay(150)
            assertEquals(calls, f.infoCalls.get())
            f.time.incrementAndGet(); f.failInfo = false
            val retry = Gate().also { f.gate = it }
            f.visibility.foreground()
            f.finish(sdk, retry)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun manuallyDisabledNetworkingSuppressesEventsAndReenablingUsesTheSamePolicy() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            f.time.set(300_000); sdk.setNetworkEnabled(false)
            f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true)
            delay(150)
            assertEquals(1, f.queueCalls.get().toInt())
            val enabled = Gate().also { f.gate = it }
            sdk.setNetworkEnabled(true)
            f.finish(sdk, enabled)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun acceptedLoginDoesNotInheritThePreviousCustomersFailureBackoff() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            f.failInfo = true
            assertTrue(sdk.recover() is InappifyResult.Failure)
            f.failInfo = false
            val login = Gate().also { f.gate = it }
            assertTrue(sdk.logIn("another-customer") is InappifyResult.Success)
            f.finish(sdk, login)
            assertEquals("another-customer", sdk.snapshot.appUserIdentifier)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun eventsBeforeConfigureAndAfterCloseDoNoWorkAndBothMonitorsClose() = runBlocking {
        val f = Fixture()
        val sdk = f.client()
        f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true)
        delay(100)
        assertTrue(f.transport.requests.isEmpty())
        f.startup(sdk)
        sdk.close()
        val requests = f.transport.requests.size
        f.time.set(300_000)
        f.visibility.foreground(); f.connectivity.update(false); f.connectivity.update(true)
        delay(100)
        assertEquals(requests, f.transport.requests.size)
        assertTrue(f.visibility.closed)
        assertTrue(f.connectivity.isConnected) // Closed monitor ignored both queued changes.
    }

    private fun returnUrl(id: String = "payment-1", appId: Long = 12) =
        "example-app://any-developer-route?keep=1&inappify_return=payment_v1&inappify_app_id=$appId&inappify_return_id=$id#screen"

    @Test fun ordinaryLinksAndAnotherAppsMarkersDoNotForceRecovery() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            assertFalse(sdk.handleDeepLink("example-app://home?status=100"))
            assertFalse(sdk.handleDeepLink(returnUrl(appId = 99)))
            f.links.emit("example-app://ordinary-navigation")
            delay(100)
            assertEquals(1, f.queueCalls.get().toInt())
        }
    }

    @Test fun markedReturnForcesOneFreshRecoveryAndDuplicateDeliveryIsIgnored() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            val next = Gate().also { f.gate = it }
            f.links.emit(returnUrl())
            withTimeout(5_000) { next.entered.await() }
            repeat(30) { assertTrue(sdk.handleDeepLink(returnUrl())) }
            f.finish(sdk, next)
            delay(100)
            repeat(5) { f.links.emit(returnUrl()) }
            delay(100)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun coldStartReturnBeforeConfigureUsesTheLaterStartupPass() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            assertTrue(sdk.handleDeepLink(returnUrl()))
            f.startup(sdk)
            delay(150)
            assertEquals(1, f.queueCalls.get().toInt())
            assertTrue(sdk.handleDeepLink(returnUrl()))
            delay(100)
            assertEquals(1, f.queueCalls.get().toInt())
        }
    }

    @Test fun aReturnDuringAnOlderPassGetsOneFreshPassAfterItInsteadOfUsingOldPaymentState() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk)
            val old = Gate().also { f.gate = it }
            val recovery = async { sdk.recover() }
            withTimeout(5_000) { old.entered.await() }
            assertTrue(sdk.handleDeepLink(returnUrl()))
            val fresh = Gate().also { f.gate = it }
            // The existing pass still has its ordinary post-delivery CustomerInfo refresh.
            f.skipNextInfoGate = true
            old.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            f.finish(sdk, fresh)
            delay(100)
            assertEquals(3, f.queueCalls.get().toInt())
        }
    }

    @Test fun offlineOrDisabledReturnWaitsUntilNetworkingIsAvailable() = runBlocking {
        for (disabled in listOf(false, true)) {
            val f = Fixture()
            f.client().use { sdk ->
                f.startup(sdk)
                if (disabled) sdk.setNetworkEnabled(false) else f.connectivity.update(false)
                assertTrue(sdk.handleDeepLink(returnUrl()))
                delay(100)
                assertEquals(1, f.queueCalls.get().toInt())
                val online = Gate().also { f.gate = it }
                if (disabled) sdk.setNetworkEnabled(true) else f.connectivity.update(true)
                f.finish(sdk, online)
                assertEquals(2, f.queueCalls.get().toInt())
            }
        }
    }

    @Test fun wrongAppQueuedBeforeConfigureIsDiscardedAndCloseReleasesLinkObservation() = runBlocking {
        val f = Fixture()
        val sdk = f.client()
        assertTrue(sdk.handleDeepLink(returnUrl(appId = 99)))
        f.startup(sdk)
        delay(100)
        assertEquals(1, f.queueCalls.get().toInt())
        sdk.close()
        assertTrue(f.links.closed)
        assertFalse(sdk.handleDeepLink(returnUrl()))
    }

    @Test fun failedReturnIsRetriedWhenRedeliveredAfterBackoffInsteadOfBeingMarkedHandled() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.startup(sdk); f.failInfo = true
            val failure = Gate().also { f.gate = it }
            assertTrue(sdk.handleDeepLink(returnUrl()))
            f.finish(sdk, failure, success = false)
            delay(100)
            f.time.set(30_000); f.failInfo = false
            val retry = Gate().also { f.gate = it }
            assertTrue(sdk.handleDeepLink(returnUrl()))
            f.finish(sdk, retry)
            assertEquals(2, f.queueCalls.get().toInt())
        }
    }

    @Test fun markedReturnHonorsErrorBackoffAndClosingCancelsTheDeferredRetry() = runBlocking {
        val f = Fixture()
        val sdk = f.client()
        try {
            f.startup(sdk); f.failInfo = true
            assertTrue(sdk.recover() is InappifyResult.Failure)
            val calls = f.infoCalls.get()
            assertTrue(sdk.handleDeepLink(returnUrl()))
            delay(100)
            assertEquals(calls, f.infoCalls.get())
            sdk.close()
            assertTrue(f.links.closed)
        } finally { sdk.close() }
    }
}
