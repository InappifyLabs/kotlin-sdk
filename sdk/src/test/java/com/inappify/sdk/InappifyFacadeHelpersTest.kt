package com.inappify.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyFacadeHelpersTest {

    @Test
    fun isCustomerAnonymous_matchesInappifyMarkerSemantics() {
        assertTrue(isCustomerAnonymous(null))
        assertTrue(isCustomerAnonymous("InaAnonymousId-1"))
        assertTrue(isCustomerAnonymous("customer-InaAnonymous-migrated"))

        assertFalse(isCustomerAnonymous(""))
        assertFalse(isCustomerAnonymous("inaanonymousid-1"))
        assertFalse(isCustomerAnonymous("customer-1"))
    }

    @Test
    fun customerEntitlementHelpers_matchActivitySemantics() {
        val activeAfterDuplicate = InappifyEntitlement(
            identifier = "duplicate",
            isActive = true,
            expirationDate = "2999-01-01T00:00:00Z",
        )
        val customerInfo = InappifyCustomerInfo(
            entitlements = listOf(
                InappifyEntitlement(identifier = "inactive", isActive = false),
                InappifyEntitlement(identifier = "missing-flag", isActive = null),
                InappifyEntitlement(identifier = "no-expiration", isActive = true),
                InappifyEntitlement(identifier = "empty-expiration", isActive = true, expirationDate = ""),
                InappifyEntitlement(identifier = "invalid-expiration", isActive = true, expirationDate = "not-a-date"),
                InappifyEntitlement(identifier = "past", isActive = true, expirationDate = "2000-01-01T00:00:00Z"),
                InappifyEntitlement(identifier = "future", isActive = true, expirationDate = "2999-01-01T00:00:00.123456Z"),
                InappifyEntitlement(identifier = "offset-future", isActive = true, expirationDate = "2999-01-01T03:30:00+03:30"),
                InappifyEntitlement(identifier = "duplicate", isActive = false),
                activeAfterDuplicate,
            ),
        )

        assertFalse(customerInfo.isActiveEntitlement("inactive"))
        assertFalse(customerInfo.isActiveEntitlement("missing-flag"))
        assertFalse(customerInfo.isActiveEntitlement("past"))
        assertFalse(customerInfo.hasEntitlement("unknown"))
        assertNull(customerInfo.getEntitlement("inactive"))

        assertTrue(customerInfo.isActiveEntitlement("no-expiration"))
        assertTrue(customerInfo.isActiveEntitlement("empty-expiration"))
        assertTrue(customerInfo.isActiveEntitlement("invalid-expiration"))
        assertTrue(customerInfo.hasEntitlement("future"))
        assertTrue(customerInfo.hasEntitlement("offset-future"))
        assertSame(activeAfterDuplicate, customerInfo.findActiveEntitlement("duplicate"))
        assertSame(activeAfterDuplicate, customerInfo.getEntitlement("duplicate"))
    }

    @Test
    fun snapshotEntitlementHelpers_delegateToCustomerInfo() {
        val entitlement = InappifyEntitlement(
            identifier = "premium",
            isActive = true,
            expirationDate = null,
        )
        val snapshot = snapshotWith(
            InappifyCustomerInfo(entitlements = listOf(entitlement)),
        )
        val emptySnapshot = snapshotWith(null)

        assertTrue(snapshot.isActiveEntitlement("premium"))
        assertTrue(snapshot.hasEntitlement("premium"))
        assertSame(entitlement, snapshot.findActiveEntitlement("premium"))
        assertSame(entitlement, snapshot.getEntitlement("premium"))

        assertFalse(emptySnapshot.isActiveEntitlement("premium"))
        assertFalse(emptySnapshot.hasEntitlement("premium"))
        assertNull(emptySnapshot.findActiveEntitlement("premium"))
        assertNull(emptySnapshot.getEntitlement("premium"))
    }

    @Test
    fun entitlementExpiration_supportsContractDateFormatsAndOverflowNormalization() {
        val parseablePastDates = listOf(
            "20000101",
            "+002000-01-01",
            "2000-01-01T14Z",
            "2000-01-01T14+03",
            "20000101T140000Z",
            "2000-01-01 14:00:00,123456789z",
            "2002-02-27T14:00:00-0500",
            "2000-01-42",
            "2000-13-01",
        )
        val invalidOrOutOfRangeDates = listOf(
            "2000/01/01",
            "2000-01-01Z",
            "+999999-01-01",
        )
        val entitlements = buildList {
            parseablePastDates.forEachIndexed { index, date ->
                add(
                    InappifyEntitlement(
                        identifier = "past-$index",
                        isActive = true,
                        expirationDate = date,
                    ),
                )
            }
            invalidOrOutOfRangeDates.forEachIndexed { index, date ->
                add(
                    InappifyEntitlement(
                        identifier = "invalid-$index",
                        isActive = true,
                        expirationDate = date,
                    ),
                )
            }
            add(
                InappifyEntitlement(
                    identifier = "compact-future",
                    isActive = true,
                    expirationDate = "+0029990101T140000Z",
                ),
            )
        }
        val customerInfo = InappifyCustomerInfo(entitlements = entitlements)

        parseablePastDates.indices.forEach { index ->
            assertFalse(customerInfo.hasEntitlement("past-$index"))
        }
        invalidOrOutOfRangeDates.indices.forEach { index ->
            assertTrue(customerInfo.hasEntitlement("invalid-$index"))
        }
        assertTrue(customerInfo.hasEntitlement("compact-future"))
    }

    private fun snapshotWith(customerInfo: InappifyCustomerInfo?): InappifySnapshot =
        InappifySnapshot(
            revision = 1,
            isConfigured = true,
            isAuthenticated = customerInfo != null,
            appUserIdentifier = customerInfo?.originalAppUserId,
            market = InappifyMarket.NONE,
            country = "IR",
            appVersion = "1.0.0",
            sdkVersion = "test",
            storeInfo = null,
            forceVersion = 1,
            appId = null,
            customerInfo = customerInfo,
            offerings = null,
            failedToLoadCustomerInfo = false,
            failedToLoadOfferings = false,
        )
}
