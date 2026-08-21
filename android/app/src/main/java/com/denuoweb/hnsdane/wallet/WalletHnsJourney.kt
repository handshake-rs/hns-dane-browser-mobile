package com.denuoweb.hnsdane.wallet

/**
 * Minimal lifecycle projection for the HNS wallet journey that leads to a
 * send review. It intentionally records no account, address, balance, or
 * transaction data; Activity-owned authority/handle checks remain the source
 * of truth for those values.
 *
 * Keeping this small state machine in production code makes the crucial
 * restore → durable reopen → unlock → catch-up → verified snapshot transition
 * unit-testable without a device wallet or network peer fixture.
 */
internal class WalletHnsJourney {
    private var durableController = false
    private var directControllerInstalled = false
    private var unlocked = false
    private var verifiedSnapshot = false

    fun controllerPublished(reopenedDurable: Boolean) {
        durableController = reopenedDurable
        directControllerInstalled = false
        unlocked = false
        verifiedSnapshot = false
    }

    fun controllerRetired() {
        durableController = false
        directControllerInstalled = false
        unlocked = false
        verifiedSnapshot = false
    }

    /** A direct value controller is admitted only after a durable reopen. */
    fun directControllerInstalled(): Boolean {
        if (!durableController) return false
        directControllerInstalled = true
        verifiedSnapshot = false
        return true
    }

    fun walletUnlocked() {
        unlocked = true
        verifiedSnapshot = false
    }

    fun walletLocked() {
        unlocked = false
        verifiedSnapshot = false
    }

    fun catchupObserved() {
        verifiedSnapshot = false
    }

    fun verifiedSnapshotObserved() {
        verifiedSnapshot = true
    }

    fun clearVerifiedSnapshot() {
        verifiedSnapshot = false
    }

    fun mayReviewHnsSend(): Boolean = unlocked && verifiedSnapshot

    fun directControllerIsInstalled(): Boolean = directControllerInstalled
}
