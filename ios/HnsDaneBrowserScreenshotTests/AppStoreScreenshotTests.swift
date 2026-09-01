import Foundation
import XCTest

/// Captures App Store submission candidates from the unmodified shipping
/// runtime. This test deliberately does not set HNS_APP_STORE_SCREENSHOT_SCENE.
/// All four images are captured in one test so Proof Details is guaranteed to
/// describe the same live HNS navigation shown in the first image.
final class LiveAppStoreScreenshotTests: XCTestCase {
    private static let hnsURL = "https://shakescape/"
    private static let webPKIURL = "https://shakescape.com/"
    private static let retryableDualRootSecurityLabel =
        "The Rust proxy rejected the dual-root response · Dual-root validation failed"
    private static let retryableMissingStatusSecurityLabel =
        "No exact Rust proxy security result was available"
    private static let retryableICANNSecurityLabels = [
        retryableDualRootSecurityLabel,
        retryableMissingStatusSecurityLabel,
    ]
    private static let retryableICANNObservationSeconds: TimeInterval = 1.25

    private enum SubmissionSecurityExpectation {
        case hnsDANE
        case icannAuthenticated

        var description: String {
            switch self {
            case .hnsDANE: "a DANE-verified HNS response"
            case .icannAuthenticated: "an automatic ICANN DANE or validated WebPKI response"
            }
        }

        func matches(_ label: String) -> Bool {
            switch self {
            case .hnsDANE:
                let prefix = "DANE verified · "
                return label.hasPrefix(prefix)
                    && !String(label.dropFirst(prefix.count)).trimmingCharacters(
                        in: .whitespacesAndNewlines
                    ).isEmpty
            case .icannAuthenticated:
                return [
                    "DANE verified · ",
                    "WebPKI verified · no secure TLSA ",
                ].contains { prefix in
                    label.hasPrefix(prefix)
                        && !String(label.dropFirst(prefix.count)).trimmingCharacters(
                            in: .whitespacesAndNewlines
                        ).isEmpty
                }
            }
        }
    }

    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
        app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL",
        ]
    }

    override func tearDown() {
        app?.terminate()
        app = nil
        super.tearDown()
    }

    func testLiveSubmissionScreenshots() throws {
        let currentRuntimeStatus = launchShippingRuntime(requireCurrentHeaders: true)
        var hnsEvidence = try navigateAndWait(
            to: Self.hnsURL,
            expectedHost: "shakescape",
            expectedSecurity: .hnsDANE,
            timeout: 180
        )
        hnsEvidence["runtimeStatusBeforeNavigation"] = currentRuntimeStatus
        capture(named: "LIVE_APPSTORE_SCREENSHOT_01_HNS_PAGE")

        let settingsEvidence = openSettings(timeout: 20)
        capture(named: "LIVE_APPSTORE_SCREENSHOT_02_SETTINGS")

        let proofEvidence = try openProofDetails(timeout: 60)
        capture(named: "LIVE_APPSTORE_SCREENSHOT_03_PROOF_DETAILS")

        dismissProofDetailsAndSettings(timeout: 20)
        let webPKIEvidence = try navigateAndWait(
            to: Self.webPKIURL,
            expectedHost: "shakescape.com",
            expectedSecurity: .icannAuthenticated,
            timeout: 90,
            allowBoundedICANNRetry: true,
            expectedAddressOnFocus: Self.hnsURL
        )
        capture(named: "LIVE_APPSTORE_SCREENSHOT_04_WEBPKI")

        try attachProvenance(
            hnsEvidence: hnsEvidence,
            settingsEvidence: settingsEvidence,
            proofEvidence: proofEvidence,
            webPKIEvidence: webPKIEvidence
        )
    }

    @discardableResult
    private func launchShippingRuntime(requireCurrentHeaders: Bool) -> String {
        // A Release test run excludes the fixture implementation at compile
        // time. Removing this inherited value also prevents a caller's shell
        // from accidentally requesting a fixture scene.
        app.launchEnvironment.removeValue(forKey: "HNS_APP_STORE_SCREENSHOT_SCENE")
        app.launch()

        let address = app.textFields["app-store-screenshot.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 20), "Address field did not appear")

        let sync = app.staticTexts["app-store-screenshot.sync"]
        let readinessTimeout: TimeInterval = requireCurrentHeaders ? 1_200 : 120
        var lastRuntimeStatus = ""
        XCTAssertTrue(
            waitUntil(
                description: requireCurrentHeaders
                    ? "current Handshake headers"
                    : "shipping runtime readiness",
                timeout: readinessTimeout,
                timeoutEvidence: { " Last runtime status: \(lastRuntimeStatus)" },
                condition: {
                    // Shipping UI intentionally collapses the diagnostic row
                    // only when committed headers are current and no sync is
                    // active. The hidden row is therefore the ready signal.
                    guard sync.exists else {
                        lastRuntimeStatus = "Handshake headers current (diagnostic row hidden)"
                        return true
                    }
                    let label = sync.label.trimmingCharacters(in: .whitespacesAndNewlines)
                    if label != lastRuntimeStatus {
                        lastRuntimeStatus = label
                        print("Live screenshot runtime status: \(label)")
                    }
                    if requireCurrentHeaders {
                        return false
                    }
                    return !label.isEmpty && label != "Preparing runtime"
                }
            )
        )
        return lastRuntimeStatus
    }

    private func navigateAndWait(
        to requestedURL: String,
        expectedHost: String,
        expectedSecurity: SubmissionSecurityExpectation,
        timeout: TimeInterval,
        allowBoundedICANNRetry: Bool = false,
        expectedAddressOnFocus: String? = nil
    ) throws -> [String: Any] {
        let address = app.textFields["app-store-screenshot.address"]
        let expectedIdleAddress = try XCTUnwrap(
            idleAddress(for: requestedURL),
            "Requested screenshot URL could not be converted to idle omnibox text"
        )
        address.tap()
        if let expectedAddressOnFocus {
            XCTAssertEqual(
                address.value as? String,
                expectedAddressOnFocus,
                "Focusing the omnibox did not restore its exact canonical URL"
            )
        }

        clearAddressField(address)
        XCTAssertEqual(
            addressText(in: address),
            "",
            "Address field did not clear before entering the requested URL"
        )

        address.typeText(requestedURL)
        XCTAssertEqual(
            address.value as? String,
            requestedURL,
            "Address field did not contain the exact requested URL before submission"
        )
        address.typeText(XCUIKeyboardKey.return.rawValue)

        XCTAssertTrue(
            waitUntil(
                description: "address update for \(expectedHost)",
                timeout: timeout,
                condition: {
                    guard let value = address.value as? String,
                          value == expectedIdleAddress else {
                        return false
                    }
                    let candidate = value.contains("://")
                        ? value
                        : "https://\(value)"
                    guard let components = URLComponents(string: candidate),
                          components.scheme?.caseInsensitiveCompare("https") == .orderedSame,
                          components.host?.caseInsensitiveCompare(expectedHost) == .orderedSame,
                          components.user == nil,
                          components.password == nil else {
                        return false
                    }
                    return true
                }
            )
        )

        let webView = app.webViews.firstMatch
        XCTAssertTrue(
            waitUntil(
                description: "rendered page for \(expectedHost)",
                timeout: timeout,
                condition: {
                    webView.exists
                        && webView.descendants(matching: .staticText).firstMatch.exists
                        && self.app.buttons["Reload"].exists
                }
            )
        )

        let security = app.staticTexts["app-store-screenshot.security"]
        XCTAssertTrue(security.waitForExistence(timeout: 10), "Security status did not appear")
        var lastSecurityLabel = ""
        var retryableFailureObservedAt: TimeInterval?
        XCTAssertTrue(
            waitUntil(
                description: expectedSecurity.description,
                timeout: timeout,
                timeoutEvidence: { " Last security label: \(lastSecurityLabel)" },
                condition: {
                    let label = security.label.trimmingCharacters(in: .whitespacesAndNewlines)
                    lastSecurityLabel = label
                    if expectedSecurity.matches(label) {
                        return true
                    }
                    guard allowBoundedICANNRetry,
                          Self.retryableICANNSecurityLabels.contains(label) else {
                        retryableFailureObservedAt = nil
                        return false
                    }
                    let now = ProcessInfo.processInfo.systemUptime
                    guard let observedAt = retryableFailureObservedAt else {
                        retryableFailureObservedAt = now
                        return false
                    }
                    return now - observedAt >= Self.retryableICANNObservationSeconds
                }
            )
        )

        var navigationAttemptCount = 1
        var retryReason: String?
        if !expectedSecurity.matches(lastSecurityLabel) {
            XCTAssertTrue(
                Self.retryableICANNSecurityLabels.contains(lastSecurityLabel),
                "Only an exact bounded ICANN recovery result may be retried"
            )
            let reload = app.buttons["Reload"].firstMatch
            XCTAssertTrue(
                waitUntil(
                    description: "hittable Reload control for bounded ICANN recovery",
                    timeout: 10,
                    condition: {
                        reload.exists && reload.isHittable
                    }
                )
            )
            retryReason = lastSecurityLabel
            navigationAttemptCount = 2
            print("Retrying the exact ICANN recovery result once from origin.")
            reload.tap()

            lastSecurityLabel = ""
            XCTAssertTrue(
                waitUntil(
                    description: expectedSecurity.description
                        + " after one bounded Reload",
                    timeout: timeout,
                    timeoutEvidence: { " Last security label: \(lastSecurityLabel)" },
                    condition: {
                        let label = security.label.trimmingCharacters(
                            in: .whitespacesAndNewlines
                        )
                        lastSecurityLabel = label
                        return expectedSecurity.matches(label)
                    }
                )
            )
        }
        assertNoNavigationAlert()
        let finalSecurityLabel = security.label.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        XCTAssertTrue(
            expectedSecurity.matches(finalSecurityLabel),
            "Final security label did not prove the required trust result"
        )

        var evidence: [String: Any] = [
            "requestedURL": requestedURL,
            "finalAddress": requestedURL,
            "finalDisplayedAddress": (address.value as? String) ?? "",
            "navigationAttemptCount": navigationAttemptCount,
            // This is evidence, not an assertion. HNS may honestly report DANE,
            // fallback, insecure, or blocked depending on the live response.
            "securityLabel": finalSecurityLabel,
        ]
        evidence["retryReason"] = retryReason.map { $0 as Any } ?? NSNull()
        return evidence
    }

    private func clearAddressField(_ address: XCUIElement) {
        let currentAddress = addressText(in: address)
        guard !currentAddress.isEmpty else { return }

        let nestedClearButton = address.buttons["Clear text"]
        if nestedClearButton.waitForExistence(timeout: 3) {
            nestedClearButton.tap()
            return
        }

        let globalClearButton = app.buttons["Clear text"]
        if globalClearButton.waitForExistence(timeout: 2) {
            globalClearButton.tap()
            return
        }

        let deletes = String(
            repeating: XCUIKeyboardKey.delete.rawValue,
            count: currentAddress.count
        )
        address.typeText(deletes)
    }

    private func addressText(in address: XCUIElement) -> String {
        guard let value = address.value as? String else { return "" }
        return value == address.placeholderValue ? "" : value
    }

    private func idleAddress(for canonicalAddress: String) -> String? {
        guard let components = URLComponents(string: canonicalAddress),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host?.lowercased() else {
            return nil
        }
        let defaultPort = scheme == "https" ? 443 : 80
        let port = components.port.flatMap { value in
            value == defaultPort ? nil : ":\(value)"
        } ?? ""
        let path = components.percentEncodedPath == "/"
            ? ""
            : components.percentEncodedPath
        let query = components.percentEncodedQuery.map { "?\($0)" } ?? ""
        let fragment = components.percentEncodedFragment.map { "#\($0)" } ?? ""
        return host + port + path + query + fragment
    }

    private func openSettings(
        timeout: TimeInterval
    ) -> [String: Any] {
        let controls = app.buttons["app-store-screenshot.controls"]
        XCTAssertTrue(controls.waitForExistence(timeout: 10), "Settings control did not appear")
        controls.tap()

        let table = app.tables["settings.table"]
        XCTAssertTrue(table.waitForExistence(timeout: 10), "Settings table did not appear")
        let handshakeRow = table.cells["settings.destination.handshake"]
        let walletRowIdentifier = "settings.destination.wallet"
        let walletRow = table.cells[walletRowIdentifier]
        XCTAssertTrue(
            handshakeRow.waitForExistence(timeout: timeout),
            "Handshake settings destination did not appear"
        )
        XCTAssertTrue(
            walletRow.waitForExistence(timeout: timeout),
            "Wallet settings destination did not appear"
        )
        let walletTitle = walletRow.staticTexts["Wallet"]
        XCTAssertTrue(
            walletTitle.waitForExistence(timeout: timeout),
            "Visible native wallet row did not expose its shipping title"
        )
        let walletRowLabel = walletTitle.label.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        XCTAssertEqual(walletRowLabel, "Wallet")

        handshakeRow.tap()
        let statelessRow = table.cells[
            "settings.handshake.stateless-dane-certificates"
        ]
        let statelessToggle = app.switches[
            "settings.handshake.stateless-dane-certificates.toggle"
        ]
        XCTAssertTrue(
            statelessRow.waitForExistence(timeout: timeout),
            "Handshake validation settings did not become visible"
        )
        XCTAssertTrue(
            statelessToggle.waitForExistence(timeout: timeout),
            "Stateless DANE toggle did not appear"
        )
        XCTAssertTrue(
            app.navigationBars["Handshake"].buttons["Settings"].waitForExistence(timeout: timeout),
            "Settings back button did not appear"
        )
        app.navigationBars["Handshake"].buttons["Settings"].tap()
        XCTAssertTrue(walletRow.waitForExistence(timeout: timeout), "Settings root did not return")
        assertNoNavigationAlert()
        return [
            "nativeWalletRowIdentifier": walletRowIdentifier,
            "nativeWalletRowLabel": walletRowLabel,
            "sourceRequestedURL": Self.hnsURL,
            "statelessDANERowIdentifier":
                "settings.handshake.stateless-dane-certificates",
            "statelessDANEToggleIdentifier":
                "settings.handshake.stateless-dane-certificates.toggle",
        ]
    }

    private func openProofDetails(timeout: TimeInterval) throws -> [String: Any] {
        let table = app.tables["settings.table"]
        let advancedRow = table.cells["settings.destination.advanced"]
        XCTAssertTrue(
            advancedRow.waitForExistence(timeout: timeout),
            "Advanced settings destination did not appear"
        )
        advancedRow.tap()
        let proofRowIdentifier = "browser-settings.proof-details"
        let proofRow = table.cells[proofRowIdentifier]
        XCTAssertTrue(
            proofRow.waitForExistence(timeout: timeout),
            "HNS proof details setting did not become visible"
        )

        // Resolve the semantic row again immediately before activation. A
        // table-relative coordinate can silently select an adjacent action if
        // UIKit refreshes or reuses cells between geometry queries.
        let currentProofRow = table.cells[proofRowIdentifier]
        XCTAssertTrue(
            waitUntil(
                description: "hittable HNS proof details setting",
                timeout: 10,
                condition: {
                    currentProofRow.exists && currentProofRow.isHittable
                }
            )
        )
        currentProofRow.tap()

        let proofContent = app.textViews["browser-proof-details.content"]
        var unexpectedDestination: String?
        XCTAssertTrue(
            waitUntil(
                description: "live proof details",
                timeout: timeout,
                condition: {
                    if proofContent.exists
                        && proofContent.label == "Handshake proof details for shakescape" {
                        return true
                    }
                    if self.app.navigationBars["TLSA / DANE Inspector"].exists {
                        unexpectedDestination = "TLSA / DANE Inspector"
                        return true
                    }
                    return false
                }
            )
        )
        XCTAssertNil(
            unexpectedDestination,
            "HNS proof details selection opened \(unexpectedDestination ?? "an unexpected destination")"
        )
        guard unexpectedDestination == nil else { return [:] }
        XCTAssertEqual(
            proofContent.label,
            "Handshake proof details for shakescape",
            "HNS proof details content did not match the live HNS navigation"
        )
        assertNoNavigationAlert()

        return [
            "sourceRequestedURL": Self.hnsURL,
            "contentAccessibilityLabel": proofContent.label,
        ]
    }

    private func dismissProofDetailsAndSettings(timeout: TimeInterval) {
        // Keep the live Release process and its validated Handshake proof peer
        // alive for the ICANN capture. Relaunching here discarded that
        // in-memory peer immediately before the dual-root comparison.
        let proofContent = app.textViews["browser-proof-details.content"]
        XCTAssertTrue(
            proofContent.waitForExistence(timeout: timeout),
            "Proof Details content disappeared before dismissal"
        )
        let proofClose = app
            .navigationBars["Handshake proof verified"]
            .buttons["close"]
            .firstMatch
        XCTAssertTrue(
            proofClose.waitForExistence(timeout: timeout),
            "Proof Details close control did not appear"
        )
        proofClose.tap()
        XCTAssertTrue(
            waitUntil(
                description: "Proof Details dismissal",
                timeout: timeout,
                condition: {
                    !proofContent.exists
                        && (
                            self.app.textFields["app-store-screenshot.address"].exists
                                || self.app.tables["settings.table"].exists
                        )
                }
            )
        )

        // Settings is normally dismissed before Proof Details is presented.
        // Keep the fallback explicit so this remains robust if that changes.
        let settingsTable = app.tables["settings.table"]
        if settingsTable.exists {
            let settingsClose = app.buttons["settings.close"]
            XCTAssertTrue(
                settingsClose.waitForExistence(timeout: timeout),
                "Settings close control did not appear"
            )
            settingsClose.tap()
        }
        XCTAssertTrue(
            waitUntil(
                description: "browser restoration after Proof Details",
                timeout: timeout,
                condition: {
                    !settingsTable.exists
                        && self.app.textFields["app-store-screenshot.address"].exists
                }
            )
        )
        assertNoNavigationAlert()
    }

    private func scrollUp(
        in table: XCUIElement,
        untilFullyVisible element: XCUIElement,
        maximumNormalizedMidY: CGFloat = 1.0,
        maxSwipes: Int = 10
    ) -> Bool {
        for attempt in 0...maxSwipes {
            assertNoNavigationAlert()

            if element.exists {
                let elementFrame = element.frame
                let viewport = table.frame
                if !elementFrame.isEmpty,
                   element.isHittable,
                   viewport.height.isFinite,
                   viewport.height > 0,
                   elementFrame.minY >= viewport.minY,
                   elementFrame.maxY <= viewport.maxY,
                   (elementFrame.midY - viewport.minY) / viewport.height
                       <= maximumNormalizedMidY {
                    return true
                }
            }

            if attempt < maxSwipes {
                table.swipeUp()
            }
        }
        return false
    }

    private func scrollDown(
        in table: XCUIElement,
        untilFullyVisible element: XCUIElement,
        maxSwipes: Int = 10
    ) -> Bool {
        for attempt in 0...maxSwipes {
            assertNoNavigationAlert()

            if element.exists {
                let elementFrame = element.frame
                let viewport = table.frame
                if !elementFrame.isEmpty,
                   element.isHittable,
                   viewport.height.isFinite,
                   viewport.height > 0,
                   elementFrame.minY >= viewport.minY,
                   elementFrame.maxY <= viewport.maxY {
                    return true
                }
            }

            if attempt < maxSwipes {
                table.swipeDown()
            }
        }
        return false
    }

    @discardableResult
    private func waitUntil(
        description: String,
        timeout: TimeInterval,
        timeoutEvidence: () -> String = { "" },
        condition: () -> Bool
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if app.alerts.firstMatch.exists {
                assertNoNavigationAlert()
                return false
            }
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        } while Date() < deadline
        XCTFail("Timed out waiting for \(description).\(timeoutEvidence())")
        return false
    }

    private func assertNoNavigationAlert() {
        let alert = app.alerts.firstMatch
        guard alert.exists else { return }
        let message = alert.descendants(matching: .staticText).allElementsBoundByIndex
            .map(\.label)
            .filter { !$0.isEmpty }
            .joined(separator: " — ")
        XCTFail("Live capture stopped because the app presented an alert: \(message)")
    }

    private func capture(named name: String) {
        assertNoNavigationAlert()
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attachProvenance(
        hnsEvidence: [String: Any],
        settingsEvidence: [String: Any],
        proofEvidence: [String: Any],
        webPKIEvidence: [String: Any]
    ) throws {
        let document: [String: Any] = [
            "captureMode": "live-production-runtime",
            "configuration": "Release",
            "fixtureEnvironmentInjected": false,
            "hnsNavigation": hnsEvidence,
            "proofDetails": proofEvidence,
            "schemaVersion": 3,
            "settings": settingsEvidence,
            "webPKINavigation": webPKIEvidence,
        ]
        let data = try JSONSerialization.data(
            withJSONObject: document,
            options: [.prettyPrinted, .sortedKeys]
        )
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "LIVE_APPSTORE_PROVENANCE"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// Debug-only fixture coverage for visual regression work. These captures use
/// offline injected content, are not App Store submission candidates, and are
/// intentionally excluded from the live Release capture script.
final class NonSubmissionFixtureScreenshotRegressionTests: XCTestCase {
    private static let sceneEnvironmentKey = "HNS_APP_STORE_SCREENSHOT_SCENE"

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    func testFixtureHNSChrome() {
        let app = launch(scene: "hns-page")
        waitForFixturePage(in: app, scene: "hns-page", title: "Browse beyond traditional DNS")
        XCTAssertEqual(
            app.staticTexts["app-store-screenshot.security"].label,
            "DANE verified · authoritative DoH"
        )
        capture(named: "UI_REGRESSION_FIXTURE_01_HNS")
    }

    func testFixtureProofViewer() {
        let app = launch(scene: "proof-details")
        let proof = app.textViews["app-store-screenshot.ready.proof-details"]
        XCTAssertTrue(proof.waitForExistence(timeout: 15))
        XCTAssertTrue(proof.label.contains("shakeshift"))
        capture(named: "UI_REGRESSION_FIXTURE_02_PROOF_DETAILS")
    }

    func testFixtureWebPKIChrome() {
        let app = launch(scene: "webpki-page")
        waitForFixturePage(
            in: app,
            scene: "webpki-page",
            title: "One browser for Handshake and the open web"
        )
        XCTAssertEqual(
            app.staticTexts["app-store-screenshot.security"].label,
            "Automatic ICANN trust · Rust proxy"
        )
        capture(named: "UI_REGRESSION_FIXTURE_03_WEBPKI")
    }

    private func launch(scene: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment[Self.sceneEnvironmentKey] = scene
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL",
        ]
        app.launch()
        return app
    }

    private func waitForFixturePage(
        in app: XCUIApplication,
        scene: String,
        title: String
    ) {
        let webView = app.webViews["app-store-screenshot.ready.\(scene)"]
        XCTAssertTrue(webView.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 15))
    }

    private func capture(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
