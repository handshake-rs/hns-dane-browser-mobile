package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** One bounded page from the last authenticated native HNS synchronization. */
internal data class NativeWalletNamePage(
    val offset: Int,
    val total: Int,
    val names: List<NativeWalletName>,
    val hasMore: Boolean,
) {
    companion object {
        fun parse(bundle: ByteArray): NativeWalletNamePage? = runCatching {
            require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_JSON_BYTES))
            require(MAGIC.indices.all { bundle[it] == MAGIC[it] })
            val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
            require(header.get().toInt() and 0xff == VERSION)
            require(header.get().toInt() and 0xff == 0)
            require(header.short.toInt() == 0)
            val jsonLength = header.int
            require(jsonLength in 2..MAX_JSON_BYTES)
            require(bundle.size == HEADER_BYTES + jsonLength)
            val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
            try {
                val text = jsonBytes.toString(Charsets.UTF_8)
                require(text.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
                val value = JSONObject(text)
                require(value.keys().asSequence().toSet() == KEYS)
                val offset = exactInt(value.get("offset"), MAX_NAMES)
                val total = exactInt(value.get("total"), MAX_NAMES)
                val array = value.getJSONArray("names")
                require(array.length() <= PAGE_SIZE)
                require(offset <= total && offset + array.length() <= total)
                val names = List(array.length()) { index ->
                    NativeWalletNameParser.parse(array.getJSONObject(index))
                }
                require(names.map(NativeWalletName::name).toSet().size == names.size)
                require(names.map(NativeWalletName::nameHash).toSet().size == names.size)
                val hasMore = value.getBoolean("hasMore")
                require(hasMore == (offset + names.size < total))
                NativeWalletNamePage(offset, total, names, hasMore)
            } finally {
                jsonBytes.fill(0)
            }
        }.getOrNull()

        private fun exactInt(value: Any, maximum: Int): Int {
            require(value is Number)
            val number = value.toString()
            require(number.matches(Regex("0|[1-9][0-9]{0,4}")))
            return number.toInt().also { require(it <= maximum) }
        }

        private val MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte())
        private val KEYS = setOf("offset", "total", "names", "hasMore")
        private const val VERSION = 1
        private const val HEADER_BYTES = 12
        private const val MAX_JSON_BYTES = 64 * 1024
        private const val PAGE_SIZE = 64
        private const val MAX_NAMES = 10_000
    }
}
