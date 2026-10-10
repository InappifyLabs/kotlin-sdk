package com.inappify.sdk.internal.billing

import com.google.gson.JsonObject
import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.internal.billing.myket.binder.IInAppBillingService
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class MyketReceiptValidatorTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(key.public.encoded)
    private val validator = MyketReceiptValidator(publicKey, "com.example.myket")
    private fun receipt() = JsonObject().apply {
        addProperty("packageName", "com.example.myket")
        addProperty("productId", "coins")
        addProperty("token", "synthetic-token")
        addProperty("orderId", "synthetic-order")
        addProperty("purchaseTime", 12345L)
        addProperty("purchaseState", 0)
        addProperty("developerPayload", "synthetic-binding")
    }
    private fun sign(text: String) = Signature.getInstance("SHA1withRSA").run {
        initSign(key.private); update(text.toByteArray()); Base64.getEncoder().encodeToString(sign())
    }
    @Test fun acceptsSignedReceiptAndPreservesExactEvidence() {
        val json = receipt().toString(); val signature = sign(json)
        val result = validator.validate(json, signature, "coins", "synthetic-binding")!!
        assertEquals("synthetic-token", result.purchaseToken)
        assertEquals(json, result.originalJson)
        assertEquals(signature, result.signature)
    }
    @Test fun acceptsPurchaseTokenAlias() {
        val json = receipt().apply { remove("token"); addProperty("purchaseToken", "alias-token") }.toString()
        assertEquals("alias-token", validator.validate(json, sign(json))!!.purchaseToken)
    }
    @Test fun validatesPublicKeyBeforeStartingBilling() {
        assertTrue(validator.hasValidPublicKey)
        for (bad in listOf("", "invalid-key", Base64.getEncoder().encodeToString(byteArrayOf(1, 2)))) {
            assertFalse(MyketReceiptValidator(bad, "com.example.myket").hasValidPublicKey)
        }
    }
    @Test fun rejectsModifiedReceiptAndWrongSignature() {
        val json = receipt().toString()
        assertNull(validator.validate(json.replace("coins", "premium"), sign(json)))
        assertNull(validator.validate(json, "not-base64"))
        assertNull(MyketReceiptValidator("invalid-key", "com.example.myket").validate(json, sign(json)))
    }
    @Test fun rejectsWrongPackageProductAndAttemptEvenWithValidSignature() {
        val json = receipt().toString(); val signature = sign(json)
        assertNull(validator.validate(json, signature, "another-product"))
        assertNull(validator.validate(json, signature, "coins", "another-attempt"))
        assertNull(MyketReceiptValidator(publicKey, "com.example.other").validate(json, signature))
    }
    @Test fun rejectsMissingOrMalformedPaymentStateAndTime() {
        for (field in listOf("purchaseState", "purchaseTime")) {
            val missing = receipt().apply { remove(field) }.toString()
            assertNull(validator.validate(missing, sign(missing)))
            for (value in listOf("0", "-1", "null")) {
                val json = receipt().apply { addProperty(field, value) }.toString()
                assertNull(validator.validate(json, sign(json)))
            }
        }
        val failed = receipt().apply { addProperty("purchaseState", 1) }.toString()
        assertNull(validator.validate(failed, sign(failed)))
        val fraction = receipt().apply { addProperty("purchaseTime", 1.5) }.toString()
        assertNull(validator.validate(fraction, sign(fraction)))
    }
    @Test fun rejectsBlankTokenAndMalformedSignedJson() {
        val json = receipt().apply { addProperty("token", "") }.toString()
        assertNull(validator.validate(json, sign(json)))
        for (bad in listOf("[]", "null", "{broken")) assertNull(validator.validate(bad, sign(bad)))
    }
    @Test fun binderJavaNamespaceIsIsolatedButWireDescriptorIsPreserved() {
        assertEquals("com.android.vending.billing.IInAppBillingService", IInAppBillingService.DESCRIPTOR)
        assertTrue(IInAppBillingService::class.java.name.startsWith("com.inappify.sdk.internal.billing.myket.binder."))
        val stub = Class.forName(IInAppBillingService::class.java.name + "\$Stub")
        val methods = listOf("isBillingSupported", "getSkuDetails", "getBuyIntent", "getPurchases",
            "consumePurchase", "getBuyIntentV2", "getPurchaseConfig")
        methods.forEachIndexed { index, name ->
            val field = stub.getDeclaredField("TRANSACTION_$name").apply { isAccessible = true }
            assertEquals(index + 1, field.getInt(null))
        }
        assertEquals("bazar", InappifyMarket.BAZAAR.storeId)
        assertEquals("myket", InappifyMarket.MYKET.storeId)
        assertEquals(listOf(StoreProductType.IN_APP), InappifyMarket.MYKET.ownedProductTypes)
    }
}
