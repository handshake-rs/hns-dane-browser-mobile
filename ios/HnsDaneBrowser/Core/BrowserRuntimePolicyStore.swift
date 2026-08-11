import Foundation

enum BrowserResolutionMode: String, CaseIterable, Equatable, Sendable {
    case compatibility
    case strict
}

struct BrowserRuntimePolicy: Equatable, Sendable {
    let resolutionMode: BrowserResolutionMode
    let hnsDohResolver: String?
    let statelessDANECertificates: Bool
    let experimentalP2PDNSRelay: Bool
    let legacyHNSDoHCompatibility: Bool

    init(
        resolutionMode: BrowserResolutionMode = .strict,
        hnsDohResolver: String? = nil,
        statelessDANECertificates: Bool = false,
        experimentalP2PDNSRelay: Bool = false,
        legacyHNSDoHCompatibility: Bool = false
    ) {
        _ = resolutionMode
        _ = legacyHNSDoHCompatibility
        self.resolutionMode = .strict
        self.hnsDohResolver = Self.normalizeHNSDoHRecoveryURL(hnsDohResolver)
            .flatMap { $0.isEmpty ? nil : $0 }
        self.statelessDANECertificates = statelessDANECertificates
        self.experimentalP2PDNSRelay = experimentalP2PDNSRelay
        self.legacyHNSDoHCompatibility = false
    }

    static let `default` = BrowserRuntimePolicy()

    /// Blank disables the endpoint, a non-empty return value is canonical, and
    /// nil means the supplied value is invalid.
    static func normalizeHNSDoHRecoveryURL(_ input: String?) -> String? {
        let value = input?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if value.isEmpty { return "" }
        guard value.utf8.count <= 2 * 1024,
              !value.unicodeScalars.contains(where: {
                  CharacterSet.whitespacesAndNewlines.contains($0)
                      || CharacterSet.controlCharacters.contains($0)
              }),
              !value.contains("#"),
              !value.contains("{"),
              !value.contains("}"),
              let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              components.user == nil,
              components.password == nil,
              components.fragment == nil,
              let rawHost = components.host,
              !rawHost.isEmpty,
              !components.percentEncodedPath.isEmpty,
              components.percentEncodedPath.hasPrefix("/") else {
            return nil
        }

        var host = rawHost.lowercased()
        while host.hasSuffix(".") {
            host.removeLast()
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        let specialUseSuffixes: Set<String> = [
            "alt", "arpa", "example", "internal", "invalid",
            "local", "localhost", "onion", "test",
        ]
        guard host.utf8.count <= 253,
              labels.count >= 2,
              !isIPv4Literal(host),
              !host.contains(":"),
              !host.contains("["),
              !host.contains("]"),
              !specialUseSuffixes.contains(String(labels.last ?? "").lowercased()),
              labels.allSatisfy({ label in
                  guard !label.isEmpty, label.utf8.count <= 63,
                        let first = label.utf8.first, let last = label.utf8.last else {
                      return false
                  }
                  func isAlphanumeric(_ byte: UInt8) -> Bool {
                      (byte >= 48 && byte <= 57) || (byte >= 97 && byte <= 122)
                  }
                  return isAlphanumeric(first)
                      && isAlphanumeric(last)
                      && label.utf8.allSatisfy { isAlphanumeric($0) || $0 == 45 }
              }) else {
            return nil
        }

        let port = components.port ?? 443
        guard (1...65_535).contains(port), !browserBlockedPorts.contains(port) else {
            return nil
        }
        let authority = port == 443 ? host : "\(host):\(port)"
        let query = components.percentEncodedQuery.map { "?\($0)" } ?? ""
        return "https://\(authority)\(components.percentEncodedPath)\(query)"
    }

    private static func isIPv4Literal(_ host: String) -> Bool {
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        return octets.allSatisfy { octet in
            !octet.isEmpty
                && octet.allSatisfy(\.isNumber)
                && Int(octet).map { (0...255).contains($0) } == true
        }
    }

    private static let browserBlockedPorts: Set<Int> = [
        0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53,
        69, 77, 79, 87, 95, 101, 102, 103, 104, 109, 110, 111, 113, 115, 117,
        119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514,
        515, 526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601, 636, 989,
        990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061,
        6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080,
    ]
}

final class BrowserRuntimePolicyStore {
    private enum Key {
        static let resolutionMode = "hnsBrowser.runtimePolicy.resolutionMode"
        // Historical key: retained only as a permanent tombstone.
        static let hnsDohResolver = "hnsBrowser.runtimePolicy.hnsDohResolver"
        static let hnsDohRecoveryResolver =
            "hnsBrowser.runtimePolicy.hnsDohRecoveryResolver.v1"
        static let statelessDANE = "hnsBrowser.runtimePolicy.statelessDANE"
        static let experimentalP2PDNSRelay = "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay"
        static let legacyHNSDoHCompatibility = "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> BrowserRuntimePolicy {
        let explicitRelayPreference =
            defaults.object(forKey: Key.experimentalP2PDNSRelay) as? Bool
        let hadExplicitLegacyFallbackPreference =
            defaults.string(forKey: Key.resolutionMode) == BrowserResolutionMode.compatibility.rawValue
            || defaults.object(forKey: Key.hnsDohResolver) != nil
            || (defaults.object(forKey: Key.legacyHNSDoHCompatibility) as? Bool) == true
        let relayEnabled = explicitRelayPreference ?? false
        let recoveryInput = defaults.string(forKey: Key.hnsDohRecoveryResolver)
        let recoveryResolver = BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(recoveryInput)
        if recoveryInput != nil, recoveryResolver == nil {
            defaults.removeObject(forKey: Key.hnsDohRecoveryResolver)
        }
        let policy = BrowserRuntimePolicy(
            hnsDohResolver: recoveryResolver,
            statelessDANECertificates: defaults.bool(forKey: Key.statelessDANE),
            experimentalP2PDNSRelay: relayEnabled
        )
        if explicitRelayPreference == nil, hadExplicitLegacyFallbackPreference {
            defaults.set(relayEnabled, forKey: Key.experimentalP2PDNSRelay)
        }
        defaults.removeObject(forKey: Key.resolutionMode)
        defaults.removeObject(forKey: Key.hnsDohResolver)
        defaults.removeObject(forKey: Key.legacyHNSDoHCompatibility)
        return policy
    }

    func save(_ policy: BrowserRuntimePolicy) {
        defaults.removeObject(forKey: Key.resolutionMode)
        defaults.removeObject(forKey: Key.hnsDohResolver)
        defaults.removeObject(forKey: Key.legacyHNSDoHCompatibility)
        if let resolver = policy.hnsDohResolver {
            defaults.set(resolver, forKey: Key.hnsDohRecoveryResolver)
        } else {
            defaults.removeObject(forKey: Key.hnsDohRecoveryResolver)
        }
        defaults.set(policy.statelessDANECertificates, forKey: Key.statelessDANE)
        defaults.set(policy.experimentalP2PDNSRelay, forKey: Key.experimentalP2PDNSRelay)
    }
}

struct BrowserSyncSchedulingPolicy: Equatable, Sendable {
    let progressInterval: TimeInterval
    let retryInterval: TimeInterval
    let caughtUpInterval: TimeInterval
    let failureBackoff: [TimeInterval]

    init(
        progressInterval: TimeInterval = 30,
        retryInterval: TimeInterval = 10,
        caughtUpInterval: TimeInterval = 600,
        failureBackoff: [TimeInterval] = [5, 15, 60]
    ) {
        self.progressInterval = progressInterval
        self.retryInterval = retryInterval
        self.caughtUpInterval = caughtUpInterval
        self.failureBackoff = failureBackoff
    }

    func delay(after summary: BrowserSyncSummary?, consecutiveFailures: Int) -> TimeInterval {
        if consecutiveFailures > 0, !failureBackoff.isEmpty {
            return failureBackoff[min(consecutiveFailures - 1, failureBackoff.count - 1)]
        }
        if summary?.isCaughtUp == true {
            return caughtUpInterval
        }
        if summary?.madeHeaderProgress == true {
            return progressInterval
        }
        if summary?.needsHeaderBootstrap == true {
            return retryInterval
        }
        if summary?.hasUnknownTargetProgress == true {
            return retryInterval
        }
        return caughtUpInterval
    }
}
