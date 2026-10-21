package com.inappify.sdk.internal.v2

/** Runtime scheduling only: no cache schema, wall-clock timestamp or purchase journal changes. */
internal class OfferingsRefreshPolicy(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var lastSuccess: Long? = null
    private var lastFailure: Long? = null

    @Synchronized fun shouldRefresh(): Boolean {
        val time = monotonicMillis()
        if (lastFailure?.let { time - it < FAILURE_BACKOFF_MILLIS } == true) return false
        return lastSuccess?.let { time - it >= FRESHNESS_MILLIS } ?: true
    }

    @Synchronized fun fetched() {
        lastSuccess = monotonicMillis()
        lastFailure = null
    }

    @Synchronized fun failed() { lastFailure = monotonicMillis() }

    @Synchronized fun invalidate() { lastSuccess = null; lastFailure = null }

    companion object {
        const val FRESHNESS_MILLIS: Long = 5 * 60 * 1000L
        const val FAILURE_BACKOFF_MILLIS: Long = 30 * 1000L
    }
}
