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

    @Test
    fun scannedPaymentReopensSynchronizesAndThenPresentsWithoutLosingItsRequest() {
        assertTrue(
            paymentContinuation(hasController = false) ==
                WalletPendingPaymentContinuation.Unlock,
        )
        assertTrue(
            paymentContinuation(controllerUnlocked = false) ==
                WalletPendingPaymentContinuation.Unlock,
        )
        assertTrue(
            paymentContinuation(hasCurrentSnapshot = false) ==
                WalletPendingPaymentContinuation.Synchronize,
        )
        assertTrue(paymentContinuation() == WalletPendingPaymentContinuation.Present)
    }

    @Test
    fun externalPaymentAndBusyWalletDoNotImplicitlyUnlock() {
        assertTrue(
            paymentContinuation(resumeAfterScanner = false, controllerUnlocked = false) ==
                WalletPendingPaymentContinuation.Wait,
        )
        assertTrue(paymentContinuation(busy = true) == WalletPendingPaymentContinuation.Wait)
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

    private fun paymentContinuation(
        resumeAfterScanner: Boolean = true,
        busy: Boolean = false,
        hasController: Boolean = true,
        controllerUnlocked: Boolean = true,
        hasCurrentSnapshot: Boolean = true,
    ): WalletPendingPaymentContinuation = walletPendingPaymentContinuation(
        hasPendingPayment = true,
        resumeAfterScanner = resumeAfterScanner,
        foreground = true,
        windowHasFocus = true,
        busy = busy,
        dialogVisible = false,
        hasController = hasController,
        controllerUnlocked = controllerUnlocked,
        hasHnsValue = true,
        hasCurrentSnapshot = hasCurrentSnapshot,
    )
}
