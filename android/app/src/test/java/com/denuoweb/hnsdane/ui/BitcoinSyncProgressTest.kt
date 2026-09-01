package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinSyncProgressTest {
    @Test
    fun only_another_bitcoin_operation_is_gated_by_the_bitcoin_sync_flag() {
        assertEquals(true, walletBitcoinOperationMayStart(false))
        assertEquals(false, walletBitcoinOperationMayStart(true))
    }

    @Test
    fun wallet_pull_to_sync_requires_an_unobscured_window() {
        assertTrue(walletPullToSyncMayStart(windowHasFocus = true, knownDialogVisible = false))
        assertEquals(
            false,
            walletPullToSyncMayStart(windowHasFocus = false, knownDialogVisible = false),
        )
        assertEquals(
            false,
            walletPullToSyncMayStart(windowHasFocus = true, knownDialogVisible = true),
        )
    }

    @Test
    fun background_retention_requires_a_visible_service_for_either_read_only_sync() {
        assertEquals(true, walletBackgroundSynchronizationMayRetain(true, false, true))
        assertEquals(true, walletBackgroundSynchronizationMayRetain(false, true, true))
        assertEquals(false, walletBackgroundSynchronizationMayRetain(false, true, false))
        assertEquals(false, walletBackgroundSynchronizationMayRetain(false, false, true))
    }

    @Test
    fun app_switch_retention_is_useful_but_bounded() {
        assertTrue(WALLET_APP_SWITCH_RETENTION_MILLIS >= 15_000L)
        assertTrue(WALLET_APP_SWITCH_RETENTION_MILLIS <= 60_000L)
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
