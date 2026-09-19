package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletBrowserBoundaryTest {
    @Test
    fun browserTransitionRetiresIdleSigningAuthority() {
        assertFalse(walletIdleSessionMayRetainAcrossScreen(browserNavigationRequested = true))
    }

    @Test
    fun ordinaryNonBrowserTransitionCanUseExistingBoundedGrace() {
        assertTrue(walletIdleSessionMayRetainAcrossScreen(browserNavigationRequested = false))
    }

    @Test
    fun systemCredentialTransitionRetainsOnlyTheCurrentWalletLease() {
        assertTrue(walletCredentialTransitionMayRetain(true, true))
        assertFalse(walletCredentialTransitionMayRetain(false, true))
        assertFalse(walletCredentialTransitionMayRetain(true, false))
    }

    @Test
    fun staleNativeCompletionCannotClearANewerWalletOperation() {
        assertTrue(walletOperationCompletionOwnsBusyState(true, 8L, 8L))
        assertFalse(walletOperationCompletionOwnsBusyState(true, 9L, 8L))
        assertFalse(walletOperationCompletionOwnsBusyState(false, 8L, 8L))
    }
}
