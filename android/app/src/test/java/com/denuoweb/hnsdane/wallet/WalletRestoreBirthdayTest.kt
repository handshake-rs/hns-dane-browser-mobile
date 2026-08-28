package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletRestoreBirthdayTest {
    @Test
    fun blankDefaultsToGenesis() {
        assertEquals(0L, parseWalletRestoreBirthday(null))
        assertEquals(0L, parseWalletRestoreBirthday(""))
    }

    @Test
    fun exactUnsigned32BitBlockHeightIsAccepted() {
        assertEquals(344_000L, parseWalletRestoreBirthday("344000"))
        assertEquals(MAX_HNS_BIRTHDAY_HEIGHT, parseWalletRestoreBirthday("4294967295"))
    }

    @Test
    fun malformedOrOutOfRangeHeightIsRejected() {
        assertNull(parseWalletRestoreBirthday("-1"))
        assertNull(parseWalletRestoreBirthday(" 344000"))
        assertNull(parseWalletRestoreBirthday("344000 "))
        assertNull(parseWalletRestoreBirthday("1.5"))
        assertNull(parseWalletRestoreBirthday("4294967296"))
        assertNull(parseWalletRestoreBirthday("99999999999"))
    }
}
