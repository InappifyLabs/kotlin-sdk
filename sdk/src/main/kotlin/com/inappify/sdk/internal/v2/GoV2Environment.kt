package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.InappifyV2Configuration
import com.inappify.sdk.InappifyV2SessionScope

/** Service trust is SDK configuration; app/project scope may be learned only after signature verification. */
internal class GoV2Environment(
    val sdkApiBaseUrl: String,
    pinnedSigningKeys: Map<String, String>,
    val expectedScope: InappifyV2SessionScope? = null,
    paymentHosts: Set<String> = emptySet(),
    val commerceApiBaseUrl: String = if (sdkApiBaseUrl == InappifyV2Configuration.DEFAULT_SDK_API_BASE_URL)
        InappifyV2Configuration.DEFAULT_COMMERCE_API_BASE_URL else sdkApiBaseUrl,
) {
    val pinnedSigningKeys = pinnedSigningKeys.toMap()
    val paymentHosts = paymentHosts.toSet()

    init { require(this.pinnedSigningKeys.isNotEmpty()) }

    companion object {
        fun explicit(config: InappifyV2Configuration) = GoV2Environment(
            config.sdkApiBaseUrl, config.pinnedSigningKeys,
            InappifyV2SessionScope(config.issuer, config.appId, config.projectId), config.paymentHosts,
            config.commerceApiBaseUrl,
        )

        fun production() = GoV2Environment(
            InappifyV2Configuration.DEFAULT_SDK_API_BASE_URL,
            // Public signing key obtained over verified HTTPS from the operator-confirmed
            // /app/v2/public-keys endpoint on 2026-09-12. This is NOT an API key or private key.
            // Runtime JWKS downloads cannot replace this trust anchor with different key material.
            mapOf("sdk-v2-2026-09-09" to "AJr7nlATgLmPOiCoY1W28UucpkLw_DWMjD-trvv73Ak"),
            paymentHosts = setOf("pay.inappify.com"),
        )
    }
}

internal fun InappifyV2SessionScope.toJson(): JsonObject = JsonObject().apply {
    addProperty("issuer", issuer)
    addProperty("appId", appId)
    addProperty("projectId", projectId)
}

internal fun JsonObject.sessionScope(): InappifyV2SessionScope = InappifyV2SessionScope(
    string("issuer"), number("appId"), number("projectId"),
)

/** The signed backend subject is distinct from the host's public app-user identifier. */
internal data class VerifiedCustomerInfo(
    val info: JsonObject,
    val scope: InappifyV2SessionScope,
    val subject: String,
)
