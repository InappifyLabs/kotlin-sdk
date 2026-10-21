package com.inappify.sdk

/**
 * Immutable configuration passed to [InappifyClient.configure].
 *
 * [apiKey] and [appUserIdentifier] must not appear in logs or diagnostics.
 * Store routing and the public billing key come from the configured server session.
 * The SDK reads the application version from Android package metadata.
 */
public class InappifyOptions public constructor(
    public val apiKey: String,
    public val appUserIdentifier: String? = null,
) {
    /** Returns a representation that is safe for development logs. */
    public override fun toString(): String =
        "InappifyOptions(" +
            "apiKey=<redacted>, " +
            "appUserIdentifier=${appUserIdentifier.redacted()}" +
            ")"
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
