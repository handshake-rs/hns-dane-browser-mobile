package com.denuoweb.hnsdane.wallet

import java.net.URI
import java.net.URLDecoder

internal data class HandshakePaymentRequest(
    val address: String,
    val amountHns: String?,
    val label: String?,
    val message: String?,
)

internal object HandshakePaymentUri {
    private const val MAX_URI_CHARACTERS = 2_048
    private const val MAX_ADDRESS_BYTES = 512
    private const val MAX_METADATA_CHARACTERS = 256

    fun parse(raw: String): HandshakePaymentRequest? {
        if (raw.length !in 1..MAX_URI_CHARACTERS) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("handshake", ignoreCase = true) || uri.rawFragment != null) return null
        val schemeSpecific = uri.rawSchemeSpecificPart ?: return null
        val separator = schemeSpecific.indexOf('?')
        val address = if (separator < 0) schemeSpecific else schemeSpecific.substring(0, separator)
        if (
            address.toByteArray(Charsets.US_ASCII).size !in 1..MAX_ADDRESS_BYTES ||
            address.any { it.code !in 0x21..0x7e || it in "%/?#" }
        ) return null

        val values = linkedMapOf<String, String>()
        if (separator >= 0) {
            val query = schemeSpecific.substring(separator + 1)
            if (query.isNotEmpty()) {
                for (field in query.split('&')) {
                    val equals = field.indexOf('=')
                    val encodedName = if (equals < 0) field else field.substring(0, equals)
                    val encodedValue = if (equals < 0) "" else field.substring(equals + 1)
                    val name = decode(encodedName) ?: return null
                    val value = decode(encodedValue) ?: return null
                    if (name.startsWith("req-") || values.put(name, value) != null) return null
                }
            }
        }
        val amount = values["amount"]
        if (amount != null && parsePositiveHnsToBaseUnits(amount) == null) return null
        val label = values["label"]?.takeIf { it.length <= MAX_METADATA_CHARACTERS } ?: values["label"]?.let { return null }
        val message = values["message"]?.takeIf { it.length <= MAX_METADATA_CHARACTERS } ?: values["message"]?.let { return null }
        return HandshakePaymentRequest(address, amount, label, message)
    }

    private fun decode(value: String): String? = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrNull()
}
