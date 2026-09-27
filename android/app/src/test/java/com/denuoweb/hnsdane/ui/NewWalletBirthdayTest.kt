package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NewWalletBirthdayTest {
    @Test
    fun creationIntentContinuesOnlyFromReadyEmptyForegroundWallet() {
        assertEquals(
            true,
            walletPendingCreationMayContinue(
                requested = true,
                foreground = true,
                busy = false,
                hasCurrentAuthenticatedHeight = true,
                hasDurableWallet = false,
                hasController = false,
                hasUnconfirmedRecovery = false,
            ),
        )
        assertEquals(
            false,
            walletPendingCreationMayContinue(
                requested = true,
                foreground = false,
                busy = false,
                hasCurrentAuthenticatedHeight = true,
                hasDurableWallet = false,
                hasController = false,
                hasUnconfirmedRecovery = false,
            ),
        )
        assertEquals(
            false,
            walletPendingCreationMayContinue(
                requested = true,
                foreground = true,
                busy = false,
                hasCurrentAuthenticatedHeight = true,
                hasDurableWallet = true,
                hasController = false,
                hasUnconfirmedRecovery = false,
            ),
        )
    }

    @Test
    fun creationRequiresCurrentAuthenticatedHeightForExactNetwork() {
        assertEquals(
            345_238L,
            authenticatedNewWalletBirthdayHeight(
                HandshakeNetwork.Mainnet,
                "mainnet",
                true,
                345_238L,
            ),
        )
        assertEquals(
            null,
            authenticatedNewWalletBirthdayHeight(
                HandshakeNetwork.Mainnet,
                "mainnet",
                false,
                345_238L,
            ),
        )
        assertEquals(
            null,
            authenticatedNewWalletBirthdayHeight(
                HandshakeNetwork.Mainnet,
                "testnet",
                true,
                345_238L,
            ),
        )
    }
}
