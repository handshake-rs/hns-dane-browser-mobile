package com.denuoweb.hnsdane.core

/** Browser namespace decisions returned by the shared Rust resolver policy. */
enum class BrowserNamespaceClass {
    Hns,
    Icann,
    NativeGateway,
    Invalid,
    Unavailable,
}

fun interface BrowserNamespacePolicy {
    fun classifyHost(host: String): BrowserNamespaceClass
}

fun interface BrowserWebSocketScopePolicySource {
    /** Returns the complete document-start policy emitted by shared Rust. */
    fun webSocketScopePolicyScript(): String?
}

enum class NativeGatewayHostDecision {
    Required,
    Direct,
    Block,
}

/**
 * Android routing helpers around the shared Rust namespace decision.
 *
 * This object deliberately contains no IANA list, special-use suffix list, or
 * independent namespace classifier. Rust supplies syntax/IP admission, and an
 * unavailable or malformed Rust result remains fail-closed.
 */
object HnsHostPolicy {
    fun nativeGatewayDecision(
        host: String,
        namespacePolicy: BrowserNamespacePolicy,
    ): NativeGatewayHostDecision =
        when (namespacePolicy.classifyHost(host)) {
            BrowserNamespaceClass.Hns,
            BrowserNamespaceClass.NativeGateway,
            -> NativeGatewayHostDecision.Required
            BrowserNamespaceClass.Icann ->
                if (isCanonicalIpLiteral(host)) {
                    NativeGatewayHostDecision.Direct
                } else {
                    // A legacy suffix classifier cannot bypass dual-root DNS.
                    NativeGatewayHostDecision.Required
                }
            BrowserNamespaceClass.Invalid,
            BrowserNamespaceClass.Unavailable,
            -> NativeGatewayHostDecision.Block
        }

    /** A shell cannot determine the selected root before authenticated dual resolution. */
    fun requiresHnsResolution(
        @Suppress("UNUSED_PARAMETER") host: String,
        @Suppress("UNUSED_PARAMETER") namespacePolicy: BrowserNamespacePolicy,
    ): Boolean = false

    fun requiresNativeGatewayResolution(
        host: String,
        namespacePolicy: BrowserNamespacePolicy,
    ): Boolean = nativeGatewayDecision(host, namespacePolicy) == NativeGatewayHostDecision.Required

    fun isNativeGatewayHost(
        host: String,
        namespacePolicy: BrowserNamespacePolicy,
    ): Boolean = nativeGatewayDecision(host, namespacePolicy) == NativeGatewayHostDecision.Required

}
