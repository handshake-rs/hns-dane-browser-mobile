package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HrmHnsaWalletConsumerTest {
    @Test
    fun selectionRequiresExactCanonicalHnsaIdentityAndRedactsIt() {
        val selection = selection()
        assertEquals("mainnet", selection.networkId)
        assertEquals(MAINNET_MAGIC, selection.networkMagic)
        assertEquals(7, selection.applicationProfileId)
        assertEquals(HRM_HNSA_NAMED_SERVICE_PROFILE, "hns.named-service/v1")
        assertEquals("HrmHnsaNamedServiceSelection(<redacted>)", selection.toString())
        assertFalse(selection.toString().contains(NAME_HASH))
        assertFalse(selection.toString().contains("wallet-sync"))

        assertNull(selectionOrNull(networkId = "MAINNET"))
        assertNull(selectionOrNull(networkMagic = TESTNET_MAGIC))
        assertNull(selectionOrNull(nameHash = "ab".repeat(32).uppercase()))
        assertNull(selectionOrNull(nameHash = NAME_HASH.dropLast(1)))
        assertNull(selectionOrNull(serviceName = ""))
        assertNull(selectionOrNull(serviceName = "a".repeat(64)))
        assertNull(selectionOrNull(serviceName = "-wallet"))
        assertNull(selectionOrNull(serviceName = "wallet-"))
        assertNull(selectionOrNull(serviceName = "Wallet"))
        assertNull(selectionOrNull(serviceName = "wallet.sync"))
        assertNull(selectionOrNull(serviceName = "wallet_sync"))
        assertNull(selectionOrNull(applicationProfileId = 0))
        assertNull(selectionOrNull(applicationProfileId = 65_536))
    }

    @Test
    fun brokerAdoptionChecksBindingsIntervalsAndHnsaConstraints() {
        val walletAuthority = walletAuthority()
        val guard = RecordingGuard()
        val service = adoptedService(walletAuthority = walletAuthority, guard = guard)
        assertNotNull(service)
        assertSame(walletAuthority, checkNotNull(service).walletAuthority)
        assertEquals("BrokerVerifiedHrmHnsaNamedService(<redacted>)", service.toString())
        assertFalse(service.toString().contains(ENVELOPE_HASH))
        assertFalse(service.claim.toString().contains(RESOURCE_ID))

        assertNull(adoptedService(
            selection = selection(networkId = "testnet", networkMagic = TESTNET_MAGIC),
            walletAuthority = walletAuthority,
            guard = guard,
        ))
        assertNull(adoptedService(
            walletAuthority,
            guard,
            envelopeHash = "cd".repeat(32).uppercase(),
        ))
        assertNull(adoptedService(walletAuthority, guard, authorityRevision = 0UL))
        assertNull(adoptedService(walletAuthority, guard, operationLeaseGeneration = 0UL))
        assertNull(adoptedService(walletAuthority, guard, resourceId = RESOURCE_ID.dropLast(1)))
        assertNull(adoptedService(walletAuthority, guard, serviceGeneration = 0UL))
        assertNull(adoptedService(walletAuthority, guard, hrmIssuedAt = 2_000UL))
        assertNull(adoptedService(walletAuthority, guard, serviceNotBefore = 999UL))
        assertNull(adoptedService(walletAuthority, guard, delegationNotBefore = 1_199UL))
        assertNull(adoptedService(walletAuthority, guard, trustedTime = 1_800UL))
        assertNull(adoptedService(walletAuthority, guard, controllerKey = "04" + "44".repeat(32)))
        assertNull(adoptedService(walletAuthority, guard, profileFlags = 65_536))
        assertNull(adoptedService(walletAuthority, guard, maxEndpointLifetime = 299L))
        assertNull(adoptedService(walletAuthority, guard, maxEndpointLifetime = 604_801L))
        assertNull(adoptedService(walletAuthority, guard, allowedCapabilities = 4_294_967_296L))
    }

    @Test
    fun leaseBindsExactSelectionWalletAndCurrentClaimAndIsOneShot() {
        val selection = selection()
        val walletAuthority = walletAuthority()
        val guard = RecordingGuard()
        val service = checkNotNull(adoptedService(selection, walletAuthority, guard))
        val lease = HrmHnsaWalletConsumerLease.takeOwnership(service)
        var operationCalls = 0
        assertTrue(lease.consumeFor(selection, walletAuthority) { offered ->
            operationCalls += 1
            assertSame(service, offered)
            true
        })
        assertEquals(1, guard.calls)
        assertEquals(1, operationCalls)
        assertEquals(service.claim, guard.claim)
        assertEquals(9UL, checkNotNull(guard.claim).hrmSequence)
        assertEquals(11UL, checkNotNull(guard.claim).authorityRevision)
        assertEquals(1_500UL, checkNotNull(guard.claim).trustedOperationTime)
        assertEquals(13UL, checkNotNull(guard.claim).operationLeaseGeneration)
        assertFalse(lease.consumeFor(selection, walletAuthority) { true })
        assertFalse(service.useIfCurrent { true })

        val otherSelection = selection(serviceName = "other")
        val wrongSelectionService = checkNotNull(
            adoptedService(selection, walletAuthority, RecordingGuard()),
        )
        val wrongSelectionLease = HrmHnsaWalletConsumerLease.takeOwnership(
            wrongSelectionService,
        )
        assertFalse(wrongSelectionLease.consumeFor(otherSelection, walletAuthority) { true })
        assertFalse(wrongSelectionService.useIfCurrent { true })

        val replacementWallet = walletAuthority(authorityGeneration = 2)
        val wrongWalletService = checkNotNull(
            adoptedService(selection, walletAuthority, RecordingGuard()),
        )
        val wrongWalletLease = HrmHnsaWalletConsumerLease.takeOwnership(wrongWalletService)
        assertFalse(wrongWalletLease.consumeFor(selection, replacementWallet) { true })
        assertFalse(wrongWalletService.useIfCurrent { true })

        val sharedService = checkNotNull(
            adoptedService(selection, walletAuthority, RecordingGuard()),
        )
        val firstOwner = HrmHnsaWalletConsumerLease.takeOwnership(sharedService)
        val duplicateOwner = HrmHnsaWalletConsumerLease.takeOwnership(sharedService)
        assertTrue(firstOwner.consumeFor(selection, walletAuthority) { true })
        assertFalse(duplicateOwner.consumeFor(selection, walletAuthority) { true })
    }

    @Test
    fun deniedOrMalformedGuardCannotReleaseAnOperation() {
        val selection = selection()
        val walletAuthority = walletAuthority()
        val denied = RecordingGuard(admit = false)
        var operationCalls = 0
        val deniedLease = HrmHnsaWalletConsumerLease.takeOwnership(
            checkNotNull(adoptedService(selection, walletAuthority, denied)),
        )
        assertFalse(deniedLease.consumeFor(selection, walletAuthority) {
            operationCalls += 1
            true
        })
        assertEquals(0, operationCalls)

        val doubleInvoke = HrmHnsaCurrentAuthorityGuard { _, operation ->
            val first = operation()
            val second = operation()
            first && second
        }
        val doubleLease = HrmHnsaWalletConsumerLease.takeOwnership(
            checkNotNull(adoptedService(selection, walletAuthority, doubleInvoke)),
        )
        assertFalse(doubleLease.consumeFor(selection, walletAuthority) {
            operationCalls += 1
            true
        })
        assertEquals(1, operationCalls)

        val dishonestReturn = HrmHnsaCurrentAuthorityGuard { _, _ -> true }
        val dishonestLease = HrmHnsaWalletConsumerLease.takeOwnership(
            checkNotNull(adoptedService(selection, walletAuthority, dishonestReturn)),
        )
        assertFalse(dishonestLease.consumeFor(selection, walletAuthority) {
            operationCalls += 1
            true
        })
        assertEquals(1, operationCalls)
    }

    @Test
    fun attemptGatesBeforeAfterAndUnderBrokerGuard() {
        val selection = selection()
        val walletAuthority = walletAuthority()
        var current = state(selection, walletAuthority)
        var sourceCalls = 0
        val neverSource = HrmHnsaWalletConsumerSource { _, _ ->
            sourceCalls += 1
            null
        }
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            neverSource,
            { current.copy(foreground = false) },
        ) { true })
        assertEquals(0, sourceCalls)
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            neverSource,
            { error("state fixture") },
        ) { true })
        assertEquals(0, sourceCalls)
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            UnavailableHrmHnsaWalletConsumerSource,
            { current },
        ) { true })
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            HrmHnsaWalletConsumerSource { _, _ -> error("source fixture") },
            { current },
        ) { true })

        lateinit var reentrantLease: HrmHnsaWalletConsumerLease
        val reentrantSource = HrmHnsaWalletConsumerSource { _, _ ->
            HrmHnsaWalletConsumerLease.takeOwnership(
                checkNotNull(adoptedService(selection, walletAuthority, RecordingGuard())),
            ).also {
                reentrantLease = it
                current = current.copy(operationInFlight = true)
            }
        }
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            reentrantSource,
            { current },
        ) { true })
        current = state(selection, walletAuthority)
        assertFalse(reentrantLease.consumeFor(selection, walletAuthority) { true })

        var operationCalls = 0
        val revokingGuard = RecordingGuard(beforeCallback = {
            current = current.copy(retirementBlocked = true)
        })
        val guardedSource = source(selection, walletAuthority, revokingGuard)
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            guardedSource,
            { current },
        ) {
            operationCalls += 1
            true
        })
        assertEquals(0, operationCalls)

        current = state(selection, walletAuthority)
        val exactGuard = RecordingGuard()
        assertTrue(attemptHrmHnsaWalletConsumerUse(
            selection,
            walletAuthority,
            source(selection, walletAuthority, exactGuard),
            { current },
        ) {
            operationCalls += 1
            it.claim.serviceDelegationId == DELEGATION_ID
        })
        assertEquals(1, operationCalls)
        assertEquals(1, exactGuard.calls)
    }

    @Test
    fun stateRequiresExactIdleDurableWalletAndTrustedSelection() {
        val selection = selection()
        val walletAuthority = walletAuthority()
        val exact = state(selection, walletAuthority)
        assertTrue(hrmHnsaWalletConsumerMayUse(selection, walletAuthority, exact))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(selection = selection(serviceName = "other")),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(walletAuthority = walletAuthority(authorityGeneration = 2)),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(selection, walletAuthority, exact.copy(foreground = false)))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(protectedStorageAvailable = false),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(reopenedDurableWallet = false),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(confirmedPersistentWallet = false),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(hasUnconfirmedRecovery = true),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(operationInFlight = true),
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            walletAuthority,
            exact.copy(retirementBlocked = true),
        ))
    }

    @Test
    fun stateRequiresCurrentWalletLeaseFromItsIssuingGate() {
        val selection = selection()
        val currentFixture = walletFixture()
        assertTrue(hrmHnsaWalletConsumerMayUse(
            selection,
            currentFixture.authority,
            state(selection, currentFixture.authority),
        ))

        val releasedFixture = walletFixture()
        assertTrue(releasedFixture.gate.release(releasedFixture.lease))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            releasedFixture.authority,
            state(selection, releasedFixture.authority),
        ))

        val revokedFixture = walletFixture()
        revokedFixture.gate.newOwner(revokedFixture.path) {}
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            revokedFixture.authority,
            state(selection, revokedFixture.authority),
        ))

        val staleFixture = walletFixture()
        assertTrue(staleFixture.gate.release(staleFixture.lease))
        val replacementOwner = staleFixture.gate.newOwner(staleFixture.path) {}
        var replacementLease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(staleFixture.gate.acquire(replacementOwner) { replacementLease = it })
        val replacementAuthority = checkNotNull(WalletReadBootstrapAuthority.create(
            "mainnet",
            staleFixture.path,
            checkNotNull(replacementLease),
            1L,
            2L,
        ))
        assertFalse(hrmHnsaWalletConsumerMayUse(
            selection,
            staleFixture.authority,
            state(selection, staleFixture.authority),
        ))
        assertTrue(hrmHnsaWalletConsumerMayUse(
            selection,
            replacementAuthority,
            state(selection, replacementAuthority),
        ))
    }

    @Test
    fun attemptRejectsWalletLeaseLossAfterSourceAndUnderBrokerGuard() {
        val selection = selection()
        val afterSourceFixture = walletFixture()
        var operationCalls = 0
        val revokingSource = HrmHnsaWalletConsumerSource { requested, walletAuthority ->
            HrmHnsaWalletConsumerLease.takeOwnership(
                checkNotNull(adoptedService(requested, walletAuthority, RecordingGuard())),
            ).also {
                afterSourceFixture.gate.newOwner(afterSourceFixture.path) {}
            }
        }
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            afterSourceFixture.authority,
            revokingSource,
            { state(selection, afterSourceFixture.authority) },
        ) {
            operationCalls += 1
            true
        })
        assertEquals(0, operationCalls)

        val guardedFixture = walletFixture()
        var replacementLease: WalletStorageOwnershipGate.Lease? = null
        val staleGuard = RecordingGuard(beforeCallback = {
            assertTrue(guardedFixture.gate.release(guardedFixture.lease))
            val replacementOwner = guardedFixture.gate.newOwner(guardedFixture.path) {}
            assertTrue(guardedFixture.gate.acquire(replacementOwner) {
                replacementLease = it
            })
        })
        assertFalse(attemptHrmHnsaWalletConsumerUse(
            selection,
            guardedFixture.authority,
            source(selection, guardedFixture.authority, staleGuard),
            { state(selection, guardedFixture.authority) },
        ) {
            operationCalls += 1
            true
        })
        assertEquals(0, operationCalls)
        assertFalse(guardedFixture.authority.hasCurrentStorageLease())
        assertTrue(checkNotNull(replacementLease).isCurrent())
    }

    private fun source(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        guard: HrmHnsaCurrentAuthorityGuard,
    ) = HrmHnsaWalletConsumerSource { requestedSelection, requestedWallet ->
        assertEquals(selection, requestedSelection)
        assertEquals(walletAuthority, requestedWallet)
        HrmHnsaWalletConsumerLease.takeOwnership(
            checkNotNull(adoptedService(selection, walletAuthority, guard)),
        )
    }

    private fun state(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
    ) = HrmHnsaWalletConsumerState(
        selection = selection,
        walletAuthority = walletAuthority,
        foreground = true,
        protectedStorageAvailable = true,
        reopenedDurableWallet = true,
        confirmedPersistentWallet = true,
        hasUnconfirmedRecovery = false,
        operationInFlight = false,
        retirementBlocked = false,
    )

    private fun selection(
        networkId: String = "mainnet",
        networkMagic: Long = MAINNET_MAGIC,
        nameHash: String = NAME_HASH,
        serviceName: String = "wallet-sync",
        applicationProfileId: Int = 7,
    ): HrmHnsaNamedServiceSelection = checkNotNull(selectionOrNull(
        networkId,
        networkMagic,
        nameHash,
        serviceName,
        applicationProfileId,
    ))

    private fun selectionOrNull(
        networkId: String = "mainnet",
        networkMagic: Long = MAINNET_MAGIC,
        nameHash: String = NAME_HASH,
        serviceName: String = "wallet-sync",
        applicationProfileId: Int = 7,
    ): HrmHnsaNamedServiceSelection? = HrmHnsaNamedServiceSelection.create(
        networkId,
        networkMagic,
        nameHash,
        serviceName,
        applicationProfileId,
    )

    @Suppress("LongParameterList")
    private fun adoptedService(
        walletAuthority: WalletReadBootstrapAuthority,
        guard: HrmHnsaCurrentAuthorityGuard,
        envelopeHash: String = ENVELOPE_HASH,
        authorityRevision: ULong = 11UL,
        operationLeaseGeneration: ULong = 13UL,
        resourceId: String = RESOURCE_ID,
        serviceGeneration: ULong = 17UL,
        hrmIssuedAt: ULong = 1_000UL,
        serviceNotBefore: ULong = 1_200UL,
        delegationNotBefore: ULong = 1_300UL,
        trustedTime: ULong = 1_500UL,
        controllerKey: String = CONTROLLER_KEY,
        profileFlags: Int = 0,
        maxEndpointLifetime: Long = 3_600L,
        allowedCapabilities: Long = 3L,
        serviceSelection: HrmHnsaNamedServiceSelection = selection(),
    ): BrokerVerifiedHrmHnsaNamedService? = adoptedService(
        serviceSelection,
        walletAuthority,
        guard,
        envelopeHash,
        authorityRevision,
        operationLeaseGeneration,
        resourceId,
        serviceGeneration,
        hrmIssuedAt,
        serviceNotBefore,
        delegationNotBefore,
        trustedTime,
        controllerKey,
        profileFlags,
        maxEndpointLifetime,
        allowedCapabilities,
    )

    @Suppress("LongParameterList")
    private fun adoptedService(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        guard: HrmHnsaCurrentAuthorityGuard,
        envelopeHash: String = ENVELOPE_HASH,
        authorityRevision: ULong = 11UL,
        operationLeaseGeneration: ULong = 13UL,
        resourceId: String = RESOURCE_ID,
        serviceGeneration: ULong = 17UL,
        hrmIssuedAt: ULong = 1_000UL,
        serviceNotBefore: ULong = 1_200UL,
        delegationNotBefore: ULong = 1_300UL,
        trustedTime: ULong = 1_500UL,
        controllerKey: String = CONTROLLER_KEY,
        profileFlags: Int = 0,
        maxEndpointLifetime: Long = 3_600L,
        allowedCapabilities: Long = 3L,
    ): BrokerVerifiedHrmHnsaNamedService? =
        BrokerVerifiedHrmHnsaNamedService.adoptBrokerVerified(
            selection = selection,
            walletAuthority = walletAuthority,
            hrmSequence = 9UL,
            hrmEnvelopeHash = envelopeHash,
            authorityRevision = authorityRevision,
            trustedOperationTime = trustedTime,
            operationLeaseGeneration = operationLeaseGeneration,
            serviceResourceId = resourceId,
            serviceDelegationId = DELEGATION_ID,
            serviceGeneration = serviceGeneration,
            hrmIssuedAt = hrmIssuedAt,
            hrmExpiresAt = 2_000UL,
            serviceNotBefore = serviceNotBefore,
            serviceExpiresAt = 1_900UL,
            delegationNotBefore = delegationNotBefore,
            delegationExpiresAt = 1_800UL,
            serviceControllerKey = controllerKey,
            profileFlags = profileFlags,
            profileConstraintsHash = ZERO_HASH,
            maxEndpointLifetimeSeconds = maxEndpointLifetime,
            allowedEndpointCapabilities = allowedCapabilities,
            endpointConstraintsHash = CONSTRAINTS_HASH,
            currentAuthorityGuard = guard,
        )

    private fun walletAuthority(
        networkId: String = "mainnet",
        authorityGeneration: Long = 1L,
    ): WalletReadBootstrapAuthority = walletFixture(
        networkId,
        authorityGeneration,
    ).authority

    private fun walletFixture(
        networkId: String = "mainnet",
        authorityGeneration: Long = 1L,
    ): WalletFixture {
        val path = "/wallet/wallet-v1-$networkId/wallet.sqlite3"
        val storage = WalletStorageOwnershipGate()
        val owner = storage.newOwner(path) {}
        var lease: WalletStorageOwnershipGate.Lease? = null
        assertTrue(storage.acquire(owner) { lease = it })
        val retainedLease = checkNotNull(lease)
        val authority = checkNotNull(WalletReadBootstrapAuthority.create(
            networkId,
            path,
            retainedLease,
            1L,
            authorityGeneration,
        ))
        return WalletFixture(path, storage, retainedLease, authority)
    }

    private data class WalletFixture(
        val path: String,
        val gate: WalletStorageOwnershipGate,
        val lease: WalletStorageOwnershipGate.Lease,
        val authority: WalletReadBootstrapAuthority,
    )

    private class RecordingGuard(
        private val admit: Boolean = true,
        private val beforeCallback: () -> Unit = {},
    ) : HrmHnsaCurrentAuthorityGuard {
        var calls = 0
        var claim: HrmHnsaCurrentAuthorityClaim? = null

        override fun useIfCurrent(
            claim: HrmHnsaCurrentAuthorityClaim,
            operation: () -> Boolean,
        ): Boolean {
            calls += 1
            this.claim = claim
            beforeCallback()
            return admit && operation()
        }
    }

    private companion object {
        const val MAINNET_MAGIC = 0x5b6e_f2d3L
        const val TESTNET_MAGIC = 0xb152_0dd2L
        val NAME_HASH = "11".repeat(32)
        val ENVELOPE_HASH = "22".repeat(32)
        val RESOURCE_ID = "33".repeat(32)
        val DELEGATION_ID = "44".repeat(32)
        val ZERO_HASH = "00".repeat(32)
        val CONSTRAINTS_HASH = "55".repeat(32)
        val CONTROLLER_KEY = "02" + "66".repeat(32)
    }
}
