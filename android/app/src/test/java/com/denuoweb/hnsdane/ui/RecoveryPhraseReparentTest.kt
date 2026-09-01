package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPhraseReparentTest {
    @Test
    fun dashboardReparentRetainsSecretForExactlyOneDetach() {
        val retention = OneShotSecretReparentRetention()

        assertTrue(retention.arm())
        assertFalse(retention.shouldClearSecret())
        assertTrue(retention.shouldClearSecret())
    }

    @Test
    fun ordinaryScreenDetachClearsSecret() {
        assertTrue(OneShotSecretReparentRetention().shouldClearSecret())
    }

    @Test
    fun aSecondReparentCannotBeArmedBeforeTheFirstDetach() {
        val retention = OneShotSecretReparentRetention()

        assertTrue(retention.arm())
        assertFalse(retention.arm())
        assertFalse(retention.shouldClearSecret())
        assertTrue(retention.shouldClearSecret())
    }

    @Test
    fun unusedReparentExceptionIsDisarmedBeforeARealDetach() {
        val retention = OneShotSecretReparentRetention()

        assertTrue(retention.arm())
        retention.disarm()
        assertTrue(retention.shouldClearSecret())
    }
}
