import XCTest
@testable import HnsDaneBrowser

final class WalletProviderProtocolTests: XCTestCase {
    func testCompleteSurfaceAndRestrictedAssetModules() throws {
        XCTAssertEqual(WalletProviderProtocolV1.methods.count, 43)
        XCTAssertEqual(WalletProviderProtocolV1.events.count, 13)
        let request = try WalletProviderProtocolV1.parseRequest([
            "schemaVersion": 1,
            "kind": "request",
            "requestId": "request-1",
            "sequence": 1,
            "method": "asset_getBalance",
            "params": ["module": "bitcoin"],
        ])
        XCTAssertEqual(request.method, "asset_getBalance")
        XCTAssertThrowsError(
            try WalletProviderProtocolV1.parseRequest([
                "schemaVersion": 1,
                "kind": "request",
                "requestId": "request-2",
                "sequence": 2,
                "method": "asset_send",
                "params": ["module": "solana"],
            ])
        ) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidParams")
        }
    }

    func testGenericSignerAndFractionalBaseUnitsFailClosed() {
        XCTAssertThrowsError(
            try WalletProviderProtocolV1.parseRequest([
                "schemaVersion": 1,
                "kind": "request",
                "requestId": "request-1",
                "sequence": 1,
                "method": "eth_call",
                "params": [:],
            ])
        ) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "forbiddenMethod")
        }
        XCTAssertThrowsError(
            try WalletProviderProtocolV1.parseRequest([
                "schemaVersion": 1,
                "kind": "request",
                "requestId": "request-2",
                "sequence": 2,
                "method": "hns_send",
                "params": ["amount": 0.1],
            ])
        ) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidRequest")
        }
        for sequence in [-1 as Any, 1.5 as Any, 9_007_199_254_740_992 as Any] {
            XCTAssertThrowsError(
                try WalletProviderProtocolV1.parseRequest([
                    "schemaVersion": 1,
                    "kind": "request",
                    "requestId": "request-sequence",
                    "sequence": sequence,
                    "method": "hns_accounts",
                    "params": NSNull(),
                ])
            ) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "invalidRequest")
            }
        }
        XCTAssertThrowsError(
            try WalletProviderProtocolV1.parseRequest([
                "schemaVersion": 1,
                "kind": "request",
                "requestId": "request-params",
                "sequence": 3,
                "method": "hns_accounts",
                "params": ["unexpected": true],
            ])
        ) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidParams")
        }
    }

    func testNativeResultsAndEventsRejectSecretMaterialAndHideInternalMessages() throws {
        let request = try WalletProviderProtocolV1.parseRequest([
            "schemaVersion": 1,
            "kind": "request",
            "requestId": "request-result",
            "sequence": 1,
            "method": "hns_send",
            "params": ["amount": "1", "address": "hs1test"],
        ])
        let invalidResult = WalletProviderProtocolV1.response(
            request: request,
            result: ["recovery_phrase": "never expose this"]
        )
        XCTAssertEqual(invalidResult["ok"] as? Bool, false)
        XCTAssertEqual(
            (invalidResult["error"] as? [String: Any])?["code"] as? String,
            "invalidResult"
        )
        XCTAssertThrowsError(
            try WalletProviderProtocolV1.event(
                "accountsChanged",
                payload: ["privateKey": "never expose this"]
            )
        ) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidEvent")
        }
        let internalError = WalletProviderProtocolV1.response(
            request: request,
            error: NSError(
                domain: "WalletCore",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "sensitive native diagnostic"]
            )
        )
        let errorPayload = try XCTUnwrap(internalError["error"] as? [String: Any])
        XCTAssertEqual(errorPayload["code"] as? String, "internalError")
        XCTAssertEqual(errorPayload["message"] as? String, "Wallet request failed")
    }

    func testWalletScreenInventoryStartsFailClosed() {
        XCTAssertEqual(WalletScreen.allCases.count, 25)
        XCTAssertFalse(WalletUIState().allowsValueAction)
    }
}
