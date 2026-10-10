package com.inappify.sdk

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.math.BigDecimal

internal fun <T> immutableList(values: List<T>?): List<T>? =
    values?.let { Collections.unmodifiableList(ArrayList(it)) }

internal fun immutableJsonMap(values: Map<String, Any?>?): Map<String, Any?>? =
    values?.let { source ->
        val copy = LinkedHashMap<String, Any?>(source.size)
        source.forEach { (key, value) -> copy[key] = immutableJsonValue(value) }
        Collections.unmodifiableMap(copy)
    }

internal fun immutableJsonValue(value: Any?): Any? =
    when (value) {
        null,
        is String,
        is Boolean,
        -> value

        is Number -> normalizeNumber(value)

        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (key, item) ->
                require(key is String) { "JSON object keys must be strings." }
                copy[key] = immutableJsonValue(item)
            }
            Collections.unmodifiableMap(copy)
        }

        is List<*> ->
            Collections.unmodifiableList(
                value.mapTo(ArrayList(value.size), ::immutableJsonValue),
            )

        else -> throw IllegalArgumentException(
            "Unsupported JSON value type: ${value::class.java.name}.",
        )
    }

internal fun normalizeNumber(value: Number?): Number? {
    if (value == null) return null
    val decimal = try {
        BigDecimal(value.toString())
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException("JSON numbers must be finite.", error)
    }
    if (decimal.stripTrailingZeros().scale() <= 0) {
        return try {
            decimal.longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Integral JSON numbers must fit in 64 bits.", error)
        }
    }
    val numeric = decimal.toDouble()
    require(numeric.isFinite()) { "JSON numbers must be finite." }
    return numeric
}

internal fun Any?.redactedValue(): String = if (this == null) "null" else "<redacted>"

internal fun Collection<*>?.redactedCollection(): String =
    if (this == null) "null" else "<${size} items>"
