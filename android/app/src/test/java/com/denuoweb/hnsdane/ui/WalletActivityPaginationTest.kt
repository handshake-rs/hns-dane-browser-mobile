package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletActivityPaginationTest {
    @Test
    fun activity_page_offset_is_bounded_to_an_existing_page() {
        assertEquals(0, walletPageOffset(requestedOffset = -20, totalItems = 41, pageSize = 20))
        assertEquals(20, walletPageOffset(requestedOffset = 20, totalItems = 41, pageSize = 20))
        assertEquals(40, walletPageOffset(requestedOffset = 60, totalItems = 41, pageSize = 20))
    }

    @Test
    fun activity_page_offset_handles_empty_and_invalid_pages() {
        assertEquals(0, walletPageOffset(requestedOffset = 20, totalItems = 0, pageSize = 20))
        assertEquals(0, walletPageOffset(requestedOffset = 20, totalItems = 41, pageSize = 0))
    }
}
