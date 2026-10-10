package com.inappify.sdk

import com.inappify.sdk.internal.network.HttpRequest
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.network.TransportFailureKind
import com.inappify.sdk.internal.network.TransportResult
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTransportTest {

    private lateinit var server: MockWebServer
    private val transports = mutableListOf<OkHttpTransport>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        transports.forEach(OkHttpTransport::close)
        server.shutdown()
    }

    @Test
    fun routesOutsideV2_areRejectedBeforeHttp() = runBlocking {
        val transport = createTransport()
        for (path in listOf("../v1/configure", "/app/v1/purchase", "https://other.example/app/v2/configure")) {
            val result = transport.execute(HttpRequest(path, "{}")) as TransportResult.Failure
            assertEquals(TransportFailureKind.NETWORK, result.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun executeAfterClose_isCancelledWithoutEnqueuingARequest() = runBlocking {
        val transport = createTransport()
        transport.close()

        val result = transport.execute(request()) as TransportResult.Failure

        assertEquals(TransportFailureKind.CANCELLED, result.kind)
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    }

    @Test
    fun close_cancelsAnAlreadyRegisteredCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val transport = createTransport()
        val pending = async(Dispatchers.IO) {
            transport.execute(request())
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        transport.close()

        val result = withTimeout(2_000) {
            pending.await()
        } as TransportResult.Failure
        assertEquals(TransportFailureKind.CANCELLED, result.kind)
    }

    @Test
    fun oversizedChunkedResponse_isRejectedBeforeUnboundedBuffering() = runBlocking {
        val oversizedBody = "x".repeat(
            (OkHttpTransport.MAX_RESPONSE_BODY_BYTES + 1L).toInt(),
        )
        server.enqueue(
            MockResponse()
                .setChunkedBody(oversizedBody, 8_192),
        )
        val transport = createTransport()

        val result = transport.execute(request()) as TransportResult.Failure

        assertEquals(TransportFailureKind.MALFORMED_RESPONSE, result.kind)
    }

    @Test
    fun coroutineCancellation_closesAResponseWhoseBodyIsBeingRead() = runBlocking {
        val responseBody = BlockingResponseBody()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody)
                    .build()
            }
            .build()
        val transport = createTransport(client)
        val pending = async(Dispatchers.IO) {
            transport.execute(request())
        }
        assertTrue(responseBody.readStarted.await(2, TimeUnit.SECONDS))

        pending.cancelAndJoin()

        assertTrue(responseBody.closed.await(2, TimeUnit.SECONDS))
    }

    private fun createTransport(
        client: OkHttpClient = OkHttpClient(),
    ): OkHttpTransport = OkHttpTransport.create(
        baseUrl = server.url("/app/v2/"),
        client = client,
    ).also(transports::add)

    private fun request(): HttpRequest = HttpRequest(
        path = "configure",
        jsonBody = "{}",
    )
}

private class BlockingResponseBody : ResponseBody() {

    internal val readStarted = CountDownLatch(1)
    internal val closed = CountDownLatch(1)

    private val blockingSource = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            readStarted.countDown()
            try {
                closed.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Response-body read was interrupted.")
            }
            throw IOException("Response body was closed.")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed.countDown()
        }
    }.buffer()

    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = -1L

    override fun source(): BufferedSource = blockingSource
}
