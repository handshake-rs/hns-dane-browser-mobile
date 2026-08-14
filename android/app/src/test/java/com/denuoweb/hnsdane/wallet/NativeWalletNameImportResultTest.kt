package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletNameImportResultTest {
    @Test
    fun exactSuccessSummaryParsesThroughTheClosedHnwiV1Schema() {
        val parsed = NativeWalletNameImportBundle.parse(bundle(summary()))
        assertEquals("alpha", parsed?.name)
        assertEquals("ab".repeat(32), parsed?.nameHash)
        assertEquals(7L, parsed?.proofHeight)
        assertEquals("canonicalDecoded", parsed?.resourceStatus)
        assertEquals("walletOwned", parsed?.ownershipStatus)
        assertEquals(true, parsed?.registered)
        assertEquals(false, parsed?.expired)
    }

    @Test
    fun envelopeFlagsReservedLengthAndTrailingBytesFailClosed() {
        val valid = bundle(summary())
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[0] = 0 }))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[4] = 2 }))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[5] = 1 }))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[6] = 1 }))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[7] = 1 }))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf(valid.size - 1)))
        assertNull(NativeWalletNameImportBundle.parse(valid + byteArrayOf(0)))
        assertNull(NativeWalletNameImportBundle.parse(valid.copyOf().apply { this[11]++ }))
        assertNull(NativeWalletNameImportBundle.parse(rawBundle(ByteArray(0))))
        assertNull(
            NativeWalletNameImportBundle.parse(
                rawBundle(ByteArray(MAX_IMPORT_JSON_BYTES + 1) { '{'.code.toByte() }),
            ),
        )
    }

    @Test
    fun successRequiresTheCanonicalExactMinimizedNameShape() {
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
    fun exactUiEncodingPreservesTextAndRejectsMalformedEmptyOrOversizeInput() {
        for (exact in listOf("Alpha", "alpha.", " alpha", "é")) {
            assertArrayEquals(exact.toByteArray(Charsets.UTF_8), exactWalletNameUtf8(exact))
        }
        assertNull(exactWalletNameUtf8(""))
        assertNull(exactWalletNameUtf8("a".repeat(64)))
        assertNull(exactWalletNameUtf8("é".repeat(32)))
        assertNull(exactWalletNameUtf8(String(charArrayOf('\ud800'))))
    }

    @Test
    fun bridgeWipesBundlesAndRejectedCallerOwnedInput() {
        val encoded = bundle(summary())
        assertNotNull(NativeWalletBridge.parseAndWipeHnsNameImportBundle(encoded))
        assertTrue(encoded.all { it == 0.toByte() })

        val noHandle = "alpha".toByteArray(Charsets.UTF_8)
        assertNull(NativeWalletBridge.importHnsNameExactText(0, noHandle))
        assertTrue(noHandle.all { it == 0.toByte() })

        val oversize = ByteArray(64) { 'a'.code.toByte() }
        assertNull(NativeWalletBridge.importHnsNameExactText(1, oversize))
        assertTrue(oversize.all { it == 0.toByte() })
    }

    @Test
    fun successMustEchoTheExactRequestedUtf8() {
        val imported = checkNotNull(NativeWalletNameImportBundle.parse(bundle(summary())))
        val exact = "alpha".toByteArray(Charsets.UTF_8)
        assertTrue(walletNameImportEchoMatches(imported, exact))
        assertFalse(walletNameImportEchoMatches(imported, "Alpha".toByteArray(Charsets.UTF_8)))
        exact.fill(0)
    }

    @Test
    fun freshSnapshotRequiresExactlyOneMatchingNameAndHashIdentity() {
        val imported = checkNotNull(NativeWalletNameImportBundle.parse(bundle(summary())))
        assertTrue(walletNameImportRefreshMatches(imported, readSnapshot(listOf(imported))))
        assertTrue(
            walletNameImportRefreshMatches(
                imported,
                readSnapshot(listOf(imported.copy(proofHeight = 8))),
            ),
        )
        assertFalse(walletNameImportRefreshMatches(imported, readSnapshot(emptyList())))
        assertFalse(
            walletNameImportRefreshMatches(
                imported,
                readSnapshot(listOf(imported.copy(name = "beta"))),
            ),
        )
        assertFalse(
            walletNameImportRefreshMatches(
                imported,
                readSnapshot(listOf(imported.copy(nameHash = "cd".repeat(32)))),
            ),
        )
        assertFalse(walletNameImportRefreshMatches(imported, readSnapshot(listOf(imported, imported))))
    }

    private fun rejectSuccess(mutate: (JSONObject) -> Unit) {
        val candidate = summary()
        mutate(candidate)
        assertNull(NativeWalletNameImportBundle.parse(bundle(candidate)))
    }

    private fun summary(): JSONObject = JSONObject()
        .put("name", "alpha")
        .put("nameHash", "ab".repeat(32))
        .put("proofHeight", 7)
        .put("resourceStatus", "canonicalDecoded")
        .put("ownershipStatus", "walletOwned")
        .put("registered", true)
        .put("expired", false)

    private fun bundle(value: JSONObject): ByteArray =
        rawBundle(value.toString().toByteArray(Charsets.UTF_8))

    private fun rawBundle(json: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES + json.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(
                'H'.code.toByte(),
                'N'.code.toByte(),
                'W'.code.toByte(),
                'I'.code.toByte(),
            ))
            put(1)
            put(0)
            putShort(0)
            putInt(json.size)
            put(json)
        }.array()

    private fun readSnapshot(names: List<NativeWalletName>): NativeWalletReadSnapshot =
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
            trackedNames = names,
        )

    private companion object {
        const val HEADER_BYTES = 12
        const val MAX_IMPORT_JSON_BYTES = 4 * 1024
    }
}
