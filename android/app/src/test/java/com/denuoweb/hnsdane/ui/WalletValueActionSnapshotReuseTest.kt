package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletValueActionSnapshotReuseTest {
    @Test
    fun exact_recent_authority_can_skip_a_redundant_network_round() {
        assertTrue(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = true,
                snapshotObservedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_000L + WALLET_VALUE_ACTION_SNAPSHOT_REUSE_MILLIS,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = 345_888L,
            ),
        )
    }

    @Test
    fun stale_wrong_or_known_behind_snapshots_force_a_fresh_sync() {
        assertFalse(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = true,
                snapshotObservedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_001L + WALLET_VALUE_ACTION_SNAPSHOT_REUSE_MILLIS,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = 345_888L,
            ),
        )
        assertFalse(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = false,
                snapshotObservedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_001L,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = 345_888L,
            ),
        )
        assertFalse(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = true,
                snapshotObservedAtElapsedMillis = 10_000L,
                nowElapsedMillis = 10_001L,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = 345_889L,
            ),
        )
    }

    @Test
    fun absent_or_rolled_back_monotonic_observation_forces_a_fresh_sync() {
        assertFalse(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = true,
                snapshotObservedAtElapsedMillis = 0L,
                nowElapsedMillis = 10_000L,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = null,
            ),
        )
        assertFalse(
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = true,
                snapshotObservedAtElapsedMillis = 10_001L,
                nowElapsedMillis = 10_000L,
                snapshotHeight = 345_888L,
                latestObservedHeaderHeight = null,
            ),
        )
    }
}
