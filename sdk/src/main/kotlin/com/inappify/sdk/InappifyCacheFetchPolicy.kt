package com.inappify.sdk

/** Controls how [InappifyClient.getCustomerInfo] uses verified customer information. */
public enum class InappifyCacheFetchPolicy {
    /** Returns cached information, or a cache-miss failure; never requests the network. */
    CACHE_ONLY,
    /** Waits for current information from the server; network failures are returned. */
    FETCH_CURRENT,
    /** Returns fresh cache, otherwise waits for the server without falling back to stale data. */
    NOT_STALE_CACHED_OR_CURRENT,
    /** Returns any cache immediately and refreshes it when stale; fetches when cache is absent. */
    CACHED_OR_FETCHED,
}
