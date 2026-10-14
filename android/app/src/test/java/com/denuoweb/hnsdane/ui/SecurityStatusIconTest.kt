package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStatusIconTest {
    @Test
    fun omnibarDistinguishesSecurePagesFromPlainHttpAndPendingState() {
        assertEquals(android.R.drawable.ic_lock_lock, securityStatusIconResource(SecurityState.DaneVerified))
        assertEquals(android.R.drawable.ic_lock_lock, securityStatusIconResource(SecurityState.WebPkiOnly))
        assertEquals(android.R.drawable.ic_dialog_alert, securityStatusIconResource(SecurityState.PlainHttp))
        assertEquals(android.R.drawable.ic_dialog_alert, securityStatusIconResource(SecurityState.HnsVerified))
        assertEquals(android.R.drawable.ic_dialog_alert, securityStatusIconResource(SecurityState.ValidationFailed))
        assertEquals(android.R.drawable.ic_popup_sync, securityStatusIconResource(SecurityState.Syncing))
        assertTrue(SecurityState.entries.all { securityStatusIconResource(it) != 0 })
    }
}
