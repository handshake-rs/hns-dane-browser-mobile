package com.denuoweb.hnsdane.ui

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRecoveryConfirmationTest {
    private val words = (1..24).map { "word$it" }

    @Test
    fun choicesContainExactlyOneCorrectWordAndFourDistinctOptions() {
        words.indices.forEach { index ->
            val choices = recoveryWordChoices(words, index, SecureRandom())
            assertEquals(4, choices.size)
            assertEquals(4, choices.distinct().size)
            assertEquals(1, choices.count { it == words[index] })
        }
    }

    @Test
    fun choicesDoNotDependOnRecoveryPhraseHavingFourDistinctWords() {
        val repeated = List(24) { "same" }
        val choices = recoveryWordChoices(repeated, 0, SecureRandom())
        assertEquals(4, choices.distinct().size)
        assertTrue("same" in choices)
    }
}
