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
    val recentActivity: List<NativeBitcoinActivity>,
    val recentActivityTotal: Int,
)

internal data class NativeBitcoinActivity(
    val txid: String,
    val direction: String,
    val amountSats: Long,
    val feeSats: Long?,
    val status: String,
    val blockHeight: Long?,
    val confirmationCount: Long?,
    val lastChangedAtUnix: Long,
)

internal data class NativeBitcoinActivityPage(
    val offset: Int,
    val total: Int,
    val activity: List<NativeBitcoinActivity>,
    val hasMore: Boolean,
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
    val networkMs: Long,
    val walletApplyMs: Long,
    val chainValidationMs: Long,
    val reconciliationMs: Long,
    val totalMs: Long,
)

internal data class NativeBitcoinSyncProgress(
    val stage: String,
    val successfulHandshakes: Int,
    val requiredPeerCount: Int,
    val connectionFailures: Int,
    val peerTimeouts: Int,
    val incompatiblePeers: Int,
    val connectionsMet: Boolean,
    val chainHeight: Long?,
    val completionBasisPoints: Long,
    val processedFilterCount: Long,
    val matchedFilterCount: Long,
    val downloadedBlockCount: Long,
    val cycleElapsedMs: Long,
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

/**
 * Public, bounded reasons why a Bitcoin send could not be prepared. These are
 * intentionally actionable categories rather than native error text, which
 * may contain volatile implementation details and must not become UI API.
 */
internal enum class NativeBitcoinSendPreparationFailure {
    AmountBelowMinimum,
    InsufficientConfirmedFunds,
    InvalidDestination,
    FeeCapBelowMinimum,
    FeeCapTooLow,
    ActionPending,
    WalletUnavailable,
    InvalidRequest,
    Retry,
}

/** The exact result of a Bitcoin send preparation attempt. */
internal data class NativeBitcoinSendPreparation(
    val approval: NativeBitcoinSendApproval?,
    val failure: NativeBitcoinSendPreparationFailure?,
) {
    init {
        require((approval == null) != (failure == null))
    }
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
    val outputIndex: Int,
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
    val outputIndex: Int,
    val acceptedAtUnix: Long,
)

internal data class NativeSwapSettlementApproval(
    val actionToken: NativeHnsValueActionToken,
    val sessionId: String,
    val action: String,
    val transactionId: String,
    val inputAmount: Long,
    val outputAmount: Long,
    val fee: Long,
    val maximumFee: Long,
    val expiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeSwapSettlementReceipt(
    val sessionId: String,
    val action: String,
    val transactionId: String,
    val acceptedAtUnix: Long?,
    val attemptCount: Int?,
    val submittedAtUnix: Long?,
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

internal data class NativeHnsForBtcOfferApproval(
    val actionToken: NativeHnsValueActionToken,
    val hnsAmountDollarydoos: Long,
    val btcAmountSats: Long,
    val hnsFeeReserveDollarydoos: Long,
    val totalHnsCommitmentDollarydoos: Long,
    val offerExpiresAtUnix: Long,
    val approvalExpiresAtUnix: Long,
    val connectedPeerRequiredForAnnouncement: Boolean,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal data class NativeDirectOfferSummary(
    val offerId: String,
    val sessionId: String,
    val makerSellsHns: Boolean,
    val offeredAsset: String,
    val offeredAmount: Long,
    val receivedAsset: String,
    val receivedAmount: Long,
    val btcAmountSats: Long,
    val hnsAmountDollarydoos: Long,
    val offeredFeeReserve: Long?,
    val local: Boolean,
    val createdAtUnix: Long,
    val expiresAtUnix: Long,
)

internal data class NativeDirectOfferTakeApproval(
    val actionToken: NativeHnsValueActionToken,
    val offer: NativeDirectOfferSummary,
    val receivedFeeReserve: Long,
    val totalReceivedAssetCommitment: Long,
    val takeExpiresAtUnix: Long,
    val approvalExpiresAtUnix: Long,
) : AutoCloseable {
    override fun close() = actionToken.close()
}

internal sealed interface NativeDirectOfferTakePreparation {
    data class Approval(val value: NativeDirectOfferTakeApproval) :
        NativeDirectOfferTakePreparation

    data class InsufficientFunds(
        val receivedAsset: String,
        val confirmedAmount: Long,
    ) : NativeDirectOfferTakePreparation
}

internal data class NativeDirectOfferTakeSummary(
    val offerId: String,
    val sessionId: String,
    val offeredAsset: String,
    val offeredAmount: Long,
    val receivedAsset: String,
    val receivedAmount: Long,
    val receivedFeeReserve: Long,
    val createdAtUnix: Long,
    val expiresAtUnix: Long,
)

internal data class NativeShakescapeExecutionSummary(
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

internal data class NativeBitcoinBroadcastRecovery(
    val totalApproved: Long,
    val unobservedPrepared: Long,
    val unobservedSubmissionStarted: Long,
    val unobservedSubmitted: Long,
    val observed: Long,
    val highestAttemptCount: Int,
    val lastChangedAtUnix: Long?,
)

internal data class NativeShakescapeExecutionStatus(
    val executions: List<NativeShakescapeExecutionSummary>,
    val pendingAcceptances: List<NativeDirectOfferTakeSummary>,
    val bitcoinBroadcastRecovery: NativeBitcoinBroadcastRecovery?,
)

internal object NativeBitcoinWalletBundle {
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 16 * 1024
    private const val MAX_RECENT_ACTIVITY = 20
    private const val MAX_RETAINED_ACTIVITY = 4_096
    private const val MAX_DISPLAY_UNIX = 253_402_300_799L
    private const val VERSION = 1
    private val magic = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())

    fun snapshot(bundle: ByteArray): NativeBitcoinWalletSnapshot? = parse(bundle) { json ->
        parseSnapshot(json)
    }

    fun activityPage(bundle: ByteArray): NativeBitcoinActivityPage? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("offset", "total", "activity", "hasMore"))) {
            return@parse null
        }
        val offset = json.optInt("offset", -1).takeIf { it >= 0 } ?: return@parse null
        val total = json.optInt("total", -1)
            .takeIf { it in 0..MAX_RETAINED_ACTIVITY } ?: return@parse null
        if (offset > total) return@parse null
        val itemsJson = json.optJSONArray("activity") ?: return@parse null
        if (itemsJson.length() > MAX_RECENT_ACTIVITY) return@parse null
        val activity = ArrayList<NativeBitcoinActivity>(itemsJson.length())
        for (index in 0 until itemsJson.length()) {
            val item = parseActivity(itemsJson.optJSONObject(index) ?: return@parse null)
                ?: return@parse null
            if (activity.any { it.txid == item.txid }) return@parse null
            activity.add(item)
        }
        if (activity.size != minOf(MAX_RECENT_ACTIVITY, total - offset) ||
            activity.zipWithNext().any { (left, right) ->
                left.lastChangedAtUnix < right.lastChangedAtUnix
            }) return@parse null
        val hasMore = exactBoolean(json, "hasMore") ?: return@parse null
        if (hasMore != (offset + activity.size < total)) return@parse null
        NativeBitcoinActivityPage(offset, total, activity, hasMore)
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
                "networkMs", "walletApplyMs", "chainValidationMs", "reconciliationMs", "totalMs",
            ))
        ) return@parse null
        val snapshot = parseSnapshot(json.optJSONObject("snapshot") ?: return@parse null)
            ?: return@parse null
        val sequence = positiveLong(json, "sequence") ?: return@parse null
        val checkpointHeight = nonnegativeLong(json, "checkpointHeight") ?: return@parse null
        val connectedPeerCount = peerCount(json, "connectedPeerCount") ?: return@parse null
        val requiredPeerCount = peerCount(json, "requiredPeerCount") ?: return@parse null
        val networkMs = nonnegativeLong(json, "networkMs") ?: return@parse null
        val walletApplyMs = nonnegativeLong(json, "walletApplyMs") ?: return@parse null
        val chainValidationMs = nonnegativeLong(json, "chainValidationMs") ?: return@parse null
        val reconciliationMs = nonnegativeLong(json, "reconciliationMs") ?: return@parse null
        val totalMs = nonnegativeLong(json, "totalMs") ?: return@parse null
        if (
            checkpointHeight != snapshot.synchronizedHeight ||
            connectedPeerCount != snapshot.connectedPeerCount ||
            requiredPeerCount != snapshot.requiredPeerCount ||
            networkMs > totalMs || walletApplyMs > totalMs || chainValidationMs > totalMs ||
            reconciliationMs > totalMs
        ) return@parse null
        NativeBitcoinSynchronization(
            snapshot,
            sequence,
            checkpointHeight,
            connectedPeerCount,
            requiredPeerCount,
            networkMs,
            walletApplyMs,
            chainValidationMs,
            reconciliationMs,
            totalMs,
        )
    }

    fun syncProgress(bundle: ByteArray): NativeBitcoinSyncProgress? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "stage", "successfulHandshakes", "requiredPeerCount", "connectionFailures",
            "peerTimeouts", "incompatiblePeers", "connectionsMet", "chainHeight",
            "completionBasisPoints", "processedFilterCount", "matchedFilterCount",
            "downloadedBlockCount", "cycleElapsedMs",
        ))) return@parse null
        val stage = json.optString("stage", "").takeIf {
            it in setOf(
                "connecting", "syncing_filters", "fetching_blocks", "applying_wallet",
                "validating_chain", "reconciling", "ready", "failed",
            )
        } ?: return@parse null
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
        val processedFilters = nonnegativeLong(json, "processedFilterCount") ?: return@parse null
        val matchedFilters = nonnegativeLong(json, "matchedFilterCount") ?: return@parse null
        val downloadedBlocks = nonnegativeLong(json, "downloadedBlockCount") ?: return@parse null
        val cycleElapsedMs = nonnegativeLong(json, "cycleElapsedMs") ?: return@parse null
        if (matchedFilters > processedFilters || downloadedBlocks > matchedFilters) return@parse null
        NativeBitcoinSyncProgress(
            stage,
            handshakes,
            requiredPeers,
            connectionFailures,
            peerTimeouts,
            incompatiblePeers,
            connectionsMet,
            chainHeight,
            completion,
            processedFilters,
            matchedFilters,
            downloadedBlocks,
            cycleElapsedMs,
        )
    }

    fun sendPreparation(bundle: ByteArray): NativeBitcoinSendPreparation? = parse(bundle) { json ->
        when (json.optString("outcome", "")) {
            "approved" -> {
                if (!hasExactKeys(json, setOf("outcome", "approval"))) return@parse null
                val approval = parseSendApproval(json.optJSONObject("approval") ?: return@parse null)
                    ?: return@parse null
                NativeBitcoinSendPreparation(approval, null)
            }
            "rejected" -> {
                if (!hasExactKeys(json, setOf("outcome", "reason"))) return@parse null
                val failure = when (json.optString("reason", "")) {
                    "amount_below_minimum" -> NativeBitcoinSendPreparationFailure.AmountBelowMinimum
                    "insufficient_confirmed_funds" -> NativeBitcoinSendPreparationFailure.InsufficientConfirmedFunds
                    "invalid_destination" -> NativeBitcoinSendPreparationFailure.InvalidDestination
                    "fee_cap_below_minimum" -> NativeBitcoinSendPreparationFailure.FeeCapBelowMinimum
                    "fee_cap_too_low" -> NativeBitcoinSendPreparationFailure.FeeCapTooLow
                    "action_pending" -> NativeBitcoinSendPreparationFailure.ActionPending
                    "wallet_unavailable" -> NativeBitcoinSendPreparationFailure.WalletUnavailable
                    "invalid_request" -> NativeBitcoinSendPreparationFailure.InvalidRequest
                    "retry" -> NativeBitcoinSendPreparationFailure.Retry
                    else -> return@parse null
                }
                NativeBitcoinSendPreparation(null, failure)
            }
            else -> null
        }
    }

    private fun parseSendApproval(json: JSONObject): NativeBitcoinSendApproval? {
        if (
            !hasExactKeys(json, setOf(
                "actionToken", "destination", "amountSats", "feeSats", "maximumFeeSats", "expiresAtUnix",
            ))
        ) return null
        val token = json.optString("actionToken", "").toByteArray(Charsets.US_ASCII)
        val actionToken = NativeHnsValueActionToken.takeOwnership(token) ?: return null
        val destination = address(json.optString("destination", ""))
        val amount = positiveLong(json, "amountSats")
        val fee = positiveLong(json, "feeSats")
        val maximumFee = positiveLong(json, "maximumFeeSats")
        val expires = positiveLong(json, "expiresAtUnix")
        if (destination == null || amount == null || fee == null || maximumFee == null || expires == null || fee > maximumFee) {
            actionToken.close()
            return null
        }
        return NativeBitcoinSendApproval(actionToken, destination, amount, fee, maximumFee, expires)
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
        if (!hasExactKeys(json, setOf(
            "sessionId", "txid", "outputIndex", "attemptCount", "submittedAtUnix",
        ))) {
            return@parse null
        }
        val sessionId = hexHash(json.optString("sessionId", "")) ?: return@parse null
        val txid = hexHash(json.optString("txid", "")) ?: return@parse null
        val outputIndex = json.optInt("outputIndex", -1).takeIf { it >= 0 } ?: return@parse null
        val attempts = json.optInt("attemptCount", -1).takeIf { it in 1..16 } ?: return@parse null
        val submitted = if (json.isNull("submittedAtUnix")) null else
            positiveLong(json, "submittedAtUnix") ?: return@parse null
        NativeBitcoinHtlcFundingReceipt(sessionId, txid, outputIndex, attempts, submitted)
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
        if (!hasExactKeys(json, setOf(
            "sessionId", "transactionId", "outputIndex", "acceptedAtUnix",
        ))) {
            return@parse null
        }
        NativeHnsHtlcFundingReceipt(
            hexHash(json.optString("sessionId", "")) ?: return@parse null,
            hexHash(json.optString("transactionId", "")) ?: return@parse null,
            json.optInt("outputIndex", -1).takeIf { it >= 0 } ?: return@parse null,
            positiveLong(json, "acceptedAtUnix") ?: return@parse null,
        )
    }

    fun swapSettlementApproval(
        bundle: ByteArray,
        bitcoin: Boolean,
    ): NativeSwapSettlementApproval? = parse(bundle) { json ->
        val transactionKey = if (bitcoin) "txid" else "transactionId"
        val inputKey = if (bitcoin) "inputAmountSats" else "inputAmountDollarydoos"
        val outputKey = if (bitcoin) "outputAmountSats" else "outputAmountDollarydoos"
        val feeKey = if (bitcoin) "feeSats" else "feeDollarydoos"
        val maximumFeeKey = if (bitcoin) "maximumFeeSats" else "maximumFeeDollarydoos"
        if (!hasExactKeys(json, setOf(
            "actionToken", "sessionId", "action", transactionKey, inputKey, outputKey,
            feeKey, maximumFeeKey, "expiresAtUnix",
        ))) return@parse null
        val token = NativeHnsValueActionToken.takeOwnership(
            json.optString("actionToken", "").toByteArray(Charsets.US_ASCII),
        ) ?: return@parse null
        val session = hexHash(json.optString("sessionId", ""))
        val transaction = hexHash(json.optString(transactionKey, ""))
        val action = json.optString("action", "").takeIf { it == "redeem" || it == "refund" }
        val input = positiveLong(json, inputKey)
        val output = positiveLong(json, outputKey)
        val fee = positiveLong(json, feeKey)
        val maximumFee = positiveLong(json, maximumFeeKey)
        val expires = positiveLong(json, "expiresAtUnix")
        if (session == null || transaction == null || action == null || input == null ||
            output == null || fee == null || maximumFee == null || expires == null ||
            fee > maximumFee || fee >= input || input - fee != output
        ) {
            token.close()
            return@parse null
        }
        NativeSwapSettlementApproval(
            token, session, action, transaction, input, output, fee, maximumFee, expires,
        )
    }

    fun swapSettlementReceipt(
        bundle: ByteArray,
        bitcoin: Boolean,
    ): NativeSwapSettlementReceipt? = parse(bundle) { json ->
        val transactionKey = if (bitcoin) "txid" else "transactionId"
        val expected = if (bitcoin) {
            setOf("sessionId", "action", transactionKey, "attemptCount", "submittedAtUnix")
        } else {
            setOf("sessionId", "action", transactionKey, "acceptedAtUnix")
        }
        if (!hasExactKeys(json, expected)) return@parse null
        val session = hexHash(json.optString("sessionId", "")) ?: return@parse null
        val transaction = hexHash(json.optString(transactionKey, "")) ?: return@parse null
        val action = json.optString("action", "").takeIf { it == "redeem" || it == "refund" }
            ?: return@parse null
        if (bitcoin) {
            val attempts = json.optInt("attemptCount", -1).takeIf { it in 1..16 }
                ?: return@parse null
            val submitted = if (json.isNull("submittedAtUnix")) null else
                positiveLong(json, "submittedAtUnix") ?: return@parse null
            NativeSwapSettlementReceipt(session, action, transaction, null, attempts, submitted)
        } else {
            NativeSwapSettlementReceipt(
                session, action, transaction, positiveLong(json, "acceptedAtUnix") ?: return@parse null,
                null, null,
            )
        }
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

    fun hnsForBtcApproval(bundle: ByteArray): NativeHnsForBtcOfferApproval? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "actionToken", "hnsAmountDollarydoos", "btcAmountSats",
            "hnsFeeReserveDollarydoos", "totalHnsCommitmentDollarydoos",
            "offerExpiresAtUnix", "approvalExpiresAtUnix",
            "connectedPeerRequiredForAnnouncement",
        ))) return@parse null
        val token = NativeHnsValueActionToken.takeOwnership(
            json.optString("actionToken", "").toByteArray(Charsets.US_ASCII),
        ) ?: return@parse null
        val hns = positiveLong(json, "hnsAmountDollarydoos")
        val btc = positiveLong(json, "btcAmountSats")
        val reserve = positiveLong(json, "hnsFeeReserveDollarydoos")
        val total = positiveLong(json, "totalHnsCommitmentDollarydoos")
        val offerExpiry = positiveLong(json, "offerExpiresAtUnix")
        val approvalExpiry = positiveLong(json, "approvalExpiresAtUnix")
        val peerRequired = json.opt("connectedPeerRequiredForAnnouncement") as? Boolean
        if (hns == null || btc == null || reserve == null || total == null ||
            offerExpiry == null || approvalExpiry == null || peerRequired == null ||
            hns > Long.MAX_VALUE - reserve || hns + reserve != total
        ) {
            token.close()
            return@parse null
        }
        NativeHnsForBtcOfferApproval(
            token, hns, btc, reserve, total, offerExpiry, approvalExpiry, peerRequired,
        )
    }

    fun directOffers(bundle: ByteArray): List<NativeDirectOfferSummary>? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf("offers"))) return@parse null
        val array = json.optJSONArray("offers") ?: return@parse null
        if (array.length() > 1_024) return@parse null
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseDirectOffer(array.optJSONObject(index) ?: return@parse null)
                    ?: return@parse null)
            }
        }
    }

    fun directOfferSummary(bundle: ByteArray): NativeDirectOfferSummary? = parse(bundle) {
        parseDirectOffer(it)
    }

    fun directOfferTakePreparation(bundle: ByteArray): NativeDirectOfferTakePreparation? = parse(bundle) { json ->
        if (hasExactKeys(json, setOf("failure", "receivedAsset", "confirmedAmount"))) {
            val failure = json.optString("failure", "")
            val receivedAsset = json.optString("receivedAsset", "").takeIf {
                it == "btc" && failure == "insufficientBitcoin" ||
                    it == "hns" && failure == "insufficientHns"
            } ?: return@parse null
            val confirmed = nonnegativeLong(json, "confirmedAmount") ?: return@parse null
            return@parse NativeDirectOfferTakePreparation.InsufficientFunds(
                receivedAsset,
                confirmed,
            )
        }
        if (!hasExactKeys(json, setOf(
            "actionToken", "offer", "receivedFeeReserve", "totalReceivedAssetCommitment",
            "takeExpiresAtUnix", "approvalExpiresAtUnix",
        ))) return@parse null
        val token = NativeHnsValueActionToken.takeOwnership(
            json.optString("actionToken", "").toByteArray(Charsets.US_ASCII),
        ) ?: return@parse null
        val offer = parseDirectOffer(json.optJSONObject("offer") ?: run { token.close(); return@parse null })
        val reserve = positiveLong(json, "receivedFeeReserve")
        val total = positiveLong(json, "totalReceivedAssetCommitment")
        val takeExpiry = positiveLong(json, "takeExpiresAtUnix")
        val approvalExpiry = positiveLong(json, "approvalExpiresAtUnix")
        if (offer == null || reserve == null || total == null || takeExpiry == null || approvalExpiry == null ||
            offer.receivedAmount > Long.MAX_VALUE - reserve || offer.receivedAmount + reserve != total
        ) {
            token.close()
            return@parse null
        }
        NativeDirectOfferTakePreparation.Approval(
            NativeDirectOfferTakeApproval(token, offer, reserve, total, takeExpiry, approvalExpiry)
        )
    }

    fun directOfferTakeSummary(bundle: ByteArray): NativeDirectOfferTakeSummary? = parse(bundle) {
        parseDirectOfferTakeSummary(it)
    }

    private fun parseDirectOfferTakeSummary(json: JSONObject): NativeDirectOfferTakeSummary? {
        if (!hasExactKeys(json, setOf(
            "offerId", "sessionId", "offeredAsset", "offeredAmount", "receivedAsset",
            "receivedAmount", "receivedFeeReserve", "createdAtUnix", "expiresAtUnix",
        ))) return null
        val offeredAsset = json.optString("offeredAsset", "").takeIf { it in SHAKESCAPE_ASSETS }
            ?: return null
        val receivedAsset = json.optString("receivedAsset", "").takeIf {
            it in SHAKESCAPE_ASSETS && it != offeredAsset
        } ?: return null
        val created = positiveLong(json, "createdAtUnix") ?: return null
        val expires = positiveLong(json, "expiresAtUnix")?.takeIf { it > created } ?: return null
        return NativeDirectOfferTakeSummary(
            hexHash(json.optString("offerId", "")) ?: return null,
            hexHash(json.optString("sessionId", "")) ?: return null,
            offeredAsset,
            positiveLong(json, "offeredAmount") ?: return null,
            receivedAsset,
            positiveLong(json, "receivedAmount") ?: return null,
            positiveLong(json, "receivedFeeReserve") ?: return null,
            created,
            expires,
        )
    }

    fun shakescapeExecutions(bundle: ByteArray): NativeShakescapeExecutionStatus? = parse(bundle) { json ->
        if (!hasExactKeys(json, setOf(
            "executions", "pendingAcceptances", "bitcoinBroadcastRecovery",
        ))) return@parse null
        val array = json.optJSONArray("executions") ?: return@parse null
        if (array.length() > 1_024) return@parse null
        val executions = buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(parseShakescapeExecution(array.optJSONObject(index) ?: return@parse null) ?: return@parse null)
            }
        }
        val pendingArray = json.optJSONArray("pendingAcceptances") ?: return@parse null
        if (pendingArray.length() > 1_024) return@parse null
        val pending = buildList(pendingArray.length()) {
            for (index in 0 until pendingArray.length()) {
                val take = parseDirectOfferTakeSummary(
                    pendingArray.optJSONObject(index) ?: return@parse null,
                ) ?: return@parse null
                add(take)
            }
        }
        val recovery = if (json.isNull("bitcoinBroadcastRecovery")) null else {
            parseBroadcastRecovery(
                json.optJSONObject("bitcoinBroadcastRecovery") ?: return@parse null
            ) ?: return@parse null
        }
        NativeShakescapeExecutionStatus(executions, pending, recovery)
    }

    private fun parseBroadcastRecovery(json: JSONObject): NativeBitcoinBroadcastRecovery? {
        if (!hasExactKeys(json, setOf(
            "totalApproved", "unobservedPrepared", "unobservedSubmissionStarted",
            "unobservedSubmitted", "observed", "highestAttemptCount", "lastChangedAtUnix",
        ))) return null
        val total = nonnegativeLong(json, "totalApproved")?.takeIf { it <= 4_096 } ?: return null
        val prepared = nonnegativeLong(json, "unobservedPrepared") ?: return null
        val started = nonnegativeLong(json, "unobservedSubmissionStarted") ?: return null
        val submitted = nonnegativeLong(json, "unobservedSubmitted") ?: return null
        val observed = nonnegativeLong(json, "observed") ?: return null
        if (listOf(prepared, started, submitted, observed).any { it > 4_096 } ||
            prepared + started + submitted + observed != total
        ) return null
        val attempts = nonnegativeLong(json, "highestAttemptCount")
            ?.takeIf { it <= 16 }?.toInt() ?: return null
        val changed = if (json.isNull("lastChangedAtUnix")) null else
            positiveLong(json, "lastChangedAtUnix") ?: return null
        if ((total == 0L && (changed != null || attempts != 0)) ||
            (total > 0L && changed == null)
        ) return null
        return NativeBitcoinBroadcastRecovery(
            total, prepared, started, submitted, observed, attempts, changed,
        )
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

    private fun parseDirectOffer(json: JSONObject): NativeDirectOfferSummary? {
        if (!hasExactKeys(json, setOf(
            "offerId", "sessionId", "makerSellsHns", "offeredAsset", "offeredAmount",
            "receivedAsset", "receivedAmount", "btcAmountSats", "hnsAmountDollarydoos",
            "offeredFeeReserve", "local", "createdAtUnix", "expiresAtUnix",
        ))) return null
        val offeredAsset = json.optString("offeredAsset", "").takeIf { it in SHAKESCAPE_ASSETS }
            ?: return null
        val receivedAsset = json.optString("receivedAsset", "").takeIf {
            it in SHAKESCAPE_ASSETS && it != offeredAsset
        } ?: return null
        val makerSellsHns = json.opt("makerSellsHns") as? Boolean ?: return null
        if (makerSellsHns != (offeredAsset == "hns")) return null
        val offered = positiveLong(json, "offeredAmount") ?: return null
        val received = positiveLong(json, "receivedAmount") ?: return null
        val btc = positiveLong(json, "btcAmountSats") ?: return null
        val hns = positiveLong(json, "hnsAmountDollarydoos") ?: return null
        if ((offeredAsset == "btc" && (btc != offered || hns != received)) ||
            (offeredAsset == "hns" && (hns != offered || btc != received))
        ) return null
        val reserve = if (json.isNull("offeredFeeReserve")) null else
            positiveLong(json, "offeredFeeReserve") ?: return null
        val created = positiveLong(json, "createdAtUnix") ?: return null
        val expires = positiveLong(json, "expiresAtUnix")?.takeIf { it > created } ?: return null
        return NativeDirectOfferSummary(
            hexHash(json.optString("offerId", "")) ?: return null,
            hexHash(json.optString("sessionId", "")) ?: return null,
            makerSellsHns, offeredAsset, offered, receivedAsset, received, btc, hns,
            reserve, json.opt("local") as? Boolean ?: return null, created, expires,
        )
    }

    private fun parseShakescapeExecution(json: JSONObject): NativeShakescapeExecutionSummary? {
        if (!hasExactKeys(json, setOf(
            "sessionId", "revision", "state", "firstChain", "secondChain",
            "offeredAsset", "offeredAmount", "receivedAsset", "receivedAmount",
            "firstRefundAtUnix", "secondRefundAtUnix", "firstFundingConfirmed",
            "secondFundingConfirmed", "firstRedemptionConfirmed", "secondRedemptionConfirmed",
            "refundConfirmed", "lastVerifiedAtUnix", "failureReason",
        ))) return null
        val state = json.optString("state", "").takeIf { it in SHAKESCAPE_EXECUTION_STATES }
            ?: return null
        val firstChain = json.optString("firstChain", "").takeIf { it in SHAKESCAPE_CHAINS }
            ?: return null
        val secondChain = json.optString("secondChain", "").takeIf { it in SHAKESCAPE_CHAINS }
            ?: return null
        val offeredAsset = json.optString("offeredAsset", "").takeIf { it in SHAKESCAPE_ASSETS }
            ?: return null
        val receivedAsset = json.optString("receivedAsset", "").takeIf { it in SHAKESCAPE_ASSETS }
            ?: return null
        val firstRefund = positiveLong(json, "firstRefundAtUnix") ?: return null
        val secondRefund = positiveLong(json, "secondRefundAtUnix") ?: return null
        val failure = if (json.isNull("failureReason")) null else
            json.optString("failureReason", "").takeIf { it.isNotEmpty() && it.length <= 256 }
                ?: return null
        if (firstChain == secondChain || offeredAsset == receivedAsset || firstRefund <= secondRefund) {
            return null
        }
        return NativeShakescapeExecutionSummary(
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
                "recentActivity", "recentActivityTotal",
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
        val recentActivityJson = json.optJSONArray("recentActivity") ?: return null
        if (recentActivityJson.length() > MAX_RECENT_ACTIVITY) return null
        val recentActivity = ArrayList<NativeBitcoinActivity>(recentActivityJson.length())
        for (index in 0 until recentActivityJson.length()) {
            val item = parseActivity(recentActivityJson.optJSONObject(index) ?: return null)
                ?: return null
            if (recentActivity.any { it.txid == item.txid }) return null
            recentActivity.add(item)
        }
        if (recentActivity.zipWithNext().any { (left, right) ->
                left.lastChangedAtUnix < right.lastChangedAtUnix
            }) return null
        val recentActivityTotal = json.optInt("recentActivityTotal", -1)
            .takeIf { it in recentActivity.size..MAX_RETAINED_ACTIVITY } ?: return null
        if (recentActivity.size != minOf(recentActivityTotal, MAX_RECENT_ACTIVITY)) return null
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
            recentActivity,
            recentActivityTotal,
        )
    }

    private fun parseActivity(json: JSONObject): NativeBitcoinActivity? {
        if (!hasExactKeys(json, setOf(
                "txid", "direction", "amountSats", "feeSats", "status", "blockHeight",
                "confirmationCount", "lastChangedAtUnix",
            ))) return null
        val direction = json.optString("direction", "")
            .takeIf { it in setOf("incoming", "outgoing", "selfTransfer") } ?: return null
        val amount = nonnegativeLong(json, "amountSats") ?: return null
        if ((direction == "selfTransfer") != (amount == 0L)) return null
        val fee = if (json.isNull("feeSats")) null else {
            nonnegativeLong(json, "feeSats") ?: return null
        }
        if (direction == "incoming" && fee != null) return null
        val status = json.optString("status", "").takeIf {
            it in setOf(
                "notObserved", "prepared", "submissionStarted", "submitted",
                "unconfirmed", "confirmed",
            )
        } ?: return null
        val blockHeight = if (json.isNull("blockHeight")) null else {
            nonnegativeLong(json, "blockHeight") ?: return null
        }
        val confirmationCount = if (json.isNull("confirmationCount")) null else {
            positiveLong(json, "confirmationCount") ?: return null
        }
        if (status == "confirmed") {
            if (blockHeight == null || confirmationCount == null) return null
        } else if (blockHeight != null || confirmationCount != null) {
            return null
        }
        return NativeBitcoinActivity(
            txid = hexHash(json.optString("txid", "")) ?: return null,
            direction = direction,
            amountSats = amount,
            feeSats = fee,
            status = status,
            blockHeight = blockHeight,
            confirmationCount = confirmationCount,
            lastChangedAtUnix = nonnegativeLong(json, "lastChangedAtUnix")
                ?.takeIf { it <= MAX_DISPLAY_UNIX } ?: return null,
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

    private val SHAKESCAPE_CHAINS = setOf("bitcoin", "handshake")
    private val SHAKESCAPE_ASSETS = setOf("btc", "hns")
    private val SHAKESCAPE_EXECUTION_STATES = setOf(
        "offer_published", "offer_take_received", "offer_reserved", "terms_frozen",
        "refunds_prepared", "first_funding_pending", "first_funded", "second_funding_pending",
        "both_funded", "first_redeemed", "secret_observed", "second_redeemed", "completed",
        "refund_eligible", "refund_broadcast", "refunded", "failed",
    )
}
