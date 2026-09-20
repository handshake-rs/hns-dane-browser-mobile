package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletActiveSwapHnsRefreshTest {
    @Test
    fun `new header beyond wallet snapshot schedules one refresh`() {
        assertEquals(
            347_849L,
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_848,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = null,
            ),
        )
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_848,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = 347_849,
            ),
        )
    }

    @Test
    fun `current or unavailable header does not schedule`() {
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_849,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = null,
            ),
        )
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_849,
                observedHeaderHeight = null,
                attemptedHeaderHeight = null,
            ),
        )
    }

    @Test
    fun `missing wallet snapshot schedules the newest observed height once`() {
        assertEquals(
            347_849L,
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = null,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = 347_848,
            ),
        )
    }
}
