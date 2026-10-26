package com.inappify.sdk.internal.v2

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import com.inappify.sdk.internal.InappifyLogger
import com.google.gson.*
import com.inappify.sdk.*
import com.inappify.sdk.internal.domain.InappifyDomainJsonCodec
import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.StoreV2Context
import com.inappify.sdk.internal.StoreV2Coordinator
import com.inappify.sdk.internal.StoreV2Outcome
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.platform.*
import com.inappify.sdk.internal.network.OkHttpTransport
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Serial state owner. Shared requests belong to the client, never to one caller's Job. */
internal class GoV2Client(
    private val config: GoV2Environment,
    private val sdkApi: GoApi,
    private val storage: SessionStateStore,
    private val metadata: AppMetadataProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val backgroundRecovery: Boolean = true,
    private val countryResolver: suspend () -> String? = { null },
    private val commerceApi: GoApi? = null,
    private val billingFactory: StoreBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
        UnsupportedStoreBillingAdapter(StoreBillingError(StoreBillingErrorCode.UNSUPPORTED_MARKET,
            "Native store billing is unavailable."))
    },
    private val targetingSyncLimiter: TargetingSyncRateLimiter = TargetingSyncRateLimiter(),
    private val subscriptionRecoveryEnabled: Boolean = true,
    private val foregroundMonitor: AppForegroundMonitor = AlwaysForegroundMonitor(),
    private val offeringsRefreshPolicy: OfferingsRefreshPolicy = OfferingsRefreshPolicy(),
    private val connectivityMonitor: NetworkConnectivityMonitor = AlwaysConnectedMonitor(),
    private val recoveryRefreshPolicy: RecoveryRefreshPolicy = RecoveryRefreshPolicy(),
    private val paymentLinkMonitor: PaymentLinkMonitor = NoPaymentLinkMonitor(),
) : InappifyV2Client, StoreV2CapableClient, ConsumableFulfillmentCapableClient, HttpDiagnosticsCapableClient,
    InappifyV2RuntimeCapabilities, TargetingSyncCapableClient, PurchaseAttemptCapableClient {
    constructor(config: InappifyV2Configuration, sdkApi: GoApi, storage: SessionStateStore,
        metadata: AppMetadataProvider, now: () -> Long = System::currentTimeMillis,
        eventDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate, backgroundRecovery: Boolean = true,
        countryResolver: suspend () -> String? = { null }, commerceApi: GoApi? = null,
        billingFactory: StoreBillingAdapterFactory = StoreBillingAdapterFactory { _, _ ->
            UnsupportedStoreBillingAdapter(StoreBillingError(StoreBillingErrorCode.UNSUPPORTED_MARKET,
                "Native store billing is unavailable."))
        }, targetingSyncLimiter: TargetingSyncRateLimiter = TargetingSyncRateLimiter(),
        subscriptionRecoveryEnabled: Boolean = true,
        foregroundMonitor: AppForegroundMonitor = AlwaysForegroundMonitor(),
        offeringsRefreshPolicy: OfferingsRefreshPolicy = OfferingsRefreshPolicy(),
        connectivityMonitor: NetworkConnectivityMonitor = AlwaysConnectedMonitor(),
        recoveryRefreshPolicy: RecoveryRefreshPolicy = RecoveryRefreshPolicy(),
        paymentLinkMonitor: PaymentLinkMonitor = NoPaymentLinkMonitor()) :
        this(GoV2Environment.explicit(config), sdkApi, storage, metadata, now, eventDispatcher, backgroundRecovery,
            countryResolver, commerceApi, billingFactory, targetingSyncLimiter, subscriptionRecoveryEnabled,
            foregroundMonitor, offeringsRefreshPolicy, connectivityMonitor, recoveryRefreshPolicy, paymentLinkMonitor)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val flights = mutableMapOf<String, Deferred<*>>()
    @Volatile private var recoveryGeneration = 0L
    private var recoveryIdentity: String? = null
    private var recoverySubject: String? = null
    private val recoveryCoordinator = RecoveryCoordinator(scope, { recoveryGeneration }, ::recoverOnce)
    private class PendingReturn(val link: PaymentReturnLink, val preceding: RecoveryCoordinator.Flight?) {
        var following: RecoveryCoordinator.Flight? = null
    }
    private val paymentReturnLock = Any()
    private val pendingReturns = linkedMapOf<PaymentReturnLink, PendingReturn>()
    private val handledReturns = linkedSetOf<PaymentReturnLink>()
    private val paymentReturnWakeups = Channel<Unit>(Channel.CONFLATED)
    private val listeners = CopyOnWriteArraySet<InappifyEventListener>()
    private var trustedTimeFloor = 0L
    private var timeAnchorNanos = System.nanoTime()
    private val verifier = CustomerInfoVerifier(config, ::trustedNow)
    private var options: InappifyOptions? = null
    private var document = JsonObject()
    private var deliveryHandler: InappifyConsumableDeliveryHandler? = null
    private val attemptHistory = PurchaseAttemptHistory(storage as? PurchaseAttemptStorage)
    private var renewalFailure: V2Failure? = null
    private var nextRenewalAt = 0L
    private var detectedCountry: String? = null
    private val lifecycleLock = Any()
    private val activeOperationJobs = mutableSetOf<Job>()
    private val activeBillingAdapters = mutableSetOf<StoreBillingAdapter>()
    private val storeCoordinator = commerceApi?.let {
        StoreV2Coordinator(GoV2StoreService(::commerceRequest), storage, billingFactory, now,
            registerActiveAdapter = ::registerBillingAdapter,
            unregisterActiveAdapter = { adapter -> synchronized(lifecycleLock) { activeBillingAdapters.remove(adapter) }; Unit },
            beforeTerminal = { operation, state ->
                try { recordStoreAttempt(StoreV2Outcome.Terminal(operation, state), emit = false); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            })
    }
    @Volatile private var closed = false
    @Volatile private var currentSnapshot = InappifySnapshot.initial(BuildConfig.SDK_VERSION)
    @Volatile private var pendingLogout = false
    @Volatile private var pendingLogin = false
    @Volatile private var authenticatedScope: InappifyV2SessionScope? = null
    override val snapshot: InappifySnapshot get() = currentSnapshot

    init {
        scope.launch {
            for (ignored in paymentReturnWakeups) {
                try { recoverPaymentReturns() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the hint for the next lifecycle/network trigger. */ }
            }
        }
        paymentLinkMonitor.setListener { handleDeepLink(it); Unit }
        foregroundMonitor.setListener { foreground -> if (foreground) {
            scheduleRecoveryIfNeeded()
            scheduleOfferingsRefresh()
            scheduleCustomerInfoRefresh()
        } }
        connectivityMonitor.setListener { connected -> if (connected) scheduleRecoveryIfNeeded() }
    }
    override val isLogoutPending: Boolean get() = pendingLogout
    override val verifiedScope: InappifyV2SessionScope?
        get() = authenticatedScope.takeIf { snapshot.isConfigured && !pendingLogout && !pendingLogin }
    override fun allowsPaymentUrl(url: String): Boolean = allowedUrl(url, config.paymentHosts)

    private suspend fun <T> run(action: suspend () -> T): InappifyResult<T> = coroutineScope {
        // Own only this child scope, never the host's parent Job. Closing the client
        // cancels native operations before an adapter-close callback can be mistaken
        // for a permanent purchase/consume failure.
        val operationJob = currentCoroutineContext().job
        synchronized(lifecycleLock) {
            check(!closed) { "The Inappify client is closed." }
            activeOperationJobs.add(operationJob)
        }
        try {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                try {
                    // A queued stage from a previous identity must not run against the new session.
                    currentCoroutineContext()[RecoveryPass]?.let {
                        if (it.generation != recoveryGeneration) fail("AUTH", "RECOVERY_SUPERSEDED")
                    }
                    InappifyResult.Success(action(), snapshot)
                }
                catch (e: CancellationException) { throw e }
                catch (e: V2Failure) {
                    InappifyLogger.log(InappifyLogLevel.ERROR) { "SDK operation failed (${e.sdkError.code.name})." }
                    InappifyResult.Failure(e.sdkError, snapshot)
                }
                catch (_: Exception) { InappifyResult.Failure(InappifyError(
                    InappifyErrorCode.MALFORMED_RESPONSE, "Inappify v2 returned an invalid response."), snapshot) }
            }
        } finally { synchronized(lifecycleLock) { activeOperationJobs.remove(operationJob) } }
    }

    private fun registerBillingAdapter(adapter: StoreBillingAdapter): Boolean = synchronized(lifecycleLock) {
        !closed && activeBillingAdapters.add(adapter)
    }
    private fun createBillingAdapter(marketKey: String?, market: InappifyMarket): StoreBillingAdapter {
        val adapter = billingFactory.create(market, marketKey)
        if (!registerBillingAdapter(adapter)) {
            runCatching { adapter.close() }
            throw CancellationException("The Inappify client is closed.")
        }
        return adapter
    }
    private fun releaseBillingAdapter(adapter: StoreBillingAdapter) {
        val owned = synchronized(lifecycleLock) { activeBillingAdapters.remove(adapter) }
        if (owned) runCatching { adapter.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, action: suspend () -> InappifyResult<T>): InappifyResult<T> {
        check(!closed)
        val recoveryPass = currentCoroutineContext()[RecoveryPass]
        if (recoveryPass != null && recoveryPass.generation != recoveryGeneration)
            return run { fail("AUTH", "RECOVERY_SUPERSEDED") }
        val job = synchronized(flights) {
            val existing = flights[key]
            if (existing != null && !existing.isCompleted) existing as Deferred<InappifyResult<T>>
            else scope.async(recoveryPass ?: EmptyCoroutineContext, start = CoroutineStart.LAZY) { action() }.also { fresh ->
                flights[key] = fresh
                fresh.invokeOnCompletion { synchronized(flights) { if (flights[key] === fresh) flights.remove(key) } }
                fresh.start()
            }
        }
        return job.await()
    }

    override suspend fun configure(options: InappifyOptions): InappifyResult<Unit> =
        shared("configure:${fingerprint(options)}:${options.appUserIdentifier}") { run {
            InappifyLogger.log(InappifyLogLevel.DEBUG) { "Configuring SDK session." }
            validateOptions(options)
            if (this.options != null && fingerprint(this.options!!) != fingerprint(options))
                fail("CONFIGURATION", "CREATE_NEW_CLIENT_FOR_APP_CHANGE")
            this.options = options
            if (document.size() == 0) {
                val stored = storage.load()
                if (stored != null && stored.customerInfoJson != null && cacheMatches(stored, options)) {
                    val cacheEnvelope = runCatching { jsonObject(stored.customerInfoJson) }.getOrNull()
                    val cachedKid = runCatching {
                        cacheEnvelope?.getAsJsonObject("session")?.getAsJsonObject("verification")?.string("keyId")
                    }.getOrNull()
                    if (cachedKid != null && !verifier.known(cachedKid)) {
                        pendingLogout = cacheEnvelope?.get("pendingLogout")?.asBoolean == true
                        verifier.refresh(sdkApi.request("public-keys", null))
                    }
                    val saved = verifiedCache(stored.customerInfoJson, null)
                    if (saved != null) {
                        document = saved
                        // Keep verified app scope even when Configure requests another customer,
                        // without first publishing that customer's predecessor into the UI.
                        val cachedIdentity = saved.getAsJsonObject("session")?.string("appUserId")
                        if (saved.has("pendingLogin") || options.appUserIdentifier == null ||
                            cachedIdentity == options.appUserIdentifier) publish()
                        else pendingLogout = saved.get("pendingLogout")?.asBoolean == true
                    } else if (stored.apiKeyFingerprint == fingerprint(options)) {
                        // A stale/unknown signing kid must not rotate the locally persisted UUID.
                        // This does not accept a session, token, tenant scope or entitlements.
                        val cached = runCatching { jsonObject(stored.customerInfoJson) }.getOrNull()
                        if (cached?.get("pendingLogout")?.asBoolean == true) {
                            pendingLogout = true
                            fail("SIGNATURE", "INVALID_PENDING_LOGOUT_CACHE")
                        }
                        if (cached?.has("pendingLogin") == true)
                            fail("SIGNATURE", "INVALID_PENDING_LOGIN_CACHE")
                        runCatching { cached?.get("anonymousId")?.asString }.getOrNull()
                            ?.takeIf(::isAnonymous)?.let { id ->
                                document = JsonObject().apply {
                                    addProperty("anonymousId", id)
                                    // A local saved scope may restrict recovery, but can never grant
                                    // trust: the network reply still needs a valid pinned signature.
                                    cached?.getAsJsonObject("scope")?.let { add("scope", it.deepCopy()) }
                                }
                            }
                    }
                }
            }
            if (pendingLogout) { logoutLocked(); return@run }
            if (pendingLogin) {
                val target = document.getAsJsonObject("pendingLogin").string("appUserId")
                if (options.appUserIdentifier != null && options.appUserIdentifier != target)
                    fail("AUTH", "LOGIN_PENDING")
                resumeLogin()
                scheduleRecovery()
                return@run
            }
            val session = document.getAsJsonObject("session")
            val country = normalizeCountry(document.get("country")?.asString ?: "IR")
            val appVersion = metadata.get().versionName
            val contextChanged = session != null &&
                (document.get("country")?.asString != country || document.get("appVersion")?.asString != appVersion)
            val reusesIdentity = session != null &&
                (options.appUserIdentifier == null || options.appUserIdentifier == session.string("appUserId"))
            if (contextChanged && reusesIdentity && document.has("offerings")) {
                // A network failure must not leave products targeted at the previous
                // country/version visible. Retain the old context until a signed
                // Configure succeeds, so the next Configure still retries the change.
                commit(document.deepCopy().apply { remove("offerings") })
            }
            if (session != null && reusesIdentity &&
                !contextChanged && document.get("sessionInvalid")?.asBoolean != true &&
                isoMillis(session.string("sessionExpiresAt")) > now()) {
                if (!snapshot.isConfigured || snapshot.appUserIdentifier != session.string("appUserId")) publish()
                scheduleRecovery(); return@run
            }
            val identity = options.appUserIdentifier ?: session?.string("appUserId")
                ?: document.get("anonymousId")?.asString ?: anonymousId().also { id ->
                    commit(document.deepCopy().apply { addProperty("anonymousId", id) })
                }
            configureLocked(identity)
            scheduleRecovery()
        } }

    /** Invalid cache is never published, but must not permanently prevent online recovery. */
    private fun verifiedCache(raw: String, requestedIdentity: String?): JsonObject? = try {
        val saved = jsonObject(raw)
        val session = saved.getAsJsonObject("session")
        if (session == null) {
            if (saved.get("anonymousId")?.asString?.let(::isAnonymous) != true) null else saved
        } else if (requestedIdentity != null && requestedIdentity != session.string("appUserId")) null
        else {
            if (session.string("sessionType") != "public")
                fail("SIGNATURE", "SCOPE_MISMATCH")
            session.number("storePlatform"); isoMillis(session.string("sessionExpiresAt"))
            if (saved.get("sessionInvalid")?.asBoolean != true && session.string("sessionToken").isBlank())
                fail("SIGNATURE", "INVALID_SESSION")
            val verified = verifier.verifyWithScope(session, session.string("appUserId"), allowExpiredCache = true)
            if (session.number("appId") != verified.scope.appId ||
                (saved.has("scope") && saved.getAsJsonObject("scope").sessionScope() != verified.scope))
                fail("SIGNATURE", "SCOPE_MISMATCH")
            if (saved.has("customerSubject") && saved.string("customerSubject") != verified.subject)
                verificationMismatch("sub", "stored binding must match signed session", saved.get("customerSubject"),
                    JsonPrimitive(verified.subject), "signedSession")
            val info = verifier.verifyWithScope(saved.getAsJsonObject("info") ?: session,
                session.string("appUserId"), verified.scope, allowExpiredCache = true, expectedSubject = verified.subject).info
            InappifyDomainJsonCodec.parseCustomerInfo(info.toString())
            saved.getAsJsonObject("offerings")?.let { InappifyDomainJsonCodec.parseOfferings(it.toString()) }
            saved.add("scope", verified.scope.toJson())
            // Older Go caches have no separate binding; derive it only from verified signed data.
            saved.addProperty("customerSubject", verified.subject)
            saved.getAsJsonObject("session")?.remove("forceVersion")
            saved.getAsJsonObject("info")?.remove("forceVersion")
            saved.getAsJsonObject("offerings")?.apply {
                remove("forceVersion"); remove("hasForceUpdate")
            }
            saved
        }
    } catch (_: Exception) { null }

    /** The explicit Go configuration's older cache is reusable only after authenticating its scope. */
    private fun cacheMatches(stored: PersistedSession, options: InappifyOptions): Boolean {
        if (stored.apiKeyFingerprint == fingerprint(options)) return true
        if (config.expectedScope != null) return false
        return runCatching {
            val session = jsonObject(stored.customerInfoJson!!).getAsJsonObject("session") ?: return false
            val verified = verifier.verifyWithScope(session, session.string("appUserId"), allowExpiredCache = true)
            stored.apiKeyFingerprint == explicitFingerprint(options, verified.scope)
        }.getOrDefault(false)
    }

    private fun boundScope(state: JsonObject = document): InappifyV2SessionScope? =
        state.getAsJsonObject("scope")?.sessionScope() ?: config.expectedScope

    private fun boundSubject(): String? = document.get("customerSubject")?.asString

    private suspend fun configureLocked(identity: String, preservePendingLogin: Boolean = false,
        preservePendingLogout: Boolean = pendingLogout, retry: Boolean = true) {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val app = metadata.get()
        val country = document.get("country")?.asString ?: resolveCountry()
        val response = sdkApi.request("configure", opt.apiKey, JsonObject().apply {
            addProperty("appUserIdentifier", identity)
            addProperty("identifierValue", app.packageIdentifier)
            addProperty("versionName", app.versionName)
            addProperty("versionCode", app.versionCode)
            addProperty("sdkVersion", BuildConfig.SDK_VERSION)
            addProperty("country", normalizeCountry(country))
        }, retry = retry)
        verifiedResponse("configure", mayHaveCommitted = true) {
            acceptSession(response, identity, keepLogoutPending = preservePendingLogout,
                keepLoginPending = preservePendingLogin, configuredCountry = country)
        }
    }

    private suspend fun resolveCountry(): String {
        detectedCountry?.let { return it }
        val country = try { countryResolver() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        return (country?.takeIf(::validCountry)?.let(::normalizeCountry) ?: "IR")
            .also { detectedCountry = it }
    }

    private suspend fun verifyResponse(response: JsonObject, identity: String,
        expectedSubject: String? = boundSubject()): VerifiedCustomerInfo {
        val verification = response.getAsJsonObject("verification") ?: fail("SIGNATURE", "MISSING_VERIFICATION")
        val kid = verification.string("keyId")
        if (!verifier.known(kid)) verifier.refresh(sdkApi.request("public-keys", null))
        return verifier.verifyWithScope(response, identity, boundScope(), expectedSubject = expectedSubject)
    }

    private suspend fun verify(response: JsonObject, identity: String): JsonObject = verifyResponse(response, identity).info

    private suspend fun acceptSession(response: JsonObject, expectedIdentity: String?, keepLogoutPending: Boolean = false,
        identityTransition: Boolean = false, configuredCountry: String? = null,
        keepLoginPending: Boolean = false) {
        val identity = response.string("appUserId")
        if (expectedIdentity != null && identity != expectedIdentity)
            verificationMismatch("appUserId", "exact requested app-user match", response.get("appUserId"),
                JsonPrimitive(expectedIdentity), "request", "IDENTITY_MISMATCH", signatureVerified = false)
        if (expectedIdentity == null && !isAnonymous(identity)) fail("SIGNATURE", "EXPECTED_ANONYMOUS")
        if (response.number("appId") <= 0 || response.string("sessionType") != "public" ||
            response.string("sessionToken").isBlank() || isoMillis(response.string("sessionExpiresAt")) <= now())
            fail("SIGNATURE", "INVALID_SESSION")
        response.number("storePlatform")
        response.get("storeInfo")?.takeUnless { it.isJsonNull }?.let { response.string("storeInfo") }
        val identityChanged = document.getAsJsonObject("session")?.string("appUserId") != identity
        // Only a verified lifecycle identity transition may replace the internal customer binding.
        // Ordinary Configure reuse/renewal and resource refresh must retain the current subject.
        val verified = verifyResponse(response, identity,
            expectedSubject = if (identityChanged || identityTransition) null else boundSubject())
        if (response.number("appId") != verified.scope.appId)
            verificationMismatch("appId", "must equal signed app_id", response.get("appId"),
                JsonPrimitive(verified.scope.appId), "signedCustomerInfo")
        InappifyDomainJsonCodec.parseCustomerInfo(verified.info.toString())
        val bindingChanged = identityChanged || boundSubject() != verified.subject
        val next = if (bindingChanged) JsonObject() else document.deepCopy()
        next.add("scope", verified.scope.toJson())
        next.addProperty("customerSubject", verified.subject)
        val v2Response = response.deepCopy().apply { remove("forceVersion"); remove("hasForceUpdate") }
        next.add("session", v2Response.deepCopy())
        next.add("info", v2Response)
        if (keepLoginPending) document.getAsJsonObject("pendingLogin")?.let { next.add("pendingLogin", it.deepCopy()) }
        else next.remove("pendingLogin")
        next.addProperty("infoFetchedAt", now())
        next.addProperty("country", normalizeCountry(configuredCountry ?: document.get("country")?.asString ?: "IR"))
        next.addProperty("appVersion", metadata.get().versionName)
        if (keepLogoutPending) {
            next.addProperty("pendingLogout", true)
            document.getAsJsonObject("pendingLogoutAttempt")?.let {
                next.add("pendingLogoutAttempt", it.deepCopy())
            }
        } else {
            next.remove("pendingLogout")
            next.remove("pendingLogoutAttempt")
        }
        next.remove("sessionInvalid")
        // V2 has no forceVersion. A new session may reflect changed offerings.
        next.remove("offerings")
        if (isAnonymous(identity)) next.addProperty("anonymousId", identity)
        commit(next)
    }

    private suspend fun sessionRequest(endpoint: String, body: JsonObject = JsonObject(),
        retry: Boolean = true, allowLogout: Boolean = false): JsonObject {
        if (pendingLogout && !allowLogout) fail("AUTH", "LOGOUT_PENDING")
        if (pendingLogin) fail("AUTH", "LOGIN_PENDING")
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"))
            session = document.getAsJsonObject("session")
        }
        return try { sdkApi.request(endpoint, session.string("sessionToken"), body, retry) }
        catch (e: V2Failure) {
            if (e.sdkError.details["httpStatus"] != 401 || e.sdkError.details["serverCode"] != "SESSION_EXPIRED") throw e
            // The state mutex makes reconfiguration single-flight for all resource operations.
            renewSession(session.string("appUserId"), retry = false)
            sdkApi.request(endpoint, document.getAsJsonObject("session").string("sessionToken"), body, retry)
        }
    }

    private suspend fun renewSession(identity: String, retry: Boolean = true) {
        if (now() < nextRenewalAt) renewalFailure?.let { throw it }
        try {
            configureLocked(identity, retry = retry)
            renewalFailure = null; nextRenewalAt = 0
        } catch (e: V2Failure) {
            renewalFailure = e; nextRenewalAt = now() + 15000
            throw e
        }
    }

    override suspend fun logIn(appUserIdentifier: String): InappifyResult<Unit> = run {
        if (!validIdentity(appUserIdentifier) || isAnonymous(appUserIdentifier))
            fail("VALIDATION", "APP_USER_ID_INVALID")
        if (pendingLogout) fail("AUTH", "LOGOUT_PENDING")
        if (!sdkApi.enabled) fail("CONFIGURATION", "V2_DISABLED")
        val pending = document.getAsJsonObject("pendingLogin")
        if (pending != null) {
            if (pending.string("appUserId") != appUserIdentifier) fail("AUTH", "LOGIN_PENDING")
        } else {
            val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
            if (document.get("sessionInvalid")?.asBoolean == true ||
                isoMillis(session.string("sessionExpiresAt")) <= now()) {
                renewSession(session.string("appUserId"))
            }
            commit(document.deepCopy().apply {
                add("pendingLogin", JsonObject().apply {
                    addProperty("appUserId", appUserIdentifier)
                    addProperty("attemptId", UUID.randomUUID().toString())
                })
            })
        }
        resumeLogin()
        scheduleRecovery()
    }
    private suspend fun resumeLogin(allowFreshPredecessor: Boolean = true) {
        val pending = document.getAsJsonObject("pendingLogin") ?: fail("CONFIGURATION", "LOGIN_NOT_PENDING")
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val target = pending.string("appUserId")
        val attemptId = pending.string("attemptId")
        val response = try {
            sdkApi.request("login", session.string("sessionToken"), JsonObject().apply {
                addProperty("appUserIdentifier", target)
            }, headers = mapOf("X-Inappify-Login-Attempt" to attemptId))
        } catch (e: V2Failure) {
            if (allowFreshPredecessor && e.sdkError.details["serverCode"] == "LOGIN_ATTEMPT_NOT_FOUND") {
                // Go proved no transition committed for this signed predecessor.
                // Configure the same old identity, preserving the attempt atomically,
                // then submit it from the newly issued predecessor session.
                configureLocked(session.string("appUserId"), preservePendingLogin = true)
                return resumeLogin(allowFreshPredecessor = false)
            }
            // These responses are defined before commit, so the predecessor remains usable.
            if (e.sdkError.details["serverCode"] in setOf("APP_USER_ID_INVALID", "CUSTOMER_MERGE_FAILED"))
                commit(document.deepCopy().apply { remove("pendingLogin") })
            throw e
        }
        verifiedResponse("login", mayHaveCommitted = true) {
            acceptSession(response, target, identityTransition = true)
        }
    }
    override suspend fun logOut(): InappifyResult<Unit> = run { logoutLocked() }
    private suspend fun logoutLocked(allowFreshPredecessor: Boolean = true) {
        if (pendingLogin) fail("AUTH", "LOGIN_PENDING")
        if (!sdkApi.enabled) fail("CONFIGURATION", "V2_DISABLED")
        if (!pendingLogout) {
            val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
            if (document.get("sessionInvalid")?.asBoolean == true ||
                isoMillis(session.string("sessionExpiresAt")) <= now()) {
                renewSession(session.string("appUserId"))
            }
            commit(document.deepCopy().apply {
                addProperty("pendingLogout", true)
                add("pendingLogoutAttempt", JsonObject().apply {
                    addProperty("attemptId", UUID.randomUUID().toString() + UUID.randomUUID().toString())
                })
            })
        }
        // V2.0/V2.1 persisted the logout barrier without an attempt ID. Upgrade
        // that record before HTTP; revoked predecessors still recover anonymously.
        if (!document.has("pendingLogoutAttempt")) commit(document.deepCopy().apply {
            add("pendingLogoutAttempt", JsonObject().apply {
                addProperty("attemptId", UUID.randomUUID().toString() + UUID.randomUUID().toString())
            })
        })
        val attempt = document.getAsJsonObject("pendingLogoutAttempt")
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true && !session.has("sessionToken")) {
            // Older logout recovery may have removed a rejected token before a
            // renewal failed. Renew only this missing-token case; otherwise replay
            // the original predecessor so an existing attempt remains recoverable.
            configureLocked(session.string("appUserId"))
            session = document.getAsJsonObject("session")
        }
        val response = try {
            sdkApi.request("logout", session.string("sessionToken"), retry = false,
                headers = mapOf("X-Inappify-Logout-Attempt" to attempt.string("attemptId")))
        } catch (e: V2Failure) {
            if (allowFreshPredecessor && e.sdkError.details["serverCode"] == "LOGOUT_ATTEMPT_NOT_FOUND") {
                configureLocked(session.string("appUserId"))
                try {
                    return logoutLocked(allowFreshPredecessor = false)
                } catch (replayFailure: V2Failure) {
                    if (replayFailure.sdkError.details["serverCode"] !in setOf("SESSION_REVOKED", "SESSION_EXPIRED"))
                        throw replayFailure
                    configureLocked(anonymousId(), preservePendingLogout = false)
                    scheduleRecovery()
                    return
                }
            }
            if (e.sdkError.details["serverCode"] in setOf("SESSION_REVOKED", "SESSION_EXPIRED")) {
                // The predecessor cannot grant access. A signed fresh anonymous
                // Configure completes local logout if replay has become impossible.
                configureLocked(anonymousId(), preservePendingLogout = false)
                scheduleRecovery()
                return
            }
            throw e
        }
        verifiedResponse("logout", mayHaveCommitted = true) {
            acceptSession(response, null, identityTransition = true)
        }
        scheduleRecovery()
    }

    override suspend fun getCustomerInfo(fetchPolicy: InappifyCacheFetchPolicy): InappifyResult<InappifyCustomerInfo> {
        val cached = snapshot.customerInfo
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Reading CustomerInfo with ${fetchPolicy.name}; cache ${if (cached == null) "absent" else "available"}." }
        return when (fetchPolicy) {
            InappifyCacheFetchPolicy.CACHE_ONLY -> customerInfo(InappifyFetchPolicy.CACHE_ONLY)
            InappifyCacheFetchPolicy.FETCH_CURRENT -> refreshCustomerInfo()
            InappifyCacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT ->
                if (cached != null && !isCustomerInfoStale()) InappifyResult.Success(cached, snapshot)
                else fetchCustomerInfo(onlyIfStale = true)
            InappifyCacheFetchPolicy.CACHED_OR_FETCHED -> if (cached != null) {
                scheduleCustomerInfoRefresh()
                InappifyResult.Success(cached, snapshot)
            } else fetchCustomerInfo(onlyIfStale = true)
        }
    }

    private suspend fun refreshCustomerInfo(): InappifyResult<InappifyCustomerInfo> =
        customerInfo(InappifyFetchPolicy.NETWORK_ONLY)
    override suspend fun customerInfo(policy: InappifyFetchPolicy): InappifyResult<InappifyCustomerInfo> {
        val cached = snapshot.customerInfo
        if (cached != null && policy in setOf(InappifyFetchPolicy.CACHE_ONLY, InappifyFetchPolicy.CACHE_FIRST)) {
            if (policy == InappifyFetchPolicy.CACHE_FIRST && backgroundRecovery) scope.launch { refreshCustomerInfo() }
            return InappifyResult.Success(cached, snapshot)
        }
        if (policy == InappifyFetchPolicy.CACHE_ONLY) return run { fail("CONFIGURATION", "CACHE_MISS") }
        val result = fetchCustomerInfo()
        return if (policy == InappifyFetchPolicy.NETWORK_FIRST && result is InappifyResult.Failure &&
            snapshot.customerInfo != null) InappifyResult.Success(snapshot.customerInfo!!, snapshot) else result
    }

    private suspend fun fetchCustomerInfo(onlyIfStale: Boolean = false): InappifyResult<InappifyCustomerInfo> =
        shared("customer:${snapshot.appUserIdentifier}") { run {
            requireSession()
            if (onlyIfStale && snapshot.customerInfo != null && !isCustomerInfoStale())
                return@run snapshot.customerInfo!!
            val response = sessionRequest("customerInfo")
            val info = verifiedResponse("customerInfo") {
                verify(response, document.getAsJsonObject("session").string("appUserId"))
            }
            val parsed = InappifyDomainJsonCodec.parseCustomerInfo(info.toString()).withEntitlementClock(::trustedNow)
            commit(document.deepCopy().apply { add("info", response); addProperty("infoFetchedAt", now()) })
            parsed
        } }

    private fun isCustomerInfoStale(): Boolean {
        val fetchedAt = document.get("infoFetchedAt")?.asLong ?: return true
        val at = now()
        val lifetime = if (foregroundMonitor.isForeground) TimeUnit.MINUTES.toMillis(5)
            else TimeUnit.HOURS.toMillis(25)
        return at < fetchedAt || at - fetchedAt >= lifetime
    }

    private fun scheduleCustomerInfoRefresh() {
        if (!closed && backgroundRecovery && snapshot.isConfigured &&
            sdkApi.enabled && isCustomerInfoStale()) scope.launch { fetchCustomerInfo(onlyIfStale = true) }
    }

    override suspend fun getOfferings(): InappifyResult<InappifyOfferings> = offerings(InappifyFetchPolicy.CACHE_FIRST)
    internal suspend fun refreshOfferings(): InappifyResult<InappifyOfferings> = offerings(InappifyFetchPolicy.NETWORK_ONLY)

    override suspend fun syncAttributesAndOfferingsIfNeededInternal(): InappifyResult<InappifyOfferings> = run {
        requireSession()
        val retryAfter = targetingSyncLimiter.acquire()
        if (retryAfter != null) {
            when (val cached = TargetingSyncRateLimiter.cachedResult(snapshot, retryAfter)) {
                is InappifyResult.Success -> cached.data
                is InappifyResult.Failure -> throw V2Failure(cached.error)
            }
        } else {
            // Do not join a resource flight started before this caller's attribute sync.
            // One mutex protects admission, queue flushing, fetching and identity binding.
            fetchOfferingsLocked()
        }
    }

    override suspend fun offerings(policy: InappifyFetchPolicy): InappifyResult<InappifyOfferings> {
        val cached = snapshot.offerings
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Reading Offerings with ${policy.name}; cache ${if (cached == null) "absent" else "available"}." }
        if (cached != null && policy in setOf(InappifyFetchPolicy.CACHE_ONLY, InappifyFetchPolicy.CACHE_FIRST)) {
            if (policy == InappifyFetchPolicy.CACHE_FIRST) scheduleOfferingsRefresh()
            return InappifyResult.Success(cached, snapshot)
        }
        if (policy == InappifyFetchPolicy.CACHE_ONLY) return run { fail("CONFIGURATION", "CACHE_MISS") }
        val result = shared("offerings:${snapshot.appUserIdentifier}") { run {
            // A foreground prefetch may have populated the cache while this read waited for the mutex.
            if (policy == InappifyFetchPolicy.CACHE_FIRST && snapshot.offerings != null) snapshot.offerings!!
            else fetchOfferingsLocked()
        } }
        return if (policy == InappifyFetchPolicy.NETWORK_FIRST && result is InappifyResult.Failure &&
            snapshot.offerings != null) InappifyResult.Success(snapshot.offerings!!, snapshot) else result
    }

    private suspend fun fetchOfferingsLocked(): InappifyOfferings = try {
        flushAttributesLocked()
        val response = sessionRequest("offerings", JsonObject().apply {
            addProperty("appVersion", snapshot.appVersion ?: metadata.get().versionName)
            addProperty("sdkVersion", BuildConfig.SDK_VERSION)
            addProperty("country", snapshot.country)
            // Only the native package renderer is advertised until the element schema is supplied.
            addProperty("paywallSchemaVersion", 1)
            addProperty("paywallRendererVersion", 1)
        })
        val safe = response.deepCopy().apply {
            remove("rules"); remove("forceVersion"); remove("hasForceUpdate")
            if (!has("currentOffering")) add("currentOffering", JsonNull.INSTANCE)
        }
        val parsed = InappifyDomainJsonCodec.parseOfferings(safe.toString())
        commit(document.deepCopy().apply { add("offerings", safe) })
        offeringsRefreshPolicy.fetched()
        parsed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        offeringsRefreshPolicy.failed()
        throw failure
    }

    private fun scheduleOfferingsRefresh() {
        if (!closed && backgroundRecovery && snapshot.isConfigured && foregroundMonitor.isForeground &&
            sdkApi.enabled && offeringsRefreshPolicy.shouldRefresh()) scope.launch { refreshOfferingsIfNeeded() }
    }

    private suspend fun refreshOfferingsIfNeeded(): InappifyResult<InappifyOfferings?> =
        shared("automatic-offerings:${snapshot.appUserIdentifier}") { run {
            // Recheck after serialization: a preceding request may have fetched or invalidated this catalog.
            if (!snapshot.isConfigured || pendingLogin || pendingLogout || !foregroundMonitor.isForeground ||
                !sdkApi.enabled || !offeringsRefreshPolicy.shouldRefresh()) snapshot.offerings
            else fetchOfferingsLocked()
        } }
    override suspend fun getCurrentOffering(placementIdentifier: String?): InappifyResult<InappifyOffering?> {
        val fetched = getOfferings()
        if (fetched is InappifyResult.Failure) return fetched
        return run {
            val raw = document.getAsJsonObject("offerings")
            val items = snapshot.offerings?.offerings.orEmpty()
            val placement = raw?.getAsJsonObject("placements")?.get(placementIdentifier)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val current = raw?.get("currentOffering")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            items.firstOrNull { it.identifier == placement && placement != null }
                ?: items.firstOrNull { it.identifier == current && current != null }
                ?: items.firstOrNull { it.isDefault == true }
        }
    }
    override suspend fun getDiscountedPaymentLinks(discountCode: String): InappifyResult<InappifyDiscountCodeResult> = run {
        val response = sessionRequest("validateDiscountCode", JsonObject().apply {
            addProperty("code", discountCode)
            addProperty("paymentReturnVersion", 1)
        })
        val data = response.getAsJsonObject("data") ?: fail("DECODING", "INVALID_DISCOUNT")
        data.flag("is_valid"); data.number("error_code")
        val result = InappifyDomainJsonCodec.parseDiscountCodeResult(data.toString())
        result.paymentLinks.orEmpty().forEach { if (!allowedUrl(it.url, config.paymentHosts)) fail("DECODING", "UNTRUSTED_PAYMENT_URL") }
        result
    }

    internal suspend fun enqueueAttributes(values: Map<String, String?>): InappifyResult<Unit> = run {
        requireSession()
        values.forEach { (key, value) ->
            if (!validAttribute(key, value)) fail("VALIDATION", "ATTRIBUTE_INVALID")
        }
        val next = document.deepCopy()
        val queue = next.getAsJsonObject("attributes") ?: JsonObject().also { next.add("attributes", it) }
        values.forEach { (key, value) -> queue.add(key, if (value.isNullOrEmpty()) JsonNull.INSTANCE else JsonPrimitive(value)) }
        if (queue.size() > 1000) fail("VALIDATION", "ATTRIBUTE_QUEUE_FULL")
        next.remove("offerings")
        commit(next)
        if (backgroundRecovery) scope.launch { delay(300); flushQueuedAttributes() }
    }
    internal suspend fun flushQueuedAttributes(): InappifyResult<Unit> = shared("attributes:${snapshot.appUserIdentifier}") { run { flushAttributesLocked() } }
    private suspend fun flushAttributesLocked() {
        requireSession()
        while (true) {
            val queue = document.getAsJsonObject("attributes") ?: return
            val entries = queue.entrySet().take(50)
            if (entries.isEmpty()) return
            val batch = JsonObject().apply { entries.forEach { add(it.key, it.value.deepCopy()) } }
            try { sessionRequest("attributes", JsonObject().apply { add("attributes", batch) }) }
            catch (e: V2Failure) {
                if (e.sdkError.details["httpStatus"] == 422) {
                    // No reliable field-level error contract: quarantine this rejected batch intact.
                    val next = document.deepCopy()
                    val quarantine = next.getAsJsonObject("quarantine") ?: JsonObject().also { next.add("quarantine", it) }
                    entries.forEach { quarantine.add(it.key, it.value); next.getAsJsonObject("attributes").remove(it.key) }
                    commit(next)
                }
                throw e
            }
            commit(document.deepCopy().apply { entries.forEach { getAsJsonObject("attributes").remove(it.key) } })
        }
    }
    override suspend fun setAttributes(attributes: Map<String, String?>): InappifyResult<List<InappifyAttribute>> {
        // Capture caller-owned values before waiting for the operation mutex, as the old request adapter did.
        val queued = enqueueAttributes(attributes.toMap())
        return when (queued) {
            is InappifyResult.Failure -> queued
            is InappifyResult.Success -> when (val flushed = flushQueuedAttributes()) {
                is InappifyResult.Failure -> flushed
                is InappifyResult.Success -> InappifyResult.Success(emptyList(), snapshot)
            }
        }
    }
    override suspend fun setReservedAttribute(request: InappifyReservedAttributeRequest): InappifyResult<Unit> =
        when (val result = setAttributes(mapOf(request.attribute.backendKey to request.value))) {
            is InappifyResult.Failure -> result
            is InappifyResult.Success -> InappifyResult.Success(Unit, snapshot)
        }
    override suspend fun canSetReservedAttribute(key: String): InappifyResult<Boolean> = run { key in reservedKeys }

    override fun getEntitlement(identifier: String): InappifyEntitlement? = snapshot.customerInfo?.entitlements?.firstOrNull {
        it.identifier == identifier && it.isActive == true && (it.expirationDate.isNullOrBlank() ||
            runCatching { isoMillis(it.expirationDate) > trustedNow() }.getOrDefault(false))
    }
    override fun isActiveEntitlement(identifier: String): Boolean = getEntitlement(identifier) != null
    override fun hasEntitlement(identifier: String): Boolean = isActiveEntitlement(identifier)
    override suspend fun purchaseAttempt(attemptId: String): InappifyResult<InappifyPurchaseAttempt?> = run {
        requireSession()
        if (!attemptId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
        readPurchaseAttempts().firstOrNull { it.attemptId == attemptId }
    }
    override suspend fun purchaseAttempts(): InappifyResult<List<InappifyPurchaseAttempt>> = run {
        requireSession(); readPurchaseAttempts()
    }
    private suspend fun readPurchaseAttempts(): List<InappifyPurchaseAttempt> = try {
        java.util.Collections.unmodifiableList(attemptHistory.list(currentAttemptBinding()))
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { fail("STORAGE", "PURCHASE_ATTEMPT_HISTORY_UNAVAILABLE") }

    private fun attemptBinding(api: String, customer: String, app: String, appId: Long?, store: String): String =
        digest(listOf(api, customer, app, appId.toString(), store).joinToString("") { "${it.length}:$it" })
    private fun currentAttemptBinding(): String {
        requireSession()
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        return attemptBinding(storeFingerprint(options!!), digest(session.string("appUserId")),
            metadata.get().packageIdentifier, session.number("appId"), nativeStoreMarket(snapshot.storePlatform)?.storeId ?: "direct")
    }
    private suspend fun recordAttempt(binding: String, value: InappifyPurchaseAttempt, emit: Boolean = true,
        outcomeMayHaveCommitted: Boolean = true, directTracking: Boolean = false): InappifyPurchaseAttempt {
        val recorded = try {
            check(attemptHistory.put(binding, value, directTracking))
            attemptHistory.list(binding).first { it.attemptId == value.attemptId }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { fail("STORAGE", "PURCHASE_ATTEMPT_RESULT_NOT_PERSISTED",
            diagnostics = mapOf("outcomeMayHaveCommitted" to outcomeMayHaveCommitted, "attemptId" to value.attemptId)) }
        if (emit) emitPurchaseAttempt(binding, recorded)
        return recorded
    }
    private fun emitPurchaseAttempt(binding: String, value: InappifyPurchaseAttempt) {
        val published = snapshot
        val generation = recoveryGeneration
        scope.launch(eventDispatcher) {
            if (!closed && recoveryGeneration == generation && runCatching { currentAttemptBinding() }.getOrNull() == binding) {
                val event = InappifyEvent.create(InappifyEventType.PURCHASE_UPDATED, published, value.attemptId, value)
                listeners.forEach { listener -> runCatching { listener.onEvent(event) } }
            }
        }
    }
    private fun safeAttemptError(error: InappifyError): InappifyError = InappifyError(error.code,
        "Purchase attempt ${error.code.name}.", error.isRetryable, error.details.filter { (key, value) ->
            key in setOf("category", "serverCode", "httpStatus", "outcomeMayHaveCommitted", "phase", "attemptId", "store", "storeCode") &&
                (value == null || value is String || value is Boolean || value is Number)
        })
    private suspend fun recordStoreAttempt(outcome: StoreV2Outcome, emit: Boolean = true): InappifyPurchaseAttempt {
        val operation = outcome.operation
        val error = when (outcome) {
            is StoreV2Outcome.Failure -> outcome.error
            is StoreV2Outcome.Rejected -> outcome.error
            is StoreV2Outcome.Deferred -> deferredStoreError(outcome)
            else -> null
        }
        val status = when {
            outcome is StoreV2Outcome.Terminal -> InappifyPurchaseAttemptStatus.SUCCEEDED
            outcome is StoreV2Outcome.Rejected && (outcome.state?.status == StorePurchaseStatus.REJECTED ||
                outcome.requiresRejectionTombstone) || outcome is StoreV2Outcome.Failure &&
                outcome.requiresRejectionTombstone -> InappifyPurchaseAttemptStatus.FAILED
            else -> InappifyPurchaseAttemptStatus.PENDING
        }
        val purchase = if (error == null) storePurchaseResult(outcome, originalPackage(operation)) else null
        return recordAttempt(attemptBinding(operation.apiKeyFingerprint, operation.customerIdentifierFingerprint,
            operation.appIdentifier, operation.appId, operation.store), InappifyPurchaseAttempt(operation.id,
            operation.productIdentifier, operation.offeringIdentifier.orEmpty(), requireNotNull(nativeStoreMarket(operation.store)),
            status, purchase, error?.let(::safeAttemptError), now()), emit)
    }
    override fun isCustomerAnonymous(appUserIdentifier: String?): Boolean = appUserIdentifier?.let(::isAnonymous) == true
    override suspend fun purchase(purchaseParams: InappifyPurchaseParams): InappifyResult<InappifyPurchase> {
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Starting package purchase." }
        val request = purchaseParams.toRequest()
        val failureCode = when {
            request == null -> "PURCHASE_PACKAGE_CONTEXT_REQUIRED"
            request.productType == null && snapshot.storePlatform in setOf("Bazar", "MyKet") ->
                "PRODUCT_TYPE_REQUIRED"
            else -> null
        }
        if (failureCode != null) return InappifyResult.Failure(
            InappifyError(
                InappifyErrorCode.INVALID_CONFIGURATION,
                "The selected package is missing purchase metadata. Refresh offerings before purchasing.",
                details = mapOf("operation" to "purchase", "category" to "VALIDATION", "serverCode" to failureCode),
            ),
            snapshot,
        )
        return purchaseInternal(purchaseParams.activity, request!!)
    }
    private sealed interface PurchaseStep {
        class Wait(val recovery: RecoveryCoordinator.Flight) : PurchaseStep
        class Ready(val purchase: InappifyPurchase) : PurchaseStep
    }
    internal suspend fun purchaseInternal(activity: Activity?, request: InappifyPurchaseRequest): InappifyResult<InappifyPurchase> {
        var binding: Triple<Long, String?, String?>? = null
        val joined = mutableListOf<RecoveryPass>()
        // Capture before waiting for the state mutex: recovery can finish and remove
        // the paid checkpoint while this purchase is waiting to enter that mutex.
        recoveryCoordinator.active()?.let { active ->
            joined += active.pass
            active.result.await()
        }
        while (true) {
            val result = run {
                requireSession()
                val currentBinding = Triple(recoveryGeneration, snapshot.storePlatform, options?.let(::fingerprint))
                if ((binding != null && binding != currentBinding) || joined.any { it.generation != recoveryGeneration })
                    fail("CONFIGURATION", "PURCHASE_CONTEXT_CHANGED")
                binding = currentBinding
                if (request.apiKey != null || request.marketKey != null || request.isLostPurchase || request.dynamicPriceToken != null)
                    fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
                validatePurchaseOptions(request)
                // Never await recovery while holding its state mutex. Check admission here
                // so a pass started while the purchase waited for this mutex is also joined.
                recoveryCoordinator.active()?.let { active ->
                    if (active.pass !in joined) return@run PurchaseStep.Wait(active)
                }
                recoveredPurchase(request, joined)?.let { return@run PurchaseStep.Ready(it) }
                // A paid attempt owns its persisted offering/product binding. Catalog refreshes
                // may remove that product, but must never force the caller to pay again.
                if (nativeStoreMarket(snapshot.storePlatform) != null && request.idempotencyKey != null) {
                    val recovery = storage.loadPendingStoreRecoveryState()
                        ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
                    if (recovery.operations.any { it.id == request.idempotencyKey })
                        return@run PurchaseStep.Ready(purchaseStoreV2(activity, request, request.packageIdentifier))
                }
                pendingPurchase(request)?.let { return@run PurchaseStep.Ready(it) }
                val offering = snapshot.offerings?.offerings?.firstOrNull { it.identifier == request.offeringIdentifier }
                val selectedPackage = offering?.packages?.firstOrNull { it.product?.identifier == request.productIdentifier &&
                    (request.packageIdentifier == null || it.identifier == request.packageIdentifier) }
                    ?: fail("VALIDATION", "PRODUCT_NOT_IN_OFFERING")
                val purchase = when (snapshot.storePlatform) {
                    "DirectAndroid" -> purchaseDirectV2(request, selectedPackage.identifier)
                    "Bazar", "MyKet" -> purchaseStoreV2(activity, request, selectedPackage.identifier)
                    else -> fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
                }
                if (purchase.url != null && !allowedUrl(purchase.url, config.paymentHosts))
                    fail("DECODING", "UNTRUSTED_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
                PurchaseStep.Ready(purchase)
            }
            when (result) {
                is InappifyResult.Failure -> return result
                is InappifyResult.Success -> when (val step = result.data) {
                    is PurchaseStep.Wait -> {
                        joined += step.recovery.pass
                        // Catalog/info failure is not proof that a paid receipt is unresolved.
                        // Only matching purchase outcomes/checkpoints decide below.
                        step.recovery.result.await()
                    }
                    is PurchaseStep.Ready -> {
                        refreshCustomerInfo()
                        return InappifyResult.Success(step.purchase, snapshot)
                    }
                }
            }
        }
    }

    private fun validatePurchaseOptions(request: InappifyPurchaseRequest) {
        val market = nativeStoreMarket(snapshot.storePlatform)
        if (market != null) {
            if (request.country != null || request.appVersion != null || request.market !in setOf(InappifyMarket.NONE, market) ||
                request.isCrypto || request.discount != 0L || request.discountCode != null ||
                request.paywallId != null || request.paywallRevision != null)
                fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        } else if (snapshot.storePlatform == "DirectAndroid" && (request.country != null || request.appVersion != null ||
                request.market != InappifyMarket.NONE || request.discount != 0L || request.discountCode != null))
            fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        if (request.idempotencyKey != null && !request.idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
            fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
    }

    private fun recoveredPurchase(request: InappifyPurchaseRequest, joined: List<RecoveryPass>): InappifyPurchase? {
        val recovered = joined.asReversed().flatMap { it.purchases().asReversed() }
        val exact = request.idempotencyKey?.let { attempt -> recovered.firstOrNull { it.attemptId == attempt } }
        if (exact != null && exact.productIdentifier != request.productIdentifier) fail("VALIDATION", "PURCHASE_ATTEMPT_CONFLICT")
        exact?.nativeOperation?.let { operation ->
            if (operation.operation != PendingStoreOperationType.PURCHASE ||
                operation.productIdentifier != request.productIdentifier || operation.offeringIdentifier != request.offeringIdentifier ||
                !compatibleRetryType(request.productType, operation.productType)) fail("VALIDATION", "PURCHASE_ATTEMPT_CONFLICT")
        }
        val previous = exact ?: recovered.firstOrNull { it.productIdentifier == request.productIdentifier } ?: return null
        return when (val result = previous.result) {
            is InappifyResult.Success -> result.data
            is InappifyResult.Failure -> throw V2Failure(result.error)
        }
    }

    /** Finish known paid work for this SKU without creating another store/payment attempt. */
    private suspend fun pendingPurchase(request: InappifyPurchaseRequest): InappifyPurchase? {
        if (snapshot.storePlatform == "DirectAndroid") {
            val record = directRecords().entrySet().firstOrNull { (_, value) ->
                val delivery = value.asJsonObject.getAsJsonObject("delivery")
                delivery.string("productIdentifier") == request.productIdentifier && delivery.string("status") != "COMPLETED"
            }?.value?.asJsonObject ?: return null
            if (!sdkApi.enabled || commerceApi?.enabled == false) fail("CONFIGURATION", "V2_DISABLED")
            val delivery = GoV2DirectDelivery(record.getAsJsonObject("delivery"))
            // Only a previously committed host grant may acknowledge a delivery.
            return if (record.flag("hostDelivered")) confirmDirectDelivery(delivery.id) else delivery.purchase()
        }
        val market = nativeStoreMarket(snapshot.storePlatform) ?: return null
        val opt = options ?: return null
        val session = document.getAsJsonObject("session") ?: return null
        val recovery = storage.loadPendingStoreRecoveryState() ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
        val pending = recovery.operations.firstOrNull { it.productIdentifier == request.productIdentifier &&
            it.store == market.storeId && it.apiKeyFingerprint == storeFingerprint(opt) &&
            it.customerIdentifierFingerprint == digest(session.string("appUserId")) &&
            it.appId == session.number("appId") && it.appIdentifier == metadata.get().packageIdentifier } ?: return null
        val context = storeContext()
        if (context.marketKey.isNullOrBlank()) fail("CONFIGURATION", if (market == InappifyMarket.MYKET)
            "MYKET_PUBLIC_KEY_REQUIRED" else "BAZAAR_PUBLIC_KEY_REQUIRED")
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        if (storeTombstone(pending, context) in recovery.rejectedEvidenceTombstones)
            fail("VALIDATION", "STORE_EVIDENCE_PREVIOUSLY_REJECTED")
        val outcome = coordinator.resume(pending, context, StoreV2Coordinator.MAX_FOREGROUND_POLLS)
        finalizeRejectedStoreOutcome(outcome, context)
        return storePurchaseResult(outcome, originalPackage(pending))
    }

    private fun originalPackage(operation: PendingStoreOperation): String? = runCatching {
        JsonParser.parseString(operation.evidence.developerPayload).asJsonObject
            .get("nativePackageIdentifier")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)
    }.getOrNull()
    private suspend fun purchaseDirectV2(request: InappifyPurchaseRequest, packageIdentifier: String?): InappifyPurchase {
        if (!sdkApi.enabled) fail("CONFIGURATION", "V2_DISABLED")
        requireSession()
        if (request.country != null || request.appVersion != null || request.market != InappifyMarket.NONE ||
            request.discount != 0L || request.discountCode != null)
            fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        if (request.idempotencyKey != null && !request.idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
            fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
        val attemptId = request.idempotencyKey ?: UUID.randomUUID().toString()
        val binding = currentAttemptBinding()
        val intent = InappifyPurchase(attemptId, request.productIdentifier, request.offeringIdentifier,
            InappifyMarket.NONE, packageIdentifier = packageIdentifier)
        recordAttempt(binding, InappifyPurchaseAttempt(attemptId, request.productIdentifier, request.offeringIdentifier,
            InappifyMarket.NONE, InappifyPurchaseAttemptStatus.PENDING, intent, null, now()), outcomeMayHaveCommitted = false,
            directTracking = true)
        val response = try {
            // Only an explicit SESSION_EXPIRED rejection permits one authenticated replay.
            commerceRequest("purchase", JsonObject().apply {
                addProperty("productIdentifier", request.productIdentifier)
                addProperty("offeringIdentifier", request.offeringIdentifier)
                addProperty("attemptId", attemptId)
                addProperty("paymentReturnVersion", 1)
                request.paywallId?.let { addProperty("paywallId", it) }
                request.paywallRevision?.let { addProperty("paywallRevision", it) }
            }).body
        } catch (e: V2Failure) {
            val category = e.sdkError.details["category"]
            val status = e.sdkError.details["httpStatus"] as? Int
            val uncertain = category in setOf("NETWORK", "TIMEOUT", "DECODING") ||
                status != null // A rejection can follow durable payment creation; only status lookup proves otherwise.
            if (!uncertain) throw e
            throw V2Failure(InappifyError(e.sdkError.code, e.sdkError.message, e.sdkError.isRetryable,
                e.sdkError.details + mapOf("outcomeMayHaveCommitted" to true)))
        }
        val data = response.getAsJsonObject("data") ?: fail("DECODING", "INVALID_PURCHASE", diagnostics =
            mapOf("outcomeMayHaveCommitted" to true))
        val status = runCatching { InappifyPurchaseStatus.fromServerValue(data.string("purchaseStatus")) }
            .getOrNull() ?: fail("DECODING", "INVALID_PURCHASE_STATUS", diagnostics =
                mapOf("outcomeMayHaveCommitted" to true))
        val url = data.get("url")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString)
                fail("DECODING", "INVALID_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
            it.asString
        }
        if (status == InappifyPurchaseStatus.NEEDTOPAY && url.isNullOrBlank())
            fail("DECODING", "MISSING_PAYMENT_URL", diagnostics = mapOf("outcomeMayHaveCommitted" to true))
        val purchase = InappifyPurchase(attemptId,
            request.productIdentifier, request.offeringIdentifier, InappifyMarket.NONE, status,
            packageIdentifier, url)
        val authoritative = data.get("attempt")?.takeUnless { it.isJsonNull }?.let { raw ->
            if (!raw.isJsonObject) fail("DECODING", "INVALID_DIRECT_PURCHASE_ATTEMPT")
            val tracked = GoV2DirectAttempt(raw.asJsonObject)
            validateDirectAttempt(tracked, attemptId, request.productIdentifier, request.offeringIdentifier)
            // A callback may complete between checkout creation and this response.
            if (tracked.status == InappifyPurchaseAttemptStatus.FAILED) fail("DECODING", "DIRECT_PURCHASE_ATTEMPT_SCOPE_MISMATCH")
            tracked.result(packageIdentifier, now())
        }
        recordAttempt(binding, authoritative ?: InappifyPurchaseAttempt(attemptId, request.productIdentifier, request.offeringIdentifier,
            InappifyMarket.NONE, if (status == InappifyPurchaseStatus.DONE) InappifyPurchaseAttemptStatus.SUCCEEDED
            else InappifyPurchaseAttemptStatus.PENDING, purchase, null, now()), directTracking = true)
        return purchase
    }
    /** Called only under the state mutex: renewal, verification, persistence and replay are single-flight. */
    private suspend fun commerceRequest(endpoint: String, body: JsonObject): GoApiResponse {
        val api = commerceApi ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val expectedStore = snapshot.storePlatform
        val token = commerceSessionToken()
        return try { api.requestWithMetadata(endpoint, token, body, retry = false) }
        catch (failure: V2Failure) {
            if (failure.sdkError.details["httpStatus"] != 401 ||
                failure.sdkError.details["serverCode"] != "SESSION_EXPIRED") throw failure
            val identity = document.getAsJsonObject("session").string("appUserId")
            renewSession(identity, retry = false)
            if (snapshot.storePlatform != expectedStore) fail("CONFIGURATION", "PURCHASE_ROUTE_CHANGED")
            api.requestWithMetadata(endpoint, commerceSessionToken(), body, retry = false)
        }
    }
    private suspend fun commerceSessionToken(): String {
        if (closed) throw CancellationException("The Inappify client is closed.")
        if (!sdkApi.enabled || commerceApi?.enabled == false) fail("CONFIGURATION", "V2_DISABLED")
        requireSession()
        val expectedStore = snapshot.storePlatform
        var session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (document.get("sessionInvalid")?.asBoolean == true || isoMillis(session.string("sessionExpiresAt")) <= now()) {
            renewSession(session.string("appUserId"), retry = false)
            session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        }
        if (snapshot.storePlatform != expectedStore) fail("CONFIGURATION", "PURCHASE_ROUTE_CHANGED")
        return session.string("sessionToken")
    }
    private suspend fun storeContext(): StoreV2Context {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val token = commerceSessionToken()
        val session = document.getAsJsonObject("session") ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        val market = nativeStoreMarket(snapshot.storePlatform) ?: fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
        return StoreV2Context(opt.apiKey, token, storeFingerprint(opt),
            digest(session.string("appUserId")), metadata.get().packageIdentifier,
            session.number("appId"), document.get("country")?.asString ?: "IR",
            document.get("appVersion")?.asString ?: metadata.get().versionName,
            session.get("storeInfo")?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank), market,
            subscriptionRecoveryEnabled = subscriptionRecoveryEnabled)
    }
    private fun storeFingerprint(value: InappifyOptions): String =
        goStoreFingerprint(fingerprint(value), config.commerceApiBaseUrl)
    private suspend fun purchaseStoreV2(activity: Activity?, request: InappifyPurchaseRequest,
        packageIdentifier: String?): InappifyPurchase {
        if (request.country != null || request.appVersion != null ||
            request.market !in setOf(InappifyMarket.NONE, nativeStoreMarket(snapshot.storePlatform)) ||
            request.isCrypto || request.discount != 0L || request.discountCode != null ||
            request.paywallId != null || request.paywallRevision != null)
            fail("VALIDATION", "PURCHASE_OVERRIDE_NOT_ALLOWED")
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        if (context.marketKey.isNullOrBlank()) fail("CONFIGURATION", if (context.market == InappifyMarket.MYKET) "MYKET_PUBLIC_KEY_REQUIRED" else "BAZAAR_PUBLIC_KEY_REQUIRED")
        val attemptId = request.idempotencyKey ?: UUID.randomUUID().toString()
        if (!attemptId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) fail("VALIDATION", "PURCHASE_ATTEMPT_INVALID")
        val recovery = storage.loadPendingStoreRecoveryState() ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
        val existing = recovery.operations.firstOrNull { it.id == attemptId }
        if (existing != null) {
            if (existing.appId != context.appId || existing.apiKeyFingerprint != context.apiKeyFingerprint ||
                existing.customerIdentifierFingerprint != context.customerIdentifierFingerprint ||
                existing.appIdentifier != context.appIdentifier || existing.store != context.store ||
                existing.operation != PendingStoreOperationType.PURCHASE ||
                !compatibleRetryType(request.productType, existing.productType) ||
                existing.productIdentifier != request.productIdentifier ||
                existing.offeringIdentifier != request.offeringIdentifier)
                fail("VALIDATION", "PURCHASE_ATTEMPT_CONFLICT")
            val outcome = coordinator.resume(existing, context, StoreV2Coordinator.MAX_FOREGROUND_POLLS)
            finalizeRejectedStoreOutcome(outcome, context)
            return storePurchaseResult(outcome, packageIdentifier)
        }
        val host = activity ?: fail("VALIDATION", "STORE_UI_HOST_REQUIRED")
        val appId = context.appId?.takeIf { it > 0 } ?: fail("CONFIGURATION", "STORE_APP_ID_REQUIRED")
        val nativePackageIdentifier = packageIdentifier?.takeIf(String::isNotBlank)
            ?: fail("VALIDATION", "PACKAGE_IDENTIFIER_REQUIRED")
        val binding = currentAttemptBinding()
        recordAttempt(binding, InappifyPurchaseAttempt(attemptId, request.productIdentifier, request.offeringIdentifier,
            context.market, InappifyPurchaseAttemptStatus.PENDING, null, null, now()), outcomeMayHaveCommitted = false)
        val adapter = createBillingAdapter(context.marketKey, context.market)
        val developerPayload = JsonObject().apply {
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("productIdentifier", request.productIdentifier)
            addProperty("nativePackageIdentifier", packageIdentifier)
            addProperty("attemptId", attemptId)
            request.productType?.let { addProperty("productType", it.name) }
            addProperty("recoveryBinding", digest("${context.apiKeyFingerprint}:${context.customerIdentifierFingerprint}"))
            addProperty("purchaseBinding", goPurchaseBinding(appId, context.appIdentifier,
                context.customerIdentifierFingerprint, request.productIdentifier,
                request.offeringIdentifier, nativePackageIdentifier))
        }.toString()
        val receipt = try {
            when (val billed = adapter.purchase(StoreUiHost.from(host), StorePurchaseRequest(
                request.productIdentifier,
                if (context.market == InappifyMarket.BAZAAR && request.productType == InappifyProductType.SUBSCRIPTION) StoreProductType.SUBSCRIPTION
                else StoreProductType.IN_APP, developerPayload))) {
                is StoreBillingResult.Success -> billed.purchase
                is StoreBillingResult.Cancelled -> throw V2Failure(InappifyError(InappifyErrorCode.PURCHASE_CANCELLED,
                    "The marketplace purchase was cancelled."))
                is StoreBillingResult.Failure -> throw V2Failure(billed.error.toGoPurchaseError(attemptId, context.store))
            }
        } catch (failure: V2Failure) {
            val definitive = failure.sdkError.code == InappifyErrorCode.PURCHASE_CANCELLED &&
                failure.sdkError.details["outcomeMayHaveCommitted"] != true
            recordAttempt(binding, InappifyPurchaseAttempt(attemptId, request.productIdentifier, request.offeringIdentifier,
                context.market, if (definitive) InappifyPurchaseAttemptStatus.FAILED else InappifyPurchaseAttemptStatus.PENDING,
                null, safeAttemptError(failure.sdkError), now()))
            throw failure
        } finally { releaseBillingAdapter(adapter) }
        if (receipt.productIdentifier != request.productIdentifier || receipt.packageName != context.appIdentifier ||
            runCatching { JsonParser.parseString(receipt.developerPayload) == JsonParser.parseString(developerPayload) }
                .getOrDefault(false).not())
            throw V2Failure(InappifyError(InappifyErrorCode.MALFORMED_RESPONSE,
                "The marketplace receipt does not match the requested purchase.", details = mapOf(
                    "operation" to "purchase", "serverCode" to "STORE_RECEIPT_SCOPE_MISMATCH",
                    "store" to context.store, "attemptId" to attemptId, "outcomeMayHaveCommitted" to true)))
        val productType = pendingProductType(request.productType)
        val operation = PendingStoreOperation(attemptId, PendingStoreOperationType.PURCHASE, context.store,
            context.customerToken, context.customerIdentifierFingerprint, context.apiKeyFingerprint,
            context.appIdentifier, context.appId, request.productIdentifier, request.offeringIdentifier,
            productType, PendingStorePurchaseEvidence(receipt.purchaseToken,
                receipt.orderIdentifier.takeIf(String::isNotBlank), receipt.packageName,
                receipt.developerPayload.takeIf(String::isNotBlank),
                receipt.originalJson.takeIf(String::isNotBlank), receipt.signature.takeIf(String::isNotBlank),
                receipt.purchaseTimeMillis), createdAtEpochMillis = now())
        if (storeTombstone(operation, context) in recovery.rejectedEvidenceTombstones)
            fail("VALIDATION", "STORE_EVIDENCE_PREVIOUSLY_REJECTED")
        val outcome = coordinator.submit(operation, context)
        finalizeRejectedStoreOutcome(outcome, context)
        return storePurchaseResult(outcome, packageIdentifier)
    }
    private fun pendingProductType(type: InappifyProductType?): PendingStoreProductType = when (type) {
            InappifyProductType.CONSUMABLE -> PendingStoreProductType.CONSUMABLE
            InappifyProductType.NON_CONSUMABLE -> PendingStoreProductType.NON_CONSUMABLE
            InappifyProductType.SUBSCRIPTION -> PendingStoreProductType.SUBSCRIPTION
            null -> PendingStoreProductType.LEGACY_IN_APP
        }
    private fun compatibleRetryType(requested: InappifyProductType?, saved: PendingStoreProductType): Boolean =
        if (requested == null) saved in setOf(PendingStoreProductType.LEGACY_IN_APP, PendingStoreProductType.CONSUMABLE)
        else saved == pendingProductType(requested)
    private fun storeTombstone(operation: PendingStoreOperation, context: StoreV2Context): RejectedStoreEvidenceTombstone {
        fun combined(vararg values: Any?): String = digest(values.joinToString(":") { value ->
            val part = value?.toString().orEmpty()
            "${part.length}:$part"
        })
        val evidence = operation.evidence
        return RejectedStoreEvidenceTombstone(
            purchaseTokenFingerprint = digest(if (operation.store == "myket") "myket:${evidence.purchaseToken}" else evidence.purchaseToken),
            apiKeyFingerprint = operation.apiKeyFingerprint,
            customerIdentifierFingerprint = operation.customerIdentifierFingerprint,
            appFingerprint = combined(operation.appIdentifier, operation.appId),
            productIdentifierFingerprint = digest(operation.productIdentifier),
            productTypeFingerprint = digest(operation.productType.name),
            offeringIdentifierFingerprint = combined(operation.offeringIdentifier),
            appVersionFingerprint = combined(context.appVersion),
            forceVersionFingerprint = combined(null),
            operationFingerprint = digest(operation.operation.name),
            countryFingerprint = combined(context.country),
            evidenceFingerprint = combined(evidence.purchaseTimeMillis, evidence.orderId,
                evidence.packageName, evidence.developerPayload, evidence.originalJson, evidence.signature),
        )
    }
    private suspend fun finalizeRejectedStoreOutcome(outcome: StoreV2Outcome, context: StoreV2Context) {
        val tracked = recordStoreAttempt(outcome, emit = false)
        val permanent = when (outcome) {
            is StoreV2Outcome.Rejected -> outcome.state?.status == StorePurchaseStatus.REJECTED ||
                outcome.requiresRejectionTombstone
            is StoreV2Outcome.Failure -> outcome.requiresRejectionTombstone
            else -> false
        }
        if (permanent && !storage.finalizePendingStoreOperationWithTombstone(outcome.operation.id,
                storeTombstone(outcome.operation, context))) fail("STORAGE", "STORE_REJECTION_NOT_PERSISTED")
        emitPurchaseAttempt(attemptBinding(outcome.operation.apiKeyFingerprint, outcome.operation.customerIdentifierFingerprint,
            outcome.operation.appIdentifier, outcome.operation.appId, outcome.operation.store), tracked)
        // Waiting callers retain the original outcome independently of the read-only history.
        val pass = currentCoroutineContext()[RecoveryPass] ?: return
        val observed = try {
            InappifyResult.Success(storePurchaseResult(outcome, originalPackage(outcome.operation)), snapshot)
        } catch (failure: V2Failure) { InappifyResult.Failure(failure.sdkError, snapshot) }
        pass.record(RecoveredPurchase(outcome.operation.id, outcome.operation.productIdentifier, observed, outcome.operation))
    }
    private fun storePurchaseResult(outcome: StoreV2Outcome, packageIdentifier: String?): InappifyPurchase {
        val state = when (outcome) {
            is StoreV2Outcome.Terminal -> outcome.state
            is StoreV2Outcome.PendingDelivery -> outcome.state
            is StoreV2Outcome.Deferred -> throw V2Failure(deferredStoreError(outcome))
            is StoreV2Outcome.Rejected -> throw V2Failure(outcome.error)
            is StoreV2Outcome.Failure -> throw V2Failure(outcome.error)
        }
        InappifyLogger.log(InappifyLogLevel.INFO) { "Store purchase status: ${state.status.name}." }
        return InappifyPurchase.storeResult(outcome.operation.id, outcome.operation.productIdentifier,
            outcome.operation.offeringIdentifier.orEmpty(), packageIdentifier,
            InappifyStorePurchaseStatus.fromServerValue(state.status.wireValue),
            state.deliveryId ?: outcome.operation.deliveryId,
            state.verificationRequestId ?: outcome.operation.verificationRequestId,
            state.alreadyProcessed == true || state.status == StorePurchaseStatus.ALREADY_PROCESSED,
            requireNotNull(nativeStoreMarket(outcome.operation.store)))
    }
    private data class StoreReconciliation(
        val purchases: List<InappifyPurchase>,
        val restoreResult: InappifyRestoreResult,
        val error: InappifyError?,
    )
    private fun deferredStoreError(outcome: StoreV2Outcome.Deferred): InappifyError =
        outcome.error ?: InappifyError(InappifyErrorCode.NETWORK,
            "Store verification is pending; retry the same purchase attempt.", isRetryable = true,
            details = mapOf("operation" to "storePurchaseVerification", "outcomeMayHaveCommitted" to true,
                "phase" to outcome.operation.phase.name))
    private suspend fun reconcileStorePurchases(queryOwned: Boolean, explicitRestore: Boolean,
        confirmedCustomerInfo: InappifyCustomerInfo? = snapshot.customerInfo): StoreReconciliation {
        if (nativeStoreMarket(snapshot.storePlatform) == null) fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        if (context.marketKey.isNullOrBlank()) fail("CONFIGURATION", if (context.market == InappifyMarket.MYKET) "MYKET_PUBLIC_KEY_REQUIRED" else "BAZAAR_PUBLIC_KEY_REQUIRED")
        val recovery = storage.loadPendingStoreRecoveryState() ?: fail("STORAGE", "STORE_RECOVERY_UNAVAILABLE")
        val purchases = mutableListOf<InappifyPurchase>()
        var restored = 0
        var already = 0
        var failed = 0
        var queryFailures = 0
        var firstError: InappifyError? = null
        val knownTokens = mutableSetOf<String>()
        val knownSubscriptionReceipts = mutableSetOf<Pair<String, Long>>()
        val reservedOperationIds = recovery.operations.mapTo(mutableSetOf()) { it.id }
        val operationsById = recovery.operations.associateByTo(mutableMapOf()) { it.id }
        for (pending in recovery.operations.filter { it.store == context.store &&
            it.apiKeyFingerprint == context.apiKeyFingerprint &&
            it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
            it.appId == context.appId && it.appIdentifier == context.appIdentifier }) {
            val purchaseTime = pending.evidence.purchaseTimeMillis
            if (context.market == InappifyMarket.BAZAAR && pending.productType == PendingStoreProductType.SUBSCRIPTION && purchaseTime != null)
                knownSubscriptionReceipts += pending.evidence.purchaseToken to purchaseTime
            else knownTokens += pending.evidence.purchaseToken
            if (storeTombstone(pending, context) in recovery.rejectedEvidenceTombstones) {
                if (!storage.removePendingStoreOperation(pending.id)) fail("STORAGE", "STORE_REJECTION_NOT_PERSISTED")
                continue
            }
            val outcome = coordinator.resume(pending, context)
            finalizeRejectedStoreOutcome(outcome, context)
            when (outcome) {
                is StoreV2Outcome.Terminal, is StoreV2Outcome.PendingDelivery -> {
                    purchases += storePurchaseResult(outcome, null)
                    if (outcome is StoreV2Outcome.Terminal && explicitRestore &&
                        pending.productType != PendingStoreProductType.CONSUMABLE) {
                        if (outcome.state.status == StorePurchaseStatus.ALREADY_PROCESSED) already++ else restored++
                    }
                }
                is StoreV2Outcome.Deferred -> {
                    firstError = firstError ?: deferredStoreError(outcome)
                    if (explicitRestore) failed++
                }
                is StoreV2Outcome.Rejected -> { if (explicitRestore) failed++ else firstError = firstError ?: outcome.error }
                is StoreV2Outcome.Failure -> { if (explicitRestore) failed++ else firstError = firstError ?: outcome.error }
            }
        }
        if (queryOwned) {
            val adapter = createBillingAdapter(context.marketKey, context.market)
            try {
                for (storeType in context.market.ownedProductTypes) {
                    val pendingType = if (storeType == StoreProductType.SUBSCRIPTION) PendingStoreProductType.SUBSCRIPTION else PendingStoreProductType.LEGACY_IN_APP
                    val query = if (adapter is PartialStorePurchaseQueryAdapter)
                        adapter.queryPurchasesPartially(storeType) else adapter.queryPurchases(storeType)
                    when (query) {
                        is StorePurchaseQueryResult.Failure -> { queryFailures++; if (explicitRestore) failed++ }
                        is StorePurchaseQueryResult.Success -> {
                            if (explicitRestore) failed += query.invalidPurchaseCount
                            for (receipt in query.purchases) {
                                if (receipt.packageName != context.appIdentifier) continue
                                if (storeType == StoreProductType.SUBSCRIPTION) {
                                    if (receipt.purchaseToken in knownTokens || !knownSubscriptionReceipts.add(
                                            receipt.purchaseToken to receipt.purchaseTimeMillis)) continue
                                } else if (!knownTokens.add(receipt.purchaseToken)) continue
                                // Only a genuinely absent payload may use server-resolved ownership.
                                // Malformed JSON or a bad binding must never downgrade to this path.
                                val unboundSubscription = receipt.developerPayload.isBlank() &&
                                    context.market == InappifyMarket.BAZAAR && storeType == StoreProductType.SUBSCRIPTION
                                if (unboundSubscription && !context.subscriptionRecoveryEnabled) continue
                                if (unboundSubscription && (receipt.purchaseTimeMillis <= 0L ||
                                        receipt.originalJson.isBlank() || receipt.signature.isBlank())) {
                                    if (explicitRestore) failed++
                                    else firstError = firstError ?: InappifyError(InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                                        "Subscription recovery requires the original signed store receipt.")
                                    continue
                                }
                                val payload = runCatching { JsonParser.parseString(receipt.developerPayload).asJsonObject }
                                    .getOrNull()
                                val expectedBinding = digest("${context.apiKeyFingerprint}:${context.customerIdentifierFingerprint}")
                                if (!unboundSubscription && (payload == null || runCatching { payload.get("recoveryBinding")?.asString }
                                        .getOrNull() != expectedBinding)) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val typeField = payload?.get("productType")?.takeUnless { it.isJsonNull }
                                if (typeField != null && (!typeField.isJsonPrimitive || !typeField.asJsonPrimitive.isString)) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val declaredType = typeField?.asString?.takeUnless(String::isBlank)
                                // Older bound receipts may omit productType. The subscription query
                                // establishes that type; an in-app query cannot distinguish consumables.
                                val resolvedType = declaredType ?: if (storeType == StoreProductType.SUBSCRIPTION)
                                    InappifyProductType.SUBSCRIPTION.name else null
                                val offeringId = runCatching { payload?.string("offeringIdentifier") }.getOrNull()
                                val payloadProduct = runCatching { payload?.string("productIdentifier") }.getOrNull()
                                val attempt = if (unboundSubscription) subscriptionRecoveryReceiptId(receipt, context)
                                    else runCatching { payload?.string("attemptId") }.getOrNull()
                                val compatibleType = when (storeType) {
                                    StoreProductType.SUBSCRIPTION -> resolvedType == InappifyProductType.SUBSCRIPTION.name
                                    StoreProductType.IN_APP -> resolvedType == null || resolvedType in setOf(
                                        InappifyProductType.CONSUMABLE.name, InappifyProductType.NON_CONSUMABLE.name) ||
                                        (context.market == InappifyMarket.MYKET && resolvedType == InappifyProductType.SUBSCRIPTION.name)
                                }
                                if (!compatibleType || (!unboundSubscription &&
                                        (payloadProduct != receipt.productIdentifier || offeringId.isNullOrBlank())) ||
                                    attempt == null || !attempt.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                // Older receipts keep their existing recovery path. New bindings
                                // must match the original package as well as the customer/product.
                                if (payload?.has("purchaseBinding") == true) {
                                    val binding = payload.get("purchaseBinding")?.takeIf {
                                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                                    }?.asString
                                    val packageId = payload.get("nativePackageIdentifier")?.takeIf {
                                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                                    }?.asString
                                    val expected = context.appId?.takeIf { it > 0 }?.let { appId ->
                                        packageId?.takeIf(String::isNotBlank)?.let { packageIdentifier ->
                                            goPurchaseBinding(appId, context.appIdentifier,
                                                context.customerIdentifierFingerprint, receipt.productIdentifier,
                                                requireNotNull(offeringId), packageIdentifier)
                                        }
                                    }
                                    if (expected == null || binding != expected) {
                                        if (explicitRestore) failed++
                                        continue
                                    }
                                }
                                // Only a server-confirmed occurrence suppresses a newly discovered receipt.
                                // Pending journals above must still finish delivery/consume checkpoints.
                                // A renewed subscription may retain its token but change its purchase time.
                                val purchaseRefHash = digest(receipt.purchaseToken)
                                if (!explicitRestore && !(context.market == InappifyMarket.MYKET &&
                                        resolvedType == InappifyProductType.SUBSCRIPTION.name) && resolvedType in setOf(
                                        InappifyProductType.NON_CONSUMABLE.name, InappifyProductType.SUBSCRIPTION.name) &&
                                    confirmedCustomerInfo?.entitlements.orEmpty().any {
                                        it.purchaseStoreRefHash == purchaseRefHash &&
                                            it.purchaseStoreTime == receipt.purchaseTimeMillis
                                    }) continue
                                // Never restore a consumable merely because it appears in owned inventory.
                                if (explicitRestore && pendingType == PendingStoreProductType.LEGACY_IN_APP &&
                                    declaredType != InappifyProductType.NON_CONSUMABLE.name &&
                                    !(context.market == InappifyMarket.MYKET && declaredType == InappifyProductType.SUBSCRIPTION.name)) continue
                                // Locally verified receipts outlive catalog changes. Forward their
                                // original bound offering; only the server can accept the payment.
                                // Explicit restore never grants consumables from owned inventory.
                                val type = when (resolvedType) {
                                    InappifyProductType.CONSUMABLE.name -> PendingStoreProductType.CONSUMABLE
                                    InappifyProductType.NON_CONSUMABLE.name -> PendingStoreProductType.NON_CONSUMABLE
                                    InappifyProductType.SUBSCRIPTION.name -> PendingStoreProductType.SUBSCRIPTION
                                    else -> pendingType
                                }
                                val originalOperationId = if (explicitRestore && !unboundSubscription) "restore:${digest(if (context.market == InappifyMarket.MYKET) "myket:${receipt.purchaseToken}" else receipt.purchaseToken).take(64)}" else attempt
                                val operationId = subscriptionRecoveryId(originalOperationId, type, receipt,
                                    context, operationsById[originalOperationId])
                                // A recovered callback must not overwrite another paid receipt's journal.
                                if (!reservedOperationIds.add(operationId)) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val pending = PendingStoreOperation(
                                    id = operationId,
                                    operation = if (unboundSubscription) PendingStoreOperationType.RECOVER_SUBSCRIPTION
                                        else if (explicitRestore) PendingStoreOperationType.RESTORE
                                        else PendingStoreOperationType.PURCHASE,
                                    store = context.store, customerToken = context.customerToken,
                                    customerIdentifierFingerprint = context.customerIdentifierFingerprint,
                                    apiKeyFingerprint = context.apiKeyFingerprint,
                                    appIdentifier = context.appIdentifier, appId = context.appId,
                                    productIdentifier = receipt.productIdentifier,
                                    offeringIdentifier = offeringId,
                                    productType = type,
                                    evidence = PendingStorePurchaseEvidence(receipt.purchaseToken,
                                        receipt.orderIdentifier.takeIf(String::isNotBlank), receipt.packageName,
                                        if (unboundSubscription) receipt.developerPayload else receipt.developerPayload.takeIf(String::isNotBlank),
                                        receipt.originalJson.takeIf(String::isNotBlank),
                                        receipt.signature.takeIf(String::isNotBlank), receipt.purchaseTimeMillis),
                                    createdAtEpochMillis = now())
                                operationsById[operationId] = pending
                                if (storeTombstone(pending, context) in recovery.rejectedEvidenceTombstones) {
                                    if (explicitRestore) failed++
                                    continue
                                }
                                val outcome = coordinator.submit(pending, context)
                                finalizeRejectedStoreOutcome(outcome, context)
                                when (outcome) {
                                    is StoreV2Outcome.Terminal, is StoreV2Outcome.PendingDelivery -> {
                                        purchases += storePurchaseResult(outcome, null)
                                        if (explicitRestore && outcome is StoreV2Outcome.Terminal) {
                                            if (outcome.state.status == StorePurchaseStatus.ALREADY_PROCESSED) already++
                                            else restored++
                                        }
                                    }
                                    is StoreV2Outcome.Deferred -> {
                                        firstError = firstError ?: deferredStoreError(outcome)
                                        if (explicitRestore) failed++
                                    }
                                    is StoreV2Outcome.Rejected -> { if (explicitRestore) failed++ else firstError = firstError ?: outcome.error }
                                    is StoreV2Outcome.Failure -> { if (explicitRestore) failed++ else firstError = firstError ?: outcome.error }
                                }
                            }
                        }
                    }
                }
            } finally { releaseBillingAdapter(adapter) }
        }
        if (queryOwned && queryFailures > 0 && (!explicitRestore || queryFailures == 2))
            firstError = firstError ?: InappifyError(InappifyErrorCode.STORE_UNAVAILABLE,
                "The marketplace could not return all owned purchases.", isRetryable = true)
        return StoreReconciliation(purchases, InappifyRestoreResult(restored, already, failed), firstError)
    }
    private fun subscriptionRecoveryReceiptId(receipt: StorePurchase, context: StoreV2Context): String {
        val pieces = listOf(context.store, context.appIdentifier, context.appId.toString(),
            context.apiKeyFingerprint, context.customerIdentifierFingerprint,
            receipt.productIdentifier, receipt.purchaseToken, receipt.purchaseTimeMillis.toString())
        return "subscription-recovery:${digest(pieces.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })}"
    }
    private fun subscriptionRecoveryId(originalId: String, type: PendingStoreProductType,
        receipt: StorePurchase, context: StoreV2Context, previous: PendingStoreOperation?): String {
        // Never replace an existing paid attempt, including one owned by another account/store.
        // Only another occurrence of the same bound subscription gets its own local checkpoint.
        if (type != PendingStoreProductType.SUBSCRIPTION || previous == null ||
            previous.productType != PendingStoreProductType.SUBSCRIPTION ||
            previous.store != context.store || previous.appIdentifier != context.appIdentifier ||
            previous.appId != context.appId || previous.apiKeyFingerprint != context.apiKeyFingerprint ||
            previous.customerIdentifierFingerprint != context.customerIdentifierFingerprint ||
            previous.productIdentifier != receipt.productIdentifier ||
            previous.evidence.purchaseToken != receipt.purchaseToken ||
            previous.evidence.purchaseTimeMillis == null ||
            previous.evidence.purchaseTimeMillis == receipt.purchaseTimeMillis) return originalId
        val pieces = listOf(context.store, context.appIdentifier, context.appId.toString(),
            context.apiKeyFingerprint, context.customerIdentifierFingerprint,
            receipt.productIdentifier, receipt.purchaseToken, receipt.purchaseTimeMillis.toString())
        return "renewal:${digest(pieces.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })}"
    }
    private suspend fun syncStoreConsumables(
        confirmedCustomerInfo: InappifyCustomerInfo? = snapshot.customerInfo): InappifyConsumableSyncResult {
        if (nativeStoreMarket(snapshot.storePlatform) == null) fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
        // Rebuild missing encrypted checkpoints from the store's owned receipts first.
        val pendingConsumableIds = storage.loadPendingStoreOperations().filter {
            it.productType in setOf(PendingStoreProductType.CONSUMABLE, PendingStoreProductType.LEGACY_IN_APP)
        }.mapTo(mutableSetOf()) { it.id }
        val reconciliation = reconcileStorePurchases(queryOwned = true, explicitRestore = false,
            confirmedCustomerInfo = confirmedCustomerInfo)
        var firstError = reconciliation.error
        val reconciled = reconciliation.purchases
        val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
        val context = storeContext()
        val operations = storage.loadPendingStoreOperations().filter { it.store == context.store &&
            it.apiKeyFingerprint == context.apiKeyFingerprint &&
            it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
            it.appId == context.appId && it.appIdentifier == context.appIdentifier }
        val pending = mutableListOf<InappifyConsumableDelivery>()
        var completed = reconciled.count { it.attemptId in pendingConsumableIds && it.storePurchaseStatus in setOf(
            InappifyStorePurchaseStatus.COMPLETED, InappifyStorePurchaseStatus.ALREADY_PROCESSED) }
        val reconciledCompleted = completed
        for (operation in operations) {
            val outcome = coordinator.resume(operation, context)
            finalizeRejectedStoreOutcome(outcome, context)
            if (outcome is StoreV2Outcome.Terminal) { completed++; continue }
            if (outcome !is StoreV2Outcome.PendingDelivery) {
                firstError = firstError ?: when (outcome) {
                    is StoreV2Outcome.Deferred -> deferredStoreError(outcome)
                    is StoreV2Outcome.Rejected -> outcome.error
                    is StoreV2Outcome.Failure -> outcome.error
                    else -> null
                }
                continue
            }
            val deliveryId = outcome.state.deliveryId ?: continue
            val delivery = InappifyConsumableDelivery(deliveryId, operation.productIdentifier,
                null, context.market.deliverySource)
            val handler = deliveryHandler
            val delivered = if (handler == null) false else try {
                handler.deliver(delivery) == InappifyDeliveryResult.DELIVERED
            } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (delivered) {
                val confirmed = coordinator.confirmDelivery(outcome.operation, context)
                finalizeRejectedStoreOutcome(confirmed, context)
                if (confirmed is StoreV2Outcome.Terminal) { completed++; continue }
                if (confirmed !is StoreV2Outcome.PendingDelivery) firstError = firstError ?: when (confirmed) {
                    is StoreV2Outcome.Deferred -> deferredStoreError(confirmed)
                    is StoreV2Outcome.Rejected -> confirmed.error
                    is StoreV2Outcome.Failure -> confirmed.error
                    else -> null
                }
            }
            pending += delivery
        }
        firstError?.let { throw V2Failure(it) }
        return InappifyConsumableSyncResult(operations.size + reconciledCompleted, completed, pending)
    }
    private class DirectReconciliation(val result: InappifyConsumableSyncResult, val purchases: List<InappifyPurchase>)

    // These checkpoints live inside the encrypted, signed-identity-bound Go document.
    // They never modify or import the V1/store purchase journal. Endpoint changes cannot replay them.
    private fun directRecords(): JsonObject {
        val saved = document.getAsJsonObject("directDeliveries") ?: return JsonObject()
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        return if (saved.string("endpoint") == storeFingerprint(opt)) saved.getAsJsonObject("records").deepCopy()
            else JsonObject()
    }
    private suspend fun saveDirectRecords(records: JsonObject) {
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        // Keep pending acknowledgements; retain a bounded terminal cache for idempotent confirms.
        records.entrySet().filter { it.value.asJsonObject.getAsJsonObject("delivery").string("status") == "COMPLETED" }
            .dropLast(128).map { it.key }.forEach(records::remove)
        commit(document.deepCopy().apply {
            add("directDeliveries", JsonObject().apply {
                addProperty("endpoint", storeFingerprint(opt)); add("records", records)
            })
        })
    }
    private suspend fun syncDirectConsumables(): DirectReconciliation {
        requireSession()
        if (snapshot.storePlatform != "DirectAndroid") fail("CONFIGURATION", "PURCHASE_ROUTE_CHANGED")
        val discovered = linkedMapOf<Long, GoV2DirectDelivery>()
        val pending = linkedMapOf<Long, InappifyConsumableDelivery>()
        val completed = mutableSetOf<Long>()
        val purchases = linkedMapOf<Long, InappifyPurchase>()
        val handled = mutableSetOf<Long>()
        var firstError: V2Failure? = null
        // A previous ACK may have committed even when pending no longer lists it.
        // Resume only host-granted records of this verified identity and commerce endpoint.
        for ((_, record) in directRecords().entrySet()) {
            val saved = record.asJsonObject
            val delivery = GoV2DirectDelivery(saved.getAsJsonObject("delivery"))
            if (delivery.completed || !saved.flag("hostDelivered")) continue
            discovered[delivery.id] = delivery
            pending[delivery.id] = delivery.publicDelivery()
            handled += delivery.id
            try {
                purchases[delivery.id] = observeDirectRecovery(confirmDirectDelivery(delivery.id))
                pending.remove(delivery.id); completed += delivery.id
            } catch (failure: V2Failure) {
                observeDirectFailure(delivery, failure)
                if (firstError == null) firstError = failure
            }
        }
        for (batch in 0 until 100) {
            val response = commerceRequest("consumable-deliveries/pending", directTrackingRequest()).body
            val elements = response.getAsJsonObject("data")?.get("deliveries")
                ?.takeIf { it.isJsonArray }?.asJsonArray ?: fail("DECODING", "INVALID_DIRECT_DELIVERIES")
            val deliveries = elements.map { element ->
                if (!element.isJsonObject) fail("DECODING", "INVALID_DIRECT_DELIVERY")
                GoV2DirectDelivery(element.asJsonObject)
            }
            if (deliveries.map { it.id }.toSet().size != deliveries.size) fail("DECODING", "DUPLICATE_DELIVERY_ID")
            val records = directRecords()
            val fresh = deliveries.count { !discovered.containsKey(it.id) }
            for (delivery in deliveries) {
                val previous = records.getAsJsonObject(delivery.id.toString())
                previous?.getAsJsonObject("delivery")?.let { saved ->
                    if (GoV2DirectDelivery(saved).product != delivery.product ||
                        GoV2DirectDelivery(saved).transaction != delivery.transaction ||
                        GoV2DirectDelivery(saved).attemptId?.let { it != delivery.attemptId } == true ||
                        GoV2DirectDelivery(saved).offering?.let { it != delivery.offering } == true)
                        fail("DECODING", "DELIVERY_SCOPE_MISMATCH")
                    if (GoV2DirectDelivery(saved).completed && !delivery.completed)
                        fail("DECODING", "DELIVERY_STATE_REGRESSION")
                }
                records.add(delivery.id.toString(), JsonObject().apply {
                    add("delivery", delivery.json.deepCopy())
                    addProperty("hostDelivered", previous?.get("hostDelivered")?.asBoolean == true)
                })
            }
            if (deliveries.isNotEmpty()) saveDirectRecords(records)
            var progress = false
            for (delivery in deliveries) {
                discovered[delivery.id] = delivery
                purchases[delivery.id] = observeDirectRecovery(delivery.purchase())
                if (delivery.completed) {
                    completed += delivery.id; pending.remove(delivery.id); continue
                }
                pending[delivery.id] = delivery.publicDelivery()
                if (!handled.add(delivery.id)) continue
                var granted = records.getAsJsonObject(delivery.id.toString()).get("hostDelivered").asBoolean
                val handler = deliveryHandler
                if (!granted && handler != null) granted = withContext(Dispatchers.IO) {
                    try { handler.deliver(delivery.publicDelivery()) == InappifyDeliveryResult.DELIVERED }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                }
                if (granted) try {
                    purchases[delivery.id] = observeDirectRecovery(confirmDirectDelivery(delivery.id))
                    pending.remove(delivery.id); completed += delivery.id; progress = true
                } catch (failure: V2Failure) {
                    observeDirectFailure(delivery, failure)
                    if (firstError == null) firstError = failure
                }
            }
            if (firstError != null || deliveries.size < 100 || !progress || fresh == 0) break
        }
        val attemptFailure = syncDirectPurchaseAttempts()
        if (firstError == null) firstError = attemptFailure
        firstError?.let { throw it }
        return DirectReconciliation(InappifyConsumableSyncResult(discovered.size, completed.size, pending.values.toList()),
            purchases.values.toList())
    }
    private suspend fun observeDirectRecovery(purchase: InappifyPurchase): InappifyPurchase {
        recordDirectAttempt(purchase)
        currentCoroutineContext()[RecoveryPass]?.record(RecoveredPurchase(purchase.attemptId,
            purchase.productIdentifier, InappifyResult.Success(purchase, snapshot)))
        return purchase
    }
    private suspend fun observeDirectFailure(delivery: GoV2DirectDelivery, failure: V2Failure) {
        val purchase = delivery.purchase()
        recordDirectAttempt(purchase, failure.sdkError)
        currentCoroutineContext()[RecoveryPass]?.record(RecoveredPurchase(purchase.attemptId,
            purchase.productIdentifier, InappifyResult.Failure(failure.sdkError, snapshot)))
    }
    private suspend fun confirmDirectDelivery(deliveryId: Long): InappifyPurchase {
        requireSession()
        if (!sdkApi.enabled || commerceApi?.enabled == false) fail("CONFIGURATION", "V2_DISABLED")
        val records = directRecords()
        val record = records.getAsJsonObject(deliveryId.toString()) ?: fail("VALIDATION", "DELIVERY_NOT_FOUND")
        val expected = GoV2DirectDelivery(record.getAsJsonObject("delivery"))
        if (expected.completed) return expected.purchase().also { recordDirectAttempt(it) }
        // The public confirm API is the host's durable-grant assertion. Persist before HTTP
        // so a timeout/process restart retries acknowledgement without calling the handler again.
        record.addProperty("hostDelivered", true)
        saveDirectRecords(records)
        val delivered = try {
            val response = commerceRequest("consumable-deliveries/$deliveryId/delivered", directTrackingRequest()).body
            val data = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: fail("DECODING", "INVALID_DIRECT_DELIVERY")
            GoV2DirectDelivery(data).also {
                if (it.id != deliveryId || it.product != expected.product ||
                    it.transaction != expected.transaction || !it.completed ||
                    expected.attemptId?.let { original -> original != it.attemptId } == true ||
                    expected.offering?.let { original -> original != it.offering } == true)
                    fail("DECODING", "DELIVERY_SCOPE_MISMATCH")
            }
        } catch (failure: V2Failure) {
            val error = failure.sdkError
            val uncertain = error.details["category"] in setOf("NETWORK", "TIMEOUT", "DECODING") ||
                (error.details["httpStatus"] as? Int)?.let { it >= 500 } == true
            if (!uncertain) throw failure
            throw V2Failure(InappifyError(error.code, error.message, error.isRetryable,
                error.details + mapOf("outcomeMayHaveCommitted" to true)))
        }
        record.add("delivery", delivered.json.deepCopy())
        recordDirectAttempt(delivered.purchase(), delivery = delivered)
        saveDirectRecords(records)
        return delivered.purchase()
    }
    private fun directTrackingRequest(): JsonObject = JsonObject().apply { addProperty("purchaseAttemptTrackingVersion", 1) }
    private suspend fun recordDirectAttempt(purchase: InappifyPurchase, error: InappifyError? = null, delivery: GoV2DirectDelivery? = null) {
        val binding = currentAttemptBinding()
        val context = delivery ?: purchase.deliveryId?.let { directRecords().getAsJsonObject(it.toString()) }
            ?.getAsJsonObject("delivery")?.let(::GoV2DirectDelivery)
        val original = context?.attemptId?.let { id -> attemptHistory.list(binding).firstOrNull { it.attemptId == id } }
        if (original != null && (original.productIdentifier != context?.product || original.offeringIdentifier != context?.offering))
            fail("DECODING", "DIRECT_PURCHASE_ATTEMPT_SCOPE_MISMATCH")
        val tracked = if (context?.attemptId != null) InappifyPurchase.storeResult(context.attemptId, context.product,
            requireNotNull(context.offering), original?.purchase?.packageIdentifier,
            if (purchase.purchaseStatus == InappifyPurchaseStatus.DONE) InappifyStorePurchaseStatus.COMPLETED else InappifyStorePurchaseStatus.DELIVERY_REQUIRED,
            context.id, null, false, InappifyMarket.NONE) else purchase
        if (tracked.attemptId != purchase.attemptId && attemptHistory.list(binding).any { it.attemptId == purchase.attemptId }) {
            recordAttempt(binding, InappifyPurchaseAttempt(purchase.attemptId, purchase.productIdentifier,
                purchase.offeringIdentifier, purchase.market, if (purchase.purchaseStatus == InappifyPurchaseStatus.DONE)
                    InappifyPurchaseAttemptStatus.SUCCEEDED else InappifyPurchaseAttemptStatus.PENDING,
                purchase, error?.let(::safeAttemptError), now()), emit = false)
        }
        recordAttempt(binding, InappifyPurchaseAttempt(tracked.attemptId, tracked.productIdentifier,
            tracked.offeringIdentifier, tracked.market, if (tracked.purchaseStatus == InappifyPurchaseStatus.DONE)
                InappifyPurchaseAttemptStatus.SUCCEEDED else InappifyPurchaseAttemptStatus.PENDING,
            tracked, error?.let(::safeAttemptError), now()), directTracking = context?.attemptId != null)
    }
    private fun validateDirectAttempt(value: GoV2DirectAttempt, id: String, product: String, offering: String,
        deliveryId: Long? = null) {
        if (value.id != id || value.product != product || value.offering != offering ||
            deliveryId?.let { it != value.deliveryId } == true ||
            value.url?.let { !allowedUrl(it, config.paymentHosts) } == true)
            fail("DECODING", "DIRECT_PURCHASE_ATTEMPT_SCOPE_MISMATCH")
    }
    private suspend fun syncDirectPurchaseAttempts(): V2Failure? {
        val binding = currentAttemptBinding()
        var firstError: V2Failure? = null
        for (batch in attemptHistory.pendingDirect(binding).chunked(100)) {
            val known = batch.associateBy { it.attemptId }
            try {
                val response = commerceRequest("purchase-attempts/status", JsonObject().apply {
                    add("attemptIds", JsonArray().apply { batch.forEach { add(it.attemptId) } })
                }).body
                val rows = response.getAsJsonObject("data")?.get("attempts")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: fail("DECODING", "INVALID_DIRECT_PURCHASE_ATTEMPTS")
                if (rows.size() > batch.size) fail("DECODING", "INVALID_DIRECT_PURCHASE_ATTEMPTS")
                val parsed = rows.map { GoV2DirectAttempt(it.asJsonObject) }
                if (parsed.map { it.id }.toSet().size != parsed.size) fail("DECODING", "INVALID_DIRECT_PURCHASE_ATTEMPTS")
                parsed.forEach { value ->
                    val saved = known[value.id] ?: fail("DECODING", "DIRECT_PURCHASE_ATTEMPT_SCOPE_MISMATCH")
                    validateDirectAttempt(value, saved.attemptId, saved.productIdentifier, saved.offeringIdentifier,
                        saved.purchase?.deliveryId)
                }
                parsed.forEach { value ->
                    recordAttempt(binding, value.result(known.getValue(value.id).purchase?.packageIdentifier, now()), directTracking = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: V2Failure) {
                // Missing (including an older server), unrelated and unavailable results never clear an attempt.
                if (firstError == null) firstError = failure
                batch.forEach { saved -> recordAttempt(binding, InappifyPurchaseAttempt(saved.attemptId,
                    saved.productIdentifier, saved.offeringIdentifier, saved.market, saved.status, saved.purchase,
                    safeAttemptError(failure.sdkError), now()), directTracking = true) }
            } catch (_: Exception) {
                if (firstError == null) firstError = V2Failure(InappifyError(InappifyErrorCode.MALFORMED_RESPONSE,
                    "Invalid direct purchase status response."))
                batch.forEach { saved -> recordAttempt(binding, InappifyPurchaseAttempt(saved.attemptId,
                    saved.productIdentifier, saved.offeringIdentifier, saved.market, saved.status, saved.purchase,
                    InappifyError(InappifyErrorCode.MALFORMED_RESPONSE, "Invalid direct purchase status response."), now()), directTracking = true) }
            }
        }
        return firstError
    }
    override suspend fun syncStorePurchases(): InappifyResult<List<InappifyPurchase>> {
        val result = run {
            requireSession()
            if (snapshot.storePlatform == "DirectAndroid") syncDirectConsumables().purchases
            else reconcileStorePurchases(queryOwned = true, explicitRestore = false).let {
                it.error?.let { error -> throw V2Failure(error) }
                it.purchases
            }
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun restorePurchasesV2(): InappifyResult<InappifyRestoreResult> {
        val result = run {
            requireSession()
            if (snapshot.storePlatform == "DirectAndroid") syncDirectConsumables().let {
                InappifyRestoreResult(it.result.completedCount, 0, 0)
            }
            else reconcileStorePurchases(queryOwned = true, explicitRestore = true).let {
                it.error?.let { error -> throw V2Failure(error) }
                it.restoreResult
            }
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun confirmDeliveryV2(deliveryId: Long): InappifyResult<InappifyPurchase> {
        val result = run {
            requireSession()
            if (deliveryId <= 0) fail("VALIDATION", "INVALID_DELIVERY_ID")
            if (snapshot.storePlatform == "DirectAndroid") return@run confirmDirectDelivery(deliveryId)
            if (nativeStoreMarket(snapshot.storePlatform) == null) fail("CONFIGURATION", "UNSUPPORTED_STORE_PLATFORM")
            val coordinator = storeCoordinator ?: fail("CONFIGURATION", "LARAVEL_V2_NOT_CONFIGURED")
            val context = storeContext()
            val pending = storage.loadPendingStoreOperations().firstOrNull { it.deliveryId == deliveryId &&
                it.store == context.store && it.appIdentifier == context.appIdentifier &&
                it.apiKeyFingerprint == context.apiKeyFingerprint &&
                it.customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
                it.appId == context.appId } ?: fail("VALIDATION", "DELIVERY_NOT_FOUND")
            val outcome = coordinator.confirmDelivery(pending, context)
            finalizeRejectedStoreOutcome(outcome, context)
            storePurchaseResult(outcome, null)
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override suspend fun syncPendingConsumablesInternal(): InappifyResult<InappifyConsumableSyncResult> =
        syncPendingConsumablesWithServerDedup(deduplicate = true)

    private suspend fun syncPendingConsumablesWithServerDedup(deduplicate: Boolean): InappifyResult<InappifyConsumableSyncResult> {
        val result = shared("consumables:${snapshot.appUserIdentifier}:$deduplicate") {
            run {
                requireSession()
                if (snapshot.storePlatform == "DirectAndroid") syncDirectConsumables().result
                else syncStoreConsumables(if (deduplicate) snapshot.customerInfo else null)
            }
        }
        if (result is InappifyResult.Success) refreshCustomerInfo()
        return result.withSnapshot()
    }
    override fun setConsumableDeliveryHandlerInternal(handler: InappifyConsumableDeliveryHandler): InappifyListenerRegistration {
        check(!closed)
        deliveryHandler = handler
        return InappifyListenerRegistration.create(Runnable {
            if (deliveryHandler === handler) {
                deliveryHandler = null
            }
        })
    }
    internal suspend fun recover(): InappifyResult<Unit> {
        check(!closed)
        val work = recoveryCoordinator.request(automatic = false)
        rememberReturnRecovery()
        return work.await()
    }

    private suspend fun recoverOnce(automatic: Boolean): InappifyResult<Unit> {
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Starting ${if (automatic) "automatic" else "internal"} recovery." }
        if (pendingLogout) return recordRecoveryResult(logOut())
        if (pendingLogin) {
            val loginRecovery = run { resumeLogin() }
            if (loginRecovery is InappifyResult.Failure) return recordRecoveryResult(loginRecovery)
            // This pass completed the pending transition itself; continue for its accepted identity.
            return withContext(RecoveryPass(recoveryGeneration)) {
                recordRecoveryResult(recoverConfiguredSession(automatic))
            }
        }
        return recordRecoveryResult(recoverConfiguredSession(automatic))
    }

    private suspend fun recordRecoveryResult(result: InappifyResult<Unit>): InappifyResult<Unit> {
        val generation = currentCoroutineContext()[RecoveryPass]!!.generation
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Recovery ${if (result is InappifyResult.Success) "completed" else "deferred after failure"}." }
        // Serialize completion with event admission. An event either joins the still-active
        // pass or sees its completed timestamp; it cannot launch a late duplicate between them.
        mutex.withLock { recoveryRefreshPolicy.completed(generation, result is InappifyResult.Success) }
        return result
    }

    private suspend fun recoverConfiguredSession(automatic: Boolean): InappifyResult<Unit> {
        val info = refreshCustomerInfo()
        if (nativeStoreMarket(snapshot.storePlatform) != null) {
            // Store recovery must not depend on catalog availability. A failed info refresh
            // also cannot turn stale cached entitlements into a reason to drop owned evidence.
            val sync = syncPendingConsumablesWithServerDedup(deduplicate = info is InappifyResult.Success)
            val offerings = if (automatic) refreshOfferingsIfNeeded() else refreshOfferings()
            if (info is InappifyResult.Failure) return info
            if (sync is InappifyResult.Failure) return sync
            if (offerings is InappifyResult.Failure) return offerings
            return InappifyResult.Success(Unit, snapshot)
        }
        if (info is InappifyResult.Failure) return info
        val offerings = if (automatic) refreshOfferingsIfNeeded() else refreshOfferings()
        if (offerings is InappifyResult.Failure) return offerings
        if (snapshot.storePlatform == "DirectAndroid") {
            val sync = syncPendingConsumables()
            if (sync is InappifyResult.Failure) return sync
        }
        return InappifyResult.Success(Unit, snapshot)
    }
    override fun setNetworkEnabled(enabled: Boolean) {
        val reconnecting = enabled && !sdkApi.enabled
        sdkApi.enabled = enabled
        commerceApi?.enabled = enabled
        if (reconnecting) scheduleRecoveryIfNeeded()
        if (enabled) { scheduleOfferingsRefresh(); scheduleCustomerInfoRefresh() }
    }
    private fun scheduleRecovery() {
        // Called while Configure/Login/Logout owns the state mutex. Startup still forces a
        // pass, except while offline/disabled or inside this customer's error backoff.
        if (canRecoverAutomatically() &&
            recoveryRefreshPolicy.shouldRecover(recoveryGeneration, pending = false, startup = true))
            recoveryCoordinator.request(automatic = true).also { rememberReturnRecovery() }
        wakePaymentReturns()
    }
    private fun canRecoverAutomatically(): Boolean = backgroundRecovery && !closed && options != null &&
        document.has("session") && sdkApi.enabled && commerceApi?.enabled != false && connectivityMonitor.isConnected

    private fun scheduleRecoveryIfNeeded() {
        wakePaymentReturns()
        if (!backgroundRecovery || closed || recoveryCoordinator.isRunning()) return
        scope.launch {
            mutex.withLock {
                if (!canRecoverAutomatically() || recoveryCoordinator.isRunning()) return@withLock
                val pending = try { hasPendingRecovery() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { true } // Let ordinary recovery report/check unreadable checkpoints.
                if (recoveryRefreshPolicy.shouldRecover(recoveryGeneration, pending, startup = false))
                    recoveryCoordinator.request(automatic = true).also { rememberReturnRecovery() }
            }
        }
    }

    // Read existing checkpoints only. Admission must not renew a session, query inventory,
    // acknowledge delivery, or write a scheduling timestamp into encrypted storage.
    private suspend fun hasPendingRecovery(): Boolean {
        if (pendingLogin || pendingLogout) return true
        if (snapshot.storePlatform == "DirectAndroid")
            return directRecords().entrySet().any { (_, record) ->
                record.asJsonObject.getAsJsonObject("delivery").string("status") != "COMPLETED"
            } || attemptHistory.pendingDirect(currentAttemptBinding()).isNotEmpty()
        val market = nativeStoreMarket(snapshot.storePlatform) ?: return false
        val opt = options ?: return false
        val session = document.getAsJsonObject("session") ?: return false
        val recovery = storage.loadPendingStoreRecoveryState() ?: return true
        return recovery.operations.any { it.store == market.storeId &&
            it.apiKeyFingerprint == storeFingerprint(opt) &&
            it.customerIdentifierFingerprint == digest(session.string("appUserId")) &&
            it.appId == session.number("appId") && it.appIdentifier == metadata.get().packageIdentifier }
    }

    override fun handleDeepLink(url: String): Boolean {
        if (closed) return false
        val link = PaymentReturnLink.parse(url) ?: return false
        if (verifiedScope?.appId?.let { it != link.appId } == true) return false
        val preceding = recoveryCoordinator.active()
        synchronized(paymentReturnLock) {
            if (link in handledReturns) return true
            if (!pendingReturns.containsKey(link)) {
                if (pendingReturns.size == 32) pendingReturns.remove(pendingReturns.keys.first())
                pendingReturns[link] = PendingReturn(link, preceding)
            }
        }
        InappifyLogger.log(InappifyLogLevel.DEBUG) { "Accepted a marked payment return for recovery." }
        wakePaymentReturns()
        return true
    }

    private fun wakePaymentReturns() {
        if (!closed && synchronized(paymentReturnLock) { pendingReturns.isNotEmpty() }) paymentReturnWakeups.trySend(Unit)
    }

    private fun rememberReturnRecovery() {
        val flight = recoveryCoordinator.active() ?: return
        synchronized(paymentReturnLock) {
            pendingReturns.values.filter { it.preceding !== flight && it.following == null }
                .forEach { it.following = flight }
        }
    }

    private sealed interface ReturnStep {
        class Wait(val recovery: RecoveryCoordinator.Flight) : ReturnStep
        class Backoff(val millis: Long) : ReturnStep
        class Sync(val links: List<PaymentReturnLink>, val generation: Long,
            val work: Deferred<InappifyResult<Unit>>) : ReturnStep
    }

    private suspend fun recoverPaymentReturns() {
        while (true) {
            val step = mutex.withLock {
                if (closed || !snapshot.isConfigured || pendingLogin || pendingLogout || !sdkApi.enabled ||
                    commerceApi?.enabled == false || !connectivityMonitor.isConnected) return
                val appId = document.getAsJsonObject("session")?.number("appId") ?: return
                val batch = synchronized(paymentReturnLock) {
                    pendingReturns.keys.filter { it.appId != appId }.forEach(pendingReturns::remove)
                    pendingReturns.values.toList()
                }
                if (batch.isEmpty()) return
                val active = recoveryCoordinator.active()
                val following = batch.first().following?.takeIf { candidate ->
                    candidate.pass.generation == recoveryGeneration && batch.all { it.following === candidate }
                }
                // A pass admitted before this return may already have read the old payment
                // state. Finish it, then request fresh work; later startup work can be joined.
                if (following != null) ReturnStep.Sync(batch.map { it.link }, recoveryGeneration, following.result)
                else if (active != null && batch.any { it.preceding === active }) ReturnStep.Wait(active)
                else if (active != null) ReturnStep.Sync(batch.map { it.link }, recoveryGeneration, active.result)
                else {
                    val retry = recoveryRefreshPolicy.retryDelayMillis(recoveryGeneration)
                    if (retry > 0) ReturnStep.Backoff(retry)
                    else ReturnStep.Sync(batch.map { it.link }, recoveryGeneration, recoveryCoordinator.request(automatic = true))
                }
            }
            when (step) {
                is ReturnStep.Wait -> step.recovery.result.await()
                is ReturnStep.Backoff -> delay(step.millis)
                is ReturnStep.Sync -> {
                    val result = step.work.await()
                    if (result is InappifyResult.Success) mutex.withLock {
                        if (step.generation == recoveryGeneration) synchronized(paymentReturnLock) {
                            step.links.forEach { link -> pendingReturns.remove(link); handledReturns += link }
                            while (handledReturns.size > 128) handledReturns.remove(handledReturns.first())
                        }
                    }
                    else synchronized(paymentReturnLock) { step.links.forEach { pendingReturns[it]?.following = null } }
                    return // Failures remain pending; another event may retry after backoff.
                }
            }
        }
    }
    override fun addEventListener(listener: InappifyEventListener): InappifyListenerRegistration {
        check(!closed); listeners.add(listener)
        return InappifyListenerRegistration.create(Runnable { listeners.remove(listener) })
    }
    override fun addHttpTraceListenerInternal(listener: InappifyHttpTraceListener): InappifyListenerRegistration {
        check(!closed)
        val sdkRegistration = sdkApi.addTraceListener(listener)
        val commerceRegistration = commerceApi?.takeUnless { it === sdkApi }?.addTraceListener(listener)
        return InappifyListenerRegistration.create(Runnable {
            sdkRegistration.close()
            commerceRegistration?.close()
        })
    }
    override fun close() {
        val resources = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            (activeOperationJobs.toList() to activeBillingAdapters.toList()).also {
                activeOperationJobs.clear(); activeBillingAdapters.clear()
            }
        }
        resources.first.forEach { it.cancel(CancellationException("The Inappify client is closed.")) }
        resources.second.forEach { runCatching { it.close() } }
        foregroundMonitor.close()
        connectivityMonitor.close()
        paymentLinkMonitor.close()
        paymentReturnWakeups.close()
        scope.cancel(); sdkApi.close(); commerceApi?.close(); listeners.clear(); deliveryHandler = null
    }
    private fun requireSession() {
        if (!snapshot.isConfigured || pendingLogout || pendingLogin)
            fail("CONFIGURATION", when {
                pendingLogout -> "LOGOUT_PENDING"
                pendingLogin -> "LOGIN_PENDING"
                else -> "NOT_CONFIGURED"
            })
    }
    private suspend fun commit(next: JsonObject) {
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Closed")
        val opt = options ?: fail("CONFIGURATION", "NOT_CONFIGURED")
        if (!storage.save(PersistedSession(null, null, null, boundScope(next)?.appId, null,
                fingerprint(opt), next.toString(), null, null))) fail("CONFIGURATION", "SECURE_STORAGE_FAILED")
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Closed")
        document = next
        publish()
    }
    private fun publish() {
        val session = document.getAsJsonObject("session")
        pendingLogout = document.get("pendingLogout")?.asBoolean == true
        pendingLogin = document.has("pendingLogin")
        val rawOfferings = document.getAsJsonObject("offerings")
        val identity = session?.string("appUserId")
        if ((rawOfferings == null && snapshot.offerings != null) ||
            snapshot.appUserIdentifier != identity ||
            snapshot.country != normalizeCountry(document.get("country")?.asString ?: "IR") ||
            snapshot.appVersion != (document.get("appVersion")?.asString ?: metadata.get().versionName))
            offeringsRefreshPolicy.invalidate()
        val info = if (identity != null && !pendingLogout && !pendingLogin) verifier.verifyWithScope(
            document.getAsJsonObject("info") ?: session, identity, boundScope(), allowExpiredCache = true,
            expectedSubject = boundSubject()).info else null
        info?.get("requestDate")?.takeUnless { it.isJsonNull }?.let {
            val signedTime = isoMillis(info.string("requestDate"))
            trustedTimeFloor = maxOf(trustedNow(), signedTime)
            timeAnchorNanos = System.nanoTime()
        }
        val before = snapshot
        authenticatedScope = boundScope().takeIf { session != null && !pendingLogout && !pendingLogin }
        val nextSnapshot = InappifySnapshot(
            before.revision + 1, session != null && !pendingLogout && !pendingLogin,
            identity != null && !isAnonymous(identity) && !pendingLogout && !pendingLogin,
            if (pendingLogout || pendingLogin) null else identity, null,
            normalizeCountry(document.get("country")?.asString ?: "IR"),
            document.get("appVersion")?.asString ?: metadata.get().versionName, BuildConfig.SDK_VERSION,
            session?.get("storeInfo")?.takeUnless { it.isJsonNull }?.asString, boundScope()?.appId,
            info?.let { InappifyDomainJsonCodec.parseCustomerInfo(it.toString()).withEntitlementClock(::trustedNow) },
            if (pendingLogout || pendingLogin) null else rawOfferings?.let { InappifyDomainJsonCodec.parseOfferings(it.toString()) },
            false, false, session?.number("storePlatform")?.let { platform ->
                mapOf(1L to "DirectIos", 2L to "DirectAndroid", 3L to "DirectWeb", 5L to "PlayStore",
                    6L to "AppStore", 10L to "Bazar", 11L to "MyKet", 12L to "SibApp")[platform] ?: platform.toString()
            },
        )
        // Pending barriers hide UI state but do not replace the accepted session binding.
        // A rejected Login must not supersede recovery for the still-current customer.
        val subject = boundSubject()
        if (recoveryIdentity != identity || recoverySubject != subject) {
            recoveryIdentity = identity
            recoverySubject = subject
            recoveryGeneration++
            recoveryRefreshPolicy.reset(recoveryGeneration)
        }
        currentSnapshot = nextSnapshot
        InappifyLogger.log(InappifyLogLevel.VERBOSE) { "SDK state revision ${nextSnapshot.revision} published." }
        if (before.appUserIdentifier != nextSnapshot.appUserIdentifier)
            InappifyLogger.log(InappifyLogLevel.INFO) { "Customer identity changed." }
        if (before.customerInfo != nextSnapshot.customerInfo)
            InappifyLogger.log(InappifyLogLevel.DEBUG) { "CustomerInfo snapshot updated." }
        if (before.offerings != nextSnapshot.offerings)
            InappifyLogger.log(InappifyLogLevel.DEBUG) { "Offerings snapshot updated." }
        val published = snapshot
        val types = buildList {
            add(InappifyEventType.STATE_CHANGED)
            if (before.appUserIdentifier != published.appUserIdentifier) add(InappifyEventType.AUTHENTICATION_CHANGED)
            if (before.customerInfo != published.customerInfo) add(InappifyEventType.CUSTOMER_INFO_CHANGED)
            if (before.offerings != published.offerings) add(InappifyEventType.OFFERINGS_CHANGED)
        }
        scope.launch(eventDispatcher) {
            // Suppress queued events belonging to a previous identity.
            if (!closed && snapshot.revision == published.revision)
                types.forEach { type -> listeners.forEach { listener -> runCatching { listener.onEvent(InappifyEvent.create(type, published)) } } }
        }
    }
    private fun validateOptions(value: InappifyOptions) {
        val app = metadata.get()
        if (value.apiKey.isBlank() || value.apiKey.length > 4096 || value.apiKey.any(Char::isISOControl) ||
            app.packageIdentifier.isBlank() || app.packageIdentifier.length > 255 ||
            app.versionName.let { it.isBlank() || it.length > 50 } || app.versionCode < 1 ||
            (value.appUserIdentifier != null && !validIdentity(value.appUserIdentifier))) fail("CONFIGURATION", "INVALID_OPTIONS")
    }
    private fun fingerprint(value: InappifyOptions): String {
        config.expectedScope?.let { return explicitFingerprint(value, it) }
        // Stable before tenant scope is known, so first-request failures cannot rotate the UUID.
        val pieces = listOf("go-auto-v2", config.sdkApiBaseUrl, value.apiKey, metadata.get().packageIdentifier)
        return digest(pieces.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
    }
    private fun explicitFingerprint(value: InappifyOptions, scope: InappifyV2SessionScope): String =
        digest("${config.sdkApiBaseUrl}|${scope.issuer}|${scope.appId}|${scope.projectId}|${value.apiKey}|${metadata.get().packageIdentifier}")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun trustedNow(): Long = maxOf(now(), trustedTimeFloor +
        ((System.nanoTime() - timeAnchorNanos).coerceAtLeast(0) / 1_000_000))

    private fun <T> InappifyResult<T>.withSnapshot(): InappifyResult<T> = when (this) {
        is InappifyResult.Success -> InappifyResult.Success(data, this@GoV2Client.snapshot)
        is InappifyResult.Failure -> InappifyResult.Failure(error, this@GoV2Client.snapshot)
    }
    internal companion object {
        internal fun create(context: Context): GoV2Client = create(context, GoV2Environment.production())
        internal fun create(context: Context, unsafeRawHttpLogging: Boolean): GoV2Client =
            create(context, GoV2Environment.production(), unsafeRawHttpLogging)
        internal fun create(context: Context, configuration: InappifyV2Configuration): GoV2Client =
            create(context, GoV2Environment.explicit(configuration))
        private fun create(context: Context, configuration: GoV2Environment, unsafeRawHttpLogging: Boolean = false): GoV2Client {
            InappifyLogger.initializeDefault(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build()
            return GoV2Client(configuration, GoApi(OkHttpTransport.create(configuration.sdkApiBaseUrl.toHttpUrl(), client, unsafeRawHttpLogging), System::currentTimeMillis),
                EncryptedSessionStateStore.createGoV2(context), AndroidAppMetadataProvider(context),
                commerceApi = GoApi(OkHttpTransport.create(configuration.commerceApiBaseUrl.toHttpUrl(),
                    client.newBuilder().build(), unsafeRawHttpLogging), System::currentTimeMillis),
                billingFactory = AndroidStoreBillingAdapterFactory(context),
                foregroundMonitor = AndroidAppForegroundMonitor(),
                connectivityMonitor = AndroidNetworkConnectivityMonitor(context),
                paymentLinkMonitor = AndroidPaymentLinkMonitor(context))
        }
    }
}

internal fun anonymousId(): String = "\$INAAnonymousID:${UUID.randomUUID()}"
/** Native Go receipts belong to one commerce endpoint; SDK session-cache compatibility is separate. */
internal fun goStoreFingerprint(sessionFingerprint: String, commerceApiBaseUrl: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("go-store-v1:$sessionFingerprint:${commerceApiBaseUrl.toHttpUrl()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
internal fun isAnonymous(value: String): Boolean = value.matches(Regex(
    "\\\$INAAnonymousID:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|\\\$InaAnonymousId_[0-9A-HJKMNP-TV-Z]{26}"))
internal fun validIdentity(value: String): Boolean {
    if (isAnonymous(value)) return true
    // Go trims identifiers before storing them. Reject edge whitespace locally
    // so the response identity still matches the exact requested value.
    if (value.isBlank() || value != value.trim() || !Charsets.UTF_8.newEncoder().canEncode(value) ||
        value.codePointCount(0, value.length) > 100 ||
        value.any(Char::isISOControl)) return false
    val lower = value.lowercase(Locale.ROOT)
    return !lower.startsWith("\$inaanonymousid:") && !lower.startsWith("\$inaanonymousid_")
}
internal fun validCountry(value: String): Boolean = value.trim().matches(Regex("[A-Za-z]{2}"))
internal fun normalizeCountry(value: String): String = value.trim().uppercase(Locale.ROOT)
internal val reservedKeys = setOf("\$email", "\$displayName", "\$apnsTokens", "\$fcmTokens", "\$idfa", "\$idfv", "\$ip", "\$phoneNumber", "\$campaign", "\$keyword")
internal fun validAttribute(key: String, value: String?): Boolean =
    (key in reservedKeys || key.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}"))) &&
        (value == null || value.codePointCount(0, value.length) <= 500)
internal fun allowedUrl(raw: String, hosts: Set<String>): Boolean = runCatching {
    val url = raw.toHttpUrl()
    url.isHttps && url.port == 443 && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
}.getOrDefault(false)
