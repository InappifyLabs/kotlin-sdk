package com.inappify.sdk.internal

import com.inappify.sdk.*

/** Logging is independent of session, commerce, persistence and retry state. */
internal class InappifyLogDispatcher(
    initialLevel: InappifyLogLevel,
    initialHandler: InappifyLogHandler,
) {
    @Volatile var level: InappifyLogLevel = initialLevel
    @Volatile var handler: InappifyLogHandler = initialHandler
    private val delivering = ThreadLocal<Boolean>()

    fun log(severity: InappifyLogLevel, message: () -> String) {
        if (severity.ordinal < level.ordinal || delivering.get() == true) return
        delivering.set(true)
        try {
            val text = message().take(2048)
            val target = handler
            when (severity) {
                InappifyLogLevel.VERBOSE -> target.v("[Inappify]", text)
                InappifyLogLevel.DEBUG -> target.d("[Inappify]", text)
                InappifyLogLevel.INFO -> target.i("[Inappify]", text)
                InappifyLogLevel.WARN -> target.w("[Inappify]", text)
                InappifyLogLevel.ERROR -> target.e("[Inappify]", text, null)
            }
        } catch (_: Throwable) {
            // An application's diagnostic callback must never alter paid work.
        } finally { delivering.remove() }
    }
}

internal object InappifyLogger {
    private val dispatcher = InappifyLogDispatcher(
        if (BuildConfig.DEBUG) InappifyLogLevel.DEBUG else InappifyLogLevel.INFO,
        DefaultInappifyLogHandler(),
    )
    private val settingsLock = Any()
    @Volatile private var explicitLevel = false
    var level: InappifyLogLevel
        get() = dispatcher.level
        set(value) { synchronized(settingsLock) { explicitLevel = true; dispatcher.level = value } }
    var handler: InappifyLogHandler
        get() = dispatcher.handler
        set(value) { dispatcher.handler = value }

    fun initializeDefault(debuggable: Boolean) {
        synchronized(settingsLock) {
            if (!explicitLevel) dispatcher.level = if (debuggable) InappifyLogLevel.DEBUG else InappifyLogLevel.INFO
        }
    }
    fun log(level: InappifyLogLevel, message: () -> String) = dispatcher.log(level, message)

    /** Endpoint names only; dynamic resource IDs and unknown paths are never logged. */
    fun endpoint(value: String): String = when {
        value in setOf("configure", "login", "logout", "customerInfo", "offerings", "attributes",
            "validateDiscountCode", "public-keys", "purchase", "store/purchases",
            "store/subscriptions/recover", "consumable-deliveries/pending") -> value
        value.matches(Regex("consumable-deliveries/[0-9]+/delivered")) -> "consumable-deliveries/:id/delivered"
        value.matches(Regex("store/verifications/[A-Za-z0-9_-]+/status")) -> "store/verifications/:id/status"
        value.matches(Regex("store/deliveries/[0-9]+/(delivered|consume-result)")) -> "store/deliveries/:id/action"
        else -> "[redacted]"
    }
}
