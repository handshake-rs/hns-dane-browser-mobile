package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletNameImportAuthorityTest {
    @Test
    fun importAdmissionRequiresExactForegroundDurableUnlockedConfiguredIdleReads() {
        val authority = authority()
        val exact = state(authority)
        assertTrue(walletNameImportMayBegin(authority, exact))
        assertFalse(walletNameImportMayBegin(authority, exact.copy(unlocked = false)))
        assertFalse(walletNameImportMayBegin(authority, exact.copy(hnsReadsConfigured = false)))
        assertFalse(
            walletNameImportMayBegin(
                authority,
                exact.copy(readState = exact.readState.copy(foreground = false)),
            ),
        )
        assertFalse(
            walletNameImportMayBegin(
                authority,
                exact.copy(readState = exact.readState.copy(protectedStorageAvailable = false)),
            ),
        )
        assertFalse(
            walletNameImportMayBegin(
                authority,
                exact.copy(readState = exact.readState.copy(reopenedDurableWallet = false)),
            ),
        )
        assertFalse(
            walletNameImportMayBegin(
                authority,
                exact.copy(readState = exact.readState.copy(confirmedPersistentWallet = false)),
            ),
        )
        assertFalse(
            walletNameImportMayBegin(
                authority,
                exact.copy(readState = exact.readState.copy(operationInFlight = true)),
            ),
        )
    }

    @Test
    fun staleAuthorityLeaseAndCompletionEpochCannotPublish() {
        val fixture = leaseFixture()
        val expected = authority(fixture.lease)
        val inFlight = state(expected).let {
            it.copy(readState = it.readState.copy(operationInFlight = true))
        }
        assertTrue(walletNameImportMayPublish(expected, inFlight, 7, 7))
        assertTrue(
            walletNameImportMayPublish(
                expected,
                inFlight.copy(
                    readState = inFlight.readState.copy(protectedStorageAvailable = false),
                    unlocked = false,
                    hnsReadsConfigured = false,
                ),
                7,
                7,
            ),
        )
        assertFalse(walletNameImportMayPublish(expected, inFlight, 7, 8))
        assertFalse(
            walletNameImportMayPublish(
                expected,
                inFlight.copy(readState = inFlight.readState.copy(operationInFlight = false)),
                7,
                7,
            ),
        )
        val replacement = authority(fixture.lease, handle = 2, generation = 2)
        assertFalse(
            walletNameImportMayPublish(
                expected,
                inFlight.copy(readState = inFlight.readState.copy(authority = replacement)),
                7,
                7,
            ),
        )
        assertTrue(fixture.gate.release(fixture.lease))
        assertFalse(walletNameImportMayPublish(expected, inFlight, 7, 7))
    }

    private fun state(authority: WalletReadBootstrapAuthority) = WalletNameImportState(
        readState = WalletReadBootstrapState(
            authority = authority,
            foreground = true,
            protectedStorageAvailable = true,
            reopenedDurableWallet = true,
            confirmedPersistentWallet = true,
            hasUnconfirmedRecovery = false,
            operationInFlight = false,
            retirementBlocked = false,
        ),
        unlocked = true,
        hnsReadsConfigured = true,
    )

    private fun authority(
        lease: WalletStorageOwnershipGate.Lease = leaseFixture().lease,
        handle: Long = 1,
        generation: Long = 1,
    ): WalletReadBootstrapAuthority = checkNotNull(
        WalletReadBootstrapAuthority.create(
            networkId = "mainnet",
            databasePath = PATH,
            storageLease = lease,
            walletHandle = handle,
            authorityGeneration = generation,
        ),
    )

    private fun leaseFixture(): LeaseFixture {
        val gate = WalletStorageOwnershipGate()
        val owner = gate.newOwner(PATH) {}
        var lease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(gate.acquire(owner) { lease = it })
        return LeaseFixture(gate, checkNotNull(lease))
    }

    private data class LeaseFixture(
        val gate: WalletStorageOwnershipGate,
        val lease: WalletStorageOwnershipGate.Lease,
    )

    private companion object {
        const val PATH = "/wallet/wallet-v1-mainnet/wallet.sqlite3"
    }
}
