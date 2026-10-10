package com.inappify.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyContractModelsTest {

    @Test
    fun attributesRequest_defensivelyCopiesAndRedactsValues() {
        val source = mutableListOf(InappifyAttribute(key = "language", value = "fa"))
        val request = InappifyAttributesRequest(source, idempotencyKey = "operation-1")
        source += InappifyAttribute(key = "theme", value = "dark")

        assertEquals(1, request.attributes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (request.attributes as MutableList).add(
                InappifyAttribute(key = "timezone", value = "Asia/Tehran"),
            )
        }
        assertFalse(request.toString().contains("language"))
        assertFalse(request.toString().contains("operation-1"))
    }

    @Test
    fun customAttributeValidation_matchesMobileRules() {
        assertTrue(InappifyAttribute(key = "language-code_2", value = "fa").isValidCustomAttribute())
        assertTrue(InappifyAttribute(key = "theme", value = null).isValidCustomAttribute())
        assertTrue(InappifyAttribute(key = "theme", value = "   ").removesCustomAttribute())
        assertFalse(InappifyAttribute(key = "\$email", value = "a@b.co").isValidCustomAttribute())
        assertFalse(InappifyAttribute(key = "space key", value = "value").isValidCustomAttribute())
        assertFalse(InappifyAttribute(key = "a".repeat(41), value = "value").isValidCustomAttribute())
        assertFalse(InappifyAttribute(key = "key", value = "a".repeat(251)).isValidCustomAttribute())
    }

    @Test
    fun reservedAttributes_useBackendKeysAndValidation() {
        val expectedKeys = listOf(
            "\$email",
            "\$apnsTokens",
            "\$displayName",
            "\$fcmTokens",
            "\$idfa",
            "\$idfv",
            "\$ip",
            "\$phoneNumber",
            "\$campaign",
            "\$keyword",
        )

        assertEquals(expectedKeys, InappifyReservedAttribute.entries.map { it.backendKey })
        assertTrue(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.EMAIL,
                "person@example.com",
            ).hasValidValue(),
        )
        assertTrue(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.EMAIL,
                "",
            ).hasValidValue(),
        )
        assertFalse(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.EMAIL,
                "invalid-email",
            ).hasValidValue(),
        )
        assertFalse(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.CAMPAIGN,
                "a".repeat(251),
            ).hasValidValue(),
        )
    }

    @Test
    fun discountModels_areImmutableAndDoNotLeakSensitiveValues() {
        val links = mutableListOf(
            InappifyPurchaseLink("main", "https://example.invalid/private-checkout"),
        )
        val result = InappifyDiscountCodeResult(
            isValid = true,
            code = "WELCOME20",
            percent = 20,
            message = "Customer-specific message",
            paymentLinks = links,
            offering = InappifyOffering(identifier = "main"),
        )
        links.clear()

        assertEquals(1, result.paymentLinks?.size)
        assertFalse(result.toString().contains("WELCOME20"))
        assertFalse(result.toString().contains("private-checkout"))
        assertFalse(result.toString().contains("Customer-specific message"))
        assertFalse(result.paymentLinks?.single().toString().contains("private-checkout"))
    }
}
