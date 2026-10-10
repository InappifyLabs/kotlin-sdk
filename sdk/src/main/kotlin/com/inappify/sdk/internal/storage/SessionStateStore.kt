package com.inappify.sdk.internal.storage

internal class PersistedSession(
    internal val token: String?,
    internal val appUserIdentifier: String?,
    internal val forceVersion: Long?,
    internal val appId: Long?,
    internal val storeInfo: String?,
    internal val apiKeyFingerprint: String?,
    internal val customerInfoJson: String?,
    internal val offeringsJson: String?,
    internal val customerInfoUpdatedAt: String?,
    internal val cacheContextFingerprint: String? = null,
    internal val purchaseRecoveryId: String? = null,
    internal val storePlatform: String? = null,
    internal val cacheRestoreBlocked: Boolean = false,
) {
    override fun toString(): String =
        "PersistedSession(" +
            "token=${token.redacted()}, " +
            "appUserIdentifier=${appUserIdentifier.redacted()}, " +
            "forceVersion=$forceVersion, " +
            "appId=$appId, " +
            "storeInfo=$storeInfo, " +
            "apiKeyFingerprint=${apiKeyFingerprint.redacted()}, " +
            "cacheContextFingerprint=${cacheContextFingerprint.redacted()}, " +
            "purchaseRecoveryId=${purchaseRecoveryId.redacted()}, " +
            "storePlatform=$storePlatform, " +
            "cacheRestoreBlocked=$cacheRestoreBlocked, " +
            "customerInfoJson=${customerInfoJson.redacted()}, " +
            "offeringsJson=${offeringsJson.redacted()}, " +
            "customerInfoUpdatedAt=$customerInfoUpdatedAt" +
            ")"
}

/** Retains the credential and recovery binding while disabling only offline adoption. */
internal fun PersistedSession.blockCacheRestore(): PersistedSession = PersistedSession(
    token = token,
    appUserIdentifier = appUserIdentifier,
    forceVersion = forceVersion,
    appId = appId,
    storeInfo = storeInfo,
    apiKeyFingerprint = apiKeyFingerprint,
    customerInfoJson = customerInfoJson,
    offeringsJson = offeringsJson,
    customerInfoUpdatedAt = customerInfoUpdatedAt,
    cacheContextFingerprint = cacheContextFingerprint,
    purchaseRecoveryId = purchaseRecoveryId,
    storePlatform = storePlatform,
    cacheRestoreBlocked = true,
)

internal interface SessionStateStore {
    suspend fun load(): PersistedSession?

    /** Reads only established session storage; production does not migrate legacy credentials. */
    suspend fun loadForCacheRestore(): PersistedSession? = load()

    suspend fun save(session: PersistedSession): Boolean

    /**
     * Persists one session and retains only non-sensitive failure metadata.
     *
     * The default keeps existing implementations source-compatible. Production
     * stores should override this method when they can identify the failed I/O
     * or cryptographic stage without exposing session contents.
     */
    suspend fun saveWithDiagnostics(session: PersistedSession): SessionSaveResult =
        if (save(session)) {
            SessionSaveResult.Success
        } else {
            SessionSaveResult.Failure(
                SessionStorageFailure(stage = SessionStorageStage.UNKNOWN),
            )
        }

    suspend fun clear(): Boolean

    /** Returns a stable snapshot of encrypted V2 operations awaiting recovery. */
    suspend fun loadPendingStoreOperations(): List<PendingStoreOperation> = emptyList()

    /**
     * Atomically reads the complete V2 recovery state.
     *
     * A nullable result distinguishes temporarily unavailable encrypted
     * storage from a successfully read empty state. The default keeps simple
     * in-memory implementations source-compatible.
     */
    suspend fun loadPendingStoreRecoveryState(): PendingStoreRecoveryState? =
        PendingStoreRecoveryState(
            operations = loadPendingStoreOperations(),
            rejectedEvidenceTombstones = loadRejectedStoreEvidenceTombstones(),
        )

    /** Atomically inserts or replaces one operation, keyed by its stable id. */
    suspend fun upsertPendingStoreOperation(
        operation: PendingStoreOperation,
    ): Boolean = false

    /** Atomically removes an operation. Missing ids are treated as success. */
    suspend fun removePendingStoreOperation(operationId: String): Boolean = false

    /**
     * Atomically transforms the durable queue while holding the storage lock.
     *
     * [mutation] must be quick, deterministic, and free of blocking I/O.
     */
    suspend fun mutatePendingStoreOperations(
        mutation: (List<PendingStoreOperation>) -> List<PendingStoreOperation>,
    ): Boolean = false

    /** Clears pending V2 operations while preserving rejection markers. */
    suspend fun clearPendingStoreOperations(): Boolean = false

    /** Returns permanent store-evidence rejections retained across session clear. */
    suspend fun loadRejectedStoreEvidenceTombstones():
        List<RejectedStoreEvidenceTombstone> = emptyList()

    /** Atomically adds or refreshes one bounded rejection marker. */
    suspend fun upsertRejectedStoreEvidenceTombstone(
        tombstone: RejectedStoreEvidenceTombstone,
    ): Boolean = false

    /**
     * Finalizes one permanently rejected operation in a single durable state
     * transition. Implementations backed by persistent storage must override
     * this method atomically. The compatibility default writes the tombstone
     * first so a failed removal never discards pending purchase evidence.
     */
    suspend fun finalizePendingStoreOperationWithTombstone(
        operationId: String,
        tombstone: RejectedStoreEvidenceTombstone,
    ): Boolean =
        upsertRejectedStoreEvidenceTombstone(tombstone) &&
            removePendingStoreOperation(operationId)

    /** Clears rejection markers without changing pending purchase evidence. */
    suspend fun clearRejectedStoreEvidenceTombstones(): Boolean = false
}

internal sealed interface SessionSaveResult {
    data object Success : SessionSaveResult

    class Failure(
        val diagnostic: SessionStorageFailure,
    ) : SessionSaveResult
}

internal enum class SessionStorageStage {
    LOCK,
    ENCODE,
    SIZE_CHECK,
    ENCRYPT,
    ATOMIC_WRITE,
    FSYNC,
    UNKNOWN,
}

/** Safe metadata for diagnosing persistence without retaining session values. */
internal class SessionStorageFailure(
    val stage: SessionStorageStage,
    val causeType: String? = null,
    val rootCauseType: String? = null,
    val causeMessage: String? = null,
    val plainTextBytes: Int? = null,
    val encryptedBytes: Int? = null,
    val sessionFileExists: Boolean? = null,
    val keyResetAttempted: Boolean? = null,
) {
    fun toSafeDetails(): Map<String, Any?> = buildMap {
        put("storageStage", stage.name)
        causeType?.let { put("causeType", it) }
        rootCauseType?.let { put("rootCauseType", it) }
        causeMessage?.let { put("causeMessage", it) }
        plainTextBytes?.let { put("plainTextBytes", it) }
        encryptedBytes?.let { put("encryptedBytes", it) }
        sessionFileExists?.let { put("sessionFileExists", it) }
        keyResetAttempted?.let { put("keyResetAttempted", it) }
    }
}

private fun String?.redacted(): String = if (this == null) "null" else "<redacted>"
