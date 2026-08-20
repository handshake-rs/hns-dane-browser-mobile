package com.denuoweb.hnsdane.wallet

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * An opt-in WebView bridge. The owner must supply authority only after its exact main-frame
 * navigation has a current browser trust result. Merely installing this adapter grants nothing.
 */
internal class AndroidWalletProviderBridge(
    private val authorityForOrigin: (Uri) -> WalletBrowserAuthority?,
) : AutoCloseable {
    // Intentionally not injectable: approval UI and permission persistence must land before the
    // product can replace this source-level fail-closed adapter with a generated ABI binding.
    private val wallet: MobileWalletAbiV2 = UnavailableMobileWalletAbiV2

    private data class Session(
        val browser: WalletBrowserAuthority,
        val capabilities: WalletCapabilitiesV2,
        var lastSequence: Long,
        var reply: JavaScriptReplyProxy,
        val seenRequestIds: LinkedHashSet<String> = linkedSetOf(),
        val globalRequestTimes: ArrayDeque<Long> = ArrayDeque(),
        val methodRequestTimes: MutableMap<String, ArrayDeque<Long>> = mutableMapOf(),
    )

    private var webView: WebView? = null
    private var scriptHandler: ScriptHandler? = null
    private var session: Session? = null

    fun install(webView: WebView): Boolean {
        // This immutable release gate is checked before any WebView or bridge state is mutated.
        if (!MobileWalletProviderProtocol.PROVIDER_BRIDGE_RELEASE_QUALIFIED) return false
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return false
        check(this.webView == null) { "Wallet provider bridge is already installed" }
        this.webView = webView
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            setOf("https://*"),
            WebViewCompat.WebMessageListener(::onPostMessage),
        )
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            PROVIDER_SCRIPT,
            setOf("https://*"),
        )
        return true
    }

    private fun invalidateCurrent() {
        session = null
    }

    fun retryBootstrap() {
        if (!MobileWalletProviderProtocol.PROVIDER_BRIDGE_RELEASE_QUALIFIED) return
        webView?.evaluateJavascript("window.__hnsWalletMobileBootstrapV1?.()", null)
    }

    fun emitEvent(authority: WalletProviderAuthority, nativePayload: JSONObject) {
        val current = session ?: return
        if (!MobileWalletProviderProtocol.WALLET_RUNTIME_RELEASE_QUALIFIED) {
            invalidateCurrent()
            return
        }
        if (
            current.browser != authority.browser ||
            current.capabilities.walletSession != authority.walletSession ||
            current.capabilities.permissionGeneration != authority.permissionGeneration
        ) {
            // A delayed callback from an older provider session must not revoke a newer one.
            return
        }
        val encoded = runCatching { MobileWalletProviderProtocol.event(nativePayload) }
            .getOrElse {
                invalidateCurrent()
                return
            }
        val disconnect = nativePayload.opt("event") == "disconnect"
        if (!disconnect && !authority.browser.isCurrent(System.currentTimeMillis())) {
            invalidateCurrent()
            return
        }
        if (disconnect) {
            try {
                current.reply.postMessage(encoded)
            } finally {
                invalidateCurrent()
            }
        } else {
            current.reply.postMessage(encoded)
        }
    }

    override fun close() {
        invalidateCurrent()
        scriptHandler?.remove()
        scriptHandler = null
        webView?.let { runCatching { WebViewCompat.removeWebMessageListener(it, BRIDGE_NAME) } }
        webView = null
    }

    private fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        val sourceOriginValue = canonicalHttpsOrigin(sourceOrigin)
        if (view !== webView || !isMainFrame || sourceOriginValue == null) {
            reply.postMessage(error("invalidOrigin", "Wallet provider requires an exact HTTPS main frame"))
            return
        }
        val authority = authorityForOrigin(sourceOrigin)
        if (
            authority == null || authority.origin != sourceOriginValue ||
            !authority.isCurrent(System.currentTimeMillis())
        ) {
            reply.postMessage(error("browserAuthorityDenied", "Browser trust did not approve this document"))
            return
        }
        val raw = message.data ?: ""
        val frame = runCatching { JSONObject(raw) }.getOrNull()
        if (frame == null || !hasExactProviderSchema(frame)) {
            reply.postMessage(error("unsupportedVersion", "Unsupported wallet bridge frame"))
            return
        }
        if (isExactInitializeFrame(frame)) {
            val frameOrigin = exactStringField(frame, "origin")
            if (frameOrigin == null || frameOrigin != authority.origin) {
                reply.postMessage(error("originMismatch", "Wallet frame origin does not match source"))
                return
            }
            if (session?.browser != authority) invalidateCurrent()
            runCatching {
                val capabilities = MobileWalletProviderProtocol.validateCapabilities(
                    wallet.capabilities(authority),
                )
                val current = session
                session = if (current?.browser == authority && current.capabilities == capabilities) {
                    current.apply { this.reply = reply }
                } else {
                    Session(authority, capabilities, 0, reply)
                }
                MobileWalletProviderProtocol.response(
                    request = null,
                    result = initializationResult(capabilities.methods),
                )
            }.fold(
                onSuccess = { reply.postMessage(it) },
                onFailure = {
                    invalidateCurrent()
                    reply.postMessage(error(it))
                },
            )
            return
        }
        val current = session
        if (current == null || current.browser != authority) {
            reply.postMessage(error("staleContext", "Wallet provider document binding is stale"))
            return
        }
        val request = runCatching { MobileWalletProviderProtocol.parseRequest(raw) }
            .getOrElse {
                reply.postMessage(error(it))
                return
            }
        if (request.sequence <= current.lastSequence) {
            reply.postMessage(error("replay", "Wallet provider sequence was already observed", request))
            return
        }
        current.lastSequence = request.sequence
        if (!current.seenRequestIds.add(request.requestId)) {
            reply.postMessage(error("replay", "Wallet provider request identifier was reused", request))
            return
        }
        while (current.seenRequestIds.size > MAX_SEEN_REQUEST_IDS) {
            current.seenRequestIds.remove(current.seenRequestIds.first())
        }
        if (!recordRate(current, request.method, SystemClock.elapsedRealtime())) {
            reply.postMessage(error("rateLimited", "Wallet provider request rate was exceeded", request))
            return
        }
        runCatching {
            val fresh = MobileWalletProviderProtocol.validateCapabilities(
                wallet.capabilities(current.browser),
            )
            if (fresh.walletSession != current.capabilities.walletSession) {
                throw WalletProviderException("walletSessionChanged", "Wallet session changed")
            }
            if (fresh.permissionGeneration != current.capabilities.permissionGeneration) {
                throw WalletProviderException("permissionGenerationChanged", "Wallet permissions changed")
            }
            if (request.method !in fresh.methods) {
                throw WalletProviderException("permissionDenied", "Wallet method was not negotiated")
            }
            MobileWalletProviderProtocol.requireMethodReleaseQualified(request.method)
            val result = wallet.request(
                WalletProviderAuthority(
                    browser = current.browser,
                    walletSession = current.capabilities.walletSession,
                    permissionGeneration = current.capabilities.permissionGeneration,
                ),
                request,
            )
            MobileWalletProviderProtocol.response(request, result)
        }.fold(
            onSuccess = reply::postMessage,
            onFailure = { reply.postMessage(error(it, request)) },
        )
    }

    private fun recordRate(session: Session, method: String, now: Long): Boolean {
        prune(session.globalRequestTimes, now)
        val methodTimes = session.methodRequestTimes.getOrPut(method, ::ArrayDeque)
        prune(methodTimes, now)
        val methodLimit = if (method in MUTATING_METHODS) MUTATION_REQUESTS_PER_MINUTE else METHOD_REQUESTS_PER_MINUTE
        if (
            session.globalRequestTimes.size >= GLOBAL_REQUESTS_PER_MINUTE ||
            methodTimes.size >= methodLimit
        ) return false
        session.globalRequestTimes.addLast(now)
        methodTimes.addLast(now)
        return true
    }

    private fun prune(values: ArrayDeque<Long>, now: Long) {
        while (values.isNotEmpty() && now - values.first() >= RATE_WINDOW_MILLIS) {
            values.removeFirst()
        }
    }

    private fun error(
        code: String,
        message: String,
        request: WalletProviderRequest? = null,
    ): String = MobileWalletProviderProtocol.response(
        request = request,
        error = WalletProviderException(code, message),
    )

    private fun error(error: Throwable, request: WalletProviderRequest? = null): String =
        MobileWalletProviderProtocol.response(request = request, error = error)

    companion object {
        // This page bridge name follows provider schema v1; its private adapter speaks ABI v2.
        private const val BRIDGE_NAME = "hnsWalletNativeV1"
        private const val MAX_SEEN_REQUEST_IDS = 512
        private const val GLOBAL_REQUESTS_PER_MINUTE = 120
        private const val METHOD_REQUESTS_PER_MINUTE = 60
        private const val MUTATION_REQUESTS_PER_MINUTE = 12
        private const val RATE_WINDOW_MILLIS = 60_000L
        private val MUTATING_METHODS = setOf(
            "wallet_enableModule", "wallet_disableModule", "wallet_requestPermissions",
            "wallet_revokePermissions", "hns_send", "hns_importKnownName", "hns_transferName",
            "hns_finalizeName", "hns_signTypedMessage", "asset_send",
            "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
            "nameMarket_acceptOffer", "nameMarket_finalizePurchase", "nameMarket_recoverName",
            "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
            "swap_acceptFill", "swap_redeem", "swap_refund",
        )

        internal fun canonicalHttpsOrigin(uri: Uri): String? {
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
            val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.port
            return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
        }

        internal fun hasExactProviderSchema(frame: JSONObject): Boolean =
            frame.opt("schemaVersion") == MobileWalletProviderProtocol.SCHEMA_VERSION

        internal fun isExactInitializeFrame(frame: JSONObject): Boolean =
            frame.opt("kind") is String && frame.opt("kind") == "initialize"

        internal fun exactStringField(frame: JSONObject, field: String): String? =
            frame.opt(field) as? String

        internal fun initializationResult(methods: Set<String>): JSONObject =
            JSONObject()
                .put("providerApiVersion", 1)
                .put("methods", JSONArray(methods.sorted()))

        private val PROVIDER_SCRIPT = """
            (() => {
              'use strict';
              if (window.__hnsWalletMobileBootstrapV1) return;
              const bridge = window.hnsWalletNativeV1;
              const pending = new Map();
              const listeners = new Map();
              const eventNames = new Set(['connect','disconnect','permissionsChanged','modulesChanged','accountsChanged','balancesChanged','transactionsChanged','namesChanged','nameMarketChanged','priceRoundChanged','marketIntentChanged','swapSessionChanged','walletLocked']);
              const maxPending = 16;
              const requestTimeoutMs = 90000;
              let sequence = 0;
              let provider = null;
              bridge.onmessage = (event) => {
                let message;
                try { message = JSON.parse(event.data); } catch (_) { return; }
                if (message.schemaVersion !== 1) return;
                if (message.kind === 'initialized' && message.ok === true && !provider) {
                  provider = Object.freeze({
                    request(args) {
                      if (!args || typeof args.method !== 'string') return Promise.reject(new TypeError('method required'));
                      if (args.method.length > 96) return Promise.reject(new TypeError('method too long'));
                      if (pending.size >= maxPending) return Promise.reject(Object.assign(new Error('Too many pending wallet requests'), { code: 'rateLimited' }));
                      const id = 'mobile-' + Date.now().toString(36) + '-' + (++sequence).toString(36);
                      return new Promise((resolve, reject) => {
                        let encoded;
                        try { encoded = JSON.stringify({ schemaVersion: 1, kind: 'request', requestId: id, sequence, method: args.method, params: args.params ?? null }); } catch (_) { reject(Object.assign(new Error('Invalid wallet request'), { code: 'invalidRequest' })); return; }
                        if (new TextEncoder().encode(encoded).byteLength > 65536) { reject(Object.assign(new Error('Wallet request too large'), { code: 'requestTooLarge' })); return; }
                        const timeout = setTimeout(() => { pending.delete(id); reject(Object.assign(new Error('Wallet request timed out'), { code: 'staleContext' })); }, requestTimeoutMs);
                        pending.set(id, { resolve, reject, sequence, timeout });
                        bridge.postMessage(encoded);
                      });
                    },
                    on(name, listener) { if (!eventNames.has(name)) throw new TypeError('unsupported event'); if (typeof listener !== 'function') throw new TypeError('listener required'); const values = listeners.get(name) ?? new Set(); values.add(listener); listeners.set(name, values); return provider; },
                    removeListener(name, listener) { listeners.get(name)?.delete(listener); return provider; }
                  });
                  const announce = () => window.dispatchEvent(new CustomEvent('hns:announceProvider', { detail: { info: { id: 'org.handshake-rs.wallet.mobile', name: 'Shakescape Wallet', providerApiVersion: '1' }, provider } }));
                  window.addEventListener('hns:requestProvider', announce);
                  announce();
                } else if (message.kind === 'response') {
                  const item = pending.get(message.requestId);
                  if (!item || item.sequence !== message.sequence) return;
                  pending.delete(message.requestId);
                  clearTimeout(item.timeout);
                  message.ok === true ? item.resolve(message.result) : item.reject(Object.assign(new Error(message.error?.message ?? 'Wallet request failed'), { code: message.error?.code }));
                } else if (message.kind === 'event') {
                  if (!eventNames.has(message.event)) return;
                  if (message.event === 'disconnect') { for (const item of pending.values()) { clearTimeout(item.timeout); item.reject(Object.assign(new Error('Wallet context disconnected'), { code: 'staleContext' })); } pending.clear(); }
                  for (const listener of listeners.get(message.event) ?? []) { try { listener(message.payload); } catch (_) {} }
                }
              };
              window.__hnsWalletMobileBootstrapV1 = () => bridge.postMessage(JSON.stringify({ schemaVersion: 1, kind: 'initialize', origin: location.origin }));
              window.__hnsWalletMobileBootstrapV1();
            })();
        """.trimIndent()
    }
}
