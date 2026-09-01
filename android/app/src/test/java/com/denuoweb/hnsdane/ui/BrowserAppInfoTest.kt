package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAppInfoTest {
    @Test
    fun privacyPolicyUsesCanonicalShakescapeSite() {
        assertEquals(
            "https://shakescape.com/privacy/",
            BrowserAppInfo.PRIVACY_POLICY_URL,
        )
    }

    @Test
    fun sourceCodeUsesCanonicalCrossPlatformRepository() {
        assertEquals(
            "https://github.com/handshake-rs/hns-dane-browser-mobile",
            BrowserAppInfo.SOURCE_CODE_URL,
        )
    }
}
