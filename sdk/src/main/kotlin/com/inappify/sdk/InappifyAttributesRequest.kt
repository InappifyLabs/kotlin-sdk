package com.inappify.sdk

/** Reserved customer attributes supported by Inappify. */
public enum class InappifyReservedAttribute {
    EMAIL,
    APNS_TOKEN,
    DISPLAY_NAME,
    FCM_TOKEN,
    IDFA,
    IDFV,
    IP,
    PHONE_NUMBER,
    CAMPAIGN,
    KEYWORD,
}

/** Immutable request for storing one reserved customer attribute. */
public class InappifyReservedAttributeRequest public constructor(
    public val attribute: InappifyReservedAttribute,
    public val value: String,
) {

    /** Redacts the value because reserved attributes commonly contain PII. */
    public override fun toString(): String =
        "InappifyReservedAttributeRequest(" +
            "attribute=$attribute, " +
            "value=<redacted>" +
            ")"
}

/** Validation rules for custom attribute keys and values. */
internal fun InappifyAttribute.isValidCustomAttribute(): Boolean {
    val candidateKey = key ?: return false
    return CUSTOM_ATTRIBUTE_KEY.matches(candidateKey) &&
        candidateKey.isNotEmpty() &&
        candidateKey.length <= MAX_ATTRIBUTE_KEY_LENGTH &&
        (value?.length ?: 0) <= MAX_ATTRIBUTE_VALUE_LENGTH
}

/** A null or whitespace-only custom value deletes its key. */
internal fun InappifyAttribute.removesCustomAttribute(): Boolean =
    value == null || value.trim().isEmpty()

/** Backend key for this reserved attribute. */
internal val InappifyReservedAttribute.backendKey: String
    get() = when (this) {
        InappifyReservedAttribute.EMAIL -> "\$email"
        InappifyReservedAttribute.APNS_TOKEN -> "\$apnsTokens"
        InappifyReservedAttribute.DISPLAY_NAME -> "\$displayName"
        InappifyReservedAttribute.FCM_TOKEN -> "\$fcmTokens"
        InappifyReservedAttribute.IDFA -> "\$idfa"
        InappifyReservedAttribute.IDFV -> "\$idfv"
        InappifyReservedAttribute.IP -> "\$ip"
        InappifyReservedAttribute.PHONE_NUMBER -> "\$phoneNumber"
        InappifyReservedAttribute.CAMPAIGN -> "\$campaign"
        InappifyReservedAttribute.KEYWORD -> "\$keyword"
    }

/** Validation rules for reserved attribute values. */
internal fun InappifyReservedAttributeRequest.hasValidValue(): Boolean =
    when (attribute) {
        InappifyReservedAttribute.EMAIL ->
            value.isEmpty() || RESERVED_EMAIL_PATTERN.matches(value)

        else -> value.isEmpty() || value.length <= MAX_ATTRIBUTE_VALUE_LENGTH
    }

private const val MAX_ATTRIBUTE_KEY_LENGTH = 40
private const val MAX_ATTRIBUTE_VALUE_LENGTH = 250
private val CUSTOM_ATTRIBUTE_KEY = Regex("^(?!\\$)[a-zA-Z0-9_-]+$")
private val RESERVED_EMAIL_PATTERN = Regex("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")
