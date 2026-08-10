package com.denuoweb.hnsdane.ui

import java.net.URI
import java.util.Locale

/**
 * Standard-browser presentation for the omnibox: show only the host while the
 * field is at rest, and the full URL only while the user is editing. The start
 * page displays as an empty field so the search hint shows instead of an
 * internal appassets URL.
 */
internal object OmniboxDisplay {

    private const val START_PAGE_HOST = "appassets.androidplatform.net"

    fun displayText(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "about:blank") {
            return ""
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return trimmed
        if (scheme != "http" && scheme != "https") {
            return trimmed
        }
        val host = uri.host
            ?.trim()
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }
            ?: return trimmed
        if (host.equals(START_PAGE_HOST, ignoreCase = true)) {
            return ""
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val portPart = uri.port
            .takeIf { it in 1..65535 && it != defaultPort }
            ?.let { ":$it" }
            .orEmpty()
        return host.lowercase(Locale.US) + portPart
    }
}
