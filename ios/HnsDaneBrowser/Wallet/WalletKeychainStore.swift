import Foundation
import Security

/// Stores one network-scoped 32-byte wallet database key under user presence.
/// The key is create-only: this type never replaces an existing identity.
final class WalletKeychainStore {
    private let service = "com.denuoweb.hnsdane.wallet.database-key.v1"
    private let recoveryService = "com.denuoweb.hnsdane.wallet.recovery-phrase.v1"
    /// The direct HNS coordinator keeps this separate from the encrypted
    /// wallet database. Restoring an older database backup must never restore
    /// an older chain-authority floor along with it.
    private let directHnsFloorService = "com.denuoweb.hnsdane.wallet.direct-hns-floor.v1"
    private let account: String
    let networkID: String
    private let keyBytes = 32
    private let directHnsFloorBytes = 36

    /// Keychain updates are not compare-and-swap operations. Serializing this
    /// small record in-process makes a concurrent wallet screen unable to
    /// replace a newer committed floor with an earlier one. The process-wide
    /// storage lease still prevents a second live controller for the same
    /// database namespace.
    private static let directHnsFloorLock = NSLock()

    init(network: BrowserHandshakeNetwork) {
        networkID = network.rawValue
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

    /// Stores the confirmed recovery phrase under the same device-only user-
    /// presence policy as the database key. This is create-only so a caller
    /// cannot silently replace recovery material for an existing identity.
    func storeRecoveryPhrase(_ phrase: UnsafeRawBufferPointer) throws {
        guard phrase.count > 0, phrase.count <= 256,
              let baseAddress = phrase.baseAddress,
              phrase.contains(where: { $0 != 0 }) else {
            throw WalletProviderError(
                code: "invalidSecret",
                message: "Wallet recovery phrase is invalid"
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
        let value = NSMutableData(bytes: baseAddress, length: phrase.count)
        defer { value.resetBytes(in: NSRange(location: 0, length: value.length)) }
        let status = SecItemAdd([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: recoveryService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecAttrAccessControl: access,
            kSecValueData: value,
        ] as CFDictionary, nil)
        if status == errSecDuplicateItem {
            throw WalletProviderError(
                code: "walletAlreadyExists",
                message: "Wallet recovery material already exists on this device"
            )
        }
        guard status == errSecSuccess else { throw keychainError(status) }
    }

    func hasRecoveryPhrase() throws -> Bool {
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: recoveryService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, nil)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else { throw keychainError(status) }
        return true
    }

    func copyRecoveryPhrase(prompt: String) throws -> [UInt8]? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: recoveryService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecUseOperationPrompt: prompt,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              !data.isEmpty, data.count <= 256 else {
            throw keychainError(status)
        }
        return [UInt8](data)
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
        try deleteRecoveryPhrase()
        let status = SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw keychainError(status)
        }
        try deleteDirectHnsRollbackFloor()
    }

    private func deleteRecoveryPhrase() throws {
        let status = SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: recoveryService,
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

    /// Returns the device-bound floor used when opening the direct HNS
    /// coordinator. A missing record is the exact all-zero initial floor;
    /// malformed or inaccessible Keychain material is not silently treated as
    /// a new wallet.
    func directHnsRollbackFloorForOpen() throws -> [UInt8] {
        Self.directHnsFloorLock.lock()
        defer { Self.directHnsFloorLock.unlock() }
        guard let record = try readDirectHnsRollbackFloorRecord() else {
            return [UInt8](repeating: 0, count: directHnsFloorBytes)
        }
        return record.floor
    }

    /// Persists the interruption marker before a direct coordinator may move
    /// the encrypted wallet's header checkpoint. A replacement controller
    /// therefore opens under the previously committed floor and may only heal
    /// this marker with an equal-or-newer authenticated floor.
    func beginDirectHnsSynchronization() throws {
        Self.directHnsFloorLock.lock()
        defer { Self.directHnsFloorLock.unlock() }
        let record = try readDirectHnsRollbackFloorRecord()
        guard record?.state != .pending else {
            throw WalletProviderError(
                code: "directHnsSynchronizationInterrupted",
                message: "A direct HNS synchronization is awaiting recovery."
            )
        }
        try writeDirectHnsRollbackFloorRecord(
            DirectHnsRollbackFloorRecord(
                state: .pending,
                floor: record?.floor ?? [UInt8](repeating: 0, count: directHnsFloorBytes)
            )
        )
    }

    /// Commits one direct coordinator floor only after the caller has finished
    /// the bounded synchronization. The monotonic comparison is over the
    /// canonical big-endian height followed by the exact chainwork bytes.
    func commitDirectHnsSynchronization(_ floor: [UInt8]) throws {
        Self.directHnsFloorLock.lock()
        defer { Self.directHnsFloorLock.unlock() }
        try requireDirectHnsFloor(floor)
        guard let record = try readDirectHnsRollbackFloorRecord(), record.state == .pending else {
            throw WalletProviderError(
                code: "directHnsSynchronizationNotPrepared",
                message: "Direct HNS synchronization was not prepared."
            )
        }
        guard walletDirectHnsRollbackFloorAtLeast(floor, record.floor) else {
            throw WalletProviderError(
                code: "directHnsRollback",
                message: "The direct HNS rollback floor moved backwards."
            )
        }
        try writeDirectHnsRollbackFloorRecord(
            DirectHnsRollbackFloorRecord(state: .committed, floor: floor)
        )
    }

    /// Stores the direct coordinator's floor after successful installation.
    /// This is also used to create the first committed all-zero/nonzero record
    /// before any network synchronization begins.
    func storeInitialDirectHnsRollbackFloor(_ floor: [UInt8]) throws {
        Self.directHnsFloorLock.lock()
        defer { Self.directHnsFloorLock.unlock() }
        try requireDirectHnsFloor(floor)
        if let record = try readDirectHnsRollbackFloorRecord(),
           !walletDirectHnsRollbackFloorAtLeast(floor, record.floor) {
            throw WalletProviderError(
                code: "directHnsRollback",
                message: "The direct HNS rollback floor moved backwards."
            )
        }
        try writeDirectHnsRollbackFloorRecord(
            DirectHnsRollbackFloorRecord(state: .committed, floor: floor)
        )
    }

    private func deleteDirectHnsRollbackFloor() throws {
        Self.directHnsFloorLock.lock()
        defer { Self.directHnsFloorLock.unlock() }
        let status = SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: directHnsFloorService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ] as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw keychainError(status)
        }
    }

    private func requireDirectHnsFloor(_ floor: [UInt8]) throws {
        guard floor.count == directHnsFloorBytes else {
            throw WalletProviderError(
                code: "invalidDirectHnsFloor",
                message: "The direct HNS rollback floor has an invalid length."
            )
        }
    }

    private func readDirectHnsRollbackFloorRecord() throws -> DirectHnsRollbackFloorRecord? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: directHnsFloorService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw keychainError(status)
        }
        return try DirectHnsRollbackFloorRecord(wireBytes: [UInt8](data))
    }

    private func writeDirectHnsRollbackFloorRecord(
        _ record: DirectHnsRollbackFloorRecord
    ) throws {
        var value = record.wireBytes
        defer { WalletSecretBytes.wipe(&value) }
        let attributes = [kSecValueData: Data(value)] as CFDictionary
        let query = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: directHnsFloorService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
        ] as CFDictionary
        let update = SecItemUpdate(query, attributes)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw keychainError(update) }
        let add = SecItemAdd([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: directHnsFloorService,
            kSecAttrAccount: account,
            kSecUseDataProtectionKeychain: true,
            kSecAttrAccessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecValueData: Data(value),
        ] as CFDictionary, nil)
        guard add == errSecSuccess else { throw keychainError(add) }
    }

    private func keychainError(_ status: OSStatus) -> WalletProviderError {
        WalletProviderError(
            code: "keychain:\(status)",
            message: "Wallet Keychain operation failed"
        )
    }
}

private enum DirectHnsRollbackFloorState: UInt8 {
    case committed = 1
    case pending = 2
}

private struct DirectHnsRollbackFloorRecord {
    private static let version: UInt8 = 1
    private static let floorBytes = 36

    let state: DirectHnsRollbackFloorState
    let floor: [UInt8]

    init(state: DirectHnsRollbackFloorState, floor: [UInt8]) {
        self.state = state
        self.floor = floor
    }

    init(wireBytes: [UInt8]) throws {
        guard wireBytes.count == Self.floorBytes + 2,
              wireBytes[0] == Self.version,
              let state = DirectHnsRollbackFloorState(rawValue: wireBytes[1]) else {
            throw WalletProviderError(
                code: "invalidDirectHnsFloor",
                message: "The direct HNS rollback floor record is invalid."
            )
        }
        self.state = state
        floor = Array(wireBytes.dropFirst(2))
    }

    var wireBytes: [UInt8] {
        [Self.version, state.rawValue] + floor
    }
}

/// Floors are `u32` big-endian height followed by exact chainwork. Keeping
/// this pure makes the ordering rule independently testable without Keychain
/// access and prevents a platform integer conversion from changing it.
func walletDirectHnsRollbackFloorAtLeast(_ candidate: [UInt8], _ floor: [UInt8]) -> Bool {
    guard candidate.count == 36, floor.count == 36 else { return false }
    let candidateHeight = candidate.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    let floorHeight = floor.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
    guard candidateHeight >= floorHeight else { return false }
    guard candidateHeight == floorHeight else { return true }
    for index in 4..<36 where candidate[index] != floor[index] {
        return candidate[index] > floor[index]
    }
    return true
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
