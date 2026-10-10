package com.inappify.sdk

import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.v2.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** V2 admission, ordering and identity-barrier regression coverage. */
class TargetingSyncTest {

    @Test fun uploadsAttributesBeforeFreshOfferingsAndPersistsAndPublishesTheResult() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.client.refreshOfferings().success()
            val old = f.client.snapshot.offerings
            f.queue()
            f.calls.clear()
            f.catalog = "targeted"
            val event = CompletableDeferred<InappifyEvent>()
            f.client.addEventListener {
                if (it.type == InappifyEventType.OFFERINGS_CHANGED &&
                    it.snapshot.offerings?.offerings?.firstOrNull()?.identifier == "targeted") event.complete(it)
            }.use {
                val result = f.client.syncAttributesAndOfferingsIfNeeded().success()
                assertEquals(listOf("attributes", "offerings"), f.calls)
                assertNotSame(old, result.data)
                assertEquals("targeted", result.data.offerings!!.single().identifier)
                assertEquals("targeted", result.snapshot.offerings!!.offerings!!.single().identifier)
                assertEquals(result.snapshot.revision, withTimeout(2_000) { event.await() }.snapshot.revision)
                val persisted = f.store.value!!.customerInfoJson
                assertTrue(persisted!!.contains("targeted"))
                run {
                    val body = jsonObject(f.transport.requests.last { it.path == "attributes" }.jsonBody)
                    assertEquals("gold", body.getAsJsonObject("attributes").get("tier").asString)
                    assertFalse(jsonObject(f.transport.requests.last().jsonBody).has("forceVersion"))
                    assertTrue(f.transport.requests.last().headers["Authorization"]!!.startsWith("Bearer "))
                }
            }
        }
    }

    @Test fun sixthCallReturnsOnlyCacheAndExactWindowBoundaryAdmitsAgain() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            val snapshot = f.client.snapshot
            f.calls.clear()
            f.offline = true
            val cached = f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertSame(snapshot.offerings, cached.data)
            assertEquals(snapshot.revision, cached.snapshot.revision)
            assertTrue(f.calls.isEmpty())
            assertEquals(1, f.warnings.size)
            assertFalse(f.warnings.single().contains("fixture-key"))
            f.elapsed = 59_999
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertTrue(f.calls.isEmpty())
            f.elapsed = 60_000
            f.offline = false
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals(1, f.calls.count { it == "offerings" })
        }
    }

    @Test fun concurrentBurstAdmitsExactlyFiveNetworkOperations() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.calls.clear()
            (1..12).map { async { f.client.syncAttributesAndOfferingsIfNeeded().success() } }.awaitAll()
            assertEquals(5, f.calls.count { it == "offerings" })
            assertEquals(7, f.warnings.size)
        }
    }

    @Test fun unconfiguredCallsDoNotSpendQuota() = runBlocking {
        Fixture().use { f ->
            repeat(7) { assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure) }
            assertTrue(f.calls.isEmpty())
            f.configure()
            f.calls.clear()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            assertEquals(5, f.calls.count { it == "offerings" })
            assertTrue(f.warnings.isEmpty())
        }
    }

    @Test fun attributeNetworkFailureNeverFetchesOfferingsAndRetryKeepsValues() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.offline = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertFalse(f.calls.contains("offerings"))
            f.offline = false
            f.calls.clear()
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals(listOf("attributes", "offerings"), f.calls)
            assertEquals("gold", jsonObject(f.transport.requests.last { it.path == "attributes" }.jsonBody)
                .getAsJsonObject("attributes").get("tier").asString)
        }
    }

    @Test fun serverRejectedAttributesStopBeforeOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.rejectAttributes = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(listOf("attributes"), f.calls)
        }
    }

    @Test fun offeringsFailureReturnsFailureEvenWithCacheAndDoesNotEraseIt() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.client.refreshOfferings().success()
            val cached = f.client.snapshot.offerings
            f.failOfferings = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(cached!!.offerings!!.single().identifier,
                f.client.snapshot.offerings!!.offerings!!.single().identifier)
        }
    }

    @Test fun failedCallsCountAndMissingCacheIsAnExplicitRetryableFailure() = runBlocking {
        Fixture().use { f ->
            f.failOfferings = true
            f.configure()
            f.queue()
            f.offline = true
            repeat(5) { assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure) }
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
            assertEquals(InappifyErrorCode.UNKNOWN, result.error.code)
            assertTrue(result.error.isRetryable)
            assertEquals("RATE_LIMITED_NO_CACHE", result.error.details["reason"])
            assertEquals(60_000L, result.error.details["retryAfterMillis"])
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun cancellingAttributeSyncStopsFetchReleasesLockAndCountsAdmission() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            val entered = CompletableDeferred<Unit>()
            f.attributeGate = { entered.complete(Unit); awaitCancellation() }
            val task = async { f.client.syncAttributesAndOfferingsIfNeeded() }
            withTimeout(2_000) { entered.await() }
            task.cancelAndJoin()
            assertEquals(listOf("attributes"), f.calls)
            f.attributeGate = {}
            f.calls.clear()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            assertEquals(4, f.calls.count { it == "offerings" })
            assertEquals(1, f.warnings.size)
        }
    }

    @Test fun storageFailureAfterUploadStopsBeforeFetchingOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.store.failSave = true
            assertTrue(f.client.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(listOf("attributes"), f.calls)
        }
    }

    @Test fun loginCannotInterleaveBetweenSyncAndOfferings() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.attributeGate = { entered.complete(Unit); release.await() }
            val syncing = async(start = CoroutineStart.UNDISPATCHED) { f.client.syncAttributesAndOfferingsIfNeeded() }
            withTimeout(2_000) { entered.await() }
            val login = async(start = CoroutineStart.UNDISPATCHED) {
                f.client.login(InappifyLoginRequest("fixture-key", "customer_other_123456"))
            }
            assertFalse(f.calls.contains("login"))
            release.complete(Unit)
            val synced = withTimeout(2_000) { syncing.await() }.success()
            withTimeout(2_000) { login.await() }.success()
            assertEquals(listOf("attributes", "offerings", "login"), f.calls.take(3))
            assertEquals(f.signing.subject, synced.snapshot.appUserIdentifier)
            assertEquals("customer_other_123456", f.client.snapshot.appUserIdentifier)
        }
    }

    @Test fun identitySwitchNeverReturnsPriorAccountCacheOrResetsTheAllowance() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.catalog = "other-account"
            f.client.login(InappifyLoginRequest("fixture-key", "customer_other_123456")).success()
            f.client.refreshOfferings().success()
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded().success()
            assertEquals("other-account", result.data.offerings!!.single().identifier)
            assertEquals("customer_other_123456", result.snapshot.appUserIdentifier)
            assertTrue(f.calls.isEmpty())
            assertEquals(1, f.warnings.size)
        }
    }

    @Test fun contextInvalidationDoesNotReviveOldCacheAtTheLimit() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.client.setTargetingContext(country = "DE").success()
            f.calls.clear()
            val failure = f.client.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
            assertEquals("RATE_LIMITED_NO_CACHE", failure.error.details["reason"])
            assertNull(requireNotNull(failure.snapshot).offerings)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun syncUsesOneAwaitedFetchAndKeepsForceFieldsAbsent() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.queue()
            f.calls.clear()
            f.client.syncAttributesAndOfferingsIfNeeded().success()
            // Acquiring the client mutex again drains any incorrectly scheduled extra refresh.
            f.client.setTargetingContext(country = "DE").success()
            assertEquals(1, f.calls.count { it == "offerings" })
            assertNull(f.client.snapshot.forceVersion)
        }
    }

    @Test fun retainsExistingProtocolSpecificLogoutBarriers() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.offline = true
            assertTrue(f.client.logout() is InappifyResult.Failure)
            f.calls.clear()
            val result = f.client.syncAttributesAndOfferingsIfNeeded()
            assertTrue(result is InappifyResult.Failure)
            assertTrue(f.warnings.isEmpty())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun closedClientDoesNotMakeRequestsOrReturnCachedSuccess() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(5) { f.client.syncAttributesAndOfferingsIfNeeded().success() }
            f.client.close()
            f.calls.clear()
            try {
                f.client.syncAttributesAndOfferingsIfNeeded()
                fail("Closed clients must reject operations")
            } catch (_: IllegalStateException) { }
            assertTrue(f.calls.isEmpty())
        }
    }

    private fun <T> InappifyResult<T>.success(): InappifyResult.Success<T> {
        assertTrue("Expected success, got $this", this is InappifyResult.Success)
        return this as InappifyResult.Success<T>
    }

    private inner class Fixture : AutoCloseable {
        val signing = SigningFixture()
        val store = MemoryV2Store()
        val transport = V2Transport()
        val calls = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var elapsed = 0L
        var catalog = "default"
        var offline = false
        var failOfferings = false
        var rejectAttributes = false
        var attributeGate: suspend () -> Unit = {}
        private val limiter = TargetingSyncRateLimiter({ elapsed }, { warnings += it })
        private val metadata = AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }
        private fun offerings() = """{"status":true,"offerings":[{"identifier":"$catalog","isDefault":true}],"currentOffering":"$catalog"}"""
        init {
            transport.handler = { request ->
                calls += request.path
                if (request.path == "attributes") attributeGate()
                when {
                    offline || (request.path == "offerings" && failOfferings) ->
                        TransportResult.Failure(TransportFailureKind.NETWORK)
                    request.path == "configure" || request.path == "login" ->
                        transport.response(signing.envelope(jsonObject(request.jsonBody).string("appUserIdentifier")))
                    request.path == "offerings" -> transport.response(jsonObject(offerings()))
                    request.path == "attributes" && rejectAttributes ->
                        transport.response(jsonObject("""{"status":false,"code":"ATTRIBUTE_INVALID"}"""), 422)
                    request.path == "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                    else -> error("Unexpected path ${request.path}")
                }
            }
        }
        val client: InappifyClient = GoV2Client(signing.config, GoApi(transport, { signing.now }, {}),
            store, metadata, { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            targetingSyncLimiter = limiter)
        suspend fun configure() {
            client.configure(InappifyOptions("fixture-key", appUserIdentifier = signing.subject)).success()
        }
        suspend fun queue() {
            (client as InappifyV2Client).queueAttributes(mapOf("tier" to "gold")).success()
        }
        override fun close() = client.close()
    }
}
