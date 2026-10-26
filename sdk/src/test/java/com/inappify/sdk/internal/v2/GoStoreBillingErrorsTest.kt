package com.inappify.sdk.internal.v2

import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import com.inappify.sdk.internal.billing.myketOperationException
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

    @Test fun exceptionCategorySurvivesWithoutLeakingMessagesOrChangingUncertainPolicy() {
        val error = myketOperationException(IllegalStateException("secret-receipt"), "OPERATION_START")
            .toGoPurchaseError("attempt", "myket")
        assertEquals("ILLEGAL_STATE", error.details["storeExceptionCategory"])
        assertEquals("OPERATION_START", error.details["storeFailureStage"])
        assertEquals(true, error.details["outcomeMayHaveCommitted"])
        assertFalse(error.isRetryable)
        assertFalse(error.details.containsKey("storeResponseCode"))
        assertFalse("${error.message} ${error.details}".contains("secret-receipt"))
    }

    @Test fun unknownExceptionNamesAndNonMyketDiagnosticsAreNotPublished() {
        val raw = StoreBillingError(StoreBillingErrorCode.PURCHASE_FAILED, "secret", causeType = "private-customer",
            failureStage = "private-receipt")
        for (store in listOf("myket", "bazar")) {
            val error = raw.toGoPurchaseError("attempt", store)
            assertFalse(error.details.containsKey("storeExceptionCategory"))
            assertFalse(error.details.containsKey("storeFailureStage"))
        }
        val bazaar = myketOperationException(SecurityException(), "SETUP").toGoPurchaseError("attempt", "bazar")
        assertFalse(bazaar.details.containsKey("storeExceptionCategory"))
    }
}
