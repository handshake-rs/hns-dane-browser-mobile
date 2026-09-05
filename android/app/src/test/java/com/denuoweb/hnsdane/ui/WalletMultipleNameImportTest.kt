package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletMultipleNameImportTest {
    @Test
    fun parsesCanonicalNamesSeparatedOnlyByAsciiSpaces() {
        assertEquals(
            listOf("alpha", "beta-name", "name_2"),
            parseSpaceSeparatedWalletNames("  alpha beta-name   name_2  "),
        )
        assertNull(parseSpaceSeparatedWalletNames("alpha\nbeta"))
        assertNull(parseSpaceSeparatedWalletNames("alpha\tbeta"))
        assertNull(parseSpaceSeparatedWalletNames("alpha,beta"))
        assertEquals(listOf("xn--f8h"), parseSpaceSeparatedWalletNames("⚪"))
        assertEquals(
            listOf("alpha", "xn--5p9h"),
            parseSpaceSeparatedWalletNames("alpha 🤝"),
        )
    }

    @Test
    fun rejectsDuplicatesInvalidNamesAndTheCurrentCountLimitPlusOne() {
        assertNull(parseSpaceSeparatedWalletNames("alpha alpha"))
        assertNull(parseSpaceSeparatedWalletNames("⚪ xn--f8h"))
        for (invalid in listOf("Alpha", "-alpha", "alpha-", "example")) {
            assertNull(parseSpaceSeparatedWalletNames(invalid))
        }
        assertNull(
            parseSpaceSeparatedWalletNames(
                List(MAX_MULTIPLE_WALLET_NAME_IMPORTS + 1) { index -> "n$index" }
                    .joinToString(" "),
            ),
        )
        assertTrue(isCanonicalHandshakeNameText("a".repeat(MAX_CANONICAL_HANDSHAKE_NAME_BYTES)))
        assertNull(
            parseSpaceSeparatedWalletNames(
                "a".repeat(MAX_CANONICAL_HANDSHAKE_NAME_BYTES + 1),
            ),
        )
    }
}
