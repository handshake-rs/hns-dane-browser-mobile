package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MobileWalletProviderProtocolTest {
    @Test
    fun completeSurfaceAndRestrictedExternalModules() {
        assertEquals(1, MobileWalletProviderProtocol.SCHEMA_VERSION)
        assertEquals(2, MobileWalletProviderProtocol.WALLET_NATIVE_ABI_VERSION)
        assertEquals(allMethods, MobileWalletProviderProtocol.methods)
        assertEquals(43, allMethods.size)
        assertEquals(
            noApprovalMethods,
            allMethods.filter {
                MobileWalletProviderProtocol.methodReleaseClass(it) ==
                    WalletMethodReleaseClass.NoApproval
            }.toSet(),
        )
        assertEquals(
            approvalOnlyMethods,
            allMethods.filter {
                MobileWalletProviderProtocol.methodReleaseClass(it) ==
                    WalletMethodReleaseClass.ApprovalOnly
            }.toSet(),
        )
        assertEquals(
            approvalAndValueMethods,
            allMethods.filter {
                MobileWalletProviderProtocol.methodReleaseClass(it) ==
                    WalletMethodReleaseClass.ApprovalAndValue
            }.toSet(),
        )
        assertEquals(23, noApprovalMethods.size)
        assertEquals(5, approvalOnlyMethods.size)
        assertEquals(15, approvalAndValueMethods.size)
        assertEquals(13, MobileWalletProviderProtocol.events.size)
        val request = MobileWalletProviderProtocol.parseRequest(
            """{"schemaVersion":1,"kind":"request","requestId":"r-1","sequence":1,"method":"asset_getBalance","params":{"module":"bitcoin"}}""",
        )
        assertEquals("asset_getBalance", request.method)
        expectCode("invalidParams") {
            MobileWalletProviderProtocol.parseRequest(
                """{"schemaVersion":1,"kind":"request","requestId":"r-2","sequence":2,"method":"asset_send","params":{"module":"solana"}}""",
            )
        }
        expectCode("unsupportedMethod") {
            MobileWalletProviderProtocol.methodReleaseClass("future_unclassified_method")
        }
    }

    @Test
    fun forbiddenGenericSignersAndUnsafeNumbersFailClosed() {
        expectCode("forbiddenMethod") {
            MobileWalletProviderProtocol.parseRequest(
                """{"schemaVersion":1,"kind":"request","requestId":"r-1","sequence":1,"method":"eth_call","params":{}}""",
            )
        }
        expectCode("invalidRequest") {
            MobileWalletProviderProtocol.parseRequest(
                """{"schemaVersion":1,"kind":"request","requestId":"r-1","sequence":1,"method":"hns_send","params":{"amount":0.1}}""",
            )
        }
        for (sequence in listOf("-1", "1.5", "9007199254740992")) {
            expectCode("invalidRequest") {
                MobileWalletProviderProtocol.parseRequest(
                    """{"schemaVersion":1,"kind":"request","requestId":"r-$sequence","sequence":$sequence,"method":"hns_accounts","params":null}""",
                )
            }
        }
        expectCode("invalidParams") {
            MobileWalletProviderProtocol.parseRequest(
                """{"schemaVersion":1,"kind":"request","requestId":"r-params","sequence":3,"method":"hns_accounts","params":{"unexpected":true}}""",
            )
        }
        val typedFieldFailures = listOf(
            """{"schemaVersion":"1","kind":"request","requestId":"r","sequence":1,"method":"hns_accounts","params":null}""" to "unsupportedVersion",
            """{"schemaVersion":1.0,"kind":"request","requestId":"r","sequence":1,"method":"hns_accounts","params":null}""" to "unsupportedVersion",
            """{"schemaVersion":1,"kind":1,"requestId":"r","sequence":1,"method":"hns_accounts","params":null}""" to "invalidRequest",
            """{"schemaVersion":1,"kind":"request","requestId":7,"sequence":1,"method":"hns_accounts","params":null}""" to "invalidRequest",
            """{"schemaVersion":1,"kind":"request","requestId":"r","sequence":1.0,"method":"hns_accounts","params":null}""" to "invalidRequest",
            """{"schemaVersion":1,"kind":"request","requestId":"r","sequence":1,"method":7,"params":null}""" to "invalidRequest",
            """{"schemaVersion":1,"kind":"request","requestId":"r","sequence":1,"method":"asset_getBalance","params":{"module":7}}""" to "invalidParams",
        )
        for ((raw, code) in typedFieldFailures) {
            expectCode(code) { MobileWalletProviderProtocol.parseRequest(raw) }
        }
        expectCode("requestTooLarge") {
            val raw = JSONObject()
                .put("schemaVersion", 1)
                .put("kind", "request")
                .put("requestId", "utf8-bound")
                .put("sequence", 4)
                .put("method", "hns_send")
                .put("params", JSONObject().put("memo", "é".repeat(8_193)))
                .toString()
            MobileWalletProviderProtocol.parseRequest(raw)
        }
    }

    @Test
    fun bridgeInitializeScalarsDoNotCoerce() {
        val exact = JSONObject()
            .put("schemaVersion", 1)
            .put("kind", "initialize")
            .put("origin", "https://example.test")
        assertTrue(AndroidWalletProviderBridge.hasExactProviderSchema(exact))
        assertTrue(AndroidWalletProviderBridge.isExactInitializeFrame(exact))
        assertEquals(
            "https://example.test",
            AndroidWalletProviderBridge.exactStringField(exact, "origin"),
        )
        for (schema in listOf("1", 1.0, true)) {
            assertFalse(
                AndroidWalletProviderBridge.hasExactProviderSchema(
                    JSONObject(exact.toString()).put("schemaVersion", schema),
                ),
            )
        }
        assertFalse(
            AndroidWalletProviderBridge.isExactInitializeFrame(
                JSONObject(exact.toString()).put("kind", 1),
            ),
        )
        assertNull(
            AndroidWalletProviderBridge.exactStringField(
                JSONObject(exact.toString()).put("origin", 7),
                "origin",
            ),
        )

        val initialized = JSONObject(
            MobileWalletProviderProtocol.response(
                request = null,
                result = AndroidWalletProviderBridge.initializationResult(
                    setOf("wallet_getStatus", "hns_accounts"),
                ),
            ),
        )
        assertTrue(initialized.getBoolean("ok"))
        val result = initialized.getJSONObject("result")
        assertEquals(1, result.getInt("providerApiVersion"))
        assertEquals(
            listOf("hns_accounts", "wallet_getStatus"),
            result.getJSONArray("methods").let { methods ->
                List(methods.length()) { index -> methods.getString(index) }
            },
        )
    }

    @Test
    fun nativeResultsAndEventsRejectSecretMaterialAndHideInternalMessages() {
        val request = MobileWalletProviderProtocol.parseRequest(
            """{"schemaVersion":1,"kind":"request","requestId":"r-result","sequence":1,"method":"hns_send","params":{"amount":"1","address":"hs1test"}}""",
        )
        expectCode("invalidResult") {
            MobileWalletProviderProtocol.response(
                request,
                JSONObject().put("recovery_phrase", "never expose this"),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject()
                    .put("event", "accountsChanged")
                    .put("modules", org.json.JSONArray().put("handshake"))
                    .put("privateKey", "never expose this"),
            )
        }
        for (
            field in listOf(
                "protocolVersion", "requestNonce", "walletSession", "authorityHandle",
                "authorityRevision", "hostSessionId", "serviceSessionId",
                "runtimeSessionId", "browserRuntimeSessionId", "browserAuthoritySession",
                "restartGeneration", "channelSequence", "eventSequence", "runtimeGeneration",
                "policyGeneration", "navigationGeneration", "decisionFingerprint",
                "validUntilUnixMs", "engineContext",
            )
        ) {
            expectCode("invalidResult") {
                MobileWalletProviderProtocol.response(
                    request,
                    JSONObject().put(field, "private envelope material"),
                )
            }
        }
        for (field in listOf("event", "events", "approvalRequired")) {
            expectCode("invalidResult") {
                MobileWalletProviderProtocol.response(
                    request,
                    JSONObject().put(field, JSONObject()),
                )
            }
        }
        val nestedPrivateRoutes = listOf(
            JSONObject().put(
                "outer",
                JSONArray().put(JSONObject().put("event", "accountsChanged")),
            ),
            JSONObject().put("outer", JSONObject().put("events", JSONArray())),
            JSONObject().put(
                "outer",
                JSONObject().put("approvalRequired", JSONObject()),
            ),
            JSONObject().put(
                "outer",
                JSONObject()
                    .put("approvalId", "AQEBAQEBAQEBAQEBAQEBAQ")
                    .put("expiresAtUnixMs", 2_000_000_000_000L)
                    .put("summary", JSONObject()),
            ),
            JSONObject().put(
                "outer",
                JSONArray().put(JSONObject().put("requestNonce", 1)),
            ),
        )
        for (result in nestedPrivateRoutes) {
            expectCode("invalidResult") {
                MobileWalletProviderProtocol.response(request, result)
            }
        }
        val permissionRequest = MobileWalletProviderProtocol.parseRequest(
            """{"schemaVersion":1,"kind":"request","requestId":"r-permissions","sequence":2,"method":"wallet_getPermissions","params":null}""",
        )
        val permissionResult = JSONObject(
            MobileWalletProviderProtocol.response(
                permissionRequest,
                JSONObject().put("permissionGeneration", 7).put("capabilities", JSONArray()),
            ),
        )
        assertTrue(permissionResult.getBoolean("ok"))
        assertEquals(7, permissionResult.getJSONObject("result").getInt("permissionGeneration"))
        expectCode("invalidResult") {
            MobileWalletProviderProtocol.response(
                request,
                JSONObject().put("permissionGeneration", 7),
            )
        }
        expectCode("invalidResult") {
            MobileWalletProviderProtocol.response(
                permissionRequest,
                JSONObject().put(
                    "nested",
                    JSONObject().put("permissionGeneration", 7),
                ),
            )
        }
        for (code in listOf("invalidApproval", "approvalTooLarge")) {
            val publicError = JSONObject(
                MobileWalletProviderProtocol.response(
                    request,
                    error = WalletProviderException(code, "private diagnostic"),
                ),
            ).getJSONObject("error")
            assertEquals(code, publicError.getString("code"))
            assertFalse(publicError.getString("message").contains("private diagnostic"))
        }
        val encoded = MobileWalletProviderProtocol.response(
            request,
            error = IllegalStateException("sensitive native diagnostic"),
        )
        val error = JSONObject(encoded).getJSONObject("error")
        assertEquals("internalError", error.getString("code"))
        assertEquals("Wallet request failed", error.getString("message"))
        assertFalse(encoded.contains("sensitive native diagnostic"))
    }

    @Test
    fun immutableReleaseGatesOverrideOptimisticCallerState() {
        assertEquals(25, WalletScreen.entries.size)
        assertFalse(MobileWalletProviderProtocol.PROVIDER_BRIDGE_RELEASE_QUALIFIED)
        assertFalse(MobileWalletProviderProtocol.WALLET_RUNTIME_RELEASE_QUALIFIED)
        assertFalse(MobileWalletProviderProtocol.APPROVAL_RUNTIME_RELEASE_QUALIFIED)
        assertFalse(MobileWalletProviderProtocol.VALUE_RUNTIME_RELEASE_QUALIFIED)
        assertFalse(WalletUiState().allowsValueAction())
        assertFalse(WalletUiState().allowsApprovalAction())
        val optimistic = WalletUiState(walletAvailable = true, locked = false)
        assertFalse(optimistic.allowsValueAction())
        assertFalse(optimistic.allowsApprovalAction())
        for (
            method in listOf(
                "wallet_getStatus", "wallet_requestPermissions", "hns_signTypedMessage",
                "hns_send", "asset_send", "swap_redeem",
            )
        ) {
            expectCode("walletUnavailable") {
                MobileWalletProviderProtocol.requireMethodReleaseQualified(method)
            }
        }
        expectCode("walletUnavailable") {
            MobileWalletProviderProtocol.validateCapabilities(
                WalletCapabilitiesV2(
                    available = true,
                    abiVersion = 2,
                    walletSession = "optimistic-session",
                    permissionGeneration = 1,
                    methods = setOf("wallet_getStatus"),
                ),
            )
        }
        assertTrue(
            MobileWalletProviderProtocol.hasValidCapabilitySnapshot(
                WalletCapabilitiesV2(
                    available = true,
                    abiVersion = 2,
                    walletSession = "never-authorized-session",
                    permissionGeneration = 0,
                    methods = setOf("wallet_getCapabilities", "wallet_requestPermissions"),
                ),
            ),
        )
        assertFalse(
            MobileWalletProviderProtocol.hasValidCapabilitySnapshot(
                WalletCapabilitiesV2(
                    available = true,
                    abiVersion = 2,
                    walletSession = "invalid-session",
                    permissionGeneration = -1,
                    methods = setOf("wallet_requestPermissions"),
                ),
            ),
        )
        val browserAuthority = WalletBrowserAuthority(
            origin = "https://example.test",
            namespace = "hns",
            browserAuthoritySession = "browser-session",
            runtimeGeneration = 1,
            policyGeneration = 1,
            navigationGeneration = 1,
            decisionFingerprint = "01".repeat(32),
            validUntilUnixMs = 2,
            engineContext = WalletEngineAuthorityContext(),
        )
        assertTrue(browserAuthority.isCurrent(1))
        assertFalse(browserAuthority.isCurrent(2))
        assertFalse(
            browserAuthority == browserAuthority.copy(engineContext = WalletEngineAuthorityContext()),
        )
        val providerAuthority = WalletProviderAuthority(browserAuthority, "wallet-session", 1)
        assertFalse(providerAuthority == providerAuthority.copy(walletSession = "replacement-session"))
        assertFalse(providerAuthority == providerAuthority.copy(permissionGeneration = 2))
        assertFalse(
            UnavailableMobileWalletAbiV2.capabilities(browserAuthority).available,
        )
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: WalletProviderException) {
            assertEquals(code, error.code)
        }
    }

    private companion object {
        val noApprovalMethods = setOf(
            "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_getPermissions",
            "wallet_revokePermissions", "wallet_lock", "wallet_getStatus", "hns_accounts",
            "hns_getBalance", "hns_getTransactions", "hns_getReceiveAddress", "hns_getNames",
            "hns_getName", "hns_importKnownName", "asset_getAccount", "asset_getBalance",
            "asset_getTransactions", "asset_getReceiveTarget", "nameMarket_listOffers",
            "nameMarket_getSession", "swap_getSupportedPairs", "swap_getPriceRound",
            "swap_listMarketIntents", "swap_getSession",
        )
        val approvalOnlyMethods = setOf(
            "wallet_enableModule", "wallet_disableModule", "wallet_requestPermissions",
            "hns_requestAccounts", "hns_signTypedMessage",
        )
        val approvalAndValueMethods = setOf(
            "hns_send", "hns_transferName", "hns_finalizeName", "asset_send",
            "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
            "nameMarket_acceptOffer", "nameMarket_finalizePurchase", "nameMarket_recoverName",
            "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
            "swap_acceptFill", "swap_redeem", "swap_refund",
        )
        val allMethods = noApprovalMethods + approvalOnlyMethods + approvalAndValueMethods
    }
}
