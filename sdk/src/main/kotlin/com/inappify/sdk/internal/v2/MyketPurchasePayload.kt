package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex

/** Myket rejects oversized developer payloads before payment UI. Keep both bindings intact. */
internal object MyketPurchasePayload {
    // Conservative wire budget, verified on-device; not a claim about every Myket version's limit.
    const val MAX_BYTES = 256
    private val types = mapOf("S" to "SUBSCRIPTION", "C" to "CONSUMABLE", "N" to "NON_CONSUMABLE")
    private val keys = setOf("v", "o", "k", "a", "t", "r", "purchaseBinding")

    fun encode(payload: JsonObject): String {
        val compact = JsonObject().apply {
            addProperty("v", 1)
            addProperty("o", payload.string("offeringIdentifier"))
            addProperty("k", payload.string("nativePackageIdentifier"))
            addProperty("a", payload.string("attemptId"))
            addProperty("t", payload.get("productType")?.asString?.let { type ->
                types.entries.single { it.value == type }.key
            } ?: "L")
            // Lossless encoding of all 256 bits, leaving space for longer package identifiers.
            addProperty("r", payload.string("recoveryBinding").decodeHex().base64Url().trimEnd('='))
            // The deployed backend reads this exact field from the store-verified receipt.
            add("purchaseBinding", payload.get("purchaseBinding"))
        }.toString()
        require(compact.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Myket purchase context is too large." }
        return compact
    }

    fun decode(raw: String?, verifiedProductIdentifier: String): JsonObject {
        val payload = JsonParser.parseString(raw).asJsonObject
        if (!payload.has("v")) return payload // Previously issued, signed JSON receipts.
        require(payload.keySet() == keys && payload.get("v").toString() == "1") { "Invalid Myket payload version." }
        fun value(key: String): String {
            val field = payload.get(key)
            require(field.isJsonPrimitive && field.asJsonPrimitive.isString && field.asString.isNotBlank())
            return field.asString
        }
        val type = value("t")
        require(type == "L" || type in types)
        require(value("a").matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        val recovery = value("r").decodeBase64()
        require(recovery != null && recovery.size == 32 && recovery.base64Url().trimEnd('=') == value("r"))
        require(value("purchaseBinding").matches(Regex("v1:[0-9a-f]{64}")))
        return JsonObject().apply {
            addProperty("offeringIdentifier", value("o"))
            addProperty("nativePackageIdentifier", value("k"))
            addProperty("attemptId", value("a"))
            types[type]?.let { addProperty("productType", it) }
            addProperty("recoveryBinding", recovery.hex())
            addProperty("purchaseBinding", value("purchaseBinding"))
            // This value comes from the independently verified signed receipt, not a catalog guess.
            addProperty("productIdentifier", verifiedProductIdentifier)
        }
    }
}
