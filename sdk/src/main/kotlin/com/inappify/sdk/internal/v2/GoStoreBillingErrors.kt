package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyError
import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode

/** A lost native callback is an uncertain payment, not permission to start another purchase. */
internal fun StoreBillingError.toGoPurchaseError(attemptId: String, store: String = "bazar"): InappifyError {
    val uncertain = code in setOf(StoreBillingErrorCode.PURCHASE_FAILED,
        StoreBillingErrorCode.CONNECTION_LOST, StoreBillingErrorCode.OPERATION_TIMEOUT,
        StoreBillingErrorCode.UI_HOST_DESTROYED, StoreBillingErrorCode.ADAPTER_CLOSED,
        StoreBillingErrorCode.INVALID_PURCHASE_STATE, StoreBillingErrorCode.INVALID_PURCHASE_DATA,
        StoreBillingErrorCode.PRODUCT_MISMATCH, StoreBillingErrorCode.PACKAGE_MISMATCH)
    val publicCode = when (code) {
        StoreBillingErrorCode.PURCHASE_IN_PROGRESS -> InappifyErrorCode.PURCHASE_IN_PROGRESS
        StoreBillingErrorCode.UNSUPPORTED_MARKET -> InappifyErrorCode.UNSUPPORTED_OPERATION
        StoreBillingErrorCode.ADAPTER_CLOSED, StoreBillingErrorCode.UI_HOST_DESTROYED -> InappifyErrorCode.REQUEST_CANCELLED
        StoreBillingErrorCode.OPERATION_TIMEOUT -> InappifyErrorCode.TIMEOUT
        StoreBillingErrorCode.INVALID_REQUEST, StoreBillingErrorCode.MISSING_MARKET_KEY -> InappifyErrorCode.INVALID_CONFIGURATION
        StoreBillingErrorCode.INVALID_PURCHASE_STATE, StoreBillingErrorCode.INVALID_PURCHASE_DATA,
        StoreBillingErrorCode.PRODUCT_MISMATCH, StoreBillingErrorCode.PACKAGE_MISMATCH -> InappifyErrorCode.MALFORMED_RESPONSE
        else -> InappifyErrorCode.STORE_UNAVAILABLE
    }
    return InappifyError(publicCode, "The marketplace purchase did not complete reliably.",
        isRetryable = isRetryable && !uncertain,
        details = mapOf("operation" to "purchase", "attemptId" to attemptId, "store" to store,
            "storeCode" to code.name, "outcomeMayHaveCommitted" to uncertain))
}
