import Foundation
import XCTest
@testable import HnsDaneBrowser

private final class HrmHnsaGuardFixture: HrmHnsaCurrentAuthorityGuard {
    var admitted = true
    var invocationCount = 1
    var beforeCallback: () -> Void = {}
    private(set) var calls = 0
    private(set) var claim: HrmHnsaCurrentAuthorityClaim?

    func useIfCurrent(
        claim: HrmHnsaCurrentAuthorityClaim,
        operation: () -> Bool
    ) -> Bool {
        calls += 1
        self.claim = claim
        beforeCallback()
        guard admitted else { return false }
        guard invocationCount > 0 else { return true }
        var result = true
        for _ in 0..<invocationCount {
            result = operation() && result
        }
        return result
    }
}

private final class HrmHnsaSourceFixture: HrmHnsaWalletConsumerSource {
    private let take: (
        HrmHnsaNamedServiceSelection,
        WalletReadBootstrapAuthority
    ) -> HrmHnsaWalletConsumerLease?

    init(
        take: @escaping (
            HrmHnsaNamedServiceSelection,
            WalletReadBootstrapAuthority
        ) -> HrmHnsaWalletConsumerLease?
    ) {
        self.take = take
    }

    func takeAuthority(
        for selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority
    ) -> HrmHnsaWalletConsumerLease? {
        take(selection, walletAuthority)
    }
}

private final class HrmHnsaWalletAuthorityFixture {
    let wallet: NSObject
    let lease: WalletStorageLeaseToken
    let authority: WalletReadBootstrapAuthority

    init(network: BrowserHandshakeNetwork = .mainnet, ownerGeneration: UInt64 = 1) throws {
        let retainedWallet = NSObject()
        let path = "/private/hrm-\(UUID().uuidString)/NativeWallet/\(network.rawValue)/wallet.sqlite3"
        wallet = retainedWallet
        lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        authority = WalletReadBootstrapAuthority(
            network: network,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(retainedWallet),
            ownerGeneration: ownerGeneration
        )
    }

    deinit {
        WalletStorageLeaseRegistry.release(lease)
    }
}

final class HrmHnsaWalletConsumerTests: XCTestCase {
    func testSelectionRequiresExactCanonicalHnsaIdentityAndRedactsIt() throws {
        let selected = try selection()
        XCTAssertEqual(selected.network, .mainnet)
        XCTAssertEqual(selected.networkMagic, Self.mainnetMagic)
        XCTAssertEqual(selected.applicationProfileID, 7)
        XCTAssertEqual(hrmHnsaNamedServiceProfile, "hns.named-service/v1")
        XCTAssertEqual(selected.description, "HrmHnsaNamedServiceSelection(<redacted>)")
        XCTAssertFalse(String(reflecting: selected).contains(Self.nameHash))
        XCTAssertFalse(String(reflecting: selected).contains("wallet-sync"))

        XCTAssertNil(selectionOrNil(networkMagic: Self.testnetMagic))
        XCTAssertNil(selectionOrNil(
            nameHash: String(repeating: "ab", count: 32).uppercased()
        ))
        XCTAssertNil(selectionOrNil(nameHash: String(Self.nameHash.dropLast())))
        for invalid in [
            "", String(repeating: "a", count: 64), "-wallet", "wallet-",
            "Wallet", "wallet.sync", "wallet_sync", "wallet/sync", "wallet sync",
        ] {
            XCTAssertNil(selectionOrNil(serviceName: invalid))
        }
        XCTAssertNil(selectionOrNil(applicationProfileID: 0))
        XCTAssertNotNil(selectionOrNil(serviceName: String(repeating: "a", count: 63)))
    }

    func testBrokerAdoptionChecksBindingsIntervalsAndHnsaConstraints() throws {
        let wallet = try HrmHnsaWalletAuthorityFixture()
        let guardFixture = HrmHnsaGuardFixture()
        let adopted = try XCTUnwrap(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture
        ))
        XCTAssertEqual(adopted.walletAuthority, wallet.authority)
        XCTAssertEqual(
            adopted.description,
            "BrokerVerifiedHrmHnsaNamedService(<redacted>)"
        )
        XCTAssertFalse(adopted.description.contains(Self.envelopeHash))
        XCTAssertFalse(adopted.claim.description.contains(Self.resourceID))

        let testnetSelection = try selection(
            network: .testnet,
            networkMagic: Self.testnetMagic
        )
        XCTAssertNil(service(
            selection: testnetSelection,
            walletAuthority: wallet.authority,
            guardFixture: guardFixture
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            envelopeHash: String(repeating: "cd", count: 32).uppercased()
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            authorityRevision: 0
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            operationLeaseGeneration: 0
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            resourceID: String(Self.resourceID.dropLast())
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            serviceGeneration: 0
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            hrmIssuedAt: 2_000
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            serviceNotBefore: 999
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            serviceExpiresAt: 2_001
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            delegationNotBefore: 1_199
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            delegationExpiresAt: 1_901
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            trustedOperationTime: 1_800
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            controllerKey: "04" + String(repeating: "44", count: 32)
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            maxEndpointLifetimeSeconds: 299
        ))
        XCTAssertNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            maxEndpointLifetimeSeconds: 604_801
        ))
        XCTAssertNotNil(service(
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            serviceNotBefore: 1_000,
            serviceExpiresAt: 2_000,
            delegationNotBefore: 1_000,
            delegationExpiresAt: 2_000,
            trustedOperationTime: 1_000
        ))
    }

    func testZeroHrmCommitmentSequenceIsPreservedThroughCurrentGuard() throws {
        let selected = try selection()
        let wallet = try HrmHnsaWalletAuthorityFixture()
        let guardFixture = HrmHnsaGuardFixture()
        let adopted = try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: guardFixture,
            hrmSequence: 0
        ))
        XCTAssertEqual(adopted.claim.hrmSequence, 0)
        XCTAssertEqual(adopted.claim.serviceGeneration, 17)

        let lease = HrmHnsaWalletConsumerLease(taking: adopted)
        XCTAssertTrue(lease.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { offered in
            offered.claim.hrmSequence == 0
        })
        let guardedClaim = try XCTUnwrap(guardFixture.claim)
        XCTAssertEqual(guardedClaim.hrmSequence, 0)
        XCTAssertNil(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: HrmHnsaGuardFixture(),
            hrmSequence: 0,
            serviceGeneration: 0
        ))
    }

    func testLeaseBindsExactSelectionWalletAndCurrentClaimAndIsOneShot() throws {
        let selected = try selection()
        let wallet = try HrmHnsaWalletAuthorityFixture()
        let guardFixture = HrmHnsaGuardFixture()
        let adopted = try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: guardFixture
        ))
        let lease = HrmHnsaWalletConsumerLease(taking: adopted)
        var operationCalls = 0
        XCTAssertTrue(lease.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { offered in
            operationCalls += 1
            XCTAssertTrue(offered === adopted)
            return true
        })
        XCTAssertEqual(guardFixture.calls, 1)
        XCTAssertEqual(operationCalls, 1)
        XCTAssertEqual(guardFixture.claim, adopted.claim)
        let guardedClaim = try XCTUnwrap(guardFixture.claim)
        XCTAssertEqual(guardedClaim.hrmSequence, 9)
        XCTAssertEqual(guardedClaim.authorityRevision, 11)
        XCTAssertEqual(guardedClaim.trustedOperationTime, 1_500)
        XCTAssertEqual(guardedClaim.operationLeaseGeneration, 13)
        XCTAssertFalse(lease.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in true })
        XCTAssertFalse(adopted.useIfCurrent { _ in true })

        let other = try selection(serviceName: "other")
        let wrongSelectionService = try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: HrmHnsaGuardFixture()
        ))
        let wrongSelection = HrmHnsaWalletConsumerLease(taking: wrongSelectionService)
        XCTAssertFalse(wrongSelection.consumeFor(
            selection: other,
            walletAuthority: wallet.authority
        ) { _ in true })
        XCTAssertFalse(wrongSelectionService.useIfCurrent { _ in true })

        let replacementWallet = NSObject()
        let replacementAuthority = WalletReadBootstrapAuthority(
            network: wallet.authority.network,
            databasePath: wallet.authority.databasePath,
            lease: wallet.authority.lease,
            walletIdentity: ObjectIdentifier(replacementWallet),
            ownerGeneration: wallet.authority.ownerGeneration + 1
        )
        let wrongWalletService = try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: HrmHnsaGuardFixture()
        ))
        let wrongWallet = HrmHnsaWalletConsumerLease(taking: wrongWalletService)
        XCTAssertFalse(wrongWallet.consumeFor(
            selection: selected,
            walletAuthority: replacementAuthority
        ) { _ in true })
        XCTAssertFalse(wrongWalletService.useIfCurrent { _ in true })

        let sharedService = try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: HrmHnsaGuardFixture()
        ))
        let firstOwner = HrmHnsaWalletConsumerLease(taking: sharedService)
        let duplicateOwner = HrmHnsaWalletConsumerLease(taking: sharedService)
        XCTAssertTrue(firstOwner.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in true })
        XCTAssertFalse(duplicateOwner.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in true })
    }

    func testDeniedOrMalformedGuardCannotReleaseAnOperation() throws {
        let selected = try selection()
        let wallet = try HrmHnsaWalletAuthorityFixture()
        var operationCalls = 0

        let deniedGuard = HrmHnsaGuardFixture()
        deniedGuard.admitted = false
        let denied = HrmHnsaWalletConsumerLease(taking: try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: deniedGuard
        )))
        XCTAssertFalse(denied.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in
            operationCalls += 1
            return true
        })
        XCTAssertEqual(operationCalls, 0)

        let duplicateGuard = HrmHnsaGuardFixture()
        duplicateGuard.invocationCount = 2
        let duplicate = HrmHnsaWalletConsumerLease(taking: try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: duplicateGuard
        )))
        XCTAssertFalse(duplicate.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in
            operationCalls += 1
            return true
        })
        XCTAssertEqual(operationCalls, 1)

        let dishonestGuard = HrmHnsaGuardFixture()
        dishonestGuard.invocationCount = 0
        let dishonest = HrmHnsaWalletConsumerLease(taking: try XCTUnwrap(service(
            selection: selected,
            walletAuthority: wallet.authority,
            guardFixture: dishonestGuard
        )))
        XCTAssertFalse(dishonest.consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in
            operationCalls += 1
            return true
        })
        XCTAssertEqual(operationCalls, 1)
    }

    func testAttemptGatesBeforeAfterAndUnderBrokerGuard() throws {
        let selected = try selection()
        let wallet = try HrmHnsaWalletAuthorityFixture()
        var current = state(selection: selected, walletAuthority: wallet.authority)
        var sourceCalls = 0
        let neverSource = HrmHnsaSourceFixture { _, _ in
            sourceCalls += 1
            return nil
        }
        XCTAssertFalse(attemptHrmHnsaWalletConsumerUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            source: neverSource,
            currentState: { self.state(
                selection: selected,
                walletAuthority: wallet.authority,
                foreground: false
            ) },
            operation: { _ in true }
        ))
        XCTAssertEqual(sourceCalls, 0)
        XCTAssertFalse(attemptHrmHnsaWalletConsumerUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            source: UnavailableHrmHnsaWalletConsumerSource.shared,
            currentState: { current },
            operation: { _ in true }
        ))

        var returnedLease: HrmHnsaWalletConsumerLease?
        let reentrantSource = HrmHnsaSourceFixture { _, _ in
            let lease = HrmHnsaWalletConsumerLease(taking: try! XCTUnwrap(self.service(
                selection: selected,
                walletAuthority: wallet.authority,
                guardFixture: HrmHnsaGuardFixture()
            )))
            returnedLease = lease
            current = self.state(
                selection: selected,
                walletAuthority: wallet.authority,
                operationInFlight: true
            )
            return lease
        }
        XCTAssertFalse(attemptHrmHnsaWalletConsumerUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            source: reentrantSource,
            currentState: { current },
            operation: { _ in true }
        ))
        current = state(selection: selected, walletAuthority: wallet.authority)
        XCTAssertFalse(try XCTUnwrap(returnedLease).consumeFor(
            selection: selected,
            walletAuthority: wallet.authority
        ) { _ in true })

        var operationCalls = 0
        let revokingGuard = HrmHnsaGuardFixture()
        revokingGuard.beforeCallback = {
            current = self.state(
                selection: selected,
                walletAuthority: wallet.authority,
                retirementBlocked: true
            )
        }
        XCTAssertFalse(attemptHrmHnsaWalletConsumerUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            source: source(
                selection: selected,
                walletAuthority: wallet.authority,
                guardFixture: revokingGuard
            ),
            currentState: { current },
            operation: { _ in
                operationCalls += 1
                return true
            }
        ))
        XCTAssertEqual(operationCalls, 0)

        current = state(selection: selected, walletAuthority: wallet.authority)
        let exactGuard = HrmHnsaGuardFixture()
        XCTAssertTrue(attemptHrmHnsaWalletConsumerUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            source: source(
                selection: selected,
                walletAuthority: wallet.authority,
                guardFixture: exactGuard
            ),
            currentState: { current },
            operation: { offered in
                operationCalls += 1
                return offered.claim.serviceDelegationID == Self.delegationID
            }
        ))
        XCTAssertEqual(operationCalls, 1)
        XCTAssertEqual(exactGuard.calls, 1)
        XCTAssertFalse(WalletNativeReleaseGates.hrmHnsaWalletConsumerReleaseQualified)
    }

    func testStateRequiresExactIdleDurableWalletAndTrustedSelection() throws {
        let selected = try selection()
        let wallet = try HrmHnsaWalletAuthorityFixture()
        let exact = state(selection: selected, walletAuthority: wallet.authority)
        XCTAssertTrue(hrmHnsaWalletConsumerMayUse(
            expectedSelection: selected,
            expectedWalletAuthority: wallet.authority,
            current: exact
        ))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: try selection(serviceName: "other"),
            walletAuthority: wallet.authority
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: nil
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            foreground: false
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            protectedStorageAvailable: false
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            reopenedDurableWallet: false
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            confirmedPersistentWallet: false
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            hasUnconfirmedRecovery: true
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            operationInFlight: true
        )))
        XCTAssertFalse(mayUse(selected, wallet.authority, state(
            selection: selected,
            walletAuthority: wallet.authority,
            retirementBlocked: true
        )))

        let malformedAuthority = WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: wallet.authority.databasePath + "/../wallet.sqlite3",
            lease: wallet.authority.lease,
            walletIdentity: wallet.authority.walletIdentity,
            ownerGeneration: wallet.authority.ownerGeneration
        )
        XCTAssertFalse(mayUse(selected, malformedAuthority, state(
            selection: selected,
            walletAuthority: malformedAuthority
        )))

        let releasedWallet = try HrmHnsaWalletAuthorityFixture()
        WalletStorageLeaseRegistry.release(releasedWallet.lease)
        XCTAssertFalse(mayUse(selected, releasedWallet.authority, state(
            selection: selected,
            walletAuthority: releasedWallet.authority
        )))
    }

    private func mayUse(
        _ selection: HrmHnsaNamedServiceSelection,
        _ walletAuthority: WalletReadBootstrapAuthority,
        _ current: HrmHnsaWalletConsumerState
    ) -> Bool {
        hrmHnsaWalletConsumerMayUse(
            expectedSelection: selection,
            expectedWalletAuthority: walletAuthority,
            current: current
        )
    }

    private func source(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        guardFixture: HrmHnsaGuardFixture
    ) -> HrmHnsaSourceFixture {
        HrmHnsaSourceFixture { requestedSelection, requestedWallet in
            XCTAssertEqual(requestedSelection, selection)
            XCTAssertEqual(requestedWallet, walletAuthority)
            return HrmHnsaWalletConsumerLease(taking: try! XCTUnwrap(self.service(
                selection: selection,
                walletAuthority: walletAuthority,
                guardFixture: guardFixture
            )))
        }
    }

    private func state(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority?,
        foreground: Bool = true,
        protectedStorageAvailable: Bool = true,
        reopenedDurableWallet: Bool = true,
        confirmedPersistentWallet: Bool = true,
        hasUnconfirmedRecovery: Bool = false,
        operationInFlight: Bool = false,
        retirementBlocked: Bool = false
    ) -> HrmHnsaWalletConsumerState {
        HrmHnsaWalletConsumerState(
            selection: selection,
            walletAuthority: walletAuthority,
            foreground: foreground,
            protectedStorageAvailable: protectedStorageAvailable,
            reopenedDurableWallet: reopenedDurableWallet,
            confirmedPersistentWallet: confirmedPersistentWallet,
            hasUnconfirmedRecovery: hasUnconfirmedRecovery,
            operationInFlight: operationInFlight,
            retirementBlocked: retirementBlocked
        )
    }

    private func selection(
        network: BrowserHandshakeNetwork = .mainnet,
        networkMagic: UInt32 = Self.mainnetMagic,
        nameHash: String = Self.nameHash,
        serviceName: String = "wallet-sync",
        applicationProfileID: UInt16 = 7
    ) throws -> HrmHnsaNamedServiceSelection {
        try XCTUnwrap(selectionOrNil(
            network: network,
            networkMagic: networkMagic,
            nameHash: nameHash,
            serviceName: serviceName,
            applicationProfileID: applicationProfileID
        ))
    }

    private func selectionOrNil(
        network: BrowserHandshakeNetwork = .mainnet,
        networkMagic: UInt32 = Self.mainnetMagic,
        nameHash: String = Self.nameHash,
        serviceName: String = "wallet-sync",
        applicationProfileID: UInt16 = 7
    ) -> HrmHnsaNamedServiceSelection? {
        HrmHnsaNamedServiceSelection(
            network: network,
            networkMagic: networkMagic,
            nameHash: nameHash,
            serviceName: serviceName,
            applicationProfileID: applicationProfileID
        )
    }

    private func service(
        selection: HrmHnsaNamedServiceSelection? = nil,
        walletAuthority: WalletReadBootstrapAuthority,
        guardFixture: HrmHnsaGuardFixture,
        hrmSequence: UInt64 = 9,
        envelopeHash: String = Self.envelopeHash,
        authorityRevision: UInt64 = 11,
        operationLeaseGeneration: UInt64 = 13,
        resourceID: String = Self.resourceID,
        serviceGeneration: UInt64 = 17,
        hrmIssuedAt: UInt64 = 1_000,
        hrmExpiresAt: UInt64 = 2_000,
        serviceNotBefore: UInt64 = 1_200,
        serviceExpiresAt: UInt64 = 1_900,
        delegationNotBefore: UInt64 = 1_300,
        delegationExpiresAt: UInt64 = 1_800,
        trustedOperationTime: UInt64 = 1_500,
        controllerKey: String = Self.controllerKey,
        maxEndpointLifetimeSeconds: UInt32 = 3_600
    ) -> BrokerVerifiedHrmHnsaNamedService? {
        let selected = selection ?? selectionOrNil()!
        return BrokerVerifiedHrmHnsaNamedService.adoptBrokerVerified(
            selection: selected,
            walletAuthority: walletAuthority,
            hrmSequence: hrmSequence,
            hrmEnvelopeHash: envelopeHash,
            authorityRevision: authorityRevision,
            trustedOperationTime: trustedOperationTime,
            operationLeaseGeneration: operationLeaseGeneration,
            serviceResourceID: resourceID,
            serviceDelegationID: Self.delegationID,
            serviceGeneration: serviceGeneration,
            hrmIssuedAt: hrmIssuedAt,
            hrmExpiresAt: hrmExpiresAt,
            serviceNotBefore: serviceNotBefore,
            serviceExpiresAt: serviceExpiresAt,
            delegationNotBefore: delegationNotBefore,
            delegationExpiresAt: delegationExpiresAt,
            serviceControllerKey: controllerKey,
            profileFlags: 0,
            profileConstraintsHash: Self.zeroHash,
            maxEndpointLifetimeSeconds: maxEndpointLifetimeSeconds,
            allowedEndpointCapabilities: 3,
            endpointConstraintsHash: Self.constraintsHash,
            currentAuthorityGuard: guardFixture
        )
    }

    private static let mainnetMagic: UInt32 = 0x5b6e_f2d3
    private static let testnetMagic: UInt32 = 0xb152_0dd2
    private static let nameHash = String(repeating: "11", count: 32)
    private static let envelopeHash = String(repeating: "22", count: 32)
    private static let resourceID = String(repeating: "33", count: 32)
    private static let delegationID = String(repeating: "44", count: 32)
    private static let zeroHash = String(repeating: "00", count: 32)
    private static let constraintsHash = String(repeating: "55", count: 32)
    private static let controllerKey = "02" + String(repeating: "66", count: 32)
}
