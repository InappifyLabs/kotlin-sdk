package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.PersistedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic keys and identities only. Exercises the backend's internal-sub/public-ID contract. */
class GoSubjectBindingTest {
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val transport = V2Transport()
    private val options = InappifyOptions("public-test-key")
    private var publicId = anonymousId()
    private var internalId = "91001"
    private val environment = GoV2Environment(signing.config.sdkApiBaseUrl, signing.config.pinnedSigningKeys)

    private fun client() = GoV2Client(environment, GoApi(transport, { signing.now }, {}), store,
        AppMetadataProvider { AppMetadata("com.example.test", "2.0", 200) },
        { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false)

    private fun envelope(identity: String = publicId, subject: String = internalId,
        mutate: (JsonObject) -> Unit = {}): JsonObject = signing.envelope(identity, mutatePayload = {
        it.addProperty("sub", subject)
        // Fresh customers legitimately have no entitlement list.
        it.getAsJsonObject("customer_info").add("entitlements", com.google.gson.JsonNull.INSTANCE)
        mutate(it)
    })

    private fun install() {
        transport.handler = { req ->
            when (req.path) {
                "configure", "login" -> {
                    publicId = jsonObject(req.jsonBody).string("appUserIdentifier")
                    transport.response(envelope())
                }
                "logout" -> { publicId = anonymousId(); transport.response(envelope()) }
                "customerInfo" -> transport.response(envelope())
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[]}"""))
                "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                else -> error("Unexpected endpoint: ${req.path}")
            }
        }
    }

    private suspend fun configure(sdk: GoV2Client) {
        install()
        assertTrue(sdk.configure(options) is InappifyResult.Success)
    }
    private fun saved(): JsonObject = jsonObject(store.value!!.customerInfoJson!!)
    private fun replaceSaved(change: JsonObject.() -> Unit) {
        val before = store.value!!
        store.value = PersistedSession(null, null, null, before.appId, null,
            before.apiKeyFingerprint, saved().apply(change).toString(), null, null)
    }
    private fun error(result: InappifyResult<*>): InappifyError = (result as InappifyResult.Failure).error

    @Test fun keyOnlyConfigureAcceptsInternalSubjectAndKeepsPublicAnonymousIdentity() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            assertTrue(sdk.snapshot.isConfigured)
            assertFalse(sdk.snapshot.isAuthenticated)
            assertNotEquals(internalId, publicId)
            assertEquals(publicId, sdk.snapshot.appUserIdentifier)
            assertEquals(publicId, sdk.snapshot.customerInfo!!.originalAppUserId)
            assertEquals(internalId, saved().string("customerSubject"))
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
            assertEquals(publicId, jsonObject(transport.requests.first().jsonBody).string("appUserIdentifier"))
            assertFalse(transport.requests.first().jsonBody.contains(internalId))
        }
    }

    @Test fun refreshRejectsAnotherInternalCustomerWithoutDroppingGoodState() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val before = sdk.snapshot
            val durable = store.value!!.customerInfoJson
            internalId = "91002"
            val failure = error(sdk.refreshCustomerInfo())
            assertEquals("SCOPE_MISMATCH", failure.details["serverCode"])
            assertEquals("sub", failure.details["mismatchField"])
            assertEquals("verifiedSession", failure.details["expectedSource"])
            assertEquals(true, failure.details["signatureVerified"])
            assertEquals(200, failure.details["httpStatus"])
            assertEquals("customerInfo", failure.details["operation"])
            assertEquals(before, sdk.snapshot)
            assertEquals(durable, store.value!!.customerInfoJson)
            assertFalse(failure.details.toString().contains("91001"))
            assertFalse(failure.details.toString().contains("91002"))
            assertFalse(failure.details.toString().contains(publicId))
        }
    }

    @Test fun refreshRejectsAnotherPublicIdentityEvenWhenInternalSubjectMatches() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val before = sdk.snapshot
            transport.handler = { transport.response(envelope(identity = "different_public_user_123")) }
            val failure = error(sdk.refreshCustomerInfo())
            assertEquals("CUSTOMER_MISMATCH", failure.details["serverCode"])
            assertEquals("customer_info.originalAppUserId", failure.details["mismatchField"])
            assertEquals(before, sdk.snapshot)
        }
    }

    @Test fun renewalPreservesSubjectAndUsesPublicIdRatherThanInternalId() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val id = publicId
            val original = transport.handler
            var expired = false
            transport.handler = { req ->
                if (req.path == "customerInfo" && !expired) {
                    expired = true
                    transport.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401)
                } else original(req)
            }
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
            assertEquals(listOf("configure", "customerInfo", "configure", "customerInfo"), transport.requests.map { it.path })
            transport.requests.filter { it.path == "configure" }.forEach {
                assertEquals(id, jsonObject(it.jsonBody).string("appUserIdentifier"))
            }
            assertEquals(internalId, saved().string("customerSubject"))
        }
    }

    @Test fun renewalCannotSilentlySwitchInternalCustomer() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val before = sdk.snapshot
            transport.handler = { req -> if (req.path == "customerInfo")
                transport.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401)
                else transport.response(envelope(subject = "other-internal")) }
            val failure = error(sdk.refreshCustomerInfo())
            assertEquals("sub", failure.details["mismatchField"])
            assertEquals("configure", failure.details["operation"])
            assertEquals(true, failure.details["outcomeMayHaveCommitted"])
            assertEquals(before, sdk.snapshot)
            assertEquals(internalId, saved().string("customerSubject"))
        }
    }

    @Test fun processRestartRestoresInternalBindingWithoutNetwork() = runBlocking {
        client().use { configure(it) }
        val id = publicId
        transport.requests.clear()
        transport.handler = { error("Verified cache should load offline") }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(id, sdk.snapshot.appUserIdentifier)
            assertTrue(transport.requests.isEmpty())
            transport.handler = { transport.response(envelope(subject = "different-internal")) }
            assertEquals("sub", error(sdk.refreshCustomerInfo()).details["mismatchField"])
        }
    }

    @Test fun olderGoCacheDerivesMissingBindingFromVerifiedSession() = runBlocking {
        // The older client could save a response where sub happened to equal the public ID.
        install()
        transport.handler = { req ->
            publicId = jsonObject(req.jsonBody).string("appUserIdentifier")
            transport.response(envelope(subject = publicId))
        }
        client().use { assertTrue(it.configure(options) is InappifyResult.Success) }
        replaceSaved { remove("customerSubject") }
        transport.requests.clear()
        transport.handler = { error("Migration should use verified cache") }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(publicId, sdk.snapshot.appUserIdentifier)
            assertTrue(transport.requests.isEmpty())
            transport.handler = { transport.response(envelope(subject = "different-internal")) }
            assertEquals("sub", error(sdk.refreshCustomerInfo()).details["mismatchField"])
            transport.handler = { transport.response(envelope(subject = publicId)) }
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
            assertEquals(publicId, saved().string("customerSubject"))
        }
    }

    @Test fun cachedInfoFromAnotherInternalSubjectCannotGrantAccess() = runBlocking {
        client().use { configure(it) }
        replaceSaved { add("info", envelope(subject = "different-internal")) }
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.customerInfo)
        }
    }

    @Test fun tamperedStoredBindingIsNotTrustedOverSignedSession() = runBlocking {
        client().use { configure(it) }
        replaceSaved { addProperty("customerSubject", "different-internal") }
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertNull(sdk.snapshot.customerInfo)
        }
    }

    @Test fun loginCanReplaceBindingOnlyForTheVerifiedRequestedPublicIdentity() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            sdk.refreshOfferings(); sdk.queueAttributes(mapOf("campaign" to "previous-owner"))
            internalId = "91002"
            val target = "customer_new_identity_123"
            assertTrue(sdk.login(InappifyLoginRequest(options.apiKey, target)) is InappifyResult.Success)
            assertEquals(target, sdk.snapshot.appUserIdentifier)
            assertTrue(sdk.snapshot.isAuthenticated)
            assertEquals(internalId, saved().string("customerSubject"))
            assertNull(sdk.snapshot.offerings)
            val requestsBefore = transport.requests.size
            assertTrue(sdk.flushAttributes() is InappifyResult.Success)
            assertEquals(requestsBefore, transport.requests.size)
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
            internalId = "91001"
            assertEquals("sub", error(sdk.refreshCustomerInfo()).details["mismatchField"])
        }
    }

    @Test fun failedLoginCannotReplaceBindingAndKeepsUncertainIdentityHidden() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            transport.handler = { transport.response(envelope(identity = "wrong_public_identity_123", subject = "91002")) }
            val failure = error(sdk.login(InappifyLoginRequest(options.apiKey, "requested_public_identity_123")))
            assertEquals("appUserId", failure.details["mismatchField"])
            assertEquals("login", failure.details["operation"])
            assertEquals(200, failure.details["httpStatus"])
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.appUserIdentifier)
            assertNull(sdk.snapshot.customerInfo)
            assertTrue(saved().has("pendingLogin"))
            assertEquals(internalId, saved().string("customerSubject"))
        }
    }

    @Test fun explicitLoginMergeCanRebindSamePublicIdAndClearsOldPrivateState() = runBlocking {
        publicId = "customer_existing_alias_123"
        install()
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions(options.apiKey, publicId)) is InappifyResult.Success)
            sdk.refreshOfferings(); sdk.queueAttributes(mapOf("campaign" to "previous-owner"))
            val alias = publicId
            internalId = "91002"
            assertTrue(sdk.login(InappifyLoginRequest(options.apiKey, alias)) is InappifyResult.Success)
            assertEquals(alias, sdk.snapshot.appUserIdentifier)
            assertEquals(internalId, saved().string("customerSubject"))
            assertNull(sdk.snapshot.offerings)
            val count = transport.requests.size
            sdk.flushAttributes()
            assertEquals(count, transport.requests.size)
        }
    }

    @Test fun failedConfigureUpgradeReusesAlreadyPersistedAnonymousId() = runBlocking {
        // The old SCOPE_MISMATCH left only the pre-request anonymous document on disk.
        transport.handler = { req ->
            publicId = jsonObject(req.jsonBody).string("appUserIdentifier")
            transport.response(envelope(subject = ""))
        }
        client().use { assertTrue(it.configure(options) is InappifyResult.Failure) }
        val id = saved().string("anonymousId")
        assertFalse(saved().has("session"))
        install()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(id, sdk.snapshot.appUserIdentifier)
            assertEquals(internalId, saved().string("customerSubject"))
        }
    }

    @Test fun explicitTenantConfigurationAlsoAcceptsDistinctInternalSubject() = runBlocking {
        install()
        GoV2Client(signing.config, GoApi(transport, { signing.now }, {}), store,
            AppMetadataProvider { AppMetadata("com.example.test", "2.0", 200) },
            { signing.now }, Dispatchers.Unconfined, false).use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(publicId, sdk.snapshot.appUserIdentifier)
            assertEquals(internalId, saved().string("customerSubject"))
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
        }
    }

    @Test fun secureSaveFailureRetainsPreviousBindingAfterLogin() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val before = sdk.snapshot
            store.failSave = true; internalId = "91002"
            assertTrue(sdk.login(InappifyLoginRequest(options.apiKey, "requested_public_identity_123")) is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot)
            assertEquals("91001", saved().string("customerSubject"))
        }
    }

    @Test fun logoutAcceptsNewAnonymousPublicAndInternalIdentities() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            val previous = publicId
            internalId = "91003"
            assertTrue(sdk.logout() is InappifyResult.Success)
            assertNotEquals(previous, sdk.snapshot.appUserIdentifier)
            assertFalse(sdk.snapshot.isAuthenticated)
            assertEquals(internalId, saved().string("customerSubject"))
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
        }
    }

    @Test fun pendingLogoutAcrossRestartCannotExposeOldInternalCustomer() = runBlocking {
        client().use { sdk ->
            configure(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logout() is InappifyResult.Failure)
            assertTrue(sdk.isLogoutPending)
        }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertNull(sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.appUserIdentifier)
            install(); internalId = "91003"
            assertTrue(sdk.recover() is InappifyResult.Success)
            assertFalse(sdk.isLogoutPending)
            assertEquals(internalId, saved().string("customerSubject"))
        }
    }

    @Test fun invalidSubjectsAreRejectedWithSafeFieldSpecificDiagnostics() {
        val verifier = CustomerInfoVerifier(environment) { signing.now }
        val values = listOf<com.google.gson.JsonElement?>(null, com.google.gson.JsonNull.INSTANCE,
            com.google.gson.JsonPrimitive(91001), com.google.gson.JsonPrimitive(""),
            com.google.gson.JsonPrimitive(" \t"), com.google.gson.JsonPrimitive("private\nsubject"),
            com.google.gson.JsonPrimitive("A".repeat(513)))
        for (value in values) {
            val response = envelope { if (value == null) it.remove("sub") else it.add("sub", value) }
            val failure = assertThrows(V2Failure::class.java) { verifier.verifyWithScope(response, publicId) }.sdkError
            assertEquals("sub", failure.details["mismatchField"])
            assertEquals(true, failure.details["signatureVerified"])
            assertFalse(failure.details.toString().contains("private"))
        }
    }

    @Test fun scopeDiagnosticsDistinguishAudienceVersionIssuerAndTenant() {
        val verifier = CustomerInfoVerifier(signing.config) { signing.now }
        for (field in listOf("iss", "aud", "app_id", "project_id", "ver")) {
            val response = envelope { if (field in setOf("iss", "aud")) it.addProperty(field, "private-wrong-claim")
                else it.addProperty(field, 99) }
            val failure = assertThrows(V2Failure::class.java) { verifier.verifyWithScope(response, publicId) }.sdkError
            assertEquals(field, failure.details["mismatchField"])
            assertFalse(failure.details.toString().contains("private-wrong-claim"))
            if (field in setOf("app_id", "project_id", "ver")) assertEquals("99", failure.details["actualValue"])
        }
    }

    @Test fun configureAppIdMismatchReportsHttpSuccessAndSignedComparison() = runBlocking {
        transport.handler = { req ->
            publicId = jsonObject(req.jsonBody).string("appUserIdentifier")
            transport.response(envelope().apply { addProperty("appId", 99) })
        }
        client().use { sdk ->
            val failure = error(sdk.configure(options))
            assertEquals("appId", failure.details["mismatchField"])
            assertEquals("12", failure.details["expectedValue"])
            assertEquals("99", failure.details["actualValue"])
            assertEquals("configure", failure.details["operation"])
            assertEquals(200, failure.details["httpStatus"])
            assertEquals(true, failure.details["outcomeMayHaveCommitted"])
            assertFalse(sdk.snapshot.isConfigured)
            assertFalse(saved().has("customerSubject"))
        }
    }
}
