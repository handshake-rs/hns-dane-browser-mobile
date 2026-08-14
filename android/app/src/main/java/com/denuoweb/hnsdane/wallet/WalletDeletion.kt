package com.denuoweb.hnsdane.wallet

import java.io.File

/** Public, non-secret identity captured across the two wallet-deletion confirmations. */
internal data class WalletDeletionScope(
    val lifecycleEpoch: Long,
    val ownerGeneration: Long,
    val leaseGeneration: Long,
    val storagePath: String,
    val networkId: String,
    val walletHandle: Long,
    val accountId: String,
)

internal fun walletDeletionMayProceed(
    expected: WalletDeletionScope,
    current: WalletDeletionScope,
    foreground: Boolean,
    busy: Boolean,
    confirmedPersistentWallet: Boolean,
    hasUnconfirmedKey: Boolean,
): Boolean =
    expected == current &&
        current.lifecycleEpoch > 0L &&
        current.ownerGeneration > 0L &&
        current.leaseGeneration > 0L &&
        walletDeletionStorageMatchesNetwork(current.storagePath, current.networkId) &&
        current.walletHandle > 0L &&
        current.accountId.length == WALLET_ACCOUNT_ID_HEX_CHARACTERS &&
        current.accountId.all { it in '0'..'9' || it in 'a'..'f' } &&
        current.accountId.any { it != '0' } &&
        foreground &&
        !busy &&
        confirmedPersistentWallet &&
        !hasUnconfirmedKey

private fun walletDeletionStorageMatchesNetwork(storagePath: String, networkId: String): Boolean {
    if (networkId !in WALLET_DELETION_NETWORK_IDS) return false
    val database = File(storagePath)
    return database.isAbsolute &&
        database.name == WALLET_DATABASE_FILE_NAME &&
        database.parentFile?.name == walletStorageNamespace(networkId).directoryName
}

internal enum class WalletDeletionLatchState {
    None,
    AwaitingDurableRequest,
    DurableRequest,
}

/**
 * Process-wide truth that is deliberately stronger than SharedPreferences' in-memory view.
 * Android may apply an Editor in memory even when commit() reports a disk-write failure.
 */
internal class WalletDeletionProcessLatch {
    private val lock = Any()
    private val states = mutableMapOf<String, WalletDeletionLatchState>()

    fun observe(namespace: String, persistedMarker: Boolean): WalletDeletionLatchState =
        synchronized(lock) {
            require(namespace.isNotBlank()) { "Wallet deletion namespace is blank" }
            val current = states[namespace]
            if (current != null) return@synchronized current
            if (persistedMarker) {
                WalletDeletionLatchState.DurableRequest.also { states[namespace] = it }
            } else {
                WalletDeletionLatchState.None
            }
        }

    fun begin(namespace: String) = synchronized(lock) {
        require(namespace.isNotBlank()) { "Wallet deletion namespace is blank" }
        states.putIfAbsent(namespace, WalletDeletionLatchState.AwaitingDurableRequest)
        Unit
    }

    fun markDurable(namespace: String) = synchronized(lock) {
        require(namespace.isNotBlank()) { "Wallet deletion namespace is blank" }
        states[namespace] = WalletDeletionLatchState.DurableRequest
    }

    fun complete(namespace: String) = synchronized(lock) {
        require(states[namespace] == WalletDeletionLatchState.DurableRequest) {
            "Wallet deletion request is not durably authorized"
        }
        states.remove(namespace)
        Unit
    }
}

/** A native close failure is ambiguous, so this exact path stays unusable until process restart. */
internal class WalletControllerRetirementFailureLatch {
    private val failedPaths = mutableSetOf<String>()

    fun mark(databasePath: String) {
        val database = File(databasePath)
        require(database.isAbsolute && database.name == WALLET_DATABASE_FILE_NAME) {
            "Invalid wallet retirement-failure path"
        }
        synchronized(failedPaths) { failedPaths.add(database.path) }
    }

    fun blocks(databasePath: String): Boolean =
        synchronized(failedPaths) { databasePath in failedPaths }
}

internal object ProcessWalletControllerRetirementFailures {
    private val latch = WalletControllerRetirementFailureLatch()

    fun mark(databasePath: String) = latch.mark(databasePath)

    fun blocks(databasePath: String): Boolean = latch.blocks(databasePath)
}

internal fun walletControllerOperationMayBegin(
    retirementFailed: Boolean,
    busy: Boolean,
    ownsCurrentLease: Boolean,
): Boolean = !retirementFailed && !busy && ownsCurrentLease

internal fun walletDeleteConfirmationMatches(value: CharSequence?): Boolean =
    value != null &&
        value.length == WALLET_DELETE_CONFIRMATION.length &&
        WALLET_DELETE_CONFIRMATION.indices.all { index ->
            value[index] == WALLET_DELETE_CONFIRMATION[index]
        }

/** Lock is attempted before close; successful close retires authority even when lock failed. */
internal fun closeWalletControllerForDeletion(
    lock: () -> Boolean,
    close: () -> Boolean,
): Boolean {
    runCatching(lock)
    return runCatching(close).getOrDefault(false)
}

internal enum class WalletStorageDeletionResult {
    KeyDeletionFailed,
    FileCleanupPending,
    Deleted,
}

internal enum class WalletDatabaseKeyDeletionResult {
    Removed,
    RemovedMetadataCleanupPending,
}

/**
 * Persists deletion intent, removes key material, then removes encrypted files.
 * Completion is recorded only after every SQLite artifact is absent.
 */
internal fun deleteConfirmedWalletStorage(
    requestDeletion: () -> Unit,
    deleteDatabaseKey: () -> WalletDatabaseKeyDeletionResult,
    deleteDatabaseFiles: () -> Boolean,
    finishDeletion: () -> Unit,
): WalletStorageDeletionResult {
    if (runCatching(requestDeletion).isFailure) {
        return WalletStorageDeletionResult.KeyDeletionFailed
    }
    if (runCatching(deleteDatabaseKey).getOrNull() == null) {
        return WalletStorageDeletionResult.KeyDeletionFailed
    }
    if (!runCatching(deleteDatabaseFiles).getOrDefault(false)) {
        return WalletStorageDeletionResult.FileCleanupPending
    }
    if (runCatching(finishDeletion).isFailure) {
        return WalletStorageDeletionResult.FileCleanupPending
    }
    return WalletStorageDeletionResult.Deleted
}

internal fun walletDatabaseArtifacts(database: File): List<File> {
    require(database.isAbsolute) { "Wallet database path must be absolute" }
    require(database.name == WALLET_DATABASE_FILE_NAME) { "Unexpected wallet database name" }
    return listOf(
        database,
        File(database.path + "-wal"),
        File(database.path + "-shm"),
        File(database.path + "-journal"),
    )
}

/** Attempts every exact SQLite artifact and succeeds only when all are absent. */
internal fun deleteWalletDatabaseArtifacts(database: File): Boolean {
    val artifacts = walletDatabaseArtifacts(database)
    var deleted = true
    artifacts.forEach { artifact ->
        if (artifact.exists() && !artifact.delete()) deleted = false
    }
    return deleted && artifacts.none(File::exists)
}

internal const val WALLET_DELETE_CONFIRMATION = "DELETE"
internal const val WALLET_DATABASE_FILE_NAME = "wallet.sqlite3"

private const val WALLET_ACCOUNT_ID_HEX_CHARACTERS = 32
private val WALLET_DELETION_NETWORK_IDS = setOf("mainnet", "testnet", "regtest")
