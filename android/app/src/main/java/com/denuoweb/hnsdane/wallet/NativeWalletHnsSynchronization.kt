package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One bounded native HNS reconciliation result.
 *
 * A [snapshot] is present only after the direct peer coordinator has both a
 * current authenticated header tip and an exact filtered-block scan through
 * that tip. [catchup] deliberately contains no balance, history, name, or
 * spend data and must never be used as a wallet snapshot.
 */
internal data class NativeWalletHnsSynchronization(
    val snapshot: NativeWalletReadSnapshot?,
    val catchup: NativeWalletHnsCatchupProgress?,
) {
    init {
        require((snapshot == null) != (catchup == null))
    }

    companion object {
        fun parse(bundle: ByteArray): NativeWalletHnsSynchronization? =
            NativeWalletHnsSynchronizationParser.parse(bundle)
    }
}

/** Public, non-authoritative progress for a resumable verified catch-up. */
internal data class NativeWalletHnsCatchupProgress(
    val headerState: HeaderState,
    val headerTipHeight: Long,
    val birthdayHeight: Long,
    val scannedHeight: Long?,
    val scanTargetHeight: Long,
) {
    enum class HeaderState {
        Current,
        Syncing,
        Degraded,
    }
}

private object NativeWalletHnsSynchronizationParser {
    private val magic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'S'.code.toByte(),
        'Y'.code.toByte(),
    )

    fun parse(bundle: ByteArray): NativeWalletHnsSynchronization? = runCatching {
        require(bundle.size in HEADER_BYTES..MAX_BUNDLE_BYTES)
        require(magic.indices.all { index -> bundle[index] == magic[index] })
        val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(header.get().toInt() and 0xff == VERSION)
        val outcome = header.get().toInt() and 0xff
        require(header.short.toInt() == 0)
        val payloadLength = header.int
        require(payloadLength >= 1)
        require(bundle.size == HEADER_BYTES + payloadLength)
        val payload = bundle.copyOfRange(HEADER_BYTES, bundle.size)
        try {
            when (outcome) {
                READY -> NativeWalletHnsSynchronization(
                    snapshot = NativeWalletReadSnapshot.parse(payload)
                        ?: throw IllegalArgumentException("invalid ready snapshot"),
                    catchup = null,
                )

                CATCHING_UP -> NativeWalletHnsSynchronization(
                    snapshot = null,
                    catchup = parseCatchup(payload),
                )

                else -> throw IllegalArgumentException("unknown synchronization outcome")
            }
        } finally {
            payload.fill(0)
        }
    }.getOrNull()

    private fun parseCatchup(payload: ByteArray): NativeWalletHnsCatchupProgress {
        require(payload.size == CATCHUP_BYTES)
        val value = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val headerState = when (value.get().toInt() and 0xff) {
            HEADER_CURRENT -> NativeWalletHnsCatchupProgress.HeaderState.Current
            HEADER_SYNCING -> NativeWalletHnsCatchupProgress.HeaderState.Syncing
            HEADER_DEGRADED -> NativeWalletHnsCatchupProgress.HeaderState.Degraded
            else -> throw IllegalArgumentException("unknown header catch-up state")
        }
        val hasScannedHeight = when (value.get().toInt() and 0xff) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("invalid scanned-height presence")
        }
        require(value.short.toInt() == 0)
        val headerTipHeight = value.int.toUnsignedLong()
        val birthdayHeight = value.int.toUnsignedLong()
        val encodedScannedHeight = value.int.toUnsignedLong()
        val scanTargetHeight = value.int.toUnsignedLong()
        require(headerTipHeight == scanTargetHeight)
        val scannedHeight = if (hasScannedHeight) {
            encodedScannedHeight.also { height ->
                require(height in birthdayHeight..scanTargetHeight)
            }
        } else {
            require(encodedScannedHeight == 0L)
            null
        }
        return NativeWalletHnsCatchupProgress(
            headerState = headerState,
            headerTipHeight = headerTipHeight,
            birthdayHeight = birthdayHeight,
            scannedHeight = scannedHeight,
            scanTargetHeight = scanTargetHeight,
        )
    }

    private fun Int.toUnsignedLong(): Long = toLong() and UINT32_MAX

    private const val VERSION = 1
    private const val READY = 1
    private const val CATCHING_UP = 2
    private const val HEADER_CURRENT = 1
    private const val HEADER_SYNCING = 2
    private const val HEADER_DEGRADED = 3
    private const val HEADER_BYTES = 12
    private const val CATCHUP_BYTES = 20
    private const val MAX_BUNDLE_BYTES = HEADER_BYTES + 12 + 4 * 1024 * 1024
    private const val UINT32_MAX = 0xffff_ffffL
}
