package com.inappify.sdk

/** Stable categories of asynchronous state changes emitted by Inappify. */
public enum class InappifyEventType {
    /** One or more fields in the authoritative SDK snapshot changed. */
    STATE_CHANGED,

    /** Customer information was refreshed, replaced, or cleared. */
    CUSTOMER_INFO_CHANGED,

    /** Offerings were refreshed, invalidated, or cleared. */
    OFFERINGS_CHANGED,

    /** The authenticated customer or authentication state changed. */
    AUTHENTICATION_CHANGED,

    /** A purchase changed outside the direct lifetime of its initiating call. */
    PURCHASE_UPDATED,
}
