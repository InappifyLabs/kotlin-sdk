package com.inappify.sdk.internal.platform

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/** Observes process visibility without retaining an Activity or asking the host to forward callbacks. */
internal interface AppForegroundMonitor : AutoCloseable {
    val isForeground: Boolean
    fun setListener(listener: (Boolean) -> Unit)
}

/** Deterministic default for clients constructed directly by internal tests. Factories use Android's lifecycle. */
internal class AlwaysForegroundMonitor : AppForegroundMonitor {
    override val isForeground: Boolean = true
    override fun setListener(listener: (Boolean) -> Unit) = Unit
    override fun close() = Unit
}

internal class AndroidAppForegroundMonitor(
    private val lifecycleProvider: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle },
    private val dispatch: (() -> Unit) -> Unit = Handler(Looper.getMainLooper()).let { handler ->
        { action -> handler.post { action() }; Unit }
    },
) : AppForegroundMonitor {
    @Volatile override var isForeground: Boolean = false
        private set
    @Volatile private var closed = false
    @Volatile private var listener: ((Boolean) -> Unit)? = null
    private var observedLifecycle: Lifecycle? = null
    private val observer = LifecycleEventObserver { owner, _ -> update(owner.lifecycle) }

    init {
        dispatch {
            if (!closed) {
                val lifecycle = lifecycleProvider()
                observedLifecycle = lifecycle
                lifecycle.addObserver(observer)
                update(lifecycle)
            }
        }
    }

    private fun update(lifecycle: Lifecycle) {
        if (closed) return
        val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground != foreground) {
            isForeground = foreground
            listener?.invoke(foreground)
        }
    }

    override fun setListener(listener: (Boolean) -> Unit) {
        if (closed) return
        this.listener = listener
        if (isForeground) listener(true)
    }

    override fun close() {
        closed = true
        listener = null
        dispatch { observedLifecycle?.removeObserver(observer); observedLifecycle = null }
    }
}
