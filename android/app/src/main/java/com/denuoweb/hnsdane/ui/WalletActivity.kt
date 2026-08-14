package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.app.KeyguardManager
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
import com.denuoweb.hnsdane.wallet.NativeWalletName
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.ProcessWalletControllerRetirementFailures
import com.denuoweb.hnsdane.wallet.ProcessWalletStorageOwnership
import com.denuoweb.hnsdane.wallet.WALLET_DATABASE_FILE_NAME
import com.denuoweb.hnsdane.wallet.WALLET_DELETE_CONFIRMATION
import com.denuoweb.hnsdane.wallet.WalletDeletionScope
import com.denuoweb.hnsdane.wallet.WalletLeaseReleaseHandoff
import com.denuoweb.hnsdane.wallet.WalletNameImportState
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapAuthority
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapSource
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapState
import com.denuoweb.hnsdane.wallet.WalletStorageDeletionResult
import com.denuoweb.hnsdane.wallet.WalletStorageOwnershipGate
import com.denuoweb.hnsdane.wallet.UnavailableWalletReadBootstrapSource
import com.denuoweb.hnsdane.wallet.attemptWalletReadBootstrap
import com.denuoweb.hnsdane.wallet.closeWalletControllerForDeletion
import com.denuoweb.hnsdane.wallet.deleteConfirmedWalletStorage
import com.denuoweb.hnsdane.wallet.deleteWalletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.exactWalletNameUtf8
import com.denuoweb.hnsdane.wallet.walletNameImportRefreshMatches
import com.denuoweb.hnsdane.wallet.displayAmount
import com.denuoweb.hnsdane.wallet.formatHnsBaseUnits
import com.denuoweb.hnsdane.wallet.walletDeleteConfirmationMatches
import com.denuoweb.hnsdane.wallet.walletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.walletControllerOperationMayBegin
import com.denuoweb.hnsdane.wallet.walletDeletionMayProceed
import com.denuoweb.hnsdane.wallet.walletNameImportMayBegin
import com.denuoweb.hnsdane.wallet.walletNameImportMayPublish
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
    private lateinit var paymentReceiveView: TextView
    private lateinit var nameReceiveView: TextView
    private lateinit var historyView: TextView
    private lateinit var trackedNamesView: TextView
    private lateinit var nameImportInput: EditText
    private lateinit var nameImportStatusView: TextView
    private lateinit var restoreInput: EditText
    private lateinit var recoveryView: RecoveryPhraseView

    private var walletHandle = INVALID_HANDLE
    private var walletAuthorityGeneration = 0L
    private var walletControllerIsReopenedDurable = false
    private var busy = false
    private var lifecycleEpoch = 0L
    private var foreground = false
    private var unconfirmedDatabaseKey: ByteArray? = null
    private var storageOwner: WalletStorageOwnershipGate.Owner? = null
    private var storageLease: WalletStorageOwnershipGate.Lease? = null
    private var walletDeletionDialog: AlertDialog? = null
    private val leaseReleaseHandoff = WalletLeaseReleaseHandoff()
    private val walletReadBootstrapSource: WalletReadBootstrapSource =
        UnavailableWalletReadBootstrapSource

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.clear()
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        walletNetwork = HnsResolutionPreferences.handshakeNetwork(this)
        val storageNamespace = walletStorageNamespace(walletNetwork.id)
        walletDatabaseFile = File(
            File(noBackupFilesDir, storageNamespace.directoryName),
            WALLET_DATABASE_FILE_NAME,
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
        paymentReceiveView = walletReadSummary(R.string.wallet_reads_receive_unavailable)
        nameReceiveView = walletReadSummary(R.string.wallet_reads_name_receive_unavailable)
        historyView = walletReadSummary(R.string.wallet_reads_history_unavailable)
        trackedNamesView = walletReadSummary(R.string.wallet_reads_names_unavailable)
        nameImportInput = exactNameImportInput()
        nameImportStatusView = walletReadSummary(R.string.wallet_name_import_unavailable)
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
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_delete),
                    summary = getString(R.string.row_wallet_delete_summary),
                    actionLabel = getString(R.string.action_delete),
                    destructive = true,
                ) {
                    requestWalletDeletion()
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
                    summaryView = paymentReceiveView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_name_receive),
                    summaryView = nameReceiveView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_history),
                    summaryView = historyView,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_read_names),
                    summaryView = trackedNamesView,
                ))
                addView(nameImportInput, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_wallet_name_import),
                    summaryView = nameImportStatusView,
                    actionLabel = getString(R.string.action_import_wallet_name),
                ) {
                    importWalletName()
                })
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
        dismissWalletDeletionDialog()
        clearRestoreInput()
        clearNameImportInput()
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
                    destroyWalletController(opened)
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (opened == INVALID_HANDLE) {
                    statusView.text = getString(R.string.wallet_status_open_failed)
                    accountView.text = getString(R.string.wallet_account_unavailable)
                } else {
                    publishWalletController(opened, reopenedDurable = true)
                    attemptReadBootstrap(lease)
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
                    val controllerClosed = destroyWalletController(created)
                    recovery?.fill('\u0000')
                    databaseKey.fill(0)
                    if (controllerClosed) deleteWalletFiles()
                    if (current) {
                        if (controllerClosed) {
                            statusView.text = getString(R.string.wallet_status_create_failed)
                        } else {
                            showControllerRetirementUncertain()
                        }
                    } else {
                        releaseStorageLeaseAfterOperation(lease)
                    }
                    return@runOnUiThread
                }
                publishWalletController(created, reopenedDurable = false)
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
                    val controllerClosed = destroyWalletController(restored)
                    databaseKey.fill(0)
                    if (controllerClosed) deleteWalletFiles()
                    if (current) {
                        if (controllerClosed) {
                            statusView.text = getString(R.string.wallet_status_restore_failed)
                            accountView.text = getString(R.string.wallet_account_unavailable)
                        } else {
                            showControllerRetirementUncertain()
                        }
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
                    val controllerClosed = destroyWalletController(restored)
                    if (
                        controllerClosed &&
                        runCatching { keyStore.deleteDatabaseKey() }.isSuccess
                    ) {
                        deleteWalletFiles()
                    }
                    if (!operationIsCurrent(epoch, lease)) {
                        releaseStorageLeaseAfterOperation(lease)
                        return@runOnUiThread
                    }
                    if (controllerClosed) {
                        statusView.text = getString(R.string.wallet_status_restore_failed)
                        accountView.text = getString(R.string.wallet_account_unavailable)
                    } else {
                        showControllerRetirementUncertain()
                    }
                    return@runOnUiThread
                }

                // Publication may have won immediately before a newer owner
                // arrived. In that case leave the durable wallet intact for
                // the newer owner and retire only this native controller.
                if (!operationIsCurrent(epoch, lease)) {
                    destroyWalletController(restored)
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                publishWalletController(restored, reopenedDurable = false)
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
                    val controllerClosed = destroyController()
                    if (lease != null) {
                        if (
                            controllerClosed &&
                            runCatching { keyStore.deleteDatabaseKey() }.isSuccess
                        ) {
                            deleteWalletFiles()
                        }
                        if (!ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)) {
                            releaseStorageLease(lease)
                        }
                    }
                    if (controllerClosed) {
                        statusView.text = getString(R.string.wallet_status_key_store_failed)
                        accountView.text = getString(R.string.wallet_account_unavailable)
                    } else {
                        showControllerRetirementUncertain()
                    }
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

    private fun requestWalletDeletion() {
        val captured = captureWalletDeletionScope()
        if (captured == null) {
            Toast.makeText(
                this,
                R.string.wallet_delete_requires_unlocked_confirmed_wallet,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val (scope, lease) = captured
        dismissWalletDeletionDialog()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_delete_first_title)
            .setMessage(walletDeletionWarning(R.string.wallet_delete_first_message, scope))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue_wallet_deletion) { _, _ ->
                if (walletDeletionScopeIsCurrent(scope, lease)) {
                    showTypedWalletDeletionConfirmation(scope, lease)
                } else {
                    Toast.makeText(
                        this,
                        R.string.wallet_delete_context_changed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .create()
        walletDeletionDialog = dialog
        dialog.setOnDismissListener {
            if (walletDeletionDialog === dialog) walletDeletionDialog = null
        }
        dialog.show()
    }

    private fun showTypedWalletDeletionConfirmation(
        scope: WalletDeletionScope,
        lease: WalletStorageOwnershipGate.Lease,
    ) {
        val confirmation = EditText(this).apply {
            hint = WALLET_DELETE_CONFIRMATION
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(WALLET_DELETE_CONFIRMATION.length))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setAutofillHints(null)
            isSaveEnabled = false
            freezesText = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_delete_typed_title)
            .setMessage(walletDeletionWarning(R.string.wallet_delete_typed_message, scope))
            .setView(confirmation)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete, null)
            .create()
        walletDeletionDialog = dialog
        dialog.setOnDismissListener {
            confirmation.text?.clear()
            if (walletDeletionDialog === dialog) walletDeletionDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!walletDeleteConfirmationMatches(confirmation.text)) {
                    confirmation.error = getString(R.string.wallet_delete_type_delete_error)
                    return@setOnClickListener
                }
                if (!walletDeletionScopeIsCurrent(scope, lease)) {
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        R.string.wallet_delete_context_changed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                confirmation.text?.clear()
                dialog.dismiss()
                deleteConfirmedWallet(scope, lease)
            }
        }
        dialog.show()
    }

    private fun deleteConfirmedWallet(
        scope: WalletDeletionScope,
        lease: WalletStorageOwnershipGate.Lease,
    ) {
        if (!walletDeletionScopeIsCurrent(scope, lease)) return
        val epoch = lifecycleEpoch
        val handle = walletHandle

        // Withdraw every Activity-owned read and display capability before native or storage
        // destruction starts. The captured handle remains worker-local only.
        busy = true
        detachWalletController()
        clearRestoreInput()
        clearNameImportInput()
        recoveryView.clearSecret()
        statusView.text = getString(R.string.wallet_status_deleting)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_unavailable)

        thread(name = "hns-wallet-delete") {
            val controllerClosed = closeWalletControllerForDeletion(
                lock = { NativeWalletBridge.lock(handle) },
                close = { destroyWalletController(handle) },
            )
            val result = if (!controllerClosed) {
                WalletDeletionOperationResult.ControllerCloseFailed
            } else {
                var storageResult: WalletStorageDeletionResult? = null
                val committed = ProcessWalletStorageOwnership.commitIfCurrent(
                    lease.owner,
                    lease,
                ) {
                    storageResult = deleteConfirmedWalletStorage(
                        requestDeletion = keyStore::requestConfirmedWalletDeletion,
                        deleteDatabaseKey = keyStore::deleteDatabaseKeyForConfirmedWalletDeletion,
                        deleteDatabaseFiles = ::deleteWalletFiles,
                        finishDeletion = keyStore::finishConfirmedWalletDeletion,
                    )
                }
                if (!committed) {
                    WalletDeletionOperationResult.OwnershipRevoked
                } else {
                    when (storageResult) {
                        WalletStorageDeletionResult.Deleted ->
                            WalletDeletionOperationResult.Deleted
                        WalletStorageDeletionResult.FileCleanupPending ->
                            WalletDeletionOperationResult.FileCleanupPending
                        WalletStorageDeletionResult.KeyDeletionFailed, null ->
                            WalletDeletionOperationResult.KeyDeletionFailed
                    }
                }
            }
            runOnUiThread {
                busy = false
                if (!operationIsCurrent(epoch, lease)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                when (result) {
                    WalletDeletionOperationResult.Deleted -> {
                        showNoWallet()
                        Toast.makeText(
                            this,
                            R.string.wallet_delete_complete,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    WalletDeletionOperationResult.FileCleanupPending -> {
                        statusView.text = getString(R.string.wallet_status_delete_cleanup_pending)
                    }
                    WalletDeletionOperationResult.KeyDeletionFailed -> {
                        statusView.text = getString(R.string.wallet_status_delete_key_failed)
                    }
                    WalletDeletionOperationResult.ControllerCloseFailed -> {
                        statusView.text = getString(R.string.wallet_status_delete_close_failed)
                    }
                    WalletDeletionOperationResult.OwnershipRevoked -> Unit
                }
            }
        }
    }

    private fun captureWalletDeletionScope(): Pair<WalletDeletionScope, WalletStorageOwnershipGate.Lease>? {
        val lease = currentStorageLease() ?: return null
        if (busy || unconfirmedDatabaseKey != null || walletHandle == INVALID_HANDLE) return null
        val status = NativeWalletBridge.status(walletHandle) ?: return null
        if (status.locked) return null
        val account = NativeWalletBridge.account(walletHandle) ?: return null
        val scope = walletDeletionScope(lease, account.accountId)
        return (scope to lease).takeIf { walletDeletionScopeIsCurrent(scope, lease) }
    }

    private fun walletDeletionScopeIsCurrent(
        expected: WalletDeletionScope,
        lease: WalletStorageOwnershipGate.Lease,
    ): Boolean {
        if (ProcessWalletControllerRetirementFailures.blocks(walletStoragePath)) return false
        if (currentStorageLease() !== lease || lease.path != walletStoragePath) return false
        val handle = walletHandle
        val status = NativeWalletBridge.status(handle) ?: return false
        if (status.locked) return false
        val account = NativeWalletBridge.account(handle) ?: return false
        val current = walletDeletionScope(lease, account.accountId)
        val confirmedPersistentWallet = runCatching {
            keyStore.hasDatabaseKey() &&
                !keyStore.walletDeletionPending() &&
                walletDatabaseFile.exists()
        }.getOrDefault(false)
        return walletDeletionMayProceed(
            expected = expected,
            current = current,
            foreground = foreground && !isFinishing && !isDestroyed,
            busy = busy,
            confirmedPersistentWallet = confirmedPersistentWallet,
            hasUnconfirmedKey = unconfirmedDatabaseKey != null,
        )
    }

    private fun walletDeletionScope(
        lease: WalletStorageOwnershipGate.Lease,
        accountId: String,
    ): WalletDeletionScope = WalletDeletionScope(
        lifecycleEpoch = lifecycleEpoch,
        ownerGeneration = lease.owner.generation,
        leaseGeneration = lease.generation,
        storagePath = walletStoragePath,
        networkId = walletNetwork.id,
        walletHandle = walletHandle,
        accountId = accountId,
    )

    private fun walletDeletionWarning(
        message: Int,
        scope: WalletDeletionScope,
    ): String = getString(
        message,
        walletNetwork.displayName(this),
        scope.networkId,
        scope.accountId,
    )

    private fun dismissWalletDeletionDialog() {
        walletDeletionDialog?.dismiss()
        walletDeletionDialog = null
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
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-read-sync") {
            val snapshot = NativeWalletBridge.synchronizeHnsReads(handle)
            runOnUiThread {
                val ownsLease = currentStorageLease() === lease
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = ownsLease,
                    expectedHandle = handle,
                    currentHandle = walletHandle,
                    expectedAuthorityGeneration = authorityGeneration,
                    currentAuthorityGeneration = walletAuthorityGeneration,
                ) && operationIsCurrent(epoch, lease)
                if (!mayPublish) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                refreshControllerState()
                if (snapshot == null) {
                    resetReadProjection(R.string.wallet_reads_sync_failed)
                } else {
                    renderReadSnapshot(snapshot)
                }
            }
        }
    }

    private fun importWalletName() {
        val lease = currentStorageLease() ?: return
        val initial = walletNameImportState(lease)
        val expected = initial.readState.authority
        if (expected == null || !walletNameImportMayBegin(expected, initial)) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_unavailable)
            return
        }
        val editable = nameImportInput.text
        val exactUtf8 = editable?.let(::exactWalletNameUtf8)
        if (exactUtf8 == null) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_invalid)
            return
        }
        if (
            !walletNameImportMayBegin(expected, walletNameImportState(lease)) ||
            !beginOperation(
                lease,
                getString(R.string.wallet_status_importing_name),
                resetReads = false,
            )
        ) {
            exactUtf8.fill(0)
            return
        }
        editable?.let(::wipeEditable)
        nameImportStatusView.text = getString(R.string.wallet_name_import_importing)
        val epoch = lifecycleEpoch
        val handle = expected.walletHandle
        thread(name = "hns-wallet-name-import") {
            val imported = NativeWalletBridge.importHnsNameExactText(handle, exactUtf8)
            val snapshot = if (imported != null) {
                NativeWalletBridge.synchronizeHnsReads(handle)?.takeIf { refreshed ->
                    walletNameImportRefreshMatches(imported, refreshed)
                }
            } else {
                null
            }
            if (imported != null && snapshot == null) {
                // The import may already be durable. A missing or mismatched
                // refresh poisons the session instead of publishing ambiguity.
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                val current = walletNameImportState(lease)
                val mayPublish = walletNameImportMayPublish(
                    expected = expected,
                    current = current,
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                ) && operationIsCurrent(epoch, lease)
                busy = false
                if (!mayPublish) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                when {
                    imported == null -> {
                        refreshControllerState()
                        nameImportStatusView.text =
                            getString(R.string.wallet_name_import_failed)
                    }
                    snapshot == null -> {
                        refreshControllerState()
                        resetReadProjection(R.string.wallet_reads_sync_failed)
                        nameImportStatusView.text =
                            getString(R.string.wallet_name_import_success_refresh_failed)
                    }
                    else -> {
                        refreshControllerState(resetReads = false)
                        renderReadSnapshot(snapshot)
                        renderImportedName(imported)
                    }
                }
            }
        }
    }

    private fun refreshControllerState(resetReads: Boolean = true) {
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
        if (resetReads) {
            if (NativeWalletBridge.hasHnsReads(walletHandle)) {
                resetReadProjection(R.string.wallet_reads_ready_to_sync)
            } else {
                resetReadProjection(R.string.wallet_reads_unavailable)
            }
        }
    }

    /**
     * The shipping source is unavailable, so this currently performs no native
     * composition. Keeping admission in the real reopen lifecycle makes a
     * future brokered credential unable to bypass storage/controller authority.
     */
    private fun attemptReadBootstrap(lease: WalletStorageOwnershipGate.Lease) {
        val expectedAuthority = walletReadBootstrapState(lease).authority ?: return
        attemptWalletReadBootstrap(
            expectedAuthority = expectedAuthority,
            source = walletReadBootstrapSource,
            currentState = { walletReadBootstrapState(lease) },
            install = NativeWalletBridge::configureHnsReads,
        )
    }

    private fun walletReadBootstrapState(
        lease: WalletStorageOwnershipGate.Lease,
    ): WalletReadBootstrapState {
        val ownsExactLease = currentStorageLease() === lease
        val canonicalPath = runCatching { walletDatabaseFile.canonicalPath }.getOrNull()
        val authority = if (
            ownsExactLease && canonicalPath != null && walletHandle != INVALID_HANDLE
        ) {
            WalletReadBootstrapAuthority.create(
                networkId = walletNetwork.id,
                databasePath = canonicalPath,
                storageLease = lease,
                walletHandle = walletHandle,
                authorityGeneration = walletAuthorityGeneration,
            )
        } else {
            null
        }
        val confirmedPersistentWallet = runCatching {
            keyStore.hasDatabaseKey() &&
                !keyStore.walletDeletionPending() &&
                walletDatabaseFile.exists()
        }.getOrDefault(false)
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return WalletReadBootstrapState(
            authority = authority,
            foreground = foreground && !isFinishing && !isDestroyed,
            protectedStorageAvailable = keyguard?.isDeviceLocked == false,
            reopenedDurableWallet = walletControllerIsReopenedDurable,
            confirmedPersistentWallet = confirmedPersistentWallet,
            hasUnconfirmedRecovery = unconfirmedDatabaseKey != null || recoveryView.hasSecret(),
            operationInFlight = busy,
            retirementBlocked =
                ProcessWalletControllerRetirementFailures.blocks(walletStoragePath),
        )
    }

    private fun walletNameImportState(
        lease: WalletStorageOwnershipGate.Lease,
    ): WalletNameImportState {
        val readState = walletReadBootstrapState(lease)
        val handle = readState.authority?.walletHandle ?: INVALID_HANDLE
        val status = NativeWalletBridge.status(handle)
        return WalletNameImportState(
            readState = readState,
            unlocked = status != null && !status.locked && status.activeWalletId != null,
            hnsReadsConfigured = NativeWalletBridge.hasHnsReads(handle),
        )
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
        if (ProcessWalletControllerRetirementFailures.blocks(walletStoragePath)) {
            showControllerRetirementUncertain()
            return
        }
        val storage = runCatching {
            prepareWalletDirectory()
            reconcileIncompleteStorage() to keyStore.hasDatabaseKey()
        }.getOrNull()
        if (storage == null) {
            statusView.text = getString(R.string.wallet_status_key_store_unavailable)
            accountView.text = getString(R.string.wallet_account_unavailable)
        } else if (!storage.first) {
            statusView.text = getString(R.string.wallet_status_delete_cleanup_pending)
            accountView.text = getString(R.string.wallet_account_unavailable)
            resetReadProjection(R.string.wallet_reads_unavailable)
        } else if (!busy && walletHandle == INVALID_HANDLE && storage.second) {
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
        dismissWalletDeletionDialog()
        clearRestoreInput()
        clearNameImportInput()
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

    private fun publishWalletController(handle: Long, reopenedDurable: Boolean) {
        check(handle != INVALID_HANDLE) { "Cannot publish an invalid wallet controller" }
        check(walletHandle == INVALID_HANDLE) { "Wallet controller authority is already present" }
        advanceWalletAuthorityGeneration()
        walletHandle = handle
        walletControllerIsReopenedDurable = reopenedDurable
    }

    private fun detachWalletController(): Long {
        val handle = walletHandle
        walletHandle = INVALID_HANDLE
        walletControllerIsReopenedDurable = false
        if (handle != INVALID_HANDLE) advanceWalletAuthorityGeneration()
        return handle
    }

    private fun advanceWalletAuthorityGeneration() {
        check(walletAuthorityGeneration < Long.MAX_VALUE) {
            "Wallet controller authority generation exhausted"
        }
        walletAuthorityGeneration += 1L
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
        detachWalletController()
        thread(name = "hns-wallet-controller-retire") {
            destroyWalletController(handle)
            ProcessWalletStorageOwnership.release(lease)
            runOnUiThread {
                if (storageLease === lease) storageLease = null
            }
        }
        return true
    }

    private fun canStartNewWallet(lease: WalletStorageOwnershipGate.Lease): Boolean {
        if (ProcessWalletControllerRetirementFailures.blocks(walletStoragePath)) {
            showControllerRetirementUncertain()
            return false
        }
        val mayInspectStorage = walletSetupMayInspectStorage(
            foreground = foreground,
            ownsCurrentLease = currentStorageLease() === lease,
            busy = busy,
            hasController = walletHandle != INVALID_HANDLE,
            hasUnconfirmedKey = unconfirmedDatabaseKey != null,
        )
        if (!mayInspectStorage) return false

        val storage = runCatching {
            reconcileIncompleteStorage() to keyStore.hasDatabaseKey()
        }.getOrNull()
        if (storage == null) {
            statusView.text = getString(R.string.wallet_status_key_store_unavailable)
            return false
        }
        if (!storage.first) {
            statusView.text = getString(R.string.wallet_status_delete_cleanup_pending)
            return false
        }
        if (storage.second || walletDatabaseFile.exists()) {
            Toast.makeText(this, R.string.wallet_already_exists, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun beginOperation(
        lease: WalletStorageOwnershipGate.Lease,
        status: String,
        resetReads: Boolean = true,
    ): Boolean {
        val retirementFailed =
            ProcessWalletControllerRetirementFailures.blocks(walletStoragePath)
        if (
            !walletControllerOperationMayBegin(
                retirementFailed = retirementFailed,
                busy = busy,
                ownsCurrentLease = currentStorageLease() === lease,
            )
        ) {
            if (retirementFailed) showControllerRetirementUncertain()
            return false
        }
        busy = true
        statusView.text = status
        if (resetReads) resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
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

    private fun showControllerRetirementUncertain() {
        statusView.text = getString(R.string.wallet_status_controller_retirement_uncertain)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_unavailable)
    }

    private fun walletReadSummary(text: Int): TextView = preferenceSummary(
        text = getString(text),
        maxLines = Int.MAX_VALUE,
    )

    private fun resetReadProjection(status: Int) {
        readStatusView.text = getString(status)
        balanceView.text = getString(R.string.wallet_reads_balance_unavailable)
        paymentReceiveView.text = getString(R.string.wallet_reads_receive_unavailable)
        nameReceiveView.text = getString(R.string.wallet_reads_name_receive_unavailable)
        historyView.text = getString(R.string.wallet_reads_history_unavailable)
        trackedNamesView.text = getString(R.string.wallet_reads_names_unavailable)
        nameImportStatusView.text = when (status) {
            R.string.wallet_reads_locked -> getString(R.string.wallet_name_import_locked)
            R.string.wallet_reads_ready_to_sync -> getString(R.string.wallet_name_import_ready)
            R.string.wallet_reads_recovery_unconfirmed ->
                getString(R.string.wallet_name_import_recovery_unconfirmed)
            R.string.wallet_reads_waiting_for_wallet ->
                getString(R.string.wallet_name_import_waiting_for_wallet)
            else -> getString(R.string.wallet_name_import_unavailable)
        }
    }

    private fun renderReadSnapshot(snapshot: NativeWalletReadSnapshot) {
        readStatusView.text = getString(R.string.wallet_reads_ready, snapshot.height)
        balanceView.text = getString(
            R.string.wallet_reads_balance,
            formatHnsBaseUnits(snapshot.balanceBaseUnits),
        )
        paymentReceiveView.text = getString(
            R.string.wallet_reads_receive,
            snapshot.paymentReceiveTarget.display,
            snapshot.paymentReceiveTarget.derivationIndex,
        )
        nameReceiveView.text = snapshot.nameReceiveTarget?.let { target ->
            getString(
                R.string.wallet_reads_name_receive,
                target.display,
                target.derivationIndex,
            )
        } ?: getString(R.string.wallet_reads_name_receive_legacy_unavailable)
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
            val entries = visibleNames.joinToString("\n\n", transform = ::walletNameSummary)
            appendRemainingCount(entries, snapshot.trackedNames.size - visibleNames.size)
        }
    }

    private fun renderImportedName(name: NativeWalletName) {
        nameImportStatusView.text = getString(
            R.string.wallet_name_import_success,
            walletNameSummary(name),
        )
    }

    private fun walletNameSummary(name: NativeWalletName): String {
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
        return getString(
            R.string.wallet_reads_name,
            name.name,
            name.proofHeight,
            state,
            name.nameHash,
        )
    }

    private fun appendRemainingCount(entries: String, remaining: Int): String =
        if (remaining <= 0) entries else "$entries\n\n${getString(R.string.wallet_reads_more, remaining)}"

    private fun reconcileIncompleteStorage(): Boolean {
        if (ProcessWalletControllerRetirementFailures.blocks(walletStoragePath)) return false
        val database = walletDatabaseFile
        val storage = runCatching {
            Triple(
                keyStore.hasDatabaseKey(),
                keyStore.hasAnyDatabaseKeyMaterial(),
                keyStore.walletDeletionPending(),
            )
        }.getOrNull() ?: return false
        val (hasKey, hasKeyMaterial, deletionPending) = storage
        if (deletionPending) {
            return deleteConfirmedWalletStorage(
                requestDeletion = keyStore::requestConfirmedWalletDeletion,
                deleteDatabaseKey = keyStore::deleteDatabaseKeyForConfirmedWalletDeletion,
                deleteDatabaseFiles = ::deleteWalletFiles,
                finishDeletion = keyStore::finishConfirmedWalletDeletion,
            ) == WalletStorageDeletionResult.Deleted
        }
        if (hasKey && database.exists()) return true
        if (hasKeyMaterial || walletDatabaseArtifacts(database).any(File::exists)) {
            if (runCatching { keyStore.deleteDatabaseKey() }.isFailure) return false
            return deleteWalletFiles()
        }
        return true
    }

    private fun destroyController(): Boolean {
        val handle = detachWalletController()
        if (handle == INVALID_HANDLE) return true
        NativeWalletBridge.lock(handle)
        return destroyWalletController(handle)
    }

    private fun destroyWalletController(handle: Long): Boolean {
        if (handle == INVALID_HANDLE) return true
        val destroyed = NativeWalletBridge.destroy(handle)
        if (!destroyed) {
            ProcessWalletControllerRetirementFailures.mark(walletStoragePath)
        }
        return destroyed
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

    private fun deleteWalletFiles(): Boolean =
        !ProcessWalletControllerRetirementFailures.blocks(walletStoragePath) &&
            deleteWalletDatabaseArtifacts(walletDatabaseFile)

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

    private fun exactNameImportInput(): EditText = EditText(this).apply {
        hint = getString(R.string.wallet_name_import_hint)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_DONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // Reject overlong exact text on submission. A LengthFilter would
        // silently turn pasted or typed text into a different candidate.
        filters = emptyArray()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setAutofillHints(null)
        setSingleLine(true)
        isSaveEnabled = false
        freezesText = false
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

    private fun clearNameImportInput() {
        nameImportInput.text?.let(::wipeEditable)
        nameImportInput.clearFocus()
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
        const val NUL_CHARACTER = "\u0000"
    }
}

private enum class WalletDeletionOperationResult {
    ControllerCloseFailed,
    OwnershipRevoked,
    KeyDeletionFailed,
    FileCleanupPending,
    Deleted,
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
