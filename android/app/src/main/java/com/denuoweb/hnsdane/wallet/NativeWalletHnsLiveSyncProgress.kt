package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Non-authoritative, public status emitted while a direct HNS synchronization
 * owns the native controller. This is deliberately distinct from a wallet
 * snapshot: it contains verified heights and transport stage only, never
 * balances, transaction history, addresses, names, or send authority.
 */
internal data class NativeWalletHnsLiveSyncProgress(
    val stage: Stage,
    val headerState: NativeWalletHnsCatchupProgress.HeaderState,
    val headerRound: Int,
    val headerRetries: Int,
    val headerTipHeight: Long,
    val birthdayHeight: Long,
    val scannedHeight: Long?,
    val scanTargetHeight: Long,
) {
    enum class Stage {
        Connecting,
        Headers,
        Retrying,
        Scanning,
        Finalizing,
    }

    companion object {
        fun parse(bundle: ByteArray): NativeWalletHnsLiveSyncProgress? =
            NativeWalletHnsLiveSyncProgressParser.parse(bundle)
    }
}

private object NativeWalletHnsLiveSyncProgressParser {
    private val magic = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'L'.code.toByte(),
        'P'.code.toByte(),
    )

    fun parse(bundle: ByteArray): NativeWalletHnsLiveSyncProgress? = runCatching {
        require(bundle.size == BUNDLE_BYTES)
        require(magic.indices.all { index -> bundle[index] == magic[index] })
        val value = ByteBuffer.wrap(bundle, 4, BUNDLE_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(value.get().toInt() and 0xff == VERSION)
        val stage = when (value.get().toInt() and 0xff) {
            CONNECTING -> NativeWalletHnsLiveSyncProgress.Stage.Connecting
            HEADERS -> NativeWalletHnsLiveSyncProgress.Stage.Headers
            RETRYING -> NativeWalletHnsLiveSyncProgress.Stage.Retrying
            SCANNING -> NativeWalletHnsLiveSyncProgress.Stage.Scanning
            FINALIZING -> NativeWalletHnsLiveSyncProgress.Stage.Finalizing
            else -> throw IllegalArgumentException("unknown live sync stage")
        }
        val headerState = when (value.get().toInt() and 0xff) {
            HEADER_CURRENT -> NativeWalletHnsCatchupProgress.HeaderState.Current
            HEADER_SYNCING -> NativeWalletHnsCatchupProgress.HeaderState.Syncing
            HEADER_DEGRADED -> NativeWalletHnsCatchupProgress.HeaderState.Degraded
            else -> throw IllegalArgumentException("unknown header state")
        }
        val hasScannedHeight = when (value.get().toInt() and 0xff) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("invalid scanned-height presence")
        }
        val headerRound = value.get().toInt() and 0xff
        val headerRetries = value.get().toInt() and 0xff
        require(value.short.toInt() == 0)
        require(headerRound <= MAX_HEADER_ROUNDS)
        require(headerRetries <= MAX_HEADER_RETRIES)
        require(
            when (stage) {
                NativeWalletHnsLiveSyncProgress.Stage.Connecting,
                NativeWalletHnsLiveSyncProgress.Stage.Headers,
                NativeWalletHnsLiveSyncProgress.Stage.Retrying -> headerRound >= 1

                NativeWalletHnsLiveSyncProgress.Stage.Scanning,
                NativeWalletHnsLiveSyncProgress.Stage.Finalizing -> headerRound == 0
            }
        )
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
        NativeWalletHnsLiveSyncProgress(
            stage = stage,
            headerState = headerState,
            headerRound = headerRound,
            headerRetries = headerRetries,
            headerTipHeight = headerTipHeight,
            birthdayHeight = birthdayHeight,
            scannedHeight = scannedHeight,
            scanTargetHeight = scanTargetHeight,
        )
    }.getOrNull()

    private fun Int.toUnsignedLong(): Long = toLong() and UINT32_MAX

    private const val VERSION = 1
    private const val CONNECTING = 1
    private const val HEADERS = 2
    private const val RETRYING = 3
    private const val SCANNING = 4
    private const val FINALIZING = 5
    private const val HEADER_CURRENT = 1
    private const val HEADER_SYNCING = 2
    private const val HEADER_DEGRADED = 3
    private const val BUNDLE_BYTES = 28
    private const val MAX_HEADER_ROUNDS = 32
    private const val MAX_HEADER_RETRIES = 2
    private const val UINT32_MAX = 0xffff_ffffL
}
