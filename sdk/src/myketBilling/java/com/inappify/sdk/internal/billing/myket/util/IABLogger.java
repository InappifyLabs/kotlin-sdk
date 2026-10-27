package com.inappify.sdk.internal.billing.myket.util;

/** The SDK reports structured errors; upstream receipt logging is deliberately disabled. */
public final class IABLogger {
    public boolean mDebugLog = false;
    public String mDebugTag = "InappifyMyket";
    public void logDebug(String ignored) {}
    public void logError(String ignored) {}
    public void logWarn(String ignored) {}
}
