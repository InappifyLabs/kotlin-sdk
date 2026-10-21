package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryCoordinatorTest {
    private val snapshot = InappifySnapshot.initial("test")

    @Test fun matchingRequestsShareOneTaskAndKeepTheFirstCallersMode() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val modes = mutableListOf<Boolean>()
            val coordinator = RecoveryCoordinator(owner, { 0L }) { automatic ->
                modes += automatic
                release.await()
                InappifyResult.Success(Unit, snapshot)
            }
            val first = coordinator.request(automatic = true)
            repeat(50) { assertSame(first, coordinator.request(automatic = false)) }
            assertEquals(listOf(true), modes)
            release.complete(Unit)
            assertTrue(first.await() is InappifyResult.Success)
            // Completed work is not a resource cache or a throttle for later explicit recovery.
            assertTrue(coordinator.request(automatic = false).await() is InappifyResult.Success)
            assertEquals(listOf(true, false), modes)
        } finally { owner.cancel() }
    }

    @Test fun aNewIdentityQueuesOnePassAndDoesNotInheritTheOldFailure() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var generation = 0L
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<Long>()
            val coordinator = RecoveryCoordinator(owner, { generation }) {
                val admitted = currentCoroutineContext()[RecoveryPass]!!.generation
                started += admitted
                if (admitted == 0L) {
                    release.await()
                    InappifyResult.Failure(InappifyError(InappifyErrorCode.NETWORK, "Fixture error"), snapshot)
                } else InappifyResult.Success(Unit, snapshot)
            }
            val old = coordinator.request(automatic = true)
            generation++
            val next = coordinator.request(automatic = true)
            repeat(50) { assertSame(next, coordinator.request(automatic = false)) }
            assertEquals(listOf(0L), started)
            assertFalse(next.isCompleted)
            release.complete(Unit)
            assertTrue(old.await() is InappifyResult.Failure)
            assertTrue(next.await() is InappifyResult.Success)
            assertEquals(listOf(0L, 1L), started)
        } finally { owner.cancel() }
    }

    @Test fun aCanceledWaiterDoesNotCancelTheClientsWork() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator = RecoveryCoordinator(owner, { 0L }) {
                calls++
                release.await()
                InappifyResult.Success(Unit, snapshot)
            }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(true).await() }
            val secondWaiter = async(start = CoroutineStart.UNDISPATCHED) { coordinator.request(false).await() }
            firstWaiter.cancelAndJoin()
            assertFalse(secondWaiter.isCompleted)
            release.complete(Unit)
            assertTrue(secondWaiter.await() is InappifyResult.Success)
            assertEquals(1, calls)
        } finally { owner.cancel() }
    }

    @Test fun closingTheClientScopeCancelsActiveAndQueuedPasses() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var generation = 0L
        var calls = 0
        val coordinator = RecoveryCoordinator(owner, { generation }) {
            calls++
            awaitCancellation()
        }
        val first = coordinator.request(true)
        generation++
        val queued = coordinator.request(true)
        owner.cancel()
        assertTrue(runCatching { first.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { queued.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
    }
}
