package com.inappify.sdk.internal.billing

import java.util.concurrent.atomic.AtomicBoolean

/** Deterministic adapter used when the requested marketplace is not implemented. */
internal class UnsupportedStoreBillingAdapter(
    private val unsupportedError: StoreBillingError,
) : StoreBillingAdapter {
    private val closed = AtomicBoolean(false)

    override suspend fun purchase(
        uiHost: StoreUiHost,
        request: StorePurchaseRequest,
    ): StoreBillingResult {
        if (closed.get()) {
            return StoreBillingResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.ADAPTER_CLOSED,
                    message = "The store billing adapter is closed.",
                ),
            )
        }

        return StoreBillingResult.Failure(unsupportedError)
    }

    override suspend fun queryPurchases(
        productType: StoreProductType,
    ): StorePurchaseQueryResult {
        if (closed.get()) {
            return StorePurchaseQueryResult.Failure(
                StoreBillingError(
                    code = StoreBillingErrorCode.ADAPTER_CLOSED,
                    message = "The store billing adapter is closed.",
                ),
            )
        }

        return StorePurchaseQueryResult.Failure(unsupportedError)
    }

    override suspend fun consume(purchase: StorePurchase): StoreConsumeResult {
        if (closed.get()) {
            return StoreConsumeResult.PermanentFailure(
                StoreBillingError(
                    code = StoreBillingErrorCode.ADAPTER_CLOSED,
                    message = "The store billing adapter is closed.",
                ),
            )
        }

        return if (unsupportedError.isRetryable) {
            StoreConsumeResult.RetryableFailure(unsupportedError)
        } else {
            StoreConsumeResult.PermanentFailure(unsupportedError)
        }
    }

    override fun close() {
        closed.set(true)
    }
}
