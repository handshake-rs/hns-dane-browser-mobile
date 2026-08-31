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

/**
 * A single-use, authority-bound RPC credential.
 *
 * Construction consumes a caller-owned mutable copy. Any use attempt, even an
 * authority mismatch, atomically takes and wipes the retained copy. `close`
 * covers callers that abandon the configuration before attempting use.
 */
internal class NativeHnsReadConfiguration private constructor(
    val authority: WalletReadBootstrapAuthority,
    val loopbackPort: Int,
    private var retainedAuthorization: CharArray?,
    private var retainedShakescapePolicyJson: ByteArray?,
) : AutoCloseable {
    private val lock = Any()

    private data class RetainedMaterial(
        val authorization: CharArray,
        val shakescapePolicyJson: ByteArray?,
    )

    private fun takeRetainedMaterial(): RetainedMaterial? = synchronized(lock) {
        val authorization = retainedAuthorization ?: return@synchronized null
        retainedAuthorization = null
        val policy = retainedShakescapePolicyJson
        retainedShakescapePolicyJson = null
        RetainedMaterial(authorization, policy)
    }

    fun consumeFor(
        currentAuthority: WalletReadBootstrapAuthority,
        install: (Int, CharArray) -> Boolean,
    ): Boolean {
        val material = takeRetainedMaterial() ?: return false
        return try {
            authority == currentAuthority && install(loopbackPort, material.authorization)
        } finally {
            material.authorization.fill('\u0000')
            material.shakescapePolicyJson?.fill(0)
        }
    }

    fun consumeForValue(
        currentAuthority: WalletReadBootstrapAuthority,
        install: (Int, CharArray, ByteArray) -> Boolean,
    ): Boolean {
        val material = takeRetainedMaterial() ?: return false
        return try {
            val policy = material.shakescapePolicyJson
            authority == currentAuthority && policy != null &&
                install(loopbackPort, material.authorization, policy)
        } finally {
            material.authorization.fill('\u0000')
            material.shakescapePolicyJson?.fill(0)
        }
    }

    override fun close() {
        val material = synchronized(lock) {
            val authorization = retainedAuthorization
            val policy = retainedShakescapePolicyJson
            retainedAuthorization = null
            retainedShakescapePolicyJson = null
            authorization?.let { RetainedMaterial(it, policy) }
        }
        material?.authorization?.fill('\u0000')
        material?.shakescapePolicyJson?.fill(0)
    }

    override fun toString(): String =
        "NativeHnsReadConfiguration(authority=<redacted>, loopbackPort=<redacted>, " +
            "authorization=<redacted>)"

    companion object {
        fun takeOwnership(
            authority: WalletReadBootstrapAuthority,
            loopbackPort: Int,
            authorization: CharArray,
            shakescapePolicyJson: ByteArray? = null,
        ): NativeHnsReadConfiguration? {
            var retained: CharArray? = null
            var retainedPolicy: ByteArray? = null
            return try {
                if (
                    loopbackPort !in 1..USHORT_MAX ||
                    authorization.isEmpty() ||
                    authorization.size > MAX_AUTHORIZATION_CHARACTERS ||
                    authorization.first() == ' ' ||
                    authorization.last() == ' ' ||
                    authorization.any { it.code !in PRINTABLE_ASCII } ||
                    shakescapePolicyJson?.let { !validShakescapePolicy(it) } == true
                ) {
                    null
                } else {
                    val owned = authorization.copyOf()
                    retained = owned
                    val ownedPolicy = shakescapePolicyJson?.copyOf()
                    retainedPolicy = ownedPolicy
                    NativeHnsReadConfiguration(
                        authority,
                        loopbackPort,
                        owned,
                        ownedPolicy,
                    ).also {
                        retained = null
                        retainedPolicy = null
                    }
                }
            } finally {
                authorization.fill('\u0000')
                shakescapePolicyJson?.fill(0)
                retained?.fill('\u0000')
                retainedPolicy?.fill(0)
            }
        }

        private fun validShakescapePolicy(policy: ByteArray): Boolean =
            policy.size in 2..MAX_SHAKESCAPE_POLICY_BYTES &&
                policy.first() == '{'.code.toByte() &&
                policy.last() == '}'.code.toByte() &&
                policy.none { byte ->
                    val value = byte.toInt() and 0xff
                    value == 0 || value > 0x7f
                }

        private const val USHORT_MAX = 65_535
        private const val MAX_AUTHORIZATION_CHARACTERS = 4_096
        private const val MAX_SHAKESCAPE_POLICY_BYTES = 16 * 1024
        private val PRINTABLE_ASCII = 0x20..0x7e
    }
}

/**
 * Supplies an already-scoped credential without performing network or storage
 * work. Implementations must never read preferences, intents, links, or WebView
 * messages. A returned configuration is consumed before this call stack exits.
 */
internal fun interface WalletReadBootstrapSource {
    fun take(authority: WalletReadBootstrapAuthority): NativeHnsReadConfiguration?
}

/** Shipping builds intentionally provision no indexed backend or credential. */
internal object UnavailableWalletReadBootstrapSource : WalletReadBootstrapSource {
    override fun take(authority: WalletReadBootstrapAuthority): NativeHnsReadConfiguration? = null
}

/**
 * Performs gate-before-take and gate-after-take admission. The second state
 * read closes races with lifecycle revocation caused by a source callback.
 */
internal fun attemptWalletReadBootstrap(
    expectedAuthority: WalletReadBootstrapAuthority,
    source: WalletReadBootstrapSource,
    currentState: () -> WalletReadBootstrapState,
    install: (WalletReadBootstrapAuthority, NativeHnsReadConfiguration) -> Boolean,
): Boolean {
    if (!walletReadBootstrapMayInstall(expectedAuthority, currentState())) return false
    val configuration = runCatching { source.take(expectedAuthority) }.getOrNull() ?: return false
    configuration.use {
        val current = currentState()
        val currentAuthority = current.authority
        if (
            currentAuthority == null ||
            !walletReadBootstrapMayInstall(expectedAuthority, current)
        ) return false
        return runCatching { install(currentAuthority, configuration) }.getOrDefault(false)
    }
}
