package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.wallet.AndroidWalletKeyStore
import com.denuoweb.hnsdane.wallet.NativeWalletBridge
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.ProcessWalletStorageOwnership
import com.denuoweb.hnsdane.wallet.WalletLeaseReleaseHandoff
import com.denuoweb.hnsdane.wallet.WalletStorageOwnershipGate
import com.denuoweb.hnsdane.wallet.displayAmount
import com.denuoweb.hnsdane.wallet.formatHnsBaseUnits
import com.denuoweb.hnsdane.wallet.walletReadMayPublish
import com.denuoweb.hnsdane.wallet.walletReadCodeLabel
import com.denuoweb.hnsdane.wallet.walletSetupMayInspectStorage
import com.denuoweb.hnsdane.wallet.walletStorageNamespace
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.math.ceil

/** Dedicated native-only controller for one non-value Handshake wallet account. */
class WalletActivity : ComponentActivity() {
    private lateinit var keyStore: AndroidWalletKeyStore
    private lateinit var walletNetwork: HandshakeNetwork
    private lateinit var walletDatabaseFile: File
    private lateinit var walletStoragePath: String
    private lateinit var statusView: TextView
    private lateinit var accountView: TextView
    private lateinit var readStatusView: TextView
    private lateinit var balanceView: TextView
    private lateinit var receiveView: TextView
    private lateinit var historyView: TextView
    private lateinit var trackedNamesView: TextView
    private lateinit var restoreInput: EditText
    private lateinit var recoveryView: RecoveryPhraseView

    private var walletHandle = INVALID_HANDLE
    private var busy = false
    private var lifecycleEpoch = 0L
    private var foreground = false
    private var unconfirmedDatabaseKey: ByteArray? = null
    private var storageOwner: WalletStorageOwnershipGate.Owner? = null
    private var storageLease: WalletStorageOwnershipGate.Lease? = null
    private val leaseReleaseHandoff = WalletLeaseReleaseHandoff()

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.clear()
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        walletNetwork = HnsResolutionPreferences.handshakeNetwork(this)
        val storageNamespace = walletStorageNamespace(walletNetwork.id)
        walletDatabaseFile = File(
            File(noBackupFilesDir, storageNamespace.directoryName),
            WALLET_DATABASE,
        ).absoluteFile
        walletStoragePath = walletDatabaseFile.path
        keyStore = AndroidWalletKeyStore(applicationContext, walletNetwork.id)

        statusView = preferenceSummary(
            text = getString(R.string.wallet_status_starting),
            maxLines = Int.MAX_VALUE,
            bold = true,
        )
        accountView = preferenceSummary(
            text = getString(R.string.wallet_account_locked),
            maxLines = Int.MAX_VALUE,
        )
        readStatusView = walletReadSummary(R.string.wallet_reads_unavailable)
        balanceView = walletReadSummary(R.string.wallet_reads_balance_unavailable)
        receiveView = walletReadSummary(R.string.wallet_reads_receive_unavailable)
        historyView = walletReadSummary(R.string.wallet_reads_history_unavailable)
        trackedNamesView = walletReadSummary(R.string.wallet_reads_names_unavailable)
        restoreInput = sensitiveRestoreInput()
        recoveryView = RecoveryPhraseView(this)

        setSecondaryScreen(getString(R.string.screen_wallet)) {
            addView(screenSection(getString(R.string.section_wallet_status)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_status),
                    summaryView = statusView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_account),
                    summaryView = accountView,
                ))
            })
            addView(screenSection(getString(R.string.section_wallet_setup)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_create),
                    summary = getString(R.string.row_wallet_create_summary),
                    actionLabel = getString(R.string.action_create_wallet),
                ) {
                    createWallet()
                })
                addView(restoreInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_restore),
                    summary = getString(R.string.row_wallet_restore_summary),
                    actionLabel = getString(R.string.action_restore_wallet),
                ) {
                    restoreWallet()
                })
            })
            addView(screenSection(getString(R.string.section_wallet_recovery)) {
                addView(recoveryView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_recovery_confirm),
                    summary = getString(R.string.row_wallet_recovery_confirm_summary),
                    actionLabel = getString(R.string.action_saved_wallet_recovery),
                ) {
                    confirmRecoverySaved()
                })
            })
            addView(screenSection(getString(R.string.section_wallet_access)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_unlock),
                    summary = getString(R.string.row_wallet_unlock_summary),
                    actionLabel = getString(R.string.action_unlock_wallet),
                ) {
                    unlockWallet()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_lock),
                    summary = getString(R.string.row_wallet_lock_summary),
                    actionLabel = getString(R.string.action_lock_wallet),
                ) {
                    lockWallet()
                })
            })
            addView(screenSection(getString(R.string.section_wallet_reads)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_module),
                    summaryView = readStatusView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_balance),
                    summaryView = balanceView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_receive),
                    summaryView = receiveView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_history),
                    summaryView = historyView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_names),
                    summaryView = trackedNamesView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_sync),
                    summary = getString(R.string.row_wallet_read_sync_summary),
                    actionLabel = getString(R.string.action_sync_wallet_reads),
                ) {
                    synchronizeWalletReads()
                })
            })
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        lifecycleEpoch += 1
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        lateinit var owner: WalletStorageOwnershipGate.Owner
        owner = ProcessWalletStorageOwnership.newOwner(walletStoragePath) {
            runOnUiThread { revokeStorageOwnership(owner) }
        }
        storageOwner = owner
        requestStorageLease(owner)
    }

    override fun onStop() {
        foreground = false
        lifecycleEpoch += 1
        storageOwner?.let(ProcessWalletStorageOwnership::retire)
        storageOwner = null
        clearRestoreInput()
        recoveryView.clearSecret()
        val hadUnconfirmedWallet = unconfirmedDatabaseKey != null
        unconfirmedDatabaseKey?.fill(0)
        unconfirmedDatabaseKey = null
        val lease = storageLease
        val retirementStarted = lease != null && retireControllerAfterNativeOperation(lease)
        if (!retirementStarted) destroyController()
        resetReadProjection(R.string.wallet_reads_unavailable)
        if (hadUnconfirmedWallet && lease != null) {
            deleteWalletFiles()
        }
        if (!busy && lease != null) releaseStorageLeaseAfterOperation(lease)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.clear()
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    private fun openExistingWallet() {
        val lease = currentStorageLease() ?: return
        if (!beginOperation(lease, getString(R.string.wallet_status_opening))) return
        val epoch = lifecycleEpoch
        val path = walletDatabaseFile.absolutePath
        thread(name = "hns-wallet-open") {
            val opened = runCatching {
                keyStore.withDatabaseKey { key -> NativeWalletBridge.open(path, key) }
                    ?: INVALID_HANDLE
            }.getOrDefault(INVALID_HANDLE)
            runOnUiThread {
                busy = false
                if (!operationIsCurrent(epoch, lease)) {
                    NativeWalletBridge.destroy(opened)
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (opened == INVALID_HANDLE) {
                    statusView.text = getString(R.string.wallet_status_open_failed)
                    accountView.text = getString(R.string.wallet_account_unavailable)
                } else {
                    walletHandle = opened
                    refreshControllerState()
                }
            }
        }
    }

    private fun createWallet() {
        val lease = currentStorageLease() ?: return
        if (
            !canStartNewWallet(lease) ||
            !beginOperation(lease, getString(R.string.wallet_status_creating))
        ) return
        val epoch = lifecycleEpoch
        val path = walletDatabaseFile.absolutePath
        val network = walletNetworkCode(walletNetwork)
        val databaseKey = randomDatabaseKey()
        thread(name = "hns-wallet-create") {
            val created = NativeWalletBridge.create(
                path,
                databaseKey.copyOf(),
                network,
                SAFE_FULL_RESCAN_BIRTHDAY,
            )
            val recovery = if (created != INVALID_HANDLE) {
                NativeWalletBridge.takeRecovery(created)
            } else {
                null
            }
            runOnUiThread {
                busy = false
                val current = operationIsCurrent(epoch, lease)
                if (!current || created == INVALID_HANDLE || recovery == null) {
                    NativeWalletBridge.destroy(created)
                    recovery?.fill('\u0000')
                    databaseKey.fill(0)
                    deleteWalletFiles()
                    if (current) {
                        statusView.text = getString(R.string.wallet_status_create_failed)
                    } else {
                        releaseStorageLeaseAfterOperation(lease)
                    }
                    return@runOnUiThread
                }
                walletHandle = created
                unconfirmedDatabaseKey = databaseKey
                recoveryView.showSecret(recovery)
                statusView.text = getString(R.string.wallet_status_recovery_required)
                accountView.text = getString(R.string.wallet_account_locked)
                resetReadProjection(R.string.wallet_reads_recovery_unconfirmed)
            }
        }
    }

    private fun restoreWallet() {
        val lease = currentStorageLease() ?: return
        if (!canStartNewWallet(lease)) return
        val phrase = takeRestoreInput() ?: run {
            Toast.makeText(this, R.string.wallet_restore_phrase_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_status_restoring))) {
            phrase.fill('\u0000')
            return
        }
        val epoch = lifecycleEpoch
        val path = walletDatabaseFile.absolutePath
        val network = walletNetworkCode(walletNetwork)
        val databaseKey = randomDatabaseKey()
        thread(name = "hns-wallet-restore") {
            val restored = NativeWalletBridge.restore(
                path,
                databaseKey.copyOf(),
                network,
                SAFE_FULL_RESCAN_BIRTHDAY,
                phrase,
            )
            runOnUiThread {
                busy = false
                val current = operationIsCurrent(epoch, lease)
                if (!current || restored == INVALID_HANDLE) {
                    NativeWalletBridge.destroy(restored)
                    databaseKey.fill(0)
                    deleteWalletFiles()
                    if (current) {
                        statusView.text = getString(R.string.wallet_status_restore_failed)
                        accountView.text = getString(R.string.wallet_account_unavailable)
                    } else {
                        releaseStorageLeaseAfterOperation(lease)
                    }
                    return@runOnUiThread
                }

                val stored = runCatching {
                    ProcessWalletStorageOwnership.commitIfCurrent(lease.owner, lease) {
                        keyStore.storeDatabaseKey(databaseKey)
                    }
                }.getOrDefault(false)
                databaseKey.fill(0)
                if (!stored) {
                    NativeWalletBridge.destroy(restored)
                    runCatching { keyStore.deleteDatabaseKey() }
                    deleteWalletFiles()
                    if (!operationIsCurrent(epoch, lease)) {
                        releaseStorageLeaseAfterOperation(lease)
                        return@runOnUiThread
                    }
                    statusView.text = getString(R.string.wallet_status_restore_failed)
                    accountView.text = getString(R.string.wallet_account_unavailable)
                    return@runOnUiThread
                }

                // Publication may have won immediately before a newer owner
                // arrived. In that case leave the durable wallet intact for
                // the newer owner and retire only this native controller.
                if (!operationIsCurrent(epoch, lease)) {
                    NativeWalletBridge.destroy(restored)
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                walletHandle = restored
                refreshControllerState()
            }
        }
    }

    private fun confirmRecoverySaved() {
        if (unconfirmedDatabaseKey == null) return
        if (!recoveryView.hasSecret()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_recovery_confirm_title)
            .setMessage(R.string.wallet_recovery_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_saved_wallet_recovery) { _, _ ->
                val databaseKey = unconfirmedDatabaseKey ?: return@setPositiveButton
                val lease = currentStorageLease()
                val stored = lease != null && runCatching {
                    ProcessWalletStorageOwnership.commitIfCurrent(lease.owner, lease) {
                        keyStore.storeDatabaseKey(databaseKey)
                    }
                }.getOrDefault(false)
                databaseKey.fill(0)
                unconfirmedDatabaseKey = null
                recoveryView.clearSecret()
                if (stored) {
                    if (currentStorageLease() === lease) {
                        refreshControllerState()
                    } else {
                        destroyController()
                        releaseStorageLease(lease)
                    }
                } else {
                    destroyController()
                    if (lease != null) {
                        runCatching { keyStore.deleteDatabaseKey() }
                        deleteWalletFiles()
                        if (!ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)) {
                            releaseStorageLease(lease)
                        }
                    }
                    statusView.text = getString(R.string.wallet_status_key_store_failed)
                    accountView.text = getString(R.string.wallet_account_unavailable)
                }
            }
            .show()
    }

    private fun unlockWallet() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || unconfirmedDatabaseKey != null) return
        if (!beginOperation(lease, getString(R.string.wallet_status_unlocking))) return
        val epoch = lifecycleEpoch
        thread(name = "hns-wallet-unlock") {
            val unlocked = runCatching {
                keyStore.withDatabaseKey { key ->
                    NativeWalletBridge.unlock(handle, key)
                } == true
            }.getOrDefault(false)
            runOnUiThread {
                busy = false
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (!unlocked) {
                    statusView.text = getString(R.string.wallet_status_unlock_failed)
                    accountView.text = getString(R.string.wallet_account_locked)
                } else {
                    refreshControllerState()
                }
            }
        }
    }

    private fun lockWallet() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (
            handle == INVALID_HANDLE ||
            !beginOperation(lease, getString(R.string.wallet_status_locking))
        ) return
        val epoch = lifecycleEpoch
        thread(name = "hns-wallet-lock") {
            val locked = NativeWalletBridge.lock(handle)
            runOnUiThread {
                busy = false
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (!locked) {
                    statusView.text = getString(R.string.wallet_status_lock_failed)
                }
                refreshControllerState()
            }
        }
    }

    private fun synchronizeWalletReads() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || unconfirmedDatabaseKey != null) {
            resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
            return
        }
        val status = NativeWalletBridge.status(handle)
        if (status == null || status.locked) {
            resetReadProjection(R.string.wallet_reads_locked)
            return
        }
        if (!NativeWalletBridge.hasHnsReads(handle)) {
            resetReadProjection(R.string.wallet_reads_unavailable)
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_status_syncing_reads))) return
        readStatusView.text = getString(R.string.wallet_reads_syncing)
        val epoch = lifecycleEpoch
        thread(name = "hns-wallet-read-sync") {
            val snapshot = NativeWalletBridge.synchronizeHnsReads(handle)
            runOnUiThread {
                busy = false
                val ownsLease = currentStorageLease() === lease
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = ownsLease,
                    expectedHandle = handle,
                    currentHandle = walletHandle,
                ) && operationIsCurrent(epoch, lease)
                if (!mayPublish) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                refreshControllerState()
                if (snapshot == null) {
                    resetReadProjection(R.string.wallet_reads_sync_failed)
                } else {
                    renderReadSnapshot(snapshot)
                }
            }
        }
    }

    private fun refreshControllerState() {
        val status = NativeWalletBridge.status(walletHandle)
        if (status == null) {
            statusView.text = getString(R.string.wallet_status_unavailable)
            accountView.text = getString(R.string.wallet_account_unavailable)
            resetReadProjection(R.string.wallet_reads_unavailable)
            return
        }
        if (status.locked) {
            statusView.text = getString(R.string.wallet_status_locked)
            accountView.text = getString(R.string.wallet_account_locked)
            resetReadProjection(R.string.wallet_reads_locked)
            return
        }
        statusView.text = getString(
            R.string.wallet_status_unlocked,
            status.activeWalletId ?: getString(R.string.common_unknown),
        )
        val account = NativeWalletBridge.account(walletHandle)
        accountView.text = if (account == null) {
            getString(R.string.wallet_account_unavailable)
        } else {
            getString(
                R.string.wallet_account_identity,
                account.label,
                account.module,
                account.accountId,
            )
        }
        if (NativeWalletBridge.hasHnsReads(walletHandle)) {
            resetReadProjection(R.string.wallet_reads_ready_to_sync)
        } else {
            resetReadProjection(R.string.wallet_reads_unavailable)
        }
    }

    private fun requestStorageLease(owner: WalletStorageOwnershipGate.Owner) {
        statusView.text = getString(R.string.wallet_status_starting)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        val accepted = ProcessWalletStorageOwnership.acquire(owner) { lease ->
            runOnUiThread {
                if (
                    !foreground || storageOwner !== owner || isFinishing || isDestroyed ||
                    !ProcessWalletStorageOwnership.isCurrent(owner, lease)
                ) {
                    ProcessWalletStorageOwnership.release(lease)
                    return@runOnUiThread
                }
                storageLease = lease
                startStorageSession(lease)
            }
        }
        if (!accepted && storageOwner === owner) {
            statusView.text = getString(R.string.wallet_status_unavailable)
        }
    }

    private fun startStorageSession(lease: WalletStorageOwnershipGate.Lease) {
        if (busy || currentStorageLease() !== lease) {
            releaseStorageLease(lease)
            return
        }
        val hasDatabaseKey = runCatching {
            prepareWalletDirectory()
            reconcileIncompleteStorage()
            keyStore.hasDatabaseKey()
        }.getOrNull()
        if (hasDatabaseKey == null) {
            statusView.text = getString(R.string.wallet_status_key_store_unavailable)
            accountView.text = getString(R.string.wallet_account_unavailable)
        } else if (!busy && walletHandle == INVALID_HANDLE && hasDatabaseKey) {
            // Reopening establishes the only controller state eligible for a
            // future product-owned scoped read credential. This screen never
            // sources an endpoint or credential from user-controlled input.
            openExistingWallet()
        } else if (!busy && walletHandle == INVALID_HANDLE) {
            showNoWallet()
        }
    }

    private fun revokeStorageOwnership(owner: WalletStorageOwnershipGate.Owner) {
        if (storageOwner !== owner) return
        lifecycleEpoch += 1
        storageOwner = null
        clearRestoreInput()
        recoveryView.clearSecret()
        val hadUnconfirmedWallet = unconfirmedDatabaseKey != null
        unconfirmedDatabaseKey?.fill(0)
        unconfirmedDatabaseKey = null
        val lease = storageLease
        val retirementStarted = lease != null && retireControllerAfterNativeOperation(lease)
        if (!retirementStarted) destroyController()
        if (hadUnconfirmedWallet && lease != null) deleteWalletFiles()
        if (!busy && lease != null) releaseStorageLeaseAfterOperation(lease)
        statusView.text = getString(R.string.wallet_status_unavailable)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_unavailable)
    }

    private fun currentStorageLease(): WalletStorageOwnershipGate.Lease? {
        val owner = storageOwner ?: return null
        val lease = storageLease ?: return null
        return lease.takeIf {
            foreground &&
                it.owner === owner &&
                ProcessWalletStorageOwnership.isCurrent(owner, it)
        }
    }

    private fun operationIsCurrent(
        epoch: Long,
        lease: WalletStorageOwnershipGate.Lease,
    ): Boolean =
        epoch == lifecycleEpoch &&
            !isFinishing &&
            !isDestroyed &&
            currentStorageLease() === lease

    private fun releaseStorageLease(lease: WalletStorageOwnershipGate.Lease) {
        if (storageLease === lease) storageLease = null
        ProcessWalletStorageOwnership.release(lease)
    }

    private fun releaseStorageLeaseAfterOperation(lease: WalletStorageOwnershipGate.Lease) {
        if (leaseReleaseHandoff.operationMayRelease(lease)) {
            releaseStorageLease(lease)
        }
    }

    /**
     * A bounded RPC may still own the native controller mutex when onStop or a
     * replacement Activity revokes this owner. Retire on a worker and retain
     * the storage lease until native destruction finishes; the stale operation
     * callback is explicitly denied release authority for the handed-off lease.
     */
    private fun retireControllerAfterNativeOperation(
        lease: WalletStorageOwnershipGate.Lease,
    ): Boolean {
        if (!busy) return false
        val handle = walletHandle
        if (handle == INVALID_HANDLE) return false
        check(leaseReleaseHandoff.handOffToRetirement(lease)) {
            "Wallet storage lease already handed to controller retirement"
        }
        walletHandle = INVALID_HANDLE
        thread(name = "hns-wallet-controller-retire") {
            NativeWalletBridge.destroy(handle)
            ProcessWalletStorageOwnership.release(lease)
            runOnUiThread {
                if (storageLease === lease) storageLease = null
            }
        }
        return true
    }

    private fun canStartNewWallet(lease: WalletStorageOwnershipGate.Lease): Boolean {
        val mayInspectStorage = walletSetupMayInspectStorage(
            foreground = foreground,
            ownsCurrentLease = currentStorageLease() === lease,
            busy = busy,
            hasController = walletHandle != INVALID_HANDLE,
            hasUnconfirmedKey = unconfirmedDatabaseKey != null,
        )
        if (!mayInspectStorage) return false

        val hasDatabaseKey = runCatching {
            reconcileIncompleteStorage()
            keyStore.hasDatabaseKey()
        }.getOrNull()
        if (hasDatabaseKey == null) {
            statusView.text = getString(R.string.wallet_status_key_store_unavailable)
            return false
        }
        if (hasDatabaseKey || walletDatabaseFile.exists()) {
            Toast.makeText(this, R.string.wallet_already_exists, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun beginOperation(
        lease: WalletStorageOwnershipGate.Lease,
        status: String,
    ): Boolean {
        if (busy || currentStorageLease() !== lease) return false
        busy = true
        statusView.text = status
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        return true
    }

    private fun showNoWallet() {
        statusView.text = if (NativeWalletBridge.isAvailable) {
            getString(R.string.wallet_status_not_created)
        } else {
            getString(R.string.wallet_status_native_unavailable)
        }
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
    }

    private fun walletReadSummary(text: Int): TextView = preferenceSummary(
        text = getString(text),
        maxLines = Int.MAX_VALUE,
    )

    private fun resetReadProjection(status: Int) {
        readStatusView.text = getString(status)
        balanceView.text = getString(R.string.wallet_reads_balance_unavailable)
        receiveView.text = getString(R.string.wallet_reads_receive_unavailable)
        historyView.text = getString(R.string.wallet_reads_history_unavailable)
        trackedNamesView.text = getString(R.string.wallet_reads_names_unavailable)
    }

    private fun renderReadSnapshot(snapshot: NativeWalletReadSnapshot) {
        readStatusView.text = getString(R.string.wallet_reads_ready, snapshot.height)
        balanceView.text = getString(
            R.string.wallet_reads_balance,
            formatHnsBaseUnits(snapshot.balanceBaseUnits),
        )
        receiveView.text = getString(
            R.string.wallet_reads_receive,
            snapshot.receiveAddress,
            snapshot.derivationIndex,
        )
        val visibleTransactions = snapshot.transactions.take(MAX_VISIBLE_READ_ITEMS)
        historyView.text = if (visibleTransactions.isEmpty()) {
            getString(R.string.wallet_reads_history_empty)
        } else {
            val entries = visibleTransactions.joinToString("\n\n") { transaction ->
                val chainPosition = if (transaction.blockHeight == null) {
                    getString(R.string.wallet_reads_transaction_unconfirmed)
                } else {
                    getString(
                        R.string.wallet_reads_transaction_confirmed,
                        transaction.blockHeight,
                        transaction.confirmationCount,
                    )
                }
                getString(
                    R.string.wallet_reads_transaction,
                    walletReadCodeLabel(transaction.status),
                    transaction.displayAmount(),
                    transaction.txid,
                    chainPosition,
                )
            }
            appendRemainingCount(entries, snapshot.transactions.size - visibleTransactions.size)
        }
        val visibleNames = snapshot.trackedNames.take(MAX_VISIBLE_READ_ITEMS)
        trackedNamesView.text = if (visibleNames.isEmpty()) {
            getString(R.string.wallet_reads_names_empty)
        } else {
            val entries = visibleNames.joinToString("\n\n") { name ->
                val state = listOfNotNull(
                    walletReadCodeLabel(name.ownershipStatus),
                    walletReadCodeLabel(name.resourceStatus),
                    name.registered?.let { registered ->
                        getString(
                            if (registered) R.string.wallet_reads_name_registered
                            else R.string.wallet_reads_name_not_registered,
                        )
                    },
                    name.expired?.let { expired ->
                        getString(
                            if (expired) R.string.wallet_reads_name_expired
                            else R.string.wallet_reads_name_current,
                        )
                    },
                ).joinToString(" · ")
                getString(
                    R.string.wallet_reads_name,
                    name.name,
                    name.proofHeight,
                    state,
                    name.nameHash,
                )
            }
            appendRemainingCount(entries, snapshot.trackedNames.size - visibleNames.size)
        }
    }

    private fun appendRemainingCount(entries: String, remaining: Int): String =
        if (remaining <= 0) entries else "$entries\n\n${getString(R.string.wallet_reads_more, remaining)}"

    private fun reconcileIncompleteStorage() {
        val database = walletDatabaseFile
        val storage = runCatching {
            keyStore.hasDatabaseKey() to keyStore.hasAnyDatabaseKeyMaterial()
        }.getOrNull() ?: return
        val (hasKey, hasKeyMaterial) = storage
        if (hasKey && database.exists()) return
        if (hasKeyMaterial || database.exists() || walletSidecars(database).any(File::exists)) {
            runCatching { keyStore.deleteDatabaseKey() }
            deleteWalletFiles()
        }
    }

    private fun destroyController() {
        val handle = walletHandle
        walletHandle = INVALID_HANDLE
        if (handle != INVALID_HANDLE) {
            NativeWalletBridge.lock(handle)
            NativeWalletBridge.destroy(handle)
        }
    }

    private fun prepareWalletDirectory() {
        val noBackupRoot = noBackupFilesDir.absoluteFile
        val directory = checkNotNull(walletDatabaseFile.parentFile)
        check(directory.parentFile == noBackupRoot) { "Wallet directory escaped no-backup storage" }
        hardenOwnerPrivateDirectory(noBackupRoot, create = false)
        hardenOwnerPrivateDirectory(directory, create = true)
    }

    private fun hardenOwnerPrivateDirectory(directory: File, create: Boolean) {
        if (!directory.exists()) {
            check(create && directory.mkdir()) { "Wallet directory could not be created" }
        }
        check(directory.isDirectory) { "Wallet directory is invalid" }
        check(directory.setReadable(false, false)) { "Wallet directory read mode could not be cleared" }
        check(directory.setWritable(false, false)) { "Wallet directory write mode could not be cleared" }
        check(directory.setExecutable(false, false)) { "Wallet directory execute mode could not be cleared" }
        check(directory.setReadable(true, true)) { "Wallet directory is not owner-readable" }
        check(directory.setWritable(true, true)) { "Wallet directory is not owner-writable" }
        check(directory.setExecutable(true, true)) { "Wallet directory is not owner-searchable" }
    }

    private fun deleteWalletFiles() {
        val database = walletDatabaseFile
        (listOf(database) + walletSidecars(database)).forEach { file ->
            if (file.exists()) file.delete()
        }
    }

    private fun walletSidecars(database: File): List<File> = listOf(
        File(database.path + "-wal"),
        File(database.path + "-shm"),
        File(database.path + "-journal"),
    )

    private fun walletNetworkCode(network: HandshakeNetwork): Int = when (network) {
        HandshakeNetwork.Mainnet -> NativeWalletBridge.NETWORK_MAINNET
        HandshakeNetwork.Testnet -> NativeWalletBridge.NETWORK_TESTNET
        HandshakeNetwork.Regtest -> NativeWalletBridge.NETWORK_REGTEST
    }

    private fun randomDatabaseKey(): ByteArray {
        val key = ByteArray(DATABASE_KEY_BYTES)
        val random = SecureRandom()
        do {
            random.nextBytes(key)
        } while (key.all { it == 0.toByte() })
        return key
    }

    private fun sensitiveRestoreInput(): EditText = EditText(this).apply {
        hint = getString(R.string.wallet_restore_phrase_hint)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(InputFilter.LengthFilter(MAX_RECOVERY_CHARACTERS))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setAutofillHints(null)
        isSaveEnabled = false
        freezesText = false
        isLongClickable = false
        setTextIsSelectable(false)
        setOnLongClickListener { true }
        customSelectionActionModeCallback = DisabledActionMode
        customInsertionActionModeCallback = DisabledActionMode
    }

    private fun takeRestoreInput(): CharArray? {
        val editable = restoreInput.text ?: return null
        if (editable.isEmpty() || editable.length > MAX_RECOVERY_CHARACTERS) {
            wipeEditable(editable)
            return null
        }
        val phrase = CharArray(editable.length) { index -> editable[index] }
        wipeEditable(editable)
        return phrase
    }

    private fun clearRestoreInput() {
        restoreInput.text?.let(::wipeEditable)
        restoreInput.clearFocus()
    }

    private fun wipeEditable(editable: Editable) {
        for (index in 0 until editable.length) {
            editable.replace(index, index + 1, NUL_CHARACTER)
        }
        editable.clear()
    }

    private object DisabledActionMode : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode?) = Unit
    }

    private companion object {
        const val INVALID_HANDLE = 0L
        const val DATABASE_KEY_BYTES = 32
        const val MAX_RECOVERY_CHARACTERS = 256
        const val SAFE_FULL_RESCAN_BIRTHDAY = 0L
        const val MAX_VISIBLE_READ_ITEMS = 20
        const val WALLET_DATABASE = "wallet.sqlite3"
        const val NUL_CHARACTER = "\u0000"
    }
}

/** Draws recovery characters without converting them to an immutable String or enabling copy. */
private class RecoveryPhraseView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColors().primaryText
        typeface = Typeface.MONOSPACE
        textSize = context.uiDp(17).toFloat()
    }
    private var secret: CharArray? = null

    init {
        visibility = GONE
        setPadding(context.uiDp(12), context.uiDp(12), context.uiDp(12), context.uiDp(12))
        setBackgroundColor(context.themeColors().surface)
        isLongClickable = false
        isClickable = false
        isFocusable = false
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun showSecret(value: CharArray) {
        clearSecret()
        secret = value
        visibility = VISIBLE
        requestLayout()
        invalidate()
    }

    fun hasSecret(): Boolean = secret?.isNotEmpty() == true

    fun clearSecret() {
        secret?.fill('\u0000')
        secret = null
        visibility = GONE
        invalidate()
    }

    override fun onDetachedFromWindow() {
        clearSecret()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val lines = lineCount(secret, available.toFloat()).coerceAtLeast(1)
        val desiredHeight = paddingTop + paddingBottom + ceil(paint.fontSpacing * lines).toInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = secret ?: return
        val right = (width - paddingRight).toFloat()
        val space = paint.measureText(" ")
        var x = paddingLeft.toFloat()
        var baseline = paddingTop - paint.fontMetrics.top
        forEachWord(value) { start, length ->
            val wordWidth = paint.measureText(value, start, length)
            if (x > paddingLeft.toFloat() && x + wordWidth > right) {
                x = paddingLeft.toFloat()
                baseline += paint.fontSpacing
            }
            canvas.drawText(value, start, length, x, baseline, paint)
            x += wordWidth + space
        }
    }

    private fun lineCount(value: CharArray?, available: Float): Int {
        value ?: return 0
        val space = paint.measureText(" ")
        var lines = 1
        var x = 0f
        forEachWord(value) { start, length ->
            val wordWidth = paint.measureText(value, start, length)
            if (x > 0f && x + wordWidth > available) {
                lines += 1
                x = 0f
            }
            x += wordWidth + space
        }
        return lines
    }

    private inline fun forEachWord(value: CharArray, block: (Int, Int) -> Unit) {
        var start = 0
        while (start < value.size) {
            while (start < value.size && value[start].isWhitespace()) start += 1
            if (start >= value.size) break
            var end = start
            while (end < value.size && !value[end].isWhitespace()) end += 1
            block(start, end - start)
            start = end
        }
    }
}
