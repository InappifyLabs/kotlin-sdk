package com.inappify.sdk

import java.util.Collections
import java.util.LinkedHashMap

/** Failure reported by the HTTP transport before a usable response was available. */
public enum class InappifyHttpFailure {
    NETWORK,
    TIMEOUT,
    CANCELLED,
    MALFORMED_RESPONSE,
}

/**
 * One Inappify HTTP exchange captured by the opt-in diagnostic channel.
 *
 * Clients created through [InappifyClient.create] always produce bounded,
 * security-filtered traces. The public factory does not expose an unsafe
 * HTTP tracing mode. Check [containsSensitiveData] before forwarding traces.
 */
public class InappifyHttpTrace internal constructor(
    public val method: String,
    public val endpoint: String,
    requestHeaders: Map<String, String>,
    public val requestBody: String,
    public val statusCode: Int?,
    responseHeaders: Map<String, String>,
    public val responseBody: String?,
    public val requestId: String?,
    public val durationMillis: Long,
    public val failure: InappifyHttpFailure?,
    public val containsSensitiveData: Boolean,
) {

    init {
        require(method.isNotBlank()) { "method must not be blank." }
        require(endpoint.isNotBlank()) { "endpoint must not be blank." }
        require(durationMillis >= 0L) { "durationMillis must not be negative." }
    }

    /** Captured request headers, exposed as an immutable copy. */
    public val requestHeaders: Map<String, String> = immutableCopy(requestHeaders)

    /** Captured response headers, exposed as an immutable copy. */
    public val responseHeaders: Map<String, String> = immutableCopy(responseHeaders)

    /** Returns metadata without serializing even the redacted body text. */
    public override fun toString(): String =
        "InappifyHttpTrace(method=$method, endpoint=$endpoint, " +
            "statusCode=$statusCode, durationMillis=$durationMillis, " +
            "failure=$failure, containsSensitiveData=$containsSensitiveData, " +
            "requestBody=<redacted>, responseBody=<redacted>)"

    private companion object {
        private fun immutableCopy(source: Map<String, String>): Map<String, String> =
            Collections.unmodifiableMap(LinkedHashMap(source))
    }
}

/** Receives completed Inappify HTTP exchanges from the configured diagnostic mode. */
public fun interface InappifyHttpTraceListener {

    /**
     * Handles one completed HTTP exchange.
     *
     * The callback can run on a background thread and must return promptly.
     * Listener failures are isolated and never change the SDK operation result.
     */
    public fun onHttpTrace(trace: InappifyHttpTrace): Unit
}

/** Internal capability for production HTTP diagnostics. */
internal interface HttpDiagnosticsCapableClient {
    fun addHttpTraceListenerInternal(
        listener: InappifyHttpTraceListener,
    ): InappifyListenerRegistration
}

/**
 * Registers an opt-in listener for Inappify HTTP exchanges.
 *
 * The production client provides bounded, security-filtered values. This API
 * never enables OkHttp wire logging. Clients without this diagnostic capability
 * return an inert registration. Remove the listener when its owner is released.
 */
public fun InappifyClient.addHttpTraceListener(
    listener: InappifyHttpTraceListener,
): InappifyListenerRegistration =
    if (this is HttpDiagnosticsCapableClient) {
        addHttpTraceListenerInternal(listener)
    } else {
        InappifyListenerRegistration.create(Runnable {})
    }
