package com.inappify.sdk.internal.v2

import com.inappify.sdk.*

/** Persisted proof that this SDK has not yet handed a payment UI to Myket. */
internal object MyketPurchasePreparation {
    fun preparingError() = InappifyError(InappifyErrorCode.PURCHASE_IN_PROGRESS,
        "Preparing the Myket payment screen.", details = mapOf("store" to "myket",
            "serverCode" to "MYKET_PURCHASE_PREPARING", "outcomeMayHaveCommitted" to false))

    fun dispatchedError() = InappifyError(InappifyErrorCode.PURCHASE_IN_PROGRESS,
        "The Myket payment screen may have opened.", details = mapOf("store" to "myket",
            "serverCode" to "MYKET_PURCHASE_DISPATCHED", "outcomeMayHaveCommitted" to true))

    fun isPreparing(attempt: InappifyPurchaseAttempt): Boolean =
        attempt.market == InappifyMarket.MYKET && attempt.status == InappifyPurchaseAttemptStatus.PENDING &&
            attempt.purchase == null && attempt.error?.code == InappifyErrorCode.PURCHASE_IN_PROGRESS &&
            attempt.error.details["store"] == "myket" &&
            attempt.error.details["serverCode"] == "MYKET_PURCHASE_PREPARING" &&
            attempt.error.details["outcomeMayHaveCommitted"] == false

    fun interrupted(attempt: InappifyPurchaseAttempt, now: Long) = InappifyPurchaseAttempt(
        attempt.attemptId, attempt.productIdentifier, attempt.offeringIdentifier, attempt.market,
        InappifyPurchaseAttemptStatus.FAILED, null,
        InappifyError(InappifyErrorCode.STORE_UNAVAILABLE, "Myket payment preparation stopped before dispatch.",
            isRetryable = true, details = mapOf("store" to "myket", "serverCode" to "MYKET_PURCHASE_NOT_STARTED",
                "outcomeMayHaveCommitted" to false)), now)
}
