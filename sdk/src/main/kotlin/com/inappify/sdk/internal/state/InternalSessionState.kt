package com.inappify.sdk.internal.state

import com.inappify.sdk.InappifyCustomerInfo
import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.InappifyOfferings
import com.inappify.sdk.InappifySnapshot
import com.inappify.sdk.internal.storage.PersistedSession

/** Complete internal session; secret-bearing fields never enter public DTOs. */
internal class InternalSessionState(
    internal val isConfigured: Boolean,
    internal val isAuthenticated: Boolean,
    internal val apiKey: String?,
    internal val apiKeyFingerprint: String?,
    internal val cacheContextFingerprint: String?,
    internal val token: String?,
    internal val appUserIdentifier: String?,
    internal val market: InappifyMarket?,
    internal val marketKey: String?,
    internal val country: String?,
    internal val appVersion: String?,
    internal val sdkVersion: String,
    internal val storeInfo: String?,
    internal val forceVersion: Long?,
    internal val appId: Long?,
    internal val customerInfoJson: String?,
    internal val offeringsJson: String?,
    internal val customerInfoUpdatedAt: String?,
    internal val purchaseRecoveryId: String? = null,
    internal val customerInfo: InappifyCustomerInfo? = null,
    internal val offerings: InappifyOfferings? = null,
    internal val failedToLoadCustomerInfo: Boolean = false,
    internal val failedToLoadOfferings: Boolean = false,
    internal val revision: Long = 0,
    internal val storePlatform: String? = null,
) {

    internal fun toSnapshot(): InappifySnapshot = InappifySnapshot(
        revision = revision,
        isConfigured = isConfigured,
        isAuthenticated = isAuthenticated,
        appUserIdentifier = appUserIdentifier,
        market = market,
        country = country,
        appVersion = appVersion,
        sdkVersion = sdkVersion,
        storeInfo = storeInfo,
        forceVersion = forceVersion,
        appId = appId,
        customerInfo = customerInfo,
        offerings = offerings,
        failedToLoadCustomerInfo = failedToLoadCustomerInfo,
        failedToLoadOfferings = failedToLoadOfferings,
        storePlatform = storePlatform,
    )

    @Suppress("LongParameterList")
    internal fun copy(
        isConfigured: Boolean = this.isConfigured,
        isAuthenticated: Boolean = this.isAuthenticated,
        apiKey: String? = this.apiKey,
        apiKeyFingerprint: String? = this.apiKeyFingerprint,
        cacheContextFingerprint: String? = this.cacheContextFingerprint,
        token: String? = this.token,
        appUserIdentifier: String? = this.appUserIdentifier,
        market: InappifyMarket? = this.market,
        marketKey: String? = this.marketKey,
        country: String? = this.country,
        appVersion: String? = this.appVersion,
        sdkVersion: String = this.sdkVersion,
        storeInfo: String? = this.storeInfo,
        forceVersion: Long? = this.forceVersion,
        appId: Long? = this.appId,
        customerInfoJson: String? = this.customerInfoJson,
        offeringsJson: String? = this.offeringsJson,
        customerInfoUpdatedAt: String? = this.customerInfoUpdatedAt,
        purchaseRecoveryId: String? = this.purchaseRecoveryId,
        customerInfo: InappifyCustomerInfo? = this.customerInfo,
        offerings: InappifyOfferings? = this.offerings,
        failedToLoadCustomerInfo: Boolean = this.failedToLoadCustomerInfo,
        failedToLoadOfferings: Boolean = this.failedToLoadOfferings,
        revision: Long = this.revision,
        storePlatform: String? = this.storePlatform,
    ): InternalSessionState = InternalSessionState(
        isConfigured = isConfigured,
        isAuthenticated = isAuthenticated,
        apiKey = apiKey,
        apiKeyFingerprint = apiKeyFingerprint,
        cacheContextFingerprint = cacheContextFingerprint,
        token = token,
        appUserIdentifier = appUserIdentifier,
        market = market,
        marketKey = marketKey,
        country = country,
        appVersion = appVersion,
        sdkVersion = sdkVersion,
        storeInfo = storeInfo,
        forceVersion = forceVersion,
        appId = appId,
        customerInfoJson = customerInfoJson,
        offeringsJson = offeringsJson,
        customerInfoUpdatedAt = customerInfoUpdatedAt,
        purchaseRecoveryId = purchaseRecoveryId,
        customerInfo = customerInfo,
        offerings = offerings,
        failedToLoadCustomerInfo = failedToLoadCustomerInfo,
        failedToLoadOfferings = failedToLoadOfferings,
        revision = revision,
        storePlatform = storePlatform,
    )

    internal fun toPersistedSession(): PersistedSession = PersistedSession(
        token = token,
        appUserIdentifier = appUserIdentifier,
        forceVersion = forceVersion,
        appId = appId,
        storeInfo = storeInfo,
        apiKeyFingerprint = apiKeyFingerprint,
        cacheContextFingerprint = cacheContextFingerprint,
        customerInfoJson = customerInfoJson,
        offeringsJson = offeringsJson,
        customerInfoUpdatedAt = customerInfoUpdatedAt,
        purchaseRecoveryId = purchaseRecoveryId,
        storePlatform = storePlatform,
    )

    override fun toString(): String =
        "InternalSessionState(" +
            "isConfigured=$isConfigured, " +
            "isAuthenticated=$isAuthenticated, " +
            "apiKey=${apiKey.redacted()}, " +
            "apiKeyFingerprint=${apiKeyFingerprint.redacted()}, " +
            "cacheContextFingerprint=" +
            "${cacheContextFingerprint.redacted()}, " +
            "token=${token.redacted()}, " +
            "appUserIdentifier=${appUserIdentifier.redacted()}, " +
            "market=$market, " +
            "marketKey=${marketKey.redacted()}, " +
            "country=$country, " +
            "appVersion=$appVersion, " +
            "sdkVersion=$sdkVersion, " +
            "storeInfo=$storeInfo, " +
            "storePlatform=$storePlatform, " +
            "forceVersion=$forceVersion, " +
            "appId=$appId, " +
            "customerInfoJson=${customerInfoJson.redacted()}, " +
            "purchaseRecoveryId=${purchaseRecoveryId.redacted()}, " +
            "offeringsJson=${offeringsJson.redacted()}, " +
            "hasCustomerInfo=${customerInfo != null}, " +
            "offeringsCount=${offerings?.offerings?.size ?: 0}, " +
            "failedToLoadCustomerInfo=$failedToLoadCustomerInfo, " +
            "failedToLoadOfferings=$failedToLoadOfferings, " +
            "revision=$revision" +
            ")"

    internal companion object {
        internal fun initial(sdkVersion: String): InternalSessionState =
            InternalSessionState(
                isConfigured = false,
                isAuthenticated = false,
                apiKey = null,
                apiKeyFingerprint = null,
                cacheContextFingerprint = null,
                token = null,
                appUserIdentifier = null,
                market = null,
                marketKey = null,
                country = null,
                appVersion = null,
                sdkVersion = sdkVersion,
                storeInfo = null,
                forceVersion = 1L,
                appId = null,
                customerInfoJson = null,
                offeringsJson = null,
                customerInfoUpdatedAt = null,
                purchaseRecoveryId = null,
                customerInfo = null,
                offerings = null,
                failedToLoadCustomerInfo = false,
                failedToLoadOfferings = false,
                revision = 0,
                storePlatform = null,
            )
    }
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
