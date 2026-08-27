package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeBitcoinSyncProgressTest {
    @Test
    fun parses_the_closed_bounded_progress_projection() {
        val progress = NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":7200}""",
        ))
        requireNotNull(progress)
        assertEquals(2, progress.successfulHandshakes)
        assertEquals(910000L, progress.chainHeight)
        assertEquals(7200L, progress.completionBasisPoints)
    }

    @Test
    fun rejects_impossible_or_extended_progress() {
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":10001}""",
        )))
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":20,"peer":"untrusted"}""",
        )))
    }

    private fun bundle(json: String): ByteArray {
        val encoded = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(12 + encoded.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte()))
            put(1.toByte())
            put(0.toByte())
            putShort(0.toShort())
            putInt(encoded.size)
            put(encoded)
        }.array()
    }
}
