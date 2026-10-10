package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyV2Configuration
import org.junit.Assert.*
import org.junit.Test

class GoConfigurationCompatibilityTest {
    private val fixture = SigningFixture()

    @Test fun originalNamedParameterGetterAndConstantRemainAvailable() {
        val config = InappifyV2Configuration(
            apiBaseUrl = "https://sdk.example.com/app/v2/",
            issuer = fixture.config.issuer,
            appId = 12,
            projectId = 34,
            pinnedSigningKeys = fixture.config.pinnedSigningKeys,
            paymentHosts = emptySet(),
        )
        assertEquals(config.apiBaseUrl, config.sdkApiBaseUrl)
        assertEquals(config.apiBaseUrl, config.commerceApiBaseUrl)
        assertEquals(InappifyV2Configuration.DEFAULT_API_BASE_URL,
            InappifyV2Configuration.DEFAULT_SDK_API_BASE_URL)
        assertEquals(config.apiBaseUrl, InappifyV2Configuration::class.java
            .getMethod("getApiBaseUrl").invoke(config))
    }

    @Test fun originalKotlinDefaultArgumentConstructorCanStillBeLinkedAndInvoked() {
        // This is the descriptor used by Kotlin callers compiled against V2.0/V2.1.
        val constructor = InappifyV2Configuration::class.java.getConstructor(
            String::class.java, String::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Map::class.java, Set::class.java,
            Set::class.java, Int::class.javaPrimitiveType,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
        val config = constructor.newInstance(null, fixture.config.issuer, 12L, 34L,
            fixture.config.pinnedSigningKeys, emptySet<String>(), null, 1 or 64, null)
        assertEquals(InappifyV2Configuration.DEFAULT_API_BASE_URL, config.apiBaseUrl)
        assertTrue(config.assetHosts.isEmpty())
        assertEquals(InappifyV2Configuration.DEFAULT_COMMERCE_API_BASE_URL, config.commerceApiBaseUrl)
    }

    @Test fun additionalCommerceEndpointIsValidatedWithoutWeakeningLegacyEndpoint() {
        for (url in listOf("http://commerce.example.com/app/v2/",
            "https://commerce.example.com/app/v1/", "https://commerce.example.com/app/v2/?secret=x")) {
            assertThrows(IllegalArgumentException::class.java) {
                InappifyV2Configuration(sdkApiBaseUrl = fixture.config.apiBaseUrl,
                    issuer = fixture.config.issuer, appId = 12, projectId = 34,
                    pinnedSigningKeys = fixture.config.pinnedSigningKeys,
                    paymentHosts = emptySet(), commerceApiBaseUrl = url)
            }
        }
    }
}
