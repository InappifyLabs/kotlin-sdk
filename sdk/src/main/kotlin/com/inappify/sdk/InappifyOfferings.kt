package com.inappify.sdk

import com.inappify.sdk.internal.domain.InappifyOfferingResolver

/** Immutable offering collection and its server targeting rules. */
public class InappifyOfferings private constructor(
    offerings: List<InappifyOffering>?,
    rules: List<InappifyRule>?,
    public val forceVersion: Long?,
    public val fetchedAt: String?,
    public val currentOfferingIdentifier: String?,
    placements: Map<String, String?>?,
    public val usesServerTargeting: Boolean,
) {

    /** Original V1 constructor, including its default-argument JVM ABI. */
    @JvmOverloads
    public constructor(
        offerings: List<InappifyOffering>? = null,
        rules: List<InappifyRule>? = null,
        forceVersion: Long? = null,
        fetchedAt: String? = null,
    ) : this(offerings, rules, forceVersion, fetchedAt, null, null, false)

    /** Go v2 placement mapping; null values deliberately fall back to current/default. */
    public val placements: Map<String, String?>? = placements?.let {
        java.util.Collections.unmodifiableMap(LinkedHashMap(it))
    }

    /** Defensive, unmodifiable copy of all available offerings. */
    public val offerings: List<InappifyOffering>? = immutableList(offerings)

    /** Defensive, unmodifiable copy of ordered targeting rules. */
    public val rules: List<InappifyRule>? = immutableList(rules)

    /**
     * Returns the first server-designated default offering.
     *
     * Returns null when the collection is null.
     *
     * @throws NoSuchElementException when a non-null collection has no default
     * offering.
     */
    public fun defaultOffering(): InappifyOffering? =
        offerings?.first { it.isDefault == true }

    /** Resolves the current offering using ordered server targeting rules. */
    @JvmOverloads
    public fun currentOffering(
        context: InappifyOfferingEvaluationContext,
        placement: String? = null,
    ): InappifyOffering? = resolveOffering(context, placement)

    /** Resolves an offering from explicit, platform-independent inputs. */
    @JvmOverloads
    public fun resolveOffering(
        context: InappifyOfferingEvaluationContext,
        placement: String? = null,
    ): InappifyOffering? = try {
        if (usesServerTargeting) {
            val target = placements?.get(placement)
            offerings?.firstOrNull { target != null && it.identifier == target }
                ?: offerings?.firstOrNull { currentOfferingIdentifier != null && it.identifier == currentOfferingIdentifier }
                ?: offerings?.firstOrNull { it.isDefault == true }
        } else InappifyOfferingResolver.resolve(this, context, placement)
    } catch (_: RuntimeException) {
        null
    }

    /** Evaluates one server targeting condition against [context]. */
    @JvmOverloads
    public fun evaluateField(
        fieldName: String,
        operator: String,
        data: String,
        context: InappifyOfferingEvaluationContext,
        attributeKey: String? = null,
    ): Boolean = InappifyOfferingResolver.evaluateField(
        fieldName = fieldName,
        operator = operator,
        data = data,
        context = context,
        attributeKey = attributeKey,
    )

    /** Applies one comparison operator supported by targeting rules. */
    public fun evaluateOperator(left: Any?, operator: String, right: Any?): Boolean =
        InappifyOfferingResolver.evaluateOperator(left, operator, right)

    /** Converts a dotted version using the targeting comparison format. */
    public fun convertVersionToDouble(version: String): Double =
        InappifyOfferingResolver.convertVersionToDouble(version)

    /** Summarizes the collection without exposing rule or metadata content. */
    public override fun toString(): String =
        "InappifyOfferings(" +
            "offerings=${offerings.redactedCollection()}, " +
            "rules=${rules.redactedCollection()}, " +
            "forceVersion=$forceVersion, " +
            "fetchedAt=$fetchedAt" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyOfferings &&
            offerings == other.offerings &&
            rules == other.rules &&
            forceVersion == other.forceVersion &&
            fetchedAt == other.fetchedAt && currentOfferingIdentifier == other.currentOfferingIdentifier &&
            placements == other.placements && usesServerTargeting == other.usesServerTargeting

    public override fun hashCode(): Int {
        var result = offerings?.hashCode() ?: 0
        result = 31 * result + (rules?.hashCode() ?: 0)
        result = 31 * result + (forceVersion?.hashCode() ?: 0)
        result = 31 * result + (fetchedAt?.hashCode() ?: 0)
        if (usesServerTargeting) {
            result = 31 * result + (currentOfferingIdentifier?.hashCode() ?: 0)
            result = 31 * result + (placements?.hashCode() ?: 0)
        }
        return result
    }

    internal companion object {
        internal fun serverTargeted(offerings: List<InappifyOffering>?, forceVersion: Long?,
            fetchedAt: String?, current: String?, placements: Map<String, String?>?): InappifyOfferings =
            InappifyOfferings(offerings, null, forceVersion, fetchedAt, current, placements, true)
    }
}

/** Immutable server offering with opaque paywall data for application-defined rendering. */
public class InappifyOffering public constructor(
    public val identifier: String? = null,
    public val isDefault: Boolean? = null,
    public val serverDescription: String? = null,
    public val trialDays: Long? = null,
    metadata: Any? = null,
    packages: List<InappifyPackage>? = null,
    paywall: Map<String, Any?>? = null,
) {

    /** Deeply immutable JSON-compatible metadata supplied by the service. */
    public val metadata: Any? = immutableJsonValue(metadata)

    /** Defensive, unmodifiable copy of packages in this offering. */
    public val packages: List<InappifyPackage>? = immutableList(packages)

    /** Deeply immutable opaque paywall payload. */
    public val paywall: Map<String, Any?>? = immutableJsonMap(paywall)

    /** Redacts opaque metadata and paywall content. */
    public override fun toString(): String =
        "InappifyOffering(" +
            "identifier=$identifier, " +
            "isDefault=$isDefault, " +
            "serverDescription=$serverDescription, " +
            "trialDays=$trialDays, " +
            "metadata=${metadata.redactedValue()}, " +
            "packages=${packages.redactedCollection()}, " +
            "paywall=${paywall.redactedValue()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyOffering &&
            identifier == other.identifier &&
            isDefault == other.isDefault &&
            serverDescription == other.serverDescription &&
            trialDays == other.trialDays &&
            metadata == other.metadata &&
            packages == other.packages &&
            paywall == other.paywall

    public override fun hashCode(): Int {
        var result = identifier?.hashCode() ?: 0
        result = 31 * result + (isDefault?.hashCode() ?: 0)
        result = 31 * result + (serverDescription?.hashCode() ?: 0)
        result = 31 * result + (trialDays?.hashCode() ?: 0)
        result = 31 * result + (metadata?.hashCode() ?: 0)
        result = 31 * result + (packages?.hashCode() ?: 0)
        result = 31 * result + (paywall?.hashCode() ?: 0)
        return result
    }
}

/** Immutable purchasable package associated with an offering. */
public class InappifyPackage public constructor(
    public val identifier: String? = null,
    public val packageType: Long? = null,
    public val description: String? = null,
    public val entitlement: String? = null,
    public val name: String? = null,
    public val discountPercent: Long? = null,
    public val product: InappifyStoreProduct? = null,
) {

    public override fun toString(): String =
        "InappifyPackage(" +
            "identifier=$identifier, " +
            "packageType=$packageType, " +
            "description=$description, " +
            "entitlement=$entitlement, " +
            "name=$name, " +
            "discountPercent=$discountPercent, " +
            "product=$product" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPackage &&
            identifier == other.identifier &&
            packageType == other.packageType &&
            description == other.description &&
            entitlement == other.entitlement &&
            name == other.name &&
            discountPercent == other.discountPercent &&
            product == other.product

    public override fun hashCode(): Int {
        var result = identifier?.hashCode() ?: 0
        result = 31 * result + (packageType?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (entitlement?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (discountPercent?.hashCode() ?: 0)
        result = 31 * result + (product?.hashCode() ?: 0)
        return result
    }
}

/** Immutable store product referenced by an offering package. */
public class InappifyStoreProduct public constructor(
    public val identifier: String? = null,
    public val name: String? = null,
    prices: List<InappifyPrice>? = null,
    public val dollarPrice: Double? = null,
    public val trialDays: Long? = null,
) {

    /** Defensive, unmodifiable copy of localized product prices. */
    public val prices: List<InappifyPrice>? = immutableList(prices)

    public override fun toString(): String =
        "InappifyStoreProduct(" +
            "identifier=$identifier, " +
            "name=$name, " +
            "prices=${prices.redactedCollection()}, " +
            "dollarPrice=$dollarPrice, " +
            "trialDays=$trialDays" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyStoreProduct &&
            identifier == other.identifier &&
            name == other.name &&
            prices == other.prices &&
            dollarPrice == other.dollarPrice &&
            trialDays == other.trialDays

    public override fun hashCode(): Int {
        var result = identifier?.hashCode() ?: 0
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (prices?.hashCode() ?: 0)
        result = 31 * result + (dollarPrice?.hashCode() ?: 0)
        result = 31 * result + (trialDays?.hashCode() ?: 0)
        return result
    }
}

/** Immutable monetary data returned for a store product. */
public class InappifyPrice public constructor(
    public val id: Long? = null,
    public val appProductId: Long? = null,
    public val currency: String? = null,
    amount: Number? = null,
    originalAmount: Number? = null,
    discountAmount: Number? = null,
    public val price: Long? = null,
    public val createdAt: String? = null,
    public val updatedAt: String? = null,
    public val originPrice: Long? = null,
) {

    /** Integral values are normalized to [Long], while fractional values use [Double]. */
    public val amount: Number? = normalizeNumber(amount)

    /** Integral values are normalized to [Long], while fractional values use [Double]. */
    public val originalAmount: Number? = normalizeNumber(originalAmount)

    /** Integral values are normalized to [Long], while fractional values use [Double]. */
    public val discountAmount: Number? = normalizeNumber(discountAmount)

    public override fun toString(): String =
        "InappifyPrice(" +
            "id=$id, " +
            "appProductId=$appProductId, " +
            "currency=$currency, " +
            "amount=$amount, " +
            "originalAmount=$originalAmount, " +
            "discountAmount=$discountAmount, " +
            "price=$price, " +
            "createdAt=$createdAt, " +
            "updatedAt=$updatedAt, " +
            "originPrice=$originPrice" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPrice &&
            id == other.id &&
            appProductId == other.appProductId &&
            currency == other.currency &&
            amount == other.amount &&
            originalAmount == other.originalAmount &&
            discountAmount == other.discountAmount &&
            price == other.price &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            originPrice == other.originPrice

    public override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (appProductId?.hashCode() ?: 0)
        result = 31 * result + (currency?.hashCode() ?: 0)
        result = 31 * result + (amount?.hashCode() ?: 0)
        result = 31 * result + (originalAmount?.hashCode() ?: 0)
        result = 31 * result + (discountAmount?.hashCode() ?: 0)
        result = 31 * result + (price?.hashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        result = 31 * result + (updatedAt?.hashCode() ?: 0)
        result = 31 * result + (originPrice?.hashCode() ?: 0)
        return result
    }
}
