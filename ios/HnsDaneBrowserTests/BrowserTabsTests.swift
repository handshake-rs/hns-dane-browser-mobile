import XCTest
@testable import HnsDaneBrowser

final class BrowserTabsTests: XCTestCase {
    func testStartsWithOneHomepageTab() {
        let tabs = BrowserTabs(homepage: "https://shakescape.com/")

        XCTAssertEqual(tabs.tabs.count, 1)
        XCTAssertEqual(tabs.activeTab.address, "https://shakescape.com/")
        XCTAssertEqual(tabs.snapshot.activeID, tabs.activeTab.id)
    }

    func testOpenIsBoundedAndSelectsTheNewTab() {
        var tabs = BrowserTabs(homepage: "https://shakescape.com/")

        for index in 1..<BrowserTabs.maximumCount {
            let opened = tabs.open(homepage: "https://example.com/\(index)")
            XCTAssertEqual(opened?.id, tabs.activeID)
        }

        XCTAssertEqual(tabs.tabs.count, BrowserTabs.maximumCount)
        XCTAssertNil(tabs.open(homepage: "https://example.com/overflow"))
        XCTAssertEqual(tabs.tabs.count, BrowserTabs.maximumCount)
    }

    func testClosingActiveTabSelectsItsNeighbour() {
        var tabs = BrowserTabs(homepage: "https://one.example/")
        let second = tabs.open(homepage: "https://two.example/")!
        let third = tabs.open(homepage: "https://three.example/")!
        XCTAssertEqual(tabs.select(id: second.id)?.id, second.id)

        XCTAssertEqual(tabs.close(id: second.id), .selected(address: third.address))
        XCTAssertEqual(tabs.activeID, third.id)
        XCTAssertEqual(tabs.tabs.map(\.address), ["https://one.example/", "https://three.example/"])
    }

    func testClosingInactiveTabDoesNotChangeSelection() {
        var tabs = BrowserTabs(homepage: "https://one.example/")
        let second = tabs.open(homepage: "https://two.example/")!
        let activeID = tabs.activeID

        XCTAssertEqual(tabs.close(id: 1), .closedInactive)
        XCTAssertEqual(tabs.activeID, activeID)
        XCTAssertEqual(tabs.activeTab.id, second.id)
    }

    func testNeverClosesTheLastTab() {
        var tabs = BrowserTabs(homepage: "https://shakescape.com/")

        XCTAssertEqual(tabs.close(id: tabs.activeID), .rejected)
        XCTAssertEqual(tabs.tabs.count, 1)
    }

    func testMetadataIsBoundedAndAddressChangeClearsStaleTitle() {
        var tabs = BrowserTabs(homepage: "https://shakescape.com/")
        tabs.updateActiveTitle(String(repeating: "a", count: BrowserTabs.maximumTitleCharacters + 20))
        XCTAssertEqual(tabs.activeTab.title?.count, BrowserTabs.maximumTitleCharacters)

        tabs.updateActiveTitle("unsafe\npage\u{0000}title")
        XCTAssertEqual(tabs.activeTab.title, "unsafe page title")

        tabs.updateActiveAddress("https://example.com/")
        XCTAssertNil(tabs.activeTab.title)

        let oversized = String(repeating: "x", count: BrowserTabs.maximumAddressBytes + 1)
        tabs.updateActiveAddress(oversized)
        XCTAssertEqual(tabs.activeTab.address, "https://example.com/")
        XCTAssertEqual(
            tabs.open(homepage: oversized)?.address,
            BrowserSettingsPreferences.defaultHomepage
        )
    }
}
