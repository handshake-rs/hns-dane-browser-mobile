import Foundation
import HnsBrowserRuntime

/// Compiler-resistant clearing for process-local mutable wallet secrets.
/// The C primitive uses volatile stores because Swift's Darwin overlay does
/// not expose `explicit_bzero` consistently across supported Xcode SDKs.
enum WalletSecretBytes {
    static func wipe(_ bytes: inout [UInt8]) {
        bytes.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            guard let baseAddress = buffer.baseAddress else { return }
            hns_wallet_secure_zero(baseAddress, buffer.count)
        }
        bytes.removeAll(keepingCapacity: false)
    }
}

struct NativeWalletStatus: Decodable, Equatable {
    let locked: Bool
    let activeWallet: String?
    let enabledModules: [String]
    let mainnetSettlementEnabled: Bool
}

struct NativeWalletAccount: Decodable, Equatable {
    let accountId: String
    let module: String
    let label: String
    let receiveDisplay: String?
}

final class NativeHnsReadConfiguration {
    let loopbackPort: UInt16
    fileprivate var authorization: [UInt8]

    /// Takes ownership of a caller-controlled copy and wipes the caller's
    /// buffer on every exit. The configuration wipes its retained copy after
    /// its single native composition attempt.
    init?(loopbackPort: UInt16, authorization: inout [UInt8]) {
        guard loopbackPort != 0,
              !authorization.isEmpty,
              authorization.count <= 4_096,
              authorization.first != UInt8(ascii: " "),
              authorization.last != UInt8(ascii: " "),
              authorization.allSatisfy({ (0x20...0x7e).contains($0) }) else {
            WalletSecretBytes.wipe(&authorization)
            return nil
        }
        self.loopbackPort = loopbackPort
        self.authorization = authorization
        WalletSecretBytes.wipe(&authorization)
    }

    fileprivate func consume<T>(
        _ body: (UInt16, UnsafeRawBufferPointer) throws -> T
    ) rethrows -> T {
        defer { WalletSecretBytes.wipe(&authorization) }
        return try authorization.withUnsafeBytes { bytes in
            try body(loopbackPort, bytes)
        }
    }

    deinit {
        WalletSecretBytes.wipe(&authorization)
    }
}

struct NativeHnsReadSnapshot: Decodable, Equatable, Sendable {
    struct Amount: Decodable, Equatable, Sendable {
        let asset: String
        let baseUnits: String

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case asset
            case baseUnits = "base_units"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            asset = try container.decode(String.self, forKey: .asset)
            baseUnits = try container.decode(String.self, forKey: .baseUnits)
            guard asset == "HNS", Self.isCanonicalBaseUnits(baseUnits) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS balance")
            }
        }

        fileprivate static func isCanonicalBaseUnits(_ value: String) -> Bool {
            let maximum = "340282366920938463463374607431768211455"
            guard !value.isEmpty,
                  value.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }),
                  value == "0" || value.first != "0",
                  value.count <= maximum.count else {
                return false
            }
            return value.count < maximum.count || value <= maximum
        }
    }

    struct SignedAmount: Decodable, Equatable, Sendable {
        let negative: Bool
        let magnitude: String

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case negative, magnitude
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            negative = try container.decode(Bool.self, forKey: .negative)
            magnitude = try container.decode(String.self, forKey: .magnitude)
            guard Amount.isCanonicalBaseUnits(magnitude),
                  !negative || magnitude != "0" else {
                throw NativeWalletBridgeError.invalidOutput("invalid transaction amount")
            }
        }
    }

    struct ReceiveTarget: Decodable, Equatable, Sendable {
        let module: String
        let account: [UInt8]
        let display: String
        let derivationIndex: UInt32

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case module, account, display
            case derivationIndex = "derivation_index"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            module = try container.decode(String.self, forKey: .module)
            account = try container.decode([UInt8].self, forKey: .account)
            display = try container.decode(String.self, forKey: .display)
            derivationIndex = try container.decode(UInt32.self, forKey: .derivationIndex)
            guard module == "handshake",
                  account.count == 16,
                  account.contains(where: { $0 != 0 }),
                  !display.isEmpty,
                  display.utf8.count <= 512,
                  display.utf8.allSatisfy({ (0x21...0x7e).contains($0) }) else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS receive target")
            }
        }
    }

    struct Transaction: Decodable, Equatable, Sendable {
        let module: String
        let txid: [UInt8]
        let status: String
        let netAmount: SignedAmount
        let fee: String?
        let blockHeight: UInt64?
        let firstSeenUnix: UInt64?
        let confirmationCount: UInt32

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case module, txid, status, fee
            case netAmount = "net_amount"
            case blockHeight = "block_height"
            case firstSeenUnix = "first_seen_unix"
            case confirmationCount = "confirmation_count"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            module = try container.decode(String.self, forKey: .module)
            txid = try container.decode([UInt8].self, forKey: .txid)
            status = try container.decode(String.self, forKey: .status)
            netAmount = try container.decode(SignedAmount.self, forKey: .netAmount)
            fee = try container.decodeIfPresent(String.self, forKey: .fee)
            blockHeight = try container.decodeIfPresent(UInt64.self, forKey: .blockHeight)
            firstSeenUnix = try container.decodeIfPresent(UInt64.self, forKey: .firstSeenUnix)
            confirmationCount = try container.decode(UInt32.self, forKey: .confirmationCount)
            let statuses: Set<String> = [
                "prepared", "authorized", "broadcast", "mempool", "confirmed", "replaced",
                "conflicted", "reorged", "dropped", "failed",
            ]
            guard module == "handshake",
                  txid.count == 32,
                  txid.contains(where: { $0 != 0 }),
                  statuses.contains(status),
                  fee.map(Amount.isCanonicalBaseUnits) ?? true else {
                throw NativeWalletBridgeError.invalidOutput("invalid HNS transaction summary")
            }
        }
    }

    struct KnownName: Decodable, Equatable, Sendable {
        let name: String
        let nameHash: String
        let proofHeight: UInt64
        let resourceStatus: String
        let ownershipStatus: String
        let registered: Bool?
        let expired: Bool?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case name, nameHash, proofHeight, resourceStatus, ownershipStatus, registered, expired
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            name = try container.decode(String.self, forKey: .name)
            nameHash = try container.decode(String.self, forKey: .nameHash)
            proofHeight = try container.decode(UInt64.self, forKey: .proofHeight)
            resourceStatus = try container.decode(String.self, forKey: .resourceStatus)
            ownershipStatus = try container.decode(String.self, forKey: .ownershipStatus)
            registered = try container.decodeIfPresent(Bool.self, forKey: .registered)
            expired = try container.decodeIfPresent(Bool.self, forKey: .expired)
            let resourceStatuses: Set<String> = [
                "unavailableCanonicalBinding", "noCurrentState", "empty", "canonicalDecoded",
                "canonicalOpaque",
            ]
            let ownershipStatuses: Set<String> = [
                "watchOnlyCanonicalStateDecoderUnavailable", "walletContextUnavailable",
                "noCurrentOwner", "notWalletOwned", "walletOwned", "incomingTransfer",
                "outgoingTransfer",
            ]
            guard !name.isEmpty,
                  name.utf8.count <= 63,
                  name.utf8.allSatisfy({ (0x21...0x7e).contains($0) }),
                  nameHash.count == 64,
                  nameHash.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) || (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0) }),
                  resourceStatuses.contains(resourceStatus),
                  ownershipStatuses.contains(ownershipStatus) else {
                throw NativeWalletBridgeError.invalidOutput("invalid known HNS name")
            }
        }
    }

    struct ModuleStatus: Decodable, Equatable, Sendable {
        let phase: String
        let validatedHeight: UInt64
        let scannedHeight: UInt64
        let targetHeight: UInt64?
        let lastError: String?

        private enum CodingKeys: String, CodingKey, CaseIterable {
            case phase
            case validatedHeight = "validated_height"
            case scannedHeight = "scanned_height"
            case targetHeight = "target_height"
            case lastError = "last_error"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
            phase = try container.decode(String.self, forKey: .phase)
            validatedHeight = try container.decode(UInt64.self, forKey: .validatedHeight)
            scannedHeight = try container.decode(UInt64.self, forKey: .scannedHeight)
            targetHeight = try container.decodeIfPresent(UInt64.self, forKey: .targetHeight)
            lastError = try container.decodeIfPresent(String.self, forKey: .lastError)
            guard phase == "ready",
                  validatedHeight == scannedHeight,
                  targetHeight == validatedHeight,
                  lastError == nil else {
                throw NativeWalletBridgeError.invalidOutput("incoherent HNS synchronization status")
            }
        }
    }

    let balance: Amount
    let receiveTarget: ReceiveTarget
    let transactionHistory: [Transaction]
    let knownNames: [KnownName]
    let moduleStatus: ModuleStatus

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case balance, receiveTarget, transactionHistory, knownNames, moduleStatus
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.strictContainer(keyedBy: CodingKeys.self)
        balance = try container.decode(Amount.self, forKey: .balance)
        receiveTarget = try container.decode(ReceiveTarget.self, forKey: .receiveTarget)
        transactionHistory = try container.decode([Transaction].self, forKey: .transactionHistory)
        knownNames = try container.decode([KnownName].self, forKey: .knownNames)
        moduleStatus = try container.decode(ModuleStatus.self, forKey: .moduleStatus)
        guard transactionHistory.count <= 10_000,
              knownNames.count <= 10_000,
              Set(transactionHistory.map(\.txid)).count == transactionHistory.count,
              Set(knownNames.map(\.name)).count == knownNames.count,
              Set(knownNames.map(\.nameHash)).count == knownNames.count else {
            throw NativeWalletBridgeError.invalidOutput("HNS read snapshot exceeds native bounds")
        }
    }

    static func decode(bundle: [UInt8]) throws -> NativeHnsReadSnapshot {
        let headerLength = 12
        guard bundle.count > headerLength,
              bundle.count <= 4 * 1_024 * 1_024,
              Array(bundle[0..<4]) == Array("HNWR".utf8),
              bundle[4] == 1,
              bundle[5] == 1,
              bundle[6] == 0,
              bundle[7] == 0 else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS read bundle header")
        }
        let payloadLength = bundle[8..<12].reduce(UInt32(0)) { partial, byte in
            (partial << 8) | UInt32(byte)
        }
        guard payloadLength > 0,
              Int(payloadLength) == bundle.count - headerLength else {
            throw NativeWalletBridgeError.invalidOutput("invalid HNS read bundle length")
        }
        return try JSONDecoder().decode(
            NativeHnsReadSnapshot.self,
            from: Data(bundle[headerLength...])
        )
    }
}

private struct NativeWalletAnyCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int?

    init?(stringValue: String) {
        self.stringValue = stringValue
        intValue = nil
    }

    init?(intValue: Int) {
        stringValue = String(intValue)
        self.intValue = intValue
    }
}

private extension Decoder {
    func strictContainer<Key: CodingKey & CaseIterable>(
        keyedBy type: Key.Type
    ) throws -> KeyedDecodingContainer<Key> where Key.AllCases: Collection {
        let expected = Set(type.allCases.map(\.stringValue))
        let dynamic = try container(keyedBy: NativeWalletAnyCodingKey.self)
        let actual = Set(dynamic.allKeys.map(\.stringValue))
        guard actual == expected else {
            throw NativeWalletBridgeError.invalidOutput("native wallet JSON fields are not exact")
        }
        return try container(keyedBy: type)
    }
}

/// Process-local copy used only by the dedicated recovery display. Call
/// `clear()` as soon as the user leaves the screen.
final class WalletRecoverySecret {
    private var bytes: [UInt8]

    init(bytes: [UInt8]) {
        self.bytes = bytes
    }

    func displayText() throws -> String {
        guard let value = String(bytes: bytes, encoding: .utf8) else {
            throw NativeWalletBridgeError.invalidOutput("recovery phrase is not UTF-8")
        }
        return value
    }

    func clear() {
        WalletSecretBytes.wipe(&bytes)
    }

    deinit {
        clear()
    }
}

enum NativeWalletBridgeError: LocalizedError {
    case callFailed(operation: String, code: UInt32, detail: String)
    case invalidOutput(String)
    case closed

    var errorDescription: String? {
        switch self {
        case .callFailed(let operation, let code, let detail):
            "\(operation) failed (\(code)): \(detail)"
        case .invalidOutput(let detail):
            "The native wallet returned invalid output: \(detail)"
        case .closed:
            "The native wallet is closed."
        }
    }
}

/// Opaque Swift owner for one Rust wallet controller. It deliberately exposes
/// only create/restore/open/status/accounts/unlock/lock and recovery display.
final class RustNativeWallet: @unchecked Sendable {
    private let handleLock = NSLock()
    private var handle: HnsBrowserWalletHandle

    private init(handle: HnsBrowserWalletHandle) throws {
        guard handle != 0 else {
            throw NativeWalletBridgeError.invalidOutput("wallet handle is zero")
        }
        self.handle = handle
    }

    static func create(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer,
        network: BrowserHandshakeNetwork,
        birthdayHeight: UInt64
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_create(
                path,
                NativeWalletBridge.slice(databaseKey),
                network.walletNativeValue,
                birthdayHeight,
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet create")
        return try RustNativeWallet(handle: handle)
    }

    static func restore(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer,
        network: BrowserHandshakeNetwork,
        birthdayHeight: UInt64,
        recoveryPhrase: UnsafeRawBufferPointer
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_restore(
                path,
                NativeWalletBridge.slice(databaseKey),
                network.walletNativeValue,
                birthdayHeight,
                NativeWalletBridge.slice(recoveryPhrase),
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet restore")
        return try RustNativeWallet(handle: handle)
    }

    static func open(
        databasePath: String,
        databaseKey: UnsafeRawBufferPointer
    ) throws -> RustNativeWallet {
        var handle: HnsBrowserWalletHandle = 0
        let result = NativeWalletBridge.withUTF8Slice(databasePath) { path in
            hns_browser_wallet_open(
                path,
                NativeWalletBridge.slice(databaseKey),
                &handle
            )
        }
        try NativeWalletBridge.check(result, operation: "wallet open")
        return try RustNativeWallet(handle: handle)
    }

    func status() throws -> NativeWalletStatus {
        try decodeOutput(operation: "wallet status", invoke: hns_browser_wallet_status)
    }

    func accounts() throws -> [NativeWalletAccount] {
        try decodeOutput(operation: "wallet accounts", invoke: hns_browser_wallet_accounts)
    }

    func configureHnsReads(_ configuration: NativeHnsReadConfiguration) throws {
        try configuration.consume { port, authorization in
            try NativeWalletBridge.check(
                hns_browser_wallet_configure_hns_reads(
                    try liveHandle(),
                    port,
                    NativeWalletBridge.slice(authorization)
                ),
                operation: "wallet HNS read configuration"
            )
        }
    }

    func hasHnsReads() throws -> Bool {
        var enabled: UInt8 = 0
        try NativeWalletBridge.check(
            hns_browser_wallet_has_hns_reads(try liveHandle(), &enabled),
            operation: "wallet HNS read availability"
        )
        guard enabled <= 1 else {
            throw NativeWalletBridgeError.invalidOutput("HNS read availability is not boolean")
        }
        return enabled == 1
    }

    func synchronizeHnsReads() throws -> NativeHnsReadSnapshot {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_synchronize_hns_reads(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet HNS synchronization")
        var bundle = try NativeWalletBridge.bytes(copying: output)
        defer { WalletSecretBytes.wipe(&bundle) }
        return try NativeHnsReadSnapshot.decode(bundle: bundle)
    }

    func unlock(databaseKey: UnsafeRawBufferPointer) throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_unlock(
                try liveHandle(),
                NativeWalletBridge.slice(databaseKey)
            ),
            operation: "wallet unlock"
        )
    }

    func lock() throws {
        try NativeWalletBridge.check(
            hns_browser_wallet_lock(try liveHandle()),
            operation: "wallet lock"
        )
    }

    func takeRecoveryPhrase() throws -> WalletRecoverySecret {
        var output = HnsBrowserBuffer()
        let result = hns_browser_wallet_take_recovery_phrase(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: "wallet recovery display")
        return WalletRecoverySecret(bytes: try NativeWalletBridge.bytes(copying: output))
    }

    func close() {
        handleLock.lock()
        let current = handle
        handle = 0
        handleLock.unlock()
        if current != 0 {
            _ = hns_browser_wallet_destroy(current)
        }
    }

    deinit {
        close()
    }

    private func liveHandle() throws -> HnsBrowserWalletHandle {
        handleLock.lock()
        defer { handleLock.unlock() }
        guard handle != 0 else { throw NativeWalletBridgeError.closed }
        return handle
    }

    private func decodeOutput<T: Decodable>(
        operation: String,
        invoke: (HnsBrowserWalletHandle, UnsafeMutablePointer<HnsBrowserBuffer>?) -> HnsBrowserResult
    ) throws -> T {
        var output = HnsBrowserBuffer()
        let result = invoke(try liveHandle(), &output)
        defer { NativeWalletBridge.free(output) }
        try NativeWalletBridge.check(result, operation: operation)
        return try JSONDecoder().decode(T.self, from: NativeWalletBridge.data(copying: output))
    }
}

private enum NativeWalletBridge {
    static func withUTF8Slice<T>(
        _ value: String,
        body: (HnsBrowserSlice) throws -> T
    ) rethrows -> T {
        let bytes = Array(value.utf8)
        return try bytes.withUnsafeBufferPointer { buffer in
            try body(HnsBrowserSlice(ptr: buffer.baseAddress, len: UInt64(buffer.count)))
        }
    }

    static func slice(_ bytes: UnsafeRawBufferPointer) -> HnsBrowserSlice {
        HnsBrowserSlice(
            ptr: bytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
            len: UInt64(bytes.count)
        )
    }

    static func bytes(copying buffer: HnsBrowserBuffer) throws -> [UInt8] {
        guard buffer.len <= UInt64(Int.max) else {
            throw NativeWalletBridgeError.invalidOutput("buffer length is unsupported")
        }
        if buffer.len == 0 {
            guard buffer.ptr == nil, buffer.allocation_id == 0 else {
                throw NativeWalletBridgeError.invalidOutput("empty buffer token is malformed")
            }
            return []
        }
        guard let pointer = buffer.ptr, buffer.allocation_id != 0 else {
            throw NativeWalletBridgeError.invalidOutput("nonempty buffer is malformed")
        }
        return Array(UnsafeBufferPointer(start: pointer, count: Int(buffer.len)))
    }

    static func data(copying buffer: HnsBrowserBuffer) throws -> Data {
        Data(try bytes(copying: buffer))
    }

    static func free(_ buffer: HnsBrowserBuffer) {
        _ = hns_browser_buffer_free(buffer)
    }

    static func check(_ result: HnsBrowserResult, operation: String) throws {
        guard result != HNS_BROWSER_RESULT_OK else { return }
        var errorBuffer = HnsBrowserBuffer()
        let errorResult = hns_browser_last_error(&errorBuffer)
        defer { free(errorBuffer) }
        let detail: String
        if errorResult == HNS_BROWSER_RESULT_OK,
           let data = try? data(copying: errorBuffer),
           let message = String(data: data, encoding: .utf8),
           !message.isEmpty {
            detail = message
        } else {
            detail = "no native error detail"
        }
        throw NativeWalletBridgeError.callFailed(
            operation: operation,
            code: result,
            detail: detail
        )
    }
}

private extension BrowserHandshakeNetwork {
    var walletNativeValue: HnsBrowserNetwork {
        switch self {
        case .mainnet: HNS_BROWSER_NETWORK_MAINNET
        case .testnet: HNS_BROWSER_NETWORK_TESTNET
        case .regtest: HNS_BROWSER_NETWORK_REGTEST
        }
    }
}
