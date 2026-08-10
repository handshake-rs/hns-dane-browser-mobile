package com.denuoweb.hnsdane.wallet

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** Strict public projection of one native, tip-bound HNS wallet reconciliation. */
internal data class NativeWalletReadSnapshot(
    val balanceBaseUnits: String,
    val receiveAddress: String,
    val derivationIndex: Long,
    val height: Long,
    val transactions: List<NativeWalletTransaction>,
    val trackedNames: List<NativeWalletName>,
) {
    companion object {
        fun parse(bundle: ByteArray): NativeWalletReadSnapshot? =
            NativeWalletReadSnapshotParser.parse(bundle)
    }
}

internal data class NativeWalletTransaction(
    val txid: String,
    val status: String,
    val negative: Boolean,
    val magnitudeBaseUnits: String,
    val feeBaseUnits: String?,
    val blockHeight: Long?,
    val firstSeenUnix: Long?,
    val confirmationCount: Long,
)

internal data class NativeWalletName(
    val name: String,
    val nameHash: String,
    val proofHeight: Long,
    val resourceStatus: String,
    val ownershipStatus: String,
    val registered: Boolean?,
    val expired: Boolean?,
)

private object NativeWalletReadSnapshotParser {
    private val magic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'W'.code.toByte(),
        'R'.code.toByte(),
    )
    private val maxBaseUnits = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
    private val lowercaseHex = Regex("[0-9a-f]+")
    private val decimal = Regex("0|[1-9][0-9]{0,38}")
    private val transactionStatuses = setOf(
        "prepared",
        "authorized",
        "broadcast",
        "mempool",
        "confirmed",
        "replaced",
        "conflicted",
        "reorged",
        "dropped",
        "failed",
    )
    private val resourceStatuses = setOf(
        "unavailableCanonicalBinding",
        "noCurrentState",
        "empty",
        "canonicalDecoded",
        "canonicalOpaque",
    )
    private val ownershipStatuses = setOf(
        "watchOnlyCanonicalStateDecoderUnavailable",
        "walletContextUnavailable",
        "noCurrentOwner",
        "notWalletOwned",
        "walletOwned",
        "incomingTransfer",
        "outgoingTransfer",
    )

    fun parse(bundle: ByteArray): NativeWalletReadSnapshot? = runCatching {
        require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_JSON_BYTES))
        require(magic.indices.all { index -> bundle[index] == magic[index] })
        val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(header.get().toInt() and 0xff == VERSION)
        require(header.get().toInt() and 0xff == READ_ONLY_HNS_FLAG)
        require(header.short.toInt() == 0)
        val jsonLength = header.int
        require(jsonLength in 2..MAX_JSON_BYTES)
        require(bundle.size == HEADER_BYTES + jsonLength)
        val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
        try {
            val json = jsonBytes.toString(Charsets.UTF_8)
            require(json.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
            parseSnapshot(JSONObject(json))
        } finally {
            jsonBytes.fill(0)
        }
    }.getOrNull()

    private fun parseSnapshot(value: JSONObject): NativeWalletReadSnapshot {
        value.requireExactKeys(
            "balance",
            "receiveTarget",
            "transactionHistory",
            "knownNames",
            "moduleStatus",
        )
        val balance = value.getJSONObject("balance").apply {
            requireExactKeys("asset", "base_units")
            require(get("asset") == "HNS")
        }
        val balanceBaseUnits = canonicalBaseUnits(balance.get("base_units"))

        val receive = value.getJSONObject("receiveTarget").apply {
            requireExactKeys("module", "account", "display", "derivation_index")
            require(get("module") == "handshake")
        }
        requireByteArray(receive.getJSONArray("account"), ACCOUNT_ID_BYTES)
        val receiveAddress = receive.get("display") as? String
            ?: throw IllegalArgumentException("receive target is not text")
        require(receiveAddress.length in 1..MAX_RECEIVE_CHARACTERS)
        require(receiveAddress.all { character -> character.code in 0x21..0x7e })
        val derivationIndex = exactUnsignedLong(receive.get("derivation_index"), UINT32_MAX)

        val moduleStatus = value.getJSONObject("moduleStatus").apply {
            requireExactKeys(
                "phase",
                "validated_height",
                "scanned_height",
                "target_height",
                "last_error",
            )
            require(get("phase") == "ready")
            require(get("last_error") === JSONObject.NULL)
        }
        val validatedHeight = exactUnsignedLong(moduleStatus.get("validated_height"))
        val scannedHeight = exactUnsignedLong(moduleStatus.get("scanned_height"))
        val targetHeight = exactUnsignedLong(moduleStatus.get("target_height"))
        require(validatedHeight == scannedHeight && scannedHeight == targetHeight)

        val transactions = value.getJSONArray("transactionHistory").let { array ->
            require(array.length() <= MAX_READ_ITEMS)
            List(array.length()) { index -> parseTransaction(array.getJSONObject(index)) }
        }
        val names = value.getJSONArray("knownNames").let { array ->
            require(array.length() <= MAX_READ_ITEMS)
            List(array.length()) { index -> parseName(array.getJSONObject(index)) }
        }
        require(transactions.map(NativeWalletTransaction::txid).toSet().size == transactions.size)
        require(names.map(NativeWalletName::name).toSet().size == names.size)
        require(names.map(NativeWalletName::nameHash).toSet().size == names.size)

        return NativeWalletReadSnapshot(
            balanceBaseUnits = balanceBaseUnits,
            receiveAddress = receiveAddress,
            derivationIndex = derivationIndex,
            height = targetHeight,
            transactions = transactions,
            trackedNames = names,
        )
    }

    private fun parseTransaction(value: JSONObject): NativeWalletTransaction {
        value.requireExactKeys(
            "module",
            "txid",
            "status",
            "net_amount",
            "fee",
            "block_height",
            "first_seen_unix",
            "confirmation_count",
        )
        require(value.get("module") == "handshake")
        val txid = requireByteArray(value.getJSONArray("txid"), TRANSACTION_ID_BYTES).toLowerHex()
        val status = value.get("status") as? String
            ?: throw IllegalArgumentException("transaction status is not text")
        require(status in transactionStatuses)
        val netAmount = value.getJSONObject("net_amount").apply {
            requireExactKeys("negative", "magnitude")
        }
        val negative = netAmount.get("negative") as? Boolean
            ?: throw IllegalArgumentException("transaction sign is not boolean")
        val magnitude = canonicalBaseUnits(netAmount.get("magnitude"))
        require(!negative || magnitude != "0")
        val fee = value.optionalBaseUnits("fee")
        val blockHeight = value.optionalUnsignedLong("block_height")
        val firstSeenUnix = value.optionalUnsignedLong("first_seen_unix")
        val confirmationCount = exactUnsignedLong(value.get("confirmation_count"), UINT32_MAX)
        return NativeWalletTransaction(
            txid = txid,
            status = status,
            negative = negative,
            magnitudeBaseUnits = magnitude,
            feeBaseUnits = fee,
            blockHeight = blockHeight,
            firstSeenUnix = firstSeenUnix,
            confirmationCount = confirmationCount,
        )
    }

    private fun parseName(value: JSONObject): NativeWalletName {
        value.requireExactKeys(
            "name",
            "nameHash",
            "proofHeight",
            "resourceStatus",
            "ownershipStatus",
            "registered",
            "expired",
        )
        val name = value.get("name") as? String
            ?: throw IllegalArgumentException("tracked name is not text")
        require(name.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES)
        require(name.all { character -> character.code in 0x21..0x7e })
        val nameHash = value.get("nameHash") as? String
            ?: throw IllegalArgumentException("name hash is not text")
        require(nameHash.length == NAME_HASH_HEX_CHARACTERS && lowercaseHex.matches(nameHash))
        val resourceStatus = value.get("resourceStatus") as? String
            ?: throw IllegalArgumentException("resource status is not text")
        require(resourceStatus in resourceStatuses)
        val ownershipStatus = value.get("ownershipStatus") as? String
            ?: throw IllegalArgumentException("ownership status is not text")
        require(ownershipStatus in ownershipStatuses)
        return NativeWalletName(
            name = name,
            nameHash = nameHash,
            proofHeight = exactUnsignedLong(value.get("proofHeight")),
            resourceStatus = resourceStatus,
            ownershipStatus = ownershipStatus,
            registered = value.optionalBoolean("registered"),
            expired = value.optionalBoolean("expired"),
        )
    }

    private fun JSONObject.requireExactKeys(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet())
    }

    private fun JSONObject.optionalBoolean(field: String): Boolean? = when (val value = get(field)) {
        JSONObject.NULL -> null
        is Boolean -> value
        else -> throw IllegalArgumentException("$field is not optional boolean")
    }

    private fun JSONObject.optionalUnsignedLong(field: String): Long? = when (val value = get(field)) {
        JSONObject.NULL -> null
        else -> exactUnsignedLong(value)
    }

    private fun JSONObject.optionalBaseUnits(field: String): String? = when (val value = get(field)) {
        JSONObject.NULL -> null
        else -> canonicalBaseUnits(value)
    }

    private fun exactUnsignedLong(value: Any, maximum: Long = Long.MAX_VALUE): Long {
        require(value is Byte || value is Short || value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in 0..maximum)
        return number
    }

    private fun canonicalBaseUnits(value: Any): String {
        require(value is String && decimal.matches(value))
        require(BigInteger(value) <= maxBaseUnits)
        return value
    }

    private fun requireByteArray(value: JSONArray, expectedLength: Int): ByteArray {
        require(value.length() == expectedLength)
        return ByteArray(expectedLength) { index ->
            exactUnsignedLong(value.get(index), UBYTE_MAX).toInt().toByte()
        }
    }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        this@toLowerHex.forEach { value ->
            val byte = value.toInt() and 0xff
            append(HEX[byte ushr 4])
            append(HEX[byte and 0x0f])
        }
    }

    private const val VERSION = 1
    private const val READ_ONLY_HNS_FLAG = 1
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_READ_ITEMS = 10_000
    private const val MAX_RECEIVE_CHARACTERS = 512
    private const val MAX_NAME_BYTES = 63
    private const val ACCOUNT_ID_BYTES = 16
    private const val TRANSACTION_ID_BYTES = 32
    private const val NAME_HASH_HEX_CHARACTERS = 64
    private const val UINT32_MAX = 0xffff_ffffL
    private const val UBYTE_MAX = 0xffL
    private const val HEX = "0123456789abcdef"
}
