package com.inappify.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EventApiTest {

    @Test
    fun `event keeps safe correlation without exposing it in diagnostics`() {
        val snapshot = InappifySnapshot.initial("0.1.0-test")
        val event = InappifyEvent.create(
            type = InappifyEventType.CUSTOMER_INFO_CHANGED,
            snapshot = snapshot,
            requestId = "request-123",
        )

        assertEquals(InappifyEventType.CUSTOMER_INFO_CHANGED, event.type)
        assertSame(snapshot, event.snapshot)
        assertEquals("request-123", event.requestId)
        assertFalse(event.toString().contains("request-123"))
        assertFalse(event.toString().contains("0.1.0-test"))
    }

    @Test
    fun `event rejects correlation values unsafe for diagnostics`() {
        val snapshot = InappifySnapshot.initial("0.1.0-test")

        assertNull(
            InappifyEvent.create(
                type = InappifyEventType.STATE_CHANGED,
                snapshot = snapshot,
                requestId = "token value with spaces",
            ).requestId,
        )
    }

    @Test
    fun `listener remains a lambda compatible public contract`() {
        val snapshot = InappifySnapshot.initial("0.1.0-test")
        val event = InappifyEvent.create(
            type = InappifyEventType.STATE_CHANGED,
            snapshot = snapshot,
        )
        var received: InappifyEvent? = null
        val listener = InappifyEventListener { received = it }

        listener.onEvent(event)

        assertSame(event, received)
    }

    @Test
    fun `registration closes exactly once under concurrent callers`() {
        val closeCalls = AtomicInteger(0)
        val registration = InappifyListenerRegistration.create(
            Runnable { closeCalls.incrementAndGet() },
        )
        val start = CountDownLatch(1)
        val completed = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.execute {
                start.await()
                registration.close()
                completed.countDown()
            }
        }

        try {
            assertFalse(registration.isClosed)
            start.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(registration.isClosed)
            assertEquals(1, closeCalls.get())

            registration.close()
            assertEquals(1, closeCalls.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
