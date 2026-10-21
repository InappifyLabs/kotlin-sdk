package com.inappify.sdk.internal.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import java.util.WeakHashMap

internal interface PaymentLinkMonitor : AutoCloseable {
    fun setListener(listener: (String) -> Unit)
}

internal class NoPaymentLinkMonitor : PaymentLinkMonitor {
    override fun setListener(listener: (String) -> Unit) = Unit
    override fun close() = Unit
}

/** Cold Activity intents and AndroidX onNewIntent; never changes the host's Intent/navigation. */
internal class AndroidPaymentLinkMonitor(context: Context) : PaymentLinkMonitor {
    private val app = context.applicationContext as? Application
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var listener: ((String) -> Unit)? = null
    private val pending = ArrayDeque<String>()
    private val attached = WeakHashMap<OnNewIntentProvider, Consumer<Intent>>()
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = observe(activity)
        override fun onActivityStarted(activity: Activity) = observe(activity)
        override fun onActivityResumed(activity: Activity) = observe(activity)
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is OnNewIntentProvider) attached.remove(activity)?.let(activity::removeOnNewIntentListener)
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
    init { dispatch { if (!closed) app?.registerActivityLifecycleCallbacks(callbacks) } }
    private fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
    private fun observe(activity: Activity) {
        if (closed) return
        if (activity is OnNewIntentProvider && !attached.containsKey(activity)) {
            val observer = Consumer<Intent> { deliver(it) }
            attached[activity] = observer
            activity.addOnNewIntentListener(observer)
        }
        deliver(activity.intent)
    }
    private fun deliver(intent: Intent?) {
        if (closed || intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        val callback = listener
        if (callback != null) callback(url) else {
            if (pending.size == 8) pending.removeFirst()
            pending.addLast(url)
        }
    }
    override fun setListener(listener: (String) -> Unit) {
        dispatch {
            if (!closed) {
                this.listener = listener
                while (pending.isNotEmpty()) listener(pending.removeFirst())
            }
        }
    }
    override fun close() {
        closed = true
        listener = null
        dispatch {
            app?.unregisterActivityLifecycleCallbacks(callbacks)
            attached.forEach { (owner, observer) -> owner.removeOnNewIntentListener(observer) }
            attached.clear(); pending.clear()
        }
    }
}
