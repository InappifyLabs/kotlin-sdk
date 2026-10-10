package com.inappify.sdk

/** Stable semantic failure categories exposed by the native SDK. */
public enum class InappifyErrorCode {
    INVALID_CONFIGURATION,
    NOT_CONFIGURED,
    UNAUTHORIZED,
    NETWORK,
    REQUEST_CANCELLED,
    TIMEOUT,
    STORE_UNAVAILABLE,
    PURCHASE_IN_PROGRESS,
    PURCHASE_CANCELLED,
    PURCHASE_VERIFICATION_FAILED,
    UNSUPPORTED_OPERATION,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/**
 * Platform-independent SDK failure.
 *
 * [details] must contain non-sensitive diagnostics only. API keys, access
 * tokens, identity tickets, purchase tokens, and customer identifiers must not
 * be included in this object.
 */
public class InappifyError public constructor(
    public val code: InappifyErrorCode,
    public val message: String,
    public val isRetryable: Boolean = false,
    details: Map<String, Any?> = emptyMap(),
) {

    /** Defensive copy of non-sensitive diagnostic metadata. */
    public val details: Map<String, Any?> = details.toMap()

    /** Omits diagnostic details to reduce accidental data exposure in logs. */
    public override fun toString(): String =
        "InappifyError(" +
            "code=$code, " +
            "message=$message, " +
            "isRetryable=$isRetryable" +
            ")"
}
