import Foundation
import WebKit

/// Install into a hardened WKWebView configuration before constructing the view. The authority
/// closure must return a value only for the current, exact, trusted main-frame navigation.
@MainActor
final class WalletWebKitBridge: NSObject, WKScriptMessageHandlerWithReply {
    private struct Session {
        let browser: WalletBrowserAuthority
        let capabilities: WalletCapabilitiesV2
        var lastSequence: UInt64
        var seenRequestIDs: Set<String> = []
        var requestIDOrder: [String] = []
        var globalRequestTimes: [TimeInterval] = []
        var methodRequestTimes: [String: [TimeInterval]] = [:]
    }

    private static let handlerName = "hnsWalletNativeV1"
    // Intentionally not injectable: reviewed generated bindings and every independent release
    // gate must land before this source-level fail-closed ABI-v2 adapter can be replaced.
    private let wallet: MobileWalletABIV2 = UnavailableMobileWalletABIV2()
    private let authorityForFrame: (WKFrameInfo) -> WalletBrowserAuthority?
    private var session: Session?

    init(authorityForFrame: @escaping (WKFrameInfo) -> WalletBrowserAuthority?) {
        self.authorityForFrame = authorityForFrame
    }

    @discardableResult
    func install(in configuration: WKWebViewConfiguration) -> Bool {
        // This guard must precede every read or mutation of the WebKit configuration.
        guard WalletNativeReleaseGates.providerBridgeReleaseQualified else { return false }
        let controller = configuration.userContentController
        controller.addScriptMessageHandler(self, contentWorld: .page, name: Self.handlerName)
        controller.addUserScript(
            WKUserScript(
                source: Self.providerScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true,
                in: .page
            )
        )
        return true
    }

    private func invalidateCurrent() {
        session = nil
    }

    func retryBootstrap(in webView: WKWebView) {
        guard WalletNativeReleaseGates.providerBridgeReleaseQualified else { return }
        webView.evaluateJavaScript("window.__hnsWalletMobileBootstrapV1?.()", in: nil, in: .page)
    }

    func emitEvent(
        authority: WalletProviderAuthority,
        nativePayload: Any,
        in webView: WKWebView
    ) {
        guard let current = session else { return }
        guard WalletNativeReleaseGates.walletRuntimeReleaseQualified else {
            invalidateCurrent()
            return
        }
        let currentAuthority = WalletProviderAuthority(
            browser: current.browser,
            walletSession: current.capabilities.walletSession,
            permissionGeneration: current.capabilities.permissionGeneration
        )
        // A delayed callback from an older provider session must not revoke a newer one.
        guard currentAuthority == authority else { return }
        guard let frame = try? WalletNativeEventProjectionV2.project(nativePayload) else {
            invalidateCurrent()
            return
        }
        let event = frame["event"] as? String
        guard event == "disconnect" || authority.browser.isCurrent(nowUnixMs: Self.currentUnixMs()) else {
            invalidateCurrent()
            return
        }
        webView.callAsyncJavaScript(
            "globalThis.__hnsWalletMobileDispatchV1?.(message)",
            arguments: ["message": frame],
            in: nil,
            in: .page
        ) { _ in }
        if event == "disconnect" { invalidateCurrent() }
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage,
        replyHandler: @escaping (Any?, String?) -> Void
    ) {
        guard message.name == Self.handlerName,
              message.frameInfo.isMainFrame,
              let sourceOrigin = Self.canonicalOrigin(message.frameInfo.securityOrigin),
              let authority = authorityForFrame(message.frameInfo),
              authority.origin == sourceOrigin,
              authority.isCurrent(nowUnixMs: Self.currentUnixMs()),
              let frame = message.body as? [String: Any],
              WalletProviderProtocolV1.hasExactProviderSchemaVersion(frame["schemaVersion"]) else {
            replyHandler(
                WalletProviderProtocolV1.response(
                    error: WalletProviderError(
                        code: "browserAuthorityDenied",
                        message: "Browser trust did not approve this exact main frame"
                    )
                ),
                nil
            )
            return
        }
        if frame["kind"] as? String == "initialize" {
            guard frame["origin"] as? String == authority.origin else {
                replyHandler(
                    WalletProviderProtocolV1.response(
                        error: WalletProviderError(
                            code: "originMismatch",
                            message: "Wallet frame origin does not match its source"
                        )
                    ),
                    nil
                )
                return
            }
            if session?.browser != authority { invalidateCurrent() }
            do {
                guard WalletNativeReleaseGates.walletRuntimeReleaseQualified else {
                    throw WalletProviderError(
                        code: "walletUnavailable",
                        message: "Mobile wallet ABI v2 is unavailable"
                    )
                }
                let capabilities = try WalletProviderProtocolV1.validateCapabilities(
                    wallet.capabilities(authority: authority)
                )
                if session?.browser != authority || session?.capabilities != capabilities {
                    session = Session(browser: authority, capabilities: capabilities, lastSequence: 0)
                }
                replyHandler(
                    WalletProviderProtocolV1.response(result: [
                        "providerApiVersion": WalletProviderProtocolV1.providerAPIVersion,
                        "methods": capabilities.methods.sorted(),
                    ]),
                    nil
                )
            } catch {
                invalidateCurrent()
                replyHandler(WalletProviderProtocolV1.response(error: error), nil)
            }
            return
        }
        do {
            let request = try WalletProviderProtocolV1.parseRequest(frame)
            guard var current = session, current.browser == authority else {
                throw WalletProviderError(code: "staleContext", message: "Wallet document binding is stale")
            }
            guard request.sequence > current.lastSequence else {
                throw WalletProviderError(code: "replay", message: "Wallet request sequence was replayed")
            }
            current.lastSequence = request.sequence
            guard current.seenRequestIDs.insert(request.requestID).inserted else {
                throw WalletProviderError(code: "replay", message: "Wallet request identifier was reused")
            }
            current.requestIDOrder.append(request.requestID)
            if current.requestIDOrder.count > Self.maximumSeenRequestIDs {
                current.seenRequestIDs.remove(current.requestIDOrder.removeFirst())
            }
            guard recordRate(
                session: &current,
                method: request.method,
                now: ProcessInfo.processInfo.systemUptime
            ) else {
                session = current
                throw WalletProviderError(code: "rateLimited", message: "Wallet request rate was exceeded")
            }
            session = current
            let fresh = try WalletProviderProtocolV1.validateCapabilities(
                wallet.capabilities(authority: current.browser)
            )
            guard fresh.walletSession == current.capabilities.walletSession else {
                invalidateCurrent()
                throw WalletProviderError(code: "walletSessionChanged", message: "Wallet session changed")
            }
            guard fresh.permissionGeneration == current.capabilities.permissionGeneration else {
                invalidateCurrent()
                throw WalletProviderError(
                    code: "permissionGenerationChanged",
                    message: "Wallet permissions changed"
                )
            }
            guard fresh.methods.contains(request.method) else {
                throw WalletProviderError(code: "permissionDenied", message: "Wallet method was not negotiated")
            }
            try WalletProviderProtocolV1.requireMethodReleaseQualified(request.method)
            let result = try wallet.request(
                authority: WalletProviderAuthority(
                    browser: current.browser,
                    walletSession: current.capabilities.walletSession,
                    permissionGeneration: current.capabilities.permissionGeneration
                ),
                request: request
            )
            replyHandler(WalletProviderProtocolV1.response(request: request, result: result), nil)
        } catch {
            let request = try? WalletProviderProtocolV1.parseRequest(frame)
            replyHandler(WalletProviderProtocolV1.response(request: request, error: error), nil)
        }
    }

    private func recordRate(session: inout Session, method: String, now: TimeInterval) -> Bool {
        session.globalRequestTimes.removeAll { now - $0 >= Self.rateWindowSeconds }
        var methodTimes = session.methodRequestTimes[method, default: []]
        methodTimes.removeAll { now - $0 >= Self.rateWindowSeconds }
        let methodLimit = Self.mutatingMethods.contains(method)
            ? Self.mutationRequestsPerMinute
            : Self.methodRequestsPerMinute
        guard session.globalRequestTimes.count < Self.globalRequestsPerMinute,
              methodTimes.count < methodLimit else {
            session.methodRequestTimes[method] = methodTimes
            return false
        }
        session.globalRequestTimes.append(now)
        methodTimes.append(now)
        session.methodRequestTimes[method] = methodTimes
        return true
    }

    private static func canonicalOrigin(_ origin: WKSecurityOrigin) -> String? {
        guard origin.protocol.lowercased() == "https", !origin.host.isEmpty else { return nil }
        let host = origin.host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return origin.port == 0 || origin.port == 443
            ? "https://\(host)"
            : "https://\(host):\(origin.port)"
    }

    private static func currentUnixMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1_000)
    }

    private static let maximumSeenRequestIDs = 512
    private static let globalRequestsPerMinute = 120
    private static let methodRequestsPerMinute = 60
    private static let mutationRequestsPerMinute = 12
    private static let rateWindowSeconds: TimeInterval = 60
    private static let mutatingMethods: Set<String> = [
        "wallet_enableModule", "wallet_disableModule", "wallet_requestPermissions",
        "wallet_revokePermissions", "hns_send", "hns_importKnownName", "hns_transferName",
        "hns_finalizeName", "hns_signTypedMessage", "asset_send",
        "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
        "nameMarket_acceptOffer", "nameMarket_finalizePurchase", "nameMarket_recoverName",
        "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
        "swap_acceptFill", "swap_redeem", "swap_refund",
    ]
    private static let providerScript = #"""
    (() => {
      'use strict';
      if (window.__hnsWalletMobileBootstrapV1) return;
      const bridge = window.webkit.messageHandlers.hnsWalletNativeV1;
      const listeners = new Map();
      const pending = new Map();
      const eventNames = new Set(['connect','disconnect','permissionsChanged','modulesChanged','accountsChanged','balancesChanged','transactionsChanged','namesChanged','nameMarketChanged','priceRoundChanged','marketIntentChanged','swapSessionChanged','walletLocked']);
      const retryDelays = [150, 350, 750, 1500];
      const maxPending = 16;
      const requestTimeoutMs = 90000;
      let sequence = 0;
      let provider = null;
      let retryIndex = 0;
      const announce = () => window.dispatchEvent(new CustomEvent('hns:announceProvider', { detail: { info: { id: 'org.handshake-rs.wallet.mobile', name: 'Shakescape Wallet', providerApiVersion: '1' }, provider } }));
      globalThis.__hnsWalletMobileDispatchV1 = (message) => {
        if (!message || message.schemaVersion !== 1 || message.kind !== 'event' || !eventNames.has(message.event)) return;
        if (message.event === 'disconnect') { for (const reject of pending.values()) reject(Object.assign(new Error('Wallet context disconnected'), { code: 'staleContext' })); pending.clear(); }
        for (const listener of listeners.get(message.event) ?? []) { try { listener(message.payload); } catch (_) {} }
      };
      window.__hnsWalletMobileBootstrapV1 = async () => {
        const initialized = await bridge.postMessage({ schemaVersion: 1, kind: 'initialize', origin: location.origin });
        if (initialized?.ok !== true || provider) {
          if (initialized?.error?.code === 'browserAuthorityDenied' && retryIndex < retryDelays.length) setTimeout(window.__hnsWalletMobileBootstrapV1, retryDelays[retryIndex++]);
          return;
        }
        retryIndex = 0;
        provider = Object.freeze({
          async request(args) {
            if (!args || typeof args.method !== 'string') throw new TypeError('method required');
            if (args.method.length > 96) throw new TypeError('method too long');
            if (pending.size >= maxPending) throw Object.assign(new Error('Too many pending wallet requests'), { code: 'rateLimited' });
            const requestId = 'mobile-' + Date.now().toString(36) + '-' + (++sequence).toString(36);
            const frame = { schemaVersion: 1, kind: 'request', requestId, sequence, method: args.method, params: args.params ?? null };
            let encoded;
            try { encoded = JSON.stringify(frame); } catch (_) { throw Object.assign(new Error('Invalid wallet request'), { code: 'invalidRequest' }); }
            if (new TextEncoder().encode(encoded).byteLength > 65536) throw Object.assign(new Error('Wallet request too large'), { code: 'requestTooLarge' });
            let timeout;
            const interruption = new Promise((_, reject) => {
              pending.set(requestId, reject);
              timeout = setTimeout(() => reject(Object.assign(new Error('Wallet request timed out'), { code: 'staleContext' })), requestTimeoutMs);
            });
            try {
              const response = await Promise.race([bridge.postMessage(frame), interruption]);
              if (response?.ok === true) return response.result;
              throw Object.assign(new Error(response?.error?.message ?? 'Wallet request failed'), { code: response?.error?.code });
            } finally {
              clearTimeout(timeout);
              pending.delete(requestId);
            }
          },
          on(name, listener) { if (!eventNames.has(name)) throw new TypeError('unsupported event'); if (typeof listener !== 'function') throw new TypeError('listener required'); const values = listeners.get(name) ?? new Set(); values.add(listener); listeners.set(name, values); return provider; },
          removeListener(name, listener) { listeners.get(name)?.delete(listener); return provider; }
        });
        window.addEventListener('hns:requestProvider', announce);
        announce();
      };
      window.__hnsWalletMobileBootstrapV1().catch(() => {});
    })();
    """#
}
