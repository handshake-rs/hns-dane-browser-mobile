package com.denuoweb.hnsdane.wallet

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Closed projections returned by the wallet-owned Kyoto JNI controller.
 * These are app-owned Bitcoin values, not data fetched from an RPC node,
 * indexer, or marketplace relay.
 */
internal data class NativeBitcoinWalletSnapshot(
    val network: String,
    val receiveAddress: String,
    val confirmedSats: Long,
    val trustedPendingSats: Long,
    val untrustedPendingSats: Long,
    val immatureSats: Long,
    val totalSats: Long,
    val birthdayHeight: Long,
    val birthdayState: String,
    val synchronizedHeight: Long,
    val connectedPeerCount: Int,
    val requiredPeerCount: Int,
)

internal data class NativeBitcoinReceiveAddress(
    val receiveAddress: String,
    val snapshot: NativeBitcoinWalletSnapshot,
)

internal data class NativeBitcoinSynchronization(
    val snapshot: NativeBitcoinWalletSnapshot,
    val sequence: Long,
    val checkpointHeight: Long,
    val connectedPeerCount: Int,
    val requiredPeerCount: Int,
)

internal data class NativeBitcoinSyncProgress(
    val successfulHandshakes: Int,
    val requiredPeerCount: Int,
    val connectionFailures: Int,
    val peerTimeouts: Int,
    val incompatiblePeers: Int,
    val connectionsMet: Boolean,
    val chainHeight: Long?,
    val completionBasisPoints: Long,
)

internal data class NativeBitcoinSendApproval(
    val actionToken: NativeHnsValueActionToken,
    val destination: String,
    val amountSats: Long,
    val feeSats: Long,
    val maximumFeeSats: Long,
    val expiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeBitcoinSendReceipt(
    val txid: String,
    val wtxid: String,
    val attemptCount: Int,
    val submittedAtUnix: Long?,
)

internal data class NativeBitcoinHtlcFundingApproval(
    val actionToken: NativeHnsValueActionToken,
    val sessionId: String,
    val txid: String,
    val amountSats: Long,
    val feeSats: Long,
    val maximumFeeSats: Long,
    val refundAtUnix: Long,
    val expiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeBitcoinHtlcFundingReceipt(
    val sessionId: String,
    val txid: String,
    val attemptCount: Int,
    val submittedAtUnix: Long?,
)

internal data class NativeHnsHtlcFundingApproval(
    val actionToken: NativeHnsValueActionToken,
    val sessionId: String,
    val transactionId: String,
    val amountDollarydoos: Long,
    val feeDollarydoos: Long,
    val maximumFeeDollarydoos: Long,
    val refundAtUnix: Long,
    val expiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeHnsHtlcFundingReceipt(
    val sessionId: String,
    val transactionId: String,
    val acceptedAtUnix: Long,
)

internal data class NativeBtcForHnsOfferApproval(
    val actionToken: NativeHnsValueActionToken,
    val btcAmountSats: Long,
    val hnsAmountDollarydoos: Long,
    val bitcoinFeeReserveSats: Long,
    val totalBitcoinCommitmentSats: Long,
    val offerExpiresAtUnix: Long,
    val approvalExpiresAtUnix: Long,
    val connectedPeerRequiredForAnnouncement: Boolean,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeBtcForHnsOfferSummary(
    val offerId: String,
    val sessionId: String,
    val btcAmountSats: Long,
    val hnsAmountDollarydoos: Long,
    val bitcoinFeeReserveSats: Long,
    val createdAtUnix: Long,
    val expiresAtUnix: Long,
)

internal data class NativeDenuoExecutionSummary(
    val sessionId: String,
    val revision: Long,
    val state: String,
    val firstChain: String,
    val secondChain: String,
    val offeredAsset: String,
    val offeredAmount: Long,
    val receivedAsset: String,
    val receivedAmount: Long,
    val firstRefundAtUnix: Long,
    val secondRefundAtUnix: Long,
    val firstFundingConfirmed: Boolean,
    val secondFundingConfirmed: Boolean,
    val firstRedemptionConfirmed: Boolean,
    val secondRedemptionConfirmed: Boolean,
    val refundConfirmed: Boolean,
    val lastVerifiedAtUnix: Long,
    val failureReason: String?,
)

internal object NativeBitcoinWalletBundle {
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 16 * 1024
    private const val VERSION = 1
    private val magic = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())

    fun snapshot(bundle: ByteArray): NativeBitcoinWalletSnapshot? = parse(bundle) { json ->
        parseSnapshot(json)
    }

    fun receive(bundle: ByteArray): NativeBitcoinReceiveAddress? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("receiveAddress", "snapshot"))) return@parse null
        val receiveAddress = address(json.optString("receiveAddress", "")) ?: return@parse null
        val snapshot = parseSnapshot(json.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        if (receiveAddress != snapshot.receiveAddress) return@parse null
        NativeBitcoinReceiveAddress(receiveAddress, snapshot)
    }

    fun synchronization(bundle: ByteArray): NativeBitcoinSynchronization? = parse(bundle) { json ->
        if (
            !hasExactKeys(json, setOf(
                "snapshot", "sequence", "checkpointHeight", "connectedPeerCount", "requiredPeerCount",
            ))
        ) return@parse null
        val snapshot = parseSnapshot(json.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        val sequence = positiveLong(json, "sequence") ?: return@parse null
        val checkpointHeight = nonnegativeLong(json, "checkpointHeight") ?: return@parse null
        val connectedPeerCount = peerCount(json, "connectedPeerCount") ?: return@parse null
        val requiredPeerCount = peerCount(json, "requiredPeerCount") ?: return@parse null
        if (
            checkpointHeight != snapshot.synchronizedHeight ||
            connectedPeerCount != snapshot.connectedPeerCount ||
            requiredPeerCount != snapshot.requiredPeerCount
        ) return@parse null
        NativeBitcoinSynchronization(
            snapshot,
            sequence,
            checkpointHeight,
            connectedPeerCount,
            requiredPeerCount,
        )
    }

    fun syncProgress(bundle: ByteArray): NativeBitcoinSyncProgress? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "successfulHandshakes", "requiredPeerCount", "connectionFailures",
            "peerTimeouts", "incompatiblePeers", "connectionsMet", "chainHeight",
            "completionBasisPoints",
        ))) return@parse null
        val handshakes = json.optInt("successfulHandshakes", -1).takeIf { it in 0..255 }
            ?: return@parse null
        val requiredPeers = json.optInt("requiredPeerCount", -1).takeIf { it in 1..255 }
            ?: return@parse null
        val connectionFailures = json.optInt("connectionFailures", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val peerTimeouts = json.optInt("peerTimeouts", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val incompatiblePeers = json.optInt("incompatiblePeers", -1).takeIf { it in 0..65_535 }
            ?: return@parse null
        val connectionsMet = when (json.opt("connectionsMet")) {
            true -> true
            false -> false
            else -> return@parse null
        }
        val chainHeight = if (json.isNull("chainHeight")) null else
            nonnegativeLong(json, "chainHeight") ?: return@parse null
        val completion = nonnegativeLong(json, "completionBasisPoints")
            ?.takeIf { it <= 10_000L } ?: return@parse null
        NativeBitcoinSyncProgress(
            handshakes,
            requiredPeers,
            connectionFailures,
            peerTimeouts,
            incompatiblePeers,
            connectionsMet,
            chainHeight,
            completion,
        )
    }

    fun sendApproval(bundle: ByteArray): NativeBitcoinSendApproval? = parse(bundle) { json ->
        if (
            !hasExactKeys(json, setOf(
                "actionToken", "destination", "amountSats", "feeSats", "maximumFeeSats", "expiresAtUnix",
            ))
        ) return@parse null
        val token = json.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val actionToken = NativeHnsValueActionToken.takeOwnership(token) ?: return@parse null
        val destination = address(json.optString("destination", ""))
        val amount = positiveLong(json, "amountSats")
        val fee = positiveLong(json, "feeSats")
        val maximumFee = positiveLong(json, "maximumFeeSats")
        val expires = positiveLong(json, "expiresAtUnix")
        if (destination == null || amount == null || fee == null || maximumFee == null || expires == null || fee > maximumFee) {
            actionToken.close()
            return@parse null
        }
        NativeBitcoinSendApproval(actionToken, destination, amount, fee, maximumFee, expires)
    }

    fun sendReceipt(bundle: ByteArray): NativeBitcoinSendReceipt? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("txid", "wtxid", "attemptCount", "submittedAtUnix"))) return@parse null
        val txid = hexHash(json.optString("txid", "")) ?: return@parse null
        val wtxid = hexHash(json.optString("wtxid", "")) ?: return@parse null
        val attempts = json.optInt("attemptCount", -1).takeIf { it in 1..16 } ?: return@parse null
        val submitted = if (json.isNull("submittedAtUnix")) null else positiveLong(json, "submittedAtUnix")
            ?: return@parse null
        NativeBitcoinSendReceipt(txid, wtxid, attempts, submitted)
    }

    fun htlcFundingApproval(bundle: ByteArray): NativeBitcoinHtlcFundingApproval? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "actionToken", "sessionId", "txid", "amountSats", "feeSats",
            "maximumFeeSats", "refundAtUnix", "expiresAtUnix",
        ))) return@parse null
        val tokenBytes = json.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val token = NativeHnsValueActionToken.takeOwnership(tokenBytes) ?: return@parse null
        val sessionId = hexHash(json.optString("sessionId", ""))
        val txid = hexHash(json.optString("txid", ""))
        val amount = positiveLong(json, "amountSats")
        val fee = positiveLong(json, "feeSats")
        val maximumFee = positiveLong(json, "maximumFeeSats")
        val refundAt = positiveLong(json, "refundAtUnix")
        val expiresAt = positiveLong(json, "expiresAtUnix")
        if (
            sessionId == null || txid == null || amount == null || fee == null ||
            maximumFee == null || refundAt == null || expiresAt == null ||
            fee > maximumFee || expiresAt >= refundAt
        ) {
            token.close()
            return@parse null
        }
        NativeBitcoinHtlcFundingApproval(
            token, sessionId, txid, amount, fee, maximumFee, refundAt, expiresAt,
        )
    }

    fun htlcFundingReceipt(bundle: ByteArray): NativeBitcoinHtlcFundingReceipt? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("sessionId", "txid", "attemptCount", "submittedAtUnix"))) {
            return@parse null
        }
        val sessionId = hexHash(json.optString("sessionId", "")) ?: return@parse null
        val txid = hexHash(json.optString("txid", "")) ?: return@parse null
        val attempts = json.optInt("attemptCount", -1).takeIf { it in 1..16 } ?: return@parse null
        val submitted = if (json.isNull("submittedAtUnix")) null else
            positiveLong(json, "submittedAtUnix") ?: return@parse null
        NativeBitcoinHtlcFundingReceipt(sessionId, txid, attempts, submitted)
    }

    fun hnsHtlcFundingApproval(bundle: ByteArray): NativeHnsHtlcFundingApproval? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "actionToken", "sessionId", "transactionId", "amountDollarydoos",
            "feeDollarydoos", "maximumFeeDollarydoos", "refundAtUnix", "expiresAtUnix",
        ))) return@parse null
        val token = NativeHnsValueActionToken.takeOwnership(
            json.optString("actionToken", "").toByteArray(Charsets.US_ASCII),
        ) ?: return@parse null
        val session = hexHash(json.optString("sessionId", ""))
        val transaction = hexHash(json.optString("transactionId", ""))
        val amount = positiveLong(json, "amountDollarydoos")
        val fee = positiveLong(json, "feeDollarydoos")
        val maximumFee = positiveLong(json, "maximumFeeDollarydoos")
        val refund = positiveLong(json, "refundAtUnix")
        val expires = positiveLong(json, "expiresAtUnix")
        if (session == null || transaction == null || amount == null || fee == null ||
            maximumFee == null || refund == null || expires == null || fee > maximumFee ||
            expires >= refund
        ) {
            token.close()
            return@parse null
        }
        NativeHnsHtlcFundingApproval(
            token, session, transaction, amount, fee, maximumFee, refund, expires,
        )
    }

    fun hnsHtlcFundingReceipt(bundle: ByteArray): NativeHnsHtlcFundingReceipt? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("sessionId", "transactionId", "acceptedAtUnix"))) {
            return@parse null
        }
        NativeHnsHtlcFundingReceipt(
            hexHash(json.optString("sessionId", "")) ?: return@parse null,
            hexHash(json.optString("transactionId", "")) ?: return@parse null,
            positiveLong(json, "acceptedAtUnix") ?: return@parse null,
        )
    }

    fun btcForHnsApproval(bundle: ByteArray): NativeBtcForHnsOfferApproval? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "actionToken", "btcAmountSats", "hnsAmountDollarydoos", "bitcoinFeeReserveSats",
            "totalBitcoinCommitmentSats", "offerExpiresAtUnix", "approvalExpiresAtUnix",
            "connectedPeerRequiredForAnnouncement",
        ))) return@parse null
        val tokenBytes = json.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val token = NativeHnsValueActionToken.takeOwnership(tokenBytes) ?: return@parse null
        val btc = positiveLong(json, "btcAmountSats")
        val hns = positiveLong(json, "hnsAmountDollarydoos")
        val reserve = positiveLong(json, "bitcoinFeeReserveSats")
        val total = positiveLong(json, "totalBitcoinCommitmentSats")
        val offerExpiry = positiveLong(json, "offerExpiresAtUnix")
        val approvalExpiry = positiveLong(json, "approvalExpiresAtUnix")
        val peerRequired = json.opt("connectedPeerRequiredForAnnouncement") as? Boolean
        if (
            btc == null || hns == null || reserve == null || total == null ||
            offerExpiry == null || approvalExpiry == null || peerRequired == null ||
            btc > Long.MAX_VALUE - reserve || btc + reserve != total
        ) {
            token.close()
            return@parse null
        }
        NativeBtcForHnsOfferApproval(
            token, btc, hns, reserve, total, offerExpiry, approvalExpiry, peerRequired,
        )
    }

    fun btcForHnsSummary(bundle: ByteArray): NativeBtcForHnsOfferSummary? = parse(bundle) {
        parseBtcForHnsSummary(it)
    }

    fun btcForHnsOffers(bundle: ByteArray): List<NativeBtcForHnsOfferSummary>? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("offers"))) return@parse null
        val array = json.optJSONArray("offers") ?: return@parse null
        if (array.length() > 1_024) return@parse null
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseBtcForHnsSummary(array.optJSONObject(index) ?: return@parse null)
                    ?: return@parse null)
            }
        }
    }

    fun denuoExecutions(bundle: ByteArray): List<NativeDenuoExecutionSummary>? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("executions"))) return@parse null
        val array = json.optJSONArray("executions") ?: return@parse null
        if (array.length() > 1_024) return@parse null
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseDenuoExecution(array.optJSONObject(index) ?: return@parse null) ?: return@parse null)
            }
        }
    }

    private fun parseBtcForHnsSummary(json: JSONObject): NativeBtcForHnsOfferSummary? {
        if (!hasExactKeys(json, setOf(
            "offerId", "sessionId", "btcAmountSats", "hnsAmountDollarydoos",
            "bitcoinFeeReserveSats", "createdAtUnix", "expiresAtUnix",
        ))) return null
        val created = positiveLong(json, "createdAtUnix") ?: return null
        val expires = positiveLong(json, "expiresAtUnix")?.takeIf { it > created } ?: return null
        return NativeBtcForHnsOfferSummary(
            hexHash(json.optString("offerId", "")) ?: return null,
            hexHash(json.optString("sessionId", "")) ?: return null,
            positiveLong(json, "btcAmountSats") ?: return null,
            positiveLong(json, "hnsAmountDollarydoos") ?: return null,
            positiveLong(json, "bitcoinFeeReserveSats") ?: return null,
            created,
            expires,
        )
    }

    private fun parseDenuoExecution(json: JSONObject): NativeDenuoExecutionSummary? {
        if (!hasExactKeys(json, setOf(
            "sessionId", "revision", "state", "firstChain", "secondChain",
            "offeredAsset", "offeredAmount", "receivedAsset", "receivedAmount",
            "firstRefundAtUnix", "secondRefundAtUnix", "firstFundingConfirmed",
            "secondFundingConfirmed", "firstRedemptionConfirmed", "secondRedemptionConfirmed",
            "refundConfirmed", "lastVerifiedAtUnix", "failureReason",
        ))) return null
        val state = json.optString("state", "").takeIf { it in DENUO_EXECUTION_STATES }
            ?: return null
        val firstChain = json.optString("firstChain", "").takeIf { it in DENUO_CHAINS }
            ?: return null
        val secondChain = json.optString("secondChain", "").takeIf { it in DENUO_CHAINS }
            ?: return null
        val offeredAsset = json.optString("offeredAsset", "").takeIf { it in DENUO_ASSETS }
            ?: return null
        val receivedAsset = json.optString("receivedAsset", "").takeIf { it in DENUO_ASSETS }
            ?: return null
        val firstRefund = positiveLong(json, "firstRefundAtUnix") ?: return null
        val secondRefund = positiveLong(json, "secondRefundAtUnix") ?: return null
        val failure = if (json.isNull("failureReason")) null else
            json.optString("failureReason", "").takeIf { it.isNotEmpty() && it.length <= 256 }
                ?: return null
        if (firstChain == secondChain || offeredAsset == receivedAsset || firstRefund <= secondRefund) {
            return null
        }
        return NativeDenuoExecutionSummary(
            sessionId = hexHash(json.optString("sessionId", "")) ?: return null,
            revision = positiveLong(json, "revision") ?: return null,
            state = state,
            firstChain = firstChain,
            secondChain = secondChain,
            offeredAsset = offeredAsset,
            offeredAmount = positiveLong(json, "offeredAmount") ?: return null,
            receivedAsset = receivedAsset,
            receivedAmount = positiveLong(json, "receivedAmount") ?: return null,
            firstRefundAtUnix = firstRefund,
            secondRefundAtUnix = secondRefund,
            firstFundingConfirmed = exactBoolean(json, "firstFundingConfirmed") ?: return null,
            secondFundingConfirmed = exactBoolean(json, "secondFundingConfirmed") ?: return null,
            firstRedemptionConfirmed = exactBoolean(json, "firstRedemptionConfirmed") ?: return null,
            secondRedemptionConfirmed = exactBoolean(json, "secondRedemptionConfirmed") ?: return null,
            refundConfirmed = exactBoolean(json, "refundConfirmed") ?: return null,
            lastVerifiedAtUnix = positiveLong(json, "lastVerifiedAtUnix") ?: return null,
            failureReason = failure,
        )
    }

    private inline fun <T> parse(bundle: ByteArray, project: (JSONObject) -> T?): T? {
        return try {
            if (bundle.size !in HEADER_BYTES..HEADER_BYTES + MAX_JSON_BYTES) return null
            val buffer = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN)
            val foundMagic = ByteArray(4)
            buffer.get(foundMagic)
            if (!foundMagic.contentEquals(magic)) return null
            if (buffer.get().toInt() and 0xff != VERSION) return null
            if (buffer.get().toInt() != 0 || buffer.short.toInt() != 0) return null
            val length = buffer.int
            if (length !in 2..MAX_JSON_BYTES || length != buffer.remaining()) return null
            val encoded = ByteArray(length)
            buffer.get(encoded)
            val text = encoded.toString(Charsets.UTF_8)
            if (text.toByteArray(Charsets.UTF_8).contentEquals(encoded).not()) return null
            project(JSONObject(text))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSnapshot(json: JSONObject): NativeBitcoinWalletSnapshot? {
        if (
            !hasExactKeys(json, setOf(
                "network", "receiveAddress", "confirmedSats", "trustedPendingSats",
                "untrustedPendingSats", "immatureSats", "totalSats", "synchronizedHeight",
                "birthdayHeight", "birthdayState", "connectedPeerCount", "requiredPeerCount",
            ))
        ) return null
        val network = json.optString("network", "")
        if (network !in setOf("mainnet", "testnet", "testnet4", "signet", "regtest")) return null
        val receiveAddress = address(json.optString("receiveAddress", "")) ?: return null
        val confirmed = nonnegativeLong(json, "confirmedSats") ?: return null
        val trusted = nonnegativeLong(json, "trustedPendingSats") ?: return null
        val untrusted = nonnegativeLong(json, "untrustedPendingSats") ?: return null
        val immature = nonnegativeLong(json, "immatureSats") ?: return null
        val total = nonnegativeLong(json, "totalSats") ?: return null
        if (total != confirmed + trusted + untrusted + immature) return null
        return NativeBitcoinWalletSnapshot(
            network,
            receiveAddress,
            confirmed,
            trusted,
            untrusted,
            immature,
            total,
            nonnegativeLong(json, "birthdayHeight") ?: return null,
            json.optString("birthdayState", "").takeIf {
                it in setOf(
                    "awaitingCreationTip", "recoveryUnknown",
                    "recoveryPendingValidation", "validated",
                )
            } ?: return null,
            nonnegativeLong(json, "synchronizedHeight") ?: return null,
            peerCount(json, "connectedPeerCount") ?: return null,
            peerCount(json, "requiredPeerCount") ?: return null,
        )
    }

    private fun address(value: String): String? =
        value.takeIf { it.isNotBlank() && it.length <= 128 && it.all(Char::isLetterOrDigit) }

    private fun hexHash(value: String): String? =
        value.takeIf {
            it.length == 64 && it.any { character -> character != '0' } &&
                it.all { character -> character in '0'..'9' || character in 'a'..'f' }
        }

    private fun nonnegativeLong(json: JSONObject, key: String): Long? =
        json.optLong(key, -1L).takeIf { it >= 0L }

    private fun positiveLong(json: JSONObject, key: String): Long? =
        json.optLong(key, 0L).takeIf { it > 0L }

    private fun peerCount(json: JSONObject, key: String): Int? =
        json.optInt(key, -1).takeIf { it in 0..8 }

    private fun exactBoolean(json: JSONObject, key: String): Boolean? = json.opt(key) as? Boolean

    private fun hasExactKeys(json: JSONObject, expected: Set<String>): Boolean {
        val actual = HashSet<String>()
        val keys = json.keys()
        while (keys.hasNext()) actual.add(keys.next())
        return actual == expected
    }

    private val DENUO_CHAINS = setOf("bitcoin", "handshake")
    private val DENUO_ASSETS = setOf("btc", "hns")
    private val DENUO_EXECUTION_STATES = setOf(
        "offer_published", "offer_take_received", "offer_reserved", "terms_frozen",
        "refunds_prepared", "first_funding_pending", "first_funded", "second_funding_pending",
        "both_funded", "first_redeemed", "secret_observed", "second_redeemed", "completed",
        "refund_eligible", "refund_broadcast", "refunded", "failed",
    )
}
