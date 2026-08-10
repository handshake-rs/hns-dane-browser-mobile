package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsLoadFailurePageTest {
    @Test
    fun rendersRetryWithoutAllowingMarkupInjection() {
        val html = HnsLoadFailurePage.render(
            title = "Could not <load>",
            detail = "Waiting & retrying",
            displayHost = "app.pirate",
            retryLabel = "Try again",
            retryUrl = "https://app.pirate/?next=\"<script>alert(1)</script>",
        )

        assertTrue(html.contains("Could not &lt;load&gt;"))
        assertTrue(html.contains("Waiting &amp; retrying"))
        assertTrue(html.contains("href=\"https://app.pirate/?next=&quot;&lt;script&gt;"))
        assertFalse(html.contains("<script>alert(1)</script>"))
    }

    @Test
    fun pageHasNoNetworkCapableSubresources() {
        val html = HnsLoadFailurePage.render(
            title = "Unavailable",
            detail = "Still syncing",
            displayHost = "app.pirate",
            retryLabel = "Try again",
            retryUrl = "https://app.pirate/",
        )

        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("href=\"https://app.pirate/\""))
        assertFalse(html.contains("<script"))
    }
}
