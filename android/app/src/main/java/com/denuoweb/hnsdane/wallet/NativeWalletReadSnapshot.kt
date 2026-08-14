package com.denuoweb.hnsdane.wallet

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** Strict public projection of one native, tip-bound HNS wallet reconciliation. */
internal data class NativeWalletReadSnapshot(
    val balanceBaseUnits: String,
    val paymentReceiveTarget: NativeWalletPaymentReceiveTarget,
    val nameReceiveTarget: NativeWalletNameReceiveTarget?,
    val height: Long,
    val transactions: List<NativeWalletTransaction>,
    val trackedNames: List<NativeWalletName>,
) {
    companion object {
        fun parse(bundle: ByteArray): NativeWalletReadSnapshot? =
            NativeWalletReadSnapshotParser.parse(bundle)
    }
}

/** Ordinary HNS coin receive target; never a Handshake name-owner target. */
internal data class NativeWalletPaymentReceiveTarget(
    val accountId: String,
    val display: String,
    val derivationIndex: Long,
)

/** Dedicated Handshake name-owner receive target; never a payment address. */
internal data class NativeWalletNameReceiveTarget(
    val accountId: String,
    val display: String,
    val derivationIndex: Long,
)

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

/** One shared closed-schema decoder for HNWR rows and HNWI import results. */
internal object NativeWalletNameParser {
    private val lowercaseHex = Regex("[0-9a-f]+")
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

    fun parse(value: JSONObject): NativeWalletName {
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
        require(isCanonicalHandshakeName(name))
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
        require(keys().asSequence().toSet() == expected.toSet())
    }

    private fun JSONObject.optionalBoolean(field: String): Boolean? = when (val value = get(field)) {
        JSONObject.NULL -> null
        is Boolean -> value
        else -> throw IllegalArgumentException("$field is not optional boolean")
    }

    private fun exactUnsignedLong(value: Any): Long {
        require(value is Byte || value is Short || value is Int || value is Long)
        return (value as Number).toLong().also { require(it >= 0L) }
    }

    private fun isCanonicalHandshakeName(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAX_NAME_BYTES || value in RESERVED_NAMES) return false
        return bytes.indices.all { index ->
            val byte = bytes[index]
            byte in '0'.code.toByte()..'9'.code.toByte() ||
                byte in 'a'.code.toByte()..'z'.code.toByte() ||
                (byte == '-'.code.toByte() || byte == '_'.code.toByte()) &&
                index != 0 && index + 1 != bytes.size
        }
    }

    private const val MAX_NAME_BYTES = 63
    private const val NAME_HASH_HEX_CHARACTERS = 64
    private val RESERVED_NAMES = setOf("example", "invalid", "local", "localhost", "test")
}

private object NativeWalletReadSnapshotParser {
    private val magic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'W'.code.toByte(),
        'R'.code.toByte(),
    )
    private val maxBaseUnits = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
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

    fun parse(bundle: ByteArray): NativeWalletReadSnapshot? = runCatching {
        require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_JSON_BYTES))
        require(magic.indices.all { index -> bundle[index] == magic[index] })
        val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        val version = header.get().toInt() and 0xff
        require(version == LEGACY_VERSION || version == NAME_RECEIVE_VERSION)
        require(header.get().toInt() and 0xff == READ_ONLY_HNS_FLAG)
        require(header.short.toInt() == 0)
        val jsonLength = header.int
        require(jsonLength in 2..MAX_JSON_BYTES)
        require(bundle.size == HEADER_BYTES + jsonLength)
        val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
        try {
            val json = jsonBytes.toString(Charsets.UTF_8)
            require(json.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
            parseSnapshot(JSONObject(json), version)
        } finally {
            jsonBytes.fill(0)
        }
    }.getOrNull()

    private fun parseSnapshot(value: JSONObject, version: Int): NativeWalletReadSnapshot {
        when (version) {
            LEGACY_VERSION -> value.requireExactKeys(
                "balance",
                "receiveTarget",
                "transactionHistory",
                "knownNames",
                "moduleStatus",
            )

            NAME_RECEIVE_VERSION -> value.requireExactKeys(
                "balance",
                "receiveTarget",
                "nameReceiveTarget",
                "transactionHistory",
                "knownNames",
                "moduleStatus",
            )

            else -> throw IllegalArgumentException("unsupported HNWR version")
        }
        val balance = value.getJSONObject("balance").apply {
            requireExactKeys("asset", "base_units")
            require(get("asset") == "HNS")
        }
        val balanceBaseUnits = canonicalBaseUnits(balance.get("base_units"))

        val (paymentReceiveTarget, paymentAccount) =
            parsePaymentReceiveTarget(value.getJSONObject("receiveTarget"))
        val nameReceiveTarget = if (version == NAME_RECEIVE_VERSION) {
            val (target, account) =
                parseNameReceiveTarget(value.getJSONObject("nameReceiveTarget"))
            require(account.contentEquals(paymentAccount))
            require(target.display != paymentReceiveTarget.display)
            target
        } else {
            null
        }

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
            List(array.length()) { index ->
                NativeWalletNameParser.parse(array.getJSONObject(index))
            }
        }
        require(transactions.map(NativeWalletTransaction::txid).toSet().size == transactions.size)
        require(names.map(NativeWalletName::name).toSet().size == names.size)
        require(names.map(NativeWalletName::nameHash).toSet().size == names.size)

        return NativeWalletReadSnapshot(
            balanceBaseUnits = balanceBaseUnits,
            paymentReceiveTarget = paymentReceiveTarget,
            nameReceiveTarget = nameReceiveTarget,
            height = targetHeight,
            transactions = transactions,
            trackedNames = names,
        )
    }

    private fun parsePaymentReceiveTarget(
        value: JSONObject,
    ): Pair<NativeWalletPaymentReceiveTarget, ByteArray> {
        val account = parseReceiveTargetAccount(value)
        return NativeWalletPaymentReceiveTarget(
            accountId = account.toLowerHex(),
            display = visibleReceiveDisplay(value.get("display"), "payment receive target"),
            derivationIndex = exactUnsignedLong(value.get("derivation_index"), UINT32_MAX),
        ) to account
    }

    private fun parseNameReceiveTarget(
        value: JSONObject,
    ): Pair<NativeWalletNameReceiveTarget, ByteArray> {
        val account = parseReceiveTargetAccount(value)
        return NativeWalletNameReceiveTarget(
            accountId = account.toLowerHex(),
            display = visibleReceiveDisplay(value.get("display"), "name receive target"),
            derivationIndex = exactUnsignedLong(value.get("derivation_index"), UINT32_MAX),
        ) to account
    }

    private fun parseReceiveTargetAccount(value: JSONObject): ByteArray {
        value.requireExactKeys("module", "account", "display", "derivation_index")
        require(value.get("module") == "handshake")
        return requireByteArray(value.getJSONArray("account"), ACCOUNT_ID_BYTES).also { account ->
            require(account.any { byte -> byte != 0.toByte() })
        }
    }

    private fun visibleReceiveDisplay(value: Any, field: String): String {
        val display = value as? String
            ?: throw IllegalArgumentException("$field is not text")
        require(display.length in 1..MAX_RECEIVE_CHARACTERS)
        require(display.all { character -> character.code in 0x21..0x7e })
        return display
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

    private fun JSONObject.requireExactKeys(vararg expected: String) {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet())
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

    private const val LEGACY_VERSION = 1
    private const val NAME_RECEIVE_VERSION = 2
    private const val READ_ONLY_HNS_FLAG = 1
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_READ_ITEMS = 10_000
    private const val MAX_RECEIVE_CHARACTERS = 512
    private const val ACCOUNT_ID_BYTES = 16
    private const val TRANSACTION_ID_BYTES = 32
    private const val UINT32_MAX = 0xffff_ffffL
    private const val UBYTE_MAX = 0xffL
    private const val HEX = "0123456789abcdef"
}
