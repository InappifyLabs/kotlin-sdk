package com.inappify.sdk.internal.storage

/** Store operation kinds that may need to continue after process recreation. */
internal enum class PendingStoreOperationType {
    PURCHASE,
    RESTORE,
    /** An unbound Bazaar subscription receipt; ownership must be resolved by the server. */
    RECOVER_SUBSCRIPTION,
}

/** Durable checkpoints in the server-authoritative store purchase workflow. */
internal enum class PendingStoreOperationPhase {
    REGISTERING,
    VERIFYING,
    DELIVERY_REQUIRED,
    DELIVERY_CONFIRMING,
    CONSUME_REQUIRED,
    CONSUME_RESULT_REPORTING,
}

/** Product categories needed to resume the correct recovery workflow. */
internal enum class PendingStoreProductType {
    CONSUMABLE,
    NON_CONSUMABLE,
    SUBSCRIPTION,

    /**
     * An IN_APP receipt without an explicit product type, from a V1-compatible
     * purchase request or older owned inventory. The verified server response
     * resolves whether it needs the consumable delivery flow.
     */
    LEGACY_IN_APP,
}

/** Result retained when store consumption completed before it was reported. */
internal enum class PendingStoreConsumeResult {
    SUCCEEDED,
    FAILED,
}

/**
 * Durable marker for store evidence that was rejected permanently.
 *
 * Every member is a one-way fingerprint. The marker intentionally retains no
 * purchase token, receipt, customer identifier, or application configuration
 * in plaintext, even though the containing recovery file is encrypted.
 */
internal data class RejectedStoreEvidenceTombstone(
    internal val purchaseTokenFingerprint: String,
    internal val apiKeyFingerprint: String,
    internal val customerIdentifierFingerprint: String,
    internal val appFingerprint: String,
    internal val productIdentifierFingerprint: String,
    internal val productTypeFingerprint: String,
    internal val offeringIdentifierFingerprint: String,
    internal val appVersionFingerprint: String,
    internal val forceVersionFingerprint: String,
    /**
     * Added without changing the encrypted schema version. The legacy marker
     * deliberately cannot match a newly generated, fully scoped tombstone.
     */
    internal val operationFingerprint: String = LEGACY_TOMBSTONE_FINGERPRINT,
    internal val countryFingerprint: String = LEGACY_TOMBSTONE_FINGERPRINT,
    internal val evidenceFingerprint: String = LEGACY_TOMBSTONE_FINGERPRINT,
) {
    init {
        listOf(
            purchaseTokenFingerprint,
            apiKeyFingerprint,
            customerIdentifierFingerprint,
            appFingerprint,
            productIdentifierFingerprint,
            productTypeFingerprint,
            offeringIdentifierFingerprint,
            appVersionFingerprint,
            forceVersionFingerprint,
            operationFingerprint,
            countryFingerprint,
            evidenceFingerprint,
        ).forEach { fingerprint ->
            require(fingerprint.isNotBlank()) {
                "Tombstone fingerprints must not be blank."
            }
            require(fingerprint.length <= MAX_FINGERPRINT_LENGTH) {
                "Tombstone fingerprints exceed the supported length."
            }
        }
    }

    override fun toString(): String =
        "RejectedStoreEvidenceTombstone(fingerprints=<redacted>)"

    private companion object {
        private const val MAX_FINGERPRINT_LENGTH = 128
    }
}

/**
 * Compatibility value used only when decoding tombstones written before the
 * operation, country, and exact-evidence scopes were introduced.
 */
internal const val LEGACY_TOMBSTONE_FINGERPRINT: String =
    "0000000000000000000000000000000000000000000000000000000000000000"

/** Complete encrypted recovery payload. */
internal data class PendingStoreRecoveryState(
    internal val operations: List<PendingStoreOperation> = emptyList(),
    internal val rejectedEvidenceTombstones:
        List<RejectedStoreEvidenceTombstone> = emptyList(),
)

/**
 * Store evidence required to replay server verification after process death.
 *
 * The complete value is encrypted at rest. Its string representation never
 * exposes purchase tokens, signatures, raw store JSON, or developer payloads.
 */
internal data class PendingStorePurchaseEvidence(
    internal val purchaseToken: String,
    internal val orderId: String? = null,
    internal val packageName: String? = null,
    internal val developerPayload: String? = null,
    internal val originalJson: String? = null,
    internal val signature: String? = null,
    internal val purchaseTimeMillis: Long? = null,
) {
    init {
        require(purchaseToken.isNotBlank()) { "Purchase token must not be blank." }
        require(purchaseTimeMillis == null || purchaseTimeMillis >= 0L) {
            "Purchase time must not be negative."
        }
    }

    override fun toString(): String =
        "PendingStorePurchaseEvidence(" +
            "purchaseToken=${purchaseToken.secretValue()}, " +
            "orderId=${orderId.secretValue()}, " +
            "packageName=$packageName, " +
            "developerPayload=${developerPayload.secretValue()}, " +
            "originalJson=${originalJson.secretValue()}, " +
            "signature=${signature.secretValue()}, " +
            "purchaseTimeMillis=$purchaseTimeMillis" +
            ")"
}

/**
 * One encrypted, replayable V2 store operation.
 *
 * Identity fingerprints prevent a queued operation from being replayed under
 * a different customer, API key, or application. [customerToken] remains in
 * the encrypted schema for compatibility; replay authenticates with the
 * current matching session so token refresh and logout/login do not discard an
 * already-paid operation.
 */
internal data class PendingStoreOperation(
    internal val id: String,
    internal val operation: PendingStoreOperationType,
    internal val store: String,
    internal val customerToken: String,
    internal val customerIdentifierFingerprint: String,
    internal val apiKeyFingerprint: String,
    internal val appIdentifier: String,
    internal val appId: Long? = null,
    internal val productIdentifier: String,
    internal val offeringIdentifier: String? = null,
    internal val productType: PendingStoreProductType,
    internal val evidence: PendingStorePurchaseEvidence,
    internal val phase: PendingStoreOperationPhase =
        PendingStoreOperationPhase.REGISTERING,
    internal val verificationRequestId: Long? = null,
    internal val deliveryId: Long? = null,
    internal val attempts: Int = 0,
    internal val nextRetryAtEpochMillis: Long? = null,
    internal val deliveryAcknowledged: Boolean = false,
    internal val consumeResult: PendingStoreConsumeResult? = null,
    internal val consumeErrorCode: String? = null,
    internal val createdAtEpochMillis: Long,
    internal val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(id.isNotBlank()) { "Pending operation id must not be blank." }
        require(store.isNotBlank()) { "Store must not be blank." }
        require(customerToken.isNotBlank()) { "Customer token must not be blank." }
        require(customerIdentifierFingerprint.isNotBlank()) {
            "Customer binding must not be blank."
        }
        require(apiKeyFingerprint.isNotBlank()) {
            "API key binding must not be blank."
        }
        require(appIdentifier.isNotBlank()) { "App identifier must not be blank." }
        require(productIdentifier.isNotBlank()) {
            "Product identifier must not be blank."
        }
        require(appId == null || appId >= 0L) { "App id must not be negative." }
        require(verificationRequestId == null || verificationRequestId > 0L) {
            "Verification request id must be positive."
        }
        require(deliveryId == null || deliveryId > 0L) {
            "Delivery id must be positive."
        }
        require(attempts >= 0) { "Attempt count must not be negative." }
        require(nextRetryAtEpochMillis == null || nextRetryAtEpochMillis >= 0L) {
            "Next retry time must not be negative."
        }
        require(createdAtEpochMillis >= 0L) { "Creation time must not be negative." }
        require(updatedAtEpochMillis >= 0L) { "Update time must not be negative." }
        if (operation == PendingStoreOperationType.RECOVER_SUBSCRIPTION) {
            require(store == "bazar" && productType == PendingStoreProductType.SUBSCRIPTION)
            require(offeringIdentifier == null && evidence.developerPayload.isNullOrBlank())
            require(evidence.packageName == appIdentifier && appId != null && appId > 0L)
            require(!evidence.originalJson.isNullOrBlank() && !evidence.signature.isNullOrBlank())
            require(evidence.purchaseTimeMillis != null && evidence.purchaseTimeMillis > 0L)
            require(phase in setOf(PendingStoreOperationPhase.REGISTERING, PendingStoreOperationPhase.VERIFYING))
            require(deliveryId == null && !deliveryAcknowledged && consumeResult == null && consumeErrorCode == null)
        }
    }

    override fun toString(): String =
        "PendingStoreOperation(" +
            "id=$id, " +
            "operation=$operation, " +
            "store=$store, " +
            "customerToken=${customerToken.secretValue()}, " +
            "customerIdentifierFingerprint=" +
            "${customerIdentifierFingerprint.secretValue()}, " +
            "apiKeyFingerprint=${apiKeyFingerprint.secretValue()}, " +
            "appIdentifier=$appIdentifier, " +
            "appId=$appId, " +
            "productIdentifier=$productIdentifier, " +
            "offeringIdentifier=$offeringIdentifier, " +
            "productType=$productType, " +
            "evidence=$evidence, " +
            "phase=$phase, " +
            "verificationRequestId=$verificationRequestId, " +
            "deliveryId=$deliveryId, " +
            "attempts=$attempts, " +
            "nextRetryAtEpochMillis=$nextRetryAtEpochMillis, " +
            "deliveryAcknowledged=$deliveryAcknowledged, " +
            "consumeResult=$consumeResult, " +
            "consumeErrorCode=$consumeErrorCode, " +
            "createdAtEpochMillis=$createdAtEpochMillis, " +
            "updatedAtEpochMillis=$updatedAtEpochMillis" +
            ")"
}

private fun String?.secretValue(): String =
    if (this == null) "null" else "<redacted>"
