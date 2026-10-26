package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import org.junit.Assert.*
import org.junit.Test

class GoStoreBillingErrorsTest {
    @Test fun numericCallbackSurvivesWithoutLeakingRawMessageOrChangingRetryPolicy() {
        for (responseCode in listOf(7, 6, -1002)) {
            val error = StoreBillingError(StoreBillingErrorCode.PURCHASE_FAILED,
                "private callback payload", storeResponseCode = responseCode).toGoPurchaseError("attempt", "myket")
            assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, error.code)
            assertEquals(responseCode, error.details["storeResponseCode"])
            assertEquals("PURCHASE_FAILED", error.details["storeCode"])
            assertEquals(true, error.details["outcomeMayHaveCommitted"])
            assertFalse(error.isRetryable)
            assertFalse("${error.message} ${error.details}".contains("private callback payload"))
        }
    }

    @Test fun absentCallbackCodeIsNotInvented() {
        val error = StoreBillingError(StoreBillingErrorCode.PURCHASE_FAILED, "failed").toGoPurchaseError("attempt")
        assertFalse(error.details.containsKey("storeResponseCode"))
    }
}
