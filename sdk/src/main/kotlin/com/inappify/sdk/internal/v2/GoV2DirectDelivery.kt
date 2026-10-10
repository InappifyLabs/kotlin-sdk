package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*

/** Strict, credential-free Laravel V2 direct delivery payload. */
internal class GoV2DirectDelivery(val json: JsonObject) {
    val id: Long = json.deliveryNumber("deliveryId", positive = true) ?: invalid()
    val product: String = json.deliveryText("productIdentifier")?.takeIf { it.isNotBlank() } ?: invalid()
    val transaction: String? = json.deliveryText("transactionId")
    val status: String = json.deliveryText("status") ?: invalid()
    val completed: Boolean get() = status == "COMPLETED"
    init {
        if (json.deliveryText("source") != "direct" || status !in setOf("DELIVERY_REQUIRED", "COMPLETED")) invalid()
        json.deliveryNumber("paymentId", positive = true)
        json.deliveryNumber("consumeAttempts", positive = false)
        val delivered = json.get("alreadyDelivered")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid()
            it.asBoolean
        }
        if ((completed && delivered == false) || (!completed && delivered == true)) invalid()
    }
    fun publicDelivery() = InappifyConsumableDelivery(id, product, transaction, InappifyDeliverySource.DIRECT)
    fun purchase() = InappifyPurchase.directDeliveryResult(id, product,
        if (completed) InappifyStorePurchaseStatus.COMPLETED else InappifyStorePurchaseStatus.DELIVERY_REQUIRED)
}

private fun invalid(): Nothing = fail("DECODING", "INVALID_DIRECT_DELIVERY")
private fun JsonObject.deliveryText(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalid()
    it.asString
}
private fun JsonObject.deliveryNumber(name: String, positive: Boolean): Long? = get(name)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || !it.asString.matches(Regex("0|[1-9][0-9]*"))) invalid()
    val value = it.asString.toLongOrNull() ?: invalid()
    if (positive && value <= 0) invalid()
    value
}
