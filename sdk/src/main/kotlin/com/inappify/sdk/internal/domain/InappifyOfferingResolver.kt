package com.inappify.sdk.internal.domain

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.inappify.sdk.InappifyCondition
import com.inappify.sdk.InappifyOffering
import com.inappify.sdk.InappifyOfferingEvaluationContext
import com.inappify.sdk.InappifyOfferings
import com.inappify.sdk.InappifyRule
import java.math.BigDecimal

/** Evaluates ordered Inappify offering targeting rules. */
internal object InappifyOfferingResolver {

    internal fun resolve(
        source: InappifyOfferings,
        context: InappifyOfferingEvaluationContext,
        placement: String?,
    ): InappifyOffering? {
        val orderedRules = source.rules.orEmpty().sortedBy { it.sort ?: 0L }
        for (rule in orderedRules) {
            val matches = rule.conditions.orEmpty().all { condition ->
                evaluateField(
                    fieldName = condition.field.orEmpty(),
                    operator = condition.operator.orEmpty(),
                    data = condition.value.orEmpty(),
                    attributeKey = condition.context,
                    context = context,
                )
            }
            if (!matches) continue

            if (placement == null) return ruleDefaultOrFallback(rule, source.offerings)
            val placements = rule.placements
                ?: return ruleDefaultOrFallback(rule, source.offerings)

            for (mapping in placements.placementOfferings.orEmpty()) {
                if (mapping.placementIdentifier == placement) {
                    findOffering(source.offerings, mapping.offeringIdentifier)?.let { return it }
                }
            }
            val fallbackIdentifier = placements.fallbackOfferingId
            if (!fallbackIdentifier.isNullOrEmpty()) {
                findOffering(source.offerings, fallbackIdentifier)?.let { return it }
            }
            return ruleDefaultOrFallback(rule, source.offerings)
        }
        return defaultOfferingOrNull(source.offerings)
    }

    internal fun evaluateField(
        fieldName: String,
        operator: String,
        data: String,
        context: InappifyOfferingEvaluationContext,
        attributeKey: String?,
    ): Boolean {
        if (fieldName == "any_audience") return true
        if (operator.isEmpty()) return false
        val decoded = decodeConditionValue(data)
        return when (fieldName) {
            "country" -> evaluateOperator(
                context.country.uppercase(),
                operator,
                normalizeCountryValue(decoded),
            )

            "platform" -> evaluatePlatform(context.platform, operator, decoded)
            "custom_attribute" -> {
                val value = context.customAttributes[attributeKey]
                !value.isNullOrEmpty() && evaluateOperator(value, operator, decoded)
            }

            "app_config" -> context.appId?.let {
                evaluateOperator(it, operator, decoded)
            } ?: false

            "app_version" ->
                matchesAppContext(attributeKey, context.appId) &&
                    evaluateVersion(context.appVersion, operator, decoded)

            "sdk_version" -> evaluateVersion(context.sdkVersion, operator, decoded)
            else -> false
        }
    }

    internal fun evaluateOperator(left: Any?, operator: String, right: Any?): Boolean =
        when (operator) {
            ">" -> comparable(left, right) { a, b -> a > b }
            "<" -> comparable(left, right) { a, b -> a < b }
            ">=" -> comparable(left, right) { a, b -> a >= b }
            "<=" -> comparable(left, right) { a, b -> a <= b }
            "!=" -> !equal(left, right)
            "=" -> equal(left, right)
            "in" -> right is List<*> && right.any { equal(left, it) }
            "not in" -> right is List<*> && right.none { equal(left, it) }
            else -> false
        }

    internal fun convertVersionToDouble(version: String): Double {
        val parts = version.split('.')
        val major = parts.firstOrNull().orEmpty()
        val minor = parts.drop(1).joinToString(separator = "")
        return (if (minor.isEmpty()) major else "$major.$minor").toDoubleOrNull() ?: 0.0
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        val length = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until length) {
            val comparison = compareVersionParts(
                leftParts.getOrElse(index) { ZERO_VERSION_PART },
                rightParts.getOrElse(index) { ZERO_VERSION_PART },
            )
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun decodeConditionValue(value: String): Any? = try {
        JsonParser.parseString(value).toNativeValue()
    } catch (_: Exception) {
        value
    }

    private fun JsonElement.toNativeValue(): Any? = when {
        isJsonNull -> null
        isJsonArray -> asJsonArray.map { it.toNativeValue() }
        isJsonObject -> asJsonObject.entrySet().associate { (key, item) ->
            key to item.toNativeValue()
        }

        isJsonPrimitive && asJsonPrimitive.isBoolean -> asBoolean
        isJsonPrimitive && asJsonPrimitive.isNumber -> targetingJsonNumber()
        isJsonPrimitive -> asString
        else -> null
    }

    /**
     * Integral JSON values within the signed 64-bit range remain [Long].
     * Fractional, exponential, or larger values use [Double]. The distinction
     * is significant because version evaluation uses the string form.
     */
    private fun JsonElement.targetingJsonNumber(): Number {
        val raw = asString
        if (raw.indexOfAny(charArrayOf('.', 'e', 'E')) >= 0) return raw.toDouble()
        return raw.toLongOrNull() ?: raw.toDouble()
    }

    private fun normalizeCountryValue(value: Any?): Any? = when (value) {
        is List<*> -> value.map { item -> if (item is String) item.uppercase() else item }
        is String -> value.uppercase()
        else -> value
    }

    private fun evaluatePlatform(platform: Any, operator: String, right: Any?): Boolean {
        val aliases = mutableListOf<Any>(platform)
        if (platform == 3 || platform.toString().lowercase() == "web") {
            aliases.addAll(listOf(3, "3", "web"))
        }
        return when (operator) {
            "in" -> right is List<*> && aliases.any { left -> right.any { equal(left, it) } }
            "not in" -> right is List<*> && aliases.all { left -> right.none { equal(left, it) } }
            else -> aliases.any { left -> evaluateOperator(left, operator, right) }
        }
    }

    private fun evaluateVersion(left: String, operator: String, rawRight: Any?): Boolean {
        if (left.isEmpty()) return false
        val right = if (rawRight is List<*> && rawRight.isNotEmpty()) rawRight.first() else rawRight
        if (right !is String && right !is Number) return false
        if (versionParts(left).isEmpty() || versionParts(right.toString()).isEmpty()) return false
        val comparison = compareVersions(left, right.toString())
        return when (operator) {
            ">" -> comparison > 0
            "<" -> comparison < 0
            ">=" -> comparison >= 0
            "<=" -> comparison <= 0
            "=" -> comparison == 0
            "!=" -> comparison != 0
            else -> false
        }
    }

    private fun matchesAppContext(conditionAppId: String?, currentAppId: Long?): Boolean =
        conditionAppId.isNullOrEmpty() ||
            (currentAppId != null && equal(currentAppId, conditionAppId))

    private fun equal(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) {
            return numericallyEqual(left, right)
        }
        if (left is Number && right is String && right.trim().isNotEmpty()) {
            return right.toTargetingNumber()?.let { numericallyEqual(left, it) } == true
        }
        if (left is String && right is Number && left.trim().isNotEmpty()) {
            return left.toTargetingNumber()?.let { numericallyEqual(it, right) } == true
        }
        return left == right
    }

    private fun numericallyEqual(left: Number, right: Number): Boolean {
        val leftDouble = left.toDouble()
        val rightDouble = right.toDouble()
        if (!leftDouble.isFinite() || !rightDouble.isFinite()) {
            return leftDouble == rightDouble
        }
        val leftDecimal = left.toString().toBigDecimalOrNull() ?: return false
        val rightDecimal = right.toString().toBigDecimalOrNull() ?: return false
        return leftDecimal.compareTo(rightDecimal) == 0
    }

    private fun String.toTargetingNumber(): Number? {
        val normalized = trim()
        return normalized.toLongOrNull() ?: normalized.toDoubleOrNull()
    }

    private fun comparable(
        left: Any?,
        right: Any?,
        comparison: (BigDecimal, BigDecimal) -> Boolean,
    ): Boolean {
        val leftNumber = left.toComparableNumber() ?: return false
        val rightNumber = right.toComparableNumber() ?: return false
        return comparison(leftNumber, rightNumber)
    }

    private fun Any?.toComparableNumber(): BigDecimal? = when (this) {
        is BigDecimal -> this
        is Number -> toString().toBigDecimalOrNull()
        is String -> trim().takeIf(String::isNotEmpty)?.toBigDecimalOrNull()
        else -> null
    }

    private fun versionParts(version: String): List<String> {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        val core = trimmed.substringBefore('+').substringBefore('-')
        if (!VERSION_PATTERN.matches(core)) return emptyList()
        return core.split('.').map { part ->
            part.trimStart('0').ifEmpty { ZERO_VERSION_PART }
        }
    }

    private fun compareVersionParts(left: String, right: String): Int {
        if (left.length != right.length) return left.length.compareTo(right.length)
        return left.compareTo(right).coerceIn(-1, 1)
    }

    private fun ruleDefaultOrFallback(
        rule: InappifyRule,
        offerings: List<InappifyOffering>?,
    ): InappifyOffering? {
        val identifier = rule.defaultOffering
        return if (!identifier.isNullOrEmpty()) {
            findOffering(offerings, identifier)
        } else {
            defaultOfferingOrNull(offerings)
        }
    }

    private fun defaultOfferingOrNull(offerings: List<InappifyOffering>?): InappifyOffering? =
        offerings.orEmpty().firstOrNull { it.isDefault == true }

    private fun findOffering(
        offerings: List<InappifyOffering>?,
        identifier: String?,
    ): InappifyOffering? = offerings.orEmpty().firstOrNull { it.identifier == identifier }

    private val VERSION_PATTERN = Regex("^\\d+(?:\\.\\d+)*$")
    private const val ZERO_VERSION_PART = "0"
}
