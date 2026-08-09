package com.denuoweb.hnsdane.net

/**
 * Admission for hostname requests that require authenticated dual-root resolution.
 *
 * WebView must never fall back to a synchronous JNI gateway call when the
 * process proxy or the authoritative HNS tree root is unavailable. Such a call
 * has no navigation-scoped cancellation boundary. The process proxy owns
 * connection cancellation and is therefore the only admitted WebView route.
 */
internal enum class ProtectedWebViewRequestAction {
    ProcessProxy,
    Block,
}

internal fun protectedWebViewRequestAction(
    proxyAvailable: Boolean,
    treeRootReady: Boolean,
): ProtectedWebViewRequestAction =
    if (proxyAvailable && treeRootReady) {
        ProtectedWebViewRequestAction.ProcessProxy
    } else {
        ProtectedWebViewRequestAction.Block
    }
