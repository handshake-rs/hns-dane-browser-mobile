package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeBitcoinSyncProgressTest {
    @Test
    fun parses_the_validated_birthday_height_in_a_snapshot() {
        val snapshot = NativeBitcoinWalletBundle.snapshot(bundle(
            """{"network":"mainnet","receiveAddress":"bc1qexample","confirmedSats":10000,"trustedPendingSats":0,"untrustedPendingSats":0,"immatureSats":0,"totalSats":10000,"birthdayHeight":855000,"synchronizedHeight":855123,"connectedPeerCount":3,"requiredPeerCount":3}""",
        ))
        requireNotNull(snapshot)
        assertEquals(855000L, snapshot.birthdayHeight)
        assertEquals(855123L, snapshot.synchronizedHeight)
    }

    @Test
    fun rejects_a_snapshot_that_omits_the_birthday_height() {
        assertNull(NativeBitcoinWalletBundle.snapshot(bundle(
            """{"network":"mainnet","receiveAddress":"bc1qexample","confirmedSats":10000,"trustedPendingSats":0,"untrustedPendingSats":0,"immatureSats":0,"totalSats":10000,"synchronizedHeight":855123,"connectedPeerCount":3,"requiredPeerCount":3}""",
        )))
    }

    @Test
    fun parses_the_closed_bounded_progress_projection() {
        val progress = NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":4,"peerTimeouts":1,"incompatiblePeers":2,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":7200}""",
        ))
        requireNotNull(progress)
        assertEquals(2, progress.successfulHandshakes)
        assertEquals(3, progress.requiredPeerCount)
        assertEquals(4, progress.connectionFailures)
        assertEquals(1, progress.peerTimeouts)
        assertEquals(2, progress.incompatiblePeers)
        assertEquals(910000L, progress.chainHeight)
        assertEquals(7200L, progress.completionBasisPoints)
    }

    @Test
    fun rejects_impossible_or_extended_progress() {
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":0,"peerTimeouts":0,"incompatiblePeers":0,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":10001}""",
        )))
        assertNull(NativeBitcoinWalletBundle.syncProgress(bundle(
            """{"successfulHandshakes":2,"requiredPeerCount":3,"connectionFailures":0,"peerTimeouts":0,"incompatiblePeers":0,"connectionsMet":true,"chainHeight":910000,"completionBasisPoints":20,"peer":"untrusted"}""",
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
