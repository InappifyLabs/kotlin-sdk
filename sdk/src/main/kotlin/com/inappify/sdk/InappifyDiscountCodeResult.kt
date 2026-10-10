package com.inappify.sdk

/** Immutable payment link returned by discount-code validation. */
public class InappifyPurchaseLink public constructor(
    public val offering: String,
    public val url: String,
) {

    /** Redacts the checkout URL while retaining its non-sensitive offering. */
    public override fun toString(): String =
        "InappifyPurchaseLink(offering=$offering, url=<redacted>)"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPurchaseLink && offering == other.offering && url == other.url

    public override fun hashCode(): Int = 31 * offering.hashCode() + url.hashCode()
}

/** Discount-code validation result returned by Inappify. */
public class InappifyDiscountCodeResult public constructor(
    public val isValid: Boolean? = null,
    public val errorCode: Long? = null,
    public val code: String? = null,
    public val discountId: Long? = null,
    public val discountCodeId: Long? = null,
    public val percent: Long? = null,
    public val message: String? = null,
    paymentLinks: List<InappifyPurchaseLink>? = null,
    public val offering: InappifyOffering? = null,
) {

    /** Defensive, unmodifiable copy of payment links. */
    public val paymentLinks: List<InappifyPurchaseLink>? = immutableList(paymentLinks)

    /** Redacts discount identifiers, provider messages, and checkout links. */
    public override fun toString(): String =
        "InappifyDiscountCodeResult(" +
            "isValid=$isValid, " +
            "errorCode=$errorCode, " +
            "code=${code.redactedValue()}, " +
            "discountId=${discountId.redactedValue()}, " +
            "discountCodeId=${discountCodeId.redactedValue()}, " +
            "percent=$percent, " +
            "message=${message.redactedValue()}, " +
            "paymentLinks=${paymentLinks.redactedCollection()}, " +
            "offering=$offering" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyDiscountCodeResult &&
            isValid == other.isValid &&
            errorCode == other.errorCode &&
            code == other.code &&
            discountId == other.discountId &&
            discountCodeId == other.discountCodeId &&
            percent == other.percent &&
            message == other.message &&
            paymentLinks == other.paymentLinks &&
            offering == other.offering

    public override fun hashCode(): Int {
        var result = isValid?.hashCode() ?: 0
        result = 31 * result + (errorCode?.hashCode() ?: 0)
        result = 31 * result + (code?.hashCode() ?: 0)
        result = 31 * result + (discountId?.hashCode() ?: 0)
        result = 31 * result + (discountCodeId?.hashCode() ?: 0)
        result = 31 * result + (percent?.hashCode() ?: 0)
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (paymentLinks?.hashCode() ?: 0)
        result = 31 * result + (offering?.hashCode() ?: 0)
        return result
    }
}
