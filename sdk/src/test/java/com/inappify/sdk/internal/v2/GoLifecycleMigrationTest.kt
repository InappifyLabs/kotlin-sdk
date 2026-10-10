package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.network.TransportFailureKind
import com.inappify.sdk.internal.network.TransportResult
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.PersistedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoLifecycleMigrationTest {
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val transport = V2Transport()
    private val options = InappifyOptions("public-key", signing.subject)
    private fun client() = GoV2Client(signing.config, GoApi(transport, { signing.now }, {}), store,
        AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
        { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false)

    private suspend fun seedOldPendingLogout() {
        transport.handler = { transport.response(signing.envelope()) }
        client().use { assertTrue(it.configure(options) is InappifyResult.Success) }
        val previous = store.value!!
        val old = jsonObject(previous.customerInfoJson!!).apply {
            addProperty("pendingLogout", true)
            remove("pendingLogoutAttempt")
        }
        store.value = PersistedSession(null, null, null, previous.appId, null,
            previous.apiKeyFingerprint, old.toString(), null, null)
        transport.requests.clear()
    }

    @Test fun oldBooleanOnlyPendingLogoutMigratesBeforeHttpAndCompletes() = runBlocking {
        seedOldPendingLogout()
        client().use { sdk ->
            transport.handler = { request ->
                assertEquals("logout", request.path)
                assertNull(sdk.snapshot.customerInfo)
                assertFalse(sdk.snapshot.isConfigured)
                val persisted = jsonObject(store.value!!.customerInfoJson!!)
                assertTrue(persisted.flag("pendingLogout"))
                assertEquals(persisted.getAsJsonObject("pendingLogoutAttempt").string("attemptId"),
                    request.headers["X-Inappify-Logout-Attempt"])
                transport.response(signing.envelope(anonymousId()))
            }
            assertTrue(sdk.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertFalse(sdk.isLogoutPending)
            assertTrue(sdk.isCustomerAnonymous(sdk.snapshot.appUserIdentifier))
            assertEquals(1, transport.requests.size)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogoutAttempt"))
        }
    }

    @Test fun oldPendingLogoutMigrationStorageFailureDoesNotSendHttpOrExposeCustomer() = runBlocking {
        seedOldPendingLogout()
        store.failSave = true
        transport.handler = { error("Migration must be durable before replay") }
        client().use { sdk ->
            val result = sdk.configure(InappifyOptions("public-key")) as InappifyResult.Failure
            assertEquals("SECURE_STORAGE_FAILED", result.error.details["serverCode"])
            assertTrue(sdk.isLogoutPending)
            assertNull(sdk.snapshot.customerInfo)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun migratedAttemptIsReusedAfterOfflineRestart() = runBlocking {
        seedOldPendingLogout()
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { assertTrue(it.configure(InappifyOptions("public-key")) is InappifyResult.Failure) }
        val attempt = transport.requests.single().headers["X-Inappify-Logout-Attempt"]
        transport.requests.clear()
        transport.handler = { request ->
            assertEquals(attempt, request.headers["X-Inappify-Logout-Attempt"])
            transport.response(signing.envelope(anonymousId()))
        }
        client().use { assertTrue(it.configure(InappifyOptions("public-key")) is InappifyResult.Success) }
        assertEquals(1, transport.requests.size)
    }

    @Test fun legacyPendingLogoutWithRemovedInvalidTokenRenewsWithoutExposingIdentity() = runBlocking {
        seedOldPendingLogout()
        val previous = store.value!!
        val old = jsonObject(previous.customerInfoJson!!).apply {
            addProperty("sessionInvalid", true)
            getAsJsonObject("session").remove("sessionToken")
        }
        store.value = PersistedSession(null, null, null, previous.appId, null,
            previous.apiKeyFingerprint, old.toString(), null, null)
        client().use { sdk ->
            transport.handler = { request ->
                assertTrue(sdk.isLogoutPending)
                assertNull(sdk.snapshot.customerInfo)
                if (request.path == "configure") {
                    assertEquals(signing.subject, jsonObject(request.jsonBody).string("appUserIdentifier"))
                    transport.response(signing.envelope())
                } else {
                    assertEquals("logout", request.path)
                    assertEquals("Bearer go-session-token", request.headers["Authorization"])
                    transport.response(signing.envelope(anonymousId()))
                }
            }
            assertTrue(sdk.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertEquals(listOf("configure", "logout"), transport.requests.map { it.path })
            assertFalse(sdk.isLogoutPending)
            assertTrue(sdk.isCustomerAnonymous(sdk.snapshot.appUserIdentifier))
        }
    }

    @Test fun networkDisabledDoesNotCreateAnIdentityTransitionThatWasNeverSent() = runBlocking {
        transport.handler = { transport.response(signing.envelope()) }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            val before = sdk.snapshot
            sdk.setNetworkEnabled(false)
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "new-user")) is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot)
            assertTrue(sdk.logout() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot)
            assertFalse(sdk.isLogoutPending)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogin"))
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun anonymousUuidCasingAcceptedByPreviousVersionsRemainsAnonymous() = runBlocking {
        val oldIdentity = "\$INAAnonymousID:550E8400-E29B-41D4-A716-446655440000"
        assertTrue(isAnonymous(oldIdentity))
        assertTrue(validIdentity(oldIdentity))
        transport.handler = { request ->
            assertEquals(oldIdentity, jsonObject(request.jsonBody).string("appUserIdentifier"))
            transport.response(signing.envelope(oldIdentity))
        }
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("public-key", oldIdentity)) is InappifyResult.Success)
            assertFalse(sdk.snapshot.isAuthenticated)
        }
        transport.requests.clear()
        transport.handler = { error("Valid anonymous cache does not require HTTP") }
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertEquals(oldIdentity, sdk.snapshot.appUserIdentifier)
            assertFalse(sdk.snapshot.isAuthenticated)
        }
    }

    @Test fun defaultCountryUsesLocalFallbackWithoutAResolverDependency() = runBlocking {
        transport.handler = { request ->
            assertEquals("IR", jsonObject(request.jsonBody).string("country"))
            transport.response(signing.envelope())
        }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertEquals("IR", sdk.snapshot.country)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun failedContextChangeClearsWrongTargetedOfferingsButPreservesSameCustomerCache() = runBlocking {
        transport.handler = { request ->
            if (request.path == "offerings") transport.response(jsonObject(
                """{"status":true,"offerings":[{"identifier":"old-country","isDefault":true}]}"""))
            else transport.response(signing.envelope())
        }
        client().use { sdk ->
            assertTrue(sdk.configure(options) is InappifyResult.Success)
            assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            val customer = sdk.snapshot.customerInfo
            assertNotNull(sdk.snapshot.offerings)
            transport.requests.clear()
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            val changed = InappifyOptions("public-key", signing.subject, country = "TR", appVersion = "3.0.0")
            assertTrue(sdk.configure(changed) is InappifyResult.Failure)
            assertEquals(customer, sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertEquals("IR", saved.string("country"))
            assertEquals("2.4.0", saved.string("appVersion"))
            assertFalse(saved.has("offerings"))
            transport.requests.clear()
            assertTrue(sdk.configure(changed) is InappifyResult.Failure)
            assertFalse(transport.requests.isEmpty())
            assertTrue(transport.requests.all {
                it.path == "configure" && jsonObject(it.jsonBody).string("country") == "TR" &&
                    jsonObject(it.jsonBody).string("versionName") == "3.0.0"
            })
        }
    }
}
