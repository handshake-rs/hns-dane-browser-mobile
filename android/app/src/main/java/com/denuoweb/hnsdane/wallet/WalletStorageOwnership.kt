package com.denuoweb.hnsdane.wallet

/** Stable on-device names for one captured Handshake network. */
internal data class WalletStorageNamespace(
    val directoryName: String,
    val preferencesName: String,
    val keyAlias: String,
    val wrappingContext: String,
)

internal fun walletStorageNamespace(networkId: String): WalletStorageNamespace {
    require(networkId in WALLET_NETWORK_IDS) { "Unsupported wallet network" }
    return WalletStorageNamespace(
        directoryName = "wallet-v1-$networkId",
        preferencesName = "wallet-keystore-v1-$networkId",
        keyAlias = "hns-wallet-database-wrapping-v1-$networkId",
        wrappingContext = "hns-wallet-database-key-v1:$networkId",
    )
}

internal fun walletSetupMayInspectStorage(
    foreground: Boolean,
    ownsCurrentLease: Boolean,
    busy: Boolean,
    hasController: Boolean,
    hasUnconfirmedKey: Boolean,
): Boolean =
    foreground &&
        ownsCurrentLease &&
        !busy &&
        !hasController &&
        !hasUnconfirmedKey

internal fun walletReadMayPublish(
    expectedEpoch: Long,
    currentEpoch: Long,
    foreground: Boolean,
    ownsCurrentLease: Boolean,
    expectedHandle: Long,
    currentHandle: Long,
    expectedAuthorityGeneration: Long,
    currentAuthorityGeneration: Long,
): Boolean =
    expectedEpoch == currentEpoch &&
        foreground &&
        ownsCurrentLease &&
        expectedHandle > 0L &&
        expectedHandle == currentHandle &&
        expectedAuthorityGeneration > 0L &&
        expectedAuthorityGeneration == currentAuthorityGeneration

/**
 * Records leases whose release is owned by native-controller retirement rather
 * than the stale operation callback. Membership is retained for this short
 * Activity lifetime so a duplicate callback can never release the same lease.
 */
internal class WalletLeaseReleaseHandoff {
    private val lock = Any()
    private val retirementOwned = mutableSetOf<WalletStorageOwnershipGate.Lease>()

    fun handOffToRetirement(lease: WalletStorageOwnershipGate.Lease): Boolean =
        synchronized(lock) { retirementOwned.add(lease) }

    fun operationMayRelease(lease: WalletStorageOwnershipGate.Lease): Boolean =
        synchronized(lock) { lease !in retirementOwned }
}

/**
 * Serializes access to each process-local wallet database path across Activity
 * replacement. A newer owner revokes the old owner immediately, but cannot
 * acquire the path until the old lease has finished native work and cleanup.
 */
internal class WalletStorageOwnershipGate {
    internal class Owner internal constructor(
        val path: String,
        val generation: Long,
        internal var onRevoked: (() -> Unit)?,
    )

    internal class Lease internal constructor(
        val path: String,
        internal val owner: Owner,
        internal val generation: Long,
        private val issuingGate: WalletStorageOwnershipGate,
    ) {
        /** Checks this exact lease against the gate that issued it. */
        fun isCurrent(): Boolean = issuingGate.isCurrent(owner, this)
    }

    private data class PendingAcquire(
        val owner: Owner,
        val onAcquired: (Lease) -> Unit,
    )

    private data class PathState(
        var nextOwnerGeneration: Long = 1L,
        var nextLeaseGeneration: Long = 1L,
        var currentOwner: Owner? = null,
        var activeLease: Lease? = null,
        var pendingAcquire: PendingAcquire? = null,
    )

    private val lock = Any()
    private val paths = mutableMapOf<String, PathState>()

    fun newOwner(path: String, onRevoked: () -> Unit): Owner {
        require(path.isNotBlank()) { "Wallet storage path is blank" }
        var revokePrevious: (() -> Unit)? = null
        val owner = synchronized(lock) {
            val state = paths.getOrPut(path) { PathState() }
            check(state.nextOwnerGeneration > 0L) { "Wallet owner generation exhausted" }
            val created = Owner(path, state.nextOwnerGeneration, onRevoked)
            state.nextOwnerGeneration = state.nextOwnerGeneration
                .takeIf { it < Long.MAX_VALUE }
                ?.plus(1L)
                ?: 0L
            val previous = state.currentOwner
            state.currentOwner = created
            state.pendingAcquire = null
            revokePrevious = previous?.onRevoked
            previous?.onRevoked = null
            created
        }
        revokePrevious?.invoke()
        return owner
    }

    /**
     * Acquires now or queues the current owner behind the existing lease. Only
     * one pending callback is retained because a newer owner supersedes it.
     */
    fun acquire(owner: Owner, onAcquired: (Lease) -> Unit): Boolean {
        var acquired: Lease? = null
        val accepted = synchronized(lock) {
            val state = paths[owner.path] ?: return@synchronized false
            if (state.currentOwner !== owner) return@synchronized false
            if (state.activeLease == null) {
                acquired = newLease(state, owner)
                state.activeLease = acquired
            } else {
                state.pendingAcquire = PendingAcquire(owner, onAcquired)
            }
            true
        }
        acquired?.let(onAcquired)
        return accepted
    }

    fun isCurrent(owner: Owner, lease: Lease): Boolean = synchronized(lock) {
        val state = paths[owner.path]
        state?.currentOwner === owner && state.activeLease === lease
    }

    /** Runs a short publication step without allowing a newer owner to interleave. */
    fun commitIfCurrent(owner: Owner, lease: Lease, publish: () -> Unit): Boolean =
        synchronized(lock) {
            val state = paths[owner.path]
            if (state?.currentOwner !== owner || state.activeLease !== lease) {
                false
            } else {
                publish()
                true
            }
        }

    fun retire(owner: Owner) {
        synchronized(lock) {
            val state = paths[owner.path] ?: return
            if (state.currentOwner === owner) state.currentOwner = null
            if (state.pendingAcquire?.owner === owner) state.pendingAcquire = null
            owner.onRevoked = null
        }
    }

    fun release(lease: Lease): Boolean {
        var acquired: Pair<Lease, (Lease) -> Unit>? = null
        val released = synchronized(lock) {
            val state = paths[lease.path] ?: return@synchronized false
            if (state.activeLease !== lease) return@synchronized false
            state.activeLease = null
            val pending = state.pendingAcquire
            state.pendingAcquire = null
            if (pending != null && state.currentOwner === pending.owner) {
                val next = newLease(state, pending.owner)
                state.activeLease = next
                acquired = next to pending.onAcquired
            }
            true
        }
        acquired?.let { (next, callback) -> callback(next) }
        return released
    }

    private fun newLease(state: PathState, owner: Owner): Lease {
        check(state.nextLeaseGeneration > 0L) { "Wallet lease generation exhausted" }
        val lease = Lease(owner.path, owner, state.nextLeaseGeneration, this)
        state.nextLeaseGeneration = state.nextLeaseGeneration
            .takeIf { it < Long.MAX_VALUE }
            ?.plus(1L)
            ?: 0L
        return lease
    }
}

internal object ProcessWalletStorageOwnership {
    private val gate = WalletStorageOwnershipGate()

    fun newOwner(path: String, onRevoked: () -> Unit): WalletStorageOwnershipGate.Owner =
        gate.newOwner(path, onRevoked)

    fun acquire(
        owner: WalletStorageOwnershipGate.Owner,
        onAcquired: (WalletStorageOwnershipGate.Lease) -> Unit,
    ): Boolean = gate.acquire(owner, onAcquired)

    fun isCurrent(
        owner: WalletStorageOwnershipGate.Owner,
        lease: WalletStorageOwnershipGate.Lease,
    ): Boolean = gate.isCurrent(owner, lease)

    fun commitIfCurrent(
        owner: WalletStorageOwnershipGate.Owner,
        lease: WalletStorageOwnershipGate.Lease,
        publish: () -> Unit,
    ): Boolean = gate.commitIfCurrent(owner, lease, publish)

    fun retire(owner: WalletStorageOwnershipGate.Owner) = gate.retire(owner)

    fun release(lease: WalletStorageOwnershipGate.Lease): Boolean = gate.release(lease)
}

private val WALLET_NETWORK_IDS = setOf("mainnet", "testnet", "regtest")
