package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Operational state for a direct Denuo listener and its one active peer. */
internal data class NativeWalletDirectDenuoStatus(
    val unlocked: Boolean,
    val listenerPort: Int?,
    val peerEndpoint: String?,
) {
    companion object {
        fun parse(bundle: ByteArray): NativeWalletDirectDenuoStatus? =
            NativeWalletDirectDenuoParser.parseStatus(bundle)
    }
}

/** Exact conditional transport controls shown by the native Shakedex dashboard. */
internal data class NativeWalletDirectDenuoControls(
    val retryListener: Boolean,
    val disconnectPeer: Boolean,
)

internal fun directDenuoControls(
    status: NativeWalletDirectDenuoStatus?,
): NativeWalletDirectDenuoControls = NativeWalletDirectDenuoControls(
    retryListener = status?.listenerPort == null,
    disconnectPeer = status?.peerEndpoint != null,
)

/** Exact result of a user-requested direct Denuo connection attempt. */
internal data class NativeWalletDirectDenuoConnectResult(
    val outcome: Outcome,
    val peerEndpoint: String?,
) {
    enum class Outcome {
        Connected,
        Replaced,
        Unavailable,
        Locked,
        ConnectionFailed,
        ExchangeFailed,
    }

    companion object {
        fun parse(bundle: ByteArray): NativeWalletDirectDenuoConnectResult? =
            NativeWalletDirectDenuoParser.parseConnect(bundle)
    }
}

private object NativeWalletDirectDenuoParser {
    private val statusMagic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'D'.code.toByte(),
        'S'.code.toByte(),
    )
    private val connectMagic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'D'.code.toByte(),
        'C'.code.toByte(),
    )

    fun parseStatus(bundle: ByteArray): NativeWalletDirectDenuoStatus? = runCatching {
        require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_ENDPOINT_BYTES))
        require(statusMagic.indices.all { index -> bundle[index] == statusMagic[index] })
        val input = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(input.get().toInt() and 0xff == VERSION)
        val flags = input.get().toInt() and 0xff
        require(flags and STATUS_SUPPORTED_FLAGS == flags)
        require(input.short.toInt() == 0)
        val port = input.short.toInt() and 0xffff
        val endpointLength = input.short.toInt() and 0xffff
        require(bundle.size == HEADER_BYTES + endpointLength)
        val unlocked = flags and STATUS_UNLOCKED != 0
        val listening = flags and STATUS_LISTENING != 0
        val paired = flags and STATUS_PAIRED != 0
        require(unlocked || (!listening && !paired))
        require((port != 0) == listening)
        require((endpointLength != 0) == paired)
        NativeWalletDirectDenuoStatus(
            unlocked = unlocked,
            listenerPort = port.takeIf { listening },
            peerEndpoint = endpoint(bundle, endpointLength).takeIf { paired },
        )
    }.getOrNull()

    fun parseConnect(bundle: ByteArray): NativeWalletDirectDenuoConnectResult? = runCatching {
        require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_ENDPOINT_BYTES))
        require(connectMagic.indices.all { index -> bundle[index] == connectMagic[index] })
        val input = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(input.get().toInt() and 0xff == VERSION)
        val outcome = when (input.get().toInt() and 0xff) {
            CONNECTED -> NativeWalletDirectDenuoConnectResult.Outcome.Connected
            REPLACED -> NativeWalletDirectDenuoConnectResult.Outcome.Replaced
            UNAVAILABLE -> NativeWalletDirectDenuoConnectResult.Outcome.Unavailable
            LOCKED -> NativeWalletDirectDenuoConnectResult.Outcome.Locked
            CONNECTION_FAILED -> NativeWalletDirectDenuoConnectResult.Outcome.ConnectionFailed
            EXCHANGE_FAILED -> NativeWalletDirectDenuoConnectResult.Outcome.ExchangeFailed
            else -> throw IllegalArgumentException("unknown direct Denuo connection outcome")
        }
        require(input.short.toInt() == 0)
        val endpointLength = input.short.toInt() and 0xffff
        require(input.short.toInt() == 0)
        require(bundle.size == HEADER_BYTES + endpointLength)
        val endpoint = endpoint(bundle, endpointLength)
        val success = outcome == NativeWalletDirectDenuoConnectResult.Outcome.Connected ||
            outcome == NativeWalletDirectDenuoConnectResult.Outcome.Replaced
        require(success == endpoint.isNotEmpty())
        NativeWalletDirectDenuoConnectResult(
            outcome = outcome,
            peerEndpoint = endpoint.takeIf { success },
        )
    }.getOrNull()

    private fun endpoint(bundle: ByteArray, length: Int): String {
        require(length in 0..MAX_ENDPOINT_BYTES)
        val bytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
        try {
            val value = bytes.toString(Charsets.UTF_8)
            require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            require(value.all { character -> character.code in 0x21..0x7e })
            return value
        } finally {
            bytes.fill(0)
        }
    }

    private const val VERSION = 1
    private const val HEADER_BYTES = 12
    private const val MAX_ENDPOINT_BYTES = 128
    private const val STATUS_UNLOCKED = 1
    private const val STATUS_LISTENING = 1 shl 1
    private const val STATUS_PAIRED = 1 shl 2
    private const val STATUS_SUPPORTED_FLAGS =
        STATUS_UNLOCKED or STATUS_LISTENING or STATUS_PAIRED
    private const val CONNECTED = 1
    private const val REPLACED = 2
    private const val UNAVAILABLE = 3
    private const val LOCKED = 4
    private const val CONNECTION_FAILED = 5
    private const val EXCHANGE_FAILED = 6
}
