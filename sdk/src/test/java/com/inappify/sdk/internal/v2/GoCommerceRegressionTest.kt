package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.billing.StoreConsumeResult
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GoCommerceRegressionTest(private val nativeMarket: InappifyMarket) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun markets(): List<Array<InappifyMarket>> = listOf(arrayOf(InappifyMarket.BAZAAR), arrayOf(InappifyMarket.MYKET))
    }

    private val signing = SigningFixture()
    private val storage = CommerceStore()
    private val sdkTransport = DiagnosticTransport()
    private val commerceTransport = DiagnosticTransport()
    private val owned = mutableMapOf<StoreProductType, List<StorePurchase>>()
    private var freshPurchase: ((StorePurchaseRequest) -> StoreBillingResult)? = null
    private var billingCalls = 0
    private var consumeCalls = 0
    private var billingKey: String? = null
    private var platform = if (nativeMarket == InappifyMarket.MYKET) 11 else 10
    private val metadata = AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }
    private fun client(configuration: InappifyV2Configuration = signing.config): GoV2Client {
        sdkTransport.handler = { request -> when (request.path) {
            "configure", "customerInfo" -> sdkTransport.response(signing.envelope().apply {
                addProperty("storePlatform", platform)
                addProperty("storeInfo", "fixture-public-rsa")
            })
            "offerings" -> sdkTransport.response(jsonObject("""{"status":true,"offerings":[
                {"identifier":"first","packages":[{"identifier":"first-package","product":{"identifier":"product"}}]},
                {"identifier":"original","packages":[{"identifier":"package","product":{"identifier":"product"}}]}]}"""))
            else -> error("Unexpected SDK request ${request.path}")
        } }
        commerceTransport.handler = { request -> commerceTransport.response(jsonObject(
            if (request.isStoreTrialPurchase()) """{"status":true,"data":{"purchaseStatus":"NEEDSTORE"}}"""
            else if (request.path == "store/purchases") """{"status":true,"data":{"purchase":{"status":"COMPLETED"}}}"""
            else """{"status":true,"data":{"status":"COMPLETED"}}""")) }
        return GoV2Client(configuration, GoApi(sdkTransport, { signing.now }, {}), storage, metadata,
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            commerceApi = GoApi(commerceTransport, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { market, key ->
                assertEquals(nativeMarket, market)
                billingCalls++
                billingKey = key
                object : StoreBillingAdapter {
                    override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult =
                        freshPurchase?.invoke(request) ?: error("A recovery test must never start Billing UI")
                    override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult =
                        StorePurchaseQueryResult.Success(owned[productType].orEmpty()).also {
                            if (nativeMarket == InappifyMarket.MYKET) assertEquals(StoreProductType.IN_APP, productType)
                        }
                    override suspend fun consume(purchase: StorePurchase): StoreConsumeResult {
                        consumeCalls++
                        return StoreConsumeResult.Success
                    }
                    override fun close() = Unit
                }
            })
    }
    @org.junit.Test fun freshNativePurchaseUsesSelectedAdapterAndCommerceSession() = runBlocking {
        freshPurchase = { request ->
            assertEquals(StoreProductType.IN_APP, request.productType)
            StoreBillingResult.Success(StorePurchase("fixture-order", "fixture-token", request.developerPayload!!,
                "com.example.mobile", "product", signing.now, "fixture-json", "fixture-signature"))
        }
        client().use { sdk ->
            configured(sdk)
            val result = sdk.purchase(android.app.Activity(), InappifyPurchaseRequest("product", "original",
                market = nativeMarket, productType = InappifyProductType.NON_CONSUMABLE,
                idempotencyKey = "fresh-attempt"))
            assertTrue(result.toString(), result is InappifyResult.Success)
            assertEquals(nativeMarket, (result as InappifyResult.Success).data.market)
            assertEquals(1, billingCalls)
            assertEquals("store/purchases", commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single().path)
            assertTrue(storage.operations.isEmpty()) // Terminal non-consumables leave no pending checkpoint.
        }
    }

    private fun installTypedCatalog(type: String) {
        val original = sdkTransport.handler
        sdkTransport.handler = { request -> if (request.path == "offerings")
            sdkTransport.response(jsonObject("""{"status":true,"offerings":[
                {"identifier":"first","packages":[{"identifier":"package","product":{
                    "identifier":"product","productType":"$type"}}]},
                {"identifier":"original","packages":[{"identifier":"package","product":{
                    "identifier":"product","productType":"$type"}}]}]}""")) else original(request) }
    }

    private fun successfulReceipt(request: StorePurchaseRequest): StoreBillingResult =
        StoreBillingResult.Success(StorePurchase("fixture-order", "fixture-token", request.developerPayload!!,
            "com.example.mobile", "product", signing.now, "fixture-json", "fixture-signature"))

    @Test fun packagePurchaseUsesExactParentAndTheExistingStoreReceiptContract() = runBlocking {
        freshPurchase = { request ->
            assertEquals(StoreProductType.IN_APP, request.productType)
            val payload = if (nativeMarket == InappifyMarket.MYKET) MyketPurchasePayload.decode(request.developerPayload, "product")
                else jsonObject(request.developerPayload!!)
            assertEquals("original", payload.string("offeringIdentifier"))
            assertEquals("package", payload.string("nativePackageIdentifier"))
            assertEquals("NON_CONSUMABLE", payload.string("productType"))
            successfulReceipt(request)
        }
        client().use { sdk ->
            installTypedCatalog("NON_CONSUMABLE")
            configured(sdk)
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val result = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected)
                .idempotencyKey("package-attempt").build())
            assertTrue(result.toString(), result is InappifyResult.Success)
            assertEquals(nativeMarket, (result as InappifyResult.Success).data.market)
            assertEquals("package-attempt", result.data.attemptId)
            assertEquals("fixture-public-rsa", billingKey)
            val submitted = commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single { it.path == "store/purchases" }
            assertEquals("store/purchases", submitted.path)
            assertEquals("Bearer go-session-token", submitted.headers["Authorization"])
            val body = jsonObject(submitted.jsonBody)
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "operation", "purchase"), body.keySet())
            assertEquals("original", body.string("offeringIdentifier"))
            assertEquals("fixture-token", body.getAsJsonObject("purchase").string("token"))
            assertEquals(1, billingCalls)
            assertTrue(storage.operations.isEmpty())
        }
    }

    @Test fun packageSubscriptionUsesStoreSpecificBillingAndMyketConsumesAfterServerGrant() = runBlocking {
        freshPurchase = { request ->
            assertEquals(if (nativeMarket == InappifyMarket.MYKET) StoreProductType.IN_APP else StoreProductType.SUBSCRIPTION, request.productType)
            val payload = if (nativeMarket == InappifyMarket.MYKET) MyketPurchasePayload.decode(request.developerPayload, "product")
                else jsonObject(request.developerPayload!!)
            assertEquals("SUBSCRIPTION", payload.string("productType"))
            assertEquals(0, consumeCalls)
            successfulReceipt(request)
        }
        client().use { sdk ->
            authorizeSubscriptionConsumption()
            installTypedCatalog("SUBSCRIPTION")
            configured(sdk)
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val result = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected).build())
            assertTrue(result.toString(), result is InappifyResult.Success)
            assertEquals(if (nativeMarket == InappifyMarket.MYKET) 1 else 0, consumeCalls)
            assertEquals("store/purchases", commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single().path)
            assertTrue(storage.operations.isEmpty())
        }
    }

    @Test fun packageConsumableRetainsDeliveryCheckpointUntilHostGrant() = runBlocking {
        freshPurchase = ::successfulReceipt
        client().use { sdk ->
            installTypedCatalog("CONSUMABLE")
            configured(sdk)
            commerceTransport.handler = { request -> commerceTransport.response(jsonObject(
                if (request.isStoreTrialPurchase()) """{"status":true,"data":{"purchaseStatus":"NEEDSTORE"}}"""
                else """{"status":true,"data":{"purchase":{"status":"DELIVERY_REQUIRED","deliveryId":7}}}""")) }
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val result = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected)
                .idempotencyKey("delivery-attempt").build()) as InappifyResult.Success
            assertEquals(7L, result.data.deliveryId)
            assertNull(result.data.purchaseStatus)
            assertEquals(PendingStoreProductType.CONSUMABLE, storage.operations.single().productType)
            assertEquals(PendingStoreOperationPhase.DELIVERY_REQUIRED, storage.operations.single().phase)
            assertFalse(storage.operations.single().deliveryAcknowledged)
            assertEquals(listOf("store/purchases"), commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.map { it.path })
        }
    }

    @Test fun packagePurchaseCancellationDoesNotRegisterPaymentOrRewriteCache() = runBlocking {
        freshPurchase = { StoreBillingResult.Cancelled }
        client().use { sdk ->
            installTypedCatalog("NON_CONSUMABLE")
            configured(sdk)
            val before = storage.value
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val result = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected).build())
                as InappifyResult.Failure
            assertEquals(InappifyErrorCode.PURCHASE_CANCELLED, result.error.code)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertTrue(storage.operations.isEmpty())
            assertEquals(before?.customerInfoJson, storage.value?.customerInfoJson)
        }
    }

    @Test fun incompletePackageMetadataNeverOpensStoreOrChangesExistingCache() = runBlocking {
        client().use { sdk ->
            configured(sdk) // Existing catalogs without productType remain readable.
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val before = storage.value
            val count = sdkTransport.requests.size
            val result = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected).build())
                as InappifyResult.Failure
            assertEquals("PRODUCT_TYPE_REQUIRED", result.error.details["serverCode"])
            assertSame(sdk.snapshot, result.snapshot)
            assertSame(before, storage.value)
            assertEquals(count, sdkTransport.requests.size)
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }

    @Test fun packageParamsResumePaidAttemptWithoutActivityAfterCatalogInvalidation() = runBlocking {
        client().use { sdk ->
            installTypedCatalog("CONSUMABLE")
            configured(sdk)
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val params = InappifyPurchaseParams.Builder(selected).idempotencyKey("attempt-1").build()
            storage.upsertPendingStoreOperation(pending())
            assertTrue(sdk.enqueueAttributes(mapOf("audience" to "changed")) is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val result = sdk.purchase(params) as InappifyResult.Success
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED, result.data.storePurchaseStatus)
            assertEquals("attempt-1", result.data.attemptId)
            assertEquals(0, billingCalls)
        }
    }

    @Test fun packageParamsPreserveNativeHostAndDirectOnlyOptionValidation() = runBlocking {
        client().use { sdk ->
            installTypedCatalog("NON_CONSUMABLE")
            configured(sdk)
            val selected = sdk.snapshot.offerings!!["original"]!!.availablePackages.single()
            val missingHost = sdk.purchase(InappifyPurchaseParams.Builder(selected).build()) as InappifyResult.Failure
            assertEquals("STORE_UI_HOST_REQUIRED", missingHost.error.details["serverCode"])
            val attribution = sdk.purchase(InappifyPurchaseParams.Builder(android.app.Activity(), selected)
                .paywallAttribution(4, 2).build()) as InappifyResult.Failure
            assertEquals("PURCHASE_OVERRIDE_NOT_ALLOWED", attribution.error.details["serverCode"])
            // Retain the old request's rejection contract for existing SDK consumers.
            val legacyCrypto = sdk.purchase(android.app.Activity(), InappifyPurchaseRequest("product", "original",
                isCrypto = true, productType = InappifyProductType.NON_CONSUMABLE)) as InappifyResult.Failure
            assertEquals("PURCHASE_OVERRIDE_NOT_ALLOWED", legacyCrypto.error.details["serverCode"])
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }

    private suspend fun configured(sdk: GoV2Client) {
        assertTrue(sdk.configure(InappifyOptions("public-fixture-key", signing.subject)) is InappifyResult.Success)
        assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun pending(type: PendingStoreProductType = PendingStoreProductType.CONSUMABLE,
        phase: PendingStoreOperationPhase = PendingStoreOperationPhase.DELIVERY_REQUIRED): PendingStoreOperation =
        PendingStoreOperation("attempt-1", PendingStoreOperationType.PURCHASE, nativeMarket.storeId, "old-session-token",
            digest(signing.subject), storeFingerprint(), "com.example.mobile", 12,
            "product", "original", type, PendingStorePurchaseEvidence("fixture-store-token"),
            phase, deliveryId = if (phase == PendingStoreOperationPhase.DELIVERY_REQUIRED) 7 else null,
            createdAtEpochMillis = signing.now)
    private fun receipt(type: String = "NON_CONSUMABLE", offering: String = "original",
        product: String = "product", attempt: String = "attempt-1"): StorePurchase {
        val binding = digest("${storeFingerprint()}:${digest(signing.subject)}")
        val payload = JsonObject().apply {
            addProperty("productIdentifier", product); addProperty("offeringIdentifier", offering)
            addProperty("nativePackageIdentifier", "package"); addProperty("productType", type)
            addProperty("recoveryBinding", binding); addProperty("attemptId", attempt)
        }
        return StorePurchase("fixture-order", "fixture-store-token", payload.toString(), "com.example.mobile",
            "product", signing.now, "fixture-original", "fixture-signature")
    }
    private fun storeFingerprint() = goStoreFingerprint(storage.value!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl)
    @Test fun cannotReplayReceiptFromTheOtherStore() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending().copy(store = if (nativeMarket == InappifyMarket.MYKET) "bazar" else "myket"))
            val retry = sdk.purchase(InappifyPurchaseRequest("product", "original", idempotencyKey = "attempt-1",
                productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", retry.error.details["serverCode"])
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            assertTrue((sdk.syncStorePurchases() as InappifyResult.Success).data.isEmpty())
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertEquals(1, storage.operations.size)
        }
    }
    @Test fun myketSubscriptionAgainstOldBackendRetainsReceiptWithoutConsuming() = runBlocking {
        if (nativeMarket != InappifyMarket.MYKET) return@runBlocking
        freshPurchase = ::successfulReceipt
        client().use { sdk ->
            configured(sdk)
            val result = sdk.purchase(android.app.Activity(), InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.SUBSCRIPTION)) as InappifyResult.Failure
            assertEquals(InappifyErrorCode.MALFORMED_RESPONSE, result.error.code)
            assertEquals(0, consumeCalls)
            assertEquals(1, commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.size)
            assertEquals(1, storage.operations.size)
        }
    }

    private fun authorizeSubscriptionConsumption() {
        commerceTransport.handler = { request -> commerceTransport.response(jsonObject(
            if (request.isStoreTrialPurchase()) """{"status":true,"data":{"purchaseStatus":"NEEDSTORE"}}"""
            else """{"status":true,"data":{"purchase":{"status":"COMPLETED","paymentId":11,"eventId":12,
                "requiresConsumption":${nativeMarket == InappifyMarket.MYKET}}}}""")) }
    }

    @Test fun myketOwnedSubscriptionRecoversFromInAppInventoryWithoutHostDelivery() = runBlocking {
        if (nativeMarket != InappifyMarket.MYKET) return@runBlocking
        client().use { sdk ->
            configured(sdk)
            authorizeSubscriptionConsumption()
            owned[StoreProductType.IN_APP] = listOf(receipt(type = "SUBSCRIPTION"))
            // Inventory's adapter is released before a pending consumption retry.
            sdk.syncStorePurchases()
            owned.clear()
            storage.operations.toList().forEach { storage.upsertPendingStoreOperation(it.copy(nextRetryAtEpochMillis = null)) }
            sdk.syncStorePurchases()
            assertEquals(1, consumeCalls)
            assertTrue(storage.operations.isEmpty())
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.all { it.path == "store/purchases" })
        }
    }

    @Test fun changingCommerceEndpointKeepsSessionCacheButCannotReplayPaidEvidence() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            owned[StoreProductType.IN_APP] = listOf(receipt(type = "CONSUMABLE"))
        }
        val originalCacheFingerprint = storage.value!!.apiKeyFingerprint
        val originalOperation = storage.operations.single()
        val otherCommerce = InappifyV2Configuration(sdkApiBaseUrl = signing.config.sdkApiBaseUrl,
            issuer = signing.config.issuer, appId = signing.config.appId, projectId = signing.config.projectId,
            pinnedSigningKeys = signing.config.pinnedSigningKeys, paymentHosts = signing.config.paymentHosts,
            commerceApiBaseUrl = "https://other-commerce.example/app/v2/")
        client(otherCommerce).use { sdk ->
            configured(sdk)
            val retry = sdk.purchase(InappifyPurchaseRequest("product", "original",
                idempotencyKey = "attempt-1", productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Failure
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", retry.error.details["serverCode"])
            val confirm = sdk.confirmDelivery(7) as InappifyResult.Failure
            assertEquals("DELIVERY_NOT_FOUND", confirm.error.details["serverCode"])
            val sync = sdk.syncStorePurchases() as InappifyResult.Success
            assertTrue(sync.data.isEmpty())
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertEquals(originalOperation, storage.operations.single())
            assertEquals(originalCacheFingerprint, storage.value!!.apiKeyFingerprint)
        }
    }
    @Test fun existingBazaarRequestAcceptsExplicitMarketAndServerRsa() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.CONSUMABLE, market = nativeMarket,
                idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Success)
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                (result as InappifyResult.Success).data.storePurchaseStatus)
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }
    @Test fun serverBillingKeySurvivesCachedRestartWithoutConfigureRequest() = runBlocking {
        client().use { sdk -> configured(sdk) }
        val savedSession = jsonObject(storage.value!!.customerInfoJson!!).getAsJsonObject("session")
        assertEquals("fixture-public-rsa", savedSession.string("storeInfo"))
        sdkTransport.requests.clear()

        client().use { restarted ->
            val handler = sdkTransport.handler
            sdkTransport.handler = { request ->
                check(request.path != "configure") { "The valid cached session must be reused." }
                handler(request)
            }
            assertTrue(restarted.configure(InappifyOptions("public-fixture-key", signing.subject))
                is InappifyResult.Success)
            assertTrue(sdkTransport.requests.isEmpty())
            assertTrue(restarted.syncStorePurchases() is InappifyResult.Success)
            assertEquals("fixture-public-rsa", billingKey)
            assertTrue(sdkTransport.requests.none { it.path == "configure" })
        }
    }
    @Test fun retryCannotChangeProductTypeOfAnExistingAttempt() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.NON_CONSUMABLE, idempotencyKey = "attempt-1"))
            assertEquals("PURCHASE_ATTEMPT_CONFLICT", (result as InappifyResult.Failure).error.details["serverCode"])
            assertEquals(0, billingCalls)
            assertEquals(PendingStoreProductType.CONSUMABLE, storage.operations.single().productType)
        }
    }
    @Test fun paidAttemptResumesAfterAttributeInvalidationAndLegacyTypeWasClassified() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            assertTrue(sdk.enqueueAttributes(mapOf("audience" to "changed"))
                is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original", idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Success)
            assertEquals(InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                (result as InappifyResult.Success).data.storePurchaseStatus)
            assertEquals(0, billingCalls)
        }
    }
    @Test fun unsupportedDirectDiscountCannotSilentlyCreateAFullPriceCheckout() = runBlocking {
        platform = 2
        client().use { sdk ->
            configured(sdk)
            assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "original", discount = 20)) is InappifyResult.Failure)
            assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "original", discountCode = "fixture-code")) is InappifyResult.Failure)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }
    @Test fun disabledNetworkDoesNotQueryStoreOrAcknowledgeDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setNetworkEnabled(false)
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Failure)
            assertTrue(sdk.confirmDelivery(7) is InappifyResult.Failure)
            assertEquals(0, billingCalls)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertFalse(storage.operations.single().deliveryAcknowledged)
        }
    }
    @Test fun transportFailureRetainsAttemptAndCannotBeReportedAsPurchaseSuccess() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending(phase = PendingStoreOperationPhase.REGISTERING))
            commerceTransport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            val result = sdk.purchase(InappifyPurchaseRequest("product", "original",
                productType = InappifyProductType.CONSUMABLE, idempotencyKey = "attempt-1"))
            assertTrue(result is InappifyResult.Failure)
            assertTrue((result as InappifyResult.Failure).error.isRetryable)
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals("attempt-1", storage.operations.single().id)
            assertEquals(1, commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.size)
            assertEquals("Bearer go-session-token", commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single().headers["Authorization"])
        }
    }
    @Test fun ownedRecoveryUsesOriginalOfferingAndCurrentTokenWithoutLegacyCredential() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt())
            val result = sdk.syncStorePurchases()
            assertTrue(result is InappifyResult.Success)
            val purchase = (result as InappifyResult.Success).data.single()
            assertEquals("original", purchase.offeringIdentifier)
            assertEquals("attempt-1", purchase.attemptId)
            val request = commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single()
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertEquals("original", jsonObject(request.jsonBody).string("offeringIdentifier"))
            assertFalse(jsonObject(request.jsonBody).has("apikey"))
            assertEquals("fixture-public-rsa", billingKey)
            assertTrue(storage.operations.isEmpty())
        }
    }
    @Test fun ownedRecoveryCannotInventAnotherOfferingOrChangeReceiptProduct() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            for (invalid in listOf(receipt(offering = ""), receipt(product = "other"))) {
                owned[StoreProductType.IN_APP] = listOf(invalid)
                assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            }
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertTrue(storage.operations.isEmpty())
        }
    }
    @Test fun validatedOwnedReceiptCanRecoverAfterCatalogWasInvalidated() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt(offering = "previous-offering"))
            assertTrue(sdk.enqueueAttributes(mapOf("audience" to "changed"))
                is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertEquals("previous-offering", jsonObject(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.single().jsonBody)
                .string("offeringIdentifier"))
        }
    }
    @Test fun subscriptionInventoryCannotBeReclassifiedAsConsumable() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.SUBSCRIPTION] = listOf(receipt(type = "CONSUMABLE"))
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }
    @Test fun ownedReceiptsSharingAttemptCannotOverwriteOneAnothersPendingDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val first = receipt(type = "CONSUMABLE")
            val other = StorePurchase("other-order", "other-token", first.developerPayload,
                first.packageName, first.productIdentifier, first.purchaseTimeMillis, "other-original", "other-signature")
            owned[StoreProductType.IN_APP] = listOf(first, other)
            commerceTransport.handler = { commerceTransport.response(jsonObject(
                """{"status":true,"data":{"purchase":{"status":"DELIVERY_REQUIRED","deliveryId":7}}}""")) }
            assertTrue(sdk.syncStorePurchases() is InappifyResult.Success)
            assertEquals(1, commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.size)
            assertEquals("fixture-store-token", storage.operations.single().evidence.purchaseToken)
        }
    }
    @Test fun explicitRestoreSkipsOwnedConsumables() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            owned[StoreProductType.IN_APP] = listOf(receipt(type = "CONSUMABLE"))
            assertTrue(sdk.restoreStorePurchases() is InappifyResult.Success)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }
    @Test fun deliveryHandlerCancellationNeverAcknowledgesOrConsumes() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setConsumableDeliveryHandler { throw CancellationException("fixture cancellation") }
            try { sdk.syncPendingConsumables(); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertFalse(storage.operations.single().deliveryAcknowledged)
        }
    }
    @Test fun failedAcknowledgementAfterHostDeliveryCannotBecomeSyncSuccess() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending())
            sdk.setConsumableDeliveryHandler { InappifyDeliveryResult.DELIVERED }
            commerceTransport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            val result = sdk.syncPendingConsumables()
            assertTrue(result is InappifyResult.Failure)
            assertEquals(true, (result as InappifyResult.Failure).error.details["outcomeMayHaveCommitted"])
            assertTrue(storage.operations.single().deliveryAcknowledged)
            assertEquals(PendingStoreOperationPhase.DELIVERY_CONFIRMING, storage.operations.single().phase)
        }
    }
    @Test fun deferredVerificationCannotStarveAnotherPaidDelivery() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending(phase = PendingStoreOperationPhase.REGISTERING)
                .copy(id = "deferred-attempt", nextRetryAtEpochMillis = Long.MAX_VALUE))
            storage.upsertPendingStoreOperation(pending())
            var offeredDelivery: Long? = null
            sdk.setConsumableDeliveryHandler { delivery ->
                offeredDelivery = delivery.deliveryId
                InappifyDeliveryResult.RETRY_LATER
            }
            assertTrue(sdk.syncPendingConsumables() is InappifyResult.Failure)
            assertEquals(7L, offeredDelivery)
            assertEquals(2, storage.operations.size)
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
        }
    }
    @Test fun completedReconciliationsAreCountedInConsumableSync() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending().copy(
                phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                deliveryAcknowledged = true, consumeResult = PendingStoreConsumeResult.SUCCEEDED))
            val result = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(1, result.data.discoveredCount)
            assertEquals(1, result.data.completedCount)
        }
    }
    @Test fun httpListenerRegistersAndUnregistersBothTransports() {
        client().use { sdk ->
            val registration = sdk.addHttpTraceListener { }
            assertEquals(1, sdkTransport.listeners.size)
            assertEquals(1, commerceTransport.listeners.size)
            registration.close()
            assertTrue(sdkTransport.listeners.isEmpty())
            assertTrue(commerceTransport.listeners.isEmpty())
        }
    }
    @Test fun storeVerificationAndConsumeReportRenewExpiredSessionWithoutNewBillingPurchase() = runBlocking {
        for (phase in listOf(PendingStoreOperationPhase.VERIFYING, PendingStoreOperationPhase.CONSUME_RESULT_REPORTING)) {
            storage.value = null
            client().use { sdk ->
                configured(sdk)
                sdkTransport.requests.clear(); commerceTransport.requests.clear()
                storage.upsertPendingStoreOperation(pending(type = if (phase == PendingStoreOperationPhase.VERIFYING) PendingStoreProductType.NON_CONSUMABLE else PendingStoreProductType.CONSUMABLE).copy(phase = phase,
                    verificationRequestId = if (phase == PendingStoreOperationPhase.VERIFYING) 9 else null,
                    deliveryAcknowledged = phase == PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                    consumeResult = if (phase == PendingStoreOperationPhase.CONSUME_RESULT_REPORTING) PendingStoreConsumeResult.SUCCEEDED else null))
                val base = sdkTransport.handler
                sdkTransport.handler = { request -> if (request.path == "configure") sdkTransport.response(signing.envelope().apply {
                    addProperty("storePlatform", platform); addProperty("sessionToken", "renewed-session"); addProperty("storeInfo", "fixture-public-rsa")
                }) else base(request) }
                commerceTransport.handler = {
                    if (commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.size == 1) TransportResult.Response(HttpResponse(401,
                        """{"status":false,"code":"SESSION_EXPIRED"}""", null))
                    else commerceTransport.response(jsonObject("""{"status":true,"data":{"status":"COMPLETED"}}"""))
                }
                assertTrue(sdk.syncPendingConsumables() is InappifyResult.Success)
                assertEquals(1, sdkTransport.requests.count { it.path == "configure" })
                assertEquals(listOf("Bearer go-session-token", "Bearer renewed-session"), commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.map { it.headers["Authorization"] })
                assertEquals(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }[0].jsonBody, commerceTransport.requests.filter { !it.isStoreTrialPurchase() }[1].jsonBody)
                assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.all { if (phase == PendingStoreOperationPhase.VERIFYING) it.path == "store/verifications/9/status" else it.path == "store/deliveries/7/consume-result" })
                assertTrue(storage.operations.isEmpty())
            }
        }
    }
    @Test fun verificationRetryAfterPreventsImmediatePollAfterRestart() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            storage.upsertPendingStoreOperation(pending().copy(phase = PendingStoreOperationPhase.VERIFYING, verificationRequestId = 9))
            commerceTransport.handler = { TransportResult.Response(HttpResponse(200,
                """{"status":true,"data":{"status":"PROCESSING","verificationRequestId":9,"retryAfter":1}}""", null,
                headers = mapOf("Retry-After" to "120"))) }
            sdk.syncPendingConsumables()
            assertEquals(1, commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.size)
            assertTrue(requireNotNull(storage.operations.single().nextRetryAtEpochMillis) >= signing.now + 120000)
        }
        commerceTransport.requests.clear()
        client().use { sdk ->
            configured(sdk); sdk.syncPendingConsumables()
            assertTrue(commerceTransport.requests.filter { !it.isStoreTrialPurchase() }.isEmpty())
            assertEquals(1, storage.operations.size)
        }
    }
    private class CommerceStore : SessionStateStore {
        var value: PersistedSession? = null
        val operations = mutableListOf<PendingStoreOperation>()
        override suspend fun load(): PersistedSession? = value
        override suspend fun save(session: PersistedSession): Boolean { value = session; return true }
        override suspend fun clear(): Boolean { value = null; return true }
        override suspend fun loadPendingStoreOperations() = operations.toList()
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean {
            operations.removeAll { it.id == operation.id }; operations += operation; return true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean =
            true.also { operations.removeAll { it.id == operationId } }
    }
    private class DiagnosticTransport : HttpTransport, HttpDiagnosticSource {
        val requests = mutableListOf<HttpRequest>()
        val listeners = mutableSetOf<InappifyHttpTraceListener>()
        lateinit var handler: suspend (HttpRequest) -> TransportResult
        override suspend fun execute(request: HttpRequest): TransportResult { requests += request; return handler(request) }
        override fun close() = Unit
        override fun addHttpTraceListener(listener: InappifyHttpTraceListener): InappifyListenerRegistration {
            listeners += listener
            return InappifyListenerRegistration.create(Runnable { listeners -= listener })
        }
        fun response(body: JsonObject): TransportResult = TransportResult.Response(HttpResponse(200, body.toString(), null))
    }
}
