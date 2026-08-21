package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletReadSnapshotTest {
    @Test
    fun exactV2TipBoundHnsProjectionParsesDistinctReceiveTargets() {
        val parsed = NativeWalletReadSnapshot.parse(bundle(snapshot(), version = 2))
        assertEquals("1234567", parsed?.balanceBaseUnits)
        assertEquals("22".repeat(16), parsed?.paymentReceiveTarget?.accountId)
        assertEquals("hs1qreadtarget", parsed?.paymentReceiveTarget?.display)
        assertEquals(3L, parsed?.paymentReceiveTarget?.derivationIndex)
        assertEquals("22".repeat(16), parsed?.nameReceiveTarget?.accountId)
        assertEquals("hs1qnametarget", parsed?.nameReceiveTarget?.display)
        assertEquals(4L, parsed?.nameReceiveTarget?.derivationIndex)
        assertNotEquals(
            parsed?.paymentReceiveTarget?.display,
            parsed?.nameReceiveTarget?.display,
        )
        assertEquals(7L, parsed?.height)
        assertEquals(
            "1111111111111111111111111111111111111111111111111111111111111111",
            parsed?.transactions?.single()?.txid,
        )
        assertEquals("alpha", parsed?.trackedNames?.single()?.name)
        assertTrue(parsed?.trackedNames?.single()?.registered == true)
    }

    @Test
    fun exactLegacyV1ProjectionParsesWithoutNameReceiveTarget() {
        val parsed = NativeWalletReadSnapshot.parse(
            bundle(snapshot(includeNameReceiveTarget = false), version = 1),
        )
        assertEquals("hs1qreadtarget", parsed?.paymentReceiveTarget?.display)
        assertNull(parsed?.nameReceiveTarget)
    }

    @Test
    fun envelopeVersionsFlagsReservedAndLengthFailClosed() {
        val valid = bundle(snapshot(), version = 2)
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[0] = 0 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[4] = 0 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[4] = 3 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[5] = 0 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[5] = 3 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[6] = 1 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf().apply { this[7] = 1 }))
        assertNull(NativeWalletReadSnapshot.parse(valid.copyOf(valid.size - 1)))
        assertNull(NativeWalletReadSnapshot.parse(valid + byteArrayOf(0)))
    }

    @Test
    fun envelopeVersionSelectsOneExactRootSchema() {
        val legacyShape = snapshot(includeNameReceiveTarget = false)
        val nameReceiveShape = snapshot(includeNameReceiveTarget = true)

        assertNull(NativeWalletReadSnapshot.parse(bundle(nameReceiveShape, version = 1)))
        assertNull(NativeWalletReadSnapshot.parse(bundle(legacyShape, version = 2)))

        val unknownV2 = snapshot().put("sendEnabled", false)
        assertNull(NativeWalletReadSnapshot.parse(bundle(unknownV2, version = 2)))
        val missingV1 = snapshot(includeNameReceiveTarget = false).apply { remove("knownNames") }
        assertNull(NativeWalletReadSnapshot.parse(bundle(missingV1, version = 1)))
        val unknownTargetField = snapshot().apply {
            getJSONObject("nameReceiveTarget").put("role", "HnsName")
        }
        assertNull(NativeWalletReadSnapshot.parse(bundle(unknownTargetField, version = 2)))
    }

    @Test
    fun receiveTargetsRequireOneEqualNonzeroHandshakeAccount() {
        rejectV2 { it.getJSONObject("receiveTarget").put("module", "bitcoin") }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("module", "bitcoin") }
        rejectV2 { it.getJSONObject("receiveTarget").put("account", bytes(16, 0)) }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("account", bytes(16, 0)) }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("account", bytes(16, 0x23)) }
        rejectV2 { it.getJSONObject("receiveTarget").put("account", bytes(15, 0x22)) }
        rejectV2 {
            it.getJSONObject("nameReceiveTarget")
                .getJSONArray("account")
                .put(0, 256)
        }
        rejectV2 {
            it.getJSONObject("nameReceiveTarget")
                .getJSONArray("account")
                .put(0, "34")
        }
    }

    @Test
    fun receiveDisplaysAreBoundedVisibleAsciiAndNeverConflated() {
        rejectV2 { it.getJSONObject("receiveTarget").put("display", "") }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("display", "") }
        rejectV2 { it.getJSONObject("receiveTarget").put("display", "hs1qé") }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("display", "hs1qé") }
        rejectV2 { it.getJSONObject("receiveTarget").put("display", "hs1q\naddress") }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("display", "hs1q address") }
        rejectV2 { it.getJSONObject("receiveTarget").put("display", "p".repeat(513)) }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("display", "n".repeat(513)) }
        rejectV2 {
            val payment = it.getJSONObject("receiveTarget").getString("display")
            it.getJSONObject("nameReceiveTarget").put("display", payment)
        }

        val boundary = snapshot().apply {
            getJSONObject("receiveTarget")
                .put("display", "p".repeat(512))
                .put("derivation_index", UINT32_MAX)
            getJSONObject("nameReceiveTarget")
                .put("display", "n".repeat(512))
                .put("derivation_index", UINT32_MAX)
        }
        val parsed = NativeWalletReadSnapshot.parse(bundle(boundary, version = 2))
        assertEquals(512, parsed?.paymentReceiveTarget?.display?.length)
        assertEquals(512, parsed?.nameReceiveTarget?.display?.length)
        assertEquals(UINT32_MAX, parsed?.paymentReceiveTarget?.derivationIndex)
        assertEquals(UINT32_MAX, parsed?.nameReceiveTarget?.derivationIndex)
    }

    @Test
    fun receiveIndicesAreExactUnsigned32BitIntegers() {
        rejectV2 { it.getJSONObject("receiveTarget").put("derivation_index", -1) }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("derivation_index", -1) }
        rejectV2 {
            it.getJSONObject("receiveTarget").put("derivation_index", UINT32_MAX + 1)
        }
        rejectV2 {
            it.getJSONObject("nameReceiveTarget").put("derivation_index", UINT32_MAX + 1)
        }
        rejectV2 { it.getJSONObject("receiveTarget").put("derivation_index", 1.5) }
        rejectV2 { it.getJSONObject("nameReceiveTarget").put("derivation_index", "4") }
    }

    @Test
    fun nonHnsOrIncoherentReadStateFailsClosed() {
        rejectV2 { it.getJSONObject("balance").put("asset", "BTC") }
        rejectV2 { it.getJSONObject("balance").put("base_units", 1234567) }
        rejectV2 { it.getJSONObject("moduleStatus").put("phase", "degraded") }
        rejectV2 { it.getJSONObject("moduleStatus").put("scanned_height", 6) }
        rejectV2 { it.getJSONObject("moduleStatus").put("last_error", "ignored") }
        rejectV2 {
            it.getJSONArray("transactionHistory")
                .getJSONObject(0)
                .put("module", "ethereum")
        }
        rejectV2 {
            it.getJSONArray("knownNames")
                .getJSONObject(0)
                .put("ownershipStatus", "marketListed")
        }
    }

    @Test
    fun acceptedV2BundleIsStillWipedByTheNativeBridgeBoundary() {
        val encoded = bundle(snapshot(), version = 2)
        val parsed = NativeWalletBridge.parseAndWipeHnsReadBundle(encoded)
        assertNotNull(parsed?.nameReceiveTarget)
        assertTrue(encoded.all { it == 0.toByte() })
    }

    @Test
    fun localReceiveTargetParsesBeforeSynchronizationAndTheBridgeWipesIt() {
        val encoded = localReceiveBundle(
            JSONObject()
                .put("module", "handshake")
                .put("account", bytes(16, 0x33))
                .put("display", "hs1qlocalreceivetarget")
                .put("derivation_index", UINT32_MAX),
        )
        val parsed = NativeWalletBridge.parseAndWipeLocalHnsReceiveTargetBundle(encoded)
        assertEquals("33".repeat(16), parsed?.accountId)
        assertEquals("hs1qlocalreceivetarget", parsed?.display)
        assertEquals(UINT32_MAX, parsed?.derivationIndex)
        assertTrue(encoded.all { it == 0.toByte() })
    }

    @Test
    fun localReceiveTargetIsClosedSchemaAndFailsClosed() {
        fun reject(mutate: (JSONObject) -> Unit) {
            val target = JSONObject()
                .put("module", "handshake")
                .put("account", bytes(16, 0x33))
                .put("display", "hs1qlocalreceivetarget")
                .put("derivation_index", 0)
            mutate(target)
            assertNull(NativeWalletPaymentReceiveTarget.parseLocal(localReceiveBundle(target)))
        }

        reject { it.put("module", "bitcoin") }
        reject { it.put("account", bytes(16, 0)) }
        reject { it.put("display", "hs1q local") }
        reject { it.put("derivation_index", UINT32_MAX + 1) }
        reject { it.put("untrustedTip", 7) }

        val malformed = localReceiveBundle(
            JSONObject()
                .put("module", "handshake")
                .put("account", bytes(16, 0x33))
                .put("display", "hs1qlocalreceivetarget")
                .put("derivation_index", 0),
        )
        malformed[5] = 1
        assertNull(NativeWalletPaymentReceiveTarget.parseLocal(malformed))
    }

    private fun rejectV2(mutate: (JSONObject) -> Unit) {
        val candidate = snapshot()
        mutate(candidate)
        assertNull(NativeWalletReadSnapshot.parse(bundle(candidate, version = 2)))
    }

    private fun snapshot(includeNameReceiveTarget: Boolean = true): JSONObject {
        val value = JSONObject()
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
                        .put("name", "alpha")
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
        if (includeNameReceiveTarget) {
            value.put(
                "nameReceiveTarget",
                JSONObject()
                    .put("module", "handshake")
                    .put("account", bytes(16, 0x22))
                    .put("display", "hs1qnametarget")
                    .put("derivation_index", 4),
            )
        }
        return value
    }

    private fun bytes(length: Int, value: Int): JSONArray = JSONArray().apply {
        repeat(length) { put(value) }
    }

    private fun bundle(value: JSONObject, version: Int): ByteArray {
        val json = value.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'R'.code.toByte()))
            put(version.toByte())
            put(1)
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()
    }

    private fun localReceiveBundle(value: JSONObject): ByteArray {
        val json = value.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()))
            put(1)
            put(0)
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()
    }

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
    }
}
