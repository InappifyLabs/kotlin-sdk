package com.inappify.sdk.internal.billing

import android.content.Context
import com.inappify.sdk.InappifyMarket

/** Android billing factory which never retains an Activity context. */
internal class AndroidStoreBillingAdapterFactory(
    context: Context,
) : StoreBillingAdapterFactory {
    private val applicationContext: Context = context.applicationContext

    override fun create(
        market: InappifyMarket,
        marketKey: String?,
    ): StoreBillingAdapter = when (market) {
        InappifyMarket.BAZAAR -> {
            if (marketKey.isNullOrEmpty()) {
                UnsupportedStoreBillingAdapter(
                    StoreBillingError(
                        code = StoreBillingErrorCode.MISSING_MARKET_KEY,
                        message = "Cafe Bazaar billing requires a public RSA key.",
                    ),
                )
            } else {
                BazaarStoreBillingAdapter(
                    applicationContext = applicationContext,
                    rsaPublicKey = marketKey,
                )
            }
        }

        InappifyMarket.MYKET -> if (marketKey.isNullOrBlank()) {
            UnsupportedStoreBillingAdapter(StoreBillingError(
                StoreBillingErrorCode.MISSING_MARKET_KEY,
                "Myket billing requires a public RSA key.",
            ))
        } else {
            MyketStoreBillingAdapter(applicationContext, marketKey)
        }

        InappifyMarket.NONE -> UnsupportedStoreBillingAdapter(
            StoreBillingError(
                code = StoreBillingErrorCode.UNSUPPORTED_MARKET,
                message = "A native billing marketplace has not been configured.",
            ),
        )
    }
}
