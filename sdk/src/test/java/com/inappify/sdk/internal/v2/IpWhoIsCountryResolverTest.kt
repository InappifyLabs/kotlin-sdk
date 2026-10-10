package com.inappify.sdk.internal.v2

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpWhoIsCountryResolverTest {
    @Test fun readsCountryCodeOnlyFromSuccessfulResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"country_code":"tr"}"""))
            assertEquals("TR", IpWhoIsCountryResolver(server.url("/")).resolve())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidResponseReturnsNullWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            assertNull(IpWhoIsCountryResolver(server.url("/")).resolve())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unsuccessfulPayloadReturnsNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":false,"country_code":"US"}"""))
            assertNull(IpWhoIsCountryResolver(server.url("/")).resolve())
            assertEquals(1, server.requestCount)
        }
    }
}
