package com.inappify.sdk

/** Purchase state returned after Inappify server processing. */
public enum class InappifyPurchaseStatus {
    /** The checkout completed and the server accepted the purchase. */
    DONE,

    /** Additional payment action is required before the checkout can complete. */
    NEEDTOPAY,

    ;

    internal companion object {
        /**
         * Maps server status values. A missing status maps to null; unknown
         * values are rejected as malformed.
         */
        internal fun fromServerValue(value: String?): InappifyPurchaseStatus? =
            when (value) {
                null -> null
                "DONE" -> DONE
                "NEEDTOPAY" -> NEEDTOPAY
                else -> throw IllegalArgumentException("Unknown purchase status.")
            }
    }
}

/**
 * Immutable, token-free result of a purchase attempt.
 *
 * [attemptId] correlates SDK events for one logical attempt. [url] may contain
 * payment-provider state and therefore must not be logged. Before opening it,
 * hosts must validate HTTPS and an explicit Inappify payment-host allowlist;
 * returning from that URL is only a fulfillment-sync trigger. Store receipts
 * and purchase tokens are deliberately retained only inside the SDK.
 */
public class InappifyPurchase private constructor(
    public val attemptId: String,
    public val productIdentifier: String,
    public val offeringIdentifier: String,
    public val market: InappifyMarket,
    public val purchaseStatus: InappifyPurchaseStatus? = null,
    public val packageIdentifier: String? = null,
    public val url: String? = null,
    public val checkoutId: String? = null,
    public val checkoutStatus: String? = null,
    public val nextActionType: String? = null,
    /**
     * Server delivery key for a consumable awaiting idempotent host fulfilment
     * and [InappifyClient.confirmDelivery].
     */
    public val deliveryId: Long?,
    /** Informational durable verification id; polling is owned by the SDK. */
    public val verificationRequestId: Long?,
    /** Whether the server had already processed this store event. */
    public val alreadyProcessed: Boolean,
    /** Server-authoritative V2 store workflow status, absent for V1 purchases. */
    public val storePurchaseStatus: InappifyStorePurchaseStatus?,
) {

    /** Version-one-compatible purchase result constructor. */
    @JvmOverloads
    public constructor(
        attemptId: String,
        productIdentifier: String,
        offeringIdentifier: String,
        market: InappifyMarket,
        purchaseStatus: InappifyPurchaseStatus? = null,
        packageIdentifier: String? = null,
        url: String? = null,
        checkoutId: String? = null,
        checkoutStatus: String? = null,
        nextActionType: String? = null,
    ) : this(
        attemptId = attemptId,
        productIdentifier = productIdentifier,
        offeringIdentifier = offeringIdentifier,
        market = market,
        purchaseStatus = purchaseStatus,
        packageIdentifier = packageIdentifier,
        url = url,
        checkoutId = checkoutId,
        checkoutStatus = checkoutStatus,
        nextActionType = nextActionType,
        deliveryId = null,
        verificationRequestId = null,
        alreadyProcessed = false,
        storePurchaseStatus = null,
    )

    /** Returns useful state while redacting correlation, product, checkout, and URL values. */
    public override fun toString(): String {
        val legacy = "InappifyPurchase(" +
            "attemptId=${attemptId.redactedValue()}, " +
            "productIdentifier=${productIdentifier.redactedValue()}, " +
            "offeringIdentifier=${offeringIdentifier.redactedValue()}, " +
            "packageIdentifier=${packageIdentifier.redactedValue()}, " +
            "market=$market, " +
            "purchaseStatus=$purchaseStatus, " +
            "url=${url.redactedValue()}, " +
            "checkoutId=${checkoutId.redactedValue()}, " +
            "checkoutStatus=$checkoutStatus, " +
            "nextActionType=$nextActionType"
        return if (!hasV2StoreState()) {
            "$legacy)"
        } else {
            "$legacy, " +
                "hasDeliveryId=${deliveryId != null}, " +
                "hasVerificationRequestId=${verificationRequestId != null}, " +
                "alreadyProcessed=$alreadyProcessed, " +
                "storePurchaseStatus=$storePurchaseStatus)"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is InappifyPurchase &&
            attemptId == other.attemptId &&
            productIdentifier == other.productIdentifier &&
            offeringIdentifier == other.offeringIdentifier &&
            packageIdentifier == other.packageIdentifier &&
            market == other.market &&
            purchaseStatus == other.purchaseStatus &&
            url == other.url &&
            checkoutId == other.checkoutId &&
            checkoutStatus == other.checkoutStatus &&
            nextActionType == other.nextActionType &&
            deliveryId == other.deliveryId &&
            verificationRequestId == other.verificationRequestId &&
            alreadyProcessed == other.alreadyProcessed &&
            storePurchaseStatus == other.storePurchaseStatus

    public override fun hashCode(): Int {
        var result = attemptId.hashCode()
        result = 31 * result + productIdentifier.hashCode()
        result = 31 * result + offeringIdentifier.hashCode()
        result = 31 * result + (packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + market.hashCode()
        result = 31 * result + (purchaseStatus?.hashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (checkoutId?.hashCode() ?: 0)
        result = 31 * result + (checkoutStatus?.hashCode() ?: 0)
        result = 31 * result + (nextActionType?.hashCode() ?: 0)
        if (hasV2StoreState()) {
            result = 31 * result + (deliveryId?.hashCode() ?: 0)
            result = 31 * result + (verificationRequestId?.hashCode() ?: 0)
            result = 31 * result + alreadyProcessed.hashCode()
            result = 31 * result + (storePurchaseStatus?.hashCode() ?: 0)
        }
        return result
    }

    private fun hasV2StoreState(): Boolean =
        deliveryId != null ||
            verificationRequestId != null ||
            alreadyProcessed ||
            storePurchaseStatus != null

    internal companion object {
        @Suppress("LongParameterList")
        internal fun storeResult(
            attemptId: String,
            productIdentifier: String,
            offeringIdentifier: String,
            packageIdentifier: String?,
            status: InappifyStorePurchaseStatus,
            deliveryId: Long?,
            verificationRequestId: Long?,
            alreadyProcessed: Boolean,
        ): InappifyPurchase = storeResult(attemptId, productIdentifier, offeringIdentifier,
            packageIdentifier, status, deliveryId, verificationRequestId, alreadyProcessed, InappifyMarket.BAZAAR)

        @Suppress("LongParameterList")
        internal fun storeResult(
            attemptId: String,
            productIdentifier: String,
            offeringIdentifier: String,
            packageIdentifier: String?,
            status: InappifyStorePurchaseStatus,
            deliveryId: Long?,
            verificationRequestId: Long?,
            alreadyProcessed: Boolean,
            market: InappifyMarket,
        ): InappifyPurchase = InappifyPurchase(
            attemptId = attemptId,
            productIdentifier = productIdentifier,
            offeringIdentifier = offeringIdentifier,
            market = market,
            purchaseStatus = when (status) {
                InappifyStorePurchaseStatus.COMPLETED,
                InappifyStorePurchaseStatus.RESTORED,
                InappifyStorePurchaseStatus.ALREADY_PROCESSED,
                -> InappifyPurchaseStatus.DONE

                InappifyStorePurchaseStatus.PROCESSING,
                InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
                InappifyStorePurchaseStatus.CONSUME_REQUIRED,
                InappifyStorePurchaseStatus.REJECTED,
                -> null
            },
            packageIdentifier = packageIdentifier,
            url = null,
            checkoutId = null,
            checkoutStatus = null,
            nextActionType = null,
            deliveryId = deliveryId,
            verificationRequestId = verificationRequestId,
            alreadyProcessed = alreadyProcessed,
            storePurchaseStatus = status,
        )

        internal fun directDeliveryResult(
            deliveryId: Long,
            productIdentifier: String,
            status: InappifyStorePurchaseStatus,
        ): InappifyPurchase = InappifyPurchase(
            attemptId = "direct-delivery-$deliveryId",
            productIdentifier = productIdentifier,
            offeringIdentifier = "",
            market = InappifyMarket.NONE,
            purchaseStatus = if (status == InappifyStorePurchaseStatus.COMPLETED) {
                InappifyPurchaseStatus.DONE
            } else {
                null
            },
            packageIdentifier = null,
            url = null,
            checkoutId = null,
            checkoutStatus = null,
            nextActionType = null,
            deliveryId = deliveryId,
            verificationRequestId = null,
            alreadyProcessed = false,
            storePurchaseStatus = status,
        )
    }
}
