package com.inappify.sdk.internal.v2

import android.content.Context
import com.google.gson.JsonObject
import com.inappify.sdk.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoAutoConfigureTest {
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val requests = V2Transport()
    private var environment = GoV2Environment(signing.config.sdkApiBaseUrl, signing.config.pinnedSigningKeys)
    private val options = InappifyOptions("test-public-key")
    private val metadata = AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) }

    private fun client() = GoV2Client(environment, GoApi(requests, { signing.now }, {}), store,
        metadata, { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false)

    private fun respondToConfigure() {
        requests.handler = { req ->
            assertEquals("configure", req.path)
            requests.response(signing.envelope(jsonObject(req.jsonBody).string("appUserIdentifier")))
        }
    }

    @Test fun keyOnlyConfigurePersistsUuidBeforeHttpAndLearnsAuthenticatedScope() = runBlocking {
        requests.handler = { req ->
            val body = jsonObject(req.jsonBody)
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertEquals(setOf("appUserIdentifier", "identifierValue", "versionName", "versionCode", "sdkVersion", "country"), body.keySet())
            assertEquals("IR", body.string("country"))
            assertEquals(BuildConfig.SDK_VERSION, body.string("sdkVersion"))
            assertEquals("Bearer test-public-key", req.headers["Authorization"])
            assertEquals("com.example.mobile", body.string("identifierValue"))
            assertEquals("2.4.0", body.string("versionName"))
            assertEquals(20400L, body.number("versionCode"))
            val id = body.string("appUserIdentifier")
            assertEquals(id, saved.string("anonymousId"))
            assertEquals(4, UUID.fromString(id.removePrefix("\$INAAnonymousID:")).version())
            assertFalse(saved.has("session")); assertFalse(saved.has("scope"))
            requests.response(signing.envelope(id))
        }
        client().use { sdk ->
            assertNull(sdk.verifiedSessionScope)
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertTrue(sdk.snapshot.isConfigured)
            assertFalse(sdk.snapshot.isAuthenticated)
            assertEquals(12L, sdk.snapshot.appId)
            assertEquals(InappifyV2SessionScope(signing.config.issuer, 12, 34), sdk.verifiedSessionScope)
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertEquals(sdk.verifiedSessionScope, saved.getAsJsonObject("scope").sessionScope())
            assertEquals(sdk.snapshot.appUserIdentifier, saved.string("anonymousId"))
        }
    }

    @Test fun timeoutAndProcessRestartReuseTheSamePersistedUuid() = runBlocking {
        requests.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
        client().use { sdk -> assertTrue(sdk.configure(options) is InappifyResult.Failure) }
        val firstId = jsonObject(requests.requests.first().jsonBody).string("appUserIdentifier")
        assertTrue(requests.requests.all { jsonObject(it.jsonBody).string("appUserIdentifier") == firstId })
        requests.requests.clear(); respondToConfigure()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(firstId, sdk.snapshot.appUserIdentifier)
            assertEquals(firstId, jsonObject(requests.requests.single().jsonBody).string("appUserIdentifier"))
        }
    }

    @Test fun processRestartUsesVerifiedCacheWithoutAnotherConfigure() = runBlocking {
        respondToConfigure()
        val id = client().use { sdk -> assertTrue(sdk.configure(options) is InappifyResult.Success); sdk.snapshot.appUserIdentifier }
        requests.requests.clear()
        requests.handler = { error("Cache reuse must not contact the backend") }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(id, sdk.snapshot.appUserIdentifier)
            assertEquals(34L, sdk.verifiedSessionScope!!.projectId)
            assertTrue(requests.requests.isEmpty())
        }
    }

    @Test fun concurrentKeyOnlyConfigureCallsShareOneRequestAndUuid() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        requests.handler = { req ->
            started.complete(Unit); release.await()
            requests.response(signing.envelope(jsonObject(req.jsonBody).string("appUserIdentifier")))
        }
        client().use { sdk ->
            val callers = (1..20).map { async { sdk.configure(options) } }
            started.await(); release.complete(Unit)
            assertTrue(callers.awaitAll().all { it is InappifyResult.Success })
            assertEquals(1, requests.requests.size)
        }
    }

    @Test fun failureSavingUuidPreventsAnyNetworkRequest() = runBlocking {
        store.failSave = true
        client().use { sdk ->
            val result = sdk.configure(options) as InappifyResult.Failure
            assertEquals("SECURE_STORAGE_FAILED", result.error.details["serverCode"])
            assertTrue(requests.requests.isEmpty())
            assertFalse(sdk.snapshot.isConfigured)
        }
    }

    @Test fun failureSavingVerifiedSessionDoesNotPublishScopeOrToken() = runBlocking {
        requests.handler = { req ->
            store.failSave = true
            requests.response(signing.envelope(jsonObject(req.jsonBody).string("appUserIdentifier")))
        }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertNull(sdk.verifiedSessionScope)
            assertNull(sdk.snapshot.customerInfo)
            assertFalse(sdk.snapshot.isConfigured)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("session"))
        }
    }

    @Test fun unsignedAppIdMustMatchAuthenticatedAppClaim() = runBlocking {
        requests.handler = { req -> requests.response(signing.envelope(
            jsonObject(req.jsonBody).string("appUserIdentifier")).apply { addProperty("appId", 99) }) }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertNull(sdk.verifiedSessionScope); assertFalse(sdk.snapshot.isConfigured)
        }
    }

    @Test fun firstResponseCannotInventInvalidScopeEvenWithAValidSignature() = runBlocking {
        for (field in listOf("iss", "app_id", "project_id")) {
            store.value = null
            requests.handler = { req -> requests.response(signing.envelope(
                jsonObject(req.jsonBody).string("appUserIdentifier"), mutatePayload = {
                    if (field == "iss") it.addProperty(field, "") else it.addProperty(field, 0)
                })) }
            client().use { sdk ->
                assertTrue(sdk.configure(options) is InappifyResult.Failure)
                assertNull(sdk.verifiedSessionScope)
            }
        }
    }

    @Test fun subsequentResponsesCannotChangeIssuerAppOrProject() = runBlocking {
        respondToConfigure()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            val scope = sdk.verifiedSessionScope
            for (field in listOf("iss", "app_id", "project_id")) {
                requests.handler = { requests.response(signing.envelope(sdk.snapshot.appUserIdentifier!!,
                    mutatePayload = { if (field == "iss") it.addProperty(field, "another-issuer") else it.addProperty(field, 99) })) }
                assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
                assertEquals(scope, sdk.verifiedSessionScope)
                assertTrue(sdk.snapshot.isConfigured)
            }
        }
    }

    @Test fun loginRejectsCrossProjectSessionAndRecoversOnlyTheDurableSameScopeAttempt() = runBlocking {
        respondToConfigure()
        val target = "customer_other_123456"
        var predecessorToken: String? = null
        var attemptId: String? = null
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            val before = jsonObject(store.value!!.customerInfoJson!!)
            predecessorToken = before.getAsJsonObject("session").string("sessionToken")
            requests.handler = { requests.response(signing.envelope(target,
                mutatePayload = { it.addProperty("project_id", 35) }).apply {
                    addProperty("sessionToken", "rejected-cross-project-token")
                }) }
            val rejected = sdk.login(InappifyLoginRequest(options.apiKey, target)) as InappifyResult.Failure
            assertEquals("SIGNATURE", rejected.error.details["category"])
            assertEquals("project_id", rejected.error.details["mismatchField"])
            assertEquals("login", rejected.error.details["operation"])
            assertEquals(true, rejected.error.details["outcomeMayHaveCommitted"])
            // A rejected HTTP-success login may still have committed remotely.
            // Keep the predecessor for replay, but grant access to neither identity.
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.appUserIdentifier)
            assertNull(sdk.snapshot.customerInfo)
            assertNull(sdk.verifiedSessionScope)
            assertFalse(sdk.hasEntitlement("pro"))
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Failure)
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertEquals(before.getAsJsonObject("session"), saved.getAsJsonObject("session"))
            assertEquals(before.getAsJsonObject("info"), saved.getAsJsonObject("info"))
            assertEquals(before.getAsJsonObject("scope"), saved.getAsJsonObject("scope"))
            assertEquals(before.string("customerSubject"), saved.string("customerSubject"))
            assertEquals(target, saved.getAsJsonObject("pendingLogin").string("appUserId"))
            attemptId = saved.getAsJsonObject("pendingLogin").string("attemptId")
            assertEquals(attemptId, requests.requests.last().headers["X-Inappify-Login-Attempt"])
        }
        requests.requests.clear()
        requests.handler = { request ->
            assertEquals("login", request.path)
            assertEquals("Bearer $predecessorToken", request.headers["Authorization"])
            assertEquals(attemptId, request.headers["X-Inappify-Login-Attempt"])
            assertEquals(target, jsonObject(request.jsonBody).string("appUserIdentifier"))
            requests.response(signing.envelope(target))
        }
        client().use { restarted ->
            assertTrue(restarted.configure(options) is InappifyResult.Success)
            assertEquals(target, restarted.snapshot.appUserIdentifier)
            assertEquals(target, restarted.snapshot.customerInfo?.originalAppUserId)
            assertEquals(34L, restarted.verifiedSessionScope!!.projectId)
            assertTrue(restarted.snapshot.isAuthenticated)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogin"))
            assertEquals(1, requests.requests.size)
        }
    }

    @Test fun downloadedAttackerKeyCannotBootstrapTrust() = runBlocking {
        val attacker = SigningFixture()
        requests.handler = { req ->
            if (req.path == "public-keys") requests.response(jsonObject("""{"keys":[{"kty":"OKP","crv":"Ed25519","alg":"EdDSA","use":"sig","kid":"alien","x":"${attacker.config.pinnedSigningKeys.values.single()}"}]}"""))
            else requests.response(attacker.envelope(jsonObject(req.jsonBody).string("appUserIdentifier"),
                mutateHeader = { it.addProperty("kid", "alien") }))
        }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Failure)
            assertNull(sdk.verifiedSessionScope); assertNull(sdk.snapshot.customerInfo)
            assertEquals(listOf("configure", "public-keys"), requests.requests.map { it.path })
            assertEquals("GET", requests.requests.last().method)
            assertNull(requests.requests.last().headers["Authorization"])
        }
    }

    @Test fun cachedUnknownKeyAliasCanRecoverWithoutRotatingUuid() = runBlocking {
        requests.handler = { req ->
            if (req.path == "public-keys") requests.response(jsonObject("""{"keys":[{"kty":"OKP","crv":"Ed25519","alg":"EdDSA","use":"sig","kid":"alias","x":"${signing.config.pinnedSigningKeys.values.single()}"}]}"""))
            else requests.response(signing.envelope(jsonObject(req.jsonBody).string("appUserIdentifier"),
                mutateHeader = { it.addProperty("kid", "alias") }))
        }
        val id = client().use { sdk -> assertTrue(sdk.configure(options) is InappifyResult.Success); sdk.snapshot.appUserIdentifier }
        requests.requests.clear()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(id, sdk.snapshot.appUserIdentifier)
            assertEquals(listOf("public-keys"), requests.requests.map { it.path })
        }
    }

    @Test fun cachedScopeIsRetainedWhenConfigureRequestsAnotherCustomer() = runBlocking {
        respondToConfigure()
        client().use { assertTrue(it.configure(options) is InappifyResult.Success) }
        requests.handler = { req -> requests.response(signing.envelope(
            jsonObject(req.jsonBody).string("appUserIdentifier"), mutatePayload = { it.addProperty("project_id", 99) })) }
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions(options.apiKey, "customer_other_123456")) is InappifyResult.Failure)
            assertNull(sdk.snapshot.customerInfo)
            assertNull(sdk.verifiedSessionScope)
        }
    }

    @Test fun explicitGoCacheMigratesOnlyForTheSameKeyAndVerifiedScope() = runBlocking {
        respondToConfigure()
        val id = GoV2Client(signing.config, GoApi(requests, { signing.now }, {}), store, metadata,
            { signing.now }, Dispatchers.Unconfined, false).use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success); sdk.snapshot.appUserIdentifier
        }
        requests.requests.clear()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals(id, sdk.snapshot.appUserIdentifier)
            assertTrue(requests.requests.isEmpty())
        }
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("another-public-key")) is InappifyResult.Success)
            assertNotEquals(id, sdk.snapshot.appUserIdentifier)
            assertEquals(1, requests.requests.size)
        }
    }

    @Test fun expiredSessionRenewsWithTheSamePersistedIdentity() = runBlocking {
        respondToConfigure()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            val id = sdk.snapshot.appUserIdentifier!!
            var expired = false
            requests.requests.clear()
            requests.handler = { req ->
                if (req.path == "customerInfo" && !expired) {
                    expired = true
                    requests.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401)
                } else {
                    if (req.path == "configure") assertEquals(id, jsonObject(req.jsonBody).string("appUserIdentifier"))
                    requests.response(signing.envelope(id))
                }
            }
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Success)
            assertEquals(listOf("customerInfo", "configure", "customerInfo"), requests.requests.map { it.path })
            assertEquals(id, sdk.snapshot.appUserIdentifier)
        }
    }

    @Test fun logoutUsesServerAnonymousIdentityAndHidesScopeWhilePending() = runBlocking {
        respondToConfigure()
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            val before = sdk.snapshot.appUserIdentifier
            requests.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logout() is InappifyResult.Failure)
            assertTrue(sdk.isLogoutPending); assertNull(sdk.verifiedSessionScope)
            val nextId = anonymousId()
            requests.handler = { requests.response(signing.envelope(nextId)) }
            assertTrue(sdk.recover() is InappifyResult.Success)
            assertEquals(nextId, sdk.snapshot.appUserIdentifier)
            assertNotEquals(before, nextId)
            assertEquals(12L, sdk.verifiedSessionScope!!.appId)
        }
    }

    @Test fun defaultFactoryIsAdditiveAndProductionTrustIsNotAnAppInput() {
        val env = GoV2Environment.production()
        assertNull(env.expectedScope)
        assertEquals(InappifyV2Configuration.DEFAULT_SDK_API_BASE_URL, env.sdkApiBaseUrl)
        assertEquals(32, base64Url(env.pinnedSigningKeys.values.single()).size)
        assertNotNull(InappifyV2Client::class.java.getDeclaredMethod("create", Context::class.java))
        assertNotNull(InappifyV2Client::class.java.getDeclaredMethod("create", Context::class.java, InappifyV2Configuration::class.java))
        assertTrue(allowedUrl("https://pay.inappify.com/redirect/test", env.paymentHosts))
        assertFalse(allowedUrl("https://pay.inappify.com.evil.test/redirect", env.paymentHosts))
    }
}
