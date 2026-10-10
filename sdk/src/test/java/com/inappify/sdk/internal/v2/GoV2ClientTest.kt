package com.inappify.sdk.internal.v2

import com.google.gson.*
import com.inappify.sdk.*
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
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "isCrypto"), jsonObject(request.jsonBody).keySet())
        }
    }
    private val signing = SigningFixture()
    private val store = MemoryV2Store()
    private val transport = V2Transport()
    private var identity = signing.subject
    private fun client(storage: MemoryV2Store = store,
        countryResolver: suspend () -> String? = { null }): GoV2Client = GoV2Client(signing.config,
        GoApi(transport, { signing.now }, {}), storage,
        AppMetadataProvider { AppMetadata("com.example.mobile", "2.4.0", 20400) },
        { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
        countryResolver = countryResolver, commerceApi = GoApi(transport, { signing.now }, {}))
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
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "1234")) is InappifyResult.Success)
            val login = transport.requests.last { it.path == "login" }
            assertEquals("1234", jsonObject(login.jsonBody).string("appUserIdentifier"))
            assertEquals("1234", sdk.snapshot.appUserIdentifier)
        }
    }
    @Test fun configureUsesExactV2BodyAndCommitsSignedScope() = runBlocking {
        client().use { sdk ->
            assertNull(sdk.snapshot.forceVersion)
            configured(sdk)
            val request = transport.requests.single()
            assertEquals(setOf("appUserIdentifier", "identifierValue", "versionName", "versionCode", "sdkVersion", "country"),
                jsonObject(request.jsonBody).keySet())
            assertEquals(20400, jsonObject(request.jsonBody).number("versionCode"))
            assertEquals(BuildConfig.SDK_VERSION, jsonObject(request.jsonBody).string("sdkVersion"))
            assertEquals("IR", jsonObject(request.jsonBody).string("country"))
            assertTrue(sdk.isActiveEntitlement("pro")); assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
            assertNull(sdk.snapshot.forceVersion); assertFalse(sdk.hasForceUpdate)
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
            assertNull(sdk.snapshot.forceVersion)
            assertFalse(sdk.hasForceUpdate)
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
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.customerInfo)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
        }
    }
    @Test fun secureWriteFailureCannotPublishNewIdentity() = runBlocking {
        client().use { sdk ->
            configured(sdk); store.failSave = true
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Failure)
            assertEquals(signing.subject, sdk.snapshot.appUserIdentifier)
        }
    }
    @Test fun loginMergeFailureRetainsOldIdentityAndCache() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"CUSTOMER_MERGE_FAILED"}"""), 409) }
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Failure)
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
            assertTrue(sdk.login(InappifyLoginRequest("public-key", target)) is InappifyResult.Failure)
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
            assertTrue(sdk.login(InappifyLoginRequest("public-key", target)) is InappifyResult.Success)
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
            configured(sdk); sdk.refreshOfferings(); sdk.queueAttributes(mapOf("campaign" to "old"))
            assertTrue(sdk.login(InappifyLoginRequest("public-key", "customer_other_123456")) is InappifyResult.Success)
            assertNull(sdk.snapshot.offerings)
            val attributesBefore = transport.requests.count { it.path == "attributes" }
            sdk.flushAttributes()
            assertEquals(attributesBefore, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun offlineLogoutHidesIdentityAndPersistsPendingStateAcrossRestart() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            transport.handler = { TransportResult.Failure(TransportFailureKind.NETWORK) }
            assertTrue(sdk.logout() is InappifyResult.Failure)
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
            assertTrue(sdk.logout() is InappifyResult.Failure)
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
            assertTrue(sdk.logout() is InappifyResult.Success)
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
            assertTrue(sdk.logout() is InappifyResult.Failure)
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
            assertNull(restarted.snapshot.forceVersion)
            assertFalse(restarted.hasForceUpdate)
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
            val jobs = (1..20).map { async(start = CoroutineStart.UNDISPATCHED) { sdk.refreshCustomerInfo() } }
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
    @Test fun configureContextChangeInvalidatesOnlyOfferingsCache() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val customer = sdk.snapshot.customerInfo
            assertNotNull(sdk.snapshot.offerings)
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject, country = "TR", appVersion = "3.0.0")) is InappifyResult.Success)
            assertEquals(2, transport.requests.count { it.path == "configure" })
            val configureBody = jsonObject(transport.requests.last { it.path == "configure" }.jsonBody)
            assertEquals("TR", configureBody.string("country"))
            assertEquals(BuildConfig.SDK_VERSION, configureBody.string("sdkVersion"))
            assertEquals(customer, sdk.snapshot.customerInfo)
            assertNull(sdk.snapshot.offerings)
            sdk.refreshOfferings()
            val body = jsonObject(transport.requests.last().jsonBody)
            assertEquals("TR", body.string("country")); assertEquals("3.0.0", body.string("appVersion"))
        }
    }
    @Test fun lowercaseCountryIsNormalizedInConfigureAndSnapshot() = runBlocking {
        client().use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject, country = "tr")) is InappifyResult.Success)
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
    @Test fun explicitCountrySkipsIpLookup() = runBlocking {
        var lookups = 0
        client(countryResolver = { lookups++; "DE" }).use { sdk ->
            installHandler()
            assertTrue(sdk.configure(InappifyOptions("public-key", signing.subject, country = "TR")) is InappifyResult.Success)
            assertEquals("TR", jsonObject(transport.requests.last().jsonBody).string("country"))
            assertEquals(0, lookups)
        }
    }
    @Test fun cancellingOneCallerDoesNotCancelSharedRefresh() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            transport.handler = { entered.complete(Unit); release.await(); transport.response(signing.envelope()) }
            val first = async { sdk.refreshCustomerInfo() }; entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { sdk.refreshCustomerInfo() }
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
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
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
            val result = sdk.refreshCustomerInfo()
            assertTrue(result is InappifyResult.Failure)
            assertEquals(1, transport.requests.count { it.path == "configure" })
            assertEquals(1, transport.requests.count { it.path == "customerInfo" })
        }
    }
    @Test fun attributesCoalesceDeleteBatchAndNeverReturnServerValues() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            sdk.queueAttributes((1..101).associate { "key$it" to "initial" })
            sdk.queueAttributes(mapOf("key1" to "new", "key2" to null))
            assertTrue(sdk.flushAttributes() is InappifyResult.Success)
            val requests = transport.requests.filter { it.path == "attributes" }.map { jsonObject(it.jsonBody).getAsJsonObject("attributes") }
            assertEquals(listOf(50, 50, 1), requests.map { it.size() })
            assertEquals("new", requests.first().string("key1")); assertTrue(requests.first().get("key2").isJsonNull)
            assertNull(sdk.snapshot.customerInfo?.attributes)
            sdk.flushAttributes(); assertEquals(3, transport.requests.count { it.path == "attributes" })
        }
    }
    @Test fun attributeTimeoutRetainsQueueAnd422QuarantinesRejectedBatch() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.queueAttributes(mapOf("campaign" to "summer"))
            transport.handler = { TransportResult.Failure(TransportFailureKind.TIMEOUT) }
            assertTrue(sdk.flushAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").has("campaign"))
            transport.handler = { transport.response(jsonObject("""{"status":false,"code":"ATTRIBUTE_KEY_INVALID"}"""), 422) }
            assertTrue(sdk.flushAttributes() is InappifyResult.Failure)
            assertTrue(jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("quarantine").has("campaign"))
            assertTrue(sdk.flushAttributes() is InappifyResult.Success)
        }
    }
    @Test fun offeringsUseServerPlacementThenCurrentThenDefault() = runBlocking {
        client().use { sdk ->
            configured(sdk); assertTrue(sdk.refreshOfferings() is InappifyResult.Success)
            fun selected(result: InappifyResult<InappifyOffering?>) = (result as InappifyResult.Success).data?.identifier
            assertEquals("default", selected(sdk.getCurrentOffering("onboarding", false, null)))
            assertEquals("targeted", selected(sdk.getCurrentOffering("settings", false, null)))
            assertEquals("targeted", selected(sdk.getCurrentOffering(null, false, null)))
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
    @Test fun invalidPlacementsCannotReplaceGoodOfferings() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.refreshOfferings()
            val before = sdk.snapshot.offerings
            transport.handler = { transport.response(jsonObject("""{"status":true,"offerings":[],"placements":{"onboarding":123}}""")) }
            assertTrue(sdk.refreshOfferings() is InappifyResult.Failure)
            assertEquals(before, sdk.snapshot.offerings)
            assertNull(sdk.snapshot.forceVersion)
        }
    }
    @Test fun invalidDiscountIsBusinessResultAndNotRetried() = runBlocking {
        client().use { sdk ->
            configured(sdk)
            val result = sdk.validateDiscountCode(InappifyDiscountCodeRequest("WRONG")) as InappifyResult.Success
            assertEquals(false, result.data.isValid); assertEquals(2L, result.data.errorCode)
            assertEquals("{\"code\":\"WRONG\"}", transport.requests.last().jsonBody)
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
            val purchase = sdk.purchase(InappifyPurchaseRequest("coins", "default",
                productType = InappifyProductType.CONSUMABLE)) as InappifyResult.Success
            assertEquals(InappifyPurchaseStatus.NEEDTOPAY, purchase.data.purchaseStatus)
            assertNull(purchase.data.deliveryId)
            val checkout = transport.requests.single { it.path == "purchase" }
            assertEquals("Bearer go-session-token", checkout.headers["Authorization"])
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "isCrypto"),
                jsonObject(checkout.jsonBody).keySet())
            assertEquals("coins", jsonObject(checkout.jsonBody).string("productIdentifier"))
            val before = transport.requests.size
            val confirmation = sdk.confirmDelivery(7) as InappifyResult.Failure
            assertEquals("DELIVERY_NOT_FOUND", confirmation.error.details["serverCode"])
            assertEquals(before, transport.requests.size)
            val sync = sdk.syncPendingConsumables() as InappifyResult.Success
            assertEquals(0, sync.data.discoveredCount)
            val pending = transport.requests.single { it.path == "consumable-deliveries/pending" }
            assertEquals("{}", pending.jsonBody)
            assertEquals("Bearer go-session-token", pending.headers["Authorization"])
        }
    }
    @Test fun killSwitchKeepsVerifiedCacheAndBlocksNetwork() = runBlocking {
        client().use { sdk ->
            configured(sdk); sdk.setNetworkEnabled(false)
            assertTrue(sdk.refreshCustomerInfo() is InappifyResult.Failure)
            assertTrue(sdk.customerInfo(InappifyFetchPolicy.CACHE_ONLY) is InappifyResult.Success)
            assertEquals(1, transport.requests.size)
        }
    }
}
