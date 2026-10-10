package com.inappify.sdk

/**
 * Immutable discount-code validation request.
 *
 * [offeringIdentifier] is accepted for API consistency but is not sent by the
 * current discount endpoint. Validation uses the active session.
 */
public class InappifyDiscountCodeRequest @JvmOverloads public constructor(
    public val discountCode: String,
    public val offeringIdentifier: String? = null,
) {

    /** Redacts both values because a discount code may be customer-specific. */
    public override fun toString(): String =
        "InappifyDiscountCodeRequest(" +
            "discountCode=<redacted>, " +
            "offeringIdentifier=${offeringIdentifier.redactedValue()}" +
            ")"
}
