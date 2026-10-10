package com.inappify.sdk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inappify.sdk.internal.storage.EncryptedSessionStateStore
import com.inappify.sdk.internal.storage.PendingStoreOperation
import com.inappify.sdk.internal.storage.PendingStoreOperationPhase
import com.inappify.sdk.internal.storage.PendingStoreOperationType
import com.inappify.sdk.internal.storage.PendingStoreProductType
import com.inappify.sdk.internal.storage.PendingStorePurchaseEvidence
import com.inappify.sdk.internal.storage.PersistedSession
import com.inappify.sdk.internal.storage.RejectedStoreEvidenceTombstone
import com.inappify.sdk.internal.storage.SessionSaveResult
import com.inappify.sdk.internal.storage.SessionStorageStage
import com.inappify.sdk.internal.storage.blockCacheRestore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android Keystore and filesystem implementation. */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStateStoreInstrumentedTest {

    private lateinit var context: Context
    private lateinit var store: EncryptedSessionStateStore

    @Before
    fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = EncryptedSessionStateStore.create(context)
        store.clear()
        store.clearPendingStoreOperations()
        store.clearRejectedStoreEvidenceTombstones()
        storageLockFile().delete()
        migrationMarker().delete()
        legacyPreferences().edit().clear().commit()
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
        store.clearPendingStoreOperations()
        store.clearRejectedStoreEvidenceTombstones()
        storageLockFile().delete()
        migrationMarker().delete()
        legacyPreferences().edit().clear().commit()
    }

    @Test
    fun encryptedSession_roundTripsWithoutPlaintextSecrets() = runBlocking {
        val session = persistedSession()

        assertTrue(store.save(session))
        val restored = store.load()

        assertNotNull(restored)
        assertEquals(session.token, restored?.token)
        assertEquals(
            session.appUserIdentifier,
            restored?.appUserIdentifier,
        )
        assertEquals(session.apiKeyFingerprint, restored?.apiKeyFingerprint)
        assertEquals(
            session.cacheContextFingerprint,
            restored?.cacheContextFingerprint,
        )
        assertEquals(session.purchaseRecoveryId, restored?.purchaseRecoveryId)
        val encrypted = sessionFile().readBytes()
        assertFalse(encrypted.containsSubsequence(session.token!!.encodeToByteArray()))
        assertFalse(
            encrypted.containsSubsequence(
                session.appUserIdentifier!!.encodeToByteArray(),
            ),
        )
        assertFalse(
            encrypted.containsSubsequence(
                session.purchaseRecoveryId!!.encodeToByteArray(),
            ),
        )
    }

    @Test
    fun offlineRejectionMarkerSurvivesEncryptedStoreRecreation() = runBlocking {
        val original = persistedSession()
        assertTrue(store.save(original.blockCacheRestore()))
        val recreated = EncryptedSessionStateStore.create(context)
        val restored = recreated.loadForCacheRestore()
        assertTrue(restored?.cacheRestoreBlocked == true)
        assertEquals(original.token, restored?.token)
        assertEquals(original.appUserIdentifier, restored?.appUserIdentifier)
        assertEquals(original.purchaseRecoveryId, restored?.purchaseRecoveryId)
        assertTrue(recreated.save(original))
        assertFalse(recreated.loadForCacheRestore()!!.cacheRestoreBlocked)
    }

    @Test
    fun offlineReadDoesNotMigrateLegacyCredentialsOrCleanPreferences() = runBlocking {
        assertTrue(legacyPreferences().edit().putString("flutter.token", "legacy-offline-token")
            .putString("flutter.appUserIdentifier", "legacy-offline-customer").commit())
        assertNull(store.loadForCacheRestore())
        assertEquals("legacy-offline-token", legacyPreferences().getString("flutter.token", null))
        assertFalse(migrationMarker().exists())
    }

    @Test
    fun repeatedSessionWrites_useProviderGeneratedDistinctIvs() = runBlocking {
        val session = persistedSession("provider-iv")

        assertTrue(store.save(session))
        val firstCiphertext = sessionFile().readBytes()
        assertTrue(store.save(session))
        val secondCiphertext = sessionFile().readBytes()

        assertFalse(firstCiphertext.contentEquals(secondCiphertext))
        assertEquals(session.token, store.load()?.token)
    }

    @Test
    fun oversizedSession_reportsSafeSizeDiagnosticsWithoutWriting() = runBlocking {
        val oversized = PersistedSession(
            token = "customer-token",
            appUserIdentifier = "customer-identifier",
            forceVersion = 1,
            appId = 3,
            storeInfo = "bazar",
            apiKeyFingerprint = "api-key-fingerprint",
            customerInfoJson = "x".repeat(4 * 1024 * 1024),
            offeringsJson = "{\"offerings\":[]}",
            customerInfoUpdatedAt = "2026-09-01T12:00:00.000Z",
        )

        val result = store.saveWithDiagnostics(oversized) as SessionSaveResult.Failure
        val diagnostic = result.diagnostic

        assertEquals(SessionStorageStage.SIZE_CHECK, diagnostic.stage)
        assertEquals("java.lang.IllegalArgumentException", diagnostic.causeType)
        assertTrue(requireNotNull(diagnostic.plainTextBytes) > 4 * 1024 * 1024)
        assertNull(diagnostic.encryptedBytes)
        assertEquals(false, diagnostic.sessionFileExists)
        assertEquals(false, diagnostic.keyResetAttempted)
        assertFalse(sessionFile().exists())
        assertFalse(diagnostic.toSafeDetails().toString().contains("customer-token"))
    }

    @Test
    fun corruptedCiphertext_isDiscardedSafely() = runBlocking {
        assertTrue(store.save(persistedSession()))
        sessionFile().writeBytes(byteArrayOf(1, 2, 3, 4))

        val restored = store.load()

        assertNull(restored)
        assertFalse(sessionFile().exists())
    }

    @Test
    fun independentStores_serializeConcurrentWrites() = runBlocking {
        val secondStore = EncryptedSessionStateStore.create(context)
        val firstSession = persistedSession("first")
        val secondSession = persistedSession("second")
        val start = CompletableDeferred<Unit>()

        val writes = listOf(
            async(Dispatchers.Default) {
                start.await()
                store.save(firstSession)
            },
            async(Dispatchers.Default) {
                start.await()
                secondStore.save(secondSession)
            },
        )
        start.complete(Unit)

        assertTrue(writes.awaitAll().all { it })
        val restoredToken = store.load()?.token
        assertTrue(
            restoredToken == firstSession.token ||
                restoredToken == secondSession.token,
        )
    }

    @Test
    fun encryptedPendingQueue_roundTripsWithoutPlaintextEvidence() = runBlocking {
        val operation = pendingOperation("round-trip")

        assertTrue(store.upsertPendingStoreOperation(operation))
        assertEquals(listOf(operation), store.loadPendingStoreOperations())

        val encrypted = pendingOperationsFile().readBytes()
        assertFalse(
            encrypted.containsSubsequence(
                operation.evidence.purchaseToken.encodeToByteArray(),
            ),
        )
        assertFalse(
            encrypted.containsSubsequence(
                requireNotNull(operation.evidence.signature).encodeToByteArray(),
            ),
        )
    }

    @Test
    fun interruptedFirstPendingWrite_promotesCompleteStagingFile() = runBlocking {
        val operation = pendingOperation("interrupted-first-write")
        assertTrue(store.upsertPendingStoreOperation(operation))
        val base = pendingOperationsFile()
        val staged = File("${base.path}.new")
        assertTrue(base.renameTo(staged))
        assertFalse(base.exists())

        val restored = store.loadPendingStoreRecoveryState()

        assertNotNull(restored)
        assertEquals(listOf(operation), restored?.operations)
        assertTrue(base.exists())
        assertFalse(staged.exists())
    }

    @Test
    fun sessionClear_doesNotDeleteRecoverablePendingPurchase() = runBlocking {
        val operation = pendingOperation("survives-logout")
        assertTrue(store.save(persistedSession()))
        assertTrue(store.upsertPendingStoreOperation(operation))

        assertTrue(store.clear())

        assertNull(store.load())
        assertEquals(listOf(operation), store.loadPendingStoreOperations())
    }

    @Test
    fun tombstone_survivesSessionClearAndPendingQueueMutations() = runBlocking {
        val operation = pendingOperation("preserved")
        val tombstone = rejectedTombstone("preserved")
        assertTrue(store.save(persistedSession()))
        assertTrue(store.upsertPendingStoreOperation(operation))

        assertTrue(store.upsertRejectedStoreEvidenceTombstone(tombstone))
        assertEquals(listOf(operation), store.loadPendingStoreOperations())
        assertEquals(
            listOf(tombstone),
            store.loadRejectedStoreEvidenceTombstones(),
        )

        assertTrue(store.clear())
        assertTrue(store.clearPendingStoreOperations())

        assertNull(store.load())
        assertTrue(store.loadPendingStoreOperations().isEmpty())
        assertEquals(
            listOf(tombstone),
            store.loadRejectedStoreEvidenceTombstones(),
        )

        assertTrue(store.upsertPendingStoreOperation(operation))
        assertTrue(store.clearRejectedStoreEvidenceTombstones())
        assertEquals(listOf(operation), store.loadPendingStoreOperations())
        assertTrue(store.loadRejectedStoreEvidenceTombstones().isEmpty())
    }

    @Test
    fun permanentRejectionFinalization_atomicallyReplacesPendingWithTombstone() =
        runBlocking {
            val operation = pendingOperation("atomic-finalization")
            val tombstone = rejectedTombstone("atomic-finalization")
            assertTrue(store.upsertPendingStoreOperation(operation))

            assertTrue(
                store.finalizePendingStoreOperationWithTombstone(
                    operationId = operation.id,
                    tombstone = tombstone,
                ),
            )

            val recoveryState = store.loadPendingStoreRecoveryState()
            assertNotNull(recoveryState)
            assertTrue(requireNotNull(recoveryState).operations.isEmpty())
            assertEquals(
                listOf(tombstone),
                recoveryState.rejectedEvidenceTombstones,
            )
        }

    @Test
    fun tombstones_areEncryptedAndBoundedWithoutDroppingPendingQueue() = runBlocking {
        val operation = pendingOperation("bounded")
        assertTrue(store.upsertPendingStoreOperation(operation))
        repeat(257) { index ->
            assertTrue(
                store.upsertRejectedStoreEvidenceTombstone(
                    rejectedTombstone(index.toString()),
                ),
            )
        }

        val restored = store.loadRejectedStoreEvidenceTombstones()

        assertEquals(256, restored.size)
        assertFalse(restored.contains(rejectedTombstone("0")))
        assertTrue(restored.contains(rejectedTombstone("256")))
        assertEquals(listOf(operation), store.loadPendingStoreOperations())
        assertFalse(
            pendingOperationsFile().readBytes().containsSubsequence(
                rejectedTombstone("256").purchaseTokenFingerprint.encodeToByteArray(),
            ),
        )
    }

    @Test
    fun corruptedPendingCiphertext_isDiscardedWithoutTouchingSession() = runBlocking {
        assertTrue(store.save(persistedSession()))
        assertTrue(store.upsertPendingStoreOperation(pendingOperation("corrupt")))
        pendingOperationsFile().writeBytes(byteArrayOf(1, 2, 3, 4))

        assertTrue(store.loadPendingStoreOperations().isEmpty())

        assertFalse(pendingOperationsFile().exists())
        val restoredSession = store.load()
        assertEquals(persistedSession().token, restoredSession?.token)
        assertEquals(
            persistedSession().appUserIdentifier,
            restoredSession?.appUserIdentifier,
        )
    }

    @Test
    fun independentStores_atomicallyMergeConcurrentPendingPurchases() = runBlocking {
        val secondStore = EncryptedSessionStateStore.create(context)
        val start = CompletableDeferred<Unit>()
        val operations = listOf(
            pendingOperation("first"),
            pendingOperation("second"),
        )
        val writes = operations.mapIndexed { index, operation ->
            async(Dispatchers.Default) {
                start.await()
                if (index == 0) {
                    store.upsertPendingStoreOperation(operation)
                } else {
                    secondStore.upsertPendingStoreOperation(operation)
                }
            }
        }
        start.complete(Unit)

        assertTrue(writes.awaitAll().all { it })
        assertEquals(operations.toSet(), store.loadPendingStoreOperations().toSet())
    }

    @Test
    fun flutterMigration_encryptsOwnedValuesAndPreservesUnrelatedValue() =
        runBlocking {
            val preferences = legacyPreferences()
            assertTrue(
                preferences.edit()
                    .putString("flutter.token", "legacy-token")
                    .putString(
                        "flutter.appUserIdentifier",
                        "legacy-customer",
                    )
                    .putLong("flutter.forceVersion", 2_147_483_648L)
                    .putString("flutter.unrelated", "keep-me")
                    .commit(),
            )

            val restored = store.load()

            assertEquals("legacy-token", restored?.token)
            assertEquals("legacy-customer", restored?.appUserIdentifier)
            assertFalse(preferences.contains("flutter.token"))
            assertFalse(preferences.contains("flutter.appUserIdentifier"))
            assertEquals("keep-me", preferences.getString("flutter.unrelated", null))
            assertTrue(sessionFile().exists())
        }

    private fun persistedSession(suffix: String = "default"): PersistedSession = PersistedSession(
        token = "customer-token-$suffix",
        appUserIdentifier = "customer-identifier-$suffix",
        forceVersion = 2_147_483_648L,
        appId = 4_294_967_296L,
        storeInfo = "bazar",
        apiKeyFingerprint = "api-key-fingerprint",
        cacheContextFingerprint = "cache-context-fingerprint",
        purchaseRecoveryId = "purchase-recovery-id-$suffix",
        customerInfoJson =
            "{\"originalAppUserId\":\"customer-identifier-$suffix\"}",
        offeringsJson = "{\"offerings\":[]}",
        customerInfoUpdatedAt = "2026-08-26T12:00:00.000Z",
    )

    private fun pendingOperation(suffix: String): PendingStoreOperation =
        PendingStoreOperation(
            id = "operation-$suffix",
            operation = PendingStoreOperationType.PURCHASE,
            store = "bazar",
            customerToken = "customer-token-$suffix",
            customerIdentifierFingerprint = "customer-binding-$suffix",
            apiKeyFingerprint = "api-binding-$suffix",
            appIdentifier = context.packageName,
            appId = 42L,
            productIdentifier = "product-$suffix",
            offeringIdentifier = "main",
            productType = PendingStoreProductType.CONSUMABLE,
            evidence = PendingStorePurchaseEvidence(
                purchaseToken = "purchase-token-$suffix",
                orderId = "order-$suffix",
                packageName = context.packageName,
                developerPayload = "payload-$suffix",
                originalJson = "json-$suffix",
                signature = "signature-$suffix",
                purchaseTimeMillis = 1_725_000_000_000L,
            ),
            phase = PendingStoreOperationPhase.REGISTERING,
            createdAtEpochMillis = 1_725_000_000_000L,
        )

    private fun rejectedTombstone(suffix: String): RejectedStoreEvidenceTombstone =
        RejectedStoreEvidenceTombstone(
            purchaseTokenFingerprint = "purchase-token-fingerprint-$suffix",
            apiKeyFingerprint = "api-key-fingerprint",
            customerIdentifierFingerprint = "customer-fingerprint",
            appFingerprint = "app-fingerprint",
            productIdentifierFingerprint = "product-fingerprint",
            productTypeFingerprint = "type-fingerprint",
            offeringIdentifierFingerprint = "offering-fingerprint",
            appVersionFingerprint = "app-version-fingerprint",
            forceVersionFingerprint = "force-version-fingerprint",
        )

    private fun sessionFile(): File =
        File(context.noBackupFilesDir, "inappify_session_v1.bin")

    private fun pendingOperationsFile(): File =
        File(context.noBackupFilesDir, "inappify_pending_store_operations_v2.bin")

    private fun storageLockFile(): File =
        File(context.noBackupFilesDir, "inappify_session_v1.lock")

    private fun migrationMarker(): File = File(
        context.noBackupFilesDir,
        "inappify_flutter_session_migration_v1",
    )

    private fun legacyPreferences() = context.getSharedPreferences(
        "FlutterSharedPreferences",
        Context.MODE_PRIVATE,
    )
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return (0..size - needle.size).any { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
}
