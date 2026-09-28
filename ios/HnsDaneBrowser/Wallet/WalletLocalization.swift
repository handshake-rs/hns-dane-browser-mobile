import Foundation

/// Controlled wallet copy is keyed identically on Android and Apple. The
/// canonical English lives in Android's base resources; Wallet.xcstrings is a
/// generated, checked-in projection containing the same copy and translations.
enum WalletCopy {
    static func text(_ key: String) -> String {
        NSLocalizedString(
            key,
            tableName: "Wallet",
            bundle: .main,
            comment: ""
        )
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(
            format: text(key),
            locale: Locale.current,
            arguments: arguments
        )
    }
}
