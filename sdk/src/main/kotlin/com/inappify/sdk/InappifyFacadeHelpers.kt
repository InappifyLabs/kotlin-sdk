@file:JvmName("InappifyFacadeHelpers")

package com.inappify.sdk

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Returns true when [appUserIdentifier] is absent or contains Inappify's
 * case-sensitive anonymous-customer marker.
 */
public fun isCustomerAnonymous(appUserIdentifier: String?): Boolean =
    appUserIdentifier == null || appUserIdentifier.contains(ANONYMOUS_IDENTIFIER_MARKER)

/** Returns whether this customer has an active entitlement named [name]. */
public fun InappifyCustomerInfo.isActiveEntitlement(name: String): Boolean =
    findActiveEntitlement(name) != null

/** Convenience alias for [isActiveEntitlement]. */
public fun InappifyCustomerInfo.hasEntitlement(identifier: String): Boolean =
    isActiveEntitlement(identifier)

/**
 * Returns the first active entitlement named [identifier], or `null` when none is active.
 *
 * An entitlement is active only when its backend flag is exactly `true`. A missing, empty, or
 * malformed expiration does not deactivate the entitlement; a parseable
 * expiration must be later than the current instant.
 */
public fun InappifyCustomerInfo.findActiveEntitlement(identifier: String): InappifyEntitlement? =
    entitlements?.firstOrNull { entitlement ->
        entitlement.identifier == identifier && entitlement.isEntitlementActive()
    }

/** Convenience alias for [findActiveEntitlement]. */
public fun InappifyCustomerInfo.getEntitlement(identifier: String): InappifyEntitlement? =
    findActiveEntitlement(identifier)

/** Returns whether this snapshot contains an active entitlement named [name]. */
public fun InappifySnapshot.isActiveEntitlement(name: String): Boolean =
    customerInfo?.isActiveEntitlement(name) == true

/** Convenience alias for [isActiveEntitlement]. */
public fun InappifySnapshot.hasEntitlement(identifier: String): Boolean =
    isActiveEntitlement(identifier)

/** Returns the first active entitlement named [identifier] from this snapshot. */
public fun InappifySnapshot.findActiveEntitlement(identifier: String): InappifyEntitlement? =
    customerInfo?.findActiveEntitlement(identifier)

/** Returns the first active entitlement named [identifier] from this snapshot. */
public fun InappifySnapshot.getEntitlement(identifier: String): InappifyEntitlement? =
    findActiveEntitlement(identifier)

private fun InappifyEntitlement.isEntitlementActive(): Boolean {
    if (isActive != true) return false
    val expiration = expirationDate
    if (expiration == null || expiration.isEmpty()) return true
    val expirationMillis = parseIso8601Millis(expiration) ?: return true
    return expirationMillis > System.currentTimeMillis()
}

/** Parses the ISO-8601 variants accepted by the Inappify entitlement contract. */
private fun parseIso8601Millis(value: String): Long? {
    val match = SUPPORTED_DATE_TIME_PATTERN.matchEntire(value) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: 0
    var minute = match.groupValues[5].toIntOrNull() ?: 0
    val second = match.groupValues[6].toIntOrNull() ?: 0
    val milliseconds = match.groupValues[7]
        .take(3)
        .padEnd(3, '0')
        .toIntOrNull() ?: 0
    val hasExplicitZone = match.groupValues[8].isNotEmpty()
    val offsetSign = match.groupValues[9]
    if (offsetSign.isNotEmpty()) {
        val offsetHours = match.groupValues[10].toIntOrNull() ?: return null
        val offsetMinutes = match.groupValues[11].toIntOrNull() ?: 0
        val totalOffsetMinutes = offsetHours * 60 + offsetMinutes
        minute -= if (offsetSign == "-") -totalOffsetMinutes else totalOffsetMinutes
    }

    val timeZone = if (hasExplicitZone) {
        TimeZone.getTimeZone("UTC")
    } else {
        TimeZone.getDefault()
    }
    val calendar = GregorianCalendar(timeZone, Locale.US).apply {
        isLenient = true
        gregorianChange = Date(Long.MIN_VALUE)
        clear()
        if (year <= 0) {
            set(Calendar.ERA, GregorianCalendar.BC)
            set(Calendar.YEAR, 1 - year)
        } else {
            set(Calendar.ERA, GregorianCalendar.AD)
            set(Calendar.YEAR, year)
        }
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, milliseconds)
    }
    val result = try {
        calendar.timeInMillis
    } catch (_: IllegalArgumentException) {
        return null
    }
    return result.takeIf {
        it in -MAX_SUPPORTED_MILLISECONDS_SINCE_EPOCH..
            MAX_SUPPORTED_MILLISECONDS_SINCE_EPOCH
    }
}

private const val ANONYMOUS_IDENTIFIER_MARKER = "InaAnonymous"
private const val MAX_SUPPORTED_MILLISECONDS_SINCE_EPOCH = 8_640_000_000_000_000L
private val SUPPORTED_DATE_TIME_PATTERN = Regex(
    "^([+-]?\\d{4,6})-?(\\d{2})-?(\\d{2})" +
        "(?:[ T](\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?" +
        "( ?[zZ]| ?([-+])(\\d{2})(?::?(\\d{2}))?)?)?$",
)
