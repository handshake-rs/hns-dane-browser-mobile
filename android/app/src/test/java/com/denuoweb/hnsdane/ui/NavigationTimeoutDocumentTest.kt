package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTimeoutDocumentTest {
    @Test
    fun documentOffersRetryWithoutNetworkDependencies() {
        val document = navigationTimeoutDocument(
            targetUrl = "https://example.test/path?a=1&b=2",
            title = "Page took too long",
            message = "Try again",
            retryLabel = "Retry",
            addressBarMessage = "Enter another address above.",
        )

        assertTrue(document.contains("href=\"https://example.test/path?a=1&amp;b=2\""))
        assertTrue(document.contains("Retry"))
        assertTrue(document.contains("default-src 'none'"))
        assertFalse(document.contains("<script"))
        assertFalse(document.contains("https://example.test/path?a=1&b=2\""))
    }

    @Test
    fun documentEscapesVisibleCopyAndTargetAttributes() {
        val document = navigationTimeoutDocument(
            targetUrl = "https://example.test/\"><bad>",
            title = "<title>",
            message = "Don't stop & wait",
            retryLabel = "Try <again>",
            addressBarMessage = "Use the \"address bar\".",
        )

        assertTrue(document.contains("https://example.test/&quot;&gt;&lt;bad&gt;"))
        assertTrue(document.contains("&lt;title&gt;"))
        assertTrue(document.contains("Don&#39;t stop &amp; wait"))
        assertTrue(document.contains("Try &lt;again&gt;"))
        assertTrue(document.contains("Use the &quot;address bar&quot;."))
    }
}
