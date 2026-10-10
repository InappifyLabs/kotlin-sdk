package com.inappify.sdk

import java.util.Collections

/**
 * Immutable inputs used to evaluate Inappify offering rules.
 *
 * The client builds this context from its current snapshot. Callers may
 * provide an explicit value when evaluating a standalone [InappifyOfferings]
 * model.
 */
public class InappifyOfferingEvaluationContext public constructor(
    public val country: String,
    public val platform: String,
    public val appVersion: String,
    public val sdkVersion: String = "",
    public val appId: Long? = null,
    customAttributes: Map<String, String?> = emptyMap(),
) {

    /** Defensive, unmodifiable copy of customer attributes keyed by identifier. */
    public val customAttributes: Map<String, String?> =
        Collections.unmodifiableMap(LinkedHashMap(customAttributes))
}
