package com.inappify.sdk.internal.billing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inappify.sdk.internal.billing.myket.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Checks signed receipt fields before they can reach recovery, fulfillment or consumption. */
internal class MyketReceiptValidator(publicKey: String, private val packageName: String) {
    private val key = runCatching {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(publicKey)))
    }.getOrNull()
    val hasValidPublicKey: Boolean get() = key != null

    fun validate(json: String, signature: String, expectedProduct: String? = null,
                 expectedPayload: String? = null): StorePurchase? = try {
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(requireNotNull(key))
        verifier.update(json.toByteArray(Charsets.UTF_8))
        if (!verifier.verify(Base64.decode(signature))) null else {
            val receipt = JsonParser.parseString(json).asJsonObject
            val product = receipt.text("productId")
            val token = receipt.text("token").ifEmpty { receipt.text("purchaseToken") }
            val app = receipt.text("packageName")
            val payload = receipt.text("developerPayload")
            val time = receipt.number("purchaseTime")
            if (receipt.number("purchaseState") != 0L || time == null || time < 0L ||
                product.isBlank() || token.isBlank() || app != packageName ||
                (expectedProduct != null && product != expectedProduct) ||
                (expectedPayload != null && payload != expectedPayload)) null
            else StorePurchase(receipt.text("orderId"), token, payload, app, product, time, json, signature)
        }
    } catch (_: Exception) { null }

    private fun JsonObject.text(name: String): String {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return ""
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun JsonObject.number(name: String): Long? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asString.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull()
    }
}
