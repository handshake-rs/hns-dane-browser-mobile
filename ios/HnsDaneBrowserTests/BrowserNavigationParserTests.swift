import XCTest
@testable import HnsDaneBrowser

final class BrowserNavigationParserTests: XCTestCase {
    func testExplicitURLPreservesPathAndRoutesDNSHostToWholeBrowserGateway() throws {
        let parser = BrowserNavigationParser(
            canonicalizeHost: { $0.lowercased() }
        )

        let destination = try parser.parse("https://WWW.Example.COM/docs/page?q=1")

        XCTAssertEqual(destination.url.absoluteString, "https://WWW.Example.COM/docs/page?q=1")
        XCTAssertEqual(destination.canonicalHost, "www.example.com")
        XCTAssertEqual(destination.hostKind, .nativeGateway)
        XCTAssertEqual(destination.proxyScope, .wholeBrowser)
    }

    func testBareHandshakeLookingHostUsesTheSameWholeBrowserGateway() throws {
        let parser = BrowserNavigationParser(
            canonicalizeHost: { $0.lowercased() }
        )

        let destination = try parser.parse("Nathan.Woodburn/docs")

        XCTAssertEqual(destination.url.absoluteString, "https://Nathan.Woodburn/docs")
        XCTAssertEqual(destination.canonicalHost, "nathan.woodburn")
        XCTAssertEqual(destination.hostKind, .nativeGateway)
        XCTAssertEqual(destination.proxyScope, .wholeBrowser)
    }

    func testCanonicalRustHostDrivesUnicodeScopeAndStatusIdentity() throws {
        var extractedHost: String?
        let parser = BrowserNavigationParser(
            canonicalizeHost: {
                extractedHost = $0
                return "xn--bcher-kva"
            }
        )

        let destination = try parser.parse("https://bücher/")

        XCTAssertNotNil(extractedHost)
        XCTAssertEqual(destination.canonicalHost, "xn--bcher-kva")
        XCTAssertEqual(destination.hostKind, .nativeGateway)
        XCTAssertEqual(destination.proxyScope, .wholeBrowser)
    }

    func testPublicIPAddressUsesBoundedWholeBrowserTunnelWithoutDNSClassification() throws {
        let parser = BrowserNavigationParser(canonicalizeHost: { $0 })

        let destination = try parser.parse("https://192.0.2.1/")

        XCTAssertEqual(destination.hostKind, .icann)
        XCTAssertEqual(destination.proxyScope, .wholeBrowser)
    }

    func testSearchTextAndUnsupportedSchemesFailClosed() {
        let parser = BrowserNavigationParser(
            canonicalizeHost: { $0 }
        )

        XCTAssertThrowsError(try parser.parse("two words"))
        XCTAssertThrowsError(try parser.parse("file:///tmp/page.html"))
    }
}
