import Foundation

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
