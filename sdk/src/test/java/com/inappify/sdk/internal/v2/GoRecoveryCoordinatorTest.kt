package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoRecoveryCoordinatorTest {
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pauseOnce() { if (entered.complete(Unit)) release.await() }
    }

    private class Fixture(private val market: InappifyMarket? = InappifyMarket.BAZAAR) {
        val signing = SigningFixture()
        val storage = MemoryV2Store()
        val transport = V2Transport()
        val gates = ConcurrentHashMap<String, Gate>()
        val queries = CopyOnWriteArrayList<Pair<String, StoreProductType>>()
        val infoCustomers = CopyOnWriteArrayList<String>()
        @Volatile var customer = signing.subject
        @Volatile var failInfo = false
        @Volatile var rejectLogin = false
        init {
            transport.handler = { request -> when (request.path) {
                "configure", "login" -> {
                    if (request.path == "login" && rejectLogin)
                        transport.response(jsonObject("""{"status":false,"code":"CUSTOMER_MERGE_FAILED"}"""), 409)
                    else {
                        customer = jsonObject(request.jsonBody).string("appUserIdentifier")
                        transport.response(envelope(customer))
                    }
                }
                "customerInfo" -> {
                    val requestedCustomer = customer
                    infoCustomers += requestedCustomer
                    gates[requestedCustomer]?.pauseOnce()
                    if (failInfo) transport.response(jsonObject("""{"status":false,"code":"FIXTURE_UNAVAILABLE"}"""), 503)
                    else transport.response(envelope(requestedCustomer))
                }
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[]}"""))
                "consumable-deliveries/pending" -> transport.response(jsonObject("""{"status":true,"data":{"deliveries":[]}}"""))
                else -> error("Unexpected path ${request.path}")
            } }
        }
        private fun envelope(identity: String) = signing.envelope(identity).apply {
            addProperty("storePlatform", when (market) { InappifyMarket.BAZAAR -> 10; InappifyMarket.MYKET -> 11; else -> 2 })
            addProperty("storeInfo", "fixture-public-rsa")
        }
        fun client(automatic: Boolean = true) = GoV2Client(signing.config,
            GoApi(transport, { signing.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = automatic,
            commerceApi = GoApi(transport, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult =
                    error("Recovery must never open purchase UI")
                override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult {
                    queries += customer to productType
                    return StorePurchaseQueryResult.Success(emptyList())
                }
                override fun close() = Unit
            } })
        fun options() = InappifyOptions("public-key", signing.subject)
        suspend fun await(gate: Gate) { withTimeout(5_000) { gate.entered.await() } }
    }

    @Test fun configureAndConcurrentManualRecoveryUseOnePassForBothNativeStores() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val f = Fixture(market)
            val gate = Gate().also { f.gates[f.customer] = it }
            f.client().use { sdk ->
                assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
                f.await(gate) // Configure has already returned while recovery is paused.
                val reads = List(25) { async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() } }
                gate.release.complete(Unit)
                reads.forEach { assertTrue(withTimeout(5_000) { it.await() } is InappifyResult.Success) }
                val types = if (market == InappifyMarket.BAZAAR)
                    listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP) else listOf(StoreProductType.IN_APP)
                assertEquals(types, f.queries.map { it.second })
                assertEquals(2, f.infoCustomers.size) // Initial info and the existing post-sync refresh.
                assertEquals(1, f.transport.requests.count { it.path == "offerings" })
            }
        }
    }

    @Test fun repeatedConfigureDuringRecoveryDoesNotScheduleALateDuplicate() = runBlocking {
        val f = Fixture()
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val repeats = List(20) { async(start = CoroutineStart.UNDISPATCHED) { sdk.configure(f.options()) } }
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            gate.release.complete(Unit)
            repeats.forEach { assertTrue(withTimeout(5_000) { it.await() } is InappifyResult.Success) }
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            assertEquals(1, f.transport.requests.count { it.path == "configure" })
            assertEquals(2, f.queries.size)
            assertEquals(2, f.infoCustomers.size)
        }
    }

    @Test fun loginDuringOldRecoveryQueuesTheNewCustomerAndStopsOldStages() = runBlocking {
        val f = Fixture()
        val oldGate = Gate().also { f.gates[f.customer] = it }
        val nextCustomer = "next-customer"
        val nextGate = Gate().also { f.gates[nextCustomer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(oldGate)
            val oldRecovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            val login = async(start = CoroutineStart.UNDISPATCHED) { sdk.logIn(nextCustomer) }
            oldGate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { login.await() } is InappifyResult.Success)
            f.await(nextGate)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            nextGate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            val superseded = withTimeout(5_000) { oldRecovery.await() } as InappifyResult.Failure
            assertEquals("RECOVERY_SUPERSEDED", superseded.error.details["serverCode"])
            assertEquals(listOf(nextCustomer, nextCustomer), f.queries.map { it.first })
            assertEquals(listOf(f.signing.subject, nextCustomer, nextCustomer), f.infoCustomers.toList())
            assertEquals(nextCustomer, sdk.snapshot.appUserIdentifier)
            assertEquals(nextCustomer, sdk.snapshot.customerInfo!!.originalAppUserId)
        }
    }

    @Test fun restoredConfigureCoalescesRecoveryWithoutChangingTheStoredFormat() = runBlocking {
        val f = Fixture()
        f.client(automatic = false).use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
        }
        val keys = jsonObject(f.storage.value!!.customerInfoJson!!).keySet().toSet()
        f.transport.requests.clear()
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            assertFalse(f.transport.requests.any { it.path == "configure" })
            assertEquals(2, f.queries.size)
            assertEquals(keys, jsonObject(f.storage.value!!.customerInfoJson!!).keySet())
        }
    }

    @Test fun aRejectedLoginDoesNotSupersedeRecoveryForTheUnchangedCustomer() = runBlocking {
        val f = Fixture().apply { rejectLogin = true }
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            val login = async(start = CoroutineStart.UNDISPATCHED) { sdk.logIn("rejected-customer") }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { login.await() } is InappifyResult.Failure)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Success)
            assertEquals(f.signing.subject, sdk.snapshot.appUserIdentifier)
            assertEquals(listOf(f.signing.subject, f.signing.subject), f.queries.map { it.first })
            assertEquals(2, f.infoCustomers.size)
        }
    }

    @Test fun failedRecoveryDoesNotTurnConfigureIntoFailureOrBlockTheNextPass() = runBlocking {
        val f = Fixture().apply { failInfo = true }
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { recovery.await() } is InappifyResult.Failure)
            assertTrue(sdk.snapshot.isConfigured)
            assertEquals(2, f.queries.size) // Store work still runs after the info failure.
            f.failInfo = false
            assertTrue(withTimeout(5_000) { sdk.recover() } is InappifyResult.Success)
            assertEquals(4, f.queries.size)
        }
    }

    @Test fun directRecoveryWaitersShareOneDeliveryQueueRead() = runBlocking {
        val f = Fixture(market = null)
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val reads = List(20) { async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() } }
            gate.release.complete(Unit)
            reads.forEach { assertTrue(withTimeout(5_000) { it.await() } is InappifyResult.Success) }
            assertEquals(1, f.transport.requests.count { it.path == "consumable-deliveries/pending" })
            assertTrue(f.queries.isEmpty())
        }
    }

    @Test fun canceledRecoveryWaiterDoesNotInterruptStartupRecovery() = runBlocking {
        val f = Fixture()
        val gate = Gate().also { f.gates[f.customer] = it }
        f.client().use { sdk ->
            assertTrue(sdk.configure(f.options()) is InappifyResult.Success)
            f.await(gate)
            val canceled = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            val remaining = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
            canceled.cancelAndJoin()
            gate.release.complete(Unit)
            assertTrue(withTimeout(5_000) { remaining.await() } is InappifyResult.Success)
            assertEquals(2, f.queries.size)
        }
    }
}
