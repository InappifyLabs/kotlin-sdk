package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoCommerceSessionTest {
    private val signing = SigningFixture()
    private val storage = MemoryV2Store()
    private val sdkHttp = V2Transport()
    private val commerce = V2Transport()
    private var identity = signing.subject
    private var token = "session-initial"
    private var now = signing.now
    private var expired = false
    private var pending = true
    private var ackFailure = false
    private var handlerCalls = 0
    private val options get() = InappifyOptions("public-bootstrap-key", identity)
    private fun envelope() = signing.envelope(identity).apply {
        addProperty("sessionToken", token)
        if (expired) addProperty("sessionExpiresAt", "2026-09-08T08:00:01Z")
    }
    private fun delivery(status: String = "DELIVERY_REQUIRED", id: Long = 7, product: String = "coins") = jsonObject(
        """{"deliveryId":$id,"productIdentifier":"$product","source":"direct","status":"$status"}""")
    private fun client(config: InappifyV2Configuration = signing.config, commerceTransport: HttpTransport = commerce): GoV2Client {
        sdkHttp.handler = { request -> when (request.path) {
            "configure", "customerInfo" -> sdkHttp.response(envelope())
            "login" -> {
                identity = jsonObject(request.jsonBody).string("appUserIdentifier")
                token = "session-login"; sdkHttp.response(envelope())
            }
            "logout" -> {
                identity = anonymousId()
                token = "session-logout"; sdkHttp.response(envelope())
            }
            "offerings" -> sdkHttp.response(jsonObject("""{"status":true,"offerings":[{"identifier":"default",
                "packages":[{"identifier":"pack","product":{"identifier":"coins"}}]}]}"""))
            else -> error("Unexpected ${request.path}")
        } }
        commerce.handler = { request -> when (request.path) {
            "purchase" -> commerce.response(jsonObject("""{"status":true,"data":{"purchaseStatus":"DONE"}}"""))
            "consumable-deliveries/pending" -> commerce.response(JsonObject().apply {
                addProperty("status", true); add("data", JsonObject().apply {
                    add("deliveries", com.google.gson.JsonArray().apply { if (pending) add(delivery()) })
                })
            })
            "consumable-deliveries/7/delivered" -> if (ackFailure) TransportResult.Failure(TransportFailureKind.TIMEOUT)
                else commerce.response(JsonObject().apply { addProperty("status", true); add("data", delivery("COMPLETED")) })
            else -> error("Unexpected ${request.path}")
        } }
        return GoV2Client(config, GoApi(sdkHttp, { now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.app", "1.0", 1) }, { now },
            Dispatchers.Unconfined, backgroundRecovery = false, commerceApi = GoApi(commerceTransport, { now }, {}))
    }
    private suspend fun configure(sdk: GoV2Client) {
        assertTrue(sdk.configure(options) is InappifyResult.Success)
        assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
    }
    private fun handler(sdk: GoV2Client) = sdk.setConsumableDeliveryHandler { handlerCalls++; InappifyDeliveryResult.DELIVERED }
    private fun reject(code: String, status: Int = 401) = commerce.response(jsonObject("""{"status":false,"code":"$code"}"""), status)

    @Test fun directDoneAndNeedToPayUseOnlySessionAndAllowedFields() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default")) is InappifyResult.Success)
            commerce.handler = { commerce.response(jsonObject("""{"status":true,"data":{"purchaseStatus":"NEEDTOPAY",
                "url":"https://pay.inappify.com/checkout"}}""")) }
            assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default").withPaywallAttribution(42, 7)) is InappifyResult.Success)
            commerce.requests.forEach {
                assertEquals("Bearer session-initial", it.headers["Authorization"])
                assertEquals("application/json", it.headers["Accept"])
                assertTrue(jsonObject(it.jsonBody).keySet().all { field -> field in setOf("productIdentifier", "offeringIdentifier", "isCrypto", "paywallId", "paywallRevision") })
            }
        }
    }
    @Test fun expiredCommerceRenewsOnceAndReplaysIdenticalBodyWithFreshSession() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            commerce.handler = { if (commerce.requests.size == 1) {
                token = "session-renewed"; reject("SESSION_EXPIRED")
            } else commerce.response(jsonObject("""{"status":true,"data":{"purchaseStatus":"DONE"}}""")) }
            assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default")) is InappifyResult.Success)
            assertEquals(listOf("Bearer session-initial", "Bearer session-renewed"), commerce.requests.map { it.headers["Authorization"] })
            assertEquals(commerce.requests[0].jsonBody, commerce.requests[1].jsonBody)
            assertEquals(2, sdkHttp.requests.count { it.path == "configure" })
            assertEquals(signing.subject, jsonObject(sdkHttp.requests.last { it.path == "configure" }.jsonBody).string("appUserIdentifier"))
        }
    }
    @Test fun onlyExpired401CanRenewAndSecondFailureIsTerminal() = runBlocking {
        for ((code, status) in listOf("SESSION_REQUIRED" to 401, "SESSION_INVALID" to 401, "SESSION_REVOKED" to 401,
            "V2_NOT_AVAILABLE" to 503, "SESSION_EXPIRED" to 403, "SESSION_EXPIRED" to 401)) {
            commerce.requests.clear(); sdkHttp.requests.clear()
            client().use { sdk ->
                configure(sdk)
                val before = sdkHttp.requests.count { it.path == "configure" }
                commerce.handler = { reject(code, status) }
                assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default")) is InappifyResult.Failure)
                val retry = code == "SESSION_EXPIRED" && status == 401
                assertEquals(if (retry) 2 else 1, commerce.requests.size)
                assertEquals(before + if (retry) 1 else 0, sdkHttp.requests.count { it.path == "configure" })
            }
        }
    }
    @Test fun invalidSignedRenewalCannotReplaceSessionOrReplayPayment() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val saved = storage.value!!.customerInfoJson
            commerce.handler = { reject("SESSION_EXPIRED") }
            sdkHttp.handler = { sdkHttp.response(envelope().apply { addProperty("appUserId", "wrong-customer") }) }
            assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default")) is InappifyResult.Failure)
            assertEquals(saved, storage.value!!.customerInfoJson)
            assertEquals(1, commerce.requests.size)
        }
    }
    @Test fun failedAtomicSaveDoesNotReplayWithUncommittedRenewal() = runBlocking {
        client().use { sdk ->
            configure(sdk); val saved = storage.value!!.customerInfoJson
            commerce.handler = { token = "new-uncommitted-token"; storage.failSave = true; reject("SESSION_EXPIRED") }
            assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default")) is InappifyResult.Failure)
            assertEquals(saved, storage.value!!.customerInfoJson)
            assertEquals(1, commerce.requests.size)
        }
    }
    @Test fun directPendingAndAckUseSeparateEndpointsAndIdempotentConfirmation() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val sync = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(1, sync.data.pendingDeliveries.size)
            assertEquals(0, handlerCalls)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Success)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Success)
            assertEquals(listOf("consumable-deliveries/pending", "consumable-deliveries/7/delivered"), commerce.requests.map { it.path })
            assertTrue(commerce.requests.all { it.jsonBody == "{}" && it.headers["Authorization"] == "Bearer session-initial" })
        }
    }
    @Test fun persistedHostGrantRetriesAckAfterRestartEvenWhenPendingIsEmpty() = runBlocking {
        client().use { sdk ->
            configure(sdk); handler(sdk); ackFailure = true
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Failure)
            assertEquals(1, handlerCalls)
        }
        client().use { restarted ->
            ackFailure = false; pending = false
            configure(restarted); handler(restarted)
            val result = restarted.syncPendingConsumables() as InappifyResult.Success
            assertEquals(1, result.data.completedCount)
            assertEquals(1, handlerCalls)
            assertEquals(2, commerce.requests.count { it.path.endsWith("/delivered") })
        }
    }
    @Test fun persistedPendingDoesNotCrossIdentityOrCommerceEndpoint() = runBlocking {
        client().use { sdk ->
            configure(sdk); sdk.syncPendingConsumables()
            assertTrue(sdk.login(InappifyLoginRequest("public-bootstrap-key", "customer-other")) is InappifyResult.Success)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            assertFalse(commerce.requests.any { it.path.endsWith("/delivered") })
        }
        identity = signing.subject
        client().use { sdk -> configure(sdk); sdk.syncPendingConsumables() }
        val config = InappifyV2Configuration(signing.config.sdkApiBaseUrl, signing.config.issuer, 12, 34,
            signing.config.pinnedSigningKeys, signing.config.paymentHosts, emptySet(), "https://other.example.com/app/v2/")
        client(config).use { sdk ->
            configure(sdk)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            assertFalse(commerce.requests.any { it.path.endsWith("/delivered") })
        }
    }
    @Test fun configureLoginLogoutAndExpiryAlwaysChangeTheDispatchedToken() = runBlocking {
        client().use { sdk ->
            expired = true; configure(sdk)
            expired = false; now += 2000; token = "session-refreshed"
            pending = false
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Success)
            assertTrue(sdk.login(InappifyLoginRequest("public-bootstrap-key", "customer-second")) is InappifyResult.Success)
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Success)
            assertTrue(sdk.logout() is InappifyResult.Success)
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Success)
            assertEquals(listOf("Bearer session-refreshed", "Bearer session-login", "Bearer session-logout"), commerce.requests.map { it.headers["Authorization"] })
        }
    }
    @Test fun concurrentPendingSyncHasOneRenewalAndOneDurableHostGrant() = runBlocking {
        client().use { sdk ->
            configure(sdk); handler(sdk)
            val base = commerce.handler
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var first = true
            commerce.handler = { request -> if (first) {
                first = false; entered.complete(Unit); release.await(); token = "renewed"; reject("SESSION_EXPIRED")
            } else base(request) }
            val jobs = (1..20).map { async(start = CoroutineStart.UNDISPATCHED) { sdk.syncPendingConsumables() } }
            entered.await(); release.complete(Unit)
            assertTrue(jobs.awaitAll().all { it is InappifyResult.Success })
            assertEquals(1, handlerCalls)
            assertEquals(2, sdkHttp.requests.count { it.path == "configure" })
            assertEquals(1, commerce.requests.count { it.path.endsWith("/delivered") })
        }
    }
    @Test fun malformedOrStoreDeliveryNeverReachesHostOrAcknowledgement() = runBlocking {
        for (bad in listOf(delivery().apply { addProperty("source", "bazar") },
            delivery().apply { addProperty("deliveryId", "7") }, delivery().apply { addProperty("alreadyDelivered", true) })) {
            client().use { sdk ->
                configure(sdk); handler(sdk)
                commerce.handler = { commerce.response(jsonObject("""{"status":true,"data":{"deliveries":[$bad]}}""")) }
                assertTrue(sdk.syncPendingConsumables() is InappifyResult.Failure)
                assertEquals(0, handlerCalls)
                assertFalse(commerce.requests.any { it.path.endsWith("/delivered") })
            }
        }
    }
    @Test fun directTimeoutDoesNotRetryPaymentCreation() = runBlocking {
        client().use { sdk ->
            configure(sdk); commerce.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
            val result = sdk.purchase(InappifyPurchaseRequest("coins", "default")) as InappifyResult.Failure
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals(1, commerce.requests.size)
        }
    }
    @Test fun retryLaterAndStorageFailureNeverAcknowledgeUndurableHostDelivery() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            sdk.setConsumableDeliveryHandler { InappifyDeliveryResult.RETRY_LATER }
            val pendingResult = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(1, pendingResult.data.pendingDeliveries.size)
            sdk.setConsumableDeliveryHandler { storage.failSave = true; InappifyDeliveryResult.DELIVERED }
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Failure)
            assertFalse(commerce.requests.any { it.path.endsWith("/delivered") })
        }
    }
    @Test fun mismatchedAckCannotCompleteAnotherDeliveryAndRetainsRecoveryCheckpoint() = runBlocking {
        client().use { sdk ->
            configure(sdk); sdk.syncPendingConsumables()
            commerce.handler = { commerce.response(jsonObject("""{"status":true,"data":${delivery("COMPLETED", id = 99)}}""")) }
            val result = sdk.confirmDelivery(7) as InappifyResult.Failure
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            val records = jsonObject(storage.value!!.customerInfoJson!!).getAsJsonObject("directDeliveries").getAsJsonObject("records")
            assertTrue(records.getAsJsonObject("7").flag("hostDelivered"))
            assertEquals("DELIVERY_REQUIRED", records.getAsJsonObject("7").getAsJsonObject("delivery").string("status"))
        }
    }
    @Test fun allSevenCommerceRoutesUseBearerOverRealHttpAndRedactSecrets() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val http = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val transport = OkHttpTransport.create(server.url("/app/v2/"), http)
        val traces = java.util.concurrent.CopyOnWriteArrayList<InappifyHttpTrace>()
        val tracesDelivered = java.util.concurrent.CountDownLatch(7)
        transport.addHttpTraceListener { traces += it; tracesDelivered.countDown() }
        fun enqueue(data: String) = server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"status":true,"data":$data}"""))
        try {
            client(commerceTransport = transport).use { sdk ->
                configure(sdk)
                enqueue("""{"purchaseStatus":"DONE"}""")
                assertTrue(sdk.purchase(InappifyPurchaseRequest("coins", "default").withPaywallAttribution(42, 7)) is InappifyResult.Success)
                enqueue("""{"deliveries":[${delivery()}]}""")
                assertTrue(sdk.syncPendingConsumables() is InappifyResult.Success)
                enqueue(delivery("COMPLETED").toString())
                assertTrue(sdk.confirmDelivery(7) is InappifyResult.Success)
                var session = "store-session-a"
                val service = GoV2StoreService(GoApi(transport, { now }, {})) { session }
                enqueue("""{"purchase":{"status":"PROCESSING","verificationRequestId":9,"retryAfter":2}}""")
                val submit = service.submitStorePurchase(StorePurchaseApiRequest(
                    "coins", "default", StorePurchaseOperation.PURCHASE,
                    StorePurchaseEvidence("receipt-secret", 10, null, "com.example.app", null, "original-secret", "signature-secret")))
                assertTrue(submit is StoreServiceResult.Response)
                session = "store-session-b"
                enqueue("""{"status":"DELIVERY_REQUIRED","deliveryId":7}""")
                service.getStoreVerificationStatus(StoreVerificationStatusApiRequest(9))
                enqueue("""{"status":"CONSUME_REQUIRED","deliveryId":7}""")
                service.markStoreDeliveryDelivered(StoreDeliveryApiRequest(7))
                enqueue("""{"status":"COMPLETED","deliveryId":7}""")
                service.reportStoreConsumeResult(StoreConsumeResultApiRequest(7, StoreConsumeResult.SUCCEEDED, null))
                // Diagnostics run on their own executor; closing the owning client cancels queued callbacks.
                assertTrue("All HTTP diagnostics must arrive before close", tracesDelivered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            val expected = listOf("purchase", "consumable-deliveries/pending", "consumable-deliveries/7/delivered",
                "store/purchases", "store/verifications/9/status", "store/deliveries/7/delivered", "store/deliveries/7/consume-result")
            for ((index, endpoint) in expected.withIndex()) {
                val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/app/v2/$endpoint", request.path)
                assertEquals("POST", request.method)
                assertEquals("Bearer " + when { index < 3 -> "session-initial"; index == 3 -> "store-session-a"; else -> "store-session-b" }, request.getHeader("Authorization"))
                assertEquals("application/json", request.getHeader("Accept"))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
                val body = jsonObject(request.body.readUtf8())
                assertTrue(body.keySet().intersect(setOf("apikey", "apiKey", "token", "appIdentifier", "country", "appVersion", "forceVersion")).isEmpty())
                if (index in listOf(1, 2, 4, 5)) assertEquals(0, body.size())
                if (index == 3) assertEquals("receipt-secret", body.getAsJsonObject("purchase").string("token"))
            }
            assertEquals(7, traces.size)
            val rendered = traces.joinToString { "${it.requestHeaders} ${it.requestBody} ${it.responseBody}" }
            for (secret in listOf("session-initial", "store-session-a", "store-session-b", "receipt-secret", "signature-secret", "original-secret"))
                assertFalse("Sensitive value leaked", rendered.contains(secret))
        } finally { transport.close(); server.shutdown(); http.dispatcher.executorService.shutdown() }
    }

}
