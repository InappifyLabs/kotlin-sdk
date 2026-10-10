package com.inappify.sdk

import com.inappify.sdk.internal.network.HttpDiagnosticSanitizer
import com.inappify.sdk.internal.network.HttpRequest
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.network.TransportResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDiagnosticsTest {
    @Test
    fun `bearer credentials echoed in errors are redacted`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTransport.create(server.url("/app/v2/"), OkHttpClient())
        val trace = AtomicReference<InappifyHttpTrace>()
        val delivered = CountDownLatch(1)
        transport.addHttpTraceListener { trace.set(it); delivered.countDown() }
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":false,"message":"expired go-bearer-secret","sessionToken":"new-session-secret","verification":{"jws":"signed-secret"}}"""))
            transport.execute(HttpRequest("customerInfo", "{}", headers = mapOf("Authorization" to "Bearer go-bearer-secret")))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals("<redacted>", trace.get().requestHeaders["Authorization"])
            listOf("go-bearer-secret", "new-session-secret", "signed-secret").forEach {
                assertFalse(trace.get().responseBody!!.contains(it))
            }
        } finally { transport.close(); server.shutdown() }
    }

    @Test
    fun `login attempt secret is redacted from diagnostics`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTransport.create(server.url("/app/v2/"), OkHttpClient())
        val trace = AtomicReference<InappifyHttpTrace>()
        val delivered = CountDownLatch(1)
        transport.addHttpTraceListener { trace.set(it); delivered.countDown() }
        try {
            val attempt = "login-attempt-opaque-secret-1234567890"
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"status":false,"message":"$attempt"}"""))
            transport.execute(HttpRequest("logout", "{}",
                headers = mapOf("X-Inappify-Login-Attempt" to attempt,
                    "X-Inappify-Logout-Attempt" to attempt)))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals("<redacted>", trace.get().requestHeaders["X-Inappify-Login-Attempt"])
            assertEquals("<redacted>", trace.get().requestHeaders["X-Inappify-Logout-Attempt"])
            assertFalse(trace.get().responseBody!!.contains(attempt))
        } finally { transport.close(); server.shutdown() }
    }

    @Test
    fun `transport emits one useful exchange with credentials redacted`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTransport.create(
            baseUrl = server.url("/app/v2/"),
            client = OkHttpClient(),
        )
        val received = AtomicReference<InappifyHttpTrace?>()
        val delivered = CountDownLatch(1)
        val registration = transport.addHttpTraceListener { trace ->
            received.set(trace)
            delivered.countDown()
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Request-ID", "safe-request-123")
                .setHeader("Set-Cookie", "must-not-be-visible")
                .setBody(
                    """{"status":false,"token":"server-token-secret","message":"bad key mobile-api-key"}""",
                ),
        )

        val result = transport.execute(
            HttpRequest(
                path = "configure?checkoutId=query-secret",
                jsonBody =
                    """{"apikey":"mobile-api-key","appUserIdentifier":"user-secret","vCode":42}""",
            ),
        )

        assertTrue(result is TransportResult.Response)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        val trace = received.get()
        assertNotNull(trace)
        requireNotNull(trace)
        assertEquals("POST", trace.method)
        assertTrue(trace.endpoint.endsWith("/app/v2/configure"))
        assertFalse(trace.endpoint.contains("query-secret"))
        assertEquals(401, trace.statusCode)
        assertEquals("safe-request-123", trace.requestId)
        assertFalse(trace.containsSensitiveData)
        assertEquals("application/json", trace.responseHeaders["Content-Type"])
        assertFalse(trace.responseHeaders.containsKey("Set-Cookie"))
        assertTrue(trace.requestBody.contains("\"vCode\":42"))
        assertFalse(trace.requestBody.contains("mobile-api-key"))
        assertFalse(trace.requestBody.contains("user-secret"))
        assertFalse(trace.responseBody.orEmpty().contains("server-token-secret"))
        assertFalse(trace.responseBody.orEmpty().contains("mobile-api-key"))
        assertNull(trace.failure)
        assertFalse(trace.toString().contains(trace.requestBody))
        assertFalse(trace.toString().contains(trace.responseBody.orEmpty()))

        registration.close()
        transport.close()
        server.shutdown()
    }

    @Test
    fun `unsafe debug transport emits complete raw exchange`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTransport.create(
            baseUrl = server.url("/app/v2/"),
            client = OkHttpClient(),
            unsafeRawHttpLogging = true,
        )
        val received = AtomicReference<InappifyHttpTrace?>()
        val delivered = CountDownLatch(1)
        transport.addHttpTraceListener { trace ->
            received.set(trace)
            delivered.countDown()
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Request-ID", "raw-request-id")
                .setHeader("Set-Cookie", "raw-cookie-secret")
                .setBody(
                    """{"status":true,"token":"server-token-secret","deliveryId":"delivery-secret"}""",
                ),
        )

        transport.execute(
            HttpRequest(
                path = "store/verifications/verification-secret/status?token=query-secret",
                jsonBody =
                    """{"apikey":"mobile-api-key","token":"session-secret","signature":"store-signature"}""",
            ),
        )

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        val trace = requireNotNull(received.get())
        assertTrue(trace.containsSensitiveData)
        assertTrue(trace.endpoint.contains("verification-secret"))
        assertTrue(trace.endpoint.contains("query-secret"))
        assertTrue(trace.requestBody.contains("mobile-api-key"))
        assertTrue(trace.requestBody.contains("session-secret"))
        assertTrue(trace.requestBody.contains("store-signature"))
        assertTrue(trace.responseBody.orEmpty().contains("server-token-secret"))
        assertTrue(trace.responseBody.orEmpty().contains("delivery-secret"))
        assertEquals("raw-cookie-secret", trace.responseHeaders["Set-Cookie"])
        assertEquals("raw-request-id", trace.requestId)
        assertFalse(trace.toString().contains("mobile-api-key"))
        assertFalse(trace.toString().contains("server-token-secret"))

        transport.close()
        server.shutdown()
    }

    @Test
    fun `listener failure cannot change transport result or suppress peers`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OkHttpTransport.create(
            baseUrl = server.url("/app/v2/"),
            client = OkHttpClient(),
        )
        var peerCalls = 0
        val delivered = CountDownLatch(1)
        transport.addHttpTraceListener { error("host listener failed") }
        transport.addHttpTraceListener {
            peerCalls += 1
            delivered.countDown()
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = transport.execute(HttpRequest("configure", "{}"))

        assertTrue(result is TransportResult.Response)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(1, peerCalls)
        transport.close()
        server.shutdown()
    }

    @Test
    fun `sanitizer redacts marketplace evidence attributes and dynamic paths`() {
        val purchaseToken = "purchase-token-secret"
        val signature = "signature-secret"
        val body =
            """
            {
              "apikey":"api-secret",
              "productIdentifier":"premium_monthly",
              "offeringIdentifier":"default",
              "purchase":{
                "token":"$purchaseToken",
                "signature":"$signature",
                "orderId":"order-secret",
                "purchaseTime":123
              },
              "attributes":[{"key":"nickname","value":"person-secret"}],
              "status":true
            }
            """.trimIndent()

        val sanitized = HttpDiagnosticSanitizer.sanitizeBody(body, emptySet())
        val endpoint = HttpDiagnosticSanitizer.sanitizeEndpoint(
            url = (
                "https://api.inappify.com/app/v2/store/deliveries/" +
                    "delivery-secret/consume-result"
                ).toHttpUrl(),
            unresolvedPath = "unused",
        )

        assertTrue(sanitized.contains("premium_monthly"))
        assertTrue(sanitized.contains("\"purchaseTime\":123"))
        assertTrue(sanitized.contains("\"status\":true"))
        listOf(
            "api-secret",
            purchaseToken,
            signature,
            "order-secret",
            "person-secret",
        ).forEach { secret -> assertFalse(sanitized.contains(secret)) }
        assertFalse(endpoint.contains("delivery-secret"))
        assertTrue(endpoint.contains("{redacted}"))
    }

    @Test
    fun `non JSON bodies are described without exposing their content`() {
        val raw = "upstream echoed api-secret in an HTML error"

        val sanitized = HttpDiagnosticSanitizer.sanitizeBody(raw, setOf("api-secret"))

        assertTrue(sanitized.startsWith("<non-JSON body omitted:"))
        assertFalse(sanitized.contains("api-secret"))
    }
}
