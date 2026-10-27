package com.inappify.sdk.internal.v2

import android.app.Activity
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoMyketPreparationTest {
    private class Store : SessionStateStore, PurchaseAttemptStorage {
        var session: PersistedSession? = null
        var history = """{"version":1,"attempts":[]}"""
        var rejectDispatch = false
        override suspend fun load() = session
        override suspend fun save(session: PersistedSession): Boolean { this.session = session; return true }
        override suspend fun clear(): Boolean { session = null; return true }
        override suspend fun readPurchaseAttemptHistory() = history
        override suspend fun mutatePurchaseAttemptHistory(mutation: (String) -> String): Boolean {
            val next = mutation(history)
            if (rejectDispatch && next.contains("MYKET_PURCHASE_DISPATCHED")) return false
            history = next
            return true
        }
        override suspend fun loadPendingStoreRecoveryState() = PendingStoreRecoveryState()
    }
    private class Fixture(val market: InappifyMarket = InappifyMarket.MYKET) {
        val signing = SigningFixture()
        val storage = Store()
        val entered = CompletableDeferred<Unit>()
        var purchase: suspend (StorePurchaseRequest) -> StoreBillingResult = { awaitCancellation() }
        val commerce = V2Transport().apply {
            handler = { request ->
                check(request.isStoreTrialPurchase())
                response(jsonObject("""{"status":true,"data":{"purchaseStatus":"NEEDSTORE"}}"""))
            }
        }
        val sdkTransport = V2Transport().apply {
            handler = { request -> response(if (request.path == "offerings") jsonObject(
                """{"status":true,"offerings":[{"identifier":"default","packages":[{"identifier":"package","product":{"identifier":"product"}}]}]}""")
                else signing.envelope().apply { addProperty("storePlatform", if (market == InappifyMarket.MYKET) 11 else 10); addProperty("storeInfo", "fixture-rsa") }) }
        }
        fun client() = GoV2Client(signing.config, GoApi(sdkTransport, { signing.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "1", 1) }, { signing.now },
            Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerce, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { _, _ -> object : StoreBillingAdapter, StorePurchaseLaunchTracking {
                override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult {
                    entered.complete(Unit)
                    return purchase(request)
                }
                override suspend fun queryPurchases(productType: StoreProductType) = StorePurchaseQueryResult.Success(emptyList())
                override fun close() = Unit
            } })
        suspend fun configure(client: GoV2Client) {
            assertTrue(client.configure(InappifyOptions("fixture-key", signing.subject)) is InappifyResult.Success)
            assertTrue(client.getOfferings() is InappifyResult.Success)
        }
        suspend fun buy(client: GoV2Client, id: String = "attempt") = client.purchase(Activity(),
            InappifyPurchaseRequest("product", "default", packageIdentifier = "package", idempotencyKey = id,
                productType = InappifyProductType.CONSUMABLE))
        suspend fun attempt(client: GoV2Client) = (client.purchaseAttempt("attempt") as InappifyResult.Success).data!!
    }

    @Test fun preparationTimeoutIsTerminalAndAllowsANewAttempt() = runBlocking {
        val f = Fixture()
        f.purchase = { StoreBillingResult.Failure(StoreBillingError(StoreBillingErrorCode.OPERATION_TIMEOUT, "safe", true)) }
        f.client().use { client ->
            f.configure(client)
            val failure = f.buy(client) as InappifyResult.Failure
            assertEquals(false, failure.error.details["outcomeMayHaveCommitted"])
            assertEquals(InappifyPurchaseAttemptStatus.FAILED, f.attempt(client).status)
            f.purchase = { it.beforeStoreLaunch!!.invoke(); StoreBillingResult.Cancelled }
            assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, (f.buy(client, "retry") as InappifyResult.Failure).error.code)
        }
    }

    @Test fun restartResolvesOnlyPreparationAndNeverADispatchedOrLegacyAttempt() = runBlocking {
        for (phase in listOf("preparing", "dispatched", "legacy", "bazaar")) {
            val f = Fixture(if (phase == "bazaar") InappifyMarket.BAZAAR else InappifyMarket.MYKET)
            val holding = CompletableDeferred<Unit>()
            f.purchase = { request ->
                if (phase == "dispatched") request.beforeStoreLaunch!!.invoke()
                holding.complete(Unit)
                awaitCancellation()
            }
            val original = f.client()
            f.configure(original)
            val call = async { f.buy(original) }
            holding.await()
            original.close()
            withTimeout(2000) { call.join() }
            if (phase == "legacy") {
                val document = jsonObject(f.storage.history)
                document.getAsJsonArray("attempts").single().asJsonObject.remove("error")
                f.storage.history = document.toString()
            }
            f.client().use { restarted ->
                f.configure(restarted)
                val attempt = f.attempt(restarted)
                assertEquals(phase, if (phase == "preparing") InappifyPurchaseAttemptStatus.FAILED else InappifyPurchaseAttemptStatus.PENDING, attempt.status)
                if (phase == "preparing") assertEquals(false, attempt.error!!.details["outcomeMayHaveCommitted"])
            }
        }
    }

    @Test fun timeoutAfterDispatchRemainsUncertain() = runBlocking {
        val f = Fixture()
        f.purchase = { it.beforeStoreLaunch!!.invoke(); StoreBillingResult.Failure(StoreBillingError(StoreBillingErrorCode.OPERATION_TIMEOUT, "safe", true)) }
        f.client().use { client ->
            f.configure(client)
            assertEquals(true, (f.buy(client) as InappifyResult.Failure).error.details["outcomeMayHaveCommitted"])
            assertEquals(InappifyPurchaseAttemptStatus.PENDING, f.attempt(client).status)
        }
    }

    @Test fun failedDispatchPersistenceCannotOpenTheStore() = runBlocking {
        val f = Fixture()
        var opened = false
        f.storage.rejectDispatch = true
        f.purchase = { it.beforeStoreLaunch!!.invoke(); opened = true; StoreBillingResult.Cancelled }
        f.client().use { client ->
            f.configure(client)
            assertTrue(f.buy(client) is InappifyResult.Failure)
            assertFalse(opened)
            assertEquals(InappifyPurchaseAttemptStatus.FAILED, f.attempt(client).status)
        }
    }

    @Test fun lateLaunchCannotReviveATerminalPreparationTimeout() = runBlocking {
        val f = Fixture()
        var lateLaunch: (suspend () -> Unit)? = null
        f.purchase = { lateLaunch = it.beforeStoreLaunch; StoreBillingResult.Failure(
            StoreBillingError(StoreBillingErrorCode.OPERATION_TIMEOUT, "safe", true)) }
        f.client().use { client ->
            f.configure(client)
            f.buy(client)
            assertTrue(runCatching { lateLaunch!!.invoke() }.isFailure)
            assertEquals(InappifyPurchaseAttemptStatus.FAILED, f.attempt(client).status)
        }
    }

    @Test fun confirmedCancellationAfterDispatchIsTerminal() = runBlocking {
        val f = Fixture()
        f.purchase = { it.beforeStoreLaunch!!.invoke(); StoreBillingResult.Cancelled }
        f.client().use { client ->
            f.configure(client)
            assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, (f.buy(client) as InappifyResult.Failure).error.code)
            assertEquals(InappifyPurchaseAttemptStatus.FAILED, f.attempt(client).status)
        }
    }
}
