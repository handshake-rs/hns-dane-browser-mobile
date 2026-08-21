import CoreFoundation
import Foundation

enum WalletAssetV2: String, CaseIterable {
    case hns = "HNS"
    case btc = "BTC"
    case eth = "ETH"
}

enum WalletModuleV2: String, CaseIterable {
    case handshake, bitcoin, ethereum

    var asset: WalletAssetV2 {
        switch self {
        case .handshake: return .hns
        case .bitcoin: return .btc
        case .ethereum: return .eth
        }
    }
}

enum WalletPermissionCapabilityV2: String, CaseIterable {
    case accounts, balance, transactions
    case receiveTarget = "receive_target"
    case send, names
    case nameTransfer = "name_transfer"
    case nameFinalize = "name_finalize"
    case typedIdentitySignature = "typed_identity_signature"
    case nameMarket = "name_market"
    case crossChainMarket = "cross_chain_market"
    case swapSettlement = "swap_settlement"
}

enum WalletApprovalWarningV2: String, CaseIterable {
    case feeEstimateMayChange
    case nameTransferIsIrreversible
    case refundRequiresManualAction
    case settlementCanBeDelayed
}

enum WalletFinalityV2: String {
    case proofOfWorkConfirmations = "proof_of_work_confirmations"
    case ethereumFinalizedCheckpoint = "ethereum_finalized_checkpoint"
}

struct WalletApprovalAmountV2: Equatable {
    let asset: WalletAssetV2
    let baseUnits: String
}

struct WalletHnsNameDisclosureV3: Equatable {
    let name: String
    let nameHash: String
}

enum WalletApprovalSummaryV3: Equatable {
    case permissions(
        capabilities: [WalletPermissionCapabilityV2],
        hnsNames: [WalletHnsNameDisclosureV3]
    )
    case moduleEnablement(module: WalletModuleV2, action: String)
    case send(
        amount: WalletApprovalAmountV2,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        chain: WalletModuleV2,
        finality: WalletFinalityV2,
        warnings: [WalletApprovalWarningV2]
    )
    case nameTransfer(
        name: String,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case nameFinalize(
        name: String,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case typedSignature(messageType: String, messageDigest: String)
    case nameMarketOffer(
        action: String,
        name: String,
        listingID: String?,
        price: WalletApprovalAmountV2,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case nameMarketPurchase(
        name: String,
        listingID: String,
        payment: WalletApprovalAmountV2,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case directOffer(
        action: String,
        directOfferID: String?,
        offered: WalletApprovalAmountV2,
        received: WalletApprovalAmountV2,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case directOfferTake(
        directOfferID: String,
        swapSessionID: String,
        offered: WalletApprovalAmountV2,
        received: WalletApprovalAmountV2,
        refundTimeoutUnixMs: UInt64,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case swapRedeem(
        swapSessionID: String,
        amount: WalletApprovalAmountV2,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        finality: WalletFinalityV2,
        warnings: [WalletApprovalWarningV2]
    )
    case swapRefund(
        swapSessionID: String,
        amount: WalletApprovalAmountV2,
        recipient: String,
        maximumFee: WalletApprovalAmountV2,
        refundAvailableAtUnixMs: UInt64,
        warnings: [WalletApprovalWarningV2]
    )

    var kind: String {
        switch self {
        case .permissions: return "permissions"
        case .moduleEnablement: return "moduleEnablement"
        case .send: return "send"
        case .nameTransfer: return "nameTransfer"
        case .nameFinalize: return "nameFinalize"
        case .typedSignature: return "typedSignature"
        case .nameMarketOffer: return "nameMarketOffer"
        case .nameMarketPurchase: return "nameMarketPurchase"
        case .directOffer: return "directOffer"
        case .directOfferTake: return "directOfferTake"
        case .swapRedeem: return "swapRedeem"
        case .swapRefund: return "swapRefund"
        }
    }
}

struct WalletApprovalPromptV3: Equatable {
    let schemaVersion: Int
    let approvalID: String
    let method: String
    let origin: String
    let expiresAtUnixMs: UInt64
    let summary: WalletApprovalSummaryV3
}

struct WalletApprovalDisplayV3: Equatable {
    struct Row: Equatable {
        let label: String
        let value: String
    }

    let title: String
    let rows: [Row]
}

/// Browser-owned approval-schema-v3 public projection of the private ABI-v2 approval union.
enum WalletApprovalProjectionV3 {
    static let maximumLifetimeMs: UInt64 = 90_000
    static let maximumApprovalBytes = 16 * 1024
    private static let maximumPublicStringBytes = 4_096
    private static let maximumHnsNameDisclosures = 64
    private static let maximumU128 = "340282366920938463463374607431768211455"
    private static let reservedHnsNames: Set<String> = [
        "example", "invalid", "local", "localhost", "test",
    ]

    static func validatePrompt(
        _ candidate: Any,
        expectedOrigin: String,
        expectedRequest: WalletProviderRequest,
        nowUnixMs: UInt64
    ) throws -> WalletApprovalPromptV3 {
        let value = try record(candidate)
        guard JSONSerialization.isValidJSONObject(value),
              let encoded = try? JSONSerialization.data(withJSONObject: value) else {
            throw invalidApproval()
        }
        guard !encoded.isEmpty, encoded.count <= maximumApprovalBytes else {
            throw WalletProviderError(
                code: "approvalTooLarge",
                message: "Native wallet approval prompt exceeded its byte limit"
            )
        }
        try requireExactFields(
            value,
            ["schemaVersion", "approvalId", "method", "origin", "expiresAtUnixMs", "summary"]
        )
        guard try unsignedInteger(value["schemaVersion"])
              == UInt64(WalletProviderProtocolV1.approvalSchemaVersion),
              let approvalID = value["approvalId"] as? String,
              isCanonicalApprovalID(approvalID),
              let method = value["method"] as? String,
              WalletProviderProtocolV1.methods.contains(method),
              method == expectedRequest.method,
              let rawOrigin = value["origin"] as? String else {
            throw invalidApproval()
        }
        let origin = try canonicalHTTPSOrigin(rawOrigin)
        guard origin == expectedOrigin else { throw invalidApproval() }
        let expiresAtUnixMs = try unsignedInteger(value["expiresAtUnixMs"])
        let maximumExpiry = nowUnixMs > WalletProviderProtocolV1.maximumSafeJSONIntegerUInt64 - maximumLifetimeMs
            ? WalletProviderProtocolV1.maximumSafeJSONIntegerUInt64
            : nowUnixMs + maximumLifetimeMs
        guard expiresAtUnixMs > nowUnixMs, expiresAtUnixMs <= maximumExpiry else {
            throw invalidApproval()
        }
        let summary = try summary(
            value["summary"],
            method: method,
            expectedRequest: expectedRequest
        )
        return WalletApprovalPromptV3(
            schemaVersion: WalletProviderProtocolV1.approvalSchemaVersion,
            approvalID: approvalID,
            method: method,
            origin: origin,
            expiresAtUnixMs: expiresAtUnixMs,
            summary: summary
        )
    }

    static func display(_ prompt: WalletApprovalPromptV3) -> WalletApprovalDisplayV3 {
        var rows: [WalletApprovalDisplayV3.Row] = []
        func add(_ label: String, _ value: String) {
            rows.append(.init(label: label, value: value))
        }
        func addAmount(_ label: String, _ amount: WalletApprovalAmountV2) {
            add(label, "\(amount.baseUnits) \(amount.asset.rawValue)")
        }
        func addWarnings(_ warnings: [WalletApprovalWarningV2]) {
            if !warnings.isEmpty { add("Warnings", warnings.map(\.rawValue).joined(separator: ", ")) }
        }

        let title: String
        switch prompt.summary {
        case let .permissions(capabilities, hnsNames):
            title = "Approve wallet permissions"
            add("Capabilities", capabilities.map(\.rawValue).joined(separator: ", "))
            for (index, disclosure) in hnsNames.enumerated() {
                add("HNS name \(index + 1)", disclosure.name)
                add("HNS name hash \(index + 1)", disclosure.nameHash)
            }
        case let .moduleEnablement(module, action):
            title = action == "enable" ? "Enable wallet module" : "Disable wallet module"
            add("Module", module.rawValue)
            add("Action", action)
        case let .send(amount, recipient, maximumFee, chain, finality, warnings):
            title = "Approve asset send"
            addAmount("Amount", amount)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            add("Chain", chain.rawValue)
            add("Finality", finality.rawValue)
            addWarnings(warnings)
        case let .nameTransfer(name, recipient, maximumFee, warnings):
            title = "Approve name transfer"
            add("Name", name)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .nameFinalize(name, recipient, maximumFee, warnings):
            title = "Approve name finalization"
            add("Name", name)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .typedSignature(messageType, messageDigest):
            title = "Approve typed signature"
            add("Message type", messageType)
            add("Message digest", messageDigest)
        case let .nameMarketOffer(action, name, listingID, price, maximumFee, warnings):
            title = "Approve name offer action"
            add("Action", action)
            add("Name", name)
            if let listingID { add("Listing ID", listingID) }
            addAmount("Price", price)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .nameMarketPurchase(name, listingID, payment, recipient, maximumFee, warnings):
            title = "Approve name purchase"
            add("Name", name)
            add("Listing ID", listingID)
            addAmount("Payment", payment)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .directOffer(action, directOfferID, offered, received, maximumFee, warnings):
            title = "Approve direct offer"
            add("Action", action)
            if let directOfferID { add("Direct offer ID", directOfferID) }
            addAmount("Offered", offered)
            addAmount("Received", received)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .directOfferTake(directOfferID, swapSessionID, offered, received, refundTimeout, maximumFee, warnings):
            title = "Approve direct-offer take"
            add("Direct offer ID", directOfferID)
            add("Swap session ID", swapSessionID)
            addAmount("Offered", offered)
            addAmount("Received", received)
            add("Refund timeout", String(refundTimeout))
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .swapRedeem(swapSessionID, amount, recipient, maximumFee, finality, warnings):
            title = "Approve swap redemption"
            add("Swap session ID", swapSessionID)
            addAmount("Amount", amount)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            add("Finality", finality.rawValue)
            addWarnings(warnings)
        case let .swapRefund(swapSessionID, amount, recipient, maximumFee, availableAt, warnings):
            title = "Approve swap refund"
            add("Swap session ID", swapSessionID)
            addAmount("Amount", amount)
            add("Recipient", recipient)
            addAmount("Maximum fee", maximumFee)
            add("Refund available at", String(availableAt))
            addWarnings(warnings)
        }
        return WalletApprovalDisplayV3(title: title, rows: rows)
    }

    private static func summary(
        _ candidate: Any?,
        method: String,
        expectedRequest: WalletProviderRequest
    ) throws -> WalletApprovalSummaryV3 {
        let value = try record(candidate)
        guard let kind = value["kind"] as? String else { throw invalidApproval() }
        switch kind {
        case "permissions":
            try requireMethod(method, ["wallet_requestPermissions", "hns_requestAccounts"])
            try requireExactFields(value, ["kind", "capabilities", "hnsNames"])
            let capabilities = try canonicalEnums(
                value["capabilities"],
                values: WalletPermissionCapabilityV2.allCases,
                allowEmpty: false
            )
            let hnsNames = try hnsNameDisclosures(value["hnsNames"])
            try requireRequestedCapabilities(capabilities, request: expectedRequest)
            guard capabilities.contains(.names) || hnsNames.isEmpty,
                  method != "hns_requestAccounts" || hnsNames.isEmpty else {
                throw invalidApproval()
            }
            return .permissions(capabilities: capabilities, hnsNames: hnsNames)
        case "moduleEnablement":
            try requireMethod(method, ["wallet_enableModule", "wallet_disableModule"])
            try requireExactFields(value, ["kind", "module", "action"])
            let module = try module(value["module"])
            let action = try oneOf(value["action"], ["enable", "disable"])
            let expectedAction = method == "wallet_enableModule" ? "enable" : "disable"
            guard action == expectedAction,
                  (expectedRequest.params as? [String: Any])?["module"] as? String == module.rawValue else {
                throw invalidApproval()
            }
            return .moduleEnablement(module: module, action: action)
        case "send":
            try requireMethod(method, ["hns_send", "asset_send"])
            try requireExactFields(
                value,
                ["kind", "amount", "recipient", "maximumFee", "chain", "finality", "warnings"]
            )
            let amount = try amount(value["amount"], allowZero: false)
            let maximumFee = try Self.amount(value["maximumFee"], allowZero: true)
            let chain = try module(value["chain"])
            let expectedChain = method == "hns_send"
                ? WalletModuleV2.handshake.rawValue
                : (expectedRequest.params as? [String: Any])?["module"] as? String
            let finality = try finality(value["finality"])
            guard chain.rawValue == expectedChain,
                  amount.asset == chain.asset,
                  maximumFee.asset == amount.asset,
                  finality == finalityForAsset(amount.asset) else { throw invalidApproval() }
            return .send(
                amount: amount,
                recipient: try publicString(value["recipient"]),
                maximumFee: maximumFee,
                chain: chain,
                finality: finality,
                warnings: try warnings(value["warnings"])
            )
        case "nameTransfer", "nameFinalize":
            try requireMethod(
                method,
                kind == "nameTransfer" ? ["hns_transferName"] : ["hns_finalizeName"]
            )
            try requireExactFields(value, ["kind", "name", "recipient", "maximumFee", "warnings"])
            let name = try publicString(value["name"])
            let recipient = try publicString(value["recipient"])
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            let warnings = try warnings(value["warnings"])
            guard maximumFee.asset == .hns else { throw invalidApproval() }
            return kind == "nameTransfer"
                ? .nameTransfer(name: name, recipient: recipient, maximumFee: maximumFee, warnings: warnings)
                : .nameFinalize(name: name, recipient: recipient, maximumFee: maximumFee, warnings: warnings)
        case "typedSignature":
            try requireMethod(method, ["hns_signTypedMessage"])
            try requireExactFields(value, ["kind", "messageType", "messageDigest"])
            return .typedSignature(
                messageType: try publicString(value["messageType"]),
                messageDigest: try publicString(value["messageDigest"])
            )
        case "nameMarketOffer":
            try requireMethod(
                method,
                ["nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer", "nameMarket_recoverName"]
            )
            try requireExactFields(
                value,
                ["kind", "action", "name", "listingId", "price", "maximumFee", "warnings"]
            )
            let action = try oneOf(value["action"], ["create", "cancel", "recover"])
            let actionByMethod = [
                "nameMarket_createFixedPriceOffer": "create",
                "nameMarket_cancelOffer": "cancel",
                "nameMarket_recoverName": "recover",
            ]
            let price = try amount(value["price"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard actionByMethod[method] == action,
                  price.asset == .hns, maximumFee.asset == .hns else { throw invalidApproval() }
            return .nameMarketOffer(
                action: action,
                name: try publicString(value["name"]),
                listingID: try optionalPublicString(value["listingId"]),
                price: price,
                maximumFee: maximumFee,
                warnings: try warnings(value["warnings"])
            )
        case "nameMarketPurchase":
            try requireMethod(method, ["nameMarket_acceptOffer", "nameMarket_finalizePurchase"])
            try requireExactFields(
                value,
                ["kind", "name", "listingId", "payment", "recipient", "maximumFee", "warnings"]
            )
            let payment = try amount(value["payment"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard payment.asset == .hns, maximumFee.asset == .hns else { throw invalidApproval() }
            return .nameMarketPurchase(
                name: try publicString(value["name"]),
                listingID: try publicString(value["listingId"]),
                payment: payment,
                recipient: try publicString(value["recipient"]),
                maximumFee: maximumFee,
                warnings: try warnings(value["warnings"])
            )
        case "directOffer":
            try requireMethod(method, ["swap_publishDirectOffer", "swap_cancelDirectOffer"])
            try requireExactFields(
                value,
                [
                    "kind", "action", "directOfferId", "offered", "received",
                    "maximumFee", "warnings",
                ]
            )
            let action = try oneOf(value["action"], ["publish", "cancel"])
            let offered = try amount(value["offered"], allowZero: false)
            let received = try amount(value["received"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard action == (method == "swap_publishDirectOffer" ? "publish" : "cancel"),
                  offered.asset != received.asset,
                  maximumFee.asset == offered.asset else { throw invalidApproval() }
            return .directOffer(
                action: action,
                directOfferID: try optionalPublicString(value["directOfferId"]),
                offered: offered,
                received: received,
                maximumFee: maximumFee,
                warnings: try warnings(value["warnings"])
            )
        case "directOfferTake":
            try requireMethod(method, ["swap_takeDirectOffer", "swap_acceptDirectOffer"])
            try requireExactFields(
                value,
                [
                    "kind", "directOfferId", "swapSessionId", "offered", "received",
                    "refundTimeoutUnixMs", "maximumFee", "warnings",
                ]
            )
            let offered = try amount(value["offered"], allowZero: false)
            let received = try amount(value["received"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard offered.asset != received.asset,
                  maximumFee.asset == offered.asset else { throw invalidApproval() }
            return .directOfferTake(
                directOfferID: try publicString(value["directOfferId"]),
                swapSessionID: try publicString(value["swapSessionId"]),
                offered: offered,
                received: received,
                refundTimeoutUnixMs: try positiveTime(value["refundTimeoutUnixMs"]),
                maximumFee: maximumFee,
                warnings: try warnings(value["warnings"])
            )
        case "swapRedeem":
            try requireMethod(method, ["swap_redeem"])
            try requireExactFields(
                value,
                ["kind", "swapSessionId", "amount", "recipient", "maximumFee", "finality", "warnings"]
            )
            let amount = try amount(value["amount"], allowZero: false)
            let maximumFee = try Self.amount(value["maximumFee"], allowZero: true)
            let finality = try finality(value["finality"])
            guard maximumFee.asset == amount.asset,
                  finality == finalityForAsset(amount.asset) else { throw invalidApproval() }
            return .swapRedeem(
                swapSessionID: try publicString(value["swapSessionId"]),
                amount: amount,
                recipient: try publicString(value["recipient"]),
                maximumFee: maximumFee,
                finality: finality,
                warnings: try warnings(value["warnings"])
            )
        case "swapRefund":
            try requireMethod(method, ["swap_refund"])
            try requireExactFields(
                value,
                [
                    "kind", "swapSessionId", "amount", "recipient", "maximumFee",
                    "refundAvailableAtUnixMs", "warnings",
                ]
            )
            let amount = try amount(value["amount"], allowZero: false)
            let maximumFee = try Self.amount(value["maximumFee"], allowZero: true)
            guard maximumFee.asset == amount.asset else { throw invalidApproval() }
            return .swapRefund(
                swapSessionID: try publicString(value["swapSessionId"]),
                amount: amount,
                recipient: try publicString(value["recipient"]),
                maximumFee: maximumFee,
                refundAvailableAtUnixMs: try positiveTime(value["refundAvailableAtUnixMs"]),
                warnings: try warnings(value["warnings"])
            )
        default:
            throw invalidApproval()
        }
    }

    private static func amount(_ candidate: Any?, allowZero: Bool) throws -> WalletApprovalAmountV2 {
        let value = try record(candidate)
        try requireExactFields(value, ["asset", "baseUnits"])
        let asset = try asset(value["asset"])
        guard let baseUnits = value["baseUnits"] as? String,
              isCanonicalBaseUnits(baseUnits),
              baseUnits.count < maximumU128.count
                || (baseUnits.count == maximumU128.count && baseUnits <= maximumU128),
              allowZero || baseUnits != "0" else { throw invalidApproval() }
        return WalletApprovalAmountV2(asset: asset, baseUnits: baseUnits)
    }

    private static func isCanonicalApprovalID(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard bytes.count == 22,
              let final = bytes.last,
              [0x41, 0x51, 0x67, 0x77].contains(final),
              bytes.contains(where: { $0 != 0x41 }) else { return false }
        return bytes.allSatisfy { byte in
            (0x30...0x39).contains(byte)
                || (0x41...0x5a).contains(byte)
                || (0x61...0x7a).contains(byte)
                || byte == 0x5f
                || byte == 0x2d
        }
    }

    private static func isCanonicalBaseUnits(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard !bytes.isEmpty, bytes.count <= 39,
              bytes.allSatisfy({ (0x30...0x39).contains($0) }),
              bytes.count == 1 || bytes.first != 0x30 else { return false }
        return true
    }

    private static func requireRequestedCapabilities(
        _ capabilities: [WalletPermissionCapabilityV2],
        request: WalletProviderRequest
    ) throws {
        if request.method == "hns_requestAccounts" {
            guard capabilities == [.accounts] else { throw invalidApproval() }
            return
        }
        guard !capabilities.contains(.accounts) else { throw invalidApproval() }
        guard let params = request.params as? [String: Any] else { throw invalidApproval() }
        let hasCapabilities = params.keys.contains("capabilities")
        let hasScopes = params.keys.contains("scopes")
        guard hasCapabilities != hasScopes else { throw invalidApproval() }
        let requested = try canonicalEnums(
            params[hasCapabilities ? "capabilities" : "scopes"],
            values: WalletPermissionCapabilityV2.allCases,
            allowEmpty: false,
            requireCanonicalInput: false
        )
        let canonical = WalletPermissionCapabilityV2.allCases.filter(requested.contains)
        guard capabilities == canonical else { throw invalidApproval() }
    }

    private static func hnsNameDisclosures(_ candidate: Any?) throws -> [WalletHnsNameDisclosureV3] {
        guard let rawValues = candidate as? [Any],
              rawValues.count <= maximumHnsNameDisclosures else {
            throw invalidApproval()
        }
        var disclosures: [WalletHnsNameDisclosureV3] = []
        var names = Set<String>()
        var hashes = Set<String>()
        for rawValue in rawValues {
            let value = try record(rawValue)
            try requireExactFields(value, ["name", "nameHash"])
            guard let name = value["name"] as? String,
                  let nameHash = value["nameHash"] as? String,
                  isCanonicalHnsName(name),
                  isLowerHex256(nameHash),
                  sha3_256Hex(Array(name.utf8)) == nameHash,
                  names.insert(name).inserted,
                  hashes.insert(nameHash).inserted else {
                throw invalidApproval()
            }
            let disclosure = WalletHnsNameDisclosureV3(name: name, nameHash: nameHash)
            if let previous = disclosures.last {
                guard previous.name < disclosure.name
                        || previous.name == disclosure.name
                        && previous.nameHash < disclosure.nameHash else {
                    throw invalidApproval()
                }
            }
            disclosures.append(disclosure)
        }
        return disclosures
    }

    private static func isCanonicalHnsName(_ name: String) -> Bool {
        let bytes = Array(name.utf8)
        guard (1...63).contains(bytes.count), !reservedHnsNames.contains(name) else {
            return false
        }
        return bytes.enumerated().allSatisfy { index, byte in
            (0x30...0x39).contains(byte) || (0x61...0x7a).contains(byte)
                || (byte == 0x2d || byte == 0x5f)
                && index != 0 && index + 1 != bytes.count
        }
    }

    private static func isLowerHex256(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        return bytes.count == 64 && bytes.allSatisfy {
            (0x30...0x39).contains($0) || (0x61...0x66).contains($0)
        }
    }

    private static func sha3_256Hex(_ input: [UInt8]) -> String {
        let alphabet = Array("0123456789abcdef".utf8)
        var output: [UInt8] = []
        output.reserveCapacity(64)
        for byte in sha3_256(input) {
            output.append(alphabet[Int(byte >> 4)])
            output.append(alphabet[Int(byte & 0x0f)])
        }
        return String(decoding: output, as: UTF8.self)
    }

    private static func sha3_256(_ input: [UInt8]) -> [UInt8] {
        let rateBytes = 136
        var message = input
        message.append(0x06)
        while message.count % rateBytes != rateBytes - 1 {
            message.append(0)
        }
        message.append(0x80)

        var state = [UInt64](repeating: 0, count: 25)
        for blockStart in stride(from: 0, to: message.count, by: rateBytes) {
            for lane in 0..<(rateBytes / 8) {
                var value: UInt64 = 0
                for byteIndex in 0..<8 {
                    value |= UInt64(message[blockStart + lane * 8 + byteIndex])
                        << UInt64(byteIndex * 8)
                }
                state[lane] ^= value
            }
            keccakF1600(&state)
        }

        var digest: [UInt8] = []
        digest.reserveCapacity(32)
        for lane in 0..<4 {
            for byteIndex in 0..<8 {
                digest.append(UInt8(truncatingIfNeeded: state[lane] >> UInt64(byteIndex * 8)))
            }
        }
        return digest
    }

    private static func keccakF1600(_ state: inout [UInt64]) {
        let rotationOffsets = [
            0, 1, 62, 28, 27,
            36, 44, 6, 55, 20,
            3, 10, 43, 25, 39,
            41, 45, 15, 21, 8,
            18, 2, 61, 56, 14,
        ]
        let roundConstants: [UInt64] = [
            0x0000000000000001, 0x0000000000008082,
            0x800000000000808a, 0x8000000080008000,
            0x000000000000808b, 0x0000000080000001,
            0x8000000080008081, 0x8000000000008009,
            0x000000000000008a, 0x0000000000000088,
            0x0000000080008009, 0x000000008000000a,
            0x000000008000808b, 0x800000000000008b,
            0x8000000000008089, 0x8000000000008003,
            0x8000000000008002, 0x8000000000000080,
            0x000000000000800a, 0x800000008000000a,
            0x8000000080008081, 0x8000000000008080,
            0x0000000080000001, 0x8000000080008008,
        ]
        for roundConstant in roundConstants {
            var columns = [UInt64](repeating: 0, count: 5)
            for x in 0..<5 {
                columns[x] = state[x] ^ state[x + 5] ^ state[x + 10]
                    ^ state[x + 15] ^ state[x + 20]
            }
            var deltas = [UInt64](repeating: 0, count: 5)
            for x in 0..<5 {
                deltas[x] = columns[(x + 4) % 5] ^ rotateLeft(columns[(x + 1) % 5], by: 1)
            }
            for y in 0..<5 {
                for x in 0..<5 {
                    state[x + 5 * y] ^= deltas[x]
                }
            }

            var rotated = [UInt64](repeating: 0, count: 25)
            for y in 0..<5 {
                for x in 0..<5 {
                    rotated[y + 5 * ((2 * x + 3 * y) % 5)] = rotateLeft(
                        state[x + 5 * y],
                        by: rotationOffsets[x + 5 * y]
                    )
                }
            }
            for y in 0..<5 {
                for x in 0..<5 {
                    state[x + 5 * y] = rotated[x + 5 * y]
                        ^ (~rotated[(x + 1) % 5 + 5 * y]
                            & rotated[(x + 2) % 5 + 5 * y])
                }
            }
            state[0] ^= roundConstant
        }
    }

    private static func rotateLeft(_ value: UInt64, by shift: Int) -> UInt64 {
        guard shift != 0 else { return value }
        return (value << shift) | (value >> (64 - shift))
    }

    private static func warnings(_ candidate: Any?) throws -> [WalletApprovalWarningV2] {
        try canonicalEnums(candidate, values: WalletApprovalWarningV2.allCases, allowEmpty: true)
    }

    private static func canonicalEnums<Value: RawRepresentable & Equatable>(
        _ candidate: Any?,
        values: [Value],
        allowEmpty: Bool,
        requireCanonicalInput: Bool = true
    ) throws -> [Value] where Value.RawValue == String {
        guard let rawValues = candidate as? [Any],
              allowEmpty || !rawValues.isEmpty,
              rawValues.count <= values.count else { throw invalidApproval() }
        let strings = try rawValues.map { value -> String in
            guard let value = value as? String else { throw invalidApproval() }
            return value
        }
        guard Set(strings).count == strings.count else { throw invalidApproval() }
        let parsed = try strings.map { string -> Value in
            guard let value = values.first(where: { $0.rawValue == string }) else {
                throw invalidApproval()
            }
            return value
        }
        let canonical = values.filter(parsed.contains)
        if requireCanonicalInput, parsed != canonical { throw invalidApproval() }
        return parsed
    }

    private static func asset(_ candidate: Any?) throws -> WalletAssetV2 {
        guard let value = candidate as? String, let asset = WalletAssetV2(rawValue: value) else {
            throw invalidApproval()
        }
        return asset
    }

    private static func module(_ candidate: Any?) throws -> WalletModuleV2 {
        guard let value = candidate as? String, let module = WalletModuleV2(rawValue: value) else {
            throw invalidApproval()
        }
        return module
    }

    private static func finality(_ candidate: Any?) throws -> WalletFinalityV2 {
        guard let value = candidate as? String, let finality = WalletFinalityV2(rawValue: value) else {
            throw invalidApproval()
        }
        return finality
    }

    private static func finalityForAsset(_ asset: WalletAssetV2) -> WalletFinalityV2 {
        asset == .eth ? .ethereumFinalizedCheckpoint : .proofOfWorkConfirmations
    }

    private static func publicString(_ candidate: Any?) throws -> String {
        guard let value = candidate as? String, !value.isEmpty,
              value.utf8.count <= maximumPublicStringBytes,
              value.utf8.allSatisfy({ (0x20...0x7e).contains($0) }) else {
            throw invalidApproval()
        }
        return value
    }

    private static func canonicalHTTPSOrigin(_ candidate: String) throws -> String {
        guard !candidate.isEmpty, candidate.utf8.count <= 512,
              candidate.utf8.allSatisfy({ $0 <= 0x7f }),
              let components = URLComponents(string: candidate),
              components.scheme == "https",
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.percentEncodedPath.isEmpty,
              let rawHost = components.host, !rawHost.isEmpty else {
            throw invalidApproval()
        }
        let host = rawHost.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        guard !host.isEmpty else { throw invalidApproval() }
        let serializedHost = host.contains(":") ? "[\(host)]" : host
        let canonical = components.port == nil || components.port == 443
            ? "https://\(serializedHost)"
            : "https://\(serializedHost):\(components.port!)"
        guard candidate == canonical else { throw invalidApproval() }
        return candidate
    }

    private static func optionalPublicString(_ candidate: Any?) throws -> String? {
        candidate is NSNull ? nil : try publicString(candidate)
    }

    private static func positiveTime(_ candidate: Any?) throws -> UInt64 {
        let value = try unsignedInteger(candidate)
        guard value > 0 else { throw invalidApproval() }
        return value
    }

    private static func unsignedInteger(_ candidate: Any?) throws -> UInt64 {
        guard let number = candidate as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue >= 0,
              number.doubleValue <= WalletProviderProtocolV1.maximumSafeJSONInteger,
              number.doubleValue == floor(number.doubleValue) else { throw invalidApproval() }
        return number.uint64Value
    }

    private static func oneOf(_ candidate: Any?, _ values: Set<String>) throws -> String {
        guard let candidate = candidate as? String, values.contains(candidate) else {
            throw invalidApproval()
        }
        return candidate
    }

    private static func requireMethod(_ method: String, _ methods: Set<String>) throws {
        guard methods.contains(method) else { throw invalidApproval() }
    }

    private static func requireExactFields(_ value: [String: Any], _ fields: Set<String>) throws {
        guard Set(value.keys) == fields else { throw invalidApproval() }
    }

    private static func record(_ candidate: Any?) throws -> [String: Any] {
        guard let value = candidate as? [String: Any] else { throw invalidApproval() }
        return value
    }

    private static func invalidApproval() -> WalletProviderError {
        WalletProviderError(code: "invalidApproval", message: "Native wallet approval prompt is invalid")
    }
}
