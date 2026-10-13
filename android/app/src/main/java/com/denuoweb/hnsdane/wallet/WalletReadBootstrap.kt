package com.denuoweb.hnsdane.wallet

import java.io.File

/**
 * Exact, opaque authority for one attempt to compose synchronized HNS reads.
 *
 * The database path is canonicalized before it enters the authority. Owner,
 * lease, controller, and Activity authority generations prevent a credential
 * captured for an earlier lifecycle from being replayed into its replacement.
 */
internal class WalletReadBootstrapAuthority private constructor(
    val networkId: String,
    val databasePath: String,
    val ownerGeneration: Long,
    val leaseGeneration: Long,
    val walletHandle: Long,
    val authorityGeneration: Long,
    private val storageLease: WalletStorageOwnershipGate.Lease,
) {
    override fun equals(other: Any?): Boolean =
        other is WalletReadBootstrapAuthority &&
            networkId == other.networkId &&
            databasePath == other.databasePath &&
            ownerGeneration == other.ownerGeneration &&
            leaseGeneration == other.leaseGeneration &&
            walletHandle == other.walletHandle &&
            authorityGeneration == other.authorityGeneration &&
            storageLease === other.storageLease

    override fun hashCode(): Int {
        var result = networkId.hashCode()
        result = 31 * result + databasePath.hashCode()
        result = 31 * result + ownerGeneration.hashCode()
        result = 31 * result + leaseGeneration.hashCode()
        result = 31 * result + walletHandle.hashCode()
        result = 31 * result + authorityGeneration.hashCode()
        result = 31 * result + System.identityHashCode(storageLease)
        return result
    }

    override fun toString(): String =
        "WalletReadBootstrapAuthority(networkId=$networkId, databasePath=<redacted>, " +
            "ownerGeneration=<redacted>, leaseGeneration=<redacted>, " +
            "walletHandle=<redacted>, authorityGeneration=<redacted>)"

    /** Checks the retained lease without exposing its gate, owner, or identity. */
    fun hasCurrentStorageLease(): Boolean = storageLease.isCurrent()

    companion object {
        fun create(
            networkId: String,
            databasePath: String,
            storageLease: WalletStorageOwnershipGate.Lease,
            walletHandle: Long,
            authorityGeneration: Long,
        ): WalletReadBootstrapAuthority? {
            if (
                storageLease.owner.generation <= 0L || storageLease.generation <= 0L ||
                walletHandle <= 0L || authorityGeneration <= 0L
            ) return null
            val namespace = runCatching { walletStorageNamespace(networkId) }.getOrNull()
                ?: return null
            val requested = File(databasePath)
            if (!requested.isAbsolute) return null
            val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
            val canonicalLeasePath = runCatching { File(storageLease.path).canonicalPath }
                .getOrNull() ?: return null
            if (
                canonicalLeasePath != canonical.path ||
                canonical.name != WALLET_DATABASE_FILE_NAME ||
                canonical.parentFile?.name != namespace.directoryName
            ) return null
            return WalletReadBootstrapAuthority(
                networkId = networkId,
                databasePath = canonical.path,
                ownerGeneration = storageLease.owner.generation,
                leaseGeneration = storageLease.generation,
                walletHandle = walletHandle,
                authorityGeneration = authorityGeneration,
                storageLease = storageLease,
            )
        }
    }
}

/** Current lifecycle facts re-read immediately before native composition. */
internal data class WalletReadBootstrapState(
    val authority: WalletReadBootstrapAuthority?,
    val foreground: Boolean,
    val protectedStorageAvailable: Boolean,
    val reopenedDurableWallet: Boolean,
    val confirmedPersistentWallet: Boolean,
    val hasUnconfirmedRecovery: Boolean,
    val operationInFlight: Boolean,
    val retirementBlocked: Boolean,
)

internal fun walletReadBootstrapMayInstall(
    expected: WalletReadBootstrapAuthority,
    current: WalletReadBootstrapState,
): Boolean =
    current.authority == expected &&
        expected.hasCurrentStorageLease() &&
        current.foreground &&
        current.protectedStorageAvailable &&
        current.reopenedDurableWallet &&
        current.confirmedPersistentWallet &&
        !current.hasUnconfirmedRecovery &&
        !current.operationInFlight &&
        !current.retirementBlocked

/**
 * Starts a rollback-journalled direct HNS synchronization.
 *
 * A process can die after persisting the interruption marker but before
 * committing its authenticated floor. The replacement direct coordinator is
 * opened under that stored floor, so it may safely complete that interrupted
 * journal entry before starting a new bounded synchronization. Other journal
 * failures remain unavailable to the caller.
 */
internal fun beginDirectHnsSynchronizationWithRecovery(
    begin: () -> Unit,
    recoverInterrupted: () -> Unit,
): DirectHnsSynchronizationJournalStart {
    if (runCatching(begin).isSuccess) return DirectHnsSynchronizationJournalStart.Started
    return if (runCatching {
            recoverInterrupted()
            begin()
        }.isSuccess
    ) {
        DirectHnsSynchronizationJournalStart.Recovered
    } else {
        DirectHnsSynchronizationJournalStart.Failed
    }
}

internal enum class DirectHnsSynchronizationJournalStart {
    Started,
    Recovered,
    Failed,
}
