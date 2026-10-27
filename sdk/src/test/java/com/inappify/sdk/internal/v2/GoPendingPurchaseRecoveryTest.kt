package com.inappify.sdk.internal.v2

import android.app.Activity
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class GoPendingPurchaseRecoveryTest {
    private class Timer(val millis: Long, val release: CompletableDeferred<Unit> = CompletableDeferred())
    private class Store : SessionStateStore, PurchaseAttemptStorage {
        var session: PersistedSession? = null
        var history = """{"version":1,"attempts":[]}"""
        override suspend fun load() = session
        override suspend fun save(session: PersistedSession): Boolean { this.session = session; return true }
        override suspend fun clear(): Boolean { session = null; return true }
        override suspend fun readPurchaseAttemptHistory() = history
        override suspend fun mutatePurchaseAttemptHistory(mutation: (String) -> String): Boolean {
            history = mutation(history); return true
        }
        override suspend fun loadPendingStoreRecoveryState() = PendingStoreRecoveryState()
    }
    private class Fixture(val market: InappifyMarket = InappifyMarket.MYKET) {
        val signing = SigningFixture()
        val store = Store()
        val timers = Channel<Timer>(Channel.UNLIMITED)
        val queries = AtomicInteger()
        val purchases = AtomicInteger()
        val infoCalls = AtomicInteger()
        val connectivity = NetworkConnectionState(true)
        var cancelled = false
        var rejectDirect = false
        val sdk = V2Transport().apply { handler = { request ->
            if (request.path == "customerInfo") infoCalls.incrementAndGet()
            response(if (request.path == "offerings") jsonObject(
                """{"status":true,"offerings":[{"identifier":"default","packages":[{"identifier":"package","trialDays":0,"product":{"identifier":"product"}}]}]}""")
            else signing.envelope().apply {
                addProperty("storePlatform", when (market) { InappifyMarket.MYKET -> 11; InappifyMarket.BAZAAR -> 10; else -> 2 })
                addProperty("storeInfo", "fixture-rsa")
            })
        } }
        val commerce = V2Transport().apply { handler = { request ->
            response(jsonObject(when (request.path) {
                "purchase" -> if (request.isStoreTrialPurchase()) """{"status":true,"data":{"purchaseStatus":"NEEDSTORE"}}"""
                    else """{"status":true,"data":{"purchaseStatus":"NEEDTOPAY","url":"https://pay.inappify.com/fixture"}}"""
                "consumable-deliveries/pending" -> """{"status":true,"data":{"deliveries":[]}}"""
                "purchase-attempts/status" -> {
                    val ids = jsonObject(request.jsonBody).getAsJsonArray("attemptIds").map { it.asString }
                    val rows = ids.joinToString(",") { id ->
                        val status = if (rejectDirect && id == "attempt") "FAILED" else "PENDING"
                        """{"attemptId":"$id","productIdentifier":"product","offeringIdentifier":"default","status":"$status"}"""
                    }
                    """{"status":true,"data":{"attempts":[$rows]}}"""
                }
                else -> error("Unexpected commerce path ${request.path}")
            }))
        } }
        fun client(automatic: Boolean = true) = GoV2Client(signing.config, GoApi(sdk, { signing.now }, {}), store,
            AppMetadataProvider { AppMetadata("com.example.mobile", "1", 1) }, { signing.now }, Dispatchers.Unconfined,
            backgroundRecovery = automatic, commerceApi = GoApi(commerce, { signing.now }, {}),
            connectivityMonitor = connectivity, pendingRecoveryDelay = { millis ->
                Timer(millis).also { timers.send(it); it.release.await() }
            }, billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter, StorePurchaseLaunchTracking {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
                    purchases.incrementAndGet()
                    request.beforeStoreLaunch?.invoke()
                    return if (cancelled) StoreBillingResult.Cancelled else StoreBillingResult.Failure(
                        StoreBillingError(StoreBillingErrorCode.OPERATION_TIMEOUT, "safe", true))
                }
                override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult {
                    queries.incrementAndGet()
                    return StorePurchaseQueryResult.Success(emptyList())
                }
                override fun close() = Unit
            } })
        suspend fun configure(client: GoV2Client) {
            assertTrue(client.configure(InappifyOptions("fixture-key", signing.subject)) is InappifyResult.Success)
            assertTrue(client.recover() is InappifyResult.Success)
            assertTrue(client.getOfferings() is InappifyResult.Success)
        }
        suspend fun buy(client: GoV2Client, id: String = "attempt") = client.purchase(Activity(),
            InappifyPurchaseRequest("product", "default", packageIdentifier = "package", idempotencyKey = id,
                productType = InappifyProductType.CONSUMABLE))
        suspend fun currentTimer(): Timer {
            delay(80) // Drain replaced preparation/dispatch timers after the serialized purchase.
            var latest = withTimeout(5_000) { timers.receive() }
            while (true) latest = timers.tryReceive().getOrNull() ?: return latest
        }
        suspend fun awaitRecovery(client: GoV2Client, timer: Timer) {
            val before = infoCalls.get()
            timer.release.complete(Unit)
            withTimeout(5_000) { while (infoCalls.get() == before) delay(10) }
            assertTrue(client.recover() is InappifyResult.Success) // Join, never start a duplicate payment.
        }
    }

    @Test fun timerReconcilesWithoutRepaymentOrInferringFailureFromEmptyInventory() = runBlocking {
        for (market in listOf(InappifyMarket.MYKET, InappifyMarket.BAZAAR)) {
            val f = Fixture(market)
            f.client().use { client ->
                f.configure(client)
                assertTrue(f.buy(client) is InappifyResult.Failure)
                val before = f.queries.get()
                val timer = f.currentTimer()
                assertEquals(600_000L, timer.millis)
                assertEquals(before, f.queries.get())
                f.awaitRecovery(client, timer)
                assertTrue(f.queries.get() > before)
                assertEquals(1, f.purchases.get())
                assertEquals(InappifyPurchaseAttemptStatus.PENDING,
                    (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!.status)
                assertEquals(600_000L, f.currentTimer().millis)
            }
        }
    }

    @Test fun confirmedCancellationIsTerminalAndNeverSchedulesARepayment() = runBlocking {
        val f = Fixture().apply { cancelled = true }
        f.client().use { client ->
            f.configure(client)
            assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, (f.buy(client) as InappifyResult.Failure).error.code)
            assertEquals(InappifyPurchaseAttemptStatus.FAILED,
                (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!.status)
            while (true) {
                val replaced = f.timers.tryReceive().getOrNull() ?: break
                replaced.release.complete(Unit)
            }
            delay(100)
            assertEquals(1, f.purchases.get())
            f.buy(client, "explicit-new-attempt")
            assertEquals(2, f.purchases.get())
        }
    }

    @Test fun closingTheClientCancelsTheTimerAndPreservesThePendingRecord() = runBlocking {
        val f = Fixture()
        val client = f.client()
        f.configure(client); f.buy(client)
        val timer = f.currentTimer()
        val before = f.infoCalls.get()
        client.close()
        timer.release.complete(Unit)
        delay(100)
        assertEquals(before, f.infoCalls.get())
        assertTrue(f.store.history.contains("PENDING"))
    }

    @Test fun directRecoveryUsesAuthoritativeStatusAndPreservesAnotherUnresolvedAttempt() = runBlocking {
        val f = Fixture(InappifyMarket.NONE)
        f.client().use { client ->
            f.configure(client)
            assertTrue(f.buy(client) is InappifyResult.Success)
            assertTrue(f.buy(client, "other-attempt") is InappifyResult.Success)
            f.rejectDirect = true
            f.awaitRecovery(client, f.currentTimer())
            assertEquals(InappifyPurchaseAttemptStatus.FAILED,
                (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!.status)
            assertEquals(InappifyPurchaseAttemptStatus.PENDING,
                (client.purchaseAttempt("other-attempt") as InappifyResult.Success).data!!.status)
            assertEquals(0, f.purchases.get())
            assertEquals(2, f.commerce.requests.count { it.path == "purchase" })
        }
    }

    @Test fun offlineDeadlineDefersUntilReconnectWithoutErasingTheAttempt() = runBlocking {
        val f = Fixture()
        f.client().use { client ->
            f.configure(client); f.buy(client)
            val timer = f.currentTimer()
            val before = f.infoCalls.get()
            f.connectivity.update(false)
            timer.release.complete(Unit)
            delay(100)
            assertEquals(before, f.infoCalls.get())
            assertEquals(InappifyPurchaseAttemptStatus.PENDING,
                (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!.status)
            f.connectivity.update(true)
            f.awaitRecovery(client, f.currentTimer())
            assertEquals(InappifyPurchaseAttemptStatus.PENDING,
                (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!.status)
        }
    }
}
