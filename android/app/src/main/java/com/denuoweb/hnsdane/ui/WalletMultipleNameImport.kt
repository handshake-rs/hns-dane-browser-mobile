package com.denuoweb.hnsdane.ui

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
    val names = text
        .trim(' ')
        .split(' ')
        .filter(String::isNotEmpty)
    return names.takeIf {
        it.isNotEmpty() && it.size <= MAX_MULTIPLE_WALLET_NAME_IMPORTS &&
            it.toSet().size == it.size && it.all(::isCanonicalHandshakeNameText)
    }
}

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
