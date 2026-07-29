import Foundation
import XCTest
@testable import HnsDaneBrowser

final class MainFrameAdmissionPolicyTests: XCTestCase {
    private let policy = MainFrameAdmissionPolicy()

    func testSameScopeAllowsNativeNavigation() {
        let scope = BrowserProxyScope.handshakeRoot("woodburn")
        XCTAssertEqual(
            policy.evaluate(activeScope: scope, destinationScope: scope, httpMethod: "POST"),
            .allow
        )
    }

    func testCrossScopeGetRotates() {
        XCTAssertEqual(
            policy.evaluate(
                activeScope: .handshakeRoot("woodburn"),
                destinationScope: .icann,
                httpMethod: "GET"
            ),
            .rotateProxy
        )
    }

    func testCrossScopePostCannotBeReplayed() {
        XCTAssertEqual(
            policy.evaluate(
                activeScope: .icann,
                destinationScope: .handshakeRoot("woodburn"),
                httpMethod: "POST"
            ),
            .blockNonIdempotentReplay
        )
    }
}

final class NavigationReplayPolicyTests: XCTestCase {
    private let policy = NavigationReplayPolicy()

    func testGetAndHeadCanBeReplayedAcrossLifecycleRotation() {
        XCTAssertTrue(policy.allowsAutomaticReplay(httpMethod: "GET"))
        XCTAssertTrue(policy.allowsAutomaticReplay(httpMethod: "head"))
        XCTAssertTrue(policy.allowsAutomaticReplay(httpMethod: nil))
    }

    func testRequestBodiesAreNeverAutomaticallyReplayed() {
        XCTAssertFalse(policy.allowsAutomaticReplay(httpMethod: "POST"))
        XCTAssertFalse(policy.allowsAutomaticReplay(httpMethod: "PUT"))
        XCTAssertFalse(policy.allowsAutomaticReplay(httpMethod: "PATCH"))
        XCTAssertFalse(policy.allowsAutomaticReplay(httpMethod: "DELETE"))
    }
}

final class ProvisionalNavigationFailureRecoveryPolicyTests: XCTestCase {
    private let policy = ProvisionalNavigationFailureRecoveryPolicy()

    func testFirstConnectionLostFailureReplaysGetAndHead() {
        let url = URL(string: "https://example.com/")!
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSURLErrorFailingURLErrorKey: url]
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .replayOnce
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: "head",
                automaticReplayCount: 0
            ),
            .replayOnce
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: nil,
                automaticReplayCount: 0
            ),
            .replayOnce
        )
    }

    func testConnectionLostFailureIsNeverReplayedTwice() {
        let url = URL(string: "https://example.com/")!
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSURLErrorFailingURLErrorKey: url]
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 1
            ),
            .report
        )
    }

    func testConnectionLostFailureDoesNotReplayUnsafeMethods() {
        let url = URL(string: "https://example.com/")!
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost,
            userInfo: [NSURLErrorFailingURLErrorKey: url]
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: "POST",
                automaticReplayCount: 0
            ),
            .report
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                requestURL: url,
                httpMethod: "PUT",
                automaticReplayCount: 0
            ),
            .report
        )
    }

    func testOtherFailuresRemainReportable() {
        let url = URL(string: "https://example.com/")!
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: "example.failure",
                    code: NSURLErrorNetworkConnectionLost,
                    userInfo: [NSURLErrorFailingURLErrorKey: url]
                ),
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorTimedOut,
                    userInfo: [NSURLErrorFailingURLErrorKey: url]
                ),
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
    }

    func testFailureMustIdentifyTheRetainedRequestURL() {
        let url = URL(string: "https://example.com/")!
        let otherURL = URL(string: "https://example.net/")!

        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorNetworkConnectionLost,
                    userInfo: [NSURLErrorFailingURLErrorKey: otherURL]
                ),
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorNetworkConnectionLost
                ),
                requestURL: url,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
    }
}
