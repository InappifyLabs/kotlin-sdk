package com.inappify.sdk

import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class InappifyOfferingNavigationTest {
    private fun catalog(current: String = "targeted") = InappifyDomainJsonCodec.parseOfferings(
        """{"offerings":[
            {"identifier":"default","isDefault":true},
            {"identifier":"targeted"},
            {"identifier":"special_offer","packages":[{"identifier":"monthly"}]}
        ],"currentOffering":"$current",
        "placements":{"onboarding":"special_offer","settings":null,"broken":"missing"}}""",
    )

    @Test fun exactIdentifierLookupDoesNotSubstituteCurrentOrDefault() {
        val offerings = catalog()
        assertSame(offerings.offerings!![2], offerings.getOffering("special_offer"))
        assertSame(offerings.getOffering("special_offer"), offerings["special_offer"])
        assertNull(offerings.getOffering("missing"))
        assertNull(offerings["missing"])
    }

    @Test fun currentAndPlacementPreserveGoFallbacksIncludingNullAndUnknownMappings() {
        val offerings = catalog()
        assertEquals("targeted", offerings.current?.identifier)
        assertEquals("special_offer", offerings.getCurrentOfferingForPlacement("onboarding")?.identifier)
        for (placement in listOf("settings", "broken", "unknown")) {
            assertSame(offerings.current, offerings.getCurrentOfferingForPlacement(placement))
        }
        val missingCurrent = catalog("missing")
        assertEquals("default", missingCurrent.current?.identifier)
        assertEquals("default", missingCurrent.getCurrentOfferingForPlacement("settings")?.identifier)
    }

    @Test fun identifierMapIsReadOnlyAndPackagesReuseTheExistingCollection() {
        val offerings = catalog()
        assertEquals(setOf("default", "targeted", "special_offer"), offerings.all.keys)
        assertSame(offerings["special_offer"], offerings.all["special_offer"])
        assertThrows(UnsupportedOperationException::class.java) {
            (offerings.all as MutableMap<String, InappifyOffering>).clear()
        }
        val selected = offerings["special_offer"]!!
        assertSame(selected.packages, selected.availablePackages)
        assertEquals("monthly", selected.availablePackages.single().identifier)
        assertTrue(offerings["default"]!!.availablePackages.isEmpty())
    }

    @Test fun emptyCatalogHasNoSelectionAndDoesNotThrow() {
        val empty = InappifyOfferings()
        assertTrue(empty.all.isEmpty())
        assertNull(empty.current)
        assertNull(empty.getOffering("missing"))
        assertNull(empty.getCurrentOfferingForPlacement("unknown"))
    }

    @Test fun navigationDoesNotChangeSerializedCacheOrAddWireFields() {
        val offerings = catalog()
        val before = InappifyDomainJsonCodec.encodeOfferings(offerings)
        offerings.all
        offerings.current
        offerings.getCurrentOfferingForPlacement("onboarding")?.availablePackages
        assertEquals(before, InappifyDomainJsonCodec.encodeOfferings(offerings))
        val wire = JsonParser.parseString(before).asJsonObject
        assertFalse(wire.has("all"))
        assertFalse(wire.has("current"))
        val selected = wire.getAsJsonArray("offerings")[2].asJsonObject
        assertTrue(selected.has("packages"))
        assertFalse(selected.has("availablePackages"))
        val restored = InappifyDomainJsonCodec.parseOfferings(before)
        assertEquals("targeted", restored.current?.identifier)
        assertEquals("special_offer", restored.getCurrentOfferingForPlacement("onboarding")?.identifier)
    }

    @Test fun developerInterfaceDoesNotExposeRefreshOfferings() {
        assertFalse(InappifyClient::class.java.methods.any { it.name == "refreshOfferings" })
        assertFalse(InappifyV2Client::class.java.methods.any { it.name == "refreshOfferings" })
    }
}
