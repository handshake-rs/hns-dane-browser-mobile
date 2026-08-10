package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStorageOwnershipTest {
    @Test
    fun newerOwnerWaitsForOldCleanupAndStaleOwnerCannotPublish() {
        val gate = WalletStorageOwnershipGate()
        var firstRevocations = 0
        val first = gate.newOwner("/wallet/mainnet/wallet.sqlite3") {
            firstRevocations += 1
        }
        var firstLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(gate.acquire(first) { firstLease = it })
        assertNotNull(firstLease)

        val second = gate.newOwner("/wallet/mainnet/wallet.sqlite3") {}
        assertEquals(1, firstRevocations)
        assertFalse(gate.isCurrent(first, checkNotNull(firstLease)))
        var stalePublication = false
        assertFalse(
            gate.commitIfCurrent(first, checkNotNull(firstLease)) {
                stalePublication = true
            },
        )
        assertFalse(stalePublication)

        var secondLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(gate.acquire(second) { secondLease = it })
        assertNull(secondLease)

        assertTrue(gate.release(checkNotNull(firstLease)))
        assertNotNull(secondLease)
        assertTrue(gate.isCurrent(second, checkNotNull(secondLease)))
        assertFalse(gate.acquire(first) {})
    }

    @Test
    fun networkNamespacesAndPathLeasesAreIndependent() {
        val mainnet = walletStorageNamespace("mainnet")
        val testnet = walletStorageNamespace("testnet")
        assertNotEquals(mainnet.directoryName, testnet.directoryName)
        assertNotEquals(mainnet.preferencesName, testnet.preferencesName)
        assertNotEquals(mainnet.keyAlias, testnet.keyAlias)
        assertNotEquals(mainnet.wrappingContext, testnet.wrappingContext)

        val gate = WalletStorageOwnershipGate()
        val mainOwner = gate.newOwner("/wallet/${mainnet.directoryName}/wallet.sqlite3") {}
        val testOwner = gate.newOwner("/wallet/${testnet.directoryName}/wallet.sqlite3") {}
        var mainLease: WalletStorageOwnershipGate.Lease? = null
        var testLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(gate.acquire(mainOwner) { mainLease = it })
        assertTrue(gate.acquire(testOwner) { testLease = it })
        assertNotNull(mainLease)
        assertNotNull(testLease)
    }

    @Test
    fun busyOrStaleSetupCannotReachDestructiveStorageInspection() {
        assertTrue(
            walletSetupMayInspectStorage(
                foreground = true,
                ownsCurrentLease = true,
                busy = false,
                hasController = false,
                hasUnconfirmedKey = false,
            ),
        )
        assertFalse(
            walletSetupMayInspectStorage(
                foreground = true,
                ownsCurrentLease = true,
                busy = true,
                hasController = false,
                hasUnconfirmedKey = false,
            ),
        )
        assertFalse(
            walletSetupMayInspectStorage(
                foreground = true,
                ownsCurrentLease = false,
                busy = false,
                hasController = false,
                hasUnconfirmedKey = false,
            ),
        )
        assertFalse(
            walletSetupMayInspectStorage(
                foreground = false,
                ownsCurrentLease = true,
                busy = false,
                hasController = false,
                hasUnconfirmedKey = false,
            ),
        )
    }
}
