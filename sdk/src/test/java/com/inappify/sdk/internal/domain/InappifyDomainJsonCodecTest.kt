package com.inappify.sdk.internal.domain

import com.google.gson.JsonParser
import com.inappify.sdk.InappifyAttribute
import com.inappify.sdk.InappifyCondition
import com.inappify.sdk.InappifyCustomerInfo
import com.inappify.sdk.InappifyOffering
import com.inappify.sdk.InappifyOfferings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyDomainJsonCodecTest {

    @Test
    fun parseCustomerInfo_acceptsSupportedResponseAliases() {
        val customer = InappifyDomainJsonCodec.parseCustomerInfo(
            """
            {
              "original_app_user_id": "customer-1",
              "first_seen": "2026-08-01T00:00:00Z",
              "request_date": "2026-08-26T00:00:00Z",
              "latest_expiration_date": "2026-09-01T00:00:00Z",
              "has_used_trial": true,
              "entitlements": {
                "premium": {
                  "isActive": true,
                  "isSandbox": false,
                  "periodType": "normal",
                  "purchaseStoreRefHash": "store-ref",
                  "purchaseStoreTime": 1725000000000
                }
              },
              "attributes": {
                "language": "fa",
                "email_verified": true,
                "nullable": null
              },
              "transactions": [{
                "number": "transaction-number",
                "status": 1,
                "amount": 50000,
                "transaction_date": "2026-08-25T00:00:00Z",
                "tracking_number": "tracking-number",
                "is_crypto_gate": false,
                "package_name": "monthly",
                "store_purchase_ref_id": "purchase-ref",
                "is_trial": true
              }]
            }
            """.trimIndent(),
        )

        assertEquals("customer-1", customer.originalAppUserId)
        assertEquals("2026-08-01T00:00:00Z", customer.firstSeen)
        assertTrue(customer.hasUsedTrial == true)
        assertEquals("premium", customer.entitlements?.single()?.identifier)
        assertTrue(customer.entitlements?.single()?.isActive == true)
        assertEquals(1_725_000_000_000L, customer.entitlements?.single()?.purchaseStoreTime)
        assertEquals("fa", customer.attributes?.first { it.key == "language" }?.value)
        assertEquals("true", customer.attributes?.first { it.key == "email_verified" }?.value)
        assertNull(customer.attributes?.first { it.key == "nullable" }?.value)
        assertEquals("monthly", customer.transactions?.single()?.packageName)
        assertTrue(customer.transactions?.single()?.isTrial == true)
    }

    @Test
    fun parseCustomerInfo_truncatesIntegerFieldsTowardZero() {
        val customer = InappifyDomainJsonCodec.parseCustomerInfo(
            """
            {
              "entitlements": [{
                "identifier": "premium",
                "purchase_store_time": 1725000000000.9
              }],
              "transactions": [{
                "number": "transaction-number",
                "status": -2.9,
                "amount": 50000.8
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            1_725_000_000_000L,
            customer.entitlements?.single()?.purchaseStoreTime,
        )
        assertEquals(-2L, customer.transactions?.single()?.status)
        assertEquals(50_000L, customer.transactions?.single()?.amount)
    }

    @Test
    fun parseOfferings_normalizesAliasesProductsAndRules() {
        val offerings = InappifyDomainJsonCodec.parseOfferings(
            """
            {
              "all": [{
                "identifier": "main",
                "is_default": false,
                "server_description": "Main plan",
                "trial_days": 7,
                "metadata": {"campaign": "summer", "segments": [1, "new"]},
                "packages": [{
                  "identifier": "monthly",
                  "package_type": 1,
                  "discount_percent": 10,
                  "product": {
                    "identifier": "monthly-product",
                    "dollar_price": 2.5,
                    "trial_days": 3,
                    "price": [{
                      "appProductId": 42,
                      "currency": "IRR",
                      "amount": 129.5,
                      "price": 129,
                      "originAmount": 150,
                      "discount_amount": 20.5,
                      "createdAt": "2026-08-01",
                      "updatedAt": "2026-08-02"
                    }]
                  }
                }]
              }],
              "current": {"identifier": "main"},
              "force_version": 17,
              "fetched_at": "2026-08-26T10:00:00Z",
              "rules": [{
                "defaultOfferingIdentifier": "main",
                "sort": 3,
                "conditions": [{
                  "targetId": 9,
                  "context": 73,
                  "field": "platform",
                  "operator": "in",
                  "value": ["android", 2]
                }],
                "placements": {
                  "fallbackOfferingIdentifier": "main",
                  "offerings": [{
                    "placementIdentifier": "pricing",
                    "offeringIdentifier": "main"
                  }]
                }
              }]
            }
            """.trimIndent(),
        )

        val offering = offerings.offerings?.single()
        val price = offering?.packages?.single()?.product?.prices?.single()
        val condition = offerings.rules?.single()?.conditions?.single()

        assertTrue(offering?.isDefault == true)
        assertEquals("Main plan", offering?.serverDescription)
        assertEquals(17L, offerings.forceVersion)
        assertEquals(42L, price?.appProductId)
        assertEquals(129.5, price?.amount)
        assertEquals(150L, price?.originalAmount)
        assertEquals(20.5, price?.discountAmount)
        assertEquals("73", condition?.context)
        assertEquals("[\"android\",2]", condition?.value)
        assertEquals("main", offerings.rules?.single()?.defaultOffering)
        assertEquals(
            "pricing",
            offerings.rules?.single()?.placements?.placementOfferings?.single()?.placementIdentifier,
        )
    }

    @Test
    fun parseOfferings_truncatesIntegerFieldsTowardZero() {
        val offerings = InappifyDomainJsonCodec.parseOfferings(
            """
            {
              "forceVersion": 17.9,
              "offerings": [{
                "identifier": "main",
                "trialDays": 7.9,
                "packages": [{
                  "identifier": "monthly",
                  "packageType": 1.9,
                  "discountPercent": -10.9,
                  "product": {
                    "identifier": "monthly-product",
                    "trialDays": 3.9,
                    "prices": [{
                      "id": 4.9,
                      "app_product_id": 42.9,
                      "amount": 129.9,
                      "price": 129.9,
                      "originPrice": 150.9
                    }]
                  }
                }]
              }],
              "rules": [{
                "sort": 3.9,
                "conditions": [{"id": 8.9, "target_id": 9.9}]
              }]
            }
            """.trimIndent(),
        )

        val offering = offerings.offerings?.single()
        val nestedPackage = offering?.packages?.single()
        val product = nestedPackage?.product
        val price = product?.prices?.single()
        val rule = offerings.rules?.single()
        val condition = rule?.conditions?.single()

        assertEquals(17L, offerings.forceVersion)
        assertEquals(7L, offering?.trialDays)
        assertEquals(1L, nestedPackage?.packageType)
        assertEquals(-10L, nestedPackage?.discountPercent)
        assertEquals(3L, product?.trialDays)
        assertEquals(4L, price?.id)
        assertEquals(42L, price?.appProductId)
        assertEquals(129L, price?.price)
        assertEquals(150L, price?.originPrice)
        assertEquals(3L, rule?.sort)
        assertEquals(8L, condition?.id)
        assertEquals(9L, condition?.targetId)
    }

    @Test
    fun longIntegerFields_preserveMoneyIdsAndOrderingBeyondIntRange() {
        val customer = InappifyDomainJsonCodec.parseCustomerInfo(
            """{"transactions":[{"status":-2147483649.9,"amount":5000000000.9}]}""",
        )
        val offerings = InappifyDomainJsonCodec.parseOfferings(
            """
            {
              "forceVersion": 2147483648.9,
              "offerings": [{
                "trialDays": 2147483649.9,
                "packages": [{
                  "packageType": 2147483650.9,
                  "discountPercent": 2147483651.9,
                  "product": {"prices": [{
                    "id": 2147483652.9,
                    "app_product_id": 2147483653.9,
                    "price": 5000000000.9,
                    "originPrice": 6000000000.9
                  }]}
                }]
              }],
              "rules": [{"sort":2147483654.9,"conditions":[{"id":2147483655.9,"target_id":2147483656.9}]}]
            }
            """.trimIndent(),
        )

        assertEquals(-2_147_483_649L, customer.transactions?.single()?.status)
        assertEquals(5_000_000_000L, customer.transactions?.single()?.amount)
        assertEquals(2_147_483_648L, offerings.forceVersion)
        val offering = offerings.offerings?.single()
        val nestedPackage = offering?.packages?.single()
        val price = nestedPackage?.product?.prices?.single()
        assertEquals(2_147_483_649L, offering?.trialDays)
        assertEquals(2_147_483_650L, nestedPackage?.packageType)
        assertEquals(2_147_483_651L, nestedPackage?.discountPercent)
        assertEquals(2_147_483_652L, price?.id)
        assertEquals(2_147_483_653L, price?.appProductId)
        assertEquals(5_000_000_000L, price?.price)
        assertEquals(6_000_000_000L, price?.originPrice)
        assertEquals(2_147_483_654L, offerings.rules?.single()?.sort)
        assertEquals(2_147_483_655L, offerings.rules?.single()?.conditions?.single()?.id)
        assertEquals(2_147_483_656L, offerings.rules?.single()?.conditions?.single()?.targetId)
    }

    @Test
    fun longIntegerFields_rejectValuesOutsideRangeAfterTruncation() {
        assertThrows(InappifyDomainJsonException::class.java) {
            InappifyDomainJsonCodec.parseCustomerInfo(
                """{"transactions":[{"amount":9223372036854775808.9}]}""",
            )
        }
    }

    @Test
    fun encodeOfferings_usesCanonicalBackendKeysAndRoundTrips() {
        val source = InappifyDomainJsonCodec.parseOfferings(
            """
            {
              "offerings": [{
                "identifier": "main",
                "isDefault": true,
                "packages": [{
                  "identifier": "monthly",
                  "packageType": 1,
                  "product": {
                    "identifier": "product",
                    "prices": [{"amount": 100, "price": 100}]
                  }
                }]
              }],
              "rules": [{
                "default_offering": "main",
                "conditions": [{"field": "country", "operator": "=", "value": "IR"}]
              }],
              "forceVersion": 2,
              "fetchedAt": null
            }
            """.trimIndent(),
        )

        val encoded = InappifyDomainJsonCodec.encodeOfferings(source)
        val root = JsonParser.parseString(encoded).asJsonObject
        val product = root
            .getAsJsonArray("offerings")[0].asJsonObject
            .getAsJsonArray("packages")[0].asJsonObject
            .getAsJsonObject("product")
        val rule = root.getAsJsonArray("rules")[0].asJsonObject

        assertTrue(product.has("price"))
        assertFalse(product.has("prices"))
        assertTrue(rule.has("default_offering"))
        assertFalse(rule.has("defaultOfferingIdentifier"))
        assertTrue(root.has("fetchedAt"))
        assertTrue(root["fetchedAt"].isJsonNull)
        assertEquals(source, InappifyDomainJsonCodec.parseOfferings(encoded))
    }

    @Test
    fun encodeCustomerInfo_usesCanonicalMixedCaseContractAndRoundTrips() {
        val source = InappifyDomainJsonCodec.parseCustomerInfo(
            """
            {
              "originalAppUserId": "customer",
              "entitlements": [{"identifier": "premium", "is_active": true}],
              "attributes": [{"key": "language", "value": "fa"}]
            }
            """.trimIndent(),
        )

        val encoded = InappifyDomainJsonCodec.encodeCustomerInfo(source)
        val root = JsonParser.parseString(encoded).asJsonObject

        assertTrue(root.has("originalAppUserId"))
        assertFalse(root.has("original_app_user_id"))
        assertTrue(root.getAsJsonArray("entitlements")[0].asJsonObject.has("is_active"))
        assertEquals(source, InappifyDomainJsonCodec.parseCustomerInfo(encoded))
    }

    @Test
    fun customerInfo_roundTripsMillisecondStorePurchaseTimeWithoutOverflow() {
        val source = InappifyDomainJsonCodec.parseCustomerInfo(
            """
            {
              "originalAppUserId": "customer",
              "entitlements": [{
                "identifier": "premium",
                "purchase_store_time": 1725000000000
              }]
            }
            """.trimIndent(),
        )

        val encoded = InappifyDomainJsonCodec.encodeCustomerInfo(source)
        val decoded = InappifyDomainJsonCodec.parseCustomerInfo(encoded)

        assertEquals(
            1_725_000_000_000L,
            decoded.entitlements?.single()?.purchaseStoreTime,
        )
    }

    @Test
    fun parseCustomerInfo_rejectsPurchaseStoreTimeOutsideNativeLongRangeAfterTruncation() {
        val error = assertThrows(InappifyDomainJsonException::class.java) {
            InappifyDomainJsonCodec.parseCustomerInfo(
                """
                {
                  "entitlements": [{
                    "purchase_store_time": 9223372036854775808.9
                  }]
                }
                """.trimIndent(),
            )
        }

        assertTrue(error.message.orEmpty().contains("purchase_store_time"))
    }

    @Test
    fun parseMalformedNestedProduct_fallsBackToEmptyOffering() {
        val offerings = InappifyDomainJsonCodec.parseOfferings(
            """
            {
              "offerings": [{
                "identifier": "main",
                "packages": [{"product": {"prices": {"amount": 10}}}]
              }]
            }
            """.trimIndent(),
        )

        val fallback = offerings.offerings?.single()
        assertNotNull(fallback)
        assertNull(fallback?.identifier)
        assertNull(fallback?.packages)
        assertNull(fallback?.paywall)
    }

    @Test
    fun parseMalformedPaywall_fallsBackToEmptyOffering() {
        val offerings = InappifyDomainJsonCodec.parseOfferings(
            """{"offerings":[{"identifier":"main","paywall":[]}]}""",
        )

        val fallback = offerings.offerings?.single()
        assertNotNull(fallback)
        assertNull(fallback?.identifier)
        assertNull(fallback?.paywall)
    }

    @Test
    fun parseNonObjectOfferingItem_stillFailsWholePayload() {
        val error = assertThrows(InappifyDomainJsonException::class.java) {
            InappifyDomainJsonCodec.parseOfferings("""{"offerings":[42]}""")
        }

        assertTrue(error.message.orEmpty().contains("offerings.offerings[0]"))
    }

    @Test
    fun parseWrongScalarType_doesNotSilentlyCreateEmptyModel() {
        val error = assertThrows(InappifyDomainJsonException::class.java) {
            InappifyDomainJsonCodec.parseCustomerInfo(
                """{"hasUsedTrial":"yes"}""",
            )
        }

        assertTrue(error.message.orEmpty().contains("customerInfo.hasUsedTrial"))
    }

    @Test
    fun publicCollectionsAndOpaqueJson_areDeeplyImmutable() {
        val nestedValues = mutableListOf<Any?>("first")
        val metadata = mutableMapOf<String, Any?>("values" to nestedValues)
        val mutableOfferings = mutableListOf(
            InappifyOffering(identifier = "main", metadata = metadata),
        )
        val model = InappifyOfferings(offerings = mutableOfferings)

        nestedValues += "second"
        metadata["late"] = true
        mutableOfferings += InappifyOffering(identifier = "late")

        assertEquals(1, model.offerings?.size)
        @Suppress("UNCHECKED_CAST")
        val frozenMetadata = model.offerings?.single()?.metadata as Map<String, Any?>
        assertFalse(frozenMetadata.containsKey("late"))
        assertEquals(listOf("first"), frozenMetadata["values"])
        assertThrows(UnsupportedOperationException::class.java) {
            (model.offerings as MutableList<InappifyOffering>).add(
                InappifyOffering(identifier = "mutation"),
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (frozenMetadata["values"] as MutableList<Any?>).add("mutation")
        }
    }

    @Test
    fun toString_redactsCustomerRuleAndOpaqueValues() {
        val customer = InappifyCustomerInfo(
            originalAppUserId = "customer-secret",
            attributes = listOf(InappifyAttribute("email", "user@example.com")),
        )
        val condition = InappifyCondition(
            context = "email",
            field = "custom_attribute",
            operator = "=",
            value = "user@example.com",
        )
        val offering = InappifyOffering(
            identifier = "main",
            metadata = mapOf("secret" to "metadata-secret"),
        )

        assertFalse(customer.toString().contains("customer-secret"))
        assertFalse(customer.toString().contains("user@example.com"))
        assertFalse(condition.toString().contains("user@example.com"))
        assertFalse(condition.toString().contains("email"))
        assertFalse(offering.toString().contains("metadata-secret"))
        assertNotNull(customer.attributes)
    }
}
