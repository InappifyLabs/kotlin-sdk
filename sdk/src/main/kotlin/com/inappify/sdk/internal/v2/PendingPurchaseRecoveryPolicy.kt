package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyPurchaseAttempt
import com.inappify.sdk.InappifyPurchaseAttemptStatus

/** A reconciliation deadline, never an expiry or permission to charge again. */
internal class PendingPurchaseRecoveryPolicy {
    private var lastCheckAt: Long? = null

    fun reset() { lastCheckAt = null }
    fun checked(now: Long) { lastCheckAt = now }

    fun delayMillis(attempts: List<InappifyPurchaseAttempt>, now: Long): Long? {
        val oldest = attempts.filter { it.status == InappifyPurchaseAttemptStatus.PENDING }
            .minOfOrNull { it.updatedAtEpochMillis } ?: return null
        // Clamp future timestamps so a clock correction cannot postpone checking forever.
        val baseline = maxOf(oldest.coerceAtMost(now), lastCheckAt?.coerceAtMost(now) ?: 0L)
        return (INTERVAL_MILLIS - (now - baseline)).coerceIn(0, INTERVAL_MILLIS)
    }

    companion object { const val INTERVAL_MILLIS = 10 * 60 * 1000L }
}
