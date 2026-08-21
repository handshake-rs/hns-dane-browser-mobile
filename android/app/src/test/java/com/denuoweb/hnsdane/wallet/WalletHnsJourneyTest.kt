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
}
