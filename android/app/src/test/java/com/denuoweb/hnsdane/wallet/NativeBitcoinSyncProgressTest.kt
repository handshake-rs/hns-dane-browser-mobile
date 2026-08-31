package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeBitcoinSyncProgressTest {
    @Test
    fun parses_the_validated_birthday_height_in_a_snapshot() {
        val snapshot = NativeBitcoinWalletBundle.snapshot(bundle(
            """{"network":"mainnet","receiveAddress":"bc1qexample","confirmedSats":10000,"trustedPendingSats":0,"untrustedPendingSats":0,"immatureSats":0,"totalSats":10000,"birthdayHeight":855000,"birthdayState":"validated","synchronizedHeight":855123,"connectedPeerCount":3,"requiredPeerCount":3}""",
        ))
        requireNotNull(snapshot)
        assertEquals(855000L, snapshot.birthdayHeight)
        assertEquals(855123L, snapshot.synchronizedHeight)
    }

    @Test
    fun rejects_a_snapshot_that_omits_the_birthday_height() {
        assertNull(NativeBitcoinWalletBundle.snapshot(bundle(
            """{"network":"mainnet","receiveAddress":"bc1qexample","confirmedSats":10000,"trustedPendingSats":0,"untrustedPendingSats":0,"immatureSats":0,"totalSats":10000,"birthdayState":"validated","synchronizedHeight":855123,"connectedPeerCount":3,"requiredPeerCount":3}""",
        )))
    }

    @Test
    fun rejects_an_unknown_birthday_lifecycle_state() {
        assertNull(NativeBitcoinWalletBundle.snapshot(bundle(
            """{"network":"mainnet","receiveAddress":"bc1qexample","confirmedSats":0,"trustedPendingSats":0,"untrustedPendingSats":0,"immatureSats":0,"totalSats":0,"birthdayHeight":0,"birthdayState":"peerGuessed","synchronizedHeight":0,"connectedPeerCount":0,"requiredPeerCount":3}""",
        )))
    }

    @Test
    fun parses_the_closed_bounded_progress_projection() {
        val progress = NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":4,"peerTimeouts":1,"incompatiblePeers":2,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":7200}""",
        ))
        requireNotNull(progress)
        assertEquals(2, progress.successfulHandshakes)
        assertEquals(3, progress.requiredPeerCount)
        assertEquals(4, progress.connectionFailures)
        assertEquals(1, progress.peerTimeouts)
        assertEquals(2, progress.incompatiblePeers)
        assertEquals(910000L, progress.chainHeight)
        assertEquals(7200L, progress.completionBasisPoints)
    }

    @Test
    fun rejects_impossible_or_extended_progress() {
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":0,"peerTimeouts":0,"incompatiblePeers":0,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":10001}""",
        )))
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":0,"peerTimeouts":0,"incompatiblePeers":0,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":20,"peer":"untrusted"}""",
        )))
    }

    @Test
    fun parses_only_bounded_actionable_bitcoin_send_preparation_results() {
        assertEquals(1_000L, NativeWalletBridge.MINIMUM_BITCOIN_MAXIMUM_FEE_SATS)

        val rejected = NativeBitcoinWalletBundle.sendPreparation(bundle(
            """{"outcome":"rejected","reason":"fee_cap_below_minimum"}""",
        ))
        requireNotNull(rejected)
        assertNull(rejected.approval)
        assertEquals(NativeBitcoinSendPreparationFailure.FeeCapBelowMinimum, rejected.failure)

        val approved = NativeBitcoinWalletBundle.sendPreparation(bundle(
            """{"outcome":"approved","approval":{"actionToken":"${"ab".repeat(32)}","destination":"bc1qexample","amountSats":500,"feeSats":200,"maximumFeeSats":1000,"expiresAtUnix":2000}}""",
        ))
        requireNotNull(approved)
        assertEquals(500L, approved.approval?.amountSats)
        assertNull(approved.failure)
        approved.approval?.close()

        assertNull(NativeBitcoinWalletBundle.sendPreparation(bundle(
            """{"outcome":"rejected","reason":"wallet_dump"}""",
        )))
        assertNull(NativeBitcoinWalletBundle.sendPreparation(bundle(
            """{"outcome":"rejected","reason":"retry","detail":"untrusted"}""",
        )))
    }

    @Test
    fun parses_exact_btc_for_hns_approval_and_active_offer() {
        val approval = NativeBitcoinWalletBundle.btcForHnsApproval(bundle(
            """{"actionToken":"${"ab".repeat(32)}","btcAmountSats":9000,"hnsAmountDollarydoos":2000000,"bitcoinFeeReserveSats":1000,"totalBitcoinCommitmentSats":10000,"offerExpiresAtUnix":2000,"approvalExpiresAtUnix":1100,"connectedPeerRequiredForAnnouncement":true}""",
        ))
        requireNotNull(approval)
        assertEquals(9_000L, approval.btcAmountSats)
        assertEquals(10_000L, approval.totalBitcoinCommitmentSats)
        approval.close()

        val offerId = "12".repeat(32)
        val sessionId = "34".repeat(32)
        val offers = NativeBitcoinWalletBundle.btcForHnsOffers(bundle(
            """{"offers":[{"offerId":"$offerId","sessionId":"$sessionId","btcAmountSats":9000,"hnsAmountDollarydoos":2000000,"bitcoinFeeReserveSats":1000,"createdAtUnix":1000,"expiresAtUnix":2000}]}""",
        ))
        requireNotNull(offers)
        assertEquals(1, offers.size)
        assertEquals(offerId, offers.single().offerId)
    }

    @Test
    fun rejects_offer_approval_that_hides_an_incoherent_commitment() {
        assertNull(NativeBitcoinWalletBundle.btcForHnsApproval(bundle(
            """{"actionToken":"${"ab".repeat(32)}","btcAmountSats":9000,"hnsAmountDollarydoos":2000000,"bitcoinFeeReserveSats":1000,"totalBitcoinCommitmentSats":9999,"offerExpiresAtUnix":2000,"approvalExpiresAtUnix":1100,"connectedPeerRequiredForAnnouncement":true}""",
        )))
    }

    @Test
    fun parses_only_session_bound_fee_capped_htlc_funding_approvals() {
        val sessionId = "11".repeat(32)
        val txid = "22".repeat(32)
        val approval = NativeBitcoinWalletBundle.htlcFundingApproval(bundle(
            """{"actionToken":"${"33".repeat(32)}","sessionId":"$sessionId","txid":"$txid","amountSats":10000,"feeSats":400,"maximumFeeSats":500,"refundAtUnix":2000,"expiresAtUnix":1500}""",
        ))
        requireNotNull(approval)
        assertEquals(sessionId, approval.sessionId)
        assertEquals(txid, approval.txid)
        assertEquals(10_000L, approval.amountSats)
        approval.close()

        assertNull(NativeBitcoinWalletBundle.htlcFundingApproval(bundle(
            """{"actionToken":"${"33".repeat(32)}","sessionId":"$sessionId","txid":"$txid","amountSats":10000,"feeSats":501,"maximumFeeSats":500,"refundAtUnix":2000,"expiresAtUnix":1500}""",
        )))
        assertNull(NativeBitcoinWalletBundle.htlcFundingApproval(bundle(
            """{"actionToken":"${"33".repeat(32)}","sessionId":"$sessionId","txid":"$txid","amountSats":10000,"feeSats":400,"maximumFeeSats":500,"refundAtUnix":2000,"expiresAtUnix":1500,"unexpected":true}""",
        )))
    }

    @Test
    fun parses_only_session_bound_htlc_funding_receipts() {
        val sessionId = "44".repeat(32)
        val txid = "55".repeat(32)
        val receipt = NativeBitcoinWalletBundle.htlcFundingReceipt(bundle(
            """{"sessionId":"$sessionId","txid":"$txid","attemptCount":2,"submittedAtUnix":1700000000}""",
        ))
        requireNotNull(receipt)
        assertEquals(sessionId, receipt.sessionId)
        assertEquals(txid, receipt.txid)
        assertEquals(2, receipt.attemptCount)

        assertNull(NativeBitcoinWalletBundle.htlcFundingReceipt(bundle(
            """{"sessionId":"${"0".repeat(64)}","txid":"$txid","attemptCount":2,"submittedAtUnix":1700000000}""",
        )))
    }

    @Test
    fun parses_only_session_bound_fee_capped_hns_htlc_funding() {
        val sessionId = "66".repeat(32)
        val transactionId = "77".repeat(32)
        val approval = NativeBitcoinWalletBundle.hnsHtlcFundingApproval(bundle(
            """{"actionToken":"${"88".repeat(32)}","sessionId":"$sessionId","transactionId":"$transactionId","amountDollarydoos":2000000,"feeDollarydoos":4000,"maximumFeeDollarydoos":5000,"refundAtUnix":2000,"expiresAtUnix":1500}""",
        ))
        requireNotNull(approval)
        assertEquals(sessionId, approval.sessionId)
        assertEquals(transactionId, approval.transactionId)
        assertEquals(2_000_000L, approval.amountDollarydoos)
        approval.close()

        assertNull(NativeBitcoinWalletBundle.hnsHtlcFundingApproval(bundle(
            """{"actionToken":"${"88".repeat(32)}","sessionId":"$sessionId","transactionId":"$transactionId","amountDollarydoos":2000000,"feeDollarydoos":5001,"maximumFeeDollarydoos":5000,"refundAtUnix":2000,"expiresAtUnix":1500}""",
        )))
        assertNull(NativeBitcoinWalletBundle.hnsHtlcFundingApproval(bundle(
            """{"actionToken":"${"88".repeat(32)}","sessionId":"$sessionId","transactionId":"$transactionId","amountDollarydoos":2000000,"feeDollarydoos":4000,"maximumFeeDollarydoos":5000,"refundAtUnix":2000,"expiresAtUnix":1500,"unexpected":true}""",
        )))

        val receipt = NativeBitcoinWalletBundle.hnsHtlcFundingReceipt(bundle(
            """{"sessionId":"$sessionId","transactionId":"$transactionId","acceptedAtUnix":1700000000}""",
        ))
        requireNotNull(receipt)
        assertEquals(transactionId, receipt.transactionId)
        assertNull(NativeBitcoinWalletBundle.hnsHtlcFundingReceipt(bundle(
            """{"sessionId":"${"0".repeat(64)}","transactionId":"$transactionId","acceptedAtUnix":1700000000}""",
        )))
    }

    @Test
    fun parses_a_bounded_durable_swap_execution_projection() {
        val sessionId = "66".repeat(32)
        val status = NativeBitcoinWalletBundle.shakescapeExecutions(bundle(
            """{"executions":[{"sessionId":"$sessionId","revision":7,"state":"first_funded","firstChain":"bitcoin","secondChain":"handshake","offeredAsset":"btc","offeredAmount":10000,"receivedAsset":"hns","receivedAmount":2000000,"firstRefundAtUnix":2000,"secondRefundAtUnix":1500,"firstFundingConfirmed":true,"secondFundingConfirmed":false,"firstRedemptionConfirmed":false,"secondRedemptionConfirmed":false,"refundConfirmed":false,"lastVerifiedAtUnix":1200,"failureReason":null}],"bitcoinBroadcastRecovery":{"totalApproved":3,"unobservedPrepared":1,"unobservedSubmissionStarted":1,"unobservedSubmitted":0,"observed":1,"highestAttemptCount":2,"lastChangedAtUnix":1250}}""",
        ))
        requireNotNull(status)
        assertEquals(1, status.executions.size)
        assertEquals("first_funded", status.executions.single().state)
        assertEquals(true, status.executions.single().firstFundingConfirmed)
        assertEquals(1L, status.bitcoinBroadcastRecovery?.unobservedPrepared)
        assertEquals(2, status.bitcoinBroadcastRecovery?.highestAttemptCount)

        assertNull(NativeBitcoinWalletBundle.shakescapeExecutions(bundle(
            """{"executions":[{"sessionId":"$sessionId","revision":7,"state":"peer_says_done","firstChain":"bitcoin","secondChain":"handshake","offeredAsset":"btc","offeredAmount":10000,"receivedAsset":"hns","receivedAmount":2000000,"firstRefundAtUnix":2000,"secondRefundAtUnix":1500,"firstFundingConfirmed":true,"secondFundingConfirmed":false,"firstRedemptionConfirmed":false,"secondRedemptionConfirmed":false,"refundConfirmed":false,"lastVerifiedAtUnix":1200,"failureReason":null}],"bitcoinBroadcastRecovery":null}""",
        )))
        assertNull(NativeBitcoinWalletBundle.shakescapeExecutions(bundle(
            """{"executions":[],"bitcoinBroadcastRecovery":{"totalApproved":2,"unobservedPrepared":1,"unobservedSubmissionStarted":0,"unobservedSubmitted":0,"observed":0,"highestAttemptCount":0,"lastChangedAtUnix":1250}}""",
        )))
        assertNull(NativeBitcoinWalletBundle.shakescapeExecutions(bundle(
            """{"executions":[],"bitcoinBroadcastRecovery":null,"peer":"untrusted"}""",
        )))
    }

    @Test
    fun parses_only_exact_fee_capped_swap_settlement_outputs() {
        val session = "12".repeat(32)
        val transaction = "34".repeat(32)
        val token = "56".repeat(32)
        val bitcoin = NativeBitcoinWalletBundle.swapSettlementApproval(bundle(
            """{"actionToken":"$token","sessionId":"$session","action":"redeem","txid":"$transaction","inputAmountSats":10000,"outputAmountSats":9600,"feeSats":400,"maximumFeeSats":500,"expiresAtUnix":1700000000}""",
        ), bitcoin = true)
        requireNotNull(bitcoin)
        assertEquals("redeem", bitcoin.action)
        assertEquals(9_600L, bitcoin.outputAmount)
        bitcoin.close()

        val hns = NativeBitcoinWalletBundle.swapSettlementApproval(bundle(
            """{"actionToken":"$token","sessionId":"$session","action":"refund","transactionId":"$transaction","inputAmountDollarydoos":2000000,"outputAmountDollarydoos":1996000,"feeDollarydoos":4000,"maximumFeeDollarydoos":5000,"expiresAtUnix":1700000000}""",
        ), bitcoin = false)
        requireNotNull(hns)
        assertEquals("refund", hns.action)
        hns.close()

        assertNull(NativeBitcoinWalletBundle.swapSettlementApproval(bundle(
            """{"actionToken":"$token","sessionId":"$session","action":"redeem","txid":"$transaction","inputAmountSats":10000,"outputAmountSats":9601,"feeSats":400,"maximumFeeSats":500,"expiresAtUnix":1700000000}""",
        ), bitcoin = true))
        assertNull(NativeBitcoinWalletBundle.swapSettlementApproval(bundle(
            """{"actionToken":"$token","sessionId":"$session","action":"steal","txid":"$transaction","inputAmountSats":10000,"outputAmountSats":9600,"feeSats":400,"maximumFeeSats":500,"expiresAtUnix":1700000000}""",
        ), bitcoin = true))

        val receipt = NativeBitcoinWalletBundle.swapSettlementReceipt(bundle(
            """{"sessionId":"$session","action":"redeem","txid":"$transaction","attemptCount":2,"submittedAtUnix":1700000001}""",
        ), bitcoin = true)
        requireNotNull(receipt)
        assertEquals(2, receipt.attemptCount)
        assertNull(NativeBitcoinWalletBundle.swapSettlementReceipt(bundle(
            """{"sessionId":"$session","action":"redeem","txid":"$transaction","attemptCount":0,"submittedAtUnix":1700000001}""",
        ), bitcoin = true))
    }

    private fun bundle(json: String): ByteArray {
        val encoded = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + encoded.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte()))
            put(1.toByte())
            put(0.toByte())
            putShort(0.toShort())
            putInt(encoded.size)
            put(encoded)
        }.array()
    }
}
