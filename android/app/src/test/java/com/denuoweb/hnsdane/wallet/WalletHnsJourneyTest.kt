package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletHnsJourneyTest {
    @Test
    fun restoredWalletMustReopenDurablyThenCatchUpBeforeReviewingSend() {
        val journey = WalletHnsJourney()

        // Restore initially creates a non-durable controller. It must be
        // retired/reopened after key persistence before direct value authority
        // is admitted.
        journey.controllerPublished(reopenedDurable = false)
        assertFalse(journey.directControllerInstalled())
        assertFalse(journey.mayReviewHnsSend())

        journey.controllerRetired()
        journey.controllerPublished(reopenedDurable = true)
        assertTrue(journey.directControllerInstalled())
        assertTrue(journey.directControllerIsInstalled())

        journey.walletUnlocked()
        journey.catchupObserved()
        assertFalse(journey.mayReviewHnsSend())

        journey.verifiedSnapshotObserved()
        assertTrue(journey.mayReviewHnsSend())
    }

    @Test
    fun lockOrProjectionResetRevokesSendReviewUntilANewVerifiedSnapshot() {
        val journey = WalletHnsJourney()
        journey.controllerPublished(reopenedDurable = true)
        assertTrue(journey.directControllerInstalled())
        journey.walletUnlocked()
        journey.verifiedSnapshotObserved()
        assertTrue(journey.mayReviewHnsSend())

        journey.clearVerifiedSnapshot()
        assertFalse(journey.mayReviewHnsSend())

        journey.verifiedSnapshotObserved()
        journey.walletLocked()
        assertFalse(journey.mayReviewHnsSend())
    }

    @Test
    fun repeatedUnlockedConfirmationPreservesVerifiedSnapshot() {
        val journey = WalletHnsJourney()
        journey.controllerPublished(reopenedDurable = true)
        journey.walletUnlocked()
        journey.verifiedSnapshotObserved()

        // Dashboard status polling can positively confirm the same state
        // after synchronization. It must not make the snapshot unverified.
        journey.walletUnlocked()

        assertTrue(journey.isConfirmedUnlocked())
        assertTrue(journey.mayReviewHnsSend())
    }
}
