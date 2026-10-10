package com.inappify.sdk.internal.billing

import com.inappify.sdk.InappifyDeliverySource
import com.inappify.sdk.InappifyMarket

internal val InappifyMarket.storeId: String
    get() = when (this) {
        InappifyMarket.BAZAAR -> "bazar"
        InappifyMarket.MYKET -> "myket"
        InappifyMarket.NONE -> error("Direct purchases have no native store.")
    }

internal fun nativeStoreMarket(value: String?): InappifyMarket? = when (value?.lowercase()) {
    "bazar", "bazaar" -> InappifyMarket.BAZAAR
    "myket" -> InappifyMarket.MYKET
    else -> null
}

internal val InappifyMarket.deliverySource: InappifyDeliverySource
    get() = when (this) {
        InappifyMarket.BAZAAR -> InappifyDeliverySource.BAZAAR
        InappifyMarket.MYKET -> InappifyDeliverySource.MYKET
        InappifyMarket.NONE -> error("Direct purchases have no native store.")
    }

/** Myket has no native subscription product. Never query or sell one implicitly. */
internal val InappifyMarket.ownedProductTypes: List<StoreProductType>
    get() = if (this == InappifyMarket.MYKET) listOf(StoreProductType.IN_APP)
        else listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP)
