package com.inappify.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyOfferingResolverTest {

    private val defaultOffering = InappifyOffering(identifier = "default", isDefault = true)
    private val targetedOffering = InappifyOffering(identifier = "targeted", isDefault = false)
    private val fallbackOffering = InappifyOffering(identifier = "fallback", isDefault = false)

    @Test
    fun resolveOffering_returnsDefaultWhenNoRuleMatches() {
        val offerings = InappifyOfferings(
            offerings = listOf(defaultOffering, targetedOffering),
            rules = listOf(
                InappifyRule(
                    defaultOffering = "targeted",
                    conditions = listOf(
                        InappifyCondition(
                            field = "country",
                            operator = "=",
                            value = "US",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(defaultOffering, offerings.resolveOffering(context(country = "IR")))
    }

    @Test
    fun resolveOffering_usesSortedMatchingRuleAndPlacement() {
        val offerings = InappifyOfferings(
            offerings = listOf(defaultOffering, targetedOffering, fallbackOffering),
            rules = listOf(
                InappifyRule(
                    defaultOffering = "default",
                    sort = 20,
                    conditions = listOf(InappifyCondition(field = "any_audience")),
                ),
                InappifyRule(
                    defaultOffering = "default",
                    sort = 10,
                    conditions = listOf(
                        InappifyCondition(
                            field = "custom_attribute",
                            context = "segment",
                            operator = "=",
                            value = "vip",
                        ),
                    ),
                    placements = InappifyPlacement(
                        fallbackOfferingId = "fallback",
                        placementOfferings = listOf(
                            InappifyPlacementOffering(
                                placementIdentifier = "payment",
                                offeringIdentifier = "targeted",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            targetedOffering,
            offerings.resolveOffering(
                context = context(customAttributes = mapOf("segment" to "vip")),
                placement = "payment",
            ),
        )
        assertEquals(
            fallbackOffering,
            offerings.resolveOffering(
                context = context(customAttributes = mapOf("segment" to "vip")),
                placement = "unknown",
            ),
        )
    }

    @Test
    fun evaluateField_matchesSupportedOperatorsAndVersionRules() {
        val offerings = InappifyOfferings()
        val context = context(
            country = "ir",
            appVersion = "3.10.2",
            customAttributes = mapOf("level" to "12"),
        )

        assertTrue(offerings.evaluateField("country", "in", "[\"US\",\"IR\"]", context))
        assertTrue(
            offerings.evaluateField(
                "custom_attribute",
                ">=",
                "10",
                context,
                attributeKey = "level",
            ),
        )
        assertTrue(offerings.evaluateField("app_version", ">", "3.9.9", context))
        assertFalse(offerings.evaluateField("app_version", "<", "3.9.9", context))
        assertTrue(offerings.evaluateOperator("2", "=", 2))
        assertTrue(offerings.evaluateOperator("a", "not in", listOf("b", "c")))
        assertEquals(3.102, offerings.convertVersionToDouble("3.10.2"), 0.000001)
    }

    @Test
    fun evaluateOperator_preservesEqualityTypesForDirectAndMembershipChecks() {
        val offerings = InappifyOfferings()

        assertFalse(offerings.evaluateOperator("01", "=", "1"))
        assertTrue(offerings.evaluateOperator("01", "!=", "1"))
        assertTrue(offerings.evaluateOperator(1, "=", 1.0))
        assertTrue(offerings.evaluateOperator("01", "=", 1))
        assertTrue(offerings.evaluateOperator(1, "=", "1.0"))

        assertFalse(offerings.evaluateOperator("01", "in", listOf("1")))
        assertTrue(offerings.evaluateOperator("01", "in", listOf(1)))
        assertTrue(offerings.evaluateOperator("01", "not in", listOf("1")))
        assertFalse(offerings.evaluateOperator("01", "not in", listOf(1)))
    }

    @Test
    fun evaluateVersion_usesJsonLongAndDoubleSemantics() {
        val offerings = InappifyOfferings()
        val context = context(appVersion = "3.9")

        // JSON numeric 3.10 becomes Double 3.1 rather than retaining decimal scale.
        assertTrue(offerings.evaluateField("app_version", ">", "3.10", context))
        assertFalse(
            offerings.evaluateField(
                "app_version",
                "=",
                "3.10",
                context(appVersion = "3.10"),
            ),
        )
        assertTrue(offerings.evaluateField("app_version", ">", "3", context))
    }

    @Test
    fun resolveOffering_handlesVersionSegmentsLargerThanJvmIntegers() {
        val hugeSegment = "9".repeat(1_000)
        val offerings = InappifyOfferings(
            offerings = listOf(defaultOffering, targetedOffering),
            rules = listOf(
                InappifyRule(
                    defaultOffering = "targeted",
                    conditions = listOf(
                        InappifyCondition(
                            field = "app_version",
                            operator = "<",
                            value = "\"$hugeSegment.0\"",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(targetedOffering, offerings.resolveOffering(context()))
    }

    @Test
    fun defaultOffering_preservesNullAndMissingDefaultBehavior() {
        assertNull(InappifyOfferings(offerings = null).defaultOffering())
        assertThrows(NoSuchElementException::class.java) {
            InappifyOfferings(offerings = listOf(targetedOffering)).defaultOffering()
        }
    }

    private fun context(
        country: String = "IR",
        appVersion: String = "1.0.0",
        customAttributes: Map<String, String?> = emptyMap(),
    ): InappifyOfferingEvaluationContext = InappifyOfferingEvaluationContext(
        country = country,
        platform = "android",
        appVersion = appVersion,
        sdkVersion = "1.0.0",
        appId = 12,
        customAttributes = customAttributes,
    )
}
