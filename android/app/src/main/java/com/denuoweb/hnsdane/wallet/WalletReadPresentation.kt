package com.denuoweb.hnsdane.wallet

import java.math.BigInteger

internal fun formatHnsBaseUnits(baseUnits: String): String {
    val value = BigInteger(baseUnits)
    require(value.signum() >= 0)
    val (whole, fraction) = value.divideAndRemainder(HNS_BASE_UNITS)
    if (fraction.signum() == 0) return whole.toString()
    val fractional = fraction.toString().padStart(HNS_DECIMAL_PLACES, '0').trimEnd('0')
    return "${whole}.${fractional}"
}

/** Parses exact user-facing HNS decimal text into canonical integer base units. */
internal fun parsePositiveHnsToBaseUnits(exact: CharSequence): String? {
    if (exact.isEmpty() || exact.length > MAX_HNS_INPUT_CHARACTERS) return null
    // A leading fractional separator is conventional wallet input. Normalize
    // it before the exact grammar and integer conversion so `.1` has the same
    // unambiguous base-unit meaning as `0.1`, without admitting signs,
    // whitespace, grouping separators, or a bare decimal point.
    val text = exact.toString().let { input ->
        if (input.startsWith('.')) "0$input" else input
    }
    if (!HNS_DECIMAL_INPUT.matches(text)) return null
    val separator = text.indexOf('.')
    val whole = if (separator < 0) text else text.substring(0, separator)
    val fraction = if (separator < 0) "" else text.substring(separator + 1)
    val baseUnits = runCatching {
        BigInteger(whole)
            .multiply(HNS_BASE_UNITS)
            .add(BigInteger(fraction.padEnd(HNS_DECIMAL_PLACES, '0').ifEmpty { "0" }))
    }.getOrNull() ?: return null
    return baseUnits
        .takeIf { it.signum() > 0 && it <= MAX_HNS_BASE_UNITS }
        ?.toString()
}

internal fun NativeWalletTransaction.displayAmount(): String = buildString {
    if (negative) append('-')
    append(formatHnsBaseUnits(magnitudeBaseUnits))
    append(" HNS")
}

internal fun walletReadCodeLabel(value: String): String = buildString(value.length + 8) {
    value.forEachIndexed { index, character ->
        when {
            character == '_' -> append(' ')
            character.isUpperCase() -> {
                if (index > 0) append(' ')
                append(character.lowercaseChar())
            }
            else -> append(character)
        }
    }
}

private const val HNS_DECIMAL_PLACES = 6
private const val MAX_HNS_INPUT_CHARACTERS = 46
private val HNS_BASE_UNITS = BigInteger.TEN.pow(HNS_DECIMAL_PLACES)
private val MAX_HNS_BASE_UNITS = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
private val HNS_DECIMAL_INPUT = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?")
