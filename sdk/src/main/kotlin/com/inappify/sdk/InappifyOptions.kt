package com.inappify.sdk

/**
 * Immutable configuration passed to [InappifyClient.configure].
 *
 * [apiKey] and [marketKey] are credentials and must never appear in public
 * snapshots, logs, or diagnostics. [appUserIdentifier] can contain personally
 * identifiable information and must not be written to logs or diagnostics.
 */
public class InappifyOptions public constructor(
    public val apiKey: String,
    public val appUserIdentifier: String? = null,
    public val market: InappifyMarket? = null,
    public val marketKey: String? = null,
    public val country: String? = null,
    public val appVersion: String? = null,
) {
    /**
     * Enabled by default; the commerce service must support
     * `store/subscriptions/recover`. Set false to disable recovery explicitly.
     * This never relaxes purchase-binding checks.
     */
    public var enableSubscriptionRecoveryWithoutPayload: Boolean = true
        private set

    /** Keeps the existing constructor and its Kotlin default-argument ABI intact. */
    public constructor(
        apiKey: String,
        appUserIdentifier: String? = null,
        market: InappifyMarket? = null,
        marketKey: String? = null,
        country: String? = null,
        appVersion: String? = null,
        enableSubscriptionRecoveryWithoutPayload: Boolean,
    ) : this(apiKey, appUserIdentifier, market, marketKey, country, appVersion) {
        this.enableSubscriptionRecoveryWithoutPayload = enableSubscriptionRecoveryWithoutPayload
    }

    /** Returns a representation that is safe for development logs. */
    public override fun toString(): String =
        "InappifyOptions(" +
            "apiKey=<redacted>, " +
            "appUserIdentifier=${appUserIdentifier.redacted()}, " +
            "market=$market, " +
            "marketKey=${marketKey.redacted()}, " +
            "country=$country, " +
            "appVersion=$appVersion" +
            ")"
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
