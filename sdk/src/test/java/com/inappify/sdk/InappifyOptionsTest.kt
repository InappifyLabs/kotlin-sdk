package com.inappify.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InappifyOptionsTest {
    @Test
    fun configureAcceptsOnlyAnAppKeyAndOptionalCustomerIdentity() {
        val anonymous = InappifyOptions(apiKey = "public-fixture-key")
        assertNull(anonymous.appUserIdentifier)
        val identified = InappifyOptions("public-fixture-key", "customer-123")
        assertEquals("customer-123", identified.appUserIdentifier)

        val constructor = InappifyOptions::class.java.constructors.single { !it.isSynthetic }
        assertEquals(listOf(String::class.java, String::class.java), constructor.parameterTypes.toList())
        assertEquals(setOf("getApiKey", "getAppUserIdentifier", "toString"),
            InappifyOptions::class.java.declaredMethods.map { it.name }.toSet())
    }
}
