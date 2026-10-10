package com.inappify.sdk

/**
 * Immutable request for authenticating an Inappify customer.
 *
 * Both values are sensitive and must never be written to logs or diagnostics.
 */
public class InappifyLoginRequest public constructor(
    public val apiKey: String,
    public val appUserIdentifier: String,
) {

    /** Returns a representation that never exposes request values. */
    public override fun toString(): String =
        "InappifyLoginRequest(" +
            "apiKey=<redacted>, " +
            "appUserIdentifier=<redacted>" +
            ")"
}
