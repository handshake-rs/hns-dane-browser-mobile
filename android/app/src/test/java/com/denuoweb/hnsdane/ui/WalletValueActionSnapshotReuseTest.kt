package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletValueActionSnapshotReuseTest {
    @Test
    fun observational_status_contention_is_retried_without_retrying_an_action() {
        val ready = com.denuoweb.hnsdane.wallet.NativeWalletStatus(
            locked = false,
            activeWalletId = "wallet",
            hnsReadsEnabled = true,
            hnsValueEnabled = true,
            shakedexEnabled = true,
            mainnetSettlementEnabled = true,
        )
        val observations = listOf(null, null, ready)
        var reads = 0
        var waits = 0

        val observed = awaitWalletValueActionStatus(
            attempts = 5,
            readStatus = {
                observations.getOrNull(reads++)
            },
            waitBeforeRetry = { waits += 1 },
        )

        assertEquals(ready, observed)
        assertEquals(2, waits)
        assertEquals(3, reads)
    }

    @Test
    fun bounded_status_wait_stops_after_exact_attempt_count() {
        var reads = 0
        var waits = 0

        val observed = awaitWalletValueActionStatus(
            attempts = 4,
            readStatus = {
                reads += 1
                null
            },
            waitBeforeRetry = { waits += 1 },
        )

        assertNull(observed)
        assertEquals(4, reads)
        assertEquals(3, waits)
    }

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
