package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.denuoweb.hnsdane.BuildConfig
import com.denuoweb.hnsdane.HnsDaneApplication
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.HeaderSnapshotInstaller
import com.denuoweb.hnsdane.net.HnsSyncProgress
import com.denuoweb.hnsdane.wallet.AndroidWalletKeyStore
import com.denuoweb.hnsdane.wallet.DirectHnsSynchronizationJournalStart
import com.denuoweb.hnsdane.wallet.NativeHnsSendApproval
import com.denuoweb.hnsdane.wallet.NativeHnsValueApproval
import com.denuoweb.hnsdane.wallet.NativeHnsValueApprovalKind
import com.denuoweb.hnsdane.wallet.NativeHnsValueIntent
import com.denuoweb.hnsdane.wallet.NativeBitcoinSendApproval
import com.denuoweb.hnsdane.wallet.NativeBitcoinSendPreparationFailure
import com.denuoweb.hnsdane.wallet.NativeBitcoinHtlcFundingApproval
import com.denuoweb.hnsdane.wallet.NativeBitcoinSyncProgress
import com.denuoweb.hnsdane.wallet.NativeBtcForHnsOfferApproval
import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary
import com.denuoweb.hnsdane.wallet.NativeHnsHtlcFundingApproval
import com.denuoweb.hnsdane.wallet.NativeSwapSettlementApproval
import com.denuoweb.hnsdane.wallet.NativeShakedexQuery
import com.denuoweb.hnsdane.wallet.NativeWalletDirectShakescapeConnectResult
import com.denuoweb.hnsdane.wallet.directShakescapeControls
import com.denuoweb.hnsdane.wallet.NativeWalletBridge
import com.denuoweb.hnsdane.wallet.NativeWalletHnsCatchupProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsLiveSyncProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsSynchronization
import com.denuoweb.hnsdane.wallet.NativeWalletName
import com.denuoweb.hnsdane.wallet.NativeWalletPaymentReceiveTarget
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.NativeWalletTransaction
import com.denuoweb.hnsdane.wallet.HandshakePaymentRequest
import com.denuoweb.hnsdane.wallet.HandshakePaymentUri
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.denuoweb.hnsdane.wallet.MAX_HNS_BIRTHDAY_HEIGHT
import com.denuoweb.hnsdane.wallet.ProcessWalletControllerRetirementFailures
import com.denuoweb.hnsdane.wallet.ProcessWalletStorageOwnership
import com.denuoweb.hnsdane.wallet.WALLET_DATABASE_FILE_NAME
import com.denuoweb.hnsdane.wallet.WALLET_DELETE_CONFIRMATION
import com.denuoweb.hnsdane.wallet.WalletDeletionScope
import com.denuoweb.hnsdane.wallet.WalletDashboardMode
import com.denuoweb.hnsdane.wallet.WalletLeaseReleaseHandoff
import com.denuoweb.hnsdane.wallet.WalletHnsJourney
import com.denuoweb.hnsdane.wallet.WalletHnsLiveSyncPresentation
import com.denuoweb.hnsdane.wallet.WalletHnsLiveSyncPresentationCache
import com.denuoweb.hnsdane.wallet.WalletHnsLiveSyncPresentationLease
import com.denuoweb.hnsdane.wallet.walletHnsPresentationMayAcquireStorage
import com.denuoweb.hnsdane.wallet.WalletNameImportState
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapAuthority
import com.denuoweb.hnsdane.wallet.WalletReadBootstrapState
import com.denuoweb.hnsdane.wallet.WalletStorageDeletionResult
import com.denuoweb.hnsdane.wallet.WalletStorageOwnershipGate
import com.denuoweb.hnsdane.wallet.WalletSyncForegroundService
import com.denuoweb.hnsdane.wallet.beginDirectHnsSynchronizationWithRecovery
import com.denuoweb.hnsdane.wallet.closeWalletControllerForDeletion
import com.denuoweb.hnsdane.wallet.deleteConfirmedWalletStorage
import com.denuoweb.hnsdane.wallet.deleteWalletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.exactWalletNameUtf8
import com.denuoweb.hnsdane.wallet.displayAmount
import com.denuoweb.hnsdane.wallet.formatHnsBaseUnits
import com.denuoweb.hnsdane.wallet.hnsBalanceProjection
import com.denuoweb.hnsdane.wallet.hnsReceiveTargets
import com.denuoweb.hnsdane.wallet.parsePositiveHnsToBaseUnits
import com.denuoweb.hnsdane.wallet.parseWalletRestoreBirthday
import com.denuoweb.hnsdane.wallet.walletDeleteConfirmationMatches
import com.denuoweb.hnsdane.wallet.walletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.walletDashboardMode
import com.denuoweb.hnsdane.wallet.walletControllerOperationMayBegin
import com.denuoweb.hnsdane.wallet.walletDeletionMayProceed
import com.denuoweb.hnsdane.wallet.walletNameImportMayBegin
import com.denuoweb.hnsdane.wallet.walletNameImportMayPublish
import com.denuoweb.hnsdane.wallet.walletPendingOutgoingRefreshHeight
import com.denuoweb.hnsdane.wallet.walletBackgroundHnsSyncMayRetain
import com.denuoweb.hnsdane.wallet.walletReadMayPublish
import com.denuoweb.hnsdane.wallet.walletReadBootstrapMayInstall
import com.denuoweb.hnsdane.wallet.walletReadCodeLabel
import com.denuoweb.hnsdane.wallet.walletTransactionStatusLabel
import com.denuoweb.hnsdane.wallet.walletSetupMayInspectStorage
import com.denuoweb.hnsdane.wallet.walletStorageNamespace
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import kotlin.concurrent.thread
import kotlin.math.ceil

/**
 * The native direct-wallet installer determines whether a wallet actually
 * consumes this stream from its persisted birthday.  The Android shell cannot
 * safely infer that birthday, so Mainnet must make the pinned stream available
 * on every installation; native ignores it for recovery wallets that retain a
 * different honest birthday.
 */
internal fun walletDirectHnsNeedsGenesisBootstrap(network: HandshakeNetwork): Boolean =
    network == HandshakeNetwork.Mainnet

/**
 * A newly generated seed cannot have wallet activity before the instant it is
 * created. Prefer the process-owned browser's authenticated current height so
 * the independent wallet scans only blocks that could contain activity for
 * that seed. The bundled mainnet checkpoint remains a fail-safe when no fresh,
 * authoritative browser height has been observed yet; it is not otherwise the
 * wallet birthday.
 */
internal fun newWalletBirthdayHeight(
    network: HandshakeNetwork,
    verifiedHeaderHeight: Long?,
): Long {
    val verified = verifiedHeaderHeight?.takeIf { it in 1..MAX_HNS_BIRTHDAY_HEIGHT }
    return when (network) {
        HandshakeNetwork.Mainnet -> maxOf(
            HeaderSnapshotInstaller.SNAPSHOT_HEIGHT,
            verified ?: HeaderSnapshotInstaller.SNAPSHOT_HEIGHT,
        )
        HandshakeNetwork.Testnet, HandshakeNetwork.Regtest ->
            verified ?: 0L
    }
}

internal const val EXTRA_HANDSHAKE_PAYMENT_URI =
    "com.denuoweb.hnsdane.extra.HANDSHAKE_PAYMENT_URI"

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
    private var directShakescapeWorkerHandle: Long = INVALID_HANDLE
    private var nameImportInput: EditText? = null
    private lateinit var nameImportStatusView: TextView
    // Send-form inputs belong to one dialog instance. Retaining and reusing a
    // view after its dialog is dismissed would leave it parented and makes a
    // later Review Send attempt crash when Android attaches it again.
    private var sendRecipientInput: EditText? = null
    private var sendAmountInput: EditText? = null
    private var sendMaximumFeeInput: EditText? = null
    private lateinit var sendStatusView: TextView
    private lateinit var bitcoinStatusView: TextView
    private lateinit var bitcoinBalanceView: TextView
    private lateinit var bitcoinReceiveView: TextView
    private lateinit var valueActionStatusView: TextView
    private lateinit var shakedexQueryStatusView: TextView
    private lateinit var directShakescapeStatusView: TextView
    private var restoreInput: EditText? = null
    private lateinit var recoveryView: RecoveryPhraseView
    private lateinit var dashboardContent: LinearLayout
    @Volatile
    private var walletHandle = INVALID_HANDLE
    private var walletAuthorityGeneration = 0L
    private var walletControllerIsReopenedDurable = false
    private var busy = false
        set(value) {
            if (field == value) return
            field = value
            // Operation callbacks update their final status text after
            // clearing this flag. Redraw on the next UI turn so every action
            // disabled by beginOperation is reliably restored even on error
            // branches that historically updated only a summary label.
            if (!value && ::dashboardContent.isInitialized) {
                dashboardContent.post {
                    if (!busy && foreground && !isFinishing && !isDestroyed) {
                        renderWalletDashboard()
                    }
                }
            }
        }
    private var lifecycleEpoch = 0L
    private var foreground = false
    // Browsing remote content is a security-boundary transition, not an
    // ordinary configuration change. Do not leave an idle signing-capable
    // controller alive behind MainActivity merely for faster Back navigation.
    // A user-started, visibly notified read-only sync is the only exception.
    private var browserNavigationRequested = false
    // A confirmed, unlocked direct wallet may keep only a user-initiated,
    // read-only direct-peer scan alive while the user moves between screens or
    // briefly leaves the app. Android keeps that narrow exception visible with
    // a data-sync foreground-service notification; all mutations still tear
    // down immediately on stop.
    private var retainingInAppWalletSession = false
    private var unconfirmedDatabaseKey: ByteArray? = null
    private var storageOwner: WalletStorageOwnershipGate.Owner? = null
    private var storageLease: WalletStorageOwnershipGate.Lease? = null
    private var walletDeletionDialog: AlertDialog? = null
    private var sendFormDialog: AlertDialog? = null
    private var sendApprovalDialog: AlertDialog? = null
    private var pendingSendApproval: NativeHnsSendApproval? = null
    private var valueApprovalDialog: AlertDialog? = null
    private var pendingValueApproval: NativeHnsValueApproval? = null
    private var latestReadSnapshot: NativeWalletReadSnapshot? = null
    private var loadedTrackedNames: List<NativeWalletName> = emptyList()
    private var trackedNamePageOffset: Int = 0
    private var recentActivityPageOffset: Int = 0
    private var pendingHandshakePayment: HandshakePaymentRequest? = null
    private var pendingPaymentPresentationScheduled = false
    private var scannedPaymentShouldResumeAfterUnlock = false
    private var browserSyncObservation: Closeable? = null
    private var pendingOutgoingSnapshotHeight: Long? = null
    private var pendingOutgoingRefreshAttemptedHeight: Long? = null
    private var latestObservedBrowserHeaderHeight: Long? = null
    private var pendingQrBitmap: Bitmap? = null
    private val handshakeQrScanner = registerForActivityResult(ScanContract()) { result ->
        val request = result.contents?.let(HandshakePaymentUri::parse)
        if (result.contents == null) {
            return@registerForActivityResult
        }
        if (request == null) {
            Toast.makeText(this, R.string.wallet_qr_invalid, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        pendingHandshakePayment = request
        scannedPaymentShouldResumeAfterUnlock = true
        schedulePendingPaymentPresentation()
    }
    private val saveQrCode = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val bitmap = pendingQrBitmap
        pendingQrBitmap = null
        if (uri != null && bitmap != null) {
            runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } ?: error("QR output could not be opened")
            }.onFailure {
                Toast.makeText(this, R.string.wallet_qr_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
        bitmap?.recycle()
    }
    private val bulkNameFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importWalletNamesFromFile(uri)
    }
    // This is wallet-local public output. It exists only while this exact
    // native controller stays unlocked and never substitutes for a synced
    // balance, history, name, or spend projection.
    private var localPaymentReceiveTarget: NativeWalletPaymentReceiveTarget? = null
    private var latestReadSnapshotHandle = INVALID_HANDLE
    private var latestReadSnapshotAuthorityGeneration = 0L
    private var latestReadSnapshotEpoch = 0L
    private var walletHnsSyncInProgress = false
    private var walletBitcoinSyncInProgress = false
    private var bitcoinSnapshot: com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot? = null
    private var bitcoinSyncStopRequested = false
    private var bitcoinBirthdayResetInProgress = false
    @Volatile
    private var bitcoinSyncProgressWatcher: AtomicBoolean? = null
    private var walletForegroundSyncServiceActive = false
    @Volatile
    private var liveHnsSyncPoller: AtomicBoolean? = null
    @Volatile
    private var cachedHnsSyncPresentationWatcher: AtomicBoolean? = null
    @Volatile
    private var hnsCatchupRetry: AtomicBoolean? = null
    @Volatile
    private var walletBackgroundRetirement: AtomicBoolean? = null
    private var durableWalletStoragePresent = false
    private var walletOpenDeferredUntilDeviceUnlock = false
    private var walletUnlockRequested = false
    private val walletHnsJourney = WalletHnsJourney()
    private val leaseReleaseHandoff = WalletLeaseReleaseHandoff()

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.clear()
        super.onCreate(null)
        // Production wallets must not leak recovery material, addresses, or
        // balances through screenshots and screen recording. Debug builds are
        // intentionally capturable so their UI and synchronization behavior
        // can be documented and diagnosed on a development device.
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        walletNetwork = HnsResolutionPreferences.handshakeNetwork(this)
        consumeHandshakePaymentIntent(intent)
        val storageNamespace = walletStorageNamespace(walletNetwork.id)
        walletDatabaseFile = File(
            File(noBackupFilesDir, storageNamespace.directoryName),
            WALLET_DATABASE_FILE_NAME,
        ).absoluteFile
        walletStoragePath = walletDatabaseFile.path
        // A native controller is deliberately retired whenever the app goes
        // to the background, but its absence must never make a durable wallet
        // look like a first-run setup. The storage lease performs the full
        // key/file reconciliation before this hint can authorize any action.
        durableWalletStoragePresent = walletDatabaseFile.exists()
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
        nameImportStatusView = walletReadSummary(R.string.wallet_name_import_unavailable)
        sendStatusView = walletReadSummary(R.string.wallet_send_unavailable)
        bitcoinStatusView = walletReadSummary(R.string.wallet_bitcoin_unavailable)
        bitcoinBalanceView = walletReadSummary(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView = walletReadSummary(R.string.wallet_bitcoin_receive_unavailable).apply {
            setTextIsSelectable(true)
        }
        valueActionStatusView = walletReadSummary(R.string.wallet_value_actions_unavailable)
        shakedexQueryStatusView = walletReadSummary(R.string.wallet_shakedex_queries_unavailable)
        directShakescapeStatusView = walletReadSummary(R.string.wallet_direct_shakescape_unavailable)
        recoveryView = RecoveryPhraseView(this)
        dashboardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setSettingsScreen(
            title = getString(R.string.screen_wallet),
            onPullDownAtTop = ::pullToSynchronizeWalletReads,
        ) {
            addView(dashboardContent)
        }
        // Back returns to the browser by reordering Main above this Activity.
        // A user-started read-only synchronization may continue under the
        // visible foreground service, while idle signing authority is retired
        // before attacker-controlled website content remains visible.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                browserNavigationRequested = true
                startActivity(
                    Intent(this@WalletActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_RETURN_TO_BACKGROUND_AFTER_BROWSER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        })
        renderWalletDashboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (
            hasFocus && walletOpenDeferredUntilDeviceUnlock && foreground && !busy &&
            walletHandle == INVALID_HANDLE && durableWalletStoragePresent &&
            currentStorageLease() != null
        ) {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard?.isDeviceLocked == false) {
                walletOpenDeferredUntilDeviceUnlock = false
                openExistingWallet()
            }
        }
        if (hasFocus) schedulePendingPaymentPresentation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeHandshakePaymentIntent(intent)
        schedulePendingPaymentPresentation()
    }

    private fun consumeHandshakePaymentIntent(intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_HANDSHAKE_PAYMENT_URI) ?: return
        intent.removeExtra(EXTRA_HANDSHAKE_PAYMENT_URI)
        val request = HandshakePaymentUri.parse(raw)
        if (request == null) {
            Toast.makeText(this, R.string.wallet_qr_invalid, Toast.LENGTH_LONG).show()
        } else {
            pendingHandshakePayment = request
        }
    }

    private fun schedulePendingPaymentPresentation() {
        if (pendingHandshakePayment == null || pendingPaymentPresentationScheduled) return
        pendingPaymentPresentationScheduled = true
        window.decorView.post {
            pendingPaymentPresentationScheduled = false
            val handle = walletHandle
            val dialogVisible = walletDeletionDialog?.isShowing == true ||
                sendFormDialog?.isShowing == true || sendApprovalDialog?.isShowing == true ||
                valueApprovalDialog?.isShowing == true
            val controllerUnlocked = if (busy || handle == INVALID_HANDLE) {
                false
            } else {
                NativeWalletBridge.status(handle)?.locked == false
            }
            val controllerMayBeInspected = !busy && handle != INVALID_HANDLE
            when (walletPendingPaymentContinuation(
                hasPendingPayment = pendingHandshakePayment != null,
                resumeAfterScanner = scannedPaymentShouldResumeAfterUnlock,
                foreground = foreground,
                windowHasFocus = window.decorView.hasWindowFocus(),
                busy = busy || walletHnsSyncInProgress,
                dialogVisible = dialogVisible,
                hasController = handle != INVALID_HANDLE,
                controllerUnlocked = controllerUnlocked,
                hasHnsValue = controllerMayBeInspected && NativeWalletBridge.hasHnsValue(handle),
                hasCurrentSnapshot = controllerMayBeInspected && hasCurrentWalletReadSnapshot(handle),
                hasPendingOutgoing = pendingOutgoingSnapshotHeight != null,
            )) {
                WalletPendingPaymentContinuation.None,
                WalletPendingPaymentContinuation.Wait -> Unit

                WalletPendingPaymentContinuation.Unlock -> requestWalletUnlock()
                WalletPendingPaymentContinuation.Synchronize -> synchronizeWalletReads()
                WalletPendingPaymentContinuation.Present -> {
                    val request = pendingHandshakePayment ?: return@post
                    pendingHandshakePayment = null
                    scannedPaymentShouldResumeAfterUnlock = false
                    showHnsSendDialog(request)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        browserNavigationRequested = false
        startPendingOutgoingRefreshObserver()
        walletBackgroundRetirement?.set(false)
        walletBackgroundRetirement = null
        if (retainingInAppWalletSession && currentStorageLease() != null && walletHandle != INVALID_HANDLE) {
            Log.i(TAG, "Resuming retained direct wallet sync on the existing WalletActivity")
            // Keep the controller, peer sessions, and retry generation that
            // were already progressing while another app screen was visible.
            // In particular, do not advance lifecycleEpoch here: an in-flight
            // bounded scan must remain authorized to publish its checkpoint.
            retainingInAppWalletSession = false
            restoreCachedHnsSyncPresentation()
            // Do not probe status() while a bounded scan owns the native
            // controller mutex. Its public progress mailbox is enough to
            // redraw this screen, and a contended probe can otherwise turn a
            // healthy background scan into an unavailable-looking dashboard.
            if (walletHnsSyncInProgress || walletBitcoinSyncInProgress) {
                renderWalletDashboard()
                return
            }
            // The retained authority generation is unchanged, so keep a
            // verified snapshot that finished while another app screen was
            // visible instead of resetting it to a misleading empty state.
            refreshControllerState(resetReads = false)
            return
        }
        Log.i(TAG, "Starting a WalletActivity storage session; no retained direct HNS controller is available")
        retainingInAppWalletSession = false
        lifecycleEpoch += 1
        resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        restoreCachedHnsSyncPresentation()
        renderWalletDashboard()
        startCachedHnsSyncPresentationWatcher()
        beginStorageOwnershipSessionIfReady()
    }

    /**
     * A task removed from Recents destroys its WalletActivity even though the
     * user-started foreground scan is intentionally still running. A newly
     * launched task must observe that public scan presentation without first
     * creating a newer storage owner: owner creation revokes the old activity
     * and would detach its native progress mailbox while the scan continues.
     */
    private fun beginStorageOwnershipSessionIfReady() {
        if (
            !foreground || storageOwner != null || walletHandle != INVALID_HANDLE ||
                isFinishing || isDestroyed ||
                !walletHnsPresentationMayAcquireStorage(
                    WalletHnsLiveSyncPresentationCache.latest(walletNetwork.id),
                )
        ) return
        lateinit var owner: WalletStorageOwnershipGate.Owner
        owner = ProcessWalletStorageOwnership.newOwner(walletStoragePath) {
            runOnUiThread { revokeStorageOwnership(owner) }
        }
        storageOwner = owner
        requestStorageLease(owner)
    }

    override fun onStop() {
        val retainInAppSession = mayRetainInAppWalletSession()
        Log.i(
            TAG,
            "Stopping WalletActivity: retainDirectWallet=$retainInAppSession " +
                "finishing=$isFinishing destroyed=$isDestroyed " +
                "hnsSync=$walletHnsSyncInProgress bitcoinSync=$walletBitcoinSyncInProgress " +
                "busy=$busy",
        )
        foreground = false
        browserSyncObservation?.close()
        browserSyncObservation = null
        // An Unlock tap may be queued while the durable controller is still
        // reopening. Never carry that user-presence request off this screen.
        walletUnlockRequested = false
        cachedHnsSyncPresentationWatcher?.set(false)
        if (retainInAppSession) {
            // Android calls the departing Activity's onStop before it reports
            // whether the process has actually gone background. Preserve the
            // current lease across an in-app transition, then retire it after
            // that lifecycle report only if no app Activity remains visible.
            retainingInAppWalletSession = true
            dismissWalletDeletionDialog()
            dismissSendApproval(rejectNative = false)
            dismissValueApproval(rejectNative = false)
            clearNameImportInput()
            clearSendInputs()
            scheduleWalletRetirementIfApplicationBackgrounds()
            super.onStop()
            return
        }
        lifecycleEpoch += 1
        retainingInAppWalletSession = false
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        stopWalletForegroundSyncService()
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
        if (!busy && !walletBitcoinSyncInProgress && lease != null) {
            releaseStorageLeaseAfterOperation(lease)
        }
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
        listOf(statusView, readStatusView, balanceView, sendStatusView).forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        // RecoveryPhraseView clears its secret whenever it leaves the real
        // wallet screen. A dashboard redraw also detaches and immediately
        // reattaches reusable children, so mark that one synchronous detach as
        // a reparent before rebuilding the hierarchy. Otherwise the queued
        // redraw after wallet creation erases the phrase before the user can
        // record it.
        recoveryView.detachForDashboardReparent()
        dashboardContent.removeAllViews()
        val hasController = walletHandle != INVALID_HANDLE
        // A direct peer synchronization owns the native controller for a
        // bounded network round. Its public progress has a separate mailbox,
        // so never wait for its status mutex on Android's main thread.
        val hnsSynchronizationActive = hasActiveWalletHnsSynchronization()
        val controllerUnlocked = hasController && (
            hnsSynchronizationActive || NativeWalletBridge.status(walletHandle)?.locked == false
        )
        when (
            walletDashboardMode(
                hasUnconfirmedRecovery =
                    unconfirmedDatabaseKey != null || recoveryView.hasSecret(),
                hasRetainedSynchronization = hasRetainedHnsSyncPresentation(),
                hasController = hasController,
                hasDurableWalletStorage = durableWalletStoragePresent,
                synchronizationInProgress = hnsSynchronizationActive,
                controllerUnlocked = controllerUnlocked,
            )
        ) {
            WalletDashboardMode.Recovery -> renderRecoveryDashboard()
            WalletDashboardMode.RetainedSynchronization ->
                renderRetainedHnsSyncHandoffDashboard()
            WalletDashboardMode.NoWallet -> renderNoWalletDashboard()
            WalletDashboardMode.LockedWallet -> renderLockedWalletDashboard()
            WalletDashboardMode.UnlockedWallet ->
                renderUnlockedWalletDashboard(
                    actionsAvailable = !busy && !hnsSynchronizationActive,
                    synchronizationInProgress = hnsSynchronizationActive,
                )
        }
        if (pendingHandshakePayment != null) schedulePendingPaymentPresentation()
    }

    private fun renderNoWalletDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_no_wallet),
            detail = statusView,
            healthy = false,
            inProgress = busy,
        ))
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_get_started)) {
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_create),
                summary = getString(R.string.wallet_dashboard_create_summary),
            ) { createWallet() }.disabledWhenWalletHandoff(busy))
            addSettingsRow(navRow(
                title = getString(R.string.row_wallet_restore),
                summary = getString(R.string.wallet_dashboard_restore_summary),
            ) { showRestoreWalletDialog() }.disabledWhenWalletHandoff(busy))
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
        renderUnlockedWalletDashboard(
            actionsAvailable = false,
            synchronizationInProgress = true,
        )
        if (WalletHnsLiveSyncPresentationCache.canRequestCancellation(walletNetwork.id)) {
            dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_wallet)) {
                addSettingsRow(actionRow(
                    title = getString(R.string.action_stop_wallet_sync),
                    summary = getString(R.string.wallet_stop_sync_summary),
                ) { requestHnsSyncCancellation() })
            })
        }
    }

    private fun hasRetainedHnsSyncPresentation(): Boolean =
        walletHandle == INVALID_HANDLE &&
            WalletHnsLiveSyncPresentationCache.latest(walletNetwork.id) != null

    private fun renderRecoveryDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_dashboard_recovery_phrase),
            detail = statusView,
            healthy = false,
            inProgress = busy,
        ))
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_recovery_phrase)) {
            addView(recoveryView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_recovery_confirm),
                summary = getString(R.string.wallet_dashboard_recovery_summary),
            ) { confirmRecoverySaved() }.disabledWhenWalletHandoff(busy))
        })
    }

    private fun renderLockedWalletDashboard() {
        dashboardContent.addView(statusCard(
            label = getString(
                if (busy) R.string.wallet_dashboard_working else R.string.wallet_dashboard_locked,
                walletNetwork.displayName(this),
            ),
            detail = statusView,
            healthy = false,
            inProgress = busy,
        ))
        dashboardContent.addView(settingsGroup {
            addSettingsRow(actionRow(
                title = getString(R.string.row_wallet_unlock),
                summary = getString(R.string.wallet_dashboard_unlock_summary),
            ) { requestWalletUnlock() }.disabledWhenWalletHandoff(busy))
        })
        addWalletTiles(locked = true, actionsAvailable = !busy)
    }

    private fun renderUnlockedWalletDashboard(
        actionsAvailable: Boolean = true,
        synchronizationInProgress: Boolean = false,
    ) {
        dashboardContent.addView(statusCard(
            label = getString(
                if (synchronizationInProgress) {
                    R.string.wallet_dashboard_synchronizing
                } else {
                    R.string.wallet_dashboard_unlocked
                },
                walletNetwork.displayName(this),
            ),
            detail = statusView,
            inProgress = busy || synchronizationInProgress,
        ))
        dashboardContent.addView(walletBalanceCard(actionsAvailable))
        if (latestReadSnapshot == null || synchronizationInProgress) {
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
        if (actionsAvailable) schedulePendingPaymentPresentation()
    }

    private fun walletBalanceCard(actionsAvailable: Boolean = true): LinearLayout =
        LinearLayout(this).apply {
            val paymentActionsAvailable =
                walletHnsPaymentActionsAvailable(
                    actionsAvailable = actionsAvailable,
                    hasPendingOutgoing = pendingOutgoingSnapshotHeight != null,
                )
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
                // Spendable value and pending outgoing value are intentionally
                // separate rows while a send is unconfirmed.
                maxLines = Int.MAX_VALUE
                setTextColor(themeColors().primaryText)
                setPadding(0, uiDp(10), 0, uiDp(12))
            }
            addView(balanceView)
            addView(LinearLayout(this@WalletActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_receive)) {
                    showReceiveWalletDialog()
                }.apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_qr_code, 0, 0, 0)
                    compoundDrawablePadding = uiDp(5)
                    compoundDrawablesRelative.firstOrNull()?.setTint(themeColors().action)
                }.disabledWhenWalletHandoff(!paymentActionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_send), secondary = true) {
                    showHnsSendDialog()
                }.disabledWhenWalletHandoff(!paymentActionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = uiDp(8)
                })
                addView(dashboardActionButton(getString(R.string.wallet_dashboard_sync)) {
                    synchronizeWalletReads()
                }.disabledWhenWalletHandoff(!actionsAvailable), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = uiDp(8)
                })
            })
            // Send preparation deliberately leaves the form before its native
            // approval is ready. Keep its guarded progress and any
            // fail-closed result on the dashboard instead of making a tap
            // appear to do nothing.
            sendStatusView.apply {
                textSize = 13f
                setTextColor(themeColors().secondaryText)
                setPadding(0, uiDp(12), 0, 0)
            }
            addView(sendStatusView)
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
        val walletTile = dashboardTile(
            title = getString(R.string.wallet_dashboard_wallet),
            summary = if (locked) getString(R.string.wallet_dashboard_locked_short)
            else getString(R.string.wallet_dashboard_unlocked_short),
        ) { showWalletDetails() }.disabledWhenWalletHandoff(!actionsAvailable)
        dashboardContent.addView(walletTileRow(
            dashboardTile(
                title = getString(R.string.wallet_dashboard_names),
                summary = if (locked) getString(R.string.wallet_dashboard_locked_short)
                else namesSummary(),
            ) { showNamesDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
            walletTile,
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
        if (NativeWalletBridge.walletOwnedDirectShakescapeStatus(walletHandle)?.peerEndpoint != null) {
            getString(R.string.wallet_dashboard_connected)
        } else {
            getString(R.string.wallet_dashboard_not_connected)
        }

    private fun recentActivitySummary(): String = latestReadSnapshot?.transactions?.size?.let { count ->
        resources.getQuantityString(R.plurals.wallet_dashboard_transactions, count, count)
    } ?: getString(R.string.wallet_dashboard_no_synced_activity)

    private fun showRestoreWalletDialog() {
        val phraseInput = sensitiveRestoreInput()
        val birthdayInput = restoreBirthdayInput()
        restoreInput = phraseInput
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(20), uiDp(4), uiDp(20), 0)
            addView(phraseInput)
            addView(birthdayInput)
            addView(TextView(this@WalletActivity).apply {
                text = getString(R.string.wallet_restore_birthday_explanation)
                textSize = 13f
                setTextColor(themeColors().secondaryText)
                setPadding(0, uiDp(8), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.row_wallet_restore)
            .setMessage(R.string.wallet_dashboard_restore_dialog_summary)
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_restore_wallet, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val birthday = parseWalletRestoreBirthday(birthdayInput.text)
                if (birthday == null) {
                    birthdayInput.error = getString(R.string.wallet_restore_birthday_invalid)
                    return@setOnClickListener
                }
                birthdayInput.error = null
                val phrase = takeRestoreInput(phraseInput)
                if (restoreInput === phraseInput) restoreInput = null
                dialog.dismiss()
                restoreWallet(phrase, birthday)
            }
        }
        dialog.setOnDismissListener {
            if (restoreInput === phraseInput) clearRestoreInput()
        }
        dialog.show()
    }

    private fun showReceiveWalletDialog() {
        if (pendingOutgoingSnapshotHeight != null) {
            Toast.makeText(this, R.string.wallet_pending_outgoing_actions_disabled, Toast.LENGTH_LONG).show()
            return
        }
        val payment = latestReadSnapshot?.hnsReceiveTargets()?.paymentAddress
            ?: localPaymentReceiveTarget?.display
            ?: ""
        if (payment.isBlank()) {
            Toast.makeText(this, R.string.wallet_dashboard_address_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val paymentUri = "handshake:$payment"
        val qrBitmap = walletQrBitmap(paymentUri)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(24), uiDp(8), uiDp(24), 0)
            addView(ImageView(this@WalletActivity).apply {
                setImageBitmap(qrBitmap)
                contentDescription = getString(R.string.wallet_receive_qr_description)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                uiDp(240),
            ))
            addView(walletAddressDialogText(payment).apply { setPadding(0, uiDp(8), 0, uiDp(8)) })
            addView(dashboardActionButton(getString(R.string.wallet_dashboard_copy_address), secondary = true) {
                copyWalletAddress(payment, R.string.wallet_dashboard_receive)
            })
            addView(dashboardActionButton(getString(R.string.wallet_save_qr_code), secondary = true) {
                pendingQrBitmap?.recycle()
                pendingQrBitmap = walletQrBitmap(paymentUri)
                saveQrCode.launch("shakescape-hns-receive.png")
            })
            addView(dashboardActionButton(getString(R.string.wallet_dashboard_name_transfer), secondary = true) {
                showNameReceiveDialog()
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_receive)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnDismissListener { qrBitmap.recycle() }
        dialog.show()
    }

    private fun walletQrBitmap(value: String, size: Int = 768): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[(y * size) + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun showNameReceiveDialog() {
        val address = latestReadSnapshot?.hnsReceiveTargets()?.nameTransferAddress.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_name_transfer)
            .setView(walletAddressDialogText(address))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.wallet_dashboard_copy_address) { _, _ ->
                copyWalletAddress(address, R.string.wallet_dashboard_name_transfer)
            }
            .show()
    }

    private fun walletAddressDialogText(address: String): TextView = TextView(this).apply {
        text = address
        gravity = Gravity.CENTER
        isSingleLine = true
        maxLines = 1
        ellipsize = null
        setHorizontallyScrolling(false)
        setTextIsSelectable(true)
        setTextColor(themeColors().primaryText)
        typeface = Typeface.MONOSPACE
        setAutoSizeTextTypeUniformWithConfiguration(
            8,
            18,
            1,
            TypedValue.COMPLEX_UNIT_SP,
        )
        setPadding(uiDp(24), uiDp(8), uiDp(24), uiDp(8))
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

    private fun showHnsSendDialog(prefill: HandshakePaymentRequest? = null) {
        if (pendingOutgoingSnapshotHeight != null) {
            Toast.makeText(this, R.string.wallet_pending_outgoing_actions_disabled, Toast.LENGTH_LONG).show()
            return
        }
        val recipientInput = hnsSendRecipientInput()
        val amountInput = hnsSendAmountInput(R.string.wallet_send_amount_hint)
        val maximumFeeInput = hnsSendAmountInput(R.string.wallet_send_maximum_fee_hint).apply {
            setText(DEFAULT_HNS_MAXIMUM_FEE)
        }
        prefill?.let { request ->
            recipientInput.setText(request.address)
            request.amountHns?.let(amountInput::setText)
        }
        sendRecipientInput = recipientInput
        sendAmountInput = amountInput
        sendMaximumFeeInput = maximumFeeInput
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(20), uiDp(4), uiDp(20), 0)
            addView(labeledWalletSendInput(R.string.wallet_send_recipient_label, recipientInput).apply {
                addView(dashboardActionButton(getString(R.string.wallet_scan_payment_qr), secondary = true) {
                    scanHandshakePaymentQr()
                }.apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_camera, 0, 0, 0)
                    compoundDrawablePadding = uiDp(5)
                    compoundDrawablesRelative.firstOrNull()?.setTint(themeColors().secondaryAction)
                })
            })
            addView(labeledWalletSendInput(R.string.wallet_send_amount_label, amountInput))
            addView(labeledWalletSendInput(R.string.wallet_send_maximum_fee_label, maximumFeeInput))
            addView(TextView(this@WalletActivity).apply {
                text = getString(R.string.wallet_dashboard_send_form_notice)
                textSize = 13f
                setTextColor(themeColors().secondaryText)
                setPadding(0, uiDp(8), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_send)
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            // Install the handler after show() so local validation can keep
            // this exact dialog (and its entered values) open. AlertDialog's
            // normal positive-button handler dismisses first, which turned a
            // correct fail-closed rejection into an apparent no-op.
            .setPositiveButton(R.string.action_prepare_wallet_send, null)
            .create()
        sendFormDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val request = validatedWalletHnsSendInput(
                    recipientInput,
                    amountInput,
                    maximumFeeInput,
                ) ?: return@setOnClickListener
                clearSendInputs()
                dialog.dismiss()
                prepareWalletSend(request)
            }
        }
        dialog.setOnDismissListener {
            if (sendFormDialog === dialog) sendFormDialog = null
            if (sendRecipientInput === recipientInput) clearSendInputs()
        }
        dialog.show()
    }

    private fun scanHandshakePaymentQr() {
        sendFormDialog?.dismiss()
        handshakeQrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.wallet_scan_payment_qr))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun labeledWalletSendInput(labelResource: Int, input: EditText): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            input.id = View.generateViewId()
            addView(TextView(this@WalletActivity).apply {
                text = getString(labelResource)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors().secondaryText)
                labelFor = input.id
                setPadding(0, uiDp(8), 0, 0)
            })
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

    private fun showWalletDetails() {
        if (WalletHnsLiveSyncPresentationCache.canRequestCancellation(walletNetwork.id)) {
            walletDetailDialog(
                title = getString(R.string.wallet_dashboard_wallet),
                rows = listOf(
                    getString(R.string.row_wallet_status) to statusView.text.toString(),
                    getString(R.string.row_wallet_account) to accountView.text.toString(),
                ),
                actions = listOf(
                    getString(R.string.action_stop_wallet_sync) to
                        ::requestHnsSyncCancellation,
                ),
            )
            return
        }
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

    private fun showActivityDetails() {
        val snapshot = latestReadSnapshot
        if (snapshot == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.wallet_dashboard_recent_activity)
                .setMessage(historyView.text)
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        val transactions = snapshot.transactions
        recentActivityPageOffset = walletPageOffset(
            requestedOffset = recentActivityPageOffset,
            totalItems = transactions.size,
            pageSize = MAX_VISIBLE_READ_ITEMS,
        )
        val page = transactions.drop(recentActivityPageOffset).take(MAX_VISIBLE_READ_ITEMS)
        val message = if (page.isEmpty()) {
            getString(R.string.wallet_reads_history_empty)
        } else {
            val first = recentActivityPageOffset + 1
            val last = recentActivityPageOffset + page.size
            getString(
                R.string.wallet_activity_page_position,
                first,
                last,
                transactions.size,
            ) + "\n\n" + formatWalletTransactions(page)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(24), uiDp(6), uiDp(24), 0)
            addView(TextView(this@WalletActivity).apply {
                text = message
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(themeColors().primaryText)
                setTextIsSelectable(true)
                setPadding(0, uiDp(8), 0, uiDp(12))
            })
            if (page.isNotEmpty()) {
                addView(dashboardActionButton(
                    getString(R.string.wallet_dashboard_copy_activity),
                    secondary = true,
                ) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(
                            getString(R.string.wallet_dashboard_recent_activity),
                            message,
                        ),
                    )
                    Toast.makeText(
                        this@WalletActivity,
                        R.string.common_copied,
                        Toast.LENGTH_SHORT,
                    ).show()
                })
            }
        }
        val scrollableContent = ScrollView(this).apply {
            addView(content)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_dashboard_recent_activity)
            .setView(scrollableContent)
            .setNegativeButton(R.string.action_cancel, null)
        if (recentActivityPageOffset > 0) {
            dialog.setNeutralButton(R.string.action_previous_wallet_activity) { _, _ ->
                recentActivityPageOffset -= MAX_VISIBLE_READ_ITEMS
                window.decorView.post(::showActivityDetails)
            }
        }
        if (recentActivityPageOffset + page.size < transactions.size) {
            dialog.setPositiveButton(R.string.action_next_wallet_activity) { _, _ ->
                recentActivityPageOffset += MAX_VISIBLE_READ_ITEMS
                window.decorView.post(::showActivityDetails)
            }
        }
        dialog.show()
    }

    private fun formatWalletTransactions(transactions: List<NativeWalletTransaction>): String =
        transactions.joinToString("\n\n") { transaction ->
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
                walletTransactionStatusLabel(transaction.status),
                transaction.displayAmount(),
                transaction.txid,
                chainPosition,
            )
        }

    private fun showNamesDashboard() {
        val snapshot = latestReadSnapshot
        val actions = buildList {
            add(getString(R.string.action_import_wallet_name) to ::showNameImportDialog)
            add(getString(R.string.action_import_wallet_names_file) to ::showBulkNameFilePicker)
            if (snapshot != null && trackedNamePageOffset > 0) {
                add(getString(R.string.action_previous_wallet_names) to ::loadPreviousWalletNamePage)
            }
            if (
                snapshot != null &&
                trackedNamePageOffset + loadedTrackedNames.size < snapshot.trackedNameCount
            ) {
                add(getString(R.string.action_load_more_wallet_names) to ::loadNextWalletNamePage)
            }
            add(getString(R.string.wallet_dashboard_name_actions) to ::showNameActionMenu)
        }
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_names),
            rows = listOf(
                getString(R.string.row_wallet_read_names) to trackedNamesView.text.toString(),
                getString(R.string.row_wallet_value_action_status) to valueActionStatusView.text.toString(),
            ),
            actions = actions,
        )
    }

    private fun showBulkNameFilePicker() {
        bulkNameFilePicker.launch(arrayOf("text/plain", "application/json", "text/csv"))
    }

    private fun importWalletNamesFromFile(uri: Uri) {
        nameImportStatusView.text = getString(R.string.wallet_name_bulk_import_reading)
        thread(name = "hns-wallet-name-file-read") {
            val names = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_BULK_NAME_FILE_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } finally {
                        buffer.fill(0)
                    }
                } ?: throw IllegalArgumentException("name file is unavailable")
                try {
                    val text = bytes.toString(Charsets.UTF_8)
                    require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                    parseBulkWalletNames(text)
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()
            runOnUiThread {
                if (names == null) {
                    nameImportStatusView.text = getString(R.string.wallet_name_bulk_import_invalid_file)
                } else {
                    importWalletNames(names)
                }
            }
        }
    }

    private fun parseBulkWalletNames(text: String): List<String> {
        val names = if (text.startsWith("[")) {
            val array = JSONArray(text)
            List(array.length()) { index -> array.getString(index) }
        } else {
            text.lineSequence().toList()
        }
        require(names.isNotEmpty() && names.size <= MAX_BULK_NAME_IMPORTS)
        require(names.toSet().size == names.size)
        require(names.all(::isExactWalletNameText))
        return names
    }

    private fun isExactWalletNameText(name: String): Boolean {
        val bytes = exactWalletNameUtf8(name) ?: return false
        return try {
            bytes.toString(Charsets.UTF_8) == name
        } finally {
            bytes.fill(0)
        }
    }

    private fun loadNextWalletNamePage() {
        loadWalletNamePage(trackedNamePageOffset + loadedTrackedNames.size)
    }

    private fun loadPreviousWalletNamePage() {
        loadWalletNamePage((trackedNamePageOffset - MAX_VISIBLE_READ_ITEMS).coerceAtLeast(0))
    }

    private fun loadWalletNamePage(offset: Int) {
        val snapshot = latestReadSnapshot ?: return
        val handle = walletHandle
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        if (offset !in 0 until snapshot.trackedNameCount) return
        thread(name = "hns-wallet-name-page") {
            val page = NativeWalletBridge.hnsNamePage(handle, offset, MAX_VISIBLE_READ_ITEMS)
            runOnUiThread {
                if (
                    page == null || page.offset != offset || page.total != snapshot.trackedNameCount ||
                    page.names.isEmpty() ||
                    handle != walletHandle || epoch != lifecycleEpoch ||
                    authorityGeneration != walletAuthorityGeneration
                ) {
                    Toast.makeText(this, R.string.wallet_name_page_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                trackedNamePageOffset = page.offset
                loadedTrackedNames = page.names
                renderLoadedTrackedNames(snapshot.trackedNameCount)
                showNamesDashboard()
            }
        }
    }

    private fun showNameImportDialog() {
        val input = exactNameImportInput()
        nameImportInput = input
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.row_wallet_name_import)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_import_wallet_name) { _, _ ->
                val exactUtf8 = input.text?.let(::exactWalletNameUtf8)
                clearNameImportInput()
                importWalletName(exactUtf8)
            }
            .create()
        dialog.setOnDismissListener {
            if (nameImportInput === input) clearNameImportInput()
        }
        dialog.show()
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
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.action_wallet_bitcoin_receive) to ::revealBitcoinReceiveAddress)
        if (walletBitcoinSyncInProgress) {
            actions.add(
                getString(R.string.action_stop_bitcoin_sync) to ::requestBitcoinSyncCancellation,
            )
        } else {
            actions.add(getString(R.string.action_sync_wallet_reads) to ::synchronizeBitcoin)
        }
        if (bitcoinBirthdayMayStart()) {
            actions.add(
                getString(R.string.action_wallet_bitcoin_birthday) to ::showBitcoinBirthdayForm,
            )
        }
        actions.add(getString(R.string.wallet_dashboard_send_bitcoin) to ::showBitcoinSendForm)
        actions.add(getString(R.string.wallet_swap_sell_btc) to ::showBtcForHnsOfferForm)
        actions.add(getString(R.string.wallet_swap_active_offers) to ::showActiveBtcForHnsOffers)
        actions.add(getString(R.string.wallet_swap_executions) to ::showShakescapeExecutions)
        walletLiveDetailDialog(
            title = getString(R.string.wallet_dashboard_bitcoin),
            rows = listOf(
                getString(R.string.row_wallet_bitcoin_status) to bitcoinStatusView,
                getString(R.string.row_wallet_bitcoin_balance) to bitcoinBalanceView,
                getString(R.string.row_wallet_bitcoin_receive) to bitcoinReceiveView,
            ),
            actions = actions,
        )
    }

    /**
     * Bitcoin synchronization reports progress independently from the HNS UI
     * operation gate. Retain its actual projection views in this detail sheet
     * so a long Kyoto scan updates the visible dialog instead of changing only
     * a frozen backing value behind it.
     */
    private fun walletLiveDetailDialog(
        title: String,
        rows: List<Pair<String, TextView>>,
        actions: List<Pair<String, () -> Unit>>,
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(24), uiDp(6), uiDp(24), 0)
            rows.forEach { (label, detail) ->
                (detail.parent as? ViewGroup)?.removeView(detail)
                addView(TextView(this@WalletActivity).apply {
                    text = label
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColors().primaryText)
                    setPadding(0, uiDp(8), 0, 0)
                })
                addView(detail.apply {
                    textSize = 14f
                    setTextColor(themeColors().primaryText)
                    setPadding(0, uiDp(3), 0, uiDp(12))
                })
            }
            actions.forEach { (label, action) ->
                addView(
                    dashboardActionButton(label, secondary = true, action = action),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(8) },
                )
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnDismissListener {
            rows.forEach { (_, detail) ->
                (detail.parent as? ViewGroup)?.removeView(detail)
            }
        }
        dialog.show()
    }

    private fun showShakedexDashboard() {
        val transport = NativeWalletBridge.walletOwnedDirectShakescapeStatus(walletHandle)
        val transportControls = directShakescapeControls(transport)
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.row_wallet_list_offers) to ::showListOffersForm,
            getString(R.string.row_wallet_accept_offer) to ::showAcceptOfferForm,
            getString(R.string.row_wallet_finalize_purchase) to ::showFinalizePurchaseForm,
            getString(R.string.row_wallet_pair_direct_shakescape) to ::showPairDirectShakescapeForm,
            getString(R.string.row_wallet_get_session) to ::showGetSessionForm,
        ).apply {
            if (transportControls.retryListener) {
                add(
                    getString(R.string.row_wallet_retry_direct_shakescape_host) to
                        ::retryWalletOwnedDirectShakescapeListener,
                )
            }
            if (transportControls.disconnectPeer) {
                add(
                    getString(R.string.row_wallet_disconnect_direct_shakescape) to
                        ::disconnectWalletOwnedDirectShakescape,
                )
            }
        }
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_shakedex),
            rows = listOf(
                getString(R.string.row_wallet_direct_shakescape_host) to directShakescapeStatusView.text.toString(),
                getString(R.string.row_wallet_shakedex_status) to shakedexQueryStatusView.text.toString(),
            ),
            actions = actions,
        )
    }

    private fun walletDetailDialog(
        title: String,
        rows: List<Pair<String, String>>,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        lateinit var dialog: AlertDialog
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
                addView(
                    dashboardActionButton(label, secondary = true) {
                        // The operation status belongs to the dashboard. Do
                        // not leave a stale detail sheet covering its working
                        // state after an action such as Unlock begins.
                        dialog.dismiss()
                        action()
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(8) },
                )
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.show()
    }

    private fun openExistingWallet() {
        val lease = currentStorageLease() ?: return
        if (!beginOperation(lease, getString(R.string.wallet_status_opening))) return
        walletOpenDeferredUntilDeviceUnlock = false
        val epoch = lifecycleEpoch
        val path = walletDatabaseFile.absolutePath
        thread(name = "hns-wallet-open") {
            var databaseKeyAvailable = false
            val opened = runCatching {
                keyStore.withDatabaseKey { key ->
                    databaseKeyAvailable = true
                    NativeWalletBridge.open(path, key)
                } ?: INVALID_HANDLE
            }.getOrDefault(INVALID_HANDLE)
            runOnUiThread {
                busy = false
                if (!operationIsCurrent(epoch, lease)) {
                    destroyWalletController(opened)
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (opened == INVALID_HANDLE) {
                    durableWalletStoragePresent = true
                    walletOpenDeferredUntilDeviceUnlock = !databaseKeyAvailable
                    if (databaseKeyAvailable) walletUnlockRequested = false
                    statusView.text = getString(
                        if (databaseKeyAvailable) {
                            R.string.wallet_status_open_failed
                        } else {
                            R.string.wallet_status_device_locked
                        },
                    )
                    accountView.text = getString(R.string.wallet_account_locked)
                    resetReadProjection(R.string.wallet_reads_locked)
                    renderWalletDashboard()
                } else {
                    walletOpenDeferredUntilDeviceUnlock = false
                    durableWalletStoragePresent = true
                    publishWalletController(opened, reopenedDurable = true)
                    // Publish the ordinary locked state first, then let the
                    // asynchronous direct-controller preparation replace it
                    // with its WORKING presentation. Calling refresh after
                    // preparation began used to overwrite that progress with
                    // "Signing authority is unavailable" while Unlock was
                    // still disabled.
                    refreshControllerState()
                    attemptReadBootstrap(lease)
                    runPendingWalletUnlockIfReady()
                }
            }
        }
    }

    /**
     * Preserve an explicit Unlock tap made while the durable wallet controller
     * or its direct-HNS bootstrap is still opening. Previously the visible
     * Unlock row called [openExistingWallet] a second time and the busy guard
     * silently discarded the tap, forcing the user to wait and tap again.
     */
    private fun requestWalletUnlock() {
        walletUnlockRequested = true
        runPendingWalletUnlockIfReady()
        if (walletHandle == INVALID_HANDLE) {
            beginStorageOwnershipSessionIfReady()
            if (!busy && currentStorageLease() != null) openExistingWallet()
        }
    }

    private fun runPendingWalletUnlockIfReady() {
        if (!walletPendingUnlockMayRun(
                requested = walletUnlockRequested,
                foreground = foreground,
                busy = busy,
                hasLease = currentStorageLease() != null,
                hasController = walletHandle != INVALID_HANDLE,
                hasUnconfirmedRecovery = unconfirmedDatabaseKey != null,
            )
        ) return
        walletUnlockRequested = false
        unlockWallet()
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
        val birthdayHeight = newWalletBirthdayHeight(
            walletNetwork,
            latestObservedBrowserHeaderHeight,
        )
        thread(name = "hns-wallet-create") {
            val created = NativeWalletBridge.create(
                path,
                databaseKey.copyOf(),
                network,
                birthdayHeight,
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

    private fun restoreWallet(phrase: CharArray?, birthdayHeight: Long) {
        if (phrase == null) {
            Toast.makeText(this, R.string.wallet_restore_phrase_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (birthdayHeight !in 0..MAX_HNS_BIRTHDAY_HEIGHT) {
            phrase.fill('\u0000')
            Toast.makeText(this, R.string.wallet_restore_birthday_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val lease = currentStorageLease() ?: run {
            phrase.fill('\u0000')
            return
        }
        if (!canStartNewWallet(lease)) {
            phrase.fill('\u0000')
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
                birthdayHeight,
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
                        startWalletOwnedDirectShakescapeWorker(handle, lease, epoch)
                    }
                    // A direct controller is installed locked. Once the user
                    // has explicitly unlocked it, take one bounded, verified
                    // snapshot so the confirmed available balance is visible
                    // without requiring a separate, unexplained refresh.
                    if (
                        !WalletHnsLiveSyncPresentationCache.automaticSyncIsPaused(walletNetwork.id) &&
                            NativeWalletBridge.hasHnsReads(handle)
                    ) {
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
     * Runs while this app retains the unlocked direct-wallet session. Native
     * code holds the listener and rejects/forgets every board socket on lock
     * or controller retirement; this worker remains app-foreground-only and
     * never becomes an Android background wallet service.
     */
    private fun startWalletOwnedDirectShakescapeWorker(
        handle: Long,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        if (directShakescapeWorkerHandle == handle) return
        directShakescapeWorkerHandle = handle
        thread(name = "hns-wallet-direct-shakescape") {
            try {
                var serviceTicks = 0
                while (
                    walletSessionIsActive() && operationIsCurrent(epoch, lease) && walletHandle == handle &&
                        (NativeWalletBridge.status(handle)?.locked == false)
                ) {
                    NativeWalletBridge.serviceWalletOwnedDirectShakescape(handle)
                    serviceTicks += 1
                    if (serviceTicks % DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS == 0) {
                        runOnUiThread {
                            if (
                                directShakescapeWorkerHandle == handle &&
                                    operationIsCurrent(epoch, lease) && walletHandle == handle
                            ) {
                                refreshDirectShakescapeStatus()
                            }
                        }
                    }
                    Thread.sleep(DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS)
                }
            } finally {
                if (directShakescapeWorkerHandle == handle) {
                    directShakescapeWorkerHandle = INVALID_HANDLE
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

    private fun requestHnsSyncCancellation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_stop_sync_title)
            .setMessage(R.string.wallet_stop_sync_message)
            .setNegativeButton(R.string.action_keep_synchronizing, null)
            .setPositiveButton(R.string.action_stop_sync) { _, _ ->
                WalletHnsLiveSyncPresentationCache.requestCancellation(walletNetwork.id)
                restoreCachedHnsSyncPresentation()
                renderWalletDashboard()
            }
            .show()
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

    private fun pullToSynchronizeWalletReads() {
        val knownDialogVisible = walletDeletionDialog?.isShowing == true ||
            sendApprovalDialog?.isShowing == true ||
            valueApprovalDialog?.isShowing == true
        if (!walletPullToSyncMayStart(
                windowHasFocus = window.decorView.hasWindowFocus(),
                knownDialogVisible = knownDialogVisible,
            )
        ) return
        synchronizeWalletReads()
    }

    private fun synchronizeWalletReads() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || unconfirmedDatabaseKey != null) {
            resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
            return
        }
        if (!NativeWalletBridge.hasHnsReads(handle)) {
            resetReadProjection(R.string.wallet_reads_unavailable)
            return
        }
        WalletHnsLiveSyncPresentationCache.resumeAutomaticSync(walletNetwork.id)
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        // A refresh must not erase the last authenticated projection before
        // its replacement exists. Keep it visible, but invalidate its
        // authority fence immediately so a stale balance can never prepare a
        // send while this round is pending or after a failed refresh.
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_syncing_reads),
                resetReads = false,
            )
        ) return
        invalidateReadSnapshotAuthority()
        val presentationLease = WalletHnsLiveSyncPresentationCache.begin(
            walletNetwork.id,
            requestCancellation = {
                hnsCatchupRetry?.set(false)
                NativeWalletBridge.cancelHnsSynchronization(handle)
                runOnUiThread { finishStoppedHnsCatchupIfReady(lease, handle) }
            },
        )
        walletHnsSyncInProgress = true
        startWalletForegroundSyncService("HNS")
        Log.i(TAG, "Starting a bounded direct HNS synchronization round")
        readStatusView.text = getString(R.string.wallet_reads_syncing)
        showReadProjectionSynchronizationPendingIfNeeded()
        renderWalletDashboard()
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        val poller = startLiveHnsSyncProgressPolling(handle, presentationLease)
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
            val synchronization = if (
                preflightFailure == null && !presentationLease.cancellationRequested.get()
            ) {
                synchronizeHnsReadsWithRollbackFloor(handle)
            } else {
                null
            }
            poller.set(false)
            when {
                synchronization?.snapshot != null ->
                    WalletHnsLiveSyncPresentationCache.clear(presentationLease)

                synchronization?.catchup != null ->
                    WalletHnsLiveSyncPresentationCache.finishCatchup(
                        presentationLease,
                        synchronization.catchup,
                    )

                else -> WalletHnsLiveSyncPresentationCache.clear(presentationLease)
            }
            runOnUiThread {
                val ownsLease = currentStorageLease() === lease
                val mayPublish = walletReadMayPublish(
                    expectedEpoch = epoch,
                    currentEpoch = lifecycleEpoch,
                    foreground = walletSessionIsActive(),
                    ownsCurrentLease = ownsLease,
                    expectedHandle = handle,
                    currentHandle = walletHandle,
                    expectedAuthorityGeneration = authorityGeneration,
                    currentAuthorityGeneration = walletAuthorityGeneration,
                ) && operationIsCurrent(epoch, lease)
                if (!mayPublish) {
                    Log.i(
                        TAG,
                        "Discarding completed direct HNS round because its WalletActivity authority changed",
                    )
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                walletHnsSyncInProgress = false
                if (liveHnsSyncPoller === poller) liveHnsSyncPoller = null
                when {
                    preflightFailure != null -> {
                        refreshControllerState()
                        Log.w(TAG, "Direct HNS synchronization preflight rejected the wallet state")
                        resetReadProjection(preflightFailure)
                    }
                    synchronization == null -> {
                        refreshControllerState(resetReads = false)
                        Log.w(TAG, "Direct HNS synchronization returned no authenticated result")
                        retainReadProjectionAfterRefreshFailure()
                    }
                    synchronization.snapshot != null -> {
                        refreshControllerState(resetReads = false)
                        Log.i(TAG, "Direct HNS synchronization reached a verified wallet snapshot")
                        renderReadSnapshot(synchronization.snapshot)
                    }
                    synchronization.catchup != null -> {
                        Log.i(
                            TAG,
                            "Direct HNS synchronization checkpointed catch-up at " +
                                "${synchronization.catchup.scannedHeight ?: synchronization.catchup.birthdayHeight} " +
                                "of ${synchronization.catchup.scanTargetHeight}",
                        )
                        // A bounded checkpoint is an internal yield, not an
                        // unlock or terminal wallet state. Install the retry
                        // token before rendering so the dashboard remains one
                        // continuous synchronization with value actions
                        // disabled throughout the short checkpoint gap.
                        scheduleHnsCatchupRetry(lease, handle, epoch, authorityGeneration)
                        statusView.text = getString(R.string.wallet_status_syncing_reads)
                        renderReadCatchup(synchronization.catchup)
                    }
                    else -> {
                        refreshControllerState(resetReads = false)
                        retainReadProjectionAfterRefreshFailure()
                    }
                }
                finishWalletForegroundSyncIfIdle()
            }
        }
    }

    /**
     * A direct HNS sync is bounded so it can always release controller
     * ownership promptly. Catch-up is therefore a resumable result, not a
     * terminal UI state: continue with a short delay while retaining the
     * durable verified height. Its visible foreground-service notification
     * keeps this read-only continuation alive across a brief app switch; it
     * never exposes a partial projection.
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
        Log.i(TAG, "Scheduling the next bounded direct HNS catch-up round")
        thread(name = "hns-wallet-catchup-retry") {
            try {
                Thread.sleep(HNS_CATCHUP_RETRY_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                retry.set(false)
            }
            runOnUiThread {
                val mayRetry =
                    retry.get() && hnsCatchupRetry === retry && !busy &&
                        !walletHnsSyncInProgress &&
                        !WalletHnsLiveSyncPresentationCache.automaticSyncIsPaused(walletNetwork.id) &&
                        walletOperationMayPublish(epoch, lease, handle, authorityGeneration) &&
                        NativeWalletBridge.status(handle)?.locked == false
                if (mayRetry) {
                    Log.i(TAG, "Starting the scheduled direct HNS catch-up round")
                    synchronizeWalletReads()
                } else {
                    retry.set(false)
                    if (hnsCatchupRetry === retry) hnsCatchupRetry = null
                    Log.i(
                        TAG,
                        "Direct HNS catch-up retry was not authorized: " +
                            "enabled=${retry.get()} current=${hnsCatchupRetry === retry} " +
                            "busy=$busy syncInProgress=$walletHnsSyncInProgress " +
                            "sessionActive=${walletSessionIsActive()}",
                    )
                    finishStoppedHnsCatchupIfReady(lease, handle)
                    finishWalletForegroundSyncIfIdle()
                }
            }
        }
    }

    /**
     * A Stop Sync tap during the short delay between bounded rounds is already
     * at a durable checkpoint, so there is no native call to unwind. Restore
     * ordinary controls immediately instead of waiting for the retry sleeper.
     */
    private fun finishStoppedHnsCatchupIfReady(
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
    ) {
        if (
            !WalletHnsLiveSyncPresentationCache.automaticSyncIsPaused(walletNetwork.id) ||
                walletHnsSyncInProgress || currentStorageLease() !== lease ||
                walletHandle != handle
        ) return
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)
        refreshControllerState(resetReads = false)
        readStatusView.text = getString(R.string.wallet_reads_sync_stopped_checkpoint)
        renderWalletDashboard()
        finishWalletForegroundSyncIfIdle()
    }

    private fun revealBitcoinReceiveAddress() {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!walletBitcoinOperationMayStart(
                walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_operation_busy)
            return
        }
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
        if (!walletBitcoinOperationMayStart(
                walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_already_running)
            return
        }
        if (busy) {
            showWalletBusyFeedback()
            return
        }
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            resetBitcoinProjection()
            Log.w(TAG, "Direct Bitcoin synchronization was requested while the wallet was locked or unavailable")
            Toast.makeText(this, R.string.wallet_bitcoin_locked, Toast.LENGTH_SHORT).show()
            return
        }
        walletBitcoinSyncInProgress = true
        bitcoinSyncStopRequested = false
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_syncing)
        startWalletForegroundSyncService("Bitcoin")
        Log.i(TAG, "Starting a bounded direct Bitcoin synchronization round")
        val epoch = lifecycleEpoch
        startBitcoinSyncProgressWatcher(handle, lease, epoch)
        thread(name = "bitcoin-wallet-direct-sync") {
            val synchronization = NativeWalletBridge.synchronizeBitcoin(handle)
            runOnUiThread {
                val stopped = bitcoinSyncStopRequested
                walletBitcoinSyncInProgress = false
                bitcoinSyncStopRequested = false
                bitcoinSyncProgressWatcher?.set(false)
                bitcoinSyncProgressWatcher = null
                finishWalletForegroundSyncIfIdle()
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                if (stopped) {
                    Log.i(TAG, "Direct Bitcoin synchronization stopped by user request")
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_stopped)
                } else if (synchronization == null) {
                    Log.w(TAG, "Direct Bitcoin synchronization returned no verified snapshot")
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_failed)
                } else {
                    Log.i(
                        TAG,
                        "Direct Bitcoin synchronization reached checkpoint " +
                            "${synchronization.checkpointHeight}",
                    )
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

    private fun requestBitcoinSyncCancellation() {
        if (!walletBitcoinSyncInProgress || bitcoinSyncStopRequested) return
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_stop_bitcoin_sync_title)
            .setMessage(R.string.wallet_stop_bitcoin_sync_message)
            .setNegativeButton(R.string.action_keep_synchronizing, null)
            .setPositiveButton(R.string.action_stop_sync) { _, _ ->
                bitcoinSyncStopRequested = true
                if (NativeWalletBridge.stopBitcoinSynchronization(walletHandle)) {
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_stopping)
                } else {
                    bitcoinSyncStopRequested = false
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_stop_failed)
                }
            }
            .show()
    }

    private fun startBitcoinSyncProgressWatcher(
        handle: Long,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        bitcoinSyncProgressWatcher?.set(false)
        val watcher = AtomicBoolean(true)
        bitcoinSyncProgressWatcher = watcher
        val startedAt = SystemClock.elapsedRealtime()
        thread(name = "bitcoin-wallet-sync-progress") {
            var baselineWork: Long? = null
            var baselineAt = startedAt
            while (
                watcher.get() && walletBitcoinSyncInProgress &&
                operationIsCurrent(epoch, lease) && walletHandle == handle
            ) {
                val progress = NativeWalletBridge.bitcoinSyncProgress(handle)
                val now = SystemClock.elapsedRealtime()
                if (progress != null) {
                    val completedWork = progress.completionBasisPoints
                    if (baselineWork == null && completedWork > 0L) {
                        baselineWork = completedWork
                        baselineAt = now
                    }
                    val baseline = baselineWork
                    val etaMillis = if (baseline != null) {
                        estimateBitcoinSyncRemainingMillis(
                            completedWork = completedWork,
                            totalWork = 10_000L,
                            baselineWork = baseline,
                            measurementMillis = now - baselineAt,
                        )
                    } else {
                        null
                    }
                    runOnUiThread {
                        if (
                            watcher.get() && walletBitcoinSyncInProgress &&
                            operationIsCurrent(epoch, lease) && walletHandle == handle
                        ) {
                            bitcoinStatusView.text = bitcoinSyncProgressText(
                                progress,
                                now - startedAt,
                                etaMillis,
                            )
                        }
                    }
                }
                try {
                    Thread.sleep(BITCOIN_SYNC_PROGRESS_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    watcher.set(false)
                }
            }
        }
    }

    private fun bitcoinSyncProgressText(
        progress: NativeBitcoinSyncProgress,
        elapsedMillis: Long,
        etaMillis: Long?,
    ): String {
        val elapsed = formatBitcoinSyncDuration(elapsedMillis)
        if (!progress.connectionsMet) {
            return getString(
                R.string.wallet_bitcoin_sync_connecting,
                progress.successfulHandshakes,
                progress.requiredPeerCount,
                progress.connectionFailures,
                progress.peerTimeouts,
                progress.incompatiblePeers,
                elapsed,
            )
        }
        if (progress.completionBasisPoints <= 0L) {
            return getString(R.string.wallet_bitcoin_sync_discovering, elapsed)
        }
        val percentTenths = progress.completionBasisPoints.coerceIn(0L, 10_000L) / 10L
        val chainHeight = progress.chainHeight?.toString() ?: getString(R.string.common_unknown)
        val eta = etaMillis?.let(::formatBitcoinSyncDuration)
            ?: getString(R.string.wallet_bitcoin_sync_eta_calculating)
        return getString(
            R.string.wallet_bitcoin_sync_progress,
            percentTenths / 10L,
            percentTenths % 10L,
            chainHeight,
            elapsed,
            eta,
        )
    }

    private fun resetBitcoinProjection() {
        bitcoinSnapshot = null
        bitcoinBalanceView.text = getString(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView.text = getString(R.string.wallet_bitcoin_receive_unavailable)
        val status = NativeWalletBridge.status(walletHandle)
        if (status?.locked == false && NativeWalletBridge.hasBitcoinValue(walletHandle)) {
            NativeWalletBridge.bitcoinSnapshot(walletHandle)?.let { snapshot ->
                renderBitcoinSnapshot(snapshot)
                bitcoinStatusView.text = getString(R.string.wallet_bitcoin_ready)
                return
            }
        }
        bitcoinStatusView.text = when {
            status?.locked == true -> getString(R.string.wallet_bitcoin_locked)
            NativeWalletBridge.hasBitcoinValue(walletHandle) -> getString(R.string.wallet_bitcoin_ready)
            else -> getString(R.string.wallet_bitcoin_unavailable)
        }
    }

    private fun refreshDirectShakescapeStatus() {
        val status = NativeWalletBridge.walletOwnedDirectShakescapeStatus(walletHandle)
        directShakescapeStatusView.text = when {
            status == null -> getString(R.string.wallet_direct_shakescape_unavailable)
            !status.unlocked -> getString(R.string.wallet_direct_shakescape_locked)
            status.listenerPort == null -> getString(
                R.string.wallet_direct_shakescape_host_unavailable,
                DIRECT_SHAKESCAPE_LISTEN_PORT,
                status.peerEndpoint ?: getString(R.string.wallet_direct_shakescape_peer_none),
            )

            status.peerEndpoint == null -> getString(
                R.string.wallet_direct_shakescape_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_shakescape_peer_none),
            )

            else -> getString(
                R.string.wallet_direct_shakescape_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_shakescape_peer_connected, status.peerEndpoint),
            )
        }
    }

    private fun renderBitcoinSnapshot(snapshot: com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot) {
        bitcoinSnapshot = snapshot
        val birthday = when (snapshot.birthdayState) {
            "awaitingCreationTip" -> getString(R.string.wallet_bitcoin_birthday_creation_pending)
            "recoveryUnknown" -> getString(R.string.wallet_bitcoin_birthday_recovery_unknown)
            "recoveryPendingValidation" -> getString(
                R.string.wallet_bitcoin_birthday_recovery_pending,
                snapshot.birthdayHeight,
            )
            else -> getString(R.string.wallet_bitcoin_birthday_validated, snapshot.birthdayHeight)
        }
        bitcoinBalanceView.text = getString(
            R.string.wallet_bitcoin_balance,
            snapshot.confirmedSats,
            snapshot.trustedPendingSats,
            snapshot.untrustedPendingSats,
            snapshot.immatureSats,
            snapshot.totalSats,
            birthday,
            snapshot.synchronizedHeight,
        )
        bitcoinReceiveView.text = getString(R.string.wallet_bitcoin_receive, snapshot.receiveAddress)
    }

    private fun showBitcoinBirthdayForm() {
        if (walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_operation_busy)
            return
        }
        if (busy) {
            showWalletBusyFeedback()
            return
        }
        if (!bitcoinBirthdayMayStart()) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_unavailable)
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.wallet_bitcoin_birthday_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(10))
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_bitcoin_birthday_title)
            .setMessage(R.string.wallet_bitcoin_birthday_message)
            .setView(input)
            .setNegativeButton(R.string.action_cancel) { _, _ -> wipeEditable(input.text) }
            .setPositiveButton(R.string.action_apply, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val height = input.text?.toString()?.toLongOrNull()
                    ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                if (height == null) {
                    input.error = getString(R.string.wallet_bitcoin_birthday_invalid_height)
                    return@setOnClickListener
                }
                wipeEditable(input.text)
                dialog.dismiss()
                setBitcoinBirthdayHeight(height)
            }
        }
        dialog.setOnDismissListener { wipeEditable(input.text) }
        dialog.show()
    }

    private fun bitcoinBirthdayMayStart(): Boolean {
        if (
            busy || walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress ||
            currentStorageLease() == null || walletHandle == INVALID_HANDLE
        ) return false
        val birthdayState = bitcoinSnapshot?.birthdayState ?: return false
        return birthdayState in setOf("recoveryUnknown", "recoveryPendingValidation") &&
            NativeWalletBridge.status(walletHandle)?.locked == false &&
            NativeWalletBridge.hasBitcoinValue(walletHandle)
    }

    private fun setBitcoinBirthdayHeight(height: Long) {
        val lease = currentStorageLease() ?: run {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_unavailable)
            return
        }
        val handle = walletHandle
        if (
            handle == INVALID_HANDLE || walletBitcoinSyncInProgress ||
            bitcoinBirthdayResetInProgress || !NativeWalletBridge.hasBitcoinValue(handle)
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_setting_bitcoin_birthday),
                resetReads = false,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_unavailable)
            return
        }
        bitcoinBirthdayResetInProgress = true
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_setting, height)
        Log.i(TAG, "Starting bounded direct Bitcoin birthday validation")
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-wallet-birthday") {
            val snapshot = NativeWalletBridge.setBitcoinBirthdayHeight(handle, height)
            runOnUiThread {
                bitcoinBirthdayResetInProgress = false
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                if (snapshot == null) {
                    Log.w(TAG, "Direct Bitcoin birthday validation did not complete")
                    bitcoinStatusView.text = getString(R.string.wallet_bitcoin_birthday_failed)
                } else {
                    Log.i(TAG, "Direct Bitcoin birthday validation completed")
                    renderBitcoinSnapshot(snapshot)
                    bitcoinStatusView.text = getString(
                        R.string.wallet_bitcoin_birthday_set,
                        snapshot.birthdayHeight,
                    )
                }
            }
        }
    }

    private data class WalletActionInput(
        val hint: Int,
        val numeric: Boolean = false,
        val decimal: Boolean = true,
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
                    InputType.TYPE_CLASS_NUMBER or (
                        if (field.decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                    )
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

    private fun showBitcoinSendForm() {
        if (!walletBitcoinOperationMayStart(
                walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_operation_busy)
            return
        }
        showWalletActionForm(
            R.string.row_wallet_bitcoin_send,
            listOf(
                WalletActionInput(R.string.wallet_bitcoin_send_destination_hint),
                WalletActionInput(
                    R.string.wallet_bitcoin_send_amount_hint,
                    numeric = true,
                    decimal = false,
                ),
                WalletActionInput(
                    R.string.wallet_bitcoin_send_fee_hint,
                    numeric = true,
                    decimal = false,
                    initial = NativeWalletBridge.MINIMUM_BITCOIN_MAXIMUM_FEE_SATS.toString(),
                ),
            ),
        ) { values ->
            val amountSats = values[1].toLongOrNull()?.takeIf { it > 0L }
            val maximumFeeSats = values[2].toLongOrNull()
            if (amountSats == null) {
                bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_invalid_request)
                return@showWalletActionForm
            }
            if (maximumFeeSats == null ||
                maximumFeeSats < NativeWalletBridge.MINIMUM_BITCOIN_MAXIMUM_FEE_SATS
            ) {
                bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_fee_cap_minimum)
                return@showWalletActionForm
            }
            prepareBitcoinSend(values[0], amountSats, maximumFeeSats)
        }
    }

    private fun showBtcForHnsOfferForm() {
        if (!walletBitcoinOperationMayStart(
                walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_operation_busy)
            return
        }
        showWalletActionForm(
            R.string.wallet_swap_sell_btc,
            listOf(
                WalletActionInput(R.string.wallet_swap_btc_amount_hint, numeric = true),
                WalletActionInput(R.string.wallet_swap_hns_amount_hint),
                WalletActionInput(R.string.wallet_swap_fee_reserve_hint, numeric = true),
                WalletActionInput(R.string.wallet_swap_lifetime_hours_hint, initial = "24", numeric = true),
            ),
        ) { values ->
            val btc = values[0].toLongOrNull()?.takeIf { it > 0L }
            val hns = parsePositiveHnsToBaseUnits(values[1])?.toLongOrNull()?.takeIf { it > 0L }
            val reserve = values[2].toLongOrNull()?.takeIf { it > 0L }
            val lifetime = values[3].toLongOrNull()
                ?.takeIf { it in 1L..168L }
                ?.let { runCatching { Math.multiplyExact(it, 3_600L) }.getOrNull() }
            if (btc == null || hns == null || reserve == null || lifetime == null) {
                bitcoinStatusView.text = getString(R.string.wallet_swap_prepare_failed)
                return@showWalletActionForm
            }
            prepareBtcForHnsOffer(btc, hns, reserve, lifetime)
        }
    }

    private fun showActiveBtcForHnsOffers() {
        val handle = walletHandle
        if (handle == INVALID_HANDLE || busy) return
        bitcoinStatusView.text = getString(R.string.wallet_swap_loading_offers)
        thread(name = "bitcoin-hns-offer-list") {
            val offers = NativeWalletBridge.localBtcForHnsOffers(handle)
            runOnUiThread {
                if (walletHandle != handle) return@runOnUiThread
                if (offers == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_list_failed)
                    return@runOnUiThread
                }
                if (offers.isEmpty()) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_no_active_offers)
                    return@runOnUiThread
                }
                val labels = offers.map {
                    "${it.btcAmountSats} sats → ${formatHnsBaseUnits(it.hnsAmountDollarydoos.toString())} HNS · ${it.offerId.take(12)}…"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.wallet_swap_active_offers)
                    .setItems(labels) { _, index -> confirmCancelBtcForHnsOffer(offers[index]) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun showShakescapeExecutions() {
        val handle = walletHandle
        if (handle == INVALID_HANDLE || busy) return
        bitcoinStatusView.text = getString(R.string.wallet_swap_loading_executions)
        thread(name = "denuo-execution-list") {
            val status = NativeWalletBridge.shakescapeExecutions(handle)
            runOnUiThread {
                if (walletHandle != handle) return@runOnUiThread
                if (status == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_execution_list_failed)
                } else if (status.executions.isEmpty()) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_no_executions) +
                        "\n" + bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery)
                } else {
                    bitcoinStatusView.text = bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery)
                    val labels = status.executions.map {
                        "${it.state.replace('_', ' ')} · ${it.offeredAmount} ${it.offeredAsset.uppercase()} → ${it.receivedAmount} ${it.receivedAsset.uppercase()} · ${it.sessionId.take(12)}…"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.wallet_swap_executions)
                        .setMessage(bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery))
                        .setItems(labels) { _, index -> showShakescapeExecution(status.executions[index]) }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun bitcoinBroadcastRecoveryText(
        recovery: com.denuoweb.hnsdane.wallet.NativeBitcoinBroadcastRecovery?,
    ): String = when {
        recovery == null -> getString(R.string.wallet_swap_recovery_unavailable)
        recovery.totalApproved == 0L -> getString(R.string.wallet_swap_recovery_none)
        else -> getString(
            R.string.wallet_swap_recovery_detail,
            recovery.unobservedPrepared,
            recovery.unobservedSubmissionStarted,
            recovery.unobservedSubmitted,
            recovery.observed,
            recovery.highestAttemptCount,
            recovery.lastChangedAtUnix ?: 0L,
        )
    }

    private fun showShakescapeExecution(execution: NativeShakescapeExecutionSummary) {
        val message = getString(
            R.string.wallet_swap_execution_detail,
            execution.sessionId,
            execution.state.replace('_', ' '),
            execution.firstChain,
            execution.secondChain,
            execution.firstFundingConfirmed.toString(),
            execution.secondFundingConfirmed.toString(),
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_execution_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
        if (execution.state == "first_funding_pending" && execution.firstChain == "bitcoin") {
            builder.setPositiveButton(R.string.wallet_swap_fund_bitcoin) { _, _ ->
                showWalletActionForm(
                    R.string.wallet_swap_fund_bitcoin,
                    listOf(WalletActionInput(R.string.wallet_swap_funding_fee_hint, numeric = true)),
                ) { values ->
                    val fee = values.single().toLongOrNull()?.takeIf { it > 0L }
                    if (fee == null) bitcoinStatusView.text = getString(R.string.wallet_swap_funding_prepare_failed)
                    else prepareBtcForHnsFunding(execution, fee)
                }
            }
        } else if (execution.state == "second_funding_pending" && execution.secondChain == "handshake") {
            builder.setPositiveButton(R.string.wallet_swap_fund_hns) { _, _ ->
                showWalletActionForm(
                    R.string.wallet_swap_fund_hns,
                    listOf(WalletActionInput(R.string.wallet_swap_hns_funding_fee_hint, numeric = true)),
                ) { values ->
                    val fee = values.single().toLongOrNull()?.takeIf { it > 0L }
                    if (fee == null) bitcoinStatusView.text = getString(R.string.wallet_swap_hns_funding_prepare_failed)
                    else prepareHnsForBtcFunding(execution, fee)
                }
            }
        } else if (execution.state in setOf("both_funded", "first_redeemed", "secret_observed")) {
            builder.setPositiveButton(R.string.wallet_swap_settlement_actions) { _, _ ->
                showSwapSettlementActions(execution)
            }
        }
        builder.show()
    }

    private fun showSwapSettlementActions(execution: NativeShakescapeExecutionSummary) {
        val actions = arrayOf(
            getString(R.string.wallet_swap_redeem_hns),
            getString(R.string.wallet_swap_redeem_bitcoin),
            getString(R.string.wallet_swap_refund_hns),
            getString(R.string.wallet_swap_refund_bitcoin),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_settlement_actions)
            .setItems(actions) { _, index ->
                val bitcoin = index == 1 || index == 3
                val action = if (index < 2) "redeem" else "refund"
                showWalletActionForm(
                    R.string.wallet_swap_settlement_fee_title,
                    listOf(WalletActionInput(
                        if (bitcoin) R.string.wallet_swap_settlement_btc_fee_hint
                        else R.string.wallet_swap_settlement_hns_fee_hint,
                        numeric = true,
                    )),
                ) { values ->
                    val fee = values.single().toLongOrNull()?.takeIf { it > 0L }
                    if (fee == null) {
                        bitcoinStatusView.text = getString(R.string.wallet_swap_settlement_prepare_failed)
                    } else {
                        prepareSwapSettlement(execution, action, bitcoin, fee)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun prepareSwapSettlement(
        execution: NativeShakescapeExecutionSummary,
        action: String,
        bitcoin: Boolean,
        maximumFee: Long,
    ) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!beginOperation(lease, getString(R.string.wallet_swap_settlement_preparing), resetReads = false)) return
        val epoch = lifecycleEpoch
        thread(name = "denuo-swap-settlement-prepare") {
            val approval = NativeWalletBridge.prepareSwapSettlement(
                handle, execution.sessionId, action, maximumFee, bitcoin,
            )
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    approval?.let {
                        NativeWalletBridge.rejectSwapSettlement(handle, it.actionToken, bitcoin)
                        it.close()
                    }
                    releaseStorageLeaseAfterOperation(lease)
                } else if (approval == null) {
                    busy = false
                    bitcoinStatusView.text = getString(R.string.wallet_swap_settlement_prepare_failed)
                    releaseStorageLeaseAfterOperation(lease)
                } else {
                    showSwapSettlementApproval(approval, bitcoin, lease, epoch)
                }
            }
        }
    }

    private fun showSwapSettlementApproval(
        approval: NativeSwapSettlementApproval,
        bitcoin: Boolean,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "denuo-swap-settlement-reject") {
                NativeWalletBridge.rejectSwapSettlement(walletHandle, approval.actionToken, bitcoin)
                runOnUiThread { busy = false; releaseStorageLeaseAfterOperation(lease) }
            }
        }
        val unit = if (bitcoin) "sats" else "dollarydoos"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.wallet_swap_settlement_approval_title, approval.action))
            .setMessage(getString(
                R.string.wallet_swap_settlement_approval_message,
                approval.inputAmount, unit, approval.outputAmount, approval.fee,
                approval.maximumFee, approval.transactionId, approval.sessionId,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_broadcast_settlement) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                thread(name = "denuo-swap-settlement-broadcast") {
                    val receipt = NativeWalletBridge.approveSwapSettlement(
                        walletHandle, approval.actionToken, bitcoin,
                    )
                    runOnUiThread {
                        if (operationIsCurrent(epoch, lease)) {
                            busy = false
                            bitcoinStatusView.text = if (receipt == null) {
                                getString(R.string.wallet_swap_settlement_broadcast_failed)
                            } else {
                                getString(
                                    R.string.wallet_swap_settlement_submitted,
                                    receipt.action, receipt.transactionId.take(12),
                                )
                            }
                        }
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareBtcForHnsFunding(execution: NativeShakescapeExecutionSummary, maximumFeeSats: Long) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!beginOperation(lease, getString(R.string.wallet_swap_funding_preparing), resetReads = false)) return
        val epoch = lifecycleEpoch
        thread(name = "denuo-bitcoin-funding-prepare") {
            val approval = NativeWalletBridge.prepareBtcForHnsFunding(
                handle, execution.sessionId, maximumFeeSats,
            )
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    approval?.let { NativeWalletBridge.rejectBtcForHnsFunding(handle, it.actionToken); it.close() }
                    releaseStorageLeaseAfterOperation(lease)
                } else if (approval == null) {
                    busy = false
                    bitcoinStatusView.text = getString(R.string.wallet_swap_funding_prepare_failed)
                } else {
                    showBtcForHnsFundingApproval(approval, lease, epoch)
                }
            }
        }
    }

    private fun showBtcForHnsFundingApproval(
        approval: NativeBitcoinHtlcFundingApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "denuo-bitcoin-funding-reject") {
                NativeWalletBridge.rejectBtcForHnsFunding(walletHandle, approval.actionToken)
                runOnUiThread { busy = false; releaseStorageLeaseAfterOperation(lease) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_funding_approval_title)
            .setMessage(getString(
                R.string.wallet_swap_funding_approval_message,
                approval.amountSats, approval.feeSats, approval.maximumFeeSats,
                approval.txid, approval.sessionId,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_broadcast_funding) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                thread(name = "denuo-bitcoin-funding-broadcast") {
                    val receipt = NativeWalletBridge.approveBtcForHnsFunding(
                        walletHandle, approval.actionToken,
                    )
                    runOnUiThread {
                        if (operationIsCurrent(epoch, lease)) {
                            busy = false
                            bitcoinStatusView.text = if (receipt == null) {
                                getString(R.string.wallet_swap_funding_broadcast_failed)
                            } else {
                                getString(R.string.wallet_swap_funding_submitted, receipt.txid.take(12))
                            }
                        }
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareHnsForBtcFunding(
        execution: NativeShakescapeExecutionSummary,
        maximumFeeDollarydoos: Long,
    ) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!beginOperation(lease, getString(R.string.wallet_swap_hns_funding_preparing), resetReads = false)) return
        val epoch = lifecycleEpoch
        thread(name = "denuo-hns-funding-prepare") {
            val approval = NativeWalletBridge.prepareHnsForBtcFunding(
                handle, execution.sessionId, maximumFeeDollarydoos,
            )
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    approval?.let { NativeWalletBridge.rejectHnsForBtcFunding(handle, it.actionToken); it.close() }
                    releaseStorageLeaseAfterOperation(lease)
                } else if (approval == null) {
                    busy = false
                    bitcoinStatusView.text = getString(R.string.wallet_swap_hns_funding_prepare_failed)
                } else {
                    showHnsForBtcFundingApproval(approval, lease, epoch)
                }
            }
        }
    }

    private fun showHnsForBtcFundingApproval(
        approval: NativeHnsHtlcFundingApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "denuo-hns-funding-reject") {
                NativeWalletBridge.rejectHnsForBtcFunding(walletHandle, approval.actionToken)
                runOnUiThread { busy = false; releaseStorageLeaseAfterOperation(lease) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_hns_funding_approval_title)
            .setMessage(getString(
                R.string.wallet_swap_hns_funding_approval_message,
                formatHnsBaseUnits(approval.amountDollarydoos.toString()),
                formatHnsBaseUnits(approval.feeDollarydoos.toString()),
                formatHnsBaseUnits(approval.maximumFeeDollarydoos.toString()),
                approval.transactionId, approval.sessionId,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_broadcast_hns_funding) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                thread(name = "denuo-hns-funding-broadcast") {
                    val receipt = NativeWalletBridge.approveHnsForBtcFunding(
                        walletHandle, approval.actionToken,
                    )
                    runOnUiThread {
                        if (operationIsCurrent(epoch, lease)) {
                            busy = false
                            bitcoinStatusView.text = if (receipt == null) {
                                getString(R.string.wallet_swap_hns_funding_broadcast_failed)
                            } else {
                                getString(R.string.wallet_swap_hns_funding_submitted, receipt.transactionId.take(12))
                            }
                        }
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun confirmCancelBtcForHnsOffer(
        offer: com.denuoweb.hnsdane.wallet.NativeBtcForHnsOfferSummary,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_cancel_title)
            .setMessage(getString(
                R.string.wallet_swap_cancel_message,
                offer.btcAmountSats,
                formatHnsBaseUnits(offer.hnsAmountDollarydoos.toString()),
                offer.offerId,
            ))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.wallet_swap_cancel_offer) { _, _ ->
                val handle = walletHandle
                bitcoinStatusView.text = getString(R.string.wallet_swap_cancelling)
                thread(name = "bitcoin-hns-offer-cancel") {
                    val cancelled = NativeWalletBridge.cancelBtcForHnsOffer(handle, offer.offerId)
                    runOnUiThread {
                        if (walletHandle == handle) {
                            bitcoinStatusView.text = getString(
                                if (cancelled) R.string.wallet_swap_cancelled
                                else R.string.wallet_swap_cancel_failed,
                            )
                        }
                    }
                }
            }
            .show()
    }

    private fun prepareBtcForHnsOffer(
        btcAmountSats: Long,
        hnsAmountDollarydoos: Long,
        bitcoinFeeReserveSats: Long,
        listingLifetimeSeconds: Long,
    ) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_unavailable)
            return
        }
        if (!beginOperation(lease, getString(R.string.wallet_swap_preparing), resetReads = false)) return
        bitcoinStatusView.text = getString(R.string.wallet_swap_preparing)
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-hns-offer-prepare") {
            val approval = NativeWalletBridge.prepareBtcForHnsOffer(
                handle,
                btcAmountSats,
                hnsAmountDollarydoos,
                bitcoinFeeReserveSats,
                listingLifetimeSeconds,
            )
            val exact = approval?.takeIf {
                it.btcAmountSats == btcAmountSats &&
                    it.hnsAmountDollarydoos == hnsAmountDollarydoos &&
                    it.bitcoinFeeReserveSats == bitcoinFeeReserveSats
            }
            if (approval != null && exact == null) {
                NativeWalletBridge.rejectBtcForHnsOffer(handle, approval.actionToken)
                approval.close()
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                    exact?.let {
                        NativeWalletBridge.rejectBtcForHnsOffer(handle, it.actionToken)
                        it.close()
                    }
                    releaseStorageLeaseAfterOperation(lease)
                } else if (exact == null) {
                    busy = false
                    bitcoinStatusView.text = getString(R.string.wallet_swap_prepare_failed)
                } else {
                    showBtcForHnsOfferApproval(exact, lease, epoch)
                }
            }
        }
    }

    private fun showBtcForHnsOfferApproval(
        approval: NativeBtcForHnsOfferApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        val expiry = runCatching {
            DateFormat.getDateTimeInstance().format(
                Date(Math.multiplyExact(approval.offerExpiresAtUnix, 1_000L)),
            )
        }.getOrElse { approval.offerExpiresAtUnix.toString() }
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "bitcoin-hns-offer-reject") {
                NativeWalletBridge.rejectBtcForHnsOffer(walletHandle, approval.actionToken)
                runOnUiThread {
                    busy = false
                    releaseStorageLeaseAfterOperation(lease)
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wallet_swap_approval_title)
            .setMessage(getString(
                R.string.wallet_swap_approval_message,
                approval.btcAmountSats,
                formatHnsBaseUnits(approval.hnsAmountDollarydoos.toString()),
                approval.bitcoinFeeReserveSats,
                approval.totalBitcoinCommitmentSats,
                expiry,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_publish) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                bitcoinStatusView.text = getString(R.string.wallet_swap_publishing)
                thread(name = "bitcoin-hns-offer-publish") {
                    val published = NativeWalletBridge.approveBtcForHnsOffer(
                        walletHandle,
                        approval.actionToken,
                    )
                    runOnUiThread {
                        if (!operationIsCurrent(epoch, lease)) {
                            releaseStorageLeaseAfterOperation(lease)
                            return@runOnUiThread
                        }
                        busy = false
                        bitcoinStatusView.text = if (published == null) {
                            getString(R.string.wallet_swap_publish_failed)
                        } else {
                            getString(R.string.wallet_swap_published, published.offerId.take(12))
                        }
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareBitcoinSend(destination: String, amountSats: Long, maximumFeeSats: Long) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!walletBitcoinOperationMayStart(
                walletBitcoinSyncInProgress || bitcoinBirthdayResetInProgress,
            )
        ) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_sync_operation_busy)
            return
        }
        if (handle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(handle)) {
            bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_bitcoin_send),
                resetReads = false,
            )
        ) return
        bitcoinStatusView.text = getString(R.string.wallet_bitcoin_send_preparing)
        val epoch = lifecycleEpoch
        thread(name = "bitcoin-wallet-send-prepare") {
            val preparation = NativeWalletBridge.prepareBitcoinSend(
                handle, destination, amountSats, maximumFeeSats,
            )
            val approval = preparation.approval
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
                    bitcoinStatusView.text = bitcoinSendPreparationFailureMessage(preparation.failure)
                } else {
                    showBitcoinSendApproval(exact, lease, epoch)
                }
            }
        }
    }

    private fun bitcoinSendPreparationFailureMessage(
        failure: NativeBitcoinSendPreparationFailure?,
    ): String = getString(
        when (failure) {
            NativeBitcoinSendPreparationFailure.AmountBelowMinimum ->
                R.string.wallet_bitcoin_send_amount_minimum
            NativeBitcoinSendPreparationFailure.InsufficientConfirmedFunds ->
                R.string.wallet_bitcoin_send_insufficient_confirmed
            NativeBitcoinSendPreparationFailure.InvalidDestination ->
                R.string.wallet_bitcoin_send_invalid_destination
            NativeBitcoinSendPreparationFailure.FeeCapBelowMinimum ->
                R.string.wallet_bitcoin_send_fee_cap_minimum
            NativeBitcoinSendPreparationFailure.FeeCapTooLow ->
                R.string.wallet_bitcoin_send_fee_cap_too_low
            NativeBitcoinSendPreparationFailure.ActionPending ->
                R.string.wallet_bitcoin_send_action_pending
            NativeBitcoinSendPreparationFailure.WalletUnavailable ->
                R.string.wallet_bitcoin_send_unavailable
            NativeBitcoinSendPreparationFailure.InvalidRequest ->
                R.string.wallet_bitcoin_send_invalid_request
            NativeBitcoinSendPreparationFailure.Retry, null ->
                R.string.wallet_bitcoin_send_prepare_failed
        },
    )

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
                            refreshControllerState(resetReads = false)
                            statusView.text = getString(R.string.wallet_status_bitcoin_recovery_locked)
                            getString(R.string.wallet_bitcoin_send_ambiguous)
                        } else {
                            refreshControllerState(resetReads = false)
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

    private fun showPairDirectShakescapeForm() = showWalletActionForm(
        R.string.row_wallet_pair_direct_shakescape,
        listOf(WalletActionInput(R.string.wallet_direct_shakescape_endpoint_hint)),
    ) { values ->
        connectWalletOwnedDirectShakescape(values[0])
    }

    private fun connectWalletOwnedDirectShakescape(endpoint: String) {
        val (lease, handle) = directShakescapeContext() ?: run {
            directShakescapeStatusView.text = getString(R.string.wallet_direct_shakescape_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_connecting_direct_shakescape),
                resetReads = false,
            )
        ) return
        shakedexQueryStatusView.text = getString(R.string.wallet_direct_shakescape_connecting)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-shakescape-connect") {
            val result = NativeWalletBridge.connectWalletOwnedDirectShakescape(handle, endpoint)
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
                shakedexQueryStatusView.text = directShakescapeConnectionMessage(result)
            }
        }
    }

    private fun retryWalletOwnedDirectShakescapeListener() {
        val (lease, handle) = directShakescapeContext() ?: run {
            directShakescapeStatusView.text = getString(R.string.wallet_direct_shakescape_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_retrying_direct_shakescape),
                resetReads = false,
            )
        ) return
        directShakescapeStatusView.text = getString(R.string.wallet_direct_shakescape_host_retrying)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-shakescape-listener-retry") {
            val listening = NativeWalletBridge.retryWalletOwnedDirectShakescapeListener(handle)
            runOnUiThread {
                if (!walletOperationMayPublish(epoch, lease, handle, authorityGeneration)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = getString(
                    if (listening) R.string.wallet_direct_shakescape_host_retry_ready
                    else R.string.wallet_direct_shakescape_host_retry_failed,
                )
            }
        }
    }

    private fun disconnectWalletOwnedDirectShakescape() {
        val (lease, handle) = directShakescapeContext() ?: run {
            directShakescapeStatusView.text = getString(R.string.wallet_direct_shakescape_unavailable)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_disconnecting_direct_shakescape),
                resetReads = false,
            )
        ) return
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-shakescape-disconnect") {
            val disconnected = NativeWalletBridge.disconnectWalletOwnedDirectShakescape(handle)
            runOnUiThread {
                if (!walletOperationMayPublish(epoch, lease, handle, authorityGeneration)) {
                    releaseStorageLeaseAfterOperation(lease)
                    return@runOnUiThread
                }
                busy = false
                refreshControllerState(resetReads = false)
                shakedexQueryStatusView.text = getString(
                    if (disconnected) R.string.wallet_direct_shakescape_disconnected
                    else R.string.wallet_direct_shakescape_no_peer,
                )
            }
        }
    }

    private fun directShakescapeConnectionMessage(
        result: NativeWalletDirectShakescapeConnectResult?,
    ): String = when (result?.outcome) {
        NativeWalletDirectShakescapeConnectResult.Outcome.Connected -> getString(
            R.string.wallet_direct_shakescape_connected,
            result?.peerEndpoint ?: getString(R.string.common_unknown),
        )

        NativeWalletDirectShakescapeConnectResult.Outcome.Replaced -> getString(
            R.string.wallet_direct_shakescape_replaced,
            result?.peerEndpoint ?: getString(R.string.common_unknown),
        )

        NativeWalletDirectShakescapeConnectResult.Outcome.Unavailable ->
            getString(R.string.wallet_direct_shakescape_connect_unavailable)

        NativeWalletDirectShakescapeConnectResult.Outcome.Locked ->
            getString(R.string.wallet_direct_shakescape_connect_locked)

        NativeWalletDirectShakescapeConnectResult.Outcome.ConnectionFailed ->
            getString(R.string.wallet_direct_shakescape_connect_failed)

        NativeWalletDirectShakescapeConnectResult.Outcome.ExchangeFailed ->
            getString(R.string.wallet_direct_shakescape_exchange_failed)

        null -> getString(R.string.wallet_direct_shakescape_connect_bridge_failed)
    }

    private fun invalidValueActionInput() {
        valueActionStatusView.text = getString(R.string.wallet_value_actions_invalid)
    }

    private fun invalidShakedexQueryInput() {
        shakedexQueryStatusView.text = getString(R.string.wallet_shakedex_queries_invalid)
    }

    private fun valueActionContext(
        requiresShakedex: Boolean = true,
    ): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        val snapshot = latestReadSnapshot
        val status = NativeWalletBridge.status(handle)
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && snapshot != null && status != null && !status.locked &&
                status.hnsValueEnabled && (!requiresShakedex || status.shakedexEnabled) &&
                NativeWalletBridge.hasHnsValue(handle) &&
                latestReadSnapshotHandle == handle &&
                latestReadSnapshotAuthorityGeneration == walletAuthorityGeneration &&
                latestReadSnapshotEpoch == lifecycleEpoch &&
                unconfirmedDatabaseKey == null
        }
    }

    private fun directShakescapeContext(): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        val status = NativeWalletBridge.status(handle)
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && status != null && !status.locked &&
                unconfirmedDatabaseKey == null &&
                NativeWalletBridge.walletOwnedDirectShakescapeStatus(handle) != null
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
        foreground = walletSessionIsActive(),
        ownsCurrentLease = currentStorageLease() === lease,
        expectedHandle = handle,
        currentHandle = walletHandle,
        expectedAuthorityGeneration = authorityGeneration,
        currentAuthorityGeneration = walletAuthorityGeneration,
    ) && operationIsCurrent(epoch, lease)

    private fun prepareWalletValueAction(intent: NativeHnsValueIntent) {
        val requiresShakedex = when (intent) {
            is NativeHnsValueIntent.TransferName,
            is NativeHnsValueIntent.FinalizeName -> false
            is NativeHnsValueIntent.CreateFixedPriceOffer,
            is NativeHnsValueIntent.CancelOffer,
            is NativeHnsValueIntent.AcceptOffer,
            is NativeHnsValueIntent.FinalizePurchase,
            is NativeHnsValueIntent.RecoverName -> true
        }
        val (lease, handle) = valueActionContext(requiresShakedex) ?: run {
            valueActionStatusView.text = getString(R.string.wallet_value_actions_requires_sync)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_value_action),
                resetReads = false,
            )
        ) return
        valueActionStatusView.text = getString(R.string.wallet_value_actions_syncing)
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
            val synchronization = synchronizeHnsReadsWithRollbackFloor(handle)
            val snapshot = synchronization?.snapshot
            val approval = snapshot?.let {
                NativeWalletBridge.prepareHnsValueAction(handle, intent)
            }
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
                    if (snapshot == null) {
                        refreshControllerState()
                        val catchup = synchronization?.catchup
                        if (catchup == null) {
                            valueActionStatusView.text =
                                getString(R.string.wallet_value_actions_sync_failed)
                        } else {
                            renderReadCatchup(catchup)
                            valueActionStatusView.text = getString(
                                R.string.wallet_value_actions_catchup,
                                catchup.scannedHeight ?: catchup.birthdayHeight,
                                catchup.scanTargetHeight,
                            )
                        }
                    } else {
                        renderReadSnapshot(snapshot)
                        refreshControllerState(resetReads = false)
                        valueActionStatusView.text =
                            getString(R.string.wallet_value_actions_prepare_failed)
                    }
                } else {
                    snapshot?.let(::renderReadSnapshot)
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

    private fun prepareWalletSend(request: WalletHnsSendInput?) {
        val lease = currentStorageLease() ?: run {
            Log.w(TAG, "HNS send review was not started: no current storage lease")
            return
        }
        val handle = walletHandle
        val validRequest = request ?: run {
            Log.w(TAG, "HNS send review was rejected locally: invalid form input")
            sendStatusView.text = getString(R.string.wallet_send_invalid)
            return
        }
        val status = NativeWalletBridge.status(handle)
        if (
            handle == INVALID_HANDLE || status == null || status.locked ||
            !NativeWalletBridge.hasHnsValue(handle) ||
            unconfirmedDatabaseKey != null
        ) {
            Log.w(
                TAG,
                "HNS send review was not started: native value authority or synchronized state is unavailable",
            )
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }

        if (hasCurrentWalletReadSnapshot(handle)) {
            Log.i(TAG, "HNS send review is preparing from the current verified wallet snapshot")
            prepareWalletSendFromCurrentSnapshot(lease, handle, validRequest)
        } else {
            // A first receive or a resumed wallet commonly has no snapshot
            // yet. Review send must make that state visible and obtain one
            // bounded verified snapshot itself, rather than silently doing
            // nothing and forcing the user to discover a separate refresh.
            Log.i(TAG, "HNS send review needs a fresh verified wallet snapshot")
            synchronizeBeforePreparingWalletSend(lease, handle, validRequest)
        }
    }

    private fun walletHnsSendInput(
        recipient: String,
        amount: CharSequence?,
        maximumFee: CharSequence?,
    ): WalletHnsSendInput? {
        val amountBaseUnits = amount?.let(::parsePositiveHnsToBaseUnits)
        val maximumFeeBaseUnits = maximumFee?.let(::parsePositiveHnsToBaseUnits)
        if (
            recipient.toByteArray(Charsets.UTF_8).size !in 1..MAX_SEND_RECIPIENT_BYTES ||
            recipient.any { it.code !in 0x21..0x7e } ||
            amountBaseUnits == null || maximumFeeBaseUnits == null
        ) return null
        return WalletHnsSendInput(recipient, amountBaseUnits, maximumFeeBaseUnits)
    }

    /**
     * Keeps input-specific feedback inside the send dialog. The normal
     * `walletHnsSendInput` function remains the single canonical conversion
     * to base units; this wrapper only determines which field needs repair.
     */
    private fun validatedWalletHnsSendInput(
        recipientInput: EditText,
        amountInput: EditText,
        maximumFeeInput: EditText,
    ): WalletHnsSendInput? {
        val recipient = recipientInput.text?.toString().orEmpty()
        val amount = amountInput.text
        val maximumFee = maximumFeeInput.text
        val recipientValid =
            recipient.toByteArray(Charsets.UTF_8).size in 1..MAX_SEND_RECIPIENT_BYTES &&
                recipient.none { it.code !in 0x21..0x7e }
        val amountValid = amount?.let(::parsePositiveHnsToBaseUnits) != null
        val maximumFeeValid = maximumFee?.let(::parsePositiveHnsToBaseUnits) != null
        recipientInput.error = if (recipientValid) null else {
            getString(R.string.wallet_send_recipient_invalid)
        }
        amountInput.error = if (amountValid) null else getString(R.string.wallet_send_amount_invalid)
        maximumFeeInput.error = if (maximumFeeValid) null else {
            getString(R.string.wallet_send_maximum_fee_invalid)
        }
        if (!recipientValid || !amountValid || !maximumFeeValid) {
            Log.w(
                TAG,
                "HNS send review was rejected locally: invalid " +
                    "recipient=${!recipientValid} amount=${!amountValid} maximumFee=${!maximumFeeValid}",
            )
            return null
        }
        return walletHnsSendInput(recipient, amount, maximumFee)
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
            Log.w(TAG, "HNS send review cannot synchronize: native HNS reads are unavailable")
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_syncing_reads),
                resetReads = false,
            )
        ) {
            Log.w(TAG, "HNS send review snapshot synchronization could not start")
            return
        }
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
                    Log.w(TAG, "HNS send review did not obtain a verified wallet snapshot")
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
                    Log.i(TAG, "HNS send review received a fresh verified wallet snapshot")
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
            Log.w(TAG, "HNS send review lost its required synchronized value authority")
            sendStatusView.text = getString(R.string.wallet_send_requires_sync)
            return
        }
        if (
            !beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_send),
                resetReads = false,
            )
        ) {
            Log.w(TAG, "HNS send native preparation could not start")
            return
        }
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
                    Log.w(TAG, "HNS send native preparation returned no valid one-time approval")
                    busy = false
                    refreshControllerState(resetReads = false)
                    sendStatusView.text = getString(R.string.wallet_send_prepare_failed)
                } else {
                    Log.i(TAG, "HNS send native preparation produced a one-time approval for review")
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
        val pendingRefreshFloor = latestReadSnapshot?.height
        val pendingRecoveryAccountId = NativeWalletBridge.account(handle)?.accountId
        thread(name = "hns-wallet-send-broadcast") {
            val receipt = NativeWalletBridge.approveHnsValueAction(handle, approval.actionToken)
            approval.close()
            if (receipt != null) {
                // Persist the fail-closed recovery marker before attempting
                // the post-broadcast network refresh. It must survive an
                // Activity or process exit immediately after peer submission.
                persistPendingOutgoingRecovery(pendingRecoveryAccountId, pendingRefreshFloor)
            }
            // Native code keeps this controller unlocked only when the send
            // was rejected during final authenticated re-preparation, before
            // signing or broadcast could begin. A null receipt in that state
            // is therefore safe to re-sync and review again; every ambiguous
            // outcome remains locked by NativeWalletBridge.
            val retryAfterPreBroadcastSync =
                receipt == null && NativeWalletBridge.status(handle)?.locked == false
            var refreshedSnapshot: NativeWalletReadSnapshot? = null
            var verifiedAdmissionStatus: String? = null
            if (receipt != null) {
                for (attempt in 0 until HNS_POST_BROADCAST_VERIFICATION_ATTEMPTS) {
                    refreshedSnapshot = synchronizeHnsSnapshotWithRollbackFloor(handle)
                    verifiedAdmissionStatus = refreshedSnapshot?.transactions
                        ?.singleOrNull { transaction -> transaction.txid == receipt.txid }
                        ?.status
                        ?.takeIf { status -> status == "mempool" || status == "confirmed" }
                    if (verifiedAdmissionStatus != null || refreshedSnapshot == null) break
                    if (attempt + 1 < HNS_POST_BROADCAST_VERIFICATION_ATTEMPTS) {
                        Thread.sleep(HNS_POST_BROADCAST_VERIFICATION_INTERVAL_MILLIS)
                    }
                }
            }
            val snapshot = refreshedSnapshot
            val admissionStatus = verifiedAdmissionStatus
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
                        if (retryAfterPreBroadcastSync) {
                            Log.i(
                                TAG,
                                "HNS send was rejected before authorization or broadcast; refreshing verified history",
                            )
                            sendStatusView.text = getString(
                                R.string.wallet_send_pre_broadcast_syncing,
                            )
                            synchronizeWalletReads()
                        } else {
                            Log.w(
                                TAG,
                                "HNS send outcome is ambiguous; the native controller locked before another value action",
                            )
                            sendStatusView.text = getString(R.string.wallet_send_broadcast_ambiguous)
                        }
                    }
                    snapshot == null -> {
                        Log.w(
                            TAG,
                            "HNS transaction was submitted to peers, but post-broadcast wallet synchronization failed",
                        )
                        sendStatusView.text = getString(R.string.wallet_send_submitted_sync_failed)
                        pendingOutgoingSnapshotHeight = pendingRefreshFloor ?: 0L
                        maybeRefreshPendingOutgoingAfterNewBlock()
                    }
                    admissionStatus != null -> {
                        Log.i(TAG, "HNS transaction has verified network admission status=$admissionStatus")
                        renderReadSnapshot(snapshot)
                        sendStatusView.text = getString(
                            R.string.wallet_send_admission_verified,
                            receipt.txid,
                            admissionStatus,
                        )
                    }
                    else -> {
                        Log.w(
                            TAG,
                            "HNS transaction bytes were written to peers but mempool admission was not verified",
                        )
                        renderReadSnapshot(snapshot)
                        sendStatusView.text = getString(R.string.wallet_send_admission_unverified)
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

    private fun importWalletName(exactUtf8: ByteArray?) {
        val name = exactUtf8?.let { bytes ->
            try {
                bytes.toString(Charsets.UTF_8).takeIf { text ->
                    text.toByteArray(Charsets.UTF_8).contentEquals(bytes)
                }
            } finally {
                bytes.fill(0)
            }
        }
        if (name == null) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_invalid)
            return
        }
        importWalletNames(listOf(name))
    }

    private fun importWalletNames(names: List<String>) {
        val lease = currentStorageLease() ?: run {
            return
        }
        val initial = walletNameImportState(lease)
        val expected = initial.readState.authority
        if (expected == null || !walletNameImportMayBegin(expected, initial)) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_unavailable)
            return
        }
        if (
            names.isEmpty() || names.size > MAX_BULK_NAME_IMPORTS ||
            names.toSet().size != names.size ||
            names.any { !isExactWalletNameText(it) }
        ) {
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
            return
        }
        nameImportStatusView.text = getString(R.string.wallet_name_bulk_import_importing, names.size)
        val epoch = lifecycleEpoch
        val handle = expected.walletHandle
        thread(name = "hns-wallet-name-bulk-import") {
            val importedCount = NativeWalletBridge.importHnsNamesExactText(handle, names)
            // Exactly one synchronized refresh follows the complete atomic
            // import. Catch-up retains the previous balance and schedules its
            // normal bounded continuation instead of poisoning the session.
            val synchronization = if (importedCount == names.size) {
                synchronizeHnsReadsWithRollbackFloor(handle)
            } else null
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
                    importedCount != names.size -> {
                        refreshControllerState()
                        nameImportStatusView.text =
                            getString(R.string.wallet_name_import_failed)
                    }
                    synchronization?.snapshot != null -> {
                        refreshControllerState(resetReads = false)
                        renderReadSnapshot(synchronization.snapshot)
                        nameImportStatusView.text =
                            getString(R.string.wallet_name_bulk_import_success, importedCount)
                    }
                    synchronization?.catchup != null -> {
                        refreshControllerState(resetReads = false)
                        renderReadCatchup(synchronization.catchup)
                        nameImportStatusView.text = getString(
                            R.string.wallet_name_bulk_import_catching_up,
                            importedCount,
                        )
                        scheduleHnsCatchupRetry(
                            lease,
                            handle,
                            epoch,
                            expected.authorityGeneration,
                        )
                    }
                    else -> {
                        refreshControllerState(resetReads = false)
                        retainReadProjectionAfterRefreshFailure()
                        nameImportStatusView.text = getString(
                            R.string.wallet_name_bulk_import_refresh_pending,
                            importedCount,
                        )
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
            refreshDirectShakescapeStatus()
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
            refreshDirectShakescapeStatus()
            renderWalletDashboard()
            return
        }
        statusView.text = getString(R.string.wallet_status_unlocked)
        val account = NativeWalletBridge.account(walletHandle)
        restorePendingOutgoingRecovery(account?.accountId)
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
        if (!walletBitcoinSyncInProgress) resetBitcoinProjection()
        refreshDirectShakescapeStatus()
        renderWalletDashboard()
    }

    private fun attemptReadBootstrap(lease: WalletStorageOwnershipGate.Lease) {
        val expectedAuthority = walletReadBootstrapState(lease).authority ?: return
        if (!walletReadBootstrapMayInstall(expectedAuthority, walletReadBootstrapState(lease))) return
        if (!beginOperation(lease, getString(R.string.wallet_status_preparing_unlock))) return
        val epoch = lifecycleEpoch
        thread(name = "hns-wallet-direct-install") {
            val installed = runCatching {
                val floor = keyStore.directHnsRollbackFloorForOpen()
                // Prefer the browser's canonical stream through this wallet's
                // exact birthday. Native independently replays every header,
                // and direct wallet peers must still establish currentness.
                // Re-supplying the stream is idempotent after a persisted
                // wallet checkpoint has advanced beyond the birthday.
                val birthdayHeight = NativeWalletBridge.birthdayHeight(
                    expectedAuthority.walletHandle,
                )
                val bootstrap = if (walletDirectHnsNeedsGenesisBootstrap(walletNetwork)) {
                    birthdayHeight?.let { birthday ->
                        HeaderSnapshotInstaller.exportWalletBirthdayBootstrap(
                            context = applicationContext,
                            dataDir = filesDir.absolutePath,
                            network = walletNetwork.id,
                            birthdayHeight = birthday,
                        )
                    }
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
                runPendingWalletUnlockIfReady()
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
            durableWalletStoragePresent = true
            statusView.text = getString(R.string.wallet_status_delete_cleanup_pending)
            accountView.text = getString(R.string.wallet_account_unavailable)
            resetReadProjection(R.string.wallet_reads_unavailable)
        } else if (!busy && walletHandle == INVALID_HANDLE && storage.second) {
            durableWalletStoragePresent = true
            // Reopening establishes the only controller state eligible for a
            // future product-owned scoped read credential. This screen never
            // sources an endpoint or credential from user-controlled input.
            openExistingWallet()
        } else if (!busy && walletHandle == INVALID_HANDLE) {
            durableWalletStoragePresent = false
            showNoWallet()
        }
    }

    private fun revokeStorageOwnership(owner: WalletStorageOwnershipGate.Owner) {
        if (storageOwner !== owner) return
        retainingInAppWalletSession = false
        walletBackgroundRetirement?.set(false)
        walletBackgroundRetirement = null
        lifecycleEpoch += 1
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        stopWalletForegroundSyncService()
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
        if (!busy && !walletBitcoinSyncInProgress && lease != null) {
            releaseStorageLeaseAfterOperation(lease)
        }
        statusView.text = getString(R.string.wallet_status_unavailable)
        accountView.text = getString(R.string.wallet_account_unavailable)
        resetReadProjection(R.string.wallet_reads_unavailable)
    }

    private fun currentStorageLease(): WalletStorageOwnershipGate.Lease? {
        val owner = storageOwner ?: return null
        val lease = storageLease ?: return null
        return lease.takeIf {
            walletSessionIsActive() &&
                it.owner === owner &&
                ProcessWalletStorageOwnership.isCurrent(owner, it)
        }
    }

    private fun walletSessionIsActive(): Boolean = foreground || retainingInAppWalletSession

    private fun hasActiveWalletHnsSynchronization(): Boolean =
        walletHnsSyncInProgress || hnsCatchupRetry?.get() == true

    private fun startWalletForegroundSyncService(chain: String) {
        if (walletForegroundSyncServiceActive) return
        walletForegroundSyncServiceActive = WalletSyncForegroundService.start(this)
        if (walletForegroundSyncServiceActive) {
            Log.i(TAG, "Started visible foreground protection for direct $chain wallet synchronization")
        } else {
            Log.w(TAG, "Direct $chain wallet synchronization will stop if the app leaves foreground")
        }
    }

    private fun stopWalletForegroundSyncService() {
        if (!walletForegroundSyncServiceActive) return
        walletForegroundSyncServiceActive = false
        WalletSyncForegroundService.stop(this)
        Log.i(TAG, "Stopped visible foreground protection for direct wallet synchronization")
    }

    private fun finishWalletForegroundSyncIfIdle() {
        if (hasActiveWalletHnsSynchronization() || walletBitcoinSyncInProgress) return
        stopWalletForegroundSyncService()
        if (retainingInAppWalletSession && !foreground && !isAppForeground()) {
            scheduleWalletRetirementIfApplicationBackgrounds()
        }
    }

    /**
     * Continue only public direct-peer synchronization for an already
     * confirmed and unlocked wallet. A send, deletion, restore, or any other
     * wallet mutation still follows the normal onStop teardown path.
     */
    private fun mayRetainInAppWalletSession(): Boolean {
        val lease = currentStorageLease() ?: return false
        val handle = walletHandle
        if (
            isFinishing || isDestroyed || handle == INVALID_HANDLE ||
                unconfirmedDatabaseKey != null || (busy && !walletHnsSyncInProgress)
        ) {
            return false
        }
        if (hasActiveWalletHnsSynchronization()) {
            // A bounded direct sync owns the native controller mutex. Calling
            // status() or hasHnsReads() here would contend with that exact
            // scan and can return no result, incorrectly turning an active
            // public sync into a teardown. Retaining this narrow read-only
            // exception outside the app additionally requires the visible
            // foreground-service notification to have been started; all
            // mutations remain excluded by the busy check above.
            return walletBackgroundHnsSyncMayRetain(
                hasActiveReadOnlyHnsSync = true,
                foregroundServiceActive = walletForegroundSyncServiceActive,
            ) &&
                ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)
        }
        if (walletBitcoinSyncInProgress) {
            // A user-started read-only Bitcoin scan may cross an app-background
            // transition only while its visible data-sync notification is
            // active. Lock/delete still explicitly stop Kyoto and retire the
            // controller through the ordinary lifecycle path.
            return walletForegroundSyncServiceActive &&
                ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)
        }
        if (!walletIdleSessionMayRetainAcrossScreen(browserNavigationRequested)) {
            // MainActivity renders attacker-controlled website content. Even
            // though no wallet WebView bridge is installed, retire idle
            // signing authority before that content remains visible. This
            // limits a future WebView/native compromise to a locked wallet.
            return false
        }
        return NativeWalletBridge.hasHnsReads(handle) &&
            NativeWalletBridge.status(handle)?.locked == false &&
            ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)
    }

    /**
     * Keep an already-open controller across a brief app switch. The old
     * 250-ms lifecycle-only delay retired it almost immediately, so returning
     * from another app rebuilt the controller and peer state and presented a
     * normal freshness check like a new wallet synchronization. The token is
     * cancelled by onStart; if the user stays away, the unlocked controller
     * and its storage lease are still retired after this bounded grace period.
     *
     * A user-started HNS synchronization is different: its foreground service
     * retains the controller for the entire bounded scan/catch-up chain. If it
     * finishes while backgrounded, finishWalletForegroundSyncIfIdle starts
     * this same grace period instead of tearing the controller down at once.
     */
    private fun scheduleWalletRetirementIfApplicationBackgrounds() {
        walletBackgroundRetirement?.set(false)
        val retirement = AtomicBoolean(true)
        walletBackgroundRetirement = retirement
        thread(name = "hns-wallet-background-retirement") {
            try {
                Thread.sleep(WALLET_APP_SWITCH_RETENTION_MILLIS)
            } catch (_: InterruptedException) {
                retirement.set(false)
            }
            runOnUiThread {
                if (
                    retirement.get() && walletBackgroundRetirement === retirement &&
                        retainingInAppWalletSession && !foreground && !isAppForeground() &&
                        !walletBackgroundSynchronizationMayRetain(
                            hasActiveReadOnlyHnsSync = hasActiveWalletHnsSynchronization(),
                            hasActiveReadOnlyBitcoinSync = walletBitcoinSyncInProgress,
                            foregroundServiceActive = walletForegroundSyncServiceActive,
                        )
                ) {
                    walletBackgroundRetirement = null
                    retireRetainedInAppWalletSession()
                }
            }
        }
    }

    private fun isAppForeground(): Boolean =
        (application as? HnsDaneApplication)?.isAppForeground == true

    /** Finish the ordinary locked-controller retirement after an actual app-background transition. */
    private fun retireRetainedInAppWalletSession() {
        if (!retainingInAppWalletSession) return
        retainingInAppWalletSession = false
        lifecycleEpoch += 1
        hnsCatchupRetry?.set(false)
        hnsCatchupRetry = null
        stopWalletForegroundSyncService()
        storageOwner?.let(ProcessWalletStorageOwnership::retire)
        storageOwner = null
        dismissWalletDeletionDialog()
        dismissSendApproval(rejectNative = false)
        dismissValueApproval(rejectNative = false)
        clearRestoreInput()
        clearNameImportInput()
        clearSendInputs()
        recoveryView.clearSecret()
        val lease = storageLease
        val retirementStarted = lease != null && retireControllerAfterNativeOperation(lease)
        if (!retirementStarted) destroyController()
        resetReadProjection(R.string.wallet_reads_unavailable)
        if (!busy && !walletBitcoinSyncInProgress && lease != null) {
            releaseStorageLeaseAfterOperation(lease)
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
     * A bounded RPC may still own a native controller domain when onStop or a
     * replacement Activity revokes this owner. Retire on a worker and retain
     * the storage lease until native destruction finishes; the stale operation
     * callback is explicitly denied release authority for the handed-off lease.
     */
    private fun retireControllerAfterNativeOperation(
        lease: WalletStorageOwnershipGate.Lease,
    ): Boolean {
        if (!busy && !walletBitcoinSyncInProgress) return false
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
        val operationBusy = busy || hasActiveWalletHnsSynchronization()
        if (
            !walletControllerOperationMayBegin(
                retirementFailed = retirementFailed,
                busy = operationBusy,
                ownsCurrentLease = currentStorageLease() === lease,
            )
        ) {
            when {
                retirementFailed -> showControllerRetirementUncertain()
                operationBusy -> showWalletBusyFeedback()
                else -> statusView.text = getString(R.string.wallet_status_unavailable)
            }
            return false
        }
        busy = true
        statusView.text = status
        if (resetReads) resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        renderWalletDashboard()
        return true
    }

    private fun showWalletBusyFeedback() {
        Toast.makeText(this, R.string.wallet_action_busy, Toast.LENGTH_SHORT).show()
    }

    private fun showNoWallet() {
        WalletHnsLiveSyncPresentationCache.resumeAutomaticSync(walletNetwork.id)
        durableWalletStoragePresent = false
        walletOpenDeferredUntilDeviceUnlock = false
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
        loadedTrackedNames = emptyList()
        trackedNamePageOffset = 0
        recentActivityPageOffset = 0
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
        if (pendingOutgoingSnapshotHeight != null) {
            readStatusView.text = getString(R.string.wallet_pending_outgoing_recovery)
            balanceView.text = getString(R.string.wallet_pending_outgoing_balance_unavailable)
        }
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
        if (pendingOutgoingSnapshotHeight != null) {
            sendStatusView.text = getString(R.string.wallet_pending_outgoing_actions_disabled)
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
     * An active direct-peer round must not look like an instruction to start
     * another synchronization. Keep an existing authenticated projection on
     * screen, but replace the empty pre-sync placeholders when this wallet has
     * never published a verified snapshot.
     */
    private fun showReadProjectionSynchronizationPendingIfNeeded() {
        if (latestReadSnapshot == null) {
            balanceView.text = getString(R.string.wallet_reads_balance_syncing)
        }
        sendStatusView.text = getString(R.string.wallet_send_syncing)
    }

    /**
     * Preserve a verified projection for display while making it unusable as
     * current value authority. The exact handle/generation/epoch join is what
     * gates send preparation, so clearing only that join is fail-closed
     * without replacing useful balance and history with an empty screen.
     */
    private fun invalidateReadSnapshotAuthority() {
        latestReadSnapshotHandle = INVALID_HANDLE
        latestReadSnapshotAuthorityGeneration = 0L
        latestReadSnapshotEpoch = 0L
    }

    private fun retainReadProjectionAfterRefreshFailure() {
        invalidateReadSnapshotAuthority()
        if (latestReadSnapshot == null) {
            resetReadProjection(R.string.wallet_reads_sync_failed)
        } else {
            readStatusView.text = getString(R.string.wallet_reads_refresh_failed_retained)
            renderWalletDashboard()
        }
    }

    /**
     * Show authenticated direct-peer catch-up without retaining any partial
     * wallet projection. The native controller persists this progress, but a
     * balance and guarded value actions remain unavailable until a later
     * bounded sync reaches the exact verified tip.
     */
    private fun renderReadCatchup(progress: NativeWalletHnsCatchupProgress) {
        walletHnsJourney.catchupObserved()
        resetReadProjection(R.string.wallet_reads_catching_up)
        showReadProjectionSynchronizationPendingIfNeeded()
        if (progress.scannedHeight == null && progress.headerTipHeight < progress.birthdayHeight) {
            readStatusView.text = getString(
                R.string.wallet_reads_catching_up_before_birthday,
                progress.headerTipHeight,
                progress.birthdayHeight,
            )
            renderWalletDashboard()
            return
        }
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
    private fun startLiveHnsSyncProgressPolling(
        handle: Long,
        presentationLease: WalletHnsLiveSyncPresentationLease,
    ): AtomicBoolean {
        liveHnsSyncPoller?.set(false)
        return AtomicBoolean(true).also { poller ->
            liveHnsSyncPoller = poller
            thread(name = "hns-wallet-live-sync-progress") {
                var loggedStage: NativeWalletHnsLiveSyncProgress.Stage? = null
                var loggedScanHeight: Long? = null
                while (poller.get()) {
                    NativeWalletBridge.liveHnsSynchronizationProgress(handle)?.let { progress ->
                        WalletHnsLiveSyncPresentationCache.publishLive(presentationLease, progress)
                        val scanHeight = progress.scannedHeight
                        if (
                            progress.stage != loggedStage ||
                                (scanHeight != null &&
                                    (loggedScanHeight == null || scanHeight - loggedScanHeight!! >= 256L))
                        ) {
                            Log.i(
                                TAG,
                                "Direct HNS live progress stage=${progress.stage} " +
                                    "headers=${progress.headerTipHeight} scan=${scanHeight ?: progress.birthdayHeight} " +
                                    "target=${progress.scanTargetHeight}",
                            )
                            loggedStage = progress.stage
                            loggedScanHeight = scanHeight
                        }
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
                    if (presentation != lastPresentation) {
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
                                beginStorageOwnershipSessionIfReady()
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
            WalletHnsLiveSyncPresentation.Preparing -> {
                statusView.text = getString(R.string.wallet_status_sync_handoff)
                readStatusView.text = getString(R.string.wallet_reads_syncing)
                showReadProjectionSynchronizationPendingIfNeeded()
            }
            is WalletHnsLiveSyncPresentation.Live -> renderLiveHnsSyncProgress(presentation.progress)
            is WalletHnsLiveSyncPresentation.Catchup -> renderCachedHnsCatchup(presentation.progress)
            is WalletHnsLiveSyncPresentation.Cancelling -> {
                statusView.text = getString(R.string.wallet_status_stopping_sync)
                readStatusView.text = getString(R.string.wallet_reads_stopping_at_safe_checkpoint)
            }
            null -> Unit
        }
    }

    private fun renderLiveHnsSyncProgress(progress: NativeWalletHnsLiveSyncProgress) {
        walletHnsJourney.catchupObserved()
        showReadProjectionSynchronizationPendingIfNeeded()
        readStatusView.text = when (progress.stage) {
            NativeWalletHnsLiveSyncProgress.Stage.Connecting -> getString(
                R.string.wallet_reads_live_connecting,
                progress.headerTipHeight,
            )

            NativeWalletHnsLiveSyncProgress.Stage.Headers -> getString(
                R.string.wallet_reads_live_headers,
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
        showReadProjectionSynchronizationPendingIfNeeded()
        if (progress.scannedHeight == null && progress.headerTipHeight < progress.birthdayHeight) {
            readStatusView.text = getString(
                R.string.wallet_reads_catching_up_before_birthday,
                progress.headerTipHeight,
                progress.birthdayHeight,
            )
            return
        }
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
        loadedTrackedNames = snapshot.trackedNames.take(MAX_VISIBLE_READ_ITEMS)
        trackedNamePageOffset = 0
        recentActivityPageOffset = 0
        localPaymentReceiveTarget = snapshot.paymentReceiveTarget
        latestReadSnapshotHandle = walletHandle
        latestReadSnapshotAuthorityGeneration = walletAuthorityGeneration
        latestReadSnapshotEpoch = lifecycleEpoch
        readStatusView.text = getString(R.string.wallet_reads_ready, snapshot.height)
        val balance = snapshot.hnsBalanceProjection()
        if (balance.hasPendingOutgoing) {
            pendingOutgoingSnapshotHeight = snapshot.height
            persistPendingOutgoingRecovery(snapshot.height)
        } else {
            pendingOutgoingSnapshotHeight = null
            pendingOutgoingRefreshAttemptedHeight = null
            clearPendingOutgoingRecovery()
        }
        balanceView.textSize = if (balance.hasPendingOutgoing) 18f else 24f
        balanceView.text = when {
            balance.hasPendingOutgoing -> getString(
                R.string.wallet_reads_balance_with_pending,
                formatHnsBaseUnits(balance.spendableBaseUnits),
                formatHnsBaseUnits(balance.pendingOutgoingBaseUnits),
            )
            else -> getString(
                R.string.wallet_reads_balance,
                formatHnsBaseUnits(balance.spendableBaseUnits),
            )
        }
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
            val entries = formatWalletTransactions(visibleTransactions)
            appendRemainingCount(entries, snapshot.transactions.size - visibleTransactions.size)
        }
        renderLoadedTrackedNames(snapshot.trackedNameCount)
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
        maybeRefreshPendingOutgoingAfterNewBlock()
    }

    private fun startPendingOutgoingRefreshObserver() {
        if (browserSyncObservation != null) return
        val app = application as? HnsDaneApplication ?: return
        browserSyncObservation = app.observeSync { snapshot ->
            val progress = HnsSyncProgress.fromJson(snapshot.statusJson)
            val height = progress.bestHeight
            if (
                progress.network != walletNetwork.id ||
                    !progress.isCurrent ||
                    height == null
            ) return@observeSync
            runOnUiThread {
                latestObservedBrowserHeaderHeight = maxOf(
                    latestObservedBrowserHeaderHeight ?: 0L,
                    height,
                )
                maybeRefreshPendingOutgoingAfterNewBlock()
            }
        }
    }

    private fun maybeRefreshPendingOutgoingAfterNewBlock() {
        val refreshHeight = walletPendingOutgoingRefreshHeight(
            pendingSnapshotHeight = pendingOutgoingSnapshotHeight,
            observedHeaderHeight = latestObservedBrowserHeaderHeight,
            attemptedHeaderHeight = pendingOutgoingRefreshAttemptedHeight,
        ) ?: return
        val handle = walletHandle
        if (
            !foreground || busy || walletHnsSyncInProgress ||
                currentStorageLease() == null || handle == INVALID_HANDLE ||
                NativeWalletBridge.status(handle)?.locked != false ||
                !NativeWalletBridge.hasHnsReads(handle)
        ) return
        pendingOutgoingRefreshAttemptedHeight = refreshHeight
        Log.i(
            TAG,
            "Refreshing pending outgoing transaction after verified Handshake height $refreshHeight",
        )
        synchronizeWalletReads()
    }

    private fun renderLoadedTrackedNames(total: Int) {
        trackedNamesView.text = if (loadedTrackedNames.isEmpty()) {
            getString(R.string.wallet_reads_names_empty)
        } else {
            val entries = loadedTrackedNames.joinToString("\n\n", transform = ::walletNameSummary)
            entries + "\n\n" + getString(
                R.string.wallet_name_page_position,
                trackedNamePageOffset + 1,
                trackedNamePageOffset + loadedTrackedNames.size,
                total,
            )
        }
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
        if (deleted) {
            WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)
            clearPendingOutgoingRecovery()
        }
        return deleted
    }

    private fun restorePendingOutgoingRecovery(accountId: String?) {
        val preferences = getSharedPreferences(PENDING_OUTGOING_PREFS, MODE_PRIVATE)
        if (!accountId.isNullOrBlank() &&
            preferences.getString(PENDING_OUTGOING_ACCOUNT, null) == accountId
        ) {
            pendingOutgoingSnapshotHeight = preferences.getLong(PENDING_OUTGOING_HEIGHT, 0L)
        } else {
            pendingOutgoingSnapshotHeight = null
            pendingOutgoingRefreshAttemptedHeight = null
        }
    }

    private fun persistPendingOutgoingRecovery(height: Long?) {
        persistPendingOutgoingRecovery(
            NativeWalletBridge.account(walletHandle)?.accountId,
            height,
        )
    }

    private fun persistPendingOutgoingRecovery(accountId: String?, height: Long?) {
        if (accountId.isNullOrBlank()) return
        getSharedPreferences(PENDING_OUTGOING_PREFS, MODE_PRIVATE).edit()
            .putString(PENDING_OUTGOING_ACCOUNT, accountId)
            .putLong(PENDING_OUTGOING_HEIGHT, height ?: 0L)
            .commit()
    }

    private fun clearPendingOutgoingRecovery() {
        getSharedPreferences(PENDING_OUTGOING_PREFS, MODE_PRIVATE).edit().clear().commit()
        pendingOutgoingSnapshotHeight = null
        pendingOutgoingRefreshAttemptedHeight = null
    }

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

    private fun restoreBirthdayInput(): EditText = EditText(this).apply {
        hint = getString(R.string.wallet_restore_birthday_hint)
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(InputFilter.LengthFilter(10))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setAutofillHints(null)
        setSingleLine(true)
        isSaveEnabled = false
        freezesText = false
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

    private fun takeRestoreInput(input: EditText): CharArray? {
        val editable = input.text ?: return null
        if (editable.isEmpty() || editable.length > MAX_RECOVERY_CHARACTERS) {
            wipeEditable(editable)
            return null
        }
        val phrase = CharArray(editable.length) { index -> editable[index] }
        wipeEditable(editable)
        return phrase
    }

    private fun clearRestoreInput() {
        restoreInput?.let { input ->
            input.text?.let(::wipeEditable)
            input.clearFocus()
        }
        restoreInput = null
    }

    private fun clearNameImportInput() {
        nameImportInput?.let { input ->
            input.text?.let(::wipeEditable)
            input.clearFocus()
        }
        nameImportInput = null
    }

    private fun clearSendInputs() {
        listOfNotNull(sendRecipientInput, sendAmountInput, sendMaximumFeeInput).forEach { input ->
            input.text?.let(::wipeEditable)
            input.clearFocus()
        }
        sendRecipientInput = null
        sendAmountInput = null
        sendMaximumFeeInput = null
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
        const val MAX_BULK_NAME_IMPORTS = 10_000
        const val MAX_BULK_NAME_FILE_BYTES = 1024 * 1024
        const val MAX_SEND_RECIPIENT_BYTES = 512
        const val DEFAULT_HNS_MAXIMUM_FEE = "0.05"
        const val MAX_VALUE_ACTION_INPUT_CHARACTERS = 512
        const val DEFAULT_LISTING_LIFETIME_SECONDS = 7 * 24 * 60 * 60L
        const val DEFAULT_OFFER_PAGE_SIZE = 32
        const val DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS = 250L
        const val DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS = 20
        const val DIRECT_SHAKESCAPE_LISTEN_PORT = 12_038
        const val LIVE_HNS_SYNC_PROGRESS_POLL_MILLIS = 500L
        const val BITCOIN_SYNC_PROGRESS_POLL_MILLIS = 1_000L
        const val HNS_CATCHUP_RETRY_DELAY_MILLIS = 2_000L
        const val HNS_POST_BROADCAST_VERIFICATION_ATTEMPTS = 3
        const val HNS_POST_BROADCAST_VERIFICATION_INTERVAL_MILLIS = 1_000L
        const val DIRECT_HNS_MAX_HEADER_AGREEMENT_RECOVERIES_PER_SYNC = 5
        const val NUL_CHARACTER = "\u0000"
        const val PENDING_OUTGOING_PREFS = "wallet_pending_outgoing_recovery"
        const val PENDING_OUTGOING_ACCOUNT = "account_id"
        const val PENDING_OUTGOING_HEIGHT = "snapshot_height"
    }
}

/**
 * Long enough for an ordinary app switch without retaining unlocked wallet
 * authority indefinitely when Shakescape remains in the background.
 */
internal const val WALLET_APP_SWITCH_RETENTION_MILLIS = 30_000L

/** Idle signing authority never survives an explicit Wallet -> Browser transition. */
internal fun walletIdleSessionMayRetainAcrossScreen(browserNavigationRequested: Boolean): Boolean =
    !browserNavigationRequested

internal fun walletHnsPaymentActionsAvailable(
    actionsAvailable: Boolean,
    hasPendingOutgoing: Boolean,
): Boolean = actionsAvailable && !hasPendingOutgoing

internal fun walletPendingUnlockMayRun(
    requested: Boolean,
    foreground: Boolean,
    busy: Boolean,
    hasLease: Boolean,
    hasController: Boolean,
    hasUnconfirmedRecovery: Boolean,
): Boolean =
    requested && foreground && !busy && hasLease && hasController && !hasUnconfirmedRecovery

internal enum class WalletPendingPaymentContinuation {
    None,
    Wait,
    Unlock,
    Synchronize,
    Present,
}

/**
 * Keeps a scanned public payment URI alive while the scanner transition
 * causes the protected wallet controller to reopen. External deep links do
 * not implicitly unlock; only the in-wallet camera action carries that user
 * intent across the lifecycle boundary.
 */
internal fun walletPendingPaymentContinuation(
    hasPendingPayment: Boolean,
    resumeAfterScanner: Boolean,
    foreground: Boolean,
    windowHasFocus: Boolean,
    busy: Boolean,
    dialogVisible: Boolean,
    hasController: Boolean,
    controllerUnlocked: Boolean,
    hasHnsValue: Boolean,
    hasCurrentSnapshot: Boolean,
    hasPendingOutgoing: Boolean,
): WalletPendingPaymentContinuation = when {
    !hasPendingPayment -> WalletPendingPaymentContinuation.None
    !foreground || !windowHasFocus || busy || dialogVisible ->
        WalletPendingPaymentContinuation.Wait
    !hasController || !controllerUnlocked -> if (resumeAfterScanner) {
        WalletPendingPaymentContinuation.Unlock
    } else {
        WalletPendingPaymentContinuation.Wait
    }
    !hasHnsValue -> WalletPendingPaymentContinuation.Wait
    hasPendingOutgoing -> WalletPendingPaymentContinuation.Wait
    !hasCurrentSnapshot -> if (resumeAfterScanner) {
        WalletPendingPaymentContinuation.Synchronize
    } else {
        WalletPendingPaymentContinuation.Wait
    }
    else -> WalletPendingPaymentContinuation.Present
}

internal fun estimateBitcoinSyncRemainingMillis(
    completedWork: Long,
    totalWork: Long,
    baselineWork: Long,
    measurementMillis: Long,
): Long? {
    val measuredWork = completedWork - baselineWork
    val remainingWork = totalWork - completedWork
    if (
        totalWork <= 0L || completedWork !in 0 until totalWork || baselineWork < 0L ||
        measuredWork < 32L || measurementMillis < 5_000L || remainingWork <= 0L
    ) return null
    val estimate = measurementMillis.toDouble() * remainingWork.toDouble() / measuredWork.toDouble()
    return estimate.takeIf { it.isFinite() && it in 1.0..604_800_000.0 }?.toLong()
}

internal fun walletBitcoinOperationMayStart(bitcoinSyncInProgress: Boolean): Boolean =
    !bitcoinSyncInProgress

internal fun walletPullToSyncMayStart(
    windowHasFocus: Boolean,
    knownDialogVisible: Boolean,
): Boolean = windowHasFocus && !knownDialogVisible

internal fun walletPageOffset(
    requestedOffset: Int,
    totalItems: Int,
    pageSize: Int,
): Int {
    if (totalItems <= 0 || pageSize <= 0) return 0
    val lastPageOffset = ((totalItems - 1) / pageSize) * pageSize
    return requestedOffset.coerceIn(0, lastPageOffset)
}

internal fun walletBackgroundSynchronizationMayRetain(
    hasActiveReadOnlyHnsSync: Boolean,
    hasActiveReadOnlyBitcoinSync: Boolean,
    foregroundServiceActive: Boolean,
): Boolean =
    foregroundServiceActive && (hasActiveReadOnlyHnsSync || hasActiveReadOnlyBitcoinSync)

internal fun formatBitcoinSyncDuration(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${remainder}s"
        else -> "${remainder}s"
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
    private val detachmentPolicy = OneShotSecretReparentRetention()

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

    fun detachForDashboardReparent() {
        val currentParent = parent as? ViewGroup ?: return
        check(detachmentPolicy.arm())
        currentParent.removeView(this)
        // removeView dispatches detachment synchronously for the attached
        // dashboard. Disarm defensively as well so a detached parent can never
        // carry the one-shot exception into a later real screen teardown.
        detachmentPolicy.disarm()
    }

    override fun onDetachedFromWindow() {
        if (detachmentPolicy.shouldClearSecret()) clearSecret()
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

/** Retains a secret across exactly one synchronous dashboard reparent. */
internal class OneShotSecretReparentRetention {
    private var armed = false

    fun arm(): Boolean {
        if (armed) return false
        armed = true
        return true
    }

    fun shouldClearSecret(): Boolean {
        if (!armed) return true
        armed = false
        return false
    }

    fun disarm() {
        armed = false
    }
}
