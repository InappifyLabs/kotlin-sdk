package com.inappify.sdk.internal.v2

import com.inappify.sdk.*
import com.inappify.sdk.internal.TargetingSyncRateLimiter
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoTargetingSyncTest {
    private val signing = SigningFixture()
    private val transport = V2Transport()
    private val store = MemoryV2Store()
    private var rejectSecondBatch = false
    private var attributeRequests = 0

    private fun client(): GoV2Client {
        transport.handler = { request -> when (request.path) {
            "configure" -> transport.response(signing.envelope())
            "offerings" -> transport.response(jsonObject(
                """{"status":true,"offerings":[{"identifier":"targeted","isDefault":true}],"currentOffering":"targeted","placements":{"onboarding":"targeted"}}"""))
            "attributes" -> {
                attributeRequests++
                if (rejectSecondBatch && attributeRequests == 2) transport.response(
                    jsonObject("""{"status":false,"code":"ATTRIBUTE_INVALID"}"""), 422)
                else TransportResult.Response(HttpResponse(204, null, null))
            }
            else -> error("Unexpected ${request.path}")
        } }
        return GoV2Client(signing.config, GoApi(transport, { signing.now }, {}), store,
            AppMetadataProvider { AppMetadata("com.example.mobile", "1.0", 1) },
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = false,
            targetingSyncLimiter = TargetingSyncRateLimiter({ 0 }, {}))
    }

    @Test fun flushesAllBatchesIncludingDeletesBeforeOneNetworkFetch() = runBlocking {
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("fixture-key", appUserIdentifier = signing.subject)) is InappifyResult.Success)
            val values = (1..51).associate { "key$it" to "value$it" } + ("deleted" to null)
            assertTrue(sdk.queueAttributes(values) is InappifyResult.Success)
            transport.requests.clear()
            val result = sdk.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Success
            assertEquals(listOf("attributes", "attributes", "offerings"), transport.requests.map { it.path })
            val batches = transport.requests.filter { it.path == "attributes" }.map {
                jsonObject(it.jsonBody).getAsJsonObject("attributes")
            }
            assertEquals(listOf(50, 2), batches.map { it.size() })
            assertTrue(batches.last().get("deleted").isJsonNull)
            assertEquals("targeted", result.data.placements!!["onboarding"])
            transport.requests.clear()
            assertTrue(sdk.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Success)
            assertEquals(listOf("offerings"), transport.requests.map { it.path })
        }
    }

    @Test fun rejectedLaterBatchIsQuarantinedAndStopsOfferingsUntilCallerCorrectsIt() = runBlocking {
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("fixture-key", appUserIdentifier = signing.subject)) is InappifyResult.Success)
            assertTrue(sdk.queueAttributes((1..51).associate { "key$it" to "value$it" }) is InappifyResult.Success)
            transport.requests.clear()
            rejectSecondBatch = true
            assertTrue(sdk.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Failure)
            assertEquals(listOf("attributes", "attributes"), transport.requests.map { it.path })
            val saved = jsonObject(store.value!!.customerInfoJson!!)
            assertEquals(0, saved.getAsJsonObject("attributes").size())
            assertEquals("value51", saved.getAsJsonObject("quarantine").get("key51").asString)
            assertTrue(sdk.queueAttributes(mapOf("key51" to "corrected")) is InappifyResult.Success)
            transport.requests.clear()
            assertTrue(sdk.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Success)
            assertEquals(listOf("attributes", "offerings"), transport.requests.map { it.path })
            assertEquals("corrected", jsonObject(transport.requests.first().jsonBody)
                .getAsJsonObject("attributes").get("key51").asString)
        }
    }

    @Test fun attributesQueuedAfterQuotaRemainPendingAndInvalidatedOfferingsAreNotReturned() = runBlocking {
        client().use { sdk ->
            assertTrue(sdk.configure(InappifyOptions("fixture-key", appUserIdentifier = signing.subject)) is InappifyResult.Success)
            repeat(5) { assertTrue(sdk.syncAttributesAndOfferingsIfNeeded() is InappifyResult.Success) }
            assertTrue(sdk.queueAttributes(mapOf("tier" to "new")) is InappifyResult.Success)
            transport.requests.clear()
            val result = sdk.syncAttributesAndOfferingsIfNeeded() as InappifyResult.Failure
            assertEquals("RATE_LIMITED_NO_CACHE", result.error.details["reason"])
            assertTrue(transport.requests.isEmpty())
            assertNull(requireNotNull(result.snapshot).offerings)
            assertEquals("new", jsonObject(store.value!!.customerInfoJson!!).getAsJsonObject("attributes").get("tier").asString)
        }
    }
}
