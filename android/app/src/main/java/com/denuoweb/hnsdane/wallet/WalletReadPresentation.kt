package com.denuoweb.hnsdane.wallet

import java.math.BigInteger

internal enum class WalletDashboardMode {
    Recovery,
    RetainedSynchronization,
    NoWallet,
    LockedWallet,
    UnlockedWallet,
}

/**
 * A process-local controller is intentionally disposable on Android lifecycle
 * transitions. Durable storage therefore remains independent evidence that
 * the setup actions must stay hidden while that controller is reopened.
 */
internal fun walletDashboardMode(
    hasUnconfirmedRecovery: Boolean,
    hasRetainedSynchronization: Boolean,
    hasController: Boolean,
    hasDurableWalletStorage: Boolean,
    synchronizationInProgress: Boolean,
    controllerUnlocked: Boolean,
): WalletDashboardMode = when {
    hasUnconfirmedRecovery -> WalletDashboardMode.Recovery
    hasRetainedSynchronization -> WalletDashboardMode.RetainedSynchronization
    !hasController && hasDurableWalletStorage -> WalletDashboardMode.LockedWallet
    !hasController -> WalletDashboardMode.NoWallet
    synchronizationInProgress -> WalletDashboardMode.UnlockedWallet
    !controllerUnlocked -> WalletDashboardMode.LockedWallet
    else -> WalletDashboardMode.UnlockedWallet
}

internal fun formatHnsBaseUnits(baseUnits: String): String {
    val value = BigInteger(baseUnits)
    require(value.signum() >= 0)
    val (whole, fraction) = value.divideAndRemainder(HNS_BASE_UNITS)
    if (fraction.signum() == 0) return whole.toString()
    val fractional = fraction.toString().padStart(HNS_DECIMAL_PLACES, '0').trimEnd('0')
    return "${whole}.${fractional}"
}

/** Parses exact user-facing HNS decimal text into canonical integer base units. */
internal fun parsePositiveHnsToBaseUnits(exact: CharSequence): String? {
    if (exact.isEmpty() || exact.length > MAX_HNS_INPUT_CHARACTERS) return null
    // A leading fractional separator is conventional wallet input. Normalize
    // it before the exact grammar and integer conversion so `.1` has the same
    // unambiguous base-unit meaning as `0.1`, without admitting signs,
    // whitespace, grouping separators, or a bare decimal point.
    val text = exact.toString().let { input ->
        if (input.startsWith('.')) "0$input" else input
    }
    if (!HNS_DECIMAL_INPUT.matches(text)) return null
    val separator = text.indexOf('.')
    val whole = if (separator < 0) text else text.substring(0, separator)
    val fraction = if (separator < 0) "" else text.substring(separator + 1)
    val baseUnits = runCatching {
        BigInteger(whole)
            .multiply(HNS_BASE_UNITS)
            .add(BigInteger(fraction.padEnd(HNS_DECIMAL_PLACES, '0').ifEmpty { "0" }))
    }.getOrNull() ?: return null
    return baseUnits
        .takeIf { it.signum() > 0 && it <= MAX_HNS_BASE_UNITS }
        ?.toString()
}

internal fun NativeWalletTransaction.displayAmount(): String = buildString {
    if (negative) append('-')
    append(formatHnsBaseUnits(magnitudeBaseUnits))
    append(" HNS")
}

/**
 * Separates the native reservation-aware spendable balance from outgoing
 * transactions that are still pending. The native value runtime already
 * excludes every actively reserved input; subtracting a transaction's net
 * amount here would double-count the reservation and still fail to represent
 * the indivisible input value that is temporarily unavailable.
 */
internal data class WalletHnsBalanceProjection(
    val spendableBaseUnits: String,
    val pendingOutgoingBaseUnits: String,
) {
    val hasPendingOutgoing: Boolean
        get() = pendingOutgoingBaseUnits != "0"
}

internal fun NativeWalletReadSnapshot.hnsBalanceProjection(): WalletHnsBalanceProjection {
    val pendingOutgoing = transactions
        .asSequence()
        .filter { transaction ->
            transaction.negative && transaction.status in PENDING_HNS_TRANSACTION_STATUSES
        }
        .fold(BigInteger.ZERO) { total, transaction ->
            total.add(BigInteger(transaction.magnitudeBaseUnits))
        }
    return WalletHnsBalanceProjection(
        spendableBaseUnits = BigInteger(balanceBaseUnits).toString(),
        pendingOutgoingBaseUnits = pendingOutgoing.toString(),
    )
}

/**
 * Selects one new authenticated header height for a pending-transaction
 * refresh. A failed or no-op wallet round cannot spin at the same height; the
 * next automatic attempt requires strictly newer browser-chain evidence.
 */
internal fun walletPendingOutgoingRefreshHeight(
    pendingSnapshotHeight: Long?,
    observedHeaderHeight: Long?,
    attemptedHeaderHeight: Long?,
): Long? = observedHeaderHeight?.takeIf { observed ->
    pendingSnapshotHeight != null &&
        observed > pendingSnapshotHeight &&
        (attemptedHeaderHeight == null || observed > attemptedHeaderHeight)
}

/** Raw native-validated receive values for copy/share controls. */
internal data class WalletHnsReceiveTargets(
    val paymentAddress: String,
    val nameTransferAddress: String?,
)

internal fun NativeWalletReadSnapshot.hnsReceiveTargets(): WalletHnsReceiveTargets =
    WalletHnsReceiveTargets(
        paymentAddress = paymentReceiveTarget.display,
        nameTransferAddress = nameReceiveTarget?.display,
    )

internal fun walletReadCodeLabel(value: String): String = buildString(value.length + 8) {
    value.forEachIndexed { index, character ->
        when {
            character == '_' -> append(' ')
            character.isUpperCase() -> {
                if (index > 0) append(' ')
                append(character.lowercaseChar())
            }
            else -> append(character)
        }
    }
}

/**
 * Do not present a process-local wallet status as proof of remote mempool
 * admission. The raw code remains unchanged for strict ABI parsing and
 * pending-balance arithmetic; only the human-facing transaction label is
 * qualified here.
 */
internal fun walletTransactionStatusLabel(value: String): String = when (value) {
    "broadcast" -> "submitted to peers"
    "mempool" -> "pending (local wallet)"
    else -> walletReadCodeLabel(value)
}

private const val HNS_DECIMAL_PLACES = 6
private const val MAX_HNS_INPUT_CHARACTERS = 46
private val HNS_BASE_UNITS = BigInteger.TEN.pow(HNS_DECIMAL_PLACES)
private val MAX_HNS_BASE_UNITS = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
private val HNS_DECIMAL_INPUT = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?")
private val PENDING_HNS_TRANSACTION_STATUSES = setOf(
    "prepared",
    "authorized",
    "broadcast",
    "mempool",
)
