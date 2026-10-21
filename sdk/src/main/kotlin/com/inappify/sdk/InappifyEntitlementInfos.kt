package com.inappify.sdk

import com.inappify.sdk.internal.v2.isoMillis
import java.util.Collections

/** Entitlements indexed by identifier, with list access retained for existing integrations. */
public class InappifyEntitlementInfos internal constructor(
    private val values: List<InappifyEntitlement>,
    private val now: () -> Long,
) : AbstractList<InappifyEntitlement>() {
    /** All known rights, including expired and inactive rights. */
    public val all: Map<String, InappifyEntitlement> = Collections.unmodifiableMap(
        linkedMapOf<String, InappifyEntitlement>().apply {
            this@InappifyEntitlementInfos.values.forEach { entitlement -> entitlement.identifier?.let {
                if (!containsKey(it)) put(it, entitlement)
            } }
        },
    )

    /** Current evaluation clock for platform bridges; no system-clock reinterpretation is needed. */
    public val evaluationTimeMillis: Long get() = now()

    /** Active rights at the time of access; expiry uses the client's trusted clock. */
    public val active: Map<String, InappifyEntitlement>
        get() {
            val at = now()
            return Collections.unmodifiableMap(all.filterValues { entitlement ->
                entitlement.isActive == true && (entitlement.expirationDate.isNullOrBlank() ||
                    runCatching { isoMillis(entitlement.expirationDate) > at }.getOrDefault(false))
            })
        }

    /** Looks up a right regardless of its active state. */
    public operator fun get(identifier: String): InappifyEntitlement? = all[identifier]

    public override val size: Int get() = values.size
    public override fun get(index: Int): InappifyEntitlement = values[index]
}
