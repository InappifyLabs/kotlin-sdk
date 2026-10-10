package com.inappify.sdk

/**
 * Store-side behavior of an Inappify product.
 *
 * The value controls which Billing API is used and whether a verified item
 * enters the delivery/consume cycle. Entitlement decisions remain
 * authoritative on the Inappify server.
 */
public enum class InappifyProductType {
    /**
     * Content delivered once and consumed only after the host calls
     * [InappifyClient.confirmDelivery] for the returned delivery identifier.
     */
    CONSUMABLE,

    /** A durable one-time purchase that can be restored. */
    NON_CONSUMABLE,

    /** A recurring store subscription that can be restored. */
    SUBSCRIPTION,
}
