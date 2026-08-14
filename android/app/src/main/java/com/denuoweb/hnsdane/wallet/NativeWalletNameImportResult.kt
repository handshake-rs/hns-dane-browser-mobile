package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Closed HNWI-v1 outcome from the trusted-native exact-text import boundary. */
internal sealed class NativeWalletNameImportResult {
    data class Success(val summary: NativeWalletName) : NativeWalletNameImportResult()
    object InvalidInput : NativeWalletNameImportResult()
    object Unavailable : NativeWalletNameImportResult()
    object Failed : NativeWalletNameImportResult()

    companion object {
        fun parse(bundle: ByteArray): NativeWalletNameImportResult? = runCatching {
            require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_JSON_BYTES))
            require(MAGIC.indices.all { index -> bundle[index] == MAGIC[index] })
            val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
            require(header.get().toInt() and 0xff == VERSION)
            val status = header.get().toInt() and 0xff
            require(header.short.toInt() == 0)
            val jsonLength = header.int
            require(jsonLength in 0..MAX_JSON_BYTES)
            require(bundle.size == HEADER_BYTES + jsonLength)
            if (status != STATUS_SUCCESS) {
                require(jsonLength == 0)
                return@runCatching when (status) {
                    STATUS_INVALID -> InvalidInput
                    STATUS_UNAVAILABLE -> Unavailable
                    STATUS_FAILED -> Failed
                    else -> throw IllegalArgumentException("unsupported HNWI outcome")
                }
            }
            require(jsonLength >= 2)
            val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
            try {
                require(jsonBytes.first() == '{'.code.toByte())
                require(jsonBytes.last() == '}'.code.toByte())
                val json = jsonBytes.toString(Charsets.UTF_8)
                require(json.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
                Success(NativeWalletNameParser.parse(JSONObject(json)))
            } finally {
                jsonBytes.fill(0)
            }
        }.getOrNull()

        private const val VERSION = 1
        private const val STATUS_SUCCESS = 1
        private const val STATUS_INVALID = 2
        private const val STATUS_UNAVAILABLE = 3
        private const val STATUS_FAILED = 4
        private const val HEADER_BYTES = 12
        private const val MAX_JSON_BYTES = 16 * 1024
        private val MAGIC = byteArrayOf(
            'H'.code.toByte(),
            'N'.code.toByte(),
            'W'.code.toByte(),
            'I'.code.toByte(),
        )
    }
}

/** A successful import may publish only after HNWR-v2 confirms its exact identity. */
internal fun walletNameImportRefreshMatches(
    imported: NativeWalletName,
    refreshed: NativeWalletReadSnapshot,
): Boolean = refreshed.trackedNames.any { current ->
    current.name.toByteArray(Charsets.UTF_8).contentEquals(
        imported.name.toByteArray(Charsets.UTF_8),
    ) && current.nameHash == imported.nameHash
}
