package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoCustomerInfoPolicyTest {
    private class Visibility(var visible: Boolean = true) : AppForegroundMonitor {
        private var listener: ((Boolean) -> Unit)? = null
        override val isForeground: Boolean get() = visible
        override fun setListener(listener: (Boolean) -> Unit) { this.listener = listener }
        fun change(value: Boolean) { visible = value; listener?.invoke(value) }
        override fun close() { listener = null }
    }

    private class Fixture {
        val signing = SigningFixture()
        var now = signing.now
        var customer = signing.subject
        val storage = MemoryV2Store()
        val transport = V2Transport()
        val visibility = Visibility()
        var gate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
        var failure = false
        init {
            transport.handler = { req -> when (req.path) {
                "configure", "login" -> {
                    customer = jsonObject(req.jsonBody).string("appUserIdentifier")
                    transport.response(envelope())
                }
                "customerInfo" -> {
                    gate?.let { it.first.complete(Unit); it.second.await() }
                    if (failure) TransportResult.Failure(TransportFailureKind.NETWORK)
                    else transport.response(envelope())
                }
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[]}"""))
                "consumable-deliveries/pending" -> transport.response(jsonObject(
                    """{"status":true,"data":{"deliveries":[]}}"""))
                else -> error("Unexpected path ${req.path}")
            } }
        }
        fun envelope() = signing.envelope(customer, mutatePayload = {
            it.addProperty("exp", now / 1000 + 300)
        }).apply {
            addProperty("sessionExpiresAt", "2099-09-09T00:00:00Z")
            getAsJsonObject("verification").addProperty("expiresAt",
                java.time.Instant.ofEpochMilli(now + 300_000).toString())
        }
        fun client(automatic: Boolean = false) = GoV2Client(signing.config,
            GoApi(transport, { now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { now }, Dispatchers.Unconfined, backgroundRecovery = automatic, foregroundMonitor = visibility,
            commerceApi = GoApi(transport, { now }, {}))
        suspend fun configure(sdk: GoV2Client, automatic: Boolean = false) = coroutineScope {
            if (automatic) gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            assertTrue(sdk.configure(InappifyOptions("public-key", customer)) is InappifyResult.Success)
            if (automatic) {
                withTimeout(5_000) { gate!!.first.await() }
                val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
                gate!!.second.complete(Unit)
                assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
                gate = null
            }
            transport.requests.clear()
        }
        fun count() = transport.requests.count { it.path == "customerInfo" }
    }

    @Test fun cacheOnlyMissDoesNotUseNetworkAndFreshDefaultDoesNotWriteOrFetch() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.CACHE_ONLY) is InappifyResult.Failure)
            assertTrue(f.transport.requests.isEmpty())
            f.configure(sdk)
            val saved = f.storage.value
            val cached = sdk.snapshot.customerInfo
            repeat(20) {
                assertSame(cached, (sdk.getCustomerInfo() as InappifyResult.Success).data)
                assertSame(cached, (sdk.getCustomerInfo(InappifyCacheFetchPolicy.CACHE_ONLY) as InappifyResult.Success).data)
            }
            assertEquals(0, f.count())
            assertSame(saved, f.storage.value)
        }
    }

    @Test fun notStalePolicyWaitsAtFiveMinutesAndConcurrentReadsShareOneFetch() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            f.now += 299_999
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT) is InappifyResult.Success)
            assertEquals(0, f.count())
            f.now++
            f.gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            val reads = List(20) { async(start = CoroutineStart.UNDISPATCHED) {
                sdk.getCustomerInfo(InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT)
            } }
            withTimeout(5_000) { f.gate!!.first.await() }
            assertTrue(reads.none { it.isCompleted })
            f.gate!!.second.complete(Unit)
            reads.forEach { assertTrue(withTimeout(5_000) { it.await() } is InappifyResult.Success) }
            assertEquals(1, f.count())
        }
    }

    @Test fun fetchCurrentAndStaleRequiredPoliciesReturnErrorsAndKeepVerifiedCache() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) is InappifyResult.Success)
            assertEquals(1, f.count())
            val cached = sdk.snapshot.customerInfo
            val saved = f.storage.value
            f.failure = true
            f.now += 300_000
            for (policy in listOf(InappifyCacheFetchPolicy.FETCH_CURRENT, InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT))
                assertTrue(sdk.getCustomerInfo(policy) is InappifyResult.Failure)
            assertSame(cached, sdk.snapshot.customerInfo)
            assertSame(saved, f.storage.value)
            assertSame(cached, (sdk.getCustomerInfo() as InappifyResult.Success).data)
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) is InappifyResult.Failure)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.NETWORK_FIRST) is InappifyResult.Success)
        }
    }

    @Test fun staleDefaultReturnsImmediatelyAndSharesItsRefreshWithExplicitFetch() = runBlocking {
        val f = Fixture()
        f.client(automatic = true).use { sdk ->
            f.configure(sdk, automatic = true)
            val cached = sdk.snapshot.customerInfo
            f.now += 300_000
            f.gate = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
            repeat(20) { assertSame(cached, (sdk.getCustomerInfo() as InappifyResult.Success).data) }
            withTimeout(5_000) { f.gate!!.first.await() }
            val explicit = async(start = CoroutineStart.UNDISPATCHED) {
                sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT)
            }
            f.gate!!.second.complete(Unit)
            assertTrue(withTimeout(5_000) { explicit.await() } is InappifyResult.Success)
            assertEquals(1, f.count())
        }
    }

    @Test fun backgroundFreshnessIsTwentyFiveHoursAndRestoredCacheKeepsItsTimestamp() = runBlocking {
        val f = Fixture()
        f.client().use { sdk -> f.configure(sdk) }
        f.now += 300_000
        f.client().use { sdk ->
            f.configure(sdk)
            assertEquals(0, f.transport.requests.size)
            f.visibility.change(false)
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT) is InappifyResult.Success)
            assertEquals(0, f.count())
            f.now = f.signing.now + 25 * 60 * 60 * 1000L
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT) is InappifyResult.Success)
            assertEquals(1, f.count())
        }
    }

    @Test fun identityChangeReplacesAllEntitlementViewsAndFreshness() = runBlocking {
        val f = Fixture()
        f.client().use { sdk ->
            f.configure(sdk)
            f.now += 300_000
            assertTrue(sdk.logIn("second-customer") is InappifyResult.Success)
            val info = (sdk.getCustomerInfo() as InappifyResult.Success).data
            assertEquals("second-customer", info.originalAppUserId)
            assertNotNull(info.entitlements.active["pro"])
            assertEquals(0, f.count())
        }
    }
}
