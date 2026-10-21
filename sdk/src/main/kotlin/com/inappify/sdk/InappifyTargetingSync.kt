package com.inappify.sdk

/** Optional capability for combined attribute and Offering synchronization. */
internal interface TargetingSyncCapableClient {
    suspend fun syncAttributesAndOfferingsIfNeededInternal(): InappifyResult<InappifyOfferings>
}

/**
 * Synchronizes subscriber attributes, then fetches fresh offerings for the current customer.
 * Await [InappifyClient.setAttributes] or reserved-attribute setters before calling this method
 * when targeting rules depend on custom attributes. Attributes are uploaded through the
 * existing attributes endpoint by flushing the write-only queue; they are not downloaded.
 *
 * Admitted calls never use cached offerings as their result or as a network-error fallback.
 * Successful responses update the normal snapshot, persistent cache and events. A failed
 * attribute sync stops the operation before the offerings request. Both steps are serialized
 * together with identity changes; existing setters/getters retain their previous behavior.
 *
 * Each client permits five admitted calls per rolling 60 seconds, measured with a monotonic
 * clock. Failed/cancelled admitted calls also count. Further calls log a non-sensitive warning
 * and return only the current session's cached offerings, without syncing or making HTTP calls.
 * Without that cache, Failure has code UNKNOWN, reason RATE_LIMITED_NO_CACHE and retryAfterMillis
 * in its details. The limit is in-memory, per client, is not reset by login/logout, and does not
 * rate-limit other SDK methods or individual protocol retries/batches within an admitted call.
 *
 * Custom clients without this capability return UNSUPPORTED_OPERATION.
 */
public suspend fun InappifyClient.syncAttributesAndOfferingsIfNeeded(): InappifyResult<InappifyOfferings> =
    if (this is TargetingSyncCapableClient) {
        syncAttributesAndOfferingsIfNeededInternal()
    } else {
        InappifyResult.Failure(
            InappifyError(
                InappifyErrorCode.UNSUPPORTED_OPERATION,
                "Attribute and offerings synchronization is not supported by this client.",
                details = mapOf("operation" to "syncAttributesAndOfferingsIfNeeded"),
            ),
            snapshot,
        )
    }
