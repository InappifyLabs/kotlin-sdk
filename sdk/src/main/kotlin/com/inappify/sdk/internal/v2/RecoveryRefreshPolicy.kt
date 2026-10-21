package com.inappify.sdk.internal.v2

/** Client-lifetime scheduling only; never persisted in session or purchase storage. */
internal class RecoveryRefreshPolicy(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var generation = 0L
    private var lastSuccess: Long? = null
    private var lastFailure: Long? = null

    @Synchronized fun reset(generation: Long) {
        this.generation = generation
        lastSuccess = null
        lastFailure = null
    }

    @Synchronized fun shouldRecover(generation: Long, pending: Boolean, startup: Boolean): Boolean {
        if (generation != this.generation) return false
        val time = monotonicMillis()
        if (lastFailure?.let { time - it < FAILURE_BACKOFF_MILLIS } == true) return false
        return startup || pending || lastSuccess?.let { time - it >= FRESHNESS_MILLIS } != false
    }

    @Synchronized fun completed(generation: Long, success: Boolean) {
        // A superseded pass must not change the new customer's schedule.
        if (generation != this.generation) return
        if (success) {
            lastSuccess = monotonicMillis()
            lastFailure = null
        } else lastFailure = monotonicMillis()
    }

    @Synchronized fun retryDelayMillis(generation: Long): Long {
        if (generation != this.generation) return 0
        return lastFailure?.let { (FAILURE_BACKOFF_MILLIS - (monotonicMillis() - it)).coerceIn(0, FAILURE_BACKOFF_MILLIS) } ?: 0
    }

    companion object {
        const val FRESHNESS_MILLIS: Long = 5 * 60 * 1000L
        const val FAILURE_BACKOFF_MILLIS: Long = 30 * 1000L
    }
}
