package com.inappify.sdk

/** Immutable customer state returned by Inappify. */
public class InappifyCustomerInfo public constructor(
    public val originalAppUserId: String? = null,
    public val firstSeen: String? = null,
    public val requestDate: String? = null,
    public val latestExpirationDate: String? = null,
    public val hasUsedTrial: Boolean? = null,
    entitlements: List<InappifyEntitlement>? = null,
    transactions: List<InappifyTransaction>? = null,
    attributes: List<InappifyAttribute>? = null,
) {

    /** Defensive, unmodifiable copy of the customer's entitlements. */
    public val entitlements: List<InappifyEntitlement>? = immutableList(entitlements)

    /** Defensive, unmodifiable copy of the customer's transactions. */
    public val transactions: List<InappifyTransaction>? = immutableList(transactions)

    /** Defensive, unmodifiable copy of the customer's custom attributes. */
    public val attributes: List<InappifyAttribute>? = immutableList(attributes)

    /** Redacts the customer identifier and all nested customer-owned values. */
    public override fun toString(): String =
        "InappifyCustomerInfo(" +
            "originalAppUserId=${originalAppUserId.redactedValue()}, " +
            "firstSeen=${firstSeen.redactedValue()}, " +
            "requestDate=${requestDate.redactedValue()}, " +
            "latestExpirationDate=${latestExpirationDate.redactedValue()}, " +
            "hasUsedTrial=$hasUsedTrial, " +
            "entitlements=${entitlements.redactedCollection()}, " +
            "transactions=${transactions.redactedCollection()}, " +
            "attributes=${attributes.redactedCollection()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyCustomerInfo &&
            originalAppUserId == other.originalAppUserId &&
            firstSeen == other.firstSeen &&
            requestDate == other.requestDate &&
            latestExpirationDate == other.latestExpirationDate &&
            hasUsedTrial == other.hasUsedTrial &&
            entitlements == other.entitlements &&
            transactions == other.transactions &&
            attributes == other.attributes

    public override fun hashCode(): Int {
        var result = originalAppUserId?.hashCode() ?: 0
        result = 31 * result + (firstSeen?.hashCode() ?: 0)
        result = 31 * result + (requestDate?.hashCode() ?: 0)
        result = 31 * result + (latestExpirationDate?.hashCode() ?: 0)
        result = 31 * result + (hasUsedTrial?.hashCode() ?: 0)
        result = 31 * result + (entitlements?.hashCode() ?: 0)
        result = 31 * result + (transactions?.hashCode() ?: 0)
        result = 31 * result + (attributes?.hashCode() ?: 0)
        return result
    }
}

/** Immutable entitlement granted to the current customer. */
public class InappifyEntitlement public constructor(
    public val identifier: String? = null,
    public val isActive: Boolean? = null,
    public val isSandbox: Boolean? = null,
    public val periodType: String? = null,
    public val purchaseDate: String? = null,
    public val expirationDate: String? = null,
    public val ownershipType: String? = null,
    public val entitlementType: String? = null,
    public val purchaseStoreRefHash: String? = null,
    /** Marketplace purchase time represented as epoch milliseconds. */
    public val purchaseStoreTime: Long? = null,
) {

    /** Redacts the store reference while retaining useful entitlement state. */
    public override fun toString(): String =
        "InappifyEntitlement(" +
            "identifier=$identifier, " +
            "isActive=$isActive, " +
            "isSandbox=$isSandbox, " +
            "periodType=$periodType, " +
            "purchaseDate=${purchaseDate.redactedValue()}, " +
            "expirationDate=${expirationDate.redactedValue()}, " +
            "ownershipType=$ownershipType, " +
            "entitlementType=$entitlementType, " +
            "purchaseStoreRefHash=${purchaseStoreRefHash.redactedValue()}, " +
            "purchaseStoreTime=${purchaseStoreTime.redactedValue()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyEntitlement &&
            identifier == other.identifier &&
            isActive == other.isActive &&
            isSandbox == other.isSandbox &&
            periodType == other.periodType &&
            purchaseDate == other.purchaseDate &&
            expirationDate == other.expirationDate &&
            ownershipType == other.ownershipType &&
            entitlementType == other.entitlementType &&
            purchaseStoreRefHash == other.purchaseStoreRefHash &&
            purchaseStoreTime == other.purchaseStoreTime

    public override fun hashCode(): Int {
        var result = identifier?.hashCode() ?: 0
        result = 31 * result + (isActive?.hashCode() ?: 0)
        result = 31 * result + (isSandbox?.hashCode() ?: 0)
        result = 31 * result + (periodType?.hashCode() ?: 0)
        result = 31 * result + (purchaseDate?.hashCode() ?: 0)
        result = 31 * result + (expirationDate?.hashCode() ?: 0)
        result = 31 * result + (ownershipType?.hashCode() ?: 0)
        result = 31 * result + (entitlementType?.hashCode() ?: 0)
        result = 31 * result + (purchaseStoreRefHash?.hashCode() ?: 0)
        result = 31 * result + (purchaseStoreTime?.hashCode() ?: 0)
        return result
    }
}

/** Immutable customer attribute. Attribute values may contain PII. */
public class InappifyAttribute public constructor(
    public val key: String? = null,
    public val value: String? = null,
) {

    /** Redacts both key and value to avoid leaking customer-defined data. */
    public override fun toString(): String =
        "InappifyAttribute(key=${key.redactedValue()}, value=${value.redactedValue()})"

    public override fun equals(other: Any?): Boolean =
        other is InappifyAttribute && key == other.key && value == other.value

    public override fun hashCode(): Int = 31 * (key?.hashCode() ?: 0) + (value?.hashCode() ?: 0)
}

/** Immutable transaction summary returned as part of customer information. */
public class InappifyTransaction public constructor(
    public val number: String? = null,
    public val status: Long? = null,
    public val amount: Long? = null,
    public val transactionDate: String? = null,
    public val trackingNumber: String? = null,
    public val isCryptoGate: Boolean? = null,
    public val packageName: String? = null,
    public val storePurchaseRefId: String? = null,
    public val isTrial: Boolean? = null,
) {

    /** Redacts transaction and store identifiers. */
    public override fun toString(): String =
        "InappifyTransaction(" +
            "number=${number.redactedValue()}, " +
            "status=$status, " +
            "amount=${amount.redactedValue()}, " +
            "transactionDate=${transactionDate.redactedValue()}, " +
            "trackingNumber=${trackingNumber.redactedValue()}, " +
            "isCryptoGate=$isCryptoGate, " +
            "packageName=$packageName, " +
            "storePurchaseRefId=${storePurchaseRefId.redactedValue()}, " +
            "isTrial=$isTrial" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyTransaction &&
            number == other.number &&
            status == other.status &&
            amount == other.amount &&
            transactionDate == other.transactionDate &&
            trackingNumber == other.trackingNumber &&
            isCryptoGate == other.isCryptoGate &&
            packageName == other.packageName &&
            storePurchaseRefId == other.storePurchaseRefId &&
            isTrial == other.isTrial

    public override fun hashCode(): Int {
        var result = number?.hashCode() ?: 0
        result = 31 * result + (status?.hashCode() ?: 0)
        result = 31 * result + (amount?.hashCode() ?: 0)
        result = 31 * result + (transactionDate?.hashCode() ?: 0)
        result = 31 * result + (trackingNumber?.hashCode() ?: 0)
        result = 31 * result + (isCryptoGate?.hashCode() ?: 0)
        result = 31 * result + (packageName?.hashCode() ?: 0)
        result = 31 * result + (storePurchaseRefId?.hashCode() ?: 0)
        result = 31 * result + (isTrial?.hashCode() ?: 0)
        return result
    }
}
