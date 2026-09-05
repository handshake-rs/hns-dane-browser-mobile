package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.HnsPageSecurityPath
import com.denuoweb.hnsdane.core.HnsPageTlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationStateTest {
    @Test
    fun sameDocumentHistoryCompletesWithoutMainFrameCallbacks() {
        assertTrue(
            shouldCompleteSameDocumentHistoryTraversal(
                traversalGeneration = 8,
                currentGeneration = 8,
                mainFrameLoadStarted = false,
                admittedUrl = "https://shakeshift/news",
                visitedUrl = "https://shakeshift/",
            ),
        )
    }

    @Test
    fun historyCompletionRejectsNetworkLoadsStaleCallbacksAndOtherOrigins() {
        assertFalse(
            shouldCompleteSameDocumentHistoryTraversal(
                8, 8, true, "https://shakeshift/news", "https://shakeshift/",
            ),
        )
        assertFalse(
            shouldCompleteSameDocumentHistoryTraversal(
                7, 8, false, "https://shakeshift/news", "https://shakeshift/",
            ),
        )
        assertFalse(
            shouldCompleteSameDocumentHistoryTraversal(
                8, 8, false, "https://shakeshift/news", "https://example.com/",
            ),
        )
    }

    @Test
    fun warmCacheKeepsLastExplicitDaneTransportForTheOrigin() {
        assertEquals(
            HnsPageSecurityPath.DaneAuthoritativeDns53,
            retainedSecurityPathForWarmResolution(
                reportedPath = null,
                retainedPath = HnsPageSecurityPath.DaneAuthoritativeDns53,
                retainedUrl = "https://shakeshift/",
                currentUrl = "https://shakeshift/story",
                tlsPolicy = HnsPageTlsPolicy.Dane,
            ),
        )
    }

    @Test
    fun explicitOrIncompatibleSecurityPathIsNeverReplaced() {
        assertEquals(
            HnsPageSecurityPath.DaneAuthoritativeDoh,
            retainedSecurityPathForWarmResolution(
                HnsPageSecurityPath.DaneAuthoritativeDoh,
                HnsPageSecurityPath.DaneAuthoritativeDns53,
                "https://shakeshift/",
                "https://shakeshift/",
                HnsPageTlsPolicy.Dane,
            ),
        )
        assertNull(
            retainedSecurityPathForWarmResolution(
                null,
                HnsPageSecurityPath.HnsAuthoritativeDns53,
                "http://shakeshift/",
                "https://shakeshift/",
                HnsPageTlsPolicy.Dane,
            ),
        )
    }
}
