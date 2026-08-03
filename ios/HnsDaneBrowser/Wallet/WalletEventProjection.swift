import CoreFoundation
import Foundation

enum WalletDisconnectReasonV2: String, CaseIterable {
    case authorityRevoked, authorityExpired, navigationChanged, policyChanged
    case walletSessionChanged, serviceRestarted
}

enum WalletNativeProviderEventV2: Equatable {
    case connect(permissionGeneration: UInt64)
    case disconnect(reason: WalletDisconnectReasonV2)
    case permissionsChanged(
        permissionGeneration: UInt64,
        capabilities: [WalletPermissionCapabilityV2]
    )
    case modulesChanged(modules: [WalletModuleV2])
    case accountsChanged(modules: [WalletModuleV2])
    case balancesChanged(modules: [WalletModuleV2])
    case transactionsChanged(modules: [WalletModuleV2])
    case namesChanged(names: [String])
    case nameMarketChanged(listingIDs: [String])
    case priceRoundChanged(pairs: [String])
    case marketIntentChanged(marketIntentIDs: [String])
    case swapSessionChanged(swapSessionIDs: [String])
    case walletLocked

    var name: String {
        switch self {
        case .connect: return "connect"
        case .disconnect: return "disconnect"
        case .permissionsChanged: return "permissionsChanged"
        case .modulesChanged: return "modulesChanged"
        case .accountsChanged: return "accountsChanged"
        case .balancesChanged: return "balancesChanged"
        case .transactionsChanged: return "transactionsChanged"
        case .namesChanged: return "namesChanged"
        case .nameMarketChanged: return "nameMarketChanged"
        case .priceRoundChanged: return "priceRoundChanged"
        case .marketIntentChanged: return "marketIntentChanged"
        case .swapSessionChanged: return "swapSessionChanged"
        case .walletLocked: return "walletLocked"
        }
    }

    var publicPayload: [String: Any] {
        switch self {
        case let .connect(permissionGeneration):
            return ["permissionGeneration": permissionGeneration]
        case let .disconnect(reason):
            return ["reason": reason.rawValue]
        case let .permissionsChanged(permissionGeneration, capabilities):
            return [
                "permissionGeneration": permissionGeneration,
                "capabilities": capabilities.map(\.rawValue),
            ]
        case let .modulesChanged(modules), let .accountsChanged(modules),
             let .balancesChanged(modules), let .transactionsChanged(modules):
            return ["modules": modules.map(\.rawValue)]
        case let .namesChanged(names):
            return ["names": names]
        case let .nameMarketChanged(listingIDs):
            return ["listingIds": listingIDs]
        case let .priceRoundChanged(pairs):
            return ["pairs": pairs]
        case let .marketIntentChanged(marketIntentIDs):
            return ["marketIntentIds": marketIntentIDs]
        case let .swapSessionChanged(swapSessionIDs):
            return ["swapSessionIds": swapSessionIDs]
        case .walletLocked:
            return [:]
        }
    }
}

enum WalletNativeEventProjectionV2 {
    private static let maximumPublicItems = 128
    private static let maximumPublicStringBytes = 4_096

    static func project(_ candidate: Any) throws -> [String: Any] {
        guard JSONSerialization.isValidJSONObject(candidate),
              let nativeEncoded = try? JSONSerialization.data(withJSONObject: candidate) else {
            throw WalletProviderError(code: "invalidEvent", message: "Native wallet event was invalid")
        }
        guard !nativeEncoded.isEmpty,
              nativeEncoded.count <= WalletProviderProtocolV1.maximumMessageBytes else {
            throw WalletProviderError(code: "eventTooLarge", message: "Wallet event exceeded its byte limit")
        }
        let event = try parse(candidate)
        let value: [String: Any] = [
            "schemaVersion": WalletProviderProtocolV1.schemaVersion,
            "kind": "event",
            "event": event.name,
            "payload": event.publicPayload,
        ]
        guard JSONSerialization.isValidJSONObject(value),
              let encoded = try? JSONSerialization.data(withJSONObject: value),
              encoded.count <= WalletProviderProtocolV1.maximumMessageBytes else {
            throw WalletProviderError(code: "eventTooLarge", message: "Wallet event exceeded its byte limit")
        }
        return value
    }

    private static func parse(_ candidate: Any) throws -> WalletNativeProviderEventV2 {
        let value = try record(candidate)
        guard let event = value["event"] as? String else { throw invalidEvent() }
        switch event {
        case "connect":
            try exactFields(value, ["event", "permissionGeneration"])
            return .connect(permissionGeneration: try nonzeroInteger(value["permissionGeneration"]))
        case "disconnect":
            try exactFields(value, ["event", "reason"])
            guard let reasonValue = value["reason"] as? String,
                  let reason = WalletDisconnectReasonV2(rawValue: reasonValue) else {
                throw invalidEvent()
            }
            return .disconnect(reason: reason)
        case "permissionsChanged":
            try exactFields(value, ["event", "permissionGeneration", "capabilities"])
            return .permissionsChanged(
                permissionGeneration: try nonzeroInteger(value["permissionGeneration"]),
                capabilities: try canonicalEnums(
                    value["capabilities"],
                    values: WalletPermissionCapabilityV2.allCases
                )
            )
        case "modulesChanged", "accountsChanged", "balancesChanged", "transactionsChanged":
            try exactFields(value, ["event", "modules"])
            let modules = try canonicalEnums(value["modules"], values: WalletModuleV2.allCases)
            switch event {
            case "modulesChanged": return .modulesChanged(modules: modules)
            case "accountsChanged": return .accountsChanged(modules: modules)
            case "balancesChanged": return .balancesChanged(modules: modules)
            default: return .transactionsChanged(modules: modules)
            }
        case "namesChanged":
            try exactFields(value, ["event", "names"])
            return .namesChanged(names: try publicStrings(value["names"]))
        case "nameMarketChanged":
            try exactFields(value, ["event", "listingIds"])
            return .nameMarketChanged(listingIDs: try publicStrings(value["listingIds"]))
        case "priceRoundChanged":
            try exactFields(value, ["event", "pairs"])
            return .priceRoundChanged(pairs: try publicStrings(value["pairs"]))
        case "marketIntentChanged":
            try exactFields(value, ["event", "marketIntentIds"])
            return .marketIntentChanged(marketIntentIDs: try publicStrings(value["marketIntentIds"]))
        case "swapSessionChanged":
            try exactFields(value, ["event", "swapSessionIds"])
            return .swapSessionChanged(swapSessionIDs: try publicStrings(value["swapSessionIds"]))
        case "walletLocked":
            try exactFields(value, ["event"])
            return .walletLocked
        default:
            throw invalidEvent()
        }
    }

    private static func canonicalEnums<Value: RawRepresentable & Equatable>(
        _ candidate: Any?,
        values: [Value]
    ) throws -> [Value] where Value.RawValue == String {
        guard let rawValues = candidate as? [Any], rawValues.count <= values.count else {
            throw invalidEvent()
        }
        let strings = try rawValues.map { value -> String in
            guard let value = value as? String else { throw invalidEvent() }
            return value
        }
        guard Set(strings).count == strings.count else { throw invalidEvent() }
        let parsed = try strings.map { string -> Value in
            guard let value = values.first(where: { $0.rawValue == string }) else {
                throw invalidEvent()
            }
            return value
        }
        guard parsed == values.filter(parsed.contains) else { throw invalidEvent() }
        return parsed
    }

    private static func publicStrings(_ candidate: Any?) throws -> [String] {
        guard let values = candidate as? [Any], values.count <= maximumPublicItems else {
            throw invalidEvent()
        }
        return try values.map { value in
            guard let value = value as? String, !value.isEmpty,
                  value.utf8.count <= maximumPublicStringBytes,
                  value.utf8.allSatisfy({ (0x20...0x7e).contains($0) }) else {
                throw invalidEvent()
            }
            return value
        }
    }

    private static func nonzeroInteger(_ candidate: Any?) throws -> UInt64 {
        guard let number = candidate as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite,
              number.doubleValue >= 1,
              number.doubleValue <= WalletProviderProtocolV1.maximumSafeJSONInteger,
              number.doubleValue == floor(number.doubleValue) else { throw invalidEvent() }
        return number.uint64Value
    }

    private static func exactFields(_ value: [String: Any], _ fields: Set<String>) throws {
        guard Set(value.keys) == fields else { throw invalidEvent() }
    }

    private static func record(_ candidate: Any?) throws -> [String: Any] {
        guard let value = candidate as? [String: Any] else { throw invalidEvent() }
        return value
    }

    private static func invalidEvent() -> WalletProviderError {
        WalletProviderError(code: "invalidEvent", message: "Native wallet event was invalid")
    }
}
