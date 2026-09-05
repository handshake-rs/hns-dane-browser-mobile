package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.HostnameAscii

internal const val MAX_MULTIPLE_WALLET_NAME_IMPORTS = 10_000
internal const val MAX_CANONICAL_HANDSHAKE_NAME_BYTES = 63
internal const val MAX_MULTIPLE_NAME_INPUT_CHARACTERS =
    MAX_MULTIPLE_WALLET_NAME_IMPORTS * MAX_CANONICAL_HANDSHAKE_NAME_BYTES +
        MAX_MULTIPLE_WALLET_NAME_IMPORTS - 1

private val RESERVED_HANDSHAKE_NAMES = setOf("example", "invalid", "local", "localhost", "test")

/**
 * Parses the exact review list accepted by the multi-name field. ASCII spaces
 * are the only separators; the UI never silently converts commas or line
 * breaks into a different import request. Native consensus validation remains
 * authoritative at the atomic import boundary.
 */
internal fun parseSpaceSeparatedWalletNames(text: String): List<String>? {
    if (
        text.isEmpty() || text.length > MAX_MULTIPLE_NAME_INPUT_CHARACTERS ||
        text.any { character -> character.isWhitespace() && character != ' ' }
    ) {
        return null
    }
    val enteredNames = text
        .trim(' ')
        .split(' ')
        .filter(String::isNotEmpty)
    if (enteredNames.isEmpty() || enteredNames.size > MAX_MULTIPLE_WALLET_NAME_IMPORTS) {
        return null
    }
    val canonicalNames = enteredNames.map { canonicalHandshakeNameImportText(it) ?: return null }
    return canonicalNames.takeIf { it.toSet().size == it.size }
}

/** Converts one Unicode U-label to the exact ASCII A-label stored on chain. */
internal fun canonicalHandshakeNameImportText(name: String): String? {
    if (name.isEmpty() || name.any { it == '.' || it.isWhitespace() }) return null
    if (name.all { it.code < 0x80 }) return name.takeIf(::isCanonicalHandshakeNameText)
    val canonical = HostnameAscii.toAscii(name) ?: return null
    return canonical.takeIf { '.' !in it && isCanonicalHandshakeNameText(it) }
}

internal fun displayHandshakeNameText(name: String): String = HostnameAscii.toUnicode(name)

internal fun isCanonicalHandshakeNameText(name: String): Boolean {
    if (
        name.isEmpty() || name.length > MAX_CANONICAL_HANDSHAKE_NAME_BYTES ||
        name in RESERVED_HANDSHAKE_NAMES
    ) {
        return false
    }
    return name.indices.all { index ->
        when (val character = name[index]) {
            in '0'..'9', in 'a'..'z' -> true
            '-', '_' -> index != 0 && index + 1 != name.length
            else -> false
        }
    }
}
