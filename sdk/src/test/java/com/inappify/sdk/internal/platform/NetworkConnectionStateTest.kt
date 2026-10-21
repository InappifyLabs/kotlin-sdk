package com.inappify.sdk.internal.platform

import org.junit.Assert.*
import org.junit.Test

class NetworkConnectionStateTest {
    @Test fun initialOnlineAndRepeatedCapabilitiesAreNotReconnectEvents() {
        val state = NetworkConnectionState(true)
        val events = mutableListOf<Boolean>()
        state.setListener { events += it }
        repeat(20) { state.update(true) }
        assertTrue(state.isConnected)
        assertTrue(events.isEmpty())
    }

    @Test fun offlineToOnlineIsEmittedExactlyOncePerTransition() {
        val state = NetworkConnectionState(false)
        val events = mutableListOf<Boolean>()
        state.setListener { events += it }
        repeat(10) { state.update(true) }
        repeat(10) { state.update(false) }
        state.update(true)
        assertEquals(listOf(true, false, true), events)
    }

    @Test fun closingDetachesTheListenerAndIgnoresQueuedAndroidEvents() {
        val state = NetworkConnectionState(false)
        val events = mutableListOf<Boolean>()
        state.setListener { events += it }
        state.close()
        state.setListener { fail("Closed monitor must not attach listeners") }
        state.update(true)
        assertFalse(state.isConnected)
        assertTrue(events.isEmpty())
    }
}
