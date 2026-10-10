package com.inappify.sdk

/** Optional capability for explicit local-session restoration. */
internal interface CachedSessionCapableClient {
    suspend fun restoreCachedSessionInternal(options: InappifyOptions): InappifyResult<Boolean>
}

/**
 * Attempts explicit local restoration only for clients implementing that capability.
 *
 * The production client returned by [InappifyClient.create] and [InappifyV2Client.create]
 * does not implement it and returns [InappifyErrorCode.UNSUPPORTED_OPERATION].
 * Use [InappifyClient.configure] to load configuration-bound, verified session cache;
 * then use fetch policies and [InappifyV2Client.recover] for refresh and reconciliation.
 * This extension performs no network fallback and never switches protocols.
 */
public suspend fun InappifyClient.restoreCachedSession(
    options: InappifyOptions,
): InappifyResult<Boolean> = if (this is CachedSessionCapableClient) {
    restoreCachedSessionInternal(options)
} else {
    InappifyResult.Failure(
        error = InappifyError(
            code = InappifyErrorCode.UNSUPPORTED_OPERATION,
            message = "Local V1 session restoration is not supported by this client.",
            details = mapOf("operation" to "restoreCachedSession"),
        ),
        snapshot = snapshot,
    )
}
