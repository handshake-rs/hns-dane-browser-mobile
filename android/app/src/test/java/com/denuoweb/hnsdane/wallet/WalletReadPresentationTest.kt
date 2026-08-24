package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletReadPresentationTest {
    @Test
    fun hnsAmountsRemainExactWithoutFloatingPoint() {
        assertEquals("0", formatHnsBaseUnits("0"))
        assertEquals("1", formatHnsBaseUnits("1000000"))
        assertEquals("1.000001", formatHnsBaseUnits("1000001"))
        assertEquals("1.23", formatHnsBaseUnits("1230000"))
        assertEquals(
            "340282366920938463463374607431768.211455",
            formatHnsBaseUnits("340282366920938463463374607431768211455"),
        )
    }

    @Test
    fun hnsAmountParserAcceptsLeadingDecimalShorthandExactly() {
        assertEquals("100000", parsePositiveHnsToBaseUnits(".1"))
        assertEquals("10000", parsePositiveHnsToBaseUnits(".01"))
        assertEquals("1", parsePositiveHnsToBaseUnits(".000001"))
        assertNull(parsePositiveHnsToBaseUnits("."))
        assertNull(parsePositiveHnsToBaseUnits("-.1"))
        assertNull(parsePositiveHnsToBaseUnits("00.1"))
    }

    @Test
    fun nativeStatusCodesBecomePlainLabels() {
        assertEquals("canonical decoded", walletReadCodeLabel("canonicalDecoded"))
        assertEquals("watch only canonical state decoder unavailable", walletReadCodeLabel("watchOnlyCanonicalStateDecoderUnavailable"))
        assertEquals("wallet scan", walletReadCodeLabel("wallet_scan"))
    }
}
