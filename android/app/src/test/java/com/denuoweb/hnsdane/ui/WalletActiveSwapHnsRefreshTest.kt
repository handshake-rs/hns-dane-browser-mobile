package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionStatus
import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary
import com.denuoweb.hnsdane.wallet.NativeBitcoinBroadcastRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletActiveSwapHnsRefreshTest {
    @Test
    fun `funding alerts require a pending local action in either swap direction`() {
        fun execution(role: String, state: String, fundingState: String? = null) =
            NativeShakescapeExecutionSummary(
                sessionId = "session", revision = 1, state = state,
                firstChain = "handshake", secondChain = "bitcoin",
                offeredAsset = "hns", offeredAmount = 100_546,
                receivedAsset = "btc", receivedAmount = 1_330,
                localRole = role, fundingDeadlineUnix = 200,
                firstFundingCutoffUnix = 150, firstRefundAtUnix = 300,
                secondRefundAtUnix = 250, localFundingState = fundingState,
                firstFundingConfirmed = false, secondFundingConfirmed = false,
                firstRedemptionConfirmed = false, secondRedemptionConfirmed = false,
                refundConfirmed = false, lastVerifiedAtUnix = 1, failureReason = null,
            )

        assertEquals(true, swapFundingActionRequired(execution("maker", "first_funding_pending"), 100))
        assertEquals(false, swapFundingActionRequired(execution("maker", "first_funding_pending"), 151))
        assertEquals(false, swapFundingActionRequired(execution("maker", "first_funding_pending", "broadcast"), 100))
        assertEquals(true, swapFundingActionRequired(execution("taker", "first_funded"), 100))
        assertEquals(true, swapFundingActionRequired(execution("taker", "first_funded").copy(
            firstChain = "bitcoin", secondChain = "handshake",
            offeredAsset = "btc", receivedAsset = "hns",
        ), 100))
        assertEquals(true, swapFundingActionRequired(execution("taker", "second_funding_pending"), 100))
        assertEquals(false, swapFundingActionRequired(execution("taker", "second_funding_pending", "confirmed"), 100))
        assertEquals(false, swapFundingActionRequired(execution("taker", "second_funding_pending"), 200))
        assertEquals(false, swapFundingActionRequired(execution("maker", "first_funded"), 100))
    }

    @Test
    fun `approved Bitcoin broadcast requires sync until chain observation`() {
        fun status(prepared: Long, submitted: Long, observed: Long) =
            NativeShakescapeExecutionStatus(
                emptyList(), emptyList(), emptyList(),
                NativeBitcoinBroadcastRecovery(
                    totalApproved = 1, unobservedPrepared = prepared,
                    unobservedSubmissionStarted = 0, unobservedSubmitted = submitted,
                    observed = observed, highestAttemptCount = 1, lastChangedAtUnix = 1,
                ),
            )
        assertEquals(true, walletBitcoinBroadcastRecoveryPending(status(1, 0, 0)))
        assertEquals(true, walletBitcoinBroadcastRecoveryPending(status(0, 1, 0)))
        assertEquals(false, walletBitcoinBroadcastRecoveryPending(status(0, 0, 1)))
    }

    @Test
    fun `peer replay revision does not force another chain scan`() {
        val execution = NativeShakescapeExecutionSummary(
            sessionId = "session", revision = 1, state = "first_funding_pending",
            firstChain = "handshake", secondChain = "bitcoin",
            offeredAsset = "hns", offeredAmount = 100_546,
            receivedAsset = "btc", receivedAmount = 1_330,
            localRole = "maker", fundingDeadlineUnix = 100,
            firstFundingCutoffUnix = 100, firstRefundAtUnix = 200,
            secondRefundAtUnix = 150, localFundingState = null,
            firstFundingConfirmed = false, secondFundingConfirmed = false,
            firstRedemptionConfirmed = false, secondRedemptionConfirmed = false,
            refundConfirmed = false, lastVerifiedAtUnix = 1, failureReason = null,
        )
        fun fingerprint(value: NativeShakescapeExecutionSummary) =
            walletActiveSwapSyncFingerprint(
                NativeShakescapeExecutionStatus(listOf(value), emptyList(), emptyList(), null),
            )

        assertEquals(fingerprint(execution), fingerprint(execution.copy(revision = 2,
            lastVerifiedAtUnix = 2)))
        assertNotEquals(fingerprint(execution), fingerprint(execution.copy(
            state = "first_funded", firstFundingConfirmed = true)))
    }

    @Test
    fun `initial execution projection inherits the just verified snapshot`() {
        assertEquals(
            42_000L,
            walletActiveSwapFingerprintBaseline(
                previousFingerprint = null,
                currentSnapshotObservedAtElapsedMillis = 42_000L,
            ),
        )
        assertNull(
            walletActiveSwapFingerprintBaseline(
                previousFingerprint = null,
                currentSnapshotObservedAtElapsedMillis = null,
            ),
        )
    }

    @Test
    fun `changed execution projection requests an immediate refresh`() {
        assertEquals(
            Long.MIN_VALUE,
            walletActiveSwapFingerprintBaseline(
                previousFingerprint = "previous",
                currentSnapshotObservedAtElapsedMillis = 42_000L,
            ),
        )
    }

    @Test
    fun `new header beyond wallet snapshot schedules one refresh`() {
        assertEquals(
            347_849L,
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_848,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = null,
            ),
        )
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_848,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = 347_849,
            ),
        )
    }

    @Test
    fun `current or unavailable header does not schedule`() {
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_849,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = null,
            ),
        )
        assertNull(
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = 347_849,
                observedHeaderHeight = null,
                attemptedHeaderHeight = null,
            ),
        )
    }

    @Test
    fun `missing wallet snapshot schedules the newest observed height once`() {
        assertEquals(
            347_849L,
            walletActiveSwapHnsRefreshHeight(
                snapshotHeight = null,
                observedHeaderHeight = 347_849,
                attemptedHeaderHeight = 347_848,
            ),
        )
    }
}
