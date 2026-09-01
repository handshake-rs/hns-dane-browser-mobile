package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletPendingUnlockTest {
    @Test
    fun queuedTapRunsOnceControllerBootstrapIsReady() {
        assertTrue(
            walletPendingUnlockMayRun(
                requested = true,
                foreground = true,
                busy = false,
                hasLease = true,
                hasController = true,
                hasUnconfirmedRecovery = false,
            ),
        )
    }

    @Test
    fun queuedTapWaitsForLeaseControllerAndBootstrap() {
        assertFalse(ready(hasLease = false))
        assertFalse(ready(hasController = false))
        assertFalse(ready(busy = true))
    }

    @Test
    fun queuedTapCannotRunAfterScreenLeavesForeground() {
        assertFalse(ready(foreground = false))
        assertFalse(ready(requested = false))
        assertFalse(ready(hasUnconfirmedRecovery = true))
    }

    private fun ready(
        requested: Boolean = true,
        foreground: Boolean = true,
        busy: Boolean = false,
        hasLease: Boolean = true,
        hasController: Boolean = true,
        hasUnconfirmedRecovery: Boolean = false,
    ): Boolean = walletPendingUnlockMayRun(
        requested = requested,
        foreground = foreground,
        busy = busy,
        hasLease = hasLease,
        hasController = hasController,
        hasUnconfirmedRecovery = hasUnconfirmedRecovery,
    )
}
