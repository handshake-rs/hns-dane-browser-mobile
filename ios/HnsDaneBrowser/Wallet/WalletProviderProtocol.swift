import CoreFoundation
import Foundation

/// Opaque browser-engine authority capability. Final identity equality prevents unrelated
/// authority contexts from defining themselves as equal and it has no serializable fields.
final class WalletEngineAuthorityContext {}

struct WalletBrowserAuthority: Equatable {
    let origin: String
    let namespace: String
    let browserAuthoritySession: String
    let runtimeGeneration: UInt64
    let policyGeneration: UInt64
    let navigationGeneration: UInt64
    let decisionFingerprint: String
    let validUntilUnixMs: UInt64
    let engineContext: WalletEngineAuthorityContext

    func isCurrent(nowUnixMs: UInt64) -> Bool {
        (namespace == "hns" || namespace == "icann")
            && !browserAuthoritySession.isEmpty
            && browserAuthoritySession.count <= 160
            && runtimeGeneration > 0
            && policyGeneration > 0
            && navigationGeneration > 0
            && decisionFingerprint.utf8.count == 64
            && decisionFingerprint.utf8.allSatisfy {
                (0x30...0x39).contains($0) || (0x61...0x66).contains($0)
            }
            && decisionFingerprint.utf8.contains { $0 != 0x30 }
            && validUntilUnixMs > nowUnixMs
            && validUntilUnixMs <= WalletProviderProtocolV1.maximumSafeJSONIntegerUInt64
    }

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.origin == rhs.origin
            && lhs.namespace == rhs.namespace
            && lhs.browserAuthoritySession == rhs.browserAuthoritySession
            && lhs.runtimeGeneration == rhs.runtimeGeneration
            && lhs.policyGeneration == rhs.policyGeneration
            && lhs.navigationGeneration == rhs.navigationGeneration
            && lhs.decisionFingerprint == rhs.decisionFingerprint
            && lhs.validUntilUnixMs == rhs.validUntilUnixMs
            && lhs.engineContext === rhs.engineContext
    }
}

struct WalletCapabilitiesV2: Equatable {
    let available: Bool
    let abiVersion: Int
    let walletSession: String
    let permissionGeneration: UInt64
    let methods: Set<String>
}

struct WalletProviderAuthority: Equatable {
    let browser: WalletBrowserAuthority
    let walletSession: String
    let permissionGeneration: UInt64
}

struct WalletProviderRequest {
    let requestID: String
    let sequence: UInt64
    let method: String
    let params: Any?
}

@MainActor
protocol MobileWalletABIV2: AnyObject {
    func capabilities(authority: WalletBrowserAuthority) throws -> WalletCapabilitiesV2
    func request(authority: WalletProviderAuthority, request: WalletProviderRequest) throws -> Any
}

@MainActor
final class UnavailableMobileWalletABIV2: MobileWalletABIV2 {
    func capabilities(authority: WalletBrowserAuthority) throws -> WalletCapabilitiesV2 {
        WalletCapabilitiesV2(
            available: false,
            abiVersion: WalletProviderProtocolV1.nativeABIVersion,
            walletSession: "",
            permissionGeneration: 0,
            methods: []
        )
    }

    func request(authority: WalletProviderAuthority, request: WalletProviderRequest) throws -> Any {
        throw WalletProviderError(code: "walletUnavailable", message: "Mobile wallet ABI v2 is unavailable")
    }
}

/// These independent release decisions remain immutable until reviewed native bindings,
/// browser authority, approval UI, and value qualification are shipped together.
enum WalletNativeReleaseGates {
    static let providerBridgeReleaseQualified = false
    static let walletRuntimeReleaseQualified = false
    static let approvalRuntimeReleaseQualified = false
    static let valueRuntimeReleaseQualified = false

    static var installationAvailable: Bool {
        providerBridgeReleaseQualified
    }

    static var approvalDispatchAvailable: Bool {
        walletRuntimeReleaseQualified && approvalRuntimeReleaseQualified
    }

    static var valueActionsAvailable: Bool {
        walletRuntimeReleaseQualified
            && approvalRuntimeReleaseQualified
            && valueRuntimeReleaseQualified
    }
}

struct WalletProviderError: Error, Equatable {
    let code: String
    let message: String
}

enum WalletMethodReleaseClass: Equatable {
    case noApproval
    case approvalOnly
    case approvalAndValue
}

enum WalletProviderProtocolV1 {
    static let schemaVersion = 1
    static let providerAPIVersion = 1
    static let nativeABIVersion = 2
    static let approvalSchemaVersion = 2
    static let maximumMessageBytes = 64 * 1024
    static let maximumResultBytes = 256 * 1024
    static let maximumSafeJSONInteger = 9_007_199_254_740_991.0
    static let maximumSafeJSONIntegerUInt64: UInt64 = 9_007_199_254_740_991

    static let methods: Set<String> = [
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_enableModule",
        "wallet_disableModule", "wallet_requestPermissions", "wallet_getPermissions",
        "wallet_revokePermissions", "wallet_lock", "wallet_getStatus",
        "hns_requestAccounts", "hns_accounts", "hns_getBalance", "hns_getTransactions",
        "hns_getReceiveAddress", "hns_send", "hns_getNames", "hns_getName",
        "hns_importKnownName", "hns_transferName", "hns_finalizeName", "hns_signTypedMessage",
        "asset_getAccount", "asset_getBalance", "asset_getTransactions",
        "asset_getReceiveTarget", "asset_send", "nameMarket_listOffers",
        "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer", "nameMarket_acceptOffer",
        "nameMarket_getSession", "nameMarket_finalizePurchase", "nameMarket_recoverName",
        "swap_getSupportedPairs", "swap_getPriceRound", "swap_listMarketIntents",
        "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
        "swap_acceptFill", "swap_getSession", "swap_redeem", "swap_refund",
    ]

    static let noApprovalMethods: Set<String> = [
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_getPermissions",
        "wallet_revokePermissions", "wallet_lock", "wallet_getStatus", "hns_accounts",
        "hns_getBalance", "hns_getTransactions", "hns_getReceiveAddress", "hns_getNames",
        "hns_getName", "hns_importKnownName", "asset_getAccount", "asset_getBalance",
        "asset_getTransactions", "asset_getReceiveTarget", "nameMarket_listOffers",
        "nameMarket_getSession", "swap_getSupportedPairs", "swap_getPriceRound",
        "swap_listMarketIntents", "swap_getSession",
    ]

    static let approvalOnlyMethods: Set<String> = [
        "wallet_enableModule", "wallet_disableModule", "wallet_requestPermissions",
        "hns_requestAccounts", "hns_signTypedMessage",
    ]

    static let approvalAndValueMethods: Set<String> = [
        "hns_send", "hns_transferName", "hns_finalizeName", "asset_send",
        "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
        "nameMarket_acceptOffer", "nameMarket_finalizePurchase", "nameMarket_recoverName",
        "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
        "swap_acceptFill", "swap_redeem", "swap_refund",
    ]

    static let events: Set<String> = [
        "connect", "disconnect", "permissionsChanged", "modulesChanged", "accountsChanged",
        "balancesChanged", "transactionsChanged", "namesChanged", "nameMarketChanged",
        "priceRoundChanged", "marketIntentChanged", "swapSessionChanged", "walletLocked",
    ]

    static let forbiddenMethods: Set<String> = [
        "eth_sendTransaction", "eth_call", "eth_estimateGas", "eth_sign", "personal_sign",
        "wallet_addEthereumChain", "wallet_switchEthereumChain", "bitcoin_signPsbt",
        "signRawTransaction",
    ]

    private static let assetMethods: Set<String> = [
        "asset_getAccount", "asset_getBalance", "asset_getTransactions",
        "asset_getReceiveTarget", "asset_send",
    ]
    private static let noParameterMethods: Set<String> = [
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_getPermissions",
        "wallet_lock", "wallet_getStatus", "hns_requestAccounts", "hns_accounts",
        "hns_getBalance", "hns_getReceiveAddress", "hns_getNames",
        "swap_getSupportedPairs", "swap_listMarketIntents",
    ]
    private static let sensitiveFields: Set<String> = [
        "authorityhandle", "authorityrevision", "browserauthoritysession",
        "channelsequence", "decisionfingerprint", "eventsequence", "hostsessionid",
        "browserruntimesessionid", "enginecontext", "navigationgeneration",
        "policygeneration", "protocolversion",
        "requestnonce", "restartgeneration", "runtimegeneration", "runtimesessionid",
        "servicesessionid", "validuntilunixms", "walletsession", "approvalrequired",
        "recoveryphrase", "mnemonic", "seed", "seedbytes", "privatekey", "passphrase",
        "databaseencryptionkey", "encryptionkey", "htlcpreimage", "preimage",
        "providercapabilitysecret", "sessionauthorizationtoken",
    ]
    private static let permissionResultMethods: Set<String> = [
        "wallet_getPermissions", "wallet_revokePermissions",
        "wallet_requestPermissions", "hns_requestAccounts",
    ]

    static func parseRequest(_ candidate: Any) throws -> WalletProviderRequest {
        guard JSONSerialization.isValidJSONObject(candidate),
              let data = try? JSONSerialization.data(withJSONObject: candidate),
              !data.isEmpty, data.count <= maximumMessageBytes,
              let value = candidate as? [String: Any],
              hasExactProviderSchemaVersion(value["schemaVersion"]),
              value["kind"] as? String == "request" else {
            throw WalletProviderError(code: "invalidRequest", message: "Invalid wallet provider frame")
        }
        try validateJSON(value, depth: 0)
        guard let requestID = value["requestId"] as? String,
              isCanonicalRequestID(requestID),
              let sequenceNumber = value["sequence"] as? NSNumber,
              CFGetTypeID(sequenceNumber) != CFBooleanGetTypeID(),
              sequenceNumber.doubleValue.isFinite,
              sequenceNumber.doubleValue >= 1,
              sequenceNumber.doubleValue <= maximumSafeJSONInteger,
              sequenceNumber.doubleValue == floor(sequenceNumber.doubleValue),
              let method = value["method"] as? String else {
            throw WalletProviderError(code: "invalidRequest", message: "Invalid wallet request metadata")
        }
        if forbiddenMethods.contains(method) {
            throw WalletProviderError(code: "forbiddenMethod", message: "\(method) is intentionally unavailable")
        }
        guard methods.contains(method) else {
            throw WalletProviderError(code: "unsupportedMethod", message: "Unsupported wallet provider method")
        }
        let params = value["params"] is NSNull ? nil : value["params"]
        if noParameterMethods.contains(method),
           let params,
           (params as? [String: Any])?.isEmpty != true {
            throw WalletProviderError(
                code: "invalidParams",
                message: "\(method) does not accept parameters"
            )
        }
        if assetMethods.contains(method) {
            guard let params = params as? [String: Any],
                  let module = params["module"] as? String,
                  module == "bitcoin" || module == "ethereum" else {
                throw WalletProviderError(code: "invalidParams", message: "External asset module is invalid")
            }
        }
        return WalletProviderRequest(
            requestID: requestID,
            sequence: sequenceNumber.uint64Value,
            method: method,
            params: params
        )
    }

    static func releaseClass(for method: String) throws -> WalletMethodReleaseClass {
        if noApprovalMethods.contains(method) { return .noApproval }
        if approvalOnlyMethods.contains(method) { return .approvalOnly }
        if approvalAndValueMethods.contains(method) { return .approvalAndValue }
        throw WalletProviderError(code: "unsupportedMethod", message: "Unsupported wallet provider method")
    }

    static func requireMethodReleaseQualified(_ method: String) throws {
        guard WalletNativeReleaseGates.walletRuntimeReleaseQualified else {
            throw WalletProviderError(
                code: "walletUnavailable",
                message: "Mobile wallet runtime is unavailable"
            )
        }
        switch try releaseClass(for: method) {
        case .noApproval:
            return
        case .approvalOnly:
            guard WalletNativeReleaseGates.approvalDispatchAvailable else {
                throw WalletProviderError(
                    code: "walletUnavailable",
                    message: "Mobile wallet approval runtime is unavailable"
                )
            }
        case .approvalAndValue:
            guard WalletNativeReleaseGates.valueActionsAvailable else {
                throw WalletProviderError(
                    code: "walletUnavailable",
                    message: "Mobile wallet value runtime is unavailable"
                )
            }
        }
    }

    static func hasExactProviderSchemaVersion(_ candidate: Any?) -> Bool {
        guard let number = candidate as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue == floor(number.doubleValue) else { return false }
        return number.intValue == schemaVersion && number.doubleValue == Double(schemaVersion)
    }

    static func validateCapabilities(_ value: WalletCapabilitiesV2) throws -> WalletCapabilitiesV2 {
        guard WalletNativeReleaseGates.walletRuntimeReleaseQualified,
              value.available, value.abiVersion == nativeABIVersion,
              !value.walletSession.isEmpty, value.walletSession.count <= 160,
              value.permissionGeneration > 0, value.methods.isSubset(of: methods) else {
            throw WalletProviderError(code: "walletUnavailable", message: "Native wallet ABI v2 is unavailable")
        }
        return value
    }

    static func response(
        request: WalletProviderRequest? = nil,
        result: Any? = nil,
        error: Error? = nil
    ) -> [String: Any] {
        var value: [String: Any] = [
            "schemaVersion": schemaVersion,
            "kind": request == nil ? "initialized" : "response",
        ]
        if let request {
            value["requestId"] = request.requestID
            value["sequence"] = request.sequence
        }
        if let error {
            let providerError = error as? WalletProviderError
            let proposedCode = providerError?.code
            let code = proposedCode.flatMap {
                publicErrorCodes.contains($0) ? $0 : nil
            } ?? "internalError"
            value["ok"] = false
            value["error"] = [
                "code": code,
                "message": publicErrorMessage(code),
            ]
        } else {
            do {
                try validateJSON(
                    result ?? NSNull(),
                    depth: 0,
                    invalidCode: "invalidResult",
                    sizeCode: "resultTooLarge",
                    allowPermissionGenerationAtRoot:
                        request.map { permissionResultMethods.contains($0.method) } ?? false
                )
                value["ok"] = true
                value["result"] = result ?? NSNull()
            } catch {
                return response(request: request, error: error)
            }
        }
        guard JSONSerialization.isValidJSONObject(value),
              let encoded = try? JSONSerialization.data(withJSONObject: value),
              encoded.count <= maximumResultBytes else {
            if error != nil {
                return [
                    "schemaVersion": schemaVersion,
                    "kind": request == nil ? "initialized" : "response",
                    "ok": false,
                    "error": ["code": "internalError", "message": "Wallet request failed"],
                ]
            }
            return response(
                request: request,
                error: WalletProviderError(
                    code: "resultTooLarge",
                    message: "Native wallet result exceeded its byte limit"
                )
            )
        }
        return value
    }

    private static func validateJSON(
        _ value: Any,
        depth: Int,
        invalidCode: String = "invalidRequest",
        sizeCode: String = "requestTooLarge",
        allowPermissionGenerationAtRoot: Bool = false
    ) throws {
        guard depth <= 12 else {
            throw WalletProviderError(code: sizeCode, message: "Wallet frame is nested too deeply")
        }
        switch value {
        case is NSNull, is Bool:
            return
        case let string as String:
            guard string.utf8.count <= 16 * 1024 else {
                throw WalletProviderError(code: sizeCode, message: "Wallet string exceeds its limit")
            }
        case let number as NSNumber:
            guard CFGetTypeID(number) != CFBooleanGetTypeID(),
                  number.doubleValue == floor(number.doubleValue),
                  number.doubleValue.isFinite,
                  abs(number.doubleValue) <= maximumSafeJSONInteger else {
                throw WalletProviderError(code: invalidCode, message: "Use safe integers or decimal base units")
            }
        case let array as [Any]:
            guard array.count <= 128 else {
                throw WalletProviderError(code: sizeCode, message: "Wallet array has too many values")
            }
            for child in array {
                try validateJSON(
                    child,
                    depth: depth + 1,
                    invalidCode: invalidCode,
                    sizeCode: sizeCode,
                    allowPermissionGenerationAtRoot: allowPermissionGenerationAtRoot
                )
            }
        case let object as [String: Any]:
            guard object.count <= 128 else {
                throw WalletProviderError(code: sizeCode, message: "Wallet object has too many fields")
            }
            if invalidCode == "invalidResult", containsPrivateRoute(object) {
                throw WalletProviderError(
                    code: "invalidResult",
                    message: "Native events and approvals require private routing"
                )
            }
            for (key, child) in object {
                let normalizedKey = normalizedField(key)
                let invalidPermissionGeneration = normalizedKey == "permissiongeneration"
                    && (!allowPermissionGenerationAtRoot || depth != 0)
                guard !["__proto__", "prototype", "constructor"].contains(key),
                      !sensitiveFields.contains(normalizedKey),
                      !invalidPermissionGeneration else {
                    throw WalletProviderError(code: invalidCode, message: "Forbidden wallet field")
                }
                try validateJSON(
                    child,
                    depth: depth + 1,
                    invalidCode: invalidCode,
                    sizeCode: sizeCode,
                    allowPermissionGenerationAtRoot: allowPermissionGenerationAtRoot
                )
            }
        default:
            throw WalletProviderError(code: invalidCode, message: "Wallet frame contains a non-JSON value")
        }
    }

    private static func isCanonicalRequestID(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard !bytes.isEmpty, bytes.count <= 96 else { return false }
        return bytes.allSatisfy { byte in
            (0x30...0x39).contains(byte)
                || (0x41...0x5a).contains(byte)
                || (0x61...0x7a).contains(byte)
                || [0x2e, 0x3a, 0x5f, 0x2d].contains(byte)
        }
    }

    private static func containsPrivateRoute(_ object: [String: Any]) -> Bool {
        let fields = Set(object.keys.map(normalizedField))
        return fields.contains("event")
            || fields.contains("events")
            || fields.contains("approvalrequired")
            || (
                fields.contains("approvalid")
                    && fields.contains("expiresatunixms")
                    && fields.contains("summary")
            )
    }

    private static func normalizedField(_ value: String) -> String {
        String(value.lowercased().filter { $0.isLetter || $0.isNumber })
    }

    private static let publicErrorCodes: Set<String> = [
        "approvalTooLarge", "browserAuthorityDenied", "eventTooLarge", "forbiddenMethod", "internalError",
        "invalidApproval", "invalidEvent", "invalidOrigin", "invalidParams", "invalidRequest", "invalidResult",
        "originMismatch", "permissionDenied", "permissionGenerationChanged", "rateLimited",
        "replay", "requestTooLarge", "resultTooLarge", "staleContext", "unsupportedMethod",
        "unsupportedVersion", "userRejected", "walletLocked", "walletSessionChanged",
        "walletUnavailable",
    ]

    private static func publicErrorMessage(_ code: String) -> String {
        switch code {
        case "approvalTooLarge": return "Native wallet approval exceeded its byte limit"
        case "browserAuthorityDenied": return "Browser trust did not approve this document"
        case "eventTooLarge": return "Wallet event exceeded its byte limit"
        case "forbiddenMethod": return "The requested signing method is intentionally unavailable"
        case "invalidEvent": return "Native wallet event was invalid"
        case "invalidApproval": return "Native wallet approval was invalid"
        case "invalidOrigin": return "Wallet provider requires an exact HTTPS main frame"
        case "invalidParams": return "Wallet provider parameters were invalid"
        case "invalidRequest": return "Wallet provider request was invalid"
        case "invalidResult": return "Native wallet result was invalid"
        case "originMismatch": return "Wallet frame origin did not match its source"
        case "permissionDenied": return "Wallet permission was denied"
        case "permissionGenerationChanged": return "Wallet permissions changed during the request"
        case "rateLimited": return "Wallet provider request rate was exceeded"
        case "replay": return "Wallet provider request was already observed"
        case "requestTooLarge": return "Wallet provider request exceeded its byte limit"
        case "resultTooLarge": return "Native wallet result exceeded its byte limit"
        case "staleContext": return "Wallet provider document binding is stale"
        case "unsupportedMethod": return "Wallet provider method is unsupported"
        case "unsupportedVersion": return "Wallet provider version is unsupported"
        case "userRejected": return "Wallet request was rejected"
        case "walletLocked": return "Wallet is locked"
        case "walletSessionChanged": return "Wallet session changed during the request"
        case "walletUnavailable": return "Mobile wallet is unavailable"
        default: return "Wallet request failed"
        }
    }
}
