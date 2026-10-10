@file:Suppress("DEPRECATION")

package com.inappify.sdk.internal.storage

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Session persistence backed by AES-GCM and Android Keystore.
 *
 * File locking serializes access between client instances and application
 * processes. Values from previous Inappify SDK installations are removed only
 * after an encrypted write succeeds.
 */
internal class EncryptedSessionStateStore private constructor(
    private val applicationContext: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val goV2: Boolean = false,
) : SessionStateStore {

    private val sessionFile = AtomicFile(
        File(applicationContext.noBackupFilesDir, if (goV2) "inappify_go_session_v2.bin" else SESSION_FILE_NAME),
    )
    private val pendingStoreOperationsFile = AtomicFile(
        File(applicationContext.noBackupFilesDir, PENDING_OPERATIONS_FILE_NAME),
    )
    private val migrationMarker = File(
        applicationContext.noBackupFilesDir,
        MIGRATION_MARKER_FILE_NAME,
    )
    private val storageLockFile = File(
        applicationContext.noBackupFilesDir,
        STORAGE_LOCK_FILE_NAME,
    )
    private val sessionCipher = SessionCipher(
        context = applicationContext,
        keyScope = if (goV2) CipherKeyScope.GO_V2 else CipherKeyScope.SESSION,
    )
    private val pendingOperationsCipher = SessionCipher(
        context = applicationContext,
        keyScope = CipherKeyScope.PENDING_STORE_OPERATIONS,
    )

    override suspend fun load(): PersistedSession? = withStorageLock {
        // Go state is independently encrypted. Never migrate or clean up V1 credentials here.
        if (goV2) {
            if (!sessionFile.promoteInterruptedFirstWrite()) return@withStorageLock null
            return@withStorageLock if (sessionFile.hasRecoverableData()) readSession() else null
        }
        if (!sessionFile.promoteInterruptedFirstWrite()) {
            return@withStorageLock null
        }
        val restored = if (sessionFile.hasRecoverableData()) {
            readSession()
        } else {
            null
        }
        if (restored != null) {
            completeLegacyCleanupIfNeeded()
            restored
        } else {
            if (sessionFile.hasRecoverableData()) deleteCorruptedSession()
            migrateLegacySession()
        }
    }

    override suspend fun save(session: PersistedSession): Boolean =
        saveWithDiagnostics(session) is SessionSaveResult.Success

    override suspend fun loadForCacheRestore(): PersistedSession? = withStorageLock {
        // AtomicFile recovery is allowed, but offline bootstrap must not migrate,
        // delete, or rewrite credentials and must never touch purchase recovery.
        if (!sessionFile.promoteInterruptedFirstWrite()) return@withStorageLock null
        if (sessionFile.hasRecoverableData()) readSession() else null
    }

    override suspend fun saveWithDiagnostics(
        session: PersistedSession,
    ): SessionSaveResult = try {
        withStorageLock {
            val result = writeSessionWithDiagnostics(session)
            if (result is SessionSaveResult.Success && !goV2) {
                completeLegacyCleanupIfNeeded()
            }
            result
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        SessionSaveResult.Failure(
            storageFailure(
                stage = SessionStorageStage.LOCK,
                exception = exception,
                plainTextBytes = null,
                encryptedBytes = null,
                sessionFileExists = sessionFile.hasRecoverableDataSafely(),
                keyResetAttempted = false,
            ),
        )
    }

    override suspend fun clear(): Boolean = withStorageLock {
        try {
            sessionFile.delete()
        } catch (_: Exception) {
            return@withStorageLock false
        }
        !sessionFile.baseFile.exists()
    }

    override suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> =
        withStorageLockOrFallback(emptyList()) {
            readPendingStoreRecoveryState()?.operations.orEmpty()
        }

    override suspend fun loadPendingStoreRecoveryState(): PendingStoreRecoveryState? =
        withStorageLockOrFallback(null) {
            readPendingStoreRecoveryState()
        }

    override suspend fun upsertPendingStoreOperation(
        operation: PendingStoreOperation,
    ): Boolean = withStorageLockOrFallback(false) {
        val updated = LinkedHashMap<String, PendingStoreOperation>()
        val current = readPendingStoreRecoveryState()
            ?: return@withStorageLockOrFallback false
        current.operations.forEach { pending ->
            updated[pending.id] = pending
        }
        updated[operation.id] = operation
        writePendingStoreRecoveryState(
            current.copy(operations = updated.values.toList()),
        )
    }

    override suspend fun removePendingStoreOperation(operationId: String): Boolean =
        withStorageLockOrFallback(false) {
            if (operationId.isBlank()) return@withStorageLockOrFallback false
            val current = readPendingStoreRecoveryState()
                ?: return@withStorageLockOrFallback false
            if (current.operations.none { operation -> operation.id == operationId }) {
                return@withStorageLockOrFallback true
            }
            writePendingStoreRecoveryState(
                current.copy(
                    operations = current.operations.filterNot { operation ->
                        operation.id == operationId
                    },
                ),
            )
        }

    override suspend fun mutatePendingStoreOperations(
        mutation: (List<PendingStoreOperation>) -> List<PendingStoreOperation>,
    ): Boolean = withStorageLockOrFallback(false) {
        try {
            val current = readPendingStoreRecoveryState()
                ?: return@withStorageLockOrFallback false
            writePendingStoreRecoveryState(
                current.copy(
                    operations = mutation(current.operations.toList()).toList(),
                ),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun clearPendingStoreOperations(): Boolean =
        withStorageLockOrFallback(false) {
            val current = readPendingStoreRecoveryState()
                ?: return@withStorageLockOrFallback false
            writePendingStoreRecoveryState(current.copy(operations = emptyList()))
        }

    override suspend fun loadRejectedStoreEvidenceTombstones():
        List<RejectedStoreEvidenceTombstone> = withStorageLockOrFallback(emptyList()) {
            readPendingStoreRecoveryState()?.rejectedEvidenceTombstones.orEmpty()
        }

    override suspend fun upsertRejectedStoreEvidenceTombstone(
        tombstone: RejectedStoreEvidenceTombstone,
    ): Boolean = withStorageLockOrFallback(false) {
        val current = readPendingStoreRecoveryState()
            ?: return@withStorageLockOrFallback false
        val bounded = current.rejectedEvidenceTombstones
            .filterNot { existing -> existing == tombstone }
            .plus(tombstone)
            .takeLast(PendingStoreOperationCodec.MAX_TOMBSTONE_COUNT)
        writePendingStoreRecoveryState(
            current.copy(rejectedEvidenceTombstones = bounded),
        )
    }

    override suspend fun finalizePendingStoreOperationWithTombstone(
        operationId: String,
        tombstone: RejectedStoreEvidenceTombstone,
    ): Boolean = withStorageLockOrFallback(false) {
        if (operationId.isBlank()) return@withStorageLockOrFallback false
        val current = readPendingStoreRecoveryState()
            ?: return@withStorageLockOrFallback false
        if (current.operations.none { operation -> operation.id == operationId }) {
            return@withStorageLockOrFallback false
        }
        val boundedTombstones = current.rejectedEvidenceTombstones
            .filterNot { existing -> existing == tombstone }
            .plus(tombstone)
            .takeLast(PendingStoreOperationCodec.MAX_TOMBSTONE_COUNT)
        writePendingStoreRecoveryState(
            current.copy(
                operations = current.operations.filterNot { operation ->
                    operation.id == operationId
                },
                rejectedEvidenceTombstones = boundedTombstones,
            ),
        )
    }

    override suspend fun clearRejectedStoreEvidenceTombstones(): Boolean =
        withStorageLockOrFallback(false) {
            val current = readPendingStoreRecoveryState()
                ?: return@withStorageLockOrFallback false
            writePendingStoreRecoveryState(
                current.copy(rejectedEvidenceTombstones = emptyList()),
            )
        }

    private suspend fun <T> withStorageLock(block: () -> T): T =
        withContext(ioDispatcher) {
            RandomAccessFile(storageLockFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.use { channel ->
                    val lock = acquireStorageLock(channel)
                    try {
                        block()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

    private suspend fun <T> withStorageLockOrFallback(
        fallback: T,
        block: () -> T,
    ): T = try {
        withStorageLock(block)
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        fallback
    }

    private suspend fun acquireStorageLock(channel: FileChannel): FileLock {
        while (true) {
            currentCoroutineContext().ensureActive()
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock != null) return lock
            delay(STORAGE_LOCK_RETRY_MILLIS)
        }
    }

    private fun readSession(): PersistedSession? = try {
        val encrypted = sessionFile.openRead().use {
            it.readBytesWithLimit(MAX_SESSION_FILE_BYTES)
        }
        decode(sessionCipher.decrypt(encrypted).decodeToString())
    } catch (_: Exception) {
        null
    }

    private fun writeSession(session: PersistedSession): Boolean =
        writeSessionWithDiagnostics(session) is SessionSaveResult.Success

    private fun writeSessionWithDiagnostics(
        session: PersistedSession,
    ): SessionSaveResult {
        var plainTextBytes: Int? = null
        var encryptedBytes: Int? = null
        var keyResetAttempted = false

        val plainText = try {
            encode(session).encodeToByteArray().also { plainTextBytes = it.size }
        } catch (exception: Exception) {
            return sessionSaveFailure(
                stage = SessionStorageStage.ENCODE,
                exception = exception,
                plainTextBytes = plainTextBytes,
                encryptedBytes = encryptedBytes,
                keyResetAttempted = keyResetAttempted,
            )
        }
        if (plainText.size > MAX_SESSION_PLAINTEXT_BYTES) {
            return sessionSaveFailure(
                stage = SessionStorageStage.SIZE_CHECK,
                exception = IllegalArgumentException(
                    "Session payload exceeds the $MAX_SESSION_PLAINTEXT_BYTES-byte limit.",
                ),
                plainTextBytes = plainText.size,
                encryptedBytes = null,
                keyResetAttempted = keyResetAttempted,
            )
        }

        val allowKeyReset = !sessionFile.hasRecoverableData()
        val encrypted = try {
            sessionCipher.encrypt(
                plainText = plainText,
                allowKeyReset = allowKeyReset,
                onKeyResetAttempted = { keyResetAttempted = true },
            ).also { encryptedBytes = it.size }
        } catch (exception: Exception) {
            return sessionSaveFailure(
                stage = SessionStorageStage.ENCRYPT,
                exception = exception,
                plainTextBytes = plainText.size,
                encryptedBytes = encryptedBytes,
                keyResetAttempted = keyResetAttempted,
            )
        }
        if (encrypted.size > MAX_SESSION_FILE_BYTES) {
            return sessionSaveFailure(
                stage = SessionStorageStage.SIZE_CHECK,
                exception = IllegalArgumentException(
                    "Encrypted session exceeds the $MAX_SESSION_FILE_BYTES-byte limit.",
                ),
                plainTextBytes = plainText.size,
                encryptedBytes = encrypted.size,
                keyResetAttempted = keyResetAttempted,
            )
        }

        val output = try {
            sessionFile.startWrite()
        } catch (exception: Exception) {
            return sessionSaveFailure(
                stage = SessionStorageStage.ATOMIC_WRITE,
                exception = exception,
                plainTextBytes = plainText.size,
                encryptedBytes = encrypted.size,
                keyResetAttempted = keyResetAttempted,
            )
        }
        fun failAtomicWrite(
            stage: SessionStorageStage,
            exception: Exception,
        ): SessionSaveResult.Failure {
            try {
                sessionFile.failWrite(output)
            } catch (_: Exception) {
                // Preserve the original failure as the actionable diagnostic.
            }
            return sessionSaveFailure(
                stage = stage,
                exception = exception,
                plainTextBytes = plainText.size,
                encryptedBytes = encrypted.size,
                keyResetAttempted = keyResetAttempted,
            )
        }
        try {
            output.write(encrypted)
        } catch (exception: Exception) {
            return failAtomicWrite(SessionStorageStage.ATOMIC_WRITE, exception)
        }
        try {
            output.fd.sync()
        } catch (exception: Exception) {
            return failAtomicWrite(SessionStorageStage.FSYNC, exception)
        }
        try {
            sessionFile.finishWrite(output)
        } catch (exception: Exception) {
            return failAtomicWrite(SessionStorageStage.ATOMIC_WRITE, exception)
        }
        return SessionSaveResult.Success
    }

    private fun sessionSaveFailure(
        stage: SessionStorageStage,
        exception: Exception,
        plainTextBytes: Int?,
        encryptedBytes: Int?,
        keyResetAttempted: Boolean,
    ): SessionSaveResult.Failure = SessionSaveResult.Failure(
        storageFailure(
            stage = stage,
            exception = exception,
            plainTextBytes = plainTextBytes,
            encryptedBytes = encryptedBytes,
            sessionFileExists = sessionFile.hasRecoverableDataSafely(),
            keyResetAttempted = keyResetAttempted,
        ),
    )

    private fun readPendingStoreRecoveryState(): PendingStoreRecoveryState? {
        if (!pendingStoreOperationsFile.promoteInterruptedFirstWrite()) {
            return null
        }
        if (!pendingStoreOperationsFile.hasRecoverableData()) {
            return PendingStoreRecoveryState()
        }
        val plainText = try {
            val encrypted = pendingStoreOperationsFile.openRead().use {
                val storedSize = pendingStoreOperationsFile.baseFile.length()
                if (storedSize <= 0L || storedSize > MAX_PENDING_OPERATIONS_FILE_BYTES) {
                    deletePendingStoreOperations()
                    return PendingStoreRecoveryState()
                }
                it.readBytesWithLimit(MAX_PENDING_OPERATIONS_FILE_BYTES)
            }
            if (!pendingOperationsCipher.hasValidEnvelope(encrypted)) {
                deletePendingStoreOperations()
                return PendingStoreRecoveryState()
            }
            pendingOperationsCipher.decrypt(encrypted).decodeToString()
        } catch (_: AEADBadTagException) {
            deletePendingStoreOperations()
            return PendingStoreRecoveryState()
        } catch (_: Exception) {
            // Preserve evidence across transient file or Keystore errors.
            return null
        }
        return try {
            PendingStoreOperationCodec.decodeState(plainText)
        } catch (_: Exception) {
            // Successfully decrypted but structurally invalid data is corrupt.
            deletePendingStoreOperations()
            PendingStoreRecoveryState()
        }
    }

    private fun writePendingStoreRecoveryState(
        recoveryState: PendingStoreRecoveryState,
    ): Boolean {
        if (
            recoveryState.operations.isEmpty() &&
            recoveryState.rejectedEvidenceTombstones.isEmpty()
        ) {
            return deletePendingStoreOperations()
        }
        return try {
            val plainText = PendingStoreOperationCodec.encodeState(recoveryState)
                .encodeToByteArray()
            require(plainText.size <= MAX_PENDING_OPERATIONS_PLAINTEXT_BYTES) {
                "Pending operation payload exceeds the storage limit."
            }
            val encrypted = pendingOperationsCipher.encrypt(
                plainText = plainText,
                allowKeyReset = !pendingStoreOperationsFile.hasRecoverableData(),
            )
            require(encrypted.size <= MAX_PENDING_OPERATIONS_FILE_BYTES) {
                "Encrypted pending operation payload exceeds the storage limit."
            }
            val output = pendingStoreOperationsFile.startWrite()
            try {
                output.write(encrypted)
                output.fd.sync()
                pendingStoreOperationsFile.finishWrite(output)
                true
            } catch (exception: Exception) {
                pendingStoreOperationsFile.failWrite(output)
                throw exception
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deletePendingStoreOperations(): Boolean = try {
        pendingStoreOperationsFile.delete()
        !pendingStoreOperationsFile.baseFile.exists()
    } catch (_: Exception) {
        false
    }

    private fun migrateLegacySession(): PersistedSession? {
        if (migrationMarker.exists()) return null

        val preferences = try {
            applicationContext.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        } catch (_: Exception) {
            return null
        }
        val values = try {
            preferences.all
        } catch (_: Exception) {
            return null
        }
        val migrated = legacySessionFrom(values)
        if (migrated == null) {
            completeLegacyCleanupIfNeeded()
            return null
        }

        if (!writeSession(migrated)) return migrated
        completeLegacyCleanupIfNeeded()
        return migrated
    }

    @SuppressLint("ApplySharedPref")
    private fun completeLegacyCleanupIfNeeded() {
        if (migrationMarker.exists()) return

        val preferences = try {
            applicationContext.getSharedPreferences(
                LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        } catch (_: Exception) {
            return
        }
        val removed = try {
            val editor = preferences.edit()
            LEGACY_PREFERENCE_KEYS.forEach { key -> editor.remove(key) }
            // Synchronous confirmation is required before marking migration complete.
            editor.commit()
        } catch (_: Exception) {
            false
        }
        if (!removed) return

        try {
            if (!migrationMarker.exists()) migrationMarker.createNewFile()
        } catch (_: Exception) {
            // Cleanup is idempotent and will be retried on the next load/save.
        }
    }

    private fun deleteCorruptedSession() {
        try {
            sessionFile.delete()
        } catch (_: Exception) {
            // A later atomic write may still replace the invalid file.
        }
    }

    private fun encode(session: PersistedSession): String =
        JsonObject().apply {
            addNullableString("token", session.token)
            addNullableString("appUserIdentifier", session.appUserIdentifier)
            addNullableNumber("forceVersion", session.forceVersion)
            addNullableNumber("appId", session.appId)
            addNullableString("storeInfo", session.storeInfo)
            addNullableString("storePlatform", session.storePlatform)
            addNullableString("apiKeyFingerprint", session.apiKeyFingerprint)
            addNullableString(
                "cacheContextFingerprint",
                session.cacheContextFingerprint,
            )
            addNullableString("purchaseRecoveryId", session.purchaseRecoveryId)
            addProperty("cacheRestoreBlocked", session.cacheRestoreBlocked)
            addNullableString("customerInfoJson", session.customerInfoJson)
            addNullableString("offeringsJson", session.offeringsJson)
            addNullableString(
                "customerInfoUpdatedAt",
                session.customerInfoUpdatedAt,
            )
        }.toString()

    private fun decode(raw: String): PersistedSession {
        val root = JsonParser.parseString(raw)
        require(root.isJsonObject) { "Session payload must be a JSON object." }
        val json = root.asJsonObject
        return PersistedSession(
            token = json.stringValue("token"),
            appUserIdentifier = json.stringValue("appUserIdentifier"),
            forceVersion = json.longValue("forceVersion"),
            appId = json.longValue("appId"),
            storeInfo = json.stringValue("storeInfo"),
            storePlatform = json.stringValue("storePlatform"),
            apiKeyFingerprint = json.stringValue("apiKeyFingerprint"),
            cacheContextFingerprint = json.stringValue(
                "cacheContextFingerprint",
            ),
            purchaseRecoveryId = json.stringValue("purchaseRecoveryId"),
            customerInfoJson = json.stringValue("customerInfoJson"),
            offeringsJson = json.stringValue("offeringsJson"),
            customerInfoUpdatedAt = json.stringValue("customerInfoUpdatedAt"),
            cacheRestoreBlocked = json.readCacheRestoreBlocked(),
        )
    }

    private fun JsonObject.addNullableString(name: String, value: String?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addNullableNumber(name: String, value: Number?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.stringValue(name: String): String? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString

    private fun JsonObject.longValue(name: String): Long? =
        get(name)
            ?.takeUnless(JsonElement::isJsonNull)
            ?.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isNumber }
            ?.let { value ->
                try {
                    BigDecimal(value.asString).longValueExact()
                } catch (_: ArithmeticException) {
                    null
                } catch (_: NumberFormatException) {
                    null
                }
            }

    internal companion object {
        private const val SESSION_FILE_NAME = "inappify_session_v1.bin"
        private const val PENDING_OPERATIONS_FILE_NAME =
            "inappify_pending_store_operations_v2.bin"
        private const val STORAGE_LOCK_FILE_NAME = "inappify_session_v1.lock"
        private const val MIGRATION_MARKER_FILE_NAME =
            "inappify_flutter_session_migration_v1"
        private const val LEGACY_PREFERENCES_NAME = "FlutterSharedPreferences"
        private const val LEGACY_TOKEN_KEY = "flutter.token"
        private const val LEGACY_USER_IDENTIFIER_KEY =
            "flutter.appUserIdentifier"
        private const val LEGACY_FORCE_VERSION_KEY = "flutter.forceVersion"
        private const val LEGACY_APP_ID_KEY = "flutter.appId"
        private const val LEGACY_CUSTOMER_INFO_KEY = "flutter.customerInfo"
        private const val LEGACY_OFFERINGS_KEY = "flutter.offerings"
        private const val LEGACY_CUSTOMER_INFO_DATE_KEY =
            "flutter.dateTimeCustomerInfo"
        private const val MAX_SESSION_PLAINTEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_SESSION_FILE_BYTES =
            MAX_SESSION_PLAINTEXT_BYTES + 1024
        private const val MAX_PENDING_OPERATIONS_PLAINTEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_PENDING_OPERATIONS_FILE_BYTES =
            MAX_PENDING_OPERATIONS_PLAINTEXT_BYTES + 1024
        private const val STORAGE_LOCK_RETRY_MILLIS = 10L

        internal val LEGACY_PREFERENCE_KEYS: Set<String>
            get() = setOf(
                LEGACY_TOKEN_KEY,
                LEGACY_USER_IDENTIFIER_KEY,
                LEGACY_FORCE_VERSION_KEY,
                LEGACY_APP_ID_KEY,
                LEGACY_CUSTOMER_INFO_KEY,
                LEGACY_OFFERINGS_KEY,
                LEGACY_CUSTOMER_INFO_DATE_KEY,
            )

        internal fun legacySessionFrom(values: Map<String, *>): PersistedSession? =
            PersistedSession(
                token = values[LEGACY_TOKEN_KEY] as? String,
                appUserIdentifier = values[LEGACY_USER_IDENTIFIER_KEY] as? String,
                forceVersion = values.numberValue(LEGACY_FORCE_VERSION_KEY),
                appId = values.numberValue(LEGACY_APP_ID_KEY),
                storeInfo = null,
                apiKeyFingerprint = null,
                customerInfoJson = values[LEGACY_CUSTOMER_INFO_KEY] as? String,
                offeringsJson = values[LEGACY_OFFERINGS_KEY] as? String,
                customerInfoUpdatedAt =
                    values[LEGACY_CUSTOMER_INFO_DATE_KEY] as? String,
            ).takeIf { session -> session.hasData() }

        internal fun create(context: Context): EncryptedSessionStateStore =
            EncryptedSessionStateStore(
                applicationContext = context.applicationContext,
                ioDispatcher = Dispatchers.IO,
            )

        internal fun createGoV2(context: Context): EncryptedSessionStateStore =
            EncryptedSessionStateStore(context.applicationContext, Dispatchers.IO, goV2 = true)
    }
}

private fun PersistedSession.hasData(): Boolean =
    !token.isNullOrBlank() ||
        !appUserIdentifier.isNullOrBlank() ||
        forceVersion != null ||
        appId != null ||
        customerInfoJson != null ||
        offeringsJson != null

private fun Map<String, *>.numberValue(key: String): Long? =
    (get(key) as? Number)?.let { value ->
        try {
            BigDecimal(value.toString()).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

/** Includes AtomicFile's current and crash-recovery files before key rotation. */
private fun AtomicFile.hasRecoverableData(): Boolean =
    baseFile.exists() ||
        File("${baseFile.path}.bak").exists() ||
        File("${baseFile.path}.new").exists()

private fun AtomicFile.hasRecoverableDataSafely(): Boolean? = try {
    hasRecoverableData()
} catch (_: Exception) {
    null
}

private fun storageFailure(
    stage: SessionStorageStage,
    exception: Exception,
    plainTextBytes: Int?,
    encryptedBytes: Int?,
    sessionFileExists: Boolean?,
    keyResetAttempted: Boolean,
): SessionStorageFailure {
    val rootCause = exception.rootCause()
    return SessionStorageFailure(
        stage = stage,
        causeType = exception.javaClass.name,
        rootCauseType = rootCause.javaClass.name,
        causeMessage = rootCause.message
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.take(MAX_STORAGE_CAUSE_MESSAGE_LENGTH),
        plainTextBytes = plainTextBytes,
        encryptedBytes = encryptedBytes,
        sessionFileExists = sessionFileExists,
        keyResetAttempted = keyResetAttempted,
    )
}

private fun Throwable.rootCause(): Throwable {
    var current = this
    repeat(MAX_STORAGE_CAUSE_DEPTH) {
        val next = current.cause ?: return current
        if (next === current) return current
        current = next
    }
    return current
}

/** Promotes a fully written first AtomicFile staging file after process death. */
private fun AtomicFile.promoteInterruptedFirstWrite(): Boolean {
    val base = baseFile
    val backup = File("${base.path}.bak")
    val staged = File("${base.path}.new")
    if (base.exists() || backup.exists() || !staged.exists()) return true
    return staged.renameTo(base)
}

private enum class CipherKeyScope(
    val aesKeyAlias: String,
    val rsaKeyAlias: String,
    val wrappedAesKeyFileName: String,
    val certificateCommonName: String,
) {
    GO_V2(
        aesKeyAlias = "com.inappify.sdk.go_session.aes.v2",
        rsaKeyAlias = "com.inappify.sdk.go_session.rsa.v2",
        wrappedAesKeyFileName = "inappify_wrapped_go_session_key_v2.bin",
        certificateCommonName = "Inappify Go Session Key",
    ),
    SESSION(
        aesKeyAlias = "com.inappify.sdk.session.aes.v1",
        rsaKeyAlias = "com.inappify.sdk.session.rsa.v1",
        wrappedAesKeyFileName = "inappify_wrapped_session_key_v1.bin",
        certificateCommonName = "Inappify Session Key",
    ),
    PENDING_STORE_OPERATIONS(
        aesKeyAlias = "com.inappify.sdk.pending_store.aes.v2",
        rsaKeyAlias = "com.inappify.sdk.pending_store.rsa.v2",
        wrappedAesKeyFileName = "inappify_wrapped_pending_store_key_v2.bin",
        certificateCommonName = "Inappify Pending Store Key",
    ),
}

private class SessionCipher(
    private val context: Context,
    private val keyScope: CipherKeyScope,
) {
    fun encrypt(
        plainText: ByteArray,
        allowKeyReset: Boolean,
        onKeyResetAttempted: () -> Unit = {},
    ): ByteArray {
        return try {
            encryptWith(currentSecretKey(), plainText)
        } catch (exception: Exception) {
            if (!allowKeyReset) throw exception
            onKeyResetAttempted()
            resetKeyMaterial()
            encryptWith(currentSecretKey(), plainText)
        }
    }

    fun hasValidEnvelope(payload: ByteArray): Boolean {
        if (payload.size <= HEADER_SIZE) return false
        val buffer = ByteBuffer.wrap(payload)
        val formatVersion = buffer.get().toInt()
        if (formatVersion != FORMAT_VERSION) return false
        val ivSize = buffer.get().toInt() and 0xff
        return ivSize in 1..buffer.remaining() &&
            buffer.remaining() - ivSize >= GCM_TAG_LENGTH_BYTES
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(hasValidEnvelope(payload)) { "Encrypted session envelope is invalid." }
        val buffer = ByteBuffer.wrap(payload)
        buffer.get()
        val ivSize = buffer.get().toInt() and 0xff
        val iv = ByteArray(ivSize).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val decryptor = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                currentSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
            )
        }
        return decryptor.doFinal(encrypted)
    }

    private fun encryptWith(key: SecretKey, plainText: ByteArray): ByteArray {
        val encryptor = Cipher.getInstance(AES_TRANSFORMATION).apply {
            // Android Keystore keys created with randomized encryption reject
            // caller-provided IVs. Let the active provider generate the nonce,
            // then persist that exact value in the encrypted envelope.
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = requireNotNull(encryptor.iv) {
            "The encryption provider did not generate an IV."
        }
        require(iv.size in 1..MAX_GCM_IV_SIZE_BYTES) {
            "The encryption provider generated an invalid IV."
        }
        val encrypted = encryptor.doFinal(plainText)
        return ByteBuffer.allocate(HEADER_SIZE + iv.size + encrypted.size)
            .put(FORMAT_VERSION.toByte())
            .put(iv.size.toByte())
            .put(iv)
            .put(encrypted)
            .array()
    }

    private fun currentSecretKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            api23SecretKey()
        } else {
            legacyWrappedSecretKey()
        }

    @TargetApi(Build.VERSION_CODES.M)
    private fun api23SecretKey(): SecretKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(keyScope.aesKeyAlias)) {
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE,
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyScope.aesKeyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or
                            KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        }
        return keyStore.getKey(keyScope.aesKeyAlias, null) as SecretKey
    }

    @Suppress("DEPRECATION")
    private fun legacyWrappedSecretKey(): SecretKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(keyScope.rsaKeyAlias)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val specification = KeyPairGeneratorSpec.Builder(context)
                .setAlias(keyScope.rsaKeyAlias)
                .setSubject(X500Principal("CN=${keyScope.certificateCommonName}"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE).apply {
                initialize(specification)
            }.generateKeyPair()
        }

        val wrappedKeyFile = AtomicFile(
            File(context.noBackupFilesDir, keyScope.wrappedAesKeyFileName),
        )
        val entry = keyStore.getEntry(keyScope.rsaKeyAlias, null)
            as KeyStore.PrivateKeyEntry
        val rawKey = if (wrappedKeyFile.baseFile.exists()) {
            val wrapped = wrappedKeyFile.openRead().use {
                it.readBytesWithLimit(MAX_WRAPPED_KEY_FILE_BYTES)
            }
            Cipher.getInstance(RSA_TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, entry.privateKey)
            }.doFinal(wrapped)
        } else {
            val generated = KeyGenerator.getInstance("AES").apply {
                init(LEGACY_AES_KEY_SIZE_BITS)
            }.generateKey().encoded
            val wrapped = Cipher.getInstance(RSA_TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
            }.doFinal(generated)
            require(wrapped.size <= MAX_WRAPPED_KEY_FILE_BYTES) {
                "Wrapped session key exceeds the storage limit."
            }
            val output = wrappedKeyFile.startWrite()
            try {
                output.write(wrapped)
                output.fd.sync()
                wrappedKeyFile.finishWrite(output)
            } catch (exception: Exception) {
                wrappedKeyFile.failWrite(output)
                throw exception
            }
            generated
        }
        return SecretKeySpec(rawKey, "AES")
    }

    private fun resetKeyMaterial() {
        try {
            androidKeyStore().apply {
                if (containsAlias(keyScope.aesKeyAlias)) {
                    deleteEntry(keyScope.aesKeyAlias)
                }
                if (containsAlias(keyScope.rsaKeyAlias)) {
                    deleteEntry(keyScope.rsaKeyAlias)
                }
            }
        } catch (_: Exception) {
            // The next key request surfaces any unrecoverable Keystore error.
        }
        try {
            AtomicFile(
                File(context.noBackupFilesDir, keyScope.wrappedAesKeyFileName),
            ).delete()
        } catch (_: Exception) {
            // The next unwrap or write reports an unusable wrapped-key file.
        }
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        private const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 2
        private const val MAX_GCM_IV_SIZE_BYTES = 32
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        private const val LEGACY_AES_KEY_SIZE_BITS = 128
        private const val MAX_WRAPPED_KEY_FILE_BYTES = 16 * 1024
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    }
}

internal fun InputStream.readBytesWithLimit(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "File-size limit must be positive." }
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maxBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count == -1) break
        total += count
        require(total <= maxBytes) { "Stored file exceeds the size limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val MAX_STORAGE_CAUSE_MESSAGE_LENGTH = 240
private const val MAX_STORAGE_CAUSE_DEPTH = 8
