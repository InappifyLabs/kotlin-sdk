package com.inappify.sdk.internal.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.inappify.sdk.*

/** Separate encrypted file; existing session and pending-operation formats remain unchanged. */
internal interface PurchaseAttemptStorage {
    suspend fun readPurchaseAttemptHistory(): String?
    suspend fun mutatePurchaseAttemptHistory(mutation: (String) -> String): Boolean
}

internal class PurchaseAttemptHistory(private val storage: PurchaseAttemptStorage?) {
    private var memory = "{\"version\":1,\"attempts\":[]}"

    suspend fun list(binding: String): List<InappifyPurchaseAttempt> =
        rows(read()).filter { it.get("binding").asString == binding }.map(::decode)

    suspend fun pendingDirect(binding: String): List<InappifyPurchaseAttempt> = rows(read()).filter {
        it.get("binding").asString == binding && it.get("status").asString == "PENDING" &&
            it.get("directTracking")?.asBoolean == true
    }.map(::decode)

    suspend fun put(binding: String, attempt: InappifyPurchaseAttempt, directTracking: Boolean = false): Boolean {
        require(binding.matches(Regex("[0-9a-f]{64}")))
        val mutation: (String) -> String = { raw ->
            val saved = rows(raw).toMutableList()
            val index = saved.indexOfFirst { it.get("binding").asString == binding && it.get("attemptId").asString == attempt.attemptId }
            val previous = saved.getOrNull(index)?.let(::decode)
            if (previous != null && (previous.productIdentifier != attempt.productIdentifier ||
                    previous.offeringIdentifier != attempt.offeringIdentifier || previous.market != attempt.market)) {
                throw IllegalArgumentException("Purchase attempt binding conflict.")
            }
            if (previous?.isFinal != true || attempt.isFinal && previous.status != InappifyPurchaseAttemptStatus.SUCCEEDED) {
                val trackedDirect = directTracking || saved.getOrNull(index)?.get("directTracking")?.asBoolean == true
                if (index >= 0) saved.removeAt(index)
                saved += encode(attempt).apply {
                    addProperty("binding", binding)
                    if (trackedDirect) addProperty("directTracking", true)
                }
            }
            // Pending work is never evicted by a terminal outcome. No time-based expiry.
            val terminal = saved.filter { it.get("status").asString != "PENDING" }.takeLast(256).toSet()
            val retained = saved.filter { it.get("status").asString == "PENDING" || it in terminal }
            require(retained.count { it.get("status").asString == "PENDING" } <= 256)
            JsonObject().apply {
                addProperty("version", 1)
                add("attempts", JsonArray().apply { retained.forEach(::add) })
            }.toString()
        }
        return if (storage != null) storage.mutatePurchaseAttemptHistory(mutation)
        else { memory = mutation(memory); true }
    }

    private suspend fun read(): String = storage?.readPurchaseAttemptHistory()
        ?: if (storage == null) memory else throw IllegalStateException("Purchase outcome storage unavailable.")

    private fun rows(raw: String): List<JsonObject> {
        val root = JsonParser.parseString(raw).asJsonObject
        require(root.get("version").asInt == 1)
        val rows = root.getAsJsonArray("attempts")
        require(rows.size() <= 512)
        return rows.map { element -> element.asJsonObject.also { row ->
            require(row.get("binding").asString.matches(Regex("[0-9a-f]{64}")))
            row.get("directTracking")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }
            decode(row)
        } }
    }

    private fun decode(row: JsonObject): InappifyPurchaseAttempt {
        return InappifyPurchaseAttempt(
            row.string("attemptId"), row.string("productIdentifier"), row.string("offeringIdentifier"),
            InappifyMarket.valueOf(row.string("market")), InappifyPurchaseAttemptStatus.valueOf(row.string("status")),
            row.optional("purchase")?.asJsonObject?.let(::decodePurchase),
            row.optional("error")?.asJsonObject?.let(::decodeError), row.long("updatedAtEpochMillis"),
        )
    }

    // Explicit versioned fields survive consumer obfuscation and later model changes.
    // Never serialize SDK classes reflectively into durable storage.
    private fun encode(value: InappifyPurchaseAttempt): JsonObject = JsonObject().apply {
        addProperty("attemptId", value.attemptId)
        addProperty("productIdentifier", value.productIdentifier)
        addProperty("offeringIdentifier", value.offeringIdentifier)
        addProperty("market", value.market.name)
        addProperty("status", value.status.name)
        addProperty("updatedAtEpochMillis", value.updatedAtEpochMillis)
        value.purchase?.let { add("purchase", encodePurchase(it)) }
        value.error?.let { error -> add("error", JsonObject().apply {
            addProperty("code", error.code.name)
            addProperty("message", error.message)
            addProperty("isRetryable", error.isRetryable)
            add("details", JsonObject().apply { error.details.forEach { (key, detail) ->
                add(key, when (detail) {
                    null -> com.google.gson.JsonNull.INSTANCE
                    is String -> JsonPrimitive(detail)
                    is Boolean -> JsonPrimitive(detail)
                    is Number -> JsonPrimitive(detail)
                    else -> throw IllegalArgumentException("Invalid purchase attempt diagnostic.")
                })
            } })
        }) }
    }

    private fun encodePurchase(value: InappifyPurchase): JsonObject = JsonObject().apply {
        addProperty("attemptId", value.attemptId)
        addProperty("productIdentifier", value.productIdentifier)
        addProperty("offeringIdentifier", value.offeringIdentifier)
        addProperty("market", value.market.name)
        addProperty("purchaseStatus", value.purchaseStatus?.name)
        addProperty("packageIdentifier", value.packageIdentifier)
        addProperty("url", value.url)
        addProperty("checkoutId", value.checkoutId)
        addProperty("checkoutStatus", value.checkoutStatus)
        addProperty("nextActionType", value.nextActionType)
        addProperty("deliveryId", value.deliveryId)
        addProperty("verificationRequestId", value.verificationRequestId)
        addProperty("alreadyProcessed", value.alreadyProcessed)
        addProperty("storePurchaseStatus", value.storePurchaseStatus?.name)
    }

    private fun decodePurchase(row: JsonObject): InappifyPurchase {
        val id = row.string("attemptId")
        val product = row.string("productIdentifier")
        val offering = row.string("offeringIdentifier")
        val market = InappifyMarket.valueOf(row.string("market"))
        val purchaseStatus = row.optionalString("purchaseStatus")?.let(InappifyPurchaseStatus::valueOf)
        val storeStatus = row.optionalString("storePurchaseStatus")?.let(InappifyStorePurchaseStatus::valueOf)
        val purchase = if (storeStatus != null) {
            require(listOf("url", "checkoutId", "checkoutStatus", "nextActionType").all { row.optional(it) == null })
            InappifyPurchase.storeResult(id, product, offering, row.optionalString("packageIdentifier"), storeStatus,
                row.optional("deliveryId")?.exactLong(), row.optional("verificationRequestId")?.exactLong(),
                row.boolean("alreadyProcessed"), market)
        } else {
            require(row.optional("deliveryId") == null && row.optional("verificationRequestId") == null &&
                !row.boolean("alreadyProcessed"))
            InappifyPurchase(id, product, offering, market, purchaseStatus, row.optionalString("packageIdentifier"),
                row.optionalString("url"), row.optionalString("checkoutId"), row.optionalString("checkoutStatus"),
                row.optionalString("nextActionType"))
        }
        require(purchase.purchaseStatus == purchaseStatus)
        return purchase
    }

    private fun decodeError(row: JsonObject): InappifyError = InappifyError(
        InappifyErrorCode.valueOf(row.string("code")), row.string("message"), row.boolean("isRetryable"),
        row.getAsJsonObject("details").entrySet().associate { (key, value) -> key to when {
            value.isJsonNull -> null
            !value.isJsonPrimitive -> throw IllegalArgumentException("Invalid purchase attempt diagnostic.")
            value.asJsonPrimitive.isBoolean -> value.asBoolean
            value.asJsonPrimitive.isString -> value.asString
            else -> value.asBigDecimal.let { number ->
                try { number.longValueExact() } catch (_: ArithmeticException) { number.toDouble() }
            }
        } },
    )

    private fun JsonObject.optional(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(key: String): String = requireNotNull(optionalString(key))
    private fun JsonObject.optionalString(key: String): String? = optional(key)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
    }
    private fun JsonObject.boolean(key: String): Boolean = get(key).let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    }
    private fun JsonObject.long(key: String): Long = get(key).exactLong()
    private fun JsonElement.exactLong(): Long {
        require(isJsonPrimitive && asJsonPrimitive.isNumber)
        return asBigDecimal.longValueExact()
    }
}
