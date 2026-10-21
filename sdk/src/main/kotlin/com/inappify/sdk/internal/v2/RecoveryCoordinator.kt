package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.storage.PendingStoreOperation
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

/** Identifies the runtime identity state a recovery pass was admitted for. Never persisted. */
internal class RecoveryPass(val generation: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RecoveryPass>

    // Retained only by callers joining this pass. A completed paid checkpoint may
    // disappear from disk before a waiting purchase gets the state mutex.
    private val purchases = linkedMapOf<String, RecoveredPurchase>()
    @Synchronized fun record(purchase: RecoveredPurchase) { purchases[purchase.attemptId] = purchase }
    @Synchronized fun purchases(): List<RecoveredPurchase> = purchases.values.toList()
}

internal class RecoveredPurchase(
    val attemptId: String,
    val productIdentifier: String,
    val result: InappifyResult<InappifyPurchase>,
    val nativeOperation: PendingStoreOperation? = null,
)

/** Coalesces matching recovery calls and serializes passes admitted after identity transitions. */
internal class RecoveryCoordinator(
    private val scope: CoroutineScope,
    private val generation: () -> Long,
    private val recover: suspend (automatic: Boolean) -> InappifyResult<Unit>,
) {
    internal class Flight(val pass: RecoveryPass, val result: Deferred<InappifyResult<Unit>>)
    private val lock = Any()
    private var latest: Flight? = null

    fun active(): Flight? = synchronized(lock) {
        latest?.takeIf { it.pass.generation == generation() && !it.result.isCompleted }
    }
    fun isRunning(): Boolean = active() != null

    // Register immediately, including when Configure/Login still owns the state mutex.
    // The work belongs to the client scope, so canceling one waiter cannot cancel it.
    fun request(automatic: Boolean): Deferred<InappifyResult<Unit>> = synchronized(lock) {
        val admittedGeneration = generation()
        val previous = latest
        if (previous != null && previous.pass.generation == admittedGeneration && !previous.result.isCompleted) {
            previous.result
        } else {
            val pass = RecoveryPass(admittedGeneration)
            scope.async(pass, start = CoroutineStart.LAZY) {
                // join deliberately does not propagate an earlier pass's failure to the next identity.
                previous?.result?.join()
                scope.ensureActive()
                recover(automatic)
            }.also { work ->
                latest = Flight(pass, work)
                work.invokeOnCompletion {
                    synchronized(lock) { if (latest?.result === work) latest = null }
                }
                work.start()
            }
        }
    }
}
