package com.inappify.sdk.internal.v2

import com.inappify.sdk.internal.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoV2StoreServiceTest {
    private val transport = V2Transport()
    private val service = GoV2StoreService(GoApi(transport, { 0L }, {})) { "fixture-go-session" }
    @Test fun subscriptionRecoveryNeverSendsGuessedContextOrMutatesSignedEvidence() = runBlocking {
        transport.handler = { transport.response(jsonObject("""{"status":true,"data":{"purchase":{
            "status":"PROCESSING","source":"bazar","productIdentifier":"sku","verificationRequestId":9}}}""")) }
        val receipt = "{\"developerPayload\":\"\",\"purchaseToken\":\"receipt-token\"}"
        val result = service.recoverStoreSubscription(StoreSubscriptionRecoveryApiRequest("sku",
            StorePurchaseEvidence("receipt-token", 123L, "order", "com.example.app", "", receipt, "signature")))
        assertTrue(result is StoreServiceResult.Response)
        val request = transport.requests.single()
        assertEquals("store/subscriptions/recover", request.path)
        assertEquals("Bearer fixture-go-session", request.headers["Authorization"])
        val body = jsonObject(request.jsonBody)
        assertEquals(setOf("productIdentifier", "purchase"), body.keySet())
        assertEquals(receipt, body.getAsJsonObject("purchase").string("originalJson"))
        assertEquals("", body.getAsJsonObject("purchase").string("developerPayload"))
    }
    private suspend fun status(state: String): StoreServiceResult {
        transport.handler = { transport.response(jsonObject("""{"status":true,"data":$state}""")) }
        return service.getStoreVerificationStatus(StoreVerificationStatusApiRequest(9))
    }
    @Test fun rejectsCoercedFractionalOverflowNegativeAndNonNumericIdentifiers() = runBlocking {
        for (value in listOf("1.5", "\"7\"", "true", "9223372036854775808", "0", "-1", "{}")) {
            val result = status("""{"status":"DELIVERY_REQUIRED","deliveryId":$value}""")
            assertTrue("Invalid ID $value was accepted", result is StoreServiceResult.Failure)
            assertEquals(ServiceFailureKind.MALFORMED_RESPONSE, (result as StoreServiceResult.Failure).kind)
        }
    }
    @Test fun rejectsMalformedOptionalFlagsAndScopeInsteadOfErasingThem() = runBlocking {
        for (field in listOf("\"alreadyDelivered\":\"false\"", "\"alreadyProcessed\":1",
            "\"productIdentifier\":12", "\"source\":true", "\"retryAfter\":-1", "\"retryAfter\":0.5")) {
            assertTrue(status("""{"status":"DELIVERY_REQUIRED","deliveryId":7,$field}""") is StoreServiceResult.Failure)
        }
    }
    @Test fun validOptionalFieldsAndNullsRetainExactIdentifiers() = runBlocking {
        val result = status("""{"status":"DELIVERY_REQUIRED","deliveryId":9223372036854775807,
            "verificationRequestId":9,"retryAfter":0,"alreadyDelivered":false,"eventId":null}""") as StoreServiceResult.Response
        val state = requireNotNull(result.payload.state)
        assertEquals(Long.MAX_VALUE, state.deliveryId)
        assertEquals(0L, state.retryAfter)
        assertEquals(false, state.alreadyDelivered)
        assertEquals("Bearer fixture-go-session", transport.requests.single().headers["Authorization"])
        assertEquals("{}", transport.requests.single().jsonBody)
    }
    @Test fun deliveryAcknowledgementAcceptsFlatAndWrappedTransitionResponses() = runBlocking {
        for (data in listOf("""{"status":"CONSUME_REQUIRED","deliveryId":7}""",
            """{"purchase":{"status":"CONSUME_REQUIRED","deliveryId":7}}""")) {
            transport.handler = { transport.response(jsonObject("""{"status":true,"data":$data}""")) }
            val result = service.markStoreDeliveryDelivered(StoreDeliveryApiRequest(7))
            assertEquals(StorePurchaseStatus.CONSUME_REQUIRED, (result as StoreServiceResult.Response).payload.state!!.status)
        }
    }
    @Test fun transportCancellationIsNeverConvertedToMalformedResponse() = runBlocking {
        transport.handler = { throw CancellationException("fixture cancellation") }
        try {
            service.markStoreDeliveryDelivered(StoreDeliveryApiRequest(7))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun rateLimitPreservesRetryAfterForDurableCoordinatorBackoff() = runBlocking {
        transport.handler = { transport.response(jsonObject("""{"status":false,"code":"RATE_LIMITED"}"""), 429,
            mapOf("Retry-After" to "300")) }
        val result = service.getStoreVerificationStatus(StoreVerificationStatusApiRequest(9)) as StoreServiceResult.Response
        assertEquals(429, result.statusCode)
        assertEquals(300L, result.retryAfterSeconds)
        assertEquals(1, transport.requests.size)
    }

}
