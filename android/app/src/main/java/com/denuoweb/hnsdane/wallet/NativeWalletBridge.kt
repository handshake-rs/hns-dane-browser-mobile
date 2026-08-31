package com.denuoweb.hnsdane.wallet

import com.denuoweb.hnsdane.net.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray

/**
 * Narrow Android binding for the lifecycle, synchronized-read, and guarded
 * native HNS value controllers.
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

    /** Installs one exact, authority-bound app-owned read-only sidecar binding. */
    fun configureHnsReads(
        currentAuthority: WalletReadBootstrapAuthority,
        configuration: NativeHnsReadConfiguration,
    ): Boolean = configuration.consumeFor(currentAuthority) { loopbackPort, authorization ->
        isValidHandle(currentAuthority.walletHandle) &&
            isAvailable &&
            runCatching {
                nativeConfigureHnsReads(
                    currentAuthority.walletHandle,
                    loopbackPort,
                    authorization,
                )
            }.getOrDefault(false)
    }

    /** Installs the full same-store HNS controller without exposing signing authority to Kotlin. */
    fun configureHnsValue(
        currentAuthority: WalletReadBootstrapAuthority,
        configuration: NativeHnsReadConfiguration,
        databaseKey: ByteArray,
    ): Boolean = consumeDatabaseKey(databaseKey) { key ->
        configuration.consumeForValue(
            currentAuthority,
        ) { loopbackPort, authorization, shakescapePolicyJson ->
            isValidHandle(currentAuthority.walletHandle) &&
                isAvailable &&
                runCatching {
                    nativeConfigureHnsValue(
                        currentAuthority.walletHandle,
                        key,
                        loopbackPort,
                        authorization,
                        shakescapePolicyJson,
                    )
                }.getOrDefault(false)
        }
    }

    /**
     * Installs the wallet-owned direct HNS runtime. Unlike the legacy sidecar
     * adapter this has no RPC endpoint or credential: native code derives the
     * account watch set locally, verifies HNS peers, and broadcasts directly.
     */
    fun configureWalletOwnedDirectHnsValue(
        currentAuthority: WalletReadBootstrapAuthority,
        databaseKey: ByteArray,
        rollbackFloor: ByteArray,
        genesisBootstrapPath: String,
    ): Boolean = try {
        consumeDatabaseKey(databaseKey) { key ->
            consumeDirectHnsRollbackFloor(rollbackFloor) { floor ->
                isValidHandle(currentAuthority.walletHandle) &&
                    isAvailable &&
                    runCatching {
                        nativeConfigureWalletOwnedDirectHnsValue(
                            currentAuthority.walletHandle,
                            key,
                            floor,
                            genesisBootstrapPath,
                        )
                    }.getOrDefault(false)
            }
        }
    } finally {
        rollbackFloor.fill(0)
    }

    fun hasHnsReads(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeHasHnsReads(handle) }.getOrDefault(false)

    fun hasHnsValue(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeHasHnsValue(handle) }.getOrDefault(false)

    /**
     * Performs one bounded synchronization. A catch-up result carries no
     * spendable snapshot, but preserves enough verified progress for the UI
     * to resume a restored wallet without forcing an unsafe controller error.
     */
    fun synchronizeHnsReads(handle: Long): NativeWalletHnsSynchronization? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeSynchronizeHnsReads(handle) }.getOrNull()
                ?: return null
            parseAndWipeHnsSynchronizationBundle(bundle)
        } else {
            null
        }

    /**
     * Reads the direct synchronizer's public progress mailbox without asking
     * the native wallet controller for a wallet projection. This call is safe
     * to poll while [synchronizeHnsReads] is waiting on direct peers.
     */
    fun liveHnsSynchronizationProgress(handle: Long): NativeWalletHnsLiveSyncProgress? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeHnsLiveSynchronizationProgress(handle) }.getOrNull()
                ?: return null
            parseAndWipeHnsLiveSynchronizationProgressBundle(bundle)
        } else {
            null
        }

    /** Requests a stop without waiting for the native wallet controller mutex. */
    fun cancelHnsSynchronization(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeCancelHnsSynchronization(handle) }.getOrDefault(false)

    /**
     * Derive the current ordinary HNS payment address from the unlocked local
     * wallet. This makes no peer or node request and is intentionally not a
     * balance, history, or spend projection.
     */
    fun localHnsReceiveTarget(handle: Long): NativeWalletPaymentReceiveTarget? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeLocalHnsReceiveTarget(handle) }.getOrNull()
                ?: return null
            parseAndWipeLocalHnsReceiveTargetBundle(bundle)
        } else {
            null
        }

    /** The active direct coordinator's authenticated floor, or null for legacy controllers. */
    fun directHnsRollbackFloor(handle: Long): ByteArray? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeDirectHnsRollbackFloor(handle) }.getOrNull()
                ?.takeIf { it.size == DIRECT_HNS_ROLLBACK_FLOOR_BYTES }
                ?: null
        } else {
            null
        }

    /**
     * Services at most one message from the unlocked wallet's own direct
     * Shakescape listener. This never contacts a relay or changes chain authority.
     */
    fun serviceWalletOwnedDirectShakescape(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeServiceWalletOwnedDirectShakescape(handle) }.getOrDefault(false)

    /**
     * Opens one exact user-paired direct board socket. The native boundary
     * accepts only IPv4:port or [IPv6]:port and never resolves a hostname.
     */
    /** Current listener and active direct-peer transport state, if direct Shakescape is installed. */
    fun walletOwnedDirectShakescapeStatus(handle: Long): NativeWalletDirectShakescapeStatus? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeWalletOwnedDirectShakescapeStatus(handle) }.getOrNull()
                ?: return null
            parseAndWipeWalletOwnedDirectShakescapeStatusBundle(bundle)
        } else {
            null
        }

    /** Retry the local direct-Shakescape listener without changing wallet authority. */
    fun retryWalletOwnedDirectShakescapeListener(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeRetryWalletOwnedDirectShakescapeListener(handle) }.getOrDefault(false)

    /** Disconnect only the active direct-Shakescape peer transport. */
    fun disconnectWalletOwnedDirectShakescape(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeDisconnectWalletOwnedDirectShakescape(handle) }.getOrDefault(false)

    fun connectWalletOwnedDirectShakescape(
        handle: Long,
        endpoint: String,
    ): NativeWalletDirectShakescapeConnectResult? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeConnectWalletOwnedDirectShakescape(handle, endpoint) }.getOrNull()
                ?.let(::parseAndWipeWalletOwnedDirectShakescapeConnectBundle)
        } else {
            null
        }

    fun prepareBtcForHnsOffer(
        handle: Long,
        btcAmountSats: Long,
        hnsAmountDollarydoos: Long,
        bitcoinFeeReserveSats: Long,
        listingLifetimeSeconds: Long,
    ): NativeBtcForHnsOfferApproval? {
        if (
            !isValidHandle(handle) || !isAvailable || btcAmountSats <= 0L ||
            hnsAmountDollarydoos <= 0L || bitcoinFeeReserveSats <= 0L ||
            listingLifetimeSeconds <= 0L
        ) return null
        val bundle = runCatching {
            nativePrepareBtcForHnsOffer(
                handle,
                btcAmountSats,
                hnsAmountDollarydoos,
                bitcoinFeeReserveSats,
                listingLifetimeSeconds,
            )
        }.getOrNull() ?: return null
        return try {
            NativeBitcoinWalletBundle.btcForHnsApproval(bundle)
        } finally {
            bundle.fill(0)
        }
    }

    fun approveBtcForHnsOffer(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeBtcForHnsOfferSummary? = actionToken.consume { tokenAscii ->
        if (!isValidHandle(handle) || !isAvailable) return@consume null
        val bundle = runCatching { nativeApproveBtcForHnsOffer(handle, tokenAscii) }.getOrNull()
            ?: return@consume null
        try {
            NativeBitcoinWalletBundle.btcForHnsSummary(bundle)
        } finally {
            bundle.fill(0)
        }
    }

    fun rejectBtcForHnsOffer(handle: Long, actionToken: NativeHnsValueActionToken): Boolean =
        actionToken.consume { tokenAscii ->
            isValidHandle(handle) && isAvailable &&
                runCatching { nativeRejectBtcForHnsOffer(handle, tokenAscii) }.getOrDefault(false)
        } ?: false

    fun localBtcForHnsOffers(handle: Long): List<NativeBtcForHnsOfferSummary>? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeLocalBtcForHnsOffers(handle) }.getOrNull()
                ?: return null
            try {
                NativeBitcoinWalletBundle.btcForHnsOffers(bundle)
            } finally {
                bundle.fill(0)
            }
        } else {
            null
        }

    fun shakescapeExecutions(handle: Long): NativeShakescapeExecutionStatus? =
        if (isValidHandle(handle) && isAvailable) {
            val bundle = runCatching { nativeShakescapeExecutions(handle) }.getOrNull()
                ?: return null
            try {
                NativeBitcoinWalletBundle.shakescapeExecutions(bundle)
            } finally {
                bundle.fill(0)
            }
        } else {
            null
        }

    fun cancelBtcForHnsOffer(handle: Long, offerId: String): Boolean =
        isValidHandle(handle) && isAvailable &&
            offerId.length == 64 && offerId.all { it in '0'..'9' || it in 'a'..'f' } &&
            runCatching { nativeCancelBtcForHnsOffer(handle, offerId) }.getOrDefault(false)

    /** Consumes one exact UTF-8 name for the trusted native read controller only. */
    fun importHnsNameExactText(handle: Long, exactUtf8: ByteArray): NativeWalletName? = try {
        if (
            !isAvailable || !isValidHandle(handle) ||
            exactUtf8.size !in 1..MAX_HNS_NAME_BYTES
        ) {
            null
        } else {
            // JNI clears its input array before entering the potentially
            // blocking controller call. Retain one bounded mutable comparison
            // copy and wipe it on every return path.
            val expectedUtf8 = exactUtf8.copyOf()
            try {
                val bundle = runCatching { nativeImportHnsNameExactText(handle, exactUtf8) }
                    .getOrNull() ?: return null
                val imported = parseAndWipeHnsNameImportBundle(bundle)
                if (imported == null || !walletNameImportEchoMatches(imported, expectedUtf8)) {
                    // A non-null native reply means the mutation may have committed.
                    // Malformed or non-exact projection therefore poisons this session.
                    lock(handle)
                    null
                } else {
                    imported
                }
            } finally {
                expectedUtf8.fill(0)
            }
        }
    } finally {
        exactUtf8.fill(0)
    }

    /** Imports one bounded exact-name batch; native performs one atomic commit and no refresh. */
    fun importHnsNamesExactText(handle: Long, exactNames: List<String>): Int {
        if (
            !isAvailable || !isValidHandle(handle) ||
            exactNames.isEmpty() || exactNames.size > MAX_BULK_HNS_NAMES ||
            exactNames.toSet().size != exactNames.size ||
            exactNames.any { name ->
                val encoded = name.toByteArray(Charsets.UTF_8)
                encoded.size !in 1..MAX_HNS_NAME_BYTES ||
                    encoded.toString(Charsets.UTF_8) != name
            }
        ) {
            return 0
        }
        val encoded = JSONArray(exactNames).toString().toByteArray(Charsets.UTF_8)
        return try {
            if (encoded.size > MAX_BULK_HNS_NAMES_JSON_BYTES) 0 else
                runCatching { nativeImportHnsNamesExactText(handle, encoded) }.getOrDefault(0)
        } finally {
            encoded.fill(0)
        }
    }

    /** Reads a page retained by the last authenticated synchronization without another sync. */
    fun hnsNamePage(handle: Long, offset: Int, limit: Int = HNS_NAME_PAGE_SIZE): NativeWalletNamePage? =
        if (
            isAvailable && isValidHandle(handle) && offset >= 0 &&
            limit in 1..HNS_NAME_PAGE_SIZE
        ) {
            runCatching { nativeHnsNamePage(handle, offset, limit) }.getOrNull()?.let { bundle ->
                try {
                    NativeWalletNamePage.parse(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    internal fun parseAndWipeHnsReadBundle(bundle: ByteArray): NativeWalletReadSnapshot? = try {
        NativeWalletReadSnapshot.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsSynchronizationBundle(
        bundle: ByteArray,
    ): NativeWalletHnsSynchronization? = try {
        NativeWalletHnsSynchronization.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsLiveSynchronizationProgressBundle(
        bundle: ByteArray,
    ): NativeWalletHnsLiveSyncProgress? = try {
        NativeWalletHnsLiveSyncProgress.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeWalletOwnedDirectShakescapeStatusBundle(
        bundle: ByteArray,
    ): NativeWalletDirectShakescapeStatus? = try {
        NativeWalletDirectShakescapeStatus.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeWalletOwnedDirectShakescapeConnectBundle(
        bundle: ByteArray,
    ): NativeWalletDirectShakescapeConnectResult? = try {
        NativeWalletDirectShakescapeConnectResult.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeLocalHnsReceiveTargetBundle(
        bundle: ByteArray,
    ): NativeWalletPaymentReceiveTarget? = try {
        NativeWalletPaymentReceiveTarget.parseLocal(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsNameImportBundle(
        bundle: ByteArray,
    ): NativeWalletName? = try {
        NativeWalletNameImportBundle.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    /** Prepares but does not execute one exact HNS send. Every input is consumed. */
    fun prepareHnsSend(
        handle: Long,
        recipientUtf8: ByteArray,
        amountBaseUnitsAscii: ByteArray,
        maximumFeeBaseUnitsAscii: ByteArray,
    ): NativeHnsSendApproval? = try {
        if (
            !isAvailable || !isValidHandle(handle) ||
            recipientUtf8.size !in 1..MAX_HNS_RECIPIENT_BYTES ||
            amountBaseUnitsAscii.size !in 1..MAX_BASE_UNITS_ASCII_BYTES ||
            maximumFeeBaseUnitsAscii.size !in 1..MAX_BASE_UNITS_ASCII_BYTES
        ) {
            null
        } else {
            val bundle = runCatching {
                nativePrepareHnsSend(
                    handle,
                    recipientUtf8,
                    amountBaseUnitsAscii,
                    maximumFeeBaseUnitsAscii,
                )
            }.getOrNull() ?: return null
            val approval = parseAndWipeHnsSendApprovalBundle(bundle)
            if (approval == null) {
                // Native preparation may already have registered an approval.
                // A malformed display projection makes that session unusable.
                lock(handle)
            }
            approval
        }
    } finally {
        recipientUtf8.fill(0)
        amountBaseUnitsAscii.fill(0)
        maximumFeeBaseUnitsAscii.fill(0)
    }

    /** Prepares any closed native name/Shakedex action without accepting raw provider calls. */
    fun prepareHnsValueAction(
        handle: Long,
        intent: NativeHnsValueIntent,
    ): NativeHnsValueApproval? {
        val intentJson = intent.encodeJson() ?: return null
        return try {
            if (!isAvailable || !isValidHandle(handle)) return null
            val bundle = runCatching { nativePrepareHnsValueAction(handle, intentJson) }
                .getOrNull() ?: return null
            val approval = parseAndWipeHnsValueApprovalBundle(bundle)
            if (approval == null) lock(handle)
            approval
        } finally {
            intentJson.fill(0)
        }
    }

    fun queryShakedex(
        handle: Long,
        query: NativeShakedexQuery,
    ): NativeShakedexQueryResult? {
        val queryJson = query.encodeJson() ?: return null
        return try {
            if (!isAvailable || !isValidHandle(handle)) return null
            val bundle = runCatching { nativeQueryShakedex(handle, queryJson) }
                .getOrNull() ?: return null
            parseAndWipeShakedexQueryBundle(bundle)
        } finally {
            queryJson.fill(0)
        }
    }

    /** Consumes the exact one-shot token and returns only a validated send receipt. */
    fun approveHnsValueAction(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeHnsSendReceipt? = actionToken.consume { tokenAscii ->
        if (!isAvailable || !isValidHandle(handle)) return@consume null
        val bundle = runCatching { nativeApproveHnsValueAction(handle, tokenAscii) }.getOrNull()
        val receipt = bundle?.let(::parseAndWipeHnsSendReceiptBundle)
        if (receipt == null && status(handle)?.locked != false) {
            // The native controller deliberately remains unlocked only for
            // its exact, proven pre-broadcast retry state. Every other
            // missing receipt may follow authorization or peer broadcast, so
            // retain the fail-closed lock before a later send can begin.
            lock(handle)
        }
        receipt
    }

    /** Consumes one non-send action token and returns the bounded native provider result. */
    fun approveHnsValueActionResult(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeHnsValueResult? = actionToken.consume { tokenAscii ->
        if (!isAvailable || !isValidHandle(handle)) return@consume null
        val bundle = runCatching { nativeApproveHnsValueActionResult(handle, tokenAscii) }
            .getOrNull() ?: return@consume null
        val result = parseAndWipeHnsValueResultBundle(bundle)
        if (result == null) lock(handle)
        result
    }

    /** Consumes the exact token while asking Rust to discard the pending approval. */
    fun rejectHnsValueAction(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): Boolean = actionToken.consume { tokenAscii ->
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeRejectHnsValueAction(handle, tokenAscii) }.getOrDefault(false)
    } ?: false

    internal fun parseAndWipeHnsSendApprovalBundle(
        bundle: ByteArray,
    ): NativeHnsSendApproval? = try {
        NativeHnsSendApproval.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsSendReceiptBundle(
        bundle: ByteArray,
    ): NativeHnsSendReceipt? = try {
        NativeHnsSendReceipt.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsValueApprovalBundle(
        bundle: ByteArray,
    ): NativeHnsValueApproval? = try {
        NativeHnsValueApproval.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeHnsValueResultBundle(
        bundle: ByteArray,
    ): NativeHnsValueResult? = try {
        NativeHnsValueResult.parse(bundle)
    } finally {
        bundle.fill(0)
    }

    internal fun parseAndWipeShakedexQueryBundle(
        bundle: ByteArray,
    ): NativeShakedexQueryResult? = try {
        NativeShakedexQueryResult.parse(bundle)
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

    /** True only while the direct Kyoto controller is active under this wallet's unlock. */
    fun hasBitcoinValue(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeHasBitcoinValue(handle) }.getOrDefault(false)

    fun bitcoinSnapshot(handle: Long): NativeBitcoinWalletSnapshot? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeBitcoinSnapshot(handle) }.getOrNull()?.let { bundle ->
                try {
                    NativeBitcoinWalletBundle.snapshot(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    /**
     * Resets an incomplete recovery scan to the locally validated predecessor
     * of the earliest block that may contain wallet activity.
     */
    fun setBitcoinBirthdayHeight(
        handle: Long,
        earliestTransactionHeight: Long,
    ): NativeBitcoinWalletSnapshot? =
        if (
            isValidHandle(handle) && isAvailable &&
            earliestTransactionHeight in 1..Int.MAX_VALUE.toLong()
        ) {
            runCatching {
                nativeSetBitcoinBirthdayHeight(handle, earliestTransactionHeight.toInt())
            }.getOrNull()?.let { bundle ->
                try {
                    NativeBitcoinWalletBundle.snapshot(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    /** Reveal and persist one locally derived BIP84 receive address. */
    fun nextBitcoinReceiveAddress(handle: Long): NativeBitcoinReceiveAddress? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeNextBitcoinReceiveAddress(handle) }.getOrNull()?.let { bundle ->
                try {
                    NativeBitcoinWalletBundle.receive(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    /** Drive one user-scheduled, wallet-owned Kyoto compact-filter cycle. */
    fun synchronizeBitcoin(handle: Long): NativeBitcoinSynchronization? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeSynchronizeBitcoin(handle) }.getOrNull()?.let { bundle ->
                try {
                    NativeBitcoinWalletBundle.synchronization(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    /** Interrupts the active direct Bitcoin synchronization outside its controller lock. */
    fun stopBitcoinSynchronization(handle: Long): Boolean =
        isValidHandle(handle) && isAvailable &&
            runCatching { nativeStopBitcoinSynchronization(handle) }.getOrDefault(false)

    fun bitcoinSyncProgress(handle: Long): NativeBitcoinSyncProgress? =
        if (isValidHandle(handle) && isAvailable) {
            runCatching { nativeBitcoinSyncProgress(handle) }.getOrNull()?.let { bundle ->
                try {
                    NativeBitcoinWalletBundle.syncProgress(bundle)
                } finally {
                    bundle.fill(0)
                }
            }
        } else {
            null
        }

    /** Prepares a direct Bitcoin send; no signature or network submission occurs here. */
    fun prepareBitcoinSend(
        handle: Long,
        destination: String,
        amountSats: Long,
        maximumFeeSats: Long,
    ): NativeBitcoinSendApproval? {
        if (
            !isValidHandle(handle) || !isAvailable || destination.length !in 1..128 ||
            amountSats <= 0L || maximumFeeSats <= 0L
        ) return null
        val destinationUtf8 = destination.toByteArray(Charsets.US_ASCII)
        val amountAscii = amountSats.toString().toByteArray(Charsets.US_ASCII)
        val feeAscii = maximumFeeSats.toString().toByteArray(Charsets.US_ASCII)
        return try {
            val bundle = runCatching {
                nativePrepareBitcoinSend(handle, destinationUtf8, amountAscii, feeAscii)
            }.getOrNull() ?: return null
            val approval = try {
                NativeBitcoinWalletBundle.sendApproval(bundle)
            } finally {
                bundle.fill(0)
            }
            if (approval == null) lock(handle)
            approval
        } finally {
            destinationUtf8.fill(0)
            amountAscii.fill(0)
            feeAscii.fill(0)
        }
    }

    /** Consumes the displayed direct Bitcoin approval exactly once. */
    fun approveBitcoinSend(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeBitcoinSendReceipt? = actionToken.consume { tokenAscii ->
        if (!isValidHandle(handle) || !isAvailable) return@consume null
        val bundle = runCatching { nativeApproveBitcoinSend(handle, tokenAscii) }.getOrNull()
            ?: return@consume null
        val receipt = try {
            NativeBitcoinWalletBundle.sendReceipt(bundle)
        } finally {
            bundle.fill(0)
        }
        if (receipt == null) lock(handle)
        receipt
    }

    fun rejectBitcoinSend(handle: Long, actionToken: NativeHnsValueActionToken): Boolean =
        actionToken.consume { tokenAscii ->
            isValidHandle(handle) && isAvailable &&
                runCatching { nativeRejectBitcoinSend(handle, tokenAscii) }.getOrDefault(false)
        } ?: false

    fun prepareBtcForHnsFunding(
        handle: Long,
        sessionId: String,
        maximumFeeSats: Long,
    ): NativeBitcoinHtlcFundingApproval? {
        if (!isValidHandle(handle) || !isAvailable || !isCanonicalHash(sessionId) || maximumFeeSats <= 0L) {
            return null
        }
        val sessionAscii = sessionId.toByteArray(Charsets.US_ASCII)
        val feeAscii = maximumFeeSats.toString().toByteArray(Charsets.US_ASCII)
        return try {
            val bundle = runCatching {
                nativePrepareBtcForHnsFunding(handle, sessionAscii, feeAscii)
            }.getOrNull() ?: return null
            try {
                NativeBitcoinWalletBundle.htlcFundingApproval(bundle)
            } finally {
                bundle.fill(0)
            }
        } finally {
            sessionAscii.fill(0)
            feeAscii.fill(0)
        }
    }

    fun approveBtcForHnsFunding(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeBitcoinHtlcFundingReceipt? = actionToken.consume { tokenAscii ->
        if (!isValidHandle(handle) || !isAvailable) return@consume null
        val bundle = runCatching { nativeApproveBtcForHnsFunding(handle, tokenAscii) }.getOrNull()
            ?: return@consume null
        try {
            NativeBitcoinWalletBundle.htlcFundingReceipt(bundle)
        } finally {
            bundle.fill(0)
        }
    }

    fun rejectBtcForHnsFunding(handle: Long, actionToken: NativeHnsValueActionToken): Boolean =
        actionToken.consume { tokenAscii ->
            isValidHandle(handle) && isAvailable &&
                runCatching { nativeRejectBtcForHnsFunding(handle, tokenAscii) }.getOrDefault(false)
        } ?: false

    fun prepareHnsForBtcFunding(
        handle: Long,
        sessionId: String,
        maximumFeeDollarydoos: Long,
    ): NativeHnsHtlcFundingApproval? {
        if (!isValidHandle(handle) || !isAvailable || !isCanonicalHash(sessionId) || maximumFeeDollarydoos <= 0L) {
            return null
        }
        val sessionAscii = sessionId.toByteArray(Charsets.US_ASCII)
        val feeAscii = maximumFeeDollarydoos.toString().toByteArray(Charsets.US_ASCII)
        return try {
            val bundle = runCatching {
                nativePrepareHnsForBtcFunding(handle, sessionAscii, feeAscii)
            }.getOrNull() ?: return null
            try {
                NativeBitcoinWalletBundle.hnsHtlcFundingApproval(bundle)
            } finally {
                bundle.fill(0)
            }
        } finally {
            sessionAscii.fill(0)
            feeAscii.fill(0)
        }
    }

    fun approveHnsForBtcFunding(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
    ): NativeHnsHtlcFundingReceipt? = actionToken.consume { tokenAscii ->
        if (!isValidHandle(handle) || !isAvailable) return@consume null
        val bundle = runCatching { nativeApproveHnsForBtcFunding(handle, tokenAscii) }.getOrNull()
            ?: return@consume null
        try {
            NativeBitcoinWalletBundle.hnsHtlcFundingReceipt(bundle)
        } finally {
            bundle.fill(0)
        }
    }

    fun rejectHnsForBtcFunding(handle: Long, actionToken: NativeHnsValueActionToken): Boolean =
        actionToken.consume { tokenAscii ->
            isValidHandle(handle) && isAvailable &&
                runCatching { nativeRejectHnsForBtcFunding(handle, tokenAscii) }.getOrDefault(false)
        } ?: false

    fun prepareSwapSettlement(
        handle: Long,
        sessionId: String,
        action: String,
        maximumFee: Long,
        bitcoin: Boolean,
    ): NativeSwapSettlementApproval? {
        if (!isValidHandle(handle) || !isAvailable || !isCanonicalHash(sessionId) ||
            action !in setOf("redeem", "refund") || maximumFee <= 0L
        ) return null
        val sessionAscii = sessionId.toByteArray(Charsets.US_ASCII)
        val feeAscii = maximumFee.toString().toByteArray(Charsets.US_ASCII)
        return try {
            val bundle = runCatching {
                if (bitcoin) nativePrepareBitcoinSwapSettlement(
                    handle, sessionAscii, action == "redeem", feeAscii,
                ) else nativePrepareHnsSwapSettlement(
                    handle, sessionAscii, action == "redeem", feeAscii,
                )
            }.getOrNull() ?: return null
            try {
                NativeBitcoinWalletBundle.swapSettlementApproval(bundle, bitcoin)
            } finally {
                bundle.fill(0)
            }
        } finally {
            sessionAscii.fill(0)
            feeAscii.fill(0)
        }
    }

    fun approveSwapSettlement(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
        bitcoin: Boolean,
    ): NativeSwapSettlementReceipt? = actionToken.consume { tokenAscii ->
        if (!isValidHandle(handle) || !isAvailable) return@consume null
        val bundle = runCatching {
            if (bitcoin) nativeApproveBitcoinSwapSettlement(handle, tokenAscii)
            else nativeApproveHnsSwapSettlement(handle, tokenAscii)
        }.getOrNull() ?: return@consume null
        try {
            NativeBitcoinWalletBundle.swapSettlementReceipt(bundle, bitcoin)
        } finally {
            bundle.fill(0)
        }
    }

    fun rejectSwapSettlement(
        handle: Long,
        actionToken: NativeHnsValueActionToken,
        bitcoin: Boolean,
    ): Boolean = actionToken.consume { tokenAscii ->
        isValidHandle(handle) && isAvailable && runCatching {
            if (bitcoin) nativeRejectBitcoinSwapSettlement(handle, tokenAscii)
            else nativeRejectHnsSwapSettlement(handle, tokenAscii)
        }.getOrDefault(false)
    } ?: false

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

    private inline fun <T> consumeDirectHnsRollbackFloor(
        floor: ByteArray,
        block: (ByteArray) -> T,
    ): T {
        require(floor.size == DIRECT_HNS_ROLLBACK_FLOOR_BYTES) {
            "Direct HNS rollback floor must be 36 bytes"
        }
        return block(floor)
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
        val hnsReadsEnabled = flags and STATUS_HNS_READS != 0
        val hnsValueEnabled = flags and STATUS_HNS_VALUE != 0
        val shakedexEnabled = flags and STATUS_SHAKEDEX != 0
        val mainnetSettlementEnabled = flags and STATUS_MAINNET_SETTLEMENT != 0
        if (hasActiveWallet == walletId.all { it == 0.toByte() }) return null
        if (locked == hasActiveWallet) return null
        if (
            hnsValueEnabled && !hnsReadsEnabled ||
            shakedexEnabled && !hnsValueEnabled ||
            mainnetSettlementEnabled && !hnsValueEnabled
        ) return null
        return NativeWalletStatus(
            locked = locked,
            activeWalletId = walletId.takeIf { hasActiveWallet }?.toLowerHex(),
            hnsReadsEnabled = hnsReadsEnabled,
            hnsValueEnabled = hnsValueEnabled,
            shakedexEnabled = shakedexEnabled,
            mainnetSettlementEnabled = mainnetSettlementEnabled,
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

    private fun isCanonicalHash(value: String): Boolean =
        value.length == 64 &&
            value.any { it != '0' } &&
            value.all { it in '0'..'9' || it in 'a'..'f' }

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
    private external fun nativeConfigureHnsValue(
        handle: Long,
        databaseKey: ByteArray,
        loopbackPort: Int,
        authorization: CharArray,
        shakescapePolicyJson: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativeConfigureWalletOwnedDirectHnsValue(
        handle: Long,
        databaseKey: ByteArray,
        rollbackFloor: ByteArray,
        genesisBootstrapPath: String,
    ): Boolean

    @JvmStatic
    private external fun nativeDirectHnsRollbackFloor(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeServiceWalletOwnedDirectShakescape(handle: Long): Boolean

    @JvmStatic
    private external fun nativeWalletOwnedDirectShakescapeStatus(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeRetryWalletOwnedDirectShakescapeListener(handle: Long): Boolean

    @JvmStatic
    private external fun nativeDisconnectWalletOwnedDirectShakescape(handle: Long): Boolean

    @JvmStatic
    private external fun nativeConnectWalletOwnedDirectShakescape(handle: Long, endpoint: String): ByteArray?

    @JvmStatic
    private external fun nativePrepareBtcForHnsOffer(
        handle: Long,
        btcAmountSats: Long,
        hnsAmountDollarydoos: Long,
        bitcoinFeeReserveSats: Long,
        listingLifetimeSeconds: Long,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveBtcForHnsOffer(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectBtcForHnsOffer(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativeLocalBtcForHnsOffers(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeShakescapeExecutions(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeCancelBtcForHnsOffer(handle: Long, offerId: String): Boolean

    @JvmStatic
    private external fun nativeHasHnsReads(handle: Long): Boolean

    @JvmStatic
    private external fun nativeHasHnsValue(handle: Long): Boolean

    @JvmStatic
    private external fun nativeHasBitcoinValue(handle: Long): Boolean

    @JvmStatic
    private external fun nativeBitcoinSnapshot(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeSetBitcoinBirthdayHeight(
        handle: Long,
        earliestTransactionHeight: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeNextBitcoinReceiveAddress(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeSynchronizeBitcoin(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeStopBitcoinSynchronization(handle: Long): Boolean

    @JvmStatic
    private external fun nativePrepareBitcoinSend(
        handle: Long,
        destinationUtf8: ByteArray,
        amountSatsAscii: ByteArray,
        maximumFeeSatsAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveBitcoinSend(handle: Long, actionTokenAscii: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeRejectBitcoinSend(handle: Long, actionTokenAscii: ByteArray): Boolean

    @JvmStatic
    private external fun nativePrepareBtcForHnsFunding(
        handle: Long,
        sessionIdAscii: ByteArray,
        maximumFeeSatsAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveBtcForHnsFunding(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectBtcForHnsFunding(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativePrepareHnsForBtcFunding(
        handle: Long,
        sessionIdAscii: ByteArray,
        maximumFeeAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveHnsForBtcFunding(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectHnsForBtcFunding(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativePrepareBitcoinSwapSettlement(
        handle: Long,
        sessionIdAscii: ByteArray,
        redeem: Boolean,
        maximumFeeAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveBitcoinSwapSettlement(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectBitcoinSwapSettlement(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativePrepareHnsSwapSettlement(
        handle: Long,
        sessionIdAscii: ByteArray,
        redeem: Boolean,
        maximumFeeAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveHnsSwapSettlement(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectHnsSwapSettlement(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativeSynchronizeHnsReads(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeHnsLiveSynchronizationProgress(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeCancelHnsSynchronization(handle: Long): Boolean

    @JvmStatic
    private external fun nativeLocalHnsReceiveTarget(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeImportHnsNameExactText(
        handle: Long,
        exactUtf8: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeImportHnsNamesExactText(handle: Long, exactNamesJson: ByteArray): Int

    @JvmStatic
    private external fun nativeHnsNamePage(handle: Long, offset: Int, limit: Int): ByteArray?

    @JvmStatic
    private external fun nativePrepareHnsSend(
        handle: Long,
        recipientUtf8: ByteArray,
        amountBaseUnitsAscii: ByteArray,
        maximumFeeBaseUnitsAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativePrepareHnsValueAction(
        handle: Long,
        intentJson: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeQueryShakedex(
        handle: Long,
        queryJson: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveHnsValueAction(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeApproveHnsValueActionResult(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): ByteArray?

    @JvmStatic
    private external fun nativeRejectHnsValueAction(
        handle: Long,
        actionTokenAscii: ByteArray,
    ): Boolean

    @JvmStatic
    private external fun nativeUnlock(handle: Long, databaseKey: ByteArray): Boolean

    @JvmStatic
    private external fun nativeLock(handle: Long): Boolean

    @JvmStatic
    private external fun nativeTakeRecovery(handle: Long): CharArray?

    @JvmStatic
    private external fun nativeBitcoinSyncProgress(handle: Long): ByteArray?

    @JvmStatic
    private external fun nativeDestroy(handle: Long): Boolean

    private const val INVALID_HANDLE = 0L
    private const val DATABASE_KEY_BYTES = 32
    private const val WALLET_ID_BYTES = 16
    private const val ACCOUNT_ID_BYTES = 16
    private const val MAX_RECOVERY_CHARACTERS = 256
    private const val MAX_ACCOUNT_LABEL_BYTES = 128
    private const val MAX_HNS_NAME_BYTES = 63
    private const val MAX_BULK_HNS_NAMES = 10_000
    private const val MAX_BULK_HNS_NAMES_JSON_BYTES = 1024 * 1024
    private const val HNS_NAME_PAGE_SIZE = 64
    private const val MAX_HNS_RECIPIENT_BYTES = 512
    private const val MAX_BASE_UNITS_ASCII_BYTES = 39
    private const val STATUS_BUNDLE_BYTES = 24
    private const val ACCOUNT_FIXED_BYTES = 28
    private const val BUNDLE_VERSION = 1
    private const val STATUS_LOCKED = 1
    private const val STATUS_ACTIVE_WALLET = 1 shl 1
    private const val STATUS_HNS_READS = 1 shl 2
    private const val STATUS_HNS_VALUE = 1 shl 3
    private const val STATUS_SHAKEDEX = 1 shl 4
    private const val STATUS_MAINNET_SETTLEMENT = 1 shl 5
    private const val STATUS_ALLOWED_FLAGS = STATUS_LOCKED or STATUS_ACTIVE_WALLET or
        STATUS_HNS_READS or STATUS_HNS_VALUE or STATUS_SHAKEDEX or STATUS_MAINNET_SETTLEMENT
    private const val MODULE_HANDSHAKE = 1
    private const val HEX = "0123456789abcdef"
    private val STATUS_MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'S'.code.toByte())
    private val ACCOUNT_MAGIC = byteArrayOf('H'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 'A'.code.toByte())
    private const val DIRECT_HNS_ROLLBACK_FLOOR_BYTES = 36
}

internal data class NativeWalletStatus(
    val locked: Boolean,
    val activeWalletId: String?,
    val hnsReadsEnabled: Boolean,
    val hnsValueEnabled: Boolean,
    val shakedexEnabled: Boolean,
    val mainnetSettlementEnabled: Boolean,
)

internal data class NativeWalletAccount(
    val accountId: String,
    val module: String,
    val label: String,
)
