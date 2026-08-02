package com.denuoweb.hnsdane.wallet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MobileWalletProviderProtocolTest {
    @Test
    fun completeSurfaceAndRestrictedExternalModules() {
        assertEquals(43, MobileWalletProviderProtocol.methods.size)
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
                "accountsChanged",
                JSONObject().put("privateKey", "never expose this"),
            )
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
    fun walletScreensExistButUnavailableStateCannotMoveValue() {
        assertEquals(25, WalletScreen.entries.size)
        assertFalse(WalletUiState().allowsValueAction())
        assertTrue(
            WalletUiState(walletAvailable = true, locked = false).allowsValueAction(),
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
}
