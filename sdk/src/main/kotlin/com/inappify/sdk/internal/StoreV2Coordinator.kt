package com.inappify.sdk.internal

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.inappify.sdk.InappifyError
import com.inappify.sdk.InappifyErrorCode
import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import com.inappify.sdk.internal.billing.StoreConsumeResult
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.storeId
import com.inappify.sdk.internal.network.StoreV2Backend
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.StoreConsumeResult as NetworkConsumeResult
import com.inappify.sdk.internal.network.StoreConsumeResultApiRequest
import com.inappify.sdk.internal.network.StoreDeliveryApiRequest
import com.inappify.sdk.internal.network.StorePurchaseApiRequest
import com.inappify.sdk.internal.network.StorePurchaseEvidence
import com.inappify.sdk.internal.network.StorePurchaseOperation
import com.inappify.sdk.internal.network.StorePurchaseState
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.network.StoreServiceResult
import com.inappify.sdk.internal.network.StoreSubscriptionRecoveryApiRequest
import com.inappify.sdk.internal.network.StoreVerificationStatusApiRequest
import com.inappify.sdk.internal.storage.PendingStoreConsumeResult
import com.inappify.sdk.internal.storage.PendingStoreOperation
import com.inappify.sdk.internal.storage.PendingStoreOperationPhase
import com.inappify.sdk.internal.storage.PendingStoreOperationType
import com.inappify.sdk.internal.storage.PendingStoreProductType
import com.inappify.sdk.internal.storage.SessionStateStore
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Non-secret runtime values needed to advance a durable store operation. */
internal class StoreV2Context(
    internal val apiKey: String,
    internal val customerToken: String,
    internal val apiKeyFingerprint: String,
    internal val customerIdentifierFingerprint: String,
    internal val appIdentifier: String,
    internal val appId: Long?,
    internal val country: String,
    internal val appVersion: String,
    internal val forceVersion: Long?,
    internal val marketKey: String?,
    internal val market: InappifyMarket = InappifyMarket.BAZAAR,
    internal val subscriptionRecoveryEnabled: Boolean = true,
) {
    internal val store: String get() = market.storeId
}

/** Result of advancing one server-authoritative store operation. */
internal sealed interface StoreV2Outcome {
    val operation: PendingStoreOperation
    val forceVersion: Long?

    class Terminal(
        override val operation: PendingStoreOperation,
        internal val state: StorePurchaseState,
        override val forceVersion: Long?,
    ) : StoreV2Outcome

    class PendingDelivery(
        override val operation: PendingStoreOperation,
        internal val state: StorePurchaseState,
        override val forceVersion: Long?,
    ) : StoreV2Outcome

    class Deferred(
        override val operation: PendingStoreOperation,
        internal val state: StorePurchaseState?,
        override val forceVersion: Long?,
        internal val error: InappifyError?,
    ) : StoreV2Outcome

    class Rejected(
        override val operation: PendingStoreOperation,
        internal val state: StorePurchaseState?,
        override val forceVersion: Long?,
        internal val error: InappifyError,
        /** Suppresses only the exact unchanged evidence/configuration tuple. */
        internal val requiresRejectionTombstone: Boolean = false,
    ) : StoreV2Outcome

    class Failure(
        override val operation: PendingStoreOperation,
        override val forceVersion: Long?,
        internal val error: InappifyError,
        internal val retainedForRetry: Boolean,
        internal val requiresRejectionTombstone: Boolean = false,
    ) : StoreV2Outcome
}

/**
 * Durable marketplace V2 state machine.
 *
 * Store callbacks only provide evidence. This coordinator is the sole place
 * that can turn that evidence into a terminal server-authoritative result.
 */
internal class StoreV2Coordinator(
    private val service: StoreV2Backend,
    private val stateStore: SessionStateStore,
    private val billingAdapterFactory: StoreBillingAdapterFactory,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterMillis: (Long) -> Long = { upperBound ->
        if (upperBound <= 0L) 0L else Random.nextLong(upperBound + 1L)
    },
    private val consumeTimeoutMillis: Long = DEFAULT_CONSUME_TIMEOUT_MILLIS,
    private val registerActiveAdapter: (StoreBillingAdapter) -> Boolean = { true },
    private val unregisterActiveAdapter: (StoreBillingAdapter) -> Unit = {},
) {

    init {
        require(consumeTimeoutMillis > 0L) {
            "consumeTimeoutMillis must be greater than zero."
        }
    }

    /** Persists a newly paid store operation before any server request. */
    internal suspend fun submit(
        operation: PendingStoreOperation,
        context: StoreV2Context,
        maxPolls: Int = MAX_FOREGROUND_POLLS,
    ): StoreV2Outcome {
        operation.bindingError(context)?.let {
            return StoreV2Outcome.Failure(operation, null, it, retainedForRetry = true)
        }
        if (!stateStore.upsertPendingStoreOperation(operation)) {
            return storageFailure(operation)
        }
        return advance(operation, context, maxPolls)
    }

    /** Resumes an operation that was already encrypted on disk. */
    internal suspend fun resume(
        operation: PendingStoreOperation,
        context: StoreV2Context,
        maxPolls: Int = MAX_BACKGROUND_POLLS,
    ): StoreV2Outcome {
        val bindingError = operation.bindingError(context)
        if (bindingError != null) {
            return StoreV2Outcome.Failure(
                operation = operation,
                forceVersion = null,
                error = bindingError,
                retainedForRetry = true,
            )
        }
        return advance(operation, context, maxPolls)
    }

    /** Marks host delivery durably before acknowledging it to the server. */
    internal suspend fun confirmDelivery(
        operation: PendingStoreOperation,
        context: StoreV2Context,
        maxPolls: Int = MAX_FOREGROUND_POLLS,
    ): StoreV2Outcome {
        val bindingError = operation.bindingError(context)
        if (bindingError != null) {
            return StoreV2Outcome.Failure(
                operation = operation,
                forceVersion = null,
                error = bindingError,
                retainedForRetry = true,
            )
        }
        if (operation.deliveryId == null) {
            return permanentFailure(
                operation = operation,
                code = InappifyErrorCode.INVALID_CONFIGURATION,
                message = "The pending consumable has no delivery identifier.",
                remove = false,
            )
        }
        val confirming = operation.copy(
            phase = PendingStoreOperationPhase.DELIVERY_CONFIRMING,
            deliveryAcknowledged = true,
            updatedAtEpochMillis = currentTimeMillis(),
        )
        if (!stateStore.upsertPendingStoreOperation(confirming)) {
            return storageFailure(confirming)
        }
        return advance(confirming, context, maxPolls)
    }

    private suspend fun advance(
        initial: PendingStoreOperation,
        context: StoreV2Context,
        maxPolls: Int,
    ): StoreV2Outcome {
        require(maxPolls >= 0) { "maxPolls must not be negative." }
        if (initial.operation == PendingStoreOperationType.RECOVER_SUBSCRIPTION &&
            !context.subscriptionRecoveryEnabled) {
            return StoreV2Outcome.Deferred(initial, null, null, InappifyError(
                InappifyErrorCode.INVALID_CONFIGURATION,
                "Subscription recovery is disabled in this client configuration.",
                details = mapOf("operation" to "subscriptionRecovery", "outcomeMayHaveCommitted" to true),
            ))
        }
        var operation = initial
        var forceVersion: Long? = null
        var polls = 0

        while (true) {
            when (operation.phase) {
                PendingStoreOperationPhase.REGISTERING -> {
                    if (!waitUntilDue(operation)) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    val recovery = operation.operation == PendingStoreOperationType.RECOVER_SUBSCRIPTION
                    val offeringIdentifier = operation.offeringIdentifier
                    if (!recovery && offeringIdentifier.isNullOrBlank()) {
                        return permanentFailure(
                            operation = operation,
                            code = InappifyErrorCode.INVALID_CONFIGURATION,
                            message = "The pending store operation has no offering identifier.",
                            remove = true,
                        )
                    }
                    val evidence = operation.evidence.let { saved ->
                        StorePurchaseEvidence(
                            token = saved.purchaseToken,
                            purchaseTime = saved.purchaseTimeMillis,
                            orderId = saved.orderId,
                            packageName = saved.packageName,
                            developerPayload = saved.developerPayload,
                            originalJson = saved.originalJson,
                            signature = saved.signature,
                        )
                    }
                    val response = if (recovery) {
                        service.recoverStoreSubscription(
                            StoreSubscriptionRecoveryApiRequest(
                                productIdentifier = operation.productIdentifier,
                                purchase = evidence,
                            ),
                        )
                    } else {
                        service.submitStorePurchase(
                            StorePurchaseApiRequest(
                                productIdentifier = operation.productIdentifier,
                                offeringIdentifier = requireNotNull(offeringIdentifier),
                                operation = when (operation.operation) {
                                    PendingStoreOperationType.PURCHASE -> StorePurchaseOperation.PURCHASE
                                    PendingStoreOperationType.RESTORE -> StorePurchaseOperation.RESTORE
                                    PendingStoreOperationType.RECOVER_SUBSCRIPTION ->
                                        error("Recovery cannot use the purchase endpoint")
                                },
                                purchase = evidence,
                            ),
                        )
                    }
                    when (
                        val evaluated = evaluate(
                            response,
                            operation,
                            context,
                        )
                    ) {
                        is ApiEvaluation.State -> {
                            forceVersion = monotonic(forceVersion, evaluated.forceVersion)
                            when (
                                val applied = applyState(
                                    operation = operation,
                                    state = evaluated.state,
                                    forceVersion = forceVersion,
                                    context = context,
                                )
                            ) {
                                is AppliedState.Continue -> operation = applied.operation
                                is AppliedState.Outcome -> return applied.outcome
                            }
                        }

                        is ApiEvaluation.Retry ->
                            return defer(operation, forceVersion, evaluated.error, null)
                        is ApiEvaluation.Reject -> {
                            return StoreV2Outcome.Rejected(
                                operation = operation,
                                state = null,
                                forceVersion = forceVersion,
                                error = evaluated.error,
                                requiresRejectionTombstone =
                                    evaluated.requiresRejectionTombstone,
                            )
                        }
                    }
                }

                PendingStoreOperationPhase.VERIFYING -> {
                    if (polls >= maxPolls) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    val verificationRequestId = operation.verificationRequestId
                        ?: return permanentFailure(
                            operation = operation,
                            code = InappifyErrorCode.MALFORMED_RESPONSE,
                            message = "The pending verification has no request identifier.",
                            remove = false,
                        )
                    if (!waitUntilDue(operation)) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    polls += 1
                    when (
                        val evaluated = evaluate(
                            service.getStoreVerificationStatus(
                                StoreVerificationStatusApiRequest(
                                    verificationRequestId = verificationRequestId,
                                ),
                            ),
                            operation,
                            context,
                        )
                    ) {
                        is ApiEvaluation.State -> {
                            forceVersion = monotonic(forceVersion, evaluated.forceVersion)
                            when (
                                val applied = applyState(
                                    operation = operation,
                                    state = evaluated.state,
                                    forceVersion = forceVersion,
                                    context = context,
                                )
                            ) {
                                is AppliedState.Continue -> operation = applied.operation
                                is AppliedState.Outcome -> return applied.outcome
                            }
                        }

                        is ApiEvaluation.Retry ->
                            return defer(operation, forceVersion, evaluated.error, null)
                        is ApiEvaluation.Reject -> {
                            return StoreV2Outcome.Rejected(
                                operation = operation,
                                state = null,
                                forceVersion = forceVersion,
                                error = evaluated.error,
                                requiresRejectionTombstone =
                                    evaluated.requiresRejectionTombstone,
                            )
                        }
                    }
                }

                PendingStoreOperationPhase.DELIVERY_REQUIRED ->
                    return StoreV2Outcome.PendingDelivery(
                        operation = operation,
                        state = operation.toSyntheticState(
                            StorePurchaseStatus.DELIVERY_REQUIRED,
                        ),
                        forceVersion = forceVersion,
                    )

                PendingStoreOperationPhase.DELIVERY_CONFIRMING -> {
                    if (!waitUntilDue(operation)) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    val deliveryId = requireNotNull(operation.deliveryId)
                    when (
                        val evaluated = evaluate(
                            service.markStoreDeliveryDelivered(
                                StoreDeliveryApiRequest(
                                    deliveryId = deliveryId,
                                ),
                            ),
                            operation,
                            context,
                        )
                    ) {
                        is ApiEvaluation.State -> {
                            forceVersion = monotonic(forceVersion, evaluated.forceVersion)
                            when (
                                val applied = applyState(
                                    operation = operation,
                                    state = evaluated.state,
                                    forceVersion = forceVersion,
                                    context = context,
                                )
                            ) {
                                is AppliedState.Continue -> operation = applied.operation
                                is AppliedState.Outcome -> return applied.outcome
                            }
                        }

                        is ApiEvaluation.Retry ->
                            return defer(operation, forceVersion, evaluated.error, null)
                        is ApiEvaluation.Reject -> {
                            return StoreV2Outcome.Rejected(
                                operation = operation,
                                state = null,
                                forceVersion = forceVersion,
                                error = evaluated.error,
                                requiresRejectionTombstone =
                                    evaluated.requiresRejectionTombstone,
                            )
                        }
                    }
                }

                PendingStoreOperationPhase.CONSUME_REQUIRED -> {
                    if (!waitUntilDue(operation)) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    val consumeResult = consume(operation, context)
                    operation = when (consumeResult) {
                        StoreConsumeResult.Success -> operation.copy(
                            phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                            consumeResult = PendingStoreConsumeResult.SUCCEEDED,
                            consumeErrorCode = null,
                            updatedAtEpochMillis = currentTimeMillis(),
                        )

                        is StoreConsumeResult.RetryableFailure -> operation.copy(
                            phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                            consumeResult = PendingStoreConsumeResult.FAILED,
                            consumeErrorCode = STORE_TEMPORARILY_UNAVAILABLE,
                            updatedAtEpochMillis = currentTimeMillis(),
                        )

                        is StoreConsumeResult.PermanentFailure -> operation.copy(
                            phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
                            consumeResult = PendingStoreConsumeResult.FAILED,
                            consumeErrorCode = STORE_CONSUME_FAILED,
                            updatedAtEpochMillis = currentTimeMillis(),
                        )
                    }
                    if (!stateStore.upsertPendingStoreOperation(operation)) {
                        return storageFailure(operation)
                    }
                }

                PendingStoreOperationPhase.CONSUME_RESULT_REPORTING -> {
                    val deliveryId = operation.deliveryId
                        ?: return permanentFailure(
                            operation = operation,
                            code = InappifyErrorCode.MALFORMED_RESPONSE,
                            message = "The consumed purchase has no delivery identifier.",
                            remove = false,
                        )
                    val consumeResult = operation.consumeResult
                        ?: return permanentFailure(
                            operation = operation,
                            code = InappifyErrorCode.MALFORMED_RESPONSE,
                            message = "The store consume result is unavailable.",
                            remove = false,
                        )
                    if (!waitUntilDue(operation)) {
                        return StoreV2Outcome.Deferred(
                            operation = operation,
                            state = null,
                            forceVersion = forceVersion,
                            error = null,
                        )
                    }
                    when (
                        val evaluated = evaluate(
                            service.reportStoreConsumeResult(
                                StoreConsumeResultApiRequest(
                                    deliveryId = deliveryId,
                                    result = when (consumeResult) {
                                        PendingStoreConsumeResult.SUCCEEDED ->
                                            NetworkConsumeResult.SUCCEEDED
                                        PendingStoreConsumeResult.FAILED ->
                                            NetworkConsumeResult.FAILED
                                    },
                                    errorCode = operation.consumeErrorCode,
                                ),
                            ),
                            operation,
                            context,
                        )
                    ) {
                        is ApiEvaluation.State -> {
                            forceVersion = monotonic(forceVersion, evaluated.forceVersion)
                            if (
                                consumeResult == PendingStoreConsumeResult.FAILED &&
                                evaluated.state.status == StorePurchaseStatus.CONSUME_REQUIRED
                            ) {
                                if (operation.consumeErrorCode == STORE_CONSUME_FAILED) {
                                    return permanentFailure(
                                        operation = operation,
                                        code = InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                                        message = "The marketplace permanently rejected purchase consumption.",
                                        remove = true,
                                    )
                                }
                                val retrying = operation.copy(
                                    phase = PendingStoreOperationPhase.CONSUME_REQUIRED,
                                    consumeResult = null,
                                    consumeErrorCode = null,
                                    attempts = operation.attempts + 1,
                                    nextRetryAtEpochMillis = retryAt(
                                        attempt = operation.attempts + 1,
                                        retryAfterSeconds = evaluated.state.retryAfter,
                                    ),
                                    updatedAtEpochMillis = currentTimeMillis(),
                                )
                                if (!stateStore.upsertPendingStoreOperation(retrying)) {
                                    return storageFailure(retrying)
                                }
                                return StoreV2Outcome.Deferred(
                                    operation = retrying,
                                    state = evaluated.state,
                                    forceVersion = forceVersion,
                                    error = InappifyError(
                                        code = InappifyErrorCode.STORE_UNAVAILABLE,
                                        message = "The marketplace could not consume the purchase yet.",
                                        isRetryable = true,
                                        details = mapOf(
                                            "operation" to OPERATION_CONSUME,
                                            "store" to operation.store,
                                        ),
                                    ),
                                )
                            }
                            when (
                                val applied = applyState(
                                    operation = operation,
                                    state = evaluated.state,
                                    forceVersion = forceVersion,
                                    context = context,
                                )
                            ) {
                                is AppliedState.Continue -> operation = applied.operation
                                is AppliedState.Outcome -> return applied.outcome
                            }
                        }

                        is ApiEvaluation.Retry ->
                            return defer(operation, forceVersion, evaluated.error, null)
                        is ApiEvaluation.Reject -> {
                            return StoreV2Outcome.Rejected(
                                operation = operation,
                                state = null,
                                forceVersion = forceVersion,
                                error = evaluated.error,
                                requiresRejectionTombstone =
                                    evaluated.requiresRejectionTombstone,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun applyState(
        operation: PendingStoreOperation,
        state: StorePurchaseState,
        forceVersion: Long?,
        context: StoreV2Context,
    ): AppliedState {
        return when (state.status) {
        StorePurchaseStatus.COMPLETED,
        StorePurchaseStatus.RESTORED,
        StorePurchaseStatus.ALREADY_PROCESSED,
        -> {
            val invalidConsumableTerminal =
                operation.productType == PendingStoreProductType.CONSUMABLE &&
                    when (state.status) {
                        StorePurchaseStatus.ALREADY_PROCESSED -> false
                        StorePurchaseStatus.RESTORED -> true
                        StorePurchaseStatus.COMPLETED ->
                            !operation.deliveryAcknowledged ||
                                operation.phase !=
                                PendingStoreOperationPhase.CONSUME_RESULT_REPORTING ||
                                operation.consumeResult != PendingStoreConsumeResult.SUCCEEDED
                        else -> false
                    }
            if (invalidConsumableTerminal) {
                return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server completed a consumable before its delivery and consumption checkpoints.",
                        remove = false,
                    ),
                )
            }
            if (!stateStore.removePendingStoreOperation(operation.id)) {
                AppliedState.Outcome(
                    StoreV2Outcome.Failure(
                        operation = operation,
                        forceVersion = forceVersion,
                        error = InappifyError(
                            code = InappifyErrorCode.UNKNOWN,
                            message = "The completed store purchase checkpoint could not be finalized securely.",
                            isRetryable = true,
                            details = operation.safeDetails(),
                        ),
                        retainedForRetry = true,
                    ),
                )
            } else {
                AppliedState.Outcome(
                    StoreV2Outcome.Terminal(
                        operation = operation,
                        state = state,
                        forceVersion = forceVersion,
                    ),
                )
            }
        }

        StorePurchaseStatus.REJECTED -> {
            AppliedState.Outcome(
                StoreV2Outcome.Rejected(
                    operation = operation,
                    state = state,
                    forceVersion = forceVersion,
                    error = rejectionError(state, operation, context),
                ),
            )
        }

        StorePurchaseStatus.PROCESSING -> {
            val verificationId = state.verificationRequestId
                ?: operation.verificationRequestId
                ?: return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server omitted the verification request identifier.",
                        remove = false,
                    ),
                )
            val next = operation.copy(
                phase = PendingStoreOperationPhase.VERIFYING,
                verificationRequestId = verificationId,
                deliveryId = state.deliveryId ?: operation.deliveryId,
                attempts = operation.attempts + 1,
                nextRetryAtEpochMillis = retryAt(
                    attempt = operation.attempts + 1,
                    retryAfterSeconds = state.retryAfter,
                ),
                updatedAtEpochMillis = currentTimeMillis(),
            )
            if (!stateStore.upsertPendingStoreOperation(next)) {
                AppliedState.Outcome(storageFailure(next))
            } else {
                AppliedState.Continue(next)
            }
        }

        StorePurchaseStatus.DELIVERY_REQUIRED -> {
            if (
                operation.productType != PendingStoreProductType.CONSUMABLE &&
                operation.productType != PendingStoreProductType.LEGACY_IN_APP
            ) {
                return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server requested delivery for a non-consumable product.",
                        remove = false,
                    ),
                )
            }
            val deliveryId = state.deliveryId ?: operation.deliveryId
                ?: return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server omitted the consumable delivery identifier.",
                        remove = false,
                    ),
                )
            val next = operation.copy(
                // A delivery state is the server-authoritative proof that an
                // untyped legacy IN_APP receipt is consumable.
                productType = PendingStoreProductType.CONSUMABLE,
                phase = if (operation.deliveryAcknowledged) {
                    PendingStoreOperationPhase.DELIVERY_CONFIRMING
                } else {
                    PendingStoreOperationPhase.DELIVERY_REQUIRED
                },
                deliveryId = deliveryId,
                verificationRequestId = state.verificationRequestId
                    ?: operation.verificationRequestId,
                attempts = if (operation.deliveryAcknowledged) {
                    operation.attempts + 1
                } else {
                    operation.attempts
                },
                nextRetryAtEpochMillis = if (operation.deliveryAcknowledged) {
                    retryAt(operation.attempts + 1, state.retryAfter)
                } else {
                    null
                },
                updatedAtEpochMillis = currentTimeMillis(),
            )
            if (!stateStore.upsertPendingStoreOperation(next)) {
                AppliedState.Outcome(storageFailure(next))
            } else if (next.phase == PendingStoreOperationPhase.DELIVERY_REQUIRED) {
                AppliedState.Outcome(
                    StoreV2Outcome.PendingDelivery(
                        operation = next,
                        state = state,
                        forceVersion = forceVersion,
                    ),
                )
            } else if (operation.deliveryAcknowledged) {
                AppliedState.Outcome(
                    StoreV2Outcome.Deferred(
                        operation = next,
                        state = state,
                        forceVersion = forceVersion,
                        error = null,
                    ),
                )
            } else {
                AppliedState.Continue(next)
            }
        }

        StorePurchaseStatus.CONSUME_REQUIRED -> {
            if (operation.productType != PendingStoreProductType.CONSUMABLE) {
                return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server requested consumption for a non-consumable product.",
                        remove = false,
                    ),
                )
            }
            if (!operation.deliveryAcknowledged) {
                return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server requested consumption before host delivery was confirmed.",
                        remove = false,
                    ),
                )
            }
            if (
                operation.consumeResult == PendingStoreConsumeResult.FAILED &&
                operation.consumeErrorCode == STORE_CONSUME_FAILED
            ) {
                return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
                        message = "The marketplace permanently rejected purchase consumption.",
                        remove = true,
                    ),
                )
            }
            val deliveryId = state.deliveryId ?: operation.deliveryId
                ?: return AppliedState.Outcome(
                    permanentFailure(
                        operation = operation,
                        code = InappifyErrorCode.MALFORMED_RESPONSE,
                        message = "The server omitted the consumable delivery identifier.",
                        remove = false,
                    ),
                )
            val alreadyConsumed =
                operation.consumeResult == PendingStoreConsumeResult.SUCCEEDED
            val next = operation.copy(
                phase = if (alreadyConsumed) {
                    PendingStoreOperationPhase.CONSUME_RESULT_REPORTING
                } else {
                    PendingStoreOperationPhase.CONSUME_REQUIRED
                },
                deliveryId = deliveryId,
                deliveryAcknowledged = operation.deliveryAcknowledged,
                attempts = if (alreadyConsumed) {
                    operation.attempts + 1
                } else {
                    operation.attempts
                },
                nextRetryAtEpochMillis = if (alreadyConsumed) {
                    retryAt(operation.attempts + 1, state.retryAfter)
                } else {
                    retryAt(operation.attempts, state.retryAfter)
                },
                updatedAtEpochMillis = currentTimeMillis(),
            )
            if (!stateStore.upsertPendingStoreOperation(next)) {
                AppliedState.Outcome(storageFailure(next))
            } else if (alreadyConsumed) {
                AppliedState.Outcome(
                    StoreV2Outcome.Deferred(
                        operation = next,
                        state = state,
                        forceVersion = forceVersion,
                        error = null,
                    ),
                )
            } else {
                AppliedState.Continue(next)
            }
        }
        }
    }

    private suspend fun consume(
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): StoreConsumeResult {
        val adapter = try {
            billingAdapterFactory.create(context.market, context.marketKey)
        } catch (_: Exception) {
            return StoreConsumeResult.RetryableFailure(
                StoreBillingError(
                    code = StoreBillingErrorCode.CONNECTION_FAILED,
                    message = "The marketplace billing adapter is unavailable.",
                    isRetryable = true,
                ),
            )
        }
        if (!registerActiveAdapter(adapter)) {
            try {
                adapter.close()
            } catch (_: Exception) {
                // The unused adapter has no recoverable cleanup operation.
            }
            return StoreConsumeResult.RetryableFailure(
                StoreBillingError(
                    code = StoreBillingErrorCode.PURCHASE_IN_PROGRESS,
                    message = "Another native store operation is already in progress.",
                    isRetryable = true,
                ),
            )
        }
        return try {
            withTimeoutOrNull(consumeTimeoutMillis) {
                adapter.consume(operation.toStorePurchase())
            } ?: StoreConsumeResult.RetryableFailure(
                StoreBillingError(
                    code = StoreBillingErrorCode.OPERATION_TIMEOUT,
                    message = "The marketplace purchase consumption timed out.",
                    isRetryable = true,
                ),
            )
        } finally {
            try {
                unregisterActiveAdapter(adapter)
            } catch (_: Exception) {
                // Adapter cleanup below remains authoritative and idempotent.
            }
            try {
                adapter.close()
            } catch (_: Exception) {
                // The consume outcome is durable and cleanup is idempotent.
            }
        }
    }

    private fun evaluate(
        result: StoreServiceResult,
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): ApiEvaluation = when (result) {
        is StoreServiceResult.Failure -> ApiEvaluation.Retry(
            InappifyError(
                code = when (result.kind) {
                    ServiceFailureKind.NETWORK -> InappifyErrorCode.NETWORK
                    ServiceFailureKind.TIMEOUT -> InappifyErrorCode.TIMEOUT
                    ServiceFailureKind.CANCELLED -> InappifyErrorCode.REQUEST_CANCELLED
                    ServiceFailureKind.MALFORMED_RESPONSE ->
                        InappifyErrorCode.MALFORMED_RESPONSE
                    ServiceFailureKind.UNKNOWN -> InappifyErrorCode.UNKNOWN
                },
                message = when (result.kind) {
                    ServiceFailureKind.NETWORK ->
                        "The Inappify store verification service is unavailable."
                    ServiceFailureKind.TIMEOUT ->
                        "The Inappify store verification request timed out."
                    ServiceFailureKind.CANCELLED ->
                        "The Inappify store verification request was cancelled."
                    ServiceFailureKind.MALFORMED_RESPONSE ->
                        "The Inappify store verification response was invalid."
                    ServiceFailureKind.UNKNOWN ->
                        "The Inappify store verification request failed."
                },
                isRetryable = true,
                details = operation.safeDetails(context),
            ),
        )

        is StoreServiceResult.Response -> {
            val payload = result.payload
            val retryableHttp = result.statusCode == HTTP_REQUEST_TIMEOUT ||
                result.statusCode == HTTP_TOO_EARLY ||
                result.statusCode == HTTP_TOO_MANY_REQUESTS ||
                result.statusCode >= HTTP_SERVER_ERROR
            if (
                result.statusCode != HTTP_OK ||
                payload.status != true ||
                payload.state == null
            ) {
                val authorizationRejection = result.isAuthorizationRejection(payload)
                val error = InappifyError(
                    code = if (authorizationRejection) {
                        InappifyErrorCode.UNAUTHORIZED
                    } else if (retryableHttp) {
                        InappifyErrorCode.NETWORK
                    } else {
                        InappifyErrorCode.PURCHASE_VERIFICATION_FAILED
                    },
                    message = if (authorizationRejection) {
                        "The Inappify store verification request is not authorized."
                    } else if (retryableHttp) {
                        "The Inappify store verification service is temporarily unavailable."
                    } else {
                        "The Inappify server rejected the store purchase."
                    },
                    isRetryable = retryableHttp || authorizationRejection,
                    details = operation.safeDetails(context) + buildMap<String, Any?> {
                        put("httpStatus", result.statusCode)
                        result.retryAfterSeconds?.let { put("retryAfterSeconds", it) }
                        payload.errorCode.safeBackendDiagnostic(
                            operation = operation,
                            context = context,
                        )?.let {
                            put("backendCode", it)
                        }
                        payload.message.safeBackendMessage(
                            operation = operation,
                            context = context,
                        )?.let {
                            put("backendMessage", it)
                        }
                    },
                )
                if (retryableHttp) {
                    ApiEvaluation.Retry(error)
                } else {
                    ApiEvaluation.Reject(
                        error = error,
                        requiresRejectionTombstone =
                            result.isPermanentValidationRejection(payload, operation),
                    )
                }
            } else {
                val received = requireNotNull(payload.state)
                val wrongScope = (operation.operation == PendingStoreOperationType.RECOVER_SUBSCRIPTION &&
                    (received.source != operation.store || received.productIdentifier != operation.productIdentifier)) ||
                    (received.source != null && received.source != operation.store) ||
                    (received.productIdentifier != null && received.productIdentifier != operation.productIdentifier) ||
                    (received.verificationRequestId != null && operation.verificationRequestId != null &&
                        received.verificationRequestId != operation.verificationRequestId) ||
                    (received.deliveryId != null && operation.deliveryId != null && received.deliveryId != operation.deliveryId) ||
                    (received.deliveryId != null && received.status !in setOf(
                        StorePurchaseStatus.DELIVERY_REQUIRED, StorePurchaseStatus.CONSUME_REQUIRED) &&
                        operation.productType in setOf(PendingStoreProductType.NON_CONSUMABLE, PendingStoreProductType.SUBSCRIPTION)) ||
                    (received.alreadyDelivered == false && received.status == StorePurchaseStatus.CONSUME_REQUIRED) ||
                    (received.alreadyDelivered == true && received.status == StorePurchaseStatus.DELIVERY_REQUIRED)
                if (wrongScope) ApiEvaluation.Reject(InappifyError(
                    code = InappifyErrorCode.MALFORMED_RESPONSE,
                    message = "The store response does not match the pending purchase scope or delivery state.",
                    details = operation.safeDetails(context),
                ), requiresRejectionTombstone = false)
                else ApiEvaluation.State(state = received, forceVersion = payload.forceVersion)
            }
        }
    }

    private fun StoreServiceResult.Response.isAuthorizationRejection(
        payload: com.inappify.sdk.internal.network.StoreBackendResponse,
    ): Boolean =
        statusCode == HTTP_UNAUTHORIZED ||
            statusCode == HTTP_FORBIDDEN ||
            payload.errorCode
                ?.trim()
                ?.uppercase()
                ?.let(AUTHORIZATION_ERROR_CODES::contains) == true

    private fun StoreServiceResult.Response.isPermanentValidationRejection(
        payload: com.inappify.sdk.internal.network.StoreBackendResponse,
        operation: PendingStoreOperation,
    ): Boolean {
        if (isAuthorizationRejection(payload)) return false
        return payload.errorCode
            ?.trim()
            ?.uppercase()
            ?.let { code -> code in PERMANENT_VALIDATION_ERROR_CODES ||
                (operation.operation == PendingStoreOperationType.RECOVER_SUBSCRIPTION &&
                    code in PERMANENT_SUBSCRIPTION_RECOVERY_ERROR_CODES) } == true
    }

    private suspend fun defer(
        operation: PendingStoreOperation,
        forceVersion: Long?,
        error: InappifyError,
        state: StorePurchaseState?,
    ): StoreV2Outcome {
        val deferred = operation.copy(
            attempts = operation.attempts + 1,
            nextRetryAtEpochMillis = retryAt(operation.attempts + 1,
                state?.retryAfter ?: (error.details["retryAfterSeconds"] as? Long)),
            updatedAtEpochMillis = currentTimeMillis(),
        )
        val retained = stateStore.upsertPendingStoreOperation(deferred)
        return StoreV2Outcome.Deferred(
            operation = deferred,
            state = state,
            forceVersion = forceVersion,
            error = if (retained) error else storageError(operation),
        )
    }

    private suspend fun permanentFailure(
        operation: PendingStoreOperation,
        code: InappifyErrorCode,
        message: String,
        remove: Boolean,
    ): StoreV2Outcome.Failure {
        return StoreV2Outcome.Failure(
            operation = operation,
            forceVersion = null,
            error = InappifyError(
                code = code,
                message = message,
                details = operation.safeDetails(),
            ),
            retainedForRetry = !remove,
            requiresRejectionTombstone = remove,
        )
    }

    private fun storageFailure(operation: PendingStoreOperation): StoreV2Outcome.Failure =
        StoreV2Outcome.Failure(
            operation = operation,
            forceVersion = null,
            error = storageError(operation),
            retainedForRetry = false,
        )

    private fun storageError(operation: PendingStoreOperation): InappifyError =
        InappifyError(
            code = InappifyErrorCode.UNKNOWN,
            message = "The pending store purchase could not be stored securely.",
            isRetryable = true,
            details = operation.safeDetails(),
        )

    private fun rejectionError(
        state: StorePurchaseState,
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): InappifyError =
        InappifyError(
            code = InappifyErrorCode.PURCHASE_VERIFICATION_FAILED,
            message = "The Inappify server rejected the store purchase.",
            details = buildMap {
                put("operation", OPERATION_STORE_VERIFICATION)
                put("store", operation.store)
                state.errorCode.safeBackendDiagnostic(
                    operation = operation,
                    context = context,
                )?.let { put("backendCode", it) }
                state.message.safeBackendMessage(
                    operation = operation,
                    context = context,
                )?.let { put("backendMessage", it) }
            },
        )

    private suspend fun waitUntilDue(operation: PendingStoreOperation): Boolean {
        val dueAt = operation.nextRetryAtEpochMillis ?: return true
        val now = currentTimeMillis()
        if (dueAt <= now) return true
        val remaining = try {
            Math.subtractExact(dueAt, now)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (remaining > MAX_IN_PROCESS_WAIT_MILLIS) return false
        sleep(remaining)
        return true
    }

    private fun retryAt(attempt: Int, retryAfterSeconds: Long?): Long {
        val exponent = min(max(attempt - 1, 0), MAX_BACKOFF_EXPONENT)
        val exponentialSeconds = min(
            BASE_RETRY_SECONDS shl exponent,
            MAX_RETRY_SECONDS,
        )
        val requestedSeconds = retryAfterSeconds?.coerceAtLeast(0L) ?: 0L
        val delaySeconds = max(exponentialSeconds, requestedSeconds)
        val delayMillis = try {
            Math.multiplyExact(delaySeconds, MILLIS_PER_SECOND)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val retryWithoutJitter = saturatingAdd(currentTimeMillis(), delayMillis)
        val jitter = jitterMillis(MAX_JITTER_MILLIS).coerceIn(0L, MAX_JITTER_MILLIS)
        return saturatingAdd(retryWithoutJitter, jitter)
    }

    private fun saturatingAdd(value: Long, nonNegativeIncrement: Long): Long = try {
        Math.addExact(value, nonNegativeIncrement.coerceAtLeast(0L))
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun PendingStoreOperation.bindingError(
        context: StoreV2Context,
    ): InappifyError? {
        val matches = store == context.store && apiKeyFingerprint == context.apiKeyFingerprint &&
            customerIdentifierFingerprint == context.customerIdentifierFingerprint &&
            appIdentifier == context.appIdentifier &&
            (appId == null || appId == context.appId)
        return if (matches) {
            null
        } else {
            InappifyError(
                code = InappifyErrorCode.UNAUTHORIZED,
                message = "The pending store purchase belongs to another app or customer.",
                details = safeDetails(),
            )
        }
    }

    private fun PendingStoreOperation.toStorePurchase(): StorePurchase = StorePurchase(
        orderIdentifier = evidence.orderId.orEmpty(),
        purchaseToken = evidence.purchaseToken,
        developerPayload = evidence.developerPayload.orEmpty(),
        packageName = evidence.packageName.orEmpty(),
        productIdentifier = productIdentifier,
        purchaseTimeMillis = evidence.purchaseTimeMillis ?: 0L,
        originalJson = evidence.originalJson.orEmpty(),
        signature = evidence.signature.orEmpty(),
    )

    private fun PendingStoreOperation.toSyntheticState(
        status: StorePurchaseStatus,
    ): StorePurchaseState = StorePurchaseState(
        status = status,
        paymentId = null,
        eventId = null,
        deliveryId = deliveryId,
        verificationRequestId = verificationRequestId,
        retryAfter = null,
        errorCode = null,
        message = null,
        alreadyProcessed = false,
    )

    private fun PendingStoreOperation.safeDetails(): Map<String, Any?> = mapOf(
        "operation" to OPERATION_STORE_VERIFICATION,
        "store" to store,
        "phase" to phase.name,
        "attemptId" to id.take(MAX_DIAGNOSTIC_LENGTH),
        "outcomeMayHaveCommitted" to true,
    )

    private fun PendingStoreOperation.safeDetails(
        context: StoreV2Context,
    ): Map<String, Any?> = buildMap {
        put("operation", OPERATION_STORE_VERIFICATION)
        put("store", store)
        put("phase", phase.name)
        id.sanitizeBackendText(
            operation = this@safeDetails,
            context = context,
            maximumLength = MAX_DIAGNOSTIC_LENGTH,
        )?.let { put("attemptId", it) }
        put("outcomeMayHaveCommitted", true)
    }

    private fun String?.safeBackendDiagnostic(
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): String? = sanitizeBackendText(
        operation = operation,
        context = context,
        maximumLength = MAX_DIAGNOSTIC_LENGTH,
    )?.takeIf(SAFE_DIAGNOSTIC_PATTERN::matches)

    private fun String?.safeBackendMessage(
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): String? = sanitizeBackendText(
        operation = operation,
        context = context,
        maximumLength = MAX_BACKEND_MESSAGE_LENGTH,
    )

    /**
     * Keeps bounded backend diagnostics after exact-secret redaction.
     * Meaningful parsed receipt leaves are protected as secrets too, and any
     * remaining shared secret fragment rejects the whole diagnostic before
     * truncation. Inputs that cannot be inspected within the fixed work budget
     * are also dropped.
     */
    private fun String?.sanitizeBackendText(
        operation: PendingStoreOperation,
        context: StoreV2Context,
        maximumLength: Int,
    ): String? {
        val source = this ?: return null
        if (source.length > MAX_BACKEND_DIAGNOSTIC_INPUT_LENGTH) return null
        val secrets = operation.backendSecrets(context) ?: return null
        var sanitized = source
        for (secret in secrets.exactValues) {
            sanitized = sanitized.replaceSecretWithinBudget(secret) ?: return null
        }
        if (sanitized.any(Char::isISOControl)) return null
        val trimmed = sanitized.trim().takeIf(String::isNotEmpty) ?: return null
        if (secrets.sharesMeaningfulFragmentWith(trimmed)) return null
        return trimmed.take(maximumLength)
    }

    private fun String.replaceSecretWithinBudget(secret: String): String? {
        var occurrenceCount = 0L
        var searchFrom = 0
        while (searchFrom < length) {
            val foundAt = indexOf(secret, startIndex = searchFrom)
            if (foundAt < 0) break
            occurrenceCount += 1L
            searchFrom = foundAt + secret.length
        }
        if (occurrenceCount == 0L) return this
        val projectedLength = length.toLong() +
            (REDACTED_VALUE.length - secret.length).toLong() * occurrenceCount
        if (projectedLength > MAX_SANITIZED_DIAGNOSTIC_WORK_LENGTH) return null
        return replace(secret, REDACTED_VALUE)
    }

    private fun PendingStoreOperation.backendSecrets(
        context: StoreV2Context,
    ): BackendSecrets? {
        val exactValues = linkedSetOf<String>()
        val fragments = linkedSetOf<String>()
        var totalSecretCharacters = 0

        fun addExact(value: String?): Boolean {
            val secret = value?.takeIf(String::isNotBlank) ?: return true
            if (secret in exactValues) return true
            if (
                secret.length > MAX_SECRET_VALUE_LENGTH ||
                exactValues.size >= MAX_SECRET_VALUE_COUNT ||
                totalSecretCharacters > MAX_SECRET_TOTAL_CHARACTERS - secret.length
            ) {
                return false
            }
            exactValues += secret
            totalSecretCharacters += secret.length
            return true
        }

        fun addFragments(value: String?): Boolean {
            val secret = value?.takeIf(String::isNotBlank) ?: return true
            if (secret.length < MIN_SECRET_FRAGMENT_LENGTH) return true
            for (start in 0..secret.length - MIN_SECRET_FRAGMENT_LENGTH) {
                val fragment = secret.substring(
                    startIndex = start,
                    endIndex = start + MIN_SECRET_FRAGMENT_LENGTH,
                )
                if (
                    fragment !in fragments &&
                    fragments.size >= MAX_SECRET_FRAGMENT_COUNT
                ) {
                    return false
                }
                fragments += fragment
            }
            return true
        }

        fun addSecret(value: String?): Boolean =
            addExact(value) && addFragments(value)

        fun addJsonSecret(value: String?): Boolean {
            val raw = value?.takeIf(String::isNotBlank) ?: return true
            if (!addExact(raw)) return false
            return when (val extraction = raw.extractJsonPrimitiveSecrets()) {
                JsonSecretExtraction.Incomplete -> false
                JsonSecretExtraction.NotJson -> addFragments(raw)
                is JsonSecretExtraction.Complete ->
                    extraction.values.all(::addSecret)
            }
        }

        val directSecrets = listOfNotNull(
            context.apiKey,
            context.customerToken,
            context.marketKey,
            customerToken,
            apiKeyFingerprint,
            customerIdentifierFingerprint,
            evidence.purchaseToken,
            evidence.orderId,
            evidence.signature,
        )
        if (!directSecrets.all(::addSecret)) return null
        if (!addJsonSecret(evidence.developerPayload)) return null
        if (!addJsonSecret(evidence.originalJson)) return null

        return BackendSecrets(
            exactValues = exactValues.sortedByDescending(String::length),
            fragments = fragments,
        )
    }

    private fun String.extractJsonPrimitiveSecrets(): JsonSecretExtraction {
        if (length > MAX_JSON_SECRET_INPUT_LENGTH) {
            return JsonSecretExtraction.Incomplete
        }
        val root = try {
            JsonParser.parseString(this)
        } catch (_: RuntimeException) {
            return JsonSecretExtraction.NotJson
        } catch (_: StackOverflowError) {
            return JsonSecretExtraction.Incomplete
        }

        val pending = ArrayDeque<JsonElement>()
        val values = mutableListOf<String>()
        pending.addLast(root)
        var visitedNodes = 0
        while (pending.isNotEmpty()) {
            if (++visitedNodes > MAX_JSON_SECRET_NODE_COUNT) {
                return JsonSecretExtraction.Incomplete
            }
            val element = pending.removeLast()
            when {
                element.isJsonPrimitive -> {
                    val primitive = element.asJsonPrimitive.asString
                    if (primitive.length >= MIN_SECRET_FRAGMENT_LENGTH) {
                        values += primitive
                    }
                }
                element.isJsonArray -> {
                    for (child in element.asJsonArray) {
                        if (pending.size + visitedNodes >= MAX_JSON_SECRET_NODE_COUNT) {
                            return JsonSecretExtraction.Incomplete
                        }
                        pending.addLast(child)
                    }
                }
                element.isJsonObject -> {
                    for ((_, child) in element.asJsonObject.entrySet()) {
                        if (pending.size + visitedNodes >= MAX_JSON_SECRET_NODE_COUNT) {
                            return JsonSecretExtraction.Incomplete
                        }
                        pending.addLast(child)
                    }
                }
            }
        }
        return JsonSecretExtraction.Complete(values)
    }

    private fun BackendSecrets.sharesMeaningfulFragmentWith(value: String): Boolean {
        val candidate = value.replace(REDACTED_VALUE, "")
        if (candidate.length < MIN_SECRET_FRAGMENT_LENGTH) return false
        for (start in 0..candidate.length - MIN_SECRET_FRAGMENT_LENGTH) {
            val fragment = candidate.substring(
                startIndex = start,
                endIndex = start + MIN_SECRET_FRAGMENT_LENGTH,
            )
            if (fragment in fragments) return true
        }
        return false
    }

    private class BackendSecrets(
        val exactValues: List<String>,
        val fragments: Set<String>,
    )

    private sealed interface JsonSecretExtraction {
        object NotJson : JsonSecretExtraction
        object Incomplete : JsonSecretExtraction
        class Complete(val values: List<String>) : JsonSecretExtraction
    }

    private fun monotonic(current: Long?, received: Long?): Long? = when {
        current == null -> received
        received == null -> current
        else -> max(current, received)
    }

    private sealed interface ApiEvaluation {
        class State(
            val state: StorePurchaseState,
            val forceVersion: Long?,
        ) : ApiEvaluation

        class Retry(val error: InappifyError) : ApiEvaluation
        class Reject(
            val error: InappifyError,
            val requiresRejectionTombstone: Boolean,
        ) : ApiEvaluation
    }

    private sealed interface AppliedState {
        class Continue(val operation: PendingStoreOperation) : AppliedState
        class Outcome(val outcome: StoreV2Outcome) : AppliedState
    }

    internal companion object {
        internal const val MAX_FOREGROUND_POLLS: Int = 8
        internal const val MAX_BACKGROUND_POLLS: Int = 1
        internal const val DEFAULT_CONSUME_TIMEOUT_MILLIS = 30_000L
        private const val BASE_RETRY_SECONDS = 2L
        private const val MAX_RETRY_SECONDS = 15L
        private const val MAX_BACKOFF_EXPONENT = 3
        private const val MAX_JITTER_MILLIS = 250L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_IN_PROCESS_WAIT_MILLIS =
            MAX_RETRY_SECONDS * MILLIS_PER_SECOND
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_EARLY = 425
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val MAX_DIAGNOSTIC_LENGTH = 128
        private const val MAX_BACKEND_MESSAGE_LENGTH = 256
        private const val MAX_BACKEND_DIAGNOSTIC_INPUT_LENGTH = 2_048
        private const val MAX_SANITIZED_DIAGNOSTIC_WORK_LENGTH = 4_096
        private const val MAX_SECRET_VALUE_COUNT = 64
        private const val MAX_SECRET_VALUE_LENGTH = 8_192
        private const val MAX_SECRET_TOTAL_CHARACTERS = 16_384
        private const val MAX_SECRET_FRAGMENT_COUNT = 8_192
        private const val MIN_SECRET_FRAGMENT_LENGTH = 8
        private const val MAX_JSON_SECRET_INPUT_LENGTH = 8_192
        private const val MAX_JSON_SECRET_NODE_COUNT = 128
        private const val REDACTED_VALUE = "<redacted>"
        private const val STORE_TEMPORARILY_UNAVAILABLE =
            "STORE_TEMPORARILY_UNAVAILABLE"
        private const val STORE_CONSUME_FAILED = "STORE_CONSUME_FAILED"
        private const val OPERATION_STORE_VERIFICATION = "storePurchaseVerification"
        private const val OPERATION_CONSUME = "consumeStorePurchase"
        private val AUTHORIZATION_ERROR_CODES = setOf(
            "UNAUTHORIZED",
            "AUTHENTICATION_FAILED",
            "INVALID_TOKEN",
            "TOKEN_EXPIRED",
        )
        private val PERMANENT_VALIDATION_ERROR_CODES = setOf(
            "APP_MISMATCH",
            "INVALID_PURCHASE",
            "PRODUCT_MISMATCH",
        )
        private val PERMANENT_SUBSCRIPTION_RECOVERY_ERROR_CODES = setOf(
            "SUBSCRIPTION_NOT_LINKED",
            "SUBSCRIPTION_OWNER_MISMATCH",
            "SUBSCRIPTION_EVIDENCE_INVALID",
            "SUBSCRIPTION_BINDING_INVALID",
        )
        private val SAFE_DIAGNOSTIC_PATTERN =
            Regex("(?:(?:[A-Za-z0-9_.-])|(?:<redacted>)){1,128}")
    }
}
