package com.denuoweb.hnsdane.wallet

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/** Single-use process-local capability for one exact native HNS value action. */
internal class NativeHnsValueActionToken private constructor(
    private var retainedAscii: ByteArray?,
) : AutoCloseable {
    private val lock = Any()

    fun <T> consume(block: (ByteArray) -> T): T? {
        val ascii = synchronized(lock) {
            retainedAscii?.also { retainedAscii = null }
        } ?: return null
        return try {
            block(ascii)
        } finally {
            ascii.fill(0)
        }
    }

    override fun close() {
        synchronized(lock) {
            retainedAscii?.fill(0)
            retainedAscii = null
        }
    }

    override fun toString(): String = "NativeHnsValueActionToken(<redacted>)"

    companion object {
        fun takeOwnership(ascii: ByteArray): NativeHnsValueActionToken? {
            var retained: ByteArray? = null
            return try {
                if (
                    ascii.size != ACTION_TOKEN_ASCII_BYTES ||
                    ascii.all { it == '0'.code.toByte() } ||
                    ascii.any { byte ->
                        byte !in '0'.code.toByte()..'9'.code.toByte() &&
                            byte !in 'a'.code.toByte()..'f'.code.toByte()
                    }
                ) {
                    null
                } else {
                    val owned = ascii.copyOf()
                    retained = owned
                    NativeHnsValueActionToken(owned).also { retained = null }
                }
            } finally {
                ascii.fill(0)
                retained?.fill(0)
            }
        }

        private const val ACTION_TOKEN_ASCII_BYTES = 64
    }
}

/** Exact native summary that the user must see before a send can execute. */
internal data class NativeHnsSendApproval(
    val actionToken: NativeHnsValueActionToken,
    val expiresAtUnix: Long,
    val amountBaseUnits: String,
    val recipient: String,
    val maximumFeeBaseUnits: String,
    val finality: String,
    val warnings: List<String>,
) : AutoCloseable {
    override fun close() = actionToken.close()

    companion object {
        fun parse(bundle: ByteArray): NativeHnsSendApproval? =
            NativeHnsSendApprovalParser.parse(bundle)
    }
}

/** Minimized closed projection returned only after the node accepts a broadcast. */
internal data class NativeHnsSendReceipt(
    val txid: String,
    val acceptedAtUnix: Long,
) {
    companion object {
        fun parse(bundle: ByteArray): NativeHnsSendReceipt? =
            NativeHnsSendReceiptParser.parse(bundle)
    }
}

/** Every value-moving action exposed by the installed native wallet UI. */
internal sealed interface NativeHnsValueIntent {
    data class TransferName(
        val name: String,
        val recipient: String,
        val maximumFeeBaseUnits: String,
    ) : NativeHnsValueIntent

    data class FinalizeName(
        val name: String,
        val expectedRecipient: String?,
        val maximumFeeBaseUnits: String,
    ) : NativeHnsValueIntent

    data class CreateFixedPriceOffer(
        val name: String,
        val priceBaseUnits: String,
        val maximumFeeBaseUnits: String,
        val listingLifetimeSeconds: Long,
    ) : NativeHnsValueIntent

    data class CancelOffer(val sellerSessionId: String) : NativeHnsValueIntent

    data class AcceptOffer(
        val listingId: String,
        val maximumFeeBaseUnits: String,
    ) : NativeHnsValueIntent

    data class FinalizePurchase(
        val sessionId: String,
        val maximumFeeBaseUnits: String,
    ) : NativeHnsValueIntent

    data class RecoverName(
        val sellerSessionId: String,
        val maximumFeeBaseUnits: String,
    ) : NativeHnsValueIntent
}

internal sealed interface NativeShakedexQuery {
    data class ListOffers(
        val cursor: String? = null,
        val limit: Int = 32,
    ) : NativeShakedexQuery

    data class GetSession(val sessionId: String) : NativeShakedexQuery
}

internal enum class NativeHnsValueApprovalKind {
    NAME_TRANSFER,
    NAME_FINALIZE,
    NAME_MARKET_OFFER,
    NAME_MARKET_PURCHASE,
}

/** Validated native approval projection for name and Shakedex actions. */
internal data class NativeHnsValueApproval(
    val actionToken: NativeHnsValueActionToken,
    val expiresAtUnix: Long,
    val kind: NativeHnsValueApprovalKind,
    val title: String,
    val detailLines: List<String>,
) : AutoCloseable {
    override fun close() = actionToken.close()

    companion object {
        fun parse(bundle: ByteArray): NativeHnsValueApproval? =
            NativeHnsValueApprovalParser.parse(bundle)
    }
}

/** Bounded provider result produced only by a closed native value intent. */
internal data class NativeHnsValueResult(val displayJson: String) {
    companion object {
        fun parse(bundle: ByteArray): NativeHnsValueResult? =
            parseDisplayJsonBundle(bundle, "HNVX")?.let(::NativeHnsValueResult)
    }
}

/** Bounded provider result produced only by a closed native Shakedex query. */
internal data class NativeShakedexQueryResult(val displayJson: String) {
    companion object {
        fun parse(bundle: ByteArray): NativeShakedexQueryResult? =
            parseDisplayJsonBundle(bundle, "HNVQ")?.let(::NativeShakedexQueryResult)
    }
}

internal fun NativeHnsValueIntent.encodeJson(): ByteArray? = runCatching {
    val value = when (this) {
        is NativeHnsValueIntent.TransferName -> JSONObject()
            .put("action", "transferName")
            .put("name", name.requirePublicText(MAX_NAME_CHARACTERS))
            .put("recipient", recipient.requirePublicText(MAX_RECIPIENT_CHARACTERS))
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
        is NativeHnsValueIntent.FinalizeName -> JSONObject()
            .put("action", "finalizeName")
            .put("name", name.requirePublicText(MAX_NAME_CHARACTERS))
            .put(
                "expectedRecipient",
                expectedRecipient?.requirePublicText(MAX_RECIPIENT_CHARACTERS) ?: JSONObject.NULL,
            )
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
        is NativeHnsValueIntent.CreateFixedPriceOffer -> JSONObject()
            .put("action", "createFixedPriceOffer")
            .put("name", name.requirePublicText(MAX_NAME_CHARACTERS))
            .put("price", priceBaseUnits.requireBaseUnits(nonzero = true))
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
            .put(
                "listingLifetimeSeconds",
                listingLifetimeSeconds.also {
                    require(it in MIN_LISTING_LIFETIME_SECONDS..MAX_LISTING_LIFETIME_SECONDS)
                },
            )
        is NativeHnsValueIntent.CancelOffer -> JSONObject()
            .put("action", "cancelOffer")
            .put("sellerSessionId", sellerSessionId.requireObjectId())
        is NativeHnsValueIntent.AcceptOffer -> JSONObject()
            .put("action", "acceptOffer")
            .put("listingId", listingId.requireObjectId())
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
        is NativeHnsValueIntent.FinalizePurchase -> JSONObject()
            .put("action", "finalizePurchase")
            .put("sessionId", sessionId.requireObjectId())
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
        is NativeHnsValueIntent.RecoverName -> JSONObject()
            .put("action", "recoverName")
            .put("sellerSessionId", sellerSessionId.requireObjectId())
            .put("maximumFee", maximumFeeBaseUnits.requireBaseUnits(nonzero = true))
    }
    value.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size in 2..MAX_INTENT_JSON_BYTES)
    }
}.getOrNull()

internal fun NativeShakedexQuery.encodeJson(): ByteArray? = runCatching {
    val value = when (this) {
        is NativeShakedexQuery.ListOffers -> JSONObject()
            .put("query", "listOffers")
            .put("cursor", cursor?.requireObjectId() ?: JSONObject.NULL)
            .put("limit", limit.also { require(it in 1..MAX_OFFER_PAGE_SIZE) })
        is NativeShakedexQuery.GetSession -> JSONObject()
            .put("query", "getSession")
            .put("sessionId", sessionId.requireObjectId())
    }
    value.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size in 2..MAX_QUERY_JSON_BYTES)
    }
}.getOrNull()

private object NativeHnsValueApprovalParser {
    private val magic = "HNVP".toByteArray(Charsets.US_ASCII)

    fun parse(bundle: ByteArray): NativeHnsValueApproval? {
        var token: NativeHnsValueActionToken? = null
        return try {
            parseWalletJsonBundle(bundle, magic, MAX_APPROVAL_JSON_BYTES) { value ->
                value.requireExactKeys("actionToken", "expiresAtUnix", "summary")
                val tokenText = value.get("actionToken") as? String
                    ?: throw IllegalArgumentException("action token is not text")
                val tokenAscii = tokenText.toByteArray(Charsets.US_ASCII)
                require(tokenText.toByteArray(Charsets.UTF_8).contentEquals(tokenAscii))
                token = NativeHnsValueActionToken.takeOwnership(tokenAscii)
                    ?: throw IllegalArgumentException("action token is not canonical")
                val summary = value.getJSONObject("summary")
                val parsed = parseSummary(summary)
                NativeHnsValueApproval(
                    actionToken = requireNotNull(token).also { token = null },
                    expiresAtUnix = exactUnsignedLong(value.get("expiresAtUnix")),
                    kind = parsed.first,
                    title = parsed.second,
                    detailLines = parsed.third,
                )
            }
        } catch (_: Throwable) {
            null
        } finally {
            token?.close()
        }
    }

    private fun parseSummary(
        summary: JSONObject,
    ): Triple<NativeHnsValueApprovalKind, String, List<String>> =
        when (summary.get("kind") as? String) {
            "nameTransfer", "nameFinalize" -> parseNameAction(summary)
            "nameMarketOffer" -> parseOffer(summary)
            "nameMarketPurchase" -> parsePurchase(summary)
            else -> throw IllegalArgumentException("unsupported native value summary")
        }

    private fun parseNameAction(
        summary: JSONObject,
    ): Triple<NativeHnsValueApprovalKind, String, List<String>> {
        summary.requireExactKeys("kind", "name", "recipient", "maximumFee", "warnings")
        val kind = summary.getString("kind")
        val name = summary.getString("name").requirePublicText(MAX_NAME_CHARACTERS)
        val recipient = summary.getString("recipient").requirePublicText(MAX_RECIPIENT_CHARACTERS)
        val fee = summary.getJSONObject("maximumFee").hnsBaseUnits(nonzero = true)
        val warnings = summary.getJSONArray("warnings").strictTextList()
        val expectedWarnings = if (kind == "nameTransfer") {
            listOf("feeEstimateMayChange", "nameTransferIsIrreversible")
        } else {
            listOf("feeEstimateMayChange")
        }
        require(warnings == expectedWarnings)
        return Triple(
            if (kind == "nameTransfer") NativeHnsValueApprovalKind.NAME_TRANSFER
            else NativeHnsValueApprovalKind.NAME_FINALIZE,
            if (kind == "nameTransfer") "Transfer Handshake name" else "Finalize name transfer",
            listOf("Name: $name", "Recipient: $recipient", "Maximum fee: $fee base units") +
                warnings.map(::displayWarning),
        )
    }

    private fun parseOffer(
        summary: JSONObject,
    ): Triple<NativeHnsValueApprovalKind, String, List<String>> {
        summary.requireExactKeys(
            "kind",
            "action",
            "name",
            "listingId",
            "price",
            "maximumFee",
            "warnings",
        )
        val action = summary.getString("action")
        require(action in setOf("create", "cancel", "recover"))
        val name = summary.getString("name").requirePublicText(MAX_NAME_CHARACTERS)
        val listingId = summary.opt("listingId").takeUnless { it == null || it === JSONObject.NULL }
            ?.let { (it as? String)?.requireObjectId() ?: error("listing id is not text") }
        val price = summary.getJSONObject("price").hnsBaseUnits(nonzero = true)
        val fee = summary.getJSONObject("maximumFee").hnsBaseUnits(nonzero = action != "cancel")
        val warnings = summary.getJSONArray("warnings").strictTextList()
        val expectedWarnings = when (action) {
            "create" -> listOf(
                "feeEstimateMayChange",
                "nameTransferIsIrreversible",
                "settlementCanBeDelayed",
            )
            "recover" -> listOf(
                "feeEstimateMayChange",
                "refundRequiresManualAction",
                "settlementCanBeDelayed",
            )
            else -> emptyList()
        }
        require(warnings == expectedWarnings)
        val title = when (action) {
            "create" -> "Create fixed-price name offer"
            "cancel" -> "Cancel name offer"
            else -> "Recover name from offer"
        }
        val lines = mutableListOf("Name: $name", "Price: $price base units")
        listingId?.let { lines += "Listing: $it" }
        lines += "Maximum fee: $fee base units"
        lines += warnings.map(::displayWarning)
        return Triple(NativeHnsValueApprovalKind.NAME_MARKET_OFFER, title, lines)
    }

    private fun parsePurchase(
        summary: JSONObject,
    ): Triple<NativeHnsValueApprovalKind, String, List<String>> {
        summary.requireExactKeys(
            "kind",
            "name",
            "listingId",
            "payment",
            "recipient",
            "maximumFee",
            "warnings",
        )
        val name = summary.getString("name").requirePublicText(MAX_NAME_CHARACTERS)
        val listingId = summary.getString("listingId").requireObjectId()
        val payment = summary.getJSONObject("payment").hnsBaseUnits(nonzero = true)
        val recipient = summary.getString("recipient").requirePublicText(MAX_RECIPIENT_CHARACTERS)
        val fee = summary.getJSONObject("maximumFee").hnsBaseUnits(nonzero = true)
        val warnings = summary.getJSONArray("warnings").strictTextList()
        require(warnings == listOf("feeEstimateMayChange", "settlementCanBeDelayed"))
        return Triple(
            NativeHnsValueApprovalKind.NAME_MARKET_PURCHASE,
            "Execute Shakedex purchase step",
            listOf(
                "Name: $name",
                "Listing/session: $listingId",
                "Payment: $payment base units",
                "Recipient: $recipient",
                "Maximum fee: $fee base units",
            ) + warnings.map(::displayWarning),
        )
    }

    private const val MAX_APPROVAL_JSON_BYTES = 16 * 1024
}

private fun parseDisplayJsonBundle(bundle: ByteArray, magic: String): String? = runCatching {
    parseWalletJsonBundle(
        bundle,
        magic.toByteArray(Charsets.US_ASCII),
        MAX_RESULT_JSON_BYTES,
    ) { value -> value.toString(2) }
}.getOrNull()

private fun displayWarning(warning: String): String = when (warning) {
    "feeEstimateMayChange" -> "Warning: network fee may change before broadcast."
    "nameTransferIsIrreversible" -> "Warning: the name transfer is irreversible."
    "refundRequiresManualAction" -> "Warning: recovery requires an explicit manual action."
    "settlementCanBeDelayed" -> "Warning: Shakedex settlement can require later steps."
    else -> throw IllegalArgumentException("unknown approval warning")
}

private fun String.requirePublicText(maximum: Int): String = also {
    require(length in 1..maximum && all { character -> character.code in 0x21..0x7e })
}

private fun String.requireObjectId(): String = also {
    require(LOWERCASE_OBJECT_ID.matches(this) && any { character -> character != '0' })
}

private fun String.requireBaseUnits(nonzero: Boolean): String = also {
    require(CANONICAL_BASE_UNITS.matches(this))
    val amount = BigInteger(this)
    require(amount <= MAX_BASE_UNITS && (!nonzero || amount.signum() > 0))
}

private object NativeHnsSendApprovalParser {
    private val magic = "HNVP".toByteArray(Charsets.US_ASCII)

    fun parse(bundle: ByteArray): NativeHnsSendApproval? {
        var token: NativeHnsValueActionToken? = null
        return try {
            parseWalletJsonBundle(bundle, magic, MAX_APPROVAL_JSON_BYTES) { value ->
                value.requireExactKeys("actionToken", "expiresAtUnix", "summary")
                val tokenText = value.get("actionToken") as? String
                    ?: throw IllegalArgumentException("action token is not text")
                val tokenAscii = tokenText.toByteArray(Charsets.US_ASCII)
                require(tokenText.toByteArray(Charsets.UTF_8).contentEquals(tokenAscii))
                token = NativeHnsValueActionToken.takeOwnership(tokenAscii)
                    ?: throw IllegalArgumentException("action token is not canonical")

                val summary = value.getJSONObject("summary").apply {
                    requireExactKeys(
                        "kind",
                        "amount",
                        "recipient",
                        "maximumFee",
                        "chain",
                        "finality",
                        "warnings",
                    )
                    require(get("kind") == "send")
                    require(get("chain") == "handshake")
                }
                val amount = summary.getJSONObject("amount").hnsBaseUnits(nonzero = true)
                val maximumFee = summary.getJSONObject("maximumFee").hnsBaseUnits(nonzero = true)
                val recipient = summary.get("recipient") as? String
                    ?: throw IllegalArgumentException("recipient is not text")
                require(
                    recipient.length in 1..MAX_RECIPIENT_CHARACTERS &&
                        recipient.all { it.code in 0x21..0x7e },
                )
                val finality = summary.get("finality") as? String
                    ?: throw IllegalArgumentException("finality is not text")
                require(finality == "proof_of_work_confirmations")
                val warnings = summary.getJSONArray("warnings").strictTextList()
                require(warnings == listOf("feeEstimateMayChange"))

                NativeHnsSendApproval(
                    actionToken = requireNotNull(token).also { token = null },
                    expiresAtUnix = exactUnsignedLong(value.get("expiresAtUnix")),
                    amountBaseUnits = amount,
                    recipient = recipient,
                    maximumFeeBaseUnits = maximumFee,
                    finality = finality,
                    warnings = warnings,
                )
            }
        } catch (_: Throwable) {
            null
        } finally {
            token?.close()
        }
    }

    private const val MAX_APPROVAL_JSON_BYTES = 16 * 1024
    private const val MAX_RECIPIENT_CHARACTERS = 512
}

private object NativeHnsSendReceiptParser {
    private val magic = "HNVX".toByteArray(Charsets.US_ASCII)
    private val lowercaseHex = Regex("[0-9a-f]{64}")

    fun parse(bundle: ByteArray): NativeHnsSendReceipt? = runCatching {
        parseWalletJsonBundle(bundle, magic, MAX_RESULT_JSON_BYTES) { value ->
            value.requireExactKeys("module", "txid", "acceptedAtUnix")
            require(value.get("module") == "handshake")
            val txid = value.get("txid") as? String
                ?: throw IllegalArgumentException("transaction id is not text")
            require(lowercaseHex.matches(txid) && txid.any { it != '0' })
            NativeHnsSendReceipt(
                txid = txid,
                acceptedAtUnix = exactUnsignedLong(value.get("acceptedAtUnix")),
            )
        }
    }.getOrNull()

    private const val MAX_RESULT_JSON_BYTES = 256 * 1024
}

private inline fun <T> parseWalletJsonBundle(
    bundle: ByteArray,
    expectedMagic: ByteArray,
    maximumJsonBytes: Int,
    parse: (JSONObject) -> T,
): T {
    require(bundle.size in HEADER_BYTES..(HEADER_BYTES + maximumJsonBytes))
    require(expectedMagic.indices.all { index -> bundle[index] == expectedMagic[index] })
    val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
    require(header.get().toInt() and 0xff == BUNDLE_VERSION)
    require(header.get().toInt() == 0)
    require(header.short.toInt() == 0)
    val jsonLength = header.int
    require(jsonLength in 2..maximumJsonBytes)
    require(bundle.size == HEADER_BYTES + jsonLength)
    val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
    return try {
        val json = jsonBytes.toString(Charsets.UTF_8)
        require(json.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
        parse(JSONObject(json))
    } finally {
        jsonBytes.fill(0)
    }
}

private fun JSONObject.hnsBaseUnits(nonzero: Boolean): String {
    requireExactKeys("asset", "base_units")
    require(get("asset") == "HNS")
    val value = get("base_units") as? String
        ?: throw IllegalArgumentException("base units are not text")
    require(CANONICAL_BASE_UNITS.matches(value))
    val amount = BigInteger(value)
    require(amount <= MAX_BASE_UNITS && (!nonzero || amount.signum() > 0))
    return value
}

private fun JSONArray.strictTextList(): List<String> =
    List(length()) { index ->
        get(index) as? String ?: throw IllegalArgumentException("array item is not text")
    }

private fun JSONObject.requireExactKeys(vararg expected: String) {
    require(keys().asSequence().toSet() == expected.toSet())
}

private fun exactUnsignedLong(value: Any): Long {
    require(value is Byte || value is Short || value is Int || value is Long)
    return (value as Number).toLong().also { require(it >= 0L) }
}

private const val HEADER_BYTES = 12
private const val BUNDLE_VERSION = 1
private const val MAX_APPROVAL_JSON_BYTES = 16 * 1024
private const val MAX_RESULT_JSON_BYTES = 256 * 1024
private const val MAX_INTENT_JSON_BYTES = 8 * 1024
private const val MAX_QUERY_JSON_BYTES = 4 * 1024
private const val MAX_NAME_CHARACTERS = 63
private const val MAX_RECIPIENT_CHARACTERS = 512
private const val MAX_OFFER_PAGE_SIZE = 64
private const val MIN_LISTING_LIFETIME_SECONDS = 10 * 60L
private const val MAX_LISTING_LIFETIME_SECONDS = 30 * 24 * 60 * 60L
private val MAX_BASE_UNITS = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
private val CANONICAL_BASE_UNITS = Regex("0|[1-9][0-9]{0,38}")
private val LOWERCASE_OBJECT_ID = Regex("[0-9a-f]{64}")
