package com.inappify.sdk.internal.billing

import android.content.Context

/** Store adapters included at build time; never selected by an untrusted client value. */
internal object CompiledStoreAdapters {
    fun bazaar(context: Context, publicKey: String): StoreBillingAdapter =
        BazaarStoreBillingAdapter(context, publicKey)

    fun myket(context: Context, publicKey: String): StoreBillingAdapter =
        MyketStoreBillingAdapter(context, publicKey)
}
