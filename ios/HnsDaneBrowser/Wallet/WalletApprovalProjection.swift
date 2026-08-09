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

enum WalletApprovalSummaryV2: Equatable {
    case permissions(capabilities: [WalletPermissionCapabilityV2])
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
    case marketIntent(
        action: String,
        marketIntentID: String?,
        offered: WalletApprovalAmountV2,
        requestedAsset: WalletAssetV2,
        priceRound: String,
        maximumFee: WalletApprovalAmountV2,
        warnings: [WalletApprovalWarningV2]
    )
    case fillAcceptance(
        marketIntentID: String,
        fillID: String,
        offered: WalletApprovalAmountV2,
        expected: WalletApprovalAmountV2,
        priceRound: String,
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
        case .marketIntent: return "marketIntent"
        case .fillAcceptance: return "fillAcceptance"
        case .swapRedeem: return "swapRedeem"
        case .swapRefund: return "swapRefund"
        }
    }
}

struct WalletApprovalPromptV2: Equatable {
    let schemaVersion: Int
    let approvalID: String
    let method: String
    let origin: String
    let expiresAtUnixMs: UInt64
    let summary: WalletApprovalSummaryV2
}

struct WalletApprovalDisplayV2: Equatable {
    struct Row: Equatable {
        let label: String
        let value: String
    }

    let title: String
    let rows: [Row]
}

enum WalletApprovalProjectionV2 {
    static let maximumLifetimeMs: UInt64 = 90_000
    static let maximumApprovalBytes = 16 * 1024
    private static let maximumPublicStringBytes = 4_096
    private static let maximumU128 = "340282366920938463463374607431768211455"

    static func validatePrompt(
        _ candidate: Any,
        expectedOrigin: String,
        expectedRequest: WalletProviderRequest,
        nowUnixMs: UInt64
    ) throws -> WalletApprovalPromptV2 {
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
        return WalletApprovalPromptV2(
            schemaVersion: WalletProviderProtocolV1.approvalSchemaVersion,
            approvalID: approvalID,
            method: method,
            origin: origin,
            expiresAtUnixMs: expiresAtUnixMs,
            summary: summary
        )
    }

    static func display(_ prompt: WalletApprovalPromptV2) -> WalletApprovalDisplayV2 {
        var rows: [WalletApprovalDisplayV2.Row] = []
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
        case let .permissions(capabilities):
            title = "Approve wallet permissions"
            add("Capabilities", capabilities.map(\.rawValue).joined(separator: ", "))
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
        case let .marketIntent(action, marketIntentID, offered, requestedAsset, priceRound, maximumFee, warnings):
            title = "Approve market intent"
            add("Action", action)
            if let marketIntentID { add("Market intent ID", marketIntentID) }
            addAmount("Offered", offered)
            add("Requested asset", requestedAsset.rawValue)
            add("Price round", priceRound)
            addAmount("Maximum fee", maximumFee)
            addWarnings(warnings)
        case let .fillAcceptance(marketIntentID, fillID, offered, expected, priceRound, refundTimeout, maximumFee, warnings):
            title = "Approve marketplace fill"
            add("Market intent ID", marketIntentID)
            add("Fill ID", fillID)
            addAmount("Offered", offered)
            addAmount("Expected", expected)
            add("Price round", priceRound)
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
        return WalletApprovalDisplayV2(title: title, rows: rows)
    }

    private static func summary(
        _ candidate: Any?,
        method: String,
        expectedRequest: WalletProviderRequest
    ) throws -> WalletApprovalSummaryV2 {
        let value = try record(candidate)
        guard let kind = value["kind"] as? String else { throw invalidApproval() }
        switch kind {
        case "permissions":
            try requireMethod(method, ["wallet_requestPermissions", "hns_requestAccounts"])
            try requireExactFields(value, ["kind", "capabilities"])
            let capabilities = try canonicalEnums(
                value["capabilities"],
                values: WalletPermissionCapabilityV2.allCases,
                allowEmpty: false
            )
            try requireRequestedCapabilities(capabilities, request: expectedRequest)
            return .permissions(capabilities: capabilities)
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
            let parsedAmount = try amount(value["amount"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            let chain = try module(value["chain"])
            let expectedChain = method == "hns_send"
                ? WalletModuleV2.handshake.rawValue
                : (expectedRequest.params as? [String: Any])?["module"] as? String
            let finality = try finality(value["finality"])
            guard chain.rawValue == expectedChain,
                  parsedAmount.asset == chain.asset,
                  maximumFee.asset == parsedAmount.asset,
                  finality == finalityForAsset(parsedAmount.asset) else { throw invalidApproval() }
            return .send(
                amount: parsedAmount,
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
        case "marketIntent":
            try requireMethod(method, ["swap_publishMarketIntent", "swap_cancelMarketIntent"])
            try requireExactFields(
                value,
                [
                    "kind", "action", "marketIntentId", "offered", "requestedAsset",
                    "priceRound", "maximumFee", "warnings",
                ]
            )
            let action = try oneOf(value["action"], ["publish", "cancel"])
            let offered = try amount(value["offered"], allowZero: false)
            let requestedAsset = try asset(value["requestedAsset"])
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard action == (method == "swap_publishMarketIntent" ? "publish" : "cancel"),
                  offered.asset != requestedAsset,
                  maximumFee.asset == offered.asset else { throw invalidApproval() }
            return .marketIntent(
                action: action,
                marketIntentID: try optionalPublicString(value["marketIntentId"]),
                offered: offered,
                requestedAsset: requestedAsset,
                priceRound: try publicString(value["priceRound"]),
                maximumFee: maximumFee,
                warnings: try warnings(value["warnings"])
            )
        case "fillAcceptance":
            try requireMethod(method, ["swap_requestMatch", "swap_acceptFill"])
            try requireExactFields(
                value,
                [
                    "kind", "marketIntentId", "fillId", "offered", "expected", "priceRound",
                    "refundTimeoutUnixMs", "maximumFee", "warnings",
                ]
            )
            let offered = try amount(value["offered"], allowZero: false)
            let expected = try amount(value["expected"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard offered.asset != expected.asset,
                  maximumFee.asset == offered.asset else { throw invalidApproval() }
            return .fillAcceptance(
                marketIntentID: try publicString(value["marketIntentId"]),
                fillID: try publicString(value["fillId"]),
                offered: offered,
                expected: expected,
                priceRound: try publicString(value["priceRound"]),
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
            let parsedAmount = try amount(value["amount"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            let finality = try finality(value["finality"])
            guard maximumFee.asset == parsedAmount.asset,
                  finality == finalityForAsset(parsedAmount.asset) else { throw invalidApproval() }
            return .swapRedeem(
                swapSessionID: try publicString(value["swapSessionId"]),
                amount: parsedAmount,
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
            let parsedAmount = try amount(value["amount"], allowZero: false)
            let maximumFee = try amount(value["maximumFee"], allowZero: true)
            guard maximumFee.asset == parsedAmount.asset else { throw invalidApproval() }
            return .swapRefund(
                swapSessionID: try publicString(value["swapSessionId"]),
                amount: parsedAmount,
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
