package com.inappify.sdk.internal.billing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreBillingConsumeContractTest {

    @Test
    fun defaultConsume_isPermanentAndKeepsExistingAdaptersSourceCompatible() = runBlocking {
        val adapter = object : StoreBillingAdapter {
            override suspend fun purchase(
                uiHost: StoreUiHost,
                request: StorePurchaseRequest,
            ): StoreBillingResult = error("Not used by this test.")

            override suspend fun queryPurchases(
                productType: StoreProductType,
            ): StorePurchaseQueryResult = error("Not used by this test.")

            override fun close() = Unit
        }

        val result = adapter.consume(storePurchase())

        assertTrue(result is StoreConsumeResult.PermanentFailure)
        result as StoreConsumeResult.PermanentFailure
        assertEquals(StoreBillingErrorCode.UNSUPPORTED_MARKET, result.error.code)
        assertFalse(result.error.isRetryable)
    }

    @Test
    fun unsupportedAdapter_preservesRetryabilityWithoutLeakingPurchaseEvidence() = runBlocking {
        val expected = StoreBillingError(
            code = StoreBillingErrorCode.CONNECTION_FAILED,
            message = "The configured billing service is unavailable.",
            isRetryable = true,
        )
        val adapter = UnsupportedStoreBillingAdapter(expected)

        val result = adapter.consume(storePurchase())

        assertTrue(result is StoreConsumeResult.RetryableFailure)
        result as StoreConsumeResult.RetryableFailure
        assertSame(expected, result.error)
        assertFalse(result.toString().contains(PURCHASE_TOKEN))
        assertFalse(result.toString().contains(ORIGINAL_JSON))
        assertFalse(result.toString().contains(SIGNATURE))
    }

    @Test
    fun unsupportedAdapter_closedConsumeIsPermanent() = runBlocking {
        val adapter = UnsupportedStoreBillingAdapter(
            StoreBillingError(
                code = StoreBillingErrorCode.UNSUPPORTED_MARKET,
                message = "Consumption is unsupported.",
            ),
        )
        adapter.close()

        val result = adapter.consume(storePurchase())

        assertTrue(result is StoreConsumeResult.PermanentFailure)
        result as StoreConsumeResult.PermanentFailure
        assertEquals(StoreBillingErrorCode.ADAPTER_CLOSED, result.error.code)
        assertFalse(result.error.isRetryable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun retryableFailure_rejectsPermanentErrorMetadata() {
        StoreConsumeResult.RetryableFailure(
            StoreBillingError(
                code = StoreBillingErrorCode.CONSUME_FAILED,
                message = "Permanent consume failure.",
                isRetryable = false,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun permanentFailure_rejectsRetryableErrorMetadata() {
        StoreConsumeResult.PermanentFailure(
            StoreBillingError(
                code = StoreBillingErrorCode.CONSUME_FAILED,
                message = "Transient consume failure.",
                isRetryable = true,
            ),
        )
    }

    private fun storePurchase(): StorePurchase = StorePurchase(
        orderIdentifier = "order-id",
        purchaseToken = PURCHASE_TOKEN,
        developerPayload = "developer-payload",
        packageName = "com.example.app",
        productIdentifier = "coins",
        purchaseTimeMillis = 1_725_000_000_000L,
        originalJson = ORIGINAL_JSON,
        signature = SIGNATURE,
    )

    private companion object {
        const val PURCHASE_TOKEN = "secret-purchase-token"
        const val ORIGINAL_JSON = "secret-original-json"
        const val SIGNATURE = "secret-signature"
    }
}
