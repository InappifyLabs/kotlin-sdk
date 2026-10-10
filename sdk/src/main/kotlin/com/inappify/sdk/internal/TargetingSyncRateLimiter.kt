package com.inappify.sdk.internal

import android.os.SystemClock
import android.util.Log
import com.inappify.sdk.InappifyError
import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.InappifyOfferings
import com.inappify.sdk.InappifyResult
import com.inappify.sdk.InappifySnapshot
import java.util.ArrayDeque

/** Owned by one client and accessed only under that client's operation mutex. */
internal class TargetingSyncRateLimiter(
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val warn: (String) -> Unit = { Log.w("Inappify", it); Unit },
) {
    private val starts = ArrayDeque<Long>()
    private var lastNow = Long.MIN_VALUE

    /** Null admits a call; otherwise the caller must not perform network work. */
    fun acquire(): Long? {
        // A faulty injected clock must not reset the allowance or produce negative delays.
        val now = maxOf(lastNow, elapsedMillis())
        lastNow = now
        while (starts.isNotEmpty() && now - starts.first >= WINDOW_MILLIS) starts.removeFirst()
        if (starts.size < LIMIT) {
            starts.addLast(now)
            return null
        }
        runCatching { warn("$OPERATION is limited to 5 calls per minute; returning current-session offerings cache if available.") }
        return WINDOW_MILLIS - (now - starts.first)
    }

    companion object {
        const val OPERATION = "syncAttributesAndOfferingsIfNeeded"
        private const val LIMIT = 5
        private const val WINDOW_MILLIS = 60_000L

        fun cachedResult(snapshot: InappifySnapshot, retryAfterMillis: Long): InappifyResult<InappifyOfferings> {
            val cached = snapshot.offerings
            return if (snapshot.isConfigured && cached != null) {
                InappifyResult.Success(cached, snapshot)
            } else {
                InappifyResult.Failure(InappifyError(
                    InappifyErrorCode.UNKNOWN,
                    "Attribute and offerings synchronization is rate limited and no current-session offerings cache is available.",
                    isRetryable = true,
                    details = mapOf("operation" to OPERATION, "category" to "RATE_LIMIT",
                        "reason" to "RATE_LIMITED_NO_CACHE", "retryAfterMillis" to retryAfterMillis),
                ), snapshot)
            }
        }
    }
}
