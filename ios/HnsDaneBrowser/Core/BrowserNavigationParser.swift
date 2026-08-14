import Foundation

/// Keeps the exact admitted address separate from the compact text shown while
/// the omnibox is idle. It also provides the fail-closed origin comparison used
/// for WebKit URL observations that do not pass through navigation policy (for
/// example, `history.pushState`).
struct BrowserAddressPresentation {
    private static let startPageHost = "appassets.androidplatform.net"

    private struct WebAddress {
        let scheme: String
        let host: String
        let explicitPort: Int?
        let percentEncodedPath: String
        let percentEncodedQuery: String?
        let percentEncodedFragment: String?

        var effectivePort: Int {
            explicitPort ?? (scheme == "https" ? 443 : 80)
        }

        var displayHost: String {
            host.contains(":") ? "[\(host)]" : host
        }
    }

    private struct ParsedAuthority {
        let host: String
        let explicitPort: Int?
    }

    static func editingText(for canonicalAddress: String?) -> String {
        canonicalAddress ?? ""
    }

    static func displayText(for rawValue: String?) -> String {
        let value = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !value.isEmpty, value != "about:blank" else { return "" }
        guard let address = parseWebAddress(value) else { return value }

        if address.scheme == "https",
           address.effectivePort == 443,
           address.host == startPageHost,
           address.percentEncodedPath == "/assets/start.html",
           address.percentEncodedQuery == nil,
           address.percentEncodedFragment == nil {
            return ""
        }

        let defaultPort = address.scheme == "https" ? 443 : 80
        let port = address.explicitPort.flatMap { explicitPort in
            explicitPort == defaultPort ? nil : ":\(explicitPort)"
        } ?? ""
        let path = address.percentEncodedPath == "/"
            ? ""
            : address.percentEncodedPath
        let query = address.percentEncodedQuery.map { "?\($0)" } ?? ""
        let fragment = address.percentEncodedFragment.map { "#\($0)" } ?? ""
        return address.displayHost + port + path + query + fragment
    }

    static func isSameAdmittedWebOrigin(
        _ admittedAddress: String?,
        _ observedAddress: String?
    ) -> Bool {
        guard let admittedAddress,
              let observedAddress,
              let admitted = parseWebAddress(admittedAddress),
              let observed = parseWebAddress(observedAddress) else {
            return false
        }
        return admitted.scheme == observed.scheme
            && admitted.host == observed.host
            && admitted.effectivePort == observed.effectivePort
    }

    private static func parseWebAddress(_ value: String) -> WebAddress? {
        guard value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              let schemeSeparator = value.range(of: "://") else {
            return nil
        }
        let scheme = value[..<schemeSeparator.lowerBound].lowercased()
        guard scheme == "http" || scheme == "https" else { return nil }

        let remainder = value[schemeSeparator.upperBound...]
        let authorityEnd = remainder.firstIndex { character in
            character == "/" || character == "?" || character == "#"
        } ?? remainder.endIndex
        let authority = remainder[..<authorityEnd]
        guard !authority.isEmpty,
              !authority.contains("@"),
              let parsedAuthority = parseAuthority(authority),
              let components = URLComponents(string: value),
              components.user == nil,
              components.password == nil,
              let componentScheme = components.scheme?.lowercased(),
              componentScheme == scheme,
              let componentHost = components.host,
              !componentHost.isEmpty else {
            return nil
        }

        let componentPort = components.port
        guard componentPort == parsedAuthority.explicitPort else { return nil }

        var host = parsedAuthority.host.trimmingCharacters(in: .whitespacesAndNewlines)
        if host.hasPrefix("[") && host.hasSuffix("]") {
            host.removeFirst()
            host.removeLast()
        }
        while host.hasSuffix(".") {
            host.removeLast()
        }
        host = host.lowercased()
        guard !host.isEmpty else { return nil }

        return WebAddress(
            scheme: scheme,
            host: host,
            explicitPort: parsedAuthority.explicitPort,
            percentEncodedPath: components.percentEncodedPath,
            percentEncodedQuery: components.percentEncodedQuery,
            percentEncodedFragment: components.percentEncodedFragment
        )
    }

    /// Keeps the displayed host independent of Foundation's IDNA-decoded
    /// `URLComponents.host`, while distinguishing an absent port from malformed
    /// syntax before Foundation is asked to expose `URLComponents.port`.
    private static func parseAuthority(_ authority: Substring) -> ParsedAuthority? {
        let host: Substring
        let portText: Substring?
        if authority.hasPrefix("[") {
            guard let closingBracket = authority.firstIndex(of: "]"),
                  closingBracket != authority.index(after: authority.startIndex) else {
                return nil
            }
            host = authority[...closingBracket]
            let suffix = authority[authority.index(after: closingBracket)...]
            if suffix.isEmpty {
                portText = nil
            } else {
                guard suffix.first == ":" else { return nil }
                portText = suffix.dropFirst()
            }
        } else {
            guard !authority.contains("[") && !authority.contains("]") else { return nil }
            let separators = authority.indices.filter { authority[$0] == ":" }
            guard separators.count <= 1 else { return nil }
            if let separator = separators.first {
                host = authority[..<separator]
                portText = authority[authority.index(after: separator)...]
            } else {
                host = authority
                portText = nil
            }
        }

        guard !host.isEmpty else { return nil }
        let explicitPort: Int?
        if let portText {
            guard !portText.isEmpty,
                  portText.allSatisfy({ $0.isNumber }),
                  let port = Int(portText),
                  (1...65_535).contains(port) else {
                return nil
            }
            explicitPort = port
        } else {
            explicitPort = nil
        }
        return ParsedAuthority(host: String(host), explicitPort: explicitPort)
    }
}

struct BrowserNavigationParser {
    let canonicalizeHost: (String) throws -> String

    func parse(_ rawValue: String) throws -> BrowserDestination {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw BrowserCoreError.invalidAddress("Enter an address.") }
        guard trimmed.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else {
            throw BrowserCoreError.invalidAddress("Enter a complete web or Handshake address.")
        }

        let explicitScheme = trimmed.range(
            of: #"^[A-Za-z][A-Za-z0-9+.-]*://"#,
            options: .regularExpression
        ) != nil
        let candidate = explicitScheme ? trimmed : "https://\(trimmed)"
        guard var components = URLComponents(string: candidate),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.user == nil,
              components.password == nil,
              let extractedHost = components.host,
              !extractedHost.isEmpty else {
            throw BrowserCoreError.unsupportedAddress
        }

        let canonicalHost = try canonicalizeHost(extractedHost)
        // URL parsing is intentionally namespace-agnostic. All canonical DNS
        // names share one retained whole-browser proxy; that Rust generation
        // resolves the complete origin through HNS and ICANN and owns the
        // authenticated selection plan. Public IP literals remain the
        // proxy's bounded opaque-address path.
        let hostKind: BrowserHostKind =
            BrowserAuthenticationPolicy.isIPAddressLiteral(canonicalHost)
            ? .icann
            : .nativeGateway
        let scope: BrowserProxyScope = .wholeBrowser

        components.scheme = scheme
        guard let url = components.url else {
            throw BrowserCoreError.invalidAddress("The address is malformed.")
        }
        return BrowserDestination(
            url: url,
            canonicalHost: canonicalHost,
            hostKind: hostKind,
            proxyScope: scope
        )
    }
}
