package com.inappify.sdk.internal.domain

import com.google.gson.JsonParser
import com.inappify.sdk.InappifyAttribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyContractCodecTest {

    @Test
    fun discountResult_roundTripsBackendFieldsAndNestedOffering() {
        val result = InappifyDomainJsonCodec.parseDiscountCodeResult(
            """
            {
              "is_valid": true,
              "error_code": null,
              "code": "WELCOME20",
              "discount_id": 31,
              "discount_code_id": 32,
              "percent": 20,
              "message": "Accepted",
              "payment_links": [
                {"offering": "main", "url": "https://example.invalid/pay"}
              ],
              "offering": {
                "identifier": "main",
                "isDefault": true,
                "packages": [{
                  "identifier": "monthly",
                  "product": {
                    "identifier": "premium-monthly",
                    "prices": [{"amount": 190000, "currency": "IRR"}]
                  }
                }]
              }
            }
            """.trimIndent(),
        )

        assertTrue(result.isValid == true)
        assertEquals("WELCOME20", result.code)
        assertEquals(20L, result.percent)
        assertEquals("main", result.paymentLinks?.single()?.offering)
        assertEquals("main", result.offering?.identifier)
        assertEquals(190000L, result.offering?.packages?.single()?.product?.prices?.single()?.price)

        val encoded = JsonParser.parseString(
            InappifyDomainJsonCodec.encodeDiscountCodeResult(result),
        ).asJsonObject
        assertTrue(encoded.has("is_valid"))
        assertTrue(encoded.has("discount_code_id"))
        assertTrue(encoded.has("payment_links"))
        assertEquals("main", encoded["offering"].asJsonObject["identifier"].asString)
    }

    @Test
    fun discountResult_rejectsMalformedRequiredPurchaseLinks() {
        assertThrows(InappifyDomainJsonException::class.java) {
            InappifyDomainJsonCodec.parseDiscountCodeResult(
                """{"payment_links":[{"offering":"main"}]}""",
            )
        }
    }

    @Test
    fun discountResult_truncatesNumericFieldsTowardZero() {
        val result = InappifyDomainJsonCodec.parseDiscountCodeResult(
            """
            {
              "error_code": -3.9,
              "discount_id": 31.9,
              "discount_code_id": 32.1,
              "percent": 20.8,
              "offering": {
                "identifier": "main",
                "trialDays": 7.9,
                "packages": [{
                  "identifier": "monthly",
                  "packageType": 2.7,
                  "discountPercent": 15.6,
                  "product": {
                    "identifier": "premium-monthly",
                    "trialDays": 4.9,
                    "prices": [{
                      "id": 9.8,
                      "app_product_id": 10.2,
                      "amount": 190000.9,
                      "originPrice": 200000.7
                    }]
                  }
                }]
              }
            }
            """.trimIndent(),
        )

        assertEquals(-3L, result.errorCode)
        assertEquals(31L, result.discountId)
        assertEquals(32L, result.discountCodeId)
        assertEquals(20L, result.percent)
        assertEquals(7L, result.offering?.trialDays)
        val nestedPackage = result.offering?.packages?.single()
        assertEquals(2L, nestedPackage?.packageType)
        assertEquals(15L, nestedPackage?.discountPercent)
        assertEquals(4L, nestedPackage?.product?.trialDays)
        val price = nestedPackage?.product?.prices?.single()
        assertEquals(9L, price?.id)
        assertEquals(10L, price?.appProductId)
        assertEquals(190000L, price?.price)
        assertEquals(200000L, price?.originPrice)
    }

    @Test
    fun discountResult_fallsBackToEmptyOfferingWhenNestedOfferingIsMalformed() {
        val result = InappifyDomainJsonCodec.parseDiscountCodeResult(
            """
            {
              "is_valid": true,
              "code": "WELCOME20",
              "offering": {
                "identifier": 42,
                "packages": "not-an-array"
              }
            }
            """.trimIndent(),
        )

        assertTrue(result.isValid == true)
        assertEquals("WELCOME20", result.code)
        assertEquals(null, result.offering?.identifier)
        assertEquals(null, result.offering?.packages)
    }

    @Test
    fun attributes_roundTripBackendArrayShape() {
        val source = listOf(
            InappifyAttribute(key = "language", value = "fa"),
            InappifyAttribute(key = "theme", value = null),
        )
        val encoded = InappifyDomainJsonCodec.encodeAttributes(source)
        val parsed = InappifyDomainJsonCodec.parseAttributes(encoded)

        assertEquals(source, parsed)
        assertTrue(JsonParser.parseString(encoded).asJsonArray[1]
            .asJsonObject["value"].isJsonNull)
    }
}
