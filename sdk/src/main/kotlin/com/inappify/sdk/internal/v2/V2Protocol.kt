package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.internal.bind.util.ISO8601Utils
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import java.io.StringReader
import java.text.ParsePosition
import kotlin.random.Random
import kotlinx.coroutines.delay
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal class V2Failure(val sdkError: InappifyError) : Exception(sdkError.message)

internal fun fail(category: String, code: String, retryable: Boolean = false,
    httpStatus: Int? = null, diagnostics: Map<String, Any?> = emptyMap()): Nothing = throw V2Failure(InappifyError(
    code = when (category) {
        "CONFIGURATION", "VALIDATION" -> InappifyErrorCode.INVALID_CONFIGURATION
        "AUTH" -> InappifyErrorCode.UNAUTHORIZED
        "NETWORK" -> InappifyErrorCode.NETWORK
        "TIMEOUT" -> InappifyErrorCode.TIMEOUT
        "SIGNATURE", "DECODING" -> InappifyErrorCode.MALFORMED_RESPONSE
        else -> InappifyErrorCode.UNKNOWN
    }, message = "Inappify v2 $category failure ($code).", isRetryable = retryable,
    details = diagnostics + mapOf("category" to category, "serverCode" to code, "httpStatus" to httpStatus),
))

/** Report the failing comparison, never raw customer IDs, tokens or arbitrary signed strings. */
internal fun verificationMismatch(field: String, rule: String, actual: JsonElement?,
    expected: JsonElement? = null, source: String = "contract", code: String = "SCOPE_MISMATCH",
    signatureVerified: Boolean = true): Nothing {
    val details = mutableMapOf<String, Any?>("mismatchField" to field, "validationRule" to rule,
        "expectedSource" to source, "signatureVerified" to signatureVerified)
    fun describe(prefix: String, value: JsonElement?) {
        details["${prefix}Type"] = when {
            value == null -> "missing"
            value.isJsonNull -> "null"
            value.isJsonObject -> "object"
            value.isJsonArray -> "array"
            value.asJsonPrimitive.isString -> "string"
            value.asJsonPrimitive.isNumber -> "number"
            else -> "boolean"
        }
        if (value?.isJsonPrimitive == true) {
            if (value.asJsonPrimitive.isString) details["${prefix}Length"] = value.asString.length
            // These numeric fields describe protocol/app scope, never customer identity.
            if (field in setOf("appId", "app_id", "project_id", "ver") &&
                value.asJsonPrimitive.isNumber && value.asString.length <= 24)
                details["${prefix}Value"] = value.asString
        }
    }
    describe("actual", actual)
    if (expected != null) describe("expected", expected)
    fail("SIGNATURE", code, diagnostics = details)
}

/** This wrapper is used only after an HTTP-success envelope, never for cache verification. */
internal suspend fun <T> verifiedResponse(operation: String, mayHaveCommitted: Boolean = false,
    action: suspend () -> T): T = try { action() } catch (e: V2Failure) {
    if (e.sdkError.details["category"] != "SIGNATURE") throw e
    throw V2Failure(InappifyError(e.sdkError.code, e.sdkError.message, e.sdkError.isRetryable,
        e.sdkError.details + mapOf("operation" to operation, "httpStatus" to 200,
            "outcomeMayHaveCommitted" to mayHaveCommitted)))
}

/** Strict bounded JSON also rejects duplicate security-critical members. */
internal fun jsonObject(raw: String): JsonObject {
    if (raw.toByteArray(Charsets.UTF_8).size > 1024 * 1024) fail("DECODING", "BODY_TOO_LARGE")
    try {
        val reader = JsonReader(StringReader(raw)).apply { strictness = Strictness.STRICT }
        var nodes = 0
        fun value(depth: Int): JsonElement {
            require(depth <= 32 && ++nodes <= 50000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName(); require(!has(name)); add(name, value(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                    reader.beginArray()
                    while (reader.hasNext()) add(value(depth + 1))
                    reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonPrimitive(reader.nextString().toBigDecimal())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("Invalid JSON")
            }
        }
        val result = value(0).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return result
    } catch (e: V2Failure) { throw e }
    catch (_: Exception) { fail("DECODING", "INVALID_JSON") }
}

internal fun JsonObject.string(name: String): String = get(name)?.let {
    if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else null
} ?: fail("DECODING", "INVALID_FIELD")
internal fun JsonObject.number(name: String): Long = try {
    val v = get(name); require(v.isJsonPrimitive && v.asJsonPrimitive.isNumber)
    v.asBigDecimal.longValueExact()
} catch (_: Exception) { fail("DECODING", "INVALID_FIELD") }
internal fun JsonObject.flag(name: String): Boolean = get(name)?.let {
    if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else null
} ?: fail("DECODING", "INVALID_FIELD")
internal fun isoMillis(value: String): Long = try {
    val pos = ParsePosition(0)
    val result = ISO8601Utils.parse(value, pos).time
    require(pos.index == value.length && value.contains('T'))
    result
} catch (_: Exception) { fail("DECODING", "INVALID_DATE") }

internal fun base64Url(value: String): ByteArray {
    if (!value.matches(Regex("[A-Za-z0-9_-]+"))) fail("SIGNATURE", "INVALID_BASE64URL")
    val decoded = value.decodeBase64() ?: fail("SIGNATURE", "INVALID_BASE64URL")
    if (decoded.base64Url().trimEnd('=') != value) fail("SIGNATURE", "INVALID_BASE64URL")
    return decoded.toByteArray()
}

internal class CustomerInfoVerifier(
    private val config: GoV2Environment,
    private val now: () -> Long,
) {
    constructor(config: InappifyV2Configuration, now: () -> Long) : this(GoV2Environment.explicit(config), now)
    private val keys = config.pinnedSigningKeys.mapValues { (_, value) -> base64Url(value).also {
        require(it.size == 32) { "Ed25519 keys must contain exactly 32 bytes." }
    } }.toMutableMap()

    fun known(kid: String): Boolean = keys.containsKey(kid)

    /** Network keys must match a configured trust anchor; downloads cannot establish trust. */
    fun refresh(jwks: JsonObject) {
        val candidates = jwks.getAsJsonArray("keys") ?: fail("SIGNATURE", "INVALID_JWKS")
        require(candidates.size() <= 100)
        val additions = mutableMapOf<String, ByteArray>()
        candidates.forEach { entry ->
            val key = entry.asJsonObject
            if (key.string("kty") == "OKP" && key.string("crv") == "Ed25519" &&
                key.string("alg") == "EdDSA" && key.string("use") == "sig") {
                val bytes = base64Url(key.string("x"))
                if (keys.values.any { it.contentEquals(bytes) }) {
                    val kid = key.string("kid")
                    if (!keys.containsKey(kid)) additions[kid] = bytes
                }
            }
        }
        keys.putAll(additions)
    }

    fun verify(envelope: JsonObject, appUserId: String, allowExpiredCache: Boolean = false): JsonObject =
        verifyWithScope(envelope, appUserId, config.expectedScope, allowExpiredCache).info

    fun verifyWithScope(envelope: JsonObject, appUserId: String,
        expectedScope: InappifyV2SessionScope? = config.expectedScope,
        allowExpiredCache: Boolean = false, expectedSubject: String? = null): VerifiedCustomerInfo {
        try {
            val verification = envelope.getAsJsonObject("verification")
                ?: fail("SIGNATURE", "MISSING_VERIFICATION")
            val parts = verification.string("jws").split('.')
            if (parts.size != 3) fail("SIGNATURE", "INVALID_JWS")
            val header = jsonObject(base64Url(parts[0]).toString(Charsets.UTF_8))
            if (header.string("alg") != "EdDSA" || header.string("typ") != "INA-CUSTOMER-INFO" ||
                header.has("crit") || header.has("b64")) fail("SIGNATURE", "INVALID_HEADER")
            val kid = header.string("kid")
            if (verification.string("algorithm") != "EdDSA" || verification.string("keyId") != kid)
                fail("SIGNATURE", "VERIFICATION_MISMATCH")
            val key = keys[kid] ?: fail("SIGNATURE", "UNKNOWN_KEY")
            val signature = base64Url(parts[2])
            val bytes = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(key, 0))
            verifier.update(bytes, 0, bytes.size)
            if (signature.size != 64 || !verifier.verifySignature(signature)) fail("SIGNATURE", "INVALID_SIGNATURE")
            val payload = jsonObject(base64Url(parts[1]).toString(Charsets.UTF_8))
            // The production key is bound to the official service. First Configure learns scope
            // from these authenticated claims, not from unsigned JSON, token decoding or UI input.
            fun claimString(field: String): String = payload.get(field)?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }?.asString ?: verificationMismatch(field, "string required", payload.get(field))
            fun claimNumber(field: String): Long = try { payload.number(field) }
                catch (_: V2Failure) { verificationMismatch(field, "integer required", payload.get(field)) }
            val scope = InappifyV2SessionScope(claimString("iss"), claimNumber("app_id"), claimNumber("project_id"))
            if (scope.issuer.isBlank() || scope.issuer.length > 2048 || scope.issuer.any(Char::isISOControl))
                verificationMismatch("iss", "nonblank issuer without control characters; max 2048", payload.get("iss"))
            for ((field, value) in listOf("app_id" to scope.appId, "project_id" to scope.projectId))
                if (value <= 0) verificationMismatch(field, "positive integer required", payload.get(field))
            for ((source, expected) in listOf("verifiedSessionScope" to expectedScope, "explicitConfiguration" to config.expectedScope)) {
                if (expected == null) continue
                if (scope.issuer != expected.issuer)
                    verificationMismatch("iss", "exact scope match", payload.get("iss"), JsonPrimitive(expected.issuer), source)
                if (scope.appId != expected.appId)
                    verificationMismatch("app_id", "exact scope match", payload.get("app_id"), JsonPrimitive(expected.appId), source)
                if (scope.projectId != expected.projectId)
                    verificationMismatch("project_id", "exact scope match", payload.get("project_id"), JsonPrimitive(expected.projectId), source)
            }
            if (claimString("aud") != "inappify-sdk")
                verificationMismatch("aud", "must equal inappify-sdk", payload.get("aud"))
            if (claimNumber("ver") != 2L)
                verificationMismatch("ver", "must equal 2", payload.get("ver"), JsonPrimitive(2))
            val internalSubject = claimString("sub")
            if (internalSubject.isBlank() || internalSubject.length > 512 || internalSubject.any(Char::isISOControl))
                verificationMismatch("sub", "nonblank subject without control characters; max 512", payload.get("sub"))
            if (expectedSubject != null && internalSubject != expectedSubject)
                verificationMismatch("sub", "exact internal customer match", payload.get("sub"), JsonPrimitive(expectedSubject), "verifiedSession")
            val exp = payload.number("exp")
            if (exp <= 0 || exp > Long.MAX_VALUE / 1000) fail("SIGNATURE", "INVALID_EXPIRY")
            if (isoMillis(verification.string("expiresAt")) / 1000 != exp) fail("SIGNATURE", "EXPIRY_MISMATCH")
            if (!allowExpiredCache && now() / 1000 >= exp) fail("SIGNATURE", "EXPIRED_SIGNATURE")
            if (payload.has("nbf") && payload.number("nbf") > now() / 1000) fail("SIGNATURE", "NOT_YET_VALID")
            val info = payload.getAsJsonObject("customer_info") ?: fail("SIGNATURE", "MISSING_CUSTOMER_INFO")
            if (info.string("originalAppUserId") != appUserId)
                verificationMismatch("customer_info.originalAppUserId", "exact public app-user match",
                    info.get("originalAppUserId"), JsonPrimitive(appUserId), "requestedAppUserId", "CUSTOMER_MISMATCH")
            if (isoMillis(info.string("requestDate")) >= exp * 1000) fail("SIGNATURE", "INVALID_REQUEST_DATE")
            if (info != envelope.get("customerInfo")) fail("SIGNATURE", "CUSTOMER_INFO_MISMATCH")
            val verifiedInfo = info.deepCopy().apply { remove("attributes") }
            // The V1 DTO helpers tolerate legacy date strings; never expose such dates as verified v2 data.
            InappifyDomainJsonCodec.parseCustomerInfo(verifiedInfo.toString()).entitlements.orEmpty().forEach { entitlement ->
                entitlement.expirationDate?.takeIf { it.isNotEmpty() }?.let(::isoMillis)
            }
            return VerifiedCustomerInfo(verifiedInfo, scope, internalSubject)
        } catch (e: V2Failure) { throw e }
        catch (_: Exception) { fail("SIGNATURE", "INVALID_VERIFICATION") }
    }
}

internal data class GoApiResponse(val body: JsonObject, val retryAfterSeconds: Long?)

internal class GoApi(
    private val transport: HttpTransport,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun addTraceListener(listener: InappifyHttpTraceListener): InappifyListenerRegistration =
        (transport as? HttpDiagnosticSource)?.addHttpTraceListener(listener)
            ?: InappifyListenerRegistration.create(Runnable {})
    @Volatile var enabled = true
    suspend fun request(endpoint: String, token: String?, body: JsonObject = JsonObject(),
        retry: Boolean = true, headers: Map<String, String> = emptyMap()): JsonObject {
        return requestWithMetadata(endpoint, token, body, retry, headers).body
    }
    suspend fun requestWithMetadata(endpoint: String, token: String?, body: JsonObject = JsonObject(),
        retry: Boolean = true, headers: Map<String, String> = emptyMap()): GoApiResponse {
        if (!enabled) fail("CONFIGURATION", "V2_DISABLED")
        val raw = body.toString()
        if (raw.toByteArray().size > 1024 * 1024) fail("VALIDATION", "BODY_TOO_LARGE")
        repeat(if (retry) 4 else 1) { attempt ->
            if (!enabled) fail("CONFIGURATION", "V2_DISABLED")
            val response = transport.execute(HttpRequest(endpoint, raw,
                method = if (endpoint == "public-keys") "GET" else "POST",
                headers = buildMap {
                    put("Accept", "application/json")
                    put("Content-Type", "application/json")
                    put("User-Agent", "InAppify-Android/${BuildConfig.SDK_VERSION}")
                    token?.let { put("Authorization", "Bearer $it") }
                    putAll(headers)
                }))
            if (response is TransportResult.Failure) {
                val category = when (response.kind) {
                    TransportFailureKind.TIMEOUT -> "TIMEOUT"
                    TransportFailureKind.NETWORK -> "NETWORK"
                    else -> "DECODING"
                }
                if (category in setOf("NETWORK", "TIMEOUT") && retry && attempt < 3) {
                    sleep(retryDelay(attempt, emptyMap())); return@repeat
                }
                fail(category, response.kind.name, category != "DECODING")
            }
            val http = (response as TransportResult.Response).response
            if (http.statusCode == 204 && endpoint == "attributes") return GoApiResponse(JsonObject(), null)
            if (http.statusCode in setOf(429, 500, 502, 503, 504) && retry && attempt < 3) {
                sleep(retryDelay(attempt, http.headers)); return@repeat
            }
            val json = try { jsonObject(http.body ?: "") } catch (_: V2Failure) {
                if (http.statusCode == 200) fail("DECODING", "INVALID_JSON", httpStatus = 200)
                JsonObject()
            }
            if (http.statusCode != 200 || json.get("status") == JsonPrimitive(false)) {
                val numericCode = json.get("errorCode")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asString?.toLongOrNull()?.takeIf { it >= 0 }
                val code = json.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                    ?: numericCode?.let { "ERROR_CODE_$it" } ?: "HTTP_${http.statusCode}"
                val category = when {
                    code.startsWith("PUBLIC_API_KEY") || code == "APP_IDENTIFIER_INVALID" -> "CONFIGURATION"
                    http.statusCode == 401 || http.statusCode == 403 -> "AUTH"
                    http.statusCode in setOf(400, 422) -> "VALIDATION"
                    http.statusCode == 429 -> "RATE_LIMIT"
                    else -> "SERVER"
                }
                fail(category, code, http.statusCode in setOf(429, 500, 502, 503, 504),
                    http.statusCode, buildMap {
                        numericCode?.let { put("errorCode", it) }
                        retryAfterSeconds(http.headers)?.let { put("retryAfterSeconds", it) }
                    })
            }
            if (endpoint != "public-keys" && !json.flag("status")) fail("DECODING", "INVALID_STATUS")
            return GoApiResponse(json, retryAfterSeconds(http.headers))
        }
        error("Unreachable")
    }
    private fun retryAfterSeconds(headers: Map<String, String>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim() ?: return null
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
        return runCatching {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(value)
                ?: return null
            val remaining = (date.time - now()).coerceAtLeast(0)
            remaining / 1000 + if (remaining % 1000 == 0L) 0 else 1
        }.getOrNull()
    }
    private fun retryDelay(attempt: Int, headers: Map<String, String>): Long {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        val retryAfter = header("Retry-After")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 0 }?.let { it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
                ?: runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .parse(raw)!!.time - now() }.getOrNull()
        }
        val rateReset = header("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / 1000 }
            ?.let { it * 1000 - now() }
        return maxOf((2000L shl attempt).coerceAtMost(30000), retryAfter ?: 0, rateReset ?: 0)
            .coerceAtMost(Long.MAX_VALUE - 250) + Random.nextLong(251)
    }
    fun close() = transport.close()
}
