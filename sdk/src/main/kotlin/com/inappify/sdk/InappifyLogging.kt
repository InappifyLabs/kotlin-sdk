package com.inappify.sdk

import android.util.Log
import com.inappify.sdk.internal.InappifyLogger

/** Minimum severity to deliver, ordered from most to least detailed. */
public enum class InappifyLogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Receives filtered SDK messages on the calling thread. Keep callbacks short. */
public interface InappifyLogHandler {
    public fun v(tag: String, msg: String)
    public fun d(tag: String, msg: String)
    public fun i(tag: String, msg: String)
    public fun w(tag: String, msg: String)
    public fun e(tag: String, msg: String, throwable: Throwable?)
}

/** Restores the default Android Logcat output after using a custom handler. */
public class DefaultInappifyLogHandler : InappifyLogHandler {
    override fun v(tag: String, msg: String) { Log.v(tag, msg) }
    override fun d(tag: String, msg: String) { Log.d(tag, msg) }
    override fun i(tag: String, msg: String) { Log.i(tag, msg) }
    override fun w(tag: String, msg: String) { Log.w(tag, msg) }
    override fun e(tag: String, msg: String, throwable: Throwable?) { Log.e(tag, msg) }
}

/** Process-wide log settings, shared by independently created SDK clients. */
internal object InappifyLoggingSettings {
    var level: InappifyLogLevel
        get() = InappifyLogger.level
        set(value) { InappifyLogger.level = value }
    var handler: InappifyLogHandler
        get() = InappifyLogger.handler
        set(value) { InappifyLogger.handler = value }
}
