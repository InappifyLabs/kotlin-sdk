package com.inappify.sdk

/** A recorded outcome is correlation evidence, not current entitlement authorization. */
public enum class InappifyPurchaseAttemptStatus { PENDING, SUCCEEDED, FAILED }

/** Latest known result for one logical purchase, scoped to the configured app/customer/market. */
public class InappifyPurchaseAttempt public constructor(
    public val attemptId: String,
    public val productIdentifier: String,
    public val offeringIdentifier: String,
    public val market: InappifyMarket,
    public val status: InappifyPurchaseAttemptStatus,
    public val purchase: InappifyPurchase?,
    public val error: InappifyError?,
    public val updatedAtEpochMillis: Long,
) {
    init {
        require(attemptId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(productIdentifier.isNotBlank() && updatedAtEpochMillis >= 0)
        require(purchase == null || purchase.attemptId == attemptId && purchase.productIdentifier == productIdentifier &&
            purchase.offeringIdentifier == offeringIdentifier && purchase.market == market)
        require(status != InappifyPurchaseAttemptStatus.SUCCEEDED || purchase?.purchaseStatus == InappifyPurchaseStatus.DONE)
        require(status != InappifyPurchaseAttemptStatus.FAILED || error != null)
    }
    public val isFinal: Boolean get() = status != InappifyPurchaseAttemptStatus.PENDING
    public override fun toString(): String = "InappifyPurchaseAttempt(status=$status, result=<redacted>)"
}

/** Additive capability keeps third-party client implementations binary compatible. */
internal interface PurchaseAttemptCapableClient {
    suspend fun purchaseAttempt(attemptId: String): InappifyResult<InappifyPurchaseAttempt?>
    suspend fun purchaseAttempts(): InappifyResult<List<InappifyPurchaseAttempt>>
}

/** Reads local encrypted outcome history only. Null means unknown, never failed or safe to repay. */
public suspend fun InappifyClient.getPurchaseAttempt(attemptId: String): InappifyResult<InappifyPurchaseAttempt?> =
    (this as? PurchaseAttemptCapableClient)?.purchaseAttempt(attemptId) ?: InappifyResult.Failure(
        InappifyError(InappifyErrorCode.UNSUPPORTED_OPERATION, "Purchase attempt tracking is unavailable."), snapshot)

/** Returns this app/customer/market's recorded attempts; no network or store UI is opened. */
public suspend fun InappifyClient.getPurchaseAttempts(): InappifyResult<List<InappifyPurchaseAttempt>> =
    (this as? PurchaseAttemptCapableClient)?.purchaseAttempts() ?: InappifyResult.Failure(
        InappifyError(InappifyErrorCode.UNSUPPORTED_OPERATION, "Purchase attempt tracking is unavailable."), snapshot)
