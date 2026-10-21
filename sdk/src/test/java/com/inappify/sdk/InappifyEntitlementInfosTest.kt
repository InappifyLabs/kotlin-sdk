package com.inappify.sdk

import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import org.junit.Assert.*
import org.junit.Test

class InappifyEntitlementInfosTest {
    @Test fun allIncludesInactiveAndExpiredWhileActiveUsesTrustedExpiryAtAccessTime() {
        var now = 1_788_854_400_000L
        val rights = mutableListOf(
            InappifyEntitlement("permanent", true),
            InappifyEntitlement("subscription", true, expirationDate = "2026-09-08T08:01:00Z"),
            InappifyEntitlement("expired", true, expirationDate = "2026-09-08T07:59:00Z"),
            InappifyEntitlement("revoked", false),
            InappifyEntitlement("invalid-date", true, expirationDate = "bad-date"),
        )
        val info = InappifyCustomerInfo(entitlements = rights).withEntitlementClock { now }
        assertEquals(now, info.entitlements.evaluationTimeMillis)
        rights.clear()
        assertEquals(5, info.entitlements.size)
        assertEquals(setOf("permanent", "subscription"), info.entitlements.active.keys)
        assertEquals(5, info.entitlements.all.size)
        assertFalse(info.entitlements["revoked"]!!.isActive!!)
        assertNull(info.entitlements["missing"])
        now += 60_000
        assertEquals(now, info.entitlements.evaluationTimeMillis)
        assertEquals(setOf("permanent"), info.entitlements.active.keys)
        assertEquals("permanent", info.entitlements.first().identifier)
        assertEquals("permanent", info.entitlements[0].identifier)
    }

    @Test fun mapsAreUnmodifiableAndEmptyInfoHasEmptyViews() {
        val empty = InappifyCustomerInfo()
        assertTrue(empty.entitlements.all.isEmpty())
        assertTrue(empty.entitlements.active.isEmpty())
        val info = InappifyCustomerInfo(entitlements = listOf(InappifyEntitlement("pro", true)))
        for (map in listOf(info.entitlements.all, info.entitlements.active)) {
            try {
                (map as MutableMap).clear()
                fail("Entitlement views must be unmodifiable")
            } catch (_: UnsupportedOperationException) { }
        }
    }

    @Test fun codecPreservesNullVersusEmptyListAndNeverSerializesDerivedMaps() {
        for (raw in listOf("{\"entitlements\":null}", "{\"entitlements\":[]}",
            """{"entitlements":[{"identifier":"old","is_active":false}]}""")) {
            val parsed = InappifyDomainJsonCodec.parseCustomerInfo(raw)
            val encoded = InappifyDomainJsonCodec.encodeCustomerInfo(parsed)
            assertFalse(encoded.contains("evaluationTimeMillis"))
            assertFalse(encoded.contains("\"active\""))
            assertFalse(encoded.contains("\"all\""))
            assertEquals(parsed, InappifyDomainJsonCodec.parseCustomerInfo(encoded))
            if (raw.contains("null")) assertTrue(encoded.contains("\"entitlements\":null"))
            if (raw.contains("[]")) assertTrue(encoded.contains("\"entitlements\":[]"))
        }
    }
}
