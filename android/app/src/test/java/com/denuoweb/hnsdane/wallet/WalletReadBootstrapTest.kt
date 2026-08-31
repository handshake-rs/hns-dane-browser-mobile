package com.denuoweb.hnsdane.wallet

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun configurationConsumesCallerAndRetainedAuthorizationExactlyOnce() {
        val authority = authority()
        val caller = "Bearer scoped-read-fixture".toCharArray()
        val configuration = NativeHnsReadConfiguration.takeOwnership(
            authority,
            12_039,
            caller,
        )
        assertNotNull(configuration)
        assertTrue(caller.all { it == '\u0000' })
        assertFalse(configuration.toString().contains("scoped-read-fixture"))
        assertFalse(configuration.toString().contains(authority.databasePath))
        assertFalse(configuration.toString().contains("walletHandle=1"))
        assertFalse(configuration.toString().contains("12039"))

        var borrowed: CharArray? = null
        assertTrue(checkNotNull(configuration).consumeFor(authority) { port, authorization ->
            assertEquals(12_039, port)
            assertEquals('B', authorization.first())
            borrowed = authorization
            true
        })
        assertTrue(checkNotNull(borrowed).all { it == '\u0000' })
        assertFalse(configuration.consumeFor(authority) { _, _ -> true })
    }

    @Test
    fun valueConfigurationConsumesAuthorizationAndExactShakescapePolicyTogether() {
        val authority = authority()
        val callerAuthorization = "Bearer value-fixture".toCharArray()
        val callerPolicy = "{\"network_magic\":1}".toByteArray(Charsets.US_ASCII)
        val configuration = checkNotNull(
            NativeHnsReadConfiguration.takeOwnership(
                authority,
                12_039,
                callerAuthorization,
                callerPolicy,
            ),
        )
        assertTrue(callerAuthorization.all { it == '\u0000' })
        assertTrue(callerPolicy.all { it == 0.toByte() })

        var borrowedAuthorization: CharArray? = null
        var borrowedPolicy: ByteArray? = null
        assertTrue(
            configuration.consumeForValue(authority) { port, authorization, policy ->
                assertEquals(12_039, port)
                assertEquals("{\"network_magic\":1}", policy.toString(Charsets.US_ASCII))
                borrowedAuthorization = authorization
                borrowedPolicy = policy
                true
            },
        )
        assertTrue(checkNotNull(borrowedAuthorization).all { it == '\u0000' })
        assertTrue(checkNotNull(borrowedPolicy).all { it == 0.toByte() })
        assertFalse(configuration.consumeForValue(authority) { _, _, _ -> true })
    }

    @Test
    fun invalidConfigurationInputIsRejectedAndWiped() {
        val authority = authority()
        for ((port, value) in listOf(
            0 to "Bearer fixture",
            65_536 to "Bearer fixture",
            12_039 to " leading",
            12_039 to "trailing ",
            12_039 to "line\nbreak",
            12_039 to "",
        )) {
            val caller = value.toCharArray()
            assertNull(NativeHnsReadConfiguration.takeOwnership(authority, port, caller))
            assertTrue(caller.all { it == '\u0000' })
        }
    }

    @Test
    fun mismatchExceptionCloseAndReplayAllFailClosed() {
        val lease = newLease(mainnetPath)
        val expected = authority(storageLease = lease)
        val replacement = authority(storageLease = lease, authorityGeneration = 2)

        var mismatchCalled = false
        val mismatchCaller = "Bearer mismatch".toCharArray()
        val mismatch = checkNotNull(
            NativeHnsReadConfiguration.takeOwnership(expected, 12_039, mismatchCaller),
        )
        assertFalse(mismatch.consumeFor(replacement) { _, _ ->
            mismatchCalled = true
            true
        })
        assertFalse(mismatchCalled)
        assertFalse(mismatch.consumeFor(expected) { _, _ -> true })

        var thrownBorrow: CharArray? = null
        val thrownCaller = "Bearer thrown".toCharArray()
        val thrown = checkNotNull(
            NativeHnsReadConfiguration.takeOwnership(expected, 12_039, thrownCaller),
        )
        try {
            thrown.consumeFor(expected) { _, authorization ->
                thrownBorrow = authorization
                error("fixture")
            }
            throw AssertionError("configuration install exception was not propagated")
        } catch (error: IllegalStateException) {
            assertEquals("fixture", error.message)
        }
        assertTrue(checkNotNull(thrownBorrow).all { it == '\u0000' })
        assertFalse(thrown.consumeFor(expected) { _, _ -> true })

        val closedCaller = "Bearer closed".toCharArray()
        val closed = checkNotNull(
            NativeHnsReadConfiguration.takeOwnership(expected, 12_039, closedCaller),
        )
        closed.close()
        assertFalse(closed.consumeFor(expected) { _, _ -> true })
    }

    @Test
    fun concurrentReplayAllowsOnlyOneInstallAttempt() {
        val authority = authority()
        val caller = "Bearer concurrent".toCharArray()
        val configuration = checkNotNull(
            NativeHnsReadConfiguration.takeOwnership(authority, 12_039, caller),
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val installs = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = List(2) {
            Thread {
                ready.countDown()
                start.await()
                results += configuration.consumeFor(authority) { _, _ ->
                    installs.incrementAndGet()
                    true
                }
            }.apply { start() }
        }
        ready.await()
        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(1, installs.get())
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
    }

    @Test
    fun attemptGatesBeforeAndAfterTakeAndProductionSourceIsUnavailable() {
        val authority = authority()
        val valid = state(authority)
        var sourceCalls = 0
        val neverSource = WalletReadBootstrapSource {
            sourceCalls += 1
            null
        }
        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = authority,
            source = neverSource,
            currentState = { valid.copy(foreground = false) },
            install = { _, _ -> true },
        ))
        assertEquals(0, sourceCalls)

        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = authority,
            source = UnavailableWalletReadBootstrapSource,
            currentState = { valid },
            install = { _, _ -> true },
        ))

        var stateReads = 0
        var offered: NativeHnsReadConfiguration? = null
        var installCalls = 0
        val revokingSource = WalletReadBootstrapSource { requested ->
            val caller = "Bearer revoked".toCharArray()
            checkNotNull(
                NativeHnsReadConfiguration.takeOwnership(requested, 12_039, caller),
            ).also { offered = it }
        }
        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = authority,
            source = revokingSource,
            currentState = {
                stateReads += 1
                if (stateReads == 1) valid else valid.copy(foreground = false)
            },
            install = { _, _ ->
                installCalls += 1
                true
            },
        ))
        assertEquals(0, installCalls)
        assertFalse(checkNotNull(offered).consumeFor(authority) { _, _ -> true })
    }

    @Test
    fun attemptRejectsLeaseRevokedByReentrantSourceAcquisition() {
        val fixture = leaseFixture(mainnetPath)
        val authority = authority(storageLease = fixture.lease)
        val valid = state(authority)
        lateinit var offered: NativeHnsReadConfiguration
        var installCalls = 0
        val revokingSource = WalletReadBootstrapSource { requested ->
            val caller = "Bearer storage-revoked".toCharArray()
            checkNotNull(
                NativeHnsReadConfiguration.takeOwnership(requested, 12_039, caller),
            ).also {
                offered = it
                fixture.gate.newOwner(mainnetPath) {}
            }
        }

        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = authority,
            source = revokingSource,
            currentState = { valid },
            install = { _, _ ->
                installCalls += 1
                true
            },
        ))
        assertFalse(authority.hasCurrentStorageLease())
        assertEquals(0, installCalls)
        assertFalse(offered.consumeFor(authority) { _, _ -> true })
    }

    @Test
    fun attemptConsumesWrongAuthorityAndInstallFailureWithoutReplay() {
        val lease = newLease(mainnetPath)
        val expected = authority(storageLease = lease)
        val replacement = authority(
            storageLease = lease,
            walletHandle = 2,
            authorityGeneration = 2,
        )
        val valid = state(expected)
        lateinit var offered: NativeHnsReadConfiguration
        val wrongSource = WalletReadBootstrapSource {
            val caller = "Bearer wrong-authority".toCharArray()
            checkNotNull(
                NativeHnsReadConfiguration.takeOwnership(replacement, 12_039, caller),
            ).also { offered = it }
        }
        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = expected,
            source = wrongSource,
            currentState = { valid },
            install = NativeWalletBridge::configureHnsReads,
        ))
        assertFalse(offered.consumeFor(replacement) { _, _ -> true })

        val unavailableNativeSource = WalletReadBootstrapSource { requested ->
            val caller = "Bearer unavailable-native".toCharArray()
            checkNotNull(NativeHnsReadConfiguration.takeOwnership(requested, 12_039, caller))
        }
        assertFalse(attemptWalletReadBootstrap(
            expectedAuthority = expected,
            source = unavailableNativeSource,
            currentState = { valid },
            install = NativeWalletBridge::configureHnsReads,
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
