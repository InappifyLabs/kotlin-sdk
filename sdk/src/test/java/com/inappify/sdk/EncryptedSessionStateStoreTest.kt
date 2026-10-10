package com.inappify.sdk

import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.readBytesWithLimit
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedSessionStateStoreTest {

    @Test
    fun legacySessionFrom_readsFlutterSchemaWithoutConsumingUnrelatedValues() {
        val values = mapOf<String, Any>(
            "flutter.token" to "legacy-token",
            "flutter.appUserIdentifier" to "customer-1",
            "flutter.forceVersion" to 2_147_483_648L,
            "flutter.appId" to 4_294_967_296L,
            "flutter.customerInfo" to "{\"originalAppUserId\":\"customer-1\"}",
            "flutter.offerings" to "{\"offerings\":[]}",
            "flutter.dateTimeCustomerInfo" to "2026-08-26T12:00:00.000Z",
            "flutter.unrelatedApplicationValue" to "must-remain",
        )

        val migrated = EncryptedSessionStateStore.legacySessionFrom(values)

        requireNotNull(migrated)
        assertEquals("legacy-token", migrated.token)
        assertEquals("customer-1", migrated.appUserIdentifier)
        assertEquals(2_147_483_648L, migrated.forceVersion)
        assertEquals(4_294_967_296L, migrated.appId)
        assertEquals(
            "{\"originalAppUserId\":\"customer-1\"}",
            migrated.customerInfoJson,
        )
        assertEquals("{\"offerings\":[]}", migrated.offeringsJson)
        assertNull(migrated.cacheContextFingerprint)
        assertNull(migrated.purchaseRecoveryId)
        assertEquals(
            "2026-08-26T12:00:00.000Z",
            migrated.customerInfoUpdatedAt,
        )
        assertFalse(
            EncryptedSessionStateStore.LEGACY_PREFERENCE_KEYS.contains(
                "flutter.unrelatedApplicationValue",
            ),
        )
    }

    @Test
    fun legacyCleanupKeySet_containsOnlyOwnedInappifyKeys() {
        assertEquals(
            setOf(
                "flutter.token",
                "flutter.appUserIdentifier",
                "flutter.forceVersion",
                "flutter.appId",
                "flutter.customerInfo",
                "flutter.offerings",
                "flutter.dateTimeCustomerInfo",
            ),
            EncryptedSessionStateStore.LEGACY_PREFERENCE_KEYS,
        )
    }

    @Test
    fun legacySessionFrom_ignoresUnrelatedOrWronglyTypedValues() {
        val migrated = EncryptedSessionStateStore.legacySessionFrom(
            mapOf(
                "flutter.token" to 123,
                "flutter.appUserIdentifier" to false,
                "flutter.unrelatedApplicationValue" to "value",
            ),
        )

        assertNull(migrated)
    }

    @Test
    fun boundedFileReader_acceptsLimitAndRejectsOversizedInput() {
        val payload = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(
            payload,
            ByteArrayInputStream(payload).readBytesWithLimit(payload.size),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(payload).readBytesWithLimit(payload.size - 1)
        }
    }
}
