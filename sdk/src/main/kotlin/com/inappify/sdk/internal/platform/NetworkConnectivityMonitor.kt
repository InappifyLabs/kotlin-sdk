package com.inappify.sdk.internal.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

internal interface NetworkConnectivityMonitor : AutoCloseable {
    val isConnected: Boolean
    fun setListener(listener: (Boolean) -> Unit)
}

internal class AlwaysConnectedMonitor : NetworkConnectivityMonitor {
    override val isConnected: Boolean = true
    override fun setListener(listener: (Boolean) -> Unit) = Unit
    override fun close() = Unit
}

/** Emits transitions only: initial connectivity is not a second startup trigger. */
internal class NetworkConnectionState(initialConnected: Boolean) : NetworkConnectivityMonitor {
    @Volatile override var isConnected: Boolean = initialConnected
        private set
    private var closed = false
    private var listener: ((Boolean) -> Unit)? = null

    @Synchronized fun update(connected: Boolean) {
        if (closed || connected == isConnected) return
        isConnected = connected
        listener?.invoke(connected)
    }
    @Synchronized override fun setListener(listener: (Boolean) -> Unit) {
        if (!closed) this.listener = listener
    }
    @Synchronized override fun close() { closed = true; listener = null }
}

/** Uses the application context and releases every Android registration on close. */
internal class AndroidNetworkConnectivityMonitor(context: Context) : NetworkConnectivityMonitor {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val state = NetworkConnectionState(readConnected())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    override val isConnected: Boolean get() = state.isConnected

    init {
        val manager = connectivity
        if (Build.VERSION.SDK_INT >= 24 && manager != null) {
            val observer = object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                override fun onAvailable(network: Network) { current = network }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (network == current) state.update(capabilities.hasInternet())
                }
                override fun onLost(network: Network) {
                    if (network == current) { current = null; state.update(false) }
                }
            }
            // Monitoring must not turn an unavailable Android service into Configure failure.
            runCatching { manager.registerDefaultNetworkCallback(observer); callback = observer }
        } else if (manager != null) {
            // Default-network callbacks start at API 24. Dynamic registration supports API 21-23.
            val observer = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { state.update(readConnected()) }
            }
            @Suppress("DEPRECATION")
            runCatching { app.registerReceiver(observer, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)); receiver = observer }
        }
    }

    @Suppress("DEPRECATION")
    private fun readConnected(): Boolean = runCatching {
        val manager = connectivity ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= 23) manager.getNetworkCapabilities(manager.activeNetwork)?.hasInternet() == true
        else manager.activeNetworkInfo?.isConnected == true
    }.getOrDefault(false)

    private fun NetworkCapabilities.hasInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    override fun setListener(listener: (Boolean) -> Unit) = state.setListener(listener)
    override fun close() {
        state.close()
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }; callback = null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null
    }
}
