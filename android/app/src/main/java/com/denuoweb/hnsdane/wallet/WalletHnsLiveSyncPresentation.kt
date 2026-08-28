package com.denuoweb.hnsdane.wallet

/**
 * Process-local display state for a sync that can outlive a WalletActivity
 * instance. The durable source of truth remains the encrypted native wallet;
 * this cache holds only the last public stage and verified heights so backing
 * out and returning does not look like progress was discarded. Every writer
 * owns a distinct lease, so a stopped poller cannot resurrect old progress
 * after a later terminal result has cleared the display state.
 */
internal sealed interface WalletHnsLiveSyncPresentation {
    data class Live(val progress: NativeWalletHnsLiveSyncProgress) :
        WalletHnsLiveSyncPresentation

    data class Catchup(val progress: NativeWalletHnsCatchupProgress) :
        WalletHnsLiveSyncPresentation
}

internal class WalletHnsLiveSyncPresentationLease internal constructor(
    internal val networkId: String,
)

internal object WalletHnsLiveSyncPresentationCache {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    /** Starts a new public synchronization presentation and revokes older writers. */
    fun begin(networkId: String): WalletHnsLiveSyncPresentationLease = synchronized(lock) {
        WalletHnsLiveSyncPresentationLease(networkId).also { lease ->
            entries[networkId] = Entry(lease = lease)
        }
    }

    fun publishLive(
        lease: WalletHnsLiveSyncPresentationLease,
        progress: NativeWalletHnsLiveSyncProgress,
    ) {
        synchronized(lock) {
            entries[lease.networkId]
                ?.takeIf { entry -> entry.lease === lease && entry.acceptsLiveProgress }
                ?.let { entry -> entry.presentation = WalletHnsLiveSyncPresentation.Live(progress) }
        }
    }

    /** Atomically retains catch-up while revoking the live poller for this lease. */
    fun finishCatchup(
        lease: WalletHnsLiveSyncPresentationLease,
        progress: NativeWalletHnsCatchupProgress,
    ) {
        synchronized(lock) {
            entries[lease.networkId]
                ?.takeIf { entry -> entry.lease === lease }
                ?.let { entry ->
                    entry.presentation = WalletHnsLiveSyncPresentation.Catchup(progress)
                    entry.acceptsLiveProgress = false
                }
        }
    }

    fun latest(networkId: String): WalletHnsLiveSyncPresentation? =
        synchronized(lock) { entries[networkId]?.presentation }

    /** Removes one terminal writer and prevents all of its future publications. */
    fun clear(lease: WalletHnsLiveSyncPresentationLease) {
        synchronized(lock) {
            entries[lease.networkId]
                ?.takeIf { entry -> entry.lease === lease }
                ?.let { entries.remove(lease.networkId) }
        }
    }

    /** Invalidates any retained display state, for example after wallet deletion. */
    fun clear(networkId: String) {
        synchronized(lock) { entries.remove(networkId) }
    }

    private data class Entry(
        val lease: WalletHnsLiveSyncPresentationLease,
        var presentation: WalletHnsLiveSyncPresentation? = null,
        var acceptsLiveProgress: Boolean = true,
    )
}

/** A live writer still owns the wallet controller and storage lease. */
internal fun walletHnsPresentationMayAcquireStorage(
    presentation: WalletHnsLiveSyncPresentation?,
): Boolean = presentation !is WalletHnsLiveSyncPresentation.Live
