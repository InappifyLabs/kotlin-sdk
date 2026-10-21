package com.inappify.sdk

import com.google.gson.JsonParser
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import org.junit.Assert.*
import org.junit.Test

class InappifyPurchaseParamsTest {
    @Test fun samePackageAndProductIdentifiersKeepTheirExactParentOffering() {
        val original = InappifyPackage("monthly", product = InappifyStoreProduct("pro"))
        val first = InappifyOffering("first", packages = listOf(original))
        val second = InappifyOffering("second", packages = listOf(original))
        assertNull(original.offeringIdentifier)
        assertEquals("first", first.availablePackages.single().offeringIdentifier)
        assertEquals("second", second.availablePackages.single().offeringIdentifier)
        assertEquals("first", InappifyPurchaseParams.Builder(first.availablePackages.single())
            .build().toRequest()!!.offeringIdentifier)
        assertEquals("second", InappifyPurchaseParams.Builder(second.availablePackages.single())
            .build().toRequest()!!.offeringIdentifier)
    }

    @Test fun goProductTypesAndParentContextSurviveCatalogEncodingWithoutNewPackageFields() {
        for (type in InappifyProductType.values()) {
            val raw = """{"offerings":[{"identifier":"main","packages":[
                {"identifier":"monthly","product":{"identifier":"pro","productType":"${type.name}"}}
            ]}]}"""
            val catalog = InappifyDomainJsonCodec.parseOfferings(raw)
            val selected = catalog["main"]!!.availablePackages.single()
            assertEquals(type, selected.product!!.productType)
            assertEquals("main", selected.offeringIdentifier)
            val encoded = InappifyDomainJsonCodec.encodeOfferings(catalog)
            val wirePackage = JsonParser.parseString(encoded).asJsonObject
                .getAsJsonArray("offerings")[0].asJsonObject.getAsJsonArray("packages")[0].asJsonObject
            assertEquals(setOf("identifier", "packageType", "description", "entitlement", "name",
                "discountPercent", "product"), wirePackage.keySet())
            assertEquals(type.name, wirePackage.getAsJsonObject("product").get("productType").asString)
            val restored = InappifyDomainJsonCodec.parseOfferings(encoded)["main"]!!.availablePackages.single()
            assertEquals(catalog, InappifyDomainJsonCodec.parseOfferings(encoded))
            assertEquals("main", restored.offeringIdentifier)
            assertEquals(type, restored.product!!.productType)
        }
    }

    @Test fun oldUnknownAndMalformedProductTypesAreNeverInferredFromPackagePeriod() {
        for (field in listOf("", "\"productType\":null,", "\"productType\":\"FUTURE_TYPE\",",
            "\"productType\":1,", "\"productType\":{},")) {
            val catalog = InappifyDomainJsonCodec.parseOfferings("""{"offerings":[
                {"identifier":"main","packages":[{"identifier":"monthly","packageType":1,
                "product":{$field"identifier":"pro"}}]}]}""")
            val selected = catalog["main"]!!.availablePackages.single()
            assertNull(selected.product!!.productType)
            val encoded = JsonParser.parseString(InappifyDomainJsonCodec.encodeOfferings(catalog)).asJsonObject
                .getAsJsonArray("offerings")[0].asJsonObject.getAsJsonArray("packages")[0].asJsonObject
            assertFalse(encoded.getAsJsonObject("product").has("productType"))
        }
    }

    @Test fun builderKeepsBuiltInvocationsIndependentAndRedactsPurchaseCorrelation() {
        val product = InappifyStoreProduct("secret-product", productType = InappifyProductType.CONSUMABLE)
        val selected = InappifyOffering("secret-offering", packages = listOf(
            InappifyPackage("secret-package", product = product))).availablePackages.single()
        val builder = InappifyPurchaseParams.Builder(selected).idempotencyKey("secret-attempt")
        val first = builder.build()
        val second = builder.idempotencyKey("other-attempt").paywallAttribution(7, 3).build()
        assertEquals("secret-attempt", first.idempotencyKey)
        assertNull(first.paywallId)
        val request = second.toRequest()!!
        assertEquals("other-attempt", request.idempotencyKey)
        assertFalse(request.isCrypto)
        assertEquals(7L, request.paywallId)
        assertEquals(3L, request.paywallRevision)
        assertEquals(InappifyProductType.CONSUMABLE, request.productType)
        for (secret in listOf("secret-product", "secret-offering", "secret-package", "secret-attempt"))
            assertFalse(first.toString().contains(secret))
        assertThrows(IllegalArgumentException::class.java) { builder.paywallAttribution(0, 1) }
    }

    @Test fun unboundOrIncompletePackagesCannotInventPurchaseContext() {
        assertNull(InappifyPurchaseParams.Builder(InappifyPackage("monthly",
            product = InappifyStoreProduct("pro"))).build().toRequest())
        for (invalid in listOf(InappifyPackage("monthly"), InappifyPackage(product = InappifyStoreProduct("pro")),
            InappifyPackage("monthly", product = InappifyStoreProduct(" ")))) {
            val selected = InappifyOffering("main", packages = listOf(invalid)).availablePackages.single()
            assertNull(InappifyPurchaseParams.Builder(selected).build().toRequest())
        }
    }

    @Test fun newPurchaseApiHasNoCryptoOption() {
        assertFalse(InappifyPurchaseParams::class.java.methods.any { it.name.contains("crypto", ignoreCase = true) })
        assertFalse(InappifyPurchaseParams.Builder::class.java.methods.any { it.name.contains("crypto", ignoreCase = true) })
    }

    @Test fun originalModelJvmAndDefaultArgumentConstructorsRemainAvailable() {
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val string = String::class.java
        val long = java.lang.Long::class.java
        val double = java.lang.Double::class.java
        val int = Int::class.javaPrimitiveType
        InappifyPackage::class.java.getConstructor()
        InappifyPackage::class.java.getConstructor(string, long, string, string, string, long, InappifyStoreProduct::class.java)
        InappifyPackage::class.java.getConstructor(string, long, string, string, string, long,
            InappifyStoreProduct::class.java, int, marker)
        InappifyStoreProduct::class.java.getConstructor()
        InappifyStoreProduct::class.java.getConstructor(string, string, List::class.java, double, long)
        InappifyStoreProduct::class.java.getConstructor(string, string, List::class.java, double, long, int, marker)
    }
}
