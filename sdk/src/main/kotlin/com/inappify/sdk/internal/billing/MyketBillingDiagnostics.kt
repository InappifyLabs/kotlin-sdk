package com.inappify.sdk.internal.billing

/** Fixed categories only: exception messages and stack traces may contain payment evidence. */
internal fun myketOperationException(error: Exception, stage: String): StoreBillingError = StoreBillingError(
    code = if (stage == "SETUP") StoreBillingErrorCode.CONNECTION_FAILED else StoreBillingErrorCode.PURCHASE_FAILED,
    message = "Myket billing operation raised an exception.",
    isRetryable = stage == "SETUP",
    causeType = when (error) {
        is SecurityException -> "SECURITY"
        is IllegalStateException -> "ILLEGAL_STATE"
        is IllegalArgumentException -> "INVALID_ARGUMENT"
        is NullPointerException -> "NULL_REFERENCE"
        is ClassCastException -> "TYPE_MISMATCH"
        else -> "OTHER"
    },
    failureStage = stage,
)
