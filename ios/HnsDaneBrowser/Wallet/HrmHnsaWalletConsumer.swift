import Foundation

/// Exact version-1 HRM resource profile used by the HNSA draft.
let hrmHnsaNamedServiceProfile = "hns.named-service/v1"

/// Trusted application selection for one named service. This identity is not
/// evidence that any HRM, HNSA, endpoint, or application record was validated.
struct HrmHnsaNamedServiceSelection:
    Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    let network: BrowserHandshakeNetwork
    let networkMagic: UInt32
    let nameHash: String
    let serviceName: String
    let applicationProfileID: UInt16

    init?(
        network: BrowserHandshakeNetwork,
        networkMagic: UInt32,
        nameHash: String,
        serviceName: String,
        applicationProfileID: UInt16
    ) {
        guard network.hrmHnsaNetworkMagic == networkMagic,
              nameHash.isLowerHex(byteCount: 32),
              serviceName.isCanonicalHnsaServiceName,
              applicationProfileID != 0 else {
            return nil
        }
        self.network = network
        self.networkMagic = networkMagic
        self.nameHash = nameHash
        self.serviceName = serviceName
        self.applicationProfileID = applicationProfileID
    }

    var description: String { "HrmHnsaNamedServiceSelection(<redacted>)" }
    var debugDescription: String { description }
}

/// Exact durable HRM/HNSA observation asserted by a trusted native broker.
/// HRM commitment sequence zero is valid. The nonzero rule applies to the
/// HNSA service generation and to endpoint sequences outside this seam.
/// Swift never constructs this claim from CBOR, DNS, URLs, provider messages,
/// or legacy authority records.
struct HrmHnsaCurrentAuthorityClaim:
    Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    let selection: HrmHnsaNamedServiceSelection
    let hrmSequence: UInt64
    let hrmEnvelopeHash: String
    let authorityRevision: UInt64
    let trustedOperationTime: UInt64
    let operationLeaseGeneration: UInt64
    let serviceResourceID: String
    let serviceDelegationID: String
    let serviceGeneration: UInt64

    var description: String { "HrmHnsaCurrentAuthorityClaim(<redacted>)" }
    var debugDescription: String { description }
}

/// Broker-owned exact-current guard. Implementations must invoke `operation`
/// synchronously at most once, only while the sole subject aggregate or its
/// namespace-wide fenced operation lease remains current and held.
protocol HrmHnsaCurrentAuthorityGuard: AnyObject {
    func useIfCurrent(
        claim: HrmHnsaCurrentAuthorityClaim,
        operation: () -> Bool
    ) -> Bool
}

/// Opaque, broker-verified current `hns.named-service/v1` authority.
///
/// `adoptBrokerVerified` performs defensive shape checks only. It deliberately
/// does not parse or validate HRM/HNSA cryptography, durable rollback state, or
/// application-profile semantics. A future production source may call it only
/// after a qualified native broker has completed and durably acknowledged all
/// of those steps.
final class BrokerVerifiedHrmHnsaNamedService:
    @unchecked Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    let claim: HrmHnsaCurrentAuthorityClaim
    let walletAuthority: WalletReadBootstrapAuthority
    let hrmIssuedAt: UInt64
    let hrmExpiresAt: UInt64
    let serviceNotBefore: UInt64
    let serviceExpiresAt: UInt64
    let delegationNotBefore: UInt64
    let delegationExpiresAt: UInt64
    let serviceControllerKey: String
    let profileFlags: UInt16
    let profileConstraintsHash: String
    let maxEndpointLifetimeSeconds: UInt32
    let allowedEndpointCapabilities: UInt32
    let endpointConstraintsHash: String

    private let currentAuthorityGuard: any HrmHnsaCurrentAuthorityGuard
    private let useLock = NSLock()
    private var useAvailable = true

    private init(
        claim: HrmHnsaCurrentAuthorityClaim,
        walletAuthority: WalletReadBootstrapAuthority,
        hrmIssuedAt: UInt64,
        hrmExpiresAt: UInt64,
        serviceNotBefore: UInt64,
        serviceExpiresAt: UInt64,
        delegationNotBefore: UInt64,
        delegationExpiresAt: UInt64,
        serviceControllerKey: String,
        profileFlags: UInt16,
        profileConstraintsHash: String,
        maxEndpointLifetimeSeconds: UInt32,
        allowedEndpointCapabilities: UInt32,
        endpointConstraintsHash: String,
        currentAuthorityGuard: any HrmHnsaCurrentAuthorityGuard
    ) {
        self.claim = claim
        self.walletAuthority = walletAuthority
        self.hrmIssuedAt = hrmIssuedAt
        self.hrmExpiresAt = hrmExpiresAt
        self.serviceNotBefore = serviceNotBefore
        self.serviceExpiresAt = serviceExpiresAt
        self.delegationNotBefore = delegationNotBefore
        self.delegationExpiresAt = delegationExpiresAt
        self.serviceControllerKey = serviceControllerKey
        self.profileFlags = profileFlags
        self.profileConstraintsHash = profileConstraintsHash
        self.maxEndpointLifetimeSeconds = maxEndpointLifetimeSeconds
        self.allowedEndpointCapabilities = allowedEndpointCapabilities
        self.endpointConstraintsHash = endpointConstraintsHash
        self.currentAuthorityGuard = currentAuthorityGuard
    }

    static func adoptBrokerVerified(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        hrmSequence: UInt64,
        hrmEnvelopeHash: String,
        authorityRevision: UInt64,
        trustedOperationTime: UInt64,
        operationLeaseGeneration: UInt64,
        serviceResourceID: String,
        serviceDelegationID: String,
        serviceGeneration: UInt64,
        hrmIssuedAt: UInt64,
        hrmExpiresAt: UInt64,
        serviceNotBefore: UInt64,
        serviceExpiresAt: UInt64,
        delegationNotBefore: UInt64,
        delegationExpiresAt: UInt64,
        serviceControllerKey: String,
        profileFlags: UInt16,
        profileConstraintsHash: String,
        maxEndpointLifetimeSeconds: UInt32,
        allowedEndpointCapabilities: UInt32,
        endpointConstraintsHash: String,
        currentAuthorityGuard: any HrmHnsaCurrentAuthorityGuard
    ) -> BrokerVerifiedHrmHnsaNamedService? {
        guard selection.network == walletAuthority.network,
              hrmEnvelopeHash.isLowerHex(byteCount: 32),
              authorityRevision != 0,
              operationLeaseGeneration != 0,
              serviceResourceID.isLowerHex(byteCount: 32),
              serviceDelegationID.isLowerHex(byteCount: 32),
              serviceGeneration != 0,
              hrmIssuedAt < hrmExpiresAt,
              serviceNotBefore >= hrmIssuedAt,
              serviceExpiresAt <= hrmExpiresAt,
              serviceNotBefore < serviceExpiresAt,
              delegationNotBefore >= serviceNotBefore,
              delegationExpiresAt <= serviceExpiresAt,
              delegationNotBefore < delegationExpiresAt,
              trustedOperationTime >= delegationNotBefore,
              trustedOperationTime < delegationExpiresAt,
              serviceControllerKey.isCompressedSecp256k1Key,
              profileConstraintsHash.isLowerHex(byteCount: 32),
              (300...604_800).contains(maxEndpointLifetimeSeconds),
              endpointConstraintsHash.isLowerHex(byteCount: 32) else {
            return nil
        }
        let claim = HrmHnsaCurrentAuthorityClaim(
            selection: selection,
            hrmSequence: hrmSequence,
            hrmEnvelopeHash: hrmEnvelopeHash,
            authorityRevision: authorityRevision,
            trustedOperationTime: trustedOperationTime,
            operationLeaseGeneration: operationLeaseGeneration,
            serviceResourceID: serviceResourceID,
            serviceDelegationID: serviceDelegationID,
            serviceGeneration: serviceGeneration
        )
        return BrokerVerifiedHrmHnsaNamedService(
            claim: claim,
            walletAuthority: walletAuthority,
            hrmIssuedAt: hrmIssuedAt,
            hrmExpiresAt: hrmExpiresAt,
            serviceNotBefore: serviceNotBefore,
            serviceExpiresAt: serviceExpiresAt,
            delegationNotBefore: delegationNotBefore,
            delegationExpiresAt: delegationExpiresAt,
            serviceControllerKey: serviceControllerKey,
            profileFlags: profileFlags,
            profileConstraintsHash: profileConstraintsHash,
            maxEndpointLifetimeSeconds: maxEndpointLifetimeSeconds,
            allowedEndpointCapabilities: allowedEndpointCapabilities,
            endpointConstraintsHash: endpointConstraintsHash,
            currentAuthorityGuard: currentAuthorityGuard
        )
    }

    /// Prevents a malformed guard from invoking a dependent operation twice.
    func useIfCurrent(
        _ operation: (BrokerVerifiedHrmHnsaNamedService) -> Bool
    ) -> Bool {
        useLock.lock()
        let mayAttempt = useAvailable
        useAvailable = false
        useLock.unlock()
        guard mayAttempt else { return false }

        let stateLock = NSRecursiveLock()
        var callbackActive = true
        var callbackInvoked = false
        var callbackSucceeded = false
        let guardSucceeded = currentAuthorityGuard.useIfCurrent(claim: claim) {
            stateLock.lock()
            defer { stateLock.unlock() }
            guard callbackActive, !callbackInvoked else { return false }
            callbackInvoked = true
            let result = operation(self)
            callbackSucceeded = result
            return result
        }
        stateLock.lock()
        callbackActive = false
        let result = guardSucceeded && callbackInvoked && callbackSucceeded
        stateLock.unlock()
        return result
    }

    func discard() {
        useLock.lock()
        useAvailable = false
        useLock.unlock()
    }

    var description: String { "BrokerVerifiedHrmHnsaNamedService(<redacted>)" }
    var debugDescription: String { description }
}

/// Single-use transfer of one broker-issued authority into the wallet.
final class HrmHnsaWalletConsumerLease:
    @unchecked Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    private let stateLock = NSLock()
    private var retainedService: BrokerVerifiedHrmHnsaNamedService?

    init(taking service: BrokerVerifiedHrmHnsaNamedService) {
        retainedService = service
    }

    func consumeFor(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        operation: (BrokerVerifiedHrmHnsaNamedService) -> Bool
    ) -> Bool {
        stateLock.lock()
        let service = retainedService
        retainedService = nil
        stateLock.unlock()
        guard let service else { return false }
        guard service.claim.selection == selection,
              service.walletAuthority == walletAuthority else {
            service.discard()
            return false
        }
        return service.useIfCurrent(operation)
    }

    func discard() {
        stateLock.lock()
        let service = retainedService
        retainedService = nil
        stateLock.unlock()
        service?.discard()
    }

    deinit { discard() }

    var description: String { "HrmHnsaWalletConsumerLease(<redacted>)" }
    var debugDescription: String { description }
}

/// Moves one authority for the exact trusted selection and live wallet.
/// Sources must not derive either identity from a URL, endpoint record,
/// provider message, link, preference, pasteboard, or renderer input.
protocol HrmHnsaWalletConsumerSource: AnyObject {
    func takeAuthority(
        for selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority
    ) -> HrmHnsaWalletConsumerLease?
}

/// Shipping builds have no HRM broker or recognized wallet application profile.
final class UnavailableHrmHnsaWalletConsumerSource: HrmHnsaWalletConsumerSource {
    static let shared = UnavailableHrmHnsaWalletConsumerSource()

    private init() {}

    func takeAuthority(
        for selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority
    ) -> HrmHnsaWalletConsumerLease? {
        nil
    }
}

/// Current wallet/application facts re-read around acquisition and under guard.
struct HrmHnsaWalletConsumerState: Equatable, Sendable {
    let selection: HrmHnsaNamedServiceSelection?
    let walletAuthority: WalletReadBootstrapAuthority?
    let foreground: Bool
    let protectedStorageAvailable: Bool
    let reopenedDurableWallet: Bool
    let confirmedPersistentWallet: Bool
    let hasUnconfirmedRecovery: Bool
    let operationInFlight: Bool
    let retirementBlocked: Bool
}

func hrmHnsaWalletConsumerMayUse(
    expectedSelection: HrmHnsaNamedServiceSelection,
    expectedWalletAuthority: WalletReadBootstrapAuthority,
    current: HrmHnsaWalletConsumerState
) -> Bool {
    current.selection == expectedSelection
        && current.walletAuthority == expectedWalletAuthority
        && expectedSelection.network == expectedWalletAuthority.network
        && walletReadBootstrapAuthorityIsWellFormed(expectedWalletAuthority)
        && WalletStorageLeaseRegistry.isCurrent(expectedWalletAuthority.lease)
        && current.foreground
        && current.protectedStorageAvailable
        && current.reopenedDurableWallet
        && current.confirmedPersistentWallet
        && !current.hasUnconfirmedRecovery
        && !current.operationInFlight
        && !current.retirementBlocked
}

/// Fails closed before source acquisition, after its potentially re-entrant
/// callback, and once more while the broker holds exact current HRM authority.
func attemptHrmHnsaWalletConsumerUse(
    expectedSelection: HrmHnsaNamedServiceSelection,
    expectedWalletAuthority: WalletReadBootstrapAuthority,
    source: any HrmHnsaWalletConsumerSource,
    currentState: () -> HrmHnsaWalletConsumerState,
    operation: (BrokerVerifiedHrmHnsaNamedService) -> Bool
) -> Bool {
    guard hrmHnsaWalletConsumerMayUse(
        expectedSelection: expectedSelection,
        expectedWalletAuthority: expectedWalletAuthority,
        current: currentState()
    ),
    let lease = source.takeAuthority(
        for: expectedSelection,
        walletAuthority: expectedWalletAuthority
    ) else {
        return false
    }
    defer { lease.discard() }

    let afterSource = currentState()
    guard let currentWalletAuthority = afterSource.walletAuthority,
          hrmHnsaWalletConsumerMayUse(
              expectedSelection: expectedSelection,
              expectedWalletAuthority: expectedWalletAuthority,
              current: afterSource
          ) else {
        return false
    }
    return lease.consumeFor(
        selection: expectedSelection,
        walletAuthority: currentWalletAuthority
    ) { service in
        hrmHnsaWalletConsumerMayUse(
            expectedSelection: expectedSelection,
            expectedWalletAuthority: expectedWalletAuthority,
            current: currentState()
        ) && operation(service)
    }
}

private extension BrowserHandshakeNetwork {
    var hrmHnsaNetworkMagic: UInt32 {
        switch self {
        case .mainnet: 0x5b6e_f2d3
        case .testnet: 0xb152_0dd2
        case .regtest: 0xae38_95cf
        }
    }
}

private extension String {
    func isLowerHex(byteCount: Int) -> Bool {
        utf8.count == byteCount * 2
            && utf8.allSatisfy {
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0)
                    || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
            }
    }

    var isCanonicalHnsaServiceName: Bool {
        let bytes = Array(utf8)
        guard (1...63).contains(bytes.count),
              bytes.first != UInt8(ascii: "-"),
              bytes.last != UInt8(ascii: "-") else {
            return false
        }
        return bytes.allSatisfy {
            (UInt8(ascii: "a")...UInt8(ascii: "z")).contains($0)
                || (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0)
                || $0 == UInt8(ascii: "-")
        }
    }

    var isCompressedSecp256k1Key: Bool {
        isLowerHex(byteCount: 33) && (hasPrefix("02") || hasPrefix("03"))
    }
}
