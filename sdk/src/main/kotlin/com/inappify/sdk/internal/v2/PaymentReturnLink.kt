package com.inappify.sdk.internal.v2

import java.net.URI
import java.net.URLDecoder

/** A routing hint, never receipt evidence or proof of payment. */
internal data class PaymentReturnLink(val appId: Long, val returnId: String) {
    companion object {
        fun parse(url: String): PaymentReturnLink? = runCatching {
            if (url.length > 16_384 || url.any(Char::isISOControl)) return null
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme in setOf("javascript", "data", "file", "intent")) return null
            // Also supports opaque custom URIs, e.g. myapp:payment?key=value.
            val query = url.substringBefore('#').substringAfter('?', "")
            val reserved = setOf("inappify_return", "inappify_app_id", "inappify_return_id")
            val values = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                if (key !in reserved) continue
                if (values.containsKey(key)) return null
                values[key] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            if (values["inappify_return"] != "payment_v1") return null
            val app = values["inappify_app_id"]?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull() ?: return null
            val id = values["inappify_return_id"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return null
            PaymentReturnLink(app, id)
        }.getOrNull()
    }
}
