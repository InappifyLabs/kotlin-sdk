package com.inappify.sdk

/**
 * Immutable request for authenticating an Inappify customer.
 *
 * The configured session authenticates login; no app key is required here.
 * The customer identifier must never be written to logs or diagnostics.
 */
public class InappifyLoginRequest public constructor(
    public val appUserIdentifier: String,
) {

    /** Retains the published two-argument constructor for existing callers. */
    @Deprecated("Login uses the configured session; pass only appUserIdentifier.")
    @Suppress("UNUSED_PARAMETER")
    public constructor(apiKey: String, appUserIdentifier: String) : this(appUserIdentifier)

    /** Returns a representation that never exposes request values. */
    public override fun toString(): String =
        "InappifyLoginRequest(" +
            "appUserIdentifier=<redacted>" +
            ")"
}
