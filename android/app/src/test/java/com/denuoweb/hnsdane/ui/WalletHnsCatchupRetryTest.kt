package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.wallet.NativeWalletHnsCatchupProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletHnsCatchupRetryTest {
    @Test
    fun degradedTransportUsesPeerMaintenanceBackoff() {
        assertEquals(
            HNS_CATCHUP_DEGRADED_RETRY_DELAY_MILLIS,
            directHnsCatchupRetryDelayMillis(NativeWalletHnsCatchupProgress.HeaderState.Degraded),
        )
    }

    @Test
    fun advancingCatchupRetainsShortContinuationDelay() {
        for (state in listOf(
            NativeWalletHnsCatchupProgress.HeaderState.Current,
            NativeWalletHnsCatchupProgress.HeaderState.Syncing,
        )) {
            assertEquals(
                HNS_CATCHUP_PROGRESS_RETRY_DELAY_MILLIS,
                directHnsCatchupRetryDelayMillis(state),
            )
        }
    }
}
