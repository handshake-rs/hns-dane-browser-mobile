package com.denuoweb.hnsdane.ui

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import android.provider.Settings
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
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import java.net.Inet4Address
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
import com.denuoweb.hnsdane.wallet.NativeBitcoinActivity
import com.denuoweb.hnsdane.wallet.NativeBitcoinActivityPage
import com.denuoweb.hnsdane.wallet.NativeBitcoinHtlcFundingApproval
import com.denuoweb.hnsdane.wallet.NativeBitcoinSyncProgress
import com.denuoweb.hnsdane.wallet.NativeBtcForHnsOfferApproval
import com.denuoweb.hnsdane.wallet.NativeDirectOfferSummary
import com.denuoweb.hnsdane.wallet.NativeDirectOfferTakeApproval
import com.denuoweb.hnsdane.wallet.NativeDirectOfferTakePreparation
import com.denuoweb.hnsdane.wallet.NativeHnsForBtcOfferApproval
import com.denuoweb.hnsdane.wallet.NativeShakedexNameOffer
import com.denuoweb.hnsdane.wallet.NativeShakedexQueryResult
import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary
import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionStatus
import com.denuoweb.hnsdane.wallet.NativeHnsHtlcFundingApproval
import com.denuoweb.hnsdane.wallet.NativeSwapSettlementApproval
import com.denuoweb.hnsdane.wallet.NativeShakedexQuery
import com.denuoweb.hnsdane.wallet.NativeWalletDirectShakescapeConnectResult
import com.denuoweb.hnsdane.wallet.NativeWalletDirectShakescapeStatus
import com.denuoweb.hnsdane.wallet.directShakescapeControls
import com.denuoweb.hnsdane.wallet.NativeWalletBridge
import com.denuoweb.hnsdane.wallet.NativeWalletHnsCatchupProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsLiveSyncProgress
import com.denuoweb.hnsdane.wallet.NativeWalletHnsSynchronization
import com.denuoweb.hnsdane.wallet.NativeWalletName
import com.denuoweb.hnsdane.wallet.NativeWalletPaymentReceiveTarget
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.NativeWalletStatus
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
import com.denuoweb.hnsdane.wallet.WalletNetworkTransport
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
import com.denuoweb.hnsdane.wallet.parsePositiveHnsToBaseUnits
import com.denuoweb.hnsdane.wallet.parseWalletRestoreBirthday
import com.denuoweb.hnsdane.wallet.walletDeleteConfirmationMatches
import com.denuoweb.hnsdane.wallet.walletDatabaseArtifacts
import com.denuoweb.hnsdane.wallet.walletDashboardMode
import com.denuoweb.hnsdane.wallet.walletControllerOperationMayBegin
import com.denuoweb.hnsdane.wallet.walletCellularDataWarningVisible
import com.denuoweb.hnsdane.wallet.walletDeletionMayProceed
import com.denuoweb.hnsdane.wallet.walletNameImportMayBegin
import com.denuoweb.hnsdane.wallet.walletNameImportMayPublish
import com.denuoweb.hnsdane.wallet.walletPendingOutgoingRefreshHeight
import com.denuoweb.hnsdane.wallet.walletBackgroundHnsSyncMayRetain
import com.denuoweb.hnsdane.wallet.walletOperationRetainsStorageLease
import com.denuoweb.hnsdane.wallet.walletReadMayPublish
import com.denuoweb.hnsdane.wallet.walletReadBootstrapMayInstall
import com.denuoweb.hnsdane.wallet.walletReadCodeLabel
import com.denuoweb.hnsdane.wallet.walletTransactionStatusLabel
import com.denuoweb.hnsdane.wallet.walletSetupMayInspectStorage
import com.denuoweb.hnsdane.wallet.walletStorageNamespace
import java.io.File
import java.io.Closeable
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

private const val SHOW_SHAKEDEX_WALLET_CARD = true

/** Exact received-asset commitment signed by a direct-offer taker. */
internal fun directOfferTakeRequiredFunding(receivedAmount: Long, feeReserve: Long): Long? =
    if (receivedAmount > feeReserve && feeReserve > 0L) {
        receivedAmount
    } else {
        null
    }

/** Dedicated native controller for one complete Handshake wallet and Shakedex account. */
class WalletActivity : ComponentActivity() {
    private lateinit var keyStore: AndroidWalletKeyStore
    private lateinit var walletNetwork: HandshakeNetwork
    private lateinit var walletDatabaseFile: File
    private lateinit var walletStoragePath: String
    private lateinit var statusView: TextView
    private lateinit var cellularDataWarningView: TextView
    private lateinit var accountView: TextView
    private lateinit var readStatusView: TextView
    private lateinit var balanceView: TextView
    private lateinit var paymentReceiveView: TextView
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
    private lateinit var bitcoinActivityView: TextView
    private lateinit var valueActionStatusView: TextView
    private lateinit var shakedexQueryStatusView: TextView
    private lateinit var shakedexExecutionStatusView: TextView
    private lateinit var directShakescapeStatusView: TextView
    private var restoreInput: EditText? = null
    private lateinit var recoveryView: RecoveryPhraseView
    private lateinit var dashboardContent: LinearLayout
    private lateinit var namesGalleryFooter: LinearLayout
    @Volatile
    private var walletHandle = INVALID_HANDLE
    private var walletAuthorityGeneration = 0L
    private var walletControllerIsReopenedDurable = false
    // Read by the wallet-owned networking worker and written by the UI/action
    // threads.  Visibility matters: a stale `false` lets a multi-frame network
    // drain repeatedly reacquire the native controller while an authorized
    // transaction is waiting to commit.
    @Volatile
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
    /**
     * Monotonic identity for the UI operation that currently owns [busy]. A
     * native result can return after Android stopped/restarted this Activity;
     * that stale result may finish only its own busy state and must never
     * clear a newer operation.
     */
    private var walletOperationSerial = 0L
    private var lifecycleEpoch = 0L
    private var foreground = false
    private lateinit var connectivityManager: ConnectivityManager
    private var walletNetworkCallbackRegistered = false
    private var activeWalletNetworkTransport = WalletNetworkTransport.Other
    private val walletNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshActiveWalletNetworkTransport()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = publishActiveWalletNetworkTransport(classifyWalletNetworkTransport(networkCapabilities))

        override fun onLost(network: Network) = refreshActiveWalletNetworkTransport()
    }
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
    // Every wallet-owned AlertDialog is registered weakly at the shared
    // builder boundary. A confirmed lock dismisses all of them, including
    // Bitcoin/Shakedex sheets that are otherwise unrelated to the explicit
    // send/value approval fields below.
    private val walletPopupDialogs: MutableSet<AlertDialog> =
        Collections.newSetFromMap(WeakHashMap())
    private var pendingSendApproval: NativeHnsSendApproval? = null
    private var valueApprovalDialog: AlertDialog? = null
    private var pendingValueApproval: NativeHnsValueApproval? = null
    private val trackedShakedexFinalizePromptAttempts = mutableSetOf<String>()
    private var trackedShakedexFinalizeApprovalTransactionId: String? = null
    private var latestReadSnapshot: NativeWalletReadSnapshot? = null
    private var loadedTrackedNames: List<NativeWalletName> = emptyList()
    private var trackedNamePageOffset: Int = 0
    private var showingNamesPage = false
    private var showingTrackedNameSearch = false
    private var selectedTrackedNameIndex = 0
    private var trackedNameNavigationInFlight = false
    private var trackedNameSearchIndex: List<String> = emptyList()
    private var trackedNameSearchIndexHeight = -1L
    private var trackedNameSearchIndexCount = -1
    private var trackedNameSearchIndexInFlight = false
    private var trackedNameSearchInput: AutoCompleteTextView? = null
    private var walletNameImportInProgressCount = 0
    private var recentActivityPageOffset: Int = 0
    private var bitcoinActivityPageOffset: Int = 0
    private var pendingHandshakePayment: HandshakePaymentRequest? = null
    private var pendingPaymentPresentationScheduled = false
    private var scannedPaymentShouldResumeAfterUnlock = false
    private var browserSyncObservation: Closeable? = null
    private var pendingOutgoingSnapshotHeight: Long? = null
    private var pendingOutgoingRefreshAttemptedHeight: Long? = null
    private var latestObservedBrowserHeaderHeight: Long? = null
    private var activeSwapHnsRefreshAttemptedHeight: Long? = null
    private var directShakescapePeerEndpoint: String? = null
    /** Most recently successful outbound board endpoints, newest first. */
    private val recentDirectShakescapePeers = ArrayDeque<String>(3)
    /**
     * Last successfully decoded operational transport snapshot. Native direct
     * service/status calls deliberately share one non-blocking controller
     * exclusion domain, so a null read means "busy", not "disconnected".
     */
    private var directShakescapeTransportStatus: NativeWalletDirectShakescapeStatus? = null
    private var pendingQrBitmap: Bitmap? = null
    private var displayedLiveHnsSyncStage: NativeWalletHnsLiveSyncProgress.Stage? = null
    private var displayedLiveHnsSyncStageSinceMillis = 0L
    private var pendingWalletAuthentication: (() -> Unit)? = null
    private var cancelledWalletAuthentication: (() -> Unit)? = null
    private var localNetworkPermissionRequestInFlight = false
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkPermissionRequestInFlight = false
        if (!granted) {
            Log.w(TAG, "Local-network permission denied; router discovery and LAN inbound tests remain unavailable")
        }
        refreshDirectShakescapeStatus()
    }
    private lateinit var atomicSwapNotifications: AtomicSwapNotificationCoordinator
    private var atomicSwapNotificationPermissionRequestInFlight = false
    private val atomicSwapNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        atomicSwapNotificationPermissionRequestInFlight = false
        if (granted && ::atomicSwapNotifications.isInitialized) {
            latestShakescapeExecutionStatus?.let(::publishAtomicSwapNotifications)
        }
    }
    private val walletAuthentication = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val approved = pendingWalletAuthentication
        val cancelled = cancelledWalletAuthentication
        pendingWalletAuthentication = null
        cancelledWalletAuthentication = null
        if (result.resultCode == Activity.RESULT_OK) approved?.invoke() else cancelled?.invoke()
    }
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
    // This is wallet-local public output. It exists only while this exact
    // native controller stays unlocked and never substitutes for a synced
    // balance, history, name, or spend projection.
    private var localPaymentReceiveTarget: NativeWalletPaymentReceiveTarget? = null
    private var latestReadSnapshotHandle = INVALID_HANDLE
    private var latestReadSnapshotAuthorityGeneration = 0L
    private var latestReadSnapshotEpoch = 0L
    private var latestReadSnapshotObservedAtElapsedMillis = 0L
    @Volatile
    private var walletHnsSyncInProgress = false
    private var walletBitcoinSyncInProgress = false
    private var bitcoinSnapshot: com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot? = null
    private var bitcoinSyncStopRequested = false
    private var bitcoinBirthdayResetInProgress = false
    private var latestShakescapeExecutionStatus: NativeShakescapeExecutionStatus? = null
    private var activeShakescapeDashboardSummary: String? = null
    private var lastAutomaticSwapHnsSyncAtElapsedMillis = Long.MIN_VALUE
    private var lastAutomaticSwapHnsSyncFingerprint: String? = null
    private var lastAutomaticSwapBitcoinSyncAtElapsedMillis = Long.MIN_VALUE
    private var lastAutomaticSwapBitcoinSyncFingerprint: String? = null
    private var automaticSwapBitcoinSyncPausedUntilElapsedMillis = Long.MIN_VALUE
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
    private var walletUnlockAuthenticationGranted = false
    private val walletHnsJourney = WalletHnsJourney()
    private val leaseReleaseHandoff = WalletLeaseReleaseHandoff()

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.clear()
        super.onCreate(null)
        atomicSwapNotifications = AtomicSwapNotificationCoordinator(applicationContext)
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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        statusView = preferenceSummary(
            text = getString(R.string.wallet_status_starting),
            maxLines = Int.MAX_VALUE,
            bold = true,
        )
        cellularDataWarningView = preferenceSummary(
            text = getString(R.string.wallet_cellular_warning),
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
        historyView = walletReadSummary(R.string.wallet_reads_history_unavailable)
        trackedNamesView = walletReadSummary(R.string.wallet_reads_names_unavailable)
        nameImportStatusView = walletReadSummary(R.string.wallet_name_import_unavailable)
        sendStatusView = walletReadSummary(R.string.wallet_send_unavailable)
        bitcoinStatusView = walletReadSummary(R.string.wallet_bitcoin_unavailable)
        bitcoinBalanceView = walletReadSummary(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView = walletReadSummary(R.string.wallet_bitcoin_receive_unavailable).apply {
            setTextIsSelectable(true)
        }
        bitcoinActivityView = walletReadSummary(R.string.wallet_bitcoin_activity_unavailable)
        valueActionStatusView = walletReadSummary(R.string.wallet_value_actions_unavailable)
        shakedexQueryStatusView = walletReadSummary(R.string.wallet_shakedex_queries_unavailable)
        shakedexExecutionStatusView = walletReadSummary(R.string.wallet_swap_status_unavailable)
        directShakescapeStatusView = walletReadSummary(R.string.wallet_direct_shakescape_unavailable)
        recoveryView = RecoveryPhraseView(this)
        dashboardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        namesGalleryFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = uiDp(10).toFloat()
            setBackgroundColor(themeColors().background)
            setPadding(uiDp(20), uiDp(7), uiDp(20), uiDp(8))
        }
        setSettingsScreen(
            // The wallet's state and content identify the screen. Omitting the
            // redundant title also lets the Names omnibar own the top edge.
            title = "",
            onPullDownAtTop = ::pullToSynchronizeWalletReads,
            persistentFooter = namesGalleryFooter,
        ) {
            addView(dashboardContent)
        }
        // Back returns to the browser by reordering Main above this Activity.
        // A user-started read-only synchronization may continue under the
        // visible foreground service, while idle signing authority is retired
        // before attacker-controlled website content remains visible.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingNamesPage) {
                    showingNamesPage = false
                    showingTrackedNameSearch = false
                    renderWalletDashboard()
                    return
                }
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
        startWalletNetworkMonitoring()
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
            // The device-credential Activity can briefly stop this Activity.
            // The service worker normally survives that retained transition,
            // but an already-scheduled worker exit may race onStart. Starting
            // is idempotent, so always repair a missing worker here.
            if (
                NativeWalletBridge.status(walletHandle)?.locked == false &&
                    NativeWalletBridge.directHnsRollbackFloor(walletHandle) != null
            ) {
                startWalletOwnedDirectShakescapeWorker(
                    walletHandle,
                    checkNotNull(currentStorageLease()),
                    lifecycleEpoch,
                )
            }
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
        // Publish retained ownership before clearing foreground. Otherwise
        // the peer worker can observe both flags false in this narrow window,
        // exit, and leave an unlocked wallet presenting stale peer state after
        // the device-credential Activity returns.
        retainingInAppWalletSession = retainInAppSession
        foreground = false
        stopWalletNetworkMonitoring()
        browserSyncObservation?.close()
        browserSyncObservation = null
        // An Unlock tap may be queued while the durable controller is still
        // reopening. Never carry that user-presence request off this screen.
        walletUnlockRequested = false
        walletUnlockAuthenticationGranted = false
        walletNameImportInProgressCount = 0
        cachedHnsSyncPresentationWatcher?.set(false)
        dismissWalletPopupsForLock()
        if (retainInAppSession) {
            // Android calls the departing Activity's onStop before it reports
            // whether the process has actually gone background. Preserve the
            // current lease across an in-app transition, then retire it after
            // that lifecycle report only if no app Activity remains visible.
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

    private fun startWalletNetworkMonitoring() {
        refreshActiveWalletNetworkTransport()
        if (walletNetworkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(walletNetworkCallback)
            walletNetworkCallbackRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Unable to monitor the active wallet network transport", error)
        }
    }

    private fun stopWalletNetworkMonitoring() {
        if (!walletNetworkCallbackRegistered) return
        walletNetworkCallbackRegistered = false
        runCatching { connectivityManager.unregisterNetworkCallback(walletNetworkCallback) }
            .onFailure { error ->
                Log.w(TAG, "Unable to stop wallet network transport monitoring", error)
            }
    }

    private fun refreshActiveWalletNetworkTransport() {
        val capabilities = connectivityManager.activeNetwork?.let(
            connectivityManager::getNetworkCapabilities
        )
        publishActiveWalletNetworkTransport(classifyWalletNetworkTransport(capabilities))
    }

    private fun classifyWalletNetworkTransport(
        capabilities: NetworkCapabilities?,
    ): WalletNetworkTransport = when {
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
            WalletNetworkTransport.Cellular
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
            WalletNetworkTransport.Wifi
        else -> WalletNetworkTransport.Other
    }

    private fun publishActiveWalletNetworkTransport(transport: WalletNetworkTransport) {
        runOnUiThread {
            if (activeWalletNetworkTransport == transport) return@runOnUiThread
            activeWalletNetworkTransport = transport
            if (foreground && ::dashboardContent.isInitialized && !isFinishing && !isDestroyed) {
                renderWalletDashboard()
            }
        }
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
        namesGalleryFooter.visibility = View.GONE
        namesGalleryFooter.removeAllViews()
        trackedNameSearchInput = null
        if (!showingNamesPage) showingTrackedNameSearch = false
        dashboardContent.setSecondaryScreenScrollingEnabled(!showingNamesPage)
        // The dashboard is redrawn as the wallet changes state, but these
        // views preserve live state (including secrets, selection, and status
        // text) across redraws. Removing a card from `dashboardContent` does
        // not detach its nested children, so detach each reusable view before
        // placing it in a newly-created card. Without this, the second render
        // crashes with "The specified child already has a parent."
        listOf(
            statusView,
            cellularDataWarningView,
            readStatusView,
            balanceView,
            sendStatusView,
            valueActionStatusView,
        ).forEach { view ->
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
        val readOnlySynchronizationActive =
            hnsSynchronizationActive || walletBitcoinSyncInProgress
        val controllerStatus = if (
            hasController && !readOnlySynchronizationActive && walletNameImportInProgressCount == 0
        ) {
            NativeWalletBridge.status(walletHandle)
        } else {
            null
        }
        // status() deliberately uses a non-blocking native mutex. A direct
        // ShakeScape service tick can therefore make it temporarily return
        // null even though this exact controller remains unlocked. Record
        // only positive lock/unlock observations and preserve the last
        // confirmed state across contention; never turn "busy right now"
        // into a visible wallet lock.
        when (controllerStatus?.locked) {
            true -> walletHnsJourney.walletLocked()
            false -> walletHnsJourney.walletUnlocked()
            null -> Unit
        }
        val controllerUnlocked = hasController && (
            readOnlySynchronizationActive || walletNameImportInProgressCount > 0 ||
                walletHnsJourney.isConfirmedUnlocked()
        )
        // Presentation may safely retain a confirmed unlocked state while a
        // read-only worker owns the mutex. Value actions still require a
        // fresh, available native status observation.
        val controllerAvailableForActions = controllerStatus?.locked == false
        val availability = walletDashboardAvailability(
            busy = busy,
            hnsSynchronizationActive = hnsSynchronizationActive,
            bitcoinSynchronizationActive = walletBitcoinSyncInProgress,
            controllerUnlocked = controllerUnlocked,
            controllerAvailableForActions = controllerAvailableForActions,
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
                if (showingNamesPage && latestReadSnapshot != null) {
                    renderNamesPage(
                        navigationAvailable = availability.navigation,
                    )
                } else {
                    renderUnlockedWalletDashboard(
                        actionsAvailable = availability.mutations,
                        navigationAvailable = availability.navigation,
                        synchronizationInProgress = hnsSynchronizationActive,
                    )
                }
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

    private fun addCellularDataWarningIfNeeded(walletUnlocked: Boolean) {
        if (!walletCellularDataWarningVisible(walletUnlocked, activeWalletNetworkTransport)) return
        dashboardContent.addView(statusCard(
            label = getString(R.string.wallet_cellular_warning_title),
            detail = cellularDataWarningView,
            healthy = false,
        ))
    }

    private fun renderUnlockedWalletDashboard(
        actionsAvailable: Boolean = true,
        navigationAvailable: Boolean = actionsAvailable,
        synchronizationInProgress: Boolean = false,
    ) {
        addCellularDataWarningIfNeeded(walletUnlocked = true)
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
        latestReadSnapshot?.finalizeNotices?.takeIf { it.isNotEmpty() }?.let { notices ->
            dashboardContent.addView(statusCard(
                label = getString(R.string.wallet_dashboard_finalize_notice),
                detail = preferenceSummary(
                    text = notices.joinToString("\n\n", transform = ::formatFinalizeNotice),
                    maxLines = Int.MAX_VALUE,
                ),
                healthy = notices.any { it.phase == "finalizeAvailable" },
            ))
        }
        addWalletTiles(locked = false, actionsAvailable = navigationAvailable)
        dashboardContent.addView(settingsGroup(getString(R.string.wallet_dashboard_recent_activity)) {
            addSettingsRow(navRow(
                title = getString(R.string.wallet_dashboard_recent_activity),
                summary = recentActivitySummary(),
            ) { showActivityDetails() }.disabledWhenWalletHandoff(!navigationAvailable))
        })
        if (actionsAvailable) schedulePendingPaymentPresentation()
    }

    private fun renderNamesPage(navigationAvailable: Boolean) {
        val snapshot = latestReadSnapshot ?: run {
            showingNamesPage = false
            renderUnlockedWalletDashboard(navigationAvailable)
            return
        }
        addCellularDataWarningIfNeeded(walletUnlocked = true)
        val total = snapshot.trackedNameCount
        if (total == 0) selectedTrackedNameIndex = 0
        val relativeIndex = selectedTrackedNameIndex - trackedNamePageOffset
        val selected = loadedTrackedNames.getOrNull(relativeIndex)
        val state = selected?.let { name ->
            listOfNotNull(
                walletNameOwnershipLabel(name.ownershipStatus),
                walletReadCodeLabel(name.resourceStatus),
                name.registered?.let {
                    getString(
                        if (it) R.string.wallet_reads_name_registered
                        else R.string.wallet_reads_name_not_registered,
                    )
                },
                name.expired?.takeIf { it }?.let {
                    getString(R.string.wallet_name_card_previously_expired)
                },
            ).joinToString(" · ")
        } ?: getString(R.string.wallet_name_card_empty)

        if (showingTrackedNameSearch) {
            val search = trackedNameSearchField(
                actionsAvailable = navigationAvailable,
                total = total,
            )
            dashboardContent.addView(search, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = uiDp(8)
            })
            search.post {
                search.translationY = -search.height.toFloat()
                search.alpha = 0f
                search.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220L)
                    .start()
                search.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(search, 0)
                if (search.text.isNullOrEmpty() && search.adapter?.count.orZero() > 0) {
                    search.showDropDown()
                }
            }
        }

        val nameCard = MetallicNameCardView(this).apply {
            bind(
                name = selected?.name,
                state = state,
                sections = selected?.let(::walletNameCardSections).orEmpty(),
            )
            isEnabled = selected != null
        }
        dashboardContent.addView(nameCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        renderNamesGalleryFooter(navigationAvailable, total)
        // The footer must be populated and visible before its window position
        // can define the remaining card viewport.
        fitNameCardToViewport(nameCard)
    }

    /**
     * The Names page is a single collectible composition, not a document.
     * Measure the actual space between the card and persistent controls so
     * short and tall phones get one complete, non-scrolling card viewport.
     */
    private fun fitNameCardToViewport(card: MetallicNameCardView) {
        fun updateHeight() {
            if (!showingNamesPage || !namesGalleryFooter.isShown) return
            val cardPosition = IntArray(2)
            val footerPosition = IntArray(2)
            card.getLocationInWindow(cardPosition)
            namesGalleryFooter.getLocationInWindow(footerPosition)
            // Pin the card view immediately above the persistent controls.
            // The renderer retains its own 22 dp internal safe edge for 3-D
            // tilt, so another full screen-content inset here only creates a
            // false bottom gap and costs short phones an entire data row.
            val available = footerPosition[1] - cardPosition[1] - uiDp(4)
            if (available <= 0 || card.layoutParams.height == available) return
            card.layoutParams = card.layoutParams.apply { height = available }
        }
        // A freshly rendered card can have the same parent/footer bounds as
        // the card it replaced. In that case neither view emits a subsequent
        // layout-change callback, leaving the replacement at its intrinsic
        // (short) height. Observe the completed view-tree layout instead so
        // every replacement, including a search result, is fitted once its
        // final positions are known. Remove the observer with the card to
        // avoid retaining old card instances across wallet refreshes.
        val observer = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() = updateHeight()
        }
        val treeObserver = card.viewTreeObserver
        treeObserver.addOnGlobalLayoutListener(observer)
        card.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                if (treeObserver.isAlive) {
                    treeObserver.removeOnGlobalLayoutListener(observer)
                } else {
                    view.viewTreeObserver.takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(observer)
                }
                view.removeOnAttachStateChangeListener(this)
            }
        })
        card.post {
            updateHeight()
            card.postOnAnimation(::updateHeight)
        }
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
        if (locked) {
            dashboardContent.addView(
                walletTile,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = uiDp(8) },
            )
            return
        }
        dashboardContent.addView(walletTileRow(
            dashboardTile(
                title = getString(R.string.wallet_dashboard_names),
                summary = namesSummary(),
            ) { showNamesDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
            walletTile,
        ))
        val bitcoinTile = dashboardTile(
            title = getString(R.string.wallet_dashboard_bitcoin),
            summary = bitcoinSummary(),
        ) { showBitcoinDashboard() }.disabledWhenWalletHandoff(!actionsAvailable)
        if (SHOW_SHAKEDEX_WALLET_CARD) {
            dashboardContent.addView(walletTileRow(
                bitcoinTile,
                dashboardTile(
                    title = getString(R.string.wallet_dashboard_shakedex),
                    summary = shakedexSummary(),
                ) { showShakedexDashboard() }.disabledWhenWalletHandoff(!actionsAvailable),
            ))
        } else {
            dashboardContent.addView(
                bitcoinTile,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = uiDp(8) },
            )
        }
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

    private fun namesSummary(): String = latestReadSnapshot?.trackedNameCount?.let { count ->
        resources.getQuantityString(R.plurals.wallet_dashboard_tracked_names, count, count)
    } ?: getString(R.string.wallet_dashboard_sync_required)

    private fun bitcoinSummary(): String =
        if (bitcoinBalanceView.text == getString(R.string.wallet_bitcoin_balance_unavailable)) {
            getString(R.string.wallet_dashboard_sync_required)
        } else {
            getString(R.string.wallet_dashboard_ready)
        }

    private fun shakedexSummary(): String =
        latestReadSnapshot?.finalizeNotices?.firstOrNull()?.let(::formatFinalizeNotice)
            ?: activeShakescapeDashboardSummary ?: if (directShakescapePeerEndpoint != null) {
            getString(R.string.wallet_dashboard_connected)
        } else {
            getString(R.string.wallet_dashboard_not_connected)
        }

    private fun recentActivitySummary(): String = latestReadSnapshot?.transactions?.size?.let { count ->
        resources.getQuantityString(R.plurals.wallet_dashboard_transactions, count, count)
    } ?: getString(R.string.wallet_dashboard_no_synced_activity)

    private fun bitcoinActivitySummary(): String = bitcoinSnapshot?.recentActivityTotal?.let { count ->
        if (count == 0) getString(R.string.wallet_bitcoin_activity_empty) else {
            resources.getQuantityString(R.plurals.wallet_bitcoin_transactions, count, count)
        }
    } ?: getString(R.string.wallet_bitcoin_activity_unavailable)

    private fun formatFinalizeNotice(notice: com.denuoweb.hnsdane.wallet.NativeHnsFinalizeNotice): String =
        when (notice.phase) {
            "transferPending" -> getString(
                R.string.wallet_finalize_notice_transfer_pending,
                notice.name,
                notice.transactionId,
                notice.currentHeight,
            )
            "finalizeWaiting" -> getString(
                R.string.wallet_finalize_notice_waiting,
                notice.name,
                notice.currentHeight,
                notice.finalizeEligibleHeight,
                notice.transactionId,
            )
            "finalizeAvailable" -> getString(
                R.string.wallet_finalize_notice_available,
                notice.name,
                notice.currentHeight,
                notice.finalizeEligibleHeight,
                notice.transactionId,
            )
            "finalizePending" -> getString(
                R.string.wallet_finalize_notice_finalize_pending,
                notice.name,
                notice.transactionId,
                notice.currentHeight,
            )
            else -> error("closed native finalize notice phase")
        }

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
        val dialog = walletAlertDialogBuilder()
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
                if (phrase == null) {
                    restoreWallet(null, birthday)
                } else {
                    requireWalletAuthentication(
                        getString(R.string.wallet_auth_restore_title),
                        getString(R.string.wallet_auth_restore_message),
                        action = { restoreWallet(phrase, birthday) },
                        cancelled = { phrase.fill('\u0000') },
                    )
                }
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
        val payment = latestReadSnapshot?.paymentReceiveTarget?.display
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
        }
        val dialog = walletAlertDialogBuilder()
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
        val dialog = walletAlertDialogBuilder()
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
        val locked = NativeWalletBridge.status(walletHandle)?.locked
        val actions = mutableListOf<Pair<String, () -> Unit>>().apply {
            when (locked) {
                false -> add(getString(R.string.row_wallet_lock) to ::lockWallet)
                true -> add(getString(R.string.row_wallet_unlock) to ::unlockWallet)
                null -> Unit
            }
            if (locked == false) {
                if (runCatching { keyStore.hasRecoveryPhrase() }.getOrDefault(false)) {
                    add(getString(R.string.row_wallet_view_recovery) to ::requestRecoveryPhraseDisplay)
                }
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
            walletDetailDialog(
                title = getString(R.string.wallet_dashboard_recent_activity),
                rows = listOf(
                    getString(R.string.wallet_activity_transactions) to historyView.text.toString(),
                ),
            )
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
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (recentActivityPageOffset > 0) {
            actions += getString(R.string.action_previous_wallet_activity) to {
                recentActivityPageOffset -= MAX_VISIBLE_READ_ITEMS
                window.decorView.post(::showActivityDetails)
            }
        }
        if (recentActivityPageOffset + page.size < transactions.size) {
            actions += getString(R.string.action_next_wallet_activity) to {
                recentActivityPageOffset += MAX_VISIBLE_READ_ITEMS
                window.decorView.post(::showActivityDetails)
            }
        }
        if (page.isNotEmpty()) {
            actions += getString(R.string.wallet_dashboard_copy_activity) to {
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
            }
        }
        showWalletModal(
            title = getString(R.string.wallet_dashboard_recent_activity),
            rows = listOf(
                getString(R.string.wallet_activity_transactions) to TextView(this).apply {
                    text = message
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(themeColors().primaryText)
                    setTextIsSelectable(true)
                },
            ),
            actions = actions,
        )
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
        if (latestReadSnapshot == null) {
            Toast.makeText(this, R.string.wallet_dashboard_sync_required, Toast.LENGTH_SHORT).show()
            return
        }
        showingNamesPage = true
        showingTrackedNameSearch = false
        val total = latestReadSnapshot?.trackedNameCount ?: 0
        selectedTrackedNameIndex = if (total == 0) 0 else {
            selectedTrackedNameIndex.coerceIn(0, total - 1)
        }
        renderWalletDashboard()
    }

    private fun loadWalletNamePageForSelection(index: Int) {
        val snapshot = latestReadSnapshot ?: return
        val offset = walletNamePageOffsetForIndex(
            index,
            MAX_VISIBLE_READ_ITEMS,
            snapshot.trackedNameCount,
        ) ?: return
        if (trackedNameNavigationInFlight) return
        trackedNameNavigationInFlight = true
        val handle = walletHandle
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-name-page") {
            val page = NativeWalletBridge.hnsNamePage(handle, offset, MAX_VISIBLE_READ_ITEMS)
            runOnUiThread {
                trackedNameNavigationInFlight = false
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
                selectedTrackedNameIndex = index
                renderLoadedTrackedNames(snapshot.trackedNameCount)
                if (showingNamesPage) renderWalletDashboard()
            }
        }
    }

    private fun selectRelativeTrackedName(delta: Int) {
        val snapshot = latestReadSnapshot ?: return
        val target = selectedTrackedNameIndex + delta
        if (target !in 0 until snapshot.trackedNameCount || trackedNameNavigationInFlight) return
        val relative = target - trackedNamePageOffset
        if (relative in loadedTrackedNames.indices) {
            selectedTrackedNameIndex = target
            renderWalletDashboard()
        } else {
            loadWalletNamePageForSelection(target)
        }
    }

    private fun trackedNameSearchField(actionsAvailable: Boolean, total: Int): AutoCompleteTextView =
        AutoCompleteTextView(this).apply {
            hint = getString(R.string.wallet_name_search_omnibar_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            threshold = 1
            textSize = 15f
            setTextColor(themeColors().primaryText)
            setHintTextColor(themeColors().secondaryText)
            setPadding(uiDp(18), uiDp(12), uiDp(18), uiDp(12))
            background = settingsSurfaceDrawable(
                accent = themeColors().secondaryAction,
                fill = themeColors().background,
                cornerRadius = 22,
            )
            isEnabled = actionsAvailable && total > 0 && !trackedNameNavigationInFlight
            trackedNameSearchInput = this
            installTrackedNameSuggestions(this)
            setOnItemClickListener { parent, _, position, _ ->
                val selectedName = parent.getItemAtPosition(position) as? String
                    ?: return@setOnItemClickListener
                setText(selectedName, false)
                searchTrackedNames(selectedName)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                val query = canonicalTrackedNameSearchText(text?.toString().orEmpty())
                if (query == null) {
                    error = getString(R.string.wallet_name_search_invalid)
                } else {
                    searchTrackedNames(query)
                }
                true
            }
            setOnClickListener {
                if (text.isNullOrEmpty() && adapter?.count.orZero() > 0) showDropDown()
            }
            ensureTrackedNameSearchIndex()
        }

    private fun installTrackedNameSuggestions(input: AutoCompleteTextView) {
        val names = trackedNameSearchIndex.ifEmpty { loadedTrackedNames.map(NativeWalletName::name) }
        input.setAdapter(ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            names.map(::displayHandshakeNameText),
        ))
    }

    private fun ensureTrackedNameSearchIndex() {
        val snapshot = latestReadSnapshot ?: return
        val cacheMatches = trackedNameSearchIndexHeight == snapshot.height &&
            trackedNameSearchIndexCount == snapshot.trackedNameCount
        if (cacheMatches || trackedNameSearchIndexInFlight || snapshot.trackedNameCount == 0) return

        trackedNameSearchIndexInFlight = true
        val handle = walletHandle
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-name-autocomplete") {
            val names = ArrayList<String>(snapshot.trackedNameCount)
            var offset = 0
            var valid = true
            while (offset < snapshot.trackedNameCount) {
                val page = NativeWalletBridge.hnsNamePage(handle, offset, 64)
                if (
                    page == null || page.offset != offset ||
                    page.total != snapshot.trackedNameCount || page.names.isEmpty()
                ) {
                    valid = false
                    break
                }
                names.addAll(page.names.map(NativeWalletName::name))
                offset += page.names.size
                if (!page.hasMore) break
            }
            if (names.size != snapshot.trackedNameCount || names.toSet().size != names.size) {
                valid = false
            }
            runOnUiThread {
                trackedNameSearchIndexInFlight = false
                if (
                    !valid || handle != walletHandle || epoch != lifecycleEpoch ||
                    authorityGeneration != walletAuthorityGeneration ||
                    latestReadSnapshot !== snapshot
                ) return@runOnUiThread
                trackedNameSearchIndex = names
                trackedNameSearchIndexHeight = snapshot.height
                trackedNameSearchIndexCount = snapshot.trackedNameCount
                trackedNameSearchInput?.let(::installTrackedNameSuggestions)
            }
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun closeTrackedNameSearch() {
        val input = trackedNameSearchInput
        showingTrackedNameSearch = false
        input?.clearFocus()
        input?.windowToken?.let { token ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
    }

    private fun walletNameCardSections(name: NativeWalletName): List<WalletNameCardSection> {
        fun stat(
            label: String,
            value: Any,
            monospace: Boolean = false,
            singleLine: Boolean = false,
        ) = WalletNameCardStat(label, value.toString(), monospace, singleLine)
        fun row(vararg stats: WalletNameCardStat) = WalletNameCardRow(stats.toList())

        val identity = WalletNameCardSection(
            title = "CHAIN IDENTITY",
            rows = listOf(
                row(
                    stat("OWNERSHIP", walletNameOwnershipLabel(name.ownershipStatus)),
                    stat("RESOURCE STATUS", walletReadCodeLabel(name.resourceStatus)),
                ),
                row(
                    stat("PROOF HEIGHT", name.proofHeight),
                    stat("SNAPSHOT HEIGHT", latestReadSnapshot?.height ?: name.proofHeight),
                ),
                row(
                    stat("REGISTERED", name.registered?.yesNoUnknown() ?: "UNKNOWN"),
                    stat("EXPIRED BEFORE", name.expired?.yesNoUnknown() ?: "UNKNOWN"),
                ),
                row(stat("NAME HASH", name.nameHash, monospace = true, singleLine = true)),
            ),
        )
        val covenantRows = name.canonicalState?.let { state ->
            listOf(
                row(
                    stat("VALUE", "${formatHnsBaseUnits(state.valueBaseUnits)} HNS"),
                    stat("HIGHEST BID", "${formatHnsBaseUnits(state.highestBaseUnits)} HNS"),
                ),
                row(
                    stat("START HEIGHT", state.startHeight),
                    stat("RENEWAL HEIGHT", state.renewalHeight),
                ),
                row(
                    stat("TRANSFER HEIGHT", state.transferHeight),
                    stat("REVOKED HEIGHT", state.revokedHeight),
                ),
                row(
                    stat("CLAIMED HEIGHT", state.claimedHeight),
                    stat("RENEWAL COVENANTS", state.renewals),
                ),
                row(stat("WEAK NAME", state.weak.yesNoUnknown())),
            )
        } ?: listOf(row(stat("CANONICAL STATE", "UNAVAILABLE")))
        val rawResource = when (val raw = name.rawResourceHex) {
            null -> "UNAVAILABLE"
            "" -> "EMPTY"
            else -> compactRawResourceHex(raw)
        }
        return listOf(
            identity,
            WalletNameCardSection("COVENANT STATE", covenantRows),
            WalletNameCardSection(
                title = "RESOURCE DATA",
                rows = listOf(
                    row(
                        stat("RECORDS", name.resourceRecordCount ?: "UNKNOWN"),
                        stat("BYTES", name.rawResourceHex?.length?.div(2) ?: "UNKNOWN"),
                    ),
                    row(stat(
                        "RAW RESOURCE HEX PREVIEW",
                        rawResource,
                        monospace = true,
                        singleLine = true,
                    )),
                ),
            ),
        )
    }

    private fun Boolean.yesNoUnknown(): String = if (this) "YES" else "NO"

    private fun walletNameOwnershipLabel(status: String): String = when (status) {
        "watchOnlyCanonicalStateDecoderUnavailable",
        "watchOnlyOwnerTransactionUnavailable" -> "WATCH ONLY"
        "walletContextUnavailable" -> "UNCLASSIFIED"
        else -> walletReadCodeLabel(status)
    }

    private fun renderNamesGalleryFooter(actionsAvailable: Boolean, total: Int) {
        namesGalleryFooter.visibility = View.VISIBLE
        namesGalleryFooter.addView(TextView(this).apply {
            text = if (walletNameImportInProgressCount > 0) {
                resources.getQuantityString(
                    R.plurals.wallet_name_card_importing,
                    walletNameImportInProgressCount,
                    walletNameImportInProgressCount,
                )
            } else if (total == 0) {
                getString(R.string.wallet_name_card_position_empty)
            } else {
                getString(
                    R.string.wallet_name_card_position,
                    selectedTrackedNameIndex + 1,
                    total,
                )
            }
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(themeColors().secondaryText)
            setPadding(0, 0, 0, uiDp(5))
        })
        namesGalleryFooter.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun galleryButton(
                label: String,
                secondary: Boolean = false,
                action: () -> Unit,
            ) = dashboardActionButton(label, secondary, action).apply {
                textSize = 9.5f
                setPadding(uiDp(2), uiDp(6), uiDp(2), uiDp(6))
            }
            fun buttonLayout(first: Boolean = false) = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                if (!first) leftMargin = uiDp(5)
            }
            addView(galleryButton(
                getString(R.string.wallet_name_previous),
                secondary = true,
            ) { selectRelativeTrackedName(-1) }.disabledWhenWalletHandoff(
                !actionsAvailable || trackedNameNavigationInFlight || selectedTrackedNameIndex <= 0,
            ), buttonLayout(first = true))
            addView(galleryButton(
                getString(R.string.wallet_name_search_button),
                secondary = true,
            ) {
                showingTrackedNameSearch = true
                renderWalletDashboard()
            }.disabledWhenWalletHandoff(
                !actionsAvailable || trackedNameNavigationInFlight || total == 0,
            ), buttonLayout())
            addView(galleryButton(
                getString(R.string.wallet_name_options),
            ) { showNameActionMenu() }.disabledWhenWalletHandoff(!actionsAvailable),
                buttonLayout(),
            )
            addView(galleryButton(
                getString(R.string.wallet_name_next),
                secondary = true,
            ) { selectRelativeTrackedName(1) }.disabledWhenWalletHandoff(
                !actionsAvailable || trackedNameNavigationInFlight ||
                    selectedTrackedNameIndex + 1 >= total,
            ), buttonLayout())
        })
    }

    private fun searchTrackedNames(query: String) {
        val snapshot = latestReadSnapshot ?: return
        if (trackedNameNavigationInFlight) return
        val local = loadedTrackedNames.indexOfFirst { it.name == query }
        if (local >= 0) {
            selectedTrackedNameIndex = trackedNamePageOffset + local
            closeTrackedNameSearch()
            renderWalletDashboard()
            return
        }
        trackedNameNavigationInFlight = true
        renderWalletDashboard()
        val handle = walletHandle
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-name-search") {
            var foundPage: com.denuoweb.hnsdane.wallet.NativeWalletNamePage? = null
            var foundIndex = -1
            var offset = 0
            while (offset < snapshot.trackedNameCount && foundIndex < 0) {
                val page = NativeWalletBridge.hnsNamePage(handle, offset, MAX_VISIBLE_READ_ITEMS)
                    ?: break
                val match = page.names.indexOfFirst { it.name == query }
                if (match >= 0) {
                    foundPage = page
                    foundIndex = page.offset + match
                } else if (page.names.isEmpty() || !page.hasMore) {
                    break
                } else {
                    offset = page.offset + page.names.size
                }
            }
            runOnUiThread {
                trackedNameNavigationInFlight = false
                val matchedPage = foundPage
                if (
                    handle != walletHandle || epoch != lifecycleEpoch ||
                    authorityGeneration != walletAuthorityGeneration ||
                    latestReadSnapshot !== snapshot || !showingNamesPage
                ) return@runOnUiThread
                if (matchedPage == null || foundIndex < 0) {
                    Toast.makeText(this, R.string.wallet_name_search_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    trackedNamePageOffset = matchedPage.offset
                    loadedTrackedNames = matchedPage.names
                    selectedTrackedNameIndex = foundIndex
                    closeTrackedNameSearch()
                    renderLoadedTrackedNames(snapshot.trackedNameCount)
                }
                renderWalletDashboard()
            }
        }
    }

    private fun showNameImportDialog() {
        val input = exactNameImportInput()
        nameImportInput = input
        showWalletFormDialog(
            title = getString(R.string.row_wallet_name_import),
            fields = listOf(getString(R.string.wallet_name_import_hint) to input),
            primaryLabel = getString(R.string.action_import_wallet_name),
            onPrimary = { dialog ->
                val canonical = input.text?.toString()?.let(::canonicalHandshakeNameImportText)
                val exactUtf8 = canonical?.let(::exactWalletNameUtf8)
                clearNameImportInput()
                dialog.dismiss()
                importWalletName(exactUtf8)
            },
            onDismiss = { if (nameImportInput === input) clearNameImportInput() },
        )
    }

    private fun showMultipleNameImportDialog() {
        val input = multipleNameImportInput()
        nameImportInput = input
        showWalletFormDialog(
            title = getString(R.string.action_import_multiple_wallet_names),
            message = getString(R.string.wallet_name_multiple_import_hint),
            fields = listOf(getString(R.string.action_import_multiple_wallet_names) to input),
            primaryLabel = getString(R.string.action_review_wallet_names),
            onPrimary = { dialog ->
                val names = parseSpaceSeparatedWalletNames(input.text?.toString().orEmpty())
                if (names == null) {
                    input.error = getString(R.string.wallet_name_multiple_import_invalid)
                } else {
                    clearNameImportInput()
                    dialog.dismiss()
                    window.decorView.post { showMultipleNameImportReview(names) }
                }
            },
            onDismiss = { if (nameImportInput === input) clearNameImportInput() },
        )
    }

    private fun showMultipleNameImportReview(names: List<String>) {
        val list = TextView(this).apply {
            text = names.mapIndexed { index, name ->
                "${index + 1}. ${displayHandshakeNameText(name)}"
            }.joinToString("\n")
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColors().primaryText)
            typeface = Typeface.MONOSPACE
            contentDescription = getString(R.string.wallet_name_multiple_import_review_list)
        }
        showWalletModal(
            title = getString(R.string.wallet_name_multiple_import_review_title),
            rows = listOf(
                getString(R.string.wallet_modal_details) to TextView(this).apply {
                    text = getString(R.string.wallet_name_multiple_import_review_message, names.size)
                    textSize = 14f
                    setTextColor(themeColors().primaryText)
                },
                getString(R.string.wallet_name_multiple_import_review_list) to list,
            ),
            actions = listOf(
                getString(R.string.action_import_multiple_wallet_names) to {
                    importWalletNames(names)
                },
            ),
        )
    }

    private fun showNameActionMenu() {
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_name_actions),
            rows = listOf(
                getString(R.string.wallet_modal_details) to
                    getString(R.string.wallet_name_actions_description),
                getString(R.string.row_wallet_name_import) to nameImportStatusView.text.toString(),
            ),
            actions = listOf(
                getString(R.string.action_import_wallet_name) to ::showNameImportDialog,
                getString(R.string.action_import_multiple_wallet_names) to ::showMultipleNameImportDialog,
                getString(R.string.row_wallet_transfer_name) to ::showTransferNameForm,
                getString(R.string.row_wallet_finalize_name) to ::showFinalizeNameForm,
                getString(R.string.row_wallet_set_records) to ::showSetNameRecordsForm,
            ),
            dismissOnAction = false,
        )
    }

    private fun showBitcoinDashboard() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.action_wallet_bitcoin_receive) to ::revealBitcoinReceiveAddress)
        if (walletBitcoinSyncInProgress) {
            actions.add(
                getString(R.string.action_stop_bitcoin_sync) to ::requestBitcoinSyncCancellation,
            )
        } else {
            actions.add(getString(R.string.action_sync_wallet_reads) to {
                automaticSwapBitcoinSyncPausedUntilElapsedMillis = Long.MIN_VALUE
                synchronizeBitcoin()
            })
        }
        if (bitcoinBirthdayMayStart()) {
            actions.add(
                getString(R.string.action_wallet_bitcoin_birthday) to ::showBitcoinBirthdayForm,
            )
        }
        actions.add(getString(R.string.wallet_dashboard_send_bitcoin) to ::showBitcoinSendForm)
        actions.add(getString(R.string.wallet_bitcoin_recent_activity) to ::showBitcoinActivityDetails)
        walletLiveDetailDialog(
            title = getString(R.string.wallet_dashboard_bitcoin),
            rows = listOf(
                getString(R.string.row_wallet_bitcoin_status) to bitcoinStatusView,
                getString(R.string.row_wallet_bitcoin_balance) to bitcoinBalanceView,
                getString(R.string.row_wallet_bitcoin_receive) to bitcoinReceiveView,
                getString(R.string.wallet_bitcoin_recent_activity) to bitcoinActivityView,
            ),
            actions = actions,
        )
    }

    private fun showBitcoinActivityDetails() {
        val snapshot = bitcoinSnapshot
        val total = snapshot?.recentActivityTotal ?: 0
        bitcoinActivityPageOffset = walletPageOffset(
            requestedOffset = bitcoinActivityPageOffset,
            totalItems = total,
            pageSize = MAX_VISIBLE_READ_ITEMS,
        )
        val loadedPage = when {
            snapshot == null -> null
            bitcoinActivityPageOffset == 0 -> NativeBitcoinActivityPage(
                offset = 0,
                total = snapshot.recentActivityTotal,
                activity = snapshot.recentActivity,
                hasMore = snapshot.recentActivity.size < snapshot.recentActivityTotal,
            )
            else -> NativeWalletBridge.bitcoinActivityPage(
                walletHandle,
                bitcoinActivityPageOffset,
            )
        }
        if (snapshot != null && loadedPage == null) {
            Toast.makeText(
                this,
                R.string.wallet_bitcoin_activity_page_failed,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val page = loadedPage?.activity.orEmpty()
        val pageTotal = loadedPage?.total ?: 0
        val message = when {
            snapshot == null -> getString(R.string.wallet_bitcoin_activity_unavailable)
            page.isEmpty() -> getString(R.string.wallet_bitcoin_activity_empty)
            else -> {
                val first = (loadedPage?.offset ?: 0) + 1
                val last = (loadedPage?.offset ?: 0) + page.size
                getString(
                    R.string.wallet_activity_page_position,
                    first,
                    last,
                    pageTotal,
                ) + "\n\n" + formatBitcoinActivity(page)
            }
        }
        val actions = buildList<Pair<String, () -> Unit>> {
            if (bitcoinActivityPageOffset > 0) {
                add(getString(R.string.action_previous_wallet_activity) to {
                    bitcoinActivityPageOffset -= MAX_VISIBLE_READ_ITEMS
                    showBitcoinActivityDetails()
                })
            }
            if (loadedPage?.hasMore == true) {
                add(getString(R.string.action_next_wallet_activity) to {
                    bitcoinActivityPageOffset += MAX_VISIBLE_READ_ITEMS
                    showBitcoinActivityDetails()
                })
            }
            if (page.isNotEmpty()) {
                add(getString(R.string.wallet_dashboard_copy_activity) to {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(
                            getString(R.string.wallet_bitcoin_recent_activity),
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
        showWalletModal(
            title = getString(R.string.wallet_bitcoin_recent_activity),
            rows = listOf(
                getString(R.string.wallet_bitcoin_recent_activity) to TextView(this).apply {
                    text = message
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(themeColors().primaryText)
                    setTextIsSelectable(true)
                },
            ),
            actions = actions,
        )
    }

    private fun formatBitcoinActivity(activity: List<NativeBitcoinActivity>): String =
        activity.joinToString("\n\n") { item ->
            val amount = when (item.direction) {
                "incoming" -> getString(R.string.wallet_bitcoin_activity_incoming, item.amountSats)
                "outgoing" -> getString(R.string.wallet_bitcoin_activity_outgoing, item.amountSats)
                else -> getString(R.string.wallet_bitcoin_activity_self_transfer)
            }
            val status = when (item.status) {
                "confirmed" -> getString(
                    R.string.wallet_bitcoin_activity_confirmed,
                    item.blockHeight,
                    item.confirmationCount,
                )
                "unconfirmed" -> getString(R.string.wallet_bitcoin_activity_unconfirmed)
                "prepared" -> getString(R.string.wallet_bitcoin_activity_prepared)
                "submissionStarted" -> getString(R.string.wallet_bitcoin_activity_submission_started)
                "submitted" -> getString(R.string.wallet_bitcoin_activity_submitted)
                else -> getString(R.string.wallet_bitcoin_activity_not_observed)
            }
            buildList {
                add(amount)
                item.feeSats?.let {
                    add(getString(R.string.wallet_bitcoin_activity_fee, it))
                }
                add(status)
                add(getString(R.string.wallet_bitcoin_activity_txid, item.txid))
                add(getString(
                    R.string.wallet_bitcoin_activity_updated,
                    DateFormat.getDateTimeInstance().format(Date(item.lastChangedAtUnix * 1000L)),
                ))
            }.joinToString("\n")
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
        rows.forEach { (_, detail) ->
            (detail.parent as? ViewGroup)?.removeView(detail)
        }
        showWalletModal(
            title = title,
            rows = rows.map { (label, detail) -> label to detail },
            actions = actions,
            dismissOnAction = false,
        ) {
            rows.forEach { (_, detail) ->
                (detail.parent as? ViewGroup)?.removeView(detail)
            }
        }
    }

    private fun showShakedexDashboard() {
        refreshDirectShakescapeStatus()
        val transport = directShakescapeTransportStatus
        val transportControls = directShakescapeControls(transport)
        val paired = transport?.peerEndpoint != null
        val connectionActions = mutableListOf(
            WalletModalAction(
                getString(R.string.row_wallet_pair_direct_shakescape),
                action = ::showPairDirectShakescapeForm,
                dismissParent = true,
            ),
        ).apply {
            if (transportControls.retryListener) {
                add(
                    WalletModalAction(
                        getString(R.string.row_wallet_retry_direct_shakescape_host),
                        enabled = paired,
                        action = ::retryWalletOwnedDirectShakescapeListener,
                    ),
                )
            }
            if (transportControls.disconnectPeer) {
                add(
                    WalletModalAction(
                        getString(R.string.row_wallet_disconnect_direct_shakescape),
                        enabled = paired,
                        action = ::disconnectWalletOwnedDirectShakescape,
                    ),
                )
            }
        }
        walletDetailDialog(
            title = getString(R.string.wallet_dashboard_shakedex),
            rows = listOf(
                getString(R.string.row_wallet_direct_shakescape_host) to directShakescapeStatusView.text.toString(),
                getString(R.string.row_wallet_swap_progress) to shakedexExecutionStatusView.text.toString(),
                getString(R.string.row_wallet_shakedex_status) to shakedexQueryStatusView.text.toString(),
            ),
            actionSections = listOf(
                WalletModalActionSection(
                    getString(R.string.wallet_modal_actions),
                    connectionActions,
                ),
                WalletModalActionSection(
                    getString(R.string.wallet_swap_coin_actions),
                    listOf(
                        WalletModalAction(getString(R.string.wallet_swap_sell_btc), paired, ::showBtcForHnsOfferForm),
                        WalletModalAction(getString(R.string.wallet_swap_sell_hns), paired, ::showHnsForBtcOfferForm),
                        WalletModalAction(getString(R.string.wallet_swap_available_offers), paired, ::showAvailableDirectOffers),
                        WalletModalAction(getString(R.string.wallet_swap_my_offers), paired, ::showMyDirectOffers),
                        WalletModalAction(getString(R.string.wallet_swap_executions), paired, ::showShakescapeExecutions),
                    ),
                ),
                WalletModalActionSection(
                    getString(R.string.wallet_swap_name_actions),
                    listOf(
                        WalletModalAction(getString(R.string.row_wallet_create_offer), paired, ::showCreateOfferForm),
                        WalletModalAction(getString(R.string.row_wallet_cancel_offer), paired, ::showCancelOfferForm),
                        WalletModalAction(getString(R.string.row_wallet_recover_name), paired, ::showRecoverNameForm),
                        WalletModalAction(getString(R.string.row_wallet_list_offers), paired, ::showListOffersForm),
                        WalletModalAction(getString(R.string.row_wallet_get_session), paired, ::showGetSessionForm),
                    ),
                ),
            ),
            dismissOnAction = false,
        )
    }

    private fun walletDetailDialog(
        title: String,
        rows: List<Pair<String, String>>,
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        actionSections: List<WalletModalActionSection> = emptyList(),
        dismissOnAction: Boolean = true,
    ) {
        showWalletModal(
            title = title,
            rows = rows.map { (label, detail) ->
                label to TextView(this).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(themeColors().primaryText)
                    setTextIsSelectable(true)
                }
            },
            actions = actions,
            actionSections = actionSections,
            dismissOnAction = dismissOnAction,
        )
    }

    /** Applies the same visible perimeter used by wallet action controls to every popup. */
    private fun walletAlertDialogBuilder(): AlertDialog.Builder =
        object : AlertDialog.Builder(this) {
            override fun create(): AlertDialog = super.create().also(::frameWalletPopup)

            override fun show(): AlertDialog = super.show().also(::frameWalletPopup)
        }

    private fun frameWalletPopup(dialog: AlertDialog): AlertDialog = dialog.apply {
        walletPopupDialogs.add(dialog)
        window?.setBackgroundDrawable(
            settingsSurfaceDrawable(
                accent = themeColors().secondaryAction,
                fill = themeColors().background,
                cornerRadius = 24,
            ),
        )
    }

    /** A locked native wallet must never remain visually covered by wallet data. */
    private fun dismissWalletPopupsForLock() {
        // Detach approval listeners before the general sweep. Native lock or
        // controller retirement already invalidates their one-time tokens;
        // dismissing UI must not start a competing rejection operation.
        dismissSendApproval(rejectNative = false)
        dismissValueApproval(rejectNative = false)
        val dialogs = walletPopupDialogs.toList()
        walletPopupDialogs.clear()
        dialogs.forEach { dialog ->
            if (dialog.isShowing) dialog.dismiss()
        }
        // Drop strong references held for specialized cleanup paths as well.
        walletDeletionDialog = null
        sendFormDialog = null
        clearNameImportInput()
        clearSendInputs()
    }

    private fun showWalletFormDialog(
        title: String,
        message: String? = null,
        fields: List<Pair<String, View>>,
        primaryLabel: String,
        onPrimary: (AlertDialog) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(18), uiDp(18), uiDp(18), uiDp(12))
            setBackgroundColor(themeColors().background)
            addView(TextView(this@WalletActivity).apply {
                text = title
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors().primaryText)
                setPadding(uiDp(2), 0, uiDp(2), uiDp(14))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            message?.takeIf { it.isNotBlank() }?.let { detail ->
                addView(
                    walletModalDetailCard(
                        getString(R.string.wallet_modal_details),
                        TextView(this@WalletActivity).apply {
                            text = detail
                            textSize = 14f
                            setTextColor(themeColors().primaryText)
                        },
                    ),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(10) },
                )
            }
            fields.forEach { (label, field) ->
                addView(
                    walletModalDetailCard(label, field),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(10) },
                )
            }
            addView(walletModalSectionHeading(getString(R.string.wallet_modal_actions)))
            addView(
                dashboardActionButton(primaryLabel) { onPrimary(dialog) }.apply {
                    minimumHeight = uiDp(48)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = uiDp(8) },
            )
            addView(
                dashboardActionButton(getString(R.string.action_cancel), secondary = true) {
                    dialog.dismiss()
                }.apply { minimumHeight = uiDp(48) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog = walletAlertDialogBuilder()
            .setView(ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            })
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                settingsSurfaceDrawable(
                    accent = themeColors().secondaryAction,
                    fill = themeColors().background,
                    cornerRadius = 24,
                ),
            )
        }
        dialog.show()
    }

    private fun showWalletModal(
        title: String,
        rows: List<Pair<String, View>>,
        actions: List<Pair<String, () -> Unit>>,
        actionSections: List<WalletModalActionSection> = emptyList(),
        dismissOnAction: Boolean = true,
        onDismiss: () -> Unit = {},
    ) {
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(18), uiDp(18), uiDp(18), uiDp(12))
            setBackgroundColor(themeColors().background)
            addView(TextView(this@WalletActivity).apply {
                text = title
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors().primaryText)
                setPadding(uiDp(2), 0, uiDp(2), uiDp(14))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            rows.forEach { (label, detail) ->
                addView(
                    walletModalDetailCard(label, detail),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(10) },
                )
            }
            if (actions.isNotEmpty()) {
                addView(walletModalSectionHeading(getString(R.string.wallet_modal_actions)))
            }
            var actionIndex = 0
            actions.forEachIndexed { index, (label, action) ->
                addView(
                    dashboardActionButton(label, secondary = index != 0) {
                        if (dismissOnAction) dialog.dismiss()
                        action()
                    }.apply {
                        textSize = 14f
                        minimumHeight = uiDp(48)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(8) },
                )
                actionIndex += 1
            }
            actionSections.forEach { section ->
                if (section.actions.isEmpty()) return@forEach
                addView(walletModalSectionHeading(section.title))
                section.actions.forEach { item ->
                    addView(
                        dashboardActionButton(item.label, secondary = actionIndex != 0) {
                            if (dismissOnAction || item.dismissParent) dialog.dismiss()
                            item.action()
                        }.disabledWhenWalletHandoff(!item.enabled).apply {
                            textSize = 14f
                            minimumHeight = uiDp(48)
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = uiDp(8) },
                    )
                    actionIndex += 1
                }
            }
            addView(
                dashboardActionButton(
                    getString(R.string.wallet_modal_done),
                    secondary = true,
                ) { dialog.dismiss() }.apply {
                    textSize = 14f
                    minimumHeight = uiDp(48)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = uiDp(4) },
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        dialog = walletAlertDialogBuilder()
            .setView(scroll)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                settingsSurfaceDrawable(
                    accent = themeColors().secondaryAction,
                    fill = themeColors().background,
                    cornerRadius = 24,
                ),
            )
        }
        dialog.show()
    }

    private fun walletModalDetailCard(label: String, detail: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = settingsSurfaceDrawable()
            setPadding(uiDp(15), uiDp(13), uiDp(15), uiDp(14))
            addView(TextView(this@WalletActivity).apply {
                text = label.uppercase()
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(themeColors().action)
            })
            addView(
                detail,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = uiDp(7) },
            )
        }

    private fun walletModalSectionHeading(label: String): TextView =
        TextView(this).apply {
            text = label.uppercase()
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(themeColors().secondaryText)
            setPadding(uiDp(3), uiDp(5), uiDp(3), uiDp(8))
            ViewCompat.setAccessibilityHeading(this, true)
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
                    if (databaseKeyAvailable) {
                        walletUnlockRequested = false
                        walletUnlockAuthenticationGranted = false
                    }
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
        queueWalletUnlock(authenticationGranted = false)
    }

    /**
     * Re-entering from Android's credential screen starts a new storage
     * ownership session. Older 32-bit devices can return the successful
     * activity result before that asynchronous lease and controller reopen.
     * Retain the one-shot authorization until the existing readiness gate can
     * consume it instead of silently dropping it or asking for a second PIN.
     */
    private fun resumeWalletUnlockAfterAuthentication() {
        queueWalletUnlock(authenticationGranted = true)
    }

    private fun queueWalletUnlock(authenticationGranted: Boolean) {
        if (authenticationGranted) walletUnlockAuthenticationGranted = true
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
        if (walletUnlockAuthenticationGranted) {
            walletUnlockAuthenticationGranted = false
            unlockWalletAfterAuthentication()
        } else {
            unlockWallet()
        }
    }

    private fun createWallet() {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_create_title),
            getString(R.string.wallet_auth_create_message),
            action = ::createWalletAfterAuthentication,
        )
    }

    private fun createWalletAfterAuthentication() {
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

    private fun restoreWallet(
        phrase: CharArray?,
        birthdayHeight: Long,
    ) {
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
                    phrase.fill('\u0000')
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
                        keyStore.storeDatabaseKey(databaseKey, phrase)
                    }
                }.getOrDefault(false)
                phrase.fill('\u0000')
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
        val phrase = recoveryView.copySecret() ?: return
        setRecoveryPhraseObscured(true)
        showRecoveryConfirmationQuiz(
            phrase = phrase,
            onConfirmed = { confirmedPhrase -> persistConfirmedRecovery(confirmedPhrase) },
            onAborted = { setRecoveryPhraseObscured(false) },
        )
    }

    /**
     * A modal is not a secrecy boundary: large screens can render most of the
     * phrase around it and accessibility services can still traverse the
     * covered view. Remove the phrase from both surfaces for the entire quiz,
     * restoring it only when verification is cancelled or fails.
     */
    private fun setRecoveryPhraseObscured(obscured: Boolean) {
        recoveryView.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
        recoveryView.importantForAccessibility = if (obscured) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun persistConfirmedRecovery(confirmedPhrase: CharArray) {
        val databaseKey = unconfirmedDatabaseKey
        if (databaseKey == null) {
            confirmedPhrase.fill('\u0000')
            setRecoveryPhraseObscured(false)
            return
        }
        val lease = currentStorageLease()
        val stored = lease != null && runCatching {
            ProcessWalletStorageOwnership.commitIfCurrent(lease.owner, lease) {
                keyStore.storeDatabaseKey(databaseKey, confirmedPhrase)
            }
        }.getOrDefault(false)
        confirmedPhrase.fill('\u0000')
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

    private fun showRecoveryConfirmationQuiz(
        phrase: CharArray,
        onConfirmed: (CharArray) -> Unit,
        onAborted: () -> Unit,
    ) {
        val words = String(phrase).trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.size != RECOVERY_WORD_COUNT) {
            phrase.fill('\u0000')
            onAborted()
            Toast.makeText(this, R.string.wallet_recovery_quiz_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val wordList = runCatching {
            assets.open(BIP39_ENGLISH_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map(String::trim).filter(String::isNotBlank).toList()
            }.also { loaded ->
                require(loaded.size == BIP39_ENGLISH_WORD_COUNT)
                require(loaded.distinct().size == BIP39_ENGLISH_WORD_COUNT)
                require(words.all(loaded::contains))
            }
        }.getOrElse {
            phrase.fill('\u0000')
            onAborted()
            Toast.makeText(this, R.string.wallet_recovery_quiz_invalid, Toast.LENGTH_LONG).show()
            return
        }
        var index = 0
        var incorrectChoice = false
        var confirmed = false
        val random = SecureRandom()
        val prompt = TextView(this).apply {
            textSize = 16f
            setTextColor(themeColors().primaryText)
            setPadding(0, 0, 0, uiDp(12))
        }
        val buttons = List(RECOVERY_CHOICE_COUNT) {
            Button(this).apply { isAllCaps = false }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(20), uiDp(8), uiDp(20), 0)
            addView(prompt)
            buttons.forEach { button ->
                addView(button, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
        val dialog = walletAlertDialogBuilder()
            .setTitle(R.string.wallet_recovery_quiz_title)
            .setMessage(R.string.wallet_recovery_quiz_message)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        fun renderQuestion() {
            prompt.text = getString(R.string.wallet_recovery_quiz_word, index + 1, words.size)
            val choices = recoveryWordChoices(words, index, wordList, random)
            buttons.forEachIndexed { choiceIndex, button ->
                button.text = choices[choiceIndex]
                button.setOnClickListener {
                    if (choices[choiceIndex] != words[index]) incorrectChoice = true
                    index += 1
                    if (index < words.size) {
                        renderQuestion()
                    } else {
                        confirmed = !incorrectChoice
                        dialog.dismiss()
                        if (incorrectChoice) {
                            phrase.fill('\u0000')
                            onAborted()
                            walletAlertDialogBuilder()
                                .setTitle(R.string.wallet_recovery_quiz_failed_title)
                                .setMessage(R.string.wallet_recovery_quiz_failed_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        } else {
                            onConfirmed(phrase)
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            if (!confirmed && index < words.size) {
                phrase.fill('\u0000')
                onAborted()
            }
        }
        dialog.setOnShowListener { renderQuestion() }
        dialog.show()
    }

    private fun requestRecoveryPhraseDisplay() {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_recovery_title),
            getString(R.string.wallet_auth_recovery_message),
            action = ::showStoredRecoveryPhrase,
        )
    }

    private fun showStoredRecoveryPhrase() {
        val phrase = runCatching { keyStore.loadRecoveryPhrase() }.getOrNull()
        if (phrase == null) {
            Toast.makeText(this, R.string.wallet_recovery_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val view = RecoveryPhraseView(this).apply { showSecret(phrase) }
        walletAlertDialogBuilder()
            .setTitle(R.string.row_wallet_view_recovery)
            .setMessage(R.string.wallet_recovery_display_warning)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .apply {
                setOnDismissListener { view.clearSecret() }
                show()
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
    }

    private fun requireWalletAuthentication(
        title: String,
        message: String,
        action: () -> Unit,
        cancelled: () -> Unit = {},
    ) {
        if (pendingWalletAuthentication != null) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isDeviceSecure != true) {
            cancelled()
            walletAlertDialogBuilder()
                .setTitle(R.string.wallet_auth_required_title)
                .setMessage(R.string.wallet_auth_required_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.wallet_auth_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
                .show()
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, message)
        if (intent == null) {
            action()
            return
        }
        pendingWalletAuthentication = action
        cancelledWalletAuthentication = cancelled
        walletAuthentication.launch(intent)
    }

    private fun unlockWallet() {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_unlock_title),
            getString(R.string.wallet_auth_unlock_message),
            action = ::resumeWalletUnlockAfterAuthentication,
        )
    }

    private fun unlockWalletAfterAuthentication() {
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
                    val directHnsAvailable =
                        NativeWalletBridge.directHnsRollbackFloor(handle) != null
                    if (directHnsAvailable) {
                        requestLocalNetworkPermissionForDirectShakescape()
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
                    } else if (directHnsAvailable) {
                        startWalletOwnedDirectShakescapeWorker(handle, lease, epoch)
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
        dismissWalletPopupsForLock()
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
            // Android filters multicast delivery unless the foreground owner
            // holds this lock. UPnP discovery needs SSDP responses; PCP and
            // NAT-PMP remain ordinary unicast and continue if Wi-Fi or the
            // lock is unavailable.
            val multicastLock = runCatching {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("hns-shakescape-upnp")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            try {
                var serviceTicks = 0
                var installedRouterRoute: Pair<String, String>? = null
                var routerRouteInitialized = false
                while (
                    walletSessionIsActive() && operationIsCurrent(epoch, lease) && walletHandle == handle
                ) {
                    // The HNS synchronization call and ShakeScape service
                    // share one native controller exclusion domain. Yield as
                    // soon as the UI has authorized a verified wallet round;
                    // otherwise a reachability or peer-maintenance tick can
                    // make the non-blocking sync preflight look spuriously
                    // locked and force a second Unlock/Sync tap.
                    if (busy || walletHnsSyncInProgress) {
                        Thread.sleep(DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS)
                        continue
                    }
                    // `status()` is deliberately non-blocking. Contention is
                    // a temporary scheduling result, not evidence that this
                    // worker or wallet session ended. Only an affirmative
                    // native lock observation may stop the active worker.
                    val nativeLocked = NativeWalletBridge.status(handle)?.locked
                    when {
                        walletDirectShakescapeWorkerMustStop(nativeLocked) -> break
                        !walletDirectShakescapeWorkerMayService(nativeLocked) -> {
                            Thread.sleep(DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS)
                            continue
                        }
                    }
                    if (
                        !routerRouteInitialized ||
                            serviceTicks % DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS == 0
                    ) {
                        val route = currentDirectShakescapeRouterRoute()
                        if (!routerRouteInitialized || route != installedRouterRoute) {
                            NativeWalletBridge.updateWalletOwnedDirectShakescapeRouterRoute(
                                handle,
                                route,
                            )
                            installedRouterRoute = route
                            routerRouteInitialized = true
                        }
                    }
                    // One native call services at most one complete frame. A board
                    // reconciliation can legitimately contain inventory, retained
                    // cancellation proofs, and session-recovery envelopes. Draining
                    // only one frame per foreground tick lets those periodic batches
                    // arrive faster than they are consumed and can strand a requested
                    // offer response behind an ever-growing socket backlog. Continue
                    // only while native proves that it serviced useful work; the first
                    // idle poll ends the burst, so an idle wallet remains inexpensive
                    // and lock/busy checks still run at the outer 250 ms boundary.
                    var directFramesServiced = 0
                    while (
                        directFramesServiced < MAX_DIRECT_SHAKESCAPE_FRAMES_PER_TICK &&
                            !busy &&
                            !walletHnsSyncInProgress &&
                            NativeWalletBridge.serviceWalletOwnedDirectShakescape(handle)
                    ) {
                        directFramesServiced += 1
                    }
                    val transportWorkServiced = directFramesServiced != 0
                    val reconciliationChanged = if (
                        transportWorkServiced ||
                            serviceTicks % DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS == 0
                    ) {
                        NativeWalletBridge.reconcileWalletOwnedDirectShakescape(handle)
                    } else {
                        false
                    }
                    serviceTicks += 1
                    if (
                        transportWorkServiced || reconciliationChanged ||
                            serviceTicks % DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS == 0
                    ) {
                        val executionStatus = NativeWalletBridge.shakescapeExecutions(handle)
                        runOnUiThread {
                            if (
                                directShakescapeWorkerHandle == handle &&
                                    operationIsCurrent(epoch, lease) && walletHandle == handle
                            ) {
                                val transportChanged = refreshDirectShakescapeStatus()
                                val executionChanged = executionStatus?.let {
                                    refreshShakescapeExecutionStatus(it)
                                } ?: false
                                val automaticHnsSyncStarted = executionStatus?.let {
                                    maybeStartAutomaticSwapHnsSync(it)
                                } ?: false
                                val automaticBitcoinSyncStarted = if (automaticHnsSyncStarted) {
                                    false
                                } else {
                                    executionStatus?.let(::maybeStartAutomaticSwapBitcoinSync)
                                        ?: false
                                }
                                if (
                                    transportChanged || executionChanged ||
                                        automaticHnsSyncStarted || automaticBitcoinSyncStarted
                                ) {
                                    renderWalletDashboard()
                                }
                            }
                        }
                    }
                    if (serviceTicks % DIRECT_SHAKESCAPE_NETWORK_MAINTENANCE_TICKS == 0) {
                        runOnUiThread {
                            val status = NativeWalletBridge
                                .walletOwnedDirectShakescapeStatus(handle)
                            if (
                                status?.publiclyReachable == true &&
                                    !status.networkServiceReady &&
                                    !busy && !walletHnsSyncInProgress &&
                                    !WalletHnsLiveSyncPresentationCache
                                        .automaticSyncIsPaused(walletNetwork.id) &&
                                    operationIsCurrent(epoch, lease) && walletHandle == handle
                            ) {
                                Log.i(
                                    TAG,
                                    "Refreshing authenticated HNS state for the active public listener",
                                )
                                synchronizeWalletReads()
                            }
                        }
                    }
                    Thread.sleep(DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS)
                }
            } finally {
                if (multicastLock?.isHeld == true) multicastLock.release()
                if (directShakescapeWorkerHandle == handle) {
                    directShakescapeWorkerHandle = INVALID_HANDLE
                }
            }
        }
    }

    /**
     * Android restricts the route-netlink query used by portable Rust port
     * mappers. LinkProperties is the supported source for the active LAN's
     * exact local IPv4/default-gateway pair.
     */
    private fun currentDirectShakescapeRouterRoute(): Pair<String, String>? = runCatching {
        val connectivity = applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null
        val network = connectivity.activeNetwork ?: return@runCatching null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching null
        if (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) {
            return@runCatching null
        }
        val properties = connectivity.getLinkProperties(network) ?: return@runCatching null
        val local = properties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
            ?: return@runCatching null
        val gateway = properties.routes
            .asSequence()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway as? Inet4Address }
            .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
            ?: return@runCatching null
        val localAddress = local.hostAddress ?: return@runCatching null
        val gatewayAddress = gateway.hostAddress ?: return@runCatching null
        localAddress to gatewayAddress
    }.getOrNull()

    /**
     * Android 17 gates LAN ingress and router-discovery traffic behind this
     * runtime permission. Internet peer traffic and public IPv6 remain valid
     * without it, so denial must not disable the direct Handshake service.
     */
    private fun requestLocalNetworkPermissionForDirectShakescape() {
        if (
            Build.VERSION.SDK_INT < 37 || hasLocalNetworkPermission() ||
                localNetworkPermissionRequestInFlight
        ) return
        localNetworkPermissionRequestInFlight = true
        localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED

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
        val dialog = walletAlertDialogBuilder()
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
        walletAlertDialogBuilder()
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
        val dialog = walletAlertDialogBuilder()
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
        val lease = currentStorageLease() ?: run {
            Log.w(TAG, "Direct HNS synchronization requested without an active wallet lease")
            Toast.makeText(this, R.string.wallet_reads_locked, Toast.LENGTH_SHORT).show()
            return
        }
        val handle = walletHandle
        if (handle == INVALID_HANDLE || unconfirmedDatabaseKey != null) {
            resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
            return
        }
        // The foreground peer worker may briefly own the native controller.
        // hasHnsReads() uses try_lock and reports false on contention, so a
        // UI-thread preflight here can silently discard an explicit Sync tap.
        // The worker below waits for the controller and validates its state.
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
            // JNI acquires the controller lock and checks the active wallet.
            // Nonblocking status/availability calls here would mistake peer
            // maintenance contention for a locked or unconfigured wallet.
            val synchronization = if (!presentationLease.cancellationRequested.get()) {
                synchronizeHnsReadsWithRollbackFloor(handle)
            } else {
                null
            }
            // A completed HNS snapshot is also the authority boundary for
            // verified HNS swap funding/spends. Reconcile the durable swap
            // journal immediately while the controller is known to be
            // unlocked instead of waiting for a direct-peer service tick or
            // an enabled "Atomic swap executions" button. The latter may be
            // unavailable while the peer is offline, exactly when refund and
            // recovery state still needs to remain visible.
            val executionStatus = if (synchronization?.snapshot != null) {
                NativeWalletBridge.shakescapeExecutions(handle)
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
                    synchronization == null -> {
                        refreshControllerState(resetReads = false)
                        Log.w(TAG, "Direct HNS synchronization returned no authenticated result")
                        retainReadProjectionAfterRefreshFailure()
                    }
                    synchronization.snapshot != null -> {
                        refreshControllerState(resetReads = false)
                        Log.i(TAG, "Direct HNS synchronization reached a verified wallet snapshot")
                        renderReadSnapshot(synchronization.snapshot)
                        executionStatus?.let(::refreshShakescapeExecutionStatus)
                    }
                    synchronization.catchup != null -> {
                        Log.i(
                            TAG,
                            "Direct HNS synchronization checkpointed catch-up at " +
                                "${synchronization.catchup.scannedHeight ?: synchronization.catchup.birthdayHeight} " +
                                "of ${synchronization.catchup.scanTargetHeight}",
                        )
                        if (
                            synchronization.catchup.headerState !=
                                NativeWalletHnsCatchupProgress.HeaderState.OutboundPortBlocked
                        ) {
                            // A bounded checkpoint is an internal yield, not
                            // an unlock or terminal wallet state. Keep value
                            // actions disabled across its short retry gap.
                            scheduleHnsCatchupRetry(
                                lease,
                                handle,
                                epoch,
                                authorityGeneration,
                                synchronization.catchup.headerState,
                            )
                            statusView.text = getString(R.string.wallet_status_syncing_reads)
                        } else {
                            // A path-wide port diagnosis is terminal for this
                            // foreground attempt. The user can change network
                            // and explicitly synchronize again; do not spin an
                            // inert two-second retry loop.
                            hnsCatchupRetry?.set(false)
                            hnsCatchupRetry = null
                            statusView.text = getString(R.string.wallet_status_outbound_12038_blocked)
                        }
                        renderReadCatchup(synchronization.catchup)
                    }
                    else -> {
                        refreshControllerState(resetReads = false)
                        retainReadProjectionAfterRefreshFailure()
                    }
                }
                if (
                    synchronization?.catchup == null &&
                        NativeWalletBridge.directHnsRollbackFloor(handle) != null
                ) {
                    startWalletOwnedDirectShakescapeWorker(handle, lease, epoch)
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
        headerState: NativeWalletHnsCatchupProgress.HeaderState,
    ) {
        hnsCatchupRetry?.set(false)
        val retry = AtomicBoolean(true)
        hnsCatchupRetry = retry
        Log.i(TAG, "Scheduling the next bounded direct HNS catch-up round")
        thread(name = "hns-wallet-catchup-retry") {
            try {
                Thread.sleep(directHnsCatchupRetryDelayMillis(headerState))
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
                    // A newly accepted atomic swap may have been waiting for
                    // this cycle to return Kyoto to a durable Ready phase so
                    // the foreground peer worker can install its exact HTLC
                    // watch. Count completion—not only start—as the latest
                    // automatic-sync activity. Otherwise a scan lasting longer
                    // than the cadence is immediately reacquired by the next
                    // status refresh, starving watch installation indefinitely.
                    lastAutomaticSwapBitcoinSyncAtElapsedMillis =
                        SystemClock.elapsedRealtime()
                    renderBitcoinSnapshot(synchronization.snapshot)
                    bitcoinStatusView.text = getString(
                        R.string.wallet_bitcoin_synchronized,
                        synchronization.checkpointHeight,
                        synchronization.connectedPeerCount,
                        synchronization.requiredPeerCount,
                        formatBitcoinSyncDuration(synchronization.totalMs),
                    )
                }
            }
        }
    }

    private fun requestBitcoinSyncCancellation() {
        if (!walletBitcoinSyncInProgress || bitcoinSyncStopRequested) return
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_stop_bitcoin_sync_title)
            .setMessage(R.string.wallet_stop_bitcoin_sync_message)
            .setNegativeButton(R.string.action_keep_synchronizing, null)
            .setPositiveButton(R.string.action_stop_sync) { _, _ ->
                bitcoinSyncStopRequested = true
                if (NativeWalletBridge.stopBitcoinSynchronization(walletHandle)) {
                    automaticSwapBitcoinSyncPausedUntilElapsedMillis =
                        SystemClock.elapsedRealtime() + SWAP_BITCOIN_STOP_OPERATION_GRACE_MILLIS
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
        etaMillis: Long?,
    ): String {
        val elapsed = formatBitcoinSyncDuration(progress.cycleElapsedMs)
        if (progress.stage == "connecting") {
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
        when (progress.stage) {
            "fetching_blocks" -> return getString(
                R.string.wallet_bitcoin_sync_fetching_blocks,
                progress.downloadedBlockCount,
                progress.matchedFilterCount,
                elapsed,
            )
            "applying_wallet" -> return getString(
                R.string.wallet_bitcoin_sync_applying_wallet,
                elapsed,
            )
            "validating_chain" -> return getString(
                R.string.wallet_bitcoin_sync_validating_chain,
                elapsed,
            )
            "reconciling" -> return getString(
                R.string.wallet_bitcoin_sync_reconciling,
                elapsed,
            )
            "ready" -> return getString(R.string.wallet_bitcoin_ready)
            "failed" -> return getString(R.string.wallet_bitcoin_sync_failed)
        }
        if (progress.completionBasisPoints <= 0L) {
            val chainHeight = progress.chainHeight
            if (chainHeight != null) {
                return getString(
                    R.string.wallet_bitcoin_sync_discovering_with_headers,
                    chainHeight,
                    progress.processedFilterCount,
                    elapsed,
                )
            }
            return getString(
                R.string.wallet_bitcoin_sync_discovering,
                progress.processedFilterCount,
                elapsed,
            )
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
            progress.processedFilterCount,
            progress.matchedFilterCount,
        )
    }

    private fun resetBitcoinProjection() {
        bitcoinSnapshot = null
        bitcoinActivityPageOffset = 0
        bitcoinBalanceView.text = getString(R.string.wallet_bitcoin_balance_unavailable)
        bitcoinReceiveView.text = getString(R.string.wallet_bitcoin_receive_unavailable)
        bitcoinActivityView.text = getString(R.string.wallet_bitcoin_activity_unavailable)
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

    /** Refresh live transport state and report whether the dashboard's peer
     * projection changed. Historical pairing text is never connection proof. */
    private fun refreshDirectShakescapeStatus(): Boolean {
        val status = freshDirectShakescapeStatus(walletHandle) ?: return false
        directShakescapeTransportStatus = status
        val previousPeerEndpoint = directShakescapePeerEndpoint
        directShakescapePeerEndpoint = status.peerEndpoint
        status.peerEndpoint?.let(::rememberDirectShakescapePeer)
        if (previousPeerEndpoint != null && directShakescapePeerEndpoint == null) {
            shakedexQueryStatusView.text = getString(R.string.wallet_direct_shakescape_no_peer)
        }
        val reachability = status.let { current ->
            when {
                current.advertised -> getString(
                    R.string.wallet_direct_shakescape_reachability_advertised,
                    when {
                        current.publicIpv6 -> "public IPv6"
                        current.routerMapped -> "router TCP mapping"
                        else -> "public TCP"
                    },
                    current.peerCount,
                    current.candidateCount,
                )
                current.publiclyReachable && current.networkServiceReady -> getString(
                    R.string.wallet_direct_shakescape_reachability_waiting_hsd_peer,
                    current.candidateCount,
                )
                current.publiclyReachable -> getString(
                    R.string.wallet_direct_shakescape_reachability_waiting_network,
                    current.candidateCount,
                )
                current.publicIpv6 -> getString(
                    R.string.wallet_direct_shakescape_reachability_ipv6_verification,
                    current.candidateCount,
                )
                current.networkServiceReady -> getString(
                    if (hasLocalNetworkPermission()) {
                        R.string.wallet_direct_shakescape_reachability_mapping
                    } else {
                        R.string.wallet_direct_shakescape_reachability_local_network_permission
                    },
                    current.candidateCount,
                )
                else -> getString(
                    R.string.wallet_direct_shakescape_reachability_syncing,
                    current.candidateCount,
                )
            }
        }
        directShakescapeStatusView.text = when {
            !status.unlocked -> getString(R.string.wallet_direct_shakescape_locked)
            status.listenerPort == null -> getString(
                R.string.wallet_direct_shakescape_host_unavailable,
                DIRECT_SHAKESCAPE_LISTEN_PORT,
                status.peerEndpoint ?: getString(R.string.wallet_direct_shakescape_peer_none),
                reachability,
            )

            status.peerEndpoint == null -> getString(
                R.string.wallet_direct_shakescape_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_shakescape_peer_none),
                reachability,
            )

            else -> getString(
                R.string.wallet_direct_shakescape_host_listening,
                status.listenerPort,
                getString(R.string.wallet_direct_shakescape_peer_connected, status.peerEndpoint),
                reachability,
            )
        }
        return previousPeerEndpoint != directShakescapePeerEndpoint
    }

    /** Bound a transient native try-lock miss without treating it as state. */
    private fun freshDirectShakescapeStatus(
        handle: Long,
    ): NativeWalletDirectShakescapeStatus? {
        if (handle == INVALID_HANDLE) return null
        repeat(5) { attempt ->
            NativeWalletBridge.walletOwnedDirectShakescapeStatus(handle)?.let { return it }
            if (attempt < 4) Thread.sleep(10)
        }
        return null
    }

    /** Project durable settlement progress into the ShakeDex card itself.
     * Pairing is transport; this is the locally verified execution state. */
    private fun refreshShakescapeExecutionStatus(
        status: NativeShakescapeExecutionStatus,
    ): Boolean {
        val previous = shakedexExecutionStatusView.text.toString()
        latestShakescapeExecutionStatus = status
        publishAtomicSwapNotifications(status)
        val terminal = setOf("completed", "refunded", "failed")
        val execution = status.executions
            .filterNot { it.state in terminal }
            .maxByOrNull { it.lastVerifiedAtUnix }
        val pending = status.pendingAcceptances.maxByOrNull { it.createdAtUnix }
        if (execution == null && pending == null) {
            activeSwapHnsRefreshAttemptedHeight = null
            finishWalletForegroundSyncIfIdle()
        } else {
            // An accepted atomic swap is an explicit request to keep its
            // authenticated peer and chain watches alive. Android requires a
            // visible foreground service before that work may survive screen
            // off; transaction signing still requires a separate approval.
            startWalletForegroundSyncService("atomic swap monitoring")
        }
        activeShakescapeDashboardSummary = when {
            execution != null -> getString(
                R.string.wallet_dashboard_swap_active,
                execution.state.replace('_', ' '),
            )
            pending != null -> getString(R.string.wallet_dashboard_swap_negotiating)
            else -> null
        }
        shakedexExecutionStatusView.text = when {
            execution != null -> getString(
                R.string.wallet_swap_status_active,
                swapExecutionStage(execution),
                formatSwapAmount(execution.offeredAsset, execution.offeredAmount),
                formatSwapAmount(execution.receivedAsset, execution.receivedAmount),
                execution.sessionId.take(12),
            )
            pending != null -> getString(
                R.string.wallet_swap_status_negotiating,
                formatSwapAmount(pending.offeredAsset, pending.offeredAmount),
                formatSwapAmount(pending.receivedAsset, pending.receivedAmount),
                pending.sessionId.take(12),
            )
            status.executions.isNotEmpty() -> {
                val latest = status.executions.maxByOrNull { it.lastVerifiedAtUnix }!!
                getString(
                    R.string.wallet_swap_status_terminal,
                    latest.state.replace('_', ' '),
                    latest.sessionId.take(12),
                    latest.failureReason ?: getString(R.string.wallet_swap_status_no_failure),
                )
            }
            else -> getString(R.string.wallet_swap_status_none)
        }
        return previous != shakedexExecutionStatusView.text.toString()
    }

    private fun publishAtomicSwapNotifications(status: NativeShakescapeExecutionStatus) {
        if (!::atomicSwapNotifications.isInitialized) return
        val permissionNeeded = atomicSwapNotifications.reconcile(
            status,
            ::swapExecutionNotificationStage,
        )
        if (
            permissionNeeded && !atomicSwapNotificationPermissionRequestInFlight &&
                atomicSwapNotifications.shouldRequestPermission()
        ) {
            atomicSwapNotificationPermissionRequestInFlight = true
            atomicSwapNotifications.markPermissionRequested()
            atomicSwapNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun swapExecutionNotificationStage(
        execution: NativeShakescapeExecutionSummary,
    ): String = swapExecutionStage(execution, includeBitcoinSync = false)

    private fun swapExecutionStage(
        execution: NativeShakescapeExecutionSummary,
        includeBitcoinSync: Boolean = true,
    ): String {
        val first = execution.firstChain.replaceFirstChar { it.uppercase() }
        val second = execution.secondChain.replaceFirstChar { it.uppercase() }
        val localFundingSubmitted = execution.localFundingState == "broadcast" ||
            execution.localFundingState == "seen" ||
            execution.localFundingState == "confirmed"
        if (includeBitcoinSync && walletBitcoinSyncInProgress) {
            return getString(R.string.wallet_swap_stage_bitcoin_syncing)
        }
        return when (execution.state) {
            "terms_frozen", "refunds_prepared" ->
                getString(R.string.wallet_swap_stage_terms_waiting)
            "first_funding_pending" -> if (
                execution.localRole == "maker" &&
                System.currentTimeMillis() / 1_000L >= execution.fundingDeadlineUnix
            ) {
                getString(R.string.wallet_swap_stage_funding_expired)
            } else if (
                execution.localRole == "maker" && localFundingSubmitted
            ) {
                getString(R.string.wallet_swap_stage_funding_submitted, first)
            } else if (
                execution.localRole == "maker" && execution.localFundingState == "reorged"
            ) {
                getString(R.string.wallet_swap_stage_funding_reorged, first)
            } else if (execution.localRole == "maker") {
                getString(R.string.wallet_swap_stage_funding_ready_here, first)
            } else {
                getString(R.string.wallet_swap_stage_waiting_counterparty_funding, first)
            }
            "first_funded" -> if (execution.localRole == "taker") {
                if (System.currentTimeMillis() / 1_000L < execution.fundingDeadlineUnix) {
                    if (localFundingSubmitted) {
                        getString(R.string.wallet_swap_stage_funding_submitted, second)
                    } else if (execution.localFundingState == "reorged") {
                        getString(R.string.wallet_swap_stage_funding_reorged, second)
                    } else {
                        getString(R.string.wallet_swap_stage_funding_ready_here, second)
                    }
                } else {
                    getString(R.string.wallet_swap_stage_funding_expired)
                }
            } else if (
                System.currentTimeMillis() / 1_000L >= execution.firstRefundAtUnix
            ) {
                // The maker never prepares the second-chain lock. Once the
                // signed first-chain timeout has elapsed, direct the maker to
                // the exact native refund action instead of implying that a
                // now-unsafe counterparty funding transition is in progress.
                getString(R.string.wallet_swap_stage_refund_ready_here, first)
            } else {
                getString(R.string.wallet_swap_stage_waiting_counterparty_funding, second)
            }
            "second_funding_pending" -> if (execution.localRole == "taker") {
                if (localFundingSubmitted) {
                    getString(R.string.wallet_swap_stage_funding_submitted, second)
                } else if (execution.localFundingState == "reorged") {
                    getString(R.string.wallet_swap_stage_funding_reorged, second)
                } else {
                    getString(R.string.wallet_swap_stage_funding_ready_here, second)
                }
            } else {
                getString(R.string.wallet_swap_stage_waiting_counterparty_funding, second)
            }
            "both_funded" -> if (execution.localRole == "maker") {
                getString(R.string.wallet_swap_stage_redeem_ready_here, second)
            } else {
                getString(R.string.wallet_swap_stage_waiting_counterparty_redeem, second)
            }
            "first_redeemed", "secret_observed" -> if (execution.localRole == "taker") {
                getString(R.string.wallet_swap_stage_secret_redeem_ready_here, first)
            } else {
                getString(R.string.wallet_swap_stage_waiting_counterparty_final_redeem, first)
            }
            "second_redeemed" -> getString(R.string.wallet_swap_stage_second_redeemed)
            "completed" -> getString(R.string.wallet_swap_stage_completed)
            "refund_eligible", "refund_broadcast" -> getString(R.string.wallet_swap_stage_refunding)
            "refunded" -> getString(R.string.wallet_swap_stage_refunded)
            "failed" -> getString(R.string.wallet_swap_stage_failed)
            else -> execution.state.replace('_', ' ')
        }
    }

    private fun hasLiveAtomicSwap(
        status: NativeShakescapeExecutionStatus? = latestShakescapeExecutionStatus,
    ): Boolean {
        val projection = status ?: return false
        val terminal = setOf("completed", "refunded", "failed")
        return projection.pendingAcceptances.isNotEmpty() ||
            projection.executions.any { it.state !in terminal }
    }

    /**
     * Screen-off stops the browser Activity's header observer, but a live swap
     * still has to discover HNS confirmations, redemptions, and refund state.
     * Run a bounded native scan on a conservative cadence while the explicit
     * atomic-swap foreground service retains this unlocked controller.
     */
    private fun maybeStartAutomaticSwapHnsSync(
        status: NativeShakescapeExecutionStatus,
    ): Boolean {
        if (!hasLiveAtomicSwap(status)) {
            lastAutomaticSwapHnsSyncAtElapsedMillis = Long.MIN_VALUE
            lastAutomaticSwapHnsSyncFingerprint = null
            return false
        }
        val fingerprint = buildString {
            status.executions
                .filter { it.state !in setOf("completed", "refunded", "failed") }
                .sortedBy { it.sessionId }
                .forEach {
                    append(it.sessionId).append(':').append(it.revision).append(':')
                        .append(it.state).append(';')
                }
            status.pendingAcceptances.sortedBy { it.sessionId }.forEach {
                append(it.sessionId).append(':').append(it.createdAtUnix).append(';')
            }
        }
        val previousFingerprint = lastAutomaticSwapHnsSyncFingerprint
        if (fingerprint != previousFingerprint) {
            lastAutomaticSwapHnsSyncFingerprint = fingerprint
            walletActiveSwapFingerprintBaseline(
                previousFingerprint = previousFingerprint,
                currentSnapshotObservedAtElapsedMillis =
                    latestReadSnapshotObservedAtElapsedMillis.takeIf {
                        latestReadSnapshot != null && hasCurrentWalletReadSnapshot(walletHandle)
                    },
            )?.let { lastAutomaticSwapHnsSyncAtElapsedMillis = it }
        }
        val now = SystemClock.elapsedRealtime()
        if (
            busy || walletHnsSyncInProgress || walletBitcoinSyncInProgress ||
                walletHandle == INVALID_HANDLE || !NativeWalletBridge.hasHnsReads(walletHandle)
        ) return false
        if (
            lastAutomaticSwapHnsSyncAtElapsedMillis != Long.MIN_VALUE &&
                now - lastAutomaticSwapHnsSyncAtElapsedMillis <
                SWAP_HNS_AUTO_SYNC_INTERVAL_MILLIS
        ) return false
        lastAutomaticSwapHnsSyncAtElapsedMillis = now
        Log.i(TAG, "Starting automatic HNS synchronization for active atomic swap state")
        synchronizeWalletReads()
        return walletHnsSyncInProgress
    }

    /** Keep both swap participants' compact-filter/watch state current while
     * an execution is live. This is read-only synchronization; funding and
     * settlement remain behind explicit native approval dialogs. */
    private fun maybeStartAutomaticSwapBitcoinSync(
        status: NativeShakescapeExecutionStatus,
    ): Boolean {
        val terminal = setOf("completed", "refunded", "failed")
        val liveExecutions = status.executions.filter { it.state !in terminal }
        if (liveExecutions.isEmpty() && status.pendingAcceptances.isEmpty()) {
            automaticSwapBitcoinSyncPausedUntilElapsedMillis = Long.MIN_VALUE
            lastAutomaticSwapBitcoinSyncFingerprint = null
            return false
        }
        val fingerprint = buildString {
            liveExecutions.sortedBy { it.sessionId }.forEach {
                append(it.sessionId).append(':').append(it.revision).append(':')
                    .append(it.state).append(';')
            }
            status.pendingAcceptances.sortedBy { it.sessionId }.forEach {
                append(it.sessionId).append(':').append(it.createdAtUnix).append(';')
            }
        }
        if (fingerprint != lastAutomaticSwapBitcoinSyncFingerprint) {
            lastAutomaticSwapBitcoinSyncFingerprint = fingerprint
            lastAutomaticSwapBitcoinSyncAtElapsedMillis = Long.MIN_VALUE
        }
        val now = SystemClock.elapsedRealtime()
        if (now < automaticSwapBitcoinSyncPausedUntilElapsedMillis) return false
        // A new protocol revision, including a transition that makes local
        // Bitcoin funding actionable, must first establish Kyoto's durable
        // Ready checkpoint. The dashboard disables value actions while this
        // bounded scan owns the controller, so preparation cannot race it.
        if (walletBitcoinSyncInProgress ||
            bitcoinBirthdayResetInProgress || busy ||
            walletHandle == INVALID_HANDLE || !NativeWalletBridge.hasBitcoinValue(walletHandle)
        ) return false
        if (lastAutomaticSwapBitcoinSyncAtElapsedMillis != Long.MIN_VALUE &&
            now - lastAutomaticSwapBitcoinSyncAtElapsedMillis < SWAP_BITCOIN_AUTO_SYNC_INTERVAL_MILLIS
        ) return false
        lastAutomaticSwapBitcoinSyncAtElapsedMillis = now
        Log.i(TAG, "Starting automatic Bitcoin synchronization for active atomic swap state")
        synchronizeBitcoin()
        refreshShakescapeExecutionStatus(status)
        return walletBitcoinSyncInProgress
    }

    private fun clearDirectShakescapeStatusProjection(locked: Boolean) {
        directShakescapeTransportStatus = null
        directShakescapePeerEndpoint = null
        latestShakescapeExecutionStatus = null
        activeShakescapeDashboardSummary = null
        lastAutomaticSwapHnsSyncAtElapsedMillis = Long.MIN_VALUE
        lastAutomaticSwapHnsSyncFingerprint = null
        lastAutomaticSwapBitcoinSyncAtElapsedMillis = Long.MIN_VALUE
        lastAutomaticSwapBitcoinSyncFingerprint = null
        automaticSwapBitcoinSyncPausedUntilElapsedMillis = Long.MIN_VALUE
        if (::directShakescapeStatusView.isInitialized) {
            directShakescapeStatusView.text = getString(
                if (locked) R.string.wallet_direct_shakescape_locked
                else R.string.wallet_direct_shakescape_unavailable,
            )
        }
        if (::shakedexExecutionStatusView.isInitialized) {
            shakedexExecutionStatusView.text = getString(
                if (locked) R.string.wallet_swap_status_locked
                else R.string.wallet_swap_status_unavailable,
            )
        }
    }

    private fun renderBitcoinSnapshot(snapshot: com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot) {
        bitcoinSnapshot = snapshot
        bitcoinActivityPageOffset = 0
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
        bitcoinActivityView.text = bitcoinActivitySummary()
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
        showWalletFormDialog(
            title = getString(R.string.wallet_bitcoin_birthday_title),
            message = getString(R.string.wallet_bitcoin_birthday_message),
            fields = listOf(getString(R.string.wallet_bitcoin_birthday_hint) to input),
            primaryLabel = getString(R.string.action_apply),
            onPrimary = { dialog ->
                val height = input.text?.toString()?.toLongOrNull()
                    ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                if (height == null) {
                    input.error = getString(R.string.wallet_bitcoin_birthday_invalid_height)
                } else {
                    wipeEditable(input.text)
                    dialog.dismiss()
                    setBitcoinBirthdayHeight(height)
                }
            },
            onDismiss = { wipeEditable(input.text) },
        )
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
        val readOnly: Boolean = false,
        val multiline: Boolean = false,
        val maxCharacters: Int = MAX_VALUE_ACTION_INPUT_CHARACTERS,
    )

    private data class WalletModalAction(
        val label: String,
        val enabled: Boolean = true,
        val action: () -> Unit,
        val dismissParent: Boolean = false,
    )

    private data class WalletModalActionSection(
        val title: String,
        val actions: List<WalletModalAction>,
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
        lateinit var dialog: AlertDialog
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(uiDp(18), uiDp(18), uiDp(18), uiDp(12))
            setBackgroundColor(themeColors().background)
            addView(TextView(this@WalletActivity).apply {
                text = getString(title)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColors().primaryText)
                setPadding(uiDp(2), 0, uiDp(2), uiDp(14))
                ViewCompat.setAccessibilityHeading(this, true)
            })
        }
        val inputs = fields.map { field ->
            EditText(this).apply {
                hint = getString(field.hint)
                inputType = if (field.numeric) {
                    InputType.TYPE_CLASS_NUMBER or (
                        if (field.decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                    )
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                        (if (field.multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                }
                filters = arrayOf(InputFilter.LengthFilter(field.maxCharacters))
                setSingleLine(!field.multiline)
                if (field.multiline) {
                    minLines = 7
                    gravity = Gravity.TOP or Gravity.START
                }
                if (field.initial.isNotEmpty()) setText(field.initial)
                if (field.readOnly) {
                    isFocusable = false
                    isCursorVisible = false
                    isLongClickable = false
                }
                setTextColor(themeColors().primaryText)
                setHintTextColor(themeColors().secondaryText)
            }.also { input ->
                layout.addView(
                    walletModalDetailCard(getString(field.hint), input),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = uiDp(10) },
                )
            }
        }
        layout.addView(walletModalSectionHeading(getString(R.string.wallet_modal_actions)))
        layout.addView(
            dashboardActionButton(getString(R.string.action_prepare)) {
                val values = inputs.map { it.text?.toString().orEmpty() }
                inputs.forEach { wipeEditable(it.text) }
                dialog.dismiss()
                submit(values)
            }.apply { minimumHeight = uiDp(48) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = uiDp(8) },
        )
        layout.addView(
            dashboardActionButton(getString(R.string.action_cancel), secondary = true) {
                dialog.dismiss()
            }.apply { minimumHeight = uiDp(48) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        dialog = walletAlertDialogBuilder()
            .setView(scroll)
            .create()
        dialog.setOnDismissListener { inputs.forEach { wipeEditable(it.text) } }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                settingsSurfaceDrawable(
                    accent = themeColors().divider,
                    fill = themeColors().background,
                    cornerRadius = 24,
                ),
            )
        }
        dialog.show()
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
                WalletActionInput(
                    R.string.wallet_swap_btc_amount_hint,
                    numeric = true,
                    initial = NativeWalletBridge.MINIMUM_BITCOIN_HTLC_SATS.toString(),
                ),
                WalletActionInput(R.string.wallet_swap_hns_amount_hint),
                WalletActionInput(
                    R.string.wallet_swap_fee_reserve_hint,
                    numeric = true,
                    initial = NativeWalletBridge.MINIMUM_BITCOIN_FEE_RESERVE_SATS.toString(),
                ),
                WalletActionInput(R.string.wallet_swap_lifetime_hours_hint, initial = "24", numeric = true),
            ),
        ) { values ->
            val btc = values[0].toLongOrNull()
                ?.takeIf { it >= NativeWalletBridge.MINIMUM_BITCOIN_HTLC_SATS }
            val hns = parsePositiveHnsToBaseUnits(values[1])?.toLongOrNull()
                ?.takeIf { it >= NativeWalletBridge.MINIMUM_HNS_SWAP_DOLLARYDOOS }
            val reserve = values[2].toLongOrNull()?.takeIf {
                it >= NativeWalletBridge.MINIMUM_BITCOIN_FEE_RESERVE_SATS
            }
            val lifetime = values[3].toLongOrNull()
                ?.takeIf { it in 2L..168L }
                ?.let { runCatching { Math.multiplyExact(it, 3_600L) }.getOrNull() }
            if (btc == null || hns == null || reserve == null || btc < reserve ||
                btc - reserve < NativeWalletBridge.BITCOIN_HTLC_RECEIVER_DUST_SATS ||
                lifetime == null
            ) {
                bitcoinStatusView.text = getString(R.string.wallet_swap_prepare_failed)
                return@showWalletActionForm
            }
            prepareBtcForHnsOffer(btc, hns, reserve, lifetime)
        }
    }

    private fun showHnsForBtcOfferForm() {
        showWalletActionForm(
            R.string.wallet_swap_sell_hns,
            listOf(
                WalletActionInput(R.string.wallet_swap_hns_offered_hint),
                WalletActionInput(
                    R.string.wallet_swap_btc_requested_hint,
                    numeric = true,
                    initial = NativeWalletBridge.MINIMUM_BITCOIN_HTLC_SATS.toString(),
                ),
                WalletActionInput(
                    R.string.wallet_swap_hns_fee_reserve_hint,
                    initial = DEFAULT_HNS_MAXIMUM_FEE,
                ),
                WalletActionInput(
                    R.string.wallet_swap_lifetime_hours_hint,
                    initial = "24",
                    numeric = true,
                ),
            ),
        ) { values ->
            val hns = parsePositiveHnsToBaseUnits(values[0])?.toLongOrNull()
                ?.takeIf { it >= NativeWalletBridge.MINIMUM_HNS_SWAP_DOLLARYDOOS }
            val btc = values[1].toLongOrNull()
                ?.takeIf { it >= NativeWalletBridge.MINIMUM_BITCOIN_HTLC_SATS }
            val reserve = parsePositiveHnsToBaseUnits(values[2])?.toLongOrNull()?.takeIf { it > 0L }
            val lifetime = values[3].toLongOrNull()
                ?.takeIf { it in 2L..168L }
                ?.let { runCatching { Math.multiplyExact(it, 3_600L) }.getOrNull() }
            if (hns == null || btc == null || reserve == null ||
                reserve < NativeWalletBridge.MINIMUM_HNS_FEE_RESERVE_DOLLARYDOOS ||
                hns < reserve ||
                hns - reserve < NativeWalletBridge.HNS_SWAP_RECEIVER_DUST_DOLLARYDOOS ||
                lifetime == null
            ) {
                bitcoinStatusView.text = getString(R.string.wallet_swap_hns_prepare_failed)
                return@showWalletActionForm
            }
            prepareHnsForBtcOffer(hns, btc, reserve, lifetime)
        }
    }

    private fun showMyDirectOffers() {
        val handle = walletHandle
        if (handle == INVALID_HANDLE || busy) return
        bitcoinStatusView.text = getString(R.string.wallet_swap_loading_my_offers)
        thread(name = "direct-offer-list") {
            val offers = NativeWalletBridge.localDirectOffers(handle)
            runOnUiThread {
                if (walletHandle != handle) return@runOnUiThread
                if (offers == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_list_failed)
                } else if (offers.isEmpty()) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_no_active_offers)
                } else {
                    val labels = offers.map(::directOfferLabel).toTypedArray()
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_my_offers)
                        .setItems(labels) { _, index -> confirmCancelDirectOffer(offers[index]) }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun showAvailableDirectOffers() {
        val handle = walletHandle
        if (handle == INVALID_HANDLE || busy) return
        bitcoinStatusView.text = getString(R.string.wallet_swap_loading_available_offers)
        thread(name = "available-direct-offer-list") {
            val offers = NativeWalletBridge.availableDirectOffers(handle)
            runOnUiThread {
                if (walletHandle != handle) return@runOnUiThread
                if (offers == null) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_available_failed)
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_available_offers)
                        .setMessage(R.string.wallet_swap_available_failed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else if (offers.isEmpty()) {
                    bitcoinStatusView.text = getString(R.string.wallet_swap_no_available_offers)
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_available_offers)
                        .setMessage(R.string.wallet_swap_no_available_offers)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_available_offers)
                        .setItems(offers.map(::directOfferLabel).toTypedArray()) { _, index ->
                            showDirectOfferTakeForm(offers[index])
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun directOfferLabel(offer: NativeDirectOfferSummary): String =
        if (offer.makerSellsHns) {
            "${formatHnsBaseUnits(offer.hnsAmountDollarydoos.toString())} HNS → ${offer.btcAmountSats} sats · ${offer.offerId.take(12)}…"
        } else {
            "${offer.btcAmountSats} sats → ${formatHnsBaseUnits(offer.hnsAmountDollarydoos.toString())} HNS · ${offer.offerId.take(12)}…"
        }

    private fun showDirectOfferTakeForm(offer: NativeDirectOfferSummary) {
        val bitcoin = offer.receivedAsset == "btc"
        showWalletActionForm(
            R.string.wallet_swap_take_offer,
            listOf(WalletActionInput(
                if (bitcoin) R.string.wallet_swap_take_btc_fee_reserve_hint
                else R.string.wallet_swap_take_hns_fee_reserve_hint,
                numeric = bitcoin,
                initial = if (bitcoin) NativeWalletBridge.MINIMUM_BITCOIN_FEE_RESERVE_SATS.toString()
                else DEFAULT_HNS_MAXIMUM_FEE,
            )),
        ) { values ->
            val reserve = if (bitcoin) values.single().toLongOrNull()
            else parsePositiveHnsToBaseUnits(values.single())?.toLongOrNull()
            val leavesSpendableOutput = reserve != null && offer.receivedAmount >= reserve &&
                if (bitcoin) {
                    offer.receivedAmount - reserve >=
                        NativeWalletBridge.BITCOIN_HTLC_RECEIVER_DUST_SATS
                } else {
                    offer.receivedAmount - reserve >=
                        NativeWalletBridge.HNS_SWAP_RECEIVER_DUST_DOLLARYDOOS
                }
            if (reserve == null || reserve <= 0L || !leavesSpendableOutput ||
                bitcoin && reserve < NativeWalletBridge.MINIMUM_BITCOIN_FEE_RESERVE_SATS ||
                !bitcoin && reserve < NativeWalletBridge.MINIMUM_HNS_FEE_RESERVE_DOLLARYDOOS
            ) {
                bitcoinStatusView.text = getString(R.string.wallet_swap_take_prepare_failed)
            } else {
                val available = if (bitcoin) {
                    bitcoinSnapshot?.confirmedSats
                } else {
                    latestReadSnapshot
                        ?.takeIf {
                            latestReadSnapshotHandle == walletHandle &&
                                latestReadSnapshotAuthorityGeneration == walletAuthorityGeneration &&
                                latestReadSnapshotEpoch == lifecycleEpoch
                        }
                        ?.hnsBalanceProjection()
                        ?.spendableBaseUnits
                        ?.toLongOrNull()
                }
                val required = directOfferTakeRequiredFunding(offer.receivedAmount, reserve)
                if (required != null && available != null && required > available) {
                    val message = if (bitcoin) {
                        getString(
                            R.string.wallet_swap_take_insufficient_btc,
                            offer.receivedAmount,
                            reserve,
                            required,
                            available,
                        )
                    } else {
                        getString(
                            R.string.wallet_swap_take_insufficient_hns,
                            formatHnsBaseUnits(offer.receivedAmount.toString()),
                            formatHnsBaseUnits(reserve.toString()),
                            formatHnsBaseUnits(required.toString()),
                            formatHnsBaseUnits(available.toString()),
                        )
                    }
                    bitcoinStatusView.text = message
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_take_offer)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    prepareDirectOfferTake(offer, reserve)
                }
            }
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
                walletAlertDialogBuilder()
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
                    val message = getString(R.string.wallet_swap_execution_list_failed)
                    bitcoinStatusView.text = message
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_executions)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else if (status.executions.isEmpty() && status.pendingAcceptances.isEmpty()) {
                    val message = getString(R.string.wallet_swap_no_executions_waiting) +
                        "\n\n" + bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery)
                    bitcoinStatusView.text = message
                    walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_executions)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    bitcoinStatusView.text = bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery)
                    val terminalStates = setOf("completed", "refunded", "failed")
                    val orderedExecutions = status.executions.sortedWith(
                        compareBy<NativeShakescapeExecutionSummary> {
                            it.state in terminalStates
                        }.thenByDescending { it.lastVerifiedAtUnix },
                    )
                    val liveExecutions = orderedExecutions.filterNot {
                        it.state in terminalStates
                    }
                    if (status.pendingAcceptances.isEmpty() && liveExecutions.size == 1) {
                        showShakescapeExecution(liveExecutions.single())
                        return@runOnUiThread
                    }
                    val pendingLabels = status.pendingAcceptances.map {
                        getString(
                            R.string.wallet_swap_pending_acceptance,
                            formatSwapAmount(it.offeredAsset, it.offeredAmount),
                            formatSwapAmount(it.receivedAsset, it.receivedAmount),
                            formatSwapAmount(it.receivedAsset, it.receivedFeeReserve),
                            it.sessionId.take(12),
                        )
                    }
                    val executionLabels = orderedExecutions.map {
                        "${it.state.replace('_', ' ')} · ${it.offeredAmount} ${it.offeredAsset.uppercase()} → ${it.receivedAmount} ${it.receivedAsset.uppercase()} · ${it.sessionId.take(12)}…"
                    }
                    val labels = (pendingLabels + executionLabels).toTypedArray()
                    // AlertDialog's message ScrollView and selectable ListView
                    // are mutually exclusive content modes on Android. Setting
                    // both leaves the recovery message visible while silently
                    // suppressing every acceptance/execution row. Keep the
                    // recovery projection on the Bitcoin card and reserve this
                    // dialog's content area for the actionable inventory.
                    val picker = walletAlertDialogBuilder()
                        .setTitle(R.string.wallet_swap_executions)
                        .setItems(labels) { _, index ->
                            if (index < status.pendingAcceptances.size) {
                                confirmAbandonPendingAcceptance(status.pendingAcceptances[index])
                            } else {
                                showShakescapeExecution(
                                    orderedExecutions[index - status.pendingAcceptances.size],
                                )
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .create()
                    // The inventory is loaded on a native worker. On a fast
                    // return, attaching its ListView immediately can let the
                    // originating action's trailing release select whichever
                    // execution occupies the same screen coordinate. Do not
                    // attach an actionable window until that gesture has
                    // unconditionally drained.
                    window.decorView.postDelayed({
                        if (!isFinishing && !isDestroyed && walletHandle == handle) {
                            picker.show()
                        }
                    }, 500L)
                }
            }
        }
    }

    private fun formatSwapAmount(asset: String, amount: Long): String =
        if (asset == "hns") {
            "${formatHnsBaseUnits(amount.toString())} HNS"
        } else {
            "$amount sats"
        }

    private fun confirmAbandonPendingAcceptance(take: com.denuoweb.hnsdane.wallet.NativeDirectOfferTakeSummary) {
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_abandon_acceptance_title)
            .setMessage(getString(
                R.string.wallet_swap_abandon_acceptance_message,
                formatSwapAmount(
                    take.receivedAsset,
                    take.receivedAmount,
                ),
                take.sessionId,
            ))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.wallet_swap_abandon_acceptance) { _, _ ->
                val handle = walletHandle
                thread(name = "direct-take-abandon") {
                    val abandoned = NativeWalletBridge.abandonPendingDirectOfferTake(
                        handle,
                        take.sessionId,
                    )
                    runOnUiThread {
                        if (walletHandle != handle) return@runOnUiThread
                        bitcoinStatusView.text = getString(
                            if (abandoned) R.string.wallet_swap_acceptance_abandoned
                            else R.string.wallet_swap_acceptance_abandon_failed,
                        )
                        if (abandoned) {
                            latestReadSnapshot?.let(::renderReadSnapshot)
                        }
                        showShakescapeExecutions()
                    }
                }
            }
            .show()
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
            execution.localRole,
            execution.firstChain,
            execution.secondChain,
            execution.firstFundingConfirmed.toString(),
            execution.secondFundingConfirmed.toString(),
            execution.fundingDeadlineUnix,
            execution.firstRefundAtUnix,
            execution.secondRefundAtUnix,
            execution.failureReason ?: getString(R.string.wallet_swap_failure_none),
        )
        val builder = walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_execution_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
        val fundingChain = when (execution.state) {
            "first_funding_pending" -> execution.firstChain.takeIf {
                execution.localRole == "maker" &&
                    System.currentTimeMillis() / 1_000L < execution.fundingDeadlineUnix
            }
            // Preparing the taker's second-chain lock is the operation that
            // durably applies SecondFundingReady. Waiting until the execution
            // already says `second_funding_pending` hides the only action that
            // can make that transition and deadlocks every two-chain swap.
            "first_funded" -> execution.secondChain.takeIf {
                execution.localRole == "taker" &&
                    System.currentTimeMillis() / 1_000L < execution.fundingDeadlineUnix
            }
            "second_funding_pending" -> execution.secondChain.takeIf {
                execution.localRole == "taker"
            }
            else -> null
        }
        if (fundingChain == "bitcoin") {
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
        } else if (fundingChain == "handshake") {
            builder.setPositiveButton(R.string.wallet_swap_fund_hns) { _, _ ->
                showWalletActionForm(
                    R.string.wallet_swap_fund_hns,
                    listOf(WalletActionInput(
                        R.string.wallet_swap_hns_funding_fee_hint,
                        numeric = true,
                        initial = DEFAULT_HNS_MAXIMUM_FEE_BASE_UNITS,
                    )),
                ) { values ->
                    val fee = values.single().toLongOrNull()?.takeIf { it > 0L }
                    if (fee == null) bitcoinStatusView.text = getString(R.string.wallet_swap_hns_funding_prepare_failed)
                    else prepareHnsForBtcFunding(execution, fee)
                }
            }
        } else if (execution.state == "first_funded" && execution.localRole == "maker") {
            // A counterparty can expire before observing the first-chain lock.
            // Native policy authorizes only this maker's exact refund and
            // enforces the signed timeout, so keep recovery reachable even
            // though the execution never advanced to both-funded.
            val bitcoin = execution.firstChain == "bitcoin"
            builder.setPositiveButton(
                if (bitcoin) R.string.wallet_swap_refund_bitcoin
                else R.string.wallet_swap_refund_hns,
            ) { _, _ ->
                showSwapSettlementFeeForm(execution, "refund", bitcoin)
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
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_settlement_actions)
            .setItems(actions) { _, index ->
                val bitcoin = index == 1 || index == 3
                val action = if (index < 2) "redeem" else "refund"
                showSwapSettlementFeeForm(execution, action, bitcoin)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSwapSettlementFeeForm(
        execution: NativeShakescapeExecutionSummary,
        action: String,
        bitcoin: Boolean,
    ) {
        showWalletActionForm(
            R.string.wallet_swap_settlement_fee_title,
            listOf(WalletActionInput(
                if (bitcoin) R.string.wallet_swap_settlement_btc_fee_hint
                else R.string.wallet_swap_settlement_hns_fee_hint,
                numeric = true,
                initial = if (bitcoin) "" else DEFAULT_HNS_MAXIMUM_FEE_BASE_UNITS,
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

    private fun prepareSwapSettlement(
        execution: NativeShakescapeExecutionSummary,
        action: String,
        bitcoin: Boolean,
        maximumFee: Long,
    ) {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = {
                prepareSwapSettlementAfterAuthentication(
                    execution, action, bitcoin, maximumFee,
                )
            },
        )
    }

    private fun prepareSwapSettlementAfterAuthentication(
        execution: NativeShakescapeExecutionSummary,
        action: String,
        bitcoin: Boolean,
        maximumFee: Long,
    ) {
        val lease = currentStorageLease() ?: return
        val handle = walletHandle
        if (!beginOperation(
                lease,
                getString(R.string.wallet_swap_settlement_preparing),
                resetReads = false,
            )
        ) return
        val operationSerial = walletOperationSerial
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
                    finishWalletOperationIfOwned(operationSerial)
                    releaseStorageLeaseAfterOperation(lease)
                } else if (approval == null) {
                    finishWalletOperationIfOwned(operationSerial)
                    bitcoinStatusView.text = getString(R.string.wallet_swap_settlement_prepare_failed)
                    releaseStorageLeaseAfterOperation(lease)
                } else {
                    showSwapSettlementApproval(
                        approval, bitcoin, lease, handle, epoch, operationSerial,
                    )
                }
            }
        }
    }

    private fun showSwapSettlementApproval(
        approval: NativeSwapSettlementApproval,
        bitcoin: Boolean,
        lease: WalletStorageOwnershipGate.Lease,
        handle: Long,
        epoch: Long,
        operationSerial: Long,
    ) {
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "denuo-swap-settlement-reject") {
                NativeWalletBridge.rejectSwapSettlement(handle, approval.actionToken, bitcoin)
                runOnUiThread {
                    finishWalletOperationIfOwned(operationSerial)
                    releaseStorageLeaseAfterOperation(lease)
                }
            }
        }
        val unit = if (bitcoin) "sats" else "dollarydoos"
        walletAlertDialogBuilder()
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
                        handle, approval.actionToken, bitcoin,
                    )
                    runOnUiThread {
                        val current = operationIsCurrent(epoch, lease)
                        finishWalletOperationIfOwned(operationSerial)
                        if (current) {
                            refreshControllerState(resetReads = false)
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
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = { prepareBtcForHnsFundingAfterAuthentication(execution, maximumFeeSats) },
        )
    }

    private fun prepareBtcForHnsFundingAfterAuthentication(
        execution: NativeShakescapeExecutionSummary,
        maximumFeeSats: Long,
    ) {
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
                    statusView.text = getString(R.string.wallet_swap_funding_prepare_failed)
                    bitcoinStatusView.text = getString(R.string.wallet_swap_funding_prepare_failed)
                    releaseStorageLeaseAfterOperation(lease)
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
        walletAlertDialogBuilder()
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
                            val resultStatus = if (receipt == null) {
                                getString(R.string.wallet_swap_funding_broadcast_failed)
                            } else {
                                getString(R.string.wallet_swap_funding_submitted, receipt.txid.take(12))
                            }
                            statusView.text = resultStatus
                            bitcoinStatusView.text = resultStatus
                            renderWalletDashboard()
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
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = {
                prepareHnsForBtcFundingAfterAuthentication(execution, maximumFeeDollarydoos)
            },
        )
    }

    private fun prepareHnsForBtcFundingAfterAuthentication(
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
                    val failure = getString(R.string.wallet_swap_hns_funding_prepare_failed)
                    statusView.text = failure
                    bitcoinStatusView.text = failure
                    renderWalletDashboard()
                    releaseStorageLeaseAfterOperation(lease)
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
        walletAlertDialogBuilder()
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
                            val resultStatus = if (receipt == null) {
                                getString(R.string.wallet_swap_hns_funding_broadcast_failed)
                            } else {
                                getString(R.string.wallet_swap_hns_funding_submitted, receipt.transactionId.take(12))
                            }
                            statusView.text = resultStatus
                            bitcoinStatusView.text = resultStatus
                            renderWalletDashboard()
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
        walletAlertDialogBuilder()
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

    private fun confirmCancelDirectOffer(offer: NativeDirectOfferSummary) {
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_cancel_title)
            .setMessage(getString(
                R.string.wallet_swap_cancel_direct_message,
                directOfferLabel(offer),
                offer.offerId,
            ))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.wallet_swap_cancel_offer) { _, _ ->
                val handle = walletHandle
                thread(name = "direct-offer-cancel") {
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

    private fun prepareHnsForBtcOffer(
        hnsAmountDollarydoos: Long,
        btcAmountSats: Long,
        hnsFeeReserveDollarydoos: Long,
        listingLifetimeSeconds: Long,
    ) {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = {
                val lease = currentStorageLease() ?: return@requireWalletAuthentication
                val handle = walletHandle
                if (!beginOperation(lease, getString(R.string.wallet_swap_preparing), resetReads = false)) return@requireWalletAuthentication
                val epoch = lifecycleEpoch
                thread(name = "hns-btc-offer-prepare") {
                    val approval = NativeWalletBridge.prepareHnsForBtcOffer(
                        handle, hnsAmountDollarydoos, btcAmountSats,
                        hnsFeeReserveDollarydoos, listingLifetimeSeconds,
                    )
                    runOnUiThread {
                        if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                            approval?.let {
                                NativeWalletBridge.rejectHnsForBtcOffer(handle, it.actionToken)
                                it.close()
                            }
                            releaseStorageLeaseAfterOperation(lease)
                        } else if (approval == null) {
                            busy = false
                            refreshControllerState(resetReads = false)
                            bitcoinStatusView.text = getString(R.string.wallet_swap_hns_prepare_failed)
                            statusView.text = bitcoinStatusView.text
                            releaseStorageLeaseAfterOperation(lease)
                        } else {
                            showHnsForBtcOfferApproval(approval, lease, epoch)
                        }
                    }
                }
            },
        )
    }

    private fun showHnsForBtcOfferApproval(
        approval: NativeHnsForBtcOfferApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        val handle = walletHandle
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "hns-btc-offer-reject") {
                NativeWalletBridge.rejectHnsForBtcOffer(handle, approval.actionToken)
                runOnUiThread {
                    if (operationIsCurrent(epoch, lease) && walletHandle == handle) {
                        busy = false
                        statusView.text = getString(R.string.wallet_status_unlocked)
                        refreshControllerState(resetReads = false)
                    }
                    releaseStorageLeaseAfterOperation(lease)
                }
            }
        }
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_hns_approval_title)
            .setMessage(getString(
                R.string.wallet_swap_hns_approval_message,
                formatHnsBaseUnits(approval.hnsAmountDollarydoos.toString()),
                approval.btcAmountSats,
                formatHnsBaseUnits(approval.hnsFeeReserveDollarydoos.toString()),
                formatHnsBaseUnits(approval.totalHnsCommitmentDollarydoos.toString()),
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_publish) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                statusView.text = getString(R.string.wallet_swap_publishing)
                thread(name = "hns-btc-offer-publish") {
                    val published = NativeWalletBridge.approveHnsForBtcOffer(
                        handle, approval.actionToken,
                    )
                    runOnUiThread {
                        if (operationIsCurrent(epoch, lease) && walletHandle == handle) {
                            busy = false
                            refreshControllerState(resetReads = false)
                            bitcoinStatusView.text = if (published == null) {
                                getString(R.string.wallet_swap_publish_failed)
                            } else {
                                getString(R.string.wallet_swap_hns_published, published.offerId.take(12))
                            }
                            statusView.text = bitcoinStatusView.text
                        }
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareDirectOfferTake(offer: NativeDirectOfferSummary, feeReserve: Long) {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = {
                val lease = currentStorageLease() ?: return@requireWalletAuthentication
                val handle = walletHandle
                if (!beginOperation(lease, getString(R.string.wallet_swap_take_preparing), resetReads = false)) return@requireWalletAuthentication
                val epoch = lifecycleEpoch
                thread(name = "direct-offer-take-prepare") {
                    val preparation = NativeWalletBridge.prepareDirectOfferTake(
                        handle, offer.offerId, feeReserve,
                    )
                    runOnUiThread {
                        if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                            (preparation as? NativeDirectOfferTakePreparation.Approval)?.value?.let {
                                NativeWalletBridge.rejectDirectOfferTake(handle, it.actionToken)
                                it.close()
                            }
                            releaseStorageLeaseAfterOperation(lease)
                        } else if (preparation is NativeDirectOfferTakePreparation.InsufficientFunds) {
                            busy = false
                            val message = directOfferTakeInsufficientFundsMessage(
                                offer,
                                feeReserve,
                                preparation,
                            )
                            bitcoinStatusView.text = message
                            statusView.text = getString(R.string.wallet_status_unlocked)
                            renderWalletDashboard()
                            walletAlertDialogBuilder()
                                .setTitle(R.string.wallet_swap_take_offer)
                                .setMessage(message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            releaseStorageLeaseAfterOperation(lease)
                        } else {
                            val approval =
                                (preparation as? NativeDirectOfferTakePreparation.Approval)?.value
                            if (approval == null || approval.offer.offerId != offer.offerId) {
                                approval?.let {
                                    NativeWalletBridge.rejectDirectOfferTake(handle, it.actionToken)
                                    it.close()
                                }
                                busy = false
                                bitcoinStatusView.text = getString(R.string.wallet_swap_take_prepare_failed)
                                statusView.text = getString(R.string.wallet_status_unlocked)
                                renderWalletDashboard()
                                releaseStorageLeaseAfterOperation(lease)
                            } else {
                                showDirectOfferTakeApproval(approval, lease, epoch)
                            }
                        }
                    }
                }
            },
        )
    }

    private fun directOfferTakeInsufficientFundsMessage(
        offer: NativeDirectOfferSummary,
        feeReserve: Long,
        failure: NativeDirectOfferTakePreparation.InsufficientFunds,
    ): String {
        if (failure.receivedAsset != offer.receivedAsset) {
            return getString(R.string.wallet_swap_take_prepare_failed)
        }
        val required = directOfferTakeRequiredFunding(offer.receivedAmount, feeReserve)
            ?: return getString(R.string.wallet_swap_take_prepare_failed)
        return if (failure.receivedAsset == "btc") {
            if (required > failure.confirmedAmount) {
                getString(
                    R.string.wallet_swap_take_insufficient_btc,
                    offer.receivedAmount,
                    feeReserve,
                    required,
                    failure.confirmedAmount,
                )
            } else {
                getString(
                    R.string.wallet_swap_take_reserved_btc,
                    required,
                    failure.confirmedAmount,
                )
            }
        } else if (required > failure.confirmedAmount) {
            getString(
                R.string.wallet_swap_take_insufficient_hns,
                formatHnsBaseUnits(offer.receivedAmount.toString()),
                formatHnsBaseUnits(feeReserve.toString()),
                formatHnsBaseUnits(required.toString()),
                formatHnsBaseUnits(failure.confirmedAmount.toString()),
            )
        } else {
            getString(
                R.string.wallet_swap_take_reserved_hns,
                formatHnsBaseUnits(required.toString()),
                formatHnsBaseUnits(failure.confirmedAmount.toString()),
            )
        }
    }

    private fun showDirectOfferTakeApproval(
        approval: NativeDirectOfferTakeApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
    ) {
        val handle = walletHandle
        var settled = false
        fun reject() {
            if (settled) return
            settled = true
            thread(name = "direct-offer-take-reject") {
                NativeWalletBridge.rejectDirectOfferTake(handle, approval.actionToken)
                runOnUiThread {
                    if (operationIsCurrent(epoch, lease) && walletHandle == handle) {
                        busy = false
                        refreshControllerState(resetReads = false)
                    }
                    releaseStorageLeaseAfterOperation(lease)
                }
            }
        }
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_swap_take_approval_title)
            .setMessage(getString(
                R.string.wallet_swap_take_approval_message,
                directOfferLabel(approval.offer),
                approval.offer.receivedAmount,
                approval.offer.receivedAsset.uppercase(),
                approval.receivedFeeReserve,
                approval.totalReceivedAssetCommitment,
            ))
            .setNegativeButton(R.string.action_reject) { _, _ -> reject() }
            .setPositiveButton(R.string.wallet_swap_take_confirm) { _, _ ->
                if (settled) return@setPositiveButton
                settled = true
                thread(name = "direct-offer-take-approve") {
                    val accepted = NativeWalletBridge.approveDirectOfferTake(
                        handle, approval.actionToken,
                    )
                    runOnUiThread {
                        if (operationIsCurrent(epoch, lease) && walletHandle == handle) {
                            busy = false
                            refreshControllerState(resetReads = false)
                            bitcoinStatusView.text = if (accepted == null) {
                                getString(R.string.wallet_swap_take_failed)
                            } else {
                                getString(R.string.wallet_swap_take_sent, accepted.sessionId.take(12))
                            }
                        }
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareBtcForHnsOffer(
        btcAmountSats: Long,
        hnsAmountDollarydoos: Long,
        bitcoinFeeReserveSats: Long,
        listingLifetimeSeconds: Long,
    ) {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = {
                prepareBtcForHnsOfferAfterAuthentication(
                    btcAmountSats,
                    hnsAmountDollarydoos,
                    bitcoinFeeReserveSats,
                    listingLifetimeSeconds,
                )
            },
        )
    }

    private fun prepareBtcForHnsOfferAfterAuthentication(
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
                    refreshControllerState(resetReads = false)
                    bitcoinStatusView.text = getString(R.string.wallet_swap_prepare_failed)
                    statusView.text = bitcoinStatusView.text
                    releaseStorageLeaseAfterOperation(lease)
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
        val handle = walletHandle
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
                NativeWalletBridge.rejectBtcForHnsOffer(handle, approval.actionToken)
                runOnUiThread {
                    if (operationIsCurrent(epoch, lease) && walletHandle == handle) {
                        busy = false
                        statusView.text = getString(R.string.wallet_status_unlocked)
                        refreshControllerState(resetReads = false)
                    }
                    releaseStorageLeaseAfterOperation(lease)
                }
            }
        }
        walletAlertDialogBuilder()
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
                statusView.text = getString(R.string.wallet_swap_publishing)
                bitcoinStatusView.text = getString(R.string.wallet_swap_publishing)
                thread(name = "bitcoin-hns-offer-publish") {
                    val published = NativeWalletBridge.approveBtcForHnsOffer(
                        handle,
                        approval.actionToken,
                    )
                    runOnUiThread {
                        if (!operationIsCurrent(epoch, lease) || walletHandle != handle) {
                            releaseStorageLeaseAfterOperation(lease)
                            return@runOnUiThread
                        }
                        busy = false
                        refreshControllerState(resetReads = false)
                        bitcoinStatusView.text = if (published == null) {
                            getString(R.string.wallet_swap_publish_failed)
                        } else {
                            getString(R.string.wallet_swap_published, published.offerId.take(12))
                        }
                        statusView.text = bitcoinStatusView.text
                        releaseStorageLeaseAfterOperation(lease)
                    }
                }
            }
            .setOnCancelListener { reject() }
            .show()
    }

    private fun prepareBitcoinSend(destination: String, amountSats: Long, maximumFeeSats: Long) {
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = { prepareBitcoinSendAfterAuthentication(destination, amountSats, maximumFeeSats) },
        )
    }

    private fun prepareBitcoinSendAfterAuthentication(
        destination: String,
        amountSats: Long,
        maximumFeeSats: Long,
    ) {
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
        walletAlertDialogBuilder()
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
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
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
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
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

    private fun showSetNameRecordsForm() = showWalletActionForm(
        R.string.row_wallet_set_records,
        listOf(
            WalletActionInput(R.string.wallet_action_name_hint),
            WalletActionInput(
                R.string.wallet_action_resource_records_hint,
                multiline = true,
                maxCharacters = MAX_RESOURCE_EDITOR_CHARACTERS,
            ),
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[2])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(
            NativeHnsValueIntent.SetNameRecords(values[0], values[1], fee),
        )
    }

    private fun showCreateOfferForm() = showWalletActionForm(
        R.string.row_wallet_create_offer,
        listOf(
            WalletActionInput(R.string.wallet_action_name_hint),
            WalletActionInput(R.string.wallet_action_price_hint, numeric = true),
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
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

    private fun showAcceptOfferForm(selectedOffer: NativeShakedexNameOffer) = showWalletActionForm(
        R.string.row_wallet_accept_offer,
        listOf(
            WalletActionInput(
                R.string.wallet_action_listing_hint,
                initial = selectedOffer.listingId,
                readOnly = true,
            ),
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
        ),
    ) { values ->
        val fee = parsePositiveHnsToBaseUnits(values[1])
        if (fee == null) return@showWalletActionForm invalidValueActionInput()
        prepareWalletValueAction(NativeHnsValueIntent.AcceptOffer(values[0], fee, fee))
    }

    private fun showRecoverNameForm() = showWalletActionForm(
        R.string.row_wallet_recover_name,
        listOf(
            WalletActionInput(R.string.wallet_action_seller_session_hint),
            WalletActionInput(
                R.string.wallet_action_maximum_fee_hint,
                numeric = true,
                initial = DEFAULT_HNS_MAXIMUM_FEE,
            ),
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

    private fun showPairDirectShakescapeForm() {
        if (busy) {
            showWalletBusyFeedback()
            return
        }
        val endpointInput = EditText(this).apply {
            hint = getString(R.string.wallet_direct_shakescape_endpoint_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(MAX_DIRECT_SHAKESCAPE_ENDPOINT_CHARACTERS))
            setSingleLine(true)
            setTextColor(themeColors().primaryText)
            setHintTextColor(themeColors().secondaryText)
        }
        val recent = recentDirectShakescapePeers.take(MAX_VISIBLE_DIRECT_SHAKESCAPE_PEERS)
        val discovered = directShakescapeTransportStatus?.discoveredPeers
            .orEmpty()
            .filterNot(recent::contains)
            .take(MAX_VISIBLE_DIRECT_SHAKESCAPE_PEERS)
        showWalletFormDialog(
            title = getString(R.string.row_wallet_pair_direct_shakescape),
            fields = listOf(
                getString(R.string.wallet_direct_shakescape_endpoint_hint) to endpointInput,
                getString(R.string.wallet_direct_shakescape_recent_peers) to
                    directShakescapePeerChoices(
                        recent,
                        getString(R.string.wallet_direct_shakescape_no_recent_peers),
                        endpointInput,
                    ),
                getString(R.string.wallet_direct_shakescape_discovered_peers) to
                    directShakescapePeerChoices(
                        discovered,
                        getString(R.string.wallet_direct_shakescape_no_discovered_peers),
                        endpointInput,
                    ),
            ),
            primaryLabel = getString(R.string.action_prepare),
            onPrimary = { dialog ->
                val endpoint = endpointInput.text?.toString().orEmpty()
                wipeEditable(endpointInput.text)
                dialog.dismiss()
                connectWalletOwnedDirectShakescape(endpoint)
            },
            onDismiss = { wipeEditable(endpointInput.text) },
        )
    }

    private fun directShakescapePeerChoices(
        peers: List<String>,
        emptyMessage: String,
        endpointInput: EditText,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (peers.isEmpty()) {
            addView(TextView(this@WalletActivity).apply {
                text = emptyMessage
                textSize = 14f
                setTextColor(themeColors().secondaryText)
            })
        } else {
            peers.forEachIndexed { index, endpoint ->
                addView(
                    dashboardActionButton(endpoint, secondary = true) {
                        endpointInput.setText(endpoint)
                        endpointInput.setSelection(endpoint.length)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index + 1 < peers.size) bottomMargin = uiDp(8)
                    },
                )
            }
        }
    }

    private fun rememberDirectShakescapePeer(endpoint: String) {
        recentDirectShakescapePeers.remove(endpoint)
        recentDirectShakescapePeers.addFirst(endpoint)
        while (recentDirectShakescapePeers.size > MAX_VISIBLE_DIRECT_SHAKESCAPE_PEERS) {
            recentDirectShakescapePeers.removeLast()
        }
    }

    private fun connectWalletOwnedDirectShakescape(endpoint: String) {
        val normalizedEndpoint = endpoint.trim()
        val (lease, handle) = directShakescapeContext() ?: run {
            val message = getString(R.string.wallet_direct_shakescape_unavailable)
            directShakescapeStatusView.text = message
            shakedexQueryStatusView.text = message
            Log.w(
                TAG,
                "Direct Shakescape pairing stopped before native connection because the " +
                    "wallet operation context was unavailable",
            )
            showShakedexDashboard()
            return
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_connecting_direct_shakescape),
                resetReads = false,
            )
        ) {
            Log.w(
                TAG,
                "Direct Shakescape pairing stopped at the operation gate: " +
                    "busy=$busy hnsSync=${hasActiveWalletHnsSynchronization()}",
            )
            showShakedexDashboard()
            return
        }
        shakedexQueryStatusView.text = getString(R.string.wallet_direct_shakescape_connecting)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-direct-shakescape-connect") {
            Log.i(TAG, "Starting native direct Shakescape pairing with $normalizedEndpoint")
            val result = NativeWalletBridge.connectWalletOwnedDirectShakescape(
                handle,
                normalizedEndpoint,
            )
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
                result?.peerEndpoint?.let(::rememberDirectShakescapePeer)
                val outcome = result?.outcome?.name ?: "BridgeFailed"
                if (result?.peerEndpoint != null) {
                    Log.i(
                        TAG,
                        "Native direct Shakescape pairing completed: " +
                            "outcome=$outcome peer=${result.peerEndpoint}",
                    )
                } else {
                    Log.w(TAG, "Native direct Shakescape pairing completed: outcome=$outcome")
                }
                showShakedexDashboard()
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
        val status = freshValueActionStatus(handle)
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && snapshot != null && status != null && !status.locked &&
                status.hnsValueEnabled && (!requiresShakedex || status.shakedexEnabled) &&
                latestReadSnapshotHandle == handle &&
                latestReadSnapshotAuthorityGeneration == walletAuthorityGeneration &&
                latestReadSnapshotEpoch == lifecycleEpoch &&
                unconfirmedDatabaseKey == null
        }
    }

    /**
     * A direct peer/service tick deliberately makes native status non-blocking,
     * so one `try_lock` miss is not evidence that an authenticated value wallet
     * disappeared. Retry that read for one short bounded UI interval. The
     * eventual native preparation remains the signing/ownership authority.
     */
    private fun freshValueActionStatus(handle: Long): NativeWalletStatus? {
        return awaitWalletValueActionStatus(
            attempts = 5,
            readStatus = { NativeWalletBridge.status(handle) },
            waitBeforeRetry = { Thread.sleep(10) },
        )
    }

    /**
     * Capture only Android-owned controller identity before starting an exact
     * value operation. Native `status()` is a non-blocking `try_lock`; asking
     * it on the UI thread immediately after the device-credential Activity
     * returns used to turn harmless direct-peer contention into a false
     * "synchronize first" rejection. Once [beginOperation] owns `busy`, the
     * direct worker yields and the blocking native preparation accessor waits
     * for any already-running tick without freezing the UI.
     *
     * A read snapshot is deliberately not required here. The preparation path
     * already performs a fresh authenticated synchronization whenever its
     * existing snapshot cannot safely be reused.
     */
    private fun valueActionPreparationContext(): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        return (lease to handle).takeIf {
            handle != INVALID_HANDLE && unconfirmedDatabaseKey == null
        }
    }

    private fun directShakescapeContext(): Pair<WalletStorageOwnershipGate.Lease, Long>? {
        val lease = currentStorageLease() ?: return null
        val handle = walletHandle
        return (lease to handle).takeIf {
            walletDirectShakescapeOperationMayBegin(
                hasCurrentLease = true,
                hasController = handle != INVALID_HANDLE,
                hasUnconfirmedKey = unconfirmedDatabaseKey != null,
            )
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
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = { prepareWalletValueActionAfterAuthentication(intent) },
        )
    }

    private fun prepareWalletValueActionAfterAuthentication(intent: NativeHnsValueIntent) {
        val (lease, handle) = valueActionPreparationContext() ?: run {
            valueActionStatusView.text = getString(R.string.wallet_value_actions_requires_sync)
            Log.w(TAG, "Value action stopped before native preparation because the wallet controller was unavailable")
            showValueActionUnavailable()
            return
        }
        val reusableSnapshot = latestReadSnapshot?.takeIf { snapshot ->
            walletValueActionMayReuseVerifiedSnapshot(
                hasCurrentAuthority = hasCurrentWalletReadSnapshot(handle),
                snapshotObservedAtElapsedMillis = latestReadSnapshotObservedAtElapsedMillis,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
                snapshotHeight = snapshot.height,
                latestObservedHeaderHeight = latestObservedBrowserHeaderHeight,
            )
        }
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_value_action),
                resetReads = false,
            )
        ) return
        valueActionStatusView.text = getString(
            if (reusableSnapshot != null) {
                R.string.wallet_value_actions_preparing_from_recent_snapshot
            } else {
                R.string.wallet_value_actions_syncing
            },
        )
        Log.i(
            TAG,
            if (reusableSnapshot != null) {
                "HNS value review is reusing its recent verified wallet snapshot"
            } else {
                "HNS value review needs a fresh verified wallet snapshot"
            },
        )
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        val expectedKind = when (intent) {
            is NativeHnsValueIntent.TransferName -> NativeHnsValueApprovalKind.NAME_TRANSFER
            is NativeHnsValueIntent.FinalizeName -> NativeHnsValueApprovalKind.NAME_FINALIZE
            is NativeHnsValueIntent.SetNameRecords -> NativeHnsValueApprovalKind.NAME_UPDATE
            is NativeHnsValueIntent.CreateFixedPriceOffer,
            is NativeHnsValueIntent.CancelOffer,
            is NativeHnsValueIntent.RecoverName -> NativeHnsValueApprovalKind.NAME_MARKET_OFFER
            is NativeHnsValueIntent.AcceptOffer,
            is NativeHnsValueIntent.FinalizePurchase ->
                NativeHnsValueApprovalKind.NAME_MARKET_PURCHASE
        }
        thread(name = "hns-wallet-value-prepare") {
            // Effectful JNI entry points use the blocking controller accessor
            // and run only on this worker thread. `busy` prevents every new
            // direct-peer tick, so an already-running tick drains and hands
            // the controller to synchronization/preparation without a polling
            // race. Native preparation remains the exact lock, capability,
            // ownership, and transaction authority.
            val synchronization = if (reusableSnapshot == null) {
                synchronizeHnsReadsWithRollbackFloor(handle)
            } else {
                null
            }
            val snapshot = reusableSnapshot ?: synchronization?.snapshot
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
                        if (reusableSnapshot == null) renderReadSnapshot(snapshot)
                        refreshControllerState(resetReads = false)
                        valueActionStatusView.text =
                            getString(R.string.wallet_value_actions_prepare_failed)
                        showValuePreparationFailure()
                    }
                } else {
                    if (reusableSnapshot == null) snapshot?.let(::renderReadSnapshot)
                    showValueApproval(exact, lease, epoch, authorityGeneration)
                }
            }
        }
    }

    private fun showValuePreparationFailure() {
        dismissValueApproval(rejectNative = false)
        val dialog = walletAlertDialogBuilder()
            .setTitle(R.string.wallet_auth_transaction_title)
            .setMessage(R.string.wallet_value_actions_prepare_failed)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        valueApprovalDialog = dialog
        dialog.setOnDismissListener {
            if (valueApprovalDialog === dialog) valueApprovalDialog = null
        }
        dialog.show()
    }

    private fun showValueActionUnavailable() {
        dismissValueApproval(rejectNative = false)
        val dialog = walletAlertDialogBuilder()
            .setTitle(R.string.wallet_auth_transaction_title)
            .setMessage(R.string.wallet_value_actions_requires_sync)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        valueApprovalDialog = dialog
        dialog.setOnDismissListener {
            if (valueApprovalDialog === dialog) valueApprovalDialog = null
        }
        dialog.show()
    }

    private fun showValueApproval(
        approval: NativeHnsValueApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingValueApproval !== approval) {
            dismissValueApproval(rejectNative = true)
            pendingValueApproval = approval
        }
        val expires = runCatching {
            DateFormat.getDateTimeInstance().format(
                Date(Math.multiplyExact(approval.expiresAtUnix, 1_000L)),
            )
        }.getOrElse { approval.expiresAtUnix.toString() }
        val message = (approval.detailLines + getString(
            R.string.wallet_value_actions_expires,
            expires,
        )).joinToString("\n\n")
        val dialog = walletAlertDialogBuilder()
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
        trackedShakedexFinalizeApprovalTransactionId = null
        valueActionStatusView.text = getString(R.string.wallet_value_actions_executing)
        val handle = walletHandle
        val pendingRefreshFloor = latestReadSnapshot?.height
        val pendingRecoveryAccountId = NativeWalletBridge.account(handle)?.accountId
        thread(name = "hns-wallet-value-execute") {
            val result = NativeWalletBridge.approveHnsValueActionResult(
                handle,
                approval.actionToken,
            )
            approval.close()
            if (result != null) {
                // The peer-submission result is already authoritative for
                // whether native execution began. Persist recovery before any
                // follow-up network read, then show that receipt immediately;
                // the potentially slow mempool/history refresh must not hide
                // a successful broadcast behind a generic busy indicator.
                persistPendingOutgoingRecovery(pendingRecoveryAccountId, pendingRefreshFloor)
                runOnUiThread {
                    val mayPresent = walletReadMayPublish(
                        expectedEpoch = epoch,
                        currentEpoch = lifecycleEpoch,
                        foreground = foreground,
                        ownsCurrentLease = currentStorageLease() === lease,
                        expectedHandle = handle,
                        currentHandle = walletHandle,
                        expectedAuthorityGeneration = authorityGeneration,
                        currentAuthorityGeneration = walletAuthorityGeneration,
                    ) && operationIsCurrent(epoch, lease)
                    if (mayPresent) {
                        valueActionStatusView.text =
                            getString(R.string.wallet_value_actions_submitted_reconciling)
                        showSubmittedValueActionResult(result.displayJson)
                    }
                }
            }
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

    private fun showSubmittedValueActionResult(displayJson: String) {
        walletAlertDialogBuilder()
            .setTitle(R.string.wallet_value_actions_result_title)
            .setMessage(getString(R.string.wallet_value_actions_result, displayJson))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun rejectPreparedValueAction(
        approval: NativeHnsValueApproval,
        lease: WalletStorageOwnershipGate.Lease,
        epoch: Long,
        authorityGeneration: Long,
    ) {
        if (pendingValueApproval !== approval) return
        pendingValueApproval = null
        trackedShakedexFinalizeApprovalTransactionId?.let(
            trackedShakedexFinalizePromptAttempts::remove,
        )
        trackedShakedexFinalizeApprovalTransactionId = null
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
        if (rejectNative) {
            trackedShakedexFinalizeApprovalTransactionId?.let(
                trackedShakedexFinalizePromptAttempts::remove,
            )
        }
        trackedShakedexFinalizeApprovalTransactionId = null
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
            val message = getString(R.string.wallet_shakedex_queries_requires_sync)
            shakedexQueryStatusView.text = message
            showShakedexQueryResult(query, result = null, message = message)
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
                val offerPage = if (query is NativeShakedexQuery.ListOffers) {
                    result?.offerPage()
                } else {
                    null
                }
                val message = if (result == null) {
                    getString(R.string.wallet_shakedex_queries_failed)
                } else if (query is NativeShakedexQuery.ListOffers && offerPage == null) {
                    getString(R.string.wallet_shakedex_offer_page_invalid)
                } else if (offerPage != null) {
                    getString(
                        R.string.wallet_shakedex_offer_page_summary,
                        offerPage.offers.size,
                        offerPage.boardRevision,
                    )
                } else {
                    getString(R.string.wallet_shakedex_queries_result, result.displayJson)
                }
                shakedexQueryStatusView.text = message
                Log.i(
                    TAG,
                    "Authenticated Shakedex ${shakedexQueryKind(query)} completed: " +
                        "success=${result != null}",
                )
                showShakedexQueryResult(query, result, message)
            }
        }
    }

    /**
     * The ShakeDex dashboard copies its status strings into a modal snapshot.
     * An asynchronous board query must therefore present its own result above
     * the retained dashboard; updating only the backing card leaves the user
     * looking at an unrelated, stale Atomic Swap Progress row.
     */
    private fun showShakedexQueryResult(
        query: NativeShakedexQuery,
        result: NativeShakedexQueryResult?,
        message: String,
    ) {
        if (query is NativeShakedexQuery.ListOffers) {
            result?.offerPage()?.let {
                showShakedexOfferPicker(it.boardRevision, it.offers, it.nextCursor)
                return
            }
        }
        val title = when (query) {
            is NativeShakedexQuery.ListOffers -> R.string.row_wallet_list_offers
            is NativeShakedexQuery.GetSession -> R.string.row_wallet_get_session
        }
        walletDetailDialog(
            title = getString(title),
            rows = listOf(getString(R.string.wallet_modal_details) to message),
        )
    }

    private fun showShakedexOfferPicker(
        boardRevision: Long,
        offers: List<NativeShakedexNameOffer>,
        nextCursor: String?,
    ) {
        val summary = if (offers.isEmpty()) {
            getString(R.string.wallet_shakedex_offer_page_empty, boardRevision)
        } else {
            getString(R.string.wallet_shakedex_offer_page_select, offers.size, boardRevision)
        }
        val actions = offers.map { offer ->
            val expiry = runCatching {
                DateFormat.getDateTimeInstance().format(
                    Date(Math.multiplyExact(offer.expiresAtUnix, 1_000L)),
                )
            }.getOrElse { offer.expiresAtUnix.toString() }
            WalletModalAction(
                label = getString(
                    R.string.wallet_shakedex_offer_choice,
                    offer.name,
                    formatHnsBaseUnits(offer.priceBaseUnits),
                    formatHnsBaseUnits(offer.marketplaceFeeBaseUnits),
                    expiry,
                ),
                action = { showAcceptOfferForm(offer) },
            )
        }
        walletDetailDialog(
            title = getString(R.string.row_wallet_list_offers),
            rows = listOf(getString(R.string.wallet_modal_details) to summary),
            actionSections = buildList {
                if (actions.isNotEmpty()) {
                    add(WalletModalActionSection(
                        getString(R.string.wallet_shakedex_available_name_offers),
                        actions,
                    ))
                }
                if (nextCursor != null) {
                    add(WalletModalActionSection(
                        getString(R.string.wallet_shakedex_more_offers),
                        listOf(WalletModalAction(
                            getString(R.string.wallet_shakedex_next_offer_page),
                            action = {
                                queryWalletShakedex(
                                    NativeShakedexQuery.ListOffers(nextCursor, DEFAULT_OFFER_PAGE_SIZE),
                                )
                            },
                        )),
                    ))
                }
            },
        )
    }

    private fun shakedexQueryKind(query: NativeShakedexQuery): String = when (query) {
        is NativeShakedexQuery.ListOffers -> "offer-list query"
        is NativeShakedexQuery.GetSession -> "session query"
    }

    private fun prepareWalletSend(request: WalletHnsSendInput?) {
        val validRequest = request ?: run {
            Log.w(TAG, "HNS send review was rejected locally: invalid form input")
            sendStatusView.text = getString(R.string.wallet_send_invalid)
            return
        }
        requireWalletAuthentication(
            getString(R.string.wallet_auth_transaction_title),
            getString(R.string.wallet_auth_transaction_message),
            action = { prepareWalletSendAfterAuthentication(validRequest) },
        )
    }

    private fun prepareWalletSendAfterAuthentication(validRequest: WalletHnsSendInput) {
        val lease = currentStorageLease() ?: run {
            Log.w(TAG, "HNS send review was not started: no current storage lease")
            return
        }
        val handle = walletHandle
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
        val dialog = walletAlertDialogBuilder()
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
        val canonical = canonicalHandshakeNameImportText(name)
        if (canonical == null) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_invalid)
            return
        }
        importWalletNames(listOf(canonical))
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
            names.isEmpty() || names.size > MAX_MULTIPLE_WALLET_NAME_IMPORTS ||
            names.toSet().size != names.size ||
            names.any { !isCanonicalHandshakeNameText(it) }
        ) {
            nameImportStatusView.text = getString(R.string.wallet_name_import_invalid)
            return
        }
        walletNameImportInProgressCount = names.size
        if (
            !walletNameImportMayBegin(expected, walletNameImportState(lease)) ||
            !beginOperation(
                lease,
                getString(R.string.wallet_status_importing_name),
                resetReads = false,
            )
        ) {
            walletNameImportInProgressCount = 0
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
                walletNameImportInProgressCount = 0
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
                        val message = getString(R.string.wallet_name_import_failed)
                        nameImportStatusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    synchronization?.snapshot != null -> {
                        refreshControllerState(resetReads = false)
                        renderReadSnapshot(synchronization.snapshot)
                        val message =
                            getString(R.string.wallet_name_bulk_import_success, importedCount)
                        nameImportStatusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                    synchronization?.catchup != null -> {
                        refreshControllerState(resetReads = false)
                        renderReadCatchup(synchronization.catchup)
                        val message = getString(
                            R.string.wallet_name_bulk_import_catching_up,
                            importedCount,
                        )
                        nameImportStatusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        scheduleHnsCatchupRetry(
                            lease,
                            handle,
                            epoch,
                            expected.authorityGeneration,
                            synchronization.catchup.headerState,
                        )
                    }
                    else -> {
                        refreshControllerState(resetReads = false)
                        retainReadProjectionAfterRefreshFailure()
                        val message = getString(
                            R.string.wallet_name_bulk_import_refresh_pending,
                            importedCount,
                        )
                        nameImportStatusView.text = message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun refreshControllerState(resetReads: Boolean = true) {
        val status = NativeWalletBridge.status(walletHandle)
        if (status == null) {
            // Native status is a deliberate try-lock. A direct service tick or
            // just-finished value operation can make one read miss without
            // changing wallet authority. Preserve the last proof-bound chain
            // snapshot whenever this Activity still owns a live controller;
            // action preflights independently require a fresh native status.
            if (walletHandle != INVALID_HANDLE && currentStorageLease() != null) {
                renderWalletDashboard()
                return
            }
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
            stopWalletForegroundSyncService()
            dismissWalletPopupsForLock()
            walletHnsJourney.walletLocked()
            clearDirectShakescapeStatusProjection(locked = true)
            localPaymentReceiveTarget = null
            statusView.text = getString(R.string.wallet_status_locked)
            accountView.text = getString(R.string.wallet_account_locked)
            resetReadProjection(R.string.wallet_reads_locked)
            resetBitcoinProjection()
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
                fun configure(segmentPath: String): Boolean {
                    val floor = keyStore.directHnsRollbackFloorForOpen()
                    return keyStore.withDatabaseKey { databaseKey ->
                        NativeWalletBridge.configureWalletOwnedDirectHnsValue(
                            currentAuthority = expectedAuthority,
                            databaseKey = databaseKey,
                            rollbackFloor = floor,
                            headerSegmentPath = segmentPath,
                        )
                    } == true
                }
                // Existing wallets normally open directly from their encrypted,
                // rollback-fenced birthday checkpoint. Export browser headers
                // when native reports that either a pristine restore or the
                // bounded pre-birthday FINALIZE-header migration needs the
                // segment after the pinned block-300,000 anchor.
                if (configure("")) {
                    true
                } else {
                    val birthdayHeight = NativeWalletBridge.birthdayHeight(
                        expectedAuthority.walletHandle,
                    )
                    val segment = if (walletDirectHnsNeedsGenesisBootstrap(walletNetwork)) {
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
                        segment != null && configure(segment.absolutePath)
                    } finally {
                        segment?.delete()
                    }
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
        val openingFloor = NativeWalletBridge.directHnsRollbackFloorForSync(handle)
            ?: return NativeWalletBridge.synchronizeHnsReads(handle)
        openingFloor.fill(0)
        val journalStart = beginDirectHnsSynchronizationWithRecovery(
            begin = keyStore::beginDirectHnsSynchronization,
            recoverInterrupted = {
                val recoveredFloor = NativeWalletBridge.directHnsRollbackFloorForSync(handle)
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
        val updatedFloor = NativeWalletBridge.directHnsRollbackFloorForSync(handle)
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
        } else if (accepted && storageOwner === owner && storageLease == null) {
            statusView.text = getString(R.string.wallet_status_waiting_for_previous_controller)
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
        if (
            hasActiveWalletHnsSynchronization() || walletBitcoinSyncInProgress ||
                hasLiveAtomicSwap()
        ) return
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
        if (walletCredentialTransitionMayRetain(
                authenticationPending = pendingWalletAuthentication != null,
                ownsCurrentLease = ProcessWalletStorageOwnership.isCurrent(lease.owner, lease),
            )
        ) {
            // ConfirmDeviceCredential is a system-owned Activity. Keep this
            // exact controller and lease across that bounded round trip so
            // the authenticated continuation does not return to a newly
            // locked wallet. The ordinary 30-second background retirement is
            // still scheduled and onStart cancels it only when this Activity
            // actually returns.
            return ProcessWalletStorageOwnership.isCurrent(lease.owner, lease)
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
        if (hasLiveAtomicSwap()) {
            // Funding and settlement remain explicitly approval-gated, but
            // the authenticated peer plus read-only chain watches must survive
            // screen off or the two anonymous participants cannot progress.
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
                            hasActiveAtomicSwap = hasLiveAtomicSwap(),
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
        clearDirectShakescapeStatusProjection(locked = false)
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
        val retain = walletOperationRetainsStorageLease(
            sessionActive = walletSessionIsActive(),
            ownsCurrentLease = currentStorageLease() === lease,
            hasController = walletHandle != INVALID_HANDLE,
        )
        if (!retain && leaseReleaseHandoff.operationMayRelease(lease)) {
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
            destroyWalletController(handle, retainPublicHnsSessions = true)
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
        check(walletOperationSerial < Long.MAX_VALUE) {
            "Wallet operation serial exhausted"
        }
        walletOperationSerial += 1L
        busy = true
        statusView.text = status
        if (resetReads) resetReadProjection(R.string.wallet_reads_waiting_for_wallet)
        renderWalletDashboard()
        return true
    }

    private fun finishWalletOperationIfOwned(operationSerial: Long) {
        if (walletOperationCompletionOwnsBusyState(
                busy = busy,
                currentOperationSerial = walletOperationSerial,
                completingOperationSerial = operationSerial,
            )
        ) busy = false
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
        latestReadSnapshotObservedAtElapsedMillis = 0L
        readStatusView.text = getString(status)
        balanceView.text = getString(R.string.wallet_reads_balance_unavailable)
        paymentReceiveView.text = localPaymentReceiveTarget?.let { target ->
            localPaymentReceiveText(target)
        } ?: getString(R.string.wallet_reads_receive_unavailable)
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
        latestReadSnapshotObservedAtElapsedMillis = 0L
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

            NativeWalletHnsCatchupProgress.HeaderState.OutboundPortBlocked -> getString(
                R.string.wallet_reads_outbound_12038_blocked,
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
        val now = SystemClock.elapsedRealtime()
        val displayedStage = displayedLiveHnsSyncStage
        if (
            displayedStage != null && displayedStage != progress.stage &&
                now - displayedLiveHnsSyncStageSinceMillis < MINIMUM_HNS_SYNC_STAGE_VISIBILITY_MILLIS
        ) {
            return
        }
        showReadProjectionSynchronizationPendingIfNeeded()
        if (displayedStage != progress.stage) {
            displayedLiveHnsSyncStage = progress.stage
            displayedLiveHnsSyncStageSinceMillis = now
        }
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

            NativeWalletHnsCatchupProgress.HeaderState.OutboundPortBlocked -> getString(
                R.string.wallet_reads_outbound_12038_blocked,
            )
        }
    }

    private fun renderReadSnapshot(snapshot: NativeWalletReadSnapshot) {
        WalletHnsLiveSyncPresentationCache.clear(walletNetwork.id)
        walletHnsJourney.verifiedSnapshotObserved()
        latestReadSnapshot = snapshot
        loadedTrackedNames = snapshot.trackedNames.take(MAX_VISIBLE_READ_ITEMS)
        trackedNamePageOffset = 0
        selectedTrackedNameIndex = 0
        trackedNameNavigationInFlight = false
        recentActivityPageOffset = 0
        localPaymentReceiveTarget = snapshot.paymentReceiveTarget
        latestReadSnapshotHandle = walletHandle
        latestReadSnapshotAuthorityGeneration = walletAuthorityGeneration
        latestReadSnapshotEpoch = lifecycleEpoch
        latestReadSnapshotObservedAtElapsedMillis = SystemClock.elapsedRealtime()
        if (hasLiveAtomicSwap()) {
            lastAutomaticSwapHnsSyncAtElapsedMillis =
                latestReadSnapshotObservedAtElapsedMillis
        }
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
        val onChainBalanceText = when {
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
        val swapReserved = NativeWalletBridge.reservedHnsForDirectOffers(walletHandle) ?: 0L
        balanceView.textSize = if (balance.hasPendingOutgoing || swapReserved > 0L) 18f else 24f
        balanceView.text = if (swapReserved > 0L) {
            val spendable = balance.spendableBaseUnits.toLongOrNull() ?: 0L
            val available = (spendable - swapReserved).coerceAtLeast(0L)
            val confirmedText = if (balance.hasPendingOutgoing) {
                getString(
                    R.string.wallet_reads_balance_confirmed_with_pending,
                    formatHnsBaseUnits(balance.spendableBaseUnits),
                    formatHnsBaseUnits(balance.pendingOutgoingBaseUnits),
                )
            } else {
                getString(
                    R.string.wallet_reads_balance_confirmed,
                    formatHnsBaseUnits(balance.spendableBaseUnits),
                )
            }
            confirmedText + "\n" + getString(
                R.string.wallet_reads_swap_reserved,
                formatHnsBaseUnits(swapReserved.toString()),
                formatHnsBaseUnits(available.toString()),
            )
        } else {
            onChainBalanceText
        }
        paymentReceiveView.text = getString(
            R.string.wallet_reads_receive,
            snapshot.paymentReceiveTarget.display,
            snapshot.paymentReceiveTarget.derivationIndex,
        )
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
        maybeRefreshActiveSwapAfterNewBlock()
        scheduleTrackedShakedexFinalizeApproval(snapshot)
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
                maybeRefreshActiveSwapAfterNewBlock()
            }
        }
    }

    /**
     * A live cross-chain swap must observe HNS confirmations, redemptions, and
     * refund eligibility without requiring either participant to open the
     * execution dialog. The browser already maintains an authenticated header
     * view; each newly observed height therefore schedules at most one bounded
     * wallet scan while this unlocked foreground wallet has a live session.
     *
     * This does not trust the browser projection as settlement evidence. It
     * only uses the new height as a wake-up signal; native wallet scanning and
     * the execution journal still verify every state transition independently.
     */
    private fun maybeRefreshActiveSwapAfterNewBlock() {
        val status = latestShakescapeExecutionStatus ?: return
        val terminal = setOf("completed", "refunded", "failed")
        val hasLiveSwap = status.pendingAcceptances.isNotEmpty() ||
            status.executions.any { it.state !in terminal }
        if (!hasLiveSwap || pendingOutgoingSnapshotHeight != null) return
        val refreshHeight = walletActiveSwapHnsRefreshHeight(
            snapshotHeight = latestReadSnapshot?.height,
            observedHeaderHeight = latestObservedBrowserHeaderHeight,
            attemptedHeaderHeight = activeSwapHnsRefreshAttemptedHeight,
        ) ?: return
        val handle = walletHandle
        if (
            !foreground || busy || walletHnsSyncInProgress ||
                currentStorageLease() == null || handle == INVALID_HANDLE ||
                NativeWalletBridge.status(handle)?.locked != false ||
                !NativeWalletBridge.hasHnsReads(handle)
        ) return
        activeSwapHnsRefreshAttemptedHeight = refreshHeight
        Log.i(
            TAG,
            "Refreshing active atomic swap after verified Handshake height $refreshHeight",
        )
        synchronizeWalletReads()
    }

    /**
     * Purchases accepted after this release carry an approval-bound automatic
     * FINALIZE fee cap and are submitted by native recovery during sync. A
     * pre-upgrade purchase has no such authority, so discover it from durable
     * workflow state and raise one ordinary exact approval without asking the
     * user to locate or paste an internal buyer-session identifier.
     */
    private fun scheduleTrackedShakedexFinalizeApproval(snapshot: NativeWalletReadSnapshot) {
        val notice = snapshot.finalizeNotices.firstOrNull {
            it.phase == "finalizeAvailable" &&
                it.transactionId !in trackedShakedexFinalizePromptAttempts
        } ?: return
        if (
            busy || walletHnsSyncInProgress || pendingValueApproval != null ||
                pendingWalletAuthentication != null
        ) return
        val (lease, handle) = valueActionContext(requiresShakedex = true) ?: return
        if (!trackedShakedexFinalizePromptAttempts.add(notice.transactionId)) return
        if (!beginOperation(
                lease,
                getString(R.string.wallet_status_preparing_value_action),
                resetReads = false,
            )
        ) {
            trackedShakedexFinalizePromptAttempts.remove(notice.transactionId)
            return
        }
        valueActionStatusView.text = getString(R.string.wallet_finalize_notice_preparing_legacy)
        val epoch = lifecycleEpoch
        val authorityGeneration = walletAuthorityGeneration
        thread(name = "hns-wallet-tracked-finalize-prepare") {
            val approval = NativeWalletBridge.prepareNextShakedexFinalize(handle)
            val exact = approval?.takeIf {
                it.kind == NativeHnsValueApprovalKind.NAME_MARKET_PURCHASE
            }
            if (approval != null && exact == null) {
                NativeWalletBridge.rejectHnsValueAction(handle, approval.actionToken)
                approval.close()
                NativeWalletBridge.lock(handle)
            }
            runOnUiThread {
                val mayPublish = walletOperationMayPublish(
                    epoch,
                    lease,
                    handle,
                    authorityGeneration,
                )
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
                    valueActionStatusView.text = getString(
                        R.string.wallet_value_actions_ready,
                        snapshot.height,
                    )
                    return@runOnUiThread
                }
                pendingValueApproval = exact
                trackedShakedexFinalizeApprovalTransactionId = notice.transactionId
                requireWalletAuthentication(
                    getString(R.string.wallet_auth_transaction_title),
                    getString(R.string.wallet_finalize_notice_authenticate_legacy, notice.name),
                    action = {
                        if (
                            pendingValueApproval === exact &&
                                walletOperationMayPublish(
                                    epoch,
                                    lease,
                                    handle,
                                    authorityGeneration,
                                )
                        ) {
                            showValueApproval(exact, lease, epoch, authorityGeneration)
                        } else {
                            rejectPreparedValueAction(
                                exact,
                                lease,
                                epoch,
                                authorityGeneration,
                            )
                        }
                    },
                    cancelled = {
                        rejectPreparedValueAction(
                            exact,
                            lease,
                            epoch,
                            authorityGeneration,
                        )
                    },
                )
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
            displayHandshakeNameText(name.name),
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
        return destroyWalletController(handle, retainPublicHnsSessions = true)
    }

    private fun destroyWalletController(
        handle: Long,
        retainPublicHnsSessions: Boolean = false,
    ): Boolean {
        if (handle == INVALID_HANDLE) return true
        val destroyed = if (retainPublicHnsSessions) {
            NativeWalletBridge.destroyRetainingPublicHnsSessions(handle)
        } else {
            NativeWalletBridge.destroy(handle)
        }
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

    private fun multipleNameImportInput(): EditText = EditText(this).apply {
        hint = getString(R.string.wallet_name_multiple_import_example)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_DONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        filters = arrayOf(InputFilter { source, start, end, destination, destinationStart, destinationEnd ->
            val replacementLength = end - start
            val nextLength = destination.length - (destinationEnd - destinationStart) + replacementLength
            if (nextLength <= MAX_MULTIPLE_NAME_INPUT_CHARACTERS) null else ""
        })
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        setAutofillHints(null)
        minLines = 4
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
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
        const val RECOVERY_WORD_COUNT = 24
        const val RECOVERY_CHOICE_COUNT = 4
        const val SAFE_FULL_RESCAN_BIRTHDAY = 0L
        const val MAX_VISIBLE_READ_ITEMS = 20
        const val MAX_SEND_RECIPIENT_BYTES = 512
        const val DEFAULT_HNS_MAXIMUM_FEE = "0.1"
        const val DEFAULT_HNS_MAXIMUM_FEE_BASE_UNITS = "100000"
        const val MAX_VALUE_ACTION_INPUT_CHARACTERS = 512
        const val MAX_RESOURCE_EDITOR_CHARACTERS = 4 * 1024
        const val DEFAULT_LISTING_LIFETIME_SECONDS = 7 * 24 * 60 * 60L
        const val DEFAULT_OFFER_PAGE_SIZE = 32
        const val DIRECT_SHAKESCAPE_FOREGROUND_TICK_MILLIS = 250L
        const val MAX_DIRECT_SHAKESCAPE_FRAMES_PER_TICK = 16
        const val DIRECT_SHAKESCAPE_STATUS_REFRESH_TICKS = 20
        const val DIRECT_SHAKESCAPE_NETWORK_MAINTENANCE_TICKS = 120
        const val DIRECT_SHAKESCAPE_LISTEN_PORT = 12_038
        const val MAX_VISIBLE_DIRECT_SHAKESCAPE_PEERS = 3
        const val MAX_DIRECT_SHAKESCAPE_ENDPOINT_CHARACTERS = 128
        const val LIVE_HNS_SYNC_PROGRESS_POLL_MILLIS = 500L
        const val MINIMUM_HNS_SYNC_STAGE_VISIBILITY_MILLIS = 3_000L
        const val BITCOIN_SYNC_PROGRESS_POLL_MILLIS = 1_000L
        const val SWAP_BITCOIN_AUTO_SYNC_INTERVAL_MILLIS = 15 * 60_000L
        // Stop must leave enough time to open, review, authenticate, and start
        // a Bitcoin value operation. Without this guard the 250 ms direct-peer
        // tick can immediately reacquire the controller after a successful
        // cancellation, making every Bitcoin action appear inert.
        const val SWAP_BITCOIN_STOP_OPERATION_GRACE_MILLIS = 5 * 60_000L
        const val HNS_POST_BROADCAST_VERIFICATION_ATTEMPTS = 3
        const val HNS_POST_BROADCAST_VERIFICATION_INTERVAL_MILLIS = 1_000L
        const val DIRECT_HNS_MAX_HEADER_AGREEMENT_RECOVERIES_PER_SYNC = 5
        const val NUL_CHARACTER = "\u0000"
        const val PENDING_OUTGOING_PREFS = "wallet_pending_outgoing_recovery"
        const val PENDING_OUTGOING_ACCOUNT = "account_id"
        const val PENDING_OUTGOING_HEIGHT = "snapshot_height"
    }
}

internal const val HNS_CATCHUP_PROGRESS_RETRY_DELAY_MILLIS = 2_000L
internal const val HNS_CATCHUP_DEGRADED_RETRY_DELAY_MILLIS = 30_000L
internal const val SWAP_HNS_AUTO_SYNC_INTERVAL_MILLIS = 2 * 60_000L
internal const val WALLET_VALUE_ACTION_SNAPSHOT_REUSE_MILLIS = 60_000L

/**
 * Loading an existing execution journal immediately after a verified unlock
 * sync must inherit that sync's observation time. A later execution revision
 * remains urgent and resets the cadence so settlement changes are scanned.
 */
internal fun walletActiveSwapFingerprintBaseline(
    previousFingerprint: String?,
    currentSnapshotObservedAtElapsedMillis: Long?,
): Long? = when {
    previousFingerprint != null -> Long.MIN_VALUE
    currentSnapshotObservedAtElapsedMillis != null -> currentSnapshotObservedAtElapsedMillis
    else -> null
}

/**
 * Retry only a non-blocking native status read. A returned status is final for
 * this gate, including a genuinely locked or disabled wallet; callers must
 * never retry transaction preparation or any other effectful native call.
 */
internal inline fun awaitWalletValueActionStatus(
    attempts: Int,
    readStatus: () -> NativeWalletStatus?,
    waitBeforeRetry: () -> Unit,
): NativeWalletStatus? {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        readStatus()?.let { return it }
        if (attempt + 1 < attempts) waitBeforeRetry()
    }
    return null
}

/**
 * A value-action review may skip a redundant network round only while its
 * authenticated projection still belongs to the exact live wallet authority,
 * is recent on the monotonic clock, and is not behind a newer header already
 * observed by the browser. Native preparation still revalidates the complete
 * name/coin evidence and constructs the exact transaction from this snapshot.
 */
internal fun walletValueActionMayReuseVerifiedSnapshot(
    hasCurrentAuthority: Boolean,
    snapshotObservedAtElapsedMillis: Long,
    nowElapsedMillis: Long,
    snapshotHeight: Long,
    latestObservedHeaderHeight: Long?,
): Boolean =
    hasCurrentAuthority &&
        snapshotObservedAtElapsedMillis > 0L &&
        nowElapsedMillis >= snapshotObservedAtElapsedMillis &&
        nowElapsedMillis - snapshotObservedAtElapsedMillis <=
        WALLET_VALUE_ACTION_SNAPSHOT_REUSE_MILLIS &&
        (latestObservedHeaderHeight == null || snapshotHeight >= latestObservedHeaderHeight)

/**
 * Return a newly observed HNS height only when the wallet snapshot has not
 * authenticated it and this active-swap watcher has not already requested it.
 */
internal fun walletActiveSwapHnsRefreshHeight(
    snapshotHeight: Long?,
    observedHeaderHeight: Long?,
    attemptedHeaderHeight: Long?,
): Long? {
    val observed = observedHeaderHeight ?: return null
    if (snapshotHeight != null && observed <= snapshotHeight) return null
    if (attemptedHeaderHeight != null && observed <= attemptedHeaderHeight) return null
    return observed
}

/**
 * A checkpoint that advanced authenticated state can resume promptly. A
 * degraded checkpoint means transport is unavailable, so retry on the same
 * cadence as peer maintenance instead of spinning every two seconds.
 */
internal fun directHnsCatchupRetryDelayMillis(
    headerState: NativeWalletHnsCatchupProgress.HeaderState,
): Long =
    if (
        headerState == NativeWalletHnsCatchupProgress.HeaderState.Degraded ||
            headerState == NativeWalletHnsCatchupProgress.HeaderState.OutboundPortBlocked
    ) {
        HNS_CATCHUP_DEGRADED_RETRY_DELAY_MILLIS
    } else {
        HNS_CATCHUP_PROGRESS_RETRY_DELAY_MILLIS
    }

internal fun recoveryWordChoices(
    words: List<String>,
    correctIndex: Int,
    bip39Words: List<String>,
    random: SecureRandom,
): List<String> {
    require(correctIndex in words.indices)
    val correct = words[correctIndex]
    require(bip39Words.size == BIP39_ENGLISH_WORD_COUNT)
    require(bip39Words.distinct().size == BIP39_ENGLISH_WORD_COUNT)
    require(correct in bip39Words)
    val pool = bip39Words.filterTo(mutableListOf()) { it != correct }
    val choices = mutableListOf(correct)
    while (choices.size < 4 && pool.isNotEmpty()) {
        choices += pool.removeAt(random.nextInt(pool.size))
    }
    require(choices.size == 4)
    java.util.Collections.shuffle(choices, random)
    return choices
}

private const val BIP39_ENGLISH_ASSET = "bip39-english.txt"
internal const val BIP39_ENGLISH_WORD_COUNT = 2_048

/**
 * Long enough for an ordinary app switch without retaining unlocked wallet
 * authority indefinitely when Shakescape remains in the background.
 */
internal const val WALLET_APP_SWITCH_RETENTION_MILLIS = 30_000L

/** A system credential screen may retain only the exact current wallet lease. */
internal fun walletCredentialTransitionMayRetain(
    authenticationPending: Boolean,
    ownsCurrentLease: Boolean,
): Boolean = authenticationPending && ownsCurrentLease

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

internal fun walletDirectShakescapeWorkerMustStop(nativeLocked: Boolean?): Boolean =
    nativeLocked == true

internal fun walletDirectShakescapeWorkerMayService(nativeLocked: Boolean?): Boolean =
    nativeLocked == false

/**
 * A UI transport operation needs stable Java-owned lifetime authority only.
 * Native connect/retry/disconnect calls take the controller's blocking mutex
 * and authoritatively report a locked or unsupported controller. Requiring a
 * preceding non-blocking native status read creates a false rejection whenever
 * the foreground Shakescape service tick momentarily owns that mutex.
 */
internal fun walletDirectShakescapeOperationMayBegin(
    hasCurrentLease: Boolean,
    hasController: Boolean,
    hasUnconfirmedKey: Boolean,
): Boolean = hasCurrentLease && hasController && !hasUnconfirmedKey

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
    hasActiveAtomicSwap: Boolean,
    foregroundServiceActive: Boolean,
): Boolean =
    foregroundServiceActive &&
        (hasActiveReadOnlyHnsSync || hasActiveReadOnlyBitcoinSync || hasActiveAtomicSwap)

internal data class WalletDashboardAvailability(
    val navigation: Boolean,
    val mutations: Boolean,
)

internal fun walletDashboardAvailability(
    busy: Boolean,
    hnsSynchronizationActive: Boolean,
    bitcoinSynchronizationActive: Boolean,
    controllerUnlocked: Boolean,
    controllerAvailableForActions: Boolean,
): WalletDashboardAvailability = WalletDashboardAvailability(
    // A Bitcoin sync owns the native mutex but exposes progress separately.
    // Safe detail navigation must remain available after scanner/app lifecycle
    // transitions so the user can inspect or stop that sync.
    navigation = !busy && !hnsSynchronizationActive && controllerUnlocked,
    mutations = !busy && !hnsSynchronizationActive && !bitcoinSynchronizationActive &&
        controllerAvailableForActions,
)

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

    fun copySecret(): CharArray? = secret?.copyOf()

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

internal fun walletOperationCompletionOwnsBusyState(
    busy: Boolean,
    currentOperationSerial: Long,
    completingOperationSerial: Long,
): Boolean = busy && currentOperationSerial == completingOperationSerial

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
