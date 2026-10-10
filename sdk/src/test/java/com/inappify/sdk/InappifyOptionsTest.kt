package com.inappify.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InappifyOptionsTest {
    @Test
    fun subscriptionRecoveryIsEnabledByDefault() {
        assertTrue(InappifyOptions(apiKey = "public-fixture-key").enableSubscriptionRecoveryWithoutPayload)
    }

    @Test
    fun subscriptionRecoveryRetainsExplicitConfiguration() {
        assertFalse(InappifyOptions(
            apiKey = "public-fixture-key",
            enableSubscriptionRecoveryWithoutPayload = false,
        ).enableSubscriptionRecoveryWithoutPayload)
        assertTrue(InappifyOptions(
            apiKey = "public-fixture-key",
            enableSubscriptionRecoveryWithoutPayload = true,
        ).enableSubscriptionRecoveryWithoutPayload)
    }

    @Test
    fun originalOptionsJvmConstructorEnablesSubscriptionRecovery() {
        val options = InappifyOptions::class.java.getConstructor(
            String::class.java, String::class.java, InappifyMarket::class.java,
            String::class.java, String::class.java, String::class.java,
        ).newInstance("public-fixture-key", null, null, null, null, null)

        assertTrue(options.enableSubscriptionRecoveryWithoutPayload)
    }
}
