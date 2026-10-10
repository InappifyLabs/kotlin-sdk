package com.inappify.sdk.internal.network

/** The server operation represented by one V2 store-purchase submission. */
internal enum class StorePurchaseOperation(internal val wireValue: String) {
    PURCHASE("purchase"),
    RESTORE("restore"),
}

/**
 * Store evidence forwarded to Inappify for server-to-server verification.
 *
 * None of these values may be treated as entitlement authority on-device.
 */
internal class StorePurchaseEvidence(
    internal val token: String,
    internal val purchaseTime: Long?,
    internal val orderId: String?,
    internal val packageName: String?,
    internal val developerPayload: String?,
    internal val originalJson: String?,
    internal val signature: String?,
) {
    override fun toString(): String =
        "StorePurchaseEvidence(" +
            "token=<redacted>, " +
            "purchaseTime=<redacted>, " +
            "orderId=<redacted>, " +
            "packageName=<redacted>, " +
            "developerPayload=<redacted>, " +
            "originalJson=<redacted>, " +
            "signature=<redacted>" +
            ")"
}

/** Wire request for registering or restoring one store purchase through V2. */
internal class StorePurchaseApiRequest(
    internal val productIdentifier: String,
    internal val offeringIdentifier: String,
    internal val operation: StorePurchaseOperation,
    internal val purchase: StorePurchaseEvidence,
) {
    override fun toString(): String =
        "StorePurchaseApiRequest(" +
            "productIdentifier=<redacted>, " +
            "offeringIdentifier=<redacted>, " +
            "operation=$operation, " +
            "purchase=$purchase" +
            ")"
}

/** No client-selected owner, offering, package, or entitlement is accepted for recovery. */
internal class StoreSubscriptionRecoveryApiRequest(
    internal val productIdentifier: String,
    internal val purchase: StorePurchaseEvidence,
) {
    override fun toString(): String =
        "StoreSubscriptionRecoveryApiRequest(productIdentifier=<redacted>, purchase=$purchase)"
}

/** Wire request for polling one durable store-verification operation. */
internal class StoreVerificationStatusApiRequest(
    internal val verificationRequestId: Long,
) {
    override fun toString(): String =
        "StoreVerificationStatusApiRequest(verificationRequestId=<redacted>)"
}

/** Wire request acknowledging that the host delivered one consumable item. */
internal class StoreDeliveryApiRequest(
    internal val deliveryId: Long,
) {
    override fun toString(): String =
        "StoreDeliveryApiRequest(deliveryId=<redacted>)"
}

/** Result of consuming a delivered purchase in the official store SDK. */
internal enum class StoreConsumeResult(internal val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
}

/** Wire request reporting the store-consumption result for one delivery. */
internal class StoreConsumeResultApiRequest(
    internal val deliveryId: Long,
    internal val result: StoreConsumeResult,
    internal val errorCode: String?,
) {
    override fun toString(): String =
        "StoreConsumeResultApiRequest(" +
            "deliveryId=<redacted>, " +
            "result=$result, " +
            "errorCode=<redacted>" +
            ")"
}

/** Server-authoritative state of one V2 store operation. */
internal enum class StorePurchaseStatus(internal val wireValue: String) {
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    RESTORED("RESTORED"),
    ALREADY_PROCESSED("ALREADY_PROCESSED"),
    DELIVERY_REQUIRED("DELIVERY_REQUIRED"),
    CONSUME_REQUIRED("CONSUME_REQUIRED"),
    REJECTED("REJECTED"),
}

/** Typed projection shared by initial, polling, and delivery V2 responses. */
internal class StorePurchaseState(
    internal val status: StorePurchaseStatus,
    internal val paymentId: Long?,
    internal val eventId: Long?,
    internal val deliveryId: Long?,
    internal val verificationRequestId: Long?,
    internal val retryAfter: Long?,
    internal val errorCode: String?,
    internal val message: String?,
    internal val alreadyProcessed: Boolean?,
    internal val source: String? = null,
    internal val productIdentifier: String? = null,
    internal val alreadyDelivered: Boolean? = null,
) {
    override fun toString(): String =
        "StorePurchaseState(" +
            "status=$status, " +
            "paymentId=<redacted>, " +
            "eventId=<redacted>, " +
            "deliveryId=<redacted>, " +
            "verificationRequestId=<redacted>, " +
            "retryAfter=$retryAfter, " +
            "errorCode=<redacted>, " +
            "message=<redacted>, " +
            "alreadyProcessed=$alreadyProcessed" +
            ")"
}

/** V2 envelope kept separate from the legacy purchase response contract. */
internal class StoreBackendResponse(
    internal val status: Boolean?,
    internal val state: StorePurchaseState?,
    internal val hasForceUpdate: Boolean?,
    internal val forceVersion: Long?,
    internal val errorCode: String?,
    internal val message: String?,
) {
    override fun toString(): String =
        "StoreBackendResponse(" +
            "status=$status, " +
            "state=$state, " +
            "hasForceUpdate=$hasForceUpdate, " +
            "forceVersion=$forceVersion, " +
            "errorCode=<redacted>, " +
            "message=<redacted>" +
            ")"
}

internal enum class ServiceFailureKind {
    NETWORK,
    TIMEOUT,
    CANCELLED,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/** Result boundary for the V2 store API; uses session Bearer authentication. */
internal sealed interface StoreServiceResult {
    class Response(
        internal val statusCode: Int,
        internal val payload: StoreBackendResponse,
        internal val requestId: String?,
        internal val retryAfterSeconds: Long? = null,
    ) : StoreServiceResult

    class Failure(
        internal val kind: ServiceFailureKind,
    ) : StoreServiceResult
}

internal interface StoreV2Backend {
    suspend fun submitStorePurchase(request: StorePurchaseApiRequest): StoreServiceResult
    suspend fun recoverStoreSubscription(request: StoreSubscriptionRecoveryApiRequest): StoreServiceResult =
        StoreServiceResult.Failure(ServiceFailureKind.UNKNOWN)
    suspend fun getStoreVerificationStatus(request: StoreVerificationStatusApiRequest): StoreServiceResult
    suspend fun markStoreDeliveryDelivered(request: StoreDeliveryApiRequest): StoreServiceResult
    suspend fun reportStoreConsumeResult(request: StoreConsumeResultApiRequest): StoreServiceResult
}
