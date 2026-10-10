package com.inappify.sdk.internal.v2

import java.security.MessageDigest

/** Keep the UTF-8 byte-length encoding aligned with Laravel StorePurchaseBindingService. */
internal fun goPurchaseBinding(
    appId: Long,
    appIdentifier: String,
    customerIdentifierFingerprint: String,
    productIdentifier: String,
    offeringIdentifier: String,
    packageIdentifier: String,
): String {
    val parts = listOf("inappify-purchase-binding-v1", appId.toString(), appIdentifier,
        customerIdentifierFingerprint, productIdentifier, offeringIdentifier, packageIdentifier)
    val encoded = parts.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    val hash = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "v1:$hash"
}
