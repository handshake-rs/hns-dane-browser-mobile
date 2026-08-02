import CoreFoundation
import Foundation

struct WalletBrowserAuthority: Equatable {
    let origin: String
    let namespace: String
    let browserAuthoritySession: String
    let policyGeneration: UInt64
    let navigationGeneration: UInt64
}

struct WalletCapabilitiesV1: Equatable {
    let available: Bool
    let abiVersion: Int
    let walletSession: String
    let permissionGeneration: UInt64
    let methods: Set<String>
}

struct WalletProviderAuthority {
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
protocol MobileWalletABIV1: AnyObject {
    func capabilities(authority: WalletBrowserAuthority) throws -> WalletCapabilitiesV1
    func request(authority: WalletProviderAuthority, request: WalletProviderRequest) throws -> Any
}

@MainActor
final class UnavailableMobileWalletABIV1: MobileWalletABIV1 {
    func capabilities(authority: WalletBrowserAuthority) throws -> WalletCapabilitiesV1 {
        throw WalletProviderError(code: "walletUnavailable", message: "Mobile wallet ABI v1 is unavailable")
    }

    func request(authority: WalletProviderAuthority, request: WalletProviderRequest) throws -> Any {
        throw WalletProviderError(code: "walletUnavailable", message: "Mobile wallet ABI v1 is unavailable")
    }
}

struct WalletProviderError: Error, Equatable {
    let code: String
    let message: String
}

enum WalletProviderProtocolV1 {
    static let schemaVersion = 1
    static let abiVersion = 1
    static let maximumMessageBytes = 64 * 1024
    static let maximumResultBytes = 256 * 1024
    static let maximumSafeJSONInteger = 9_007_199_254_740_991.0

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
        "recoveryphrase", "mnemonic", "seed", "seedbytes", "privatekey", "passphrase",
        "databaseencryptionkey", "encryptionkey", "htlcpreimage", "preimage",
        "providercapabilitysecret", "sessionauthorizationtoken",
    ]

    static func parseRequest(_ candidate: Any) throws -> WalletProviderRequest {
        guard JSONSerialization.isValidJSONObject(candidate),
              let data = try? JSONSerialization.data(withJSONObject: candidate),
              !data.isEmpty, data.count <= maximumMessageBytes,
              let value = candidate as? [String: Any],
              value["schemaVersion"] as? Int == schemaVersion,
              value["kind"] as? String == "request" else {
            throw WalletProviderError(code: "invalidRequest", message: "Invalid wallet provider frame")
        }
        try validateJSON(value, depth: 0)
        guard let requestID = value["requestId"] as? String,
              requestID.range(of: #"^[A-Za-z0-9._:-]{1,96}$"#, options: .regularExpression) != nil,
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

    static func validateCapabilities(_ value: WalletCapabilitiesV1) throws -> WalletCapabilitiesV1 {
        guard value.available, value.abiVersion == abiVersion,
              !value.walletSession.isEmpty, value.walletSession.count <= 160,
              value.permissionGeneration > 0, value.methods.isSubset(of: methods) else {
            throw WalletProviderError(code: "walletUnavailable", message: "Native wallet ABI v1 is unavailable")
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
                try validateJSON(result ?? NSNull(), depth: 0, invalidCode: "invalidResult", sizeCode: "resultTooLarge")
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

    static func event(_ event: String, payload: Any?) throws -> [String: Any] {
        guard events.contains(event) else {
            throw WalletProviderError(code: "invalidEvent", message: "Unsupported wallet provider event")
        }
        try validateJSON(
            payload ?? NSNull(),
            depth: 0,
            invalidCode: "invalidEvent",
            sizeCode: "eventTooLarge"
        )
        let value: [String: Any] = [
            "schemaVersion": schemaVersion,
            "kind": "event",
            "event": event,
            "payload": payload ?? NSNull(),
        ]
        guard JSONSerialization.isValidJSONObject(value),
              let encoded = try? JSONSerialization.data(withJSONObject: value),
              encoded.count <= maximumMessageBytes else {
            throw WalletProviderError(code: "eventTooLarge", message: "Wallet event exceeded its byte limit")
        }
        return value
    }

    private static func validateJSON(
        _ value: Any,
        depth: Int,
        invalidCode: String = "invalidRequest",
        sizeCode: String = "requestTooLarge"
    ) throws {
        guard depth <= 12 else {
            throw WalletProviderError(code: sizeCode, message: "Wallet frame is nested too deeply")
        }
        switch value {
        case is NSNull, is Bool:
            return
        case let string as String:
            guard string.count <= 16 * 1024 else {
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
                try validateJSON(child, depth: depth + 1, invalidCode: invalidCode, sizeCode: sizeCode)
            }
        case let object as [String: Any]:
            guard object.count <= 128 else {
                throw WalletProviderError(code: sizeCode, message: "Wallet object has too many fields")
            }
            for (key, child) in object {
                let normalizedKey = String(key.lowercased().filter { $0.isLetter || $0.isNumber })
                guard !["__proto__", "prototype", "constructor"].contains(key),
                      !sensitiveFields.contains(normalizedKey) else {
                    throw WalletProviderError(code: invalidCode, message: "Forbidden wallet field")
                }
                try validateJSON(child, depth: depth + 1, invalidCode: invalidCode, sizeCode: sizeCode)
            }
        default:
            throw WalletProviderError(code: invalidCode, message: "Wallet frame contains a non-JSON value")
        }
    }

    private static let publicErrorCodes: Set<String> = [
        "browserAuthorityDenied", "eventTooLarge", "forbiddenMethod", "internalError",
        "invalidEvent", "invalidOrigin", "invalidParams", "invalidRequest", "invalidResult",
        "originMismatch", "permissionDenied", "permissionGenerationChanged", "rateLimited",
        "replay", "requestTooLarge", "resultTooLarge", "staleContext", "unsupportedMethod",
        "unsupportedVersion", "userRejected", "walletLocked", "walletSessionChanged",
        "walletUnavailable",
    ]

    private static func publicErrorMessage(_ code: String) -> String {
        switch code {
        case "browserAuthorityDenied": return "Browser trust did not approve this document"
        case "eventTooLarge": return "Wallet event exceeded its byte limit"
        case "forbiddenMethod": return "The requested signing method is intentionally unavailable"
        case "invalidEvent": return "Native wallet event was invalid"
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
