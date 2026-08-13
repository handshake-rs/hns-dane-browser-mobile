package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitedHistoryOriginTest {
    @Test
    fun acceptsSameOriginPathQueryAndFragmentUpdates() {
        assertTrue(
            isSameAdmittedMainFrameOrigin(
                "https://App.Pirate./feed",
                "https://app.pirate/post/1?view=full#comments",
            ),
        )
    }

    @Test
    fun acceptsDefaultPortEquivalence() {
        assertTrue(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://app.pirate:443/x"))
        assertTrue(isSameAdmittedMainFrameOrigin("http://example.com:80/", "http://EXAMPLE.com/x"))
        assertTrue(isSameAdmittedMainFrameOrigin("https://app.pirate:8443/a", "https://app.pirate:8443/b"))
    }

    @Test
    fun rejectsSchemeHostAndPortChanges() {
        assertFalse(isSameAdmittedMainFrameOrigin("http://app.pirate/", "https://app.pirate/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://other.pirate/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate:8443/", "https://app.pirate:9443/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://app.pirate:8443/"))
    }

    @Test
    fun rejectsMissingOrMalformedUrls() {
        assertFalse(isSameAdmittedMainFrameOrigin(null, "https://app.pirate/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", null))
        assertFalse(isSameAdmittedMainFrameOrigin("not a URL", "https://app.pirate/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://app.pirate:"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://app.pirate:https/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://app.pirate:65536/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://app.pirate/", "https://user@app.pirate/"))
        assertFalse(isSameAdmittedMainFrameOrigin("https://[::1]/", "https://[::1]:https/"))
    }
}
