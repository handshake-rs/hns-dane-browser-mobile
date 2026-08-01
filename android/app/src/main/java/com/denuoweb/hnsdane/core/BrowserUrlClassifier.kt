package com.denuoweb.hnsdane.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class BrowserTargetKind {
    LocalAsset,
    ExactUrl,
    HnsName,
    NativeGateway,
    Blocked,
    Search,
}

data class BrowserTarget(
    val kind: BrowserTargetKind,
    val url: String,
    val displayHost: String?,
)

internal fun isAndroidWebViewAssetHost(host: String?): Boolean =
    host.equals(ANDROID_WEBVIEW_ASSET_HOST, ignoreCase = true)

internal fun isCanonicalIpLiteral(host: String?): Boolean {
    val value = host
        ?.trim()
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?: return false
    if (value.contains(':')) {
        return value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
    }
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
            octet.all(Char::isDigit) &&
            octet.toIntOrNull()?.let { it in 0..255 && it.toString() == octet } == true
    }
}

class BrowserUrlClassifier(
    private val namespacePolicy: BrowserNamespacePolicy,
    private val searchBaseUrl: String = "https://duckduckgo.com/?q=",
) {
    fun classify(input: String): BrowserTarget {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return search(trimmed)
        }

        if (trimmed.any(Char::isWhitespace)) {
            return search(trimmed)
        }

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return exact(trimmed)
        }

        if ("://" in trimmed) {
            return search(trimmed)
        }

        val hostCandidate = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostCandidate.isBlank()) {
            return search(trimmed)
        }

        val asciiHost = HostnameAscii.toAscii(hostCandidate)
            ?: return search(trimmed)

        if (!isValidHost(asciiHost)) {
            return search(trimmed)
        }

        val suffix = trimmed.removePrefix(hostCandidate)
        val normalizedSuffix = if (suffix.isEmpty()) "/" else suffix
        val kind = targetKindForHost(
            asciiHost,
            localAssetRequest = normalizedSuffix
                .substringBefore('?')
                .substringBefore('#')
                .startsWith(ANDROID_WEBVIEW_ASSET_PATH_PREFIX),
        )
        val scheme = "https"
        val url = "$scheme://$asciiHost$normalizedSuffix"
        return BrowserTarget(kind, url, asciiHost)
    }

    private fun exact(url: String): BrowserTarget {
        val uri = runCatching { URI(url) }.getOrNull() ?: return search(url)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return search(url)
        }
        val authority = uri.httpAuthority()
            ?: return search(url)
        val host = authority.host.takeIf(::isValidHttpHost)
            ?: return search(url)
        val kind = targetKindForHost(
            host,
            localAssetRequest =
                scheme == "https" &&
                    authority.portSuffix in setOf("", ":443") &&
                    uri.rawPath.orEmpty().startsWith(ANDROID_WEBVIEW_ASSET_PATH_PREFIX),
        )
        return BrowserTarget(kind, uri.withAuthority(authority) ?: return search(url), host)
    }

    private fun targetKindForHost(
        host: String,
        localAssetRequest: Boolean = false,
    ): BrowserTargetKind {
        if (isAndroidWebViewAssetHost(host)) {
            // WebViewAssetLoader owns this synthetic HTTPS origin locally. It
            // must never enter DNS or the process-wide network proxy. Block
            // every spelling outside its exact default-port /assets/ surface.
            return if (localAssetRequest) {
                BrowserTargetKind.LocalAsset
            } else {
                BrowserTargetKind.Blocked
            }
        }
        // The Rust result is syntax/IP admission only. A legacy HNS/ICANN
        // name class can never select a root here: every non-IP DNS name
        // still enters the retained dual-root gateway.
        return when (namespacePolicy.classifyHost(host)) {
            BrowserNamespaceClass.Hns,
            BrowserNamespaceClass.NativeGateway,
            -> BrowserTargetKind.NativeGateway
            BrowserNamespaceClass.Icann ->
                if (isCanonicalIpLiteral(host)) {
                    BrowserTargetKind.ExactUrl
                } else {
                    BrowserTargetKind.NativeGateway
                }
            BrowserNamespaceClass.Invalid,
            BrowserNamespaceClass.Unavailable,
            -> BrowserTargetKind.Blocked
        }
    }

    private fun search(query: String): BrowserTarget {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchHost = runCatching {
            URI(searchBaseUrl).httpAuthority()?.host
        }.getOrNull()
        return BrowserTarget(BrowserTargetKind.Search, searchBaseUrl + encoded, searchHost)
    }

    private fun isValidHost(host: String): Boolean {
        if (host.length > 253 || host.startsWith(".") || host.endsWith(".")) {
            return false
        }

        return host.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                !label.startsWith("-") &&
                !label.endsWith("-") &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun isValidHttpHost(host: String): Boolean {
        if (host.contains(':')) {
            return host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
        }

        return isValidHost(host)
    }

    private fun URI.httpAuthority(): ParsedHttpAuthority? {
        val authority = rawAuthority ?: return null
        if (authority.isBlank() || authority.contains('@')) {
            return null
        }

        val hostPart = if (authority.startsWith("[")) {
            val endBracket = authority.indexOf(']')
            if (endBracket <= 1) {
                return null
            }
            val remainder = authority.substring(endBracket + 1)
            if (remainder.isNotEmpty() && !isValidPortSuffix(remainder)) {
                return null
            }
            authority.substring(1, endBracket)
        } else {
            val colonCount = authority.count { it == ':' }
            if (colonCount > 1) {
                return null
            }
            if (colonCount == 1) {
                val separator = authority.indexOf(':')
                val remainder = authority.substring(separator)
                if (!isValidPortSuffix(remainder)) {
                    return null
                }
                authority.substring(0, separator)
            } else {
                authority
            }
        }

        val host = normalizeHost(hostPart) ?: return null
        val portSuffix = if (authority.startsWith("[")) {
            authority.substring(authority.indexOf(']') + 1)
        } else if (authority.count { it == ':' } == 1) {
            authority.substring(authority.indexOf(':'))
        } else {
            ""
        }
        return ParsedHttpAuthority(host, portSuffix)
    }

    private fun normalizeHost(host: String): String? {
        return HostnameAscii.toAscii(host)
    }

    private fun isValidPortSuffix(value: String): Boolean =
        value.length > 1 &&
            value[0] == ':' &&
            value.drop(1).toIntOrNull()?.let { it in 1..65535 } == true

    private fun URI.withAuthority(authority: ParsedHttpAuthority): String? {
        val scheme = scheme?.lowercase(Locale.US) ?: return null
        val host = if (authority.host.contains(':')) "[${authority.host}]" else authority.host
        val path = rawPath.orEmpty()
        val query = rawQuery?.let { "?$it" }.orEmpty()
        val fragment = rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$host${authority.portSuffix}$path$query$fragment"
    }

    private data class ParsedHttpAuthority(
        val host: String,
        val portSuffix: String,
    )
}

private const val ANDROID_WEBVIEW_ASSET_HOST = "appassets.androidplatform.net"
private const val ANDROID_WEBVIEW_ASSET_PATH_PREFIX = "/assets/"
