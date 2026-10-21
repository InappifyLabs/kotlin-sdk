package com.inappify.sdk

/**
 * Internal purchase command under the configured, verified session.
 *
 * The server's storePlatform selects checkout. [market] cannot override that
 * route. Use the explicit [productType] constructor for store products.
 * Request-level [apiKey], [marketKey], [country] and [appVersion] overrides,
 * caller-supplied lost-purchase evidence, discounts and crypto checkout are
 * unsupported by the production client. Configure the session instead.
 *
 * [apiKey], [marketKey], [lostPurchaseToken] and [dynamicPriceToken] are
 * sensitive and must not be logged. [idempotencyKey], when supplied, is a
 * unique logical-attempt identifier of 1-128 letters, digits, `-`, `_`, `.`,
 * or `:`. Reuse it only when resuming that same purchase.
 */
internal class InappifyPurchaseRequest private constructor(
    public val productIdentifier: String,
    public val offeringIdentifier: String,
    public val packageIdentifier: String? = null,
    public val discountCode: String? = null,
    public val country: String? = null,
    public val appVersion: String? = null,
    public val apiKey: String? = null,
    public val discount: Long = 0L,
    @Deprecated("Legacy compatibility only. Crypto checkout is unsupported; use InappifyPurchaseParams.")
    public val isCrypto: Boolean = false,
    public val market: InappifyMarket = InappifyMarket.NONE,
    public val marketKey: String? = null,
    public val isLostPurchase: Boolean = false,
    public val lostPurchaseToken: String? = null,
    public val lostPurchaseTime: Long? = null,
    public val idempotencyKey: String? = null,
    public val dynamicPriceToken: String? = null,
    public val productType: InappifyProductType?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
    public val paywallId: Long? = null,
    public val paywallRevision: Long? = null,
) {

    /** Adds Direct checkout attribution without replacing the constructor. */
    public fun withPaywallAttribution(id: Long, revision: Long): InappifyPurchaseRequest {
        require(id > 0 && revision > 0) { "Paywall ID and revision must be positive." }
        return InappifyPurchaseRequest(productIdentifier, offeringIdentifier, packageIdentifier,
            discountCode, country, appVersion, apiKey, discount, isCrypto, market, marketKey,
            isLostPurchase, lostPurchaseToken, lostPurchaseTime, idempotencyKey, dynamicPriceToken,
            productType, Unit, id, revision)
    }

    /**
     * Creates a request without an explicit product type.
     *
     * Bazaar products use IN_APP billing without assuming non-consumable
     * ownership. For Bazaar, a verified delivery response identifies
     * a consumable; the host must grant it idempotently and confirm delivery,
     * or register a delivery handler. No automatic grant/consume is implied.
     * Use the explicit-product-type overload for new integrations, especially
     * subscriptions, which require subscription billing.
     */
    @JvmOverloads
    public constructor(
        productIdentifier: String,
        offeringIdentifier: String,
        packageIdentifier: String? = null,
        discountCode: String? = null,
        country: String? = null,
        appVersion: String? = null,
        apiKey: String? = null,
        discount: Long = 0L,
        isCrypto: Boolean = false,
        market: InappifyMarket = InappifyMarket.NONE,
        marketKey: String? = null,
        isLostPurchase: Boolean = false,
        lostPurchaseToken: String? = null,
        lostPurchaseTime: Long? = null,
        idempotencyKey: String? = null,
        dynamicPriceToken: String? = null,
    ) : this(
        productIdentifier = productIdentifier,
        offeringIdentifier = offeringIdentifier,
        packageIdentifier = packageIdentifier,
        discountCode = discountCode,
        country = country,
        appVersion = appVersion,
        apiKey = apiKey,
        discount = discount,
        isCrypto = isCrypto,
        market = market,
        marketKey = marketKey,
        isLostPurchase = isLostPurchase,
        lostPurchaseToken = lostPurchaseToken,
        lostPurchaseTime = lostPurchaseTime,
        idempotencyKey = idempotencyKey,
        dynamicPriceToken = dynamicPriceToken,
        productType = null,
        constructorMarker = Unit,
    )

    /**
     * Creates a store-aware request with an explicit [productType].
     *
     * [productType] is the final required parameter so the existing
     * constructor ABI remains available and Java calls with a null third
     * argument continue to resolve to `packageIdentifier` unambiguously.
     */
    public constructor(
        productIdentifier: String,
        offeringIdentifier: String,
        packageIdentifier: String? = null,
        discountCode: String? = null,
        country: String? = null,
        appVersion: String? = null,
        apiKey: String? = null,
        discount: Long = 0L,
        isCrypto: Boolean = false,
        market: InappifyMarket = InappifyMarket.NONE,
        marketKey: String? = null,
        isLostPurchase: Boolean = false,
        lostPurchaseToken: String? = null,
        lostPurchaseTime: Long? = null,
        idempotencyKey: String? = null,
        dynamicPriceToken: String? = null,
        productType: InappifyProductType,
    ) : this(
        productIdentifier = productIdentifier,
        offeringIdentifier = offeringIdentifier,
        packageIdentifier = packageIdentifier,
        discountCode = discountCode,
        country = country,
        appVersion = appVersion,
        apiKey = apiKey,
        discount = discount,
        isCrypto = isCrypto,
        market = market,
        marketKey = marketKey,
        isLostPurchase = isLostPurchase,
        lostPurchaseToken = lostPurchaseToken,
        lostPurchaseTime = lostPurchaseTime,
        idempotencyKey = idempotencyKey,
        dynamicPriceToken = dynamicPriceToken,
        productType = productType,
        constructorMarker = Unit,
    )

    /** Returns a representation that never exposes identifiers or correlation values. */
    public override fun toString(): String {
        val legacy = "InappifyPurchaseRequest(" +
            "productIdentifier=${productIdentifier.redactedValue()}, " +
            "offeringIdentifier=${offeringIdentifier.redactedValue()}, " +
            "packageIdentifier=${packageIdentifier.redactedValue()}, " +
            "discountCode=${discountCode.redactedValue()}, " +
            "country=$country, " +
            "appVersion=$appVersion, " +
            "apiKey=${apiKey.redactedValue()}, " +
            "discount=$discount, " +
            "isCrypto=$isCrypto, " +
            "market=$market, " +
            "marketKey=${marketKey.redactedValue()}, " +
            "isLostPurchase=$isLostPurchase, " +
            "lostPurchaseToken=${lostPurchaseToken.redactedValue()}, " +
            "lostPurchaseTime=${lostPurchaseTime.redactedValue()}, " +
            "idempotencyKey=${idempotencyKey.redactedValue()}, " +
            "dynamicPriceToken=${dynamicPriceToken.redactedValue()}"
        return if (productType == null) {
            "$legacy)"
        } else {
            "$legacy, productType=$productType)"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is InappifyPurchaseRequest &&
            productIdentifier == other.productIdentifier &&
            offeringIdentifier == other.offeringIdentifier &&
            packageIdentifier == other.packageIdentifier &&
            discountCode == other.discountCode &&
            country == other.country &&
            appVersion == other.appVersion &&
            apiKey == other.apiKey &&
            discount == other.discount &&
            isCrypto == other.isCrypto &&
            market == other.market &&
            marketKey == other.marketKey &&
            isLostPurchase == other.isLostPurchase &&
            lostPurchaseToken == other.lostPurchaseToken &&
            lostPurchaseTime == other.lostPurchaseTime &&
            idempotencyKey == other.idempotencyKey &&
            dynamicPriceToken == other.dynamicPriceToken &&
            productType == other.productType && paywallId == other.paywallId && paywallRevision == other.paywallRevision

    public override fun hashCode(): Int {
        var result = productIdentifier.hashCode()
        result = 31 * result + offeringIdentifier.hashCode()
        result = 31 * result + (packageIdentifier?.hashCode() ?: 0)
        result = 31 * result + (discountCode?.hashCode() ?: 0)
        result = 31 * result + (country?.hashCode() ?: 0)
        result = 31 * result + (appVersion?.hashCode() ?: 0)
        result = 31 * result + (apiKey?.hashCode() ?: 0)
        result = 31 * result + discount.hashCode()
        result = 31 * result + isCrypto.hashCode()
        result = 31 * result + market.hashCode()
        result = 31 * result + (marketKey?.hashCode() ?: 0)
        result = 31 * result + isLostPurchase.hashCode()
        result = 31 * result + (lostPurchaseToken?.hashCode() ?: 0)
        result = 31 * result + (lostPurchaseTime?.hashCode() ?: 0)
        result = 31 * result + (idempotencyKey?.hashCode() ?: 0)
        result = 31 * result + (dynamicPriceToken?.hashCode() ?: 0)
        if (productType != null) result = 31 * result + productType.hashCode()
        if (paywallId != null) result = 31 * result + paywallId.hashCode()
        if (paywallRevision != null) result = 31 * result + paywallRevision.hashCode()
        return result
    }
}
