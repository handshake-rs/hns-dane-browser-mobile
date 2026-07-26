import Foundation

struct BrowserAuthenticationContext: Equatable {
    enum Kind: Equatable {
        case proxy(authenticationMethod: String)
        case serverTrust(leafCertificateDER: Data?)
        case origin(authenticationMethod: String)
    }

    let kind: Kind
    let host: String
    let port: Int
    let realm: String?
}

enum BrowserAuthenticationDecision: Equatable {
    case performDefaultHandling
    case cancel
    case useProxyCredential(username: String, password: String)
    case useServerTrust
}

struct BrowserAuthenticationPolicy {
    func evaluate(
        _ context: BrowserAuthenticationContext,
        runtime: BrowserRuntime,
        liveProxy: BrowserProxySession?,
        activeScope: BrowserProxyScope?
    ) -> BrowserAuthenticationDecision {
        switch context.kind {
        case .proxy(let authenticationMethod):
            guard let liveProxy,
                  liveProxy.acceptsProxyChallenge(
                    host: context.host,
                    port: context.port,
                    realm: context.realm,
                    authenticationMethod: authenticationMethod
                  ) else {
                return .cancel
            }
            return .useProxyCredential(
                username: liveProxy.endpoint.username,
                password: liveProxy.endpoint.password
            )

        case .serverTrust(let leafCertificateDER):
            guard let canonicalHost = runtime.canonicalHost(context.host) else {
                return .cancel
            }
            // IP literals have no DNS TLSA owner and stay in the bounded
            // opaque CONNECT path, where WebKit remains the WebPKI verifier.
            if Self.isIPAddressLiteral(canonicalHost) {
                return .performDefaultHandling
            }
            // Namespace selection is never inferred in Swift. The exact
            // live-generation host certificate pin proves this challenge came
            // from the retained Rust proxy after its dual-root policy.
            guard activeScope != nil,
                  let liveProxy,
                  let leafCertificateDER,
                  !leafCertificateDER.isEmpty,
                  liveProxy.matchesLocalCertificate(
                    host: canonicalHost,
                    leafCertificateDER: leafCertificateDER
                  ) else {
                return .cancel
            }
            return .useServerTrust

        case .origin:
            // Never offer loopback proxy credentials to an origin challenge. ICANN and ordinary
            // HNS origin authentication remain WebKit-native.
            return .performDefaultHandling
        }
    }

    static func isIPAddressLiteral(_ host: String) -> Bool {
        if host.contains(":") {
            return true
        }
        let octets = host.split(separator: ".", omittingEmptySubsequences: false)
        return octets.count == 4 && octets.allSatisfy { octet in
            guard !octet.isEmpty,
                  octet.allSatisfy(\.isNumber),
                  let value = UInt8(octet) else {
                return false
            }
            return String(value) == String(octet)
        }
    }
}
