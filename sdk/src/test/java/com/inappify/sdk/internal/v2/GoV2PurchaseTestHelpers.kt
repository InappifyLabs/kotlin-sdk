package com.inappify.sdk.internal.v2

import android.app.Activity
import com.inappify.sdk.InappifyPurchase
import com.inappify.sdk.InappifyPurchaseRequest
import com.inappify.sdk.InappifyResult

/** Exercises internal purchase states without exposing raw request entry points to applications. */
internal suspend fun GoV2Client.purchase(request: InappifyPurchaseRequest): InappifyResult<InappifyPurchase> =
    purchaseInternal(null, request)

internal suspend fun GoV2Client.purchase(
    activity: Activity,
    request: InappifyPurchaseRequest,
): InappifyResult<InappifyPurchase> = purchaseInternal(activity, request)
