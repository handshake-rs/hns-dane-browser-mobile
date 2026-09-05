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

final class BrowserAddressPresentationTests: XCTestCase {
    func testEditingTextRestoresTheExactCanonicalAddress() {
        let canonicalAddress = "https://App.Pirate:443/p/%F0%9F%8E%AE?x=1#frag"

        XCTAssertEqual(
            BrowserAddressPresentation.editingText(for: canonicalAddress),
            canonicalAddress
        )
    }

    func testUnicodeNameRemainsHumanReadableAfterCanonicalNavigation() {
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://xn--f8h/"),
            "⚪"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.editingText(for: "https://xn--f8h/"),
            "https://⚪/"
        )
    }

    func testIdleTextShowsHostPathQueryAndFragmentWithoutScheme() {
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://app.pirate/p/post_123?x=1#frag"
            ),
            "app.pirate/p/post_123?x=1#frag"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "http://app.dankmeme/feed"),
            "app.dankmeme/feed"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://app.pirate/"),
            "app.pirate"
        )
    }

    func testIdleTextKeepsOnlyNonDefaultPorts() {
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://app.pirate:8443/"),
            "app.pirate:8443"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://app.pirate:443/"),
            "app.pirate"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "http://example.com:80/"),
            "example.com"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://[::1]:443/health"),
            "[::1]/health"
        )
    }

    func testIdleTextNormalizesHostAndPreservesEncodedRoutes() {
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://APP.Pirate./x"),
            "app.pirate/x"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://app.xn--pokmon-dva/p/%F0%9F%8E%AE"
            ),
            "app.pokémon/p/%F0%9F%8E%AE"
        )
    }

    func testOnlyExactSecureDefaultPortStartPageIsHidden() {
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net/assets/start.html"
            ),
            ""
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net:443/assets/start.html"
            ),
            ""
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "http://appassets.androidplatform.net/assets/start.html"
            ),
            "appassets.androidplatform.net/assets/start.html"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net:444/assets/start.html"
            ),
            "appassets.androidplatform.net:444/assets/start.html"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net/not-assets/start.html"
            ),
            "appassets.androidplatform.net/not-assets/start.html"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net/assets/start.html?source=test"
            ),
            "appassets.androidplatform.net/assets/start.html?source=test"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://appassets.androidplatform.net/assets/start.html#restore"
            ),
            "appassets.androidplatform.net/assets/start.html#restore"
        )
    }

    func testBlankNonWebCredentialAndMalformedValuesStaySafe() {
        XCTAssertEqual(BrowserAddressPresentation.displayText(for: "about:blank"), "")
        XCTAssertEqual(BrowserAddressPresentation.displayText(for: nil), "")
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "mailto:someone@example.com"),
            "mailto:someone@example.com"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "not a url"),
            "not a url"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(
                for: "https://user:secret@app.pirate/private"
            ),
            "https://user:secret@app.pirate/private"
        )
        XCTAssertEqual(
            BrowserAddressPresentation.displayText(for: "https://app.pirate:https/"),
            "https://app.pirate:https/"
        )
    }

    func testSameOriginAllowsOnlyPathQueryAndFragmentChanges() {
        XCTAssertTrue(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://App.Pirate./feed",
                "https://app.pirate/post/1?view=full#comments"
            )
        )
    }

    func testSameOriginTreatsDefaultPortsAsEquivalent() {
        XCTAssertTrue(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://app.pirate:443/x"
            )
        )
        XCTAssertTrue(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "http://example.com:80/",
                "http://EXAMPLE.com/x"
            )
        )
        XCTAssertTrue(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate:8443/a",
                "https://app.pirate:8443/b"
            )
        )
        XCTAssertTrue(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://[::1]/",
                "https://[::1]:443/x"
            )
        )
    }

    func testSameOriginRejectsSchemeHostAndPortChanges() {
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "http://app.pirate/",
                "https://app.pirate/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://other.pirate/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate:8443/",
                "https://app.pirate:9443/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://app.pirate:8443/"
            )
        )
    }

    func testSameOriginRejectsCredentialsMissingAndMalformedAddresses() {
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                nil,
                "https://app.pirate/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "not a URL",
                "https://app.pirate/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://app.pirate:"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://app.pirate:65536/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://app.pirate:https/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://app.pirate/",
                "https://user@app.pirate/"
            )
        )
        XCTAssertFalse(
            BrowserAddressPresentation.isSameAdmittedWebOrigin(
                "https://[::1]/",
                "https://[::1]:https/"
            )
        )
    }
}
