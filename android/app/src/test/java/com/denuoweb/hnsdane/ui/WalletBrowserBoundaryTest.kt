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
}
