package com.inappify.sdk.internal.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal

/** Version-tolerant JSON codec used inside the encrypted V2 queue file. */
internal object PendingStoreOperationCodec {
    internal const val MAX_OPERATION_COUNT: Int = 256
    internal const val MAX_TOMBSTONE_COUNT: Int = 256
    private const val SCHEMA_VERSION = 1

    internal fun encode(operations: List<PendingStoreOperation>): String {
        return encodeState(PendingStoreRecoveryState(operations = operations))
    }

    internal fun encodeState(state: PendingStoreRecoveryState): String {
        val operations = state.operations
        val tombstones = state.rejectedEvidenceTombstones
        require(operations.size <= MAX_OPERATION_COUNT) {
            "Pending operation queue exceeds the item limit."
        }
        require(operations.map(PendingStoreOperation::id).distinct().size == operations.size) {
            "Pending operation ids must be unique."
        }
        require(tombstones.size <= MAX_TOMBSTONE_COUNT) {
            "Rejected store evidence exceeds the item limit."
        }
        require(tombstones.distinct().size == tombstones.size) {
            "Rejected store evidence markers must be unique."
        }
        return JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            add(
                "operations",
                JsonArray().apply {
                    operations.forEach { operation -> add(operation.toJson()) }
                },
            )
            add(
                "rejectedEvidenceTombstones",
                JsonArray().apply {
                    tombstones.forEach { tombstone -> add(tombstone.toJson()) }
                },
            )
        }.toString()
    }

    /**
     * Decodes every independently valid record and ignores malformed records.
     * A missing `operations` member represents an empty queue for compatibility.
     */
    internal fun decode(raw: String): List<PendingStoreOperation> {
        return decodeState(raw).operations
    }

    /** Decodes both current payloads and V2 payloads written before tombstones existed. */
    internal fun decodeState(raw: String): PendingStoreRecoveryState {
        val root = JsonParser.parseString(raw)
        require(root.isJsonObject) { "Pending operation payload must be an object." }
        val json = root.asJsonObject
        val schemaVersion = json.intValue("schemaVersion")
        if (schemaVersion != null && schemaVersion != SCHEMA_VERSION) {
            return PendingStoreRecoveryState()
        }
        if (json.has("schemaVersion") && schemaVersion == null) {
            return PendingStoreRecoveryState()
        }
        val operations = json.get("operations")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
        val decodedById = linkedMapOf<String, PendingStoreOperation>()
        operations
            ?.asSequence()
            ?.take(MAX_OPERATION_COUNT)
            ?.mapNotNull(::decodeOperation)
            ?.forEach { operation -> decodedById[operation.id] = operation }

        val tombstones = json.get("rejectedEvidenceTombstones")
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
        val decodedTombstones = linkedSetOf<RejectedStoreEvidenceTombstone>()
        tombstones
            ?.asSequence()
            ?.take(MAX_TOMBSTONE_COUNT)
            ?.mapNotNull(::decodeTombstone)
            ?.forEach { tombstone -> decodedTombstones.add(tombstone) }
        return PendingStoreRecoveryState(
            operations = decodedById.values.toList(),
            rejectedEvidenceTombstones = decodedTombstones.toList(),
        )
    }

    private fun decodeTombstone(
        element: JsonElement,
    ): RejectedStoreEvidenceTombstone? {
        return try {
            val json = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: return null
            RejectedStoreEvidenceTombstone(
                purchaseTokenFingerprint =
                    json.requiredString("purchaseTokenFingerprint") ?: return null,
                apiKeyFingerprint = json.requiredString("apiKeyFingerprint") ?: return null,
                customerIdentifierFingerprint =
                    json.requiredString("customerIdentifierFingerprint") ?: return null,
                appFingerprint = json.requiredString("appFingerprint") ?: return null,
                productIdentifierFingerprint =
                    json.requiredString("productIdentifierFingerprint") ?: return null,
                productTypeFingerprint =
                    json.requiredString("productTypeFingerprint") ?: return null,
                offeringIdentifierFingerprint =
                    json.requiredString("offeringIdentifierFingerprint") ?: return null,
                appVersionFingerprint =
                    json.requiredString("appVersionFingerprint") ?: return null,
                forceVersionFingerprint =
                    json.requiredString("forceVersionFingerprint") ?: return null,
                operationFingerprint =
                    json.stringValue("operationFingerprint")
                        ?: LEGACY_TOMBSTONE_FINGERPRINT,
                countryFingerprint =
                    json.stringValue("countryFingerprint")
                        ?: LEGACY_TOMBSTONE_FINGERPRINT,
                evidenceFingerprint =
                    json.stringValue("evidenceFingerprint")
                        ?: LEGACY_TOMBSTONE_FINGERPRINT,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun decodeOperation(element: JsonElement): PendingStoreOperation? {
        return try {
            val json = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: return null
            val evidenceJson = json.get("evidence")
                ?.takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?: return null
            val createdAt = json.requiredLong("createdAtEpochMillis") ?: return null
            PendingStoreOperation(
                id = json.requiredString("id") ?: return null,
                operation = json.enumValue<PendingStoreOperationType>("operation")
                    ?: return null,
                store = json.requiredString("store") ?: return null,
                customerToken = json.requiredString("customerToken") ?: return null,
                customerIdentifierFingerprint =
                    json.requiredString("customerIdentifierFingerprint") ?: return null,
                apiKeyFingerprint = json.requiredString("apiKeyFingerprint")
                    ?: return null,
                appIdentifier = json.requiredString("appIdentifier") ?: return null,
                appId = json.longValue("appId"),
                productIdentifier = json.requiredString("productIdentifier")
                    ?: return null,
                offeringIdentifier = json.stringValue("offeringIdentifier"),
                productType = json.enumValue<PendingStoreProductType>("productType")
                    ?: return null,
                evidence = PendingStorePurchaseEvidence(
                    purchaseToken = evidenceJson.requiredString("purchaseToken")
                        ?: return null,
                    orderId = evidenceJson.stringValue("orderId"),
                    packageName = evidenceJson.stringValue("packageName"),
                    developerPayload = evidenceJson.stringValue("developerPayload"),
                    originalJson = evidenceJson.stringValue("originalJson"),
                    signature = evidenceJson.stringValue("signature"),
                    purchaseTimeMillis = evidenceJson.longValue("purchaseTimeMillis"),
                ),
                phase = json.enumValue<PendingStoreOperationPhase>("phase")
                    ?: return null,
                verificationRequestId = json.longValue("verificationRequestId"),
                deliveryId = json.longValue("deliveryId"),
                attempts = json.intValue("attempts") ?: 0,
                nextRetryAtEpochMillis = json.longValue("nextRetryAtEpochMillis"),
                deliveryAcknowledged =
                    json.booleanValue("deliveryAcknowledged") ?: false,
                consumeResult = json.enumValue<PendingStoreConsumeResult>(
                    "consumeResult",
                ),
                consumeErrorCode = json.stringValue("consumeErrorCode"),
                createdAtEpochMillis = createdAt,
                updatedAtEpochMillis =
                    json.longValue("updatedAtEpochMillis") ?: createdAt,
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun PendingStoreOperation.toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("operation", operation.name)
        addProperty("store", store)
        addProperty("customerToken", customerToken)
        addProperty("customerIdentifierFingerprint", customerIdentifierFingerprint)
        addProperty("apiKeyFingerprint", apiKeyFingerprint)
        addProperty("appIdentifier", appIdentifier)
        addNullableNumber("appId", appId)
        addProperty("productIdentifier", productIdentifier)
        addNullableString("offeringIdentifier", offeringIdentifier)
        addProperty("productType", productType.name)
        add("evidence", evidence.toJson())
        addProperty("phase", phase.name)
        addNullableNumber("verificationRequestId", verificationRequestId)
        addNullableNumber("deliveryId", deliveryId)
        addProperty("attempts", attempts)
        addNullableNumber("nextRetryAtEpochMillis", nextRetryAtEpochMillis)
        addProperty("deliveryAcknowledged", deliveryAcknowledged)
        addNullableString("consumeResult", consumeResult?.name)
        addNullableString("consumeErrorCode", consumeErrorCode)
        addProperty("createdAtEpochMillis", createdAtEpochMillis)
        addProperty("updatedAtEpochMillis", updatedAtEpochMillis)
    }

    private fun PendingStorePurchaseEvidence.toJson(): JsonObject =
        JsonObject().apply {
            addProperty("purchaseToken", purchaseToken)
            addNullableString("orderId", orderId)
            addNullableString("packageName", packageName)
            addNullableString("developerPayload", developerPayload)
            addNullableString("originalJson", originalJson)
            addNullableString("signature", signature)
            addNullableNumber("purchaseTimeMillis", purchaseTimeMillis)
        }

    private fun RejectedStoreEvidenceTombstone.toJson(): JsonObject =
        JsonObject().apply {
            addProperty("purchaseTokenFingerprint", purchaseTokenFingerprint)
            addProperty("apiKeyFingerprint", apiKeyFingerprint)
            addProperty(
                "customerIdentifierFingerprint",
                customerIdentifierFingerprint,
            )
            addProperty("appFingerprint", appFingerprint)
            addProperty("productIdentifierFingerprint", productIdentifierFingerprint)
            addProperty("productTypeFingerprint", productTypeFingerprint)
            addProperty(
                "offeringIdentifierFingerprint",
                offeringIdentifierFingerprint,
            )
            addProperty("appVersionFingerprint", appVersionFingerprint)
            addProperty("forceVersionFingerprint", forceVersionFingerprint)
            addProperty("operationFingerprint", operationFingerprint)
            addProperty("countryFingerprint", countryFingerprint)
            addProperty("evidenceFingerprint", evidenceFingerprint)
        }

    private fun JsonObject.addNullableString(name: String, value: String?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addNullableNumber(name: String, value: Number?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.requiredString(name: String): String? =
        stringValue(name)?.takeIf(String::isNotBlank)

    private fun JsonObject.stringValue(name: String): String? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { value -> value.isString }
            ?.asString

    private fun JsonObject.requiredLong(name: String): Long? =
        if (has(name)) longValue(name) else null

    private fun JsonObject.longValue(name: String): Long? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { value -> value.isNumber }
            ?.let { value ->
                try {
                    BigDecimal(value.asString).longValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
            }

    private fun JsonObject.intValue(name: String): Int? =
        longValue(name)
            ?.takeIf { value ->
                value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            }
            ?.toInt()

    private fun JsonObject.booleanValue(name: String): Boolean? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { value -> value.isBoolean }
            ?.asBoolean

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(name: String): T? =
        stringValue(name)?.let { raw ->
            enumValues<T>().firstOrNull { value -> value.name == raw }
        }
}
