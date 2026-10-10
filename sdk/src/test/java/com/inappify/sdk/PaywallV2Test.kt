package com.inappify.sdk

import org.junit.Assert.*
import org.junit.Test

class PaywallV2Test {
    @Test fun semanticIconsRejectEmbeddedSvgAndUnknownIdentifiers() {
        assertEquals("listcheck", InappifyPaywallIcons.normalize("ListCheck.svg"))
        assertEquals("title", InappifyPaywallIcons.normalize("Title.svg"))
        for (raw in listOf("<svg></svg>", "data:image/svg+xml,test", "https://host/icon.svg", "unknown_icon"))
            assertNull(InappifyPaywallIcons.normalize(raw))
    }
    @Test fun fontsAreAppOwnedAndRemoteFieldsAreDropped() {
        val asset = InappifyPaywallPolicy.sanitizeAsset(mapOf("id" to "font", "type" to "font", "size" to 24,
            "weight" to 700, "style" to "normal", "family" to "remote", "font" to "evil",
            "default_font_id" to "foreign", "url" to "https://evil.com/font", "data" to "base64"), emptySet())!!
        assertEquals(setOf("id", "type", "size", "weight", "style"), asset.keys)
        assertEquals(700, asset["weight"])
    }
    @Test fun assetHostMustBeExplicitlyAllowed() {
        fun asset(url: String) = InappifyPaywallPolicy.sanitizeAsset(mapOf("type" to "assets", "url" to url), setOf("cdn.inappify.com"))
        assertNotNull(asset("https://cdn.inappify.com/hero.webp"))
        assertNull(asset("http://cdn.inappify.com/hero.webp"))
        assertNull(asset("https://cdn.inappify.com.evil.com/hero.webp"))
    }
    @Test fun compatibilityGateRejectsNewSchemaAndRendererOrMissingRevision() {
        val valid = mapOf("id" to 15, "revision" to 3, "schema_version" to 2, "minimum_renderer_version" to 2, "elements" to emptyList<Any>())
        assertTrue(InappifyPaywallPolicy.isCompatible(valid, 2, 2))
        assertFalse(InappifyPaywallPolicy.isCompatible(valid, 1, 2))
        assertFalse(InappifyPaywallPolicy.isCompatible(valid, 2, 1))
        assertFalse(InappifyPaywallPolicy.isCompatible(valid - "revision", 2, 2))
    }
    @Test fun typographyUsesNearestBundledWeight() {
        val typography = InappifyPaywallTypography(regular = 1, bold = 3)
        assertEquals(1, typography.resourceForWeight(400)); assertEquals(1, typography.resourceForWeight(500))
        assertEquals(3, typography.resourceForWeight(700)); assertNull(InappifyPaywallTypography().resourceForWeight(700))
    }
    @Test fun attributionCopiesTheOriginalRequestAndKeepsV1Unchanged() {
        val old = InappifyPurchaseRequest("product", "offering", productType = InappifyProductType.CONSUMABLE)
        val attributed = old.withPaywallAttribution(15, 3)
        assertNull(old.paywallId); assertEquals(15L, attributed.paywallId); assertEquals(3L, attributed.paywallRevision)
        assertEquals(old.productIdentifier, attributed.productIdentifier); assertEquals(old.productType, attributed.productType)
        assertNotEquals(old, attributed)
    }
}
