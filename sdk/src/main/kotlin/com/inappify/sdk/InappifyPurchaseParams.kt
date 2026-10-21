package com.inappify.sdk

import android.app.Activity

/**
 * Immutable parameters for purchasing a package returned by an offering.
 *
 * Catalog context supplies the product, offering, package and product type.
 * The configured session supplies the store and billing key. These parameters
 * only adapt to the existing purchase API; they do not own payment or recovery state.
 */
public class InappifyPurchaseParams private constructor(
    internal val activity: Activity?,
    public val packageToPurchase: InappifyPackage,
    public val idempotencyKey: String?,
    public val paywallId: Long?,
    public val paywallRevision: Long?,
) {

    /** Builds one invocation; the host must not retain Activity-backed parameters beyond its lifecycle. */
    public class Builder private constructor(
        private val activity: Activity?,
        private val packageToPurchase: InappifyPackage,
        @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
    ) {
        /** Starts a native store purchase using the current foreground Activity. */
        public constructor(activity: Activity, packageToPurchase: InappifyPackage) :
            this(activity, packageToPurchase, Unit)

        /** Direct checkout and resuming an existing paid attempt do not need store UI. */
        public constructor(packageToPurchase: InappifyPackage) : this(null, packageToPurchase, Unit)

        private var idempotencyKey: String? = null
        private var paywallId: Long? = null
        private var paywallRevision: Long? = null

        /** Reuse a key only when resuming the same logical store purchase. */
        public fun idempotencyKey(idempotencyKey: String): Builder = apply {
            this.idempotencyKey = idempotencyKey
        }

        /** Direct Paywall attribution, with the same validation as the existing request helper. */
        public fun paywallAttribution(id: Long, revision: Long): Builder = apply {
            require(id > 0 && revision > 0) { "Paywall ID and revision must be positive." }
            paywallId = id
            paywallRevision = revision
        }

        public fun build(): InappifyPurchaseParams = InappifyPurchaseParams(
            activity, packageToPurchase, idempotencyKey, paywallId, paywallRevision,
        )
    }

    internal fun toRequest(): InappifyPurchaseRequest? {
        val productIdentifier = packageToPurchase.product?.identifier?.takeIf(String::isNotBlank) ?: return null
        val offeringIdentifier = packageToPurchase.offeringIdentifier?.takeIf(String::isNotBlank) ?: return null
        val packageIdentifier = packageToPurchase.identifier?.takeIf(String::isNotBlank) ?: return null
        val productType = packageToPurchase.product?.productType
        val request = if (productType == null) InappifyPurchaseRequest(
            productIdentifier, offeringIdentifier, packageIdentifier,
            idempotencyKey = idempotencyKey,
        ) else InappifyPurchaseRequest(
            productIdentifier, offeringIdentifier, packageIdentifier,
            idempotencyKey = idempotencyKey, productType = productType,
        )
        return if (paywallId != null && paywallRevision != null)
            request.withPaywallAttribution(paywallId, paywallRevision) else request
    }

    /** Avoids exposing Activity, catalog identifiers or attempt correlation values. */
    public override fun toString(): String =
        "InappifyPurchaseParams(packageToPurchase=<redacted>, " +
            "idempotencyKey=${idempotencyKey.redactedValue()}, " +
            "hasPaywallAttribution=${paywallId != null})"
}
