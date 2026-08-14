package com.denuoweb.hnsdane.wallet

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

/** Strict success-only HNWI-v1 envelope for one minimized native name summary. */
internal object NativeWalletNameImportBundle {
    fun parse(bundle: ByteArray): NativeWalletName? = runCatching {
        require(bundle.size in HEADER_BYTES..(HEADER_BYTES + MAX_JSON_BYTES))
        require(MAGIC.indices.all { index -> bundle[index] == MAGIC[index] })
        val header = ByteBuffer.wrap(bundle, 4, HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN)
        require(header.get().toInt() and 0xff == VERSION)
        require(header.get().toInt() and 0xff == FLAGS)
        require(header.short.toInt() == 0)
        val jsonLength = header.int
        require(jsonLength in 2..MAX_JSON_BYTES)
        require(bundle.size == HEADER_BYTES + jsonLength)
        val jsonBytes = bundle.copyOfRange(HEADER_BYTES, bundle.size)
        try {
            require(jsonBytes.first() == '{'.code.toByte())
            require(jsonBytes.last() == '}'.code.toByte())
            val json = jsonBytes.toString(Charsets.UTF_8)
            require(json.toByteArray(Charsets.UTF_8).contentEquals(jsonBytes))
            NativeWalletNameParser.parse(JSONObject(json))
        } finally {
            jsonBytes.fill(0)
        }
    }.getOrNull()

    private const val VERSION = 1
    private const val FLAGS = 0
    private const val HEADER_BYTES = 12
    private const val MAX_JSON_BYTES = 4 * 1024
    private val MAGIC = byteArrayOf(
        'H'.code.toByte(),
        'N'.code.toByte(),
        'W'.code.toByte(),
        'I'.code.toByte(),
    )
}

/** Encode exact UI text into a bounded mutable buffer without normalization. */
internal fun exactWalletNameUtf8(value: CharSequence): ByteArray? {
    if (value.length !in 1..MAX_WALLET_NAME_UTF8_BYTES) return null
    val characters = CharArray(value.length) { index -> value[index] }
    val scratch = ByteArray(MAX_WALLET_NAME_UTF8_BYTES)
    return try {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val output = ByteBuffer.wrap(scratch)
        val encoded = encoder.encode(CharBuffer.wrap(characters), output, true)
        if (encoded.isError || encoded.isOverflow) return null
        val flushed = encoder.flush(output)
        if (flushed.isError || flushed.isOverflow || output.position() !in 1..MAX_WALLET_NAME_UTF8_BYTES) {
            return null
        }
        scratch.copyOf(output.position())
    } finally {
        characters.fill('\u0000')
        scratch.fill(0)
    }
}

/** One current lifecycle/storage/controller projection used only by native name import. */
internal data class WalletNameImportState(
    val readState: WalletReadBootstrapState,
    val unlocked: Boolean,
    val hnsReadsConfigured: Boolean,
)

private fun walletNameImportHasExactAuthority(
    expected: WalletReadBootstrapAuthority,
    current: WalletNameImportState,
): Boolean =
    current.readState.authority == expected &&
        expected.hasCurrentStorageLease() &&
        current.readState.foreground &&
        current.readState.reopenedDurableWallet &&
        current.readState.confirmedPersistentWallet &&
        !current.readState.hasUnconfirmedRecovery &&
        !current.readState.retirementBlocked

internal fun walletNameImportMayBegin(
    expected: WalletReadBootstrapAuthority,
    current: WalletNameImportState,
): Boolean =
    walletNameImportHasExactAuthority(expected, current) &&
        current.readState.protectedStorageAvailable &&
        current.unlocked &&
        current.hnsReadsConfigured &&
        !current.readState.operationInFlight

/**
 * Completion deliberately does not require an unlocked/read-configured result:
 * the import itself may lock the controller and that failure must reach the UI.
 */
internal fun walletNameImportMayPublish(
    expected: WalletReadBootstrapAuthority,
    current: WalletNameImportState,
    expectedEpoch: Long,
    currentEpoch: Long,
): Boolean =
    expectedEpoch == currentEpoch &&
        walletNameImportHasExactAuthority(expected, current) &&
        current.readState.operationInFlight

/** Native success must echo the exact bytes supplied by the trusted UI. */
internal fun walletNameImportEchoMatches(
    imported: NativeWalletName,
    exactUtf8: ByteArray,
): Boolean {
    val returned = imported.name.toByteArray(Charsets.UTF_8)
    return try {
        returned.contentEquals(exactUtf8)
    } finally {
        returned.fill(0)
    }
}

/** A fresh HNWR snapshot must contain exactly one row with the imported identity. */
internal fun walletNameImportRefreshMatches(
    imported: NativeWalletName,
    refreshed: NativeWalletReadSnapshot,
): Boolean {
    val importedName = imported.name.toByteArray(Charsets.UTF_8)
    return try {
        refreshed.trackedNames.count { current ->
            val currentName = current.name.toByteArray(Charsets.UTF_8)
            try {
                currentName.contentEquals(importedName) && current.nameHash == imported.nameHash
            } finally {
                currentName.fill(0)
            }
        } == 1
    } finally {
        importedName.fill(0)
    }
}

private const val MAX_WALLET_NAME_UTF8_BYTES = 63
