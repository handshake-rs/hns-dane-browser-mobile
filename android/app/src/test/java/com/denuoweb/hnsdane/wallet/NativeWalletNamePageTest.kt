package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeWalletNamePageTest {
    @Test
    fun exactPageParsesWithoutRenderingTheWholeCollection() {
        val value = JSONObject()
            .put("offset", 64)
            .put("total", 2_448)
            .put("names", JSONArray().put(name("second")))
            .put("hasMore", true)
        val parsed = NativeWalletNamePage.parse(bundle(value))
        assertEquals(64, parsed?.offset)
        assertEquals(2_448, parsed?.total)
        assertEquals(listOf("second"), parsed?.names?.map(NativeWalletName::name))
        assertEquals(true, parsed?.hasMore)
    }

    @Test
    fun pageRejectsIncoherentContinuationAndUnknownFields() {
        val wrongContinuation = JSONObject()
            .put("offset", 64)
            .put("total", 65)
            .put("names", JSONArray().put(name("second")))
            .put("hasMore", true)
        assertNull(NativeWalletNamePage.parse(bundle(wrongContinuation)))

        val unknown = JSONObject()
            .put("offset", 0)
            .put("total", 0)
            .put("names", JSONArray())
            .put("hasMore", false)
            .put("cursor", "opaque")
        assertNull(NativeWalletNamePage.parse(bundle(unknown)))
    }

    private fun name(value: String): JSONObject = JSONObject()
        .put("name", value)
        .put("nameHash", "ab".repeat(32))
        .put("proofHeight", 7)
        .put("resourceStatus", "canonicalDecoded")
        .put("ownershipStatus", "walletOwned")
        .put("registered", true)
        .put("expired", false)
        .put("canonicalState", JSONObject.NULL)
        .put("rawResourceHex", JSONObject.NULL)
        .put("resourceRecordCount", JSONObject.NULL)

    private fun bundle(value: JSONObject): ByteArray {
        val json = value.toString().toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte()))
            put(1)
            put(0)
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()
    }
}
