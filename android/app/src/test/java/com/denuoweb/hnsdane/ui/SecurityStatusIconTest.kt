package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.core.SecurityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStatusIconTest {
    @Test
    fun everySecurityStateUsesItsDedicatedOmnibarIcon() {
        val icons = SecurityState.entries.map(::securityStatusIconResource)

        assertEquals(
            listOf(
                R.drawable.omnibar_status_local,
                R.drawable.omnibar_status_sync,
                R.drawable.omnibar_status_load,
                R.drawable.omnibar_status_http,
                R.drawable.omnibar_status_http_adoh,
                R.drawable.omnibar_status_http_dns53,
                R.drawable.omnibar_status_http_p2p,
                R.drawable.omnibar_status_http_rdoh,
                R.drawable.omnibar_status_https,
                R.drawable.omnibar_status_https_adoh,
                R.drawable.omnibar_status_https_dns53,
                R.drawable.omnibar_status_https_p2p,
                R.drawable.omnibar_status_https_rdoh,
                R.drawable.omnibar_status_sdane,
                R.drawable.omnibar_status_icann,
                R.drawable.omnibar_status_webpki,
                R.drawable.omnibar_status_fail,
                R.drawable.omnibar_status_noproof,
            ),
            icons,
        )
        assertTrue(icons.all { it != 0 })
        assertEquals(SecurityState.entries.size, icons.toSet().size)
    }
}
