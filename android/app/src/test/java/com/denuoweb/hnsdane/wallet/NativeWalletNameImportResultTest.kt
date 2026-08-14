package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletNameImportResultTest {
    @Test
    fun exactSuccessSummaryParsesThroughTheClosedHnwiV1Schema() {
        val parsed = NativeWalletNameImportResult.parse(bundle(STATUS_SUCCESS, summary()))
        val success = parsed as? NativeWalletNameImportResult.Success
        assertEquals("alpha", success?.summary?.name)
        assertEquals("ab".repeat(32), success?.summary?.nameHash)
        assertEquals(7L, success?.summary?.proofHeight)
        assertEquals("canonicalDecoded", success?.summary?.resourceStatus)
        assertEquals("walletOwned", success?.summary?.ownershipStatus)
        assertEquals(true, success?.summary?.registered)
        assertEquals(false, success?.summary?.expired)
    }

    @Test
    fun emptyNonSuccessOutcomesAreExactAndClosed() {
        assertSame(
            NativeWalletNameImportResult.InvalidInput,
            NativeWalletNameImportResult.parse(bundle(STATUS_INVALID)),
        )
        assertSame(
            NativeWalletNameImportResult.Unavailable,
            NativeWalletNameImportResult.parse(bundle(STATUS_UNAVAILABLE)),
        )
        assertSame(
            NativeWalletNameImportResult.Failed,
            NativeWalletNameImportResult.parse(bundle(STATUS_FAILED)),
        )
        assertNull(NativeWalletNameImportResult.parse(bundle(0)))
        assertNull(NativeWalletNameImportResult.parse(bundle(5)))
        assertNull(NativeWalletNameImportResult.parse(bundle(STATUS_INVALID, summary())))
        assertNull(NativeWalletNameImportResult.parse(bundle(STATUS_SUCCESS)))
    }

    @Test
    fun envelopeVersionReservedLengthAndTrailingBytesFailClosed() {
        val valid = bundle(STATUS_SUCCESS, summary())
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf().apply { this[0] = 0 }))
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf().apply { this[4] = 2 }))
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf().apply { this[6] = 1 }))
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf().apply { this[7] = 1 }))
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf(valid.size - 1)))
        assertNull(NativeWalletNameImportResult.parse(valid + byteArrayOf(0)))
        assertNull(NativeWalletNameImportResult.parse(valid.copyOf().apply { this[11]++ }))
    }

    @Test
    fun successRequiresTheExactMinimizedNameShape() {
        rejectSuccess { it.put("ownerOutpoint", "private") }
        rejectSuccess { it.remove("nameHash") }
        rejectSuccess { it.put("name", " Alpha") }
        rejectSuccess { it.put("name", "alpha.") }
        rejectSuccess { it.put("name", "álpha") }
        rejectSuccess { it.put("name", "a".repeat(64)) }
        rejectSuccess { it.put("name", "example") }
        rejectSuccess { it.put("name", "-alpha") }
        rejectSuccess { it.put("name", "alpha_") }
        rejectSuccess { it.put("nameHash", "AB".repeat(32)) }
        rejectSuccess { it.put("nameHash", "ab".repeat(31)) }
        rejectSuccess { it.put("proofHeight", -1) }
        rejectSuccess { it.put("proofHeight", 1.5) }
        rejectSuccess { it.put("resourceStatus", "raw") }
        rejectSuccess { it.put("ownershipStatus", "marketListed") }
        rejectSuccess { it.put("registered", 1) }
        rejectSuccess { it.put("expired", "false") }
    }

    @Test
    fun bridgeWipesBundlesAndExactUtf8EncodingNeverEditsText() {
        val encoded = bundle(STATUS_SUCCESS, summary())
        val parsed = NativeWalletBridge.parseAndWipeHnsNameImportBundle(encoded)
        assertEquals("alpha", (parsed as NativeWalletNameImportResult.Success).summary.name)
        assertTrue(encoded.all { it == 0.toByte() })

        val exact = " Alpha-.".toByteArray(Charsets.UTF_8)
        val bridged = walletNameExactUtf8(" Alpha-.")
        assertArrayEquals(exact, bridged)
        bridged?.fill(0)
        assertNull(walletNameExactUtf8("\uD800"))
        assertNull(walletNameExactUtf8("a".repeat(4 * 1024 + 1)))
    }

    @Test
    fun successRequiresTheSameExactNameAndHashInTheFreshSnapshot() {
        val imported = (NativeWalletNameImportResult.parse(
            bundle(STATUS_SUCCESS, summary()),
        ) as NativeWalletNameImportResult.Success).summary
        val matching = readSnapshot(imported)
        assertTrue(walletNameImportRefreshMatches(imported, matching))
        assertTrue(!walletNameImportRefreshMatches(imported, readSnapshot(imported.copy(
            name = "beta",
        ))))
        assertTrue(!walletNameImportRefreshMatches(imported, readSnapshot(imported.copy(
            nameHash = "cd".repeat(32),
        ))))
        assertTrue(!walletNameImportRefreshMatches(imported, readSnapshot(null)))
    }

    private fun rejectSuccess(mutate: (JSONObject) -> Unit) {
        val candidate = summary()
        mutate(candidate)
        assertNull(NativeWalletNameImportResult.parse(bundle(STATUS_SUCCESS, candidate)))
    }

    private fun summary(): JSONObject = JSONObject()
        .put("name", "alpha")
        .put("nameHash", "ab".repeat(32))
        .put("proofHeight", 7)
        .put("resourceStatus", "canonicalDecoded")
        .put("ownershipStatus", "walletOwned")
        .put("registered", true)
        .put("expired", false)

    private fun bundle(status: Int, value: JSONObject? = null): ByteArray {
        val json = value?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        return ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte()))
            put(1)
            put(status.toByte())
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()
    }

    private fun readSnapshot(name: NativeWalletName?): NativeWalletReadSnapshot =
        NativeWalletReadSnapshot(
            balanceBaseUnits = "0",
            paymentReceiveTarget = NativeWalletPaymentReceiveTarget(
                accountId = "01".repeat(16),
                display = "rs1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq8euwz",
                derivationIndex = 0,
            ),
            nameReceiveTarget = NativeWalletNameReceiveTarget(
                accountId = "01".repeat(16),
                display = "rs1qnameowner0000000000000000000000000000000",
                derivationIndex = 1,
            ),
            height = 42,
            transactions = emptyList(),
            trackedNames = listOfNotNull(name),
        )

    private companion object {
        const val STATUS_SUCCESS = 1
        const val STATUS_INVALID = 2
        const val STATUS_UNAVAILABLE = 3
        const val STATUS_FAILED = 4
    }
}
