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

    func testAutomaticReplayAndInterveningActionsPreserveOneShotBoundary() {
        XCTAssertEqual(
            policy.evaluateNavigationAction(
                isAutomaticFailureReplay: true,
                hasActiveOneShotRecovery: true
            ),
            .preserveOneShotState
        )
        XCTAssertEqual(
            policy.evaluateNavigationAction(
                isAutomaticFailureReplay: false,
                hasActiveOneShotRecovery: true
            ),
            .invalidateQueuedReplay
        )
        XCTAssertEqual(
            policy.evaluateNavigationAction(
                isAutomaticFailureReplay: false,
                hasActiveOneShotRecovery: false
            ),
            .reset
        )
    }

    func testFirstConnectionLostFailureReplaysGetAndHead() {
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .replayOnce
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: "head",
                automaticReplayCount: 0
            ),
            .replayOnce
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: nil,
                automaticReplayCount: 0
            ),
            .replayOnce
        )
    }

    func testConnectionLostFailureIsNeverReplayedTwice() {
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: "GET",
                automaticReplayCount: 1
            ),
            .report
        )
    }

    func testConnectionLostFailureDoesNotReplayUnsafeMethods() {
        let error = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNetworkConnectionLost
        )

        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: "POST",
                automaticReplayCount: 0
            ),
            .report
        )
        XCTAssertEqual(
            policy.evaluate(
                error: error,
                matchesTrackedNavigation: true,
                httpMethod: "PUT",
                automaticReplayCount: 0
            ),
            .report
        )
    }

    func testOtherFailuresRemainReportable() {
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: "example.failure",
                    code: NSURLErrorNetworkConnectionLost
                ),
                matchesTrackedNavigation: true,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorTimedOut
                ),
                matchesTrackedNavigation: true,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
    }

    func testFailureMustMatchTheTrackedNavigation() {
        XCTAssertEqual(
            policy.evaluate(
                error: NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorNetworkConnectionLost
                ),
                matchesTrackedNavigation: false,
                httpMethod: "GET",
                automaticReplayCount: 0
            ),
            .report
        )
    }
}
