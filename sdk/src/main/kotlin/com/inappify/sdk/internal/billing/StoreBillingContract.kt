package com.inappify.sdk.internal.billing

import com.inappify.sdk.InappifyMarket

/** Creates the billing implementation selected for one purchase operation. */
internal fun interface StoreBillingAdapterFactory {

    /**
     * Creates an isolated adapter for [market].
     *
     * Implementations must never include [marketKey] in exceptions, error messages, or logs.
     */
    fun create(market: InappifyMarket, marketKey: String?): StoreBillingAdapter
}

/** Store-neutral boundary used by the SDK purchase coordinator. */
internal interface StoreBillingAdapter : AutoCloseable {

    /** Starts one native store purchase using the UI host supplied for this invocation. */
    suspend fun purchase(
        uiHost: StoreUiHost,
        request: StorePurchaseRequest,
    ): StoreBillingResult

    /** Returns validated purchases currently owned by the customer for [productType]. */
    suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult

    /**
     * Consumes one validated in-app purchase in the selected marketplace.
     *
     * The default keeps existing test and third-party internal adapters source-compatible while
     * making unsupported consumption an explicit permanent result. Implementations must never
     * include the purchase token or receipt data in errors or diagnostics.
     */
    suspend fun consume(purchase: StorePurchase): StoreConsumeResult =
        StoreConsumeResult.PermanentFailure(
            StoreBillingError(
                code = StoreBillingErrorCode.UNSUPPORTED_MARKET,
                message = "Purchase consumption is not supported by this billing adapter.",
            ),
        )

    /** Releases active billing resources. This operation is idempotent. */
    override fun close()
}

/**
 * Optional capability for V2 reconciliation that validates owned receipts independently.
 * Keeping this separate avoids adding another query method to [StoreBillingAdapter].
 */
internal interface PartialStorePurchaseQueryAdapter {

    /** Returns valid receipts and counts receipts rejected during local validation. */
    suspend fun queryPurchasesPartially(
        productType: StoreProductType,
    ): StorePurchaseQueryResult
}

/** Product categories understood by native billing adapters. */
internal enum class StoreProductType {
    IN_APP,
    SUBSCRIPTION,
}

/** Validation policy for an owned-purchase query. */
internal enum class StorePurchaseQueryMode {
    /** Preserves the V1 behavior: the first invalid receipt fails the whole query. */
    STRICT,

    /** Keeps valid receipts and reports invalid receipts independently. */
    PARTIAL,
}

/** Store-neutral input for one native purchase attempt. */
internal class StorePurchaseRequest(
    val productIdentifier: String,
    val productType: StoreProductType = StoreProductType.IN_APP,
    val developerPayload: String? = null,
    val dynamicPriceToken: String? = null,
) {

    /** Omits all values that may carry developer- or server-controlled secrets. */
    override fun toString(): String =
        "StorePurchaseRequest(" +
            "productIdentifier=$productIdentifier, " +
            "productType=$productType, " +
            "hasDeveloperPayload=${!developerPayload.isNullOrEmpty()}, " +
            "hasDynamicPriceToken=${!dynamicPriceToken.isNullOrEmpty()}" +
            ")"
}

/** Terminal result of a native store purchase attempt. */
internal sealed interface StoreBillingResult {

    /** A locally verified purchase returned by the selected store. */
    class Success(val purchase: StorePurchase) : StoreBillingResult {
        override fun toString(): String = "StoreBillingResult.Success(purchase=$purchase)"
    }

    /** The customer explicitly cancelled the native payment flow. */
    data object Cancelled : StoreBillingResult {
        override fun toString(): String = "StoreBillingResult.Cancelled"
    }

    /** A structured failure that does not expose store credentials or purchase evidence. */
    class Failure(val error: StoreBillingError) : StoreBillingResult {
        override fun toString(): String = "StoreBillingResult.Failure(error=$error)"
    }
}

/** Terminal result of querying purchases already owned by the current store account. */
internal sealed interface StorePurchaseQueryResult {

    /**
     * Locally verified evidence plus receipts rejected independently by a partial query.
     * [invalidPurchaseCount] is always zero for a strict successful query.
     */
    class Success @JvmOverloads constructor(
        val purchases: List<StorePurchase>,
        val invalidPurchaseCount: Int = 0,
    ) : StorePurchaseQueryResult {
        init {
            require(invalidPurchaseCount >= 0) {
                "invalidPurchaseCount must not be negative."
            }
        }

        override fun toString(): String =
            "StorePurchaseQueryResult.Success(" +
                "purchaseCount=${purchases.size}, " +
                "invalidPurchaseCount=$invalidPurchaseCount" +
                ")"
    }

    /** A structured failure that does not expose store credentials or purchase evidence. */
    class Failure(val error: StoreBillingError) : StorePurchaseQueryResult {
        override fun toString(): String = "StorePurchaseQueryResult.Failure(error=$error)"
    }
}

/** Terminal result of consuming one validated store purchase. */
internal sealed interface StoreConsumeResult {

    /** The marketplace confirmed that the purchase was consumed. */
    data object Success : StoreConsumeResult {
        override fun toString(): String = "StoreConsumeResult.Success"
    }

    /** A transient failure for which the exact same purchase may be retried safely. */
    class RetryableFailure(val error: StoreBillingError) : StoreConsumeResult {
        init {
            require(error.isRetryable) { "A retryable consume failure requires retryable error metadata." }
        }

        override fun toString(): String = "StoreConsumeResult.RetryableFailure(error=$error)"
    }

    /** A permanent failure which requires configuration or purchase-data correction. */
    class PermanentFailure(val error: StoreBillingError) : StoreConsumeResult {
        init {
            require(!error.isRetryable) { "A permanent consume failure cannot be retryable." }
        }

        override fun toString(): String = "StoreConsumeResult.PermanentFailure(error=$error)"
    }
}

/** Validated purchase evidence required by the Inappify server verification layer. */
internal class StorePurchase(
    val orderIdentifier: String,
    val purchaseToken: String,
    val developerPayload: String,
    val packageName: String,
    val productIdentifier: String,
    val purchaseTimeMillis: Long,
    val originalJson: String,
    val signature: String,
) {

    /** Purchase evidence is intentionally omitted because it commonly contains bearer secrets. */
    override fun toString(): String =
        "StorePurchase(" +
            "productIdentifier=$productIdentifier, " +
            "packageName=$packageName, " +
            "purchaseTimeMillis=$purchaseTimeMillis" +
            ")"
}

/** Stable internal categories which can be mapped to the SDK's public error contract. */
internal enum class StoreBillingErrorCode {
    UNSUPPORTED_MARKET,
    MISSING_MARKET_KEY,
    INVALID_REQUEST,
    UI_HOST_UNAVAILABLE,
    UI_HOST_NOT_SUPPORTED,
    UI_HOST_FINISHING,
    UI_HOST_DESTROYED,
    PURCHASE_IN_PROGRESS,
    ADAPTER_CLOSED,
    MAIN_THREAD_UNAVAILABLE,
    OPERATION_TIMEOUT,
    CONNECTION_FAILED,
    CONNECTION_LOST,
    PURCHASE_FLOW_FAILED,
    PURCHASE_FAILED,
    PURCHASE_QUERY_FAILED,
    CONSUME_FAILED,
    INVALID_PURCHASE_STATE,
    INVALID_PURCHASE_DATA,
    PRODUCT_MISMATCH,
    PACKAGE_MISMATCH,
}

/** Non-sensitive diagnostic information returned by an internal billing adapter. */
internal class StoreBillingError(
    val code: StoreBillingErrorCode,
    val message: String,
    val isRetryable: Boolean = false,
    val causeType: String? = null,
) {

    /** Omits the underlying exception type to keep logs stable and intentionally minimal. */
    override fun toString(): String =
        "StoreBillingError(" +
            "code=$code, " +
            "message=$message, " +
            "isRetryable=$isRetryable" +
            ")"
}
