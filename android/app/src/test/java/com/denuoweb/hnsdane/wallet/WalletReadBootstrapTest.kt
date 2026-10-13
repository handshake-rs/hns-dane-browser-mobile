package com.denuoweb.hnsdane.wallet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletReadBootstrapTest {
    @Test
    fun interruptedDirectHnsJournalIsHealedBeforeTheNextBoundedSync() {
        val calls = mutableListOf<String>()
        var pending = true

        val result = beginDirectHnsSynchronizationWithRecovery(
            begin = {
                calls += "begin"
                check(!pending) { "interrupted synchronization is pending" }
            },
            recoverInterrupted = {
                calls += "recover"
                pending = false
            },
        )

        assertEquals(DirectHnsSynchronizationJournalStart.Recovered, result)
        assertEquals(listOf("begin", "recover", "begin"), calls)
    }

    @Test
    fun ordinaryDirectHnsJournalStartDoesNotRunRecovery() {
        val calls = mutableListOf<String>()

        val result = beginDirectHnsSynchronizationWithRecovery(
            begin = { calls += "begin" },
            recoverInterrupted = { calls += "recover" },
        )

        assertEquals(DirectHnsSynchronizationJournalStart.Started, result)
        assertEquals(listOf("begin"), calls)
    }

    @Test
    fun unrecoverableDirectHnsJournalFailureStaysClosed() {
        val calls = mutableListOf<String>()

        val result = beginDirectHnsSynchronizationWithRecovery(
            begin = {
                calls += "begin"
                error("keystore unavailable")
            },
            recoverInterrupted = {
                calls += "recover"
                error("recovery floor unavailable")
            },
        )

        assertEquals(DirectHnsSynchronizationJournalStart.Failed, result)
        assertEquals(listOf("begin", "recover"), calls)
    }

    @Test
    fun authorityCanonicalizesAndBindsEveryLifecycleDimension() {
        val aliasedPath = "/wallet/wallet-v1-mainnet/../wallet-v1-mainnet/wallet.sqlite3"
        val exactLease = newLease(aliasedPath)
        val authority = authority(databasePath = aliasedPath, storageLease = exactLease)
        assertEquals(File(aliasedPath).canonicalPath, authority.databasePath)
        val diagnostics = authority.toString()
        assertFalse(diagnostics.contains(authority.databasePath))
        assertFalse(diagnostics.contains("wallet.sqlite3"))
        assertFalse(diagnostics.contains("walletHandle=1"))

        assertNull(WalletReadBootstrapAuthority.create(
            "unknown", aliasedPath, exactLease, 1, 1,
        ))
        assertNull(WalletReadBootstrapAuthority.create(
            "mainnet", "wallet.sqlite3", exactLease, 1, 1,
        ))
        assertNull(WalletReadBootstrapAuthority.create(
            "mainnet", testnetPath, newLease(testnetPath), 1, 1,
        ))
        assertNull(WalletReadBootstrapAuthority.create(
            "mainnet", aliasedPath, newLease(testnetPath), 1, 1,
        ))
        assertNull(WalletReadBootstrapAuthority.create(
            "mainnet", aliasedPath, exactLease, 0, 1,
        ))
        assertNull(WalletReadBootstrapAuthority.create(
            "mainnet", aliasedPath, exactLease, 1, 0,
        ))

        val exact = state(authority)
        assertTrue(walletReadBootstrapMayInstall(authority, exact))
        for (changed in listOf(
            authority(networkId = "testnet", databasePath = testnetPath),
            authority(databasePath = "/other/wallet-v1-mainnet/wallet.sqlite3"),
            authority(storageLease = newLease(aliasedPath)),
            authority(storageLease = laterGenerationLease(aliasedPath)),
            authority(storageLease = exactLease, walletHandle = 2),
            authority(storageLease = exactLease, authorityGeneration = 2),
        )) {
            assertFalse(walletReadBootstrapMayInstall(authority, exact.copy(authority = changed)))
        }
    }

    @Test
    fun admissionRequiresForegroundProtectedConfirmedReopenedIdleAuthority() {
        val authority = authority()
        val exact = state(authority)
        assertTrue(walletReadBootstrapMayInstall(authority, exact))
        assertFalse(walletReadBootstrapMayInstall(authority, exact.copy(foreground = false)))
        assertFalse(
            walletReadBootstrapMayInstall(
                authority,
                exact.copy(protectedStorageAvailable = false),
            ),
        )
        assertFalse(
            walletReadBootstrapMayInstall(authority, exact.copy(reopenedDurableWallet = false)),
        )
        assertFalse(
            walletReadBootstrapMayInstall(authority, exact.copy(confirmedPersistentWallet = false)),
        )
        assertFalse(
            walletReadBootstrapMayInstall(authority, exact.copy(hasUnconfirmedRecovery = true)),
        )
        assertFalse(walletReadBootstrapMayInstall(authority, exact.copy(operationInFlight = true)))
        assertFalse(walletReadBootstrapMayInstall(authority, exact.copy(retirementBlocked = true)))
        assertFalse(walletReadBootstrapMayInstall(authority, exact.copy(authority = null)))
    }

    @Test
    fun admissionRequiresCurrentLeaseFromItsIssuingGate() {
        val currentFixture = leaseFixture(mainnetPath)
        val currentAuthority = authority(storageLease = currentFixture.lease)
        assertTrue(currentAuthority.hasCurrentStorageLease())
        assertTrue(walletReadBootstrapMayInstall(currentAuthority, state(currentAuthority)))

        val releasedFixture = leaseFixture(mainnetPath)
        val releasedAuthority = authority(storageLease = releasedFixture.lease)
        assertTrue(releasedFixture.gate.release(releasedFixture.lease))
        assertFalse(releasedAuthority.hasCurrentStorageLease())
        assertFalse(walletReadBootstrapMayInstall(releasedAuthority, state(releasedAuthority)))

        val revokedFixture = leaseFixture(mainnetPath)
        val revokedAuthority = authority(storageLease = revokedFixture.lease)
        revokedFixture.gate.newOwner(mainnetPath) {}
        assertFalse(revokedAuthority.hasCurrentStorageLease())
        assertFalse(walletReadBootstrapMayInstall(revokedAuthority, state(revokedAuthority)))

        val staleFixture = leaseFixture(mainnetPath)
        val staleAuthority = authority(storageLease = staleFixture.lease)
        assertTrue(staleFixture.gate.release(staleFixture.lease))
        val replacementOwner = staleFixture.gate.newOwner(mainnetPath) {}
        var replacementLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(staleFixture.gate.acquire(replacementOwner) { replacementLease = it })
        val replacementAuthority = authority(
            storageLease = checkNotNull(replacementLease),
            authorityGeneration = 2,
        )
        assertFalse(staleAuthority.hasCurrentStorageLease())
        assertFalse(walletReadBootstrapMayInstall(staleAuthority, state(staleAuthority)))
        assertTrue(replacementAuthority.hasCurrentStorageLease())
        assertTrue(walletReadBootstrapMayInstall(
            replacementAuthority,
            state(replacementAuthority),
        ))
    }

    private fun state(authority: WalletReadBootstrapAuthority) = WalletReadBootstrapState(
        authority = authority,
        foreground = true,
        protectedStorageAvailable = true,
        reopenedDurableWallet = true,
        confirmedPersistentWallet = true,
        hasUnconfirmedRecovery = false,
        operationInFlight = false,
        retirementBlocked = false,
    )

    private fun authority(
        networkId: String = "mainnet",
        databasePath: String = mainnetPath,
        storageLease: WalletStorageOwnershipGate.Lease = newLease(databasePath),
        walletHandle: Long = 1,
        authorityGeneration: Long = 1,
    ): WalletReadBootstrapAuthority = checkNotNull(
        WalletReadBootstrapAuthority.create(
            networkId,
            databasePath,
            storageLease,
            walletHandle,
            authorityGeneration,
        ),
    )

    private fun leaseFixture(path: String): LeaseFixture {
        val storage = WalletStorageOwnershipGate()
        val owner = storage.newOwner(path) {}
        var lease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(storage.acquire(owner) { lease = it })
        return LeaseFixture(storage, checkNotNull(lease))
    }

    private fun newLease(path: String): WalletStorageOwnershipGate.Lease =
        leaseFixture(path).lease

    private fun laterGenerationLease(path: String): WalletStorageOwnershipGate.Lease {
        val storage = WalletStorageOwnershipGate()
        val firstOwner = storage.newOwner(path) {}
        var firstLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(storage.acquire(firstOwner) { firstLease = it })
        assertTrue(storage.release(checkNotNull(firstLease)))
        val replacementOwner = storage.newOwner(path) {}
        var replacementLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(storage.acquire(replacementOwner) { replacementLease = it })
        return checkNotNull(replacementLease)
    }

    private companion object {
        const val mainnetPath = "/wallet/wallet-v1-mainnet/wallet.sqlite3"
        const val testnetPath = "/wallet/wallet-v1-testnet/wallet.sqlite3"
    }

    private data class LeaseFixture(
        val gate: WalletStorageOwnershipGate,
        val lease: WalletStorageOwnershipGate.Lease,
    )
}
