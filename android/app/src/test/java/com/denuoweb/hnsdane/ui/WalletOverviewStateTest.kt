package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletOverviewStateTest {
    @Test
    fun restoredWalletRequiresRecoveryChoiceButNewWalletCanEstablishItsBirthday() {
        assertEquals(BitcoinOverviewStage.RecoveryStart, stage("recoveryUnknown"))
        assertEquals(BitcoinOverviewStage.FirstSync, stage("awaitingCreationTip"))
    }

    @Test
    fun savedRecoveryStartAllowsTheSyncThatValidatesIt() {
        assertEquals(BitcoinOverviewStage.Sync, stage("recoveryPendingValidation"))
        assertEquals(BitcoinOverviewStage.Sync, stage("validated"))
    }

    @Test
    fun runningFullHistoryScanExposesStopInsteadOfRepeatingSetup() {
        assertEquals(BitcoinOverviewStage.Syncing, stage("recoveryUnknown", syncing = true))
        assertEquals(BitcoinOverviewStage.Stopping, stage("recoveryUnknown", syncing = true, stopping = true))
        // A completed cancellation must return to setup unless native state changed.
        assertEquals(BitcoinOverviewStage.RecoveryStart, stage("recoveryUnknown", stopping = true))
    }

    @Test
    fun savingBirthdayDoesNotOfferACompetingScan() {
        assertEquals(BitcoinOverviewStage.SavingRecoveryStart, stage("recoveryPendingValidation", saving = true))
    }

    @Test
    fun missingOrFutureSnapshotStateDoesNotClaimReadiness() {
        assertEquals(BitcoinOverviewStage.Unavailable, stage(null))
        assertEquals(BitcoinOverviewStage.Unavailable, stage("future-state"))
        // The separate progress channel remains usable when the snapshot is absent.
        assertEquals(BitcoinOverviewStage.Syncing, stage(null, syncing = true))
    }

    private fun stage(state: String?, syncing: Boolean = false, stopping: Boolean = false, saving: Boolean = false) =
        bitcoinOverviewStage(state, syncing, stopping, saving)
}
