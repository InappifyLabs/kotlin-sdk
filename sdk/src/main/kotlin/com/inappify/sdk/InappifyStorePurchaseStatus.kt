package com.inappify.sdk

/** Server-authoritative state of a V2 marketplace purchase. */
public enum class InappifyStorePurchaseStatus {
    /** Store verification is still running and will resume from durable state. */
    PROCESSING,

    /** A non-consumable purchase or subscription was confirmed by the server. */
    COMPLETED,

    /** A previously owned purchase was restored by the server. */
    RESTORED,

    /** The same store event was already registered and is successful. */
    ALREADY_PROCESSED,

    /** A verified consumable awaits idempotent host delivery. */
    DELIVERY_REQUIRED,

    /** Delivery is confirmed and the SDK must consume the store purchase. */
    CONSUME_REQUIRED,

    /** Store verification permanently rejected the purchase. */
    REJECTED,
    ;

    internal companion object {
        internal fun fromServerValue(value: String): InappifyStorePurchaseStatus =
            values().firstOrNull { status -> status.name == value }
                ?: throw IllegalArgumentException("Unknown V2 store purchase status.")
    }
}
