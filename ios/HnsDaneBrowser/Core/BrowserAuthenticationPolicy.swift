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
            switch runtime.classifyHost(canonicalHost) {
            case .icann:
                // Public IP literals have no DNS TLSA owner and stay in the bounded opaque
                // CONNECT path, where WebKit remains the WebPKI verifier.
                if Self.isIPAddressLiteral(canonicalHost) {
                    return .performDefaultHandling
                }
                // The whole-browser Rust proxy admits DNS-named ICANN subresources under
                // either an ICANN main frame or an active HNS root scope. In both cases the
                // exact live-generation certificate pin proves this challenge came from that
                // proxy after its Rust resolver/TLS policy completed.
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
            case .search:
                return .cancel
            case .handshake:
                guard case .handshakeRoot = activeScope,
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
            }

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
