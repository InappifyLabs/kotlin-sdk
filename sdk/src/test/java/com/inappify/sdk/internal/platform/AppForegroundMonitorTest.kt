package com.inappify.sdk.internal.platform

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.*
import org.junit.Test

class AppForegroundMonitorTest {
    private class TestOwner : LifecycleOwner {
        val observers = mutableSetOf<LifecycleObserver>()
        var state = Lifecycle.State.CREATED
        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState: State get() = state
            override fun addObserver(observer: LifecycleObserver) { observers += observer }
            override fun removeObserver(observer: LifecycleObserver) { observers -= observer }
        }
        fun event(event: Lifecycle.Event) {
            state = event.targetState
            observers.toList().forEach { (it as LifecycleEventObserver).onStateChanged(this, event) }
        }
    }

    @Test fun processStartupWithoutAnActivityDoesNotLookLikeForeground() {
        val owner = TestOwner()
        val events = mutableListOf<Boolean>()
        AndroidAppForegroundMonitor({ owner.lifecycle }, { it() }).use { monitor ->
            monitor.setListener { events += it }
            assertFalse(monitor.isForeground)
            assertTrue(events.isEmpty())
            owner.event(Lifecycle.Event.ON_START)
            owner.event(Lifecycle.Event.ON_RESUME)
            assertEquals(listOf(true), events)
            owner.event(Lifecycle.Event.ON_STOP)
            assertFalse(monitor.isForeground)
            assertEquals(listOf(true, false), events)
        }
        assertTrue(owner.observers.isEmpty())
    }

    @Test fun attachingAfterTheAppIsAlreadyStartedStillReportsForeground() {
        val owner = TestOwner().apply { state = Lifecycle.State.RESUMED }
        val events = mutableListOf<Boolean>()
        val monitor = AndroidAppForegroundMonitor({ owner.lifecycle }, { it() })
        monitor.setListener { events += it }
        assertTrue(monitor.isForeground)
        assertEquals(listOf(true), events)
        monitor.close()
        owner.event(Lifecycle.Event.ON_STOP)
        assertEquals(listOf(true), events)
    }

    @Test fun closingBeforeMainThreadRegistrationDoesNotLeakAnObserver() {
        val owner = TestOwner()
        val tasks = mutableListOf<() -> Unit>()
        val monitor = AndroidAppForegroundMonitor({ owner.lifecycle }, { tasks += it })
        monitor.close()
        tasks.forEach { it() }
        assertTrue(owner.observers.isEmpty())
    }
}
