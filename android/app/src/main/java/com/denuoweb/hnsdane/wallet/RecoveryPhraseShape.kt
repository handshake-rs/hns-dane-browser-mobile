package com.denuoweb.hnsdane.wallet

/** Checks only displayable shape; native restore verifies the mnemonic itself. */
internal fun isDisplayableRecoveryPhrase(phrase: CharArray): Boolean {
    if (phrase.isEmpty() || phrase.size > 256) return false
    var words = 0
    var inWord = false
    for (character in phrase) {
        if (character.isWhitespace()) {
            inWord = false
        } else {
            if (Character.isISOControl(character)) return false
            if (!inWord) words += 1
            inWord = true
        }
    }
    return words == 24 && inWord
}
