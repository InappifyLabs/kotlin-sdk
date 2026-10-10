package com.inappify.sdk.internal.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Cancellable production transport with a strict close boundary and bounded
 * response buffering.
 */
internal class OkHttpTransport private constructor(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
    unsafeRawHttpLogging: Boolean,
) : HttpTransport, HttpDiagnosticSource {

    private val lifecycleLock = Any()
    private val activeCalls = LinkedHashSet<ActiveCall>()
    private val diagnosticReporter = HttpDiagnosticReporter(unsafeRawHttpLogging)

    @Volatile
    private var closed = false

    override suspend fun execute(request: HttpRequest): TransportResult {
        val startedNanos = System.nanoTime()
        if (closed) {
            return report(
                request = request,
                url = null,
                result = TransportResult.Failure(TransportFailureKind.CANCELLED),
                startedNanos = startedNanos,
            )
        }

        val url = baseUrl.resolve(request.path)?.takeIf {
            it.scheme == baseUrl.scheme && it.host == baseUrl.host &&
                it.port == baseUrl.port && it.encodedPath.startsWith("/app/v2/")
        }
            ?: return report(
                request = request,
                url = null,
                result = TransportResult.Failure(TransportFailureKind.NETWORK),
                startedNanos = startedNanos,
            )
        val httpRequest = Request.Builder()
            .url(url)
            .header("Accept", JSON_ACCEPT_HEADER)
            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
            .method(request.method, if (request.method == "GET") null else
                request.jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val activeCall = ActiveCall(client.newCall(httpRequest))

        return try {
            val result = try {
                activeCall.awaitResponse().use { response ->
                    val responseBody = runInterruptible {
                        response.readBodyWithinLimit()
                    }
                    TransportResult.Response(
                        HttpResponse(
                            statusCode = response.code,
                            body = responseBody,
                            requestId = response.header(REQUEST_ID_HEADER),
                            contentType = response.header(CONTENT_TYPE_HEADER),
                            headers = response.headers.names().associateWith { name ->
                                response.headers.values(name).joinToString(", ")
                            },
                        ),
                    )
                }
            } catch (_: ResponseBodyTooLargeException) {
                TransportResult.Failure(TransportFailureKind.MALFORMED_RESPONSE)
            } catch (_: RequestCancelledException) {
                TransportResult.Failure(TransportFailureKind.CANCELLED)
            } catch (_: SocketTimeoutException) {
                activeCall.failure(TransportFailureKind.TIMEOUT)
            } catch (_: InterruptedIOException) {
                activeCall.failure(TransportFailureKind.TIMEOUT)
            } catch (_: IOException) {
                activeCall.failure(TransportFailureKind.NETWORK)
            } catch (error: IllegalStateException) {
                if (activeCall.call.isCanceled() || closed) {
                    TransportResult.Failure(TransportFailureKind.CANCELLED)
                } else {
                    throw error
                }
            } finally {
                release(activeCall)
            }
            report(request, url, result, startedNanos)
        } catch (cancellation: CancellationException) {
            report(
                request = request,
                url = url,
                result = TransportResult.Failure(TransportFailureKind.CANCELLED),
                startedNanos = startedNanos,
            )
            throw cancellation
        }
    }

    override fun addHttpTraceListener(
        listener: com.inappify.sdk.InappifyHttpTraceListener,
    ): com.inappify.sdk.InappifyListenerRegistration =
        diagnosticReporter.addListener(listener)

    override fun close() {
        val callsToCancel = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeCalls.toList()
        }
        callsToCancel.forEach(ActiveCall::cancelAndCloseResponse)
        diagnosticReporter.close()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private fun report(
        request: HttpRequest,
        url: HttpUrl?,
        result: TransportResult,
        startedNanos: Long,
    ): TransportResult {
        val response = (result as? TransportResult.Response)?.response
        val failure = (result as? TransportResult.Failure)?.kind
        diagnosticReporter.report(
            method = request.method,
            url = url,
            unresolvedPath = request.path,
            requestBody = request.jsonBody,
            response = response,
            failure = failure,
            durationMillis = TimeUnit.NANOSECONDS.toMillis(
                (System.nanoTime() - startedNanos).coerceAtLeast(0L),
            ),
            requestHeaders = request.headers,
        )
        return result
    }

    private suspend fun ActiveCall.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancelAndCloseResponse()
            }

            var enqueueFailure: IOException? = null
            val enqueued = synchronized(lifecycleLock) {
                if (closed || !continuation.isActive) {
                    false
                } else {
                    activeCalls.add(this)
                    try {
                        call.enqueue(
                            object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    val failure = if (call.isCanceled()) {
                                        RequestCancelledException()
                                    } else {
                                        e
                                    }
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(failure)
                                    }
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    pendingResponse.set(response)
                                    if (!continuation.isActive) {
                                        pendingResponse.compareAndSet(response, null)
                                        response.close()
                                    } else {
                                        continuation.resume(response)
                                    }
                                }
                            },
                        )
                        true
                    } catch (_: RuntimeException) {
                        activeCalls.remove(this)
                        enqueueFailure = IOException("Unable to enqueue the HTTP request.")
                        false
                    }
                }
            }

            if (!enqueued && continuation.isActive) {
                continuation.resumeWithException(
                    enqueueFailure ?: RequestCancelledException(),
                )
            }
        }

    private fun release(activeCall: ActiveCall) {
        activeCall.pendingResponse.getAndSet(null)?.close()
        synchronized(lifecycleLock) {
            activeCalls.remove(activeCall)
        }
    }

    private fun ActiveCall.failure(defaultKind: TransportFailureKind): TransportResult.Failure =
        TransportResult.Failure(
            if (call.isCanceled() || closed) {
                TransportFailureKind.CANCELLED
            } else {
                defaultKind
            },
        )

    private fun Response.readBodyWithinLimit(): String? {
        val responseBody = body ?: return null
        val declaredLength = responseBody.contentLength()
        if (declaredLength > MAX_RESPONSE_BODY_BYTES) {
            throw ResponseBodyTooLargeException()
        }

        val source = responseBody.source()
        source.request(MAX_RESPONSE_BODY_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BODY_BYTES) {
            throw ResponseBodyTooLargeException()
        }
        return source.readUtf8()
    }

    private class ActiveCall(
        internal val call: Call,
    ) {
        internal val pendingResponse = AtomicReference<Response?>()

        internal fun cancelAndCloseResponse() {
            call.cancel()
            pendingResponse.getAndSet(null)?.close()
        }
    }

    internal companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val JSON_ACCEPT_HEADER = "application/json"
        private const val REQUEST_ID_HEADER = "X-Request-ID"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val HTTP_METHOD = "POST"
        internal const val MAX_RESPONSE_BODY_BYTES = 1024L * 1024L
        internal fun create(
            baseUrl: HttpUrl,
            client: OkHttpClient,
            unsafeRawHttpLogging: Boolean = false,
        ): OkHttpTransport = OkHttpTransport(baseUrl, client, unsafeRawHttpLogging)
    }
}

private class RequestCancelledException : IOException()

private class ResponseBodyTooLargeException : IOException()
