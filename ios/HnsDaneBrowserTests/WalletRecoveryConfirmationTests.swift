import XCTest
@testable import HnsDaneBrowser

final class WalletRecoveryConfirmationTests: XCTestCase {
    func testChoicesContainCorrectWordAndFourDistinctOptions() {
        let wordList = (1...2_048).map { "word\($0)" }
        let words = Array(wordList.prefix(24))
        for index in words.indices {
            let choices = walletRecoveryWordChoices(
                words: words,
                correctIndex: index,
                bip39Words: wordList
            )
            XCTAssertEqual(choices.count, 4)
            XCTAssertEqual(Set(choices).count, 4)
            XCTAssertEqual(choices.filter { $0 == words[index] }.count, 1)
        }
    }

    func testRepeatedPhraseWordsStillProduceFourChoices() {
        var wordList = (1...2_048).map { "word\($0)" }
        wordList[0] = "same"
        let choices = walletRecoveryWordChoices(
            words: Array(repeating: "same", count: 24),
            correctIndex: 0,
            bip39Words: wordList
        )
        XCTAssertEqual(Set(choices).count, 4)
        XCTAssertTrue(choices.contains("same"))
    }
}
