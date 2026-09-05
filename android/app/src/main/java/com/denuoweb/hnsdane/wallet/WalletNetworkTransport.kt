package com.denuoweb.hnsdane.wallet

/** The transport positively identified on the active default network path. */
internal enum class WalletNetworkTransport {
    Wifi,
    Cellular,
    Other,
}

internal fun walletCellularDataWarningVisible(
    walletUnlocked: Boolean,
    transport: WalletNetworkTransport,
): Boolean = walletUnlocked && transport == WalletNetworkTransport.Cellular
