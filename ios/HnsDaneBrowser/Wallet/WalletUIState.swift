import Foundation

/// Selects one newly authenticated height for a pending-transaction refresh.
/// A failed or no-op round cannot spin until strictly newer evidence arrives.
func walletPendingOutgoingRefreshHeight(
    pendingSnapshotHeight: UInt64?,
    observedHeaderHeight: UInt64?,
    attemptedHeaderHeight: UInt64?
) -> UInt64? {
    guard let pendingSnapshotHeight,
          let observedHeaderHeight,
          observedHeaderHeight > pendingSnapshotHeight,
          attemptedHeaderHeight.map({ observedHeaderHeight > $0 }) ?? true else {
        return nil
    }
    return observedHeaderHeight
}

enum WalletPendingPaymentContinuation: Equatable {
    case none
    case wait
    case unlock
    case synchronize
    case present
}

/// Mirrors Android's camera-payment continuation. Only an in-wallet scan may
/// carry unlock intent across controller retirement; an external deep link
/// remains pending until the user explicitly unlocks.
func walletPendingPaymentContinuation(
    hasPendingPayment: Bool,
    resumeAfterScanner: Bool,
    foreground: Bool,
    dialogVisible: Bool,
    busy: Bool,
    hasController: Bool,
    controllerUnlocked: Bool,
    hasHnsValue: Bool,
    hasCurrentSnapshot: Bool,
    hasPendingOutgoing: Bool
) -> WalletPendingPaymentContinuation {
    guard hasPendingPayment else { return .none }
    guard foreground, !dialogVisible, !busy else { return .wait }
    guard hasController, controllerUnlocked else {
        return resumeAfterScanner ? .unlock : .wait
    }
    guard hasHnsValue else { return .wait }
    guard !hasPendingOutgoing else { return .wait }
    guard hasCurrentSnapshot else {
        return resumeAfterScanner ? .synchronize : .wait
    }
    return .present
}

/// A non-sensitive, fail-closed UI marker for a transaction that was written
/// to peers but has not yet been settled by a verified wallet snapshot. The
/// encrypted wallet remains authoritative; a completed snapshot clears this
/// marker as soon as it proves no outgoing transaction is pending.
enum WalletPendingOutgoingRecoveryStore {
    private static let prefix = "wallet.pendingOutgoingRecovery"

    static func load(
        networkID: String,
        accountID: String,
        defaults: UserDefaults = .standard
    ) -> UInt64? {
        let accountKey = "\(prefix).\(networkID).account"
        guard defaults.string(forKey: accountKey) == accountID else { return nil }
        let heightKey = "\(prefix).\(networkID).height"
        return UInt64(max(0, defaults.integer(forKey: heightKey)))
    }

    static func save(
        networkID: String,
        accountID: String,
        height: UInt64?,
        defaults: UserDefaults = .standard
    ) {
        defaults.set(accountID, forKey: "\(prefix).\(networkID).account")
        defaults.set(Int(min(height ?? 0, UInt64(Int.max))),
                     forKey: "\(prefix).\(networkID).height")
    }

    static func clear(networkID: String, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: "\(prefix).\(networkID).account")
        defaults.removeObject(forKey: "\(prefix).\(networkID).height")
    }
}

let walletOperationInProgressMessage =
    "The wallet is still completing synchronization or another operation. " +
    "It may continue in the background. Wait for it to finish, then try again."

func walletHnsPaymentActionsAvailable(
    baseAvailable: Bool,
    hasPendingOutgoing: Bool
) -> Bool {
    baseAvailable && !hasPendingOutgoing
}

enum WalletScreen: CaseIterable {
    case createWallet, restoreWallet, walletLock, hnsWallet, hnsReceive, hnsSend
    case transactionHistory, knownNames, transferName, finalizeName, shakedexListings
    case createListing, purchaseListing, enableBitcoin, bitcoinReceive, bitcoinSend
    case enableEthereum, ethereumReceive, ethereumSend, marketPriceBoard, matchApproval
    case activeSwaps, redeemSwap, refundSwap, providerPermissions
}

struct WalletUIState {
    var screen: WalletScreen = .walletLock
    var walletAvailable = false
    var locked = true
    var busy = false
    var publicStatus = "Native wallet ABI v2 is unavailable"

    var allowsValueAction: Bool {
        WalletNativeReleaseGates.valueActionsAvailable && walletAvailable && !locked && !busy
    }

    var allowsApprovalAction: Bool {
        WalletNativeReleaseGates.approvalDispatchAvailable && walletAvailable && !locked && !busy
    }
}

/// Keeps the value-changing approval and its required read refresh in one
/// operation. Successful submission proves that the exact approved bytes were
/// written to the connected peer set; it is not remote mempool admission or
/// confirmation. Refresh failure is represented separately because the send
/// may still have propagated and must not be replaced blindly.
struct WalletHnsPostBroadcastResult<Receipt, Snapshot> {
    let receipt: Receipt
    let snapshot: Snapshot?
}

func approveAndRefreshHnsWallet<Receipt, Snapshot>(
    approve: () throws -> Receipt,
    synchronize: () throws -> Snapshot
) throws -> WalletHnsPostBroadcastResult<Receipt, Snapshot> {
    let receipt = try approve()
    return WalletHnsPostBroadcastResult(
        receipt: receipt,
        snapshot: try? synchronize()
    )
}

enum WalletHnsSyncStage: UInt8, Equatable, Sendable {
    case connecting = 1
    case headers = 2
    case scanning = 3
    case finalizing = 4
}

struct WalletHnsSyncProgress: Equatable, Sendable {
    let stage: WalletHnsSyncStage
    let verifiedHeaderHeight: UInt64
    let birthdayHeight: UInt64
    let scannedHeight: UInt64?
    let targetHeight: UInt64
}

enum WalletHnsSyncPresentation: Equatable, Sendable {
    case preparing
    case live(WalletHnsSyncProgress)
    case cancelling(WalletHnsSyncProgress?)
    case terminal(WalletHnsSyncProgress?)
}

final class WalletHnsSyncPresentationLease: @unchecked Sendable {
    fileprivate let networkID: String
    private let cancellationLock = NSLock()
    private var cancellationRequested = false

    fileprivate init(networkID: String) {
        self.networkID = networkID
    }

    fileprivate func markCancellationRequested() {
        cancellationLock.lock()
        cancellationRequested = true
        cancellationLock.unlock()
    }

    var wasCancellationRequested: Bool {
        cancellationLock.lock()
        defer { cancellationLock.unlock() }
        return cancellationRequested
    }
}

private final class WalletHnsSyncPresentationState: @unchecked Sendable {
    struct Entry {
        let lease: WalletHnsSyncPresentationLease
        var presentation: WalletHnsSyncPresentation?
        var lastProgress: WalletHnsSyncProgress? = nil
        var requestCancellation: (@Sendable () -> Void)?
        var acceptsLiveProgress = true
    }

    let lock = NSLock()
    var entries: [String: Entry] = [:]
}

/// Process-owned public presentation state. It never owns a wallet key,
/// balance, address, name, transaction, peer endpoint, or signing authority.
enum WalletHnsSyncPresentationCache {
    private static let state = WalletHnsSyncPresentationState()

    static func begin(
        networkID: String,
        requestCancellation: (@Sendable () -> Void)? = nil
    ) -> WalletHnsSyncPresentationLease {
        let lease = WalletHnsSyncPresentationLease(networkID: networkID)
        state.lock.lock()
        state.entries[networkID] = .init(
            lease: lease,
            presentation: .preparing,
            requestCancellation: requestCancellation
        )
        state.lock.unlock()
        return lease
    }

    static func publish(
        _ progress: WalletHnsSyncProgress,
        lease: WalletHnsSyncPresentationLease
    ) {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard var entry = state.entries[lease.networkID],
              entry.lease === lease,
              entry.acceptsLiveProgress else { return }
        entry.lastProgress = progress
        if case .cancelling = entry.presentation {
            entry.presentation = .cancelling(progress)
        } else {
            entry.presentation = .live(progress)
        }
        state.entries[lease.networkID] = entry
    }

    static func finish(lease: WalletHnsSyncPresentationLease) {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard var entry = state.entries[lease.networkID],
              entry.lease === lease else { return }
        entry.presentation = .terminal(entry.lastProgress)
        entry.requestCancellation = nil
        entry.acceptsLiveProgress = false
        state.entries[lease.networkID] = entry
    }

    static func canRequestCancellation(networkID: String) -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard let entry = state.entries[networkID],
              entry.requestCancellation != nil else { return false }
        switch entry.presentation {
        case .preparing, .live: return true
        case .cancelling, .terminal, nil: return false
        }
    }

    @discardableResult
    static func requestCancellation(networkID: String) -> Bool {
        let request: (@Sendable () -> Void)?
        state.lock.lock()
        if var entry = state.entries[networkID],
           let cancellation = entry.requestCancellation {
            switch entry.presentation {
            case .preparing, .live:
                entry.lease.markCancellationRequested()
                entry.presentation = .cancelling(entry.lastProgress)
                entry.requestCancellation = nil
                state.entries[networkID] = entry
                request = cancellation
            case .cancelling, .terminal, nil:
                request = nil
            }
        } else {
            request = nil
        }
        state.lock.unlock()
        request?()
        return request != nil
    }

    static func latest(networkID: String) -> WalletHnsSyncPresentation? {
        state.lock.lock()
        defer { state.lock.unlock() }
        return state.entries[networkID]?.presentation
    }

    static func clear(networkID: String) {
        state.lock.lock()
        state.entries.removeValue(forKey: networkID)
        state.lock.unlock()
    }

}

func walletHnsPresentationMayAcquireStorage(
    _ presentation: WalletHnsSyncPresentation?
) -> Bool {
    guard let presentation else { return true }
    switch presentation {
    case .preparing, .live, .cancelling: return false
    case .terminal: return true
    }
}

enum WalletHnsSyncLifecycleDisposition: Equatable, Sendable {
    case observeLiveProgress
    case waitForForegroundOrRetirement
    case acquireStorage
}

func walletHnsSyncLifecycleDisposition(
    presentation: WalletHnsSyncPresentation?,
    viewIsVisible: Bool,
    sceneIsActive: Bool
) -> WalletHnsSyncLifecycleDisposition {
    guard viewIsVisible, sceneIsActive else {
        return .waitForForegroundOrRetirement
    }
    return walletHnsPresentationMayAcquireStorage(presentation)
        ? .acquireStorage
        : .observeLiveProgress
}

final class WalletHnsSyncProgressPoller: @unchecked Sendable {
    private let lock = NSLock()
    private var running = true

    init(wallet: RustNativeWallet, lease: WalletHnsSyncPresentationLease) {
        DispatchQueue.global(qos: .utility).async { [self, wallet, lease] in
            while isRunning {
                if let progress = try? wallet.hnsSynchronizationProgress() {
                    WalletHnsSyncPresentationCache.publish(progress, lease: lease)
                }
                Thread.sleep(forTimeInterval: 0.5)
            }
        }
    }

    func stop() {
        lock.lock()
        running = false
        lock.unlock()
    }

    private var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }
}
