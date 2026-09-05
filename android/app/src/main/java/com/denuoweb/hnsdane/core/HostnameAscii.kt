package com.denuoweb.hnsdane.core

import java.net.IDN
import java.util.Locale

internal object HostnameAscii {
    fun toAscii(host: String): String? {
        // Keep the expensive normalization and IDN/punycode paths behind a raw-input
        // bound. Web content can supply this value, and IDN.toASCII is not intended
        // to process arbitrarily large strings.
        if (host.isEmpty() || host.length > MAX_RAW_HOST_CHARS) {
            return null
        }
        val normalized = host
            .removeSurrounding("[", "]")
            .replace('\u3002', '.')
            .replace('\uFF0E', '.')
            .replace('\uFF61', '.')
        if (
            normalized.isBlank() ||
            normalized.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }
        ) {
            return null
        }

        val labels = normalized.split('.')
        if (
            labels.size > MAX_LABELS ||
            labels.dropLast(if (normalized.endsWith('.')) 1 else 0).any {
                it.isEmpty() || it.length > MAX_RAW_LABEL_CHARS
            }
        ) {
            return null
        }

        runCatching { IDN.toASCII(normalized).lowercase(Locale.US) }
            .getOrNull()
            ?.takeIf(::isValidAsciiHost)
            ?.let { return it }

        return runCatching { fallbackToAscii(labels, normalized.endsWith('.')) }
            .getOrNull()
            ?.takeIf(::isValidAsciiHost)
    }

    /** Human-facing Unicode spelling for a canonical ASCII DNS host. */
    fun toUnicode(host: String): String {
        val canonical = toAscii(host) ?: return host
        val platform = runCatching { IDN.toUnicode(canonical) }.getOrNull()
        val unicode = if (platform != null && platform != canonical) {
            platform
        } else {
            val decodedLabels = mutableListOf<String>()
            for (label in canonical.split('.')) {
                decodedLabels += if (label.startsWith("xn--")) {
                    punycodeDecode(label.removePrefix("xn--")) ?: return host
                } else label
            }
            decodedLabels.joinToString(".")
        }
        return unicode.takeIf { it != canonical && toAscii(it) == canonical } ?: host
    }

    private fun fallbackToAscii(labels: List<String>, trailingDot: Boolean): String? {
        val asciiLabels = ArrayList<String>(labels.size)
        labels.forEachIndexed { index, label ->
            if (trailingDot && index == labels.lastIndex && label.isEmpty()) {
                asciiLabels += ""
            } else {
                asciiLabels += toAsciiLabel(label) ?: return null
            }
        }
        return asciiLabels.joinToString(".").lowercase(Locale.US)
    }

    private fun isValidAsciiHost(host: String): Boolean {
        val withoutTrailingDot = host.removeSuffix(".")
        if (withoutTrailingDot.isEmpty() || withoutTrailingDot.length > MAX_ASCII_HOST_CHARS) {
            return false
        }
        return withoutTrailingDot.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= MAX_ASCII_LABEL_CHARS &&
                label.all(::isAsciiHostChar)
        }
    }

    private fun toAsciiLabel(label: String): String? {
        if (label.isEmpty()) {
            return null
        }
        if (label.all { it.code < ASCII_LIMIT }) {
            return label.takeIf { it.all(::isAsciiHostChar) }
        }

        return punycodeEncode(label)?.let { "xn--$it" }
    }

    private fun punycodeEncode(label: String): String? {
        val codePoints = label.codePoints().toArray()
        if (codePoints.isEmpty() || codePoints.any { it in SURROGATE_MIN..SURROGATE_MAX || it > UNICODE_MAX }) {
            return null
        }

        val output = StringBuilder()
        for (codePoint in codePoints) {
            if (codePoint < ASCII_LIMIT) {
                val char = codePoint.toChar()
                if (!isAsciiHostChar(char)) {
                    return null
                }
                output.append(char.lowercaseChar())
            }
        }

        val basicLength = output.length
        var handled = basicLength
        if (basicLength > 0 && handled < codePoints.size) {
            output.append(DELIMITER)
        }

        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < codePoints.size) {
            val next = codePoints.filter { it >= n }.minOrNull() ?: return null
            val handledPlusOne = handled + 1
            delta += (next - n).toLong() * handledPlusOne
            n = next

            for (codePoint in codePoints) {
                if (codePoint < n) {
                    delta += 1
                }
                if (codePoint == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val threshold = when {
                            k <= bias + TMIN -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < threshold) {
                            break
                        }
                        val digit = threshold + ((q - threshold) % (BASE - threshold)).toInt()
                        output.append(encodeDigit(digit))
                        q = (q - threshold) / (BASE - threshold)
                        k += BASE
                    }
                    output.append(encodeDigit(q.toInt()))
                    bias = adapt(delta, handledPlusOne, handled == basicLength)
                    delta = 0
                    handled += 1
                }
            }

            delta += 1
            n += 1
        }

        return output.toString()
    }

    private fun punycodeDecode(value: String): String? {
        if (value.isEmpty()) return null
        val output = mutableListOf<Int>()
        var cursor = 0
        val delimiter = value.lastIndexOf(DELIMITER)
        if (delimiter >= 0) {
            for (character in value.take(delimiter)) {
                if (character.code >= ASCII_LIMIT || !isAsciiHostChar(character)) return null
                output += character.lowercaseChar().code
            }
            cursor = delimiter + 1
        }
        var n = INITIAL_N.toLong()
        var insertion = 0L
        var bias = INITIAL_BIAS
        while (cursor < value.length) {
            val oldInsertion = insertion
            var weight = 1L
            var k = BASE
            while (true) {
                if (cursor >= value.length) return null
                val digit = decodeDigit(value[cursor++]) ?: return null
                insertion = runCatching {
                    Math.addExact(insertion, Math.multiplyExact(digit.toLong(), weight))
                }.getOrNull() ?: return null
                val threshold = when {
                    k <= bias + TMIN -> TMIN
                    k >= bias + TMAX -> TMAX
                    else -> k - bias
                }
                if (digit < threshold) break
                weight = runCatching {
                    Math.multiplyExact(weight, (BASE - threshold).toLong())
                }.getOrNull() ?: return null
                k += BASE
            }
            val outputSize = output.size + 1
            bias = adapt(insertion - oldInsertion, outputSize, oldInsertion == 0L)
            n = runCatching { Math.addExact(n, insertion / outputSize) }.getOrNull()
                ?: return null
            if (n > UNICODE_MAX || n in SURROGATE_MIN.toLong()..SURROGATE_MAX.toLong()) return null
            insertion %= outputSize
            output.add(insertion.toInt(), n.toInt())
            insertion += 1
        }
        return buildString {
            output.forEach(::appendCodePoint)
        }
    }

    private fun decodeDigit(character: Char): Int? = when (character) {
        in 'a'..'z' -> character.code - 'a'.code
        in 'A'..'Z' -> character.code - 'A'.code
        in '0'..'9' -> character.code - '0'.code + 26
        else -> null
    }

    private fun adapt(deltaValue: Long, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) deltaValue / DAMP else deltaValue / 2
        delta += delta / numPoints

        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }

        return (k + (((BASE - TMIN + 1) * delta) / (delta + SKEW))).toInt()
    }

    private fun encodeDigit(digit: Int): Char =
        when (digit) {
            in 0..25 -> ('a'.code + digit).toChar()
            in 26..35 -> ('0'.code + digit - 26).toChar()
            else -> error("invalid punycode digit")
        }

    private fun isAsciiHostChar(char: Char): Boolean =
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-'

    private const val ASCII_LIMIT = 0x80
    private const val MAX_RAW_HOST_CHARS = 1_024
    private const val MAX_RAW_LABEL_CHARS = 256
    private const val MAX_LABELS = 127
    private const val MAX_ASCII_HOST_CHARS = 253
    private const val MAX_ASCII_LABEL_CHARS = 63
    private const val UNICODE_MAX = 0x10FFFF
    private const val SURROGATE_MIN = 0xD800
    private const val SURROGATE_MAX = 0xDFFF
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val DELIMITER = '-'
}
