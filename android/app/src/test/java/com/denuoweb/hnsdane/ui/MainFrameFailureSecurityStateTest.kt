package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainFrameFailureSecurityStateTest {
    @Test
    fun syncNotCurrentFailureIsProofUnavailable() {
        assertEquals(
            SecurityState.ProofUnavailable,
            mainFrameFailureSecurityState(syncWaitPageVisible = true, syncHeadersCurrent = false),
        )
    }

    @Test
    fun currentHeadersFailureIsValidationFailed() {
        assertEquals(
            SecurityState.ValidationFailed,
            mainFrameFailureSecurityState(syncWaitPageVisible = true, syncHeadersCurrent = true),
        )
    }

    @Test
    fun ordinaryNavigationFailureUsesDefaultClassifier() {
        assertNull(mainFrameFailureSecurityState(syncWaitPageVisible = false, syncHeadersCurrent = false))
    }
}
