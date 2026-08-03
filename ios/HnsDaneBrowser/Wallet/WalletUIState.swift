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
