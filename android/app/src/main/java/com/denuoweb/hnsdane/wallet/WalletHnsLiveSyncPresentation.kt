package com.denuoweb.hnsdane.wallet

/**
 * Process-local display state for a sync that can outlive a WalletActivity
 * instance. The durable source of truth remains the encrypted native wallet;
 * this cache holds only the last public stage and verified heights so backing
 * out and returning does not look like progress was discarded.
 */
internal sealed interface WalletHnsLiveSyncPresentation {
    data class Live(val progress: NativeWalletHnsLiveSyncProgress) :
        WalletHnsLiveSyncPresentation

    data class Catchup(val progress: NativeWalletHnsCatchupProgress) :
        WalletHnsLiveSyncPresentation
}

internal object WalletHnsLiveSyncPresentationCache {
    private val lock = Any()
    private val presentations = mutableMapOf<String, WalletHnsLiveSyncPresentation>()

    fun publishLive(networkId: String, progress: NativeWalletHnsLiveSyncProgress) {
        synchronized(lock) {
            presentations[networkId] = WalletHnsLiveSyncPresentation.Live(progress)
        }
    }

    fun publishCatchup(networkId: String, progress: NativeWalletHnsCatchupProgress) {
        synchronized(lock) {
            presentations[networkId] = WalletHnsLiveSyncPresentation.Catchup(progress)
        }
    }

    fun latest(networkId: String): WalletHnsLiveSyncPresentation? =
        synchronized(lock) { presentations[networkId] }

    fun clear(networkId: String) {
        synchronized(lock) { presentations.remove(networkId) }
    }
}
