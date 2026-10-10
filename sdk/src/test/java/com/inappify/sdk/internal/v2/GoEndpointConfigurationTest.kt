package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyV2Configuration
import com.inappify.sdk.InappifyClient
import com.inappify.sdk.InappifyV2Client
import com.inappify.sdk.internal.network.OkHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GoEndpointConfigurationTest {
    private val fixture = SigningFixture()

    private fun defaultConfiguration() = InappifyV2Configuration(
        issuer = fixture.config.issuer,
        appId = fixture.config.appId,
        projectId = fixture.config.projectId,
        pinnedSigningKeys = fixture.config.pinnedSigningKeys,
        paymentHosts = fixture.config.paymentHosts,
    )

    @Test fun officialServiceRootIncludesExactlyOneGoVersionPrefix() {
        val config = defaultConfiguration()
        assertEquals("https://service.inappify.com/app/v2/", config.sdkApiBaseUrl)
        assertEquals("https://api.inappify.com/app/v2/", config.commerceApiBaseUrl)
        assertEquals("https://service.inappify.com/app/v2/configure",
            config.sdkApiBaseUrl.toHttpUrl().resolve("configure").toString())
        // Only the service URL has a default: trust and tenant settings remain explicit.
        assertEquals(fixture.config.issuer, config.issuer)
        assertEquals(fixture.config.pinnedSigningKeys, config.pinnedSigningKeys)
    }

    @Test fun explicitBackendUrlAndExistingConstructorRemainSupported() {
        val config = InappifyV2Configuration("https://staging.example.com/app/v2/",
            fixture.config.issuer, 12, 34, fixture.config.pinnedSigningKeys, emptySet(), emptySet())
        assertEquals("https://staging.example.com/app/v2/", config.sdkApiBaseUrl)
        assertEquals("https://staging.example.com/app/v2/", config.commerceApiBaseUrl)
        assertNotNull(InappifyV2Configuration::class.java.getConstructor(
            String::class.java, String::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Map::class.java, Set::class.java, Set::class.java))
    }

    @Test fun sdkAndCommerceUrlsCanBeConfiguredIndependently() {
        val config = InappifyV2Configuration(
            sdkApiBaseUrl = "https://sdk.staging.example.com/app/v2/",
            issuer = fixture.config.issuer,
            appId = 12,
            projectId = 34,
            pinnedSigningKeys = fixture.config.pinnedSigningKeys,
            paymentHosts = emptySet(),
            commerceApiBaseUrl = "https://commerce.staging.example.com/app/v2/",
        )
        assertEquals("https://sdk.staging.example.com/app/v2/", config.sdkApiBaseUrl)
        assertEquals("https://commerce.staging.example.com/app/v2/", config.commerceApiBaseUrl)
    }

    @Test fun defaultDoesNotWeakenExplicitUrlValidation() {
        for (url in listOf(
            "http://service.inappify.com/app/v2/", "https://service.inappify.com/app/v1/",
            "https://service.inappify.com/app/v2/?token=secret",
            "https://secret@service.inappify.com/app/v2/", "https://service.inappify.com/app/v2/#fragment",
        )) assertThrows(IllegalArgumentException::class.java) {
            InappifyV2Configuration(url, fixture.config.issuer, 12, 34,
                fixture.config.pinnedSigningKeys, emptySet())
        }
    }

    @Test fun everyGoEndpointUsesOfficialVersionedPathAndBearerContract() = runBlocking {
        val requests = mutableListOf<Request>()
        // Short-circuit all HTTP calls; this test never contacts the production backend.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"status":true}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = GoApi(OkHttpTransport.create(defaultConfiguration().sdkApiBaseUrl.toHttpUrl(), http), { fixture.now })
        val endpoints = listOf("configure", "public-keys", "customerInfo", "login", "logout",
            "attributes", "offerings", "validateDiscountCode")
        try {
            for (endpoint in endpoints) api.request(endpoint, when (endpoint) {
                "configure" -> "test-public-key"
                "public-keys" -> null
                else -> "test-go-session"
            })
            assertEquals(endpoints.size, requests.size)
            endpoints.zip(requests).forEach { (endpoint, request) ->
                assertEquals("https://service.inappify.com/app/v2/$endpoint", request.url.toString())
                assertEquals(if (endpoint == "public-keys") "GET" else "POST", request.method)
                assertEquals(when (endpoint) {
                    "public-keys" -> null
                    "configure" -> "Bearer test-public-key"
                    else -> "Bearer test-go-session"
                }, request.header("Authorization"))
            }
        } finally { api.close() }
    }

    @Test fun defaultFactoryExposesTheV2SessionApi() {
        assertEquals(InappifyV2Client::class.java,
            InappifyClient::class.java.getMethod("create", android.content.Context::class.java).returnType)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.inappify.sdk.internal.DefaultInappifyClient")
        }
    }
}
