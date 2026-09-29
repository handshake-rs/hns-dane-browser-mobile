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

final class WalletOverviewStateTests: XCTestCase {
    func testRecoveryChoiceAndAutomaticNewWalletBirthdayAreDistinct() {
        XCTAssertEqual(stage("recoveryUnknown"), .recoveryStart)
        XCTAssertEqual(stage("awaitingCreationTip"), .firstSync)
        XCTAssertEqual(stage("recoveryPendingValidation"), .sync)
        XCTAssertEqual(stage("validated"), .sync)
    }

    func testFullHistoryScanKeepsStopAvailableBeforeSnapshotChanges() {
        XCTAssertEqual(stage("recoveryUnknown", syncing: true), .syncing)
        XCTAssertEqual(stage("recoveryUnknown", syncing: true, stopping: true), .stopping)
        XCTAssertEqual(stage("recoveryUnknown", stopping: true), .recoveryStart)
    }

    func testSavingAndUnavailableStateCannotOfferACompetingSync() {
        XCTAssertEqual(stage("recoveryPendingValidation", saving: true), .savingRecoveryStart)
        XCTAssertEqual(stage(nil), .unavailable)
        XCTAssertEqual(stage("future-state"), .unavailable)
        XCTAssertEqual(stage(nil, syncing: true), .syncing)
    }

    private func stage(_ state: String?, syncing: Bool = false, stopping: Bool = false, saving: Bool = false) -> BitcoinOverviewStage {
        bitcoinOverviewStage(birthdayState: state, syncing: syncing, stopping: stopping, savingBirthday: saving)
    }
}
