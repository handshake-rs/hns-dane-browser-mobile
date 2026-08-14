import Foundation
import Security

/// Stores one network-scoped 32-byte wallet database key under user presence.
/// The key is create-only: this type never replaces an existing identity.
final class WalletKeychainStore {
    private let service = "com.denuoweb.hnsdane.wallet.database-key.v1"
    private let account: String
    private let keyBytes = 32

    init(network: BrowserHandshakeNetwork) {
        account = "primary.\(network.rawValue)"
    }

    /// Persists the first confirmed wallet key. The borrowed process buffer is
    /// never retained by this type and no existing Keychain item is updated.
    func storeDatabaseKey(_ key: UnsafeRawBufferPointer) throws {
        guard key.count == keyBytes,
              let baseAddress = key.baseAddress,
              key.contains(where: { $0 != 0 }) else {
            throw WalletProviderError(
                code: "invalidSecret",
                message: "Wallet database key must be 32 nonzero bytes"
            )
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

        let value = NSMutableData(bytes: baseAddress, length: key.count)
        defer { value.resetBytes(in: NSRange(location: 0, length: value.length)) }
        let status = SecItemAdd([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecAttrAccessControl: access,
            kSecValueData: value,
        ] as CFDictionary, nil)
        if status == errSecDuplicateItem {
            throw WalletProviderError(
                code: "walletAlreadyExists",
                message: "A wallet database key already exists on this device"
            )
        }
        guard status == errSecSuccess else { throw keychainError(status) }
    }

    func hasDatabaseKey() throws -> Bool {
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, nil)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else { throw keychainError(status) }
        return true
    }

    /// Authenticates user presence and lends a process-local borrowed key view
    /// only for the duration of `body`. Every mutable copy is explicitly wiped.
    func withDatabaseKey<T>(
        prompt: String,
        _ body: (UnsafeRawBufferPointer) throws -> T
    ) throws -> T? {
        guard var key = try copyDatabaseKey(prompt: prompt) else { return nil }
        defer { WalletSecretBytes.wipe(&key) }
        return try key.withUnsafeBytes(body)
    }

    func deleteDatabaseKey() throws {
        let status = SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw keychainError(status)
        }
    }

    /// A Keychain error does not prove that `SecItemDelete` left the item in
    /// place. Confirm absence before classifying confirmed-wallet deletion as
    /// failed; an absent key means encrypted database cleanup may proceed.
    func deleteDatabaseKeyForConfirmedWalletDeletion() throws {
        try deleteWalletDatabaseKeyWithAbsenceVerification(
            delete: { try self.deleteDatabaseKey() },
            keyExists: { try self.hasDatabaseKey() }
        )
    }

    private func copyDatabaseKey(prompt: String) throws -> [UInt8]? {
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
        guard status == errSecSuccess, let data = result as? Data, data.count == keyBytes else {
            throw keychainError(status)
        }
        return [UInt8](data)
    }

    private func keychainError(_ status: OSStatus) -> WalletProviderError {
        WalletProviderError(
            code: "keychain:\(status)",
            message: "Wallet Keychain operation failed"
        )
    }
}

func deleteWalletDatabaseKeyWithAbsenceVerification(
    delete: () throws -> Void,
    keyExists: () throws -> Bool
) throws {
    do {
        try delete()
    } catch {
        let deletionError = error
        let stillExists: Bool
        do {
            stillExists = try keyExists()
        } catch {
            // An unavailable verification is not proof of key destruction.
            throw error
        }
        if stillExists {
            throw deletionError
        }
    }
}
