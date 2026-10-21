package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoOfferingsRefreshTest {
    private class Visibility(var foreground: Boolean = false) : AppForegroundMonitor {
        private var listener: ((Boolean) -> Unit)? = null
        var closed = false
        override val isForeground: Boolean get() = foreground
        override fun setListener(listener: (Boolean) -> Unit) { this.listener = listener }
        fun change(foreground: Boolean) { this.foreground = foreground; listener?.invoke(foreground) }
        override fun close() { closed = true; listener = null }
    }

    private class Fixture {
        val signing = SigningFixture()
        val storage = MemoryV2Store()
        val transport = V2Transport()
        val visibility = Visibility()
        var monotonic = 0L
        var refreshPolicy = OfferingsRefreshPolicy { monotonic }
        var customer = signing.subject
        var infoGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
        var offeringGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
        var offeringFailure = false
        val options = InappifyOptions("public-key", signing.subject)

        init {
            transport.handler = { request -> when (request.path) {
                "configure", "login" -> {
                    customer = jsonObject(request.jsonBody).string("appUserIdentifier")
                    transport.response(signing.envelope(customer))
                }
                "customerInfo" -> {
                    infoGate?.let { it.first.complete(Unit); it.second.await() }
                    transport.response(signing.envelope(customer))
                }
                "offerings" -> {
                    offeringGate?.let { it.first.complete(Unit); it.second.await() }
                    if (offeringFailure) TransportResult.Failure(TransportFailureKind.NETWORK)
                    else transport.response(jsonObject("""{"status":true,"currentOffering":"main","offerings":[
                        {"identifier":"main","packages":[{"identifier":"monthly","product":{
                            "identifier":"pro","productType":"SUBSCRIPTION"}}]}]}"""))
                }
                "consumable-deliveries/pending" -> transport.response(jsonObject(
                    """{"status":true,"data":{"deliveries":[]}}"""))
                "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                else -> error("Unexpected path ${request.path}")
            } }
        }

        fun client(): GoV2Client {
            refreshPolicy = OfferingsRefreshPolicy { monotonic }
            return GoV2Client(signing.config,
                GoApi(transport, { signing.now }, {}), storage,
                AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
                { signing.now }, Dispatchers.Unconfined, backgroundRecovery = true,
                commerceApi = GoApi(transport, { signing.now }, {}), foregroundMonitor = visibility,
                offeringsRefreshPolicy = refreshPolicy,
                targetingSyncLimiter = TargetingSyncRateLimiter({ monotonic }, {}))
        }

        suspend fun configured(sdk: GoV2Client) = coroutineScope {
            // Join the actual Configure-triggered recovery after it starts, so assertions cannot race it.
            val gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            infoGate = gate
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            withTimeout(5_000) { gate.first.await() }
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            gate.second.complete(Unit)
            withTimeout(5_000) { recovery.await() }
            infoGate = null
        }

        fun offers(): Int = transport.requests.count { it.path == "offerings" }
        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) yield() }
    }

    @Test fun backgroundConfigureDefersOnlyOfferingPrefetchUntilTheAppIsVisible() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configured(sdk)
            assertEquals(0, f.offers())
            assertNull(sdk.snapshot.offerings)
            // Existing customer/delivery recovery still runs while the catalog prefetch is deferred.
            assertTrue(f.transport.requests.any { it.path == "customerInfo" })
            assertTrue(f.transport.requests.any { it.path == "consumable-deliveries/pending" })
            f.visibility.change(true)
            f.until { sdk.snapshot.offerings != null }
            assertEquals(1, f.offers())
            assertEquals("main", sdk.snapshot.offerings!!.current!!.identifier)
        }
        assertTrue(f.visibility.closed)
    }

    @Test fun freshCacheReadsAndShortForegroundTransitionsDoNotFetchAgain() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            assertEquals(1, f.offers())
            val saved = f.storage.value
            repeat(40) { assertTrue(sdk.getOfferings() is InappifyResult.Success) }
            f.monotonic = 299_999
            f.visibility.change(false)
            f.visibility.change(true)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            assertEquals(1, f.offers())
            assertSame(saved, f.storage.value)
        }
    }

    @Test fun staleCacheReturnsImmediatelyAndConcurrentReadsShareOneRefresh() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            val cached = sdk.snapshot.offerings
            f.monotonic = 300_000
            val gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            f.offeringGate = gate
            repeat(30) { assertSame(cached, (sdk.getOfferings() as InappifyResult.Success).data) }
            withTimeout(5_000) { gate.first.await() }
            assertEquals(2, f.offers())
            gate.second.complete(Unit)
            f.until { sdk.snapshot.offerings !== cached }
            assertEquals(2, f.offers())
        }
    }

    @Test fun cacheMissReadDoesNotDuplicateAnAlreadyRunningForegroundPrefetch() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configured(sdk)
            val gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            f.offeringGate = gate
            f.visibility.change(true)
            withTimeout(5_000) { gate.first.await() }
            val explicitRead = async(start = CoroutineStart.UNDISPATCHED) { sdk.getOfferings() }
            gate.second.complete(Unit)
            assertTrue(withTimeout(5_000) { explicitRead.await() } is InappifyResult.Success)
            assertEquals(1, f.offers())
        }
    }

    @Test fun failedAutomaticRefreshKeepsCacheAndWaitsThirtySecondsBeforeTryingAgain() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            val cached = sdk.snapshot.offerings
            f.monotonic = 300_000
            f.offeringFailure = true
            sdk.getOfferings()
            f.until { !f.refreshPolicy.shouldRefresh() }
            val count = f.offers()
            repeat(30) { assertSame(cached, (sdk.getOfferings() as InappifyResult.Success).data) }
            f.monotonic = 329_999
            f.visibility.change(true)
            assertEquals(count, f.offers())
            f.offeringFailure = false
            f.monotonic = 330_000
            f.visibility.change(true)
            f.until { sdk.snapshot.offerings !== cached }
            assertEquals(count + 1, f.offers())
        }
    }

    @Test fun staleBackgroundReadsWaitForForegroundButExplicitRefreshStillWorks() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            f.visibility.change(false)
            f.monotonic = 300_000
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            assertEquals(1, f.offers())
            assertTrue(sdk.offerings(InappifyFetchPolicy.NETWORK_ONLY) is InappifyResult.Success)
            assertEquals(2, f.offers())
            assertTrue(sdk.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Success)
            assertEquals(3, f.offers())
        }
    }

    @Test fun attributeInvalidationAndNetworkRestorationCannotReuseFreshnessOfTheOldCatalog() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            sdk.setNetworkEnabled(false)
            assertTrue(sdk.enqueueAttributes(mapOf("audience" to "changed")) is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            f.visibility.change(true)
            assertEquals(1, f.offers())
            sdk.setNetworkEnabled(true)
            f.until { sdk.snapshot.offerings != null }
            assertEquals(2, f.offers())
            assertEquals("IR", sdk.snapshot.country)
        }
    }

    @Test fun storedCatalogNeedsOneForegroundValidationWithoutAddingTimingFieldsToCache() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk -> f.configured(sdk) }
        f.visibility.foreground = false
        val before = f.storage.value!!.customerInfoJson!!
        val beforeKeys = jsonObject(before).keySet()
        f.client().use { sdk ->
            f.configured(sdk)
            assertNotNull(sdk.snapshot.offerings)
            val cached = sdk.snapshot.offerings
            assertEquals(1, f.offers())
            f.visibility.change(true)
            f.until { f.offers() == 2 && sdk.snapshot.offerings !== cached }
            assertEquals(beforeKeys, jsonObject(f.storage.value!!.customerInfoJson!!).keySet())
        }
    }

    @Test fun initialPrefetchFailureBackoffSurvivesUnrelatedCustomerInfoCommits() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true; offeringFailure = true }
        f.client().use { sdk ->
            f.configured(sdk)
            assertNull(sdk.snapshot.offerings)
            val initialAttempts = f.offers() // The existing transport may retry a network failure internally.
            assertTrue(initialAttempts > 0)
            assertTrue(sdk.getCustomerInfo() is InappifyResult.Success)
            repeat(20) { f.visibility.change(true) }
            assertEquals(initialAttempts, f.offers())
            f.monotonic = 30_000
            f.offeringFailure = false
            f.visibility.change(true)
            f.until { sdk.snapshot.offerings != null }
            assertEquals(initialAttempts + 1, f.offers())
        }
    }

    @Test fun cacheOnlyReadsDoNotPrefetchOrTurnAMissIntoNetworkWork() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configured(sdk)
            assertTrue(sdk.offerings(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Failure)
            assertEquals(0, f.offers())
            assertTrue(sdk.offerings(InappifyFetchPolicy.NETWORK_ONLY) is InappifyResult.Success)
            val cached = sdk.snapshot.offerings
            f.monotonic = 300_000
            assertSame(cached, (sdk.offerings(InappifyFetchPolicy.CACHE_ONLY) as InappifyResult.Success).data)
            assertEquals(1, f.offers())
        }
    }

    @Test fun loginCannotReuseThePreviousCustomersFreshnessWindow() = runBlocking {
        val f = Fixture().apply { visibility.foreground = true }
        f.client().use { sdk ->
            f.configured(sdk)
            assertEquals(1, f.offers())
            f.visibility.change(false)
            assertTrue(sdk.logIn("other-customer") is InappifyResult.Success)
            assertEquals("other-customer", sdk.snapshot.appUserIdentifier)
            assertNull(sdk.snapshot.offerings)
            f.visibility.change(true)
            f.until { sdk.snapshot.offerings != null }
            assertEquals("other-customer", sdk.snapshot.appUserIdentifier)
            assertEquals(2, f.offers())
        }
    }
}
