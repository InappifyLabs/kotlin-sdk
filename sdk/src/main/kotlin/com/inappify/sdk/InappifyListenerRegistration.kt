package com.inappify.sdk

import java.util.concurrent.atomic.AtomicReference

/**
 * Idempotent handle for one registered [InappifyEventListener].
 *
 * Calling [close] more than once, including concurrently, runs the removal
 * action at most once. Consumers should close registrations they no longer
 * need to avoid retaining their listener and its enclosing Android component.
 */
public class InappifyListenerRegistration private constructor(
    closeAction: Runnable,
) : AutoCloseable {

    private val closeAction: AtomicReference<Runnable?> =
        AtomicReference(closeAction)

    /** Whether this registration has already been closed. */
    public val isClosed: Boolean
        get() = closeAction.get() == null

    /**
     * Closes this registration.
     *
     * The first successful caller runs the removal action. Later calls have no
     * effect.
     */
    public override fun close(): Unit {
        closeAction.getAndSet(null)?.run()
    }

    /** Returns state without exposing the registered listener. */
    public override fun toString(): String =
        "InappifyListenerRegistration(isClosed=$isClosed)"

    internal companion object {

        /** Creates a registration owned by an SDK listener registry. */
        internal fun create(closeAction: Runnable): InappifyListenerRegistration =
            InappifyListenerRegistration(closeAction)
    }
}
