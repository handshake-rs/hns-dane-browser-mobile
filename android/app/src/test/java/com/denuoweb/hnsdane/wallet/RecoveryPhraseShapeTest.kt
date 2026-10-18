package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPhraseShapeTest {
    private val words = (1..24).joinToString(" ") { "word$it" }.toCharArray()

    @Test
    fun acceptsTwentyFourVisibleWords() {
        assertTrue(isDisplayableRecoveryPhrase(words))
    }

    @Test
    fun rejectsTheWipedInputPreviouslySavedAfterRestore() {
        assertFalse(isDisplayableRecoveryPhrase(CharArray(words.size)))
    }

    @Test
    fun rejectsPartialAndControlCharacterInputs() {
        assertFalse(isDisplayableRecoveryPhrase("one two three".toCharArray()))
        assertFalse(isDisplayableRecoveryPhrase(words.copyOf().apply { this[0] = '\u0000' }))
    }
}
