package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.storage.PersistedSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GoV2ClientTest {
    @Test fun directPurchaseUsesOnlyTheSessionBearer() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { req -> if (req.path == "offerings") transport.response(jsonObject(
                CommerceCatalogFixture().offerings).apply { addProperty("status", true) }) else base(req) }
            sdk.refreshOfferings()
            assertTrue(sdk.purchase(InappifyPurchaseRequest("product", "default")) is InappifyResult.Success)
            val request = transport.requests.last { it.path == "purchase" }
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "attemptId", "paymentReturnVersion"), jsonObject(request.jsonBody).keySet())
        }
    }
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val transport = V2Transport()
    private var identity = signing.subject
    private var installedVersion = "2.4.0"
    private fun client(storage: MemoryV2Store = store,
        countryResolver: suspend () -> String? = { null }): GoV2Client = GoV2Client(signing.config,
        GoApi(transport, { signing.now }, {}), storage,
        AppMetadataProvider { AppMetadata("com.example.mobile", installedVersion, 20400) },
        { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
        countryResolver = countryResolver, commerceApi = GoApi(transport, { signing.now }, {}),
        targetingSyncLimiter = TargetingSyncRateLimiter({ 0 }, {}))
    private fun installHandler() {
        transport.handler = { req ->
            when (req.path) {
                "configure", "login" -> {
                    identity = jsonObject(req.jsonBody).string("appUserIdentifier")
                    transport.response(signing.envelope(identity))
                }
                "logout" -> { identity = anonymousId(); transport.response(signing.envelope(identity)) }
                "customerInfo" -> transport.response(signing.envelope(identity))
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[{"identifier":"default","isDefault":true},{"identifier":"targeted"}],"currentOffering":"targeted","placements":{"onboarding":"default","settings":null}}"""))
                "consumable-deliveries/pending" -> transport.response(jsonObject("""{"status":true,"data":{"deliveries":[]}}"""))
                "purchase-attempts/status" -> transport.response(jsonObject("""{"status":true,"data":{"attempts":[]}}"""))
                "purchase" -> transport.response(jsonObject("""{"status":true,"data":{"purchaseStatus":"DONE"}}"""))
                "attributes" -> TransportResult.Response(HttpResponse(204, null, null))
                "validateDiscountCode" -> transport.response(jsonObject("""{"status":true,"data":{"is_valid":false,"error_code":2,"code":"","discount_id":0,"discount_code_id":0,"percent":0,"message":"Invalid","payment_links":[]}}"""))
                else -> error("Unexpected endpoint")
            }
        }
    }
    private fun configured(client: GoV2Client) = runBlocking {
        installHandler()
        val result = client.configure(InappifyOptions("public-key", signing.subject))
        assertTrue(result.toString(), result is InappifyResult.Success)
    }
    @Test fun shortNumericIdentifiersReachV2ConfigureAndLogin() = runBlocking {
        client().use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", "0")) is InappifyResult.Success)
            assertEquals("0", jsonObject(transport.requests.single().jsonBody).string("appUserIdentifier"))
            assertTrue(sdk.logIn("1234") is InappifyResult.Success)
            val login = transport.requests.last { it.path == "login" }
            assertEquals("1234", jsonObject(login.jsonBody).string("appUserIdentifier"))
            assertEquals(setOf("appUserIdentifier"), jsonObject(login.jsonBody).keySet())
            assertEquals("Bearer go-session-token", login.headers["Authorization"])
            assertEquals("1234", sdk.snapshot.appUserIdentifier)
        }
    }
    @Test fun logInRejectsInvalidIdentityWithoutChangingSessionOrCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.refreshOfferings()
            val before = sdk.snapshot
            val saved = store.value
            transport.requests.clear()

            assertTrue(sdk.logIn(" ") is InappifyResult.Failure)
            assertSame(before, sdk.snapshot)
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun configureUsesExactV2BodyAndCommitsSignedScope() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val request = transport.requests.single()
            assertEquals(setOf("appUserIdentifier", "identifierValue", "versionName", "versionCode", "sdkVersion", "country"),
                jsonObject(request.jsonBody).keySet())
            assertEquals(20400, jsonObject(request.jsonBody).number("versionCode"))
            assertEquals(BuildConfig.SDK_VERSION, jsonObject(request.jsonBody).string("sdkVersion"))
            assertEquals("IR", jsonObject(request.jsonBody).string("country"))
            assertTrue(sdk.isActiveEntitlement("pro")); assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
            assertFalse(store.value.toString().contains("go-session-token"))
        }
    }
    @Test fun directV2PurchaseNeedsNoLegacyCompanionAndDoesNotRetryAnUncertainPayment() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { req -> if (req.path == "offerings") transport.response(jsonObject(
                CommerceCatalogFixture().offerings).apply { addProperty("status", true) }) else base(req) }
            assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            transport.handler = { req -> if (req.path == "purchase")
                TransportResult.Failure(TransportFailureKind.NETWORK) else base(req) }
            val result = sdk.purchase(InappifyPurchaseRequest("product", "default"))
            assertTrue(result is InappifyResult.Failure)
            assertEquals(true, (result as InappifyResult.Failure).error.details["outcomeMayHaveCommitted"])
            assertEquals(1, transport.requests.count { it.path == "purchase" })
        }
    }
    @Test fun v2OfferingsWorkWithoutForceFieldsAndNeverSendOrStoreThem() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            val request = transport.requests.last { it.path == "offerings" }
            assertFalse(jsonObject(request.jsonBody).has("forceVersion"))
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertFalse(saved.getAsJsonObject("session").has("forceVersion"))
            assertFalse(saved.getAsJsonObject("offerings").has("forceVersion"))
            assertFalse(saved.getAsJsonObject("offerings").has("hasForceUpdate"))
        }
    }
    @Test fun invalidSignatureNeverCommitsTokenOrGrantsAccess() = runBlocking {
        client().use { sdk ->
            transport.handler = { transport.response(signing.envelope().apply { getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true) }) }
            assertTrue(sdk.configure(InappifyOptions("key", signing.subject)) is InappifyResult.Failure)
            assertFalse(sdk.snapshot.isConfigured); assertFalse(sdk.hasEntitlement("pro")); assertNull(store.value)
        }
    }
    @Test fun failedRefreshPreservesVerifiedCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot.customerInfo
            transport.handler = { transport.response(signing.envelope().apply { getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true) }) }
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.customerInfo)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
        }
    }
    @Test fun secureWriteFailureCannotPublishNewIdentity() = runBlocking {
        client().use { sdk ->
            configured(sdk); store.failSave = true
            assertTrue(sdk.logIn("customer_other_123456") is InappifyResult.Failure)
            assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
        }
    }
    @Test fun loginMergeFailureRetainsOldIdentityAndCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"CUSTOMER_MERGE_FAILED"}"""), 409) }
            assertTrue(sdk.logIn("customer_other_123456") is InappifyResult.Failure)
            assertEquals(signing.subject, sdk.snapshot.appUserIdentifier); assertTrue(sdk.hasEntitlement("pro"))
            assertTrue(sdk.snapshot.isConfigured)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogin"))
            assertEquals(2, transport.requests.size)
        }
    }
    @Test fun uncertainLoginReplaysSameAttemptAndPredecessorAfterRestart() = runBlocking {
        val target = "customer_other_123456"
        var attempt: String? = null
        client().use { sdk ->
            configured(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logIn(target) is InappifyResult.Failure)
            assertFalse(sdk.snapshot.isConfigured)
            assertNull(sdk.snapshot.appUserIdentifier)
            assertFalse(sdk.hasEntitlement("pro"))
            val loginRequests = transport.requests.filter { it.path == "login" }
            assertEquals(4, loginRequests.size)
            attempt = loginRequests.first().headers["X-Inappify-Login-Attempt"]
            assertNotNull(attempt)
            assertTrue(loginRequests.all { it.headers["X-Inappify-Login-Attempt"] == attempt &&
                it.headers["Authorization"] == "Bearer go-session-token" })
        }
        transport.requests.clear()
        installHandler()
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertEquals(target, restarted.snapshot.appUserIdentifier)
            assertEquals(1, transport.requests.size)
            assertEquals("login", transport.requests.single().path)
            assertEquals("Bearer go-session-token", transport.requests.single().headers["Authorization"])
            assertEquals(attempt, transport.requests.single().headers["X-Inappify-Login-Attempt"])
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogin"))
        }
    }
    @Test fun missingCommittedLoginAttemptReconfiguresSameIdentityAndKeepsAttempt() = runBlocking {
        val target = "customer_other_123456"
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            var loginCalls = 0
            transport.handler = { req ->
                if (req.path == "login" && ++loginCalls == 1)
                    transport.response(jsonObject("""{"status":false,"code":"LOGIN_ATTEMPT_NOT_FOUND"}"""), 401)
                else base(req)
            }
            assertTrue(sdk.logIn(target) is InappifyResult.Success)
            val requests = transport.requests
            assertEquals(listOf("configure", "login", "configure", "login"), requests.map { it.path })
            assertEquals("Bearer public-key", requests[2].headers["Authorization"])
            assertEquals(signing.subject, jsonObject(requests[2].jsonBody).string("appUserIdentifier"))
            assertEquals(requests[1].headers["X-Inappify-Login-Attempt"],
                requests[3].headers["X-Inappify-Login-Attempt"])
            assertEquals(target, sdk.snapshot.appUserIdentifier)
            assertFalse(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogin"))
        }
    }
    @Test fun loginClearsOldOfferingsAndAttributesBeforePublishingNewUser() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings(); sdk.enqueueAttributes(mapOf("campaign" to "old"))
            assertTrue(sdk.logIn("customer_other_123456") is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val attributesBefore = transport.requests.count { it.path == "attributes" }
            sdk.flushQueuedAttributes()
            assertEquals(attributesBefore, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun logOutPersistsAnonymousSessionAndDoesNotReusePreviousCustomerCache() = runBlocking {
        var anonymousIdentity: String? = null
        client().use { sdk ->
            configured(sdk)
            sdk.refreshOfferings()
            sdk.enqueueAttributes(mapOf("campaign" to "previous-customer"))

            assertTrue(sdk.logOut() is InappifyResult.Success)
            anonymousIdentity = sdk.snapshot.appUserIdentifier
            assertTrue(sdk.isCustomerAnonymous(anonymousIdentity))
            assertFalse(sdk.snapshot.isAuthenticated)
            assertFalse(sdk.isLogoutPending)
            assertNull(sdk.snapshot.offerings)
            val requestsBefore = transport.requests.size
            assertTrue(sdk.flushQueuedAttributes() is InappifyResult.Success)
            assertEquals(requestsBefore, transport.requests.size)
        }
        transport.requests.clear()
        transport.handler = { error("The anonymous session must be restored from cache.") }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertEquals(anonymousIdentity, restarted.snapshot.appUserIdentifier)
            assertNull(restarted.snapshot.offerings)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun offlineLogoutHidesIdentityAndPersistsPendingStateAcrossRestart() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logOut() is InappifyResult.Failure)
            assertTrue(sdk.isLogoutPending); assertNull(sdk.snapshot.customerInfo); assertNull(sdk.snapshot.appUserIdentifier)
        }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Failure)
            assertTrue(restarted.isLogoutPending); assertFalse(restarted.hasEntitlement("pro"))
            installHandler(); assertTrue(restarted.recover() is InappifyResult.Success)
            assertFalse(restarted.isLogoutPending); assertTrue(restarted.isCustomerAnonymous(restarted.snapshot.appUserIdentifier))
        }
    }
    @Test fun verifiedCacheLoadsWithoutNetworkAndNeverCrossesApiKey() = runBlocking {
        client().use(::configured)
        transport.requests.clear()
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertTrue(restarted.hasEntitlement("pro")); assertEquals(0, transport.requests.size)
        }
        client().use { other ->
            assertTrue(other.configure(InappifyOptions("other-key", signing.subject)) is InappifyResult.Failure)
            assertFalse(other.hasEntitlement("pro"))
        }
    }
    @Test fun lostLogoutResponseReplaysSameAttemptAfterRestart() = runBlocking {
        var firstAttempt: String? = null
        var successor: JsonObject? = null
        client().use { sdk ->
            configured(sdk)
            transport.handler = { request ->
                if (request.path == "logout") {
                    firstAttempt = request.headers["X-Inappify-Logout-Attempt"]
                    successor = signing.envelope(anonymousId())
                    TransportResult.Failure(TransportFailureKind.NETWORK)
                } else transport.response(signing.envelope())
            }
            assertTrue(sdk.logOut() is InappifyResult.Failure)
            assertNotNull(firstAttempt)
            assertTrue(sdk.isLogoutPending)
            assertNull(sdk.snapshot.customerInfo)
            assertFalse(sdk.hasEntitlement("pro"))
        }
        client().use { restarted ->
            transport.handler = { request ->
                assertEquals("logout", request.path)
                assertEquals(firstAttempt, request.headers["X-Inappify-Logout-Attempt"])
                transport.response(successor!!)
            }
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertFalse(restarted.isLogoutPending)
            assertTrue(restarted.isCustomerAnonymous(restarted.snapshot.appUserIdentifier))
            assertEquals(2, transport.requests.count { it.path == "logout" })
        }
    }
    @Test fun irrecoverableRevokedLogoutUsesVerifiedFreshAnonymousSession() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { request -> when (request.path) {
                "logout" -> transport.response(jsonObject("""{"status":false,"code":"SESSION_REVOKED"}"""), 401)
                "configure" -> transport.response(signing.envelope(jsonObject(request.jsonBody).string("appUserIdentifier")))
                else -> error("Unexpected endpoint")
            } }
            assertTrue(sdk.logOut() is InappifyResult.Success)
            assertFalse(sdk.isLogoutPending)
            assertTrue(sdk.isCustomerAnonymous(sdk.snapshot.appUserIdentifier))
            assertEquals(1, transport.requests.count { it.path == "logout" })
            assertEquals(2, transport.requests.count { it.path == "configure" })
        }
    }
    @Test fun logoutResponseStorageFailureKeepsOldIdentityHidden() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { request ->
                assertEquals("logout", request.path)
                store.failSave = true
                transport.response(signing.envelope(anonymousId()))
            }
            assertTrue(sdk.logOut() is InappifyResult.Failure)
            assertTrue(sdk.isLogoutPending)
            assertNull(sdk.snapshot.appUserIdentifier)
            assertNull(sdk.snapshot.customerInfo)
            assertFalse(sdk.hasEntitlement("pro"))
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).has("pendingLogoutAttempt"))
        }
    }
    @Test fun oldV2CacheForceFieldsAreIgnoredAndRemovedOnNextSave() = runBlocking {
        client().use(::configured)
        val previous = store.value!!
        val oldCache = jsonObject(previous.customerInfoJson!!).apply {
            getAsJsonObject("session").addProperty("forceVersion", 4)
            getAsJsonObject("info").addProperty("forceVersion", 4)
            add("offerings", jsonObject("""{"status":true,"forceVersion":4,"hasForceUpdate":true,"offerings":[]}"""))
        }
        store.value = PersistedSession(null, null, null, previous.appId, null,
            previous.apiKeyFingerprint, oldCache.toString(), null, null)
        client().use { restarted ->
            installHandler()
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertTrue(restarted.refreshOfferings() is InappifyResult.Success)
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertFalse(saved.getAsJsonObject("session").has("forceVersion"))
            assertFalse(saved.getAsJsonObject("info").has("forceVersion"))
            assertFalse(saved.getAsJsonObject("offerings").has("forceVersion"))
            assertFalse(saved.getAsJsonObject("offerings").has("hasForceUpdate"))
        }
    }
    @Test fun twentyConcurrentExpiredRequestsUseOneConfigureAndOneReplay() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var customerCalls = 0
            transport.handler = { request ->
                if (request.path == "customerInfo" && ++customerCalls == 1) {
                    entered.complete(Unit); release.await()
                    transport.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401)
                } else transport.response(signing.envelope())
            }
            val jobs = (1..20).map { async(start = CoroutineStart.UNDISPATCHED) { sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) } }
            entered.await(); yield(); release.complete(Unit)
            assertTrue(jobs.awaitAll().all { it is InappifyResult.Success })
            assertEquals(2, transport.requests.count { it.path == "configure" })
            assertEquals(2, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun corruptedCacheCannotGrantAccessAndOnlineConfigureRepairsIt() = runBlocking {
        client().use(::configured)
        val previous = store.value!!
        val corrupt = jsonObject(previous.customerInfoJson!!).apply {
            getAsJsonObject("info").getAsJsonObject("customerInfo").addProperty("hasUsedTrial", true)
        }
        store.value = PersistedSession(null, null, null, 12, null,
            previous.apiKeyFingerprint, corrupt.toString(), null, null)
        transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Failure)
            assertNull(restarted.snapshot.customerInfo)
            assertFalse(restarted.hasEntitlement("pro"))
            installHandler()
            assertTrue(restarted.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertTrue(restarted.hasEntitlement("pro"))
        }
    }
    @Test fun installedAppUpdateInvalidatesOnlyOfferingsCache() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val customer = sdk.snapshot.customerInfo
            assertNotNull(sdk.snapshot.offerings)
            installedVersion = "3.0.0"
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertEquals(2, transport.requests.count { it.path == "configure" })
            val configureBody = jsonObject(transport.requests.last { it.path == "configure" }.jsonBody)
            assertEquals("IR", configureBody.string("country"))
            assertEquals("3.0.0", configureBody.string("versionName"))
            assertEquals(20400L, configureBody.number("versionCode"))
            assertEquals(BuildConfig.SDK_VERSION, configureBody.string("sdkVersion"))
            assertEquals(customer, sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
            sdk.refreshOfferings()
            val body = jsonObject(transport.requests.last().jsonBody)
            assertEquals("IR", body.string("country")); assertEquals("3.0.0", body.string("appVersion"))
        }
    }
    @Test fun lowercaseCountryIsNormalizedInConfigureAndSnapshot() = runBlocking {
        client(countryResolver = { "tr" }).use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertEquals("TR", jsonObject(transport.requests.last().jsonBody).string("country"))
            assertEquals("TR", sdk.snapshot.country)
        }
    }
    @Test fun detectedCountryIsUsedWhenAppDidNotProvideOne() = runBlocking {
        var lookups = 0
        client(countryResolver = { lookups++; "de" }).use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertEquals("DE", jsonObject(transport.requests.last().jsonBody).string("country"))
            assertEquals("DE", sdk.snapshot.country)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            val offeringBody = jsonObject(transport.requests.last().jsonBody)
            assertEquals("DE", offeringBody.string("country"))
            assertEquals(installedVersion, offeringBody.string("appVersion"))
            assertEquals(BuildConfig.SDK_VERSION, offeringBody.string("sdkVersion"))
            assertEquals(1, lookups)
        }
    }
    @Test fun failedCountryLookupFallsBackWithoutRetryingOnNextConfigureAttempt() = runBlocking {
        var lookups = 0
        client(countryResolver = { lookups++; throw java.io.IOException("offline") }).use { sdk ->
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Failure)
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertEquals("IR", jsonObject(transport.requests.last().jsonBody).string("country"))
            assertEquals(1, lookups)
        }
    }
    @Test fun cachedCountrySkipsIpLookupOnRestart() = runBlocking {
        client(countryResolver = { "TR" }).use { sdk ->
            configured(sdk)
        }
        var lookups = 0
        client(countryResolver = { lookups++; "DE" }).use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            assertEquals("TR", sdk.snapshot.country)
            assertEquals(0, lookups)
        }
    }
    @Test fun cancellingOneCallerDoesNotCancelSharedRefresh() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            transport.handler = { entered.complete(Unit); release.await(); transport.response(signing.envelope()) }
            val first = async { sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) }; entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) }
            first.cancelAndJoin(); release.complete(Unit)
            assertTrue(second.await() is InappifyResult.Success)
            assertEquals(1, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun repeatedExpired401StopsAfterOneReconfiguration() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { req -> if (req.path == "configure") transport.response(signing.envelope()) else
                transport.response(jsonObject("""{"status":false,"code":"SESSION_EXPIRED"}"""), 401) }
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) is InappifyResult.Failure)
            assertEquals(2, transport.requests.count { it.path == "configure" })
            assertEquals(2, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun revokedSessionDoesNotSilentlyReconfigure() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { request -> if (request.path == "customerInfo")
                transport.response(jsonObject("""{"status":false,"code":"SESSION_REVOKED"}"""), 401)
                else transport.response(signing.envelope()) }
            val result = sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT)
            assertTrue(result is InappifyResult.Failure)
            assertEquals(1, transport.requests.count { it.path == "configure" })
            assertEquals(1, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun setAttributesMapMatchesGoWireContractAndInvalidatesOnlyOfferings() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.refreshOfferings()
            val customer = sdk.snapshot.customerInfo
            val unicodeValue = String(Character.toChars(0x1F600)).repeat(500)
            transport.requests.clear()

            val result = sdk.setAttributes(mapOf(
                "city" to "Tehran", "removed" to null, "empty" to "",
                "whitespace" to " ", "unicode" to unicodeValue,
            ))
            assertTrue(result is InappifyResult.Success)
            assertTrue((result as InappifyResult.Success).data.isEmpty())
            val request = transport.requests.single()
            assertEquals("attributes", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            val body = jsonObject(request.jsonBody)
            assertEquals(setOf("attributes"), body.keySet())
            val attributes = body.getAsJsonObject("attributes")
            assertEquals(setOf("city", "removed", "empty", "whitespace", "unicode"), attributes.keySet())
            assertEquals("Tehran", attributes.string("city"))
            assertTrue(attributes.get("removed").isJsonNull)
            assertTrue(attributes.get("empty").isJsonNull)
            assertEquals(" ", attributes.string("whitespace"))
            assertEquals(unicodeValue, attributes.string("unicode"))
            assertEquals(customer, sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
            assertEquals(0, jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").size())
        }
    }
    @Test fun setIpUsesTheExistingWriteOnlyAttributeQueueAndSupportsDeletion() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.refreshOfferings()
            val customer = sdk.snapshot.customerInfo
            assertEquals(true, (sdk.canSetReservedAttribute("\$ip") as InappifyResult.Success).data)
            for (value in listOf("1.2.3.4", "2001:db8::1", "")) {
                transport.requests.clear()
                assertTrue(sdk.setIp(value) is InappifyResult.Success)
                val request = transport.requests.single()
                assertEquals("attributes", request.path)
                assertEquals("POST", request.method)
                assertEquals("Bearer go-session-token", request.headers["Authorization"])
                val body = jsonObject(request.jsonBody)
                assertEquals(setOf("attributes"), body.keySet())
                val values = body.getAsJsonObject("attributes")
                assertEquals(setOf("\$ip"), values.keySet())
                if (value.isEmpty()) assertTrue(values.get("\$ip").isJsonNull)
                else assertEquals(value, values.string("\$ip"))
                assertEquals(customer, sdk.snapshot.customerInfo)
                assertNull(sdk.snapshot.customerInfo?.attributes)
                assertNull(sdk.snapshot.offerings)
                assertEquals(0, jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").size())
            }
            transport.requests.clear()
            assertTrue(sdk.setAttributes(mapOf("\$ip" to null)) is InappifyResult.Success)
            assertTrue(jsonObject(transport.requests.single().jsonBody)
                .getAsJsonObject("attributes").get("\$ip").isJsonNull)
        }
    }
    @Test fun setAttributesCapturesCallerMapBeforeWaitingForAnotherOperation() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            transport.handler = { request ->
                if (request.path == "offerings") {
                    entered.complete(Unit)
                    release.await()
                }
                base(request)
            }
            val catalog = async(start = CoroutineStart.UNDISPATCHED) { sdk.getOfferings() }
            withTimeout(5_000) { entered.await() }
            val values = linkedMapOf<String, String?>("city" to "original")
            val setter = async(start = CoroutineStart.UNDISPATCHED) { sdk.setAttributes(values) }
            values["city"] = "changed-after-call"
            values["added-after-call"] = "unexpected"
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { catalog.await() } is InappifyResult.Success)
            assertTrue(withTimeout(5_000) { setter.await() } is InappifyResult.Success)
            val sent = jsonObject(transport.requests.single { it.path == "attributes" }.jsonBody)
                .getAsJsonObject("attributes")
            assertEquals(setOf("city"), sent.keySet())
            assertEquals("original", sent.string("city"))
        }
    }
    @Test fun setAttributesMapRetainsCoalescedChangesAcrossOfflineRestart() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
            assertTrue(sdk.setAttributes(mapOf("city" to "old", "removed" to "old")) is InappifyResult.Failure)
            assertTrue(sdk.setAttributes(mapOf("city" to "new", "removed" to null)) is InappifyResult.Failure)
            val queue = jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes")
            assertEquals("new", queue.string("city"))
            assertTrue(queue.get("removed").isJsonNull)
        }
        transport.requests.clear()
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            assertTrue(transport.requests.isEmpty())
            installHandler()
            assertTrue(restarted.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Success)
            assertEquals(listOf("attributes", "offerings"), transport.requests.map { it.path })
            val sent = jsonObject(transport.requests.first().jsonBody).getAsJsonObject("attributes")
            assertEquals("new", sent.string("city"))
            assertTrue(sent.get("removed").isJsonNull)
            assertEquals(0, jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").size())
        }
    }
    @Test fun setAttributesMapRejectsInvalidBatchWithoutPartialWritesOrCacheChanges() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.refreshOfferings()
            val before = sdk.snapshot
            val saved = store.value
            transport.requests.clear()
            assertTrue(sdk.setAttributes(mapOf("city" to "Tehran", "invalid key" to "value"))
                is InappifyResult.Failure)
            assertSame(before, sdk.snapshot)
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun attributesCoalesceDeleteBatchAndNeverReturnServerValues() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.enqueueAttributes((1..101).associate { "key$it" to "initial" })
            sdk.enqueueAttributes(mapOf("key1" to "new", "key2" to null))
            assertTrue(sdk.flushQueuedAttributes() is InappifyResult.Success)
            val requests = transport.requests.filter { it.path == "attributes" }.map { jsonObject(it.jsonBody).getAsJsonObject("attributes") }
            assertEquals(listOf(50, 50, 1), requests.map { it.size() })
            assertEquals("new", requests.first().string("key1")); assertTrue(requests.first().get("key2").isJsonNull)
            assertNull(sdk.snapshot.customerInfo?.attributes)
            sdk.flushQueuedAttributes(); assertEquals(3, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun attributeTimeoutRetainsQueueAnd422QuarantinesRejectedBatch() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.enqueueAttributes(mapOf("campaign" to "summer"))
            transport.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
            assertTrue(sdk.flushQueuedAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").has("campaign"))
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"ATTRIBUTE_KEY_INVALID"}"""), 422) }
            assertTrue(sdk.flushQueuedAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("quarantine").has("campaign"))
            assertTrue(sdk.flushQueuedAttributes() is InappifyResult.Success)
        }
    }
    @Test fun getOfferingShortcutUsesExistingGoEndpointAndCachedSelections() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.requests.clear()
            val selected = sdk.getOffering("targeted") as InappifyResult.Success
            assertEquals("targeted", selected.data?.identifier)
            val request = transport.requests.single()
            assertEquals("offerings", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertEquals(setOf("appVersion", "sdkVersion", "country", "paywallSchemaVersion", "paywallRendererVersion"),
                jsonObject(request.jsonBody).keySet())
            val saved = store.value
            transport.requests.clear()
            assertEquals("default", (sdk.getOffering("default") as InappifyResult.Success).data?.identifier)
            assertNull((sdk.getOffering("missing") as InappifyResult.Success).data)
            val catalog = (sdk.getOfferings() as InappifyResult.Success).data
            assertEquals("targeted", catalog.current?.identifier)
            assertEquals("default", catalog.getCurrentOfferingForPlacement("onboarding")?.identifier)
            assertEquals("targeted", catalog.getCurrentOfferingForPlacement("settings")?.identifier)
            assertEquals("targeted", (sdk.getCurrentOffering() as InappifyResult.Success).data?.identifier)
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun exactOfferingFetchFailureDoesNotChangeCustomerOrPretendTheIdWasMissing() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot
            transport.requests.clear()
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.getOffering("special_offer") is InappifyResult.Failure)
            assertSame(before, sdk.snapshot)
            assertTrue(transport.requests.all { it.path == "offerings" })
        }
    }
    @Test fun offeringNavigationUsesRestoredCacheWithoutExtraRequests() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
        }
        transport.requests.clear()
        transport.handler = { error("Existing cached offering navigation must not require a network request.") }
        client().use { restarted ->
            assertTrue(restarted.configure(InappifyOptions("public-key")) is InappifyResult.Success)
            val saved = store.value
            assertEquals("default", (restarted.getOffering("default") as InappifyResult.Success).data?.identifier)
            val catalog = (restarted.getOfferings() as InappifyResult.Success).data
            assertEquals("targeted", catalog.current?.identifier)
            assertEquals("default", catalog.getCurrentOfferingForPlacement("onboarding")?.identifier)
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun offeringsUseServerPlacementThenCurrentThenDefault() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            fun selected(result: InappifyResult<InappifyOffering?>) = (result as InappifyResult.Success).data?.identifier
            assertEquals("default", selected(sdk.getCurrentOffering("onboarding")))
            assertEquals(1, transport.requests.count { it.path == "offerings" })
            transport.requests.clear()
            val saved = store.value
            assertEquals("targeted", selected(sdk.getCurrentOffering("settings")))
            assertEquals("targeted", selected(sdk.getCurrentOffering()))
            assertEquals("targeted", selected(sdk.getCurrentOffering("unknown")))
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
            val model = sdk.snapshot.offerings!!
            val roundTrip = InappifyDomainJsonCodec.parseOfferings(InappifyDomainJsonCodec.encodeOfferings(model))
            assertEquals(model, roundTrip)
            assertEquals("targeted", roundTrip.currentOfferingIdentifier)
            val context = InappifyOfferingEvaluationContext("IR", "android", "2.4.0")
            assertEquals("default", roundTrip.currentOffering(context, "onboarding")?.identifier)
            assertEquals("targeted", roundTrip.currentOffering(context, "settings")?.identifier)
            assertEquals("targeted", roundTrip.currentOffering(context)?.identifier)
        }
    }
    @Test fun manualTargetingSyncUpdatesCurrentAndPlacementSelectionsInTheExistingCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            fun selected(result: InappifyResult<InappifyOffering?>) = (result as InappifyResult.Success).data?.identifier
            assertEquals("targeted", selected(sdk.getCurrentOffering()))
            val original = store.value
            transport.requests.clear()
            val base = transport.handler
            transport.handler = { request ->
                if (request.path == "offerings") transport.response(jsonObject(
                    """{"status":true,"offerings":[{"identifier":"default","isDefault":true},{"identifier":"targeted"}],"currentOffering":"default","placements":{"onboarding":"targeted"}}"""))
                else base(request)
            }
            assertEquals("targeted", selected(sdk.getCurrentOffering()))
            assertEquals("default", selected(sdk.getCurrentOffering("onboarding")))
            assertSame(original, store.value)
            assertTrue(transport.requests.isEmpty())

            val synced = sdk.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Success
            assertEquals(listOf("offerings"), transport.requests.map { it.path })
            assertEquals("default", synced.data.current?.identifier)
            assertEquals("targeted", synced.data.getCurrentOfferingForPlacement("onboarding")?.identifier)
            assertNotSame(original, store.value)
            val saved = store.value
            val persisted = jsonObject(saved!!.customerInfoJson!!).getAsJsonObject("offerings")
            assertEquals("default", persisted.string("currentOffering"))
            assertEquals("targeted", persisted.getAsJsonObject("placements").string("onboarding"))
            transport.requests.clear()
            assertEquals("default", selected(sdk.getCurrentOffering()))
            assertEquals("targeted", selected(sdk.getCurrentOffering("onboarding")))
            assertEquals("default", selected(sdk.getCurrentOffering("unknown")))
            assertSame(saved, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }
    @Test fun invalidPlacementsCannotReplaceGoodOfferings() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val before = sdk.snapshot.offerings
            transport.handler = { transport.response(jsonObject("""{"status":true,"offerings":[],"placements":{"onboarding":123}}""")) }
            assertTrue(sdk.refreshOfferings() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.offerings)
        }
    }
    @Test fun invalidDiscountIsBusinessResultAndNotRetried() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot
            val saved = store.value
            val result = sdk.getDiscountedPaymentLinks("WRONG") as InappifyResult.Success
            assertEquals(false, result.data.isValid); assertEquals(2L, result.data.errorCode)
            assertEquals("{\"code\":\"WRONG\",\"paymentReturnVersion\":1}", transport.requests.last().jsonBody)
            assertEquals(1, transport.requests.count { it.path == "validateDiscountCode" })
            assertSame(before, sdk.snapshot)
            assertSame(saved, store.value)
        }
    }
    @Test fun discountedPaymentLinksKeepTheExistingResultAndCachedState() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            assertTrue(sdk.getOfferings() is InappifyResult.Success)
            val before = sdk.snapshot
            val saved = store.value
            transport.handler = { transport.response(jsonObject("""{"status":true,"data":{
                "is_valid":true,"error_code":0,"code":"SAVE20","discount_id":10,
                "discount_code_id":11,"percent":20,"message":"The discount code is valid.",
                "payment_links":[{"offering":"main","url":"https://pay.inappify.com/checkout/customer?code=SAVE20"}]}}""")) }
            val result = sdk.getDiscountedPaymentLinks(discountCode = "SAVE20") as InappifyResult.Success
            assertEquals(true, result.data.isValid)
            assertEquals(0L, result.data.errorCode)
            assertEquals("SAVE20", result.data.code)
            assertEquals(10L, result.data.discountId)
            assertEquals(11L, result.data.discountCodeId)
            assertEquals(20L, result.data.percent)
            assertEquals("main", result.data.paymentLinks!!.single().offering)
            assertEquals("https://pay.inappify.com/checkout/customer?code=SAVE20", result.data.paymentLinks!!.single().url)
            assertNull(result.data.offering)
            val request = transport.requests.last()
            assertEquals("validateDiscountCode", request.path)
            assertEquals("{\"code\":\"SAVE20\",\"paymentReturnVersion\":1}", request.jsonBody)
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertSame(before, sdk.snapshot)
            assertSame(before, result.snapshot)
            assertSame(saved, store.value)
        }
    }
    @Test fun discountDatabaseFailureIsRetryableFailureAndPreservesCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot
            val saved = store.value
            transport.handler = { transport.response(jsonObject("""{"status":false,
                "code":"DISCOUNT_LOOKUP_FAILED","message":"Discount payment links could not be loaded."}"""), 500) }
            val result = sdk.getDiscountedPaymentLinks("SAVE20") as InappifyResult.Failure
            assertEquals("DISCOUNT_LOOKUP_FAILED", result.error.details["serverCode"])
            assertEquals(500, result.error.details["httpStatus"])
            assertTrue(result.error.isRetryable)
            assertEquals(4, transport.requests.count { it.path == "validateDiscountCode" })
            assertSame(before, sdk.snapshot)
            assertSame(saved, store.value)
        }
    }
    @Test fun discountedPaymentLinksStillRejectUntrustedCheckoutUrls() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = sdk.snapshot
            val saved = store.value
            transport.handler = { transport.response(jsonObject("""{"status":true,"data":{
                "is_valid":true,"error_code":0,"payment_links":[{
                "offering":"main","url":"https://untrusted.example/checkout"}]}}""")) }
            val result = sdk.getDiscountedPaymentLinks("SAVE20") as InappifyResult.Failure
            assertEquals("UNTRUSTED_PAYMENT_URL", result.error.details["serverCode"])
            assertSame(before, sdk.snapshot)
            assertSame(saved, store.value)
        }
    }
    @Test fun directConsumableUsesSessionForCheckoutAndPendingDeliveries() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { request -> when (request.path) {
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[
                    {"identifier":"default","packages":[{"identifier":"coin-package","product":{"identifier":"coins"}}]}]}"""))
                "purchase" -> transport.response(jsonObject("""{"status":true,"data":{
                    "purchaseStatus":"NEEDTOPAY","url":"https://pay.inappify.com/checkout/fixture"}}"""))
                else -> base(request)
            } }
            assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            val purchase = sdk.purchase(InappifyPurchaseRequest("coins", "default", isCrypto = true,
                productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Success
            assertEquals(InappifyPurchaseStatus.NEEDTOPAY, purchase.data.purchaseStatus)
            assertNull(purchase.data.deliveryId)
            val checkout = transport.requests.single { it.path == "purchase" }
            assertEquals("Bearer go-session-token", checkout.headers["Authorization"])
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "attemptId", "paymentReturnVersion"),
                jsonObject(checkout.jsonBody).keySet())
            assertEquals(1, jsonObject(checkout.jsonBody).get("paymentReturnVersion").asInt)
            assertFalse(jsonObject(checkout.jsonBody).has("isCrypto"))
            assertEquals("coins", jsonObject(checkout.jsonBody).string("productIdentifier"))
            val before = transport.requests.size
            val confirmation = sdk.confirmDelivery(7) as InappifyResult.Failure
            assertEquals("DELIVERY_NOT_FOUND", confirmation.error.details["serverCode"])
            assertEquals(before, transport.requests.size)
            val sync = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(0, sync.data.discoveredCount)
            val pending = transport.requests.single { it.path == "consumable-deliveries/pending" }
            assertEquals(1L, jsonObject(pending.jsonBody).number("purchaseAttemptTrackingVersion"))
            assertEquals("Bearer go-session-token", pending.headers["Authorization"])
        }
    }
    @Test fun packageParamsKeepDirectCheckoutBodyAndNeedToPayResult() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { request -> when (request.path) {
                "offerings" -> transport.response(jsonObject("""{"status":true,"offerings":[
                    {"identifier":"first","packages":[{"identifier":"same-package","product":{
                        "identifier":"product","productType":"CONSUMABLE"}}]},
                    {"identifier":"default","packages":[{"identifier":"same-package","product":{
                        "identifier":"product","productType":"CONSUMABLE"}}]}]}"""))
                "purchase" -> transport.response(jsonObject("""{"status":true,"data":{
                    "purchaseStatus":"NEEDTOPAY","url":"https://pay.inappify.com/checkout/fixture"}}"""))
                else -> base(request)
            } }
            sdk.refreshOfferings()
            val selected = sdk.snapshot.offerings!!["default"]!!.availablePackages.single()
            val result = sdk.purchase(InappifyPurchaseParams.Builder(selected).idempotencyKey("direct-attempt")
                .paywallAttribution(8, 2).build()) as InappifyResult.Success
            assertEquals(InappifyPurchaseStatus.NEEDTOPAY, result.data.purchaseStatus)
            assertEquals("https://pay.inappify.com/checkout/fixture", result.data.url)
            assertNull(result.data.deliveryId)
            assertEquals("same-package", result.data.packageIdentifier)
            val request = transport.requests.single { it.path == "purchase" }
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            val body = jsonObject(request.jsonBody)
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "attemptId", "paymentReturnVersion", "paywallId", "paywallRevision"),
                body.keySet())
            assertEquals("direct-attempt", body.string("attemptId"))
            assertEquals("default", body.string("offeringIdentifier"))
            assertFalse(body.has("isCrypto"))
            assertEquals(8L, body.number("paywallId"))
            assertTrue(transport.requests.none { it.path.startsWith("store/") })
        }
    }

    @Test fun packageDirectCheckoutKeepsLegacyCatalogSupportAndUncertainCreationIsNotRetried() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { request -> if (request.path == "offerings") transport.response(jsonObject(
                CommerceCatalogFixture().offerings).apply { addProperty("status", true) }) else base(request) }
            sdk.refreshOfferings()
            val selected = sdk.snapshot.offerings!!["default"]!!.availablePackages.single()
            assertNull(selected.product!!.productType)
            transport.handler = { request -> if (request.path == "purchase")
                TransportResult.Failure(TransportFailureKind.NETWORK) else base(request) }
            val result = sdk.purchase(InappifyPurchaseParams.Builder(selected).build()) as InappifyResult.Failure
            assertEquals(true, result.error.details["outcomeMayHaveCommitted"])
            assertEquals(1, transport.requests.count { it.path == "purchase" })
        }
    }

    @Test fun cachedPackagesRestoreParentAndProductTypeWithoutChangingTheStoredDocument() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val base = transport.handler
            transport.handler = { request -> if (request.path == "offerings") transport.response(jsonObject(
                """{"status":true,"offerings":[{"identifier":"default","packages":[
                {"identifier":"monthly","product":{"identifier":"pro","productType":"SUBSCRIPTION"}}]}]}"""))
                else base(request) }
            sdk.refreshOfferings()
        }
        val before = store.value
        val persisted = jsonObject(before!!.customerInfoJson!!).getAsJsonObject("offerings")
            .getAsJsonArray("offerings")[0].asJsonObject.getAsJsonArray("packages")[0].asJsonObject
        assertFalse(persisted.has("offeringIdentifier"))
        assertEquals("SUBSCRIPTION", persisted.getAsJsonObject("product").string("productType"))
        client().use { sdk ->
            transport.requests.clear()
            transport.handler = { error("The restored session and catalog must not require HTTP") }
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject)) is InappifyResult.Success)
            val selected = (sdk.getOfferings() as InappifyResult.Success).data["default"]!!.availablePackages.single()
            assertEquals("default", selected.offeringIdentifier)
            assertEquals(InappifyProductType.SUBSCRIPTION, selected.product!!.productType)
            assertEquals("pro", InappifyPurchaseParams.Builder(selected).build().toRequest()!!.productIdentifier)
            assertSame(before, store.value)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun unboundPackageCannotFallBackToTheFirstOfferingOrMutateCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val before = store.value
            val requestCount = transport.requests.size
            val unbound = InappifyPackage("package", product = InappifyStoreProduct("product"))
            val result = sdk.purchase(InappifyPurchaseParams.Builder(unbound).build()) as InappifyResult.Failure
            assertEquals("PURCHASE_PACKAGE_CONTEXT_REQUIRED", result.error.details["serverCode"])
            assertEquals(requestCount, transport.requests.size)
            assertSame(before, store.value)
        }
    }

    @Test fun killSwitchKeepsVerifiedCacheAndBlocksNetwork() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.setNetworkEnabled(false)
            assertTrue(sdk.getCustomerInfo(InappifyCacheFetchPolicy.FETCH_CURRENT) is InappifyResult.Failure)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
            assertEquals(1, transport.requests.size)
        }
    }
}
