package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletReadSnapshotTest {
    @Test
    fun exactTipBoundHnsProjectionParses() {
        val parsed = NativeWalletReadSnapshot.parse(bundle(snapshot()))
        assertEquals("1234567", parsed?.balanceBaseUnits)
        assertEquals("hs1qreadtarget", parsed?.receiveAddress)
        assertEquals(7L, parsed?.height)
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111", parsed?.transactions?.single()?.txid)
        assertEquals("example", parsed?.trackedNames?.single()?.name)
        assertTrue(parsed?.trackedNames?.single()?.registered == true)
    }

    @Test
    fun envelopeAndExactSchemaFailClosed() {
        val valid = bundle(snapshot())
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[0] = 0 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[4] = 2 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[5] = 3 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[6] = 1 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf(valid.size - 1)))

        val unknown = snapshot().put("sendEnabled", false)
        assertNull(NativeWalletReadSnapshot.parse(bundle(unknown)))
        val missing = snapshot().apply { remove("knownNames") }
        assertNull(NativeWalletReadSnapshot.parse(bundle(missing)))
    }

    @Test
    fun nonHnsOrIncoherentReadStateFailsClosed() {
        fun reject(mutate: (JSONObject) -> Unit) {
            val candidate = snapshot()
            mutate(candidate)
            assertNull(NativeWalletReadSnapshot.parse(bundle(candidate)))
        }

        reject { it.getJSONObject("balance").put("asset", "BTC") }
        reject { it.getJSONObject("balance").put("base_units", 1234567) }
        reject { it.getJSONObject("receiveTarget").put("module", "bitcoin") }
        reject { it.getJSONObject("moduleStatus").put("phase", "degraded") }
        reject { it.getJSONObject("moduleStatus").put("scanned_height", 6) }
        reject { it.getJSONObject("moduleStatus").put("last_error", "ignored") }
        reject {
            it.getJSONArray("transactionHistory")
                .getJSONObject(0)
                .put("module", "ethereum")
        }
        reject {
            it.getJSONArray("knownNames")
                .getJSONObject(0)
                .put("ownershipStatus", "marketListed")
        }
    }

    private fun snapshot(): JSONObject = JSONObject()
        .put(
            "balance",
            JSONObject()
                .put("asset", "HNS")
                .put("base_units", "1234567"),
        )
        .put(
            "receiveTarget",
            JSONObject()
                .put("module", "handshake")
                .put("account", bytes(16, 0x22))
                .put("display", "hs1qreadtarget")
                .put("derivation_index", 3),
        )
        .put(
            "transactionHistory",
            JSONArray().put(
                JSONObject()
                    .put("module", "handshake")
                    .put("txid", bytes(32, 0x11))
                    .put("status", "confirmed")
                    .put(
                        "net_amount",
                        JSONObject().put("negative", false).put("magnitude", "1000000"),
                    )
                    .put("fee", "1000")
                    .put("block_height", 7)
                    .put("first_seen_unix", 1_700_000_000)
                    .put("confirmation_count", 1),
            ),
        )
        .put(
            "knownNames",
            JSONArray().put(
                JSONObject()
                    .put("name", "example")
                    .put("nameHash", "ab".repeat(32))
                    .put("proofHeight", 7)
                    .put("resourceStatus", "canonicalDecoded")
                    .put("ownershipStatus", "walletOwned")
                    .put("registered", true)
                    .put("expired", false),
            ),
        )
        .put(
            "moduleStatus",
            JSONObject()
                .put("phase", "ready")
                .put("validated_height", 7)
                .put("scanned_height", 7)
                .put("target_height", 7)
                .put("last_error", JSONObject.NULL),
        )

    private fun bytes(length: Int, value: Int): JSONArray = JSONArray().apply {
        repeat(length) { put(value) }
    }

    private fun bundle(value: JSONObject): ByteArray {
        val json = value.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'R'.code.toByte()))
            put(1)
            put(1)
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()
    }
}
