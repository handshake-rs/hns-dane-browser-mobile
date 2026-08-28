package com.denuoweb.hnsdane.wallet

/**
 * Parses the optional HNS account birthday used by wallet restoration.
 *
 * An empty field deliberately means genesis (block 0). The direct light index
 * stores heights as unsigned 32-bit values, so rejecting anything larger here
 * prevents a restore that can be persisted but cannot later open its scanner.
 */
internal fun parseWalletRestoreBirthday(value: CharSequence?): Long? {
    val text = value?.toString().orEmpty()
    if (text.isEmpty()) return 0L
    if (text.length > MAX_HNS_BIRTHDAY_DIGITS || text.any { it !in '0'..'9' }) return null
    return text.toLongOrNull()?.takeIf { it <= MAX_HNS_BIRTHDAY_HEIGHT }
}

internal const val MAX_HNS_BIRTHDAY_HEIGHT = 0xffff_ffffL
private const val MAX_HNS_BIRTHDAY_DIGITS = 10
