package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletNameGalleryTest {
    @Test
    fun pageOffsetsAreBoundedAndStable() {
        assertEquals(0, walletNamePageOffsetForIndex(0, 20, 45))
        assertEquals(20, walletNamePageOffsetForIndex(39, 20, 45))
        assertEquals(40, walletNamePageOffsetForIndex(44, 20, 45))
        assertNull(walletNamePageOffsetForIndex(-1, 20, 45))
        assertNull(walletNamePageOffsetForIndex(45, 20, 45))
        assertNull(walletNamePageOffsetForIndex(0, 0, 45))
    }

    @Test
    fun searchIsForgivingWithoutChangingStoredNames() {
        assertEquals("alpha-name", canonicalTrackedNameSearchText("  ALPHA-NAME. "))
        assertEquals("gamma_name", canonicalTrackedNameSearchText("gamma_name"))
        assertNull(canonicalTrackedNameSearchText("two names"))
        assertNull(canonicalTrackedNameSearchText("-invalid"))
    }

    @Test
    fun rawResourcePreviewIsBoundedAndKeepsBothEnds() {
        assertEquals("00", compactRawResourceHex("00"))
        val raw = (0 until 80).joinToString("") { it.toString(16).padStart(2, '0') }
        val preview = compactRawResourceHex(raw, maxCharacters = 40)
        assertEquals(40, preview.length)
        assertEquals(raw.take(29), preview.take(29))
        assertEquals('…', preview[29])
        assertEquals(raw.takeLast(10), preview.takeLast(10))
    }
}
