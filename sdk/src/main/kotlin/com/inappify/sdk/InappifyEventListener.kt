package com.inappify.sdk

/** Receives ordered asynchronous events from an [InappifyClient]. */
public fun interface InappifyEventListener {

    /**
     * Handles one immutable [event].
     *
     * SDK implementations must not invoke application code while holding an
     * internal state or lifecycle lock. An exception thrown by one listener
     * must not prevent delivery to other listeners.
     */
    public fun onEvent(event: InappifyEvent): Unit
}
