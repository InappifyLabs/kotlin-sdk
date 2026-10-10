package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.storage.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

internal class SigningFixture {
    val now = 1788854400000L
    val subject = "customer_demo_123456"
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val config = InappifyV2Configuration("https://sdk.example.com/app/v2/", "https://sdk.example.com", 12, 34,
        mapOf("test-key" to encode(pair.public.encoded.takeLast(32).toByteArray())), setOf("pay.inappify.com"), setOf("cdn.inappify.com"))
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun envelope(identity: String = subject, mutateHeader: (JsonObject) -> Unit = {},
        mutatePayload: (JsonObject) -> Unit = {}): JsonObject {
        val info = jsonObject("""{"originalAppUserId":"$identity","firstSeen":"2026-09-08T00:00:00Z","requestDate":"2026-09-08T00:00:00Z","hasUsedTrial":false,"entitlements":[{"identifier":"pro","is_active":true,"entitlement_type":"NonConsumable","expiration_date":null}]}""")
        val header = jsonObject("""{"alg":"EdDSA","typ":"INA-CUSTOMER-INFO","kid":"test-key"}""").apply(mutateHeader)
        val payload = JsonObject().apply {
            addProperty("iss", config.issuer); addProperty("aud", "inappify-sdk"); addProperty("ver", 2)
            addProperty("app_id", 12); addProperty("project_id", 34); addProperty("sub", identity)
            addProperty("exp", now / 1000 + 300); add("customer_info", info)
        }.apply(mutatePayload)
        val input = "${encode(header.toString().toByteArray())}.${encode(payload.toString().toByteArray())}"
        val signature = Signature.getInstance("Ed25519").run { initSign(pair.private); update(input.toByteArray()); sign() }
        return JsonObject().apply {
            addProperty("status", true); addProperty("sessionType", "public"); addProperty("sessionToken", "go-session-token")
            addProperty("sessionExpiresAt", "2026-09-09T00:00:00Z"); addProperty("appUserId", identity)
            addProperty("appId", 12); addProperty("storePlatform", 2)
            add("customerInfo", info.deepCopy())
            add("verification", JsonObject().apply {
                addProperty("algorithm", "EdDSA"); addProperty("keyId", header.get("kid").asString)
                addProperty("jws", "$input.${encode(signature)}"); addProperty("expiresAt", "2026-09-08T08:05:00Z")
            })
        }
    }
}

internal class MemoryV2Store : SessionStateStore {
    var value: PersistedSession? = null
    var failSave = false
    override suspend fun load(): PersistedSession? = value
    override suspend fun save(session: PersistedSession): Boolean { if (failSave) return false; value = session; return true }
    override suspend fun clear(): Boolean { value = null; return true }
}
internal class V2Transport : HttpTransport {
    val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    var handler: suspend (HttpRequest) -> TransportResult = { error("No handler") }
    override suspend fun execute(request: HttpRequest): TransportResult { requests += request; return handler(request) }
    override fun close() = Unit
    fun response(body: JsonObject, status: Int = 200, headers: Map<String, String> = emptyMap()): TransportResult =
        TransportResult.Response(HttpResponse(status, body.toString(), null, headers = headers))
}

internal class CommerceCatalogFixture {
    val offerings = """{"offerings":[{"identifier":"default","isDefault":true,"packages":[{"identifier":"package","product":{"identifier":"product"}}]}]}"""
}
