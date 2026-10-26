package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary
import com.denuoweb.hnsdane.wallet.NativeLocalRedemptionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapExecutionListTest {
    @Test
    fun oneVisibleLiveSwapGetsTheDirectCardButHistoryAndMultipleSwapsUseTheList() {
        val live = execution("live", state = "both_funded", firstFundingConfirmed = true)
        val history = execution("history", state = "completed",
            firstFundingConfirmed = true).copy(
                firstRedemptionConfirmed = true, secondRedemptionConfirmed = true)
        assertEquals(live, singleActiveSwapExecution(listOf(history, live), emptySet()))
        assertNull(singleActiveSwapExecution(listOf(history), emptySet()))
        assertNull(singleActiveSwapExecution(listOf(live,
            execution("other", state = "first_funded")), emptySet()))
        // A funded session remains visible even if the user hid its old row.
        assertEquals(live, singleActiveSwapExecution(listOf(live), setOf(live.sessionId)))
        val fundedFailure = execution("recovery", state = "failed",
            firstFundingConfirmed = true)
        assertEquals(fundedFailure, singleActiveSwapExecution(listOf(fundedFailure), emptySet()))
        assertNull(singleActiveSwapExecution(listOf(
            execution("unfunded-failure", state = "failed")), emptySet()))
    }

    @Test
    fun bitcoinScanFollowsTheSubmittedClaimAndOffersResumeOnlyForAttention() {
        val moto = execution("moto", state = "secret_observed").copy(
            localRole = "taker", firstChain = "bitcoin", secondChain = "handshake",
            localFundingState = "confirmed",
            localRedemption = NativeLocalRedemptionSummary("submitted", "a".repeat(64)),
        )
        assertTrue(swapBitcoinConfirmationPending(moto))
        // A prepared claim has not reached broadcast submission yet.
        assertFalse(swapBitcoinConfirmationPending(moto.copy(
            localRedemption = NativeLocalRedemptionSummary("queued", "a".repeat(64)))))
        assertTrue(swapBitcoinConfirmationPending(moto.copy(
            localRedemption = NativeLocalRedemptionSummary("seen", "a".repeat(64)))))
        assertFalse(swapBitcoinConfirmationPending(moto.copy(
            localRedemption = NativeLocalRedemptionSummary("confirmed", "a".repeat(64)))))
        assertFalse(swapBitcoinConfirmationPending(moto.copy(
            firstChain = "handshake", secondChain = "bitcoin")))

        val funding = execution("pixel", state = "first_funding_pending",
            localFundingState = "broadcast")
        assertTrue(swapBitcoinConfirmationPending(funding))
        assertTrue(swapBitcoinConfirmationPending(funding.copy(localFundingState = "seen")))
        assertFalse(swapBitcoinConfirmationPending(funding.copy(localFundingState = "confirmed")))
        assertFalse(swapBitcoinConfirmationPending(funding.copy(state = "completed")))

        assertFalse(swapScanNeedsAttention(syncing = true, failed = true, paused = true))
        assertFalse(swapScanNeedsAttention(syncing = false, failed = false, paused = false))
        assertTrue(swapScanNeedsAttention(syncing = false, failed = true, paused = false))
        assertTrue(swapScanNeedsAttention(syncing = false, failed = false, paused = true))
    }

    @Test
    fun interruptedPairedSwapKeepsTheCorrectParticipantActionAndRecoveryVisible() {
        val maker = execution("paired", state = "first_funding_pending",
            fundingDeadlineUnix = 200).copy(firstFundingCutoffUnix = 150)
        val taker = maker.copy(localRole = "taker", localFundingState = null)
        assertTrue(swapFundingActionRequired(maker, 100))
        assertFalse(swapFundingActionRequired(taker, 100))
        assertFalse(swapFundingActionRequired(maker, 151)) // maker's earlier cutoff

        val firstFundedMaker = maker.copy(state = "first_funded",
            localFundingState = "confirmed", firstFundingConfirmed = true)
        val firstFundedTaker = taker.copy(state = "first_funded",
            firstFundingConfirmed = true)
        assertFalse(swapFundingActionRequired(firstFundedMaker, 160))
        assertTrue(swapFundingActionRequired(firstFundedTaker, 160))

        val bothFundedMaker = firstFundedMaker.copy(state = "both_funded",
            secondFundingConfirmed = true)
        val bothFundedTaker = firstFundedTaker.copy(state = "both_funded",
            localFundingState = "confirmed", secondFundingConfirmed = true)
        assertTrue(swapRedemptionActionRequired(bothFundedMaker))
        assertFalse(swapRedemptionActionRequired(bothFundedTaker))
        assertFalse(swapRedemptionActionRequired(bothFundedMaker.copy(
            localRedemption = NativeLocalRedemptionSummary("queued", "a".repeat(64)))))

        val secretObservedTaker = bothFundedTaker.copy(state = "secret_observed",
            firstRedemptionConfirmed = true)
        assertTrue(swapRedemptionActionRequired(secretObservedTaker))
        val submittedClaim = secretObservedTaker.copy(
            localRedemption = NativeLocalRedemptionSummary("submitted", "b".repeat(64)))
        assertFalse(swapRedemptionActionRequired(submittedClaim))
        assertTrue(swapBitcoinConfirmationPending(submittedClaim))
        assertFalse(swapExecutionCanDismiss(submittedClaim))

        val reorgedMaker = firstFundedMaker.copy(state = "failed",
            firstFundingConfirmed = false, localFundingState = "reorged")
        assertFalse(swapFundingActionRequired(reorgedMaker, 160))
        assertFalse(swapExecutionCanDismiss(reorgedMaker))
        assertFalse(swapExecutionCanDismiss(firstFundedMaker.copy(state = "refund_eligible")))
        assertTrue(swapExecutionCanDismiss(firstFundedMaker.copy(state = "refunded",
            refundConfirmed = true)))
    }

    @Test
    fun scanStatesDistinguishAnActiveRoundFromOverdueAndStoppedRecovery() {
        val checking = swapScanState(true, false, false, true, 1_000, 900_000)
        val waiting = swapScanState(false, false, false, true, 1_000, 120_000)
        val overdue = swapScanState(false, false, false, true, 1_000, 700_001)
        val stopped = swapScanState(false, false, true, true, 1_000, 120_000)
        val unavailable = swapScanState(false, false, false, false, 1_000, 120_000)
        assertEquals(SwapScanState.CHECKING, checking)
        assertEquals(SwapScanState.WAITING, waiting)
        assertEquals(SwapScanState.OVERDUE, overdue)
        assertEquals(SwapScanState.STOPPED, stopped)
        assertEquals(SwapScanState.UNAVAILABLE, unavailable)
        assertFalse(swapScanNeedsAttention(checking))
        assertFalse(swapScanNeedsAttention(waiting))
        assertTrue(swapScanNeedsAttention(overdue))
        assertTrue(swapScanNeedsAttention(stopped))
        assertTrue(swapScanNeedsAttention(unavailable))
    }

    @Test
    fun submittedClaimDoesNotOfferRedeemAgainWhileSwapPhaseWaitsForScan() {
        // Moto's recorded BTC claim was submitted while the verified swap
        // phase still said secret_observed and offered "Redeem received BTC".
        val taker = execution("moto", state = "secret_observed")
            .copy(localRole = "taker", firstChain = "bitcoin", secondChain = "handshake")
        assertTrue(swapRedemptionActionRequired(taker))
        assertFalse(swapRedemptionActionRequired(taker.copy(state = "first_redeemed")))
        for (state in listOf("queued", "submitted", "seen", "confirmed", "needs_recovery")) {
            assertFalse(swapRedemptionActionRequired(taker.copy(
                localRedemption = NativeLocalRedemptionSummary(state, "a".repeat(64)),
            )))
            assertFalse(swapRedemptionActionRequired(taker.copy(
                state = "first_redeemed",
                localRedemption = NativeLocalRedemptionSummary(state, "a".repeat(64)),
            )))
        }

        val maker = execution("pixel", state = "both_funded")
        assertTrue(swapRedemptionActionRequired(maker))
        assertFalse(swapRedemptionActionRequired(maker.copy(
            localRedemption = NativeLocalRedemptionSummary("queued", "b".repeat(64)),
        )))
    }

    @Test
    fun dismissingAnUnfundedSessionNeverHidesLaterFundingOrRecovery() {
        val unfunded = execution("a", state = "first_funding_pending")
        assertTrue(swapExecutionCanDismiss(unfunded))
        assertEquals(emptyList<NativeShakescapeExecutionSummary>(),
            visibleSwapExecutions(listOf(unfunded), setOf("a")))

        for (changed in listOf(
            unfunded.copy(localFundingState = "broadcast"),
            unfunded.copy(firstFundingConfirmed = true),
            unfunded.copy(state = "refund_eligible"),
            unfunded.copy(state = "secret_observed"),
            unfunded.copy(state = "completed"),
        )) {
            assertFalse(swapExecutionCanDismiss(changed))
            assertEquals(listOf(changed), visibleSwapExecutions(listOf(changed), setOf("a")))
        }
    }

    @Test
    fun recoveredAndFundedFailuresCannotBeDismissed() {
        assertFalse(swapExecutionCanDismiss(execution("a", state = "secret_observed",
            fundingDeadlineUnix = 0)))
        assertFalse(swapExecutionCanDismiss(execution("b", state = "failed",
            firstFundingConfirmed = true)))
        assertFalse(swapExecutionCanDismiss(execution("c", state = "failed",
            localFundingState = "reorged")))
        assertFalse(swapExecutionCanDismiss(execution("expired", state = "first_funding_pending",
            fundingDeadlineUnix = 100), nowUnix = 200))
        assertTrue(swapExecutionCanDismiss(execution("d", state = "failed")))
        assertTrue(swapExecutionCanDismiss(execution("e", state = "refunded",
            refundConfirmed = true)))
    }

    @Test
    fun completedSwapHidesOnlyAfterBothRedemptionsAndReturnsIfRecoveryIsNeeded() {
        val completed = execution("settled", state = "completed", firstFundingConfirmed = true)
            .copy(firstRedemptionConfirmed = true, secondRedemptionConfirmed = true)
        assertTrue(swapExecutionCanDismiss(completed))
        assertTrue(visibleSwapExecutions(listOf(completed), setOf("settled")).isEmpty())

        for (changed in listOf(
            completed.copy(firstRedemptionConfirmed = false),
            completed.copy(secondRedemptionConfirmed = false),
            completed.copy(state = "first_redeemed"),
            completed.copy(state = "refund_eligible"),
        )) {
            assertFalse(swapExecutionCanDismiss(changed))
            assertEquals(listOf(changed), visibleSwapExecutions(listOf(changed), setOf("settled")))
        }
    }

    @Test
    fun recoveredSwapWithBothVerifiedSettlementsCanBeHidden() {
        val recovered = execution("recovered", state = "completed", fundingDeadlineUnix = 0,
            localFundingState = "submitted")
        assertTrue(swapExecutionCanDismiss(recovered))
        assertTrue(visibleSwapExecutions(listOf(recovered), setOf("recovered")).isEmpty())

        for (changed in listOf(
            recovered.copy(state = "secret_observed"),
            recovered.copy(state = "refund_eligible"),
            recovered.copy(localFundingState = null),
        )) {
            assertFalse(swapExecutionCanDismiss(changed))
            assertEquals(listOf(changed), visibleSwapExecutions(listOf(changed), setOf("recovered")))
        }
    }

    @Test
    fun pagesContainFourLatestExecutionsAndClampAfterListChanges() {
        val entries = (1L..10L).map { execution(it.toString(), lastVerifiedAtUnix = it) }
        val sorted = orderedSwapExecutions(entries)
        assertEquals(listOf("10", "9", "8", "7"),
            swapExecutionPage(sorted, 0).entries.map { it.sessionId })
        assertEquals(listOf("6", "5", "4", "3"),
            swapExecutionPage(sorted, 1).entries.map { it.sessionId })
        assertEquals(listOf("2", "1"),
            swapExecutionPage(sorted, 2).entries.map { it.sessionId })
        assertEquals(3, swapExecutionPage(sorted, 99).count)
        assertEquals(2, swapExecutionPage(sorted, 99).index)
        assertEquals(0, swapExecutionPage(emptyList(), 99).index)
        assertEquals("new", orderedSwapExecutions(listOf(
            execution("old", fundingDeadlineUnix = 100, lastVerifiedAtUnix = 1_000),
            execution("new", fundingDeadlineUnix = 200, lastVerifiedAtUnix = 100),
        )).first().sessionId)
    }

    private fun execution(
        id: String,
        state: String = "completed",
        fundingDeadlineUnix: Long = 9_999_999_999,
        localFundingState: String? = null,
        firstFundingConfirmed: Boolean = false,
        refundConfirmed: Boolean = false,
        lastVerifiedAtUnix: Long = 1,
    ) = NativeShakescapeExecutionSummary(
        sessionId = id,
        revision = 1,
        state = state,
        firstChain = "bitcoin",
        secondChain = "handshake",
        offeredAsset = "btc",
        offeredAmount = 1_330,
        receivedAsset = "hns",
        receivedAmount = 500_546,
        localRole = "maker",
        fundingDeadlineUnix = fundingDeadlineUnix,
        firstFundingCutoffUnix = 50,
        firstRefundAtUnix = 300,
        secondRefundAtUnix = 200,
        localFundingState = localFundingState,
        firstFundingConfirmed = firstFundingConfirmed,
        secondFundingConfirmed = false,
        firstRedemptionConfirmed = false,
        secondRedemptionConfirmed = false,
        refundConfirmed = refundConfirmed,
        lastVerifiedAtUnix = lastVerifiedAtUnix,
        failureReason = null,
    )
}
