package com.inappify.sdk

import android.app.Activity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicApiTest {

    @Test
    fun offeringsRetainsV1JvmConstructors() {
        val type = InappifyOfferings::class.java
        assertEquals(InappifyOfferings(), type.getConstructor().newInstance())
        type.getConstructor(List::class.java, List::class.java, java.lang.Long::class.java, String::class.java)
        type.getConstructor(List::class.java, List::class.java, java.lang.Long::class.java, String::class.java,
            Int::class.javaPrimitiveType, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))
    }

    @Test
    fun optionsToString_redactsSensitiveValues() {
        val rendered = InappifyOptions(
            apiKey = "public-api-key",
            appUserIdentifier = "09120000000",
            market = InappifyMarket.BAZAAR,
            marketKey = "market-public-key",
            country = "IR",
            appVersion = "1.0.0",
        ).toString()

        assertFalse(rendered.contains("public-api-key"))
        assertFalse(rendered.contains("09120000000"))
        assertFalse(rendered.contains("market-public-key"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun loginRequestToString_redactsSensitiveValues() {
        val rendered = InappifyLoginRequest(
            apiKey = "public-api-key",
            appUserIdentifier = "09120000000",
        ).toString()

        assertFalse(rendered.contains("public-api-key"))
        assertFalse(rendered.contains("09120000000"))
    }

    @Test
    fun initialSnapshot_isUnconfiguredAndTokenFree() {
        val snapshot = InappifySnapshot.initial(sdkVersion = "test")

        assertFalse(snapshot.isConfigured)
        assertFalse(snapshot.isAuthenticated)
        assertEquals(0L, snapshot.revision)
        assertNull(snapshot.appUserIdentifier)
        assertNull(snapshot.market)
        assertNull(snapshot.customerInfo)
        assertNull(snapshot.offerings)
        assertFalse(snapshot.failedToLoadCustomerInfo)
        assertFalse(snapshot.failedToLoadOfferings)
        assertFalse(snapshot.toString().contains("token", ignoreCase = true))
        assertFalse(snapshot.toString().contains("storePlatform"))
        assertEquals(
            "InappifySnapshot(revision=0, isConfigured=false, " +
                "isAuthenticated=false, appUserIdentifier=null, market=null, " +
                "country=null, appVersion=null, sdkVersion=test, storeInfo=null, " +
                "forceVersion=1, appId=null, hasCustomerInfo=false, " +
                "offeringsCount=0, failedToLoadCustomerInfo=false, " +
                "failedToLoadOfferings=false)",
            snapshot.toString(),
        )
    }

    @Test
    fun result_preservesAuthoritativeSnapshot() {
        val snapshot = InappifySnapshot.initial(sdkVersion = "test")
        val success = InappifyResult.Success(Unit, snapshot)
        val failure = InappifyResult.Failure(
            error = InappifyError(
                code = InappifyErrorCode.NETWORK,
                message = "Network request failed.",
                isRetryable = true,
            ),
        )

        assertSame(snapshot, success.snapshot)
        assertEquals(Unit, success.data)
        assertNull(failure.snapshot)
        assertEquals(InappifyErrorCode.NETWORK, failure.error.code)
    }

    @Test
    fun error_copiesDiagnosticDetails() {
        val source = mutableMapOf<String, Any?>("status" to 503)
        val error = InappifyError(
            code = InappifyErrorCode.NETWORK,
            message = "Network request failed.",
            details = source,
        )

        source["status"] = 200

        assertEquals(503, error.details["status"])
    }

    @Test
    fun purchaseRequest_isImmutableByValueAndRedactsIdentifiers() {
        val first = InappifyPurchaseRequest(
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            packageIdentifier = "annual-package",
            discountCode = "WELCOME15",
            country = "IR",
            appVersion = "4.5.6",
            apiKey = "purchase-api-key",
            discount = 15,
            isCrypto = false,
            market = InappifyMarket.BAZAAR,
            marketKey = "bazaar-public-key",
            isLostPurchase = true,
            lostPurchaseToken = "lost-purchase-token",
            lostPurchaseTime = 1_725_000_000_000L,
            idempotencyKey = "retry-correlation-id",
            dynamicPriceToken = "dynamic-price-token",
        )
        val second = InappifyPurchaseRequest(
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            packageIdentifier = "annual-package",
            discountCode = "WELCOME15",
            country = "IR",
            appVersion = "4.5.6",
            apiKey = "purchase-api-key",
            discount = 15,
            isCrypto = false,
            market = InappifyMarket.BAZAAR,
            marketKey = "bazaar-public-key",
            isLostPurchase = true,
            lostPurchaseToken = "lost-purchase-token",
            lostPurchaseTime = 1_725_000_000_000L,
            idempotencyKey = "retry-correlation-id",
            dynamicPriceToken = "dynamic-price-token",
        )
        val rendered = first.toString()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(rendered.contains("annual-product"))
        assertFalse(rendered.contains("premium-offering"))
        assertFalse(rendered.contains("annual-package"))
        assertFalse(rendered.contains("WELCOME15"))
        assertFalse(rendered.contains("purchase-api-key"))
        assertFalse(rendered.contains("bazaar-public-key"))
        assertFalse(rendered.contains("lost-purchase-token"))
        assertFalse(rendered.contains("1725000000000"))
        assertFalse(rendered.contains("retry-correlation-id"))
        assertFalse(rendered.contains("dynamic-price-token"))
        assertTrue(rendered.contains("discount=15"))
        assertTrue(rendered.contains("market=BAZAAR"))
        assertTrue(rendered.contains("isLostPurchase=true"))
        assertNull(first.productType)
    }

    @Test
    fun purchaseRequest_explicitProductTypeIsAdditiveToTheV1Constructor() {
        val subscription = InappifyPurchaseRequest(
            productIdentifier = "premium-monthly",
            offeringIdentifier = "main",
            productType = InappifyProductType.SUBSCRIPTION,
            market = InappifyMarket.BAZAAR,
        )
        val legacy = InappifyPurchaseRequest(
            productIdentifier = "premium-monthly",
            offeringIdentifier = "main",
            market = InappifyMarket.BAZAAR,
        )

        assertEquals(InappifyProductType.SUBSCRIPTION, subscription.productType)
        assertNull(legacy.productType)
        assertFalse(subscription.toString().contains("premium-monthly"))
    }

    @Test
    fun consumableDeliveryContract_isImmutableValidatedAndRedacted() {
        val source = mutableListOf(
            InappifyConsumableDelivery(
                deliveryId = 91L,
                productIdentifier = "coins-500",
                transactionIdentifier = "transaction-secret",
                source = InappifyDeliverySource.DIRECT,
            ),
        )
        val result = InappifyConsumableSyncResult(
            discoveredCount = 1,
            completedCount = 0,
            pendingDeliveries = source,
        )
        source.clear()

        assertEquals(1, result.pendingDeliveries.size)
        assertFalse(result.pendingDeliveries.single().toString().contains("coins-500"))
        assertFalse(result.pendingDeliveries.single().toString().contains("91"))
        assertFalse(result.pendingDeliveries.single().toString().contains("transaction-secret"))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.pendingDeliveries as MutableList).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            InappifyConsumableDelivery(
                deliveryId = 0L,
                productIdentifier = "coins",
                transactionIdentifier = null,
                source = InappifyDeliverySource.DIRECT,
            )
        }
    }

    @Test
    fun purchaseRequest_v1ValueSemanticsRemainBytecodeCompatible() {
        val request = InappifyPurchaseRequest(
            productIdentifier = "product",
            offeringIdentifier = "offering",
            packageIdentifier = "package",
            discountCode = "discount",
            country = "IR",
            appVersion = "1.0.0",
            apiKey = "api-key",
            discount = 7L,
            isCrypto = true,
            market = InappifyMarket.BAZAAR,
            marketKey = "market-key",
            isLostPurchase = true,
            lostPurchaseToken = "purchase-token",
            lostPurchaseTime = 123L,
            idempotencyKey = "attempt",
            dynamicPriceToken = "price-token",
        )

        assertEquals(
            "InappifyPurchaseRequest(" +
                "productIdentifier=<redacted>, offeringIdentifier=<redacted>, " +
                "packageIdentifier=<redacted>, discountCode=<redacted>, country=IR, " +
                "appVersion=1.0.0, apiKey=<redacted>, discount=7, isCrypto=true, " +
                "market=BAZAAR, marketKey=<redacted>, isLostPurchase=true, " +
                "lostPurchaseToken=<redacted>, lostPurchaseTime=<redacted>, " +
                "idempotencyKey=<redacted>, dynamicPriceToken=<redacted>)",
            request.toString(),
        )
        assertEquals(legacyPurchaseRequestHash(request), request.hashCode())
    }

    @Test
    fun purchase_isTokenFreeByContractAndRedactsCorrelationValues() {
        val first = InappifyPurchase(
            attemptId = "attempt-id",
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            market = InappifyMarket.BAZAAR,
            purchaseStatus = InappifyPurchaseStatus.NEEDTOPAY,
            packageIdentifier = "annual-package",
            url = "https://checkout.example/private",
            checkoutId = "checkout-id",
            checkoutStatus = "PENDING",
            nextActionType = "REDIRECT",
        )
        val second = InappifyPurchase(
            attemptId = "attempt-id",
            productIdentifier = "annual-product",
            offeringIdentifier = "premium-offering",
            market = InappifyMarket.BAZAAR,
            purchaseStatus = InappifyPurchaseStatus.NEEDTOPAY,
            packageIdentifier = "annual-package",
            url = "https://checkout.example/private",
            checkoutId = "checkout-id",
            checkoutStatus = "PENDING",
            nextActionType = "REDIRECT",
        )
        val rendered = first.toString()

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(rendered.contains("attempt-id"))
        assertFalse(rendered.contains("annual-product"))
        assertFalse(rendered.contains("premium-offering"))
        assertFalse(rendered.contains("annual-package"))
        assertFalse(rendered.contains("https://checkout.example/private"))
        assertFalse(rendered.contains("checkout-id"))
        assertTrue(rendered.contains("purchaseStatus=NEEDTOPAY"))
    }

    @Test
    fun purchase_v1ValueSemanticsRemainBytecodeCompatible() {
        val purchase = InappifyPurchase(
            attemptId = "attempt",
            productIdentifier = "product",
            offeringIdentifier = "offering",
            market = InappifyMarket.BAZAAR,
            purchaseStatus = InappifyPurchaseStatus.NEEDTOPAY,
            packageIdentifier = "package",
            url = "https://example.invalid/checkout",
            checkoutId = "checkout",
            checkoutStatus = "PENDING",
            nextActionType = "REDIRECT",
        )

        assertEquals(
            "InappifyPurchase(" +
                "attemptId=<redacted>, productIdentifier=<redacted>, " +
                "offeringIdentifier=<redacted>, packageIdentifier=<redacted>, " +
                "market=BAZAAR, purchaseStatus=NEEDTOPAY, url=<redacted>, " +
                "checkoutId=<redacted>, checkoutStatus=PENDING, " +
                "nextActionType=REDIRECT)",
            purchase.toString(),
        )
        assertEquals(legacyPurchaseHash(purchase), purchase.hashCode())
    }

    @Test
    fun purchaseStatus_matchesTheNullableBackendContract() {
        assertEquals(
            InappifyPurchaseStatus.DONE,
            InappifyPurchaseStatus.fromServerValue("DONE"),
        )
        assertEquals(
            InappifyPurchaseStatus.NEEDTOPAY,
            InappifyPurchaseStatus.fromServerValue("NEEDTOPAY"),
        )
        assertEquals(
            listOf(InappifyPurchaseStatus.DONE, InappifyPurchaseStatus.NEEDTOPAY),
            InappifyPurchaseStatus.values().toList(),
        )
        assertNull(InappifyPurchaseStatus.fromServerValue(null))
        listOf("future-state", "NEEDS_PAYMENT", "NEED_TO_PAY", " DONE ", "done")
            .forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) {
                    InappifyPurchaseStatus.fromServerValue(malformed)
                }
            }
    }

    @Test
    fun storePurchaseStatus_isAdditiveAndParsesEveryV2State() {
        StorePurchaseStatusFixture.values.forEach { (raw, expected) ->
            assertEquals(expected, InappifyStorePurchaseStatus.fromServerValue(raw))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InappifyStorePurchaseStatus.fromServerValue("done")
        }
    }

    @Test
    fun restoreResult_reportsIndependentOutcomeCounts() {
        val result = InappifyRestoreResult(
            restoredCount = 2,
            alreadyProcessedCount = 3,
            failedCount = 1,
        )

        assertEquals(6, result.totalCount)
        assertEquals(
            InappifyRestoreResult(2, 3, 1),
            result,
        )
        assertThrows(IllegalArgumentException::class.java) {
            InappifyRestoreResult(-1, 0, 0)
        }
    }

    @Test
    fun storeV2Extensions_keepCustomV1ClientsCompatible() = runBlocking {
        val client: InappifyClient = FakeClient()

        val restored = client.restorePurchases()
            as InappifyResult.Success<InappifyRestoreResult>
        val confirmation = client.confirmDelivery(42L) as InappifyResult.Failure

        assertEquals(0, restored.data.totalCount)
        assertSame(client.snapshot, restored.snapshot)
        assertEquals(
            InappifyErrorCode.UNSUPPORTED_OPERATION,
            confirmation.error.code,
        )
        assertSame(client.snapshot, confirmation.snapshot)
    }

    @Test
    fun cacheRestoreExtensionKeepsCustomV1ClientsCompatibleWithoutNetworkFallback() = runBlocking {
        val client: InappifyClient = FakeClient()
        val result = client.restoreCachedSession(InappifyOptions("test-key")) as InappifyResult.Failure
        assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, result.error.code)
        assertSame(client.snapshot, result.snapshot)
        assertEquals(0L, client.snapshot.revision)
        assertFalse(InappifyClient::class.java.declaredMethods.any { it.name == "restoreCachedSession" })
    }

    @Test
    fun targetingSyncExtensionKeepsCustomV1ClientsCompatibleWithoutChangingTheInterface() = runBlocking {
        val client: InappifyClient = FakeClient()
        val result = client.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
        assertEquals(InappifyErrorCode.UNSUPPORTED_OPERATION, result.error.code)
        assertSame(client.snapshot, result.snapshot)
        assertEquals(0L, client.snapshot.revision)
        assertFalse(InappifyClient::class.java.declaredMethods.any { it.name == "syncAttributesAndOfferingsIfNeeded" })
    }

    @Test
    fun httpDiagnosticsExtension_keepsCustomV1ClientsCompatible() {
        val client: InappifyClient = FakeClient()
        var callbackCount = 0

        val registration = client.addHttpTraceListener { callbackCount += 1 }

        assertEquals(0, callbackCount)
        assertFalse(registration.isClosed)
        registration.close()
        assertTrue(registration.isClosed)
    }

    private object StorePurchaseStatusFixture {
        val values: List<Pair<String, InappifyStorePurchaseStatus>> = listOf(
            "PROCESSING" to InappifyStorePurchaseStatus.PROCESSING,
            "COMPLETED" to InappifyStorePurchaseStatus.COMPLETED,
            "RESTORED" to InappifyStorePurchaseStatus.RESTORED,
            "ALREADY_PROCESSED" to InappifyStorePurchaseStatus.ALREADY_PROCESSED,
            "DELIVERY_REQUIRED" to InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
            "CONSUME_REQUIRED" to InappifyStorePurchaseStatus.CONSUME_REQUIRED,
            "REJECTED" to InappifyStorePurchaseStatus.REJECTED,
        )
    }

    @Suppress("unused")
    private class FakeClient : InappifyClient {
        override var snapshot: InappifySnapshot =
            InappifySnapshot.initial(sdkVersion = "test")

        override suspend fun configure(
            options: InappifyOptions,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun login(
            request: InappifyLoginRequest,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun logout(): InappifyResult<Unit> =
            InappifyResult.Success(Unit, snapshot)

        override suspend fun getCustomerInfo(
            forceRefresh: Boolean,
        ): InappifyResult<InappifyCustomerInfo> =
            InappifyResult.Success(InappifyCustomerInfo(), snapshot)

        override suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo> =
            getCustomerInfo()

        override suspend fun getOfferings(): InappifyResult<InappifyOfferings> =
            InappifyResult.Success(InappifyOfferings(), snapshot)

        override suspend fun refreshOfferings(): InappifyResult<InappifyOfferings> =
            getOfferings()

        override suspend fun validateDiscountCode(
            request: InappifyDiscountCodeRequest,
        ): InappifyResult<InappifyDiscountCodeResult> = InappifyResult.Success(
            InappifyDiscountCodeResult(),
            snapshot,
        )

        override suspend fun setTargetingContext(
            country: String?,
            appVersion: String?,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun getCurrentOffering(
            placementIdentifier: String?,
            forceRefresh: Boolean,
            context: InappifyOfferingEvaluationContext?,
        ): InappifyResult<InappifyOffering?> = InappifyResult.Success(null, snapshot)

        override suspend fun checkEntitlement(
            identifier: String,
            forceRefresh: Boolean,
        ): InappifyResult<Boolean> = InappifyResult.Success(false, snapshot)

        override suspend fun setAttributes(
            request: InappifyAttributesRequest,
        ): InappifyResult<List<InappifyAttribute>> =
            InappifyResult.Success(request.attributes, snapshot)

        override suspend fun deleteAttributes(
            request: InappifyDeleteAttributesRequest,
        ): InappifyResult<List<InappifyAttribute>> =
            InappifyResult.Success(emptyList(), snapshot)

        override suspend fun setReservedAttribute(
            request: InappifyReservedAttributeRequest,
        ): InappifyResult<Unit> = InappifyResult.Success(Unit, snapshot)

        override suspend fun syncAttributes(
            request: InappifyAttributesRequest?,
        ): InappifyResult<List<InappifyAttribute>> = InappifyResult.Success(
            request?.attributes.orEmpty(),
            snapshot,
        )

        override suspend fun canSetReservedAttribute(
            key: String,
        ): InappifyResult<Boolean> = InappifyResult.Success(
            key.isNotBlank(),
            snapshot,
        )

        override suspend fun purchase(
            request: InappifyPurchaseRequest,
        ): InappifyResult<InappifyPurchase> = unsupportedPurchase()

        override suspend fun purchase(
            activity: Activity,
            request: InappifyPurchaseRequest,
        ): InappifyResult<InappifyPurchase> = unsupportedPurchase()

        private fun unsupportedPurchase(): InappifyResult<InappifyPurchase> =
            InappifyResult.Failure(
                InappifyError(
                    code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                    message = "Purchases are not supported by this fake.",
                ),
                snapshot,
            )

        override suspend fun syncPurchases(): InappifyResult<List<InappifyPurchase>> =
            InappifyResult.Success(emptyList(), snapshot)

        override fun addEventListener(
            listener: InappifyEventListener,
        ): InappifyListenerRegistration =
            InappifyListenerRegistration.create(Runnable {})

        override fun close() = Unit
    }

    private fun legacyPurchaseRequestHash(request: InappifyPurchaseRequest): Int {
        var result = request.productIdentifier.hashCode()
        result = 31 * result + request.offeringIdentifier.hashCode()
        result = 31 * result + (request.packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + (request.discountCode?.hashCode() ?: 0)
        result = 31 * result + (request.country?.hashCode() ?: 0)
        result = 31 * result + (request.appVersion?.hashCode() ?: 0)
        result = 31 * result + (request.apiKey?.hashCode() ?: 0)
        result = 31 * result + request.discount.hashCode()
        result = 31 * result + request.isCrypto.hashCode()
        result = 31 * result + request.market.hashCode()
        result = 31 * result + (request.marketKey?.hashCode() ?: 0)
        result = 31 * result + request.isLostPurchase.hashCode()
        result = 31 * result + (request.lostPurchaseToken?.hashCode() ?: 0)
        result = 31 * result + (request.lostPurchaseTime?.hashCode() ?: 0)
        result = 31 * result + (request.idempotencyKey?.hashCode() ?: 0)
        result = 31 * result + (request.dynamicPriceToken?.hashCode() ?: 0)
        return result
    }

    private fun legacyPurchaseHash(purchase: InappifyPurchase): Int {
        var result = purchase.attemptId.hashCode()
        result = 31 * result + purchase.productIdentifier.hashCode()
        result = 31 * result + purchase.offeringIdentifier.hashCode()
        result = 31 * result + (purchase.packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + purchase.market.hashCode()
        result = 31 * result + (purchase.purchaseStatus?.hashCode() ?: 0)
        result = 31 * result + (purchase.url?.hashCode() ?: 0)
        result = 31 * result + (purchase.checkoutId?.hashCode() ?: 0)
        result = 31 * result + (purchase.checkoutStatus?.hashCode() ?: 0)
        result = 31 * result + (purchase.nextActionType?.hashCode() ?: 0)
        return result
    }
}
