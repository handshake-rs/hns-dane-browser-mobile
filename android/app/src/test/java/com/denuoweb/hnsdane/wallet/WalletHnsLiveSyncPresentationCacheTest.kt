package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletHnsLiveSyncPresentationCacheTest {
    @Test
    fun replacementActivityWaitsForLiveWriterBeforeAcquiringStorage() {
        assertFalse(
            walletHnsPresentationMayAcquireStorage(
                WalletHnsLiveSyncPresentation.Preparing,
            ),
        )
        assertFalse(
            walletHnsPresentationMayAcquireStorage(
                WalletHnsLiveSyncPresentation.Live(liveProgress(scannedHeight = 10L)),
            ),
        )
        assertFalse(
            walletHnsPresentationMayAcquireStorage(
                WalletHnsLiveSyncPresentation.Cancelling(liveProgress(scannedHeight = 10L)),
            ),
        )
        assertTrue(
            walletHnsPresentationMayAcquireStorage(
                WalletHnsLiveSyncPresentation.Catchup(catchupProgress(scannedHeight = 20L)),
            ),
        )
        assertTrue(walletHnsPresentationMayAcquireStorage(null))
    }

    @Test
    fun cancellationCapabilityIsSingleUseAndKeepsStorageFenced() {
        val network = "cache-cancellation-test"
        var cancellationRequests = 0
        val lease = WalletHnsLiveSyncPresentationCache.begin(network) {
            cancellationRequests += 1
        }
        val live = liveProgress(scannedHeight = 25L)
        WalletHnsLiveSyncPresentationCache.publishLive(lease, live)

        assertTrue(WalletHnsLiveSyncPresentationCache.canRequestCancellation(network))
        assertTrue(WalletHnsLiveSyncPresentationCache.requestCancellation(network))
        assertEquals(1, cancellationRequests)
        assertTrue(lease.cancellationRequested.get())
        assertTrue(WalletHnsLiveSyncPresentationCache.automaticSyncIsPaused(network))
        assertEquals(
            WalletHnsLiveSyncPresentation.Cancelling(live),
            WalletHnsLiveSyncPresentationCache.latest(network),
        )
        assertFalse(
            walletHnsPresentationMayAcquireStorage(
                WalletHnsLiveSyncPresentationCache.latest(network),
            ),
        )
        assertFalse(WalletHnsLiveSyncPresentationCache.requestCancellation(network))
        assertEquals(1, cancellationRequests)
        WalletHnsLiveSyncPresentationCache.resumeAutomaticSync(network)
        assertFalse(WalletHnsLiveSyncPresentationCache.automaticSyncIsPaused(network))
        WalletHnsLiveSyncPresentationCache.clear(network)
    }

    @Test
    fun terminalCatchupCannotBeOverwrittenByALateLivePoll() {
        val network = "cache-race-test"
        val lease = WalletHnsLiveSyncPresentationCache.begin(network)
        val live = liveProgress(scannedHeight = 10L)
        val catchup = catchupProgress(scannedHeight = 20L)

        WalletHnsLiveSyncPresentationCache.publishLive(lease, live)
        WalletHnsLiveSyncPresentationCache.finishCatchup(lease, catchup)
        // This models a mailbox call that began before the poller was stopped
        // and returned after the bounded native synchronization completed.
        WalletHnsLiveSyncPresentationCache.publishLive(lease, live)

        assertEquals(
            WalletHnsLiveSyncPresentation.Catchup(catchup),
            WalletHnsLiveSyncPresentationCache.latest(network),
        )
        WalletHnsLiveSyncPresentationCache.clear(network)
    }

    @Test
    fun clearedOrSupersededLeaseCannotResurrectOldProgress() {
        val network = "cache-supersession-test"
        val oldLease = WalletHnsLiveSyncPresentationCache.begin(network)
        val live = liveProgress(scannedHeight = 10L)

        WalletHnsLiveSyncPresentationCache.clear(oldLease)
        WalletHnsLiveSyncPresentationCache.publishLive(oldLease, live)
        assertNull(WalletHnsLiveSyncPresentationCache.latest(network))

        val currentLease = WalletHnsLiveSyncPresentationCache.begin(network)
        WalletHnsLiveSyncPresentationCache.publishLive(oldLease, live)
        WalletHnsLiveSyncPresentationCache.publishLive(currentLease, live)
        assertEquals(
            WalletHnsLiveSyncPresentation.Live(live),
            WalletHnsLiveSyncPresentationCache.latest(network),
        )
        WalletHnsLiveSyncPresentationCache.clear(network)
    }

    private fun liveProgress(scannedHeight: Long) = NativeWalletHnsLiveSyncProgress(
        stage = NativeWalletHnsLiveSyncProgress.Stage.Scanning,
        headerState = NativeWalletHnsCatchupProgress.HeaderState.Current,
        headerRound = 0,
        headerRetries = 0,
        headerTipHeight = 100L,
        birthdayHeight = 0L,
        scannedHeight = scannedHeight,
        scanTargetHeight = 100L,
    )

    private fun catchupProgress(scannedHeight: Long) = NativeWalletHnsCatchupProgress(
        headerState = NativeWalletHnsCatchupProgress.HeaderState.Current,
        headerTipHeight = 100L,
        birthdayHeight = 0L,
        scannedHeight = scannedHeight,
        scanTargetHeight = 100L,
    )
}
