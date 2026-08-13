package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniboxDisplayTest {
    @Test
    fun showsHostAndMeaningfulPathForWebUrls() {
        assertEquals("app.pirate", OmniboxDisplay.displayText("https://app.pirate/"))
        assertEquals(
            "app.pirate/p/post_123?x=1#frag",
            OmniboxDisplay.displayText("https://app.pirate/p/post_123?x=1#frag"),
        )
        assertEquals("app.dankmeme/feed", OmniboxDisplay.displayText("http://app.dankmeme/feed"))
    }

    @Test
    fun keepsNonDefaultPortsOnly() {
        assertEquals("app.pirate:8443", OmniboxDisplay.displayText("https://app.pirate:8443/"))
        assertEquals("app.pirate", OmniboxDisplay.displayText("https://app.pirate:443/"))
        assertEquals("example.com", OmniboxDisplay.displayText("http://example.com:80/"))
    }

    @Test
    fun normalizesHostCasingAndTrailingDot() {
        assertEquals("app.pirate/x", OmniboxDisplay.displayText("https://APP.Pirate./x"))
    }

    @Test
    fun keepsPunycodeHostsAndEncodedRoutesIntact() {
        assertEquals(
            "app.xn--pokmon--dva/p/%F0%9F%8E%AE",
            OmniboxDisplay.displayText("https://app.xn--pokmon--dva/p/%F0%9F%8E%AE"),
        )
    }

    @Test
    fun startPageAndBlankShowEmpty() {
        assertEquals("", OmniboxDisplay.displayText("https://appassets.androidplatform.net/assets/start.html"))
        assertEquals(
            "appassets.androidplatform.net:444/assets/start.html",
            OmniboxDisplay.displayText("https://appassets.androidplatform.net:444/assets/start.html"),
        )
        assertEquals(
            "appassets.androidplatform.net/not-assets/start.html",
            OmniboxDisplay.displayText("https://appassets.androidplatform.net/not-assets/start.html"),
        )
        assertEquals(
            "appassets.androidplatform.net/assets/start.html?view=recent",
            OmniboxDisplay.displayText(
                "https://appassets.androidplatform.net/assets/start.html?view=recent",
            ),
        )
        assertEquals(
            "appassets.androidplatform.net/assets/start.html#restore",
            OmniboxDisplay.displayText(
                "https://appassets.androidplatform.net/assets/start.html#restore",
            ),
        )
        assertEquals("", OmniboxDisplay.displayText("about:blank"))
        assertEquals("", OmniboxDisplay.displayText(""))
        assertEquals("", OmniboxDisplay.displayText(null))
        assertEquals("", OmniboxDisplay.displayText("   "))
    }

    @Test
    fun nonWebOrUnparseableInputStaysVerbatim() {
        assertEquals("mailto:someone@example.com", OmniboxDisplay.displayText("mailto:someone@example.com"))
        assertEquals("not a url", OmniboxDisplay.displayText("not a url"))
        assertEquals(
            "https://user:secret@app.pirate/private",
            OmniboxDisplay.displayText("https://user:secret@app.pirate/private"),
        )
    }
}
