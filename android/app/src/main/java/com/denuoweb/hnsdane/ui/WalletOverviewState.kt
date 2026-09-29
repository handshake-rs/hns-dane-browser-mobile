package com.denuoweb.hnsdane.ui

/** Presentation only. Native authority and approval remain the execution gates. */
internal enum class BitcoinOverviewStage {
    Unavailable, RecoveryStart, SavingRecoveryStart, FirstSync, Sync, Syncing, Stopping,
}

internal fun bitcoinOverviewStage(
    birthdayState: String?,
    syncing: Boolean,
    stopping: Boolean,
    savingBirthday: Boolean,
): BitcoinOverviewStage = when {
    syncing -> if (stopping) BitcoinOverviewStage.Stopping else BitcoinOverviewStage.Syncing
    savingBirthday -> BitcoinOverviewStage.SavingRecoveryStart
    birthdayState == "recoveryUnknown" -> BitcoinOverviewStage.RecoveryStart
    birthdayState == "awaitingCreationTip" -> BitcoinOverviewStage.FirstSync
    birthdayState in setOf("recoveryPendingValidation", "validated") -> BitcoinOverviewStage.Sync
    else -> BitcoinOverviewStage.Unavailable
}
