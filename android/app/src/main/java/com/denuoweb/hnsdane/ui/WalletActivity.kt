package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.HeaderSnapshotInstaller
import com.denuoweb.hnsdane.wallet.AndroidWalletKeyStore
import com.denuoweb.hnsdane.wallet.DirectHnsSynchronizationJournalStart
import com.denuoweb.hnsdane.wallet.NativeHnsSendApproval
import com.denuoweb.hnsdane.wallet.NativeHnsValueApproval
import com.denuoweb.hnsdane.wallet.NativeHnsValueApprovalKind
import com.denuoweb.hnsdane.wallet.NativeHnsValueIntent
import com.denuoweb.hnsdane.wallet.NativeBitcoinSendApproval
import com.denuoweb.hnsdane.wallet.NativeShakedexQuery
import com.denuoweb.hnsdane.wallet.NativeWalletDirectDenuoConnectResult
import com.denuoweb.hnsdane.wallet.NativeWalletBridge
import com.denuoweb.hnsdane.wallet.NativeWalletHnsCatchupProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsLiveSyncProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsSynchronization
import com.denuoweb.hnsdane.wallet.NativeWalletName
import com.denuoweb.hnsdane.wallet.NativeWalletPaymentReceiveTarget
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.ProcessWalletControllerRetirementFailures
import com.denuoweb.hnsdane.wallet.ProcessWalletStorageOwnership
import com.denuoweb.hnsdane.wallet.WALLET_DATABASE_FILE_NAME
import com.denuoweb.hnsdane.wallet.WALLET_DELETE_CONFIRMATION
import com.denuoweb.hnsdane.wallet.WalletDeletionScope
import com.denuoweb.hnsdane.wallet.WalletLeaseReleaseHandoff
import com.denuoweb.hnsdane.wallet.WalletHnsJourney
import com.denuoweb.hnsdane.wallet.WalletHnsLiveSyncPresentation
import com.denuoweb.hnsdane.wallet.WalletHnsLiveSyncPresentationCache
import com.denuoweb.hnsdane.wallet.WalletNameImportState
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapAuthority
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapState
import com.denuoweb.hnsdane.wallet.WalletStorageDeletionResult
import com.denuoweb.hnsdane.wallet.WalletStorageOwnershipGate
import com.denuoweb.hnsdane.wallet.beginDirectHnsSynchronizationWithRecovery
import com.denuoweb.hnsdane.wallet.closeWalletControllerForDeletion
import com.denuoweb.hnsdane.wallet.deleteConfirmedWalletStorage
import com.denuoweb.hnsdane.wallet.deleteWalletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.exactWalletNameUtf8
import com.denuoweb.hnsdane.wallet.walletNameImportRefreshMatches
import com.denuoweb.hnsdane.wallet.displayAmount
import com.denuoweb.hnsdane.wallet.formatHnsBaseUnits
import com.denuoweb.hnsdane.wallet.parsePositiveHnsToBaseUnits
import com.denuoweb.hnsdane.wallet.walletDeleteConfirmationMatches
import com.denuoweb.hnsdane.wallet.walletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.walletControllerOperationMayBegin
import com.denuoweb.hnsdane.wallet.walletDeletionMayProceed
import com.denuoweb.hnsdane.wallet.walletNameImportMayBegin
import com.denuoweb.hnsdane.wallet.walletNameImportMayPublish
import com.denuoweb.hnsdane.wallet.walletReadMayPublish
import com.denuoweb.hnsdane.wallet.walletReadBootstrapMayInstall
import com.denuoweb.hnsdane.wallet.walletReadCodeLabel
import com.denuoweb.hnsdane.wallet.walletSetupMayInspectStorage
import com.denuoweb.hnsdane.wallet.walletStorageNamespace
import java.io.File
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.ceil

/** Dedicated native controller for one complete Handshake wallet and Shakedex account. */
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
    @Volatile
    private var directDenuoWorkerHandle: Long = INVALID_HANDLE
    private lateinit var nameImportInput: EditText
    private lateinit var nameImportStatusView: TextView
    private lateinit var sendRecipientInput: EditText
    private lateinit var sendAmountInput: EditText
    private lateinit var sendMaximumFeeInput: EditText
    private lateinit var sendStatusView: TextView
    private lateinit var bitcoinStatusView: TextView
    private lateinit var bitcoinBalanceView: TextView
    private lateinit var bitcoinReceiveView: TextView
    private lateinit var valueActionStatusView: TextView
    private lateinit var shakedexQueryStatusView: TextView
    private lateinit var directDenuoStatusView: TextView
    private lateinit var restoreInput: EditText
    private lateinit var recoveryView: RecoveryPhraseView
    private lateinit var dashboardContent: LinearLayout
    @Volatile
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
    private var sendApprovalDialog: AlertDialog? = null
    private var pendingSendApproval: NativeHnsSendApproval? = null
    private var valueApprovalDialog: AlertDialog? = null
    private var pendingValueApproval: NativeHnsValueApproval? = null
    private var latestReadSnapshot: NativeWalletReadSnapshot? = null
    // This is wallet-local public output. It exists only while this exact
    // native controller stays unlocked and never substitutes for a synced
    // balance, history, name, or spend projection.
    private var localPaymentReceiveTarget: NativeWalletPaymentReceiveTarget? = null
    private var latestReadSnapshotHandle = INVALID_HANDLE
    private var latestReadSnapshotAuthorityGeneration = 0L
    private var latestReadSnapshotEpoch = 0L
    private var walletHnsSyncInProgress = false
    @Volatile
    private var liveHnsSyncPoller: AtomicBoolean? = null
    @Volatile
    private var cachedHnsSyncPresentationWatcher: AtomicBoolean? = null
    @Volatile
    private var hnsCatchupRetry: AtomicBoolean? = null
    private val walletHnsJourney = WalletHnsJourney()
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
        paymentReceiveView = walletReadSummary(R.string.wallet_reads_receive_unavailable).apply {
            setTextIsSelectable(true)
        }
        nameReceiveView = walletReadSummary(R.string.wallet_reads_name_receive_unavailable)
        historyView = walletReadSummary(R.string.wallet_reads_history_unavailable)
        trackedNamesView = walletReadSummary(R.string.wallet_reads_names_unavailable)
        nameImportInput = exactNameImportInput()
        nameImportStatusView = walletReadSummary(R.string.wallet_name_import_unavailable)
        sendRecipientInput = hnsSendRecipientInput()
        sendAmountInput = hnsSendAmountInput(R.string.wallet_send_amount_hint)
        sendMaximumFeeInput = hnsSendAmountInput(R.string.wallet_send_maximum_fee_hint)
        sendStatusView = walletReadSummary(R.string.wallet_send_unavailable)
        bitcoinStatusView = walletReadSummary(R.string.wallet_bitcoin_unavailable)
        bitcoinBalanceView = walletReadSummary(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView = walletReadSummary(R.string.wallet_bitcoin_receive_unavailable).apply {
            setTextIsSelectable(true)
        }
        valueActionStatusView = walletReadSummary(R.string.wallet_value_actions_unavailable)
        shakedexQueryStatusView = walletReadSummary(R.string.wallet_shakedex_queries_unavailable)
        directDenuoStatusView = walletReadSummary(R.string.wallet_direct_denuo_unavailable)
        restoreInput = sensitiveRestoreInput()
        recoveryView = RecoveryPhraseView(this)
        dashboardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setSettingsScreen(getString(R.string.screen_wallet)) {
            addView(dashboardContent)
        }
        renderWalletDashboard()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        lifecycleEpoch += 1
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        restoreCachedHnsSyncPresentation()
        renderWalletDashboard()
        startCachedHnsSyncPresentationWatcher()
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
        cachedHnsSyncPresentationWatcher?.set(false)
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        storageOwner?.let(ProcessWalletStorageOwnership::retire)
        storageOwner = null
        dismissWalletDeletionDialog()
        dismissSendApproval(rejectNative = false)
        dismissValueApproval(rejectNative = false)
        clearRestoreInput()
        clearNameImportInput()
        clearSendInputs()
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

    /**
     * The wallet is an app subsystem, not another long settings form. The
     * dashboard deliberately exposes the current state and the next useful
     * action; detailed protocol workflows remain behind the object they affect.
     */
    private fun renderWalletDashboard() {
        if (!::dashboardContent.isInitialized) return
        // The dashboard is redrawn as the wallet changes state, but these
        // views preserve live state (including secrets, selection, and status
        // text) across redraws. Removing a card from `dashboardContent` does
        // not detach its nested children, so detach each reusable view before
        // placing it in a newly-created card. Without this, the second render
        // crashes with "The specified child already has a parent."
        listOf(statusView, readStatusView, balanceView, recoveryView).forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        dashboardContent.removeAllViews()
        when {
            unconfirmedDatabaseKey != null || recoveryView.hasSecret() -> renderRecoveryDashboard()
            hasRetainedHnsSyncPresentation() -> renderRetainedHnsSyncHandoffDashboard()
            walletHandle == INVALID_HANDLE -> renderNoWalletDashboard()
            // A direct peer synchronization owns the native controller for a
            // bounded network round. Its public progress has a separate
            // mailbox, so preserve the known-unlocked dashboard rather than
            // waiting for a status lock on Android's main thread.
            walletHnsSyncInProgress -> renderUnlockedWalletDashboard()
            NativeWalletBridge.status(walletHandle)?.locked != false -> renderLockedWalletDashboard()
            else -> renderUnlockedWalletDashboard()
        }
    }

    private fun renderNoWalletDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_no_wallet),
            detail = statusView,
            healthy = false,
        ))
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_get_started)) {
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_create),
                summary = getString(R.string.wallet_dashboard_create_summary),
            ) { createWallet() })
            addSettingsRow(navRow(
                title = getString(R.string.row_wallet_restore),
                summary = getString(R.string.wallet_dashboard_restore_summary),
            ) { showRestoreWalletDialog() })
        })
    }

    /**
     * The old activity may still own a bounded native synchronization while a
     * replacement waits for its storage lease. Its public progress proves that
     * this is an existing wallet, so never combine that state with setup
     * actions that could imply the wallet disappeared or restarted.
     */
    private fun renderRetainedHnsSyncHandoffDashboard() {
        restoreCachedHnsSyncPresentation()
        // The retained live presentation proves that this is an existing,
        // previously-unlocked wallet whose old controller is still completing
        // a bounded direct-peer operation. Preserve the normal dashboard so
        // returning to Wallet never looks like a reset, but keep every action
        // disabled until this activity holds the replacement controller.
        renderUnlockedWalletDashboard(actionsAvailable = false)
    }

    private fun hasRetainedHnsSyncPresentation(): Boolean =
        walletHandle == INVALID_HANDLE &&
            WalletHnsLiveSyncPresentationCache.latest(walletNetwork.id) != null

    private fun renderRecoveryDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_recovery_phrase),
            detail = statusView,
            healthy = false,
        ))
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_recovery_phrase)) {
            addView(recoveryView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_recovery_confirm),
                summary = getString(R.string.wallet_dashboard_recovery_summary),
            ) { confirmRecoverySaved() })
        })
    }

    private fun renderLockedWalletDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_locked, walletNetwork.displayName(this)),
            detail = statusView,
            healthy = false,
        ))
        dashboardContent.addView(settingsGroup {
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_unlock),
                summary = getString(R.string.wallet_dashboard_unlock_summary),
            ) { unlockWallet() })
        })
        addWalletTiles(locked = true)
    }

    private fun renderUnlockedWalletDashboard(actionsAvailable: Boolean = true) {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_unlocked, walletNetwork.displayName(this)),
            detail = statusView,
        ))
        dashboardContent.addView(walletBalanceCard(actionsAvailable))
        if (latestReadSnapshot == null || walletHnsSyncInProgress) {
            dashboardContent.addView(statusCard(
                label = getString(R.string.wallet_dashboard_sync_attention),
                detail = readStatusView,
                healthy = false,
            ))
        }
        addWalletTiles(locked = false, actionsAvailable = actionsAvailable)
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_recent_activity)) {
            addSettingsRow(navRow(
                title = getString(R.string.wallet_dashboard_recent_activity),
                summary = recentActivitySummary(),
            ) { showActivityDetails() }.disabledWhenWalletHandoff(!actionsAvailable))
        })
    }

    private fun walletBalanceCard(actionsAvailable: Boolean = true): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = settingsSurfaceDrawable(accent = themeColors().action)
            setPadding(uiDp(16), uiDp(15), uiDp(16), uiDp(14))
            addView(TextView(this@WalletActivity).apply {
                text = getString(R.string.wallet_dashboard_hns_balance)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(themeColors().action)
            })
            balanceView.apply {
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                setTextColor(themeColors().primaryText)
                setPadding(0, uiDp(10), 0, uiDp(12))
            }
            addView(balanceView)
            addView(LinearLayout(this@WalletActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_receive)) {
                    showReceiveWalletDialog()
                }.disabledWhenWalletHandoff(!actionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_send), secondary = true) {
                    showHnsSendDialog()
                }.disabledWhenWalletHandoff(!actionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = uiDp(8)
                })
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_sync)) {
                    synchronizeWalletReads()
                }.disabledWhenWalletHandoff(!actionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = uiDp(8)
                })
            })
        }

    private fun addWalletTiles(locked: Boolean, actionsAvailable: Boolean = true) {
        dashboardContent.addView(TextView(this).apply {
            text = getString(R.string.section_wallet)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(themeColors().secondaryText)
            setPadding(uiDp(4), uiDp(18), uiDp(4), uiDp(7))
        })
        dashboardContent.addView(walletTileRow(
            dashboardTile(
                title = getString(R.string.wallet_dashboard_names),
                summary = if (locked) getString(R.string.wallet_dashboard_locked_short) else namesSummary(),
            ) { showNamesDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
            dashboardTile(
                title = getString(R.string.wallet_dashboard_bitcoin),
                summary = if (locked) getString(R.string.wallet_dashboard_locked_short) else bitcoinSummary(),
            ) { showBitcoinDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
        ))
        dashboardContent.addView(walletTileRow(
            dashboardTile(
                title = getString(R.string.wallet_dashboard_shakedex),
                summary = if (locked) getString(R.string.wallet_dashboard_locked_short) else shakedexSummary(),
            ) { showShakedexDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
            dashboardTile(
                title = getString(R.string.wallet_dashboard_wallet),
                summary = if (locked) getString(R.string.wallet_dashboard_locked_short) else getString(R.string.wallet_dashboard_unlocked_short),
            ) { showWalletDetails() }.disabledWhenWalletHandoff(!actionsAvailable),
        ))
    }

    private fun <T : View> T.disabledWhenWalletHandoff(disabled: Boolean): T = apply {
        if (disabled) {
            isEnabled = false
            isClickable = false
            isFocusable = false
            alpha = 0.55f
        }
    }

    private fun walletTileRow(first: View, second: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, uiDp(8))
            addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = uiDp(4)
            })
            addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = uiDp(4)
            })
        }

    private fun namesSummary(): String = latestReadSnapshot?.trackedNames?.size?.let { count ->
        resources.getQuantityString(R.plurals.wallet_dashboard_tracked_names, count, count)
    } ?: getString(R.string.wallet_dashboard_sync_required)

    private fun bitcoinSummary(): String =
        if (bitcoinBalanceView.text == getString(R.string.wallet_bitcoin_balance_unavailable)) {
            getString(R.string.wallet_dashboard_sync_required)
        } else {
            getString(R.string.wallet_dashboard_ready)
        }

    private fun shakedexSummary(): String =
        if (NativeWalletBridge.walletOwnedDirectDenuoStatus(walletHandle)?.peerEndpoint != null) {
            getString(R.string.wallet_dashboard_connected)
        } else {
            getString(R.string.wallet_dashboard_not_connected)
        }

    private fun recentActivitySummary(): String = latestReadSnapshot?.transactions?.size?.let { count ->
        resources.getQuantityString(R.plurals.wallet_dashboard_transactions, count, count)
    } ?: getString(R.string.wallet_dashboard_no_synced_activity)

    private fun showRestoreWalletDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.row_wallet_restore)
            .setMessage(R.string.wallet_dashboard_restore_dialog_summary)
            .setView(restoreInput)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_restore_wallet) { _, _ -> restoreWallet() }
            .show()
    }

    private fun showReceiveWalletDialog() {
        val payment = paymentReceiveView.text.toString().lineSequence().firstOrNull().orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_receive)
            .setMessage(getString(R.string.wallet_dashboard_receive_message, payment))
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.wallet_dashboard_name_transfer) { _, _ -> showNameReceiveDialog() }
            .setPositiveButton(R.string.wallet_dashboard_copy_address) { _, _ ->
                copyWalletAddress(payment, R.string.wallet_dashboard_receive)
            }
            .show()
    }

    private fun showNameReceiveDialog() {
        val address = nameReceiveView.text.toString().lineSequence().firstOrNull().orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_name_transfer)
            .setMessage(address)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.wallet_dashboard_copy_address) { _, _ ->
                copyWalletAddress(address, R.string.wallet_dashboard_name_transfer)
            }
            .show()
    }

    private fun copyWalletAddress(address: String, label: Int) {
        val unavailableAddresses = setOf(
            getString(R.string.wallet_reads_receive_unavailable),
            getString(R.string.wallet_reads_name_receive_unavailable),
            getString(R.string.wallet_reads_name_receive_legacy_unavailable),
        )
        if (address.isBlank() || address in unavailableAddresses) {
            Toast.makeText(this, R.string.wallet_dashboard_address_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(label), address),
        )
        Toast.makeText(this, R.string.common_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showHnsSendDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(20), uiDp(4), uiDp(20), 0)
            addView(sendRecipientInput)
            addView(sendAmountInput)
            addView(sendMaximumFeeInput)
            addView(TextView(this@WalletActivity).apply {
                text = getString(R.string.wallet_dashboard_send_form_notice)
                textSize = 13f
                setTextColor(themeColors().secondaryText)
                setPadding(0, uiDp(8), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_send)
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_prepare_wallet_send) { _, _ -> prepareWalletSend() }
            .show()
    }

    private fun showWalletDetails() {
        val unlocked = NativeWalletBridge.status(walletHandle)?.locked == false
        val actions = mutableListOf<Pair<String, () -> Unit>>().apply {
            add(
                if (unlocked) {
                    getString(R.string.row_wallet_lock) to ::lockWallet
                } else {
                    getString(R.string.row_wallet_unlock) to ::unlockWallet
                },
            )
            if (unlocked) {
                add(getString(R.string.row_wallet_delete) to ::requestWalletDeletion)
            }
        }
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_wallet),
            rows = listOf(
                getString(R.string.row_wallet_status) to statusView.text.toString(),
                getString(R.string.row_wallet_account) to accountView.text.toString(),
            ),
            actions = actions,
        )
    }

    private fun showActivityDetails() = walletDetailDialog(
        title = getString(R.string.wallet_dashboard_recent_activity),
        rows = listOf(getString(R.string.wallet_dashboard_recent_activity) to historyView.text.toString()),
    )

    private fun showNamesDashboard() {
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_names),
            rows = listOf(
                getString(R.string.row_wallet_read_names) to trackedNamesView.text.toString(),
                getString(R.string.row_wallet_value_action_status) to valueActionStatusView.text.toString(),
            ),
            actions = listOf(
                getString(R.string.action_import_wallet_name) to ::showNameImportDialog,
                getString(R.string.wallet_dashboard_name_actions) to ::showNameActionMenu,
            ),
        )
    }

    private fun showNameImportDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.row_wallet_name_import)
            .setView(nameImportInput)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_import_wallet_name) { _, _ -> importWalletName() }
            .show()
    }

    private fun showNameActionMenu() {
        val labels = arrayOf(
            getString(R.string.row_wallet_transfer_name),
            getString(R.string.row_wallet_finalize_name),
            getString(R.string.row_wallet_create_offer),
            getString(R.string.row_wallet_cancel_offer),
            getString(R.string.row_wallet_recover_name),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_name_actions)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showTransferNameForm()
                    1 -> showFinalizeNameForm()
                    2 -> showCreateOfferForm()
                    3 -> showCancelOfferForm()
                    else -> showRecoverNameForm()
                }
            }
            .show()
    }

    private fun showBitcoinDashboard() {
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_bitcoin),
            rows = listOf(
                getString(R.string.row_wallet_bitcoin_status) to bitcoinStatusView.text.toString(),
                getString(R.string.row_wallet_bitcoin_balance) to bitcoinBalanceView.text.toString(),
                getString(R.string.row_wallet_bitcoin_receive) to bitcoinReceiveView.text.toString(),
            ),
            actions = listOf(
                getString(R.string.action_wallet_bitcoin_receive) to ::revealBitcoinReceiveAddress,
                getString(R.string.action_sync_wallet_reads) to ::synchronizeBitcoin,
                getString(R.string.wallet_dashboard_send_bitcoin) to ::showBitcoinSendForm,
            ),
        )
    }

    private fun showShakedexDashboard() {
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_shakedex),
            rows = listOf(
                getString(R.string.row_wallet_direct_denuo_host) to directDenuoStatusView.text.toString(),
                getString(R.string.row_wallet_shakedex_status) to shakedexQueryStatusView.text.toString(),
            ),
            actions = listOf(
                getString(R.string.row_wallet_list_offers) to ::showListOffersForm,
                getString(R.string.row_wallet_accept_offer) to ::showAcceptOfferForm,
                getString(R.string.row_wallet_finalize_purchase) to ::showFinalizePurchaseForm,
                getString(R.string.row_wallet_pair_direct_denuo) to ::showPairDirectDenuoForm,
                getString(R.string.row_wallet_get_session) to ::showGetSessionForm,
            ),
        )
    }

    private fun walletDetailDialog(
        title: String,
        rows: List<Pair<String, String>>,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(24), uiDp(6), uiDp(24), 0)
            rows.forEach { (label, detail) ->
                addView(TextView(this@WalletActivity).apply {
                    text = "$label\n$detail"
                    textSize = 14f
                    setTextColor(themeColors().primaryText)
                    setPadding(0, uiDp(8), 0, uiDp(12))
                })
            }
            actions.forEach { (label, action) ->
                addView(dashboardActionButton(label, secondary = true, action = action), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = uiDp(8) })
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
                newWalletBirthday(walletNetwork),
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
                renderWalletDashboard()
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
                // A restored wallet is now durable, but the just-restored
                // lifecycle controller has deliberately never been reopened
                // from that durable store. Retire it and reopen through the
                // normal persistent path so the direct, peer-backed HNS
                // controller can be installed immediately. Without this
                // transition, the user had to leave and reopen the screen
                // before a balance snapshot or guarded HNS send was possible.
                if (!destroyWalletController(restored)) {
                    showControllerRetirementUncertain()
                    return@runOnUiThread
                }
                openExistingWallet()
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
                        // The recovery confirmation made this wallet durable.
                        // Reopen the controller from that exact durable state
                        // before installing direct HNS reads/value authority.
                        // This preserves the durable-open admission boundary
                        // while making first funding usable without an app
                        // restart.
                        if (destroyController()) {
                            openExistingWallet()
                            renderWalletDashboard()
                        } else {
                            showControllerRetirementUncertain()
                        }
                    } else {
                        destroyController()
                        releaseStorageLease(checkNotNull(lease))
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
                        renderWalletDashboard()
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
            val unlockResult: Pair<Boolean, NativeWalletPaymentReceiveTarget?> = runCatching {
                keyStore.withDatabaseKey { key ->
                    val unlocked = NativeWalletBridge.unlock(handle, key) == true
                    unlocked to if (unlocked) {
                        NativeWalletBridge.localHnsReceiveTarget(handle)
                    } else {
                        null
                    }
                }
            }.getOrNull() ?: (false to null)
            val (unlocked, localReceiveTarget) = unlockResult
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
                    walletHnsJourney.walletUnlocked()
                    refreshControllerState()
                    localReceiveTarget?.let(::renderLocalPaymentReceiveTarget)
                    if (NativeWalletBridge.directHnsRollbackFloor(handle) != null) {
                        startWalletOwnedDirectDenuoWorker(handle, lease, epoch)
                    }
                    // A direct controller is installed locked. Once the user
                    // has explicitly unlocked it, take one bounded, verified
                    // snapshot so the confirmed spendable balance is visible
                    // without requiring a separate, unexplained refresh.
                    if (NativeWalletBridge.hasHnsReads(handle)) {
                        synchronizeWalletReads()
                    }
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

    /**
     * Runs only while this Activity owns the unlocked direct-wallet controller.
     * Native code holds the listener and rejects/forgets every board socket on
     * lock or controller retirement; this worker merely gives it bounded
     * foreground scheduling. A later foreground-service integration can keep
     * the same native ownership contract when Android background policy allows
     * it, without introducing a relay.
     */
    private fun startWalletOwnedDirectDenuoWorker(
        handle: Long,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        if (directDenuoWorkerHandle == handle) return
        directDenuoWorkerHandle = handle
        thread(name = "hns-wallet-direct-denuo") {
            try {
                var serviceTicks = 0
                while (
                    foreground && operationIsCurrent(epoch, lease) && walletHandle == handle &&
                        (NativeWalletBridge.status(handle)?.locked == false)
                ) {
                    NativeWalletBridge.serviceWalletOwnedDirectDenuo(handle)
                    serviceTicks += 1
                    if (serviceTicks % DIRECT_DENUO_STATUS_REFRESH_TICKS == 0) {
                        runOnUiThread {
                            if (
                                directDenuoWorkerHandle == handle &&
                                    operationIsCurrent(epoch, lease) && walletHandle == handle
                            ) {
                                refreshDirectDenuoStatus()
                            }
                        }
                    }
                    Thread.sleep(DIRECT_DENUO_FOREGROUND_TICK_MILLIS)
                }
            } finally {
                if (directDenuoWorkerHandle == handle) {
                    directDenuoWorkerHandle = INVALID_HANDLE
                }
            }
        }
    }

    private fun requestWalletDeletion() {
        if (busy) {
            showWalletBusyFeedback()
            return
        }
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
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        if (!beginOperation(lease, getString(R.string.wallet_status_syncing_reads))) return
        walletHnsSyncInProgress = true
        readStatusView.text = getString(R.string.wallet_reads_syncing)
        renderWalletDashboard()
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        val poller = startLiveHnsSyncProgressPolling(handle)
        thread(name = "hns-wallet-read-sync") {
            // Do every native controller inspection off the UI thread. A
            // previous bounded sync may still own the controller lock; the
            // operation gate above rejects that second tap immediately while
            // this worker remains safe even if a lifecycle race occurs.
            val status = NativeWalletBridge.status(handle)
            val preflightFailure = when {
                status == null || status.locked -> R.string.wallet_reads_locked
                !NativeWalletBridge.hasHnsReads(handle) ->
                    R.string.wallet_reads_unavailable
                else -> null
            }
            val synchronization = if (preflightFailure == null) {
                synchronizeHnsReadsWithRollbackFloor(handle)
            } else {
                null
            }
            poller.set(false)
            when {
                synchronization?.snapshot != null ->
                    WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)

                synchronization?.catchup != null ->
                    WalletHnsLiveSyncPresentationCache.publishCatchup(
                        walletNetwork.id,
                        synchronization.catchup,
                    )
            }
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
                walletHnsSyncInProgress = false
                if (liveHnsSyncPoller === poller) liveHnsSyncPoller = null
                refreshControllerState()
                when {
                    preflightFailure != null -> resetReadProjection(preflightFailure)
                    synchronization == null -> resetReadProjection(R.string.wallet_reads_sync_failed)
                    synchronization.snapshot != null -> renderReadSnapshot(synchronization.snapshot)
                    synchronization.catchup != null -> {
                        renderReadCatchup(synchronization.catchup)
                        scheduleHnsCatchupRetry(lease, handle, epoch, authorityGeneration)
                    }
                    else -> resetReadProjection(R.string.wallet_reads_sync_failed)
                }
            }
        }
    }

    /**
     * A direct HNS sync is bounded so it can always release controller
     * ownership promptly. Catch-up is therefore a resumable result, not a
     * terminal UI state: continue with a short foreground-only delay while
     * retaining the durable verified height. Leaving Wallet cancels this
     * scheduler; it never creates a background wallet or exposes a partial
     * projection.
     */
    private fun scheduleHnsCatchupRetry(
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        hnsCatchupRetry?.set(false)
        val retry = AtomicBoolean(true)
        hnsCatchupRetry = retry
        thread(name = "hns-wallet-catchup-retry") {
            try {
                Thread.sleep(HNS_CATCHUP_RETRY_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                retry.set(false)
            }
            runOnUiThread {
                if (
                    retry.get() && hnsCatchupRetry === retry && !busy &&
                        !walletHnsSyncInProgress &&
                        walletOperationMayPublish(epoch, lease, handle, authorityGeneration) &&
                        NativeWalletBridge.status(handle)?.locked == false
                ) {
                    synchronizeWalletReads()
                }
            }
        }
    }

    private fun revealBitcoinReceiveAddress() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            resetBitcoinProjection()
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_status_syncing_reads), resetReads = false)) return
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_ready)
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-wallet-receive-address") {
            val address = NativeWalletBridge.nextBitcoinReceiveAddress(handle)
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                if (address == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_receive_failed)
                } else {
                    renderBitcoinSnapshot(address.snapshot)
                }
            }
        }
    }

    private fun synchronizeBitcoin() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            resetBitcoinProjection()
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_status_syncing_reads), resetReads = false)) return
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_syncing)
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-wallet-direct-sync") {
            val synchronization = NativeWalletBridge.synchronizeBitcoin(handle)
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                if (synchronization == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_failed)
                } else {
                    renderBitcoinSnapshot(synchronization.snapshot)
                    bitcoinStatusView.text = getString(
                        R.string.wallet_bitcoin_synchronized,
                        synchronization.checkpointHeight,
                        synchronization.connectedPeerCount,
                        synchronization.requiredPeerCount,
                    )
                }
            }
        }
    }

    private fun resetBitcoinProjection() {
        bitcoinBalanceView.text = getString(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView.text = getString(R.string.wallet_bitcoin_receive_unavailable)
        val status = NativeWalletBridge.status(walletHandle)
        bitcoinStatusView.text = when {
            status?.locked == true -> getString(R.string.wallet_bitcoin_locked)
            NativeWalletBridge.hasBitcoinValue(walletHandle) -> getString(R.string.wallet_bitcoin_ready)
            else -> getString(R.string.wallet_bitcoin_unavailable)
        }
    }

    private fun refreshDirectDenuoStatus() {
        val status = NativeWalletBridge.walletOwnedDirectDenuoStatus(walletHandle)
        directDenuoStatusView.text = when {
            status == null -> getString(R.string.wallet_direct_denuo_unavailable)
            !status.unlocked -> getString(R.string.wallet_direct_denuo_locked)
            status.listenerPort == null -> getString(
                R.string.wallet_direct_denuo_host_unavailable,
                DIRECT_DENUO_LISTEN_PORT,
                status.peerEndpoint ?: getString(R.string.wallet_direct_denuo_peer_none),
            )

            status.peerEndpoint == null -> getString(
                R.string.wallet_direct_denuo_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_denuo_peer_none),
            )

            else -> getString(
                R.string.wallet_direct_denuo_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_denuo_peer_connected, status.peerEndpoint),
            )
        }
    }

    private fun renderBitcoinSnapshot(snapshot: com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot) {
        bitcoinBalanceView.text = getString(
            R.string.wallet_bitcoin_balance,
            snapshot.confirmedSats,
            snapshot.trustedPendingSats,
            snapshot.untrustedPendingSats,
            snapshot.immatureSats,
            snapshot.totalSats,
        )
        bitcoinReceiveView.text = getString(R.string.wallet_bitcoin_receive, snapshot.receiveAddress)
    }

    private data class WalletActionInput(
        val hint: Int,
        val numeric: Boolean = false,
        val initial: String = "",
    )

    /** One validated public HNS payment request retained only across its sync/review flow. */
    private data class WalletHnsSendInput(
        val recipient: String,
        val amountBaseUnits: String,
        val maximumFeeBaseUnits: String,
    )

    private fun walletActionRow(title: Int, summary: Int, action: () -> Unit): View =
        preferenceRow(
            title = getString(title),
            summary = getString(summary),
            actionLabel = getString(R.string.action_open),
            action = action,
        )

    private fun showWalletActionForm(
        title: Int,
        fields: List<WalletActionInput>,
        submit: (List<String>) -> Unit,
    ) {
        if (busy) {
            showWalletBusyFeedback()
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, 0)
        }
        val inputs = fields.map { field ->
            EditText(this).apply {
                hint = getString(field.hint)
                inputType = if (field.numeric) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                filters = arrayOf(InputFilter.LengthFilter(MAX_VALUE_ACTION_INPUT_CHARACTERS))
                setSingleLine(true)
                if (field.initial.isNotEmpty()) setText(field.initial)
                layout.addView(
                    this,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_prepare) { _, _ ->
                submit(inputs.map { it.text?.toString().orEmpty() })
                inputs.forEach { wipeEditable(it.text) }
            }
            .show()
    }

    private fun showBitcoinSendForm() = showWalletActionForm(
        R.string.row_wallet_bitcoin_send,
        listOf(
            WalletActionInput(R.string.wallet_bitcoin_send_destination_hint),
            WalletActionInput(R.string.wallet_bitcoin_send_amount_hint, numeric = true),
            WalletActionInput(R.string.wallet_bitcoin_send_fee_hint, numeric = true),
        ),
    ) { values ->
        val amountSats = values[1].toLongOrNull()?.takeIf { it > 0L }
        val maximumFeeSats = values[2].toLongOrNull()?.takeIf { it > 0L }
        if (amountSats == null || maximumFeeSats == null) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_prepare_failed)
            return@showWalletActionForm
        }
        prepareBitcoinSend(values[0], amountSats, maximumFeeSats)
    }

    private fun prepareBitcoinSend(destination: String, amountSats: Long, maximumFeeSats: Long) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_unavailable)
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_status_preparing_send), resetReads = false)) return
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_preparing)
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-wallet-send-prepare") {
            val approval = NativeWalletBridge.prepareBitcoinSend(
                handle, destination, amountSats, maximumFeeSats,
            )
            val exact = approval?.takeIf {
                it.destination == destination && it.amountSats == amountSats &&
                    it.maximumFeeSats == maximumFeeSats && it.feeSats <= maximumFeeSats
            }
            if (approval != null && exact == null) {
                NativeWalletBridge.rejectBitcoinSend(handle, approval.actionToken)
                approval.close()
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    exact?.let {
                        NativeWalletBridge.rejectBitcoinSend(handle, it.actionToken)
                        it.close()
                    }
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (exact == null) {
                    busy = false
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_prepare_failed)
                } else {
                    showBitcoinSendApproval(exact, lease, epoch)
                }
            }
        }
    }

    private fun showBitcoinSendApproval(
        approval: NativeBitcoinSendApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        val expires = runCatching {
            DateFormat.getDateTimeInstance().format(Date(Math.multiplyExact(approval.expiresAtUnix, 1_000L)))
        }.getOrElse { approval.expiresAtUnix.toString() }
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "bitcoin-wallet-send-reject") {
                NativeWalletBridge.rejectBitcoinSend(walletHandle, approval.actionToken)
                runOnUiThread {
                    if (operationIsCurrent(epoch, lease)) {
                        busy = false
                        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_ready)
                    } else {
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_bitcoin_send_approval_title)
            .setMessage(getString(
                R.string.wallet_bitcoin_send_approval_message,
                approval.destination, approval.amountSats, approval.feeSats, approval.maximumFeeSats, expires,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.action_broadcast_hns) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_broadcasting)
                thread(name = "bitcoin-wallet-send-broadcast") {
                    val receipt = NativeWalletBridge.approveBitcoinSend(walletHandle, approval.actionToken)
                    runOnUiThread {
                        if (!operationIsCurrent(epoch, lease)) {
                            releaseStorageLeaseAfterOperation(lease)
                            return@runOnUiThread
                        }
                        busy = false
                        bitcoinStatusView.text = if (receipt == null) {
                            NativeWalletBridge.lock(walletHandle)
                            getString(R.string.wallet_bitcoin_send_ambiguous)
                        } else {
                            getString(R.string.wallet_bitcoin_send_accepted, receipt.txid)
                        }
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun showTransferNameForm() = showWalletActionForm(
        R.string.row_wallet_transfer_name,
        listOf(
            WalletActionInput(R.string.wallet_action_name_hint),
            WalletActionInput(R.string.wallet_action_recipient_hint),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[2])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(
            NativeHnsValueIntent.TransferName(values[0], values[1], fee),
        )
    }

    private fun showFinalizeNameForm() = showWalletActionForm(
        R.string.row_wallet_finalize_name,
        listOf(
            WalletActionInput(R.string.wallet_action_name_hint),
            WalletActionInput(R.string.wallet_action_expected_recipient_hint),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[2])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(
            NativeHnsValueIntent.FinalizeName(
                values[0],
                values[1].ifBlank { null },
                fee,
            ),
        )
    }

    private fun showCreateOfferForm() = showWalletActionForm(
        R.string.row_wallet_create_offer,
        listOf(
            WalletActionInput(R.string.wallet_action_name_hint),
            WalletActionInput(R.string.wallet_action_price_hint, numeric = true),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
            WalletActionInput(
                R.string.wallet_action_lifetime_hint,
                numeric = true,
                initial = DEFAULT_LISTING_LIFETIME_SECONDS.toString(),
            ),
        ),
    ) { values ->
        val price = parsePositiveHnsToBaseUnits(values[1])
        val fee = parsePositiveHnsToBaseUnits(values[2])
        val lifetime = values[3].toLongOrNull()
        if (price == null || fee == null || lifetime == null) {
            return@showWalletActionForm invalidValueActionInput()
        }
        prepareWalletValueAction(
            NativeHnsValueIntent.CreateFixedPriceOffer(
                values[0],
                price,
                fee,
                lifetime,
            ),
        )
    }

    private fun showCancelOfferForm() = showWalletActionForm(
        R.string.row_wallet_cancel_offer,
        listOf(WalletActionInput(R.string.wallet_action_seller_session_hint)),
    ) { values ->
        prepareWalletValueAction(NativeHnsValueIntent.CancelOffer(values[0]))
    }

    private fun showAcceptOfferForm() = showWalletActionForm(
        R.string.row_wallet_accept_offer,
        listOf(
            WalletActionInput(R.string.wallet_action_listing_hint),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[1])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(NativeHnsValueIntent.AcceptOffer(values[0], fee))
    }

    private fun showFinalizePurchaseForm() = showWalletActionForm(
        R.string.row_wallet_finalize_purchase,
        listOf(
            WalletActionInput(R.string.wallet_action_session_hint),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[1])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(NativeHnsValueIntent.FinalizePurchase(values[0], fee))
    }

    private fun showRecoverNameForm() = showWalletActionForm(
        R.string.row_wallet_recover_name,
        listOf(
            WalletActionInput(R.string.wallet_action_seller_session_hint),
            WalletActionInput(R.string.wallet_action_maximum_fee_hint, numeric = true),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[1])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(NativeHnsValueIntent.RecoverName(values[0], fee))
    }

    private fun showListOffersForm() = showWalletActionForm(
        R.string.row_wallet_list_offers,
        listOf(
            WalletActionInput(R.string.wallet_action_cursor_hint),
            WalletActionInput(
                R.string.wallet_action_limit_hint,
                numeric = true,
                initial = DEFAULT_OFFER_PAGE_SIZE.toString(),
            ),
        ),
    ) { values ->
        val limit = values[1].toIntOrNull()
        if (limit == null) return@showWalletActionForm invalidShakedexQueryInput()
        queryWalletShakedex(
            NativeShakedexQuery.ListOffers(values[0].ifBlank { null }, limit),
        )
    }

    private fun showGetSessionForm() = showWalletActionForm(
        R.string.row_wallet_get_session,
        listOf(WalletActionInput(R.string.wallet_action_session_hint)),
    ) { values ->
        queryWalletShakedex(NativeShakedexQuery.GetSession(values[0]))
    }

    private fun showPairDirectDenuoForm() = showWalletActionForm(
        R.string.row_wallet_pair_direct_denuo,
        listOf(WalletActionInput(R.string.wallet_direct_denuo_endpoint_hint)),
    ) { values ->
        connectWalletOwnedDirectDenuo(values[0])
    }

    private fun connectWalletOwnedDirectDenuo(endpoint: String) {
        val (lease, handle) = directDenuoContext() ?: run {
            directDenuoStatusView.text = getString(R.string.wallet_direct_denuo_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_connecting_direct_denuo),
                resetReads = false,
            )
        ) return
        shakedexQueryStatusView.text = getString(R.string.wallet_direct_denuo_connecting)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-denuo-connect") {
            val result = NativeWalletBridge.connectWalletOwnedDirectDenuo(handle, endpoint)
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = directDenuoConnectionMessage(result)
            }
        }
    }

    private fun retryWalletOwnedDirectDenuoListener() {
        val (lease, handle) = directDenuoContext() ?: run {
            directDenuoStatusView.text = getString(R.string.wallet_direct_denuo_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_retrying_direct_denuo),
                resetReads = false,
            )
        ) return
        directDenuoStatusView.text = getString(R.string.wallet_direct_denuo_host_retrying)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-denuo-listener-retry") {
            val listening = NativeWalletBridge.retryWalletOwnedDirectDenuoListener(handle)
            runOnUiThread {
                if (!walletOperationMayPublish(epoch, lease, handle, authorityGeneration)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = getString(
                    if (listening) R.string.wallet_direct_denuo_host_retry_ready
                    else R.string.wallet_direct_denuo_host_retry_failed,
                )
            }
        }
    }

    private fun disconnectWalletOwnedDirectDenuo() {
        val (lease, handle) = directDenuoContext() ?: run {
            directDenuoStatusView.text = getString(R.string.wallet_direct_denuo_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_disconnecting_direct_denuo),
                resetReads = false,
            )
        ) return
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-denuo-disconnect") {
            val disconnected = NativeWalletBridge.disconnectWalletOwnedDirectDenuo(handle)
            runOnUiThread {
                if (!walletOperationMayPublish(epoch, lease, handle, authorityGeneration)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = getString(
                    if (disconnected) R.string.wallet_direct_denuo_disconnected
                    else R.string.wallet_direct_denuo_no_peer,
                )
            }
        }
    }

    private fun directDenuoConnectionMessage(
        result: NativeWalletDirectDenuoConnectResult?,
    ): String = when (result?.outcome) {
        NativeWalletDirectDenuoConnectResult.Outcome.Connected -> getString(
            R.string.wallet_direct_denuo_connected,
            result?.peerEndpoint ?: getString(R.string.common_unknown),
        )

        NativeWalletDirectDenuoConnectResult.Outcome.Replaced -> getString(
            R.string.wallet_direct_denuo_replaced,
            result?.peerEndpoint ?: getString(R.string.common_unknown),
        )

        NativeWalletDirectDenuoConnectResult.Outcome.Unavailable ->
            getString(R.string.wallet_direct_denuo_connect_unavailable)

        NativeWalletDirectDenuoConnectResult.Outcome.Locked ->
            getString(R.string.wallet_direct_denuo_connect_locked)

        NativeWalletDirectDenuoConnectResult.Outcome.ConnectionFailed ->
            getString(R.string.wallet_direct_denuo_connect_failed)

        NativeWalletDirectDenuoConnectResult.Outcome.ExchangeFailed ->
            getString(R.string.wallet_direct_denuo_exchange_failed)

        null -> getString(R.string.wallet_direct_denuo_connect_bridge_failed)
    }

    private fun invalidValueActionInput() {
        valueActionStatusView.text = getString(R.string.wallet_value_actions_invalid)
    }

    private fun invalidShakedexQueryInput() {
        shakedexQueryStatusView.text = getString(R.string.wallet_shakedex_queries_invalid)
    }

    private fun valueActionContext(): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        val snapshot = latestReadSnapshot
        val status = NativeWalletBridge.status(handle)
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && snapshot != null && status != null && !status.locked &&
                status.hnsValueEnabled && status.shakedexEnabled &&
                NativeWalletBridge.hasHnsValue(handle) &&
                latestReadSnapshotHandle == handle &&
                latestReadSnapshotAuthorityGeneration == walletAuthorityGeneration &&
                latestReadSnapshotEpoch == lifecycleEpoch &&
                unconfirmedDatabaseKey == null
        }
    }

    private fun directDenuoContext(): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        val status = NativeWalletBridge.status(handle)
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && status != null && !status.locked &&
                unconfirmedDatabaseKey == null &&
                NativeWalletBridge.walletOwnedDirectDenuoStatus(handle) != null
        }
    }

    private fun walletOperationMayPublish(
        epoch: Long,
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
        authorityGeneration: Long,
    ): Boolean = walletReadMayPublish(
        expectedEpoch = epoch,
        currentEpoch = lifecycleEpoch,
        foreground = foreground,
        ownsCurrentLease = currentStorageLease() === lease,
        expectedHandle = handle,
        currentHandle = walletHandle,
        expectedAuthorityGeneration = authorityGeneration,
        currentAuthorityGeneration = walletAuthorityGeneration,
    ) && operationIsCurrent(epoch, lease)

    private fun prepareWalletValueAction(intent: NativeHnsValueIntent) {
        val (lease, handle) = valueActionContext() ?: run {
            valueActionStatusView.text = getString(R.string.wallet_value_actions_requires_sync)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_value_action),
                resetReads = false,
            )
        ) return
        valueActionStatusView.text = getString(R.string.wallet_value_actions_preparing)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        val expectedKind = when (intent) {
            is NativeHnsValueIntent.TransferName -> NativeHnsValueApprovalKind.NAME_TRANSFER
            is NativeHnsValueIntent.FinalizeName -> NativeHnsValueApprovalKind.NAME_FINALIZE
            is NativeHnsValueIntent.CreateFixedPriceOffer,
            is NativeHnsValueIntent.CancelOffer,
            is NativeHnsValueIntent.RecoverName -> NativeHnsValueApprovalKind.NAME_MARKET_OFFER
            is NativeHnsValueIntent.AcceptOffer,
            is NativeHnsValueIntent.FinalizePurchase ->
                NativeHnsValueApprovalKind.NAME_MARKET_PURCHASE
        }
        thread(name = "hns-wallet-value-prepare") {
            val approval = NativeWalletBridge.prepareHnsValueAction(handle, intent)
            val exact = approval?.takeIf { it.kind == expectedKind }
            if (approval != null && exact == null) {
                NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
                approval.close()
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
                    expectedHandle = handle,
                    currentHandle = walletHandle,
                    expectedAuthorityGeneration = authorityGeneration,
                    currentAuthorityGeneration = walletAuthorityGeneration,
                ) && operationIsCurrent(epoch, lease)
                if (!mayPublish) {
                    exact?.let {
                        NativeWalletBridge.rejectHnsValueAction(handle, it.actionToken)
                        it.close()
                    }
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (exact == null) {
                    busy = false
                    refreshControllerState(resetReads = false)
                    valueActionStatusView.text =
                        getString(R.string.wallet_value_actions_prepare_failed)
                } else {
                    showValueApproval(exact, lease, epoch, authorityGeneration)
                }
            }
        }
    }

    private fun showValueApproval(
        approval: NativeHnsValueApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        dismissValueApproval(rejectNative = true)
        pendingValueApproval = approval
        val expires = runCatching {
            DateFormat.getDateTimeInstance().format(
                Date(Math.multiplyExact(approval.expiresAtUnix, 1_000L)),
            )
        }.getOrElse { approval.expiresAtUnix.toString() }
        val message = (approval.detailLines + getString(
            R.string.wallet_value_actions_expires,
            expires,
        )).joinToString("\n\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle(approval.title)
            .setMessage(message)
            .setNegativeButton(R.string.action_reject) { _, _ ->
                rejectPreparedValueAction(approval, lease, epoch, authorityGeneration)
            }
            .setPositiveButton(R.string.action_approve_hns_value) { _, _ ->
                approvePreparedValueAction(approval, lease, epoch, authorityGeneration)
            }
            .create()
        valueApprovalDialog = dialog
        dialog.setOnCancelListener {
            rejectPreparedValueAction(approval, lease, epoch, authorityGeneration)
        }
        dialog.setOnDismissListener {
            if (valueApprovalDialog === dialog) valueApprovalDialog = null
            if (pendingValueApproval === approval) {
                rejectPreparedValueAction(approval, lease, epoch, authorityGeneration)
            }
        }
        dialog.show()
    }

    private fun approvePreparedValueAction(
        approval: NativeHnsValueApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingValueApproval !== approval) return
        pendingValueApproval = null
        valueActionStatusView.text = getString(R.string.wallet_value_actions_executing)
        val handle = walletHandle
        thread(name = "hns-wallet-value-execute") {
            val result = NativeWalletBridge.approveHnsValueActionResult(
                handle,
                approval.actionToken,
            )
            approval.close()
            val snapshot = result?.let { synchronizeHnsSnapshotWithRollbackFloor(handle) }
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = result == null || snapshot == null)
                if (snapshot != null) renderReadSnapshot(snapshot)
                valueActionStatusView.text = if (result == null) {
                    getString(R.string.wallet_value_actions_result_ambiguous)
                } else {
                    getString(R.string.wallet_value_actions_result, result.displayJson)
                }
            }
        }
    }

    private fun rejectPreparedValueAction(
        approval: NativeHnsValueApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingValueApproval !== approval) return
        pendingValueApproval = null
        val handle = walletHandle
        thread(name = "hns-wallet-value-reject") {
            val rejected = NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
            approval.close()
            if (!rejected) NativeWalletBridge.lock(handle)
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = !rejected)
                valueActionStatusView.text = getString(
                    if (rejected) R.string.wallet_value_actions_rejected
                    else R.string.wallet_value_actions_reject_failed,
                )
            }
        }
    }

    private fun dismissValueApproval(rejectNative: Boolean) {
        val approval = pendingValueApproval
        pendingValueApproval = null
        val dialog = valueApprovalDialog
        valueApprovalDialog = null
        dialog?.setOnCancelListener(null)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        if (approval != null) {
            if (rejectNative && walletHandle != INVALID_HANDLE) {
                val handle = walletHandle
                thread(name = "hns-wallet-value-dismiss") {
                    NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
                    approval.close()
                }
            } else {
                approval.close()
            }
            busy = false
        }
    }

    private fun queryWalletShakedex(query: NativeShakedexQuery) {
        val (lease, handle) = valueActionContext() ?: run {
            shakedexQueryStatusView.text =
                getString(R.string.wallet_shakedex_queries_requires_sync)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_querying_shakedex),
                resetReads = false,
            )
        ) return
        shakedexQueryStatusView.text = getString(R.string.wallet_shakedex_queries_loading)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-shakedex-query") {
            val result = NativeWalletBridge.queryShakedex(handle, query)
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = if (result == null) {
                    getString(R.string.wallet_shakedex_queries_failed)
                } else {
                    getString(R.string.wallet_shakedex_queries_result, result.displayJson)
                }
            }
        }
    }

    private fun prepareWalletSend() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        val request = walletHnsSendInput() ?: run {
            sendStatusView.text = getString(R.string.wallet_send_invalid)
            return
        }
        val status = NativeWalletBridge.status(handle)
        if (
            handle == INVALID_HANDLE || status == null || status.locked ||
            !NativeWalletBridge.hasHnsValue(handle) ||
            unconfirmedDatabaseKey != null
        ) {
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }

        if (hasCurrentWalletReadSnapshot(handle)) {
            prepareWalletSendFromCurrentSnapshot(lease, handle, request)
        } else {
            // A first receive or a resumed wallet commonly has no snapshot
            // yet. Review send must make that state visible and obtain one
            // bounded verified snapshot itself, rather than silently doing
            // nothing and forcing the user to discover a separate refresh.
            synchronizeBeforePreparingWalletSend(lease, handle, request)
        }
    }

    private fun walletHnsSendInput(): WalletHnsSendInput? {
        val recipient = sendRecipientInput.text?.toString().orEmpty()
        val amountBaseUnits = sendAmountInput.text?.let(::parsePositiveHnsToBaseUnits)
        val maximumFeeBaseUnits =
            sendMaximumFeeInput.text?.let(::parsePositiveHnsToBaseUnits)
        if (
            recipient.toByteArray(Charsets.UTF_8).size !in 1..MAX_SEND_RECIPIENT_BYTES ||
            recipient.any { it.code !in 0x21..0x7e } ||
            amountBaseUnits == null || maximumFeeBaseUnits == null
        ) return null
        return WalletHnsSendInput(recipient, amountBaseUnits, maximumFeeBaseUnits)
    }

    private fun hasCurrentWalletReadSnapshot(handle: Long): Boolean =
        walletHnsJourney.mayReviewHnsSend() && latestReadSnapshot != null &&
            latestReadSnapshotHandle == handle &&
            latestReadSnapshotAuthorityGeneration == walletAuthorityGeneration &&
            latestReadSnapshotEpoch == lifecycleEpoch

    private fun synchronizeBeforePreparingWalletSend(
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
        request: WalletHnsSendInput,
    ) {
        if (!NativeWalletBridge.hasHnsReads(handle)) {
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_syncing_reads),
                resetReads = false,
            )
        ) return
        sendStatusView.text = getString(R.string.wallet_send_syncing_before_review)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-send-sync") {
            val synchronization = synchronizeHnsReadsWithRollbackFloor(handle)
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                val snapshot = synchronization?.snapshot
                if (snapshot == null) {
                    refreshControllerState()
                    val catchup = synchronization?.catchup
                    if (catchup == null) {
                        sendStatusView.text = getString(R.string.wallet_send_sync_failed)
                    } else {
                        renderReadCatchup(catchup)
                        sendStatusView.text = getString(
                            R.string.wallet_send_catchup_before_review,
                            catchup.scannedHeight ?: catchup.birthdayHeight,
                            catchup.scanTargetHeight,
                        )
                    }
                } else {
                    renderReadSnapshot(snapshot)
                    prepareWalletSendFromCurrentSnapshot(lease, handle, request)
                }
            }
        }
    }

    private fun prepareWalletSendFromCurrentSnapshot(
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
        request: WalletHnsSendInput,
    ) {
        val status = NativeWalletBridge.status(handle)
        if (
            status == null || status.locked || !NativeWalletBridge.hasHnsValue(handle) ||
            !hasCurrentWalletReadSnapshot(handle) || unconfirmedDatabaseKey != null
        ) {
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }
        if (
            !beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_send),
                resetReads = false,
            )
        ) return
        sendStatusView.text = getString(R.string.wallet_send_preparing)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-send-prepare") {
            val approval = NativeWalletBridge.prepareHnsSend(
                handle = handle,
                recipientUtf8 = request.recipient.toByteArray(Charsets.UTF_8),
                amountBaseUnitsAscii = request.amountBaseUnits.toByteArray(Charsets.US_ASCII),
                maximumFeeBaseUnitsAscii = request.maximumFeeBaseUnits.toByteArray(Charsets.US_ASCII),
            )
            val exact = approval?.takeIf {
                it.recipient == request.recipient &&
                    it.amountBaseUnits == request.amountBaseUnits &&
                    it.maximumFeeBaseUnits == request.maximumFeeBaseUnits
            }
            if (approval != null && exact == null) {
                NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
                approval.close()
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
                    expectedHandle = handle,
                    currentHandle = walletHandle,
                    expectedAuthorityGeneration = authorityGeneration,
                    currentAuthorityGeneration = walletAuthorityGeneration,
                ) && operationIsCurrent(epoch, lease)
                if (!mayPublish) {
                    exact?.let {
                        NativeWalletBridge.rejectHnsValueAction(handle, it.actionToken)
                        it.close()
                    }
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (exact == null) {
                    busy = false
                    refreshControllerState(resetReads = false)
                    sendStatusView.text = getString(R.string.wallet_send_prepare_failed)
                } else {
                    clearSendInputs()
                    showSendApproval(exact, lease, epoch, authorityGeneration)
                }
            }
        }
    }

    private fun showSendApproval(
        approval: NativeHnsSendApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        dismissSendApproval(rejectNative = true)
        pendingSendApproval = approval
        val expires = runCatching {
            DateFormat.getDateTimeInstance().format(
                Date(Math.multiplyExact(approval.expiresAtUnix, 1_000L)),
            )
        }.getOrElse { approval.expiresAtUnix.toString() }
        val message = getString(
            R.string.wallet_send_approval_message,
            approval.recipient,
            formatHnsBaseUnits(approval.amountBaseUnits),
            formatHnsBaseUnits(approval.maximumFeeBaseUnits),
            getString(R.string.wallet_send_finality_pow),
            getString(R.string.wallet_send_warning_fee_change),
            expires,
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_send_approval_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_reject) { _, _ ->
                rejectPreparedSend(approval, lease, epoch, authorityGeneration)
            }
            .setPositiveButton(R.string.action_broadcast_hns) { _, _ ->
                approvePreparedSend(approval, lease, epoch, authorityGeneration)
            }
            .create()
        sendApprovalDialog = dialog
        dialog.setOnCancelListener {
            rejectPreparedSend(approval, lease, epoch, authorityGeneration)
        }
        dialog.setOnDismissListener {
            if (sendApprovalDialog === dialog) sendApprovalDialog = null
            if (pendingSendApproval === approval) {
                rejectPreparedSend(approval, lease, epoch, authorityGeneration)
            }
        }
        dialog.show()
    }

    private fun approvePreparedSend(
        approval: NativeHnsSendApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingSendApproval !== approval) return
        pendingSendApproval = null
        sendStatusView.text = getString(R.string.wallet_send_broadcasting)
        statusView.text = getString(R.string.wallet_status_broadcasting_send)
        val handle = walletHandle
        thread(name = "hns-wallet-send-broadcast") {
            val receipt = NativeWalletBridge.approveHnsValueAction(handle, approval.actionToken)
            approval.close()
            val snapshot = receipt?.let { synchronizeHnsSnapshotWithRollbackFloor(handle) }
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = receipt == null || snapshot == null)
                when {
                    receipt == null -> {
                        sendStatusView.text = getString(R.string.wallet_send_broadcast_ambiguous)
                    }
                    snapshot == null -> {
                        sendStatusView.text = getString(
                            R.string.wallet_send_accepted_sync_failed,
                            receipt.txid,
                        )
                    }
                    else -> {
                        renderReadSnapshot(snapshot)
                        sendStatusView.text = getString(
                            R.string.wallet_send_accepted,
                            receipt.txid,
                            receipt.acceptedAtUnix,
                        )
                    }
                }
            }
        }
    }

    private fun rejectPreparedSend(
        approval: NativeHnsSendApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingSendApproval !== approval) return
        pendingSendApproval = null
        sendStatusView.text = getString(R.string.wallet_send_rejecting)
        val handle = walletHandle
        thread(name = "hns-wallet-send-reject") {
            val rejected = NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
            approval.close()
            if (!rejected) NativeWalletBridge.lock(handle)
            runOnUiThread {
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = foreground,
                    ownsCurrentLease = currentStorageLease() === lease,
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
                refreshControllerState(resetReads = !rejected)
                sendStatusView.text = if (rejected) {
                    getString(R.string.wallet_send_rejected)
                } else {
                    getString(R.string.wallet_send_reject_failed)
                }
            }
        }
    }

    private fun dismissSendApproval(rejectNative: Boolean) {
        val approval = pendingSendApproval
        pendingSendApproval = null
        val dialog = sendApprovalDialog
        sendApprovalDialog = null
        dialog?.setOnCancelListener(null)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        if (approval != null) {
            if (rejectNative && walletHandle != INVALID_HANDLE) {
                val handle = walletHandle
                thread(name = "hns-wallet-send-dismiss") {
                    NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
                    approval.close()
                }
            } else {
                approval.close()
            }
            busy = false
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
                synchronizeHnsSnapshotWithRollbackFloor(handle)?.takeIf { refreshed ->
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
            localPaymentReceiveTarget = null
            statusView.text = getString(R.string.wallet_status_unavailable)
            accountView.text = getString(R.string.wallet_account_unavailable)
            resetReadProjection(R.string.wallet_reads_unavailable)
            resetBitcoinProjection()
            refreshDirectDenuoStatus()
            renderWalletDashboard()
            return
        }
        if (status.locked) {
            walletHnsJourney.walletLocked()
            localPaymentReceiveTarget = null
            statusView.text = getString(R.string.wallet_status_locked)
            accountView.text = getString(R.string.wallet_account_locked)
            resetReadProjection(R.string.wallet_reads_locked)
            resetBitcoinProjection()
            refreshDirectDenuoStatus()
            renderWalletDashboard()
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
                restoreCachedHnsSyncPresentation()
            } else {
                resetReadProjection(R.string.wallet_reads_unavailable)
            }
        }
        resetBitcoinProjection()
        refreshDirectDenuoStatus()
        renderWalletDashboard()
    }

    private fun attemptReadBootstrap(lease: WalletStorageOwnershipGate.Lease) {
        val expectedAuthority = walletReadBootstrapState(lease).authority ?: return
        if (!walletReadBootstrapMayInstall(expectedAuthority, walletReadBootstrapState(lease))) return
        if (!beginOperation(lease, getString(R.string.wallet_status_syncing_reads))) return
        val epoch = lifecycleEpoch
        thread(name = "hns-wallet-direct-install") {
            val installed = runCatching {
                val floor = keyStore.directHnsRollbackFloorForOpen()
                val bootstrap = if (
                    walletNetwork == HandshakeNetwork.Mainnet && floor.all { it == 0.toByte() }
                ) {
                    HeaderSnapshotInstaller.extractWalletGenesisBootstrap(applicationContext)
                } else {
                    null
                }
                try {
                    keyStore.withDatabaseKey { databaseKey ->
                        NativeWalletBridge.configureWalletOwnedDirectHnsValue(
                            currentAuthority = expectedAuthority,
                            databaseKey = databaseKey,
                            rollbackFloor = floor,
                            genesisBootstrapPath = bootstrap?.absolutePath.orEmpty(),
                        )
                    } == true
                } finally {
                    bootstrap?.delete()
                }
            }.getOrDefault(false)
            val floorStored = if (installed) {
                NativeWalletBridge.directHnsRollbackFloor(expectedAuthority.walletHandle)?.let { floor ->
                    runCatching {
                        keyStore.storeInitialDirectHnsRollbackFloor(floor)
                        true
                    }.getOrDefault(false)
                } == true
            } else {
                false
            }
            if (installed && !floorStored) {
                NativeWalletBridge.lock(expectedAuthority.walletHandle)
            }
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                if (!installed || !floorStored) {
                    resetReadProjection(R.string.wallet_reads_unavailable)
                } else {
                    walletHnsJourney.directControllerInstalled()
                }
                refreshControllerState()
            }
        }
    }

    /**
     * Sync a wallet-owned direct HNS controller under its Keystore-held
     * rollback journal. Legacy app-owned node controllers have no direct
     * coordinator and retain their existing compatibility behavior.
     */
    private fun synchronizeHnsReadsWithRollbackFloor(handle: Long): NativeWalletHnsSynchronization? {
        val openingFloor = NativeWalletBridge.directHnsRollbackFloor(handle)
            ?: return NativeWalletBridge.synchronizeHnsReads(handle)
        openingFloor.fill(0)
        val journalStart = beginDirectHnsSynchronizationWithRecovery(
            begin = keyStore::beginDirectHnsSynchronization,
            recoverInterrupted = {
                val recoveredFloor = NativeWalletBridge.directHnsRollbackFloor(handle)
                    ?: throw IllegalStateException("Direct HNS rollback floor is unavailable")
                try {
                    // The active coordinator was opened under the
                    // Keystore-held floor. `commit…` independently rejects a
                    // backwards floor, so this only heals an interrupted
                    // marker; it cannot admit a rolled-back wallet.
                    keyStore.commitDirectHnsSynchronization(recoveredFloor)
                } finally {
                    recoveredFloor.fill(0)
                }
            },
        )
        when (journalStart) {
            DirectHnsSynchronizationJournalStart.Started -> Unit
            DirectHnsSynchronizationJournalStart.Recovered -> Log.i(
                TAG,
                "Recovered an interrupted direct HNS rollback journal before retrying sync",
            )
            DirectHnsSynchronizationJournalStart.Failed -> {
                Log.e(TAG, "Direct HNS rollback journal could not begin or recover")
                return null
            }
        }
        val synchronization = NativeWalletBridge.synchronizeHnsReads(handle)
        val updatedFloor = NativeWalletBridge.directHnsRollbackFloor(handle)
        val committed = updatedFloor?.let { floor ->
            runCatching {
                keyStore.commitDirectHnsSynchronization(floor)
                true
            }.getOrDefault(false)
        } == true
        if (!committed) {
            updatedFloor?.fill(0)
            NativeWalletBridge.lock(handle)
            return null
        }
        return synchronization
    }

    private fun synchronizeHnsSnapshotWithRollbackFloor(handle: Long): NativeWalletReadSnapshot? =
        synchronizeHnsReadsWithRollbackFloor(handle)?.snapshot

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
        statusView.text = if (hasRetainedHnsSyncPresentation()) {
            getString(R.string.wallet_status_sync_handoff)
        } else {
            getString(R.string.wallet_status_starting)
        }
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
        dismissSendApproval(rejectNative = false)
        dismissValueApproval(rejectNative = false)
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
        localPaymentReceiveTarget = null
        walletControllerIsReopenedDurable = reopenedDurable
        walletHnsJourney.controllerPublished(reopenedDurable)
    }

    private fun detachWalletController(): Long {
        val handle = walletHandle
        walletHandle = INVALID_HANDLE
        localPaymentReceiveTarget = null
        walletControllerIsReopenedDurable = false
        walletHnsJourney.controllerRetired()
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
        if (busy) {
            showWalletBusyFeedback()
            return false
        }
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
            when {
                retirementFailed -> showControllerRetirementUncertain()
                busy -> showWalletBusyFeedback()
                else -> statusView.text = getString(R.string.wallet_status_unavailable)
            }
            return false
        }
        busy = true
        statusView.text = status
        if (resetReads) resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        return true
    }

    private fun showWalletBusyFeedback() {
        Toast.makeText(this, R.string.wallet_action_busy, Toast.LENGTH_SHORT).show()
    }

    private fun showNoWallet() {
        statusView.text = if (NativeWalletBridge.isAvailable) {
            getString(R.string.wallet_status_not_created)
        } else {
            getString(R.string.wallet_status_native_unavailable)
        }
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        renderWalletDashboard()
    }

    private fun showControllerRetirementUncertain() {
        statusView.text = getString(R.string.wallet_status_controller_retirement_uncertain)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_unavailable)
        renderWalletDashboard()
    }

    private fun walletReadSummary(text: Int): TextView = preferenceSummary(
        text = getString(text),
        maxLines = Int.MAX_VALUE,
    )

    private fun resetReadProjection(status: Int) {
        walletHnsJourney.clearVerifiedSnapshot()
        latestReadSnapshot = null
        latestReadSnapshotHandle = INVALID_HANDLE
        latestReadSnapshotAuthorityGeneration = 0L
        latestReadSnapshotEpoch = 0L
        readStatusView.text = getString(status)
        balanceView.text = getString(R.string.wallet_reads_balance_unavailable)
        paymentReceiveView.text = localPaymentReceiveTarget?.let { target ->
            localPaymentReceiveText(target)
        } ?: getString(R.string.wallet_reads_receive_unavailable)
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
        sendStatusView.text = when (status) {
            R.string.wallet_reads_locked -> getString(R.string.wallet_send_locked)
            R.string.wallet_reads_ready_to_sync -> if (
                NativeWalletBridge.hasHnsValue(walletHandle)
            ) {
                getString(R.string.wallet_send_requires_sync)
            } else {
                getString(R.string.wallet_send_unavailable)
            }
            R.string.wallet_reads_recovery_unconfirmed ->
                getString(R.string.wallet_send_recovery_unconfirmed)
            R.string.wallet_reads_waiting_for_wallet ->
                getString(R.string.wallet_send_waiting_for_wallet)
            else -> getString(R.string.wallet_send_unavailable)
        }
        valueActionStatusView.text = when (status) {
            R.string.wallet_reads_locked -> getString(R.string.wallet_value_actions_locked)
            R.string.wallet_reads_ready_to_sync -> if (
                NativeWalletBridge.hasHnsValue(walletHandle)
            ) {
                getString(R.string.wallet_value_actions_requires_sync)
            } else {
                getString(R.string.wallet_value_actions_unavailable)
            }
            else -> getString(R.string.wallet_value_actions_unavailable)
        }
        shakedexQueryStatusView.text = when (status) {
            R.string.wallet_reads_locked -> getString(R.string.wallet_shakedex_queries_locked)
            R.string.wallet_reads_ready_to_sync -> if (
                NativeWalletBridge.hasHnsValue(walletHandle)
            ) {
                getString(R.string.wallet_shakedex_queries_requires_sync)
            } else {
                getString(R.string.wallet_shakedex_queries_unavailable)
            }
            else -> getString(R.string.wallet_shakedex_queries_unavailable)
        }
    }

    /**
     * Show authenticated direct-peer catch-up without retaining any partial
     * wallet projection. The native controller persists this progress, but a
     * balance and guarded value actions remain unavailable until a later
     * bounded sync reaches the exact verified tip.
     */
    private fun renderReadCatchup(progress: NativeWalletHnsCatchupProgress) {
        WalletHnsLiveSyncPresentationCache.publishCatchup(walletNetwork.id, progress)
        walletHnsJourney.catchupObserved()
        resetReadProjection(R.string.wallet_reads_catching_up)
        readStatusView.text = when (progress.headerState) {
            NativeWalletHnsCatchupProgress.HeaderState.Current -> getString(
                R.string.wallet_reads_catching_up_scan,
                progress.scannedHeight ?: progress.birthdayHeight,
                progress.scanTargetHeight,
            )

            NativeWalletHnsCatchupProgress.HeaderState.Syncing -> getString(
                R.string.wallet_reads_catching_up_headers,
                progress.headerTipHeight,
                progress.scannedHeight ?: progress.birthdayHeight,
                progress.scanTargetHeight,
            )

            NativeWalletHnsCatchupProgress.HeaderState.Degraded -> getString(
                R.string.wallet_reads_catching_up_degraded,
                progress.headerTipHeight,
            )
        }
        renderWalletDashboard()
    }

    /**
     * Poll an isolated native mailbox rather than the wallet controller. The
     * poller deliberately remains alive after this Activity stops: the native
     * bounded sync is not cancelled by Back, and its public checkpoint remains
     * available when a replacement WalletActivity is opened.
     */
    private fun startLiveHnsSyncProgressPolling(handle: Long): AtomicBoolean {
        liveHnsSyncPoller?.set(false)
        return AtomicBoolean(true).also { poller ->
            liveHnsSyncPoller = poller
            thread(name = "hns-wallet-live-sync-progress") {
                while (poller.get()) {
                    NativeWalletBridge.liveHnsSynchronizationProgress(handle)?.let { progress ->
                        WalletHnsLiveSyncPresentationCache.publishLive(walletNetwork.id, progress)
                        runOnUiThread {
                            if (
                                poller.get() &&
                                liveHnsSyncPoller === poller &&
                                foreground &&
                                walletHandle == handle
                            ) {
                                renderLiveHnsSyncProgress(progress)
                            }
                        }
                    }
                    if (!poller.get()) break
                    try {
                        Thread.sleep(LIVE_HNS_SYNC_PROGRESS_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        poller.set(false)
                    }
                }
            }
        }
    }

    /**
     * A replacement WalletActivity may be waiting for the departing activity
     * to finish a bounded native call and release its storage lease. Observe
     * the same public cache during that small handoff so Back never presents
     * a misleading empty restart state.
     */
    private fun startCachedHnsSyncPresentationWatcher() {
        cachedHnsSyncPresentationWatcher?.set(false)
        AtomicBoolean(true).also { watcher ->
            cachedHnsSyncPresentationWatcher = watcher
            thread(name = "hns-wallet-cached-sync-presentation") {
                var lastPresentation: WalletHnsLiveSyncPresentation? = null
                while (watcher.get()) {
                    val presentation = WalletHnsLiveSyncPresentationCache.latest(walletNetwork.id)
                    if (presentation != null && presentation != lastPresentation) {
                        lastPresentation = presentation
                        runOnUiThread {
                            if (
                                watcher.get() &&
                                cachedHnsSyncPresentationWatcher === watcher &&
                                foreground &&
                                walletHandle == INVALID_HANDLE
                            ) {
                                restoreCachedHnsSyncPresentation()
                                renderWalletDashboard()
                            }
                        }
                    }
                    if (!watcher.get() || walletHandle != INVALID_HANDLE) break
                    try {
                        Thread.sleep(LIVE_HNS_SYNC_PROGRESS_POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        watcher.set(false)
                    }
                }
            }
        }
    }

    private fun restoreCachedHnsSyncPresentation() {
        when (val presentation = WalletHnsLiveSyncPresentationCache.latest(walletNetwork.id)) {
            is WalletHnsLiveSyncPresentation.Live -> renderLiveHnsSyncProgress(presentation.progress)
            is WalletHnsLiveSyncPresentation.Catchup -> renderCachedHnsCatchup(presentation.progress)
            null -> Unit
        }
    }

    private fun renderLiveHnsSyncProgress(progress: NativeWalletHnsLiveSyncProgress) {
        walletHnsJourney.catchupObserved()
        readStatusView.text = when (progress.stage) {
            NativeWalletHnsLiveSyncProgress.Stage.Connecting -> getString(
                R.string.wallet_reads_live_connecting,
                progress.headerTipHeight,
            )

            NativeWalletHnsLiveSyncProgress.Stage.Headers -> getString(
                R.string.wallet_reads_live_headers,
                progress.headerRound,
                DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC,
                progress.headerTipHeight,
            )

            NativeWalletHnsLiveSyncProgress.Stage.Retrying -> getString(
                R.string.wallet_reads_live_retrying,
                progress.headerRetries,
                DIRECT_HNS_MAX_HEADER_AGREEMENT_RECOVERIES_PER_SYNC,
                progress.headerTipHeight,
            )

            NativeWalletHnsLiveSyncProgress.Stage.Scanning -> getString(
                R.string.wallet_reads_live_scanning,
                progress.headerTipHeight,
                progress.scannedHeight ?: progress.birthdayHeight,
                progress.scanTargetHeight,
            )

            NativeWalletHnsLiveSyncProgress.Stage.Finalizing -> getString(
                R.string.wallet_reads_live_finalizing,
                progress.headerTipHeight,
            )
        }
    }

    private fun renderCachedHnsCatchup(progress: NativeWalletHnsCatchupProgress) {
        walletHnsJourney.catchupObserved()
        readStatusView.text = when (progress.headerState) {
            NativeWalletHnsCatchupProgress.HeaderState.Current -> getString(
                R.string.wallet_reads_catching_up_scan,
                progress.scannedHeight ?: progress.birthdayHeight,
                progress.scanTargetHeight,
            )

            NativeWalletHnsCatchupProgress.HeaderState.Syncing -> getString(
                R.string.wallet_reads_catching_up_headers,
                progress.headerTipHeight,
                progress.scannedHeight ?: progress.birthdayHeight,
                progress.scanTargetHeight,
            )

            NativeWalletHnsCatchupProgress.HeaderState.Degraded -> getString(
                R.string.wallet_reads_catching_up_degraded,
                progress.headerTipHeight,
            )
        }
    }

    private fun renderReadSnapshot(snapshot: NativeWalletReadSnapshot) {
        WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)
        walletHnsJourney.verifiedSnapshotObserved()
        latestReadSnapshot = snapshot
        localPaymentReceiveTarget = snapshot.paymentReceiveTarget
        latestReadSnapshotHandle = walletHandle
        latestReadSnapshotAuthorityGeneration = walletAuthorityGeneration
        latestReadSnapshotEpoch = lifecycleEpoch
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
        sendStatusView.text = if (NativeWalletBridge.hasHnsValue(walletHandle)) {
            getString(R.string.wallet_send_ready, snapshot.height)
        } else {
            getString(R.string.wallet_send_unavailable)
        }
        val status = NativeWalletBridge.status(walletHandle)
        valueActionStatusView.text = if (status?.hnsValueEnabled == true) {
            getString(R.string.wallet_value_actions_ready, snapshot.height)
        } else {
            getString(R.string.wallet_value_actions_unavailable)
        }
        shakedexQueryStatusView.text = if (status?.shakedexEnabled == true) {
            getString(R.string.wallet_shakedex_queries_ready, snapshot.height)
        } else {
            getString(R.string.wallet_shakedex_queries_unavailable)
        }
        renderWalletDashboard()
    }

    private fun renderLocalPaymentReceiveTarget(target: NativeWalletPaymentReceiveTarget) {
        localPaymentReceiveTarget = target
        paymentReceiveView.text = localPaymentReceiveText(target)
    }

    private fun localPaymentReceiveText(target: NativeWalletPaymentReceiveTarget): String =
        getString(
            R.string.wallet_reads_receive_local,
            target.display,
            target.derivationIndex,
        )

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

    private fun deleteWalletFiles(): Boolean {
        val deleted = !ProcessWalletControllerRetirementFailures.blocks(walletStoragePath) &&
            deleteWalletDatabaseArtifacts(walletDatabaseFile)
        if (deleted) WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)
        return deleted
    }

    private fun walletNetworkCode(network: HandshakeNetwork): Int = when (network) {
        HandshakeNetwork.Mainnet -> NativeWalletBridge.NETWORK_MAINNET
        HandshakeNetwork.Testnet -> NativeWalletBridge.NETWORK_TESTNET
        HandshakeNetwork.Regtest -> NativeWalletBridge.NETWORK_REGTEST
    }

    private fun newWalletBirthday(network: HandshakeNetwork): Long = when (network) {
        // A newly generated seed cannot have a user-intended funding history
        // before creation. Starting at the reviewed local checkpoint avoids a
        // network-wide first scan while preserving full direct-peer authority.
        HandshakeNetwork.Mainnet -> HeaderSnapshotInstaller.SNAPSHOT_HEIGHT
        HandshakeNetwork.Testnet, HandshakeNetwork.Regtest -> SAFE_FULL_RESCAN_BIRTHDAY
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

    private fun hnsSendRecipientInput(): EditText = EditText(this).apply {
        hint = getString(R.string.wallet_send_recipient_hint)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_NEXT or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // Never silently truncate a pasted address into a different recipient.
        filters = emptyArray()
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setAutofillHints(null)
        setSingleLine(true)
        isSaveEnabled = false
        freezesText = false
    }

    private fun hnsSendAmountInput(hintResource: Int): EditText = EditText(this).apply {
        hint = getString(hintResource)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_NEXT or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
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

    private fun clearSendInputs() {
        sendRecipientInput.text?.let(::wipeEditable)
        sendAmountInput.text?.let(::wipeEditable)
        sendMaximumFeeInput.text?.let(::wipeEditable)
        sendRecipientInput.clearFocus()
        sendAmountInput.clearFocus()
        sendMaximumFeeInput.clearFocus()
    }

    private fun wipeEditable(editable: Editable) {
        // Android may reject a NUL replacement through an input filter and
        // shrink the buffer immediately. Replace the complete range at once
        // so a filter cannot invalidate a later per-character index.
        if (editable.isNotEmpty()) {
            editable.replace(0, editable.length, NUL_CHARACTER.repeat(editable.length))
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
        const val TAG = "WalletActivity"
        const val INVALID_HANDLE = 0L
        const val DATABASE_KEY_BYTES = 32
        const val MAX_RECOVERY_CHARACTERS = 256
        const val SAFE_FULL_RESCAN_BIRTHDAY = 0L
        const val MAX_VISIBLE_READ_ITEMS = 20
        const val MAX_SEND_RECIPIENT_BYTES = 512
        const val MAX_VALUE_ACTION_INPUT_CHARACTERS = 512
        const val DEFAULT_LISTING_LIFETIME_SECONDS = 7 * 24 * 60 * 60L
        const val DEFAULT_OFFER_PAGE_SIZE = 32
        const val DIRECT_DENUO_FOREGROUND_TICK_MILLIS = 250L
        const val DIRECT_DENUO_STATUS_REFRESH_TICKS = 20
        const val DIRECT_DENUO_LISTEN_PORT = 12_038
        const val LIVE_HNS_SYNC_PROGRESS_POLL_MILLIS = 500L
        const val HNS_CATCHUP_RETRY_DELAY_MILLIS = 2_000L
        const val DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC = 32
        const val DIRECT_HNS_MAX_HEADER_AGREEMENT_RECOVERIES_PER_SYNC = 5
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
