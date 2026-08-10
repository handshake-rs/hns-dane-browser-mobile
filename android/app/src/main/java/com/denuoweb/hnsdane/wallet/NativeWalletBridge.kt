package com.denuoweb.hnsdane.wallet

import com.denuoweb.hnsdane.net.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Narrow Android binding for the native non-value wallet controller.
 *
 * Database keys and restore phrases are caller-owned mutable arrays. Every
 * wrapper consumes and wipes those arrays before returning. Recovery output is
 * a one-shot mutable character array; its caller must wipe it after the
 * dedicated display is dismissed.
 */
internal object NativeWalletBridge {
    const val NETWORK_MAINNET = 1
    const val NETWORK_TESTNET = 2
    const val NETWORK_REGTEST = 3

    val isAvailable: Boolean
        get() = NativeBridge.isLoaded

    fun create(
        databasePath: String,
        databaseKey: ByteArray,
        network: Int,
        birthdayHeight: Long,
    ): Long = consumeDatabaseKey(databaseKey) { key ->
        if (!isAvailable || !validNetwork(network) || birthdayHeight < 0L) {
            INVALID_HANDLE
        } else {
            runCatching { nativeCreate(databasePath, key, network, birthdayHeight) }
                .getOrDefault(INVALID_HANDLE)
        }
    }

    fun restore(
        databasePath: String,
        databaseKey: ByteArray,
        network: Int,
        birthdayHeight: Long,
        recoveryPhrase: CharArray,
    ): Long = try {
        consumeDatabaseKey(databaseKey) { key ->
            if (
                !isAvailable || !validNetwork(network) || birthdayHeight < 0L ||
                recoveryPhrase.isEmpty() || recoveryPhrase.size > MAX_RECOVERY_CHARACTERS
            ) {
                INVALID_HANDLE
            } else {
                runCatching {
                    nativeRestore(databasePath, key, network, birthdayHeight, recoveryPhrase)
                }.getOrDefault(INVALID_HANDLE)
            }
        }
    } finally {
        recoveryPhrase.fill('\u0000')
    }

    fun open(databasePath: String, databaseKey: ByteArray): Long =
        consumeDatabaseKey(databaseKey) { key ->
            if (isAvailable) {
                runCatching { nativeOpen(databasePath, key) }.getOrDefault(INVALID_HANDLE)
            } else {
                INVALID_HANDLE
            }
        }

    fun status(handle: Long): NativeWalletStatus? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeStatus(handle) }.getOrNull()?.let(::parseStatusBundle)
        } else {
            null
        }

    fun account(handle: Long): NativeWalletAccount? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeAccounts(handle) }.getOrNull()?.let(::parseSingleAccountBundle)
        } else {
            null
        }

    /**
     * Installs an app-owned read-only sidecar binding. The caller must source a
     * scoped credential from trusted native configuration; this is deliberately
     * not reachable from preferences, intents, links, or ordinary wallet UI.
     */
    fun configureHnsReads(
        handle: Long,
        loopbackPort: Int,
        authorization: CharArray,
    ): Boolean = try {
        isValidHandle(handle) &&
            isAvailable &&
            loopbackPort in 1..USHORT_MAX &&
            authorization.size in 1..MAX_AUTHORIZATION_CHARACTERS &&
            runCatching {
                nativeConfigureHnsReads(handle, loopbackPort, authorization)
            }.getOrDefault(false)
    } finally {
        authorization.fill('\u0000')
    }

    fun hasHnsReads(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeHasHnsReads(handle) }.getOrDefault(false)

    fun synchronizeHnsReads(handle: Long): NativeWalletReadSnapshot? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeSynchronizeHnsReads(handle) }.getOrNull()
                ?: return null
            parseAndWipeHnsReadBundle(bundle)
        } else {
            null
        }

    internal fun parseAndWipeHnsReadBundle(bundle: ByteArray): NativeWalletReadSnapshot? = try {
        NativeWalletReadSnapshot.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    fun unlock(handle: Long, databaseKey: ByteArray): Boolean =
        consumeDatabaseKey(databaseKey) { key ->
            isValidHandle(handle) && isAvailable &&
                runCatching { nativeUnlock(handle, key) }.getOrDefault(false)
        }

    fun lock(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeLock(handle) }.getOrDefault(false)

    fun takeRecovery(handle: Long): CharArray? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeTakeRecovery(handle) }.getOrNull()
        } else {
            null
        }

    fun destroy(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeDestroy(handle) }.getOrDefault(false)

    private inline fun <T> consumeDatabaseKey(
        databaseKey: ByteArray,
        block: (ByteArray) -> T,
    ): T = try {
        require(databaseKey.size == DATABASE_KEY_BYTES) { "Wallet database key must be 32 bytes" }
        require(databaseKey.any { it != 0.toByte() }) { "Wallet database key must be nonzero" }
        block(databaseKey)
    } finally {
        databaseKey.fill(0)
    }

    internal fun parseStatusBundle(bundle: ByteArray): NativeWalletStatus? {
        if (bundle.size != STATUS_BUNDLE_BYTES || !bundle.hasMagic(STATUS_MAGIC)) return null
        val buffer = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        if (buffer.get().toInt() and 0xff != BUNDLE_VERSION) return null
        val flags = buffer.get().toInt() and 0xff
        if (flags and STATUS_ALLOWED_FLAGS.inv() != 0) return null
        if (buffer.short.toInt() != 0) return null
        val walletId = ByteArray(WALLET_ID_BYTES)
        buffer.get(walletId)
        val hasActiveWallet = flags and STATUS_ACTIVE_WALLET != 0
        val locked = flags and STATUS_LOCKED != 0
        if (hasActiveWallet == walletId.all { it == 0.toByte() }) return null
        if (locked == hasActiveWallet) return null
        return NativeWalletStatus(
            locked = locked,
            activeWalletId = walletId.takeIf { hasActiveWallet }?.toLowerHex(),
        )
    }

    internal fun parseSingleAccountBundle(bundle: ByteArray): NativeWalletAccount? {
        if (bundle.size < ACCOUNT_FIXED_BYTES || !bundle.hasMagic(ACCOUNT_MAGIC)) return null
        val buffer = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        if (buffer.get().toInt() and 0xff != BUNDLE_VERSION) return null
        if (buffer.get().toInt() and 0xff != 1 || buffer.short.toInt() != 0) return null
        val accountId = ByteArray(ACCOUNT_ID_BYTES)
        buffer.get(accountId)
        if (accountId.all { it == 0.toByte() }) return null
        if (buffer.get().toInt() and 0xff != MODULE_HANDSHAKE) return null
        if (buffer.get().toInt() != 0) return null
        val labelLength = buffer.short.toInt() and 0xffff
        if (
            labelLength !in 1..MAX_ACCOUNT_LABEL_BYTES ||
            buffer.remaining() != labelLength
        ) return null
        val labelBytes = ByteArray(labelLength)
        buffer.get(labelBytes)
        val label = labelBytes.toString(Charsets.UTF_8)
        if (label.toByteArray(Charsets.UTF_8).contentEquals(labelBytes).not()) return null
        return NativeWalletAccount(
            accountId = accountId.toLowerHex(),
            module = "Handshake",
            label = label,
        )
    }

    private fun ByteArray.hasMagic(expected: ByteArray): Boolean =
        size >= expected.size && expected.indices.all { index -> this[index] == expected[index] }

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        this@toLowerHex.forEach { value ->
            val byte = value.toInt() and 0xff
            append(HEX[byte ushr 4])
            append(HEX[byte and 0x0f])
        }
    }

    private fun validNetwork(network: Int): Boolean =
        network == NETWORK_MAINNET || network == NETWORK_TESTNET || network == NETWORK_REGTEST

    private fun isValidHandle(handle: Long): Boolean = handle > INVALID_HANDLE

    @JvmStatic
    private external fun nativeCreate(
        databasePath: String,
        databaseKey: ByteArray,
        network: Int,
        birthdayHeight: Long,
    ): Long

    @JvmStatic
    private external fun nativeRestore(
        databasePath: String,
        databaseKey: ByteArray,
        network: Int,
        birthdayHeight: Long,
        recoveryPhrase: CharArray,
    ): Long

    @JvmStatic
    private external fun nativeOpen(databasePath: String, databaseKey: ByteArray): Long

    @JvmStatic
    private external fun nativeStatus(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeAccounts(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeConfigureHnsReads(
        handle: Long,
        loopbackPort: Int,
        authorization: CharArray,
    ): Boolean

    @JvmStatic
    private external fun nativeHasHnsReads(handle: Long): Boolean

    @JvmStatic
    private external fun nativeSynchronizeHnsReads(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeUnlock(handle: Long, databaseKey: ByteArray): Boolean

    @JvmStatic
    private external fun nativeLock(handle: Long): Boolean

    @JvmStatic
    private external fun nativeTakeRecovery(handle: Long): CharArray?

    @JvmStatic
    private external fun nativeDestroy(handle: Long): Boolean

    private const val INVALID_HANDLE = 0L
    private const val DATABASE_KEY_BYTES = 32
    private const val WALLET_ID_BYTES = 16
    private const val ACCOUNT_ID_BYTES = 16
    private const val MAX_RECOVERY_CHARACTERS = 256
    private const val MAX_AUTHORIZATION_CHARACTERS = 4_096
    private const val USHORT_MAX = 65_535
    private const val MAX_ACCOUNT_LABEL_BYTES = 128
    private const val STATUS_BUNDLE_BYTES = 24
    private const val ACCOUNT_FIXED_BYTES = 28
    private const val BUNDLE_VERSION = 1
    private const val STATUS_LOCKED = 1
    private const val STATUS_ACTIVE_WALLET = 1 shl 1
    private const val STATUS_ALLOWED_FLAGS = STATUS_LOCKED or STATUS_ACTIVE_WALLET
    private const val MODULE_HANDSHAKE = 1
    private const val HEX = "0123456789abcdef"
    private val STATUS_MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte())
    private val ACCOUNT_MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'A'.code.toByte())
}

internal data class NativeWalletStatus(
    val locked: Boolean,
    val activeWalletId: String?,
)

internal data class NativeWalletAccount(
    val accountId: String,
    val module: String,
    val label: String,
)
