package com.inappify.sdk.internal.v2

import com.google.gson.JsonObject
import com.inappify.sdk.internal.network.*
import kotlinx.coroutines.CancellationException

/** Adapts the existing durable Bazaar coordinator to the session-scoped Laravel V2 API. */
internal class GoV2StoreService(
    private val request: suspend (String, JsonObject) -> GoApiResponse,
) : StoreV2Backend {
    constructor(api: GoApi, sessionToken: suspend () -> String) : this({ endpoint, body ->
        api.requestWithMetadata(endpoint, sessionToken(), body, retry = false)
    })
    override suspend fun submitStorePurchase(request: StorePurchaseApiRequest): StoreServiceResult =
        call("store/purchases", JsonObject().apply {
            addProperty("productIdentifier", request.productIdentifier)
            addProperty("offeringIdentifier", request.offeringIdentifier)
            addProperty("operation", request.operation.wireValue)
            add("purchase", JsonObject().apply {
                addProperty("token", request.purchase.token)
                request.purchase.purchaseTime?.let { addProperty("purchaseTime", it) }
                request.purchase.orderId?.let { addProperty("orderId", it) }
                request.purchase.packageName?.let { addProperty("packageName", it) }
                request.purchase.developerPayload?.let { addProperty("developerPayload", it) }
                request.purchase.originalJson?.let { addProperty("originalJson", it) }
                request.purchase.signature?.let { addProperty("signature", it) }
            })
        }, nestedPurchase = true)

    override suspend fun recoverStoreSubscription(request: StoreSubscriptionRecoveryApiRequest): StoreServiceResult =
        call("store/subscriptions/recover", JsonObject().apply {
            addProperty("productIdentifier", request.productIdentifier)
            add("purchase", JsonObject().apply {
                addProperty("token", request.purchase.token)
                request.purchase.purchaseTime?.let { addProperty("purchaseTime", it) }
                request.purchase.orderId?.let { addProperty("orderId", it) }
                request.purchase.packageName?.let { addProperty("packageName", it) }
                request.purchase.developerPayload?.let { addProperty("developerPayload", it) }
                request.purchase.originalJson?.let { addProperty("originalJson", it) }
                request.purchase.signature?.let { addProperty("signature", it) }
            })
        }, nestedPurchase = true)

    override suspend fun getStoreVerificationStatus(request: StoreVerificationStatusApiRequest): StoreServiceResult =
        call("store/verifications/${request.verificationRequestId}/status", JsonObject())

    override suspend fun markStoreDeliveryDelivered(request: StoreDeliveryApiRequest): StoreServiceResult =
        call("store/deliveries/${request.deliveryId}/delivered", JsonObject())

    override suspend fun reportStoreConsumeResult(request: StoreConsumeResultApiRequest): StoreServiceResult =
        call("store/deliveries/${request.deliveryId}/consume-result", JsonObject().apply {
            addProperty("result", request.result.wireValue)
            request.errorCode?.let { addProperty("errorCode", it) }
        })

    private suspend fun call(endpoint: String, body: JsonObject, nestedPurchase: Boolean = false): StoreServiceResult {
        return try {
            // Submission is keyed by store receipt on the server. The coordinator owns replay/backoff.
            val response = request(endpoint, body)
            val data = response.body.getAsJsonObject("data") ?: return malformed()
            val state = (if (nestedPurchase) data.getAsJsonObject("purchase")
                else data.get("purchase")?.takeUnless { it.isJsonNull }?.asJsonObject ?: data) ?: return malformed()
            val status = state.text("status")?.let { value ->
                StorePurchaseStatus.entries.firstOrNull { it.wireValue == value }
            } ?: return malformed()
            StoreServiceResult.Response(200, StoreBackendResponse(true, StorePurchaseState(
                status = status,
                paymentId = state.long("paymentId"),
                eventId = state.long("eventId"),
                deliveryId = state.long("deliveryId"),
                verificationRequestId = state.long("verificationRequestId"),
                retryAfter = listOfNotNull(state.long("retryAfter"), response.retryAfterSeconds).maxOrNull(),
                errorCode = state.text("errorCode"),
                message = state.text("message"),
                alreadyProcessed = state.bool("alreadyProcessed"),
                source = state.text("source"),
                productIdentifier = state.text("productIdentifier"),
                alreadyDelivered = state.bool("alreadyDelivered"),
            ), false, null, null, null), null, response.retryAfterSeconds)
        } catch (failure: V2Failure) {
            val details = failure.sdkError.details
            val status = details["httpStatus"] as? Int
            if (status == null) StoreServiceResult.Failure(when (details["category"]) {
                "TIMEOUT" -> ServiceFailureKind.TIMEOUT
                "NETWORK" -> ServiceFailureKind.NETWORK
                "DECODING" -> ServiceFailureKind.MALFORMED_RESPONSE
                else -> ServiceFailureKind.UNKNOWN
            }) else StoreServiceResult.Response(status, StoreBackendResponse(false, null, false, null,
                details["serverCode"] as? String, null), null, details["retryAfterSeconds"] as? Long)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            malformed()
        }
    }

    private fun malformed(): StoreServiceResult = StoreServiceResult.Failure(ServiceFailureKind.MALFORMED_RESPONSE)
    // Do not coerce an invalid ID/boolean to a different delivery or silently erase it.
    // Missing and null optional fields are allowed; malformed present values fail closed.
    private fun JsonObject.text(name: String): String? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun JsonObject.long(name: String): Long? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        val raw = value.asString
        require(raw.matches(Regex("-?(0|[1-9][0-9]*)")))
        val number = raw.toLongOrNull() ?: throw IllegalArgumentException("Invalid store number")
        require(if (name == "retryAfter") number >= 0 else number > 0)
        return number
    }
    private fun JsonObject.bool(name: String): Boolean? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
}
