package com.denuoweb.hnsdane.wallet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local display state for a sync that can outlive a WalletActivity
 * instance. The durable source of truth remains the encrypted native wallet;
 * this cache holds only the last public stage and verified heights so backing
 * out and returning does not look like progress was discarded. Every writer
 * owns a distinct lease, so a stopped poller cannot resurrect old progress
 * after a later terminal result has cleared the display state.
 */
internal sealed interface WalletHnsLiveSyncPresentation {
    data object Preparing : WalletHnsLiveSyncPresentation

    data class Live(val progress: NativeWalletHnsLiveSyncProgress) :
        WalletHnsLiveSyncPresentation

    data class Catchup(val progress: NativeWalletHnsCatchupProgress) :
        WalletHnsLiveSyncPresentation

    data class Cancelling(val progress: NativeWalletHnsLiveSyncProgress?) :
        WalletHnsLiveSyncPresentation
}

internal class WalletHnsLiveSyncPresentationLease internal constructor(
    internal val networkId: String,
    internal val cancellationRequested: AtomicBoolean = AtomicBoolean(false),
)

internal object WalletHnsLiveSyncPresentationCache {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val automaticSyncPausedNetworks = mutableSetOf<String>()

    /** Starts a new public synchronization presentation and revokes older writers. */
    fun begin(
        networkId: String,
        requestCancellation: (() -> Unit)? = null,
    ): WalletHnsLiveSyncPresentationLease = synchronized(lock) {
        WalletHnsLiveSyncPresentationLease(networkId).also { lease ->
            entries[networkId] = Entry(
                lease = lease,
                presentation = WalletHnsLiveSyncPresentation.Preparing,
                requestCancellation = requestCancellation,
            )
        }
    }

    fun publishLive(
        lease: WalletHnsLiveSyncPresentationLease,
        progress: NativeWalletHnsLiveSyncProgress,
    ) {
        synchronized(lock) {
            entries[lease.networkId]
                ?.takeIf { entry -> entry.lease === lease && entry.acceptsLiveProgress }
                ?.let { entry ->
                    entry.lastProgress = progress
                    entry.presentation = if (entry.presentation is WalletHnsLiveSyncPresentation.Cancelling) {
                        WalletHnsLiveSyncPresentation.Cancelling(progress)
                    } else {
                        WalletHnsLiveSyncPresentation.Live(progress)
                    }
                }
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
                    entry.requestCancellation = null
                    entry.acceptsLiveProgress = false
                }
        }
    }

    fun latest(networkId: String): WalletHnsLiveSyncPresentation? =
        synchronized(lock) { entries[networkId]?.presentation }

    fun canRequestCancellation(networkId: String): Boolean = synchronized(lock) {
        entries[networkId]?.let { entry ->
            entry.requestCancellation != null &&
                (entry.presentation == null ||
                    entry.presentation is WalletHnsLiveSyncPresentation.Preparing ||
                    entry.presentation is WalletHnsLiveSyncPresentation.Live)
        } == true
    }

    fun requestCancellation(networkId: String): Boolean {
        val request = synchronized(lock) {
            val entry = entries[networkId] ?: return@synchronized null
            val cancellation = entry.requestCancellation ?: return@synchronized null
            entry.lease.cancellationRequested.set(true)
            entry.presentation = WalletHnsLiveSyncPresentation.Cancelling(entry.lastProgress)
            entry.requestCancellation = null
            automaticSyncPausedNetworks.add(networkId)
            cancellation
        }
        request?.invoke()
        return request != null
    }

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

    fun automaticSyncIsPaused(networkId: String): Boolean =
        synchronized(lock) { networkId in automaticSyncPausedNetworks }

    fun resumeAutomaticSync(networkId: String) {
        synchronized(lock) { automaticSyncPausedNetworks.remove(networkId) }
    }

    private data class Entry(
        val lease: WalletHnsLiveSyncPresentationLease,
        var presentation: WalletHnsLiveSyncPresentation? = null,
        var lastProgress: NativeWalletHnsLiveSyncProgress? = null,
        var requestCancellation: (() -> Unit)? = null,
        var acceptsLiveProgress: Boolean = true,
    )
}

/** A live writer still owns the wallet controller and storage lease. */
internal fun walletHnsPresentationMayAcquireStorage(
    presentation: WalletHnsLiveSyncPresentation?,
): Boolean = presentation !is WalletHnsLiveSyncPresentation.Live
    && presentation !is WalletHnsLiveSyncPresentation.Preparing
    && presentation !is WalletHnsLiveSyncPresentation.Cancelling
