package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.core.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityIndicatorTest {
    @Test
    fun verifiedStatesShowTheLock() {
        val lockStates = SecurityState.entries - setOf(
            SecurityState.LocalContent,
            SecurityState.Syncing,
            SecurityState.Loading,
            SecurityState.ValidationFailed,
            SecurityState.ProofUnavailable,
        )
        for (state in lockStates) {
            val presentation = SecurityIndicator.forState(state)
            assertEquals("icon for $state", R.drawable.ic_security_lock, presentation.iconRes)
            assertEquals("tone for $state", SecurityIndicator.Tone.Ok, presentation.tone)
        }
    }

    @Test
    fun localContentUsesNeutralInfo() {
        val presentation = SecurityIndicator.forState(SecurityState.LocalContent)
        assertEquals(R.drawable.ic_security_info, presentation.iconRes)
        assertEquals(SecurityIndicator.Tone.Neutral, presentation.tone)
    }

    @Test
    fun transientStatesShowNeutralInfo() {
        for (state in listOf(SecurityState.Syncing, SecurityState.Loading)) {
            val presentation = SecurityIndicator.forState(state)
            assertEquals(R.drawable.ic_security_info, presentation.iconRes)
            assertEquals(SecurityIndicator.Tone.Neutral, presentation.tone)
        }
    }

    @Test
    fun failureStatesUseDistinctSeverityIcons() {
        val failed = SecurityIndicator.forState(SecurityState.ValidationFailed)
        assertEquals(R.drawable.ic_security_error, failed.iconRes)
        assertEquals(SecurityIndicator.Tone.Danger, failed.tone)

        val unavailable = SecurityIndicator.forState(SecurityState.ProofUnavailable)
        assertEquals(R.drawable.ic_security_warning, unavailable.iconRes)
        assertEquals(SecurityIndicator.Tone.Warning, unavailable.tone)
    }

    @Test
    fun everyStateKeepsItsDetailedLabel() {
        val labels = SecurityState.entries.map { SecurityIndicator.forState(it).labelRes }
        assertEquals(labels.size, labels.toSet().size)
    }
}
