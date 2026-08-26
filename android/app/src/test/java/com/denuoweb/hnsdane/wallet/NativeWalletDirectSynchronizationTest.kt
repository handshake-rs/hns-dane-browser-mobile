package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWalletDirectSynchronizationTest {
    @Test
    fun directDenuoDashboardExposesRecoveryAndDisconnectControlsWhenApplicable() {
        assertEquals(
            NativeWalletDirectDenuoControls(retryListener = true, disconnectPeer = false),
            directDenuoControls(null),
        )
        assertEquals(
            NativeWalletDirectDenuoControls(retryListener = false, disconnectPeer = false),
            directDenuoControls(
                NativeWalletDirectDenuoStatus(true, listenerPort = 12_038, peerEndpoint = null),
            ),
        )
        assertEquals(
            NativeWalletDirectDenuoControls(retryListener = false, disconnectPeer = true),
            directDenuoControls(
                NativeWalletDirectDenuoStatus(
                    true,
                    listenerPort = 12_038,
                    peerEndpoint = "198.51.100.7:12038",
                ),
            ),
        )
    }

    @Test
    fun catchingUpBundleExposesOnlyBoundedProgressAndIsWipedAfterParsing() {
        val bundle = catchupBundle(
            headerState = 1,
            birthdayHeight = 0,
            scannedHeight = 63_999,
            scanTargetHeight = 64_000,
        )

        val parsed = NativeWalletBridge.parseAndWipeHnsSynchronizationBundle(bundle)

        assertNull(parsed?.snapshot)
        assertEquals(NativeWalletHnsCatchupProgress.HeaderState.Current, parsed?.catchup?.headerState)
        assertEquals(64_000L, parsed?.catchup?.headerTipHeight)
        assertEquals(63_999L, parsed?.catchup?.scannedHeight)
        assertEquals(64_000L, parsed?.catchup?.scanTargetHeight)
        assertTrue(bundle.all { it == 0.toByte() })
    }

    @Test
    fun malformedCatchupCannotBecomeASnapshotOrProgressProjection() {
        val bundle = catchupBundle(
            headerState = 1,
            birthdayHeight = 100,
            scannedHeight = 99,
            scanTargetHeight = 200,
        )

        assertNull(NativeWalletBridge.parseAndWipeHnsSynchronizationBundle(bundle))
        assertTrue(bundle.all { it == 0.toByte() })
    }

    @Test
    fun liveProgressBundleExposesOnlyStageAndVerifiedHeightsAndIsWiped() {
        val bundle = liveProgressBundle(
            stage = 4,
            headerState = 1,
            headerRound = 0,
            headerRetries = 1,
            birthdayHeight = 1_000,
            scannedHeight = 42_000,
            scanTargetHeight = 64_000,
        )

        val parsed = NativeWalletBridge.parseAndWipeHnsLiveSynchronizationProgressBundle(bundle)

        assertEquals(NativeWalletHnsLiveSyncProgress.Stage.Scanning, parsed?.stage)
        assertEquals(NativeWalletHnsCatchupProgress.HeaderState.Current, parsed?.headerState)
        assertEquals(0, parsed?.headerRound)
        assertEquals(1, parsed?.headerRetries)
        assertEquals(64_000L, parsed?.headerTipHeight)
        assertEquals(1_000L, parsed?.birthdayHeight)
        assertEquals(42_000L, parsed?.scannedHeight)
        assertEquals(64_000L, parsed?.scanTargetHeight)
        assertTrue(bundle.all { it == 0.toByte() })
    }

    @Test
    fun malformedLiveProgressCannotBecomeAWalletProjection() {
        val bundle = liveProgressBundle(
            stage = 2,
            headerState = 2,
            headerRound = 0,
            headerRetries = 0,
            birthdayHeight = 0,
            scannedHeight = 0,
            scanTargetHeight = 64_000,
        )

        assertNull(NativeWalletBridge.parseAndWipeHnsLiveSynchronizationProgressBundle(bundle))
        assertTrue(bundle.all { it == 0.toByte() })
    }

    @Test
    fun directDenuoStatusAndReplacementResultsHaveClosedSchemas() {
        val statusBundle = directDenuoStatusBundle(
            flags = 0b111,
            listenerPort = 12_038,
            endpoint = "198.51.100.7:12038",
        )
        val status = NativeWalletBridge.parseAndWipeWalletOwnedDirectDenuoStatusBundle(statusBundle)
        assertTrue(status?.unlocked == true)
        assertEquals(12_038, status?.listenerPort)
        assertEquals("198.51.100.7:12038", status?.peerEndpoint)
        assertTrue(statusBundle.all { it == 0.toByte() })

        val replacementBundle = directDenuoConnectBundle(
            code = 2,
            endpoint = "[2001:db8::7]:12038",
        )
        val replacement =
            NativeWalletBridge.parseAndWipeWalletOwnedDirectDenuoConnectBundle(replacementBundle)
        assertEquals(NativeWalletDirectDenuoConnectResult.Outcome.Replaced, replacement?.outcome)
        assertEquals("[2001:db8::7]:12038", replacement?.peerEndpoint)
        assertTrue(replacementBundle.all { it == 0.toByte() })

        val invalid = directDenuoConnectBundle(code = 3, endpoint = "unexpected")
        assertNull(NativeWalletBridge.parseAndWipeWalletOwnedDirectDenuoConnectBundle(invalid))
        assertTrue(invalid.all { it == 0.toByte() })
    }

    private fun catchupBundle(
        headerState: Int,
        birthdayHeight: Int,
        scannedHeight: Int,
        scanTargetHeight: Int,
    ): ByteArray = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
        put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte()))
        put(1)
        put(2)
        putShort(0)
        putInt(20)
        put(headerState.toByte())
        put(1)
        putShort(0)
        putInt(scanTargetHeight)
        putInt(birthdayHeight)
        putInt(scannedHeight)
        putInt(scanTargetHeight)
    }.array()

    private fun liveProgressBundle(
        stage: Int,
        headerState: Int,
        headerRound: Int,
        headerRetries: Int,
        birthdayHeight: Int,
        scannedHeight: Int,
        scanTargetHeight: Int,
    ): ByteArray = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
        put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte()))
        put(1)
        put(stage.toByte())
        put(headerState.toByte())
        put(1)
        put(headerRound.toByte())
        put(headerRetries.toByte())
        putShort(0)
        putInt(scanTargetHeight)
        putInt(birthdayHeight)
        putInt(scannedHeight)
        putInt(scanTargetHeight)
    }.array()

    private fun directDenuoStatusBundle(
        flags: Int,
        listenerPort: Int,
        endpoint: String,
    ): ByteArray {
        val endpointBytes = endpoint.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(12 + endpointBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte()))
            put(1)
            put(flags.toByte())
            putShort(0)
            putShort(listenerPort.toShort())
            putShort(endpointBytes.size.toShort())
            put(endpointBytes)
        }.array()
    }

    private fun directDenuoConnectBundle(code: Int, endpoint: String): ByteArray {
        val endpointBytes = endpoint.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(12 + endpointBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte()))
            put(1)
            put(code.toByte())
            putShort(0)
            putShort(endpointBytes.size.toShort())
            putShort(0)
            put(endpointBytes)
        }.array()
    }
}
