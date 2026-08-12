package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPageLoadingOverlayPolicyTest {
    @Test
    fun `regular navigation may show the overlay while loading or waiting`() {
        assertTrue(shouldShowFullPageLoadingOverlay(true, pageIsLoading = true, waitingForAuthority = false))
        assertTrue(shouldShowFullPageLoadingOverlay(true, pageIsLoading = false, waitingForAuthority = true))
    }

    @Test
    fun `history navigation keeps the current page visible`() {
        assertFalse(shouldShowFullPageLoadingOverlay(false, pageIsLoading = true, waitingForAuthority = false))
        assertFalse(shouldShowFullPageLoadingOverlay(false, pageIsLoading = false, waitingForAuthority = true))
    }

    @Test
    fun `idle navigation does not show the overlay`() {
        assertFalse(shouldShowFullPageLoadingOverlay(true, pageIsLoading = false, waitingForAuthority = false))
    }
}
