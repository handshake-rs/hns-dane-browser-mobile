package com.denuoweb.hnsdane.wallet

internal enum class WalletScreen {
    CreateWallet, RestoreWallet, WalletLock, HnsWallet, HnsReceive, HnsSend,
    TransactionHistory, KnownNames, TransferName, FinalizeName, ShakedexListings,
    CreateListing, PurchaseListing, EnableBitcoin, BitcoinReceive, BitcoinSend,
    EnableEthereum, EthereumReceive, EthereumSend, MarketPriceBoard, MatchApproval,
    ActiveSwaps, RedeemSwap, RefundSwap, ProviderPermissions,
}

internal data class WalletUiState(
    val screen: WalletScreen = WalletScreen.WalletLock,
    val walletAvailable: Boolean = false,
    val locked: Boolean = true,
    val busy: Boolean = false,
    val publicStatus: String = "Native wallet ABI v2 is unavailable",
) {
    fun allowsValueAction(): Boolean =
        MobileWalletProviderProtocol.VALUE_RUNTIME_RELEASE_QUALIFIED &&
            MobileWalletProviderProtocol.APPROVAL_RUNTIME_RELEASE_QUALIFIED &&
            MobileWalletProviderProtocol.WALLET_RUNTIME_RELEASE_QUALIFIED &&
            walletAvailable && !locked && !busy

    fun allowsApprovalAction(): Boolean =
        MobileWalletProviderProtocol.APPROVAL_RUNTIME_RELEASE_QUALIFIED &&
            MobileWalletProviderProtocol.WALLET_RUNTIME_RELEASE_QUALIFIED &&
            walletAvailable && !locked && !busy
}
