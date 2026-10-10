package com.inappify.sdk.internal.network

internal class HttpRequest(
    internal val path: String,
    internal val jsonBody: String,
    internal val method: String = "POST",
    internal val headers: Map<String, String> = emptyMap(),
)

internal class HttpResponse(
    internal val statusCode: Int,
    internal val body: String?,
    internal val requestId: String?,
    internal val contentType: String? = null,
    internal val headers: Map<String, String> = emptyMap(),
)

internal enum class TransportFailureKind {
    NETWORK,
    TIMEOUT,
    CANCELLED,
    MALFORMED_RESPONSE,
}

internal sealed interface TransportResult {
    class Response(
        internal val response: HttpResponse,
    ) : TransportResult

    class Failure(
        internal val kind: TransportFailureKind,
    ) : TransportResult
}

internal interface HttpTransport : AutoCloseable {
    suspend fun execute(request: HttpRequest): TransportResult

    override fun close()
}
