import XCTest
import WebKit
@testable import HnsDaneBrowser

final class WalletProviderProtocolTests: XCTestCase {
    private static let nowUnixMs: UInt64 = 1_800_000_000_000

    func testCompleteSurfaceAndRestrictedAssetModules() throws {
        XCTAssertEqual(WalletProviderProtocolV1.schemaVersion, 1)
        XCTAssertEqual(WalletProviderProtocolV1.providerAPIVersion, 1)
        XCTAssertEqual(WalletProviderProtocolV1.nativeABIVersion, 2)
        XCTAssertEqual(WalletProviderProtocolV1.approvalSchemaVersion, 2)
        let expectedMethods: Set<String> = [
            "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_enableModule",
            "wallet_disableModule", "wallet_requestPermissions", "wallet_getPermissions",
            "wallet_revokePermissions", "wallet_lock", "wallet_getStatus",
            "hns_requestAccounts", "hns_accounts", "hns_getBalance", "hns_getTransactions",
            "hns_getReceiveAddress", "hns_send", "hns_getNames", "hns_getName",
            "hns_importKnownName", "hns_transferName", "hns_finalizeName", "hns_signTypedMessage",
            "asset_getAccount", "asset_getBalance", "asset_getTransactions",
            "asset_getReceiveTarget", "asset_send", "nameMarket_listOffers",
            "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
            "nameMarket_acceptOffer", "nameMarket_getSession", "nameMarket_finalizePurchase",
            "nameMarket_recoverName", "swap_getSupportedPairs", "swap_getPriceRound",
            "swap_listMarketIntents", "swap_publishMarketIntent", "swap_cancelMarketIntent",
            "swap_requestMatch", "swap_acceptFill", "swap_getSession", "swap_redeem", "swap_refund",
        ]
        XCTAssertEqual(WalletProviderProtocolV1.methods, expectedMethods)
        XCTAssertEqual(WalletProviderProtocolV1.noApprovalMethods.count, 23)
        XCTAssertEqual(WalletProviderProtocolV1.approvalOnlyMethods.count, 5)
        XCTAssertEqual(WalletProviderProtocolV1.approvalAndValueMethods.count, 15)
        XCTAssertTrue(
            WalletProviderProtocolV1.noApprovalMethods.isDisjoint(
                with: WalletProviderProtocolV1.approvalOnlyMethods
            )
        )
        XCTAssertTrue(
            WalletProviderProtocolV1.noApprovalMethods.isDisjoint(
                with: WalletProviderProtocolV1.approvalAndValueMethods
            )
        )
        XCTAssertTrue(
            WalletProviderProtocolV1.approvalOnlyMethods.isDisjoint(
                with: WalletProviderProtocolV1.approvalAndValueMethods
            )
        )
        XCTAssertEqual(
            WalletProviderProtocolV1.noApprovalMethods
                .union(WalletProviderProtocolV1.approvalOnlyMethods)
                .union(WalletProviderProtocolV1.approvalAndValueMethods),
            expectedMethods
        )
        for method in WalletProviderProtocolV1.noApprovalMethods {
            XCTAssertEqual(try WalletProviderProtocolV1.releaseClass(for: method), .noApproval)
        }
        for method in WalletProviderProtocolV1.approvalOnlyMethods {
            XCTAssertEqual(try WalletProviderProtocolV1.releaseClass(for: method), .approvalOnly)
        }
        for method in WalletProviderProtocolV1.approvalAndValueMethods {
            XCTAssertEqual(try WalletProviderProtocolV1.releaseClass(for: method), .approvalAndValue)
        }
        XCTAssertThrowsError(try WalletProviderProtocolV1.releaseClass(for: "future_unknown"))
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
        XCTAssertThrowsError(try WalletProviderProtocolV1.parseRequest([
            "schemaVersion": 1,
            "kind": "request",
            "requestId": "request-terminal-newline\n",
            "sequence": 4,
            "method": "hns_accounts",
            "params": NSNull(),
        ])) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidRequest")
        }
        XCTAssertThrowsError(try WalletProviderProtocolV1.parseRequest([
            "schemaVersion": 1,
            "kind": "request",
            "requestId": "request-private-generation",
            "sequence": 5,
            "method": "hns_send",
            "params": ["permissionGeneration": 7],
        ])) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidRequest")
        }
    }

    func testProviderSchemaVersionRequiresExactNumericOne() throws {
        XCTAssertTrue(WalletProviderProtocolV1.hasExactProviderSchemaVersion(1))
        XCTAssertTrue(WalletProviderProtocolV1.hasExactProviderSchemaVersion(NSNumber(value: 1)))
        let invalidSchemas: [Any] = [true, "1", 1.5, NSNull()]
        for invalidSchema in invalidSchemas {
            XCTAssertFalse(WalletProviderProtocolV1.hasExactProviderSchemaVersion(invalidSchema))
            XCTAssertThrowsError(try WalletProviderProtocolV1.parseRequest([
                "schemaVersion": invalidSchema,
                "kind": "request",
                "requestId": "request-schema",
                "sequence": 1,
                "method": "hns_accounts",
                "params": NSNull(),
            ])) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "invalidRequest")
            }
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
        XCTAssertThrowsError(try WalletNativeEventProjectionV2.project([
            "event": "accountsChanged",
            "modules": ["handshake"],
            "privateKey": "never expose this",
        ])) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "invalidEvent")
        }
        for field in [
            "authorityHandle", "authorityRevision", "hostSessionId", "serviceSessionId",
            "runtimeSessionId", "browserRuntimeSessionId", "browserAuthoritySession",
            "channelSequence", "eventSequence", "protocolVersion", "requestNonce", "walletSession",
        ] {
            let hiddenResult = WalletProviderProtocolV1.response(
                request: request,
                result: [field: "private-native-state"]
            )
            XCTAssertEqual(hiddenResult["ok"] as? Bool, false)
            XCTAssertEqual(
                (hiddenResult["error"] as? [String: Any])?["code"] as? String,
                "invalidResult"
            )
        }
        let permissionRequest = try WalletProviderProtocolV1.parseRequest([
            "schemaVersion": 1,
            "kind": "request",
            "requestId": "request-permissions",
            "sequence": 2,
            "method": "wallet_getPermissions",
            "params": NSNull(),
        ])
        let publicPermissionGeneration = WalletProviderProtocolV1.response(
            request: permissionRequest,
            result: ["permissionGeneration": 7, "capabilities": []]
        )
        XCTAssertEqual(publicPermissionGeneration["ok"] as? Bool, true)
        let misplacedPermissionGeneration = WalletProviderProtocolV1.response(
            request: request,
            result: ["permissionGeneration": 7]
        )
        XCTAssertEqual(misplacedPermissionGeneration["ok"] as? Bool, false)
        let nestedPermissionGeneration = WalletProviderProtocolV1.response(
            request: permissionRequest,
            result: ["nested": ["permissionGeneration": 7]]
        )
        XCTAssertEqual(nestedPermissionGeneration["ok"] as? Bool, false)

        let recursivelyForbidden: [Any] = [
            ["outer": [["event": "walletLocked"]]],
            ["outer": ["events": []]],
            ["outer": ["e_vents": []]],
            ["outer": ["approvalRequired": true]],
            ["outer": [[
                "approvalId": "AQIDBAUGBwgJCgsMDQ4PEA",
                "expiresAtUnixMs": 1,
                "summary": [:],
            ]]],
            ["outer": [[
                "Approval_ID": "AQIDBAUGBwgJCgsMDQ4PEA",
                "expires-at-unix-ms": 1,
                "SUMMARY": [:],
            ]]],
            ["outer": [["browserRuntimeSessionId": "private-session"]]],
        ]
        for result in recursivelyForbidden {
            let response = WalletProviderProtocolV1.response(request: request, result: result)
            XCTAssertEqual(response["ok"] as? Bool, false)
            XCTAssertEqual(
                (response["error"] as? [String: Any])?["code"] as? String,
                "invalidResult"
            )
        }

        let multibyteResult = WalletProviderProtocolV1.response(
            request: request,
            result: ["label": String(repeating: "🧨", count: 5_000)]
        )
        XCTAssertEqual(multibyteResult["ok"] as? Bool, false)
        XCTAssertEqual(
            (multibyteResult["error"] as? [String: Any])?["code"] as? String,
            "resultTooLarge"
        )
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

        let inlineResults: [[String: Any]] = [
            ["event": "walletLocked"],
            ["events": []],
            ["approvalRequired": true],
            ["approvalId": "AQIDBAUGBwgJCgsMDQ4PEA", "expiresAtUnixMs": 1, "summary": [:]],
        ]
        for inlineResult in inlineResults {
            let response = WalletProviderProtocolV1.response(request: request, result: inlineResult)
            XCTAssertEqual(response["ok"] as? Bool, false)
            XCTAssertEqual(
                (response["error"] as? [String: Any])?["code"] as? String,
                "invalidResult"
            )
        }
    }

    func testWalletScreenInventoryStartsFailClosed() {
        XCTAssertEqual(WalletScreen.allCases.count, 25)
        XCTAssertFalse(WalletUIState().allowsValueAction)
        XCTAssertFalse(WalletUIState().allowsApprovalAction)
        let apparentlyReady = WalletUIState(walletAvailable: true, locked: false)
        XCTAssertFalse(apparentlyReady.allowsValueAction)
        XCTAssertFalse(apparentlyReady.allowsApprovalAction)
    }

    func testAllTwelveApprovalKindsValidateAndRenderFixedRows() throws {
        let now = Self.nowUnixMs
        let cases: [(method: String, params: Any?, summary: [String: Any])] = [
            (
                "wallet_requestPermissions",
                ["capabilities": ["accounts", "send"]],
                ["kind": "permissions", "capabilities": ["accounts", "send"]]
            ),
            (
                "wallet_enableModule",
                ["module": "bitcoin"],
                ["kind": "moduleEnablement", "module": "bitcoin", "action": "enable"]
            ),
            (
                "asset_send",
                ["module": "bitcoin"],
                [
                    "kind": "send", "amount": amount("BTC", "12000"),
                    "recipient": "tb1qexample", "maximumFee": amount("BTC", "420"),
                    "chain": "bitcoin", "finality": "proof_of_work_confirmations",
                    "warnings": ["feeEstimateMayChange"],
                ]
            ),
            (
                "hns_transferName",
                nil,
                [
                    "kind": "nameTransfer", "name": "example", "recipient": "hs1qrecipient",
                    "maximumFee": amount("HNS", "10"),
                    "warnings": ["nameTransferIsIrreversible"],
                ]
            ),
            (
                "hns_finalizeName",
                nil,
                [
                    "kind": "nameFinalize", "name": "example", "recipient": "hs1qrecipient",
                    "maximumFee": amount("HNS", "10"), "warnings": [],
                ]
            ),
            (
                "hns_signTypedMessage",
                nil,
                [
                    "kind": "typedSignature", "messageType": "hns-login-v1",
                    "messageDigest": String(repeating: "ab", count: 32),
                ]
            ),
            (
                "nameMarket_createFixedPriceOffer",
                nil,
                [
                    "kind": "nameMarketOffer", "action": "create", "name": "example",
                    "listingId": NSNull(), "price": amount("HNS", "5000"),
                    "maximumFee": amount("HNS", "50"), "warnings": ["feeEstimateMayChange"],
                ]
            ),
            (
                "nameMarket_acceptOffer",
                nil,
                [
                    "kind": "nameMarketPurchase", "name": "example", "listingId": "listing-1",
                    "payment": amount("HNS", "5000"), "recipient": "hs1qseller",
                    "maximumFee": amount("HNS", "50"), "warnings": [],
                ]
            ),
            (
                "swap_publishMarketIntent",
                nil,
                [
                    "kind": "marketIntent", "action": "publish", "marketIntentId": NSNull(),
                    "offered": amount("HNS", "1000"), "requestedAsset": "BTC",
                    "priceRound": "price-round-1", "maximumFee": amount("HNS", "25"),
                    "warnings": ["settlementCanBeDelayed"],
                ]
            ),
            (
                "swap_acceptFill",
                nil,
                [
                    "kind": "fillAcceptance", "marketIntentId": "intent-1", "fillId": "fill-1",
                    "offered": amount("HNS", "1000"), "expected": amount("ETH", "2000"),
                    "priceRound": "price-round-1", "refundTimeoutUnixMs": NSNumber(value: now + 600_000),
                    "maximumFee": amount("HNS", "25"),
                    "warnings": ["refundRequiresManualAction", "settlementCanBeDelayed"],
                ]
            ),
            (
                "swap_redeem",
                nil,
                [
                    "kind": "swapRedeem", "swapSessionId": "swap-1",
                    "amount": amount("ETH", "2000"),
                    "recipient": "0x1111111111111111111111111111111111111111",
                    "maximumFee": amount("ETH", "25"),
                    "finality": "ethereum_finalized_checkpoint", "warnings": [],
                ]
            ),
            (
                "swap_refund",
                nil,
                [
                    "kind": "swapRefund", "swapSessionId": "swap-1",
                    "amount": amount("BTC", "2000"), "recipient": "tb1qrefund",
                    "maximumFee": amount("BTC", "25"),
                    "refundAvailableAtUnixMs": NSNumber(value: now + 600_000),
                    "warnings": ["refundRequiresManualAction"],
                ]
            ),
        ]
        let expectedDisplays: [String: (title: String, labels: [String], values: [String])] = [
            "permissions": (
                "Approve wallet permissions",
                ["Capabilities"],
                ["accounts, send"]
            ),
            "moduleEnablement": (
                "Enable wallet module",
                ["Module", "Action"],
                ["bitcoin", "enable"]
            ),
            "send": (
                "Approve asset send",
                ["Amount", "Recipient", "Maximum fee", "Chain", "Finality", "Warnings"],
                [
                    "12000 BTC", "tb1qexample", "420 BTC", "bitcoin",
                    "proof_of_work_confirmations", "feeEstimateMayChange",
                ]
            ),
            "nameTransfer": (
                "Approve name transfer",
                ["Name", "Recipient", "Maximum fee", "Warnings"],
                ["example", "hs1qrecipient", "10 HNS", "nameTransferIsIrreversible"]
            ),
            "nameFinalize": (
                "Approve name finalization",
                ["Name", "Recipient", "Maximum fee"],
                ["example", "hs1qrecipient", "10 HNS"]
            ),
            "typedSignature": (
                "Approve typed signature",
                ["Message type", "Message digest"],
                ["hns-login-v1", String(repeating: "ab", count: 32)]
            ),
            "nameMarketOffer": (
                "Approve name offer action",
                ["Action", "Name", "Price", "Maximum fee", "Warnings"],
                ["create", "example", "5000 HNS", "50 HNS", "feeEstimateMayChange"]
            ),
            "nameMarketPurchase": (
                "Approve name purchase",
                ["Name", "Listing ID", "Payment", "Recipient", "Maximum fee"],
                ["example", "listing-1", "5000 HNS", "hs1qseller", "50 HNS"]
            ),
            "marketIntent": (
                "Approve market intent",
                [
                    "Action", "Offered", "Requested asset", "Price round", "Maximum fee",
                    "Warnings",
                ],
                [
                    "publish", "1000 HNS", "BTC", "price-round-1", "25 HNS",
                    "settlementCanBeDelayed",
                ]
            ),
            "fillAcceptance": (
                "Approve marketplace fill",
                [
                    "Market intent ID", "Fill ID", "Offered", "Expected", "Price round",
                    "Refund timeout", "Maximum fee", "Warnings",
                ],
                [
                    "intent-1", "fill-1", "1000 HNS", "2000 ETH", "price-round-1",
                    String(now + 600_000), "25 HNS",
                    "refundRequiresManualAction, settlementCanBeDelayed",
                ]
            ),
            "swapRedeem": (
                "Approve swap redemption",
                ["Swap session ID", "Amount", "Recipient", "Maximum fee", "Finality"],
                [
                    "swap-1", "2000 ETH", "0x1111111111111111111111111111111111111111",
                    "25 ETH", "ethereum_finalized_checkpoint",
                ]
            ),
            "swapRefund": (
                "Approve swap refund",
                [
                    "Swap session ID", "Amount", "Recipient", "Maximum fee",
                    "Refund available at", "Warnings",
                ],
                [
                    "swap-1", "2000 BTC", "tb1qrefund", "25 BTC", String(now + 600_000),
                    "refundRequiresManualAction",
                ]
            ),
        ]

        XCTAssertEqual(cases.count, 12)
        var kinds = Set<String>()
        for scenario in cases {
            let request = providerRequest(scenario.method, params: scenario.params)
            let prompt = try WalletApprovalProjectionV2.validatePrompt(
                approvalPrompt(method: scenario.method, summary: scenario.summary),
                expectedOrigin: "https://welcome",
                expectedRequest: request,
                nowUnixMs: now
            )
            XCTAssertEqual(prompt.schemaVersion, 2)
            XCTAssertTrue(kinds.insert(prompt.summary.kind).inserted)
            let display = WalletApprovalProjectionV2.display(prompt)
            let expected = try XCTUnwrap(expectedDisplays[prompt.summary.kind])
            XCTAssertEqual(display.title, expected.title)
            XCTAssertEqual(display.rows.map(\.label), expected.labels)
            XCTAssertEqual(display.rows.map(\.value), expected.values)
        }
        XCTAssertEqual(kinds.count, 12)
        XCTAssertEqual(expectedDisplays.count, 12)
    }

    func testApprovalProjectionRejectsPrivateEnvelopeAndNoncanonicalSummary() {
        let request = providerRequest("asset_send", params: ["module": "bitcoin"])
        let summary: [String: Any] = [
            "kind": "send", "amount": amount("BTC", "12000"),
            "recipient": "tb1qexample", "maximumFee": amount("BTC", "420"),
            "chain": "bitcoin", "finality": "proof_of_work_confirmations",
            "warnings": ["feeEstimateMayChange"],
        ]
        let valid = approvalPrompt(method: request.method, summary: summary)
        let invalidCandidates: [[String: Any]] = [
            merging(valid, ["authorityHandle": "opaque-private-handle"]),
            merging(valid, ["authorityRevision": 1]),
            merging(valid, ["expiresAtUnixMs": NSNumber(value: Self.nowUnixMs)]),
            merging(valid, ["expiresAtUnixMs": NSNumber(value: Self.nowUnixMs + 90_001)]),
            merging(valid, ["approvalId": "AAAAAAAAAAAAAAAAAAAAAA"]),
            merging(valid, ["approvalId": "AQIDBAUGBwgJCgsMDQ4PEA\n"]),
            merging(valid, ["approvalId": "AQIDBAUGBwgJCgsMDQ4PEB"]),
            merging(valid, ["origin": "https://attacker.example"]),
            merging(valid, ["origin": "https://welcome/"]),
            merging(valid, ["origin": "https://welcome:443"]),
            merging(valid, ["method": "hns_send"]),
            merging(valid, ["schemaVersion": 1]),
            merging(valid, ["schemaVersion": true]),
            merging(valid, ["schemaVersion": "2"]),
            merging(valid, ["schemaVersion": 2.5]),
            merging(valid, ["summary": merging(summary, ["futureField": true])]),
            merging(valid, ["summary": merging(summary, [
                "amount": amount("BTC", "340282366920938463463374607431768211456"),
            ])]),
            merging(valid, ["summary": merging(summary, ["amount": amount("BTC", "0")])]),
            merging(valid, ["summary": merging(summary, ["amount": amount("BTC", "01")])]),
            merging(valid, ["summary": merging(summary, ["amount": amount("BTC", "1\n")])]),
            merging(valid, ["summary": merging(summary, ["amount": amount("HNS", "12000")])]),
            merging(valid, ["summary": merging(summary, ["maximumFee": amount("HNS", "420")])]),
            merging(valid, ["summary": merging(summary, [
                "finality": "ethereum_finalized_checkpoint",
            ])]),
            merging(valid, ["summary": merging(summary, ["chain": "ethereum"])]),
            merging(valid, ["summary": merging(summary, ["recipient": "line\nbreak"])]),
            merging(valid, ["summary": merging(summary, [
                "recipient": String(repeating: "x", count: 4_097),
            ])]),
            merging(valid, ["summary": merging(summary, [
                "warnings": ["settlementCanBeDelayed", "feeEstimateMayChange"],
            ])]),
            merging(valid, ["summary": merging(summary, [
                "warnings": ["feeEstimateMayChange", "feeEstimateMayChange"],
            ])]),
            merging(valid, ["summary": merging(summary, ["warnings": ["callerWarning"]])]),
        ]
        for candidate in invalidCandidates {
            XCTAssertThrowsError(try WalletApprovalProjectionV2.validatePrompt(
                candidate,
                expectedOrigin: "https://welcome",
                expectedRequest: request,
                nowUnixMs: Self.nowUnixMs
            )) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "invalidApproval")
            }
        }

        var oversized = valid
        oversized["summary"] = merging(summary, ["recipient": String(repeating: "x", count: 17_000)])
        XCTAssertThrowsError(try WalletApprovalProjectionV2.validatePrompt(
            oversized,
            expectedOrigin: "https://welcome",
            expectedRequest: request,
            nowUnixMs: Self.nowUnixMs
        )) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "approvalTooLarge")
        }
    }

    func testAllThirteenNativeEventsProjectExactPublicPayloads() throws {
        let candidates: [[String: Any]] = [
            ["event": "connect", "permissionGeneration": 1],
            ["event": "disconnect", "reason": "policyChanged"],
            [
                "event": "permissionsChanged", "permissionGeneration": 2,
                "capabilities": ["accounts", "send"],
            ],
            ["event": "modulesChanged", "modules": ["handshake", "bitcoin"]],
            ["event": "accountsChanged", "modules": ["handshake"]],
            ["event": "balancesChanged", "modules": ["bitcoin"]],
            ["event": "transactionsChanged", "modules": ["ethereum"]],
            ["event": "namesChanged", "names": ["example"]],
            ["event": "nameMarketChanged", "listingIds": ["listing-1"]],
            ["event": "priceRoundChanged", "pairs": ["HNS/BTC"]],
            ["event": "marketIntentChanged", "marketIntentIds": ["intent-1"]],
            ["event": "swapSessionChanged", "swapSessionIds": ["swap-1"]],
            ["event": "walletLocked"],
        ]
        XCTAssertEqual(candidates.count, 13)
        var names = Set<String>()
        for candidate in candidates {
            let frame = try WalletNativeEventProjectionV2.project(candidate)
            XCTAssertEqual(Set(frame.keys), ["schemaVersion", "kind", "event", "payload"])
            XCTAssertEqual(frame["schemaVersion"] as? Int, 1)
            XCTAssertEqual(frame["kind"] as? String, "event")
            let name = try XCTUnwrap(frame["event"] as? String)
            XCTAssertTrue(names.insert(name).inserted)
            XCTAssertNotNil(frame["payload"] as? [String: Any])
            let encoded = try JSONSerialization.data(withJSONObject: frame)
            let publicJSON = try XCTUnwrap(String(data: encoded, encoding: .utf8))
            for privateField in [
                "authorityHandle", "authorityRevision", "hostSessionId", "serviceSessionId",
                "restartGeneration", "channelSequence", "eventSequence", "walletSession",
            ] {
                XCTAssertFalse(publicJSON.contains(privateField))
            }
        }
        XCTAssertEqual(names, WalletProviderProtocolV1.events)
    }

    func testNativeEventsRejectEnvelopeFieldsAndKindMismatches() {
        let privateFields: [String: Any] = [
            "protocolVersion": 2, "hostSessionId": "host", "serviceSessionId": "service",
            "restartGeneration": 1, "channelSequence": 1, "authorityHandle": "handle",
            "authorityRevision": 1, "eventSequence": 1,
        ]
        for (field, value) in privateFields {
            XCTAssertThrowsError(try WalletNativeEventProjectionV2.project([
                "event": "walletLocked", field: value,
            ])) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "invalidEvent")
            }
        }
        let invalidCandidates: [[String: Any]] = [
            ["event": "connect", "permissionGeneration": 0],
            ["event": "disconnect", "reason": "callerSelectedReason"],
            ["event": "modulesChanged", "modules": ["bitcoin", "handshake"]],
            ["event": "walletLocked", "payload": NSNull()],
            ["event": "namesChanged", "names": ["line\nbreak"]],
            ["protocolVersion": 2, "payload": ["event": "walletLocked"]],
        ]
        for candidate in invalidCandidates {
            XCTAssertThrowsError(try WalletNativeEventProjectionV2.project(candidate)) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "invalidEvent")
            }
        }
        XCTAssertThrowsError(try WalletNativeEventProjectionV2.project([
            "event": "namesChanged",
            "names": [String(repeating: "x", count: 70_000)],
        ])) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "eventTooLarge")
        }
    }

    @MainActor
    func testImmutableGatesFailBeforeWebKitConfigurationMutation() {
        XCTAssertFalse(WalletNativeReleaseGates.providerBridgeReleaseQualified)
        XCTAssertFalse(WalletNativeReleaseGates.walletRuntimeReleaseQualified)
        XCTAssertFalse(WalletNativeReleaseGates.approvalRuntimeReleaseQualified)
        XCTAssertFalse(WalletNativeReleaseGates.valueRuntimeReleaseQualified)
        XCTAssertFalse(WalletNativeReleaseGates.installationAvailable)
        XCTAssertFalse(WalletNativeReleaseGates.approvalDispatchAvailable)
        XCTAssertFalse(WalletNativeReleaseGates.valueActionsAvailable)
        for method in ["wallet_getStatus", "hns_signTypedMessage", "hns_send"] {
            XCTAssertThrowsError(try WalletProviderProtocolV1.requireMethodReleaseQualified(method)) { error in
                XCTAssertEqual((error as? WalletProviderError)?.code, "walletUnavailable")
            }
        }

        let configuration = WKWebViewConfiguration()
        let initialScripts = configuration.userContentController.userScripts
        let bridge = WalletWebKitBridge { _ in nil }
        XCTAssertFalse(bridge.install(in: configuration))
        XCTAssertEqual(configuration.userContentController.userScripts.count, initialScripts.count)

        XCTAssertThrowsError(try WalletProviderProtocolV1.validateCapabilities(
            WalletCapabilitiesV2(
                available: true,
                abiVersion: 2,
                walletSession: "wallet-session",
                permissionGeneration: 1,
                methods: ["wallet_getStatus"]
            )
        )) { error in
            XCTAssertEqual((error as? WalletProviderError)?.code, "walletUnavailable")
        }
    }

    func testEngineAuthorityShapeIsCurrentOnlyForCanonicalLiveEvidence() {
        let context = WalletEngineAuthorityContext()
        let current = WalletBrowserAuthority(
            origin: "https://welcome",
            namespace: "hns",
            browserAuthoritySession: "browser-session",
            runtimeGeneration: 1,
            policyGeneration: 2,
            navigationGeneration: 3,
            decisionFingerprint: String(repeating: "a", count: 64),
            validUntilUnixMs: Self.nowUnixMs + 1,
            engineContext: context
        )
        XCTAssertTrue(current.isCurrent(nowUnixMs: Self.nowUnixMs))
        XCTAssertFalse(WalletBrowserAuthority(
            origin: current.origin,
            namespace: current.namespace,
            browserAuthoritySession: current.browserAuthoritySession,
            runtimeGeneration: current.runtimeGeneration,
            policyGeneration: current.policyGeneration,
            navigationGeneration: current.navigationGeneration,
            decisionFingerprint: String(repeating: "0", count: 64),
            validUntilUnixMs: current.validUntilUnixMs,
            engineContext: context
        ).isCurrent(nowUnixMs: Self.nowUnixMs))
        XCTAssertFalse(current.isCurrent(nowUnixMs: current.validUntilUnixMs))
        XCTAssertNotEqual(current, WalletBrowserAuthority(
            origin: current.origin,
            namespace: current.namespace,
            browserAuthoritySession: current.browserAuthoritySession,
            runtimeGeneration: current.runtimeGeneration,
            policyGeneration: current.policyGeneration,
            navigationGeneration: current.navigationGeneration,
            decisionFingerprint: current.decisionFingerprint,
            validUntilUnixMs: current.validUntilUnixMs,
            engineContext: WalletEngineAuthorityContext()
        ))
        let providerAuthority = WalletProviderAuthority(
            browser: current,
            walletSession: "wallet-session",
            permissionGeneration: 1
        )
        XCTAssertNotEqual(providerAuthority, WalletProviderAuthority(
            browser: current,
            walletSession: "replacement-session",
            permissionGeneration: 1
        ))
        XCTAssertNotEqual(providerAuthority, WalletProviderAuthority(
            browser: current,
            walletSession: "wallet-session",
            permissionGeneration: 2
        ))
    }

    private func providerRequest(_ method: String, params: Any?) -> WalletProviderRequest {
        WalletProviderRequest(requestID: "request-approval", sequence: 1, method: method, params: params)
    }

    private func approvalPrompt(method: String, summary: [String: Any]) -> [String: Any] {
        [
            "schemaVersion": 2,
            "approvalId": "AQIDBAUGBwgJCgsMDQ4PEA",
            "method": method,
            "origin": "https://welcome",
            "expiresAtUnixMs": NSNumber(value: Self.nowUnixMs + 60_000),
            "summary": summary,
        ]
    }

    private func amount(_ asset: String, _ baseUnits: String) -> [String: Any] {
        ["asset": asset, "baseUnits": baseUnits]
    }

    private func merging(_ base: [String: Any], _ overrides: [String: Any]) -> [String: Any] {
        base.merging(overrides) { _, replacement in replacement }
    }

}
