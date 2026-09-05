package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletNetworkTransportTest {
    @Test
    fun cellularWarningRequiresAnUnlockedWalletOnAPositiveCellularPath() {
        assertTrue(walletCellularDataWarningVisible(true, WalletNetworkTransport.Cellular))
        assertFalse(walletCellularDataWarningVisible(false, WalletNetworkTransport.Cellular))
        assertFalse(walletCellularDataWarningVisible(true, WalletNetworkTransport.Wifi))
        assertFalse(walletCellularDataWarningVisible(true, WalletNetworkTransport.Other))
    }
}
