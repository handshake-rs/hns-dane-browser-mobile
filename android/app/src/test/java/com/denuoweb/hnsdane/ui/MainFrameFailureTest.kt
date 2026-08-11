package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainFrameFailureTest {
    @Test
    fun onlyCurrentAdmittedMainFrameFailureEndsLoading() {
        assertTrue(
            isCurrentMainFrameFailure(
                isForMainFrame = true,
                requestUrl = "https://app.pirate/#error",
                admittedUrl = "https://app.pirate/",
                pendingUrl = null,
            ),
        )
        assertFalse(
            isCurrentMainFrameFailure(
                isForMainFrame = false,
                requestUrl = "https://app.pirate/script.js",
                admittedUrl = "https://app.pirate/",
                pendingUrl = null,
            ),
        )
        assertFalse(
            isCurrentMainFrameFailure(
                isForMainFrame = true,
                requestUrl = "https://old.pirate/",
                admittedUrl = "https://app.pirate/",
                pendingUrl = null,
            ),
        )
        assertFalse(
            isCurrentMainFrameFailure(
                isForMainFrame = true,
                requestUrl = "https://app.pirate/",
                admittedUrl = "https://app.pirate/",
                pendingUrl = "https://new.pirate/",
            ),
        )
    }
}
