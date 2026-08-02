import Foundation
import Security

/// Stores only a 32-byte wallet database key under user presence; WebKit never receives it.
final class WalletKeychainStore {
    private let service = "com.denuoweb.hnsdane.wallet.database-key.v1"
    private let account = "primary"

    func storeDatabaseKey(_ value: Data) throws {
        guard value.count == 32 else {
            throw WalletProviderError(code: "invalidSecret", message: "Wallet database key must be 32 bytes")
        }
        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            [.userPresence],
            &accessError
        ) else {
            if let error = accessError?.takeRetainedValue() { throw error }
            throw WalletProviderError(
                code: "keychainUnavailable",
                message: "Keychain access control is unavailable"
            )
        }
        let identity: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ]
        let updateStatus = SecItemUpdate(
            identity as CFDictionary,
            [kSecValueData: value] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw keychainError(updateStatus) }

        var addition = identity
        addition[kSecAttrAccessControl] = access
        addition[kSecValueData] = value
        let status = SecItemAdd(addition as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let retryStatus = SecItemUpdate(
                identity as CFDictionary,
                [kSecValueData: value] as CFDictionary
            )
            guard retryStatus == errSecSuccess else { throw keychainError(retryStatus) }
            return
        }
        guard status == errSecSuccess else { throw keychainError(status) }
    }

    func loadDatabaseKey(prompt: String) throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecUseOperationPrompt: prompt,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, data.count == 32 else {
            throw keychainError(status)
        }
        return data
    }

    func deleteDatabaseKey() {
        SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ] as CFDictionary)
    }

    private func keychainError(_ status: OSStatus) -> WalletProviderError {
        WalletProviderError(
            code: "keychain:\(status)",
            message: "Wallet Keychain operation failed"
        )
    }
}
