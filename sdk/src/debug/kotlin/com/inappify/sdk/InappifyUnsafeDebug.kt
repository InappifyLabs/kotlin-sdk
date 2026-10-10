package com.inappify.sdk

import android.content.Context
import com.inappify.sdk.internal.v2.GoV2Client

/**
 * Debug-variant-only entry point for temporarily exposing raw HTTP diagnostics.
 *
 * The resulting trace listener receives API keys, tokens, customer identifiers,
 * marketplace receipts, signatures, delivery IDs, and complete endpoint values.
 * Never use its output outside an isolated test environment.
 */
public object InappifyUnsafeDebug {

    /** Creates a production-service client whose diagnostic traces are not redacted. */
    @JvmStatic
    public fun createClient(context: Context): InappifyClient =
        GoV2Client.create(
            context = context.applicationContext,
            unsafeRawHttpLogging = true,
        )
}
