import XCTest
@testable import HnsDaneBrowser

final class WalletRecoveryConfirmationTests: XCTestCase {
    func testChoicesContainCorrectWordAndFourDistinctOptions() {
        let words = (1...24).map { "word\($0)" }
        for index in words.indices {
            let choices = walletRecoveryWordChoices(words: words, correctIndex: index)
            XCTAssertEqual(choices.count, 4)
            XCTAssertEqual(Set(choices).count, 4)
            XCTAssertEqual(choices.filter { $0 == words[index] }.count, 1)
        }
    }

    func testRepeatedPhraseWordsStillProduceFourChoices() {
        let choices = walletRecoveryWordChoices(
            words: Array(repeating: "same", count: 24),
            correctIndex: 0
        )
        XCTAssertEqual(Set(choices).count, 4)
        XCTAssertTrue(choices.contains("same"))
    }
}
