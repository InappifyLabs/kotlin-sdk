package com.inappify.sdk

import android.content.Context
import com.inappify.sdk.internal.v2.GoV2Client
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Collections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Configuration for the V2-only session service. */
public class InappifyV2Configuration public constructor(
    public val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    public val issuer: String,
    public val appId: Long,
    public val projectId: Long,
    pinnedSigningKeys: Map<String, String>,
    paymentHosts: Set<String>,
    assetHosts: Set<String> = emptySet(),
) {
    /** Alias for the SDK service endpoint. */
    public val sdkApiBaseUrl: String get() = apiBaseUrl
    public var commerceApiBaseUrl: String = defaultCommerceBaseUrl(apiBaseUrl)
        private set

    /** Adds a separate commerce endpoint without replacing the original Kotlin default constructor. */
    public constructor(
        sdkApiBaseUrl: String = DEFAULT_SDK_API_BASE_URL,
        issuer: String,
        appId: Long,
        projectId: Long,
        pinnedSigningKeys: Map<String, String>,
        paymentHosts: Set<String>,
        assetHosts: Set<String> = emptySet(),
        commerceApiBaseUrl: String,
    ) : this(sdkApiBaseUrl, issuer, appId, projectId, pinnedSigningKeys, paymentHosts, assetHosts) {
        validateBaseUrl(commerceApiBaseUrl, "Commerce API")
        this.commerceApiBaseUrl = commerceApiBaseUrl
    }
    /** kid to unpadded base64url encoded, raw 32-byte Ed25519 public key. */
    public val pinnedSigningKeys: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(pinnedSigningKeys))
    public val paymentHosts: Set<String> = Collections.unmodifiableSet(LinkedHashSet(paymentHosts))
    public val assetHosts: Set<String> = Collections.unmodifiableSet(LinkedHashSet(assetHosts))
    init {
        validateBaseUrl(apiBaseUrl, "SDK API")
        validateBaseUrl(commerceApiBaseUrl, "Commerce API")
        require(issuer.isNotBlank() && appId > 0 && projectId > 0)
        require(pinnedSigningKeys.isNotEmpty() && pinnedSigningKeys.keys.all { it.isNotBlank() })
        require((paymentHosts + assetHosts).all { host ->
            host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) && host.contains('.')
        }) { "URL allowlists require exact lowercase host names, without wildcards." }
    }
    public override fun toString(): String = "InappifyV2Configuration(appId=$appId, projectId=$projectId)"

    public companion object {
        /** Official /app service root plus the Go v2 route prefix; commerce uses a separate V2 origin. */
        public const val DEFAULT_API_BASE_URL: String = "https://service.inappify.com/app/v2/"
        public const val DEFAULT_SDK_API_BASE_URL: String = "https://service.inappify.com/app/v2/"
        public const val DEFAULT_COMMERCE_API_BASE_URL: String = "https://api.inappify.com/app/v2/"

        private fun defaultCommerceBaseUrl(sdkUrl: String): String =
            if (sdkUrl == DEFAULT_SDK_API_BASE_URL) DEFAULT_COMMERCE_API_BASE_URL else sdkUrl

        private fun validateBaseUrl(value: String, label: String) {
            val url = value.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null && url.encodedPath == "/app/v2/") {
                "$label base URL must be an HTTPS origin followed by /app/v2/."
            }
        }
    }
}

public enum class InappifyFetchPolicy { CACHE_ONLY, CACHE_FIRST, NETWORK_FIRST, NETWORK_ONLY }

/** Additional V2 session, cache and recovery APIs. */
public interface InappifyV2Client : InappifyClient {
    public val hasForceUpdate: Boolean
    public val isLogoutPending: Boolean
    public suspend fun customerInfo(policy: InappifyFetchPolicy): InappifyResult<InappifyCustomerInfo>
    public suspend fun offerings(policy: InappifyFetchPolicy): InappifyResult<InappifyOfferings>
    /** Queues write-only values durably; null or empty string deletes a value. */
    public suspend fun queueAttributes(values: Map<String, String?>): InappifyResult<Unit>
    public suspend fun flushAttributes(): InappifyResult<Unit>
    /** Invoke on foreground, connectivity restoration, and return from a payment URL. */
    public suspend fun recover(): InappifyResult<Unit>
    /** Stops v2 network use without discarding verified cache; host owns rollout flags. */
    public fun setNetworkEnabled(enabled: Boolean)
    public companion object {
        /** Uses the official Go service and bundled signing trust; configure only needs an API key. */
        @JvmStatic
        public fun create(context: Context): InappifyV2Client =
            GoV2Client.create(context.applicationContext)

        @JvmStatic
        public fun create(context: Context, configuration: InappifyV2Configuration): InappifyV2Client =
            GoV2Client.create(context.applicationContext, configuration)
    }
}

/** Server scope authenticated by the bundled/configured signing key, never a Configure input. */
public data class InappifyV2SessionScope(
    public val issuer: String,
    public val appId: Long,
    public val projectId: Long,
)

/** Null until a session is verified, and while logout is pending. Contains no credentials. */
public val InappifyV2Client.verifiedSessionScope: InappifyV2SessionScope?
    get() = (this as? InappifyV2RuntimeCapabilities)?.verifiedScope

/** Checks the SDK's configured payment allowlist without requiring the host to duplicate it. */
public fun InappifyV2Client.isPaymentUrlAllowed(url: String): Boolean =
    (this as? InappifyV2RuntimeCapabilities)?.allowsPaymentUrl(url) == true

internal interface InappifyV2RuntimeCapabilities {
    val verifiedScope: InappifyV2SessionScope?
    fun allowsPaymentUrl(url: String): Boolean
}

/**
 * Observes token-free authoritative snapshots, including null customer state during pending logout.
 * Collectors share the client's refresh and never cancel one another's HTTP request.
 */
public fun InappifyV2Client.stateUpdates(): Flow<InappifySnapshot> = callbackFlow {
    val registration = addEventListener { event ->
        if (event.type == InappifyEventType.STATE_CHANGED) trySend(event.snapshot)
    }
    trySend(snapshot)
    awaitClose { registration.close() }
}.distinctUntilChanged { old, new -> old.revision == new.revision }
