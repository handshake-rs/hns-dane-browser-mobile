package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.HostnameAscii
import java.net.URI
import java.util.Locale

/** Standard-browser presentation for the omnibox while it is not being edited. */
internal object OmniboxDisplay {
    private const val START_PAGE_HOST = "appassets.androidplatform.net"

    fun editingText(url: String?): String {
        val value = url.orEmpty()
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        val host = uri.host ?: return value
        val displayHost = HostnameAscii.toUnicode(host)
        if (displayHost == host) return value
        val authority = uri.rawAuthority ?: return value
        val displayAuthority = authority.replaceFirst(host, displayHost)
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "${uri.scheme}://$displayAuthority$path$query$fragment"
    }

    fun displayText(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "about:blank") return ""

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return trimmed
        if (scheme != "http" && scheme != "https") return trimmed
        if (uri.rawUserInfo != null) return trimmed

        val host = uri.host
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
            ?: return trimmed
        if (
            scheme == "https" &&
            uri.port in setOf(-1, 443) &&
            host.equals(START_PAGE_HOST, ignoreCase = true) &&
            uri.rawPath == "/assets/start.html" &&
            uri.rawQuery == null &&
            uri.rawFragment == null
        ) {
            return ""
        }

        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port
            .takeIf { it in 1..65535 && it != defaultPort }
            ?.let { ":$it" }
            .orEmpty()
        val path = uri.rawPath
            ?.takeUnless { it.isEmpty() || it == "/" }
            .orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val displayHost = HostnameAscii.toUnicode(host.lowercase(Locale.US))
        return displayHost + port + path + query + fragment
    }
}
