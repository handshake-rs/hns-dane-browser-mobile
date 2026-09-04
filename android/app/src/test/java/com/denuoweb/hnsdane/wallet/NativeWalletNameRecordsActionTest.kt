package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletNameRecordsActionTest {
    @Test
    fun setRecordsIntentUsesTheClosedMultilineShape() {
        val encoded = NativeHnsValueIntent.SetNameRecords(
            name = "example",
            records = "NS ns1.example.\nGLUE4 ns1.example. 192.0.2.1",
            maximumFeeBaseUnits = "50000",
        ).encodeJson()
        val value = JSONObject(requireNotNull(encoded).toString(Charsets.UTF_8))
        assertEquals(setOf("action", "name", "records", "maximumFee"), value.keys().asSequence().toSet())
        assertEquals("setNameRecords", value.getString("action"))
        assertTrue(value.getString("records").contains('\n'))
        assertEquals("50000", value.getString("maximumFee"))
    }

    @Test
    fun nameUpdateApprovalDisplaysTheExactValidatedResource() {
        val resourceHex = "0004c0000201"
        val summary = JSONObject()
            .put("kind", "nameUpdate")
            .put("name", "example")
            .put("resourceHex", resourceHex)
            .put("resourceBytes", resourceHex.length / 2)
            .put("recordCount", 1)
            .put(
                "maximumFee",
                JSONObject().put("asset", "HNS").put("base_units", "50000"),
            )
            .put("warnings", JSONArray().put("feeEstimateMayChange"))
        val approval = NativeHnsValueApproval.parse(bundle(summary))
        requireNotNull(approval)
        assertEquals(NativeHnsValueApprovalKind.NAME_UPDATE, approval.kind)
        assertEquals("Set Handshake resource records", approval.title)
        assertTrue(approval.detailLines.contains("Records: 1"))
        assertTrue(approval.detailLines.contains("Exact resource hex: $resourceHex"))
        approval.close()

        assertNull(
            NativeHnsValueApproval.parse(
                bundle(JSONObject(summary.toString()).put("resourceHex", resourceHex.uppercase())),
            ),
        )
        assertNull(
            NativeHnsValueApproval.parse(
                bundle(JSONObject(summary.toString()).put("privateOwner", "must-not-appear")),
            ),
        )
    }

    private fun bundle(summary: JSONObject): ByteArray {
        val payload = JSONObject()
            .put("actionToken", "ab".repeat(32))
            .put("expiresAtUnix", 2_000_000_000L)
            .put("summary", summary)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put("HNVP".toByteArray(Charsets.US_ASCII))
            .put(1)
            .put(0)
            .putShort(0)
            .putInt(payload.size)
            .put(payload)
            .array()
    }
}
