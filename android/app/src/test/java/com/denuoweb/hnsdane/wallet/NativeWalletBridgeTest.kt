package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletBridgeTest {
    @Test
    fun statusBundleAcceptsOnlyLockedOrActiveNonValueState() {
        val locked = statusBundle(flags = 1, walletId = ByteArray(16))
        val parsedLocked = NativeWalletBridge.parseStatusBundle(locked)
        assertTrue(parsedLocked?.locked == true)
        assertNull(parsedLocked?.activeWalletId)

        val walletId = ByteArray(16) { (it + 1).toByte() }
        val unlocked = NativeWalletBridge.parseStatusBundle(statusBundle(2, walletId))
        assertFalse(unlocked?.locked ?: true)
        assertEquals("0102030405060708090a0b0c0d0e0f10", unlocked?.activeWalletId)

        assertNull(NativeWalletBridge.parseStatusBundle(statusBundle(4, walletId)))
        assertNull(NativeWalletBridge.parseStatusBundle(statusBundle(0, walletId)))
        assertNull(NativeWalletBridge.parseStatusBundle(statusBundle(2, ByteArray(16))))
        assertNull(NativeWalletBridge.parseStatusBundle(statusBundle(3, walletId)))
    }

    @Test
    fun accountBundleAcceptsOnlyOneHandshakeIdentity() {
        val parsed = NativeWalletBridge.parseSingleAccountBundle(accountBundle(module = 1, flags = 0))
        assertEquals("Handshake", parsed?.module)
        assertEquals("Handshake", parsed?.label)
        assertEquals("11111111111111111111111111111111", parsed?.accountId)

        assertNull(NativeWalletBridge.parseSingleAccountBundle(accountBundle(module = 2, flags = 0)))
        assertNull(NativeWalletBridge.parseSingleAccountBundle(accountBundle(module = 1, flags = 1)))
    }

    private fun statusBundle(flags: Int, walletId: ByteArray): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte()))
            put(1)
            put(flags.toByte())
            putShort(0)
            put(walletId)
        }.array()

    private fun accountBundle(module: Int, flags: Int): ByteArray {
        val label = "Handshake".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(28 + label.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'A'.code.toByte()))
            put(1)
            put(1)
            putShort(0)
            put(ByteArray(16) { 0x11 })
            put(module.toByte())
            put(flags.toByte())
            putShort(label.size.toShort())
            put(label)
        }.array()
    }
}
