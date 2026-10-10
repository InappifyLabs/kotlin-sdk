package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class V2ProtocolTest {
    private val fixture = SigningFixture()
    private fun verifier() = CustomerInfoVerifier(fixture.config) { fixture.now }
    private fun rejected(envelope: JsonObject) {
        try { verifier().verify(envelope, fixture.subject); fail("Accepted invalid signed data") }
        catch (expected: V2Failure) { assertNotNull(expected.sdkError.details["category"]) }
    }
    @Test fun verifiesJdkGeneratedEd25519Signature() {
        assertEquals(fixture.subject, verifier().verify(fixture.envelope(), fixture.subject).string("originalAppUserId"))
    }
    @Test fun rejectsAlgorithmAndTypeConfusion() {
        rejected(fixture.envelope(mutateHeader = { it.addProperty("alg", "none") }))
        rejected(fixture.envelope(mutateHeader = { it.addProperty("typ", "JWT") }))
        rejected(fixture.envelope(mutateHeader = { it.add("crit", JsonArray()) }))
    }
    @Test fun rejectsEveryCrossTenantAndIdentityClaim() {
        for ((field, value) in listOf("iss" to "other", "aud" to "other"))
            rejected(fixture.envelope(mutatePayload = { it.addProperty(field, value) }))
        rejected(fixture.envelope(mutatePayload = {
            it.getAsJsonObject("customer_info").addProperty("originalAppUserId", "another-public-customer")
        }))
        val boundSubjectFailure = assertThrows(V2Failure::class.java) {
            verifier().verifyWithScope(fixture.envelope(mutatePayload = { it.addProperty("sub", "other") }),
                fixture.subject, expectedSubject = fixture.subject)
        }
        assertEquals("sub", boundSubjectFailure.sdkError.details["mismatchField"])
        for (field in listOf("app_id", "project_id", "ver"))
            rejected(fixture.envelope(mutatePayload = { it.addProperty(field, 999) }))
    }
    @Test fun rejectsExpiredAndFutureClaims() {
        rejected(fixture.envelope(mutatePayload = { it.addProperty("exp", fixture.now / 1000 - 1) }))
        rejected(fixture.envelope(mutatePayload = { it.addProperty("nbf", fixture.now / 1000 + 600) }))
    }
    @Test fun rejectsUnsignedTwinTampering() {
        rejected(fixture.envelope().apply { getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true) })
    }
    @Test fun malformedEntitlementDateCannotReachLegacyTolerantDtoHelpers() {
        rejected(fixture.envelope(mutatePayload = {
            it.getAsJsonObject("customer_info").getAsJsonArray("entitlements")[0].asJsonObject
                .addProperty("expiration_date", "not-a-date")
        }))
    }
    @Test fun rejectsSignatureTamperingAndUnknownKey() {
        rejected(fixture.envelope().apply { getAsJsonObject("verification").addProperty("jws",
            getAsJsonObject("verification").string("jws").dropLast(10) + "AAAAAAAAAA") })
        rejected(fixture.envelope(mutateHeader = { it.addProperty("kid", "unknown") }))
    }
    @Test fun downloadedUntrustedKeysCannotReplacePins() {
        val verifier = verifier()
        val alien = SigningFixture()
        verifier.refresh(jsonObject("""{"keys":[{"kty":"OKP","crv":"Ed25519","alg":"EdDSA","use":"sig","kid":"alien","x":"${alien.config.pinnedSigningKeys.values.first()}"}]}"""))
        assertFalse(verifier.known("alien")); assertTrue(verifier.known("test-key"))
    }
    @Test fun rotationAliasMustMatchAPinnedPublicKey() {
        val verifier = verifier()
        verifier.refresh(jsonObject("""{"keys":[{"kty":"OKP","crv":"Ed25519","alg":"EdDSA","use":"sig","kid":"rotation","x":"${fixture.config.pinnedSigningKeys.values.first()}"}]}"""))
        assertTrue(verifier.known("rotation"))
        verifier.verify(fixture.envelope(mutateHeader = { it.addProperty("kid", "rotation") }), fixture.subject)
    }
    @Test fun strictJsonRejectsDuplicatesTrailingDataAndDepthBombs() {
        for (raw in listOf("""{"status":true,"status":false}""", "{}{}", "{\"x\":".repeat(40) + "null" + "}".repeat(40))) {
            try { jsonObject(raw); fail("Accepted malformed JSON") } catch (_: V2Failure) { }
        }
    }
    @Test fun requestUsesBearerAndUserAgentAndNoLegacyJsonSecrets() = runBlocking {
        val transport = V2Transport()
        transport.handler = { transport.response(jsonObject("""{"status":true}""")) }
        GoApi(transport, { fixture.now }, {}).request("configure", "public-key")
        val request = transport.requests.single()
        assertEquals("Bearer public-key", request.headers["Authorization"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("InAppify-Android/"))
        assertEquals("{}", request.jsonBody)
    }
    @Test fun attributes204HasNoJsonRequirementAndKeysAreUnauthenticatedGet() = runBlocking {
        val transport = V2Transport()
        transport.handler = { if (it.path == "attributes") TransportResult.Response(HttpResponse(204, null, null))
            else transport.response(jsonObject("""{"keys":[]}""")) }
        val api = GoApi(transport, { fixture.now }, {})
        api.request("attributes", "session")
        api.request("public-keys", null)
        assertEquals("GET", transport.requests.last().method)
        assertNull(transport.requests.last().headers["Authorization"])
    }
    @Test fun retryAfterIsRespectedAndAttemptsAreBounded() = runBlocking {
        val transport = V2Transport()
        val delays = mutableListOf<Long>()
        transport.handler = { transport.response(JsonObject(), 503, mapOf("Retry-After" to "120")) }
        try { GoApi(transport, { fixture.now }, { delays += it }).request("offerings", "session"); fail() }
        catch (e: V2Failure) { assertTrue(e.sdkError.isRetryable) }
        assertEquals(4, transport.requests.size); assertEquals(3, delays.size)
        assertTrue(delays.all { it >= 120000 })
    }
    @Test fun allValidationEnvelopesFailWithoutRetry() = runBlocking {
        for (body in listOf("""{"status":false,"code":"ATTRIBUTE_KEY_INVALID","message":"private value"}""",
            """{"error":"secret","details":"Invalid JSON"}""", """{"message":"Validation failed","errors":{"key":"secret"}}""")) {
            val transport = V2Transport().apply { handler = { response(jsonObject(body), 422) } }
            try { GoApi(transport, { fixture.now }, {}).request("attributes", "session"); fail() }
            catch (e: V2Failure) { assertFalse(e.sdkError.isRetryable); assertFalse(e.sdkError.toString().contains("secret")) }
            assertEquals(1, transport.requests.size)
        }
    }
    @Test fun longRetryAfterAndRateResetNeverRetryEarlierThanServerDeadline() = runBlocking {
        for (headers in listOf(mapOf("Retry-After" to "172800"),
            mapOf("X-RateLimit-Reset" to (fixture.now / 1000 + 172800).toString()))) {
            val transport = V2Transport().apply { handler = { response(JsonObject(), 429, headers) } }
            val delays = mutableListOf<Long>()
            try { GoApi(transport, { fixture.now }, { delays += it }).request("offerings", "session"); fail() }
            catch (_: V2Failure) { }
            assertEquals(3, delays.size)
            assertTrue(delays.all { it >= 172800000L })
        }
    }
    @Test fun anonymousAndCustomIdValidation() {
        repeat(50) { assertTrue(validIdentity(anonymousId())) }
        assertTrue(validIdentity(fixture.subject))
        assertTrue(validIdentity("1234567890123456-"))
        for (value in listOf("0", "1234", "guest", "short", "1234567890123456", "09123456789",
            "+989123456789", "user@example.com", "hello world 123456",
            "customer/123456789", "کاربر-۱۲۳")) assertTrue(value, validIdentity(value))
        for (value in listOf("", "  ", " short", "short ", "bad\nidentity", "\uD800",
            "\$INAAnonymousID:client-controlled",
            "a".repeat(101))) assertFalse(value, validIdentity(value))
        assertTrue(validCountry(" tr "))
        assertEquals("TR", normalizeCountry(" tr "))
        assertFalse(validCountry("IRN"))
    }
    @Test fun gatewayFailuresAreRetryableWithBoundedBackoff() = runBlocking {
        for (status in listOf(502, 504)) {
            val transport = V2Transport().apply { handler = { response(JsonObject(), status) } }
            val delays = mutableListOf<Long>()
            val error = assertThrows(V2Failure::class.java) {
                runBlocking { GoApi(transport, { fixture.now }, { delays += it }).request("offerings", "session") }
            }
            assertTrue(error.sdkError.isRetryable)
            assertEquals(status, error.sdkError.details["httpStatus"])
            assertEquals(4, transport.requests.size)
            assertEquals(3, delays.size)
        }
    }
    @Test fun laravelNumericErrorCodeAndFalseStatusArePreserved() = runBlocking {
        val transport = V2Transport().apply {
            handler = { response(jsonObject("""{"status":false,"errorCode":107,"message":"private value"}"""), 200) }
        }
        val error = assertThrows(V2Failure::class.java) {
            runBlocking { GoApi(transport, { fixture.now }, {}).request("purchase", "session") }
        }
        assertEquals("ERROR_CODE_107", error.sdkError.details["serverCode"])
        assertEquals(107L, error.sdkError.details["errorCode"])
        assertFalse(error.sdkError.isRetryable)
        assertEquals(1, transport.requests.size)
        assertFalse(error.sdkError.toString().contains("private value"))
    }
    @Test fun unicodeAttributeLimitAndWriteOnlyReservedKeys() {
        assertTrue(validAttribute("campaign", "😀".repeat(500)))
        assertFalse(validAttribute("campaign", "😀".repeat(501)))
        assertTrue(validAttribute("\$email", null))
        assertFalse(validAttribute("\$ip", "1.2.3.4"))
        assertFalse(validAttribute("1startsWithDigit", "x"))
    }
    @Test fun exactHttpsAllowlistRejectsSpoofedUrls() {
        val hosts = setOf("pay.inappify.com")
        assertTrue(allowedUrl("https://pay.inappify.com/x", hosts))
        listOf("http://pay.inappify.com/x", "https://pay.inappify.com.evil.com", "https://evil.com@pay.inappify.com",
            "https://pay.inappify.com:444/x", "javascript:alert(1)").forEach { assertFalse(allowedUrl(it, hosts)) }
    }
}
