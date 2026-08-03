package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class MobileWalletEventProjectionTest {
    @Test
    fun allThirteenTypedEventsProjectToProviderSchemaOne() {
        val nativePayloads = listOf(
            JSONObject().put("event", "connect").put("permissionGeneration", 1),
            JSONObject().put("event", "disconnect").put("reason", "authorityRevoked"),
            JSONObject()
                .put("event", "permissionsChanged")
                .put("permissionGeneration", 2)
                .put("capabilities", JSONArray().put("accounts").put("send")),
            moduleEvent("modulesChanged"),
            moduleEvent("accountsChanged"),
            moduleEvent("balancesChanged"),
            moduleEvent("transactionsChanged"),
            stringListEvent("namesChanged", "names", "example"),
            stringListEvent("nameMarketChanged", "listingIds", "listing-1"),
            stringListEvent("priceRoundChanged", "pairs", "HNS/BTC"),
            stringListEvent("marketIntentChanged", "marketIntentIds", "intent-1"),
            stringListEvent("swapSessionChanged", "swapSessionIds", "swap-1"),
            JSONObject().put("event", "walletLocked"),
        )

        val projectedNames = nativePayloads.map { nativePayload ->
            val encoded = JSONObject(MobileWalletProviderProtocol.event(nativePayload))
            assertEquals(1, encoded.getInt("schemaVersion"))
            assertEquals("event", encoded.getString("kind"))
            val publicPayload = encoded.getJSONObject("payload")
            assertFalse(publicPayload.has("event"))
            for (field in privateEnvelopeFields) assertFalse(publicPayload.has(field))
            encoded.getString("event")
        }.toSet()

        assertEquals(MobileWalletProviderProtocol.events, projectedNames)
    }

    @Test
    fun rawPrivateEnvelopeAndNonExactPayloadsFailClosed() {
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject()
                    .put("protocolVersion", 2)
                    .put("hostSessionId", "private")
                    .put("serviceSessionId", "private")
                    .put("restartGeneration", 1)
                    .put("channelSequence", 1)
                    .put("authorityHandle", "private")
                    .put("authorityRevision", 1)
                    .put("eventSequence", 1)
                    .put("payload", JSONObject().put("event", "walletLocked")),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject()
                    .put("event", "connect")
                    .put("permissionGeneration", 1)
                    .put("eventSequence", 1),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject().put("event", "connect").put("permissionGeneration", 0),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject().put("event", "connect").put("permissionGeneration", "1"),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject().put("event", "disconnect").put("reason", "pageRequested"),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                JSONObject()
                    .put("event", "modulesChanged")
                    .put("modules", JSONArray().put("ethereum").put("handshake")),
            )
        }
        expectCode("invalidEvent") {
            MobileWalletProviderProtocol.event(
                stringListEvent("namesChanged", "names", "contains\ncontrol"),
            )
        }
        expectCode("eventTooLarge") {
            val names = JSONArray().apply {
                repeat(128) { put("x".repeat(600)) }
            }
            MobileWalletProviderProtocol.event(
                JSONObject().put("event", "namesChanged").put("names", names),
            )
        }
    }

    private fun moduleEvent(event: String): JSONObject = JSONObject()
        .put("event", event)
        .put("modules", JSONArray().put("handshake").put("ethereum"))

    private fun stringListEvent(event: String, field: String, value: String): JSONObject =
        JSONObject().put("event", event).put(field, JSONArray().put(value))

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: WalletProviderException) {
            assertEquals(code, error.code)
        }
    }

    private companion object {
        val privateEnvelopeFields = setOf(
            "protocolVersion", "hostSessionId", "serviceSessionId", "restartGeneration",
            "channelSequence", "authorityHandle", "authorityRevision", "eventSequence",
        )
    }
}
