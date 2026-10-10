package com.inappify.sdk.internal.storage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingStoreOperationCodecTest {

    @Test
    fun codec_roundTripsCompleteRecoveryRecord() {
        val operation = completeOperation()

        val restored = PendingStoreOperationCodec.decode(
            PendingStoreOperationCodec.encode(listOf(operation)),
        )

        assertEquals(listOf(operation), restored)
    }

    @Test
    fun codec_roundTripsUntypedLegacyInAppRecoveryRecord() {
        val operation = completeOperation().copy(
            productType = PendingStoreProductType.LEGACY_IN_APP,
            phase = PendingStoreOperationPhase.REGISTERING,
            deliveryId = null,
            deliveryAcknowledged = false,
            consumeResult = null,
            consumeErrorCode = null,
        )

        val restored = PendingStoreOperationCodec.decode(
            PendingStoreOperationCodec.encode(listOf(operation)),
        )

        assertEquals(listOf(operation), restored)
    }

    @Test
    fun stateCodec_roundTripsTombstoneWithoutChangingPendingOperations() {
        val operation = completeOperation()
        val tombstone = completeTombstone("token-fingerprint")

        val restored = PendingStoreOperationCodec.decodeState(
            PendingStoreOperationCodec.encodeState(
                PendingStoreRecoveryState(
                    operations = listOf(operation),
                    rejectedEvidenceTombstones = listOf(tombstone),
                ),
            ),
        )

        assertEquals(listOf(operation), restored.operations)
        assertEquals(listOf(tombstone), restored.rejectedEvidenceTombstones)
    }

    @Test
    fun stateDecoder_readsExistingQueuePayloadWithoutTombstoneMember() {
        val legacyPayload = JsonParser.parseString(
            PendingStoreOperationCodec.encode(listOf(completeOperation())),
        ).asJsonObject.apply {
            remove("rejectedEvidenceTombstones")
        }

        val restored = PendingStoreOperationCodec.decodeState(legacyPayload.toString())

        assertEquals(listOf(completeOperation()), restored.operations)
        assertTrue(restored.rejectedEvidenceTombstones.isEmpty())
    }

    @Test
    fun stateDecoder_readsLegacyTombstoneWithoutNewIdentityScopes() {
        val payload = JsonParser.parseString(
            PendingStoreOperationCodec.encodeState(
                PendingStoreRecoveryState(
                    rejectedEvidenceTombstones = listOf(
                        completeTombstone("legacy-token-fingerprint"),
                    ),
                ),
            ),
        ).asJsonObject
        payload.getAsJsonArray("rejectedEvidenceTombstones")[0]
            .asJsonObject
            .apply {
                remove("operationFingerprint")
                remove("countryFingerprint")
                remove("evidenceFingerprint")
            }

        val restored = PendingStoreOperationCodec.decodeState(payload.toString())
            .rejectedEvidenceTombstones
            .single()

        assertEquals(LEGACY_TOMBSTONE_FINGERPRINT, restored.operationFingerprint)
        assertEquals(LEGACY_TOMBSTONE_FINGERPRINT, restored.countryFingerprint)
        assertEquals(LEGACY_TOMBSTONE_FINGERPRINT, restored.evidenceFingerprint)
    }

    @Test
    fun stateDecoder_skipsMalformedTombstoneWithoutDroppingQueue() {
        val payload = JsonParser.parseString(
            PendingStoreOperationCodec.encodeState(
                PendingStoreRecoveryState(
                    operations = listOf(completeOperation()),
                    rejectedEvidenceTombstones = listOf(
                        completeTombstone("valid-token-fingerprint"),
                    ),
                ),
            ),
        ).asJsonObject.apply {
            getAsJsonArray("rejectedEvidenceTombstones").add(
                JsonObject().apply {
                    addProperty("purchaseTokenFingerprint", "incomplete")
                },
            )
        }

        val restored = PendingStoreOperationCodec.decodeState(payload.toString())

        assertEquals(listOf(completeOperation()), restored.operations)
        assertEquals(
            listOf(completeTombstone("valid-token-fingerprint")),
            restored.rejectedEvidenceTombstones,
        )
    }

    @Test
    fun decoder_treatsLegacyPayloadWithoutQueueAsEmpty() {
        assertTrue(PendingStoreOperationCodec.decode("{}").isEmpty())
        assertTrue(
            PendingStoreOperationCodec.decode(
                """{"schemaVersion":0,"unrelated":"retained-by-owner"}""",
            ).isEmpty(),
        )
    }

    @Test
    fun decoder_skipsMalformedRecordWithoutDroppingValidRecords() {
        val root = JsonParser.parseString(
            PendingStoreOperationCodec.encode(listOf(completeOperation())),
        ).asJsonObject
        root.getAsJsonArray("operations").add(
            JsonObject().apply {
                addProperty("id", "malformed-operation")
                addProperty("customerToken", "must-not-affect-valid-record")
            },
        )

        val restored = PendingStoreOperationCodec.decode(root.toString())

        assertEquals(listOf(completeOperation()), restored)
    }

    @Test
    fun decoder_doesNotReplayUnknownSchemaOrPhaseAsRegistering() {
        val unknownSchema = JsonParser.parseString(
            PendingStoreOperationCodec.encode(listOf(completeOperation())),
        ).asJsonObject.apply {
            addProperty("schemaVersion", 2)
        }
        val unknownPhase = JsonParser.parseString(
            PendingStoreOperationCodec.encode(listOf(completeOperation())),
        ).asJsonObject.apply {
            getAsJsonArray("operations")[0].asJsonObject
                .addProperty("phase", "FUTURE_DELIVERY_PHASE")
        }

        assertTrue(PendingStoreOperationCodec.decode(unknownSchema.toString()).isEmpty())
        assertTrue(PendingStoreOperationCodec.decode(unknownPhase.toString()).isEmpty())
    }

    @Test
    fun emptyQueue_roundTripsForExplicitPendingStateClear() {
        val restored = PendingStoreOperationCodec.decode(
            PendingStoreOperationCodec.encode(emptyList()),
        )

        assertTrue(restored.isEmpty())
    }

    @Test
    fun stringRepresentations_redactAllRawReplaySecrets() {
        val rendered = completeOperation().toString()

        listOf(
            "customer-token-secret",
            "customer-fingerprint-secret",
            "api-fingerprint-secret",
            "purchase-token-secret",
            "order-id-secret",
            "developer-payload-secret",
            "original-json-secret",
            "signature-secret",
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("productIdentifier=coins_100"))
    }

    private fun completeOperation(): PendingStoreOperation =
        PendingStoreOperation(
            id = "operation-1",
            operation = PendingStoreOperationType.PURCHASE,
            store = "bazar",
            customerToken = "customer-token-secret",
            customerIdentifierFingerprint = "customer-fingerprint-secret",
            apiKeyFingerprint = "api-fingerprint-secret",
            appIdentifier = "com.example.app",
            appId = 42L,
            productIdentifier = "coins_100",
            offeringIdentifier = "main-offering",
            productType = PendingStoreProductType.CONSUMABLE,
            evidence = PendingStorePurchaseEvidence(
                purchaseToken = "purchase-token-secret",
                orderId = "order-id-secret",
                packageName = "com.example.app",
                developerPayload = "developer-payload-secret",
                originalJson = "original-json-secret",
                signature = "signature-secret",
                purchaseTimeMillis = 1_777_777_777L,
            ),
            phase = PendingStoreOperationPhase.CONSUME_RESULT_REPORTING,
            verificationRequestId = 731L,
            deliveryId = 991L,
            attempts = 4,
            nextRetryAtEpochMillis = 2_000L,
            deliveryAcknowledged = true,
            consumeResult = PendingStoreConsumeResult.FAILED,
            consumeErrorCode = "STORE_TEMPORARILY_UNAVAILABLE",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_500L,
        )

    @Test fun codecRoundTripsPayloadlessRecoveryWithoutInventingAnOffering() {
        val original = completeOperation()
        val operation = original.copy(operation = PendingStoreOperationType.RECOVER_SUBSCRIPTION,
            productType = PendingStoreProductType.SUBSCRIPTION, offeringIdentifier = null,
            phase = PendingStoreOperationPhase.VERIFYING, deliveryId = null, deliveryAcknowledged = false,
            consumeResult = null, consumeErrorCode = null,
            evidence = original.evidence.copy(developerPayload = ""))
        val decoded = PendingStoreOperationCodec.decode(PendingStoreOperationCodec.encode(listOf(operation))).single()
        assertEquals(operation, decoded)
        assertEquals(null, decoded.offeringIdentifier)
        assertEquals("", decoded.evidence.developerPayload)
    }

    @Test fun recoveryQueueCannotContainAnotherMarketOrAProvidedBinding() {
        val original = completeOperation()
        val operation = original.copy(operation = PendingStoreOperationType.RECOVER_SUBSCRIPTION,
            productType = PendingStoreProductType.SUBSCRIPTION, offeringIdentifier = null,
            phase = PendingStoreOperationPhase.REGISTERING, verificationRequestId = null,
            deliveryId = null, deliveryAcknowledged = false, consumeResult = null, consumeErrorCode = null,
            evidence = original.evidence.copy(developerPayload = ""))
        for (invalid in listOf<() -> PendingStoreOperation>(
            { operation.copy(store = "myket") },
            { operation.copy(offeringIdentifier = "guessed-offering") },
            { operation.copy(evidence = operation.evidence.copy(developerPayload = "{}")) },
            { operation.copy(evidence = operation.evidence.copy(signature = "")) },
            { operation.copy(phase = PendingStoreOperationPhase.CONSUME_REQUIRED) },
        )) {
            try { invalid(); org.junit.Assert.fail("Unsafe recovery operation was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    private fun completeTombstone(
        purchaseTokenFingerprint: String,
    ): RejectedStoreEvidenceTombstone = RejectedStoreEvidenceTombstone(
        purchaseTokenFingerprint = purchaseTokenFingerprint,
        apiKeyFingerprint = "api-key-fingerprint",
        customerIdentifierFingerprint = "customer-fingerprint",
        appFingerprint = "app-fingerprint",
        productIdentifierFingerprint = "product-fingerprint",
        productTypeFingerprint = "type-fingerprint",
        offeringIdentifierFingerprint = "offering-fingerprint",
        appVersionFingerprint = "app-version-fingerprint",
        forceVersionFingerprint = "force-version-fingerprint",
        operationFingerprint = "operation-fingerprint",
        countryFingerprint = "country-fingerprint",
        evidenceFingerprint = "evidence-fingerprint",
    )
}
