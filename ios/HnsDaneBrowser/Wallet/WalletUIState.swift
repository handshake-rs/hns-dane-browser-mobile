import Foundation

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
