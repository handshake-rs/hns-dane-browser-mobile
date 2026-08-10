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
final class RustNativeWallet {
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
