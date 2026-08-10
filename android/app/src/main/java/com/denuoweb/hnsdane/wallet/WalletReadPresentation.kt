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
private val HNS_BASE_UNITS = BigInteger.TEN.pow(HNS_DECIMAL_PLACES)
