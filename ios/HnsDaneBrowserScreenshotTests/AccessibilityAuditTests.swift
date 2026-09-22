import XCTest

final class AccessibilityAuditTests: XCTestCase {
    func testBrowserChromePassesAutomatedAccessibilityAudit() throws {
        continueAfterFailure = true
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US",
        ]
        app.launchEnvironment["HNS_APP_STORE_SCREENSHOT_SCENE"] =
            "accessibility-chrome"
        app.launch()

        XCTAssertTrue(
            app.otherElements["accessibility-audit.ready"].waitForExistence(timeout: 20),
            "Deterministic accessibility audit chrome did not appear"
        )
        try app.performAccessibilityAudit(for: [
            .contrast,
            .dynamicType,
            .elementDetection,
            .hitRegion,
            .sufficientElementDescription,
            .textClipped,
            .trait,
        ])
    }
}
