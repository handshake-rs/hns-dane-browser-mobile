package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.BrowserTargetKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebViewMediaPolicyTest {
    @Test
    fun autoplayIsLimitedToNativeHnsSurfaces() {
        assertTrue(allowInlineAutoplay(BrowserTargetKind.HnsName))
        assertTrue(allowInlineAutoplay(BrowserTargetKind.NativeGateway))
        assertFalse(allowInlineAutoplay(BrowserTargetKind.ExactUrl))
        assertFalse(allowInlineAutoplay(BrowserTargetKind.LocalAsset))
        assertFalse(allowInlineAutoplay(BrowserTargetKind.Search))
        assertFalse(allowInlineAutoplay(BrowserTargetKind.Blocked))
    }
}
