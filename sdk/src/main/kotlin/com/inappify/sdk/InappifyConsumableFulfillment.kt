package com.inappify.sdk

import java.util.Collections

/** Backend route that created a consumable delivery. */
public enum class InappifyDeliverySource {
    DIRECT,
    BAZAAR,
    MYKET,
}

/**
 * Server-authoritative consumable that the host must grant exactly once.
 *
 * [deliveryId] is the idempotency key for the host inventory transaction.
 * The SDK does not infer the granted amount from [productIdentifier].
 */
public class InappifyConsumableDelivery public constructor(
    public val deliveryId: Long,
    public val productIdentifier: String,
    public val transactionIdentifier: String?,
    public val source: InappifyDeliverySource,
) {
    init {
        require(deliveryId > 0L) { "deliveryId must be positive." }
        require(productIdentifier.isNotBlank()) { "productIdentifier must not be blank." }
    }

    /** Returns workflow metadata without exposing backend identifiers. */
    public override fun toString(): String =
        "InappifyConsumableDelivery(" +
            "deliveryId=<redacted>, " +
            "productIdentifier=<redacted>, " +
            "transactionIdentifier=<redacted>, " +
            "source=$source)"

    public override fun equals(other: Any?): Boolean =
        other is InappifyConsumableDelivery &&
            deliveryId == other.deliveryId &&
            productIdentifier == other.productIdentifier &&
            transactionIdentifier == other.transactionIdentifier &&
            source == other.source

    public override fun hashCode(): Int {
        var result = deliveryId.hashCode()
        result = 31 * result + productIdentifier.hashCode()
        result = 31 * result + (transactionIdentifier?.hashCode() ?: 0)
        result = 31 * result + source.hashCode()
        return result
    }
}

/** Result returned by the host after its idempotent inventory transaction. */
public enum class InappifyDeliveryResult {
    /** The item is durably present in the user's inventory. */
    DELIVERED,

    /** The item was not granted and must remain pending for a later sync. */
    RETRY_LATER,
}

/** Applies one consumable delivery in host-owned durable storage. */
public fun interface InappifyConsumableDeliveryHandler {
    /**
     * Grants [delivery] exactly once and returns only after the durable write commits.
     *
     * Implementations must use `delivery.deliveryId` as a unique idempotency key.
     * The SDK invokes this callback away from the Android main thread.
     */
    public suspend fun deliver(
        delivery: InappifyConsumableDelivery,
    ): InappifyDeliveryResult
}

/** Result of reconciling the current App/Customer consumable-delivery queue. */
public class InappifyConsumableSyncResult public constructor(
    public val discoveredCount: Int,
    public val completedCount: Int,
    pendingDeliveries: List<InappifyConsumableDelivery>,
) {
    init {
        require(discoveredCount >= 0) { "discoveredCount must not be negative." }
        require(completedCount >= 0) { "completedCount must not be negative." }
        require(completedCount <= discoveredCount) {
            "completedCount must not exceed discoveredCount."
        }
    }

    /** Deliveries that still require host delivery or acknowledgement retry. */
    public val pendingDeliveries: List<InappifyConsumableDelivery> =
        Collections.unmodifiableList(pendingDeliveries.toList())

    public override fun toString(): String =
        "InappifyConsumableSyncResult(" +
            "discoveredCount=$discoveredCount, " +
            "completedCount=$completedCount, " +
            "pendingCount=${pendingDeliveries.size})"

    public override fun equals(other: Any?): Boolean =
        other is InappifyConsumableSyncResult &&
            discoveredCount == other.discoveredCount &&
            completedCount == other.completedCount &&
            pendingDeliveries == other.pendingDeliveries

    public override fun hashCode(): Int {
        var result = discoveredCount
        result = 31 * result + completedCount
        result = 31 * result + pendingDeliveries.hashCode()
        return result
    }
}

/** Internal additive capability that keeps the original client interface binary compatible. */
internal interface ConsumableFulfillmentCapableClient {
    fun setConsumableDeliveryHandlerInternal(
        handler: InappifyConsumableDeliveryHandler,
    ): InappifyListenerRegistration

    suspend fun syncPendingConsumablesInternal():
        InappifyResult<InappifyConsumableSyncResult>
}

/**
 * Registers the host's exactly-once consumable delivery handler.
 *
 * Register before [InappifyClient.configure] so lifecycle reconciliation can
 * immediately deliver pending items. Closing the returned registration removes
 * this handler only if it is still the active registration.
 */
public fun InappifyClient.setConsumableDeliveryHandler(
    handler: InappifyConsumableDeliveryHandler,
): InappifyListenerRegistration =
    if (this is ConsumableFulfillmentCapableClient) {
        setConsumableDeliveryHandlerInternal(handler)
    } else {
        InappifyListenerRegistration.create(Runnable {})
    }

/**
 * Reconciles pending consumables for the configured App/Customer.
 *
 * Call this at startup/resume and after connectivity returns. If a delivery
 * handler is registered, the SDK invokes it and acknowledges only durable
 * [InappifyDeliveryResult.DELIVERED] results. Otherwise pending deliveries are
 * returned for an explicit host-managed [InappifyClient.confirmDelivery] flow.
 */
public suspend fun InappifyClient.syncPendingConsumables():
    InappifyResult<InappifyConsumableSyncResult> =
    if (this is ConsumableFulfillmentCapableClient) {
        syncPendingConsumablesInternal()
    } else {
        InappifyResult.Failure(
            error = InappifyError(
                code = InappifyErrorCode.UNSUPPORTED_OPERATION,
                message = "Consumable fulfillment is not supported by this client.",
                details = mapOf("operation" to "syncPendingConsumables"),
            ),
            snapshot = snapshot,
        )
    }
