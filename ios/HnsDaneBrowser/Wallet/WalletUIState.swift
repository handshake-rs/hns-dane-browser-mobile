import Foundation

let walletOperationInProgressMessage =
    "The wallet is still completing synchronization or another operation. " +
    "It may continue in the background. Wait for it to finish, then try again."

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
    case terminal(WalletHnsSyncProgress?)
}

final class WalletHnsSyncPresentationLease: @unchecked Sendable {
    fileprivate let networkID: String

    fileprivate init(networkID: String) {
        self.networkID = networkID
    }
}

private final class WalletHnsSyncPresentationState: @unchecked Sendable {
    struct Entry {
        let lease: WalletHnsSyncPresentationLease
        var presentation: WalletHnsSyncPresentation?
        var acceptsLiveProgress = true
    }

    let lock = NSLock()
    var entries: [String: Entry] = [:]
}

/// Process-owned public presentation state. It never owns a wallet key,
/// balance, address, name, transaction, peer endpoint, or signing authority.
enum WalletHnsSyncPresentationCache {
    private static let state = WalletHnsSyncPresentationState()

    static func begin(networkID: String) -> WalletHnsSyncPresentationLease {
        let lease = WalletHnsSyncPresentationLease(networkID: networkID)
        state.lock.lock()
        state.entries[networkID] = .init(lease: lease, presentation: .preparing)
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
        entry.presentation = .live(progress)
        state.entries[lease.networkID] = entry
    }

    static func finish(lease: WalletHnsSyncPresentationLease) {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard var entry = state.entries[lease.networkID],
              entry.lease === lease else { return }
        let lastProgress: WalletHnsSyncProgress?
        if case .live(let progress) = entry.presentation {
            lastProgress = progress
        } else {
            lastProgress = nil
        }
        entry.presentation = .terminal(lastProgress)
        entry.acceptsLiveProgress = false
        state.entries[lease.networkID] = entry
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
    case .preparing, .live: return false
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
