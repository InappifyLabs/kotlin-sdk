package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*

/** Read-only, session-authorized evidence; a redirect or device clock is never payment proof. */
internal class GoV2DirectAttempt(json: JsonObject) {
    val id = json.string("attemptId")
    val product = json.string("productIdentifier")
    val offering = json.string("offeringIdentifier")
    val status = runCatching { InappifyPurchaseAttemptStatus.valueOf(json.string("status")) }
        .getOrElse { invalidAttempt() }
    val purchaseStatus = json.optionalText("purchaseStatus")?.let { value ->
        runCatching { InappifyPurchaseStatus.valueOf(value) }.getOrElse { invalidAttempt() }
    }
    val url = json.optionalText("url")
    val deliveryId = json.get("deliveryId")?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || !it.asString.matches(Regex("[1-9][0-9]*"))) invalidAttempt()
        it.asString.toLongOrNull() ?: invalidAttempt()
    }
    val deliveryStatus = json.optionalText("deliveryStatus")
    init {
        if (!id.matches(Regex("[A-Za-z0-9_.:-]{1,128}")) || product.isBlank() || offering.isBlank()) invalidAttempt()
        if ((deliveryId == null) != (deliveryStatus == null) || deliveryStatus != null &&
            deliveryStatus !in setOf("COMPLETED", "DELIVERY_REQUIRED")) invalidAttempt()
        if (deliveryId != null && purchaseStatus != InappifyPurchaseStatus.DONE) invalidAttempt()
        if (purchaseStatus == InappifyPurchaseStatus.NEEDTOPAY && url.isNullOrBlank()) invalidAttempt()
        if (url != null && purchaseStatus != InappifyPurchaseStatus.NEEDTOPAY) invalidAttempt()
        if (status == InappifyPurchaseAttemptStatus.SUCCEEDED && (purchaseStatus != InappifyPurchaseStatus.DONE ||
                deliveryStatus == "DELIVERY_REQUIRED")) invalidAttempt()
        if (status == InappifyPurchaseAttemptStatus.FAILED && (purchaseStatus != null || deliveryId != null)) invalidAttempt()
        if (deliveryStatus == "COMPLETED" && status != InappifyPurchaseAttemptStatus.SUCCEEDED) invalidAttempt()
    }

    fun result(packageIdentifier: String?, now: Long): InappifyPurchaseAttempt {
        val purchase = if (deliveryId != null) InappifyPurchase.storeResult(id, product, offering, packageIdentifier,
            if (deliveryStatus == "COMPLETED") InappifyStorePurchaseStatus.COMPLETED else InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
            deliveryId, null, false, InappifyMarket.NONE)
        else if (status == InappifyPurchaseAttemptStatus.FAILED) null
        else InappifyPurchase(id, product, offering, InappifyMarket.NONE, purchaseStatus, packageIdentifier, url)
        return InappifyPurchaseAttempt(id, product, offering, InappifyMarket.NONE, status, purchase,
            if (status == InappifyPurchaseAttemptStatus.FAILED) InappifyError(InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                "The direct purchase attempt was rejected before a payment was created.") else null, now)
    }
}

private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalidAttempt()
    it.asString
}
private fun invalidAttempt(): Nothing = fail("DECODING", "INVALID_DIRECT_PURCHASE_ATTEMPT")
