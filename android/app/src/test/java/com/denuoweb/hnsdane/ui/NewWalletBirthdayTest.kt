package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.net.HeaderSnapshotInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class NewWalletBirthdayTest {
    @Test
    fun authenticatedCurrentHeightBecomesNewMainnetWalletBirthday() {
        assertEquals(
            345_238L,
            newWalletBirthdayHeight(HandshakeNetwork.Mainnet, 345_238L),
        )
    }

    @Test
    fun mainnetFallsBackToPinnedCheckpointWithoutAuthenticatedCurrentness() {
        assertEquals(
            HeaderSnapshotInstaller.SNAPSHOT_HEIGHT,
            newWalletBirthdayHeight(HandshakeNetwork.Mainnet, null),
        )
    }

    @Test
    fun staleOrInvalidHeightCannotMoveMainnetBirthdayBehindCheckpoint() {
        assertEquals(
            HeaderSnapshotInstaller.SNAPSHOT_HEIGHT,
            newWalletBirthdayHeight(HandshakeNetwork.Mainnet, 299_999L),
        )
        assertEquals(
            HeaderSnapshotInstaller.SNAPSHOT_HEIGHT,
            newWalletBirthdayHeight(HandshakeNetwork.Mainnet, -1L),
        )
    }

    @Test
    fun nonMainnetUsesAuthenticatedHeightOrGenesisFallback() {
        assertEquals(42L, newWalletBirthdayHeight(HandshakeNetwork.Testnet, 42L))
        assertEquals(0L, newWalletBirthdayHeight(HandshakeNetwork.Testnet, null))
        assertEquals(0L, newWalletBirthdayHeight(HandshakeNetwork.Regtest, null))
    }
}
