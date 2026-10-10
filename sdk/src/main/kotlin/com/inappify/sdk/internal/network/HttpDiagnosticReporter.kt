package com.inappify.sdk.internal.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.inappify.sdk.InappifyHttpFailure
import com.inappify.sdk.InappifyHttpTrace
import com.inappify.sdk.InappifyHttpTraceListener
import com.inappify.sdk.InappifyListenerRegistration
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl

/** Source of opt-in transport diagnostics. Production clients always redact them. */
internal interface HttpDiagnosticSource {
    fun addHttpTraceListener(
        listener: InappifyHttpTraceListener,
    ): InappifyListenerRegistration
}

/** Per-client diagnostic registry. It is empty and allocation-light by default. */
internal class HttpDiagnosticReporter(
    private val unsafeRawHttpLogging: Boolean = false,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<InappifyHttpTraceListener>()
    private val listenerExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, LISTENER_THREAD_NAME).apply { isDaemon = true }
        }

    internal fun addListener(
        listener: InappifyHttpTraceListener,
    ): InappifyListenerRegistration {
        check(!closed.get()) { "Inappify HTTP diagnostics are closed." }
        listeners.add(listener)
        if (closed.get()) {
            listeners.remove(listener)
            error("Inappify HTTP diagnostics are closed.")
        }
        return InappifyListenerRegistration.create(
            Runnable { listeners.remove(listener) },
        )
    }

    internal fun report(
        method: String,
        url: HttpUrl?,
        unresolvedPath: String,
        requestBody: String,
        response: HttpResponse?,
        failure: TransportFailureKind?,
        durationMillis: Long,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        if (closed.get() || listeners.isEmpty()) return

        val requestSecrets = if (unsafeRawHttpLogging) {
            emptySet()
        } else {
            HttpDiagnosticSanitizer.collectSensitiveValues(requestBody) + requestHeaders.entries
                .filter { it.key.equals("Authorization", true) ||
                    it.key.equals("X-Inappify-Login-Attempt", true) ||
                    it.key.equals("X-Inappify-Logout-Attempt", true) }
                .flatMap { listOf(it.value, it.value.substringAfter(' ')) }
        }
        val exposedRequestId = if (unsafeRawHttpLogging) {
            response?.requestId
        } else {
            response?.requestId
                .toSafeRequestId()
                ?.takeUnless { it in requestSecrets }
        }
        val trace = InappifyHttpTrace(
            method = method,
            endpoint = if (unsafeRawHttpLogging) {
                url?.toString() ?: unresolvedPath
            } else {
                HttpDiagnosticSanitizer.sanitizeEndpoint(url, unresolvedPath)
            },
            requestHeaders = REQUEST_HEADERS + requestHeaders.mapValues { (name, value) ->
                if (name.equals("Authorization", true) || name.equals("X-Inappify-Login-Attempt", true) ||
                    name.equals("X-Inappify-Logout-Attempt", true))
                    "<redacted>" else value.safeHeaderValue(requestSecrets)
            },
            requestBody = if (unsafeRawHttpLogging) {
                requestBody
            } else {
                HttpDiagnosticSanitizer.sanitizeBody(
                    body = requestBody,
                    additionalSecrets = emptySet(),
                    redactDiscountCode = true,
                )
            },
            statusCode = response?.statusCode,
            responseHeaders = if (unsafeRawHttpLogging) {
                response?.headers.orEmpty()
            } else {
                buildMap {
                    response?.contentType?.let {
                        put("Content-Type", it.safeHeaderValue(requestSecrets))
                    }
                    exposedRequestId?.let { put("X-Request-ID", it) }
                }
            },
            responseBody = response?.body?.let { body ->
                if (unsafeRawHttpLogging) {
                    body
                } else {
                    HttpDiagnosticSanitizer.sanitizeBody(
                        body = body,
                        additionalSecrets = requestSecrets,
                    )
                }
            },
            requestId = exposedRequestId,
            durationMillis = durationMillis.coerceAtLeast(0L),
            failure = failure?.toPublicFailure(),
            containsSensitiveData = unsafeRawHttpLogging,
        )

        try {
            listenerExecutor.execute {
                if (closed.get()) return@execute
                listeners.forEach { listener ->
                    if (closed.get()) return@execute
                    try {
                        listener.onHttpTrace(trace)
                    } catch (_: Exception) {
                        // Application diagnostic listeners must not affect SDK calls.
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Closing the client may race with diagnostic submission.
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            listeners.clear()
            listenerExecutor.shutdownNow()
        }
    }

    private fun TransportFailureKind.toPublicFailure(): InappifyHttpFailure = when (this) {
        TransportFailureKind.NETWORK -> InappifyHttpFailure.NETWORK
        TransportFailureKind.TIMEOUT -> InappifyHttpFailure.TIMEOUT
        TransportFailureKind.CANCELLED -> InappifyHttpFailure.CANCELLED
        TransportFailureKind.MALFORMED_RESPONSE -> InappifyHttpFailure.MALFORMED_RESPONSE
    }

    private fun String.safeHeaderValue(secrets: Set<String>): String =
        secrets.fold(trim()) { value, secret ->
            value.scrubSecret(secret)
        }
            .replace(HEADER_CONTROL_CHARACTERS, " ")
            .take(MAX_HEADER_VALUE_LENGTH)

    private fun String.scrubSecret(secret: String): String = when {
        secret.isEmpty() -> this
        secret.length >= MIN_HEADER_SECRET_LENGTH -> replace(secret, "<redacted>")
        this == secret -> "<redacted>"
        else -> this
    }

    private fun String?.toSafeRequestId(): String? =
        this
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.takeIf { it.length <= MAX_REQUEST_ID_LENGTH }
            ?.takeIf { value ->
                value.all { character ->
                    character.isLetterOrDigit() ||
                        character == '-' ||
                        character == '_' ||
                        character == '.' ||
                        character == ':'
                }
            }

    private companion object {
        private val REQUEST_HEADERS = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        )
        private val HEADER_CONTROL_CHARACTERS = Regex("[\\r\\n\\t]+")
        private const val MAX_HEADER_VALUE_LENGTH = 128
        private const val MAX_REQUEST_ID_LENGTH = 128
        private const val MIN_HEADER_SECRET_LENGTH = 4
        private const val LISTENER_THREAD_NAME = "inappify-sdk-http-diagnostics"
    }
}

/** Bounded structural redaction used only for the opt-in diagnostic channel. */
internal object HttpDiagnosticSanitizer {

    private val gson = Gson()

    internal fun sanitizeEndpoint(url: HttpUrl?, unresolvedPath: String): String {
        if (url == null) return sanitizeUnresolvedPath(unresolvedPath)
        val segments = url.pathSegments.mapIndexed { index, segment ->
            if (index > 0 && url.pathSegments[index - 1].isSensitivePathParent()) {
                REDACTED_PATH_SEGMENT
            } else {
                segment.take(MAX_PATH_SEGMENT_LENGTH)
            }
        }
        val port = if (
            (url.scheme == "https" && url.port == 443) ||
            (url.scheme == "http" && url.port == 80)
        ) {
            ""
        } else {
            ":${url.port}"
        }
        return buildString {
            append(url.scheme)
            append("://")
            append(url.host)
            append(port)
            append('/')
            append(segments.joinToString("/"))
        }.take(MAX_ENDPOINT_LENGTH)
    }

    internal fun collectSensitiveValues(body: String): Set<String> {
        if (body.length > MAX_INPUT_BODY_LENGTH) return emptySet()
        val root = parse(body) ?: return emptySet()
        val values = LinkedHashSet<String>()
        collectSensitiveValues(
            element = root,
            inheritedSensitive = false,
            depth = 0,
            values = values,
        )
        return values
    }

    internal fun sanitizeBody(
        body: String,
        additionalSecrets: Set<String>,
        redactDiscountCode: Boolean = false,
    ): String {
        if (body.isBlank()) return "<empty>"
        if (body.length > MAX_INPUT_BODY_LENGTH) {
            return "<body omitted: ${body.length} characters>"
        }
        val root = parse(body)
            ?: return "<non-JSON body omitted: ${body.length} characters>"
        val budget = SanitizationBudget()
        val sanitized = sanitizeElement(
            element = root,
            fieldName = null,
            depth = 0,
            budget = budget,
            additionalSecrets = additionalSecrets,
            redactDiscountCode = redactDiscountCode,
        )
        return gson.toJson(sanitized).boundedBody()
    }

    private fun parse(body: String): JsonElement? = try {
        JsonParser.parseString(body)
    } catch (_: RuntimeException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    private fun sanitizeElement(
        element: JsonElement,
        fieldName: String?,
        depth: Int,
        budget: SanitizationBudget,
        additionalSecrets: Set<String>,
        redactDiscountCode: Boolean,
    ): JsonElement {
        if (
            fieldName?.isSensitiveField() == true ||
            (redactDiscountCode && fieldName?.normalizedFieldName() == "code")
        ) {
            return JsonPrimitive(REDACTED_VALUE)
        }
        if (depth > MAX_JSON_DEPTH || !budget.consume()) {
            return JsonPrimitive(OMITTED_VALUE)
        }
        if (element.isJsonNull) return JsonNull.INSTANCE
        if (element.isJsonObject) {
            val source = element.asJsonObject
            val reservedAttributeValue = source.get("key")
                ?.takeIf(JsonElement::isJsonPrimitive)
                ?.asString
                ?.startsWith('$') == true
            val result = JsonObject()
            source.entrySet().take(MAX_OBJECT_MEMBERS).forEach { (name, value) ->
                val redactValue = reservedAttributeValue && name.equals("value", true)
                result.add(
                    name.take(MAX_JSON_KEY_LENGTH),
                    if (redactValue) {
                        JsonPrimitive(REDACTED_VALUE)
                    } else {
                        sanitizeElement(
                            element = value,
                            fieldName = name,
                            depth = depth + 1,
                            budget = budget,
                            additionalSecrets = additionalSecrets,
                            redactDiscountCode = redactDiscountCode,
                        )
                    },
                )
            }
            if (source.size() > MAX_OBJECT_MEMBERS) {
                result.addProperty("_diagnostic", OMITTED_VALUE)
            }
            return result
        }
        if (element.isJsonArray) {
            val source = element.asJsonArray
            val result = JsonArray()
            source.take(MAX_ARRAY_ELEMENTS).forEach { value ->
                result.add(
                    sanitizeElement(
                        element = value,
                        fieldName = fieldName,
                        depth = depth + 1,
                        budget = budget,
                        additionalSecrets = additionalSecrets,
                        redactDiscountCode = redactDiscountCode,
                    ),
                )
            }
            if (source.size() > MAX_ARRAY_ELEMENTS) result.add(OMITTED_VALUE)
            return result
        }

        val primitive = element.asJsonPrimitive
        if (!primitive.isString) return primitive.deepCopy()
        var value = primitive.asString
        additionalSecrets.forEach { secret ->
            value = value.scrubSecret(secret)
        }
        value = value
            .replace(EMAIL_PATTERN, REDACTED_VALUE)
            .replace(BEARER_PATTERN, REDACTED_VALUE)
            .replace(JWT_PATTERN, REDACTED_VALUE)
        if (value.length > MAX_STRING_VALUE_LENGTH) {
            value = value.take(MAX_STRING_VALUE_LENGTH) + OMITTED_VALUE
        }
        return JsonPrimitive(value)
    }

    private fun collectSensitiveValues(
        element: JsonElement,
        inheritedSensitive: Boolean,
        depth: Int,
        values: MutableSet<String>,
    ) {
        if (depth > MAX_JSON_DEPTH || values.size >= MAX_COLLECTED_SECRETS) return
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (name, value) ->
                collectSensitiveValues(
                    element = value,
                    inheritedSensitive = inheritedSensitive ||
                        name.isSensitiveField() ||
                        name.normalizedFieldName() == "code",
                    depth = depth + 1,
                    values = values,
                )
            }

            element.isJsonArray -> element.asJsonArray.forEach { value ->
                collectSensitiveValues(value, inheritedSensitive, depth + 1, values)
            }

            inheritedSensitive && element.isJsonPrimitive && element.asJsonPrimitive.isString -> {
                element.asString
                    .takeIf { it.length in 1..MAX_COLLECTED_SECRET_LENGTH }
                    ?.let(values::add)
            }
        }
    }

    private fun String.isSensitiveField(): Boolean {
        val normalized = normalizedFieldName()
        return normalized in SENSITIVE_FIELD_NAMES ||
            normalized.endsWith("token") ||
            normalized.contains("password") ||
            normalized.contains("secret") ||
            normalized.contains("signature") ||
            normalized.contains("receipt") ||
            normalized.contains("authorization") ||
            normalized.contains("cookie")
    }

    private fun String.normalizedFieldName(): String =
        lowercase(Locale.US).filter(Char::isLetterOrDigit)

    private fun String.isSensitivePathParent(): Boolean =
        lowercase(Locale.US) in SENSITIVE_PATH_PARENTS

    private fun String.scrubSecret(secret: String): String = when {
        secret.length >= MIN_SECRET_LENGTH -> replace(secret, REDACTED_VALUE)
        secret.isEmpty() -> this
        this == secret -> REDACTED_VALUE
        else -> replace(
            Regex(
                "(?<![A-Za-z0-9])${Regex.escape(secret)}(?![A-Za-z0-9])",
            ),
            REDACTED_VALUE,
        )
    }

    private fun sanitizeUnresolvedPath(path: String): String {
        val withoutQuery = path.substringBefore('?').substringBefore('#')
        val segments = withoutQuery.split('/').mapIndexed { index, segment ->
            if (index > 0 && withoutQuery.split('/')[index - 1].isSensitivePathParent()) {
                REDACTED_PATH_SEGMENT
            } else {
                segment.take(MAX_PATH_SEGMENT_LENGTH)
            }
        }
        return segments.joinToString("/").take(MAX_ENDPOINT_LENGTH)
    }

    private fun String.boundedBody(): String =
        if (length <= MAX_OUTPUT_BODY_LENGTH) {
            this
        } else {
            take(MAX_OUTPUT_BODY_LENGTH) +
                "<diagnostic truncated; originalChars=$length>"
        }

    private class SanitizationBudget {
        private var remaining = MAX_JSON_NODES

        internal fun consume(): Boolean {
            if (remaining == 0) return false
            remaining -= 1
            return true
        }
    }

    private val SENSITIVE_FIELD_NAMES = setOf(
        "apikey",
        "accesstoken",
        "refreshtoken",
        "sessiontoken",
        "customertoken",
        "jws",
        "appuserid",
        "customerinfo",
        "customer_info",
        "token",
        "marketkey",
        "appuseridentifier",
        "originalappuserid",
        "customerid",
        "customeridentifier",
        "userid",
        "email",
        "phonenumber",
        "ip",
        "idfa",
        "idfv",
        "fcmtokens",
        "apnstokens",
        "attributes",
        "purchasetokenid",
        "purchasetoken",
        "developerpayload",
        "originaljson",
        "orderid",
        "verificationrequestid",
        "deliveryid",
        "paymentid",
        "transactionid",
        "checkoutid",
        "url",
        "redirecturl",
    )
    private val SENSITIVE_PATH_PARENTS = setOf(
        "verifications",
        "deliveries",
        "checkouts",
        "payments",
        "transactions",
    )
    private val EMAIL_PATTERN = Regex(
        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
        RegexOption.IGNORE_CASE,
    )
    private val BEARER_PATTERN = Regex(
        "(?i)bearer\\s+[a-z0-9._~+/=-]{8,}",
    )
    private val JWT_PATTERN = Regex(
        "eyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}(?:\\.[a-zA-Z0-9_-]{8,})?",
    )
    private const val REDACTED_VALUE = "<redacted>"
    private const val REDACTED_PATH_SEGMENT = "{redacted}"
    private const val OMITTED_VALUE = "<omitted>"
    private const val MIN_SECRET_LENGTH = 4
    private const val MAX_COLLECTED_SECRETS = 64
    private const val MAX_COLLECTED_SECRET_LENGTH = 8_192
    private const val MAX_INPUT_BODY_LENGTH = 1024 * 1024
    private const val MAX_OUTPUT_BODY_LENGTH = 12_000
    private const val MAX_JSON_DEPTH = 24
    private const val MAX_JSON_NODES = 1_024
    private const val MAX_OBJECT_MEMBERS = 128
    private const val MAX_ARRAY_ELEMENTS = 128
    private const val MAX_STRING_VALUE_LENGTH = 1_024
    private const val MAX_JSON_KEY_LENGTH = 128
    private const val MAX_PATH_SEGMENT_LENGTH = 128
    private const val MAX_ENDPOINT_LENGTH = 1_024
}
