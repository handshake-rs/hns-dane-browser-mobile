import Foundation
import HnsBrowserRuntime

/// Compiler-resistant clearing for process-local mutable wallet secrets.
/// The C primitive uses volatile stores because Swift's Darwin overlay does
/// not expose `explicit_bzero` consistently across supported Xcode SDKs.
enum WalletSecretBytes {
    static func wipe(_ bytes: inout [UInt8]) {
        bytes.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            guard let baseAddress = buffer.baseAddress else { return }
            hns_wallet_secure_zero(baseAddress, buffer.count)
        }
        bytes.removeAll(keepingCapacity: false)
    }
}

struct NativeWalletStatus: Decodable, Equatable {
    let locked: Bool
    let activeWallet: String?
    let enabledModules: [String]
    let hnsValueEnabled: Bool
    let shakedexEnabled: Bool
    let mainnetSettlementEnabled: Bool
}

struct NativeWalletAccount: Decodable, Equatable {
    let accountId: String
    let module: String
    let label: String
    let receiveDisplay: String?
}

struct NativeBitcoinWalletSnapshot: Decodable, Equatable, Sendable {
    let network: String
    let receiveAddress: String
    let confirmedSats: UInt64
    let trustedPendingSats: UInt64
    let untrustedPendingSats: UInt64
    let immatureSats: UInt64
    let totalSats: UInt64
    let birthdayHeight: UInt32
    let birthdayState: String
    let synchronizedHeight: UInt64
    let connectedPeerCount: UInt8
    let requiredPeerCount: UInt8

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case network, receiveAddress, confirmedSats, trustedPendingSats
        case untrustedPendingSats, immatureSats, totalSats, birthdayHeight, birthdayState
        case synchronizedHeight
        case connectedPeerCount, requiredPeerCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        network = try container.decode(String.self, forKey: .network)
        receiveAddress = try container.decode(String.self, forKey: .receiveAddress)
        confirmedSats = try container.decode(UInt64.self, forKey: .confirmedSats)
        trustedPendingSats = try container.decode(UInt64.self, forKey: .trustedPendingSats)
        untrustedPendingSats = try container.decode(UInt64.self, forKey: .untrustedPendingSats)
        immatureSats = try container.decode(UInt64.self, forKey: .immatureSats)
        totalSats = try container.decode(UInt64.self, forKey: .totalSats)
        birthdayHeight = try container.decode(UInt32.self, forKey: .birthdayHeight)
        birthdayState = try container.decode(String.self, forKey: .birthdayState)
        synchronizedHeight = try container.decode(UInt64.self, forKey: .synchronizedHeight)
        connectedPeerCount = try container.decode(UInt8.self, forKey: .connectedPeerCount)
        requiredPeerCount = try container.decode(UInt8.self, forKey: .requiredPeerCount)
        let subtotal = confirmedSats.addingReportingOverflow(trustedPendingSats)
        let subtotal2 = subtotal.partialValue.addingReportingOverflow(untrustedPendingSats)
        let expected = subtotal2.partialValue.addingReportingOverflow(immatureSats)
        guard ["mainnet", "testnet", "testnet4", "signet", "regtest"].contains(network),
              Self.validAddress(receiveAddress),
              !subtotal.overflow, !subtotal2.overflow, !expected.overflow,
              expected.partialValue == totalSats,
              ["awaitingCreationTip", "recoveryUnknown", "recoveryPendingValidation", "validated"]
                .contains(birthdayState),
              connectedPeerCount <= 8, requiredPeerCount <= 8 else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin snapshot")
        }
    }

    fileprivate static func validAddress(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 128 && value.utf8.allSatisfy {
            (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                (UInt8(ascii: "A")...UInt8(ascii: "Z")).contains($0) ||
                (UInt8(ascii: "a")...UInt8(ascii: "z")).contains($0)
        }
    }
}

struct NativeBitcoinReceiveAddress: Decodable, Equatable, Sendable {
    let receiveAddress: String
    let snapshot: NativeBitcoinWalletSnapshot

    private enum CodingKeys: String, CodingKey, CaseIterable { case receiveAddress, snapshot }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        receiveAddress = try container.decode(String.self, forKey: .receiveAddress)
        snapshot = try container.decode(NativeBitcoinWalletSnapshot.self, forKey: .snapshot)
        guard receiveAddress == snapshot.receiveAddress else {
            throw NativeWalletBridgeError.invalidOutput("Bitcoin receive result changed its snapshot address")
        }
    }
}

struct NativeBitcoinSynchronization: Decodable, Equatable, Sendable {
    let snapshot: NativeBitcoinWalletSnapshot
    let sequence: UInt64
    let checkpointHeight: UInt64
    let connectedPeerCount: UInt8
    let requiredPeerCount: UInt8

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case snapshot, sequence, checkpointHeight, connectedPeerCount, requiredPeerCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        snapshot = try container.decode(NativeBitcoinWalletSnapshot.self, forKey: .snapshot)
        sequence = try container.decode(UInt64.self, forKey: .sequence)
        checkpointHeight = try container.decode(UInt64.self, forKey: .checkpointHeight)
        connectedPeerCount = try container.decode(UInt8.self, forKey: .connectedPeerCount)
        requiredPeerCount = try container.decode(UInt8.self, forKey: .requiredPeerCount)
        guard sequence > 0,
              checkpointHeight == snapshot.synchronizedHeight,
              connectedPeerCount == snapshot.connectedPeerCount,
              requiredPeerCount == snapshot.requiredPeerCount else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin synchronization")
        }
    }
}

struct NativeBitcoinSyncProgress: Decodable, Equatable, Sendable {
    let successfulHandshakes: UInt8
    let requiredPeerCount: UInt8
    let connectionFailures: UInt16
    let peerTimeouts: UInt16
    let incompatiblePeers: UInt16
    let connectionsMet: Bool
    let chainHeight: UInt64?
    let completionBasisPoints: UInt16

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case successfulHandshakes, requiredPeerCount, connectionFailures, peerTimeouts
        case incompatiblePeers, connectionsMet, chainHeight, completionBasisPoints
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        successfulHandshakes = try container.decode(UInt8.self, forKey: .successfulHandshakes)
        requiredPeerCount = try container.decode(UInt8.self, forKey: .requiredPeerCount)
        connectionFailures = try container.decode(UInt16.self, forKey: .connectionFailures)
        peerTimeouts = try container.decode(UInt16.self, forKey: .peerTimeouts)
        incompatiblePeers = try container.decode(UInt16.self, forKey: .incompatiblePeers)
        connectionsMet = try container.decode(Bool.self, forKey: .connectionsMet)
        chainHeight = try container.decodeIfPresent(UInt64.self, forKey: .chainHeight)
        completionBasisPoints = try container.decode(UInt16.self, forKey: .completionBasisPoints)
        guard requiredPeerCount > 0, completionBasisPoints <= 10_000 else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin progress")
        }
    }
}

struct NativeBitcoinSendApproval {
    let actionToken: NativeHnsSendActionToken
    let destination: String
    let amountSats: UInt64
    let feeSats: UInt64
    let maximumFeeSats: UInt64
    let expiresAtUnix: UInt64
}

struct NativeBitcoinHtlcFundingApproval {
    let actionToken: NativeHnsSendActionToken
    let sessionId: String
    let txid: String
    let amountSats: UInt64
    let feeSats: UInt64
    let maximumFeeSats: UInt64
    let refundAtUnix: UInt64
    let expiresAtUnix: UInt64
}

struct NativeBitcoinHtlcFundingReceipt: Decodable, Equatable, Sendable {
    let sessionId: String
    let txid: String
    let attemptCount: UInt8
    let submittedAtUnix: UInt64?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case sessionId, txid, attemptCount, submittedAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        txid = try container.decode(String.self, forKey: .txid)
        attemptCount = try container.decode(UInt8.self, forKey: .attemptCount)
        submittedAtUnix = try container.decodeIfPresent(UInt64.self, forKey: .submittedAtUnix)
        guard Self.validHash(sessionId), Self.validHash(txid),
              (1...16).contains(attemptCount), submittedAtUnix.map { $0 > 0 } ?? true else {
            throw NativeWalletBridgeError.invalidOutput("invalid Bitcoin HTLC funding receipt")
        }
    }

    fileprivate static func validHash(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.contains { $0 != UInt8(ascii: "0") } &&
            value.utf8.allSatisfy {
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                    (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
            }
    }
}

struct NativeHnsHtlcFundingApproval {
    let actionToken: NativeHnsSendActionToken
    let sessionId: String
    let transactionId: String
    let amountDollarydoos: UInt64
    let feeDollarydoos: UInt64
    let maximumFeeDollarydoos: UInt64
    let refundAtUnix: UInt64
    let expiresAtUnix: UInt64
}

struct NativeHnsHtlcFundingReceipt: Decodable, Equatable, Sendable {
    let sessionId: String
    let transactionId: String
    let acceptedAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case sessionId, transactionId, acceptedAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        transactionId = try container.decode(String.self, forKey: .transactionId)
        acceptedAtUnix = try container.decode(UInt64.self, forKey: .acceptedAtUnix)
        guard NativeBitcoinHtlcFundingReceipt.validHash(sessionId),
              NativeBitcoinHtlcFundingReceipt.validHash(transactionId), acceptedAtUnix > 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS HTLC funding receipt")
        }
    }
}

enum NativeSwapSettlementAction: String, Decodable, Equatable, Sendable {
    case redeem
    case refund
}

struct NativeSwapSettlementApproval {
    let actionToken: NativeHnsSendActionToken
    let sessionId: String
    let action: NativeSwapSettlementAction
    let transactionId: String
    let inputAmount: UInt64
    let outputAmount: UInt64
    let fee: UInt64
    let maximumFee: UInt64
    let expiresAtUnix: UInt64
}

struct NativeSwapSettlementReceipt: Equatable, Sendable {
    let sessionId: String
    let action: NativeSwapSettlementAction
    let transactionId: String
    let acceptedAtUnix: UInt64?
    let attemptCount: UInt8?
    let submittedAtUnix: UInt64?
}

struct NativeBtcForHnsOfferApproval {
    let actionToken: NativeHnsSendActionToken
    let btcAmountSats: UInt64
    let hnsAmountDollarydoos: UInt64
    let bitcoinFeeReserveSats: UInt64
    let totalBitcoinCommitmentSats: UInt64
    let offerExpiresAtUnix: UInt64
    let approvalExpiresAtUnix: UInt64
    let connectedPeerRequiredForAnnouncement: Bool
}

struct NativeBtcForHnsOfferSummary: Decodable, Equatable, Sendable {
    let offerId: String
    let sessionId: String
    let btcAmountSats: UInt64
    let hnsAmountDollarydoos: UInt64
    let bitcoinFeeReserveSats: UInt64
    let createdAtUnix: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case offerId, sessionId, btcAmountSats, hnsAmountDollarydoos
        case bitcoinFeeReserveSats, createdAtUnix, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        offerId = try container.decode(String.self, forKey: .offerId)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        btcAmountSats = try container.decode(UInt64.self, forKey: .btcAmountSats)
        hnsAmountDollarydoos = try container.decode(UInt64.self, forKey: .hnsAmountDollarydoos)
        bitcoinFeeReserveSats = try container.decode(UInt64.self, forKey: .bitcoinFeeReserveSats)
        createdAtUnix = try container.decode(UInt64.self, forKey: .createdAtUnix)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        let validHash: (String) -> Bool = { value in
            value.utf8.count == 64 && value.utf8.allSatisfy {
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                    (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
            }
        }
        guard validHash(offerId), validHash(sessionId), btcAmountSats > 0,
              hnsAmountDollarydoos > 0, bitcoinFeeReserveSats > 0,
              createdAtUnix > 0, expiresAtUnix > createdAtUnix else {
            throw NativeWalletBridgeError.invalidOutput("invalid BTC-for-HNS offer summary")
        }
    }
}

private struct NativeBtcForHnsOfferList: Decodable {
    let offers: [NativeBtcForHnsOfferSummary]

    private enum CodingKeys: String, CodingKey, CaseIterable { case offers }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        offers = try container.decode([NativeBtcForHnsOfferSummary].self, forKey: .offers)
        guard offers.count <= 1_024 else {
            throw NativeWalletBridgeError.invalidOutput("too many local BTC-for-HNS offers")
        }
    }
}

struct NativeShakescapeExecutionSummary: Decodable, Equatable, Sendable {
    let sessionId: String
    let revision: UInt64
    let state: String
    let firstChain: String
    let secondChain: String
    let offeredAsset: String
    let offeredAmount: UInt64
    let receivedAsset: String
    let receivedAmount: UInt64
    let firstRefundAtUnix: UInt64
    let secondRefundAtUnix: UInt64
    let firstFundingConfirmed: Bool
    let secondFundingConfirmed: Bool
    let firstRedemptionConfirmed: Bool
    let secondRedemptionConfirmed: Bool
    let refundConfirmed: Bool
    let lastVerifiedAtUnix: UInt64
    let failureReason: String?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case sessionId, revision, state, firstChain, secondChain, offeredAsset, offeredAmount
        case receivedAsset, receivedAmount, firstRefundAtUnix, secondRefundAtUnix
        case firstFundingConfirmed, secondFundingConfirmed, firstRedemptionConfirmed
        case secondRedemptionConfirmed, refundConfirmed, lastVerifiedAtUnix, failureReason
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        revision = try container.decode(UInt64.self, forKey: .revision)
        state = try container.decode(String.self, forKey: .state)
        firstChain = try container.decode(String.self, forKey: .firstChain)
        secondChain = try container.decode(String.self, forKey: .secondChain)
        offeredAsset = try container.decode(String.self, forKey: .offeredAsset)
        offeredAmount = try container.decode(UInt64.self, forKey: .offeredAmount)
        receivedAsset = try container.decode(String.self, forKey: .receivedAsset)
        receivedAmount = try container.decode(UInt64.self, forKey: .receivedAmount)
        firstRefundAtUnix = try container.decode(UInt64.self, forKey: .firstRefundAtUnix)
        secondRefundAtUnix = try container.decode(UInt64.self, forKey: .secondRefundAtUnix)
        firstFundingConfirmed = try container.decode(Bool.self, forKey: .firstFundingConfirmed)
        secondFundingConfirmed = try container.decode(Bool.self, forKey: .secondFundingConfirmed)
        firstRedemptionConfirmed = try container.decode(Bool.self, forKey: .firstRedemptionConfirmed)
        secondRedemptionConfirmed = try container.decode(Bool.self, forKey: .secondRedemptionConfirmed)
        refundConfirmed = try container.decode(Bool.self, forKey: .refundConfirmed)
        lastVerifiedAtUnix = try container.decode(UInt64.self, forKey: .lastVerifiedAtUnix)
        failureReason = try container.decodeIfPresent(String.self, forKey: .failureReason)
        let validStates: Set<String> = [
            "offer_published", "offer_take_received", "offer_reserved", "terms_frozen",
            "refunds_prepared", "first_funding_pending", "first_funded",
            "second_funding_pending", "both_funded", "first_redeemed", "secret_observed",
            "second_redeemed", "completed", "refund_eligible", "refund_broadcast",
            "refunded", "failed",
        ]
        guard NativeBitcoinHtlcFundingReceipt.validHash(sessionId), revision > 0,
              validStates.contains(state), ["bitcoin", "handshake"].contains(firstChain),
              ["bitcoin", "handshake"].contains(secondChain), firstChain != secondChain,
              ["btc", "hns"].contains(offeredAsset), ["btc", "hns"].contains(receivedAsset),
              offeredAsset != receivedAsset, offeredAmount > 0, receivedAmount > 0,
              firstRefundAtUnix > secondRefundAtUnix, lastVerifiedAtUnix > 0,
              failureReason.map { !$0.isEmpty && $0.count <= 256 } ?? true else {
            throw NativeWalletBridgeError.invalidOutput("invalid durable Shakescape execution")
        }
    }
}

struct NativeBitcoinBroadcastRecovery: Decodable, Equatable, Sendable {
    let totalApproved: UInt32
    let unobservedPrepared: UInt32
    let unobservedSubmissionStarted: UInt32
    let unobservedSubmitted: UInt32
    let observed: UInt32
    let highestAttemptCount: UInt16
    let lastChangedAtUnix: UInt64?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case totalApproved, unobservedPrepared, unobservedSubmissionStarted
        case unobservedSubmitted, observed, highestAttemptCount, lastChangedAtUnix
    }

    init(from decoder: Decoder) throws {
        let value = try decoder.strictContainer(keyedBy: CodingKeys.self)
        totalApproved = try value.decode(UInt32.self, forKey: .totalApproved)
        unobservedPrepared = try value.decode(UInt32.self, forKey: .unobservedPrepared)
        unobservedSubmissionStarted = try value.decode(
            UInt32.self, forKey: .unobservedSubmissionStarted
        )
        unobservedSubmitted = try value.decode(UInt32.self, forKey: .unobservedSubmitted)
        observed = try value.decode(UInt32.self, forKey: .observed)
        highestAttemptCount = try value.decode(UInt16.self, forKey: .highestAttemptCount)
        lastChangedAtUnix = try value.decodeIfPresent(UInt64.self, forKey: .lastChangedAtUnix)
        let counts = [unobservedPrepared, unobservedSubmissionStarted, unobservedSubmitted, observed]
        let lifecycleIsValid: Bool
        if totalApproved == 0 {
            lifecycleIsValid = lastChangedAtUnix == nil && highestAttemptCount == 0
        } else {
            lifecycleIsValid = lastChangedAtUnix.map { $0 > 0 } == true
        }
        guard totalApproved <= 4_096,
              counts.allSatisfy({ $0 <= 4_096 }),
              counts.reduce(UInt32(0), +) == totalApproved,
              highestAttemptCount <= 16,
              lifecycleIsValid else {
            throw NativeWalletBridgeError.invalidOutput("invalid Bitcoin broadcast recovery status")
        }
    }
}

struct NativeShakescapeExecutionStatus: Decodable, Equatable, Sendable {
    let executions: [NativeShakescapeExecutionSummary]
    let bitcoinBroadcastRecovery: NativeBitcoinBroadcastRecovery?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case executions, bitcoinBroadcastRecovery
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        executions = try container.decode([NativeShakescapeExecutionSummary].self, forKey: .executions)
        bitcoinBroadcastRecovery = try container.decodeIfPresent(
            NativeBitcoinBroadcastRecovery.self, forKey: .bitcoinBroadcastRecovery
        )
        guard executions.count <= 1_024 else {
            throw NativeWalletBridgeError.invalidOutput("too many durable Shakescape executions")
        }
    }
}

struct NativeBitcoinSendReceipt: Decodable, Equatable, Sendable {
    let txid: String
    let wtxid: String
    let attemptCount: UInt8
    let submittedAtUnix: UInt64?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case txid, wtxid, attemptCount, submittedAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        txid = try container.decode(String.self, forKey: .txid)
        wtxid = try container.decode(String.self, forKey: .wtxid)
        attemptCount = try container.decode(UInt8.self, forKey: .attemptCount)
        submittedAtUnix = try container.decodeIfPresent(UInt64.self, forKey: .submittedAtUnix)
        let validHash: (String) -> Bool = { value in
            value.utf8.count == 64 && value.utf8.allSatisfy {
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                    (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
            }
        }
        guard validHash(txid), validHash(wtxid), (1...16).contains(attemptCount),
              submittedAtUnix.map { $0 > 0 } ?? true else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin send receipt")
        }
    }
}

struct WalletReadBootstrapAuthority:
    Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    let network: BrowserHandshakeNetwork
    let databasePath: String
    let lease: WalletStorageLeaseToken
    let walletIdentity: ObjectIdentifier
    let ownerGeneration: UInt64

    var description: String {
        "WalletReadBootstrapAuthority(<redacted>)"
    }

    var debugDescription: String { description }
}

func walletReadBootstrapAuthorityIsWellFormed(
    _ authority: WalletReadBootstrapAuthority
) -> Bool {
    let database = URL(fileURLWithPath: authority.databasePath).standardizedFileURL
    let networkDirectory = database.deletingLastPathComponent()
    let walletRoot = networkDirectory.deletingLastPathComponent()
    return authority.ownerGeneration > 0 &&
        database.path == authority.databasePath &&
        database.lastPathComponent == "wallet.sqlite3" &&
        networkDirectory.lastPathComponent == authority.network.rawValue &&
        walletRoot.lastPathComponent == "NativeWallet" &&
        authority.lease.path == authority.databasePath
}

protocol WalletReadBootstrapSource: AnyObject {
    /// Moves at most one scoped credential for the exact live wallet
    /// authority. Sources must not synthesize credentials from preferences,
    /// links, pasteboard contents, or other renderer-controlled input.
    func takeConfiguration(
        for authority: WalletReadBootstrapAuthority
    ) -> NativeHnsReadConfiguration?
}

/// Production remains fail-closed until a trusted native companion broker is
/// qualified. Keeping the unavailable source explicit prevents UI or stored
/// settings from becoming an accidental credential channel.
final class UnavailableWalletReadBootstrapSource: WalletReadBootstrapSource {
    static let shared = UnavailableWalletReadBootstrapSource()

    private init() {}

    func takeConfiguration(
        for authority: WalletReadBootstrapAuthority
    ) -> NativeHnsReadConfiguration? {
        nil
    }
}

enum NativeHnsReadConfigurationError: Error, Equatable {
    case consumed
    case authorityMismatch
}

final class NativeHnsReadConfiguration: @unchecked Sendable, CustomStringConvertible {
    let authority: WalletReadBootstrapAuthority
    let loopbackPort: UInt16
    private let stateLock = NSLock()
    private var authorization: [UInt8]
    private var consumed = false

    var description: String {
        "NativeHnsReadConfiguration(authority: scoped, authorization: <redacted>)"
    }

    /// Takes ownership of a caller-controlled copy and wipes the caller's
    /// buffer on every exit. The configuration wipes its retained copy after
    /// its single native composition attempt.
    init?(
        authority: WalletReadBootstrapAuthority,
        loopbackPort: UInt16,
        authorization: inout [UInt8]
    ) {
        guard walletReadBootstrapAuthorityIsWellFormed(authority),
              loopbackPort != 0,
              !authorization.isEmpty,
              authorization.count <= 4_096,
              authorization.first != UInt8(ascii: " "),
              authorization.last != UInt8(ascii: " "),
              authorization.allSatisfy({ (0x20...0x7e).contains($0) }) else {
            WalletSecretBytes.wipe(&authorization)
            return nil
        }
        self.authority = authority
        self.loopbackPort = loopbackPort
        self.authorization = authorization
        WalletSecretBytes.wipe(&authorization)
    }

    func consume<T>(
        _ body: (UInt16, UnsafeRawBufferPointer) throws -> T
    ) throws -> T {
        try consume(for: authority, body)
    }

    func consume<T>(
        for currentAuthority: WalletReadBootstrapAuthority,
        _ body: (UInt16, UnsafeRawBufferPointer) throws -> T
    ) throws -> T {
        stateLock.lock()
        guard !consumed else {
            stateLock.unlock()
            throw NativeHnsReadConfigurationError.consumed
        }
        consumed = true
        var retainedAuthorization = authorization
        WalletSecretBytes.wipe(&authorization)
        stateLock.unlock()

        defer { WalletSecretBytes.wipe(&retainedAuthorization) }
        guard authority == currentAuthority else {
            throw NativeHnsReadConfigurationError.authorityMismatch
        }
        return try retainedAuthorization.withUnsafeBytes { bytes in
            try body(loopbackPort, bytes)
        }
    }

    func discard() {
        _ = try? consume { _, _ in () }
    }

    deinit {
        stateLock.lock()
        WalletSecretBytes.wipe(&authorization)
        stateLock.unlock()
    }
}

enum WalletExactHnsNameInputError: Error, Equatable {
    case consumed
}

/// Single-use mutable UTF-8 ownership for one trusted-native name prompt.
/// Construction preserves the entered bytes exactly, while inspecting at most
/// 64 bytes so an unbounded Swift allocation is never created for this bridge.
final class WalletExactHnsNameInput: @unchecked Sendable {
    private let stateLock = NSLock()
    private var bytes: [UInt8]
    private var consumed = false

    init?(exactText: String?) {
        guard let exactText else { return nil }
        var candidate: [UInt8] = []
        candidate.reserveCapacity(63)
        for byte in exactText.utf8.prefix(64) {
            guard candidate.count < 63 else {
                WalletSecretBytes.wipe(&candidate)
                return nil
            }
            candidate.append(byte)
        }
        guard !candidate.isEmpty else {
            WalletSecretBytes.wipe(&candidate)
            return nil
        }
        bytes = candidate
    }

    func consume<T>(_ body: (inout [UInt8]) throws -> T) throws -> T {
        stateLock.lock()
        guard !consumed else {
            stateLock.unlock()
            throw WalletExactHnsNameInputError.consumed
        }
        consumed = true
        var retained = bytes
        WalletSecretBytes.wipe(&bytes)
        stateLock.unlock()
        defer { WalletSecretBytes.wipe(&retained) }
        return try body(&retained)
    }

    deinit {
        stateLock.lock()
        WalletSecretBytes.wipe(&bytes)
        stateLock.unlock()
    }
}

struct NativeHnsReadSnapshot: Equatable, Sendable {
    struct Amount: Decodable, Equatable, Sendable {
        let asset: String
        let baseUnits: String

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case asset
            case baseUnits = "base_units"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            asset = try container.decode(String.self, forKey: .asset)
            baseUnits = try container.decode(String.self, forKey: .baseUnits)
            guard asset == "HNS", Self.isCanonicalBaseUnits(baseUnits) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS balance")
            }
        }

        fileprivate static func isCanonicalBaseUnits(_ value: String) -> Bool {
            let maximum = "340282366920938463463374607431768211455"
            guard !value.isEmpty,
                  value.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }),
                  value == "0" || value.first != "0",
                  value.count <= maximum.count else {
                return false
            }
            return value.count < maximum.count || value <= maximum
        }
    }

    struct SignedAmount: Decodable, Equatable, Sendable {
        let negative: Bool
        let magnitude: String

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case negative, magnitude
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            negative = try container.decode(Bool.self, forKey: .negative)
            magnitude = try container.decode(String.self, forKey: .magnitude)
            guard Amount.isCanonicalBaseUnits(magnitude),
                  !negative || magnitude != "0" else {
                throw NativeWalletBridgeError.invalidOutput("invalid transaction amount")
            }
        }
    }

    struct ReceiveTarget: Decodable, Equatable, Sendable {
        let module: String
        let account: [UInt8]
        let display: String
        let derivationIndex: UInt32

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case module, account, display
            case derivationIndex = "derivation_index"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            module = try container.decode(String.self, forKey: .module)
            account = try container.decode([UInt8].self, forKey: .account)
            display = try container.decode(String.self, forKey: .display)
            derivationIndex = try container.decode(UInt32.self, forKey: .derivationIndex)
            guard module == "handshake",
                  account.count == 16,
                  account.contains(where: { $0 != 0 }),
                  !display.isEmpty,
                  display.utf8.count <= 512,
                  display.utf8.allSatisfy({ (0x21...0x7e).contains($0) }) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS receive target")
            }
        }
    }

    /// A Handshake name-owner target is deliberately not interchangeable with
    /// an ordinary HNS payment target, even though both use the same bounded
    /// wire primitives.
    struct NameReceiveTarget: Decodable, Equatable, Sendable {
        let module: String
        let account: [UInt8]
        let display: String
        let derivationIndex: UInt32

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case module, account, display
            case derivationIndex = "derivation_index"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            module = try container.decode(String.self, forKey: .module)
            account = try container.decode([UInt8].self, forKey: .account)
            display = try container.decode(String.self, forKey: .display)
            derivationIndex = try container.decode(UInt32.self, forKey: .derivationIndex)
            guard module == "handshake",
                  account.count == 16,
                  account.contains(where: { $0 != 0 }),
                  !display.isEmpty,
                  display.utf8.count <= 512,
                  display.utf8.allSatisfy({ (0x21...0x7e).contains($0) }) else {
                throw NativeWalletBridgeError.invalidOutput(
                    "invalid HNS name receive target"
                )
            }
        }
    }

    struct Transaction: Decodable, Equatable, Sendable {
        let module: String
        let txid: [UInt8]
        let status: String
        let netAmount: SignedAmount
        let fee: String?
        let blockHeight: UInt64?
        let firstSeenUnix: UInt64?
        let confirmationCount: UInt32

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case module, txid, status, fee
            case netAmount = "net_amount"
            case blockHeight = "block_height"
            case firstSeenUnix = "first_seen_unix"
            case confirmationCount = "confirmation_count"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            module = try container.decode(String.self, forKey: .module)
            txid = try container.decode([UInt8].self, forKey: .txid)
            status = try container.decode(String.self, forKey: .status)
            netAmount = try container.decode(SignedAmount.self, forKey: .netAmount)
            fee = try container.decodeIfPresent(String.self, forKey: .fee)
            blockHeight = try container.decodeIfPresent(UInt64.self, forKey: .blockHeight)
            firstSeenUnix = try container.decodeIfPresent(UInt64.self, forKey: .firstSeenUnix)
            confirmationCount = try container.decode(UInt32.self, forKey: .confirmationCount)
            let statuses: Set<String> = [
                "prepared", "authorized", "broadcast", "mempool", "confirmed", "replaced",
                "conflicted", "reorged", "dropped", "failed",
            ]
            guard module == "handshake",
                  txid.count == 32,
                  txid.contains(where: { $0 != 0 }),
                  statuses.contains(status),
                  fee.map(Amount.isCanonicalBaseUnits) ?? true else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS transaction summary")
            }
        }
    }

    struct KnownName: Decodable, Equatable, Sendable {
        let name: String
        let nameHash: String
        let proofHeight: UInt64
        let resourceStatus: String
        let ownershipStatus: String
        let registered: Bool?
        let expired: Bool?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case name, nameHash, proofHeight, resourceStatus, ownershipStatus, registered, expired
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            name = try container.decode(String.self, forKey: .name)
            nameHash = try container.decode(String.self, forKey: .nameHash)
            proofHeight = try container.decode(UInt64.self, forKey: .proofHeight)
            resourceStatus = try container.decode(String.self, forKey: .resourceStatus)
            ownershipStatus = try container.decode(String.self, forKey: .ownershipStatus)
            registered = try container.decodeIfPresent(Bool.self, forKey: .registered)
            expired = try container.decodeIfPresent(Bool.self, forKey: .expired)
            let resourceStatuses: Set<String> = [
                "unavailableCanonicalBinding", "noCurrentState", "empty", "canonicalDecoded",
                "canonicalOpaque",
            ]
            let ownershipStatuses: Set<String> = [
                "watchOnlyCanonicalStateDecoderUnavailable", "walletContextUnavailable",
                "noCurrentOwner", "notWalletOwned", "walletOwned", "incomingTransfer",
                "outgoingTransfer",
            ]
            guard Self.isCanonicalHandshakeName(name),
                  nameHash.count == 64,
                  nameHash.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0) }),
                  resourceStatuses.contains(resourceStatus),
                  ownershipStatuses.contains(ownershipStatus) else {
                throw NativeWalletBridgeError.invalidOutput("invalid known HNS name")
            }
        }

        fileprivate static func isCanonicalHandshakeName(_ value: String) -> Bool {
            let bytes = Array(value.utf8)
            let reserved: Set<String> = ["example", "invalid", "local", "localhost", "test"]
            guard (1...63).contains(bytes.count), !reserved.contains(value) else {
                return false
            }
            return bytes.enumerated().allSatisfy { index, byte in
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte) ||
                    (UInt8(ascii: "a")...UInt8(ascii: "z")).contains(byte) ||
                    (byte == UInt8(ascii: "-") || byte == UInt8(ascii: "_")) &&
                    index != 0 && index + 1 != bytes.count
            }
        }
    }

    struct FinalizeNotice: Decodable, Equatable, Sendable {
        let name: String
        let transactionID: String
        let phase: String
        let currentHeight: UInt64
        let finalizeEligibleHeight: UInt64?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case name, phase, currentHeight, finalizeEligibleHeight
            case transactionID = "transactionId"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            name = try container.decode(String.self, forKey: .name)
            transactionID = try container.decode(String.self, forKey: .transactionID)
            phase = try container.decode(String.self, forKey: .phase)
            currentHeight = try container.decode(UInt64.self, forKey: .currentHeight)
            finalizeEligibleHeight = try container.decodeIfPresent(
                UInt64.self,
                forKey: .finalizeEligibleHeight
            )
            let phases: Set<String> = [
                "transferPending", "finalizeWaiting", "finalizeAvailable", "finalizePending",
            ]
            guard KnownName.isCanonicalHandshakeName(name),
                  transactionID.count == 64,
                  transactionID.utf8.allSatisfy({
                      (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                      (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
                  }),
                  phases.contains(phase),
                  (phase == "transferPending") == (finalizeEligibleHeight == nil) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS finalize notice")
            }
        }
    }

    struct ModuleStatus: Decodable, Equatable, Sendable {
        let phase: String
        let validatedHeight: UInt64
        let scannedHeight: UInt64
        let targetHeight: UInt64?
        let lastError: String?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case phase
            case validatedHeight = "validated_height"
            case scannedHeight = "scanned_height"
            case targetHeight = "target_height"
            case lastError = "last_error"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            phase = try container.decode(String.self, forKey: .phase)
            validatedHeight = try container.decode(UInt64.self, forKey: .validatedHeight)
            scannedHeight = try container.decode(UInt64.self, forKey: .scannedHeight)
            targetHeight = try container.decodeIfPresent(UInt64.self, forKey: .targetHeight)
            lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
            guard phase == "ready",
                  validatedHeight == scannedHeight,
                  targetHeight == validatedHeight,
                  lastError == nil else {
                throw NativeWalletBridgeError.invalidOutput("incoherent HNS synchronization status")
            }
        }
    }

    let balance: Amount
    let receiveTarget: ReceiveTarget
    let nameReceiveTarget: NameReceiveTarget?
    let transactionHistory: [Transaction]
    let knownNames: [KnownName]
    let knownNameCount: Int
    let finalizeNotices: [FinalizeNotice]
    let moduleStatus: ModuleStatus

    private struct VersionOnePayload: Decodable {
        let balance: Amount
        let receiveTarget: ReceiveTarget
        let transactionHistory: [Transaction]
        let knownNames: [KnownName]
        let moduleStatus: ModuleStatus

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case balance, receiveTarget, transactionHistory, knownNames, moduleStatus
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            balance = try container.decode(Amount.self, forKey: .balance)
            receiveTarget = try container.decode(ReceiveTarget.self, forKey: .receiveTarget)
            transactionHistory = try container.decode(
                [Transaction].self,
                forKey: .transactionHistory
            )
            knownNames = try container.decode([KnownName].self, forKey: .knownNames)
            moduleStatus = try container.decode(ModuleStatus.self, forKey: .moduleStatus)
        }
    }

    private struct VersionTwoPayload: Decodable {
        let balance: Amount
        let receiveTarget: ReceiveTarget
        let nameReceiveTarget: NameReceiveTarget
        let transactionHistory: [Transaction]
        let knownNames: [KnownName]
        let moduleStatus: ModuleStatus

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case balance, receiveTarget, nameReceiveTarget, transactionHistory, knownNames
            case moduleStatus
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            balance = try container.decode(Amount.self, forKey: .balance)
            receiveTarget = try container.decode(ReceiveTarget.self, forKey: .receiveTarget)
            nameReceiveTarget = try container.decode(
                NameReceiveTarget.self,
                forKey: .nameReceiveTarget
            )
            transactionHistory = try container.decode(
                [Transaction].self,
                forKey: .transactionHistory
            )
            knownNames = try container.decode([KnownName].self, forKey: .knownNames)
            moduleStatus = try container.decode(ModuleStatus.self, forKey: .moduleStatus)
        }
    }

    private struct VersionThreePayload: Decodable {
        let balance: Amount
        let receiveTarget: ReceiveTarget
        let nameReceiveTarget: NameReceiveTarget
        let transactionHistory: [Transaction]
        let knownNames: [KnownName]
        let knownNameCount: UInt32
        let knownNamesComplete: Bool
        let finalizeNotices: [FinalizeNotice]
        let moduleStatus: ModuleStatus

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case balance, receiveTarget, nameReceiveTarget, transactionHistory, knownNames
            case knownNameCount, knownNamesComplete, finalizeNotices, moduleStatus
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            balance = try container.decode(Amount.self, forKey: .balance)
            receiveTarget = try container.decode(ReceiveTarget.self, forKey: .receiveTarget)
            nameReceiveTarget = try container.decode(NameReceiveTarget.self, forKey: .nameReceiveTarget)
            transactionHistory = try container.decode([Transaction].self, forKey: .transactionHistory)
            knownNames = try container.decode([KnownName].self, forKey: .knownNames)
            knownNameCount = try container.decode(UInt32.self, forKey: .knownNameCount)
            knownNamesComplete = try container.decode(Bool.self, forKey: .knownNamesComplete)
            finalizeNotices = try container.decode([FinalizeNotice].self, forKey: .finalizeNotices)
            moduleStatus = try container.decode(ModuleStatus.self, forKey: .moduleStatus)
            guard knownNameCount >= knownNames.count,
                  knownNamesComplete == (Int(knownNameCount) == knownNames.count) else {
                throw NativeWalletBridgeError.invalidOutput("invalid paginated HNS name projection")
            }
        }
    }

    private init(
        balance: Amount,
        receiveTarget: ReceiveTarget,
        nameReceiveTarget: NameReceiveTarget?,
        transactionHistory: [Transaction],
        knownNames: [KnownName],
        knownNameCount: Int? = nil,
        finalizeNotices: [FinalizeNotice] = [],
        moduleStatus: ModuleStatus
    ) throws {
        self.balance = balance
        self.receiveTarget = receiveTarget
        self.nameReceiveTarget = nameReceiveTarget
        self.transactionHistory = transactionHistory
        self.knownNames = knownNames
        self.knownNameCount = knownNameCount ?? knownNames.count
        self.finalizeNotices = finalizeNotices
        self.moduleStatus = moduleStatus
        guard transactionHistory.count <= 10_000,
              knownNames.count <= 10_000,
              self.knownNameCount >= knownNames.count,
              self.knownNameCount <= 10_000,
              Set(transactionHistory.map(\.txid)).count == transactionHistory.count,
              Set(knownNames.map(\.name)).count == knownNames.count,
              Set(knownNames.map(\.nameHash)).count == knownNames.count,
              Set(finalizeNotices.map(\.name)).count == finalizeNotices.count else {
            throw NativeWalletBridgeError.invalidOutput("HNS read snapshot exceeds native bounds")
        }
        if let nameReceiveTarget {
            guard receiveTarget.account == nameReceiveTarget.account,
                  receiveTarget.display != nameReceiveTarget.display else {
                throw NativeWalletBridgeError.invalidOutput(
                    "HNS payment and name receive targets are not distinct"
                )
            }
        }
    }

    static func decode(bundle: [UInt8]) throws -> NativeHnsReadSnapshot {
        let headerLength = 12
        guard bundle.count > headerLength,
              bundle.count <= 4 * 1_024 * 1_024,
              Array(bundle[0..<4]) == Array("HNWR".utf8),
              bundle[5] == 1,
              bundle[6] == 0,
              bundle[7] == 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS read bundle header")
        }
        let payloadLength = bundle[8..<12].reduce(UInt32(0)) { partial, byte in
            (partial << 8) | UInt32(byte)
        }
        guard payloadLength > 0,
              Int(payloadLength) == bundle.count - headerLength else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS read bundle length")
        }
        let payload = Data(bundle[headerLength...])
        let decoder = JSONDecoder()
        switch bundle[4] {
        case 1:
            let decoded = try decoder.decode(VersionOnePayload.self, from: payload)
            return try NativeHnsReadSnapshot(
                balance: decoded.balance,
                receiveTarget: decoded.receiveTarget,
                nameReceiveTarget: nil,
                transactionHistory: decoded.transactionHistory,
                knownNames: decoded.knownNames,
                moduleStatus: decoded.moduleStatus
            )
        case 2:
            let decoded = try decoder.decode(VersionTwoPayload.self, from: payload)
            return try NativeHnsReadSnapshot(
                balance: decoded.balance,
                receiveTarget: decoded.receiveTarget,
                nameReceiveTarget: decoded.nameReceiveTarget,
                transactionHistory: decoded.transactionHistory,
                knownNames: decoded.knownNames,
                moduleStatus: decoded.moduleStatus
            )
        case 3:
            let decoded = try decoder.decode(VersionThreePayload.self, from: payload)
            return try NativeHnsReadSnapshot(
                balance: decoded.balance,
                receiveTarget: decoded.receiveTarget,
                nameReceiveTarget: decoded.nameReceiveTarget,
                transactionHistory: decoded.transactionHistory,
                knownNames: decoded.knownNames,
                knownNameCount: Int(decoded.knownNameCount),
                finalizeNotices: decoded.finalizeNotices,
                moduleStatus: decoded.moduleStatus
            )
        default:
            throw NativeWalletBridgeError.invalidOutput(
                "unsupported HNS read bundle version"
            )
        }
    }
}

/// Success-only private HNWI-v1 result. Failures stay in the C result channel.
enum NativeHnsNameImportBundle {
    static func decode(bundle: [UInt8]) throws -> NativeHnsReadSnapshot.KnownName {
        let headerLength = 12
        let maximumJSONBytes = 4_096
        guard bundle.count > headerLength,
              bundle.count <= headerLength + maximumJSONBytes,
              Array(bundle[0..<4]) == Array("HNWI".utf8),
              bundle[4] == 1,
              bundle[5] == 0,
              bundle[6] == 0,
              bundle[7] == 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS name import bundle header")
        }
        let payloadLength = bundle[8..<12].reduce(UInt32(0)) { partial, byte in
            (partial << 8) | UInt32(byte)
        }
        guard payloadLength >= 2,
              payloadLength <= UInt32(maximumJSONBytes),
              Int(payloadLength) == bundle.count - headerLength,
              bundle[headerLength] == UInt8(ascii: "{"),
              bundle.last == UInt8(ascii: "}") else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS name import bundle length")
        }
        var payload = Data(bundle[headerLength...])
        defer {
            let bytes = payload.startIndex..<payload.endIndex
            payload.resetBytes(in: bytes)
        }
        return try JSONDecoder().decode(
            NativeHnsReadSnapshot.KnownName.self,
            from: payload
        )
    }
}

/// Owns a single native HNS value-action capability.  It is intentionally not
/// representable as a `String` outside this file: a value action must either
/// be consumed once by approve/reject or be wiped when its review UI leaves.
final class NativeHnsSendActionToken: @unchecked Sendable {
    private let stateLock = NSLock()
    private var bytes: [UInt8]
    private var consumed = false

    init?(takingASCII candidate: inout [UInt8]) {
        defer { WalletSecretBytes.wipe(&candidate) }
        guard candidate.count == 64,
              candidate.allSatisfy({
                  (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                      (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
              }),
              candidate.contains(where: { $0 != UInt8(ascii: "0") }) else {
            return nil
        }
        bytes = candidate
        candidate.removeAll(keepingCapacity: false)
    }

    func consume<T>(_ body: (inout [UInt8]) throws -> T) throws -> T {
        stateLock.lock()
        guard !consumed else {
            stateLock.unlock()
            throw NativeWalletBridgeError.invalidOutput("HNS action token was already consumed")
        }
        consumed = true
        var retained = bytes
        WalletSecretBytes.wipe(&bytes)
        stateLock.unlock()
        defer { WalletSecretBytes.wipe(&retained) }
        return try body(&retained)
    }

    func discard() {
        stateLock.lock()
        consumed = true
        WalletSecretBytes.wipe(&bytes)
        stateLock.unlock()
    }

    deinit { discard() }
}

/// Exact native summary that must be displayed before an HNS payment can be
/// approved.  The app verifies the requested fields a second time before it
/// presents this structure to the user.
struct NativeHnsSendApproval {
    let actionToken: NativeHnsSendActionToken
    let expiresAtUnix: UInt64
    let amountBaseUnits: String
    let recipient: String
    let maximumFeeBaseUnits: String
    let finality: String
    let warnings: [String]
}

struct NativeHnsSendReceipt: Equatable, Sendable {
    let txid: String
    let acceptedAtUnix: UInt64
}

/// Every non-send HNS value action deliberately exposed by the native mobile
/// wallet. This is a closed mirror of the published Rust intent enum, not a
/// website-provider schema. The encoded JSON is consumed and wiped at the C
/// boundary before Rust parses it again.
enum NativeHnsValueIntent: Sendable {
    case transferName(name: String, recipient: String, maximumFeeBaseUnits: String)
    case finalizeName(name: String, expectedRecipient: String?, maximumFeeBaseUnits: String)
    case setNameRecords(name: String, records: String, maximumFeeBaseUnits: String)
    case createFixedPriceOffer(
        name: String,
        priceBaseUnits: String,
        maximumFeeBaseUnits: String,
        listingLifetimeSeconds: UInt64
    )
    case cancelOffer(sellerSessionID: String)
    case acceptOffer(listingID: String, maximumFeeBaseUnits: String)
    case finalizePurchase(sessionID: String, maximumFeeBaseUnits: String)
    case recoverName(sellerSessionID: String, maximumFeeBaseUnits: String)

    enum ApprovalKind: Equatable, Sendable {
        case nameTransfer
        case nameFinalize
        case nameUpdate
        case nameMarketOffer
        case nameMarketPurchase
    }

    var expectedApprovalKind: ApprovalKind {
        switch self {
        case .transferName:
            return .nameTransfer
        case .finalizeName:
            return .nameFinalize
        case .setNameRecords:
            return .nameUpdate
        case .createFixedPriceOffer, .cancelOffer, .recoverName:
            return .nameMarketOffer
        case .acceptOffer, .finalizePurchase:
            return .nameMarketPurchase
        }
    }

    var requiresShakedex: Bool {
        switch self {
        case .transferName, .finalizeName, .setNameRecords:
            return false
        case .createFixedPriceOffer, .cancelOffer, .acceptOffer,
             .finalizePurchase, .recoverName:
            return true
        }
    }

    func encodedBytes() throws -> [UInt8] {
        let object: [String: Any]
        switch self {
        case let .transferName(name, recipient, maximumFee):
            guard Self.isPublicText(name, maximum: 63),
                  Self.isPublicText(recipient, maximum: 512),
                  Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS name-transfer input")
            }
            object = [
                "action": "transferName",
                "name": name,
                "recipient": recipient,
                "maximumFee": maximumFee,
            ]
        case let .finalizeName(name, expectedRecipient, maximumFee):
            guard Self.isPublicText(name, maximum: 63),
                  expectedRecipient.map({ Self.isPublicText($0, maximum: 512) }) ?? true,
                  Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS name-finalize input")
            }
            object = [
                "action": "finalizeName",
                "name": name,
                "expectedRecipient": expectedRecipient.map { $0 as Any } ?? NSNull(),
                "maximumFee": maximumFee,
            ]
        case let .setNameRecords(name, records, maximumFee):
            guard Self.isPublicText(name, maximum: 63),
                  records.utf8.count <= 4_096,
                  Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS name-record input")
            }
            object = [
                "action": "setNameRecords",
                "name": name,
                "records": records,
                "maximumFee": maximumFee,
            ]
        case let .createFixedPriceOffer(name, price, maximumFee, lifetime):
            guard Self.isPublicText(name, maximum: 63),
                  Self.isPositiveBaseUnits(price),
                  Self.isPositiveBaseUnits(maximumFee),
                  (600...2_592_000).contains(lifetime) else {
                throw NativeWalletBridgeError.invalidOutput("invalid fixed-price name offer input")
            }
            object = [
                "action": "createFixedPriceOffer",
                "name": name,
                "price": price,
                "maximumFee": maximumFee,
                "listingLifetimeSeconds": lifetime,
            ]
        case let .cancelOffer(sellerSessionID):
            guard Self.isObjectID(sellerSessionID) else {
                throw NativeWalletBridgeError.invalidOutput("invalid name-offer seller session")
            }
            object = ["action": "cancelOffer", "sellerSessionId": sellerSessionID]
        case let .acceptOffer(listingID, maximumFee):
            guard Self.isObjectID(listingID), Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid name-offer acceptance input")
            }
            object = [
                "action": "acceptOffer",
                "listingId": listingID,
                "maximumFee": maximumFee,
            ]
        case let .finalizePurchase(sessionID, maximumFee):
            guard Self.isObjectID(sessionID), Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid name-purchase finalization input")
            }
            object = [
                "action": "finalizePurchase",
                "sessionId": sessionID,
                "maximumFee": maximumFee,
            ]
        case let .recoverName(sellerSessionID, maximumFee):
            guard Self.isObjectID(sellerSessionID), Self.isPositiveBaseUnits(maximumFee) else {
                throw NativeWalletBridgeError.invalidOutput("invalid name-offer recovery input")
            }
            object = [
                "action": "recoverName",
                "sellerSessionId": sellerSessionID,
                "maximumFee": maximumFee,
            ]
        }
        var data = try JSONSerialization.data(withJSONObject: object, options: [])
        defer { data.resetBytes(in: data.startIndex..<data.endIndex) }
        guard (2...8_192).contains(data.count) else {
            throw NativeWalletBridgeError.invalidOutput("HNS value intent exceeds native input bound")
        }
        return [UInt8](data)
    }

    private static func isPublicText(_ value: String, maximum: Int) -> Bool {
        let bytes = Array(value.utf8)
        return (1...maximum).contains(bytes.count) &&
            bytes.allSatisfy({ (0x21...0x7e).contains($0) })
    }

    private static func isPositiveBaseUnits(_ value: String) -> Bool {
        NativeHnsReadSnapshot.Amount.isCanonicalBaseUnits(value) && value != "0"
    }

    fileprivate static func isObjectID(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        return bytes.count == 64 &&
            bytes.contains(where: { $0 != UInt8(ascii: "0") }) &&
            bytes.allSatisfy({
                (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                    (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
            })
    }
}

enum NativeShakedexQuery: Sendable {
    case listOffers(cursor: String?, limit: UInt8)
    case getSession(sessionID: String)

    func encodedBytes() throws -> [UInt8] {
        let object: [String: Any]
        switch self {
        case let .listOffers(cursor, limit):
            guard (1...64).contains(limit),
                  cursor.map(NativeHnsValueIntent.isObjectID) ?? true else {
                throw NativeWalletBridgeError.invalidOutput("invalid Shakedex list query")
            }
            object = [
                "query": "listOffers",
                "cursor": cursor.map { $0 as Any } ?? NSNull(),
                "limit": Int(limit),
            ]
        case let .getSession(sessionID):
            guard NativeHnsValueIntent.isObjectID(sessionID) else {
                throw NativeWalletBridgeError.invalidOutput("invalid Shakedex session query")
            }
            object = ["query": "getSession", "sessionId": sessionID]
        }
        var data = try JSONSerialization.data(withJSONObject: object, options: [])
        defer { data.resetBytes(in: data.startIndex..<data.endIndex) }
        guard (2...4_096).contains(data.count) else {
            throw NativeWalletBridgeError.invalidOutput("Shakedex query exceeds native input bound")
        }
        return [UInt8](data)
    }
}

/// Strict, locally rendered non-send value-action review. The raw Rust
/// summary is never passed through as an opaque prompt: every supported kind
/// has an exact key set, bounded fields, and a locally selected title/rows.
struct NativeHnsValueApproval {
    let actionToken: NativeHnsSendActionToken
    let expiresAtUnix: UInt64
    let kind: NativeHnsValueIntent.ApprovalKind
    let title: String
    let detailLines: [String]
}

struct NativeHnsValueResult: Sendable {
    let displayJSON: String
}

struct NativeShakedexQueryResult: Sendable {
    let displayJSON: String
}

struct NativeDirectShakescapeStatus: Equatable, Sendable {
    let unlocked: Bool
    let listenerPort: UInt16?
    let peerEndpoint: String?
}

struct NativeDirectShakescapeConnectResult: Equatable, Sendable {
    enum Outcome: UInt8, Equatable, Sendable {
        case connected = 1
        case replaced = 2
        case unavailable = 3
        case locked = 4
        case connectionFailed = 5
        case exchangeFailed = 6
    }

    let outcome: Outcome
    let peerEndpoint: String?
}

enum NativeDirectShakescapeBundle {
    private static let headerBytes = 12
    private static let maximumEndpointBytes = 128

    static func status(_ bundle: [UInt8]) throws -> NativeDirectShakescapeStatus {
        let endpoint = try validated(bundle, magic: Array("HNDS".utf8), lengthOffset: 10)
        let flags = bundle[5]
        guard flags & ~UInt8(0b111) == 0 else { throw invalid() }
        let unlocked = flags & 1 != 0
        let listening = flags & 2 != 0
        let paired = flags & 4 != 0
        let port = UInt16(bundle[8]) << 8 | UInt16(bundle[9])
        guard unlocked || (!listening && !paired),
              (port != 0) == listening,
              (!endpoint.isEmpty) == paired else { throw invalid() }
        return NativeDirectShakescapeStatus(
            unlocked: unlocked,
            listenerPort: listening ? port : nil,
            peerEndpoint: paired ? endpoint : nil
        )
    }

    static func connect(_ bundle: [UInt8]) throws -> NativeDirectShakescapeConnectResult {
        let endpoint = try validated(bundle, magic: Array("HNDC".utf8), lengthOffset: 8)
        guard let outcome = NativeDirectShakescapeConnectResult.Outcome(rawValue: bundle[5]),
              (bundle[10] == 0 && bundle[11] == 0) else { throw invalid() }
        let success = outcome == .connected || outcome == .replaced
        guard success == !endpoint.isEmpty else { throw invalid() }
        return NativeDirectShakescapeConnectResult(
            outcome: outcome,
            peerEndpoint: success ? endpoint : nil
        )
    }

    private static func validated(
        _ bundle: [UInt8],
        magic: [UInt8],
        lengthOffset: Int
    ) throws -> String {
        guard bundle.count >= headerBytes,
              bundle.count <= headerBytes + maximumEndpointBytes,
              Array(bundle[0..<4]) == magic,
              bundle[4] == 1,
              bundle[6] == 0,
              bundle[7] == 0 else { throw invalid() }
        let length = Int(UInt16(bundle[lengthOffset]) << 8 | UInt16(bundle[lengthOffset + 1]))
        guard bundle.count == headerBytes + length else { throw invalid() }
        let bytes = Array(bundle[headerBytes...])
        guard let endpoint = String(bytes: bytes, encoding: .utf8),
              endpoint.utf8.elementsEqual(bytes),
              endpoint.utf8.allSatisfy({ (0x21...0x7e).contains($0) }) else { throw invalid() }
        return endpoint
    }

    private static func invalid() -> NativeWalletBridgeError {
        .invalidOutput("invalid direct Shakescape transport bundle")
    }
}

private enum NativeHnsValueBundle {
    private static let headerLength = 12

    static func payload(
        _ bundle: [UInt8],
        magic: [UInt8],
        maximumJSONBytes: Int
    ) throws -> Data {
        guard bundle.count >= headerLength,
              bundle.count <= headerLength + maximumJSONBytes,
              Array(bundle[0..<4]) == magic,
              bundle[4] == 1,
              bundle[5] == 0,
              bundle[6] == 0,
              bundle[7] == 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid native HNS value bundle header")
        }
        let payloadLength = bundle[8..<12].reduce(UInt32(0)) { partial, byte in
            (partial << 8) | UInt32(byte)
        }
        guard payloadLength >= 2,
              payloadLength <= UInt32(maximumJSONBytes),
              Int(payloadLength) == bundle.count - headerLength,
              bundle[headerLength] == UInt8(ascii: "{"),
              bundle.last == UInt8(ascii: "}") else {
            throw NativeWalletBridgeError.invalidOutput("invalid native HNS value bundle length")
        }
        return Data(bundle[headerLength...])
    }
}

private struct NativeBitcoinSendApprovalPayload: Decodable {
    let actionToken: String
    let destination: String
    let amountSats: UInt64
    let feeSats: UInt64
    let maximumFeeSats: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, destination, amountSats, feeSats, maximumFeeSats, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        destination = try container.decode(String.self, forKey: .destination)
        amountSats = try container.decode(UInt64.self, forKey: .amountSats)
        feeSats = try container.decode(UInt64.self, forKey: .feeSats)
        maximumFeeSats = try container.decode(UInt64.self, forKey: .maximumFeeSats)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        guard NativeBitcoinWalletSnapshot.validAddress(destination),
              amountSats > 0, feeSats > 0, maximumFeeSats > 0,
              feeSats <= maximumFeeSats, expiresAtUnix > 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin send approval")
        }
    }
}

private struct NativeBitcoinHtlcFundingApprovalPayload: Decodable {
    let actionToken: String
    let sessionId: String
    let txid: String
    let amountSats: UInt64
    let feeSats: UInt64
    let maximumFeeSats: UInt64
    let refundAtUnix: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, sessionId, txid, amountSats, feeSats, maximumFeeSats
        case refundAtUnix, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        txid = try container.decode(String.self, forKey: .txid)
        amountSats = try container.decode(UInt64.self, forKey: .amountSats)
        feeSats = try container.decode(UInt64.self, forKey: .feeSats)
        maximumFeeSats = try container.decode(UInt64.self, forKey: .maximumFeeSats)
        refundAtUnix = try container.decode(UInt64.self, forKey: .refundAtUnix)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        guard NativeBitcoinHtlcFundingReceipt.validHash(sessionId),
              NativeBitcoinHtlcFundingReceipt.validHash(txid), amountSats > 0, feeSats > 0,
              maximumFeeSats > 0, feeSats <= maximumFeeSats, expiresAtUnix > 0,
              refundAtUnix > expiresAtUnix else {
            throw NativeWalletBridgeError.invalidOutput("invalid Bitcoin HTLC funding approval")
        }
    }
}

private extension NativeBitcoinHtlcFundingApproval {
    static func decode(bundle: [UInt8]) throws -> Self {
        var payload = try NativeHnsValueBundle.payload(
            bundle, magic: Array("HNBW".utf8), maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(
            NativeBitcoinHtlcFundingApprovalPayload.self, from: payload
        )
        var token = Array(decoded.actionToken.utf8)
        guard let actionToken = NativeHnsSendActionToken(takingASCII: &token) else {
            throw NativeWalletBridgeError.invalidOutput("invalid Bitcoin HTLC action token")
        }
        return Self(
            actionToken: actionToken, sessionId: decoded.sessionId, txid: decoded.txid,
            amountSats: decoded.amountSats, feeSats: decoded.feeSats,
            maximumFeeSats: decoded.maximumFeeSats, refundAtUnix: decoded.refundAtUnix,
            expiresAtUnix: decoded.expiresAtUnix
        )
    }
}

private struct NativeHnsHtlcFundingApprovalPayload: Decodable {
    let actionToken: String
    let sessionId: String
    let transactionId: String
    let amountDollarydoos: UInt64
    let feeDollarydoos: UInt64
    let maximumFeeDollarydoos: UInt64
    let refundAtUnix: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, sessionId, transactionId, amountDollarydoos, feeDollarydoos
        case maximumFeeDollarydoos, refundAtUnix, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        transactionId = try container.decode(String.self, forKey: .transactionId)
        amountDollarydoos = try container.decode(UInt64.self, forKey: .amountDollarydoos)
        feeDollarydoos = try container.decode(UInt64.self, forKey: .feeDollarydoos)
        maximumFeeDollarydoos = try container.decode(UInt64.self, forKey: .maximumFeeDollarydoos)
        refundAtUnix = try container.decode(UInt64.self, forKey: .refundAtUnix)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        guard NativeBitcoinHtlcFundingReceipt.validHash(sessionId),
              NativeBitcoinHtlcFundingReceipt.validHash(transactionId),
              amountDollarydoos > 0, feeDollarydoos > 0, maximumFeeDollarydoos > 0,
              feeDollarydoos <= maximumFeeDollarydoos, expiresAtUnix > 0,
              refundAtUnix > expiresAtUnix else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS HTLC funding approval")
        }
    }
}

private extension NativeHnsHtlcFundingApproval {
    static func decode(bundle: [UInt8]) throws -> Self {
        var payload = try NativeHnsValueBundle.payload(
            bundle, magic: Array("HNBW".utf8), maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(
            NativeHnsHtlcFundingApprovalPayload.self, from: payload
        )
        var token = Array(decoded.actionToken.utf8)
        guard let actionToken = NativeHnsSendActionToken(takingASCII: &token) else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS HTLC action token")
        }
        return Self(
            actionToken: actionToken, sessionId: decoded.sessionId,
            transactionId: decoded.transactionId, amountDollarydoos: decoded.amountDollarydoos,
            feeDollarydoos: decoded.feeDollarydoos,
            maximumFeeDollarydoos: decoded.maximumFeeDollarydoos,
            refundAtUnix: decoded.refundAtUnix, expiresAtUnix: decoded.expiresAtUnix
        )
    }
}

private struct NativeBitcoinSwapSettlementApprovalPayload: Decodable {
    let actionToken: String
    let sessionId: String
    let action: NativeSwapSettlementAction
    let txid: String
    let inputAmountSats: UInt64
    let outputAmountSats: UInt64
    let feeSats: UInt64
    let maximumFeeSats: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, sessionId, action, txid, inputAmountSats, outputAmountSats
        case feeSats, maximumFeeSats, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let value = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try value.decode(String.self, forKey: .actionToken)
        sessionId = try value.decode(String.self, forKey: .sessionId)
        action = try value.decode(NativeSwapSettlementAction.self, forKey: .action)
        txid = try value.decode(String.self, forKey: .txid)
        inputAmountSats = try value.decode(UInt64.self, forKey: .inputAmountSats)
        outputAmountSats = try value.decode(UInt64.self, forKey: .outputAmountSats)
        feeSats = try value.decode(UInt64.self, forKey: .feeSats)
        maximumFeeSats = try value.decode(UInt64.self, forKey: .maximumFeeSats)
        expiresAtUnix = try value.decode(UInt64.self, forKey: .expiresAtUnix)
    }
}

private struct NativeHnsSwapSettlementApprovalPayload: Decodable {
    let actionToken: String
    let sessionId: String
    let action: NativeSwapSettlementAction
    let transactionId: String
    let inputAmountDollarydoos: UInt64
    let outputAmountDollarydoos: UInt64
    let feeDollarydoos: UInt64
    let maximumFeeDollarydoos: UInt64
    let expiresAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, sessionId, action, transactionId, inputAmountDollarydoos
        case outputAmountDollarydoos, feeDollarydoos, maximumFeeDollarydoos, expiresAtUnix
    }

    init(from decoder: Decoder) throws {
        let value = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try value.decode(String.self, forKey: .actionToken)
        sessionId = try value.decode(String.self, forKey: .sessionId)
        action = try value.decode(NativeSwapSettlementAction.self, forKey: .action)
        transactionId = try value.decode(String.self, forKey: .transactionId)
        inputAmountDollarydoos = try value.decode(UInt64.self, forKey: .inputAmountDollarydoos)
        outputAmountDollarydoos = try value.decode(UInt64.self, forKey: .outputAmountDollarydoos)
        feeDollarydoos = try value.decode(UInt64.self, forKey: .feeDollarydoos)
        maximumFeeDollarydoos = try value.decode(UInt64.self, forKey: .maximumFeeDollarydoos)
        expiresAtUnix = try value.decode(UInt64.self, forKey: .expiresAtUnix)
    }
}

extension NativeSwapSettlementApproval {
    static func decode(bundle: [UInt8], bitcoin: Bool) throws -> Self {
        var payload = try NativeHnsValueBundle.payload(
            bundle, magic: Array("HNBW".utf8), maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let tokenString: String
        let sessionId: String
        let action: NativeSwapSettlementAction
        let transactionId: String
        let inputAmount: UInt64
        let outputAmount: UInt64
        let fee: UInt64
        let maximumFee: UInt64
        let expiresAtUnix: UInt64
        if bitcoin {
            let value = try JSONDecoder().decode(
                NativeBitcoinSwapSettlementApprovalPayload.self, from: payload
            )
            (tokenString, sessionId, action, transactionId) = (
                value.actionToken, value.sessionId, value.action, value.txid
            )
            (inputAmount, outputAmount, fee, maximumFee, expiresAtUnix) = (
                value.inputAmountSats, value.outputAmountSats, value.feeSats,
                value.maximumFeeSats, value.expiresAtUnix
            )
        } else {
            let value = try JSONDecoder().decode(
                NativeHnsSwapSettlementApprovalPayload.self, from: payload
            )
            (tokenString, sessionId, action, transactionId) = (
                value.actionToken, value.sessionId, value.action, value.transactionId
            )
            (inputAmount, outputAmount, fee, maximumFee, expiresAtUnix) = (
                value.inputAmountDollarydoos, value.outputAmountDollarydoos,
                value.feeDollarydoos, value.maximumFeeDollarydoos, value.expiresAtUnix
            )
        }
        guard NativeBitcoinHtlcFundingReceipt.validHash(sessionId),
              NativeBitcoinHtlcFundingReceipt.validHash(transactionId), inputAmount > 0,
              outputAmount > 0, fee > 0, fee <= maximumFee, fee < inputAmount,
              inputAmount - fee == outputAmount, expiresAtUnix > 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid swap settlement approval")
        }
        var token = Array(tokenString.utf8)
        guard let actionToken = NativeHnsSendActionToken(takingASCII: &token) else {
            throw NativeWalletBridgeError.invalidOutput("invalid swap settlement action token")
        }
        return Self(
            actionToken: actionToken, sessionId: sessionId, action: action,
            transactionId: transactionId, inputAmount: inputAmount,
            outputAmount: outputAmount, fee: fee, maximumFee: maximumFee,
            expiresAtUnix: expiresAtUnix
        )
    }
}

private struct NativeBitcoinSwapSettlementReceiptPayload: Decodable {
    let sessionId: String
    let action: NativeSwapSettlementAction
    let txid: String
    let attemptCount: UInt8
    let submittedAtUnix: UInt64?

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case sessionId, action, txid, attemptCount, submittedAtUnix
    }

    init(from decoder: Decoder) throws {
        let value = try decoder.strictContainer(keyedBy: CodingKeys.self)
        sessionId = try value.decode(String.self, forKey: .sessionId)
        action = try value.decode(NativeSwapSettlementAction.self, forKey: .action)
        txid = try value.decode(String.self, forKey: .txid)
        attemptCount = try value.decode(UInt8.self, forKey: .attemptCount)
        submittedAtUnix = try value.decodeIfPresent(UInt64.self, forKey: .submittedAtUnix)
    }
}

private struct NativeHnsSwapSettlementReceiptPayload: Decodable {
    let sessionId: String
    let action: NativeSwapSettlementAction
    let transactionId: String
    let acceptedAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case sessionId, action, transactionId, acceptedAtUnix
    }

    init(from decoder: Decoder) throws {
        let value = try decoder.strictContainer(keyedBy: CodingKeys.self)
        sessionId = try value.decode(String.self, forKey: .sessionId)
        action = try value.decode(NativeSwapSettlementAction.self, forKey: .action)
        transactionId = try value.decode(String.self, forKey: .transactionId)
        acceptedAtUnix = try value.decode(UInt64.self, forKey: .acceptedAtUnix)
    }
}

extension NativeSwapSettlementReceipt {
    static func decode(bundle: [UInt8], bitcoin: Bool) throws -> Self {
        var payload = try NativeHnsValueBundle.payload(
            bundle, magic: Array("HNBW".utf8), maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        if bitcoin {
            let value = try JSONDecoder().decode(
                NativeBitcoinSwapSettlementReceiptPayload.self, from: payload
            )
            guard NativeBitcoinHtlcFundingReceipt.validHash(value.sessionId),
                  NativeBitcoinHtlcFundingReceipt.validHash(value.txid),
                  (1...16).contains(value.attemptCount),
                  value.submittedAtUnix.map { $0 > 0 } ?? true else {
                throw NativeWalletBridgeError.invalidOutput("invalid Bitcoin settlement receipt")
            }
            return Self(
                sessionId: value.sessionId, action: value.action, transactionId: value.txid,
                acceptedAtUnix: nil, attemptCount: value.attemptCount,
                submittedAtUnix: value.submittedAtUnix
            )
        }
        let value = try JSONDecoder().decode(
            NativeHnsSwapSettlementReceiptPayload.self, from: payload
        )
        guard NativeBitcoinHtlcFundingReceipt.validHash(value.sessionId),
              NativeBitcoinHtlcFundingReceipt.validHash(value.transactionId),
              value.acceptedAtUnix > 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS settlement receipt")
        }
        return Self(
            sessionId: value.sessionId, action: value.action,
            transactionId: value.transactionId, acceptedAtUnix: value.acceptedAtUnix,
            attemptCount: nil, submittedAtUnix: nil
        )
    }
}

private struct NativeBtcForHnsOfferApprovalPayload: Decodable {
    let actionToken: String
    let btcAmountSats: UInt64
    let hnsAmountDollarydoos: UInt64
    let bitcoinFeeReserveSats: UInt64
    let totalBitcoinCommitmentSats: UInt64
    let offerExpiresAtUnix: UInt64
    let approvalExpiresAtUnix: UInt64
    let connectedPeerRequiredForAnnouncement: Bool

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, btcAmountSats, hnsAmountDollarydoos, bitcoinFeeReserveSats
        case totalBitcoinCommitmentSats, offerExpiresAtUnix, approvalExpiresAtUnix
        case connectedPeerRequiredForAnnouncement
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        btcAmountSats = try container.decode(UInt64.self, forKey: .btcAmountSats)
        hnsAmountDollarydoos = try container.decode(UInt64.self, forKey: .hnsAmountDollarydoos)
        bitcoinFeeReserveSats = try container.decode(UInt64.self, forKey: .bitcoinFeeReserveSats)
        totalBitcoinCommitmentSats = try container.decode(UInt64.self, forKey: .totalBitcoinCommitmentSats)
        offerExpiresAtUnix = try container.decode(UInt64.self, forKey: .offerExpiresAtUnix)
        approvalExpiresAtUnix = try container.decode(UInt64.self, forKey: .approvalExpiresAtUnix)
        connectedPeerRequiredForAnnouncement = try container.decode(
            Bool.self, forKey: .connectedPeerRequiredForAnnouncement
        )
        guard btcAmountSats > 0, hnsAmountDollarydoos > 0, bitcoinFeeReserveSats > 0,
              btcAmountSats <= UInt64.max - bitcoinFeeReserveSats,
              btcAmountSats + bitcoinFeeReserveSats == totalBitcoinCommitmentSats,
              offerExpiresAtUnix > 0, approvalExpiresAtUnix > 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid BTC-for-HNS offer approval")
        }
    }
}

private extension NativeBtcForHnsOfferApproval {
    static func decode(bundle: [UInt8]) throws -> Self {
        var payload = try NativeHnsValueBundle.payload(
            bundle, magic: Array("HNBW".utf8), maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(NativeBtcForHnsOfferApprovalPayload.self, from: payload)
        var token = Array(decoded.actionToken.utf8)
        guard let actionToken = NativeHnsSendActionToken(takingASCII: &token) else {
            throw NativeWalletBridgeError.invalidOutput("invalid BTC-for-HNS action token")
        }
        return Self(
            actionToken: actionToken,
            btcAmountSats: decoded.btcAmountSats,
            hnsAmountDollarydoos: decoded.hnsAmountDollarydoos,
            bitcoinFeeReserveSats: decoded.bitcoinFeeReserveSats,
            totalBitcoinCommitmentSats: decoded.totalBitcoinCommitmentSats,
            offerExpiresAtUnix: decoded.offerExpiresAtUnix,
            approvalExpiresAtUnix: decoded.approvalExpiresAtUnix,
            connectedPeerRequiredForAnnouncement: decoded.connectedPeerRequiredForAnnouncement
        )
    }
}

private extension NativeBitcoinSendApproval {
    static func decode(bundle: [UInt8]) throws -> NativeBitcoinSendApproval {
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNBW".utf8),
            maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(NativeBitcoinSendApprovalPayload.self, from: payload)
        var token = Array(decoded.actionToken.utf8)
        guard let actionToken = NativeHnsSendActionToken(takingASCII: &token) else {
            throw NativeWalletBridgeError.invalidOutput("invalid direct Bitcoin action token")
        }
        return NativeBitcoinSendApproval(
            actionToken: actionToken,
            destination: decoded.destination,
            amountSats: decoded.amountSats,
            feeSats: decoded.feeSats,
            maximumFeeSats: decoded.maximumFeeSats,
            expiresAtUnix: decoded.expiresAtUnix
        )
    }
}

private struct NativeHnsSendApprovalPayload: Decodable {
    let actionToken: String
    let expiresAtUnix: UInt64
    let summary: Summary

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, expiresAtUnix, summary
    }

    struct Summary: Decodable {
        let kind: String
        let amount: NativeHnsReadSnapshot.Amount
        let recipient: String
        let maximumFee: NativeHnsReadSnapshot.Amount
        let chain: String
        let finality: String
        let warnings: [String]

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case kind, amount, recipient, maximumFee, chain, finality, warnings
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            kind = try container.decode(String.self, forKey: .kind)
            amount = try container.decode(NativeHnsReadSnapshot.Amount.self, forKey: .amount)
            recipient = try container.decode(String.self, forKey: .recipient)
            maximumFee = try container.decode(
                NativeHnsReadSnapshot.Amount.self,
                forKey: .maximumFee
            )
            chain = try container.decode(String.self, forKey: .chain)
            finality = try container.decode(String.self, forKey: .finality)
            warnings = try container.decode([String].self, forKey: .warnings)
            guard kind == "send",
                  chain == "handshake",
                  recipient.utf8.count <= 512,
                  !recipient.isEmpty,
                  recipient.utf8.allSatisfy({ (0x21...0x7e).contains($0) }),
                  finality == "proof_of_work_confirmations",
                  warnings == ["feeEstimateMayChange"] else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS send approval summary")
            }
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        summary = try container.decode(Summary.self, forKey: .summary)
    }
}

private struct NativeHnsSendReceiptPayload: Decodable {
    let module: String
    let txid: String
    let acceptedAtUnix: UInt64

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case module, txid, acceptedAtUnix
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        module = try container.decode(String.self, forKey: .module)
        txid = try container.decode(String.self, forKey: .txid)
        acceptedAtUnix = try container.decode(UInt64.self, forKey: .acceptedAtUnix)
        guard module == "handshake",
              txid.utf8.count == 64,
              txid.utf8.allSatisfy({
                  (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                      (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
              }),
              txid.utf8.contains(where: { $0 != UInt8(ascii: "0") }) else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS send receipt")
        }
    }
}

private extension NativeHnsSendApproval {
    static func decode(bundle: [UInt8]) throws -> NativeHnsSendApproval {
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNVP".utf8),
            maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(NativeHnsSendApprovalPayload.self, from: payload)
        var tokenBytes = Array(decoded.actionToken.utf8)
        guard let token = NativeHnsSendActionToken(takingASCII: &tokenBytes) else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS send action token")
        }
        return NativeHnsSendApproval(
            actionToken: token,
            expiresAtUnix: decoded.expiresAtUnix,
            amountBaseUnits: decoded.summary.amount.baseUnits,
            recipient: decoded.summary.recipient,
            maximumFeeBaseUnits: decoded.summary.maximumFee.baseUnits,
            finality: decoded.summary.finality,
            warnings: decoded.summary.warnings
        )
    }
}

private extension NativeHnsSendReceipt {
    static func decode(bundle: [UInt8]) throws -> NativeHnsSendReceipt {
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNVX".utf8),
            maximumJSONBytes: 256 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(NativeHnsSendReceiptPayload.self, from: payload)
        return NativeHnsSendReceipt(txid: decoded.txid, acceptedAtUnix: decoded.acceptedAtUnix)
    }
}

private struct NativeHnsValueApprovalPayload: Decodable {
    let actionToken: String
    let expiresAtUnix: UInt64
    let summary: Summary

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case actionToken, expiresAtUnix, summary
    }

    struct Summary: Decodable {
        let kind: NativeHnsValueIntent.ApprovalKind
        let title: String
        let detailLines: [String]

        private enum KindKey: String, CodingKey, CaseIterable {
            case kind
        }

        private enum NameKeys: String, CodingKey, CaseIterable {
            case kind, name, recipient, maximumFee, warnings
        }

        private enum UpdateKeys: String, CodingKey, CaseIterable {
            case kind, name, resourceHex, resourceBytes, recordCount, maximumFee, warnings
        }

        private enum OfferKeys: String, CodingKey, CaseIterable {
            case kind, action, name, listingId, price, maximumFee, warnings
        }

        private enum PurchaseKeys: String, CodingKey, CaseIterable {
            case kind, name, listingId, payment, recipient, maximumFee, warnings
        }

        init(from decoder: Decoder) throws {
            let kind = try decoder.container(keyedBy: KindKey.self)
                .decode(String.self, forKey: .kind)
            switch kind {
            case "nameTransfer", "nameFinalize":
                let container = try decoder.strictContainer(keyedBy: NameKeys.self)
                let name = try container.decode(String.self, forKey: .name)
                let recipient = try container.decode(String.self, forKey: .recipient)
                let maximumFee = try container.decode(
                    NativeHnsReadSnapshot.Amount.self,
                    forKey: .maximumFee
                )
                let warnings = try container.decode([String].self, forKey: .warnings)
                let expectedWarnings = kind == "nameTransfer"
                    ? ["feeEstimateMayChange", "nameTransferIsIrreversible"]
                    : ["feeEstimateMayChange"]
                guard Self.isPublicText(name, maximum: 63),
                      Self.isPublicText(recipient, maximum: 512),
                      maximumFee.baseUnits != "0",
                      warnings == expectedWarnings else {
                    throw NativeWalletBridgeError.invalidOutput("invalid HNS name action summary")
                }
                self.kind = kind == "nameTransfer" ? .nameTransfer : .nameFinalize
                title = kind == "nameTransfer"
                    ? "Transfer Handshake name"
                    : "Finalize name transfer"
                detailLines = [
                    "Name: \(name)",
                    "Recipient: \(recipient)",
                    "Maximum fee: \(Self.formatHnsBaseUnits(maximumFee.baseUnits)) HNS",
                ] + warnings.map(Self.warningText)
            case "nameUpdate":
                let container = try decoder.strictContainer(keyedBy: UpdateKeys.self)
                let name = try container.decode(String.self, forKey: .name)
                let resourceHex = try container.decode(String.self, forKey: .resourceHex)
                let resourceBytes = try container.decode(UInt16.self, forKey: .resourceBytes)
                let recordCount = try container.decode(UInt16.self, forKey: .recordCount)
                let maximumFee = try container.decode(
                    NativeHnsReadSnapshot.Amount.self,
                    forKey: .maximumFee
                )
                let warnings = try container.decode([String].self, forKey: .warnings)
                guard Self.isPublicText(name, maximum: 63),
                      resourceBytes <= 512,
                      resourceHex.utf8.count == Int(resourceBytes) * 2,
                      resourceHex.utf8.allSatisfy({ byte in
                          (0x30...0x39).contains(byte) || (0x61...0x66).contains(byte)
                      }),
                      maximumFee.baseUnits != "0",
                      warnings == ["feeEstimateMayChange"] else {
                    throw NativeWalletBridgeError.invalidOutput("invalid HNS name-update summary")
                }
                self.kind = .nameUpdate
                title = "Set Handshake resource records"
                detailLines = [
                    "Name: \(name)",
                    "Records: \(recordCount)",
                    "Resource size: \(resourceBytes) bytes",
                    "Exact resource hex: \(resourceHex.isEmpty ? "(empty; clear records)" : resourceHex)",
                    "Maximum fee: \(Self.formatHnsBaseUnits(maximumFee.baseUnits)) HNS",
                ] + warnings.map(Self.warningText)
            case "nameMarketOffer":
                let container = try decoder.strictContainer(keyedBy: OfferKeys.self)
                let action = try container.decode(String.self, forKey: .action)
                let name = try container.decode(String.self, forKey: .name)
                let listingID = try container.decodeIfPresent(String.self, forKey: .listingId)
                let price = try container.decode(NativeHnsReadSnapshot.Amount.self, forKey: .price)
                let maximumFee = try container.decode(
                    NativeHnsReadSnapshot.Amount.self,
                    forKey: .maximumFee
                )
                let warnings = try container.decode([String].self, forKey: .warnings)
                let expectedWarnings: [String]
                switch action {
                case "create":
                    expectedWarnings = [
                        "feeEstimateMayChange",
                        "nameTransferIsIrreversible",
                        "settlementCanBeDelayed",
                    ]
                case "recover":
                    expectedWarnings = [
                        "feeEstimateMayChange",
                        "refundRequiresManualAction",
                        "settlementCanBeDelayed",
                    ]
                case "cancel":
                    expectedWarnings = []
                default:
                    throw NativeWalletBridgeError.invalidOutput("unknown HNS name-offer action")
                }
                guard Self.isPublicText(name, maximum: 63),
                      listingID.map(NativeHnsValueIntent.isObjectID) ?? true,
                      price.baseUnits != "0",
                      (action == "cancel" || maximumFee.baseUnits != "0"),
                      warnings == expectedWarnings else {
                    throw NativeWalletBridgeError.invalidOutput("invalid HNS name-offer summary")
                }
                self.kind = .nameMarketOffer
                switch action {
                case "create":
                    title = "Create fixed-price name offer"
                case "cancel":
                    title = "Cancel name offer"
                case "recover":
                    title = "Recover name from offer"
                default:
                    throw NativeWalletBridgeError.invalidOutput("unknown HNS name-offer action")
                }
                var lines = [
                    "Name: \(name)",
                    "Price: \(Self.formatHnsBaseUnits(price.baseUnits)) HNS",
                ]
                if let listingID { lines.append("Listing: \(listingID)") }
                lines.append(
                    "Maximum fee: \(Self.formatHnsBaseUnits(maximumFee.baseUnits)) HNS"
                )
                detailLines = lines + warnings.map(Self.warningText)
            case "nameMarketPurchase":
                let container = try decoder.strictContainer(keyedBy: PurchaseKeys.self)
                let name = try container.decode(String.self, forKey: .name)
                let listingID = try container.decode(String.self, forKey: .listingId)
                let payment = try container.decode(NativeHnsReadSnapshot.Amount.self, forKey: .payment)
                let recipient = try container.decode(String.self, forKey: .recipient)
                let maximumFee = try container.decode(
                    NativeHnsReadSnapshot.Amount.self,
                    forKey: .maximumFee
                )
                let warnings = try container.decode([String].self, forKey: .warnings)
                guard Self.isPublicText(name, maximum: 63),
                      NativeHnsValueIntent.isObjectID(listingID),
                      payment.baseUnits != "0",
                      Self.isPublicText(recipient, maximum: 512),
                      maximumFee.baseUnits != "0",
                      warnings == ["feeEstimateMayChange", "settlementCanBeDelayed"] else {
                    throw NativeWalletBridgeError.invalidOutput("invalid HNS name-purchase summary")
                }
                self.kind = .nameMarketPurchase
                title = "Execute Shakedex purchase step"
                detailLines = [
                    "Name: \(name)",
                    "Listing/session: \(listingID)",
                    "Payment: \(Self.formatHnsBaseUnits(payment.baseUnits)) HNS",
                    "Recipient: \(recipient)",
                    "Maximum fee: \(Self.formatHnsBaseUnits(maximumFee.baseUnits)) HNS",
                ] + warnings.map(Self.warningText)
            default:
                throw NativeWalletBridgeError.invalidOutput("unsupported HNS value summary")
            }
        }

        private static func isPublicText(_ value: String, maximum: Int) -> Bool {
            let bytes = Array(value.utf8)
            return (1...maximum).contains(bytes.count) &&
                bytes.allSatisfy({ (0x21...0x7e).contains($0) })
        }

        /// Keep native approval decoding independent of the UIKit wallet
        /// presenter. The input has already passed the strict unsigned amount
        /// decoder, so this is a display-only fixed-six-decimal projection.
        private static func formatHnsBaseUnits(_ baseUnits: String) -> String {
            let decimalPlaces = 6
            let zeroes = max(0, decimalPlaces + 1 - baseUnits.count)
            let padded = String(repeating: "0", count: zeroes) + baseUnits
            let split = padded.index(padded.endIndex, offsetBy: -decimalPlaces)
            let whole = String(padded[..<split])
            var fraction = String(padded[split...])
            while fraction.last == "0" { fraction.removeLast() }
            return fraction.isEmpty ? whole : "\(whole).\(fraction)"
        }

        private static func warningText(_ warning: String) -> String {
            switch warning {
            case "feeEstimateMayChange":
                return "Warning: network fee may change before broadcast."
            case "nameTransferIsIrreversible":
                return "Warning: the name transfer is irreversible."
            case "refundRequiresManualAction":
                return "Warning: recovery requires an explicit manual action."
            case "settlementCanBeDelayed":
                return "Warning: Shakedex settlement can require later steps."
            default:
                return ""
            }
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        actionToken = try container.decode(String.self, forKey: .actionToken)
        expiresAtUnix = try container.decode(UInt64.self, forKey: .expiresAtUnix)
        summary = try container.decode(Summary.self, forKey: .summary)
    }
}

extension NativeHnsValueApproval {
    static func decode(bundle: [UInt8]) throws -> NativeHnsValueApproval {
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNVP".utf8),
            maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let decoded = try JSONDecoder().decode(NativeHnsValueApprovalPayload.self, from: payload)
        var tokenBytes = Array(decoded.actionToken.utf8)
        guard let token = NativeHnsSendActionToken(takingASCII: &tokenBytes) else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS value action token")
        }
        return NativeHnsValueApproval(
            actionToken: token,
            expiresAtUnix: decoded.expiresAtUnix,
            kind: decoded.summary.kind,
            title: decoded.summary.title,
            detailLines: decoded.summary.detailLines
        )
    }
}

extension NativeHnsValueResult {
    static func decode(bundle: [UInt8], magic: [UInt8]) throws -> NativeHnsValueResult {
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: magic,
            maximumJSONBytes: 256 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        let object = try JSONSerialization.jsonObject(with: payload, options: [])
        guard let dictionary = object as? [String: Any],
              !dictionary.isEmpty,
              Self.isBoundedJSONObject(dictionary, depth: 0) else {
            throw NativeWalletBridgeError.invalidOutput("invalid native HNS value result")
        }
        var display = try JSONSerialization.data(
            withJSONObject: dictionary,
            options: [.prettyPrinted, .sortedKeys]
        )
        defer { display.resetBytes(in: display.startIndex..<display.endIndex) }
        guard let text = String(data: display, encoding: .utf8),
              text.utf8.count <= 512 * 1_024 else {
            throw NativeWalletBridgeError.invalidOutput("native HNS value result is not displayable")
        }
        return NativeHnsValueResult(displayJSON: text)
    }

    private static func isBoundedJSONObject(_ value: Any, depth: Int) -> Bool {
        guard depth <= 8 else { return false }
        switch value {
        case let dictionary as [String: Any]:
            return dictionary.count <= 128 && dictionary.allSatisfy {
                $0.key.utf8.count <= 128 && isBoundedJSONObject($0.value, depth: depth + 1)
            }
        case let array as [Any]:
            return array.count <= 256 && array.allSatisfy { isBoundedJSONObject($0, depth: depth + 1) }
        case let string as String:
            return string.utf8.count <= 4_096
        case is NSNumber, is NSNull:
            return true
        default:
            return false
        }
    }
}

extension NativeShakedexQueryResult {
    static func decode(bundle: [UInt8]) throws -> NativeShakedexQueryResult {
        let result = try NativeHnsValueResult.decode(bundle: bundle, magic: Array("HNVQ".utf8))
        return NativeShakedexQueryResult(displayJSON: result.displayJSON)
    }
}

/** A successful import may publish only after HNWR-v2 confirms its exact identity. */
func walletNameImportRefreshMatches(
    imported: NativeHnsReadSnapshot.KnownName,
    refreshed: NativeHnsReadSnapshot
) -> Bool {
    refreshed.knownNames.contains { current in
        current.name.utf8.elementsEqual(imported.name.utf8) &&
            current.nameHash == imported.nameHash
    }
}

private struct NativeWalletAnyCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
        intValue = nil
    }

    init?(intValue: Int) {
        stringValue = String(intValue)
        self.intValue = intValue
    }
}

private extension Decoder {
    func strictContainer<Key: CodingKey & CaseIterable>(
        keyedBy type: Key.Type
    ) throws -> KeyedDecodingContainer<Key> where Key.AllCases: Collection {
        let expected = Set(type.allCases.map(\.stringValue))
        let dynamic = try container(keyedBy: NativeWalletAnyCodingKey.self)
        let actual = Set(dynamic.allKeys.map(\.stringValue))
        guard actual == expected else {
            throw NativeWalletBridgeError.invalidOutput("native wallet JSON fields are not exact")
        }
        return try container(keyedBy: type)
    }
}

/// Process-local copy used only by the dedicated recovery display. Call
/// `clear()` as soon as the user leaves the screen.
final class WalletRecoverySecret {
    private var bytes: [UInt8]

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    func displayText() throws -> String {
        guard let value = String(bytes: bytes, encoding: .utf8) else {
            throw NativeWalletBridgeError.invalidOutput("recovery phrase is not UTF-8")
        }
        return value
    }

    func withUnsafeBytes<T>(
        _ body: (UnsafeRawBufferPointer) throws -> T
    ) rethrows -> T {
        try bytes.withUnsafeBytes(body)
    }

    func clear() {
        WalletSecretBytes.wipe(&bytes)
    }

    deinit {
        clear()
    }
}

enum NativeWalletBridgeError: LocalizedError {
    case callFailed(operation: String, code: UInt32, detail: String)
    case invalidOutput(String)
    case closed

    var errorDescription: String? {
        switch self {
        case .callFailed(let operation, let code, let detail):
            "\(operation) failed (\(code)): \(detail)"
        case .invalidOutput(let detail):
            "The native wallet returned invalid output: \(detail)"
        case .closed:
            "The native wallet is closed."
        }
    }
}

/// Opaque Swift owner for one Rust wallet controller. It deliberately exposes
/// only create/restore/open/status/accounts/unlock/lock and recovery display.
final class RustNativeWallet: @unchecked Sendable {
    private let handleLock = NSLock()
    private var handle: HnsBrowserWalletHandle

    private init(handle: HnsBrowserWalletHandle) throws {
        guard handle != 0 else {
            throw NativeWalletBridgeError.invalidOutput("wallet handle is zero")
        }
        self.handle = handle
    }

    static func create(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer,
        network: BrowserHandshakeNetwork,
        birthdayHeight: UInt64
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_create(
                path,
                NativeWalletBridge.slice(databaseKey),
                network.walletNativeValue,
                birthdayHeight,
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet create")
        return try RustNativeWallet(handle: handle)
    }

    static func restore(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer,
        network: BrowserHandshakeNetwork,
        birthdayHeight: UInt64,
        recoveryPhrase: UnsafeRawBufferPointer
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_restore(
                path,
                NativeWalletBridge.slice(databaseKey),
                network.walletNativeValue,
                birthdayHeight,
                NativeWalletBridge.slice(recoveryPhrase),
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet restore")
        return try RustNativeWallet(handle: handle)
    }

    static func open(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_open(
                path,
                NativeWalletBridge.slice(databaseKey),
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet open")
        return try RustNativeWallet(handle: handle)
    }

    func status() throws -> NativeWalletStatus {
        try decodeOutput(operation: "wallet status", invoke: hns_browser_wallet_status)
    }

    func birthdayHeight() throws -> UInt64 {
        var height: UInt64 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_birthday_height(try liveHandle(), &height),
            operation: "wallet birthday"
        )
        return height
    }

    func accounts() throws -> [NativeWalletAccount] {
        try decodeOutput(operation: "wallet accounts", invoke: hns_browser_wallet_accounts)
    }

    func configureHnsReads(
        _ configuration: NativeHnsReadConfiguration,
        currentAuthority: WalletReadBootstrapAuthority
    ) throws {
        try configuration.consume(for: currentAuthority) { port, authorization in
            try NativeWalletBridge.check(
                hns_browser_wallet_configure_hns_reads(
                    try liveHandle(),
                    port,
                    NativeWalletBridge.slice(authorization)
                ),
                operation: "wallet HNS read configuration"
            )
        }
    }

    func hasHnsReads() throws -> Bool {
        var enabled: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_has_hns_reads(try liveHandle(), &enabled),
            operation: "wallet HNS read availability"
        )
        guard enabled <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("HNS read availability is not boolean")
        }
        return enabled == 1
    }

    /// Installs the wallet-owned direct HNS composition.  Configuration is
    /// deliberately one-way: the native controller owns peer discovery,
    /// consensus verification, block scanning, and broadcast thereafter.
    func configureDirectHnsValue(
        databaseKey: UnsafeRawBufferPointer,
        rollbackFloor: inout [UInt8],
        bootstrapSnapshotPath: String?
    ) throws {
        defer { WalletSecretBytes.wipe(&rollbackFloor) }
        guard rollbackFloor.count == 36 else {
            throw NativeWalletBridgeError.invalidOutput("direct HNS rollback floor has invalid length")
        }
        let currentHandle = try liveHandle()
        try rollbackFloor.withUnsafeBufferPointer { floor in
            try NativeWalletBridge.withOptionalUTF8Slice(bootstrapSnapshotPath) { snapshot in
                try NativeWalletBridge.check(
                    hns_browser_wallet_configure_direct_hns_value(
                        currentHandle,
                        NativeWalletBridge.slice(databaseKey),
                        HnsBrowserSlice(ptr: floor.baseAddress, len: UInt64(floor.count)),
                        snapshot
                    ),
                    operation: "wallet direct HNS configuration"
                )
            }
        }
    }

    func hasHnsValue() throws -> Bool {
        var enabled: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_has_hns_value(try liveHandle(), &enabled),
            operation: "wallet HNS value availability"
        )
        guard enabled <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("HNS value availability is not boolean")
        }
        return enabled == 1
    }

    func hasBitcoinValue() throws -> Bool {
        var enabled: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_has_bitcoin_value(try liveHandle(), &enabled),
            operation: "wallet Bitcoin availability"
        )
        guard enabled <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("Bitcoin availability is not boolean")
        }
        return enabled == 1
    }

    func bitcoinSnapshot() throws -> NativeBitcoinWalletSnapshot {
        try decodeBitcoinBundle(operation: "wallet Bitcoin snapshot") { handle, output in
            hns_browser_wallet_bitcoin_snapshot(handle, output)
        }
    }

    func setBitcoinBirthdayHeight(
        earliestTransactionHeight: UInt32
    ) throws -> NativeBitcoinWalletSnapshot {
        guard earliestTransactionHeight > 0 else {
            throw NativeWalletBridgeError.invalidOutput(
                "Bitcoin birthday height must be nonzero"
            )
        }
        return try decodeBitcoinBundle(operation: "wallet Bitcoin birthday reset") {
            handle, output in
            hns_browser_wallet_set_bitcoin_birthday_height(
                handle,
                earliestTransactionHeight,
                output
            )
        }
    }

    func nextBitcoinReceiveAddress() throws -> NativeBitcoinReceiveAddress {
        try decodeBitcoinBundle(operation: "wallet Bitcoin receive address") { handle, output in
            hns_browser_wallet_next_bitcoin_receive_address(handle, output)
        }
    }

    func synchronizeBitcoin() throws -> NativeBitcoinSynchronization {
        try decodeBitcoinBundle(operation: "wallet Bitcoin synchronization") { handle, output in
            hns_browser_wallet_synchronize_bitcoin(handle, output)
        }
    }

    func bitcoinSynchronizationProgress() throws -> NativeBitcoinSyncProgress? {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_bitcoin_sync_progress(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        if result == HNS_BROWSER_RESULT_NOT_READY { return nil }
        try NativeWalletBridge.check(result, operation: "wallet Bitcoin synchronization progress")
        return try decodeBitcoinOutput(output, as: NativeBitcoinSyncProgress.self)
    }

    func cancelBitcoinSynchronization() throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_cancel_bitcoin_sync(try liveHandle()),
            operation: "wallet Bitcoin synchronization cancellation"
        )
    }

    func prepareBitcoinSend(
        destination: inout [UInt8],
        amountSats: inout [UInt8],
        maximumFeeSats: inout [UInt8]
    ) throws -> NativeBitcoinSendApproval {
        defer {
            WalletSecretBytes.wipe(&destination)
            WalletSecretBytes.wipe(&amountSats)
            WalletSecretBytes.wipe(&maximumFeeSats)
        }
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = destination.withUnsafeBufferPointer { destination in
            amountSats.withUnsafeBufferPointer { amount in
                maximumFeeSats.withUnsafeBufferPointer { fee in
                    hns_browser_wallet_prepare_bitcoin_send(
                        currentHandle,
                        HnsBrowserSlice(ptr: destination.baseAddress, len: UInt64(destination.count)),
                        HnsBrowserSlice(ptr: amount.baseAddress, len: UInt64(amount.count)),
                        HnsBrowserSlice(ptr: fee.baseAddress, len: UInt64(fee.count)),
                        &output
                    )
                }
            }
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet Bitcoin send preparation")
        do {
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            return try NativeBitcoinSendApproval.decode(bundle: bundle)
        } catch {
            try? lock()
            throw error
        }
    }

    func approveBitcoinSend(
        _ actionToken: NativeHnsSendActionToken
    ) throws -> NativeBitcoinSendReceipt {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_approve_bitcoin_send(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "wallet Bitcoin send approval")
            do {
                return try decodeBitcoinOutput(output, as: NativeBitcoinSendReceipt.self)
            } catch {
                try? lock()
                throw error
            }
        }
    }

    func rejectBitcoinSend(_ actionToken: NativeHnsSendActionToken) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_reject_bitcoin_send(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                )
            }
            try NativeWalletBridge.check(result, operation: "wallet Bitcoin send rejection")
        }
    }

    func prepareBtcForHnsFunding(
        sessionId: inout [UInt8], maximumFeeSats: inout [UInt8]
    ) throws -> NativeBitcoinHtlcFundingApproval {
        defer {
            WalletSecretBytes.wipe(&sessionId)
            WalletSecretBytes.wipe(&maximumFeeSats)
        }
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = sessionId.withUnsafeBufferPointer { session in
            maximumFeeSats.withUnsafeBufferPointer { fee in
                hns_browser_wallet_prepare_btc_for_hns_funding(
                    currentHandle,
                    HnsBrowserSlice(ptr: session.baseAddress, len: UInt64(session.count)),
                    HnsBrowserSlice(ptr: fee.baseAddress, len: UInt64(fee.count)),
                    &output
                )
            }
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "BTC-for-HNS funding preparation")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeBitcoinHtlcFundingApproval.decode(bundle: bundle)
    }

    func approveBtcForHnsFunding(
        _ actionToken: NativeHnsSendActionToken
    ) throws -> NativeBitcoinHtlcFundingReceipt {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_approve_btc_for_hns_funding(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "BTC-for-HNS funding approval")
            return try decodeBitcoinOutput(output, as: NativeBitcoinHtlcFundingReceipt.self)
        }
    }

    func rejectBtcForHnsFunding(_ actionToken: NativeHnsSendActionToken) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_reject_btc_for_hns_funding(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                )
            }
            try NativeWalletBridge.check(result, operation: "BTC-for-HNS funding rejection")
        }
    }

    func prepareHnsForBtcFunding(
        sessionId: inout [UInt8], maximumFeeDollarydoos: inout [UInt8]
    ) throws -> NativeHnsHtlcFundingApproval {
        defer {
            WalletSecretBytes.wipe(&sessionId)
            WalletSecretBytes.wipe(&maximumFeeDollarydoos)
        }
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = sessionId.withUnsafeBufferPointer { session in
            maximumFeeDollarydoos.withUnsafeBufferPointer { fee in
                hns_browser_wallet_prepare_hns_for_btc_funding(
                    currentHandle,
                    HnsBrowserSlice(ptr: session.baseAddress, len: UInt64(session.count)),
                    HnsBrowserSlice(ptr: fee.baseAddress, len: UInt64(fee.count)),
                    &output
                )
            }
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "HNS-for-BTC funding preparation")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeHnsHtlcFundingApproval.decode(bundle: bundle)
    }

    func approveHnsForBtcFunding(
        _ actionToken: NativeHnsSendActionToken
    ) throws -> NativeHnsHtlcFundingReceipt {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_approve_hns_for_btc_funding(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "HNS-for-BTC funding approval")
            return try decodeBitcoinOutput(output, as: NativeHnsHtlcFundingReceipt.self)
        }
    }

    func rejectHnsForBtcFunding(_ actionToken: NativeHnsSendActionToken) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_reject_hns_for_btc_funding(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                )
            }
            try NativeWalletBridge.check(result, operation: "HNS-for-BTC funding rejection")
        }
    }

    func prepareSwapSettlement(
        sessionId: inout [UInt8], maximumFee: inout [UInt8],
        action: NativeSwapSettlementAction, bitcoin: Bool
    ) throws -> NativeSwapSettlementApproval {
        defer {
            WalletSecretBytes.wipe(&sessionId)
            WalletSecretBytes.wipe(&maximumFee)
        }
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = sessionId.withUnsafeBufferPointer { session in
            return maximumFee.withUnsafeBufferPointer { fee in
                let sessionSlice = HnsBrowserSlice(
                    ptr: session.baseAddress, len: UInt64(session.count)
                )
                let feeSlice = HnsBrowserSlice(ptr: fee.baseAddress, len: UInt64(fee.count))
                if bitcoin {
                    return hns_browser_wallet_prepare_bitcoin_swap_settlement(
                        currentHandle, sessionSlice, action == .redeem, feeSlice, &output
                    )
                } else {
                    return hns_browser_wallet_prepare_hns_swap_settlement(
                        currentHandle, sessionSlice, action == .redeem, feeSlice, &output
                    )
                }
            }
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "swap settlement preparation")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeSwapSettlementApproval.decode(bundle: bundle, bitcoin: bitcoin)
    }

    func approveSwapSettlement(
        _ actionToken: NativeHnsSendActionToken, bitcoin: Bool
    ) throws -> NativeSwapSettlementReceipt {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { bytes in
                let slice = HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                if bitcoin {
                    return hns_browser_wallet_approve_bitcoin_swap_settlement(
                        try liveHandle(), slice, &output
                    )
                } else {
                    return hns_browser_wallet_approve_hns_swap_settlement(
                        try liveHandle(), slice, &output
                    )
                }
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "swap settlement approval")
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            return try NativeSwapSettlementReceipt.decode(bundle: bundle, bitcoin: bitcoin)
        }
    }

    func rejectSwapSettlement(
        _ actionToken: NativeHnsSendActionToken, bitcoin: Bool
    ) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { bytes in
                let slice = HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                if bitcoin {
                    return hns_browser_wallet_reject_bitcoin_swap_settlement(
                        try liveHandle(), slice
                    )
                } else {
                    return hns_browser_wallet_reject_hns_swap_settlement(
                        try liveHandle(), slice
                    )
                }
            }
            try NativeWalletBridge.check(result, operation: "swap settlement rejection")
        }
    }

    func prepareBtcForHnsOffer(
        btcAmountSats: UInt64,
        hnsAmountDollarydoos: UInt64,
        bitcoinFeeReserveSats: UInt64,
        listingLifetimeSeconds: UInt64
    ) throws -> NativeBtcForHnsOfferApproval {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_prepare_btc_for_hns_offer(
            try liveHandle(),
            btcAmountSats,
            hnsAmountDollarydoos,
            bitcoinFeeReserveSats,
            listingLifetimeSeconds,
            &output
        )
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "BTC-for-HNS offer preparation")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeBtcForHnsOfferApproval.decode(bundle: bundle)
    }

    func approveBtcForHnsOffer(
        _ actionToken: NativeHnsSendActionToken
    ) throws -> NativeBtcForHnsOfferSummary {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_approve_btc_for_hns_offer(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "BTC-for-HNS offer publication")
            return try decodeBitcoinOutput(output, as: NativeBtcForHnsOfferSummary.self)
        }
    }

    func rejectBtcForHnsOffer(_ actionToken: NativeHnsSendActionToken) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { bytes in
                hns_browser_wallet_reject_btc_for_hns_offer(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
                )
            }
            try NativeWalletBridge.check(result, operation: "BTC-for-HNS offer rejection")
        }
    }

    func localBtcForHnsOffers() throws -> [NativeBtcForHnsOfferSummary] {
        let result: NativeBtcForHnsOfferList = try decodeBitcoinBundle(
            operation: "local BTC-for-HNS offers"
        ) { handle, output in
            hns_browser_wallet_local_btc_for_hns_offers(handle, output)
        }
        return result.offers
    }

    func shakescapeExecutions() throws -> NativeShakescapeExecutionStatus {
        try decodeBitcoinBundle(
            operation: "durable Shakescape executions"
        ) { handle, output in
            hns_browser_wallet_shakescape_executions(handle, output)
        }
    }

    func cancelBtcForHnsOffer(offerId: String) throws {
        var offer = Array(offerId.utf8)
        defer { WalletSecretBytes.wipe(&offer) }
        let result = try offer.withUnsafeBufferPointer { bytes in
            hns_browser_wallet_cancel_btc_for_hns_offer(
                try liveHandle(),
                HnsBrowserSlice(ptr: bytes.baseAddress, len: UInt64(bytes.count))
            )
        }
        try NativeWalletBridge.check(result, operation: "BTC-for-HNS offer cancellation")
    }

    func directHnsRollbackFloor() throws -> [UInt8] {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_direct_hns_rollback_floor(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet direct HNS rollback floor")
        let floor = try NativeWalletBridge.bytes(copying: output)
        guard floor.count == 36 else {
            throw NativeWalletBridgeError.invalidOutput("native direct HNS rollback floor has invalid length")
        }
        return floor
    }

    func localHnsReceiveTarget() throws -> NativeHnsReadSnapshot.ReceiveTarget {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_local_hns_receive_target(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet local HNS receive target")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNRT".utf8),
            maximumJSONBytes: 4_096
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        return try JSONDecoder().decode(NativeHnsReadSnapshot.ReceiveTarget.self, from: payload)
    }

    func prepareHnsSend(
        recipient: inout [UInt8],
        amountBaseUnits: inout [UInt8],
        maximumFeeBaseUnits: inout [UInt8]
    ) throws -> NativeHnsSendApproval {
        defer {
            WalletSecretBytes.wipe(&recipient)
            WalletSecretBytes.wipe(&amountBaseUnits)
            WalletSecretBytes.wipe(&maximumFeeBaseUnits)
        }
        let currentHandle = try liveHandle()
        var output = HnsBrowserBuffer()
        let result = try recipient.withUnsafeBufferPointer { recipient in
            try amountBaseUnits.withUnsafeBufferPointer { amount in
                try maximumFeeBaseUnits.withUnsafeBufferPointer { maximumFee in
                    hns_browser_wallet_prepare_hns_send(
                        currentHandle,
                        HnsBrowserSlice(ptr: recipient.baseAddress, len: UInt64(recipient.count)),
                        HnsBrowserSlice(ptr: amount.baseAddress, len: UInt64(amount.count)),
                        HnsBrowserSlice(ptr: maximumFee.baseAddress, len: UInt64(maximumFee.count)),
                        &output
                    )
                }
            }
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet HNS send preparation")
        do {
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            return try NativeHnsSendApproval.decode(bundle: bundle)
        } catch {
            // A successful native prepare may have installed a pending action.
            // Never leave it executable when its display projection failed.
            try? lock()
            throw error
        }
    }

    func prepareHnsValueAction(
        intentJSON: inout [UInt8]
    ) throws -> NativeHnsValueApproval {
        defer { WalletSecretBytes.wipe(&intentJSON) }
        guard (2...8_192).contains(intentJSON.count) else {
            throw NativeWalletBridgeError.invalidOutput("HNS value intent is outside its byte bound")
        }
        var output = HnsBrowserBuffer()
        let result = try intentJSON.withUnsafeBufferPointer { intent in
            hns_browser_wallet_prepare_hns_value_action(
                try liveHandle(),
                HnsBrowserSlice(ptr: intent.baseAddress, len: UInt64(intent.count)),
                &output
            )
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet HNS value preparation")
        do {
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            return try NativeHnsValueApproval.decode(bundle: bundle)
        } catch {
            // A completed native prepare owns an executable action token. A
            // malformed display projection therefore locks rather than leaves
            // that action available without a human-readable review.
            try? lock()
            throw error
        }
    }

    func queryShakedex(
        queryJSON: inout [UInt8]
    ) throws -> NativeShakedexQueryResult {
        defer { WalletSecretBytes.wipe(&queryJSON) }
        guard (2...4_096).contains(queryJSON.count) else {
            throw NativeWalletBridgeError.invalidOutput("Shakedex query is outside its byte bound")
        }
        var output = HnsBrowserBuffer()
        let result = try queryJSON.withUnsafeBufferPointer { query in
            hns_browser_wallet_query_shakedex(
                try liveHandle(),
                HnsBrowserSlice(ptr: query.baseAddress, len: UInt64(query.count)),
                &output
            )
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet Shakedex query")
        do {
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            return try NativeShakedexQueryResult.decode(bundle: bundle)
        } catch {
            try? lock()
            throw error
        }
    }

    func directShakescapeStatus() throws -> NativeDirectShakescapeStatus {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_direct_shakescape_status(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet direct Shakescape status")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeDirectShakescapeBundle.status(bundle)
    }

    func retryDirectShakescapeListener() throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_retry_direct_shakescape_listener(try liveHandle()),
            operation: "wallet direct Shakescape listener retry"
        )
    }

    func connectDirectShakescape(endpoint: String) throws -> NativeDirectShakescapeConnectResult {
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = NativeWalletBridge.withUTF8Slice(endpoint) { endpoint in
            hns_browser_wallet_connect_direct_shakescape(
                currentHandle, endpoint, &output
            )
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet direct Shakescape connection")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeDirectShakescapeBundle.connect(bundle)
    }

    func disconnectDirectShakescape() throws -> Bool {
        var disconnected: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_disconnect_direct_shakescape(try liveHandle(), &disconnected),
            operation: "wallet direct Shakescape disconnect"
        )
        guard disconnected <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("direct Shakescape disconnect is not boolean")
        }
        return disconnected == 1
    }

    func serviceDirectShakescape() throws -> Bool {
        var serviced: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_service_direct_shakescape(try liveHandle(), &serviced),
            operation: "wallet direct Shakescape service"
        )
        guard serviced <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("direct Shakescape service result is not boolean")
        }
        return serviced == 1
    }

    func approveHnsSend(_ actionToken: NativeHnsSendActionToken) throws -> NativeHnsSendReceipt {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { buffer in
                hns_browser_wallet_approve_hns_send(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "wallet HNS send approval")
            do {
                var bundle = try NativeWalletBridge.bytes(copying: output)
                defer { WalletSecretBytes.wipe(&bundle) }
                return try NativeHnsSendReceipt.decode(bundle: bundle)
            } catch {
                // Approval may already have signed or broadcast.  Lock on any
                // malformed/undeliverable result rather than guessing state.
                try? lock()
                throw error
            }
        }
    }

    func approveHnsValueActionResult(
        _ actionToken: NativeHnsSendActionToken
    ) throws -> NativeHnsValueResult {
        try actionToken.consume { token in
            var output = HnsBrowserBuffer()
            let result = try token.withUnsafeBufferPointer { buffer in
                hns_browser_wallet_approve_hns_value_action_result(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count)),
                    &output
                )
            }
            defer { NativeWalletBridge.free(output) }
            try NativeWalletBridge.check(result, operation: "wallet HNS value approval")
            do {
                var bundle = try NativeWalletBridge.bytes(copying: output)
                defer { WalletSecretBytes.wipe(&bundle) }
                return try NativeHnsValueResult.decode(bundle: bundle, magic: Array("HNVX".utf8))
            } catch {
                try? lock()
                throw error
            }
        }
    }

    func rejectHnsSend(_ actionToken: NativeHnsSendActionToken) throws {
        try actionToken.consume { token in
            let result = try token.withUnsafeBufferPointer { buffer in
                hns_browser_wallet_reject_hns_send(
                    try liveHandle(),
                    HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count))
                )
            }
            try NativeWalletBridge.check(result, operation: "wallet HNS send rejection")
        }
    }

    func rejectHnsValueAction(_ actionToken: NativeHnsSendActionToken) throws {
        try rejectHnsSend(actionToken)
    }

    func synchronizeHnsReads() throws -> NativeHnsReadSnapshot {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_synchronize_hns_reads(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet HNS synchronization")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeHnsReadSnapshot.decode(bundle: bundle)
    }

    /// Reads only the native synchronizer's public progress mailbox. This call
    /// never waits for or projects private wallet state.
    func hnsSynchronizationProgress() throws -> WalletHnsSyncProgress? {
        var native = HnsBrowserWalletHnsSyncProgress()
        let result = hns_browser_wallet_hns_sync_progress(try liveHandle(), &native)
        if result == HNS_BROWSER_RESULT_NOT_READY {
            return nil
        }
        try NativeWalletBridge.check(result, operation: "wallet HNS synchronization progress")
        guard native.struct_size == UInt32(MemoryLayout<HnsBrowserWalletHnsSyncProgress>.size),
              native.has_scanned_height <= 1,
              native.reserved0 == 0,
              let stage = WalletHnsSyncStage(rawValue: native.stage),
              native.verified_header_height == native.target_height else {
            throw NativeWalletBridgeError.invalidOutput(
                "wallet HNS synchronization progress is incoherent"
            )
        }
        let scannedHeight = native.has_scanned_height == 1
            ? native.scanned_height
            : nil
        if let scannedHeight,
           !(native.birthday_height...native.target_height).contains(scannedHeight) {
            throw NativeWalletBridgeError.invalidOutput(
                "wallet HNS scanned height is outside its verified range"
            )
        }
        return WalletHnsSyncProgress(
            stage: stage,
            verifiedHeaderHeight: native.verified_header_height,
            birthdayHeight: native.birthday_height,
            scannedHeight: scannedHeight,
            targetHeight: native.target_height
        )
    }

    /// Requests cancellation through the native synchronization-control
    /// mailbox without waiting for private wallet/controller ownership.
    func cancelHnsSynchronization() throws {
        let result = hns_browser_wallet_cancel_hns_sync(try liveHandle())
        try NativeWalletBridge.check(result, operation: "wallet HNS synchronization cancellation")
    }

    /// Passes the exact mutable UTF-8 bytes without trimming, case conversion,
    /// IDNA, Unicode normalization, or trailing-dot editing, then wipes them.
    func importHnsNameExactText(
        _ exactName: inout [UInt8]
    ) throws -> NativeHnsReadSnapshot.KnownName {
        defer { WalletSecretBytes.wipe(&exactName) }
        guard !exactName.isEmpty, exactName.count <= 63 else {
            throw NativeWalletBridgeError.invalidOutput(
                "trusted-native HNS name input is outside its byte bound"
            )
        }
        var output = HnsBrowserBuffer()
        let currentHandle = try liveHandle()
        let result = exactName.withUnsafeBufferPointer { buffer in
            hns_browser_wallet_import_hns_name_exact_text(
                currentHandle,
                HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count)),
                &output
            )
        }
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet HNS name import")
        do {
            var bundle = try NativeWalletBridge.bytes(copying: output)
            defer { WalletSecretBytes.wipe(&bundle) }
            let summary = try NativeHnsNameImportBundle.decode(bundle: bundle)
            guard summary.name.utf8.elementsEqual(exactName) else {
                try? lock()
                throw NativeWalletBridgeError.invalidOutput(
                    "HNS name import summary changed the exact input text"
                )
            }
            return summary
        } catch {
            // A successful C call means the import may already have committed.
            // Fail closed if copying, decoding, or exact-echo validation fails.
            try? lock()
            throw error
        }
    }

    /// Sends one reviewed array to the native controller so validation and
    /// persistence remain atomic. The JSON transport is bounded and wiped as
    /// soon as the native call returns.
    func importHnsNamesExactText(_ exactNames: [String]) throws -> Int {
        guard (1...10_000).contains(exactNames.count),
              Set(exactNames).count == exactNames.count,
              exactNames.allSatisfy({ (1...63).contains($0.utf8.count) }) else {
            throw NativeWalletBridgeError.invalidOutput(
                "trusted-native HNS bulk name input is outside its bound"
            )
        }
        var data = try JSONSerialization.data(withJSONObject: exactNames, options: [])
        defer { data.resetBytes(in: data.startIndex..<data.endIndex) }
        guard (2...1_048_576).contains(data.count) else {
            throw NativeWalletBridgeError.invalidOutput(
                "trusted-native HNS bulk name JSON exceeds its input bound"
            )
        }
        var importedCount: UInt64 = 0
        let currentHandle = try liveHandle()
        let result = data.withUnsafeBytes { buffer in
            hns_browser_wallet_import_hns_names_exact_text(
                currentHandle,
                NativeWalletBridge.slice(buffer),
                &importedCount
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet HNS bulk name import")
        guard importedCount == UInt64(exactNames.count) else {
            try? lock()
            throw NativeWalletBridgeError.invalidOutput(
                "HNS bulk name import returned an inexact count"
            )
        }
        return Int(importedCount)
    }

    func unlock(databaseKey: UnsafeRawBufferPointer) throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_unlock(
                try liveHandle(),
                NativeWalletBridge.slice(databaseKey)
            ),
            operation: "wallet unlock"
        )
    }

    func lock() throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_lock(try liveHandle()),
            operation: "wallet lock"
        )
    }

    func takeRecoveryPhrase() throws -> WalletRecoverySecret {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_take_recovery_phrase(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet recovery display")
        return WalletRecoverySecret(bytes: try NativeWalletBridge.bytes(copying: output))
    }

    func close() {
        handleLock.lock()
        let current = handle
        handle = 0
        handleLock.unlock()
        if current != 0 {
            _ = hns_browser_wallet_destroy(current)
        }
    }

    /// Confirmed deletion must observe native retirement before destroying the
    /// database key. The handle is detached even on error: the C ABI may have
    /// removed it before reporting a later teardown failure, so retrying it as
    /// though it were certainly live would invent authority.
    func closeForConfirmedDeletion() throws {
        handleLock.lock()
        let current = handle
        handle = 0
        handleLock.unlock()
        guard current != 0 else { throw NativeWalletBridgeError.closed }
        try NativeWalletBridge.check(
            hns_browser_wallet_destroy(current),
            operation: "wallet confirmed-deletion close"
        )
    }

    deinit {
        close()
    }

    private func liveHandle() throws -> HnsBrowserWalletHandle {
        handleLock.lock()
        defer { handleLock.unlock() }
        guard handle != 0 else { throw NativeWalletBridgeError.closed }
        return handle
    }

    private func decodeOutput<T: Decodable>(
        operation: String,
        invoke: (HnsBrowserWalletHandle, UnsafeMutablePointer<HnsBrowserBuffer>?) -> HnsBrowserResult
    ) throws -> T {
        var output = HnsBrowserBuffer()
        let result = invoke(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: operation)
        return try JSONDecoder().decode(T.self, from: NativeWalletBridge.data(copying: output))
    }

    private func decodeBitcoinBundle<T: Decodable>(
        operation: String,
        invoke: (HnsBrowserWalletHandle, UnsafeMutablePointer<HnsBrowserBuffer>?) -> HnsBrowserResult
    ) throws -> T {
        var output = HnsBrowserBuffer()
        let result = invoke(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: operation)
        return try decodeBitcoinOutput(output, as: T.self)
    }

    private func decodeBitcoinOutput<T: Decodable>(
        _ output: HnsBrowserBuffer,
        as type: T.Type
    ) throws -> T {
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        var payload = try NativeHnsValueBundle.payload(
            bundle,
            magic: Array("HNBW".utf8),
            maximumJSONBytes: 16 * 1_024
        )
        defer { payload.resetBytes(in: payload.startIndex..<payload.endIndex) }
        return try JSONDecoder().decode(type, from: payload)
    }
}

private enum NativeWalletBridge {
    static func withUTF8Slice<T>(
        _ value: String,
        body: (HnsBrowserSlice) throws -> T
    ) rethrows -> T {
        let bytes = Array(value.utf8)
        return try bytes.withUnsafeBufferPointer { buffer in
            try body(HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count)))
        }
    }

    static func withOptionalUTF8Slice<T>(
        _ value: String?,
        body: (HnsBrowserSlice) throws -> T
    ) rethrows -> T {
        guard let value else {
            return try body(HnsBrowserSlice(ptr: nil, len: 0))
        }
        return try withUTF8Slice(value, body: body)
    }

    static func slice(_ bytes: UnsafeRawBufferPointer) -> HnsBrowserSlice {
        HnsBrowserSlice(
            ptr: bytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
            len: UInt64(bytes.count)
        )
    }

    static func bytes(copying buffer: HnsBrowserBuffer) throws -> [UInt8] {
        guard buffer.len <= UInt64(Int.max) else {
            throw NativeWalletBridgeError.invalidOutput("buffer length is unsupported")
        }
        if buffer.len == 0 {
            guard buffer.ptr == nil, buffer.allocation_id == 0 else {
                throw NativeWalletBridgeError.invalidOutput("empty buffer token is malformed")
            }
            return []
        }
        guard let pointer = buffer.ptr, buffer.allocation_id != 0 else {
            throw NativeWalletBridgeError.invalidOutput("nonempty buffer is malformed")
        }
        return Array(UnsafeBufferPointer(start: pointer, count: Int(buffer.len)))
    }

    static func data(copying buffer: HnsBrowserBuffer) throws -> Data {
        Data(try bytes(copying: buffer))
    }

    static func free(_ buffer: HnsBrowserBuffer) {
        _ = hns_browser_buffer_free(buffer)
    }

    static func check(_ result: HnsBrowserResult, operation: String) throws {
        guard result != HNS_BROWSER_RESULT_OK else { return }
        var errorBuffer = HnsBrowserBuffer()
        let errorResult = hns_browser_last_error(&errorBuffer)
        defer { free(errorBuffer) }
        let detail: String
        if errorResult == HNS_BROWSER_RESULT_OK,
           let data = try? data(copying: errorBuffer),
           let message = String(data: data, encoding: .utf8),
           !message.isEmpty {
            detail = message
        } else {
            detail = "no native error detail"
        }
        throw NativeWalletBridgeError.callFailed(
            operation: operation,
            code: result,
            detail: detail
        )
    }
}

private extension BrowserHandshakeNetwork {
    var walletNativeValue: HnsBrowserNetwork {
        switch self {
        case .mainnet: HNS_BROWSER_NETWORK_MAINNET
        case .testnet: HNS_BROWSER_NETWORK_TESTNET
        case .regtest: HNS_BROWSER_NETWORK_REGTEST
        }
    }
}
