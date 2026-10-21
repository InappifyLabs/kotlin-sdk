package com.inappify.sdk

import android.content.Context
import com.inappify.sdk.internal.v2.GoV2Client

/**
 * Public entry point for an Inappify session.
 *
 * Implementations must serialize state-changing operations and expose the
 * latest authoritative state through [snapshot]. A client must not be reused
 * after [close] has been called.
 *
 * Semantic SDK failures are returned as [InappifyResult.Failure]. Exceptions
 * are reserved for invalid client lifecycle usage and programming errors.
 */
public interface InappifyClient : AutoCloseable {

    public companion object {

        /** Global minimum severity. Defaults to DEBUG for development and INFO for release. */
        @JvmStatic
        public var logLevel: InappifyLogLevel
            get() = InappifyLoggingSettings.level
            set(value) { InappifyLoggingSettings.level = value }

        /** Replaces the default Logcat handler. May be set before Configure. */
        @JvmStatic
        public var logHandler: InappifyLogHandler
            get() = InappifyLoggingSettings.handler
            set(value) { InappifyLoggingSettings.handler = value }

        /**
         * Creates an independent client backed by the production Inappify
         * service and application-private session storage.
         *
         * This factory makes no network request.
         * Applications should create one client in their application or
         * dependency-injection scope, retain it for that scope's lifetime, and
         * call [close] when the owner is permanently released.
         */
        @JvmStatic
        public fun create(context: Context): InappifyV2Client =
            GoV2Client.create(context.applicationContext)
    }

    /** Latest authoritative, token-free state owned by this client. */
    public val snapshot: InappifySnapshot

    /** Configures the session for the application represented by [options]. */
    public suspend fun configure(options: InappifyOptions): InappifyResult<Unit>

    /** Changes customer identity using the existing session, cache and retry flow. */
    public suspend fun logIn(appUserIdentifier: String): InappifyResult<Unit>

    /** Logs out using the existing durable transition, anonymous session and cache flow. */
    public suspend fun logOut(): InappifyResult<Unit>

    /**
     * Returns verified customer information using [fetchPolicy]. The default returns
     * cache immediately and refreshes it when stale (five minutes in the foreground,
     * twenty-five hours in the background). No periodic timer is started.
     */
    public suspend fun getCustomerInfo(
        fetchPolicy: InappifyCacheFetchPolicy = InappifyCacheFetchPolicy.CACHED_OR_FETCHED,
    ): InappifyResult<InappifyCustomerInfo>

    /**
     * Returns the offerings for the active session, reusing the session-bound
     * cache when it is available. Cached reads only schedule an automatic refresh
     * when the app is in the foreground and the five-minute freshness window
     * has elapsed. Automatic failures have a thirty-second retry backoff.
     */
    public suspend fun getOfferings(): InappifyResult<InappifyOfferings>

    /** Selects an exact offering from the normal session-bound cache/fetch flow. */
    public suspend fun getOffering(identifier: String): InappifyResult<InappifyOffering?> =
        when (val result = getOfferings()) {
            is InappifyResult.Success -> InappifyResult.Success(result.data.getOffering(identifier), result.snapshot)
            is InappifyResult.Failure -> result
        }

    /**
     * Checks [discountCode] for the active customer and returns eligible web
     * payment links. An invalid code is a successful result with isValid=false.
     * Network/server errors are failures. Does not apply a discount to purchases
     * or change cached offerings.
     */
    public suspend fun getDiscountedPaymentLinks(
        discountCode: String,
    ): InappifyResult<InappifyDiscountCodeResult>

    /**
     * Returns the server-selected offering: a placement selection, then the
     * current offering, then the default offering. The production client does
     * not evaluate targeting rules locally.
     * Uses the same cache/fetch policy as [getOfferings]. For a manual refresh,
     * await [syncAttributesAndOfferingsIfNeeded] before selecting an offering.
     * A successful result can contain null when the server supplies no matching selection.
     */
    public suspend fun getCurrentOffering(
        placementIdentifier: String? = null,
    ): InappifyResult<InappifyOffering?>

    /** Returns whether the latest snapshot contains the named active entitlement. */
    public fun isActiveEntitlement(identifier: String): Boolean =
        snapshot.isActiveEntitlement(identifier)

    /** Convenience alias for [isActiveEntitlement]. */
    public fun hasEntitlement(identifier: String): Boolean =
        isActiveEntitlement(identifier)

    /** Returns the named active entitlement from the latest snapshot. */
    public fun getEntitlement(identifier: String): InappifyEntitlement? =
        snapshot.getEntitlement(identifier)

    /** Returns whether an identifier uses Inappify's anonymous-customer marker. */
    public fun isCustomerAnonymous(appUserIdentifier: String?): Boolean =
        com.inappify.sdk.isCustomerAnonymous(appUserIdentifier)

    /**
     * Sets attributes through the existing validation, durable queue and sync flow.
     * Null or empty values delete a key. The public Go API remains write-only.
     */
    public suspend fun setAttributes(
        attributes: Map<String, String?>,
    ): InappifyResult<List<InappifyAttribute>>

    /** Stores one reserved customer attribute. */
    public suspend fun setReservedAttribute(
        request: InappifyReservedAttributeRequest,
    ): InappifyResult<Unit>

    /** Returns whether a reserved attribute key can be written. */
    public suspend fun canSetReservedAttribute(key: String): InappifyResult<Boolean>

    /** Convenience wrapper for the reserved `$email` attribute. */
    public suspend fun setEmail(email: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.EMAIL, email),
        )

    /** Convenience wrapper for the reserved `$apnsTokens` attribute. */
    public suspend fun setApnsToken(apnsToken: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.APNS_TOKEN, apnsToken),
        )

    /** Convenience wrapper for the reserved `$displayName` attribute. */
    public suspend fun setDisplayName(displayName: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.DISPLAY_NAME,
                displayName,
            ),
        )

    /** Convenience wrapper for the reserved `$fcmTokens` attribute. */
    public suspend fun setFcmToken(fcmToken: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.FCM_TOKEN, fcmToken),
        )

    /** Convenience wrapper for the reserved `$idfa` attribute. */
    public suspend fun setIdfa(idfa: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IDFA, idfa),
        )

    /** Convenience wrapper for the reserved `$idfv` attribute. */
    public suspend fun setIdfv(idfv: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IDFV, idfv),
        )

    /** Convenience wrapper for the reserved `$ip` attribute. */
    public suspend fun setIp(ip: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.IP, ip),
        )

    /** Convenience wrapper for the reserved `$phoneNumber` attribute. */
    public suspend fun setPhoneNumber(phoneNumber: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(
                InappifyReservedAttribute.PHONE_NUMBER,
                phoneNumber,
            ),
        )

    /** Convenience wrapper for the reserved `$campaign` attribute. */
    public suspend fun setCampaign(campaign: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.CAMPAIGN, campaign),
        )

    /** Convenience wrapper for the reserved `$keyword` attribute. */
    public suspend fun setKeyword(keyword: String): InappifyResult<Unit> =
        setReservedAttribute(
            InappifyReservedAttributeRequest(InappifyReservedAttribute.KEYWORD, keyword),
        )

    /**
     * Purchases a catalog package through the existing configured purchase route.
     *
     * The package supplies its parent offering and product metadata. A native
     * store purchase requires known server product classification; old or
     * incomplete catalogs must be refreshed before starting a native purchase.
     * Direct checkout keeps its existing NEEDTOPAY/URL behavior.
     */
    public suspend fun purchase(
        purchaseParams: InappifyPurchaseParams,
    ): InappifyResult<InappifyPurchase>

    /**
     * Reconciles locally verified, owned marketplace purchases with Inappify.
     *
     * This operation never opens marketplace UI. It is intended for recovery
     * when the store completed a purchase but server verification was
     * interrupted. Bound receipts are submitted without rewriting their signed
     * evidence. When enabled, eligible Bazaar subscriptions with empty payload
     * use the dedicated server-linkage recovery endpoint. Raw store evidence
     * is never returned to the application.
     */
    public suspend fun syncStorePurchases(): InappifyResult<List<InappifyPurchase>>

    /**
     * Registers an observer for authoritative state changes from this client.
     * Closing the returned registration removes only this listener.
     */
    public fun addEventListener(
        listener: InappifyEventListener,
    ): InappifyListenerRegistration

    /**
     * Releases resources owned by this client.
     *
     * Closing a client is not equivalent to [logOut] and must not perform a
     * remote logout implicitly.
     */
    public override fun close(): Unit
}
