package com.inappify.sdk.internal.v2

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.inappify.sdk.*
import com.inappify.sdk.internal.billing.*
import com.inappify.sdk.internal.network.*
import com.inappify.sdk.internal.platform.AppMetadata
import com.inappify.sdk.internal.platform.AppMetadataProvider
import com.inappify.sdk.internal.storage.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises configure's real background recovery, including a verified cached restart. */
class GoAutomaticStoreRecoveryTest {
    @Test fun payloadlessSubscriptionRecoveryCanBeExplicitlyDisabled() = runBlocking {
        val fixture = Fixture(recoveryEnabled = false)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertTrue(fixture.commerceTransport.requests.isEmpty())
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun payloadlessSubscriptionRecoveryIsEnabledWithoutAnAppOption() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.options.enableSubscriptionRecoveryWithoutPayload)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        fixture.acceptRecovery()

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertEquals("store/subscriptions/recover", fixture.commerceTransport.requests.single().path)
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun payloadlessSubscriptionUsesOnlyRecoveryRouteWithUnchangedEvidence() = runBlocking {
        for (payload in listOf("", " \t")) {
            val fixture = Fixture(recoveryEnabled = true)
            fixture.seedConfiguredSession()
            val receipt = fixture.unboundReceipt(payload = payload)
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(receipt))
            assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
            val request = fixture.commerceTransport.requests.single()
            assertEquals("store/subscriptions/recover", request.path)
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            val body = jsonObject(request.jsonBody)
            assertEquals(setOf("productIdentifier", "purchase"), body.keySet())
            val evidence = body.getAsJsonObject("purchase")
            assertEquals(receipt.developerPayload, evidence.string("developerPayload"))
            assertEquals(receipt.originalJson, evidence.string("originalJson"))
            assertEquals(receipt.signature, evidence.string("signature"))
            assertEquals(receipt.purchaseToken, evidence.string("token"))
            assertEquals(receipt.purchaseTimeMillis, evidence.number("purchaseTime"))
            assertTrue(fixture.storage.operations.isEmpty())
            assertTrue(fixture.storage.writtenOperations.all { it.offeringIdentifier == null })
        }
    }

    @Test fun malformedOrUnboundNonemptyPayloadNeverDowngradesToRecovery() = runBlocking {
        for (payload in listOf("{", "null", "{}", "{\"purchaseBinding\":\"v1:invalid\"}")) {
            val fixture = Fixture(recoveryEnabled = true)
            fixture.seedConfiguredSession()
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
                fixture.unboundReceipt(payload = payload)))
            assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
            assertTrue(fixture.commerceTransport.requests.isEmpty())
        }
    }

    @Test fun optInDoesNotBypassExistingPurchaseBindingChecks() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
            fixture.receipt(type = InappifyProductType.SUBSCRIPTION, purchaseBinding = JsonPrimitive("v1:wrong"))))
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertTrue(fixture.commerceTransport.requests.isEmpty())
    }

    @Test fun optInPreservesTheBoundPurchaseRoute() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
            fixture.receipt(type = InappifyProductType.SUBSCRIPTION)))
        fixture.commerceTransport.handler = { request ->
            assertEquals("store/purchases", request.path)
            fixture.commerceTransport.response(jsonObject("""{"status":true,"data":{"purchase":{"status":"COMPLETED"}}}"""))
        }
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertEquals("store/purchases", fixture.commerceTransport.requests.single().path)
    }

    @Test fun serverOwnershipRejectionIsQuarantinedWithoutGrantOrRepeatedSubmission() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        fixture.commerceTransport.handler = { TransportResult.Response(HttpResponse(422,
            """{"status":false,"code":"SUBSCRIPTION_OWNER_MISMATCH"}""", null)) }
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
        assertEquals(1, fixture.storage.tombstones.size)
        assertTrue(fixture.storage.operations.isEmpty())
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertEquals(1, fixture.commerceTransport.requests.size)
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun payloadlessInAppReceiptsNeverUseSubscriptionRecovery() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val fixture = Fixture(market, recoveryEnabled = true)
            fixture.seedConfiguredSession()
            fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
            assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
            assertTrue(fixture.commerceTransport.requests.isEmpty())
        }
    }

    @Test fun missingSignatureOrOriginalReceiptCannotEnterRecoveryQueue() = runBlocking {
        for (missing in listOf("signature", "receipt", "time")) {
            val fixture = Fixture(recoveryEnabled = true)
            fixture.seedConfiguredSession()
            val receipt = fixture.unboundReceipt().let {
                StorePurchase(it.orderIdentifier, it.purchaseToken, it.developerPayload, it.packageName,
                    it.productIdentifier, if (missing == "time") 0 else it.purchaseTimeMillis,
                    if (missing == "receipt") "" else it.originalJson,
                    if (missing == "signature") "" else it.signature)
            }
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(receipt))
            assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
            assertTrue(fixture.commerceTransport.requests.isEmpty())
            assertTrue(fixture.storage.operations.isEmpty())
        }
    }

    @Test fun payloadlessRenewalRetriesDurablyAndSeparatesNewTokenOrTime() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        val first = fixture.unboundReceipt()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(first, first))
        // Keep the checkpoint beyond the coordinator's bounded in-process wait.
        fixture.commerceTransport.handler = { TransportResult.Response(HttpResponse(
            503, """{"status":false}""", null, headers = mapOf("Retry-After" to "60"))) }
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
        val saved = fixture.storage.operations.single()
        assertEquals(PendingStoreOperationType.RECOVER_SUBSCRIPTION, saved.operation)
        assertTrue(saved.nextRetryAtEpochMillis!! >= fixture.signing.now + 60_000)
        assertEquals(1, fixture.commerceTransport.requests.size)

        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
            first, fixture.unboundReceipt(time = fixture.signing.now + 1000),
            fixture.unboundReceipt(token = "renewed-store-token")))
        fixture.commerceTransport.requests.clear()
        fixture.acceptRecovery()
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure) // First occurrence is not due yet.
        assertEquals(saved, fixture.storage.operations.single())
        assertEquals(2, fixture.commerceTransport.requests.size)
        assertEquals(3, fixture.storage.writtenOperations.map { it.id }.distinct().size)

        fixture.storage.upsertPendingStoreOperation(saved.copy(nextRetryAtEpochMillis = fixture.signing.now))
        fixture.owned.clear()
        fixture.commerceTransport.requests.clear()
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertEquals("store/subscriptions/recover", fixture.commerceTransport.requests.single().path)
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun disablingRecoveryRetainsCheckpointWithoutSendingIt() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        fixture.commerceTransport.handler = { fixture.commerceTransport.failureResponse(503) }
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
        val saved = fixture.storage.operations.single()
        fixture.options = InappifyOptions("public-fixture-key", fixture.signing.subject,
            enableSubscriptionRecoveryWithoutPayload = false)
        fixture.owned.clear()
        fixture.commerceTransport.requests.clear()
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
        assertEquals(saved, fixture.storage.operations.single())
        assertTrue(fixture.commerceTransport.requests.isEmpty())
    }

    @Test fun recoveryCannotReplayCheckpointBoundToAnotherCustomer() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        fixture.commerceTransport.handler = { fixture.commerceTransport.failureResponse(503) }
        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
        val saved = fixture.storage.operations.single().copy(customerIdentifierFingerprint = "other-customer")
        fixture.storage.upsertPendingStoreOperation(saved)
        fixture.owned.clear()
        fixture.commerceTransport.requests.clear()
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertEquals(saved, fixture.storage.operations.single())
        assertTrue(fixture.commerceTransport.requests.isEmpty())
    }

    @Test fun recoveryRequiresMatchingServerSourceAndProductAndNeverConsumes() = runBlocking {
        for (state in listOf(
            """{"status":"COMPLETED"}""",
            """{"status":"COMPLETED","source":"myket","productIdentifier":"product"}""",
            """{"status":"COMPLETED","source":"bazar","productIdentifier":"other"}""",
            """{"status":"DELIVERY_REQUIRED","source":"bazar","productIdentifier":"product","deliveryId":8}""")) {
            val fixture = Fixture(recoveryEnabled = true)
            fixture.seedConfiguredSession()
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
            fixture.commerceTransport.handler = { fixture.commerceTransport.response(
                jsonObject("""{"status":true,"data":{"purchase":$state}}""")) }
            assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)
            assertEquals(1, fixture.storage.operations.size)
            assertTrue(fixture.commerceTransport.requests.all { it.path == "store/subscriptions/recover" })
        }
    }

    @Test fun payloadlessServerReceiptDedupAndExplicitRestoreUseDifferentPolicies() = runBlocking {
        val fixture = Fixture(recoveryEnabled = true)
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(fixture.unboundReceipt()))
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertTrue(fixture.commerceTransport.requests.isEmpty())
        fixture.client(backgroundRecovery = false).use { sdk ->
            assertTrue(sdk.configure(fixture.options) is InappifyResult.Success)
            assertTrue(sdk.restorePurchases() is InappifyResult.Success)
        }
        assertEquals("store/subscriptions/recover", fixture.commerceTransport.requests.single().path)
    }

    @Test fun recoveryPreservesValidNewBindingsForBothMarkets() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            val fixture = Fixture(market)
            fixture.seedConfiguredSession()
            val binding = goPurchaseBinding(12, "com.example.mobile", fixture.digest(fixture.signing.subject),
                "product", "original", "package")
            val receipt = fixture.receipt(purchaseBinding = JsonPrimitive(binding))
            fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))

            assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
            val purchase = jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase")
            assertEquals(receipt.developerPayload, purchase.string("developerPayload"))
        }
    }

    @Test fun recoveryRejectsMalformedOrMismatchedNewBindingsForBothMarkets() = runBlocking {
        for (market in listOf(InappifyMarket.BAZAAR, InappifyMarket.MYKET)) {
            for (binding in listOf(JsonNull.INSTANCE, JsonPrimitive(123), JsonPrimitive("v1:wrong"))) {
                val fixture = Fixture(market)
                fixture.seedConfiguredSession()
                fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(
                    listOf(fixture.receipt(purchaseBinding = binding)))

                assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
                assertTrue(fixture.commerceTransport.requests.isEmpty())
            }
        }
    }

    @Test fun freshOnlineConfigureQueriesAndRecoversOwnedReceipt() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val receipt = fixture.receipt()
        fixture.storage.clear() // Keep the store-owned receipt while removing the cached SDK session.
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(1, fixture.sdkTransport.requests.count { it.path == "configure" })
        assertEquals("sdk:configure", fixture.events.first())
        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        val request = fixture.commerceTransport.requests.single()
        assertEquals("store/purchases", request.path)
        assertEquals("Bearer go-session-token", request.headers["Authorization"])
        assertEquals(receipt.purchaseToken, jsonObject(request.jsonBody).getAsJsonObject("purchase").string("token"))
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun cachedConfigureRecoversLostReceiptBeforeFailingOfferings() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val receipt = fixture.receipt()
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))
        fixture.offeringsFail = true

        val result = fixture.automaticRecovery()

        assertTrue(result is InappifyResult.Failure)
        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        assertFalse(fixture.sdkTransport.requests.any { it.path == "configure" })
        val request = fixture.commerceTransport.requests.single()
        assertEquals("store/purchases", request.path)
        assertEquals("Bearer go-session-token", request.headers["Authorization"])
        val body = jsonObject(request.jsonBody)
        assertEquals(setOf("productIdentifier", "offeringIdentifier", "operation", "purchase"), body.keySet())
        assertEquals("original", body.string("offeringIdentifier"))
        assertEquals(receipt.purchaseToken, body.getAsJsonObject("purchase").string("token"))
        assertEquals(receipt.purchaseTimeMillis, body.getAsJsonObject("purchase").number("purchaseTime"))
        assertTrue(fixture.events.indexOf("commerce:store/purchases") < fixture.events.indexOf("sdk:offerings"))
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun matchingServerHashAndTimeSkipSubmissionOnEveryCachedRestart() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now, active = false)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)
        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP,
            StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        assertTrue(fixture.commerceTransport.requests.isEmpty())
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun renewedSubscriptionWithSameTokenAndNewTimeIsSubmitted() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        val renewal = fixture.receipt(type = InappifyProductType.SUBSCRIPTION,
            time = fixture.signing.now + 30 * 24 * 60 * 60 * 1000L)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(renewal))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        val purchase = jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase")
        assertEquals(fixture.token, purchase.string("token"))
        assertEquals(renewal.purchaseTimeMillis, purchase.number("purchaseTime"))
    }

    @Test fun subscriptionQueryRecoversMissingOrBlankPayloadTypeWithoutChangingReceipt() = runBlocking {
        for (payloadType in listOf<JsonElement?>(null, JsonNull.INSTANCE, JsonPrimitive(""), JsonPrimitive(" \t"))) {
            val fixture = Fixture()
            fixture.seedConfiguredSession()
            val receipt = fixture.receipt(type = null, payloadType = payloadType)
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(receipt))

            assertTrue("payloadType=$payloadType", fixture.automaticRecovery() is InappifyResult.Success)

            val request = fixture.commerceTransport.requests.single()
            assertEquals("store/purchases", request.path)
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            val body = jsonObject(request.jsonBody)
            assertEquals(setOf("productIdentifier", "offeringIdentifier", "operation", "purchase"), body.keySet())
            assertEquals("product", body.string("productIdentifier"))
            assertEquals("original", body.string("offeringIdentifier"))
            assertEquals("purchase", body.string("operation"))
            val purchase = body.getAsJsonObject("purchase")
            assertEquals(receipt.purchaseToken, purchase.string("token"))
            assertEquals(receipt.purchaseTimeMillis, purchase.number("purchaseTime"))
            assertEquals(receipt.developerPayload, purchase.string("developerPayload"))
            assertEquals(receipt.originalJson, purchase.string("originalJson"))
            assertEquals(receipt.signature, purchase.string("signature"))
            if (payloadType == null) assertFalse(jsonObject(purchase.string("developerPayload")).has("productType"))
            else assertEquals(payloadType, jsonObject(purchase.string("developerPayload")).get("productType"))
            val pending = fixture.storage.writtenOperations.first { it.phase == PendingStoreOperationPhase.REGISTERING }
            assertEquals(PendingStoreProductType.SUBSCRIPTION, pending.productType)
            assertEquals(receipt.developerPayload, pending.evidence.developerPayload)
            assertTrue(fixture.storage.operations.isEmpty())
        }
    }

    @Test fun missingOrBlankSubscriptionPayloadTypeStillDeduplicatesByVerifiedHashAndTime() = runBlocking {
        for (payloadType in listOf<JsonElement?>(null, JsonNull.INSTANCE, JsonPrimitive(""), JsonPrimitive(" \t"))) {
            val fixture = Fixture()
            fixture.setServerReceipt(fixture.token, fixture.signing.now, active = false)
            fixture.seedConfiguredSession()
            fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
                fixture.receipt(type = null, payloadType = payloadType)))

            assertTrue("payloadType=$payloadType", fixture.automaticRecovery() is InappifyResult.Success)

            assertTrue(fixture.commerceTransport.requests.isEmpty())
            assertTrue(fixture.storage.writtenOperations.isEmpty())
        }
    }

    @Test fun missingSubscriptionPayloadTypeWithSameTokenAndNewTimeIsSubmitted() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        val renewal = fixture.receipt(type = null, time = fixture.signing.now + 1000)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(renewal))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        val purchase = jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase")
        assertEquals(fixture.token, purchase.string("token"))
        assertEquals(renewal.purchaseTimeMillis, purchase.number("purchaseTime"))
        assertEquals(renewal.developerPayload, purchase.string("developerPayload"))
        assertEquals(PendingStoreProductType.SUBSCRIPTION,
            fixture.storage.writtenOperations.first { it.phase == PendingStoreOperationPhase.REGISTERING }.productType)
    }

    @Test fun missingSubscriptionPayloadTypeRenewalCannotOverwriteEarlierPendingOccurrence() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        val oldReceipt = fixture.receipt(type = null)
        val oldPending = fixture.pending(oldReceipt, PendingStoreProductType.SUBSCRIPTION).copy(
            phase = PendingStoreOperationPhase.VERIFYING, verificationRequestId = 9,
            nextRetryAtEpochMillis = Long.MAX_VALUE)
        fixture.storage.upsertPendingStoreOperation(oldPending)
        fixture.storage.writtenOperations.clear()
        val renewal = fixture.receipt(type = null, time = fixture.signing.now + 1000)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(renewal))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)

        assertEquals(oldPending, fixture.storage.operations.single())
        val recovered = fixture.storage.writtenOperations.first { it.evidence.purchaseTimeMillis == renewal.purchaseTimeMillis }
        assertEquals(PendingStoreProductType.SUBSCRIPTION, recovered.productType)
        assertTrue(recovered.id.startsWith("renewal:"))
        assertNotEquals(oldPending.id, recovered.id)
        val purchase = jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase")
        assertEquals(renewal.developerPayload, purchase.string("developerPayload"))
        assertEquals(renewal.purchaseTimeMillis, purchase.number("purchaseTime"))
        assertFalse(jsonObject(purchase.string("developerPayload")).has("productType"))
    }

    @Test fun missingSubscriptionPayloadTypeKeepsTwoOwnedOccurrencesInSeparateJournals() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val first = fixture.receipt(type = null)
        val second = fixture.receipt(type = null, time = fixture.signing.now + 1000)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(first, second, second))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(listOf(first.purchaseTimeMillis, second.purchaseTimeMillis),
            fixture.commerceTransport.requests.map { jsonObject(it.jsonBody).getAsJsonObject("purchase").number("purchaseTime") })
        val written = fixture.storage.writtenOperations.filter { it.phase == PendingStoreOperationPhase.REGISTERING }
        assertEquals(2, written.size)
        assertTrue(written.all { it.productType == PendingStoreProductType.SUBSCRIPTION })
        assertEquals("attempt-1", written.first().id)
        assertTrue(written.last().id.startsWith("renewal:"))
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun inAppQueryWithMissingOrBlankPayloadTypeDoesNotInferEntitlementDelivery() = runBlocking {
        for (payloadType in listOf<JsonElement?>(null, JsonNull.INSTANCE, JsonPrimitive(""), JsonPrimitive(" \t"))) {
            val fixture = Fixture()
            fixture.setServerReceipt(fixture.token, fixture.signing.now)
            fixture.seedConfiguredSession()
            val receipt = fixture.receipt(type = null, payloadType = payloadType)
            fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))
            fixture.commerceTransport.handler = { request ->
                assertEquals("store/purchases", request.path)
                fixture.commerceTransport.response(jsonObject("""{"status":true,"data":{"purchase":{"status":"ALREADY_PROCESSED"}}}"""))
            }
            val grants = CopyOnWriteArrayList<Long>()

            assertTrue("payloadType=$payloadType", fixture.automaticRecovery(InappifyConsumableDeliveryHandler { delivery ->
                grants += delivery.deliveryId
                InappifyDeliveryResult.DELIVERED
            }) is InappifyResult.Success)

            val request = fixture.commerceTransport.requests.single()
            assertEquals("Bearer go-session-token", request.headers["Authorization"])
            assertEquals(receipt.developerPayload,
                jsonObject(request.jsonBody).getAsJsonObject("purchase").string("developerPayload"))
            assertEquals(PendingStoreProductType.LEGACY_IN_APP,
                fixture.storage.writtenOperations.first { it.phase == PendingStoreOperationPhase.REGISTERING }.productType)
            assertTrue(grants.isEmpty())
            assertTrue(fixture.storage.operations.isEmpty())
        }
    }

    @Test fun presentPayloadTypeConflictingWithStoreQueryIsNeverForwarded() = runBlocking {
        for ((storeType, payloadType) in listOf(
                StoreProductType.SUBSCRIPTION to InappifyProductType.CONSUMABLE.name,
                StoreProductType.SUBSCRIPTION to InappifyProductType.NON_CONSUMABLE.name,
                StoreProductType.SUBSCRIPTION to "UNRECOGNIZED",
                StoreProductType.IN_APP to InappifyProductType.SUBSCRIPTION.name)) {
            val fixture = Fixture()
            fixture.seedConfiguredSession()
            fixture.owned[storeType] = StorePurchaseQueryResult.Success(listOf(
                fixture.receipt(type = null, payloadType = JsonPrimitive(payloadType))))

            assertTrue("$storeType/$payloadType", fixture.automaticRecovery() is InappifyResult.Success)

            assertTrue(fixture.commerceTransport.requests.isEmpty())
            assertTrue(fixture.storage.writtenOperations.isEmpty())
        }
    }

    @Test fun malformedPresentPayloadTypeIsNotTreatedAsMissing() = runBlocking {
        for (storeType in listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP)) {
            for (payloadType in listOf(JsonObject(), JsonArray(), JsonPrimitive(5), JsonPrimitive(true))) {
                val fixture = Fixture()
                fixture.seedConfiguredSession()
                fixture.owned[storeType] = StorePurchaseQueryResult.Success(listOf(
                    fixture.receipt(type = null, payloadType = payloadType)))

                assertTrue("$storeType/$payloadType", fixture.automaticRecovery() is InappifyResult.Success)

                assertTrue(fixture.commerceTransport.requests.isEmpty())
                assertTrue(fixture.storage.writtenOperations.isEmpty())
            }
        }
    }

    @Test fun twoOwnedSubscriptionOccurrencesWithOneTokenAreSubmittedWithoutOverwriting() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val first = fixture.receipt(type = InappifyProductType.SUBSCRIPTION)
        val second = fixture.receipt(type = InappifyProductType.SUBSCRIPTION, time = fixture.signing.now + 1000)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(first, second, second))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(listOf(first.purchaseTimeMillis, second.purchaseTimeMillis),
            fixture.commerceTransport.requests.map { jsonObject(it.jsonBody).getAsJsonObject("purchase").number("purchaseTime") })
        val written = fixture.storage.writtenOperations.filter { it.phase == PendingStoreOperationPhase.REGISTERING }
        assertEquals(2, written.map { it.id }.distinct().size)
        assertEquals("attempt-1", written.first().id)
        assertTrue(written.last().id.startsWith("renewal:"))
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun matchingConsumableEntitlementStillReconcilesServerDeliveryWithoutGrantingTwice() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(
            fixture.receipt(type = InappifyProductType.CONSUMABLE)))
        fixture.commerceTransport.handler = { request ->
            assertEquals("store/purchases", request.path)
            fixture.commerceTransport.response(jsonObject("""{"status":true,"data":{"purchase":{"status":"ALREADY_PROCESSED"}}}"""))
        }
        val grants = CopyOnWriteArrayList<Long>()

        assertTrue(fixture.automaticRecovery(InappifyConsumableDeliveryHandler { delivery ->
            grants += delivery.deliveryId
            InappifyDeliveryResult.DELIVERED
        }) is InappifyResult.Success)

        assertEquals(1, fixture.commerceTransport.requests.size)
        assertTrue(grants.isEmpty())
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun subscriptionRenewalCannotOverwriteEarlierPendingOccurrence() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        val oldReceipt = fixture.receipt(type = InappifyProductType.SUBSCRIPTION)
        val oldPending = fixture.pending(oldReceipt, PendingStoreProductType.SUBSCRIPTION).copy(
            phase = PendingStoreOperationPhase.VERIFYING, verificationRequestId = 9,
            nextRetryAtEpochMillis = Long.MAX_VALUE)
        fixture.storage.upsertPendingStoreOperation(oldPending)
        fixture.storage.writtenOperations.clear()
        val renewal = fixture.receipt(type = InappifyProductType.SUBSCRIPTION, time = fixture.signing.now + 1000)
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(renewal))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure) // The older verification remains deferred.

        assertEquals(oldPending, fixture.storage.operations.single())
        val recovered = fixture.storage.writtenOperations.first { it.evidence.purchaseTimeMillis == renewal.purchaseTimeMillis }
        assertTrue(recovered.id.startsWith("renewal:"))
        assertNotEquals(oldPending.id, recovered.id)
        val wire = jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase")
        assertEquals(renewal.developerPayload, wire.string("developerPayload"))
        assertEquals(renewal.purchaseTimeMillis, wire.number("purchaseTime"))
        assertEquals("attempt-1", jsonObject(wire.string("developerPayload")).string("attemptId"))
    }

    @Test fun matchingServerReceiptDoesNotPreventPendingRegistrationFromResuming() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        val receipt = fixture.receipt()
        fixture.storage.upsertPendingStoreOperation(fixture.pending(receipt))
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(1, fixture.commerceTransport.requests.size)
        assertEquals(receipt.purchaseTimeMillis,
            jsonObject(fixture.commerceTransport.requests.single().jsonBody).getAsJsonObject("purchase").number("purchaseTime"))
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun subscriptionWithUnknownPendingTimeCannotBeInventedAsANewRenewal() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val receipt = fixture.receipt(type = InappifyProductType.SUBSCRIPTION)
        val oldPending = fixture.pending(receipt, PendingStoreProductType.SUBSCRIPTION).let { pending ->
            pending.copy(evidence = pending.evidence.copy(purchaseTimeMillis = null),
                phase = PendingStoreOperationPhase.VERIFYING, verificationRequestId = 9,
                nextRetryAtEpochMillis = Long.MAX_VALUE)
        }
        fixture.storage.upsertPendingStoreOperation(oldPending)
        fixture.storage.writtenOperations.clear()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Success(listOf(
            fixture.receipt(type = InappifyProductType.SUBSCRIPTION, time = fixture.signing.now + 1000)))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Failure)

        assertEquals(oldPending, fixture.storage.operations.single())
        assertTrue(fixture.storage.writtenOperations.isEmpty())
        assertTrue(fixture.commerceTransport.requests.isEmpty())
        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
    }

    @Test fun missingOrNonmatchingServerReceiptFieldsCannotSuppressRecovery() = runBlocking {
        for (fields in listOf("hash-only", "time-only", "empty-hash", "different-hash")) {
            val fixture = Fixture()
            val entitlement = JsonObject().apply {
                addProperty("identifier", "pro")
                addProperty("is_active", true)
                if (fields != "time-only") addProperty("purchaseStoreRefHash", when (fields) {
                    "empty-hash" -> ""
                    "different-hash" -> fixture.digest("another-token")
                    else -> fixture.digest(fixture.token)
                })
                if (fields != "hash-only") addProperty("purchaseStoreTime", fixture.signing.now)
            }
            fixture.serverEntitlements = JsonArray().apply { add(entitlement) }
            fixture.seedConfiguredSession()
            fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

            assertTrue(fields, fixture.automaticRecovery() is InappifyResult.Success)
            assertEquals(fields, 1, fixture.commerceTransport.requests.size)
        }
    }

    @Test fun failedCustomerInfoRefreshDoesNotDeduplicateAgainstOldCachedEntitlements() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        fixture.customerInfoFail = true
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

        val result = fixture.automaticRecovery()

        assertTrue(result is InappifyResult.Failure)
        assertEquals("HTTP_503", (result as InappifyResult.Failure).error.details["serverCode"])
        assertEquals(1, fixture.commerceTransport.requests.size)
        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        assertTrue(fixture.sdkTransport.requests.any { it.path == "offerings" })
    }

    @Test fun failedSubscriptionQueryDoesNotStarveValidInAppInventory() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.SUBSCRIPTION] = StorePurchaseQueryResult.Failure(StoreBillingError(
            StoreBillingErrorCode.PURCHASE_QUERY_FAILED, "Fixture query failed.", isRetryable = true))
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

        val result = fixture.automaticRecovery()

        assertTrue(result is InappifyResult.Failure)
        assertEquals(InappifyErrorCode.STORE_UNAVAILABLE, (result as InappifyResult.Failure).error.code)
        assertTrue(result.error.isRetryable)
        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        assertEquals(1, fixture.commerceTransport.requests.size)
    }

    @Test fun receiptBoundToAnotherCustomerIsNeverSubmitted() = runBlocking {
        val fixture = Fixture()
        fixture.seedConfiguredSession()
        val receipt = fixture.receipt(bindingSubject = "another-customer")
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(receipt))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertTrue(fixture.commerceTransport.requests.isEmpty())
        assertTrue(fixture.storage.operations.isEmpty())
    }

    @Test fun myketConfigureRecoveryQueriesOnlySupportedInAppInventory() = runBlocking {
        val fixture = Fixture(InappifyMarket.MYKET)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

        assertTrue(fixture.automaticRecovery() is InappifyResult.Success)

        assertEquals(listOf(StoreProductType.IN_APP), fixture.queried)
        assertEquals(1, fixture.commerceTransport.requests.size)
        assertEquals(listOf(InappifyMarket.MYKET), fixture.adapters)
    }

    @Test fun explicitSyncUsesVerifiedServerHashAndTime() = runBlocking {
        val fixture = Fixture()
        fixture.setServerReceipt(fixture.token, fixture.signing.now)
        fixture.seedConfiguredSession()
        fixture.owned[StoreProductType.IN_APP] = StorePurchaseQueryResult.Success(listOf(fixture.receipt()))

        fixture.client(backgroundRecovery = false).use { sdk ->
            assertTrue(sdk.configure(fixture.options) is InappifyResult.Success)
            assertTrue(sdk.syncPurchases() is InappifyResult.Success)
        }

        assertEquals(listOf(StoreProductType.SUBSCRIPTION, StoreProductType.IN_APP), fixture.queried)
        assertTrue(fixture.commerceTransport.requests.isEmpty())
    }

    private class Fixture(val market: InappifyMarket = InappifyMarket.BAZAAR, recoveryEnabled: Boolean? = null) {
        val signing = SigningFixture()
        val token = "automatic-recovery-store-token"
        var options = if (recoveryEnabled == null) InappifyOptions("public-fixture-key", signing.subject)
            else InappifyOptions("public-fixture-key", signing.subject,
                enableSubscriptionRecoveryWithoutPayload = recoveryEnabled)
        val storage = RecoveryStore()
        val events = CopyOnWriteArrayList<String>()
        val sdkTransport = RecordingTransport { events += "sdk:$it" }
        val commerceTransport = RecordingTransport { events += "commerce:$it" }
        val owned = ConcurrentHashMap<StoreProductType, StorePurchaseQueryResult>()
        val queried = CopyOnWriteArrayList<StoreProductType>()
        val adapters = CopyOnWriteArrayList<InappifyMarket>()
        @Volatile var serverEntitlements = JsonArray()
        @Volatile var customerInfoFail = false
        @Volatile var offeringsFail = false
        @Volatile private var queryGate: QueryGate? = null

        init {
            sdkTransport.handler = { request -> when (request.path) {
                "configure" -> sdkTransport.response(envelope())
                "customerInfo" -> if (customerInfoFail) sdkTransport.failureResponse(503)
                    else sdkTransport.response(envelope())
                "offerings" -> if (offeringsFail) sdkTransport.failureResponse(503)
                    else sdkTransport.response(jsonObject("""{"status":true,"offerings":[]}"""))
                else -> error("Unexpected SDK request ${request.path}")
            } }
            commerceTransport.handler = { request ->
                assertTrue(request.path in setOf("store/purchases", "store/subscriptions/recover"))
                if (request.path == "store/subscriptions/recover")
                    assertEquals(setOf("productIdentifier", "purchase"), jsonObject(request.jsonBody).keySet())
                commerceTransport.response(jsonObject("""{"status":true,"data":{"purchase":{"status":"COMPLETED"}}}"""))
            }
            if (recoveryEnabled == true) acceptRecovery()
        }

        fun acceptRecovery() {
            commerceTransport.handler = { request ->
                assertEquals("store/subscriptions/recover", request.path)
                commerceTransport.response(jsonObject("""{"status":true,"data":{"purchase":{
                    "status":"COMPLETED","source":"bazar","productIdentifier":"product"}}}"""))
            }
        }

        fun unboundReceipt(payload: String = "", token: String = this.token, time: Long = signing.now): StorePurchase =
            StorePurchase("renewal-order-$time", token, payload, "com.example.mobile", "product", time,
                JsonObject().apply {
                    addProperty("orderId", "renewal-order-$time")
                    addProperty("purchaseToken", token)
                    addProperty("developerPayload", payload)
                    addProperty("packageName", "com.example.mobile")
                    addProperty("productId", "product")
                    addProperty("purchaseTime", time)
                }.toString(), "signed-renewal-fixture")

        private fun envelope(): JsonObject {
            var signedInfo: JsonObject? = null
            return signing.envelope(mutatePayload = { payload ->
                signedInfo = payload.getAsJsonObject("customer_info").apply {
                    add("entitlements", serverEntitlements.deepCopy())
                }.deepCopy()
            }).apply {
                add("customerInfo", requireNotNull(signedInfo))
                addProperty("storePlatform", if (market == InappifyMarket.MYKET) 11 else 10)
                addProperty("storeInfo", "fixture-public-rsa")
            }
        }

        fun client(backgroundRecovery: Boolean): GoV2Client = GoV2Client(signing.config,
            GoApi(sdkTransport, { signing.now }, {}), storage,
            AppMetadataProvider { AppMetadata("com.example.mobile", "3.5.0", 4040) },
            { signing.now }, Dispatchers.Unconfined, backgroundRecovery = backgroundRecovery,
            commerceApi = GoApi(commerceTransport, { signing.now }, {}),
            billingFactory = StoreBillingAdapterFactory { selected, key ->
                assertEquals(market, selected)
                assertEquals("fixture-public-rsa", key)
                adapters += selected
                object : StoreBillingAdapter {
                    override suspend fun purchase(uiHost: StoreUiHost, request: StorePurchaseRequest): StoreBillingResult =
                        error("Automatic recovery must never open purchase UI")
                    override suspend fun queryPurchases(productType: StoreProductType): StorePurchaseQueryResult {
                        queried += productType
                        events += "query:$productType"
                        queryGate?.let { gate ->
                            if (gate.started.complete(Unit)) gate.release.await()
                        }
                        return owned[productType] ?: StorePurchaseQueryResult.Success(emptyList())
                    }
                    override fun close() = Unit
                }
            })

        suspend fun seedConfiguredSession() {
            client(backgroundRecovery = false).use { sdk ->
                assertTrue(sdk.configure(options) is InappifyResult.Success)
            }
            sdkTransport.requests.clear()
            events.clear()
        }

        /** Joins the recovery scheduled by configure while its first native query is paused. */
        suspend fun automaticRecovery(handler: InappifyConsumableDeliveryHandler? = null): InappifyResult<Unit> = coroutineScope {
            val gate = QueryGate()
            queryGate = gate
            client(backgroundRecovery = true).use { sdk ->
                handler?.let { sdk.setConsumableDeliveryHandler(it) }
                assertTrue("Configure succeeds independently of recovery errors.",
                    sdk.configure(options) is InappifyResult.Success)
                withTimeout(10_000) { gate.started.await() }
                val completion = async(start = CoroutineStart.UNDISPATCHED) { sdk.recover() }
                gate.release.complete(Unit)
                withTimeout(10_000) { completion.await() }
            }.also { queryGate = null }
        }

        fun setServerReceipt(token: String, time: Long, active: Boolean = true) {
            serverEntitlements = JsonArray().apply { add(JsonObject().apply {
                addProperty("identifier", "pro")
                addProperty("is_active", active)
                addProperty("entitlement_type", "Subscription")
                addProperty("purchaseStoreRefHash", digest(token))
                addProperty("purchaseStoreTime", time)
            }) }
        }

        fun receipt(type: InappifyProductType? = InappifyProductType.NON_CONSUMABLE,
            time: Long = signing.now, bindingSubject: String = signing.subject,
            payloadType: JsonElement? = type?.let { JsonPrimitive(it.name) },
            purchaseBinding: JsonElement? = null): StorePurchase {
            val fingerprint = goStoreFingerprint(storage.session!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl)
            val payload = JsonObject().apply {
                addProperty("productIdentifier", "product")
                addProperty("offeringIdentifier", "original")
                addProperty("nativePackageIdentifier", "package")
                if (payloadType != null) add("productType", payloadType)
                addProperty("recoveryBinding", digest("$fingerprint:${digest(bindingSubject)}"))
                addProperty("attemptId", "attempt-1")
                if (purchaseBinding != null) add("purchaseBinding", purchaseBinding)
            }
            return StorePurchase("fixture-order-$time", token, payload.toString(), "com.example.mobile",
                "product", time, "fixture-original-$time", "fixture-signature")
        }

        fun pending(receipt: StorePurchase, type: PendingStoreProductType = PendingStoreProductType.NON_CONSUMABLE): PendingStoreOperation =
            PendingStoreOperation("attempt-1", PendingStoreOperationType.PURCHASE, market.storeId,
                "old-session-token", digest(signing.subject),
                goStoreFingerprint(storage.session!!.apiKeyFingerprint!!, signing.config.commerceApiBaseUrl),
                "com.example.mobile", 12, "product", "original", type,
                PendingStorePurchaseEvidence(receipt.purchaseToken, receipt.orderIdentifier, receipt.packageName,
                    receipt.developerPayload, receipt.originalJson, receipt.signature, receipt.purchaseTimeMillis),
                createdAtEpochMillis = signing.now)

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private class QueryGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class RecoveryStore : SessionStateStore {
        @Volatile var session: PersistedSession? = null
        val operations = CopyOnWriteArrayList<PendingStoreOperation>()
        val writtenOperations = CopyOnWriteArrayList<PendingStoreOperation>()
        val tombstones = CopyOnWriteArrayList<RejectedStoreEvidenceTombstone>()
        override suspend fun load(): PersistedSession? = session
        override suspend fun save(session: PersistedSession): Boolean { this.session = session; return true }
        override suspend fun clear(): Boolean { session = null; return true }
        override suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> = operations.toList()
        override suspend fun loadRejectedStoreEvidenceTombstones(): List<RejectedStoreEvidenceTombstone> = tombstones.toList()
        override suspend fun upsertRejectedStoreEvidenceTombstone(tombstone: RejectedStoreEvidenceTombstone): Boolean {
            tombstones.addIfAbsent(tombstone)
            return true
        }
        override suspend fun upsertPendingStoreOperation(operation: PendingStoreOperation): Boolean = synchronized(operations) {
            operations.removeAll { it.id == operation.id }
            operations += operation
            writtenOperations += operation
            true
        }
        override suspend fun removePendingStoreOperation(operationId: String): Boolean = synchronized(operations) {
            operations.removeAll { it.id == operationId }
            true
        }
    }

    private class RecordingTransport(private val onRequest: (String) -> Unit) : HttpTransport {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        @Volatile var handler: suspend (HttpRequest) -> TransportResult = { error("No handler") }
        override suspend fun execute(request: HttpRequest): TransportResult {
            requests += request
            onRequest(request.path)
            return handler(request)
        }
        override fun close() = Unit
        fun response(body: JsonObject): TransportResult = TransportResult.Response(HttpResponse(200, body.toString(), null))
        fun failureResponse(status: Int): TransportResult = TransportResult.Response(HttpResponse(status,
            """{"status":false}""", null))
    }
}
