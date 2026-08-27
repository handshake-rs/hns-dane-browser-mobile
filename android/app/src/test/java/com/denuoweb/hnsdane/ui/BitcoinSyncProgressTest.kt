package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitcoinSyncProgressTest {
    @Test
    fun only_another_bitcoin_operation_is_gated_by_the_bitcoin_sync_flag() {
        assertEquals(true, walletBitcoinOperationMayStart(false))
        assertEquals(false, walletBitcoinOperationMayStart(true))
    }

    @Test
    fun background_retention_requires_a_visible_service_for_either_read_only_sync() {
        assertEquals(true, walletBackgroundSynchronizationMayRetain(true, false, true))
        assertEquals(true, walletBackgroundSynchronizationMayRetain(false, true, true))
        assertEquals(false, walletBackgroundSynchronizationMayRetain(false, true, false))
        assertEquals(false, walletBackgroundSynchronizationMayRetain(false, false, true))
    }

    @Test
    fun eta_waits_for_a_meaningful_measurement_window() {
        assertNull(estimateBitcoinSyncRemainingMillis(40, 200, 20, 4_999))
        assertNull(estimateBitcoinSyncRemainingMillis(40, 200, 20, 10_000))
    }

    @Test
    fun eta_uses_only_work_observed_after_the_baseline() {
        assertEquals(
            30_000L,
            estimateBitcoinSyncRemainingMillis(
                completedWork = 100,
                totalWork = 200,
                baselineWork = 50,
                measurementMillis = 15_000,
            ),
        )
    }

    @Test
    fun durations_are_compact_and_stable() {
        assertEquals("0s", formatBitcoinSyncDuration(999))
        assertEquals("2m 3s", formatBitcoinSyncDuration(123_999))
        assertEquals("2h 3m", formatBitcoinSyncDuration(7_399_000))
    }
}
