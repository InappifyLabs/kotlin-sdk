package com.inappify.sdk.internal

import com.inappify.sdk.InappifyMarket
import com.inappify.sdk.internal.billing.StoreBillingAdapter
import com.inappify.sdk.internal.billing.StoreBillingAdapterFactory
import com.inappify.sdk.internal.billing.StoreBillingError
import com.inappify.sdk.internal.billing.StoreBillingErrorCode
import com.inappify.sdk.internal.billing.StoreBillingResult
import com.inappify.sdk.internal.billing.StoreConsumeResult
import com.inappify.sdk.internal.billing.StoreProductType
import com.inappify.sdk.internal.billing.StorePurchase
import com.inappify.sdk.internal.billing.StorePurchaseQueryResult
import com.inappify.sdk.internal.billing.StorePurchaseRequest
import com.inappify.sdk.internal.billing.StoreUiHost
import com.inappify.sdk.internal.network.StoreV2Backend
import com.inappify.sdk.internal.network.ServiceFailureKind
import com.inappify.sdk.internal.network.StoreBackendResponse
import com.inappify.sdk.internal.network.StoreConsumeResult as NetworkConsumeResult
import com.inappify.sdk.internal.network.StoreConsumeResultApiRequest
import com.inappify.sdk.internal.network.StoreDeliveryApiRequest
import com.inappify.sdk.internal.network.StorePurchaseApiRequest
import com.inappify.sdk.internal.network.StorePurchaseState
import com.inappify.sdk.internal.network.StorePurchaseStatus
import com.inappify.sdk.internal.network.StoreServiceResult
import com.inappify.sdk.internal.network.StoreVerificationStatusApiRequest
import com.inappify.sdk.internal.storage.PendingStoreConsumeResult
import com.inappify.sdk.internal.storage.PendingStoreOperation
import com.inappify.sdk.internal.storage.PendingStoreOperationPhase
import com.inappify.sdk.internal.storage.PendingStoreOperationType
import com.inappify.sdk.internal.storage.PendingStoreProductType
import com.inappify.sdk.internal.storage.PendingStorePurchaseEvidence
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.SessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.inappify.sdk.internal.billing.storeId

@RunWith(Parameterized::class)
class StoreV2CoordinatorTest(private val market: InappifyMarket) {

    @Test
    fun wrongStoreCannotSubmitResumeOrConsume() = runBlocking {
        val fixture = fixture()
        val wrong = operation().copy(store = if (market == InappifyMarket.MYKET) "bazar" else "myket")
        assertTrue(fixture.coordinator.submit(wrong, context()) is StoreV2Outcome.Failure)
        assertEquals(0, fixture.stateStore.operationCount)
        fixture.stateStore.seed(wrong)
        assertTrue(fixture.coordinator.resume(wrong, context()) is StoreV2Outcome.Failure)
        assertTrue(fixture.coordinator.confirmDelivery(wrong, context()) is StoreV2Outcome.Failure)
        assertTrue(fixture.events.isEmpty())
        assertEquals(1, fixture.stateStore.operationCount)
    }

    @Test
    fun httpBackpressurePersistsRetryAfterWithoutDiscardingReceipt() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(StorePurchaseStatus.PROCESSING, httpStatus = 503, transportRetryAfter = 120)
        val outcome = fixture.coordinator.submit(operation(productType = PendingStoreProductType.CONSUMABLE), context())
        assertTrue(outcome is StoreV2Outcome.Deferred)
        assertTrue(outcome.operation.nextRetryAtEpochMillis!! >= INITIAL_TIME + 120000)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(0, fixture.billingFactory.createCalls)
    }

    @Test
    fun nonConsumableWithDeliveryIdIsAContractFailure() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(StorePurchaseStatus.COMPLETED, deliveryId = DELIVERY_ID)
        val outcome = fixture.coordinator.submit(operation(productType = PendingStoreProductType.NON_CONSUMABLE), context())
        assertFalse(outcome is StoreV2Outcome.Terminal)
        assertEquals(0, fixture.billingFactory.createCalls)
    }

    @Test
    fun submitCompleted_removesDurableOperation() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.submit(
            operation(productType = PendingStoreProductType.NON_CONSUMABLE),
            context(),
        )

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(0, fixture.stateStore.operationCount)
        assertEquals(1, fixture.stateStore.removeCalls)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
        assertEquals(0, fixture.billingFactory.createCalls)
    }

    @Test
    fun terminalStateRemovalFailure_returnsRetryableFailureAndRetainsEvidence() = runBlocking {
        val fixture = fixture()
        fixture.stateStore.removeSucceeds = false
        fixture.service.submitResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.submit(
            operation(productType = PendingStoreProductType.NON_CONSUMABLE),
            context(),
        )

        assertTrue(outcome is StoreV2Outcome.Failure)
        outcome as StoreV2Outcome.Failure
        assertTrue(outcome.error.isRetryable)
        assertTrue(outcome.retainedForRetry)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(1, fixture.stateStore.removeCalls)
    }

    @Test
    fun submitProcessing_honorsRetryAfterThenPollsToCompletion() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.PROCESSING,
            verificationRequestId = VERIFICATION_ID,
            retryAfterSeconds = 5L,
        )
        fixture.service.statusResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.submit(
            operation = operation(productType = PendingStoreProductType.NON_CONSUMABLE),
            context = context(),
            maxPolls = 1,
        )

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(listOf(5_000L), fixture.clock.sleepDurations)
        assertEquals(listOf(EVENT_SUBMIT, EVENT_STATUS), fixture.events)
        assertEquals(0, fixture.stateStore.operationCount)
        val verifying = fixture.stateStore.checkpoints.first {
            it.phase == PendingStoreOperationPhase.VERIFYING
        }
        assertEquals(1, verifying.attempts)
        assertEquals(INITIAL_TIME + 5_000L, verifying.nextRetryAtEpochMillis)
    }

    @Test
    fun submitNetworkFailure_retainsOperationForRetry() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += StoreServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )

        val outcome = fixture.coordinator.submit(operation(), context())

        assertTrue(outcome is StoreV2Outcome.Deferred)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(0, fixture.stateStore.removeCalls)
        assertEquals(PendingStoreOperationPhase.REGISTERING, fixture.stateStore.latestPhase)
        assertEquals(1, fixture.stateStore.latestAttempts)
        assertEquals(INITIAL_TIME + 2_000L, fixture.stateStore.latestRetryAt)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
    }

    @Test
    fun resumeRegistering_waitsUntilStoredRetryDeadline() = runBlocking {
        val fixture = fixture()
        val pending = operation(productType = PendingStoreProductType.NON_CONSUMABLE).copy(
            nextRetryAtEpochMillis = INITIAL_TIME + 2_000L,
        )
        fixture.stateStore.seed(pending)
        fixture.service.submitResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.resume(pending, context())

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(listOf(2_000L), fixture.clock.sleepDurations)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
    }

    @Test
    fun serverRetryAfterBeyondInProcessWindow_defersWithoutEarlyPoll() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.PROCESSING,
            verificationRequestId = VERIFICATION_ID,
            retryAfterSeconds = 60L,
        )

        val firstOutcome = fixture.coordinator.submit(
            operation = operation(productType = PendingStoreProductType.NON_CONSUMABLE),
            context = context(),
            maxPolls = 1,
        )

        assertTrue(firstOutcome is StoreV2Outcome.Deferred)
        assertEquals(INITIAL_TIME + 60_000L, fixture.stateStore.latestRetryAt)
        assertTrue(fixture.clock.sleepDurations.isEmpty())
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)

        fixture.clock.now += 60_000L
        fixture.service.statusResults += response(StorePurchaseStatus.COMPLETED)

        val resumed = fixture.coordinator.resume(
            operation = fixture.stateStore.latestOperation(),
            context = context(),
            maxPolls = 1,
        )

        assertTrue(resumed is StoreV2Outcome.Terminal)
        assertEquals(listOf(EVENT_SUBMIT, EVENT_STATUS), fixture.events)
    }

    @Test
    fun overflowingServerRetryAfter_saturatesDeadlineAndDoesNotBlock() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.PROCESSING,
            verificationRequestId = VERIFICATION_ID,
            retryAfterSeconds = Long.MAX_VALUE,
        )

        val outcome = fixture.coordinator.submit(
            operation = operation(),
            context = context(),
            maxPolls = 1,
        )

        assertTrue(outcome is StoreV2Outcome.Deferred)
        assertEquals(Long.MAX_VALUE, fixture.stateStore.latestRetryAt)
        assertTrue(fixture.clock.sleepDurations.isEmpty())
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
    }

    @Test
    fun submitRejected_retainsEvidenceForAtomicClientFinalization() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "PRODUCT_MISMATCH",
            message = "The verified product does not match.",
        )

        val outcome = fixture.coordinator.submit(operation(), context())

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("PRODUCT_MISMATCH", outcome.error.details["backendCode"])
        assertEquals(
            "The verified product does not match.",
            outcome.error.details["backendMessage"],
        )
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(0, fixture.stateStore.removeCalls)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
        assertEquals(0, fixture.billingFactory.createCalls)
    }

    @Test
    fun consumableCompletedOrRestoredBeforeConsumeReport_isRetainedAsMalformed() =
        runBlocking {
            listOf(
                StorePurchaseStatus.COMPLETED,
                StorePurchaseStatus.RESTORED,
            ).forEach { terminalStatus ->
                val fixture = fixture()
                fixture.service.submitResults += response(terminalStatus)

                val outcome = fixture.coordinator.submit(operation(), context())

                assertTrue(outcome is StoreV2Outcome.Failure)
                outcome as StoreV2Outcome.Failure
                assertEquals(
                    com.inappify.sdk.InappifyErrorCode.MALFORMED_RESPONSE,
                    outcome.error.code,
                )
                assertTrue(outcome.retainedForRetry)
                assertEquals(1, fixture.stateStore.operationCount)
                assertEquals(0, fixture.stateStore.removeCalls)
                assertEquals(0, fixture.billing.consumeCalls)
            }
        }

    @Test
    fun rejectedState_redactsEveryKnownSecretFromPublicError() = runBlocking {
        val fixture = fixture()
        val pending = operation().copy(customerToken = STORED_CUSTOMER_TOKEN)
        val currentContext = context(customerToken = REFRESHED_CUSTOMER_TOKEN)
        val secrets = knownSecrets(pending, currentContext)
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "REJECTED_${currentContext.apiKey}",
            message = "Backend echoed ${secrets.joinToString(separator = " | ")}",
        )

        val outcome = fixture.coordinator.submit(pending, currentContext)

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("REJECTED_<redacted>", outcome.error.details["backendCode"])
        assertTrue(
            outcome.error.details["backendMessage"].toString().contains("<redacted>"),
        )
        assertContainsNoSecret(outcome.error, secrets)
    }

    @Test
    fun rejectedEnvelope_redactsSecretsAndDropsControlCharacters() = runBlocking {
        val fixture = fixture()
        val pending = operation().copy(customerToken = STORED_CUSTOMER_TOKEN)
        val currentContext = context(customerToken = REFRESHED_CUSTOMER_TOKEN)
        val secrets = knownSecrets(pending, currentContext)
        fixture.service.submitResults += StoreServiceResult.Response(
            statusCode = 400,
            payload = StoreBackendResponse(
                status = false,
                state = null,
                hasForceUpdate = false,
                forceVersion = null,
                errorCode = "INVALID_${pending.evidence.purchaseToken}",
                message = "Unsafe ${currentContext.customerToken}\nmessage",
            ),
            requestId = null,
        )

        val outcome = fixture.coordinator.submit(pending, currentContext)

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("INVALID_<redacted>", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
        assertContainsNoSecret(outcome.error, secrets)
        assertEquals(null, outcome.state)
        assertFalse(outcome.requiresRejectionTombstone)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(0, fixture.stateStore.removeCalls)
    }

    @Test
    fun unknownEnvelopeFailuresNeverTombstonePaidEvidence() = runBlocking {
        listOf(200, 404, 409).forEach { httpStatus ->
            val fixture = fixture()
            fixture.service.submitResults += StoreServiceResult.Response(
                statusCode = httpStatus,
                payload = StoreBackendResponse(
                    status = false,
                    state = null,
                    hasForceUpdate = false,
                    forceVersion = null,
                    errorCode = "UNKNOWN_STORE_FAILURE",
                    message = "The request could not be completed.",
                ),
                requestId = null,
            )

            val outcome = fixture.coordinator.submit(operation(), context())

            assertTrue(outcome is StoreV2Outcome.Rejected)
            outcome as StoreV2Outcome.Rejected
            assertFalse(outcome.requiresRejectionTombstone)
            assertEquals(1, fixture.stateStore.operationCount)
            assertEquals(0, fixture.stateStore.removeCalls)
        }
    }

    @Test
    fun rejectedState_dropsDiagnosticContainingPurchaseTokenPrefix() = runBlocking {
        val fixture = fixture()
        val pending = operation()
        val tokenPrefix = pending.evidence.purchaseToken.take(10)
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "PURCHASE_TOKEN_INVALID",
            message = "Marketplace token prefix: $tokenPrefix",
        )

        val outcome = fixture.coordinator.submit(pending, context())

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("PURCHASE_TOKEN_INVALID", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
        assertFalse(outcome.error.toString().contains(tokenPrefix))
    }

    @Test
    fun rejectedState_dropsDiagnosticContainingNestedJsonSecretPrefix() = runBlocking {
        val fixture = fixture()
        val nestedSecret = "nested-receipt-secret-value"
        val pending = operation().copy(
            evidence = operation().evidence.copy(
                developerPayload =
                    """{"metadata":{"checkout":{"secret":"$nestedSecret"}}}""",
            ),
        )
        val nestedPrefix = nestedSecret.take(12)
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "PAYLOAD_INVALID",
            message = "Invalid payload value: $nestedPrefix",
        )

        val outcome = fixture.coordinator.submit(pending, context())

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("PAYLOAD_INVALID", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
        assertFalse(outcome.error.toString().contains(nestedPrefix))
    }

    @Test
    fun rejectedState_dropsPrefixesOfCurrentAndStoredCustomerTokens() = runBlocking {
        val fixture = fixture()
        val pending = operation().copy(customerToken = STORED_CUSTOMER_TOKEN)
        val currentContext = context(customerToken = REFRESHED_CUSTOMER_TOKEN)
        val storedPrefix = STORED_CUSTOMER_TOKEN.take(10)
        val currentPrefix = REFRESHED_CUSTOMER_TOKEN.take(10)
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "SESSION_INVALID",
            message = "Sessions: $storedPrefix and $currentPrefix",
        )

        val outcome = fixture.coordinator.submit(pending, currentContext)

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("SESSION_INVALID", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
        assertFalse(outcome.error.toString().contains(storedPrefix))
        assertFalse(outcome.error.toString().contains(currentPrefix))
    }

    @Test
    fun rejectedState_dropsOversizedBackendDiagnostic() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "MESSAGE_TOO_LARGE",
            message = "x".repeat(4_096),
        )

        val outcome = fixture.coordinator.submit(operation(), context())

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("MESSAGE_TOO_LARGE", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
    }

    @Test
    fun rejectedState_boundsExpansionFromRepeatedShortCredential() = runBlocking {
        val fixture = fixture()
        val shortCredentialContext = context(apiKey = "x")
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "MESSAGE_INVALID",
            message = "x".repeat(2_048),
        )

        val outcome = fixture.coordinator.submit(operation(), shortCredentialContext)

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertEquals("MESSAGE_INVALID", outcome.error.details["backendCode"])
        assertFalse(outcome.error.details.containsKey("backendMessage"))
    }

    @Test
    fun rejectedState_dropsDiagnosticsWhenReceiptExceedsInspectionBudget() = runBlocking {
        val fixture = fixture()
        val pending = operation().copy(
            evidence = operation().evidence.copy(
                originalJson = "x".repeat(8_193),
            ),
        )
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.REJECTED,
            errorCode = "RECEIPT_INVALID",
            message = "The receipt could not be verified.",
        )

        val outcome = fixture.coordinator.submit(pending, context())

        assertTrue(outcome is StoreV2Outcome.Rejected)
        outcome as StoreV2Outcome.Rejected
        assertFalse(outcome.error.details.containsKey("backendCode"))
        assertFalse(outcome.error.details.containsKey("backendMessage"))
    }

    @Test
    fun deliveryRequired_isPersistedAndReturnedAsPending() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )

        val outcome = fixture.coordinator.submit(operation(), context())

        assertTrue(outcome is StoreV2Outcome.PendingDelivery)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(
            PendingStoreOperationPhase.DELIVERY_REQUIRED,
            fixture.stateStore.latestPhase,
        )
        assertFalse(fixture.stateStore.latestDeliveryAcknowledged)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
        assertEquals(0, fixture.billingFactory.createCalls)
    }

    @Test
    fun deliveryRequired_promotesUntypedLegacyInAppReceiptToConsumable() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )

        val outcome = fixture.coordinator.submit(
            operation(productType = PendingStoreProductType.LEGACY_IN_APP),
            context(),
        )

        assertTrue(outcome is StoreV2Outcome.PendingDelivery)
        assertEquals(
            PendingStoreProductType.CONSUMABLE,
            fixture.stateStore.latestOperation().productType,
        )
        assertEquals(
            PendingStoreOperationPhase.DELIVERY_REQUIRED,
            fixture.stateStore.latestPhase,
        )
        assertEquals(DELIVERY_ID, fixture.stateStore.latestOperation().deliveryId)
        assertEquals(0, fixture.billing.consumeCalls)
    }

    @Test
    fun deliveryOrConsumeRequiredForNonConsumable_isRejectedWithoutBilling() = runBlocking {
        StorePurchaseStatus.values()
            .filter {
                it == StorePurchaseStatus.DELIVERY_REQUIRED ||
                    it == StorePurchaseStatus.CONSUME_REQUIRED
            }
            .forEach { status ->
                val fixture = fixture()
                fixture.service.submitResults += response(
                    status = status,
                    deliveryId = DELIVERY_ID,
                )

                val outcome = fixture.coordinator.submit(
                    operation(productType = PendingStoreProductType.NON_CONSUMABLE),
                    context(),
                )

                assertTrue(outcome is StoreV2Outcome.Failure)
                outcome as StoreV2Outcome.Failure
                assertEquals(
                    com.inappify.sdk.InappifyErrorCode.MALFORMED_RESPONSE,
                    outcome.error.code,
                )
                assertTrue(outcome.retainedForRetry)
                assertEquals(1, fixture.stateStore.operationCount)
                assertEquals(0, fixture.billing.consumeCalls)
            }
    }

    @Test
    fun consumeRequiredBeforeHostDelivery_isRejectedWithoutConsume() = runBlocking {
        val fixture = fixture()
        fixture.service.submitResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )

        val outcome = fixture.coordinator.submit(operation(), context())

        assertTrue(outcome is StoreV2Outcome.Failure)
        outcome as StoreV2Outcome.Failure
        assertEquals(
            com.inappify.sdk.InappifyErrorCode.MALFORMED_RESPONSE,
            outcome.error.code,
        )
        assertTrue(outcome.retainedForRetry)
        assertEquals(1, fixture.stateStore.operationCount)
        assertFalse(fixture.stateStore.latestDeliveryAcknowledged)
        assertEquals(0, fixture.billing.consumeCalls)
        assertEquals(listOf(EVENT_SUBMIT), fixture.events)
    }

    @Test
    fun confirmDelivery_deliversBeforeConsumeThenReportsCompletion() = runBlocking {
        val fixture = fixture()
        val pending = operation(
            phase = PendingStoreOperationPhase.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.stateStore.seed(pending)
        fixture.service.deliveryResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.billing.consumeResults += StoreConsumeResult.Success
        fixture.service.reportResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.confirmDelivery(pending, context())

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(
            listOf(EVENT_DELIVER, EVENT_CONSUME, EVENT_REPORT),
            fixture.events,
        )
        assertEquals(listOf(NetworkConsumeResult.SUCCEEDED), fixture.service.reportedResults)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(0, fixture.stateStore.operationCount)
    }

    @Test
    fun retryableConsume_reportsFailureAndRetriesWithoutRedelivery() = runBlocking {
        val fixture = fixture()
        val pending = operation(
            phase = PendingStoreOperationPhase.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.stateStore.seed(pending)
        fixture.service.deliveryResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.billing.consumeResults += StoreConsumeResult.RetryableFailure(
            StoreBillingError(
                code = StoreBillingErrorCode.CONNECTION_LOST,
                message = "The test marketplace connection was interrupted.",
                isRetryable = true,
            ),
        )
        fixture.service.reportResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            retryAfterSeconds = 3L,
        )

        val firstOutcome = fixture.coordinator.confirmDelivery(pending, context())

        assertTrue(firstOutcome is StoreV2Outcome.Deferred)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(PendingStoreOperationPhase.CONSUME_REQUIRED, fixture.stateStore.latestPhase)
        assertEquals(null, fixture.stateStore.latestConsumeResult)
        assertTrue(fixture.stateStore.latestDeliveryAcknowledged)
        assertEquals(listOf(NetworkConsumeResult.FAILED), fixture.service.reportedResults)
        assertEquals(
            listOf(EVENT_DELIVER, EVENT_CONSUME, EVENT_REPORT),
            fixture.events,
        )

        fixture.billing.consumeResults += StoreConsumeResult.Success
        fixture.service.reportResults += response(StorePurchaseStatus.COMPLETED)
        val queued = fixture.stateStore.latestOperation()

        val resumed = fixture.coordinator.resume(queued, context())

        assertTrue(resumed is StoreV2Outcome.Terminal)
        assertEquals(
            listOf(
                EVENT_DELIVER,
                EVENT_CONSUME,
                EVENT_REPORT,
                EVENT_CONSUME,
                EVENT_REPORT,
            ),
            fixture.events,
        )
        assertEquals(
            listOf(NetworkConsumeResult.FAILED, NetworkConsumeResult.SUCCEEDED),
            fixture.service.reportedResults,
        )
        assertEquals(2, fixture.billing.consumeCalls)
        assertEquals(0, fixture.stateStore.operationCount)
    }

    @Test
    fun permanentConsumeFailure_isNotRetriedWhenServerStillRequiresConsume() = runBlocking {
        val fixture = fixture()
        val pending = operation(
            phase = PendingStoreOperationPhase.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.stateStore.seed(pending)
        fixture.service.deliveryResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        fixture.billing.consumeResults += StoreConsumeResult.PermanentFailure(
            StoreBillingError(
                code = StoreBillingErrorCode.INVALID_PURCHASE_DATA,
                message = "The test purchase evidence is invalid.",
            ),
        )
        fixture.service.reportResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )

        val outcome = fixture.coordinator.confirmDelivery(pending, context())

        assertTrue(outcome is StoreV2Outcome.Failure)
        outcome as StoreV2Outcome.Failure
        assertFalse(outcome.retainedForRetry)
        assertTrue(outcome.requiresRejectionTombstone)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(listOf(NetworkConsumeResult.FAILED), fixture.service.reportedResults)
        assertEquals(
            listOf(EVENT_DELIVER, EVENT_CONSUME, EVENT_REPORT),
            fixture.events,
        )
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(0, fixture.stateStore.removeCalls)
    }

    @Test
    fun missingConsumeCallback_timesOutClosesAdapterAndSchedulesSafeRetry() = runBlocking {
        val fixture = fixture(consumeTimeoutMillis = 25L)
        val pending = operation(
            phase = PendingStoreOperationPhase.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            deliveryAcknowledged = true,
        )
        fixture.stateStore.seed(pending)
        fixture.billing.blockConsume = true
        fixture.service.reportResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            retryAfterSeconds = 3L,
        )

        val outcome = fixture.coordinator.resume(pending, context())

        assertTrue(outcome is StoreV2Outcome.Deferred)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(1, fixture.billing.closeCalls)
        assertEquals(listOf(NetworkConsumeResult.FAILED), fixture.service.reportedResults)
        assertEquals(PendingStoreOperationPhase.CONSUME_REQUIRED, fixture.stateStore.latestPhase)
        assertEquals(null, fixture.stateStore.latestConsumeResult)
        assertEquals(listOf(EVENT_CONSUME, EVENT_REPORT), fixture.events)
    }

    @Test
    fun cancellationDuringConsume_closesAdapterAndPreservesRetryCheckpoint() = runBlocking {
        val fixture = fixture(consumeTimeoutMillis = 60_000L)
        val pending = operation(
            phase = PendingStoreOperationPhase.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            deliveryAcknowledged = true,
        )
        fixture.stateStore.seed(pending)
        fixture.billing.blockConsume = true

        val consumeJob = launch {
            fixture.coordinator.resume(pending, context())
        }
        fixture.billing.consumeStarted.await()
        consumeJob.cancelAndJoin()

        assertTrue(consumeJob.isCancelled)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(1, fixture.billing.closeCalls)
        assertEquals(1, fixture.stateStore.operationCount)
        assertEquals(
            PendingStoreOperationPhase.CONSUME_REQUIRED,
            fixture.stateStore.latestOperation().phase,
        )
        assertEquals(listOf(EVENT_CONSUME), fixture.events)
        assertTrue(fixture.service.reportedResults.isEmpty())
    }

    @Test
    fun consumeRegistrationConflict_closesUnusedAdapterWithoutConsuming() = runBlocking {
        val fixture = fixture(registerActiveAdapter = { false })
        val pending = operation(
            phase = PendingStoreOperationPhase.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            deliveryAcknowledged = true,
        )
        fixture.stateStore.seed(pending)
        fixture.service.reportResults += response(
            status = StorePurchaseStatus.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
        )

        val outcome = fixture.coordinator.resume(pending, context())

        assertTrue(outcome is StoreV2Outcome.Deferred)
        assertEquals(0, fixture.billing.consumeCalls)
        assertEquals(1, fixture.billing.closeCalls)
        assertEquals(listOf(NetworkConsumeResult.FAILED), fixture.service.reportedResults)
        assertEquals(listOf(EVENT_REPORT), fixture.events)
    }

    @Test
    fun consumeSuccessThenReportNetworkLoss_resumeReportsWithoutConsumingAgain() = runBlocking {
        val fixture = fixture()
        val pending = operation(
            phase = PendingStoreOperationPhase.CONSUME_REQUIRED,
            deliveryId = DELIVERY_ID,
            deliveryAcknowledged = true,
        )
        fixture.stateStore.seed(pending)
        fixture.billing.consumeResults += StoreConsumeResult.Success
        fixture.service.reportResults += StoreServiceResult.Failure(
            ServiceFailureKind.NETWORK,
        )

        val firstOutcome = fixture.coordinator.resume(pending, context())

        assertTrue(firstOutcome is StoreV2Outcome.Deferred)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(
            PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
            fixture.stateStore.latestPhase,
        )
        assertEquals(
            PendingStoreConsumeResult.SUCCEEDED,
            fixture.stateStore.latestConsumeResult,
        )
        assertEquals(listOf(EVENT_CONSUME, EVENT_REPORT), fixture.events)

        fixture.service.reportResults += response(StorePurchaseStatus.COMPLETED)
        val queued = fixture.stateStore.latestOperation()

        val resumed = fixture.coordinator.resume(queued, context())

        assertTrue(resumed is StoreV2Outcome.Terminal)
        assertEquals(1, fixture.billing.consumeCalls)
        assertEquals(listOf(2_000L), fixture.clock.sleepDurations)
        assertEquals(
            listOf(EVENT_CONSUME, EVENT_REPORT, EVENT_REPORT),
            fixture.events,
        )
        assertEquals(0, fixture.stateStore.operationCount)
    }

    @Test
    fun customerOrAppBindingMismatch_makesNoApiOrBillingCall() = runBlocking {
        val customerMismatch = fixture()
        val registering = operation()
        customerMismatch.stateStore.seed(registering)

        val customerOutcome = customerMismatch.coordinator.resume(
            registering,
            context(customerBinding = DIFFERENT_BINDING),
        )

        assertTrue(customerOutcome is StoreV2Outcome.Failure)
        assertTrue((customerOutcome as StoreV2Outcome.Failure).retainedForRetry)
        assertTrue(customerMismatch.events.isEmpty())
        assertEquals(0, customerMismatch.billingFactory.createCalls)
        assertEquals(1, customerMismatch.stateStore.operationCount)

        val appMismatch = fixture()
        val deliveryPending = operation(
            phase = PendingStoreOperationPhase.DELIVERY_REQUIRED,
            deliveryId = DELIVERY_ID,
        )
        appMismatch.stateStore.seed(deliveryPending)

        val appOutcome = appMismatch.coordinator.confirmDelivery(
            deliveryPending,
            context(appIdentifier = DIFFERENT_APP_IDENTIFIER),
        )

        assertTrue(appOutcome is StoreV2Outcome.Failure)
        assertTrue((appOutcome as StoreV2Outcome.Failure).retainedForRetry)
        assertTrue(appMismatch.events.isEmpty())
        assertEquals(0, appMismatch.billingFactory.createCalls)
        assertEquals(1, appMismatch.stateStore.operationCount)
    }

    @Test
    fun resume_doesNotForwardPersistedCredentialsToTheStoreBackend() = runBlocking {
        val fixture = fixture()
        val pending = operation(
            productType = PendingStoreProductType.NON_CONSUMABLE,
        ).copy(customerToken = "expired-customer-token")
        fixture.stateStore.seed(pending)
        fixture.service.submitResults += response(StorePurchaseStatus.COMPLETED)

        val outcome = fixture.coordinator.resume(pending, context())

        assertTrue(outcome is StoreV2Outcome.Terminal)
        assertEquals(1, fixture.service.submissions.size)
        val request = fixture.service.submissions.single()
        assertEquals(pending.evidence.purchaseToken, request.purchase.token)
        assertFalse(request.javaClass.declaredFields.any { it.name == "apiKey" || it.name == "token" })
    }

    private fun fixture(
        consumeTimeoutMillis: Long = StoreV2Coordinator.DEFAULT_CONSUME_TIMEOUT_MILLIS,
        registerActiveAdapter: (StoreBillingAdapter) -> Boolean = { true },
    ): Fixture {
        val events = mutableListOf<String>()
        val clock = FakeClock()
        val service = FakeService(events)
        val stateStore = FakeStateStore()
        val billing = FakeBillingAdapter(events)
        val billingFactory = FakeBillingFactory(billing, market)
        val coordinator = StoreV2Coordinator(
            service = service,
            stateStore = stateStore,
            billingAdapterFactory = billingFactory,
            currentTimeMillis = { clock.now },
            sleep = clock::sleep,
            jitterMillis = { 0L },
            consumeTimeoutMillis = consumeTimeoutMillis,
            registerActiveAdapter = registerActiveAdapter,
        )
        return Fixture(
            coordinator = coordinator,
            service = service,
            stateStore = stateStore,
            billing = billing,
            billingFactory = billingFactory,
            clock = clock,
            events = events,
        )
    }

    private fun operation(
        phase: PendingStoreOperationPhase = PendingStoreOperationPhase.REGISTERING,
        deliveryId: Long? = null,
        deliveryAcknowledged: Boolean = false,
        consumeResult: PendingStoreConsumeResult? = null,
        productType: PendingStoreProductType = PendingStoreProductType.CONSUMABLE,
    ): PendingStoreOperation = PendingStoreOperation(
        id = "test-operation",
        operation = PendingStoreOperationType.PURCHASE,
        store = market.storeId,
        customerToken = "test-customer-token",
        customerIdentifierFingerprint = CUSTOMER_BINDING,
        apiKeyFingerprint = API_BINDING,
        appIdentifier = APP_IDENTIFIER,
        appId = APP_ID,
        productIdentifier = "test-product",
        offeringIdentifier = "test-offering",
        productType = productType,
        evidence = PendingStorePurchaseEvidence(
            purchaseToken = "test-purchase-token",
            orderId = "test-order",
            packageName = APP_IDENTIFIER,
            developerPayload = "test-payload",
            originalJson = "test-receipt",
            signature = "test-signature",
            purchaseTimeMillis = INITIAL_TIME,
        ),
        phase = phase,
        deliveryId = deliveryId,
        deliveryAcknowledged = deliveryAcknowledged,
        consumeResult = consumeResult,
        createdAtEpochMillis = INITIAL_TIME,
    )

    private fun context(
        customerBinding: String = CUSTOMER_BINDING,
        appIdentifier: String = APP_IDENTIFIER,
        apiKey: String = "test-api-key",
        customerToken: String = "test-customer-token",
        marketKey: String? = "test-market-key",
    ): StoreV2Context = StoreV2Context(
        apiKey = apiKey,
        customerToken = customerToken,
        apiKeyFingerprint = API_BINDING,
        customerIdentifierFingerprint = customerBinding,
        appIdentifier = appIdentifier,
        appId = APP_ID,
        country = "IR",
        appVersion = "1.0.0",
        forceVersion = null,
        marketKey = marketKey,
        market = market,
    )

    private fun knownSecrets(
        operation: PendingStoreOperation,
        context: StoreV2Context,
    ): List<String> = listOfNotNull(
        context.apiKey,
        context.customerToken,
        context.marketKey,
        operation.customerToken,
        operation.apiKeyFingerprint,
        operation.customerIdentifierFingerprint,
        operation.evidence.purchaseToken,
        operation.evidence.orderId,
        operation.evidence.developerPayload,
        operation.evidence.originalJson,
        operation.evidence.signature,
    ).distinct()

    private fun assertContainsNoSecret(
        error: com.inappify.sdk.InappifyError,
        secrets: List<String>,
    ) {
        val publicText = "${error.details} $error"
        secrets.forEach { secret ->
            assertFalse("Public error leaked secret: $secret", publicText.contains(secret))
        }
    }

    private fun response(
        status: StorePurchaseStatus,
        verificationRequestId: Long? = null,
        deliveryId: Long? = null,
        retryAfterSeconds: Long? = null,
        errorCode: String? = null,
        message: String? = null,
        httpStatus: Int = 200,
        transportRetryAfter: Long? = null,
    ): StoreServiceResult = StoreServiceResult.Response(
        statusCode = httpStatus,
        payload = StoreBackendResponse(
            status = true,
            state = StorePurchaseState(
                status = status,
                paymentId = null,
                eventId = null,
                deliveryId = deliveryId,
                verificationRequestId = verificationRequestId,
                retryAfter = retryAfterSeconds,
                errorCode = errorCode,
                message = message,
                alreadyProcessed = false,
            ),
            hasForceUpdate = false,
            forceVersion = null,
            errorCode = null,
            message = null,
        ),
        requestId = null,
        retryAfterSeconds = transportRetryAfter,
    )

    private class Fixture(
        val coordinator: StoreV2Coordinator,
        val service: FakeService,
        val stateStore: FakeStateStore,
        val billing: FakeBillingAdapter,
        val billingFactory: FakeBillingFactory,
        val clock: FakeClock,
        val events: MutableList<String>,
    )

    private class FakeClock {
        var now: Long = INITIAL_TIME
        val sleepDurations = mutableListOf<Long>()

        suspend fun sleep(durationMillis: Long) {
            sleepDurations += durationMillis
            now += durationMillis
        }
    }

    private class FakeStateStore : SessionStateStore {
        private val operations = linkedMapOf<String, PendingStoreOperation>()
        val checkpoints = mutableListOf<Checkpoint>()
        var removeCalls: Int = 0
            private set
        var removeSucceeds: Boolean = true

        val operationCount: Int
            get() = operations.size

        val latestPhase: PendingStoreOperationPhase
            get() = checkpoints.last().phase

        val latestAttempts: Int
            get() = checkpoints.last().attempts

        val latestRetryAt: Long?
            get() = checkpoints.last().nextRetryAtEpochMillis

        val latestDeliveryAcknowledged: Boolean
            get() = checkpoints.last().deliveryAcknowledged

        val latestConsumeResult: PendingStoreConsumeResult?
            get() = checkpoints.last().consumeResult

        fun seed(operation: PendingStoreOperation) {
            operations[operation.id] = operation
        }

        fun latestOperation(): PendingStoreOperation = operations.values.single()

        override suspend fun load(): PersistedSession? = null

        override suspend fun save(session: PersistedSession): Boolean = true

        override suspend fun clear(): Boolean {
            operations.clear()
            return true
        }

        override suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> =
            operations.values.toList()

        override suspend fun upsertPendingStoreOperation(
            operation: PendingStoreOperation,
        ): Boolean {
            operations[operation.id] = operation
            checkpoints += Checkpoint(
                phase = operation.phase,
                attempts = operation.attempts,
                nextRetryAtEpochMillis = operation.nextRetryAtEpochMillis,
                deliveryAcknowledged = operation.deliveryAcknowledged,
                consumeResult = operation.consumeResult,
            )
            return true
        }

        override suspend fun removePendingStoreOperation(operationId: String): Boolean {
            removeCalls += 1
            if (!removeSucceeds) return false
            operations.remove(operationId)
            return true
        }

        override suspend fun mutatePendingStoreOperations(
            mutation: (List<PendingStoreOperation>) -> List<PendingStoreOperation>,
        ): Boolean {
            val mutated = mutation(operations.values.toList())
            operations.clear()
            mutated.forEach { operations[it.id] = it }
            return true
        }

        override suspend fun clearPendingStoreOperations(): Boolean {
            operations.clear()
            return true
        }
    }

    private class Checkpoint(
        val phase: PendingStoreOperationPhase,
        val attempts: Int,
        val nextRetryAtEpochMillis: Long?,
        val deliveryAcknowledged: Boolean,
        val consumeResult: PendingStoreConsumeResult?,
    )

    private class FakeService(
        private val events: MutableList<String>,
    ) : StoreV2Backend {
        val submitResults = mutableListOf<StoreServiceResult>()
        val statusResults = mutableListOf<StoreServiceResult>()
        val deliveryResults = mutableListOf<StoreServiceResult>()
        val reportResults = mutableListOf<StoreServiceResult>()
        val reportedResults = mutableListOf<NetworkConsumeResult>()
        val submissions = mutableListOf<StorePurchaseApiRequest>()

        override suspend fun submitStorePurchase(
            request: StorePurchaseApiRequest,
        ): StoreServiceResult {
            events += EVENT_SUBMIT
            submissions += request
            return submitResults.takeNext(EVENT_SUBMIT)
        }

        override suspend fun getStoreVerificationStatus(
            request: StoreVerificationStatusApiRequest,
        ): StoreServiceResult {
            events += EVENT_STATUS
            return statusResults.takeNext(EVENT_STATUS)
        }

        override suspend fun markStoreDeliveryDelivered(
            request: StoreDeliveryApiRequest,
        ): StoreServiceResult {
            events += EVENT_DELIVER
            return deliveryResults.takeNext(EVENT_DELIVER)
        }

        override suspend fun reportStoreConsumeResult(
            request: StoreConsumeResultApiRequest,
        ): StoreServiceResult {
            events += EVENT_REPORT
            reportedResults += request.result
            return reportResults.takeNext(EVENT_REPORT)
        }

    }

    private class FakeBillingFactory(
        private val adapter: StoreBillingAdapter,
        private val expectedMarket: InappifyMarket,
    ) : StoreBillingAdapterFactory {
        var createCalls: Int = 0
            private set

        override fun create(
            market: InappifyMarket,
            marketKey: String?,
        ): StoreBillingAdapter {
            assertEquals(expectedMarket, market)
            createCalls += 1
            return adapter
        }
    }

    private class FakeBillingAdapter(
        private val events: MutableList<String>,
    ) : StoreBillingAdapter {
        val consumeResults = mutableListOf<StoreConsumeResult>()
        val consumeStarted = CompletableDeferred<Unit>()
        var blockConsume: Boolean = false
        var consumeCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set

        override suspend fun purchase(
            uiHost: StoreUiHost,
            request: StorePurchaseRequest,
        ): StoreBillingResult = error("Purchase is outside this coordinator test.")

        override suspend fun queryPurchases(
            productType: StoreProductType,
        ): StorePurchaseQueryResult = error("Purchase query is outside this coordinator test.")

        override suspend fun consume(purchase: StorePurchase): StoreConsumeResult {
            events += EVENT_CONSUME
            consumeCalls += 1
            consumeStarted.complete(Unit)
            if (blockConsume) awaitCancellation()
            return consumeResults.takeNext(EVENT_CONSUME)
        }

        override fun close() {
            closeCalls += 1
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun markets(): List<Array<InappifyMarket>> = listOf(arrayOf(InappifyMarket.BAZAAR), arrayOf(InappifyMarket.MYKET))

        const val INITIAL_TIME = 1_000_000L
        const val VERIFICATION_ID = 41L
        const val DELIVERY_ID = 73L
        const val APP_ID = 5L
        const val API_BINDING = "api-binding"
        const val CUSTOMER_BINDING = "customer-binding"
        const val DIFFERENT_BINDING = "different-binding"
        const val APP_IDENTIFIER = "com.example.test"
        const val DIFFERENT_APP_IDENTIFIER = "com.example.other"
        const val STORED_CUSTOMER_TOKEN = "stored-customer-token"
        const val REFRESHED_CUSTOMER_TOKEN = "refreshed-customer-token"
        const val EVENT_SUBMIT = "submit"
        const val EVENT_STATUS = "status"
        const val EVENT_DELIVER = "deliver"
        const val EVENT_CONSUME = "consume"
        const val EVENT_REPORT = "report"

        fun <T> MutableList<T>.takeNext(stage: String): T {
            check(isNotEmpty()) { "No fake response was configured for $stage." }
            return removeAt(0)
        }
    }
}
