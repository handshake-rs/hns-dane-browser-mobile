import Foundation
import HnsBrowserRuntime
import UIKit
import XCTest
@testable import HnsDaneBrowser

private final class WalletBootstrapAttemptCounts: @unchecked Sendable {
    private let lock = NSLock()
    private var compositionCount = 0
    private var replayCount = 0
    private var authorizationMismatchCount = 0

    func recordComposition(authorizationMatches: Bool) {
        lock.lock()
        compositionCount += 1
        if !authorizationMatches {
            authorizationMismatchCount += 1
        }
        lock.unlock()
    }

    func recordReplay() {
        lock.lock()
        replayCount += 1
        lock.unlock()
    }

    func snapshot() -> (compositions: Int, replays: Int, mismatches: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (compositionCount, replayCount, authorizationMismatchCount)
    }
}

private final class ReentrantWalletReadBootstrapSource: WalletReadBootstrapSource {
    private let configuration: NativeHnsReadConfiguration
    private let onTake: () -> Void

    init(
        configuration: NativeHnsReadConfiguration,
        onTake: @escaping () -> Void
    ) {
        self.configuration = configuration
        self.onTake = onTake
    }

    func takeConfiguration(
        for authority: WalletReadBootstrapAuthority
    ) -> NativeHnsReadConfiguration? {
        onTake()
        return configuration
    }
}

final class BrowserRuntimeControlTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    private func hnsReadBundle(
        json: String,
        version: UInt8 = 1,
        flags: UInt8 = 1,
        reservedHigh: UInt8 = 0,
        reservedLow: UInt8 = 0
    ) -> [UInt8] {
        let payload = Array(json.utf8)
        let length = UInt32(payload.count)
        return Array("HNWR".utf8) + [
            version,
            flags,
            reservedHigh,
            reservedLow,
            UInt8((length >> 24) & 0xff),
            UInt8((length >> 16) & 0xff),
            UInt8((length >> 8) & 0xff),
            UInt8(length & 0xff),
        ] + payload
    }

    private func hnsNameImportBundle(
        json: String,
        version: UInt8 = 1,
        flags: UInt8 = 0,
        reservedHigh: UInt8 = 0,
        reservedLow: UInt8 = 0
    ) -> [UInt8] {
        let payload = Array(json.utf8)
        let length = UInt32(payload.count)
        return Array("HNWI".utf8) + [
            version,
            flags,
            reservedHigh,
            reservedLow,
            UInt8((length >> 24) & 0xff),
            UInt8((length >> 16) & 0xff),
            UInt8((length >> 8) & 0xff),
            UInt8(length & 0xff),
        ] + payload
    }

    private func hnsNameSummaryJSON(name: String = "alpha") -> String {
        """
        {"name":"\(name)","nameHash":"\(String(repeating: "a", count: 64))","proofHeight":42,"resourceStatus":"canonicalDecoded","ownershipStatus":"walletOwned","registered":true,"expired":false}
        """
    }

    private func hnsAccountJSON(_ account: [UInt8]) -> String {
        account.map { String($0) }.joined(separator: ",")
    }

    private func hnsNameReceiveTargetJSON(
        account: [UInt8],
        module: String = "handshake",
        display: String = "rs1qnameowner0000000000000000000000000000000",
        derivationIndexJSON: String = "1"
    ) -> String {
        """
        {"module":"\(module)","account":[\(hnsAccountJSON(account))],"display":"\(display)","derivation_index":\(derivationIndexJSON)}
        """
    }

    private func minimalHnsReadJSON(
        paymentAccount: [UInt8],
        paymentModule: String = "handshake",
        paymentDisplay: String = "rs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz",
        paymentIndexJSON: String = "0",
        nameReceiveTargetJSON: String? = nil
    ) -> String {
        let nameReceiveField = nameReceiveTargetJSON.map {
            "\"nameReceiveTarget\":\($0),"
        } ?? ""
        return """
        {
          "balance":{"asset":"HNS","base_units":"0"},
          "receiveTarget":{"module":"\(paymentModule)","account":[\(hnsAccountJSON(paymentAccount))],"display":"\(paymentDisplay)","derivation_index":\(paymentIndexJSON)},
          \(nameReceiveField)
          "transactionHistory":[],
          "knownNames":[],
          "moduleStatus":{"phase":"ready","validated_height":42,"scanned_height":42,"target_height":42,"last_error":null}
        }
        """
    }

    override func setUp() {
        super.setUp()
        suiteName = "BrowserRuntimeControlTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testNativeHNSReadBundleIsStrictBoundedAndReadOnly() throws {
        let accountBytes = [UInt8(1)] + Array(repeating: UInt8(0), count: 15)
        let txid = ([2] + Array(repeating: 0, count: 31)).map { String($0) }.joined(separator: ",")
        let nameHash = String(repeating: "a", count: 64)
        let json = minimalHnsReadJSON(paymentAccount: accountBytes)
        let decoded = try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(json: json))
        XCTAssertEqual(decoded.balance.asset, "HNS")
        XCTAssertEqual(decoded.balance.baseUnits, "0")
        XCTAssertEqual(decoded.receiveTarget.module, "handshake")
        XCTAssertEqual(decoded.receiveTarget.account.count, 16)
        XCTAssertNil(decoded.nameReceiveTarget)
        XCTAssertEqual(decoded.moduleStatus.validatedHeight, 42)
        XCTAssertTrue(decoded.transactionHistory.isEmpty)
        XCTAssertTrue(decoded.knownNames.isEmpty)

        let nameTarget = hnsNameReceiveTargetJSON(
            account: accountBytes,
            derivationIndexJSON: "11"
        )
        let versionTwoJSON = minimalHnsReadJSON(
            paymentAccount: accountBytes,
            paymentIndexJSON: "7",
            nameReceiveTargetJSON: nameTarget
        )
        let versionTwo = try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: versionTwoJSON, version: 2)
        )
        XCTAssertEqual(versionTwo.receiveTarget.account, versionTwo.nameReceiveTarget?.account)
        XCTAssertEqual(versionTwo.nameReceiveTarget?.module, "handshake")
        XCTAssertEqual(versionTwo.nameReceiveTarget?.derivationIndex, 11)
        XCTAssertNotEqual(
            versionTwo.receiveTarget.display,
            versionTwo.nameReceiveTarget?.display
        )

        let populatedJSON = json
            .replacingOccurrences(
                of: "\"transactionHistory\":[]",
                with: "\"transactionHistory\":[{\"module\":\"handshake\",\"txid\":[\(txid)],\"status\":\"confirmed\",\"net_amount\":{\"negative\":false,\"magnitude\":\"1000000\"},\"fee\":\"10\",\"block_height\":40,\"first_seen_unix\":1,\"confirmation_count\":3}]"
            )
            .replacingOccurrences(
                of: "\"knownNames\":[]",
                with: "\"knownNames\":[{\"name\":\"alpha\",\"nameHash\":\"\(nameHash)\",\"proofHeight\":42,\"resourceStatus\":\"canonicalDecoded\",\"ownershipStatus\":\"walletOwned\",\"registered\":true,\"expired\":false}]"
            )
        let populated = try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: populatedJSON)
        )
        XCTAssertEqual(populated.transactionHistory.first?.status, "confirmed")
        XCTAssertEqual(populated.transactionHistory.first?.fee, "10")
        XCTAssertEqual(populated.knownNames.first?.name, "alpha")
        XCTAssertEqual(populated.knownNames.first?.ownershipStatus, "walletOwned")

        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, version: 2)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: versionTwoJSON)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: versionTwoJSON, version: 3)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, version: 0)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, flags: 0)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, flags: 3)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, reservedHigh: 1)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json, reservedLow: 1)
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: json) + [0]
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: Array(hnsReadBundle(json: json).dropLast())
        ))
        XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(
                json: versionTwoJSON.replacingOccurrences(
                    of: "\"balance\":",
                    with: "\"unexpected\":true,\"balance\":"
                ),
                version: 2
            )
        ))
        XCTAssertThrowsError(
            try NativeHnsReadSnapshot.decode(
                bundle: hnsReadBundle(json: json.replacingOccurrences(
                    of: "\"asset\":\"HNS\"",
                    with: "\"asset\":\"HNS\",\"unexpected\":true"
                ))
            )
        )
        XCTAssertThrowsError(
            try NativeHnsReadSnapshot.decode(
                bundle: hnsReadBundle(json: json.replacingOccurrences(
                    of: "\"phase\":\"ready\"",
                    with: "\"phase\":\"degraded\""
                ))
            )
        )
        XCTAssertThrowsError(
            try NativeHnsReadSnapshot.decode(
                bundle: hnsReadBundle(json: populatedJSON.replacingOccurrences(
                    of: "\"negative\":false,\"magnitude\":\"1000000\"",
                    with: "\"negative\":true,\"magnitude\":\"0\""
                ))
            )
        )
    }

    func testNativeHNSNameImportInputBundleAndRefreshIdentityAreStrict() throws {
        let exactText = "Alpha."
        let input = try XCTUnwrap(WalletExactHnsNameInput(exactText: exactText))
        try input.consume { bytes in
            XCTAssertEqual(bytes, Array(exactText.utf8))
        }
        XCTAssertThrowsError(try input.consume { _ in () }) { error in
            XCTAssertEqual(error as? WalletExactHnsNameInputError, .consumed)
        }
        XCTAssertNil(WalletExactHnsNameInput(exactText: nil))
        XCTAssertNil(WalletExactHnsNameInput(exactText: ""))
        XCTAssertNil(WalletExactHnsNameInput(
            exactText: String(repeating: "a", count: 64)
        ))
        let utf8Boundary = String(repeating: "é", count: 31) + "a"
        let utf8Input = try XCTUnwrap(
            WalletExactHnsNameInput(exactText: utf8Boundary)
        )
        try utf8Input.consume { bytes in
            XCTAssertEqual(bytes, Array(utf8Boundary.utf8))
            XCTAssertEqual(bytes.count, 63)
        }

        let json = hnsNameSummaryJSON()
        let summary = try NativeHnsNameImportBundle.decode(
            bundle: hnsNameImportBundle(json: json)
        )
        XCTAssertEqual(summary.name, "alpha")
        XCTAssertEqual(summary.proofHeight, 42)
        XCTAssertEqual(summary.resourceStatus, "canonicalDecoded")
        XCTAssertEqual(summary.ownershipStatus, "walletOwned")
        XCTAssertEqual(WalletReadPresenter.presentName(summary), [
            "alpha · proof height 42",
            "wallet owned · canonical decoded · registered · current",
            String(repeating: "a", count: 64),
        ].joined(separator: "\n"))

        let account = [UInt8(1)] + Array(repeating: UInt8(0), count: 15)
        let emptyReadJSON = minimalHnsReadJSON(paymentAccount: account)
        func snapshot(nameJSON: String?) throws -> NativeHnsReadSnapshot {
            let value = nameJSON.map {
                emptyReadJSON.replacingOccurrences(
                    of: "\"knownNames\":[]",
                    with: "\"knownNames\":[\($0)]"
                )
            } ?? emptyReadJSON
            return try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(json: value))
        }
        XCTAssertTrue(walletNameImportRefreshMatches(
            imported: summary,
            refreshed: try snapshot(nameJSON: hnsNameSummaryJSON())
        ))
        XCTAssertTrue(walletNameImportRefreshMatches(
            imported: summary,
            refreshed: try snapshot(nameJSON: hnsNameSummaryJSON()
                .replacingOccurrences(of: "\"proofHeight\":42", with: "\"proofHeight\":43")
                .replacingOccurrences(of: "canonicalDecoded", with: "canonicalOpaque"))
        ))
        XCTAssertFalse(walletNameImportRefreshMatches(
            imported: summary,
            refreshed: try snapshot(nameJSON: hnsNameSummaryJSON(name: "beta"))
        ))
        XCTAssertFalse(walletNameImportRefreshMatches(
            imported: summary,
            refreshed: try snapshot(nameJSON: hnsNameSummaryJSON().replacingOccurrences(
                of: String(repeating: "a", count: 64),
                with: String(repeating: "b", count: 64)
            ))
        ))
        XCTAssertFalse(walletNameImportRefreshMatches(
            imported: summary,
            refreshed: try snapshot(nameJSON: nil)
        ))

        var wrongMagic = hnsNameImportBundle(json: json)
        wrongMagic[0] = 0
        var wrongLength = hnsNameImportBundle(json: json)
        wrongLength[11] &+= 1
        let emptyPayload = Array("HNWI".utf8) + [
            UInt8(1), 0, 0, 0, 0, 0, 0, 0,
        ]
        for invalid in [
            wrongMagic,
            hnsNameImportBundle(json: json, version: 2),
            hnsNameImportBundle(json: json, flags: 1),
            hnsNameImportBundle(json: json, reservedHigh: 1),
            hnsNameImportBundle(json: json, reservedLow: 1),
            wrongLength,
            emptyPayload,
            hnsNameImportBundle(json: "[]"),
            hnsNameImportBundle(json: " \(json)"),
            hnsNameImportBundle(json: "\(json) "),
            hnsNameImportBundle(
                json: json.replacingOccurrences(of: "{\"name\"", with: "{\"ownerOutpoint\":\"private\",\"name\"")
            ),
            hnsNameImportBundle(
                json: json.replacingOccurrences(of: "\"nameHash\":", with: "\"missingNameHash\":")
            ),
            hnsNameImportBundle(json: String(repeating: " ", count: 4_097)),
        ] {
            XCTAssertThrowsError(try NativeHnsNameImportBundle.decode(bundle: invalid))
        }

        for invalidName in [
            " Alpha", "Alpha", "alpha.", "álpha", "-alpha", "alpha_",
            "example", String(repeating: "a", count: 64),
        ] {
            XCTAssertThrowsError(try NativeHnsNameImportBundle.decode(
                bundle: hnsNameImportBundle(
                    json: hnsNameSummaryJSON(name: invalidName)
                )
            ))
        }
    }

    @MainActor
    func testExactHNSNamePromptDisablesMutationAndHasNoInlineInput() throws {
        let field = UITextField()
        configureWalletNameImportTextField(field)
        XCTAssertEqual(field.keyboardType, .asciiCapable)
        XCTAssertEqual(field.autocapitalizationType, .none)
        XCTAssertEqual(field.autocorrectionType, .no)
        XCTAssertEqual(field.spellCheckingType, .no)
        XCTAssertEqual(field.smartDashesType, .no)
        XCTAssertEqual(field.smartQuotesType, .no)
        XCTAssertEqual(field.smartInsertDeleteType, .no)
        XCTAssertNil(field.textContentType)
        XCTAssertEqual(
            field.accessibilityIdentifier,
            "wallet.import-hns-name.text"
        )
        field.text = "Alpha."
        XCTAssertTrue(field.hasText)
        clearWalletNameImportManagedText(field)
        XCTAssertFalse(field.hasText)
        XCTAssertEqual(field.text ?? "", "")

        let controller = WalletViewController(network: .regtest)
        controller.loadViewIfNeeded()
        func descendant(identified identifier: String, in view: UIView) -> UIView? {
            if view.accessibilityIdentifier == identifier { return view }
            for child in view.subviews {
                if let match = descendant(identified: identifier, in: child) {
                    return match
                }
            }
            return nil
        }
        let button = descendant(
            identified: "wallet.import-hns-name",
            in: controller.view
        ) as? UIButton
        XCTAssertNotNil(button)
        XCTAssertFalse(try XCTUnwrap(button).isEnabled)
        XCTAssertNil(descendant(
            identified: "wallet.name-import-input",
            in: controller.view
        ))
    }

    func testNativeHNSReadTargetsRequireExactDistinctHandshakeBindings() throws {
        let account = [UInt8(1)] + Array(repeating: UInt8(0), count: 15)
        let otherAccount = [UInt8(2)] + Array(repeating: UInt8(0), count: 15)
        let zeroAccount = Array(repeating: UInt8(0), count: 16)
        let paymentDisplay = "rs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz"
        let nameDisplay = "rs1qnameowner0000000000000000000000000000000"
        let maximumDisplay = String(repeating: "x", count: 512)

        XCTAssertNoThrow(try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(
            json: minimalHnsReadJSON(
                paymentAccount: account,
                paymentDisplay: "x",
                nameReceiveTargetJSON: hnsNameReceiveTargetJSON(
                    account: account,
                    display: "y"
                )
            ),
            version: 2
        )))
        XCTAssertNoThrow(try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(
            json: minimalHnsReadJSON(
                paymentAccount: account,
                paymentDisplay: maximumDisplay,
                paymentIndexJSON: String(UInt32.max)
            )
        )))
        XCTAssertNoThrow(try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(
            json: minimalHnsReadJSON(
                paymentAccount: account,
                nameReceiveTargetJSON: hnsNameReceiveTargetJSON(
                    account: account,
                    display: maximumDisplay,
                    derivationIndexJSON: String(UInt32.max)
                )
            ),
            version: 2
        )))

        for invalidJSON in [
            minimalHnsReadJSON(paymentAccount: account, paymentModule: "bitcoin"),
            minimalHnsReadJSON(paymentAccount: Array(account.dropLast())),
            minimalHnsReadJSON(paymentAccount: zeroAccount),
            minimalHnsReadJSON(paymentAccount: account, paymentDisplay: ""),
            minimalHnsReadJSON(paymentAccount: account, paymentDisplay: "line\\nbreak"),
            minimalHnsReadJSON(paymentAccount: account, paymentDisplay: "café"),
            minimalHnsReadJSON(
                paymentAccount: account,
                paymentDisplay: String(repeating: "x", count: 513)
            ),
            minimalHnsReadJSON(paymentAccount: account, paymentIndexJSON: "-1"),
            minimalHnsReadJSON(paymentAccount: account, paymentIndexJSON: "4294967296"),
        ] {
            XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
                bundle: hnsReadBundle(json: invalidJSON)
            ))
        }

        let invalidNameTargets = [
            hnsNameReceiveTargetJSON(account: account, module: "bitcoin"),
            hnsNameReceiveTargetJSON(account: Array(account.dropLast())),
            hnsNameReceiveTargetJSON(account: zeroAccount),
            hnsNameReceiveTargetJSON(account: otherAccount),
            hnsNameReceiveTargetJSON(account: account, display: ""),
            hnsNameReceiveTargetJSON(account: account, display: "line\\nbreak"),
            hnsNameReceiveTargetJSON(account: account, display: "café"),
            hnsNameReceiveTargetJSON(
                account: account,
                display: String(repeating: "x", count: 513)
            ),
            hnsNameReceiveTargetJSON(account: account, derivationIndexJSON: "-1"),
            hnsNameReceiveTargetJSON(
                account: account,
                derivationIndexJSON: "4294967296"
            ),
            hnsNameReceiveTargetJSON(account: account, display: paymentDisplay),
        ]
        for invalidNameTarget in invalidNameTargets {
            let invalidJSON = minimalHnsReadJSON(
                paymentAccount: account,
                paymentDisplay: paymentDisplay,
                nameReceiveTargetJSON: invalidNameTarget
            )
            XCTAssertThrowsError(try NativeHnsReadSnapshot.decode(
                bundle: hnsReadBundle(json: invalidJSON, version: 2)
            ))
        }

        let validJSON = minimalHnsReadJSON(
            paymentAccount: account,
            paymentDisplay: paymentDisplay,
            nameReceiveTargetJSON: hnsNameReceiveTargetJSON(
                account: account,
                display: nameDisplay
            )
        )
        let decoded = try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: validJSON, version: 2)
        )
        XCTAssertEqual(decoded.receiveTarget.account, decoded.nameReceiveTarget?.account)
        XCTAssertNotEqual(decoded.receiveTarget.display, decoded.nameReceiveTarget?.display)
    }

    func testNativeHNSReadPresentationMatchesBoundedAndroidDetail() throws {
        XCTAssertEqual(WalletReadPresenter.formatHnsBaseUnits("0"), "0")
        XCTAssertEqual(WalletReadPresenter.formatHnsBaseUnits("1"), "0.000001")
        XCTAssertEqual(WalletReadPresenter.formatHnsBaseUnits("1000000"), "1")
        XCTAssertEqual(WalletReadPresenter.formatHnsBaseUnits("123456789"), "123.456789")
        XCTAssertEqual(
            WalletReadPresenter.formatHnsBaseUnits("340282366920938463463374607431768211455"),
            "340282366920938463463374607431768.211455"
        )
        XCTAssertEqual(WalletReadPresenter.visibleItemLimit(requested: 0), 1)
        XCTAssertEqual(WalletReadPresenter.visibleItemLimit(requested: 20), 20)
        XCTAssertEqual(WalletReadPresenter.visibleItemLimit(requested: Int.max), 20)
        XCTAssertEqual(
            WalletReadPresenter.codeLabel("watchOnlyCanonicalStateDecoderUnavailable"),
            "watch only canonical state decoder unavailable"
        )

        let account = ([1] + Array(repeating: 0, count: 15)).map { String($0) }.joined(separator: ",")
        let firstTransaction = ([2] + Array(repeating: 0, count: 31)).map { String($0) }.joined(separator: ",")
        let secondTransaction = ([3] + Array(repeating: 0, count: 31)).map { String($0) }.joined(separator: ",")
        let firstTransactionHex = "02" + String(repeating: "00", count: 31)
        let secondTransactionHex = "03" + String(repeating: "00", count: 31)
        let firstHash = String(repeating: "a", count: 64)
        let secondHash = String(repeating: "b", count: 64)
        let json = """
        {
          "balance":{"asset":"HNS","base_units":"12345678"},
          "receiveTarget":{"module":"handshake","account":[\(account)],"display":"rs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz","derivation_index":7},
          "transactionHistory":[
            {"module":"handshake","txid":[\(firstTransaction)],"status":"confirmed","net_amount":{"negative":true,"magnitude":"250000"},"fee":"10","block_height":40,"first_seen_unix":1,"confirmation_count":3},
            {"module":"handshake","txid":[\(secondTransaction)],"status":"mempool","net_amount":{"negative":false,"magnitude":"1000000"},"fee":null,"block_height":null,"first_seen_unix":2,"confirmation_count":0}
          ],
          "knownNames":[
            {"name":"alpha","nameHash":"\(firstHash)","proofHeight":42,"resourceStatus":"canonicalDecoded","ownershipStatus":"walletOwned","registered":true,"expired":false},
            {"name":"second","nameHash":"\(secondHash)","proofHeight":41,"resourceStatus":"empty","ownershipStatus":"notWalletOwned","registered":false,"expired":null}
          ],
          "moduleStatus":{"phase":"ready","validated_height":42,"scanned_height":42,"target_height":42,"last_error":null}
        }
        """
        let versionTwoJSON = json.replacingOccurrences(
            of: "\"transactionHistory\":[",
            with: "\"nameReceiveTarget\":{\"module\":\"handshake\",\"account\":[\(account)],\"display\":\"rs1qnameowner0000000000000000000000000000000\",\"derivation_index\":11},\n          \"transactionHistory\":["
        )
        let snapshot = try NativeHnsReadSnapshot.decode(
            bundle: hnsReadBundle(json: versionTwoJSON, version: 2)
        )
        let presentation = WalletReadPresenter.present(snapshot, maximumVisibleItems: 1)

        XCTAssertEqual(
            presentation.status,
            "Handshake reads are ready at height 42. Value movement and marketplace controls are unavailable."
        )
        XCTAssertEqual(presentation.balance, "12.345678 HNS confirmed spendable")
        XCTAssertEqual(
            presentation.paymentReceive,
            "Payment receive\nrs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz\nDerivation index 7"
        )
        XCTAssertEqual(
            presentation.nameReceive,
            "Name transfer receive\nrs1qnameowner0000000000000000000000000000000\nName derivation index 11"
        )
        XCTAssertNotEqual(presentation.paymentReceive, presentation.nameReceive)
        XCTAssertEqual(
            presentation.history,
            "confirmed · -0.25 HNS\n\(firstTransactionHex)\nBlock 40 · 3 confirmations\n\n1 more items are present in this synchronized snapshot."
        )
        XCTAssertEqual(
            presentation.names,
            "alpha · proof height 42\nwallet owned · canonical decoded · registered · current\n\(firstHash)\n\n1 more items are present in this synchronized snapshot."
        )

        let fullPresentation = WalletReadPresenter.present(snapshot, maximumVisibleItems: 2)
        XCTAssertTrue(fullPresentation.history.contains(
            "mempool · 1 HNS\n\(secondTransactionHex)\nUnconfirmed"
        ))
        XCTAssertTrue(fullPresentation.names.contains(
            "second · proof height 41\nnot wallet owned · empty · not registered\n\(secondHash)"
        ))
        XCTAssertFalse(fullPresentation.history.contains("more items"))
        XCTAssertFalse(fullPresentation.names.contains("more items"))

        let versionOne = try NativeHnsReadSnapshot.decode(bundle: hnsReadBundle(json: json))
        let versionOnePresentation = WalletReadPresenter.present(versionOne)
        XCTAssertEqual(
            versionOnePresentation.nameReceive,
            "Name transfer receive: unavailable for HNWR-v1."
        )
        XCTAssertTrue(versionOnePresentation.paymentReceive.contains(
            "rs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz"
        ))
    }

    func testNativeHNSReadConfigurationIsAuthorityBoundOneShotAndRedacted() throws {
        let path = "/private/bootstrap/NativeWallet/mainnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(lease) }
        let wallet = NSObject()
        let authority = WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 7
        )
        var authorization = Array("Bearer scoped-read-fixture".utf8)
        let configuration = try XCTUnwrap(NativeHnsReadConfiguration(
            authority: authority,
            loopbackPort: 12_037,
            authorization: &authorization
        ))
        XCTAssertTrue(authorization.isEmpty)
        XCTAssertEqual(configuration.authority, authority)
        XCTAssertEqual(
            String(describing: authority),
            "WalletReadBootstrapAuthority(<redacted>)"
        )
        XCTAssertFalse(String(reflecting: authority).contains(path))
        XCTAssertFalse(String(reflecting: authority).contains(lease.owner.uuidString))
        XCTAssertFalse(
            String(reflecting: authority).contains(String(describing: authority.walletIdentity))
        )
        XCTAssertFalse(configuration.description.contains("scoped-read-fixture"))

        var composedAuthorization: [UInt8] = []
        try configuration.consume { port, retainedAuthorization in
            XCTAssertEqual(port, 12_037)
            composedAuthorization = Array(retainedAuthorization)
        }
        XCTAssertEqual(composedAuthorization, Array("Bearer scoped-read-fixture".utf8))
        XCTAssertThrowsError(try configuration.consume { _, _ in
            XCTFail("a consumed credential must never reach native composition again")
        }) { error in
            XCTAssertEqual(error as? NativeHnsReadConfigurationError, .consumed)
        }

        var failedAuthorization = Array("Bearer failed-attempt".utf8)
        let failedConfiguration = try XCTUnwrap(NativeHnsReadConfiguration(
            authority: authority,
            loopbackPort: 12_037,
            authorization: &failedAuthorization
        ))
        XCTAssertThrowsError(try failedConfiguration.consume { _, _ -> Void in
            throw NativeWalletBridgeError.closed
        })
        XCTAssertThrowsError(try failedConfiguration.consume { _, _ in () }) { error in
            XCTAssertEqual(error as? NativeHnsReadConfigurationError, .consumed)
        }

        var mismatchedAuthorization = Array("Bearer mismatched-attempt".utf8)
        let mismatchedConfiguration = try XCTUnwrap(NativeHnsReadConfiguration(
            authority: authority,
            loopbackPort: 12_037,
            authorization: &mismatchedAuthorization
        ))
        let changedAuthority = WalletReadBootstrapAuthority(
            network: authority.network,
            databasePath: authority.databasePath,
            lease: authority.lease,
            walletIdentity: authority.walletIdentity,
            ownerGeneration: authority.ownerGeneration + 1
        )
        XCTAssertThrowsError(try mismatchedConfiguration.consume(
            for: changedAuthority
        ) { _, _ in
            XCTFail("mismatched authority must not reach native composition")
        }) { error in
            XCTAssertEqual(
                error as? NativeHnsReadConfigurationError,
                .authorityMismatch
            )
        }
        XCTAssertThrowsError(try mismatchedConfiguration.consume { _, _ in () }) { error in
            XCTAssertEqual(error as? NativeHnsReadConfigurationError, .consumed)
        }

        var rejected = Array(" leading-space".utf8)
        XCTAssertNil(NativeHnsReadConfiguration(
            authority: authority,
            loopbackPort: 12_037,
            authorization: &rejected
        ))
        XCTAssertTrue(rejected.isEmpty)

        let nonCanonicalAuthority = WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: path + "/../wallet.sqlite3",
            lease: WalletStorageLeaseToken(path: path + "/../wallet.sqlite3", owner: UUID()),
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 7
        )
        var rejectedAuthority = Array("Bearer must-be-wiped".utf8)
        XCTAssertNil(NativeHnsReadConfiguration(
            authority: nonCanonicalAuthority,
            loopbackPort: 12_037,
            authorization: &rejectedAuthority
        ))
        XCTAssertTrue(rejectedAuthority.isEmpty)
    }

    func testNativeHNSReadConfigurationConsumesOnceAcrossConcurrentCallers() throws {
        let path = "/private/bootstrap-concurrent/NativeWallet/testnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(lease) }
        let wallet = NSObject()
        let authority = WalletReadBootstrapAuthority(
            network: .testnet,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 11
        )
        var authorization = Array("Bearer concurrent-fixture".utf8)
        let configuration = try XCTUnwrap(NativeHnsReadConfiguration(
            authority: authority,
            loopbackPort: 12_038,
            authorization: &authorization
        ))

        let counts = WalletBootstrapAttemptCounts()
        let callers = DispatchGroup()
        for _ in 0..<32 {
            callers.enter()
            DispatchQueue.global(qos: .userInitiated).async {
                defer { callers.leave() }
                do {
                    try configuration.consume { _, retainedAuthorization in
                        counts.recordComposition(
                            authorizationMatches: Array(retainedAuthorization) ==
                                Array("Bearer concurrent-fixture".utf8)
                        )
                    }
                } catch NativeHnsReadConfigurationError.consumed {
                    counts.recordReplay()
                } catch {
                    XCTFail("unexpected configuration failure: \(error)")
                }
            }
        }
        XCTAssertEqual(callers.wait(timeout: .now() + 2), .success)
        let result = counts.snapshot()
        XCTAssertEqual(result.compositions, 1)
        XCTAssertEqual(result.replays, 31)
        XCTAssertEqual(result.mismatches, 0)
    }

    func testWalletReadBootstrapRequiresExactLiveReopenedAuthority() throws {
        let path = "/private/bootstrap-admission/NativeWallet/regtest/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let wallet = NSObject()
        let replacementWallet = NSObject()
        let authority = WalletReadBootstrapAuthority(
            network: .regtest,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 19
        )
        let admits: (WalletReadBootstrapAuthority, Bool, Bool, Bool, Bool, Bool) -> Bool = {
            current, reopened, protectedStorage, lifecycle, visible, retiring in
            walletReadBootstrapMayInstall(
                expected: authority,
                current: WalletReadBootstrapState(
                    authority: current,
                    reopenedDurableConfirmedWallet: reopened,
                    protectedStorageIsAvailable: protectedStorage,
                    lifecycleAllowsBootstrap: lifecycle,
                    viewIsCurrent: visible,
                    retirementInFlight: retiring
                )
            )
        }

        XCTAssertTrue(admits(authority, true, true, true, true, false))
        XCTAssertFalse(admits(authority, false, true, true, true, false))
        XCTAssertFalse(admits(authority, true, false, true, true, false))
        XCTAssertFalse(admits(authority, true, true, false, true, false))
        XCTAssertFalse(admits(authority, true, true, true, false, false))
        XCTAssertFalse(admits(authority, true, true, true, true, true))
        XCTAssertFalse(admits(WalletReadBootstrapAuthority(
            network: authority.network,
            databasePath: authority.databasePath,
            lease: WalletStorageLeaseToken(path: authority.databasePath, owner: UUID()),
            walletIdentity: authority.walletIdentity,
            ownerGeneration: authority.ownerGeneration
        ), true, true, true, true, false))
        XCTAssertFalse(admits(WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: authority.databasePath,
            lease: authority.lease,
            walletIdentity: authority.walletIdentity,
            ownerGeneration: authority.ownerGeneration
        ), true, true, true, true, false))
        XCTAssertFalse(admits(WalletReadBootstrapAuthority(
            network: authority.network,
            databasePath: "/private/other/NativeWallet/regtest/wallet.sqlite3",
            lease: authority.lease,
            walletIdentity: authority.walletIdentity,
            ownerGeneration: authority.ownerGeneration
        ), true, true, true, true, false))
        XCTAssertFalse(admits(WalletReadBootstrapAuthority(
            network: authority.network,
            databasePath: authority.databasePath,
            lease: authority.lease,
            walletIdentity: ObjectIdentifier(replacementWallet),
            ownerGeneration: authority.ownerGeneration
        ), true, true, true, true, false))
        XCTAssertFalse(admits(WalletReadBootstrapAuthority(
            network: authority.network,
            databasePath: authority.databasePath,
            lease: authority.lease,
            walletIdentity: authority.walletIdentity,
            ownerGeneration: authority.ownerGeneration + 1
        ), true, true, true, true, false))

        WalletStorageLeaseRegistry.release(lease)
        XCTAssertFalse(admits(authority, true, true, true, true, false))
        XCTAssertNil(
            UnavailableWalletReadBootstrapSource.shared.takeConfiguration(
                for: authority
            )
        )
    }

    func testWalletReadBootstrapRejectsReentrantAuthorityRotation() throws {
        let path = "/private/bootstrap-reentrant/NativeWallet/mainnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(lease) }
        let originalWallet = NSObject()
        let rotatedWallet = NSObject()
        let expectedAuthority = WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(originalWallet),
            ownerGeneration: 23
        )
        let rotatedAuthority = WalletReadBootstrapAuthority(
            network: .mainnet,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(rotatedWallet),
            ownerGeneration: 24
        )
        var currentState = WalletReadBootstrapState(
            authority: expectedAuthority,
            reopenedDurableConfirmedWallet: true,
            protectedStorageIsAvailable: true,
            lifecycleAllowsBootstrap: true,
            viewIsCurrent: true,
            retirementInFlight: false
        )
        var authorization = Array("Bearer rotated-authority".utf8)
        let returnedConfiguration = try XCTUnwrap(NativeHnsReadConfiguration(
            authority: rotatedAuthority,
            loopbackPort: 12_039,
            authorization: &authorization
        ))
        let source = ReentrantWalletReadBootstrapSource(
            configuration: returnedConfiguration,
            onTake: {
                currentState = WalletReadBootstrapState(
                    authority: rotatedAuthority,
                    reopenedDurableConfirmedWallet: true,
                    protectedStorageIsAvailable: true,
                    lifecycleAllowsBootstrap: true,
                    viewIsCurrent: true,
                    retirementInFlight: false
                )
            }
        )
        var nativeCompositionWasCalled = false

        XCTAssertFalse(try attemptWalletReadBootstrap(
            expectedAuthority: expectedAuthority,
            source: source,
            currentState: { currentState },
            install: { _, _ in nativeCompositionWasCalled = true }
        ))
        XCTAssertFalse(nativeCompositionWasCalled)
        XCTAssertThrowsError(try returnedConfiguration.consume { _, _ in
            XCTFail("a rejected source credential must be wiped and consumed")
        }) { error in
            XCTAssertEqual(error as? NativeHnsReadConfigurationError, .consumed)
        }
    }

    private func publicAuthorityStatus(
        status: String = "up_to_date",
        bestHeight: UInt64 = 339_308,
        targetHeight: UInt64 = 339_308,
        treeRootReady: Bool = true,
        blocksUntilAuthority: UInt64 = 0
    ) -> [String: Any] {
        [
            "syncStatusSchemaVersion": 3,
            "network": "mainnet",
            "status": status,
            "bestHeight": bestHeight,
            "bestPeerHeight": targetHeight,
            "estimatedTipHeight": targetHeight,
            "effectiveTargetHeight": targetHeight,
            "lagBlocks": targetHeight - bestHeight,
            "freshness": bestHeight == targetHeight ? "current" : "stale",
            "freshnessThresholdBlocks": 2,
            "treeIntervalBlocks": 36,
            "authoritativeTreeRootHeight": 339_301,
            "localTreeRootHeight": treeRootReady ? 339_301 : 338_977,
            "treeRootReady": treeRootReady,
            "blocksUntilAuthoritativeTreeRoot": blocksUntilAuthority,
            "targetSource": "corroboratedPeers",
            "targetPeerGroups": 3,
            "targetEvidenceExpired": false,
        ]
    }

    private func publicAuthoritySummary(
        status: String = "up_to_date",
        bestHeight: UInt64 = 339_308,
        targetHeight: UInt64 = 339_308,
        treeRootReady: Bool = true,
        blocksUntilAuthority: UInt64 = 0
    ) -> BrowserSyncSummary {
        BrowserSyncSummary(
            headline: "Handshake headers current",
            detail: "Current authoritative name tree",
            syncStatusSchemaVersion: 3,
            status: status,
            network: "mainnet",
            bestHeight: bestHeight,
            effectiveTargetHeight: targetHeight,
            lagBlocks: targetHeight - bestHeight,
            freshness: bestHeight == targetHeight ? "current" : "stale",
            freshnessThresholdBlocks: 2,
            treeIntervalBlocks: 36,
            authoritativeTreeRootHeight: 339_301,
            localTreeRootHeight: treeRootReady ? 339_301 : 338_977,
            treeRootReady: treeRootReady,
            blocksUntilAuthoritativeTreeRoot: blocksUntilAuthority,
            targetSource: "corroboratedPeers",
            targetPeerGroups: 3,
            targetEvidenceExpired: false
        )
    }

    private func regtestAuthoritySummary(
        status: String = "up_to_date",
        bestHeight: UInt64 = 1,
        treeRootReady: Bool = true,
        blocksUntilAuthority: UInt64 = 0,
        error: String? = nil
    ) -> BrowserSyncSummary {
        BrowserSyncSummary(
            headline: treeRootReady ? "Ready" : "Waiting",
            detail: treeRootReady ? "Ready" : "Waiting",
            syncStatusSchemaVersion: 3,
            status: status,
            network: "regtest",
            bestHeight: bestHeight,
            treeIntervalBlocks: 5,
            authoritativeTreeRootHeight: bestHeight > 0 ? 1 : nil,
            localTreeRootHeight: bestHeight > 0 ? 1 : nil,
            treeRootReady: treeRootReady,
            blocksUntilAuthoritativeTreeRoot: blocksUntilAuthority,
            error: error
        )
    }

    func testProofContainedHNSResolutionUsesLocalVerifiedProofLabel() {
        XCTAssertEqual(
            BrowserDiagnosticReports.resolutionSource(
                traceJSON: #"{"resolutionSource":"hns_resource"}"#
            ),
            "Local verified HNS proof"
        )
    }

    func testPort53InterceptionGuidanceRequiresConfirmedTraceEvidence() {
        XCTAssertTrue(
            BrowserDiagnosticReports.port53InterceptionDetected(
                traceJSON: #"{"port53Interception":"detected"}"#
            )
        )
        XCTAssertFalse(
            BrowserDiagnosticReports.port53InterceptionDetected(
                traceJSON: #"{"port53Interception":"not_detected"}"#
            )
        )
        XCTAssertFalse(BrowserDiagnosticReports.port53InterceptionDetected(traceJSON: nil))
    }

    func testPolicyNormalizesExplicitRecoveryButDiscardsLegacyModeFlags() {
        let configured = BrowserRuntimePolicy(
            resolutionMode: .compatibility,
            hnsDohResolver: "  HTTPS://Resolver.Example.NET:443/dns-query  ",
            statelessDANECertificates: true,
            experimentalP2PDNSRelay: true,
            legacyHNSDoHCompatibility: true
        )
        XCTAssertEqual(configured.resolutionMode, .strict)
        XCTAssertEqual(
            configured.hnsDohResolver,
            "https://resolver.example.net/dns-query"
        )
        XCTAssertFalse(configured.legacyHNSDoHCompatibility)
        XCTAssertTrue(configured.statelessDANECertificates)
        XCTAssertTrue(configured.experimentalP2PDNSRelay)
    }

    func testPolicyStoreRoundTripsNonSensitiveSettings() {
        let store = BrowserRuntimePolicyStore(defaults: defaults)
        let expected = BrowserRuntimePolicy(
            hnsDohResolver: "https://resolver.example.net/dns-query",
            statelessDANECertificates: true,
            experimentalP2PDNSRelay: true
        )

        store.save(expected)

        XCTAssertEqual(store.load(), expected)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertEqual(
            defaults.string(
                forKey: "hnsBrowser.runtimePolicy.hnsDohRecoveryResolver.v1"
            ),
            "https://resolver.example.net/dns-query"
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
    }

    func testPolicyStoreOneWayMigratesLegacyHNSFallbackSettings() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(
            "https://resolver.example/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility")
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.statelessDANE")
        defaults.set(false, forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertEqual(policy.resolutionMode, .strict)
        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertFalse(policy.legacyHNSDoHCompatibility)
        XCTAssertTrue(policy.statelessDANECertificates)
        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
    }

    func testLegacyFallbackConsentDoesNotBecomeRelayConsent() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(
            "https://resolver.example/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertEqual(
            defaults.object(
                forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay"
            ) as? Bool,
            false
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
        XCTAssertFalse(
            BrowserRuntimePolicyStore(defaults: defaults).load().experimentalP2PDNSRelay
        )
    }

    func testLegacyMigrationPreservesIndependentRelayOptIn() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertTrue(policy.experimentalP2PDNSRelay)
        XCTAssertTrue(
            BrowserRuntimePolicyStore(defaults: defaults).load().experimentalP2PDNSRelay
        )
    }

    func testPolicyDefaultsEnableStatelessDANEAndRequireRequesterRelayOptIn() {
        let store = BrowserRuntimePolicyStore(defaults: defaults)
        let policy = store.load()

        XCTAssertEqual(policy.resolutionMode, .strict)
        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertTrue(policy.statelessDANECertificates)
        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertFalse(policy.legacyHNSDoHCompatibility)

        store.save(BrowserRuntimePolicy(statelessDANECertificates: false))
        XCTAssertFalse(store.load().statelessDANECertificates)
    }

    func testFreshNativeRuntimeAcceptsZeroRevisionForDefaultPolicy() throws {
        let dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RustBrowserRuntimeTests.\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: dataDirectory,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: dataDirectory) }

        let runtime = try RustBrowserRuntime(dataDirectory.path, network: .regtest)
        defer { runtime.close() }

        XCTAssertEqual(try runtime.updatePolicy(.default), 0)
    }

    func testRecoveryURLValidationRejectsUnsafeAndSpecialUseEndpoints() {
        XCTAssertEqual(BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL("  "), "")
        XCTAssertEqual(
            BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(
                "HTTPS://Resolver.Example.NET.:443/dns-query?profile=hns"
            ),
            "https://resolver.example.net/dns-query?profile=hns"
        )
        for invalid in [
            "http://resolver.example.net/dns-query",
            "https://user@resolver.example.net/dns-query",
            "https://resolver.example.net/dns-query#fragment",
            "https://resolver.example.net",
            "https://127.0.0.1/dns-query",
            "https://resolver.local/dns-query",
            "https://resolver.example.net:53/dns-query",
            "https://resolver.example.net:6000/dns-query",
            "https://resolver.example.net/{?dns}",
        ] {
            XCTAssertNil(
                BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(invalid),
                invalid
            )
        }
    }

    func testLegacyResolverKeyNeverResurrectsNewRecoveryConsent() {
        defaults.set(
            "https://resolver.example.net/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(
            defaults.object(
                forKey: "hnsBrowser.runtimePolicy.hnsDohRecoveryResolver.v1"
            )
        )
    }

    @MainActor
    func testIOSSettingsUseTheSixDestinationHierarchy() {
        XCTAssertEqual(BrowserSettingsViewController.Destination.allCases.map(\.title), [
            "Settings",
            "Browser",
            "Homepage",
            "Privacy & Data",
            "Handshake",
            "Handshake Advanced",
            "Advanced",
            "About",
        ])
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .destinations),
            [
                .destinationBrowser,
                .destinationPrivacy,
                .destinationHandshake,
                .wallet,
                .destinationAdvanced,
                .destinationAbout,
            ]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .homepage),
            [.currentHomepage, .setCurrentPageAsHomepage, .changeHomepage, .resetHomepage]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .privacyData),
            [.cookies, .history, .downloads]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .privacyClearData),
            [.clearBrowsingData]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .handshakeConnection),
            [
                .handshakeNetwork,
                .hnsSync,
            ]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .handshakeSecurity),
            [
                .statelessDANECertificates,
                .hnsDoHRecovery,
            ]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .handshakeAdvanced),
            [
                .experimentalP2PDNSRelay,
                .addHNSRelayPeer,
                .handshakeAdvanced,
            ]
        )
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .tools), [
            .hnsDomainSetup,
            .resolverTrace,
            .hnsProofDetails,
            .tlsaDANEInspector,
            .diagnostics,
            .gateway,
        ])
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .about), [
            .build,
            .legal,
            .privacyPolicy,
            .sourceCode,
        ])
    }

    @MainActor
    func testWalletStorageLeaseRejectsConcurrentAndStaleOwners() throws {
        let path = "/private/test-wallet-\(UUID().uuidString)/wallet.sqlite3"
        let first = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))

        WalletStorageLeaseRegistry.release(
            WalletStorageLeaseToken(path: path, owner: UUID())
        )
        XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))

        WalletStorageLeaseRegistry.release(first)
        let replacement = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        WalletStorageLeaseRegistry.release(replacement)
    }

    @MainActor
    func testWalletRetirementRetainsLeaseThroughDestroyAndDeletion() throws {
        let path = "/private/test-wallet-retirement-\(UUID().uuidString)/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        var steps: [String] = []
        let plan = WalletRetirementPlan(
            lockController: {
                steps.append("lock")
                XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))
            },
            destroyController: {
                steps.append("destroy")
                XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))
            },
            deleteIncompleteWallet: {
                steps.append("delete")
                XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))
            },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        plan.execute()

        XCTAssertEqual(steps, ["lock", "destroy", "delete", "release"])
        let replacement = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        WalletStorageLeaseRegistry.release(replacement)
    }

    @MainActor
    func testConfirmedWalletDeletionRetainsExactLeaseAndOrdersKeyBeforeFiles() throws {
        let path = "/private/test/NativeWallet/mainnet/wallet.sqlite3"
        let otherPath = "/private/test/NativeWallet/testnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let otherLease = try XCTUnwrap(
            WalletStorageLeaseRegistry.acquire(path: otherPath)
        )
        defer { WalletStorageLeaseRegistry.release(otherLease) }
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .mainnet,
            accountID: String(repeating: "1", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 9
        )
        var steps: [String] = []
        let assertExactLeasesRemainCurrent = {
            XCTAssertTrue(WalletStorageLeaseRegistry.isCurrent(lease))
            XCTAssertTrue(WalletStorageLeaseRegistry.isCurrent(otherLease))
        }
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: {
                steps.append("lock")
                assertExactLeasesRemainCurrent()
            },
            destroyController: {
                steps.append("close")
                assertExactLeasesRemainCurrent()
            },
            deleteDatabaseKey: {
                steps.append("key")
                assertExactLeasesRemainCurrent()
            },
            deleteWalletFiles: {
                steps.append("files")
                assertExactLeasesRemainCurrent()
            },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        XCTAssertEqual(plan.execute(), .deleted)
        XCTAssertEqual(steps, ["lock", "close", "key", "files", "release"])
        XCTAssertFalse(WalletStorageLeaseRegistry.isCurrent(lease))
        XCTAssertTrue(WalletStorageLeaseRegistry.isCurrent(otherLease))
        let replacement = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        WalletStorageLeaseRegistry.release(replacement)
    }

    @MainActor
    func testConfirmedWalletDeletionKeyFailureBlocksFileDeletion() throws {
        let path = "/private/test-key-failure/NativeWallet/mainnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .mainnet,
            accountID: String(repeating: "2", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 1
        )
        var steps: [String] = []
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: { steps.append("lock") },
            destroyController: { steps.append("close") },
            deleteDatabaseKey: {
                steps.append("key")
                throw NSError(domain: "test", code: 1)
            },
            deleteWalletFiles: { steps.append("files") },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        XCTAssertEqual(plan.execute(), .keyDeletionFailed)
        XCTAssertEqual(steps, ["lock", "close", "key", "release"])
        XCTAssertFalse(WalletStorageLeaseRegistry.isCurrent(lease))
    }

    @MainActor
    func testConfirmedWalletDeletionCloseFailureBlocksKeyAndFiles() throws {
        let path = "/private/test-close-failure/NativeWallet/mainnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .mainnet,
            accountID: String(repeating: "7", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 5
        )
        var steps: [String] = []
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: { steps.append("lock") },
            destroyController: {
                steps.append("close")
                throw NSError(domain: "test", code: 3)
            },
            deleteDatabaseKey: { steps.append("key") },
            deleteWalletFiles: { steps.append("files") },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        XCTAssertEqual(plan.execute(), .controllerCloseFailed)
        XCTAssertEqual(steps, ["lock", "close", "release"])
        XCTAssertFalse(WalletStorageLeaseRegistry.isCurrent(lease))
        XCTAssertTrue(
            WalletStorageLeaseRegistry.isBlockedAfterRetirementFailure(path: path)
        )
        XCTAssertNil(WalletStorageLeaseRegistry.acquire(path: path))

        let otherPath = "/private/test-close-failure/NativeWallet/testnet/wallet.sqlite3"
        let otherNetwork = try XCTUnwrap(
            WalletStorageLeaseRegistry.acquire(path: otherPath)
        )
        WalletStorageLeaseRegistry.release(otherNetwork)
    }

    func testConfirmedWalletDeletionVerifiesAmbiguousKeychainFailure() throws {
        enum VerificationFailure: Error {
            case delete
            case query
        }

        var events: [String] = []
        XCTAssertNoThrow(try deleteWalletDatabaseKeyWithAbsenceVerification(
            delete: {
                events.append("delete-error")
                throw VerificationFailure.delete
            },
            keyExists: {
                events.append("verify-absent")
                return false
            }
        ))
        XCTAssertEqual(events, ["delete-error", "verify-absent"])

        events.removeAll()
        XCTAssertThrowsError(try deleteWalletDatabaseKeyWithAbsenceVerification(
            delete: {
                events.append("delete-error")
                throw VerificationFailure.delete
            },
            keyExists: {
                events.append("verify-present")
                return true
            }
        ))
        XCTAssertEqual(events, ["delete-error", "verify-present"])

        events.removeAll()
        XCTAssertThrowsError(try deleteWalletDatabaseKeyWithAbsenceVerification(
            delete: {
                events.append("delete-error")
                throw VerificationFailure.delete
            },
            keyExists: {
                events.append("verify-error")
                throw VerificationFailure.query
            }
        ))
        XCTAssertEqual(events, ["delete-error", "verify-error"])
    }

    @MainActor
    func testConfirmedWalletDeletionContinuesAfterVerifiedKeyAbsence() throws {
        let path = "/private/test-key-absent/NativeWallet/testnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .testnet,
            accountID: String(repeating: "8", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 6
        )
        var steps: [String] = []
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: { steps.append("lock") },
            destroyController: { steps.append("close") },
            deleteDatabaseKey: {
                try deleteWalletDatabaseKeyWithAbsenceVerification(
                    delete: {
                        steps.append("key-error")
                        throw NSError(domain: "test", code: 4)
                    },
                    keyExists: {
                        steps.append("key-verified-absent")
                        return false
                    }
                )
            },
            deleteWalletFiles: { steps.append("files") },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        XCTAssertEqual(plan.execute(), .deleted)
        XCTAssertEqual(steps, [
            "lock", "close", "key-error", "key-verified-absent", "files", "release",
        ])
    }

    @MainActor
    func testConfirmedWalletDeletionFileFailureBecomesCleanupPending() throws {
        let path = "/private/test-file-failure/NativeWallet/regtest/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .regtest,
            accountID: String(repeating: "3", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 4
        )
        var steps: [String] = []
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: { steps.append("lock") },
            destroyController: { steps.append("close") },
            deleteDatabaseKey: { steps.append("key") },
            deleteWalletFiles: {
                steps.append("files")
                throw NSError(domain: "test", code: 2)
            },
            releaseStorageLease: {
                steps.append("release")
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        XCTAssertEqual(plan.execute(), .encryptedOrphanCleanupPending)
        XCTAssertEqual(steps, ["lock", "close", "key", "files", "release"])
        XCTAssertFalse(WalletStorageLeaseRegistry.isCurrent(lease))
    }

    @MainActor
    func testConfirmedWalletDeletionRejectsStaleLeaseWithoutTouchingNewOwner() throws {
        let path = "/private/test-stale/NativeWallet/testnet/wallet.sqlite3"
        let staleLease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        WalletStorageLeaseRegistry.release(staleLease)
        let currentLease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(currentLease) }
        let controller = NSObject()
        let authority = WalletConfirmedDeletionAuthority(
            network: .testnet,
            accountID: String(repeating: "4", count: 32),
            databasePath: path,
            lease: staleLease,
            walletIdentity: ObjectIdentifier(controller),
            ownerGeneration: 2
        )
        var destructiveSteps = 0
        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            lockController: { destructiveSteps += 1 },
            destroyController: { destructiveSteps += 1 },
            deleteDatabaseKey: { destructiveSteps += 1 },
            deleteWalletFiles: { destructiveSteps += 1 },
            releaseStorageLease: {
                WalletStorageLeaseRegistry.release(staleLease)
            }
        )

        XCTAssertEqual(plan.execute(), .authorityRevoked)
        XCTAssertEqual(destructiveSteps, 0)
        XCTAssertTrue(WalletStorageLeaseRegistry.isCurrent(currentLease))
    }

    @MainActor
    func testConfirmedWalletDeletionRequiresExactTypedAndLiveAuthority() throws {
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: true,
                hasDatabase: true,
                hasArtifacts: true
            ),
            .confirmedWallet
        )
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: false,
                hasDatabase: true,
                hasArtifacts: true
            ),
            .deleteEncryptedOrphanArtifacts
        )
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: false,
                hasDatabase: false,
                hasArtifacts: true
            ),
            .deleteEncryptedOrphanArtifacts
        )
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: true,
                hasDatabase: false,
                hasArtifacts: true
            ),
            .deleteKeyThenEncryptedArtifacts
        )
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: true,
                hasDatabase: false,
                hasArtifacts: false
            ),
            .deleteStrayKey
        )
        XCTAssertEqual(
            walletStorageReconciliationAction(
                hasDatabaseKey: false,
                hasDatabase: false,
                hasArtifacts: false
            ),
            .empty
        )
        XCTAssertTrue(walletDeletionConfirmationMatches("DELETE"))
        let rejectedConfirmations: [String?] = [
            nil, "delete", "Delete", " DELETE", "DELETE ",
        ]
        for rejected in rejectedConfirmations {
            XCTAssertFalse(walletDeletionConfirmationMatches(rejected))
        }
        XCTAssertTrue(walletAccountIDIsCanonical(String(repeating: "a", count: 32)))
        XCTAssertFalse(walletAccountIDIsCanonical(String(repeating: "0", count: 32)))
        XCTAssertFalse(walletAccountIDIsCanonical(String(repeating: "A", count: 32)))
        XCTAssertFalse(walletAccountIDIsCanonical(String(repeating: "a", count: 31)))

        let path = "/private/test-authority/NativeWallet/mainnet/wallet.sqlite3"
        XCTAssertTrue(walletDatabasePathMatchesNetworkNamespace(path, network: .mainnet))
        XCTAssertFalse(walletDatabasePathMatchesNetworkNamespace(path, network: .testnet))
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(lease) }
        let wallet = NSObject()
        let replacementWallet = NSObject()
        let expected = WalletConfirmedDeletionAuthority(
            network: .mainnet,
            accountID: String(repeating: "5", count: 32),
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 7
        )
        XCTAssertEqual(
            walletDeletionIdentitySummary(expected),
            "Network: Mainnet (mainnet)\nAccount: \(expected.accountID)"
        )
        XCTAssertTrue(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: expected,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: false
        ))

        let staleGeneration = WalletConfirmedDeletionAuthority(
            network: expected.network,
            accountID: expected.accountID,
            databasePath: expected.databasePath,
            lease: expected.lease,
            walletIdentity: expected.walletIdentity,
            ownerGeneration: expected.ownerGeneration + 1
        )
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: staleGeneration,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: false
        ))
        let changedAccount = WalletConfirmedDeletionAuthority(
            network: expected.network,
            accountID: String(repeating: "6", count: 32),
            databasePath: expected.databasePath,
            lease: expected.lease,
            walletIdentity: expected.walletIdentity,
            ownerGeneration: expected.ownerGeneration
        )
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: changedAccount,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: false
        ))
        let replacement = WalletConfirmedDeletionAuthority(
            network: expected.network,
            accountID: expected.accountID,
            databasePath: expected.databasePath,
            lease: expected.lease,
            walletIdentity: ObjectIdentifier(replacementWallet),
            ownerGeneration: expected.ownerGeneration
        )
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: replacement,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: false
        ))
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: expected,
            lifecycleAllowsDeletion: false,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: false
        ))
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: expected,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: false,
            operationInFlight: false,
            screenIsCaptured: false
        ))
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: expected,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: true,
            screenIsCaptured: false
        ))
        XCTAssertFalse(walletConfirmedDeletionMayProceed(
            expected: expected,
            current: expected,
            lifecycleAllowsDeletion: true,
            viewIsCurrent: true,
            operationInFlight: false,
            screenIsCaptured: true
        ))

        XCTAssertTrue(walletDeletionCompletionMayApply(
            expectedRetirementGeneration: 8,
            currentRetirementGeneration: 8,
            expectedDetachedAuthorityGeneration: 10,
            currentAuthorityGeneration: 10,
            walletIsDetached: true,
            leaseIsDetached: true
        ))
        XCTAssertFalse(walletDeletionCompletionMayApply(
            expectedRetirementGeneration: 8,
            currentRetirementGeneration: 9,
            expectedDetachedAuthorityGeneration: 10,
            currentAuthorityGeneration: 10,
            walletIsDetached: true,
            leaseIsDetached: true
        ))
        XCTAssertFalse(walletDeletionCompletionMayApply(
            expectedRetirementGeneration: 8,
            currentRetirementGeneration: 8,
            expectedDetachedAuthorityGeneration: 10,
            currentAuthorityGeneration: 11,
            walletIsDetached: true,
            leaseIsDetached: true
        ))
        XCTAssertFalse(walletDeletionCompletionMayApply(
            expectedRetirementGeneration: 8,
            currentRetirementGeneration: 8,
            expectedDetachedAuthorityGeneration: 10,
            currentAuthorityGeneration: 10,
            walletIsDetached: false,
            leaseIsDetached: true
        ))
    }

    @MainActor
    func testWalletRetirementQueueReturnsAndCompletesOnMainAfterOffMainWork() async throws {
        let path = "/private/test-wallet-retirement-queue-\(UUID().uuidString)/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        let retirementStarted = expectation(description: "wallet retirement started")
        let mainActorRemainedResponsive = expectation(description: "main actor remained responsive")
        let retirementCompleted = expectation(description: "wallet retirement completed")
        let allowRetirement = DispatchSemaphore(value: 0)
        let phaseTimeout: TimeInterval = 10
        defer {
            allowRetirement.signal()
            WalletStorageLeaseRegistry.release(lease)
        }

        let plan = WalletRetirementPlan(
            lockController: {
                XCTAssertFalse(Thread.isMainThread)
                let unexpectedReplacement = WalletStorageLeaseRegistry.acquire(path: path)
                XCTAssertNil(unexpectedReplacement)
                if let unexpectedReplacement {
                    WalletStorageLeaseRegistry.release(unexpectedReplacement)
                }
                retirementStarted.fulfill()
                XCTAssertEqual(
                    allowRetirement.wait(timeout: .now() + phaseTimeout),
                    .success,
                    "retirement queue was not released by the test"
                )
            },
            destroyController: {
                XCTAssertFalse(Thread.isMainThread)
                let unexpectedReplacement = WalletStorageLeaseRegistry.acquire(path: path)
                XCTAssertNil(unexpectedReplacement)
                if let unexpectedReplacement {
                    WalletStorageLeaseRegistry.release(unexpectedReplacement)
                }
            },
            deleteIncompleteWallet: {
                XCTAssertFalse(Thread.isMainThread)
                let unexpectedReplacement = WalletStorageLeaseRegistry.acquire(path: path)
                XCTAssertNil(unexpectedReplacement)
                if let unexpectedReplacement {
                    WalletStorageLeaseRegistry.release(unexpectedReplacement)
                }
            },
            releaseStorageLease: {
                XCTAssertFalse(Thread.isMainThread)
                WalletStorageLeaseRegistry.release(lease)
            }
        )

        WalletRetirementQueue.shared.enqueue(plan) {
            XCTAssertTrue(Thread.isMainThread)
            let replacement = WalletStorageLeaseRegistry.acquire(path: path)
            XCTAssertNotNil(replacement)
            if let replacement {
                WalletStorageLeaseRegistry.release(replacement)
            }
            retirementCompleted.fulfill()
        }

        // Reaching this statement while the worker is gated proves enqueue did
        // not execute the contended retirement inline on the main actor.
        let prematureReplacement = WalletStorageLeaseRegistry.acquire(path: path)
        XCTAssertNil(prematureReplacement)
        if let prematureReplacement {
            WalletStorageLeaseRegistry.release(prematureReplacement)
        }
        DispatchQueue.main.async {
            mainActorRemainedResponsive.fulfill()
        }

        await fulfillment(
            of: [retirementStarted, mainActorRemainedResponsive],
            timeout: phaseTimeout
        )
        let replacementDuringRetirement = WalletStorageLeaseRegistry.acquire(path: path)
        XCTAssertNil(replacementDuringRetirement)
        if let replacementDuringRetirement {
            WalletStorageLeaseRegistry.release(replacementDuringRetirement)
        }

        allowRetirement.signal()
        await fulfillment(of: [retirementCompleted], timeout: phaseTimeout)
    }

    func testWalletReadCompletionRequiresExactLiveAuthority() {
        let lease = WalletStorageLeaseToken(
            path: "/private/test-wallet-read/wallet.sqlite3",
            owner: UUID()
        )
        let expectedWallet = NSObject()
        let replacementWallet = NSObject()
        let expectedIdentity = ObjectIdentifier(expectedWallet)

        XCTAssertTrue(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 7,
            expectedLease: lease,
            currentLease: lease,
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: expectedIdentity,
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 3,
            viewIsVisible: true
        ))
        XCTAssertFalse(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 7,
            expectedLease: lease,
            currentLease: WalletStorageLeaseToken(path: lease.path, owner: UUID()),
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: expectedIdentity,
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 3,
            viewIsVisible: true
        ))
        XCTAssertFalse(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 8,
            expectedLease: lease,
            currentLease: nil,
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: nil,
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 3,
            viewIsVisible: true
        ))
        XCTAssertFalse(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 7,
            expectedLease: lease,
            currentLease: lease,
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: ObjectIdentifier(replacementWallet),
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 3,
            viewIsVisible: true
        ))
        XCTAssertFalse(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 7,
            expectedLease: lease,
            currentLease: lease,
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: expectedIdentity,
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 3,
            viewIsVisible: false
        ))
        XCTAssertFalse(walletReadMayPublish(
            expectedGeneration: 7,
            currentGeneration: 7,
            expectedLease: lease,
            currentLease: lease,
            expectedWalletIdentity: expectedIdentity,
            currentWalletIdentity: expectedIdentity,
            expectedAuthorityGeneration: 3,
            currentAuthorityGeneration: 4,
            viewIsVisible: true
        ))
    }

    func testExactHNSNameImportRequiresLiveAuthorityAndSuppressesStaleCompletion() throws {
        let path = "/private/test-name-import-\(UUID().uuidString)/NativeWallet/mainnet/wallet.sqlite3"
        let lease = try XCTUnwrap(WalletStorageLeaseRegistry.acquire(path: path))
        defer { WalletStorageLeaseRegistry.release(lease) }
        let wallet = NSObject()
        let replacement = NSObject()
        let authority = WalletNameImportAuthority(
            network: .mainnet,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: 9
        )
        let ready = WalletNameImportState(
            authority: authority,
            reopenedDurableConfirmedWallet: true,
            protectedStorageIsAvailable: true,
            lifecycleAllowsImport: true,
            viewIsCurrent: true,
            retirementInFlight: false,
            operationInFlight: false,
            unlockedExactReadProfile: true,
            synchronizedHnsReadsConfigured: true
        )
        XCTAssertTrue(walletNameImportMayStart(expected: authority, current: ready))

        func state(
            authority currentAuthority: WalletNameImportAuthority?,
            reopened: Bool = true,
            protected: Bool = true,
            lifecycle: Bool = true,
            visible: Bool = true,
            retirement: Bool = false,
            operating: Bool = false,
            unlocked: Bool = true,
            reads: Bool = true
        ) -> WalletNameImportState {
            WalletNameImportState(
                authority: currentAuthority,
                reopenedDurableConfirmedWallet: reopened,
                protectedStorageIsAvailable: protected,
                lifecycleAllowsImport: lifecycle,
                viewIsCurrent: visible,
                retirementInFlight: retirement,
                operationInFlight: operating,
                unlockedExactReadProfile: unlocked,
                synchronizedHnsReadsConfigured: reads
            )
        }

        let staleAuthority = WalletNameImportAuthority(
            network: authority.network,
            databasePath: authority.databasePath,
            lease: authority.lease,
            walletIdentity: ObjectIdentifier(replacement),
            ownerGeneration: authority.ownerGeneration + 1
        )
        for rejected in [
            state(authority: staleAuthority),
            state(authority: authority, reopened: false),
            state(authority: authority, protected: false),
            state(authority: authority, lifecycle: false),
            state(authority: authority, visible: false),
            state(authority: authority, retirement: true),
            state(authority: authority, operating: true),
            state(authority: authority, unlocked: false),
            state(authority: authority, reads: false),
        ] {
            XCTAssertFalse(walletNameImportMayStart(
                expected: authority,
                current: rejected
            ))
        }

        XCTAssertTrue(walletNameImportCompletionMayApply(
            expected: authority,
            current: authority,
            expectedGeneration: 11,
            currentGeneration: 11,
            expectedLease: lease,
            currentLease: lease,
            lifecycleAllowsImport: true,
            viewIsCurrent: true,
            operationInFlight: true
        ))
        for rejected in [
            walletNameImportCompletionMayApply(
                expected: authority,
                current: staleAuthority,
                expectedGeneration: 11,
                currentGeneration: 11,
                expectedLease: lease,
                currentLease: lease,
                lifecycleAllowsImport: true,
                viewIsCurrent: true,
                operationInFlight: true
            ),
            walletNameImportCompletionMayApply(
                expected: authority,
                current: authority,
                expectedGeneration: 11,
                currentGeneration: 12,
                expectedLease: lease,
                currentLease: lease,
                lifecycleAllowsImport: true,
                viewIsCurrent: true,
                operationInFlight: true
            ),
            walletNameImportCompletionMayApply(
                expected: authority,
                current: authority,
                expectedGeneration: 11,
                currentGeneration: 11,
                expectedLease: lease,
                currentLease: lease,
                lifecycleAllowsImport: false,
                viewIsCurrent: true,
                operationInFlight: true
            ),
            walletNameImportCompletionMayApply(
                expected: authority,
                current: authority,
                expectedGeneration: 11,
                currentGeneration: 11,
                expectedLease: lease,
                currentLease: lease,
                lifecycleAllowsImport: true,
                viewIsCurrent: false,
                operationInFlight: true
            ),
            walletNameImportCompletionMayApply(
                expected: authority,
                current: authority,
                expectedGeneration: 11,
                currentGeneration: 11,
                expectedLease: lease,
                currentLease: lease,
                lifecycleAllowsImport: true,
                viewIsCurrent: true,
                operationInFlight: false
            ),
        ] {
            XCTAssertFalse(rejected)
        }

        XCTAssertTrue(walletNameImportFailureIsNonPoisoningInvalid(
            NativeWalletBridgeError.callFailed(
                operation: "test",
                code: HNS_BROWSER_RESULT_INVALID_ARGUMENT,
                detail: "invalid"
            )
        ))
        XCTAssertFalse(walletNameImportFailureIsNonPoisoningInvalid(
            NativeWalletBridgeError.callFailed(
                operation: "test",
                code: HNS_BROWSER_RESULT_RUNTIME_ERROR,
                detail: "failed"
            )
        ))
    }

    func testWalletFaceIDPurposeIsPresentInBuiltApplication() throws {
        XCTAssertEqual(
            try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "NSFaceIDUsageDescription") as? String),
            "Use Face ID to unlock your local Handshake wallet."
        )
    }

    @MainActor
    func testIOSSettingsExposeCurrentPageOnlyWhenAndroidWould() throws {
        let withoutPage = BrowserSettingsViewController(
            destination: .homepage,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        withoutPage.loadViewIfNeeded()
        XCTAssertEqual(withoutPage.numberOfSections(in: withoutPage.tableView), 1)
        XCTAssertEqual(
            withoutPage.tableView(withoutPage.tableView, numberOfRowsInSection: 0),
            3
        )

        let withPage = BrowserSettingsViewController(
            destination: .homepage,
            policy: .default,
            runtimeControlsAreAvailable: true,
            currentPageURL: "https://example.com/current"
        )
        withPage.loadViewIfNeeded()
        XCTAssertEqual(withPage.tableView(withPage.tableView, numberOfRowsInSection: 0), 4)
        let cell = withPage.tableView(
            withPage.tableView,
            cellForRowAt: IndexPath(row: 1, section: 0)
        )
        let content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        XCTAssertEqual(content.text, "Use current page")
        XCTAssertEqual(content.secondaryText, "https://example.com/current")
        XCTAssertNil(cell.accessoryView)
    }

    @MainActor
    func testIOSSettingsUseDestinationRowsWithoutFakeActionLabels() {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            currentPageURL: "https://example.com/current"
        )
        settings.loadViewIfNeeded()
        XCTAssertEqual(settings.numberOfSections(in: settings.tableView), 1)
        XCTAssertEqual(settings.tableView(settings.tableView, numberOfRowsInSection: 0), 6)
        let expectedTitles = ["Browser", "Privacy & Data", "Handshake", "Wallet", "Advanced", "About"]
        for (row, title) in expectedTitles.enumerated() {
            let cell = settings.tableView(
                settings.tableView,
                cellForRowAt: IndexPath(row: row, section: 0)
            )
            XCTAssertEqual((cell.contentConfiguration as? UIListContentConfiguration)?.text, title)
            XCTAssertEqual(cell.accessoryType, .disclosureIndicator)
            XCTAssertNil(cell.accessoryView)
        }
    }

    @MainActor
    func testStatelessDANEIsAnEnabledByDefaultSecurityToggle() throws {
        let settings = BrowserSettingsViewController(
            destination: .handshake,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        settings.loadViewIfNeeded()
        let indexPath = IndexPath(row: 0, section: 1)

        var cell = settings.tableView(settings.tableView, cellForRowAt: indexPath)
        var content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        let toggle = try XCTUnwrap(cell.accessoryView as? UISwitch)
        XCTAssertEqual(cell.accessibilityIdentifier, "settings.handshake.stateless-dane-certificates")
        XCTAssertEqual(content.text, "Stateless DANE")
        XCTAssertEqual(
            content.secondaryText,
            "On by default. Normal DNSSEC and TLSA validation remains authoritative; a stateless HNS certificate is accepted only when its proof, DNSSEC chain, and TLSA evidence all validate."
        )
        XCTAssertTrue(toggle.isOn)

        settings.update(
            policy: BrowserRuntimePolicy(statelessDANECertificates: false),
            runtimeControlsAreAvailable: true,
            isOperationInFlight: false
        )
        cell = settings.tableView(settings.tableView, cellForRowAt: indexPath)
        content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        XCTAssertEqual(
            content.secondaryText,
            "Off. Stateless-only HNS sites fail closed. Sites with normal DNSSEC and TLSA records continue to work."
        )
        XCTAssertFalse(try XCTUnwrap(cell.accessoryView as? UISwitch).isOn)
    }

    @MainActor
    func testHNSSyncRowNavigatesBeforeAnExplicitRun() throws {
        let initialSummary = BrowserSyncSummary(
            headline: "Handshake sync idle",
            detail: "Local height 300000 · peer height 300100 · accepted 0/0",
            status: "idle",
            network: "mainnet",
            peerCount: 4,
            peerGroups: 2,
            bestHeight: 300_000,
            bestPeerHeight: 300_100
        )
        let settings = BrowserSettingsViewController(
            destination: .handshake,
            policy: .default,
            runtimeControlsAreAvailable: true,
            syncSummary: initialSummary
        )
        let delegate = BrowserSettingsDelegateSpy()
        settings.delegate = delegate
        let navigation = UINavigationController(rootViewController: settings)
        navigation.loadViewIfNeeded()
        settings.loadViewIfNeeded()

        let settingsIndexPath = IndexPath(row: 1, section: 0)
        let settingsCell = settings.tableView(
            settings.tableView,
            cellForRowAt: settingsIndexPath
        )
        let settingsContent = try XCTUnwrap(
            settingsCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertEqual(
            settingsContent.secondaryText,
            "View sync status and run a manual sync."
        )

        settings.tableView(settings.tableView, didSelectRowAt: settingsIndexPath)

        let sync = try XCTUnwrap(navigation.topViewController as? HNSSyncViewController)
        XCTAssertTrue(delegate.actions.isEmpty)
        sync.loadViewIfNeeded()
        let statusCell = sync.tableView(
            sync.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        let statusContent = try XCTUnwrap(
            statusCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertTrue(statusContent.secondaryText?.contains("Handshake sync idle") == true)
        XCTAssertTrue(statusContent.secondaryText?.contains("Network: mainnet") == true)

        sync.tableView(sync.tableView, didSelectRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(delegate.actions, [.runHNSSync])
    }

    @MainActor
    func testHNSSyncStatusScreenReceivesLiveSummaryUpdates() throws {
        let settings = BrowserSettingsViewController(
            destination: .handshake,
            policy: .default,
            runtimeControlsAreAvailable: true,
            syncSummary: .unavailable
        )
        let navigation = UINavigationController(rootViewController: settings)
        navigation.loadViewIfNeeded()
        settings.loadViewIfNeeded()
        settings.tableView(
            settings.tableView,
            didSelectRowAt: IndexPath(row: 1, section: 0)
        )
        let sync = try XCTUnwrap(navigation.topViewController as? HNSSyncViewController)
        sync.loadViewIfNeeded()

        settings.update(
            policy: .default,
            runtimeControlsAreAvailable: true,
            isOperationInFlight: false,
            syncSummary: BrowserSyncSummary(
                headline: "Handshake headers current",
                detail: "Local height 335942 · peer height 335942 · accepted 2/2",
                status: "up_to_date",
                network: "mainnet",
                attempted: 2,
                successful: 2,
                accepted: 2,
                peerCount: 8,
                peerGroups: 3,
                bestHeight: 335_942,
                bestPeerHeight: 335_942
            )
        )

        let statusCell = sync.tableView(
            sync.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        let content = try XCTUnwrap(
            statusCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertTrue(content.secondaryText?.contains("Handshake headers current") == true)
        XCTAssertTrue(content.secondaryText?.contains("Peers: 8 in 3 groups") == true)
    }

    @MainActor
    func testIOSSettingsDoNotReloadForSyncSummaryOnlyUpdates() {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            syncSummary: .unavailable
        )
        settings.loadViewIfNeeded()
        let table = ReloadCountingTableView(frame: .zero, style: .insetGrouped)
        settings.tableView = table
        let initialReloadCount = table.reloadDataCallCount

        for height in 1...3 {
            settings.update(
                policy: .default,
                runtimeControlsAreAvailable: true,
                isOperationInFlight: false,
                syncSummary: BrowserSyncSummary(
                    headline: "Handshake sync \(height)",
                    detail: "Local height \(height)",
                    status: "syncing",
                    network: "mainnet",
                    bestHeight: UInt64(height)
                )
            )
        }

        XCTAssertEqual(table.reloadDataCallCount, initialReloadCount)

        settings.update(
            policy: .default,
            runtimeControlsAreAvailable: true,
            isOperationInFlight: false,
            syncSummary: .unavailable,
            historyCount: 1
        )
        XCTAssertEqual(table.reloadDataCallCount, initialReloadCount + 1)
    }

    @MainActor
    func testIOSSettingsKeepBackedDefaultsInTheirDetailDestinations() throws {
        let resolverSettings = BrowserSettingsViewController(
            destination: .handshakeAdvanced,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        resolverSettings.loadViewIfNeeded()

        let cache = resolverSettings.tableView(
            resolverSettings.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        XCTAssertEqual(
            try XCTUnwrap(cache.contentConfiguration as? UIListContentConfiguration)
                .secondaryText,
            "Ready to clear cached resolver values."
        )
        XCTAssertNil(cache.accessoryView)

        let handshakeSettings = BrowserSettingsViewController(
            destination: .handshake,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        handshakeSettings.loadViewIfNeeded()
        let hnsSync = handshakeSettings.tableView(
            handshakeSettings.tableView,
            cellForRowAt: IndexPath(row: 1, section: 0)
        )
        XCTAssertEqual(hnsSync.accessoryType, .disclosureIndicator)

        let advancedSettings = BrowserSettingsViewController(
            destination: .advanced,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        advancedSettings.loadViewIfNeeded()
        let proof = advancedSettings.tableView(
            advancedSettings.tableView,
            cellForRowAt: IndexPath(row: 2, section: 0)
        )
        XCTAssertEqual(proof.accessoryType, .disclosureIndicator)

        let aboutSettings = BrowserSettingsViewController(
            destination: .about,
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        aboutSettings.loadViewIfNeeded()
        let build = aboutSettings.tableView(
            aboutSettings.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        let buildText = try XCTUnwrap(
            (build.contentConfiguration as? UIListContentConfiguration)?.secondaryText
        )
        XCTAssertTrue(buildText.hasPrefix("release "))
        XCTAssertTrue(buildText.contains(" ("))
        XCTAssertTrue(buildText.hasSuffix(")"))
    }

    func testPolicyStoreFallsBackForUnknownResolutionMode() {
        defaults.set(
            "future-mode",
            forKey: "hnsBrowser.runtimePolicy.resolutionMode"
        )

        XCTAssertEqual(
            BrowserRuntimePolicyStore(defaults: defaults).load().resolutionMode,
            .strict
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
    }

    func testLegacyResolverAndWebPkiStatusesFailClosedButRecoveryDaneIsVerified() {
        let legacyResolver = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY,
            securityPath: HNS_BROWSER_SECURITY_PATH_STATELESS_DANE
        )
        let legacyPath = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH
        )
        let legacyWebPKI = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            allowsWebPkiFallback: true
        )
        let legacyTopLevelIcann = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"nameClass":"icann"}"#
        )
        let contradictoryIcann = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"namespaceResolution":{"outcome":"hnsOnly","selected":"icann"}}"#
        )
        let icannWebPKI = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"namespaceResolution":{"outcome":"icannOnly","selected":"icann","reason":"onlyAvailableRoot","hnsState":"authenticatedAbsent","icannState":"present","fingerprint":null}}"#
        )

        XCTAssertEqual(legacyResolver.level, .blocked)
        XCTAssertEqual(legacyPath.level, .handshakeDANE)
        XCTAssertTrue(legacyPath.detail.contains("user-configured HNS recovery DoH"))
        XCTAssertEqual(legacyWebPKI.level, .blocked)
        XCTAssertEqual(legacyTopLevelIcann.level, .blocked)
        XCTAssertEqual(contradictoryIcann.level, .blocked)
        XCTAssertEqual(icannWebPKI.level, .webPKI)
        XCTAssertTrue(icannWebPKI.detail.contains("validating ICANN DoH"))
        XCTAssertTrue(icannWebPKI.detail.contains("ICANN only"))
    }

    func testCurrentDANEStatusRemainsVerifiedInSecurityUI() {
        let summary = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY
        )

        XCTAssertEqual(summary.level, .handshakeDANE)
        XCTAssertTrue(summary.detail.contains("P2P DNS relay"))
    }

    func testSyncSchedulingUsesBoundedFailureBackoff() {
        let policy = BrowserSyncSchedulingPolicy(
            progressInterval: 30,
            caughtUpInterval: 300,
            failureBackoff: [5, 15, 60]
        )

        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 1), 5)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 2), 15)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 3), 60)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 20), 60)
    }

    func testSyncSchedulingSlowsDownWhenCaughtUp() {
        let policy = BrowserSyncSchedulingPolicy()
        let caughtUp = publicAuthoritySummary()
        let syncing = BrowserSyncSummary(
            headline: "Syncing",
            detail: "Syncing",
            status: "syncing"
        )

        XCTAssertEqual(policy.delay(after: caughtUp, consecutiveFailures: 0), 600)
        XCTAssertEqual(policy.delay(after: syncing, consecutiveFailures: 0), 600)
        XCTAssertEqual(
            policy.delay(
                after: BrowserSyncSummary(
                    headline: "Syncing",
                    detail: "Advancing",
                    status: "syncing",
                    accepted: 1
                ),
                consecutiveFailures: 0
            ),
            30
        )
        XCTAssertTrue(
            BrowserSyncSummary(
                headline: "Attention",
                detail: "Peer failed",
                status: "peer_failed"
            ).requiresRetry
        )
    }

    func testSyncSchedulingRetriesUnknownTargetPromptlyWithoutChangingProgressCadence() {
        let policy = BrowserSyncSchedulingPolicy(
            progressInterval: 30,
            retryInterval: 10,
            caughtUpInterval: 600
        )
        let unknownTarget = BrowserSyncSummary(
            headline: "Syncing",
            detail: "Waiting for corroborated target",
            syncStatusSchemaVersion: 3,
            status: "up_to_date",
            network: BrowserHandshakeNetwork.mainnet.rawValue,
            accepted: 0,
            bestHeight: 300_000
        )
        let progressing = BrowserSyncSummary(
            headline: "Syncing",
            detail: "Validating headers",
            status: "syncing",
            accepted: 2_000,
            bestHeight: 302_000
        )
        let regtest = BrowserSyncSummary(
            headline: "Syncing",
            detail: "Local regression-test network",
            syncStatusSchemaVersion: 3,
            status: "syncing",
            network: BrowserHandshakeNetwork.regtest.rawValue,
            accepted: 0,
            bestHeight: 42
        )
        let resetGenesisStatuses = ["idle", "syncing", "up_to_date", "attempted", "synced"]

        XCTAssertTrue(unknownTarget.hasUnknownTargetProgress)
        XCTAssertEqual(policy.delay(after: unknownTarget, consecutiveFailures: 0), 10)
        for status in resetGenesisStatuses {
            let resetGenesis = BrowserSyncSummary(
                headline: "Syncing",
                detail: "Recovering from genesis",
                syncStatusSchemaVersion: 3,
                status: status,
                network: BrowserHandshakeNetwork.mainnet.rawValue,
                accepted: 0,
                bestHeight: 0
            )
            XCTAssertTrue(resetGenesis.needsHeaderBootstrap)
            XCTAssertEqual(policy.delay(after: resetGenesis, consecutiveFailures: 0), 10)
        }
        XCTAssertEqual(policy.delay(after: progressing, consecutiveFailures: 0), 30)
        XCTAssertFalse(regtest.needsHeaderBootstrap)
        XCTAssertFalse(regtest.hasUnknownTargetProgress)
        XCTAssertEqual(policy.delay(after: regtest, consecutiveFailures: 0), 600)
    }

    @MainActor
    func testSyncDiagnosticVisibilityTransitionsFromCurrentToInFlightAndBack() throws {
        let current = try RustBrowserRuntime.syncSummary(from: publicAuthorityStatus())
        XCTAssertTrue(current.isCaughtUp)
        XCTAssertFalse(current.shouldShowSyncProgress)

        var inFlightStatus = publicAuthorityStatus()
        inFlightStatus["syncInFlight"] = true
        inFlightStatus["stagedBestHeight"] = 339_344
        inFlightStatus["stagedAccepted"] = 36
        let inFlight = try RustBrowserRuntime.syncSummary(from: inFlightStatus)

        XCTAssertTrue(inFlight.isCaughtUp, "staged telemetry must not change committed currentness")
        XCTAssertTrue(inFlight.shouldShowSyncProgress)
        XCTAssertEqual(inFlight.bestHeight, 339_308)
        XCTAssertEqual(inFlight.displayedSyncHeight, 339_344)
        XCTAssertEqual(inFlight.syncProgressFraction, 1)
        XCTAssertEqual(inFlight.headline, "Syncing Handshake headers")
        XCTAssertFalse(inFlight.syncDiagnosticText.contains("Committed"))
        XCTAssertTrue(inFlight.syncDiagnosticText.contains("staged validated 339344"))
        XCTAssertTrue(inFlight.syncDiagnosticText.contains("effective target 339308"))
        XCTAssertTrue(inFlight.syncDiagnosticText.contains("HNS root 339301 ready"))
        XCTAssertTrue(inFlight.syncDiagnosticText.contains("staged accepted +36"))
        XCTAssertTrue(
            HNSSyncViewController.statusText(
                summary: inFlight,
                isOperationInFlight: false
            ).hasPrefix("Running…")
        )

        var stagedOnlyStatus = publicAuthorityStatus(
            status: "syncing",
            bestHeight: 339_000,
            treeRootReady: false,
            blocksUntilAuthority: 301
        )
        stagedOnlyStatus["syncInFlight"] = true
        stagedOnlyStatus["stagedBestHeight"] = 339_308
        stagedOnlyStatus["stagedAccepted"] = 308
        let stagedOnly = try RustBrowserRuntime.syncSummary(from: stagedOnlyStatus)
        XCTAssertFalse(stagedOnly.hasAuthoritativeTreeRoot)
        XCTAssertFalse(stagedOnly.isCaughtUp)
        XCTAssertFalse(
            BrowserAuthorityAdmissionPolicy().allowsProxyResume(
                network: .mainnet,
                isForeground: true,
                syncSummary: stagedOnly
            ),
            "private staged progress must never authorize browsing"
        )

        let currentAgain = try RustBrowserRuntime.syncSummary(from: publicAuthorityStatus())
        XCTAssertFalse(currentAgain.shouldShowSyncProgress)
        XCTAssertEqual(currentAgain.displayedSyncHeight, currentAgain.bestHeight)
        XCTAssertEqual(currentAgain.headline, "Handshake headers current")
    }

    func testSyncDiagnosticOmitsUnknownTargetAndRootClauses() throws {
        let summary = try RustBrowserRuntime.syncSummary(from: [
            "syncStatusSchemaVersion": 3,
            "network": "mainnet",
            "status": "syncing",
            "bestHeight": 300_000,
            "bestPeerHeight": 337_000,
            "estimatedTipHeight": 336_900,
            "freshness": "unknown",
            "syncInFlight": true,
            "stagedBestHeight": 324_000,
            "stagedAccepted": 24_000,
        ])

        XCTAssertEqual(
            summary.detail,
            "staged validated 324000 · freshness unknown · raw peer 337000 "
                + "· estimate 336900 · staged accepted +24000"
        )
        XCTAssertFalse(summary.syncDiagnosticText.contains("Committed"))
        XCTAssertFalse(summary.syncDiagnosticText.contains("target unknown"))
        XCTAssertFalse(summary.syncDiagnosticText.contains("HNS root unknown"))
    }

    func testIOSRecognizesAndroidCurrentSyncStates() throws {
        let policy = BrowserSyncSchedulingPolicy()
        for status in ["up_to_date", "synced", "attempted"] {
            let summary = try RustBrowserRuntime.syncSummary(
                from: publicAuthorityStatus(status: status)
            )

            XCTAssertTrue(summary.isCaughtUp, status)
            XCTAssertFalse(summary.isBehind, status)
            XCTAssertEqual(summary.headline, "Handshake headers current", status)
            XCTAssertEqual(policy.delay(after: summary, consecutiveFailures: 0), 600)
        }

        let behind = try RustBrowserRuntime.syncSummary(
            from: publicAuthorityStatus(
                status: "attempted",
                bestHeight: 339_000,
                treeRootReady: false,
                blocksUntilAuthority: 301
            )
        )
        XCTAssertTrue(behind.isBehind)
        XCTAssertFalse(behind.isCaughtUp)
        XCTAssertEqual(behind.headline, "Syncing Handshake headers")
        XCTAssertEqual(policy.delay(after: behind, consecutiveFailures: 0), 600)

        let legacy = try RustBrowserRuntime.syncSummary(from: [
            "network": "mainnet",
            "status": "up_to_date",
            "bestHeight": 339_308,
            "bestPeerHeight": 339_308,
            "estimatedTipHeight": 339_400,
        ])
        XCTAssertFalse(legacy.isCaughtUp)
        XCTAssertNil(legacy.targetHeight)
        XCTAssertEqual(legacy.freshness, "unknown")
        XCTAssertEqual(legacy.headline, "Handshake sync up to date")
        XCTAssertEqual(policy.delay(after: legacy, consecutiveFailures: 0), 600)
    }

    func testAuthoritativeCurrentnessRequiresExactVersionedContract() throws {
        let current = publicAuthorityStatus()
        var variants: [[String: Any]] = []

        var missingVersion = current
        missingVersion.removeValue(forKey: "syncStatusSchemaVersion")
        variants.append(missingVersion)

        var invalidInterval = current
        invalidInterval["treeIntervalBlocks"] = 0
        variants.append(invalidInterval)

        var missingAuthority = current
        missingAuthority["treeRootReady"] = false
        variants.append(missingAuthority)

        var mismatchedAuthority = current
        mismatchedAuthority["localTreeRootHeight"] = 339_302
        variants.append(mismatchedAuthority)

        var blocksUntilAuthority = current
        blocksUntilAuthority["blocksUntilAuthoritativeTreeRoot"] = 1
        variants.append(blocksUntilAuthority)

        var insufficientGroups = current
        insufficientGroups["targetPeerGroups"] = 2
        variants.append(insufficientGroups)

        var expiredEvidence = current
        expiredEvidence["targetEvidenceExpired"] = true
        variants.append(expiredEvidence)

        var genesis = current
        genesis["bestHeight"] = 0
        genesis["effectiveTargetHeight"] = 0
        variants.append(genesis)

        for variant in variants {
            let summary = try RustBrowserRuntime.syncSummary(from: variant)
            XCTAssertFalse(summary.hasAuthoritativeCurrentness)
            XCTAssertFalse(summary.isCaughtUp)
        }
    }

    func testAuthorityAdmissionRequiresForegroundAuthoritativeCurrentness() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let current = publicAuthoritySummary()

        for network in [BrowserHandshakeNetwork.mainnet, .testnet] {
            let networkCurrent = BrowserSyncSummary(
                headline: current.headline,
                detail: current.detail,
                syncStatusSchemaVersion: current.syncStatusSchemaVersion,
                status: current.status,
                network: network.rawValue,
                bestHeight: current.bestHeight,
                effectiveTargetHeight: current.effectiveTargetHeight,
                lagBlocks: current.lagBlocks,
                freshness: current.freshness,
                freshnessThresholdBlocks: current.freshnessThresholdBlocks,
                treeIntervalBlocks: current.treeIntervalBlocks,
                authoritativeTreeRootHeight: current.authoritativeTreeRootHeight,
                localTreeRootHeight: current.localTreeRootHeight,
                treeRootReady: current.treeRootReady,
                blocksUntilAuthoritativeTreeRoot: current.blocksUntilAuthoritativeTreeRoot,
                targetSource: current.targetSource,
                targetPeerGroups: current.targetPeerGroups,
                targetEvidenceExpired: current.targetEvidenceExpired
            )
            XCTAssertTrue(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: true,
                    syncSummary: networkCurrent
                )
            )
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: false,
                    syncSummary: networkCurrent
                )
            )
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: true,
                    syncSummary: .unavailable
                )
            )
        }
    }

    func testAuthorityAdmissionAllowsHeaderLagInsideTheCurrentTreeEpoch() {
        let lagging = publicAuthoritySummary(
            status: "syncing",
            bestHeight: 339_305,
            targetHeight: 339_308
        )

        XCTAssertTrue(lagging.hasAuthoritativeTreeRoot)
        XCTAssertFalse(lagging.hasAuthoritativeCurrentness)
        XCTAssertTrue(
            BrowserAuthorityAdmissionPolicy().allowsProxyResume(
                network: .mainnet,
                isForeground: true,
                syncSummary: lagging
            )
        )
    }

    func testAuthorityAdmissionRejectsStaleGenesisAndLegacyPublicNetworkState() {
        let policy = BrowserAuthorityAdmissionPolicy()
        for network in [BrowserHandshakeNetwork.mainnet, .testnet] {
            let summaries = [
                BrowserSyncSummary(
                    headline: "Stale",
                    detail: "Stale",
                    syncStatusSchemaVersion: 3,
                    network: network.rawValue,
                    bestHeight: 339_000,
                    effectiveTargetHeight: 339_308,
                    lagBlocks: 308,
                    freshness: "stale",
                    freshnessThresholdBlocks: 2,
                    treeIntervalBlocks: 36,
                    authoritativeTreeRootHeight: 339_301,
                    localTreeRootHeight: 338_977,
                    treeRootReady: false,
                    blocksUntilAuthoritativeTreeRoot: 301,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                ),
                BrowserSyncSummary(
                    headline: "Genesis",
                    detail: "Genesis",
                    syncStatusSchemaVersion: 3,
                    network: network.rawValue,
                    bestHeight: 0,
                    effectiveTargetHeight: 0,
                    lagBlocks: 0,
                    freshness: "current",
                    freshnessThresholdBlocks: 2,
                    treeIntervalBlocks: 36,
                    treeRootReady: false,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                ),
                BrowserSyncSummary(
                    headline: "Legacy",
                    detail: "Legacy",
                    network: network.rawValue,
                    bestHeight: 339_308,
                    freshness: "current"
                ),
            ]
            for summary in summaries {
                XCTAssertFalse(
                    policy.allowsProxyResume(
                        network: network,
                        isForeground: true,
                        syncSummary: summary
                    )
                )
            }
        }
    }

    func testRegtestAuthorityAdmissionMatchesRustReadinessPrerequisites() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let nonGenesis = regtestAuthoritySummary()
        let stale = regtestAuthoritySummary(
            status: "syncing",
            treeRootReady: false,
            blocksUntilAuthority: 5
        )
        let genesis = regtestAuthoritySummary(
            status: "syncing",
            bestHeight: 0,
            treeRootReady: false
        )

        XCTAssertTrue(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: nonGenesis
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: false,
                syncSummary: nonGenesis
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: stale
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: genesis
            )
        )
    }

    func testAuthorityAdmissionRejectsWrongNetworkMissingSchemaAndErrors() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let wrongNetwork = publicAuthoritySummary(bestHeight: 339_308)
        let missingSchema = BrowserSyncSummary(
            headline: "Ready",
            detail: "Ready",
            status: "up_to_date",
            network: "regtest",
            bestHeight: 1,
            freshness: "unknown"
        )
        let failed = regtestAuthoritySummary(status: "error", error: "sync failed")

        for summary in [wrongNetwork, missingSchema, failed] {
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: .regtest,
                    isForeground: true,
                    syncSummary: summary
                )
            )
        }
    }

    func testAuthorityAdmissionReconciliationIsBidirectionalAndIdempotent() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let ready = regtestAuthoritySummary()
        let stale = regtestAuthoritySummary(
            status: "syncing",
            treeRootReady: false,
            blocksUntilAuthority: 5
        )

        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: ready,
                isAdmissionGranted: false
            ),
            .resume
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: ready,
                isAdmissionGranted: true
            ),
            .unchanged
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: stale,
                isAdmissionGranted: true
            ),
            .suspend
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: stale,
                isAdmissionGranted: false
            ),
            .unchanged
        )
    }

    func testSyncSummaryRejectsMalformedUnsignedNumbers() throws {
        let malformedValues: [NSNumber] = [
            NSNumber(value: -1),
            NSNumber(value: 1.5),
            NSNumber(value: true),
            NSDecimalNumber(string: "18446744073709551616"),
        ]

        for malformed in malformedValues {
            let summary = try RustBrowserRuntime.syncSummary(from: [
                "syncStatusSchemaVersion": 3,
                "status": "up_to_date",
                "bestHeight": malformed,
                "effectiveTargetHeight": 339_308,
                "lagBlocks": 0,
                "freshness": "current",
                "freshnessThresholdBlocks": 2,
                "treeIntervalBlocks": 36,
                "authoritativeTreeRootHeight": 339_301,
                "localTreeRootHeight": 339_301,
                "treeRootReady": true,
                "blocksUntilAuthoritativeTreeRoot": 0,
                "targetSource": "corroboratedPeers",
                "targetPeerGroups": 3,
                "targetEvidenceExpired": false,
            ])
            XCTAssertNil(summary.bestHeight)
            XCTAssertFalse(summary.isCaughtUp)
        }
    }

    func testNativeSyncSummaryPreservesUsefulRuntimeResults() throws {
        let summary = try RustBrowserRuntime.syncSummary(from: [
            "syncStatusSchemaVersion": 3,
            "network": "mainnet",
            "status": "up_to_date",
            "attempted": 4,
            "successful": 3,
            "accepted": 2,
            "syncInFlight": true,
            "stagedBestHeight": 251_024,
            "stagedAccepted": 1_024,
            "failed": 1,
            "peerCount": 8,
            "peerGroups": 3,
            "bestHeight": 250_000,
            "bestPeerHeight": 250_000,
            "estimatedTipHeight": 250_000,
            "effectiveTargetHeight": 250_000,
            "lagBlocks": 0,
            "freshness": "current",
            "freshnessThresholdBlocks": 2,
            "treeIntervalBlocks": 36,
            "authoritativeTreeRootHeight": 249_985,
            "localTreeRootHeight": 249_985,
            "treeRootReady": true,
            "blocksUntilAuthoritativeTreeRoot": 0,
            "targetSource": "corroboratedPeers",
            "targetPeerGroups": 3,
            "targetEvidenceExpired": false,
            "resourceCacheEntries": 14,
            "resourceCacheBytes": 4_096,
            "resourceCacheEvicted": 2,
            "error": NSNull(),
            "failures": [],
        ])

        XCTAssertEqual(summary.network, "mainnet")
        XCTAssertEqual(summary.syncStatusSchemaVersion, 3)
        XCTAssertEqual(summary.status, "up_to_date")
        XCTAssertEqual(summary.attempted, 4)
        XCTAssertEqual(summary.successful, 3)
        XCTAssertEqual(summary.accepted, 2)
        XCTAssertTrue(summary.syncInFlight)
        XCTAssertEqual(summary.stagedBestHeight, 251_024)
        XCTAssertEqual(summary.stagedAccepted, 1_024)
        XCTAssertEqual(summary.failed, 1)
        XCTAssertEqual(summary.peerCount, 8)
        XCTAssertEqual(summary.peerGroups, 3)
        XCTAssertEqual(summary.bestHeight, 250_000)
        XCTAssertEqual(summary.bestPeerHeight, 250_000)
        XCTAssertEqual(summary.estimatedTipHeight, 250_000)
        XCTAssertEqual(summary.effectiveTargetHeight, 250_000)
        XCTAssertEqual(summary.lagBlocks, 0)
        XCTAssertEqual(summary.freshness, "current")
        XCTAssertEqual(summary.freshnessThresholdBlocks, 2)
        XCTAssertEqual(summary.targetSource, "corroboratedPeers")
        XCTAssertEqual(summary.targetPeerGroups, 3)
        XCTAssertFalse(summary.targetEvidenceExpired)
        XCTAssertEqual(summary.resourceCacheEntries, 14)
        XCTAssertEqual(summary.resourceCacheBytes, 4_096)
        XCTAssertEqual(summary.resourceCacheEvicted, 2)
        XCTAssertFalse(summary.requiresRetry)
    }

    func testNativeProofDetailsRemainViewableAndExportable() throws {
        let details = try RustBrowserRuntime.proofDetails(
            from: [
                "host": "alice",
                "name": "alice",
                "network": "mainnet",
                "nameHash": "001122",
                "hnsProof": "verified",
                "proofStatus": "verified",
                "secure": true,
                "exists": true,
                "treeRoot": "aabbcc",
                "blockHeight": 250_000,
                "cacheStatus": "anchored_to_current_tip",
                "resourceValueHex": "00",
                "recordTypes": ["A", "TLSA"],
                "resourceRecords": [],
                "currentTip": ["height": 250_000],
                "error": NSNull(),
            ],
            fallbackHost: "fallback"
        )

        XCTAssertEqual(details.headline, "Handshake proof verified")
        XCTAssertEqual(details.host, "alice")
        XCTAssertEqual(details.proofStatus, "verified")
        XCTAssertEqual(details.secure, true)
        XCTAssertEqual(details.exists, true)
        XCTAssertEqual(details.blockHeight, 250_000)
        XCTAssertEqual(details.recordTypes, ["A", "TLSA"])
        XCTAssertTrue(details.formattedJSON.contains("\"proofStatus\" : \"verified\""))
    }

    func testDomainSetupReportUsesProofAndDecodedRecordEvidence() {
        let details = BrowserProofDetails(
            headline: "Handshake proof verified",
            detail: "Verified resource proof",
            host: "www.alice",
            name: "alice",
            network: "mainnet",
            nameHash: "001122",
            hnsProof: "verified",
            proofStatus: "verified",
            secure: true,
            exists: true,
            treeRoot: "aabbcc",
            blockHeight: 250_000,
            cacheStatus: "anchored_to_current_tip",
            recordTypes: ["A", "DS"],
            error: nil,
            formattedJSON: """
            {
              "resourceRecords": [
                {"name":"alice.","type":"A","class":1,"ttl":300,"rdataHex":"7f000001"}
              ]
            }
            """
        )

        let report = BrowserDiagnosticReports.domainSetup(details)

        XCTAssertTrue(report.contains("Host: www.alice"))
        XCTAssertTrue(report.contains("The proof contains usable delegation or address data."))
        XCTAssertTrue(report.contains("A alice. 7f000001"))
    }

    func testTLSADANEReportUsesCurrentResolutionTraceEvidence() {
        let trace = """
        {
          "host": "www.alice",
          "url": "https://www.alice/",
          "tls": {
            "mode": "dane",
            "tlsaOwner": "_443._tcp.www.alice",
            "tlsaStatus": "present",
            "tlsaFound": true,
            "dnssecSecure": true,
            "tlsaSource": "native_tlsa",
            "records": [{
              "usage": "DANE-EE",
              "selector": "SPKI",
              "matching": "SHA-256",
              "associationDataHex": "aabb"
            }],
            "certificate": {
              "webPkiStatus": "invalid",
              "endEntitySha256": "1122",
              "spkiSha256": "3344",
              "spkiDerHex": "5566",
              "intermediateCount": 1
            },
            "dane": {
              "decision": "verified",
              "matchedUsage": "DANE-EE",
              "certificateMatch": "pass",
              "webPkiFallback": false
            }
          }
        }
        """

        let report = BrowserDiagnosticReports.tlsaDANE(
            url: "https://www.alice/",
            traceJSON: trace
        )

        XCTAssertTrue(report.contains("TLSA owner: _443._tcp.www.alice"))
        XCTAssertTrue(report.contains("DANE decision: verified"))
        XCTAssertTrue(report.contains("TLSA found: true"))
        XCTAssertTrue(report.contains("WebPKI fallback: false"))
        XCTAssertTrue(report.contains("Usage: DANE-EE"))
        XCTAssertTrue(report.contains("Association data: aabb"))
    }

    func testTLSADANEReportRequiresCurrentTrace() {
        XCTAssertEqual(
            BrowserDiagnosticReports.tlsaDANE(url: "https://www.alice/", traceJSON: nil),
            "No TLSA/DANE resolution trace is available for the current page."
        )
    }

    @MainActor
    func testNetworkSwitchCommitsOnlyAfterReplacementIsReady() async throws {
        let factory = NetworkSwitchRuntimeFactory()
        let targetCreationStarted = expectation(description: "target runtime creation started")
        let allowTargetCreation = DispatchSemaphore(value: 0)
        factory.blockedNetwork = .regtest
        factory.onBlockedCreation = { targetCreationStarted.fulfill() }
        factory.creationGate = allowTargetCreation

        var persistedNetworks: [BrowserHandshakeNetwork] = []
        let process = BrowserProcess(
            runtimeFactory: factory.makeRuntime,
            initialNetwork: .testnet,
            persistNetwork: { persistedNetworks.append($0) }
        )
        defer { process.close() }

        let preparationCompleted = expectation(description: "initial runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        let previousEnvironment = try XCTUnwrap(preparationResult).get()
        let previousRuntime = try XCTUnwrap(
            previousEnvironment.runtime as? NetworkSwitchRuntimeStub
        )
        let previousRuntimeClosed = expectation(
            description: "previous runtime closed after commit"
        )
        previousRuntime.onClose = { previousRuntimeClosed.fulfill() }

        let switchCompleted = expectation(description: "network switch completed")
        var switchResult: Result<BrowserProcess.Environment, Error>?
        process.switchNetwork(to: .regtest) {
            switchResult = $0
            switchCompleted.fulfill()
        }

        await fulfillment(of: [targetCreationStarted], timeout: 2)
        XCTAssertEqual(process.currentNetwork, .testnet)
        XCTAssertTrue(persistedNetworks.isEmpty)
        XCTAssertFalse(previousRuntime.isClosed)

        allowTargetCreation.signal()
        await fulfillment(of: [switchCompleted, previousRuntimeClosed], timeout: 2)
        let replacementEnvironment = try XCTUnwrap(switchResult).get()
        XCTAssertFalse(replacementEnvironment.runtime === previousEnvironment.runtime)
        XCTAssertEqual(process.currentNetwork, .regtest)
        XCTAssertEqual(persistedNetworks, [.regtest])
        XCTAssertTrue(previousRuntime.isClosed)
    }

    @MainActor
    func testFailedNetworkSwitchPreservesReadyRuntimeAndDoesNotPersist() async throws {
        let factory = NetworkSwitchRuntimeFactory()
        factory.policyFailureNetwork = .regtest
        var persistedNetworks: [BrowserHandshakeNetwork] = []
        let process = BrowserProcess(
            runtimeFactory: factory.makeRuntime,
            initialNetwork: .testnet,
            persistNetwork: { persistedNetworks.append($0) }
        )
        defer { process.close() }

        let preparationCompleted = expectation(description: "initial runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        let previousEnvironment = try XCTUnwrap(preparationResult).get()
        let previousRuntime = try XCTUnwrap(
            previousEnvironment.runtime as? NetworkSwitchRuntimeStub
        )

        let switchCompleted = expectation(description: "failed network switch completed")
        var switchResult: Result<BrowserProcess.Environment, Error>?
        process.switchNetwork(to: .regtest) {
            switchResult = $0
            switchCompleted.fulfill()
        }
        await fulfillment(of: [switchCompleted], timeout: 2)

        XCTAssertThrowsError(try XCTUnwrap(switchResult).get())
        XCTAssertEqual(process.currentNetwork, .testnet)
        XCTAssertTrue(persistedNetworks.isEmpty)
        XCTAssertFalse(previousRuntime.isClosed)
        XCTAssertTrue(try XCTUnwrap(factory.lastRuntime(for: .regtest)).isClosed)

        let reuseCompleted = expectation(description: "previous environment remains ready")
        var reusedEnvironment: BrowserProcess.Environment?
        process.switchNetwork(to: .testnet) { result in
            reusedEnvironment = try? result.get()
            reuseCompleted.fulfill()
        }
        await fulfillment(of: [reuseCompleted], timeout: 2)
        XCTAssertTrue(reusedEnvironment?.runtime === previousEnvironment.runtime)
    }

    @MainActor
    func testPreparedEnvironmentOwnsOneProcessProxyCoordinator() async throws {
        let factory = NetworkSwitchRuntimeFactory()
        let process = BrowserProcess(
            runtimeFactory: factory.makeRuntime,
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        defer { process.close() }

        let prepared = expectation(description: "runtime prepared")
        var result: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            result = $0
            prepared.fulfill()
        }
        await fulfillment(of: [prepared], timeout: 2)
        let environment = try XCTUnwrap(result).get()

        let first = environment.proxyCoordinator()
        let second = environment.proxyCoordinator()
        XCTAssertTrue(first === second)

        environment.revokeProxyCoordinator()
        let replacement = environment.proxyCoordinator()
        XCTAssertFalse(first === replacement)
    }

    @MainActor
    func testSyncMaintenanceSafePointRunsImmediatelyWhenIdle() {
        let process = BrowserProcess(
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        defer { process.close() }
        var callbackCount = 0

        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            }
        )
        XCTAssertEqual(callbackCount, 1)
    }

    @MainActor
    func testSyncMaintenanceSafePointsWaitForSyncAndDrainOnceInOrder() async throws {
        let runtime = NetworkSwitchRuntimeStub(network: .testnet, rejectsPolicy: false)
        let syncStarted = expectation(description: "sync entered runtime")
        let allowSyncToFinish = DispatchSemaphore(value: 0)
        runtime.onSyncStart = { syncStarted.fulfill() }
        runtime.syncGate = allowSyncToFinish
        let process = BrowserProcess(
            runtimeFactory: { _, _ in runtime },
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        defer {
            allowSyncToFinish.signal()
            process.close()
        }

        let preparationCompleted = expectation(description: "runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        _ = try XCTUnwrap(preparationResult).get()

        let syncCompleted = expectation(description: "sync completed")
        process.syncNow { _ in syncCompleted.fulfill() }
        await fulfillment(of: [syncStarted], timeout: 2)

        var callbackOrder: [Int] = []
        let firstSafePoint = expectation(description: "first safe point")
        let secondSafePoint = expectation(description: "second safe point")
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                XCTAssertFalse(runtime.isSyncRunning)
                callbackOrder.append(1)
                firstSafePoint.fulfill()
            }
        )
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                XCTAssertFalse(runtime.isSyncRunning)
                callbackOrder.append(2)
                secondSafePoint.fulfill()
            }
        )
        XCTAssertTrue(callbackOrder.isEmpty)

        allowSyncToFinish.signal()
        await fulfillment(
            of: [firstSafePoint, secondSafePoint, syncCompleted],
            timeout: 2
        )
        XCTAssertEqual(callbackOrder, [1, 2])
    }

    @MainActor
    func testClosingProcessDropsQueuedSyncMaintenanceSafePoint() async throws {
        let hostedTimeout = 10.0
        let runtime = NetworkSwitchRuntimeStub(network: .testnet, rejectsPolicy: false)
        let syncStarted = expectation(description: "phase 2: sync entered runtime")
        let syncCompleted = expectation(
            description: "phase 3: close completed pending sync"
        )
        let runtimeClosed = expectation(
            description: "phase 4: runtime closed after sync release"
        )
        let allowSyncToFinish = DispatchSemaphore(value: 0)
        runtime.onSyncStart = { syncStarted.fulfill() }
        runtime.syncGate = allowSyncToFinish
        runtime.onClose = { runtimeClosed.fulfill() }
        let process = BrowserProcess(
            runtimeFactory: { _, _ in runtime },
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        var processClosed = false
        var syncGateReleased = false
        defer {
            if !processClosed {
                process.close()
                processClosed = true
            }
            if !syncGateReleased {
                allowSyncToFinish.signal()
                syncGateReleased = true
            }
        }

        let preparationCompleted = expectation(
            description: "phase 1: runtime prepared"
        )
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: hostedTimeout)
        _ = try XCTUnwrap(
            preparationResult,
            "phase 1: preparation did not provide its exact result"
        ).get()

        var syncResult: Result<BrowserSyncSummary, Error>?
        var syncCompletionCount = 0
        process.syncNow {
            syncResult = $0
            syncCompletionCount += 1
            syncCompleted.fulfill()
        }
        await fulfillment(of: [syncStarted], timeout: hostedTimeout)
        var callbackCount = 0
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            },
            "phase 2: the maintenance callback must queue while sync is gated"
        )
        XCTAssertEqual(
            callbackCount,
            0,
            "phase 2: the queued maintenance callback ran before process closure"
        )

        process.close()
        processClosed = true
        await fulfillment(of: [syncCompleted], timeout: hostedTimeout)
        let completedSyncResult = try XCTUnwrap(
            syncResult,
            "phase 3: sync completion did not provide its exact result"
        )
        guard case .failure(let syncError) = completedSyncResult else {
            XCTFail("phase 3: closing must fail the pending sync completion")
            return
        }
        XCTAssertEqual(
            syncError as? BrowserCoreError,
            .runtimeUnavailable("process is closed"),
            "phase 3: pending sync completed with the wrong closure error"
        )
        XCTAssertEqual(
            syncCompletionCount,
            1,
            "phase 3: pending sync completion must run exactly once"
        )
        XCTAssertFalse(
            runtime.isClosed,
            "phase 3: the gated runtime closed before sync was released"
        )
        XCTAssertEqual(
            callbackCount,
            0,
            "phase 3: discarded maintenance work ran during process close"
        )

        allowSyncToFinish.signal()
        syncGateReleased = true
        await fulfillment(of: [runtimeClosed], timeout: hostedTimeout)
        XCTAssertTrue(
            runtime.isClosed,
            "phase 4: close completion fired without closing the runtime"
        )
        XCTAssertEqual(
            callbackCount,
            0,
            "phase 4: a discarded maintenance callback ran after sync returned"
        )
        XCTAssertEqual(
            syncCompletionCount,
            1,
            "phase 4: the completed sync callback ran more than once"
        )
        XCTAssertFalse(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            },
            "phase 4: a closed process accepted maintenance work"
        )
        XCTAssertEqual(
            callbackCount,
            0,
            "phase 4: rejected maintenance work executed its callback"
        )
    }
}

@MainActor
private final class ReloadCountingTableView: UITableView {
    private(set) var reloadDataCallCount = 0

    override func reloadData() {
        reloadDataCallCount += 1
        super.reloadData()
    }
}

@MainActor
private final class BrowserSettingsDelegateSpy: BrowserSettingsViewControllerDelegate {
    private(set) var actions: [BrowserSettingsViewController.Action] = []

    func browserSettingsViewController(
        _ controller: BrowserSettingsViewController,
        didRequest action: BrowserSettingsViewController.Action
    ) {
        actions.append(action)
    }
}

private enum NetworkSwitchRuntimeTestError: Error {
    case rejectedPolicy
}

private final class NetworkSwitchRuntimeFactory {
    var blockedNetwork: BrowserHandshakeNetwork?
    var onBlockedCreation: (() -> Void)?
    var creationGate: DispatchSemaphore?
    var policyFailureNetwork: BrowserHandshakeNetwork?

    private let lock = NSLock()
    private var runtimes: [BrowserHandshakeNetwork: [NetworkSwitchRuntimeStub]] = [:]

    func makeRuntime(
        dataDirectory: String,
        network: BrowserHandshakeNetwork
    ) throws -> BrowserRuntime {
        _ = dataDirectory
        if network == blockedNetwork {
            onBlockedCreation?()
            creationGate?.wait()
        }
        let runtime = NetworkSwitchRuntimeStub(
            network: network,
            rejectsPolicy: network == policyFailureNetwork
        )
        lock.lock()
        runtimes[network, default: []].append(runtime)
        lock.unlock()
        return runtime
    }

    func lastRuntime(for network: BrowserHandshakeNetwork) -> NetworkSwitchRuntimeStub? {
        lock.lock()
        defer { lock.unlock() }
        return runtimes[network]?.last
    }
}

private final class NetworkSwitchRuntimeStub: BrowserRuntime {
    let network: BrowserHandshakeNetwork
    let rejectsPolicy: Bool
    var onClose: (() -> Void)?
    var onSyncStart: (() -> Void)?
    var syncGate: DispatchSemaphore?

    private let lock = NSLock()
    private var closed = false
    private var syncRunning = false

    init(network: BrowserHandshakeNetwork, rejectsPolicy: Bool) {
        self.network = network
        self.rejectsPolicy = rejectsPolicy
    }

    var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    var isSyncRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return syncRunning
    }

    func classifyNavigation(_ rawValue: String) throws -> BrowserDestination {
        throw BrowserCoreError.invalidAddress("unused network-switch test stub")
    }

    func classifyHost(_ host: String) -> BrowserHostKind { .handshake }

    func canonicalHost(_ host: String) -> String? { host.lowercased() }

    func startWholeWebKitProxy(hnsScopeRoot: String?) throws -> BrowserProxySession {
        throw BrowserCoreError.proxyStartFailed("unused network-switch test stub")
    }

    func installHeaderSnapshot(at path: String) throws {}

    func updatePolicy(_ policy: BrowserRuntimePolicy) throws -> UInt64 {
        if rejectsPolicy { throw NetworkSwitchRuntimeTestError.rejectedPolicy }
        return 1
    }

    func syncOnce() throws -> BrowserSyncSummary {
        lock.lock()
        syncRunning = true
        lock.unlock()
        defer {
            lock.lock()
            syncRunning = false
            lock.unlock()
        }
        onSyncStart?()
        syncGate?.wait()
        return syncSummary()
    }

    func syncSummary() -> BrowserSyncSummary {
        BrowserSyncSummary(
            headline: "\(network.title) ready",
            detail: "transactional network-switch test runtime",
            status: "idle",
            network: network.rawValue
        )
    }

    func clearResolverCache() throws -> BrowserSyncSummary { syncSummary() }

    func proofDetails(for hostOrURL: String) throws -> BrowserProofDetails {
        throw BrowserCoreError.runtimeUnavailable("unused network-switch test stub")
    }

    func close() {
        let callback: (() -> Void)?
        lock.lock()
        if closed {
            callback = nil
        } else {
            closed = true
            callback = onClose
        }
        lock.unlock()
        callback?()
    }
}
