package com.denuoweb.hnsdane.wallet

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletDeletionTest {
    @Test
    fun deletionRequiresExactForegroundPersistentOwnershipAndAccountScope() {
        val expected = validScope()
        assertTrue(
            walletDeletionMayProceed(
                expected = expected,
                current = expected.copy(),
                foreground = true,
                busy = false,
                confirmedPersistentWallet = true,
                hasUnconfirmedKey = false,
            ),
        )

        listOf(
            expected.copy(lifecycleEpoch = 8),
            expected.copy(ownerGeneration = 12),
            expected.copy(leaseGeneration = 14),
            expected.copy(storagePath = "/wallet/wallet-v1-testnet/wallet.sqlite3"),
            expected.copy(networkId = "testnet"),
            expected.copy(walletHandle = 18),
            expected.copy(accountId = "02".repeat(16)),
        ).forEach { changed ->
            assertFalse(
                walletDeletionMayProceed(
                    expected = expected,
                    current = changed,
                    foreground = true,
                    busy = false,
                    confirmedPersistentWallet = true,
                    hasUnconfirmedKey = false,
                ),
            )
        }
        assertFalse(mayProceed(expected, foreground = false))
        assertFalse(mayProceed(expected, busy = true))
        assertFalse(mayProceed(expected, confirmedPersistentWallet = false))
        assertFalse(mayProceed(expected, hasUnconfirmedKey = true))
    }

    @Test
    fun malformedOrUnsupportedScopeFailsClosed() {
        val expected = validScope()
        listOf(
            expected.copy(lifecycleEpoch = 0),
            expected.copy(ownerGeneration = 0),
            expected.copy(leaseGeneration = 0),
            expected.copy(storagePath = ""),
            expected.copy(storagePath = "/wallet/mainnet/wallet.sqlite3"),
            expected.copy(storagePath = "/wallet/wallet-v1-testnet/wallet.sqlite3"),
            expected.copy(networkId = "simnet"),
            expected.copy(walletHandle = 0),
            expected.copy(accountId = "01".repeat(15)),
            expected.copy(accountId = "AB".repeat(16)),
            expected.copy(accountId = "00".repeat(16)),
        ).forEach { malformed ->
            assertFalse(mayProceed(malformed))
        }
    }

    @Test
    fun typedConfirmationIsExactAndCaseSensitive() {
        assertTrue(walletDeleteConfirmationMatches("DELETE"))
        assertFalse(walletDeleteConfirmationMatches(null))
        assertFalse(walletDeleteConfirmationMatches("delete"))
        assertFalse(walletDeleteConfirmationMatches(" DELETE"))
        assertFalse(walletDeleteConfirmationMatches("DELETE "))
        assertFalse(walletDeleteConfirmationMatches("DELET"))
    }

    @Test
    fun processLatchDoesNotInferDurabilityFromFailedPreferenceMemoryState() {
        val latch = WalletDeletionProcessLatch()
        val namespace = "hns-wallet-database-wrapping-v1-mainnet"

        assertEquals(WalletDeletionLatchState.None, latch.observe(namespace, false))
        latch.begin(namespace)
        assertEquals(
            WalletDeletionLatchState.AwaitingDurableRequest,
            // Mirrors commit(false): SharedPreferences may now report the marker in memory.
            latch.observe(namespace, persistedMarker = true),
        )
        latch.markDurable(namespace)
        assertEquals(
            WalletDeletionLatchState.DurableRequest,
            latch.observe(namespace, persistedMarker = true),
        )
    }

    @Test
    fun failedCompletionCannotPermitSameProcessNamespaceReuse() {
        val latch = WalletDeletionProcessLatch()
        val namespace = "hns-wallet-database-wrapping-v1-mainnet"
        latch.begin(namespace)
        latch.markDurable(namespace)

        assertEquals(
            WalletDeletionLatchState.DurableRequest,
            // Mirrors completion commit(false): Editor removal may be visible in memory.
            latch.observe(namespace, persistedMarker = false),
        )
        latch.complete(namespace)
        assertEquals(WalletDeletionLatchState.None, latch.observe(namespace, false))
    }

    @Test
    fun persistedMarkerRestoresDurableLatchAfterProcessRestart() {
        val latch = WalletDeletionProcessLatch()
        assertEquals(
            WalletDeletionLatchState.DurableRequest,
            latch.observe("hns-wallet-database-wrapping-v1-testnet", persistedMarker = true),
        )
    }

    @Test
    fun ambiguousControllerRetirementBlocksOnlyTheExactPathForProcessLifetime() {
        val latch = WalletControllerRetirementFailureLatch()
        val mainnet = "/wallet/wallet-v1-mainnet/wallet.sqlite3"
        val testnet = "/wallet/wallet-v1-testnet/wallet.sqlite3"

        assertFalse(latch.blocks(mainnet))
        latch.mark(mainnet)
        assertTrue(latch.blocks(mainnet))
        assertFalse(latch.blocks(testnet))
        assertThrows(IllegalArgumentException::class.java) { latch.mark("wallet.sqlite3") }
        assertThrows(IllegalArgumentException::class.java) { latch.mark("/wallet/mainnet") }
    }

    @Test
    fun everyControllerOperationRequiresNoRetirementFailureAndTheCurrentIdleLease() {
        assertTrue(walletControllerOperationMayBegin(false, false, true))
        assertFalse(walletControllerOperationMayBegin(true, false, true))
        assertFalse(walletControllerOperationMayBegin(false, true, true))
        assertFalse(walletControllerOperationMayBegin(false, false, false))
    }

    @Test
    fun controllerCloseAttemptsDestroyAfterLockAndUsesCloseAsAuthorityBoundary() {
        val events = mutableListOf<String>()
        assertTrue(
            closeWalletControllerForDeletion(
                lock = {
                    events += "lock"
                    false
                },
                close = {
                    events += "close"
                    true
                },
            ),
        )
        assertEquals(listOf("lock", "close"), events)

        events.clear()
        assertFalse(
            closeWalletControllerForDeletion(
                lock = {
                    events += "lock"
                    true
                },
                close = {
                    events += "close"
                    false
                },
            ),
        )
        assertEquals(listOf("lock", "close"), events)

        events.clear()
        assertTrue(
            closeWalletControllerForDeletion(
                lock = {
                    events += "lock"
                    true
                },
                close = {
                    events += "close"
                    true
                },
            ),
        )
        assertEquals(listOf("lock", "close"), events)
    }

    @Test
    fun keyFailureNeverReachesDatabaseFiles() {
        val events = mutableListOf<String>()
        val result = deleteConfirmedWalletStorage(
            requestDeletion = { events += "request" },
            deleteDatabaseKey = {
                events += "key"
                error("keystore unavailable")
            },
            deleteDatabaseFiles = {
                events += "files"
                true
            },
            finishDeletion = { events += "finish" },
        )

        assertEquals(WalletStorageDeletionResult.KeyDeletionFailed, result)
        assertEquals(listOf("request", "key"), events)
    }

    @Test
    fun fileFailureRetainsCleanupPendingAndSuccessfulSequenceIsOrdered() {
        val events = mutableListOf<String>()
        val pending = deleteConfirmedWalletStorage(
            requestDeletion = { events += "request" },
            deleteDatabaseKey = {
                events += "key"
                WalletDatabaseKeyDeletionResult.Removed
            },
            deleteDatabaseFiles = {
                events += "files"
                false
            },
            finishDeletion = { events += "finish" },
        )
        assertEquals(WalletStorageDeletionResult.FileCleanupPending, pending)
        assertEquals(listOf("request", "key", "files"), events)

        events.clear()
        val deleted = deleteConfirmedWalletStorage(
            requestDeletion = { events += "request" },
            deleteDatabaseKey = {
                events += "key"
                WalletDatabaseKeyDeletionResult.Removed
            },
            deleteDatabaseFiles = {
                events += "files"
                true
            },
            finishDeletion = { events += "finish" },
        )
        assertEquals(WalletStorageDeletionResult.Deleted, deleted)
        assertEquals(listOf("request", "key", "files", "finish"), events)
    }

    @Test
    fun keyRemovedWithMetadataCleanupFailureStillDeletesDatabase() {
        val events = mutableListOf<String>()
        val result = deleteConfirmedWalletStorage(
            requestDeletion = { events += "request-marker" },
            // Keystore alias removal succeeded; wrapped-metadata cleanup is retried when the
            // final marker transition fails, so database deletion must still be attempted.
            deleteDatabaseKey = {
                events += "wrapping-key-removed"
                WalletDatabaseKeyDeletionResult.RemovedMetadataCleanupPending
            },
            deleteDatabaseFiles = {
                events += "database-files"
                true
            },
            finishDeletion = {
                events += "metadata-cleanup-pending"
                error("preferences commit failed")
            },
        )

        assertEquals(WalletStorageDeletionResult.FileCleanupPending, result)
        assertEquals(
            listOf(
                "request-marker",
                "wrapping-key-removed",
                "database-files",
                "metadata-cleanup-pending",
            ),
            events,
        )
    }

    @Test
    fun databaseCleanupTouchesOnlyExactSQLiteArtifactsInCapturedNamespace() {
        val root = Files.createTempDirectory("wallet-deletion-test").toFile()
        try {
            val mainnet = File(root, "wallet-v1-mainnet").apply { mkdir() }
            val testnet = File(root, "wallet-v1-testnet").apply { mkdir() }
            val mainDatabase = File(mainnet, WALLET_DATABASE_FILE_NAME).absoluteFile
            val testDatabase = File(testnet, WALLET_DATABASE_FILE_NAME).absoluteFile
            val unrelated = File(mainnet, "notes.txt")
            (walletDatabaseArtifacts(mainDatabase) + walletDatabaseArtifacts(testDatabase) + unrelated)
                .forEach { file -> assertTrue(file.createNewFile()) }

            assertTrue(deleteWalletDatabaseArtifacts(mainDatabase))
            assertTrue(walletDatabaseArtifacts(mainDatabase).none(File::exists))
            assertTrue(walletDatabaseArtifacts(testDatabase).all(File::exists))
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databaseCleanupRejectsBroadOrUnexpectedTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            walletDatabaseArtifacts(File("wallet.sqlite3"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            walletDatabaseArtifacts(File("/wallet/mainnet"))
        }
    }

    private fun mayProceed(
        scope: WalletDeletionScope,
        foreground: Boolean = true,
        busy: Boolean = false,
        confirmedPersistentWallet: Boolean = true,
        hasUnconfirmedKey: Boolean = false,
    ): Boolean = walletDeletionMayProceed(
        expected = scope,
        current = scope.copy(),
        foreground = foreground,
        busy = busy,
        confirmedPersistentWallet = confirmedPersistentWallet,
        hasUnconfirmedKey = hasUnconfirmedKey,
    )

    private fun validScope(): WalletDeletionScope = WalletDeletionScope(
        lifecycleEpoch = 7,
        ownerGeneration = 11,
        leaseGeneration = 13,
        storagePath = "/wallet/wallet-v1-mainnet/wallet.sqlite3",
        networkId = "mainnet",
        walletHandle = 17,
        accountId = "01".repeat(16),
    )
}
