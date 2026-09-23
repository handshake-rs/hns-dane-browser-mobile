package com.denuoweb.hnsdane.ui

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRecoveryConfirmationTest {
    private val bip39Words = (1..BIP39_ENGLISH_WORD_COUNT).map { "word$it" }
    private val words = bip39Words.take(24)

    @Test
    fun choicesContainExactlyOneCorrectWordAndFourDistinctOptions() {
        words.indices.forEach { index ->
            val choices = recoveryWordChoices(words, index, bip39Words, SecureRandom())
            assertEquals(4, choices.size)
            assertEquals(4, choices.distinct().size)
            assertEquals(1, choices.count { it == words[index] })
        }
    }

    @Test
    fun choicesDoNotDependOnRecoveryPhraseHavingFourDistinctWords() {
        val wordList = listOf("same") + bip39Words.drop(1)
        val repeated = List(24) { "same" }
        val choices = recoveryWordChoices(repeated, 0, wordList, SecureRandom())
        assertEquals(4, choices.distinct().size)
        assertTrue("same" in choices)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedWordListIsRejected() {
        recoveryWordChoices(words, 0, bip39Words.dropLast(1), SecureRandom())
    }
}
