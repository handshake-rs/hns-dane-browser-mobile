import Security
import UIKit
import UniformTypeIdentifiers
import LocalAuthentication
import Network
@preconcurrency import AVFoundation
import CoreImage

private let defaultHnsMaximumFee = "1"
private let defaultHnsMaximumFeeBaseUnits = "1000000"

/// Native wallet-control surface.  Every HNS peer, consensus, block scan,
/// signing, and broadcast operation remains in the Rust controller; UIKit
/// only requests a local native action and displays its exact review result.
@MainActor
final class WalletViewController: UIViewController {
    /// Debug builds stay capturable for UI diagnostics and release-candidate
    /// documentation. Distribution builds still suspend protected wallet
    /// authority while the system is recording or mirroring the screen.
    private static var screenCaptureProtectionActive: Bool {
        #if DEBUG
        false
        #else
        UIScreen.main.isCaptured
        #endif
    }

    private let network: BrowserHandshakeNetwork
    private weak var browserProcess: BrowserProcess?
    private let keychain: WalletKeychainStore
    private let readBootstrapSource: any WalletReadBootstrapSource =
        UnavailableWalletReadBootstrapSource.shared
    private var wallet: RustNativeWallet?
    private var walletWasReopenedFromDurableStorage = false
    private var recoverySecret: WalletRecoverySecret?
    /// Non-nil means creation is intentionally incomplete: this key has not
    /// entered Keychain and every lifecycle exit must destroy its database.
    private var unconfirmedDatabaseKey: [UInt8]?
    private var persistentWalletExists = false
    private var protectedStorageIsAvailable = true
    private var isOperating = false
    private var walletIsUnlocked = false
    private var directHnsValueAvailable = false
    private var shakedexAvailable = false
    private var readGeneration: UInt64 = 0
    private var nameGalleryLoadGeneration: UInt64 = 0
    private var synchronizedReadsAvailable = false
    private var latestReadSnapshot: NativeHnsReadSnapshot?
    private var recentTransactions: [NativeHnsReadSnapshot.Transaction]?
    private var finalizeNotices: [NativeHnsReadSnapshot.FinalizeNotice] = []
    private var recentActivityPageOffset = 0
    /// Native-validated receive targets are retained separately from their
    /// human-readable labels. Pasteboard actions must never copy headings or
    /// derivation metadata as though those bytes were part of an address.
    private var receiveTargets: WalletReceiveTargets?
    private var resolvedDatabasePath: String?
    private var storageLease: WalletStorageLeaseToken?
    private var walletAuthorityRequested = false
    private var walletLifecycleSuspended = false
    private var retirementGeneration: UInt64 = 0
    private var walletAuthorityGeneration: UInt64 = 0
    private var retirementInFlight = false
    private var encryptedOrphanCleanupPending = false
    private var confirmedDeletionAccountID: String?
    private weak var restorePhraseField: UITextField?
    private weak var walletNameImportAlert: UIAlertController?
    private weak var walletNameImportField: UITextField?
    private weak var namesGalleryViewController: WalletNamesGalleryViewController?
    private weak var hnsSendFormAlert: UIAlertController?
    private weak var hnsSendApprovalAlert: UIAlertController?
    private var pendingHnsSendApproval: NativeHnsSendApproval?
    private weak var hnsValueApprovalAlert: UIAlertController?
    private var pendingHnsValueApproval: NativeHnsValueApproval?
    private var directShakescapeServiceTimer: Timer?
    private var directShakescapeServiceInFlight = false
    private var directShakescapeStatusSnapshot: NativeDirectShakescapeStatus?
    private var hnsSyncPresentationTimer: Timer?
    private var bitcoinSyncInProgress = false
    private var bitcoinSyncStopRequested = false
    private var bitcoinBirthdayResetInProgress = false
    private var bitcoinSyncTimer: Timer?
    private var bitcoinSnapshot: NativeBitcoinWalletSnapshot?
    private var bitcoinValueAvailable = false
    private weak var bitcoinSendApprovalAlert: UIAlertController?
    private var pendingBitcoinSendApproval: NativeBitcoinSendApproval?
    private weak var btcForHnsOfferApprovalAlert: UIAlertController?
    private var pendingBtcForHnsOfferApproval: NativeBtcForHnsOfferApproval?
    private weak var btcForHnsFundingApprovalAlert: UIAlertController?
    private var pendingBtcForHnsFundingApproval: NativeBitcoinHtlcFundingApproval?
    private weak var hnsForBtcFundingApprovalAlert: UIAlertController?
    private var pendingHnsForBtcFundingApproval: NativeHnsHtlcFundingApproval?
    private weak var swapSettlementApprovalAlert: UIAlertController?
    private var pendingSwapSettlementApproval: NativeSwapSettlementApproval?
    private var pendingSwapSettlementIsBitcoin = false
    private var pendingHandshakePayment: HandshakePaymentRequest?
    private var pendingPaymentPresentationScheduled = false
    private var scannedPaymentShouldResumeAfterUnlock = false
    private var browserSyncObservation: UUID?
    private var latestPublishedSnapshotHeight: UInt64?
    private var pendingOutgoingSnapshotHeight: UInt64?
    private var pendingOutgoingRefreshAttemptedHeight: UInt64?
    private var latestObservedBrowserHeaderHeight: UInt64?
    private var walletAuthenticationInProgress = false
    private var displayedHnsSyncStage: WalletHnsSyncStage?
    private var displayedHnsSyncStageSince: TimeInterval = 0
    private var hnsCatchupRetryPending = false
    private var walletPathMonitor: NWPathMonitor?
    private let walletPathMonitorQueue = DispatchQueue(
        label: "com.denuoweb.hnsdane.wallet-network-path",
        qos: .utility
    )
    private var activeWalletNetworkTransport: WalletNetworkTransport = .other

    private let statusLabel = UILabel()
    private let cellularDataWarningLabel = UILabel()
    private let accountLabel = UILabel()
    private let readStatusLabel = UILabel()
    private let balanceLabel = UILabel()
    private let paymentReceiveLabel = UILabel()
    private let nameReceiveLabel = UILabel()
    private let historyLabel = UILabel()
    private let namesLabel = UILabel()
    private let nameImportStatusLabel = UILabel()
    private let bitcoinStatusLabel = UILabel()
    private let bitcoinBalanceLabel = UILabel()
    private let bitcoinReceiveLabel = UILabel()
    private let recoveryTitle = UILabel()
    private let recoveryTextView = UITextView()
    private let createButton = UIButton(type: .system)
    private let restoreButton = UIButton(type: .system)
    private let openButton = UIButton(type: .system)
    private let lockButton = UIButton(type: .system)
    private let confirmRecoveryButton = UIButton(type: .system)
    private let refreshButton = UIButton(type: .system)
    private let synchronizeButton = UIButton(type: .system)
    private let importNameButton = UIButton(type: .system)
    private let deleteButton = UIButton(type: .system)
    private let bitcoinReceiveButton = UIButton(type: .system)
    private let bitcoinSyncButton = UIButton(type: .system)
    private let bitcoinSendButton = UIButton(type: .system)
    private let bitcoinBirthdayButton = UIButton(type: .system)
    private let bitcoinSellForHnsButton = UIButton(type: .system)
    private let bitcoinOffersButton = UIButton(type: .system)
    private let bitcoinExecutionsButton = UIButton(type: .system)
    private let dashboardStack = UIStackView()
    private let walletRefreshControl = UIRefreshControl()
    private let walletOperationIndicator = UIActivityIndicatorView(style: .medium)

    init(
        network: BrowserHandshakeNetwork,
        paymentRequest: HandshakePaymentRequest? = nil,
        browserProcess: BrowserProcess? = nil
    ) {
        self.network = network
        self.keychain = WalletKeychainStore(network: network)
        self.pendingHandshakePayment = paymentRequest
        self.browserProcess = browserProcess
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Wallet"
        if pendingHandshakePayment != nil {
            navigationItem.leftBarButtonItem = UIBarButtonItem(
                barButtonSystemItem: .done,
                target: self,
                action: #selector(dismissExternalWallet)
            )
        }
        view.backgroundColor = .systemGroupedBackground
        configureView()
        for name in [
            UIApplication.willResignActiveNotification,
            UIApplication.protectedDataWillBecomeUnavailableNotification,
        ] {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(suspendWalletLifecycle),
                name: name,
                object: nil
            )
        }
        for name in [
            UIApplication.didBecomeActiveNotification,
            UIApplication.protectedDataDidBecomeAvailableNotification,
        ] {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(reactivateWalletLifecycle),
                name: name,
                object: nil
            )
        }
        #if !DEBUG
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(protectWalletLifecycle),
            name: UIApplication.userDidTakeScreenshotNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleScreenCaptureChange),
            name: UIScreen.capturedDidChangeNotification,
            object: nil
        )
        #endif
        refreshState()
        startPendingOutgoingRefreshObserver()
    }

    @objc private func dismissExternalWallet() {
        dismiss(animated: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        walletAuthorityRequested = true
        startWalletNetworkMonitoring()
        if UIApplication.shared.applicationState == .active,
           UIApplication.shared.isProtectedDataAvailable,
           !Self.screenCaptureProtectionActive {
            walletLifecycleSuspended = false
        }
        startPendingOutgoingRefreshObserver()
        startHnsSyncPresentationWatcher()
        resumeWalletLifecycle()
    }

    override func viewWillDisappear(_ animated: Bool) {
        walletAuthorityRequested = false
        stopWalletNetworkMonitoring()
        stopPendingOutgoingRefreshObserver()
        stopHnsSyncPresentationWatcher()
        protectWalletLifecycle()
        super.viewWillDisappear(animated)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        hnsSyncPresentationTimer?.invalidate()
        walletPathMonitor?.cancel()
        directShakescapeServiceTimer?.invalidate()
        pendingHnsSendApproval?.actionToken.discard()
        pendingHnsSendApproval = nil
        pendingHnsValueApproval?.actionToken.discard()
        pendingHnsValueApproval = nil
        pendingBitcoinSendApproval?.actionToken.discard()
        pendingBitcoinSendApproval = nil
        pendingBtcForHnsOfferApproval?.actionToken.discard()
        pendingBtcForHnsOfferApproval = nil
        pendingBtcForHnsFundingApproval?.actionToken.discard()
        pendingBtcForHnsFundingApproval = nil
        pendingHnsForBtcFundingApproval?.actionToken.discard()
        pendingHnsForBtcFundingApproval = nil
        pendingSwapSettlementApproval?.actionToken.discard()
        pendingSwapSettlementApproval = nil
        bitcoinSyncTimer?.invalidate()
        recoverySecret?.clear()
        let currentWallet = wallet
        let currentLease = storageLease
        let hasIncompleteWallet = unconfirmedDatabaseKey != nil
        if var key = unconfirmedDatabaseKey {
            unconfirmedDatabaseKey = nil
            WalletSecretBytes.wipe(&key)
        }
        wallet = nil
        walletWasReopenedFromDurableStorage = false
        storageLease = nil
        let deletionPath = hasIncompleteWallet && currentLease != nil
            ? resolvedDatabasePath
            : nil
        let plan = WalletRetirementPlan(
            wallet: currentWallet,
            lease: currentLease,
            incompleteDatabasePath: deletionPath
        )
        if plan.hasWork {
            WalletRetirementQueue.shared.enqueue(plan)
        }
    }

    private func startWalletNetworkMonitoring() {
        guard walletPathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let transport: WalletNetworkTransport
            if path.usesInterfaceType(.cellular) {
                transport = .cellular
            } else if path.usesInterfaceType(.wifi) {
                transport = .wifi
            } else {
                transport = .other
            }
            Task { @MainActor [weak self] in
                guard let self,
                      self.activeWalletNetworkTransport != transport else { return }
                self.activeWalletNetworkTransport = transport
                if self.walletAuthorityRequested, self.isViewLoaded {
                    self.renderWalletDashboard()
                }
            }
        }
        walletPathMonitor = monitor
        monitor.start(queue: walletPathMonitorQueue)
    }

    private func stopWalletNetworkMonitoring() {
        walletPathMonitor?.cancel()
        walletPathMonitor = nil
        activeWalletNetworkTransport = .other
    }

    private func configureView() {
        configureSummaryLabel(statusLabel, identifier: "wallet.status")
        configureSummaryLabel(
            cellularDataWarningLabel,
            identifier: "wallet.cellular-data-warning"
        )
        cellularDataWarningLabel.text = "This unlocked wallet is using a cellular connection. Header synchronization, wallet scanning, and direct peer traffic may use significant mobile data. Switch to Wi-Fi or lock the wallet to stop unlocked-wallet network activity."
        cellularDataWarningLabel.textColor = .systemOrange
        configureSummaryLabel(accountLabel, identifier: "wallet.account")
        configureSummaryLabel(readStatusLabel, identifier: "wallet.read-status")
        configureSummaryLabel(balanceLabel, identifier: "wallet.balance")
        configureSummaryLabel(paymentReceiveLabel, identifier: "wallet.receive")
        configureSummaryLabel(nameReceiveLabel, identifier: "wallet.name-receive")
        configureSummaryLabel(historyLabel, identifier: "wallet.history")
        configureSummaryLabel(namesLabel, identifier: "wallet.names")
        configureSummaryLabel(nameImportStatusLabel, identifier: "wallet.name-import-status")
        configureSummaryLabel(bitcoinStatusLabel, identifier: "wallet.bitcoin-status")
        configureSummaryLabel(bitcoinBalanceLabel, identifier: "wallet.bitcoin-balance")
        configureSummaryLabel(bitcoinReceiveLabel, identifier: "wallet.bitcoin-receive")
        bitcoinStatusLabel.text = "Direct Bitcoin wallet is unavailable while locked."
        bitcoinBalanceLabel.text = "Bitcoin balance: unavailable."
        bitcoinReceiveLabel.text = "BIP84 receive address: unavailable."

        recoveryTitle.font = .preferredFont(forTextStyle: .headline)
        recoveryTitle.adjustsFontForContentSizeCategory = true
        recoveryTitle.text = "Recovery phrase — record it offline now"
        recoveryTitle.isHidden = true

        recoveryTextView.font = .preferredFont(forTextStyle: .body)
        recoveryTextView.adjustsFontForContentSizeCategory = true
        recoveryTextView.isEditable = false
        recoveryTextView.isSelectable = false
        recoveryTextView.backgroundColor = .secondarySystemGroupedBackground
        recoveryTextView.layer.cornerRadius = 12
        recoveryTextView.textContainerInset = UIEdgeInsets(top: 14, left: 12, bottom: 14, right: 12)
        recoveryTextView.accessibilityIdentifier = "wallet.recovery-phrase"
        recoveryTextView.isHidden = true
        recoveryTextView.heightAnchor.constraint(greaterThanOrEqualToConstant: 150).isActive = true

        configureButton(createButton, title: "Create wallet", action: #selector(createWallet))
        configureButton(restoreButton, title: "Restore wallet", action: #selector(restoreWallet))
        configureButton(openButton, title: "Open and unlock", action: #selector(openOrUnlockWallet))
        configureButton(lockButton, title: "Lock", action: #selector(lockWallet))
        configureButton(
            confirmRecoveryButton,
            title: "Verify recovery phrase",
            action: #selector(confirmRecoverySaved)
        )
        configureButton(refreshButton, title: "Refresh status", action: #selector(refreshWallet))
        configureButton(
            synchronizeButton,
            title: "Synchronize HNS wallet",
            action: #selector(synchronizeWalletReads)
        )
        configureButton(
            bitcoinReceiveButton,
            title: "New Bitcoin address",
            action: #selector(nextBitcoinReceiveAddress)
        )
        configureButton(
            bitcoinSyncButton,
            title: "Synchronize Bitcoin",
            action: #selector(toggleBitcoinSynchronization)
        )
        configureButton(
            bitcoinSendButton,
            title: "Send Bitcoin",
            action: #selector(showBitcoinSendForm)
        )
        configureButton(
            bitcoinBirthdayButton,
            title: "Set Bitcoin recovery birthday",
            action: #selector(showBitcoinBirthdayForm)
        )
        configureButton(
            bitcoinSellForHnsButton,
            title: "Sell BTC for HNS",
            action: #selector(showBtcForHnsOfferForm)
        )
        configureButton(
            bitcoinOffersButton,
            title: "Active BTC-for-HNS offers",
            action: #selector(showActiveBtcForHnsOffers)
        )
        configureButton(
            bitcoinExecutionsButton,
            title: "Atomic swap executions",
            action: #selector(showShakescapeExecutions)
        )
        bitcoinBirthdayButton.isHidden = true
        configureButton(
            importNameButton,
            title: "Track exact HNS name",
            action: #selector(requestExactHnsNameImport)
        )
        importNameButton.accessibilityIdentifier = "wallet.import-hns-name"
        configureButton(
            deleteButton,
            title: "Delete confirmed wallet",
            action: #selector(requestConfirmedWalletDeletion)
        )
        deleteButton.configuration?.baseBackgroundColor = .systemRed
        deleteButton.accessibilityIdentifier = "wallet.delete-confirmed"

        dashboardStack.translatesAutoresizingMaskIntoConstraints = false
        dashboardStack.axis = .vertical
        dashboardStack.spacing = 14
        dashboardStack.accessibilityIdentifier = "wallet.dashboard"

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        walletRefreshControl.accessibilityLabel = "Synchronize HNS wallet"
        walletRefreshControl.addTarget(
            self,
            action: #selector(pullToSynchronizeWalletReads),
            for: .valueChanged
        )
        scrollView.refreshControl = walletRefreshControl
        view.addSubview(scrollView)
        scrollView.addSubview(dashboardStack)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            dashboardStack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 20),
            dashboardStack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -20),
            dashboardStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 20),
            dashboardStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            dashboardStack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -40),
        ])
    }

    private func configureSummaryLabel(_ label: UILabel, identifier: String) {
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0
        label.textColor = .secondaryLabel
        label.accessibilityIdentifier = identifier
    }

    private func configureButton(_ button: UIButton, title: String, action: Selector) {
        var configuration = UIButton.Configuration.filled()
        configuration.title = title
        configuration.cornerStyle = .medium
        button.configuration = configuration
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func renderWalletDashboard() {
        guard isViewLoaded else { return }
        dashboardStack.arrangedSubviews.forEach { view in
            dashboardStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        if isOperating {
            walletOperationIndicator.accessibilityLabel = statusLabel.text
            walletOperationIndicator.startAnimating()
        } else {
            walletOperationIndicator.stopAnimating()
        }

        if storageLease == nil,
           WalletHnsSyncPresentationCache.latest(networkID: network.rawValue) != nil {
            renderSynchronizingWalletDashboard()
        } else if unconfirmedDatabaseKey != nil {
            renderRecoveryDashboard()
        } else if wallet == nil && !persistentWalletExists && storageLease != nil &&
            protectedStorageIsAvailable && walletLifecycleMayAcquireStorage {
            renderNoWalletDashboard()
        } else if walletIsUnlocked {
            renderUnlockedWalletDashboard()
        } else {
            renderLockedWalletDashboard()
        }
        if pendingHandshakePayment != nil { schedulePendingPaymentPresentation() }
    }

    private func walletStatusBody(_ labels: [UIView]) -> [UIView] {
        isOperating ? [walletOperationIndicator as UIView] + labels : labels
    }

    private func addCellularDataWarningIfNeeded(walletUnlocked: Bool) {
        guard walletCellularDataWarningVisible(
            walletUnlocked: walletUnlocked,
            transport: activeWalletNetworkTransport
        ) else { return }
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "CELLULAR DATA IN USE",
            body: [cellularDataWarningLabel],
            accent: .systemOrange
        ))
    }

    private func renderSynchronizingWalletDashboard() {
        addCellularDataWarningIfNeeded(walletUnlocked: true)
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "● SYNCHRONIZING · \(network.title)",
            body: [statusLabel, accountLabel, readStatusLabel],
            accent: .systemOrange
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Wallet management",
            body: [deleteButton]
        ))
    }

    private func renderNoWalletDashboard() {
        dashboardStack.addArrangedSubview(dashboardCard(
            title: isOperating ? "● WORKING · \(network.title)" : "NO WALLET · \(network.title)",
            body: walletStatusBody([statusLabel, accountLabel]),
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Get started",
            body: [createButton, restoreButton]
        ))
    }

    private func renderRecoveryDashboard() {
        dashboardStack.addArrangedSubview(dashboardCard(
            title: isOperating ? "● WORKING · \(network.title)" : "RECOVERY PHRASE",
            body: walletStatusBody([statusLabel]),
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Record this phrase offline before continuing",
            body: [recoveryTitle, recoveryTextView, confirmRecoveryButton],
            accent: .systemOrange
        ))
    }

    private func renderLockedWalletDashboard() {
        dashboardStack.addArrangedSubview(dashboardCard(
            title: isOperating ? "● WORKING · \(network.title)" : "● LOCKED · \(network.title)",
            body: walletStatusBody([statusLabel, accountLabel]),
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Wallet access",
            body: [openButton]
        ))
        dashboardStack.addArrangedSubview(tileHeading("Explore"))
        dashboardStack.addArrangedSubview(dashboardTile(
            title: "Wallet",
            summary: "Open and unlock",
            enabled: !isOperating,
            action: { [weak self] in self?.showWalletManagement() }
        ))
    }

    private func renderUnlockedWalletDashboard() {
        addCellularDataWarningIfNeeded(walletUnlocked: true)
        dashboardStack.addArrangedSubview(dashboardCard(
            title: isOperating ? "● WORKING · \(network.title)" : "● UNLOCKED · \(network.title)",
            body: walletStatusBody([statusLabel]),
            accent: .systemCyan
        ))

        let receive = dashboardButton(
            title: "Receive",
            action: #selector(showPaymentReceiveAddress),
            enabled: walletHnsPaymentActionsAvailable(
                baseAvailable: receiveTargets != nil && directHnsValueAvailable && !isOperating,
                hasPendingOutgoing: pendingOutgoingSnapshotHeight != nil
            )
        )
        receive.configuration?.image = UIImage(systemName: "qrcode")
        receive.configuration?.imagePadding = 5
        let send = dashboardButton(
            title: "Send",
            action: #selector(showHnsSendForm),
            accent: .systemIndigo,
            enabled: walletHnsPaymentActionsAvailable(
                baseAvailable: synchronizedReadsAvailable && directHnsValueAvailable && !isOperating,
                hasPendingOutgoing: pendingOutgoingSnapshotHeight != nil
            )
        )
        let scan = dashboardButton(
            title: "",
            action: #selector(scanHandshakePaymentQr),
            accent: .systemIndigo,
            enabled: walletHnsPaymentActionsAvailable(
                baseAvailable: synchronizedReadsAvailable && directHnsValueAvailable && !isOperating,
                hasPendingOutgoing: pendingOutgoingSnapshotHeight != nil
            )
        )
        scan.configuration?.image = UIImage(systemName: "camera.viewfinder")
        scan.accessibilityLabel = "Scan Handshake payment QR code"
        let sync = dashboardButton(
            title: "Sync",
            action: #selector(synchronizeWalletReads),
            enabled: synchronizeButton.isEnabled
        )
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "HNS balance",
            body: [balanceLabel, dashboardButtonRow([receive, send, scan, sync])],
            accent: .systemCyan
        ))

        if !synchronizedReadsAvailable {
            dashboardStack.addArrangedSubview(dashboardCard(
                title: "Sync needed",
                body: [readStatusLabel],
                accent: .systemOrange
            ))
        }

        if !finalizeNotices.isEmpty {
            let notice = UILabel()
            notice.numberOfLines = 0
            notice.font = .preferredFont(forTextStyle: .body)
            notice.adjustsFontForContentSizeCategory = true
            notice.text = finalizeNotices.map(formatFinalizeNotice).joined(separator: "\n\n")
            dashboardStack.addArrangedSubview(dashboardCard(
                title: "PENDING FINALIZE",
                body: [notice],
                accent: finalizeNotices.contains(where: { $0.phase == "finalizeAvailable" })
                    ? .systemOrange : .systemCyan
            ))
        }

        dashboardStack.addArrangedSubview(tileHeading("Explore"))
        let namesSummary = latestReadSnapshot.map { snapshot in
            snapshot.knownNameCount == 1
                ? "1 tracked name"
                : "\(snapshot.knownNameCount) tracked names"
        } ?? "Synchronize to update"
        let namesTile = dashboardTile(
            title: "Names",
            summary: namesSummary,
            enabled: !isOperating,
            action: { [weak self] in self?.showNamesDashboard() }
        )
        let walletTile = dashboardTile(
            title: "Wallet",
            summary: "Security and lifecycle",
            enabled: !isOperating,
            action: { [weak self] in self?.showWalletManagement() }
        )
        dashboardStack.addArrangedSubview(dashboardTileRow(namesTile, walletTile))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Recent activity",
            body: [historyLabel, dashboardButton(
                title: "View activity",
                action: #selector(showWalletActivity),
                enabled: !isOperating
            )]
        ))
        schedulePendingPaymentPresentation()
    }

    private func dashboardCard(
        title: String,
        body: [UIView],
        accent: UIColor = .systemCyan
    ) -> UIView {
        let card = UIStackView()
        card.axis = .vertical
        card.spacing = 10
        card.isLayoutMarginsRelativeArrangement = true
        card.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 15, leading: 15, bottom: 15, trailing: 15)
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true

        let heading = UILabel()
        heading.text = title
        heading.font = .preferredFont(forTextStyle: .caption1)
        heading.adjustsFontForContentSizeCategory = true
        heading.textColor = accent
        heading.numberOfLines = 0
        card.addArrangedSubview(heading)
        for view in body {
            card.addArrangedSubview(view)
        }
        return card
    }

    private func tileHeading(_ title: String) -> UILabel {
        let label = UILabel()
        label.text = title.uppercased()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .secondaryLabel
        label.accessibilityTraits = .header
        return label
    }

    private func dashboardTileRow(_ first: UIView, _ second: UIView) -> UIStackView {
        let row = UIStackView(arrangedSubviews: [first, second])
        row.axis = .horizontal
        row.spacing = 10
        row.distribution = .fillEqually
        return row
    }

    private func dashboardTile(
        title: String,
        summary: String,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> UIButton {
        var configuration = UIButton.Configuration.tinted()
        configuration.title = title
        configuration.subtitle = summary
        configuration.titleAlignment = .leading
        configuration.cornerStyle = .medium
        configuration.baseForegroundColor = .label
        configuration.baseBackgroundColor = .systemIndigo
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 14, leading: 12, bottom: 14, trailing: 12)
        let button = UIButton(type: .system)
        button.configuration = configuration
        button.contentHorizontalAlignment = .leading
        button.isEnabled = enabled
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }

    private func dashboardButton(
        title: String,
        action: Selector,
        accent: UIColor = .systemCyan,
        enabled: Bool = true
    ) -> UIButton {
        var configuration = UIButton.Configuration.filled()
        configuration.title = title
        configuration.cornerStyle = .medium
        configuration.baseBackgroundColor = accent
        configuration.baseForegroundColor = .black
        let button = UIButton(type: .system)
        button.configuration = configuration
        button.isEnabled = enabled
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func dashboardButtonRow(_ buttons: [UIButton]) -> UIStackView {
        let row = UIStackView(arrangedSubviews: buttons)
        row.axis = .horizontal
        row.spacing = 8
        row.distribution = .fillEqually
        return row
    }

    @objc private func showPaymentReceiveAddress() {
        guard pendingOutgoingSnapshotHeight == nil else {
            showErrorMessage("Send and Receive are temporarily unavailable while the outgoing transaction is pending.")
            return
        }
        guard let paymentReceiveAddress = receiveTargets?.paymentAddress else { return }
        let viewer = HandshakeReceiveQrViewController(address: paymentReceiveAddress)
        viewer.onShowNameAddress = { [weak self, weak viewer] in
            viewer?.dismiss(animated: true) {
                self?.showNameReceiveAddress()
            }
        }
        present(viewer, animated: true)
    }

    private func showNameReceiveAddress() {
        guard let address = receiveTargets?.nameTransferAddress else { return }
        let alert = UIAlertController(
            title: "Receive HNS name",
            message: "Name-transfer address",
            preferredStyle: .alert
        )
        addFittingAddress(address, label: "HNS name-transfer address", to: alert)
        alert.addAction(UIAlertAction(title: "Copy address", style: .default) { _ in
            UIPasteboard.general.setItems(
                [[UTType.plainText.identifier: address]],
                options: [.localOnly: true]
            )
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func nextBitcoinReceiveAddress() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress else { return }
        bitcoinReceiveButton.isEnabled = false
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.nextBitcoinReceiveAddress() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                self.bitcoinReceiveButton.isEnabled = true
                switch outcome {
                case .success(let receive):
                    self.renderBitcoinSnapshot(receive.snapshot)
                    self.bitcoinStatusLabel.text = "New BIP84 receive address derived locally."
                    self.presentBitcoinReceiveAddress(receive.receiveAddress)
                case .failure(let error):
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func presentBitcoinReceiveAddress(_ address: String) {
        let alert = UIAlertController(
            title: "Receive Bitcoin",
            message: "Bitcoin address",
            preferredStyle: .alert
        )
        addFittingAddress(address, label: "Bitcoin receive address", to: alert)
        alert.addAction(UIAlertAction(title: "Copy address", style: .default) { _ in
            UIPasteboard.general.setItems(
                [[UTType.plainText.identifier: address]],
                options: [.localOnly: true]
            )
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func addFittingAddress(
        _ address: String,
        label: String,
        to alert: UIAlertController
    ) {
        alert.addTextField { field in
            field.text = address
            field.font = .monospacedSystemFont(ofSize: 17, weight: .regular)
            field.adjustsFontSizeToFitWidth = true
            field.minimumFontSize = 8
            field.textAlignment = .center
            field.borderStyle = .none
            field.isUserInteractionEnabled = false
            field.accessibilityLabel = label
        }
    }

    @objc private func showBitcoinBirthdayForm() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress,
              let bitcoinSnapshot,
              ["recoveryUnknown", "recoveryPendingValidation"]
                .contains(bitcoinSnapshot.birthdayState) else { return }
        let alert = UIAlertController(
            title: "Set Bitcoin recovery birthday",
            message: "For a restored wallet, enter the earliest Bitcoin block that could contain activity. The request is stored now; the next Bitcoin synchronization validates its predecessor and begins recovery from the entered block. The HNS birthday is unrelated.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Earliest possible transaction block"
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Apply", style: .destructive) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let text = alert?.textFields?.first?.text,
                  let height = UInt32(text), height > 0 else {
                self?.showErrorMessage("Enter a nonzero Bitcoin block height.")
                return
            }
            self.setBitcoinBirthdayHeight(height, wallet: wallet)
        })
        present(alert, animated: true)
    }

    private func setBitcoinBirthdayHeight(
        _ height: UInt32,
        wallet: RustNativeWallet
    ) {
        bitcoinBirthdayResetInProgress = true
        bitcoinStatusLabel.text = "Saving Bitcoin recovery birthday height \(height) for validation during synchronization…"
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result {
                try wallet.setBitcoinBirthdayHeight(earliestTransactionHeight: height)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                self.bitcoinBirthdayResetInProgress = false
                switch outcome {
                case .success(let snapshot):
                    self.renderBitcoinSnapshot(snapshot)
                    self.bitcoinStatusLabel.text = "Bitcoin recovery birthday block \(snapshot.birthdayHeight) is pending validation. Synchronize Bitcoin to authenticate it and begin recovery."
                case .failure(let error):
                    self.bitcoinStatusLabel.text = "The Bitcoin recovery birthday was not saved. This is available only for an incomplete restored-wallet recovery."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    @objc private func toggleBitcoinSynchronization() {
        if bitcoinSyncInProgress {
            stopBitcoinSynchronization()
        } else {
            startBitcoinSynchronization()
        }
    }

    private func startBitcoinSynchronization() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress else { return }
        bitcoinSyncInProgress = true
        bitcoinSyncStopRequested = false
        bitcoinStatusLabel.text = "Connecting to direct Bitcoin peers…"
        startBitcoinProgressWatcher(wallet: wallet)
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.synchronizeBitcoin() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                let stopped = self.bitcoinSyncStopRequested
                self.bitcoinSyncTimer?.invalidate()
                self.bitcoinSyncTimer = nil
                self.bitcoinSyncInProgress = false
                self.bitcoinSyncStopRequested = false
                switch outcome {
                case .success(let synchronization):
                    self.renderBitcoinSnapshot(synchronization.snapshot)
                    self.bitcoinStatusLabel.text = "Bitcoin synchronized at height \(synchronization.checkpointHeight) with \(synchronization.connectedPeerCount)/\(synchronization.requiredPeerCount) peers."
                case .failure where stopped:
                    self.bitcoinStatusLabel.text = "Bitcoin synchronization stopped at the last durable checkpoint."
                    if let snapshot = try? wallet.bitcoinSnapshot() {
                        self.renderBitcoinSnapshot(snapshot)
                    }
                case .failure(let error):
                    self.bitcoinStatusLabel.text = "Bitcoin synchronization did not complete."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func stopBitcoinSynchronization() {
        guard let wallet, bitcoinSyncInProgress, !bitcoinSyncStopRequested else { return }
        bitcoinSyncStopRequested = true
        bitcoinStatusLabel.text = "Stopping Bitcoin synchronization now…"
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.cancelBitcoinSynchronization() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                if case .failure(let error) = outcome {
                    self.bitcoinSyncStopRequested = false
                    self.showError(error)
                    self.refreshButtonStates()
                }
            }
        }
    }

    private func startBitcoinProgressWatcher(wallet: RustNativeWallet) {
        bitcoinSyncTimer?.invalidate()
        bitcoinSyncTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet, self.bitcoinSyncInProgress else {
                return
            }
            DispatchQueue.global(qos: .utility).async { [weak self, weak wallet] in
                guard let wallet else { return }
                let progress = try? wallet.bitcoinSynchronizationProgress()
                DispatchQueue.main.async { [weak self, weak wallet] in
                    guard let self, let wallet, self.wallet === wallet,
                          self.bitcoinSyncInProgress, let progress else { return }
                    let percent = Double(progress.completionBasisPoints) / 100
                    let height = progress.chainHeight.map { " · chain height \($0)" } ?? ""
                    self.bitcoinStatusLabel.text = progress.connectionsMet
                        ? "Scanning Bitcoin compact filters · \(String(format: "%.2f", percent))%\(height)"
                        : "Connecting to Bitcoin peers · \(progress.successfulHandshakes)/\(progress.requiredPeerCount) handshakes\(height)"
                }
            }
        }
    }

    @objc private func showBitcoinSendForm() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress else { return }
        let alert = UIAlertController(
            title: "Send Bitcoin",
            message: "Enter a native Bitcoin address, amount in satoshis, and an absolute fee cap. The native wallet will show the exact selected fee before signing.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Bitcoin address"
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
        }
        alert.addTextField { field in
            field.placeholder = "Amount (sats)"
            field.keyboardType = .numberPad
        }
        alert.addTextField { field in
            field.placeholder = "Maximum fee (sats)"
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Review send", style: .default) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fields = alert?.textFields, fields.count == 3 else { return }
            let destination = fields[0].text ?? ""
            let amount = fields[1].text ?? ""
            let fee = fields[2].text ?? ""
            self.authenticateWalletAction(
                reason: "Authenticate before preparing this Bitcoin transaction"
            ) { [weak self, weak wallet] in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.prepareBitcoinSendAfterAuthentication(
                    wallet: wallet,
                    destination: destination,
                    amount: amount,
                    maximumFee: fee
                )
            }
        })
        present(alert, animated: true)
    }

    private func prepareBitcoinSendAfterAuthentication(
        wallet: RustNativeWallet,
        destination: String,
        amount: String,
        maximumFee: String
    ) {
        var destinationBytes = Array(destination.utf8)
        var amountBytes = Array(amount.utf8)
        var feeBytes = Array(maximumFee.utf8)
        statusLabel.text = "Preparing exact Bitcoin send…"
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result {
                try wallet.prepareBitcoinSend(
                    destination: &destinationBytes,
                    amountSats: &amountBytes,
                    maximumFeeSats: &feeBytes
                )
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let approval): self.presentBitcoinSendApproval(approval, wallet: wallet)
                case .failure(let error):
                    self.refreshState()
                    self.showError(error)
                }
            }
        }
    }

    private func presentBitcoinSendApproval(
        _ approval: NativeBitcoinSendApproval,
        wallet: RustNativeWallet
    ) {
        pendingBitcoinSendApproval?.actionToken.discard()
        pendingBitcoinSendApproval = approval
        let message = """
        Destination: \(approval.destination)
        Amount: \(approval.amountSats) sats
        Selected fee: \(approval.feeSats) sats
        Maximum fee: \(approval.maximumFeeSats) sats

        This approval is single-use and expires at Unix time \(approval.expiresAtUnix).
        """
        let alert = UIAlertController(title: "Review Bitcoin send", message: message, preferredStyle: .alert)
        bitcoinSendApprovalAlert = alert
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) { [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBitcoinSendApproval else { return }
            self.pendingBitcoinSendApproval = nil
            DispatchQueue.global(qos: .userInitiated).async { try? wallet.rejectBitcoinSend(pending.actionToken) }
        })
        alert.addAction(UIAlertAction(title: "Sign and broadcast", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBitcoinSendApproval else { return }
            self.pendingBitcoinSendApproval = nil
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveBitcoinSend(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    switch outcome {
                    case .success(let receipt):
                        self.bitcoinStatusLabel.text = "Bitcoin transaction broadcast: \(receipt.txid)"
                        if let snapshot = try? wallet.bitcoinSnapshot() { self.renderBitcoinSnapshot(snapshot) }
                    case .failure:
                        self.refreshState()
                        self.bitcoinStatusLabel.text = "The Bitcoin transaction is durably pending, but Kyoto was not ready to submit it. Synchronize Bitcoin to retry this same transaction. Do not prepare another send."
                        self.showErrorMessage("The Bitcoin transaction is durably pending. Synchronize Bitcoin to retry this same transaction; do not prepare another send.")
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    @objc private func showBtcForHnsOfferForm() {
        authenticateWalletAction(
            reason: "Authenticate before creating a signed Bitcoin-for-HNS offer"
        ) { [weak self] in
            self?.showBtcForHnsOfferFormAfterAuthentication()
        }
    }

    private func showBtcForHnsOfferFormAfterAuthentication() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress, !isOperating else { return }
        let alert = UIAlertController(
            title: "Sell BTC for HNS",
            message: "Create one exact, indivisible direct-board offer. Confirmed Bitcoin must cover the principal, active offers, and the separate fee reserve.",
            preferredStyle: .alert
        )
        for (placeholder, keyboard, value) in [
            ("BTC offered (sats)", UIKeyboardType.numberPad, nil),
            ("HNS requested", UIKeyboardType.decimalPad, nil),
            ("Bitcoin fee reserve (sats)", UIKeyboardType.numberPad, nil),
            ("Listing lifetime (hours, 1–168)", UIKeyboardType.numberPad, "24"),
        ] {
            alert.addTextField { field in
                field.placeholder = placeholder
                field.keyboardType = keyboard
                field.text = value
            }
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Review offer", style: .default) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fields = alert?.textFields, fields.count == 4,
                  let btc = UInt64(fields[0].text ?? ""), btc > 0,
                  let hnsText = Self.positiveHnsBaseUnits(fields[1].text ?? ""),
                  let hns = UInt64(hnsText), hns > 0,
                  let reserve = UInt64(fields[2].text ?? ""), reserve > 0,
                  let hours = UInt64(fields[3].text ?? ""), (1...168).contains(hours),
                  hours <= UInt64.max / 3_600 else {
                self?.showErrorMessage("Enter positive BTC sats, HNS with at most six decimals, a positive fee reserve, and 1–168 hours.")
                return
            }
            fields.forEach { $0.text = nil }
            self.isOperating = true
            self.bitcoinStatusLabel.text = "Preparing exact BTC-for-HNS listing…"
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result {
                    try wallet.prepareBtcForHnsOffer(
                        btcAmountSats: btc,
                        hnsAmountDollarydoos: hns,
                        bitcoinFeeReserveSats: reserve,
                        listingLifetimeSeconds: hours * 3_600
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let approval):
                        self.presentBtcForHnsOfferApproval(approval, wallet: wallet)
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "The listing was not prepared. Confirmed BTC must cover active listings, this principal, and its fee reserve."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func presentBtcForHnsOfferApproval(
        _ approval: NativeBtcForHnsOfferApproval,
        wallet: RustNativeWallet
    ) {
        pendingBtcForHnsOfferApproval?.actionToken.discard()
        pendingBtcForHnsOfferApproval = approval
        let hns = WalletReadPresenter.formatHnsBaseUnits(String(approval.hnsAmountDollarydoos))
        let expiry = DateFormatter.localizedString(
            from: Date(timeIntervalSince1970: TimeInterval(approval.offerExpiresAtUnix)),
            dateStyle: .medium,
            timeStyle: .medium
        )
        let message = """
        Offer: \(approval.btcAmountSats) sats
        Receive exactly: \(hns) HNS
        Bitcoin fee reserve: \(approval.bitcoinFeeReserveSats) sats
        Total Bitcoin commitment: \(approval.totalBitcoinCommitmentSats) sats
        Listing expires: \(expiry)

        Publishing signs and shares fixed terms. It does not broadcast a Bitcoin funding transaction. Settlement requires a connected swap peer and a separately approved atomic-swap session.
        """
        let alert = UIAlertController(
            title: "Publish BTC-for-HNS offer?", message: message, preferredStyle: .alert
        )
        btcForHnsOfferApprovalAlert = alert
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsOfferApproval else { return }
            self.pendingBtcForHnsOfferApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectBtcForHnsOffer(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: "Publish offer", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsOfferApproval else { return }
            self.pendingBtcForHnsOfferApproval = nil
            self.isOperating = true
            self.bitcoinStatusLabel.text = "Signing and publishing the exact offer…"
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveBtcForHnsOffer(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let summary):
                        self.bitcoinStatusLabel.text = "BTC-for-HNS offer \(summary.offerId.prefix(12))… is active and will be announced to a connected swap peer."
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "The BTC-for-HNS offer was not published."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    @objc private func showActiveBtcForHnsOffers() {
        guard let wallet, walletIsUnlocked, bitcoinValueAvailable, !isOperating else { return }
        bitcoinStatusLabel.text = "Loading authenticated local BTC-for-HNS offers…"
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.localBtcForHnsOffers() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let offers) where offers.isEmpty:
                    self.bitcoinStatusLabel.text = "There are no active local BTC-for-HNS offers."
                case .success(let offers):
                    let alert = UIAlertController(
                        title: "Active BTC-for-HNS offers",
                        message: "Choose an offer to review cancellation.",
                        preferredStyle: .alert
                    )
                    for offer in offers {
                        let hns = WalletReadPresenter.formatHnsBaseUnits(
                            String(offer.hnsAmountDollarydoos)
                        )
                        alert.addAction(UIAlertAction(
                            title: "\(offer.btcAmountSats) sats → \(hns) HNS · \(offer.offerId.prefix(12))…",
                            style: .default
                        ) { [weak self, weak wallet] _ in
                            guard let self, let wallet, self.wallet === wallet else { return }
                            self.confirmCancelBtcForHnsOffer(offer, wallet: wallet)
                        })
                    }
                    alert.addAction(UIAlertAction(title: "Done", style: .cancel))
                    self.present(alert, animated: true)
                case .failure(let error):
                    self.bitcoinStatusLabel.text = "Active BTC-for-HNS offers could not be authenticated."
                    self.showError(error)
                }
            }
        }
    }

    @objc private func showShakescapeExecutions() {
        guard let wallet, walletIsUnlocked, bitcoinValueAvailable, !isOperating else { return }
        bitcoinStatusLabel.text = "Loading durable atomic swap state…"
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.shakescapeExecutions() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let status) where status.executions.isEmpty:
                    self.bitcoinStatusLabel.text = "There are no accepted atomic swap executions.\n\(self.bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery))"
                case .success(let status):
                    self.bitcoinStatusLabel.text = self.bitcoinBroadcastRecoveryText(
                        status.bitcoinBroadcastRecovery
                    )
                    let alert = UIAlertController(
                        title: "Atomic swap executions",
                        message: "Statuses advance only from independently verified chain evidence.\n\n\(self.bitcoinBroadcastRecoveryText(status.bitcoinBroadcastRecovery))",
                        preferredStyle: .alert
                    )
                    for execution in status.executions {
                        alert.addAction(UIAlertAction(
                            title: "\(execution.state.replacingOccurrences(of: "_", with: " ")) · \(execution.sessionId.prefix(12))…",
                            style: .default
                        ) { [weak self, weak wallet] _ in
                            guard let self, let wallet, self.wallet === wallet else { return }
                            self.showShakescapeExecution(execution, wallet: wallet)
                        })
                    }
                    alert.addAction(UIAlertAction(title: "Done", style: .cancel))
                    self.present(alert, animated: true)
                case .failure(let error):
                    self.bitcoinStatusLabel.text = "Durable atomic swap state could not be authenticated."
                    self.showError(error)
                }
            }
        }
    }

    private func bitcoinBroadcastRecoveryText(
        _ recovery: NativeBitcoinBroadcastRecovery?
    ) -> String {
        guard let recovery else {
            return "Bitcoin broadcast recovery status is temporarily unavailable while the Bitcoin controller is busy or inactive."
        }
        guard recovery.totalApproved > 0 else {
            return "No approved Bitcoin transaction is awaiting durable broadcast recovery."
        }
        return "Durable Bitcoin broadcasts — waiting for submission: \(recovery.unobservedPrepared); submission outcome pending: \(recovery.unobservedSubmissionStarted); submitted and awaiting wallet observation: \(recovery.unobservedSubmitted); observed: \(recovery.observed); highest attempt count: \(recovery.highestAttemptCount); last durable change: Unix \(recovery.lastChangedAtUnix ?? 0). Exact signed bytes remain private and automatic recovery never creates a replacement transaction."
    }

    private func showShakescapeExecution(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let message = """
        Session: \(execution.sessionId)
        State: \(execution.state.replacingOccurrences(of: "_", with: " "))
        Funding order: \(execution.firstChain), then \(execution.secondChain)
        First funding confirmed: \(execution.firstFundingConfirmed)
        Second funding confirmed: \(execution.secondFundingConfirmed)

        Only locally verified chain evidence advances this status.
        """
        let alert = UIAlertController(title: "Atomic swap status", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        if execution.state == "first_funding_pending" && execution.firstChain == "bitcoin" {
            alert.addAction(UIAlertAction(title: "Prepare Bitcoin funding", style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showBtcForHnsFundingFee(execution, wallet: wallet)
            })
        }
        if execution.state == "second_funding_pending" && execution.secondChain == "handshake" {
            alert.addAction(UIAlertAction(title: "Prepare HNS funding", style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showHnsForBtcFundingFee(execution, wallet: wallet)
            })
        }
        if ["both_funded", "first_redeemed", "secret_observed"].contains(execution.state) {
            alert.addAction(UIAlertAction(title: "Redeem or refund", style: .default) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showSwapSettlementActions(execution, wallet: wallet)
            })
        }
        present(alert, animated: true)
    }

    private func showSwapSettlementActions(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: "Authenticate before preparing an atomic-swap settlement transaction"
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showSwapSettlementActionsAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showSwapSettlementActionsAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: "Redeem or refund",
            message: "Only the action authorized for this wallet role and verified chain state can be prepared. Refunds remain unavailable until their signed timeout is mature.",
            preferredStyle: .actionSheet
        )
        for (title, action, bitcoin) in [
            ("Redeem received HNS", NativeSwapSettlementAction.redeem, false),
            ("Redeem received Bitcoin", .redeem, true),
            ("Refund locked HNS", .refund, false),
            ("Refund locked Bitcoin", .refund, true),
        ] {
            alert.addAction(UIAlertAction(title: title, style: action == .refund ? .destructive : .default) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showSwapSettlementFee(
                    execution, action: action, bitcoin: bitcoin, wallet: wallet
                )
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func showSwapSettlementFee(
        _ execution: NativeShakescapeExecutionSummary,
        action: NativeSwapSettlementAction,
        bitcoin: Bool,
        wallet: RustNativeWallet
    ) {
        let unit = bitcoin ? "sats" : "dollarydoos"
        let asset = bitcoin ? "Bitcoin" : "HNS"
        let alert = UIAlertController(
            title: "Maximum settlement fee",
            message: "Enter the maximum \(asset) fee in \(unit).",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Maximum fee (\(unit))"
            if !bitcoin {
                field.text = defaultHnsMaximumFeeBaseUnits
            }
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Review transaction", style: .default) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage("Enter a positive maximum settlement fee.")
                return
            }
            alert?.textFields?.first?.text = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                var session = Array(execution.sessionId.utf8)
                var maximumFee = Array(String(fee).utf8)
                let outcome = Result {
                    try wallet.prepareSwapSettlement(
                        sessionId: &session, maximumFee: &maximumFee,
                        action: action, bitcoin: bitcoin
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let approval):
                        self.presentSwapSettlementApproval(
                            approval, bitcoin: bitcoin, wallet: wallet
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "That swap action is not currently authorized. Check role, verified funding, secret observation, timeout maturity, synchronization, and fee cap."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func presentSwapSettlementApproval(
        _ approval: NativeSwapSettlementApproval,
        bitcoin: Bool,
        wallet: RustNativeWallet
    ) {
        pendingSwapSettlementApproval?.actionToken.discard()
        pendingSwapSettlementApproval = approval
        pendingSwapSettlementIsBitcoin = bitcoin
        let unit = bitcoin ? "sats" : "dollarydoos"
        let message = """
        Spend: \(approval.inputAmount) \(unit)
        Return: \(approval.outputAmount) \(unit)
        Network fee: \(approval.fee) \(unit) (maximum \(approval.maximumFee))
        Transaction: \(approval.transactionId)
        Session: \(approval.sessionId)

        Broadcast is irreversible. The swap advances only after independent local chain verification, not from this submission receipt.
        """
        let alert = UIAlertController(
            title: "Broadcast swap \(approval.action.rawValue)?",
            message: message,
            preferredStyle: .alert
        )
        swapSettlementApprovalAlert = alert
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingSwapSettlementApproval else { return }
            let isBitcoin = self.pendingSwapSettlementIsBitcoin
            self.pendingSwapSettlementApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectSwapSettlement(pending.actionToken, bitcoin: isBitcoin)
            }
        })
        alert.addAction(UIAlertAction(title: "Broadcast settlement", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingSwapSettlementApproval else { return }
            let isBitcoin = self.pendingSwapSettlementIsBitcoin
            self.pendingSwapSettlementApproval = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result {
                    try wallet.approveSwapSettlement(
                        pending.actionToken, bitcoin: isBitcoin
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let receipt):
                        self.bitcoinStatusLabel.text = "Swap \(receipt.action.rawValue) \(receipt.transactionId.prefix(12))… submitted; waiting for independent local confirmation."
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "Settlement submission did not complete. Check durable transaction status before retrying."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func showHnsForBtcFundingFee(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: "Authenticate before preparing an HNS funding transaction"
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showHnsForBtcFundingFeeAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showHnsForBtcFundingFeeAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: "Prepare HNS funding",
            message: "Enter a maximum HNS fee within the reserve signed into this accepted session.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Maximum funding fee (dollarydoos)"
            field.text = defaultHnsMaximumFeeBaseUnits
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Review transaction", style: .default) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage("Enter a positive maximum HNS fee in dollarydoos.")
                return
            }
            alert?.textFields?.first?.text = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                var session = Array(execution.sessionId.utf8)
                var maximumFee = Array(String(fee).utf8)
                let outcome = Result {
                    try wallet.prepareHnsForBtcFunding(
                        sessionId: &session, maximumFeeDollarydoos: &maximumFee
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let approval):
                        self.presentHnsForBtcFundingApproval(approval, wallet: wallet)
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "HNS HTLC funding was not prepared."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func presentHnsForBtcFundingApproval(
        _ approval: NativeHnsHtlcFundingApproval, wallet: RustNativeWallet
    ) {
        pendingHnsForBtcFundingApproval?.actionToken.discard()
        pendingHnsForBtcFundingApproval = approval
        let amount = WalletReadPresenter.formatHnsBaseUnits(String(approval.amountDollarydoos))
        let fee = WalletReadPresenter.formatHnsBaseUnits(String(approval.feeDollarydoos))
        let maximumFee = WalletReadPresenter.formatHnsBaseUnits(
            String(approval.maximumFeeDollarydoos)
        )
        let message = """
        Lock: \(amount) HNS
        Network fee: \(fee) HNS (maximum \(maximumFee) HNS)
        Transaction: \(approval.transactionId)
        Session: \(approval.sessionId)

        Broadcast is irreversible. Submission is not confirmation; the swap advances only after the authenticated HNS reader confirms this exact lock.
        """
        let alert = UIAlertController(
            title: "Broadcast HNS HTLC funding?", message: message, preferredStyle: .alert
        )
        hnsForBtcFundingApprovalAlert = alert
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingHnsForBtcFundingApproval else { return }
            self.pendingHnsForBtcFundingApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectHnsForBtcFunding(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: "Broadcast funding", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingHnsForBtcFundingApproval else { return }
            self.pendingHnsForBtcFundingApproval = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveHnsForBtcFunding(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let receipt):
                        self.bitcoinStatusLabel.text = "HNS HTLC funding \(receipt.transactionId.prefix(12))… submitted; waiting for authenticated confirmation."
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "The HNS HTLC funding transaction was not submitted."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func showBtcForHnsFundingFee(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: "Authenticate before preparing a Bitcoin funding transaction"
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showBtcForHnsFundingFeeAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showBtcForHnsFundingFeeAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: "Prepare Bitcoin funding",
            message: "Enter a maximum fee within the reserve signed into this accepted session.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Maximum funding fee (sats)"
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Review transaction", style: .default) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage("Enter a positive maximum Bitcoin fee in satoshis.")
                return
            }
            alert?.textFields?.first?.text = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                var session = Array(execution.sessionId.utf8)
                var maximumFee = Array(String(fee).utf8)
                let outcome = Result {
                    try wallet.prepareBtcForHnsFunding(
                        sessionId: &session, maximumFeeSats: &maximumFee
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let approval):
                        self.presentBtcForHnsFundingApproval(approval, wallet: wallet)
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "Bitcoin HTLC funding was not prepared."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func presentBtcForHnsFundingApproval(
        _ approval: NativeBitcoinHtlcFundingApproval, wallet: RustNativeWallet
    ) {
        pendingBtcForHnsFundingApproval?.actionToken.discard()
        pendingBtcForHnsFundingApproval = approval
        let message = """
        Lock: \(approval.amountSats) sats
        Network fee: \(approval.feeSats) sats (maximum \(approval.maximumFeeSats))
        Transaction: \(approval.txid)
        Session: \(approval.sessionId)

        Broadcast is irreversible. Submission is not confirmation; the swap advances only after the local Bitcoin verifier confirms the exact lock.
        """
        let alert = UIAlertController(
            title: "Broadcast Bitcoin HTLC funding?", message: message, preferredStyle: .alert
        )
        btcForHnsFundingApprovalAlert = alert
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsFundingApproval else { return }
            self.pendingBtcForHnsFundingApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectBtcForHnsFunding(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: "Broadcast funding", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsFundingApproval else { return }
            self.pendingBtcForHnsFundingApproval = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveBtcForHnsFunding(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let receipt):
                        self.bitcoinStatusLabel.text = "Bitcoin HTLC funding \(receipt.txid.prefix(12))… submitted; waiting for independent local confirmation."
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "The Bitcoin HTLC funding transaction was not submitted."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func confirmCancelBtcForHnsOffer(
        _ offer: NativeBtcForHnsOfferSummary,
        wallet: RustNativeWallet
    ) {
        let hns = WalletReadPresenter.formatHnsBaseUnits(String(offer.hnsAmountDollarydoos))
        let alert = UIAlertController(
            title: "Cancel this offer?",
            message: "Withdraw the signed offer of \(offer.btcAmountSats) sats for \(hns) HNS?\n\nOffer ID: \(offer.offerId)\n\nCancellation releases its local balance reservation and is announced to the connected swap peer. It does not cancel a swap session that has already accepted and frozen these terms.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Keep offer", style: .cancel))
        alert.addAction(UIAlertAction(title: "Cancel offer", style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.isOperating = true
            self.bitcoinStatusLabel.text = "Signing direct-offer cancellation…"
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.cancelBtcForHnsOffer(offerId: offer.offerId) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success:
                        self.bitcoinStatusLabel.text = "The BTC-for-HNS offer was cancelled and its local reservation was released."
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = "The offer was not cancelled. It may have expired or entered a swap session."
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        present(alert, animated: true)
    }

    private func renderBitcoinSnapshot(_ snapshot: NativeBitcoinWalletSnapshot) {
        bitcoinSnapshot = snapshot
        bitcoinBalanceLabel.text = "Confirmed: \(snapshot.confirmedSats) sats · pending: \(snapshot.trustedPendingSats + snapshot.untrustedPendingSats) sats · total: \(snapshot.totalSats) sats"
        let birthday: String
        switch snapshot.birthdayState {
        case "awaitingCreationTip":
            birthday = "automatic creation tip pending"
        case "recoveryUnknown":
            birthday = "recovery height unknown; full historical scan if synchronized"
        case "recoveryPendingValidation":
            birthday = "recovery block \(snapshot.birthdayHeight), pending validation"
        default:
            birthday = "validated birthday block \(snapshot.birthdayHeight)"
        }
        bitcoinBirthdayButton.isHidden = !["recoveryUnknown", "recoveryPendingValidation"]
            .contains(snapshot.birthdayState)
        bitcoinReceiveLabel.text = "BIP84 receive\n\(snapshot.receiveAddress)\n\(birthday)"
    }

    @objc private func showHnsSendForm() {
        presentHnsSendForm(prefill: nil)
    }

    private func presentHnsSendForm(prefill: HandshakePaymentRequest?) {
        guard !isOperating,
              pendingOutgoingSnapshotHeight == nil,
              walletIsUnlocked,
              directHnsValueAvailable,
              synchronizedReadsAvailable,
              presentedViewController == nil else {
            return
        }
        let alert = UIAlertController(
            title: "Send HNS",
            message: "A direct peer synchronization runs before review. The maximum fee is a cap; the wallet selects an HSD-compatible network fee at or below it. The default cap is 1 HNS.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: "Recipient",
                placeholder: "hs1…",
                accessibilityLabel: "HNS recipient address"
            )
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.isSecureTextEntry = false
            field.accessibilityIdentifier = "wallet.send.recipient"
            field.text = prefill?.address
        }
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: "Amount (HNS)",
                placeholder: "0.00",
                accessibilityLabel: "Amount in HNS"
            )
            field.keyboardType = .decimalPad
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.send.amount"
            field.text = prefill?.amountHns
        }
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: "Fee cap (HNS)",
                placeholder: defaultHnsMaximumFee,
                accessibilityLabel: "Maximum fee cap in HNS"
            )
            field.text = defaultHnsMaximumFee
            field.keyboardType = .decimalPad
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.send.maximum-fee"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in
            self?.clearHnsSendForm(alert)
        })
        alert.addAction(UIAlertAction(title: "Review send", style: .default) { [weak self, weak alert] _ in
            guard let self, let alert else { return }
            let request = self.takeHnsSendRequest(from: alert)
            self.clearHnsSendForm(alert)
            guard let request else {
                self.showErrorMessage(
                    "Enter a visible HNS recipient, a positive amount, and a positive maximum fee with no more than six decimal places."
                )
                return
            }
            self.beginHnsSendReview(request)
        })
        hnsSendFormAlert = alert
        present(alert, animated: true)
    }

    @objc private func scanHandshakePaymentQr() {
        guard presentedViewController == nil else { return }
        let scanner = HandshakeQrScannerViewController()
        scanner.onResult = { [weak self, weak scanner] value in
            guard let self else { return }
            scanner?.dismiss(animated: true) {
                guard let request = HandshakePaymentURI.parse(value) else {
                    self.showErrorMessage("That QR code is not a valid Handshake payment URI.")
                    return
                }
                // A full-screen camera may temporarily protect and release the
                // wallet controller. Reconnect first, then restore the normal
                // synchronized SEND review surface from this public URI data.
                self.pendingHandshakePayment = request
                self.scannedPaymentShouldResumeAfterUnlock = true
                self.schedulePendingPaymentPresentation()
            }
        }
        present(scanner, animated: true)
    }

    private func schedulePendingPaymentPresentation() {
        guard pendingHandshakePayment != nil,
              !pendingPaymentPresentationScheduled else { return }
        pendingPaymentPresentationScheduled = true
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.pendingPaymentPresentationScheduled = false
            let continuation = walletPendingPaymentContinuation(
                hasPendingPayment: self.pendingHandshakePayment != nil,
                resumeAfterScanner: self.scannedPaymentShouldResumeAfterUnlock,
                foreground: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil,
                dialogVisible: self.presentedViewController != nil,
                busy: self.isOperating,
                hasController: self.wallet != nil,
                controllerUnlocked: self.walletIsUnlocked,
                hasHnsValue: self.directHnsValueAvailable,
                hasCurrentSnapshot: self.recentTransactions != nil,
                hasPendingOutgoing: self.pendingOutgoingSnapshotHeight != nil
            )
            switch continuation {
            case .none, .wait:
                break
            case .unlock:
                self.openOrUnlockWallet()
            case .synchronize:
                self.synchronizeWalletReads()
            case .present:
                guard let request = self.pendingHandshakePayment else { return }
                self.pendingHandshakePayment = nil
                self.scannedPaymentShouldResumeAfterUnlock = false
                self.presentHnsSendForm(prefill: request)
            }
        }
    }

    private static func configureSendField(
        _ field: UITextField,
        visibleLabel: String,
        placeholder: String,
        accessibilityLabel: String
    ) {
        let label = UILabel()
        label.text = "\(visibleLabel)  "
        label.font = .systemFont(ofSize: 12, weight: .semibold)
        label.textColor = .secondaryLabel
        label.sizeToFit()
        field.leftView = label
        field.leftViewMode = .always
        field.placeholder = placeholder
        field.accessibilityLabel = accessibilityLabel
    }

    private func clearHnsSendForm(_ alert: UIAlertController?) {
        alert?.textFields?.forEach { field in
            field.text = nil
            field.resignFirstResponder()
        }
        if hnsSendFormAlert === alert {
            hnsSendFormAlert = nil
        }
    }

    private func takeHnsSendRequest(from alert: UIAlertController) -> WalletHnsSendRequest? {
        let fields = alert.textFields ?? []
        guard fields.count == 3 else { return nil }
        var recipient = Array((fields[0].text ?? "").utf8)
        defer { WalletSecretBytes.wipe(&recipient) }
        guard (1...512).contains(recipient.count),
              recipient.allSatisfy({ (0x21...0x7e).contains($0) }),
              let recipientText = String(bytes: recipient, encoding: .utf8),
              let amount = Self.positiveHnsBaseUnits(fields[1].text ?? ""),
              let maximumFee = Self.positiveHnsBaseUnits(fields[2].text ?? "") else {
            return nil
        }
        return WalletHnsSendRequest(
            recipient: recipientText,
            amountBaseUnits: amount,
            maximumFeeBaseUnits: maximumFee
        )
    }

    /// Converts exact wallet decimal text to canonical base units without a
    /// floating-point conversion.  HNS has six decimal places; the native
    /// boundary repeats this validation before it can prepare an action.
    private static func positiveHnsBaseUnits(_ exact: String) -> String? {
        var input = Array(exact.utf8)
        defer { WalletSecretBytes.wipe(&input) }
        guard !input.isEmpty, input.count <= 46 else { return nil }
        if input.first == UInt8(ascii: ".") {
            input.insert(UInt8(ascii: "0"), at: 0)
        }
        let decimalPositions = input.enumerated().filter { $0.element == UInt8(ascii: ".") }
        guard decimalPositions.count <= 1,
              input.allSatisfy({
                  (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) || $0 == UInt8(ascii: ".")
              }) else {
            return nil
        }
        let separator = decimalPositions.first?.offset
        let whole = separator.map { Array(input[..<$0]) } ?? input
        let fraction = separator.map { Array(input[($0 + 1)...]) } ?? []
        guard !whole.isEmpty,
              (whole.count == 1 || whole.first != UInt8(ascii: "0")),
              separator == nil || !fraction.isEmpty,
              fraction.count <= 6 else {
            return nil
        }
        var baseUnits = whole
        baseUnits.append(contentsOf: fraction)
        baseUnits.append(contentsOf: repeatElement(UInt8(ascii: "0"), count: 6 - fraction.count))
        while baseUnits.count > 1, baseUnits.first == UInt8(ascii: "0") {
            baseUnits.removeFirst()
        }
        let maximum = Array("340282366920938463463374607431768211455".utf8)
        defer { WalletSecretBytes.wipe(&baseUnits) }
        guard baseUnits != [UInt8(ascii: "0")],
              baseUnits.count < maximum.count ||
                (baseUnits.count == maximum.count &&
                    (baseUnits.elementsEqual(maximum) ||
                        baseUnits.lexicographicallyPrecedes(maximum, by: <))),
              let result = String(bytes: baseUnits, encoding: .ascii) else {
            return nil
        }
        return result
    }

    private func beginHnsSendReview(_ request: WalletHnsSendRequest) {
        authenticateWalletAction(
            reason: "Authenticate before preparing this Handshake transaction"
        ) { [weak self] in
            self?.beginHnsSendReviewAfterAuthentication(request)
        }
    }

    private func beginHnsSendReviewAfterAuthentication(_ request: WalletHnsSendRequest) {
        guard !isOperating,
              let lease = storageLease,
              let wallet,
              walletIsUnlocked,
              directHnsValueAvailable,
              synchronizedReadsAvailable,
              unconfirmedDatabaseKey == nil else {
            showErrorMessage("Unlock and synchronize the direct HNS wallet before reviewing a send.")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = "Synchronizing the direct HNS wallet for send review…"
        refreshButtonStates()
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<NativeHnsSendApproval, Error> = Result {
                _ = try Self.synchronizeDirectHnsReads(wallet: wallet, keychain: keychain)
                var recipient = Array(request.recipient.utf8)
                var amount = Array(request.amountBaseUnits.utf8)
                var maximumFee = Array(request.maximumFeeBaseUnits.utf8)
                let approval = try wallet.prepareHnsSend(
                    recipient: &recipient,
                    amountBaseUnits: &amount,
                    maximumFeeBaseUnits: &maximumFee
                )
                guard approval.recipient == request.recipient,
                      approval.amountBaseUnits == request.amountBaseUnits,
                      approval.maximumFeeBaseUnits == request.maximumFeeBaseUnits else {
                    try? wallet.rejectHnsSend(approval.actionToken)
                    try? wallet.lock()
                    throw NativeWalletBridgeError.invalidOutput(
                        "native HNS send approval changed the displayed request"
                    )
                }
                return approval
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else {
                    if case .success(let approval) = outcome {
                        DispatchQueue.global(qos: .userInitiated).async {
                            try? wallet.rejectHnsSend(approval.actionToken)
                        }
                    }
                    return
                }
                switch outcome {
                case .success(let approval):
                    self.showHnsSendApproval(
                        approval,
                        lease: lease,
                        generation: generation,
                        walletIdentity: walletIdentity,
                        authorityGeneration: authorityGeneration
                    )
                case .failure(let error):
                    self.isOperating = false
                    self.refreshState()
                    self.readStatusLabel.text = "HNS send review could not be prepared. Synchronize again before retrying."
                    self.showError(error)
                }
            }
        }
    }

    private func showHnsSendApproval(
        _ approval: NativeHnsSendApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        dismissPendingHnsSendApproval(rejectNatively: true)
        pendingHnsSendApproval = approval
        let date = Date(timeIntervalSince1970: TimeInterval(approval.expiresAtUnix))
        let expiry = DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .medium)
        let message = [
            "Recipient: \(approval.recipient)",
            "Amount: \(WalletReadPresenter.formatHnsBaseUnits(approval.amountBaseUnits)) HNS",
            "Maximum fee: \(WalletReadPresenter.formatHnsBaseUnits(approval.maximumFeeBaseUnits)) HNS",
            "Finality: proof-of-work confirmations",
            "Warning: fee estimate may change",
            "Expires: \(expiry)",
        ].joined(separator: "\n\n\n")
        let alert = UIAlertController(title: "Review HNS send", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) { [weak self] _ in
            self?.rejectHnsSendApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        alert.addAction(UIAlertAction(title: "Broadcast", style: .destructive) { [weak self] _ in
            self?.approveHnsSendApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        hnsSendApprovalAlert = alert
        present(alert, animated: true)
    }

    private func approveHnsSendApproval(
        _ approval: NativeHnsSendApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        guard pendingHnsSendApproval?.actionToken === approval.actionToken,
              let wallet else { return }
        pendingHnsSendApproval = nil
        hnsSendApprovalAlert = nil
        readStatusLabel.text = "Broadcasting the approved HNS send…"
        let pendingRefreshFloor = latestPublishedSnapshotHeight
        let pendingRecoveryAccountID = confirmedDeletionAccountID
        let pendingRecoveryNetworkID = network.rawValue
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<WalletHnsPostBroadcastResult<NativeHnsSendReceipt, NativeHnsReadSnapshot>, Error> = Result {
                let receipt = try wallet.approveHnsSend(approval.actionToken)
                if let pendingRecoveryAccountID {
                    WalletPendingOutgoingRecoveryStore.save(
                        networkID: pendingRecoveryNetworkID,
                        accountID: pendingRecoveryAccountID,
                        height: pendingRefreshFloor
                    )
                }
                var snapshot: NativeHnsReadSnapshot? = nil
                for attempt in 0..<3 {
                    snapshot = try? Self.synchronizeDirectHnsReads(wallet: wallet, keychain: keychain)
                    let admitted = snapshot?.transactionHistory.contains(where: {
                        Self.lowerHex($0.txid) == receipt.txid
                            && ($0.status == "mempool" || $0.status == "confirmed")
                    }) == true
                    if admitted || snapshot == nil { break }
                    if attempt < 2 { Thread.sleep(forTimeInterval: 1) }
                }
                return WalletHnsPostBroadcastResult(receipt: receipt, snapshot: snapshot)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                switch outcome {
                case .success(let result):
                    if let snapshot = result.snapshot { self.publish(snapshot) }
                    let admissionStatus = result.snapshot?.transactionHistory
                        .first(where: { Self.lowerHex($0.txid) == result.receipt.txid })?
                        .status
                    if let admissionStatus,
                       admissionStatus == "mempool" || admissionStatus == "confirmed" {
                        self.readStatusLabel.text = "HNS transaction \(result.receipt.txid) has verified network status: \(admissionStatus). You may close the wallet; confirmation still requires inclusion in a verified block."
                    } else if result.snapshot == nil {
                        self.pendingOutgoingSnapshotHeight = pendingRefreshFloor ?? 0
                        self.readStatusLabel.text = "Transaction pending. Shakescape could not refresh the wallet immediately and will retry automatically after it detects a new Handshake block."
                        self.maybeRefreshPendingOutgoingAfterNewBlock()
                    } else {
                        self.readStatusLabel.text = "Transaction pending. Shakescape will refresh the wallet automatically after it detects a new Handshake block."
                    }
                case .failure(let error):
                    self.readStatusLabel.text = "HNS send outcome is ambiguous. The wallet was locked; unlock and synchronize before taking another action."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func rejectHnsSendApproval(
        _ approval: NativeHnsSendApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        guard pendingHnsSendApproval?.actionToken === approval.actionToken,
              let wallet else { return }
        pendingHnsSendApproval = nil
        hnsSendApprovalAlert = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let outcome = Result { try wallet.rejectHnsSend(approval.actionToken) }
            if case .failure = outcome { try? wallet.lock() }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                switch outcome {
                case .success:
                    self.readStatusLabel.text = "HNS send rejected. No payment was broadcast."
                case .failure(let error):
                    self.readStatusLabel.text = "HNS send rejection could not be verified. The wallet was locked."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func dismissPendingHnsSendApproval(rejectNatively: Bool) {
        let approval = pendingHnsSendApproval
        pendingHnsSendApproval = nil
        hnsSendApprovalAlert?.dismiss(animated: false)
        hnsSendApprovalAlert = nil
        guard let approval else { return }
        if rejectNatively, let wallet {
            DispatchQueue.global(qos: .userInitiated).async {
                if (try? wallet.rejectHnsSend(approval.actionToken)) == nil {
                    try? wallet.lock()
                }
            }
        } else {
            approval.actionToken.discard()
        }
        isOperating = false
    }

    private func showNamesDashboard() {
        guard let snapshot = latestReadSnapshot else {
            showErrorMessage("Synchronize the HNS wallet before opening tracked names.")
            return
        }
        let gallery = WalletNamesGalleryViewController(
            names: snapshot.knownNames,
            totalNameCount: snapshot.knownNameCount,
            snapshotHeight: snapshot.moduleStatus.validatedHeight,
            actionsAvailable: !isOperating
        )
        gallery.onOptions = { [weak self] in self?.showTrackedNameOptionsMenu() }
        namesGalleryViewController = gallery
        navigationController?.pushViewController(gallery, animated: true)
        loadCompleteNameGallery(snapshot: snapshot, into: gallery)
    }

    private func loadCompleteNameGallery(
        snapshot: NativeHnsReadSnapshot,
        into gallery: WalletNamesGalleryViewController
    ) {
        nameGalleryLoadGeneration &+= 1
        let generation = nameGalleryLoadGeneration
        guard snapshot.knownNameCount > snapshot.knownNames.count,
              let wallet else { return }
        let authorityGeneration = walletAuthorityGeneration
        let expectedHeight = snapshot.moduleStatus.validatedHeight
        DispatchQueue.global(qos: .userInitiated).async { [weak self, weak wallet, weak gallery] in
            guard let wallet else { return }
            var names: [NativeHnsReadSnapshot.KnownName] = []
            var offset = 0
            var valid = true
            while offset < snapshot.knownNameCount {
                do {
                    let page = try wallet.hnsNamePage(offset: offset)
                    guard page.offset == offset,
                          page.total == snapshot.knownNameCount,
                          !page.names.isEmpty else {
                        valid = false
                        break
                    }
                    names.append(contentsOf: page.names)
                    offset += page.names.count
                    if !page.hasMore { break }
                } catch {
                    valid = false
                    break
                }
            }
            let uniqueNames = Set(names.map(\.name)).count == names.count
            let uniqueHashes = Set(names.map(\.nameHash)).count == names.count
            DispatchQueue.main.async {
                guard let self, let gallery,
                      valid,
                      names.count == snapshot.knownNameCount,
                      uniqueNames,
                      uniqueHashes,
                      generation == self.nameGalleryLoadGeneration,
                      self.wallet === wallet,
                      self.walletAuthorityGeneration == authorityGeneration,
                      self.latestReadSnapshot?.moduleStatus.validatedHeight == expectedHeight,
                      self.namesGalleryViewController === gallery else { return }
                gallery.update(
                    names: names,
                    totalNameCount: snapshot.knownNameCount,
                    snapshotHeight: expectedHeight,
                    actionsAvailable: !self.isOperating
                )
            }
        }
    }

    private func showTrackedNameOptionsMenu() {
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Name options",
            message: nameImportStatusLabel.text,
            preferredStyle: .alert
        )
        if importNameButton.isEnabled {
            alert.addAction(UIAlertAction(title: "Track exact HNS name", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in
                    self?.requestExactHnsNameImport()
                }
            })
            alert.addAction(UIAlertAction(title: "Import multiple names", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in
                    self?.requestMultipleHnsNameImport()
                }
            })
        }
        if hnsValueActionMayStart {
            alert.addAction(UIAlertAction(title: "Name actions", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showNameActionMenu() }
            })
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private var hnsValueActionMayStart: Bool {
        !isOperating &&
            wallet != nil &&
            walletIsUnlocked &&
            directHnsValueAvailable &&
            synchronizedReadsAvailable &&
            unconfirmedDatabaseKey == nil &&
            storageLease != nil
    }

    private var shakedexActionMayStart: Bool {
        hnsValueActionMayStart && shakedexAvailable
    }

    private func showNameActionMenu() {
        guard hnsValueActionMayStart, presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Name actions",
            message: "Every action runs one fresh direct-peer synchronization and shows the exact native review before broadcast.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Transfer name", style: .default) { [weak self] _ in
            self?.afterWalletMenuDismissal { [weak self] in self?.showTransferNameForm() }
        })
        alert.addAction(UIAlertAction(title: "Finalize transfer", style: .default) { [weak self] _ in
            self?.afterWalletMenuDismissal { [weak self] in self?.showFinalizeNameForm() }
        })
        alert.addAction(UIAlertAction(title: "Set records", style: .default) { [weak self] _ in
            self?.afterWalletMenuDismissal { [weak self] in self?.showSetNameRecordsForm() }
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func showShakedexDashboard() {
        guard presentedViewController == nil else { return }
        let shakescapeStatus = shakedexActionMayStart ? directShakescapeStatusSnapshot : nil
        let transportLine: String
        if let shakescapeStatus {
            let listener = shakescapeStatus.listenerPort.map { "listening on \($0)" }
                ?? "listener unavailable"
            let peer = shakescapeStatus.peerEndpoint.map { "paired with \($0)" }
                ?? "no paired peer"
            transportLine = "\(listener); \(peer)."
        } else {
            transportLine = "P2P swap connection unavailable while locked or unsynchronized."
        }
        let alert = UIAlertController(
            title: "Shakedex",
            message: shakedexActionMayStart
                ? "Offers and purchase steps remain in the direct native HNS controller. \(transportLine) No page or provider can request them."
                : "Unlock and synchronize the direct HNS wallet before querying offers or preparing a purchase step.",
            preferredStyle: .alert
        )
        if shakedexActionMayStart {
            alert.addAction(UIAlertAction(title: "Create fixed-price offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showCreateOfferForm() }
            })
            alert.addAction(UIAlertAction(title: "Cancel offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showCancelOfferForm() }
            })
            alert.addAction(UIAlertAction(title: "Recover name from offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showRecoverNameForm() }
            })
            alert.addAction(UIAlertAction(title: "List offers", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showListOffersForm() }
            })
            alert.addAction(UIAlertAction(title: "Get session", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showGetSessionForm() }
            })
            alert.addAction(UIAlertAction(title: "Accept offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showAcceptOfferForm() }
            })
            alert.addAction(UIAlertAction(title: "Finalize purchase", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showFinalizePurchaseForm() }
            })
            alert.addAction(UIAlertAction(title: "Pair direct peer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showPairDirectShakescapeForm() }
            })
            if shakescapeStatus?.listenerPort == nil {
                alert.addAction(UIAlertAction(title: "Retry listener", style: .default) { [weak self] _ in
                    self?.retryDirectShakescapeListener()
                })
            }
            if shakescapeStatus?.peerEndpoint != nil {
                alert.addAction(UIAlertAction(title: "Disconnect peer", style: .destructive) { [weak self] _ in
                    self?.disconnectDirectShakescapePeer()
                })
            }
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func afterWalletMenuDismissal(_ action: @escaping () -> Void) {
        guard presentedViewController != nil else {
            action()
            return
        }
        dismiss(animated: true, completion: action)
    }

    /// UIKit alerts are intentionally collected one field at a time. This
    /// avoids an oversized alert form and guarantees a Cancel clears the
    /// current text before any direct wallet operation begins.
    private func collectHnsValueForm(
        title: String,
        fields: [WalletHnsValueFormField],
        index: Int = 0,
        values: [String] = [],
        completion: @escaping ([String]) -> Void
    ) {
        guard index < fields.count else {
            completion(values)
            return
        }
        guard presentedViewController == nil else { return }
        let fieldDefinition = fields[index]
        let alert = UIAlertController(
            title: title,
            message: "\(index + 1) of \(fields.count): \(fieldDefinition.label)",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = fieldDefinition.placeholder
            field.text = fieldDefinition.initialValue
            field.keyboardType = fieldDefinition.numeric ? .decimalPad : .asciiCapable
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.hns-value.\(index)"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in
            alert.textFields?.forEach { $0.text = nil; $0.resignFirstResponder() }
        })
        alert.addAction(UIAlertAction(title: index + 1 == fields.count ? "Review" : "Next", style: .default) { [weak self, weak alert] _ in
            guard let self, let alert else { return }
            let text = alert.textFields?.first?.text ?? ""
            alert.textFields?.first?.text = nil
            alert.textFields?.first?.resignFirstResponder()
            let nextValues = values + [text]
            self.afterWalletMenuDismissal {
                self.collectHnsValueForm(
                    title: title,
                    fields: fields,
                    index: index + 1,
                    values: nextValues,
                    completion: completion
                )
            }
        })
        present(alert, animated: true)
    }

    private func showTransferNameForm() {
        collectHnsValueForm(
            title: "Transfer name",
            fields: [
                .init(label: "Exact name", placeholder: "name"),
                .init(label: "Recipient", placeholder: "Recipient address"),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 3,
                  let fee = Self.positiveHnsBaseUnits(values[2]) else {
                self?.showErrorMessage("Enter a name, recipient, and positive maximum fee with no more than six decimal places.")
                return
            }
            self.beginHnsValueAction(
                .transferName(name: values[0], recipient: values[1], maximumFeeBaseUnits: fee)
            )
        }
    }

    private func showFinalizeNameForm() {
        collectHnsValueForm(
            title: "Finalize transfer",
            fields: [
                .init(label: "Exact name", placeholder: "name"),
                .init(label: "Expected recipient (optional)", placeholder: "Recipient address"),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 3,
                  let fee = Self.positiveHnsBaseUnits(values[2]) else {
                self?.showErrorMessage("Enter a name and positive maximum fee with no more than six decimal places.")
                return
            }
            self.beginHnsValueAction(
                .finalizeName(
                    name: values[0],
                    expectedRecipient: values[1].isEmpty ? nil : values[1],
                    maximumFeeBaseUnits: fee
                )
            )
        }
    }

    private func showSetNameRecordsForm() {
        guard presentedViewController == nil else { return }
        let editor = NameRecordsEditorViewController { [weak self] name, records, feeText in
            guard let self,
                  let fee = Self.positiveHnsBaseUnits(feeText) else {
                self?.showErrorMessage(
                    "Enter an exact name, valid resource records, and a positive maximum fee with no more than six decimal places."
                )
                return
            }
            self.beginHnsValueAction(
                .setNameRecords(name: name, records: records, maximumFeeBaseUnits: fee)
            )
        }
        let navigation = UINavigationController(rootViewController: editor)
        navigation.modalPresentationStyle = .formSheet
        present(navigation, animated: true)
    }

    private func showCreateOfferForm() {
        collectHnsValueForm(
            title: "Create fixed-price offer",
            fields: [
                .init(label: "Exact name", placeholder: "name"),
                .init(label: "Price in HNS", placeholder: "1", numeric: true),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
                .init(label: "Listing lifetime in seconds", placeholder: "86400", numeric: true, initialValue: "86400"),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 4,
                  let price = Self.positiveHnsBaseUnits(values[1]),
                  let fee = Self.positiveHnsBaseUnits(values[2]),
                  let lifetime = UInt64(values[3]) else {
                self?.showErrorMessage("Enter a name, positive price/fee, and a whole-number listing lifetime.")
                return
            }
            self.beginHnsValueAction(
                .createFixedPriceOffer(
                    name: values[0],
                    priceBaseUnits: price,
                    maximumFeeBaseUnits: fee,
                    listingLifetimeSeconds: lifetime
                )
            )
        }
    }

    private func showCancelOfferForm() {
        collectHnsValueForm(
            title: "Cancel offer",
            fields: [.init(label: "Seller session ID", placeholder: "64 lowercase hex characters")]
        ) { [weak self] values in
            guard let self, let sellerSessionID = values.first else { return }
            self.beginHnsValueAction(.cancelOffer(sellerSessionID: sellerSessionID))
        }
    }

    private func showRecoverNameForm() {
        collectHnsValueForm(
            title: "Recover name from offer",
            fields: [
                .init(label: "Seller session ID", placeholder: "64 lowercase hex characters"),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let fee = Self.positiveHnsBaseUnits(values[1]) else {
                self?.showErrorMessage("Enter the seller session ID and a positive maximum fee.")
                return
            }
            self.beginHnsValueAction(
                .recoverName(sellerSessionID: values[0], maximumFeeBaseUnits: fee)
            )
        }
    }

    private func showAcceptOfferForm() {
        collectHnsValueForm(
            title: "Accept offer",
            fields: [
                .init(label: "Listing ID", placeholder: "64 lowercase hex characters"),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let fee = Self.positiveHnsBaseUnits(values[1]) else {
                self?.showErrorMessage("Enter the listing ID and a positive maximum fee.")
                return
            }
            self.beginHnsValueAction(.acceptOffer(listingID: values[0], maximumFeeBaseUnits: fee))
        }
    }

    private func showFinalizePurchaseForm() {
        collectHnsValueForm(
            title: "Finalize purchase",
            fields: [
                .init(label: "Session ID", placeholder: "64 lowercase hex characters"),
                .init(
                    label: "Maximum fee cap in HNS",
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let fee = Self.positiveHnsBaseUnits(values[1]) else {
                self?.showErrorMessage("Enter the session ID and a positive maximum fee.")
                return
            }
            self.beginHnsValueAction(
                .finalizePurchase(sessionID: values[0], maximumFeeBaseUnits: fee)
            )
        }
    }

    private func showListOffersForm() {
        collectHnsValueForm(
            title: "List offers",
            fields: [
                .init(label: "Cursor (optional)", placeholder: "64 lowercase hex characters"),
                .init(label: "Page size", placeholder: "32", numeric: true, initialValue: "32"),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let limit = UInt8(values[1]) else {
                self?.showErrorMessage("Enter an optional cursor and a page size from 1 to 64.")
                return
            }
            self.beginShakedexQuery(.listOffers(cursor: values[0].isEmpty ? nil : values[0], limit: limit))
        }
    }

    private func showGetSessionForm() {
        collectHnsValueForm(
            title: "Get session",
            fields: [.init(label: "Session ID", placeholder: "64 lowercase hex characters")]
        ) { [weak self] values in
            guard let self, let sessionID = values.first else { return }
            self.beginShakedexQuery(.getSession(sessionID: sessionID))
        }
    }

    private func showPairDirectShakescapeForm() {
        collectHnsValueForm(
            title: "Pair swap peer",
            fields: [.init(label: "IP-literal endpoint", placeholder: "192.0.2.1:12038")]
        ) { [weak self] values in
            guard let self, let endpoint = values.first else { return }
            self.connectDirectShakescapePeer(endpoint)
        }
    }

    private func connectDirectShakescapePeer(_ endpoint: String) {
        runDirectShakescapeOperation(status: "Pairing the explicit P2P swap endpoint…") {
            try $0.connectDirectShakescape(endpoint: endpoint)
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let connection):
                switch connection.outcome {
                case .connected:
                    self.readStatusLabel.text =
                        "Swap peer connected at \(connection.peerEndpoint ?? "unknown endpoint")."
                case .replaced:
                    self.readStatusLabel.text =
                        "Swap peer replaced with \(connection.peerEndpoint ?? "unknown endpoint")."
                case .unavailable:
                    self.readStatusLabel.text = "P2P swap connection is unavailable."
                case .locked:
                    self.readStatusLabel.text = "Unlock the wallet before pairing a swap peer."
                case .connectionFailed:
                    self.readStatusLabel.text = "The explicit P2P swap endpoint could not be reached."
                case .exchangeFailed:
                    self.readStatusLabel.text = "The peer connected but rejected the bounded P2P swap exchange."
                }
            case .failure(let error):
                self.readStatusLabel.text = "P2P swap pairing failed without changing wallet or chain state."
                self.showError(error)
            }
        }
    }

    private func retryDirectShakescapeListener() {
        runDirectShakescapeOperation(status: "Retrying the wallet-owned P2P swap listener…") {
            try $0.retryDirectShakescapeListener()
            return true
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                self.readStatusLabel.text = "The wallet-owned P2P swap listener is ready."
            case .failure(let error):
                self.readStatusLabel.text = "The P2P swap listener remains unavailable; the HNS wallet is unchanged."
                self.showError(error)
            }
        }
    }

    private func disconnectDirectShakescapePeer() {
        runDirectShakescapeOperation(status: "Disconnecting the swap peer…") {
            try $0.disconnectDirectShakescape()
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let disconnected):
                self.readStatusLabel.text = disconnected
                    ? "Swap peer disconnected."
                    : "No swap peer was connected."
            case .failure(let error):
                self.readStatusLabel.text = "The swap-peer disconnect could not be verified."
                self.showError(error)
            }
        }
    }

    private func runDirectShakescapeOperation<ResultValue>(
        status: String,
        operation: @escaping (RustNativeWallet) throws -> ResultValue,
        completion: @escaping (Result<ResultValue, Error>) -> Void
    ) {
        guard shakedexActionMayStart,
              let wallet,
              let lease = storageLease else {
            showErrorMessage("Unlock and synchronize the direct HNS wallet first.")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let identity = ObjectIdentifier(wallet)
        let authority = walletAuthorityGeneration
        readStatusLabel.text = status
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try operation(wallet) }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: identity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authority,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                completion(result)
                self.refreshButtonStates()
            }
        }
    }

    private func updateDirectShakescapeServiceTimer() {
        let shouldRun = walletAuthorityRequested && shakedexAvailable && walletIsUnlocked
        if !shouldRun {
            directShakescapeServiceTimer?.invalidate()
            directShakescapeServiceTimer = nil
            directShakescapeStatusSnapshot = nil
            return
        }
        guard directShakescapeServiceTimer == nil else { return }
        directShakescapeServiceTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) {
            [weak self] _ in self?.serviceDirectShakescapeOnce()
        }
    }

    private func serviceDirectShakescapeOnce() {
        guard !directShakescapeServiceInFlight,
              !isOperating,
              shakedexAvailable,
              walletAuthorityRequested,
              let wallet else { return }
        directShakescapeServiceInFlight = true
        let identity = ObjectIdentifier(wallet)
        let authority = walletAuthorityGeneration
        DispatchQueue.global(qos: .utility).async {
            _ = try? wallet.serviceDirectShakescape()
            let status = try? wallet.directShakescapeStatus()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.directShakescapeServiceInFlight = false
                guard self.walletAuthorityRequested,
                      self.walletAuthorityGeneration == authority,
                      self.wallet.map({ ObjectIdentifier($0) }) == identity else { return }
                self.directShakescapeStatusSnapshot = status
            }
        }
    }

    private func beginHnsValueAction(_ intent: NativeHnsValueIntent) {
        authenticateWalletAction(
            reason: "Authenticate before preparing this Handshake transaction"
        ) { [weak self] in
            self?.beginHnsValueActionAfterAuthentication(intent)
        }
    }

    private func beginHnsValueActionAfterAuthentication(_ intent: NativeHnsValueIntent) {
        guard hnsValueActionMayStart,
              !intent.requiresShakedex || shakedexAvailable,
              let lease = storageLease,
              let wallet else {
            showErrorMessage("Unlock and synchronize the direct HNS wallet before reviewing this action.")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        let expectedKind = intent.expectedApprovalKind
        readStatusLabel.text = "Synchronizing the direct HNS wallet for native action review…"
        refreshButtonStates()
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<NativeHnsValueApproval, Error> = Result {
                _ = try Self.synchronizeDirectHnsReads(wallet: wallet, keychain: keychain)
                var intentJSON = try intent.encodedBytes()
                let approval = try wallet.prepareHnsValueAction(intentJSON: &intentJSON)
                guard approval.kind == expectedKind else {
                    try? wallet.rejectHnsValueAction(approval.actionToken)
                    try? wallet.lock()
                    throw NativeWalletBridgeError.invalidOutput(
                        "native HNS approval kind changed the requested action"
                    )
                }
                return approval
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else {
                    if case .success(let approval) = outcome {
                        DispatchQueue.global(qos: .userInitiated).async {
                            try? wallet.rejectHnsValueAction(approval.actionToken)
                        }
                    }
                    return
                }
                switch outcome {
                case .success(let approval):
                    self.showHnsValueApproval(
                        approval,
                        lease: lease,
                        generation: generation,
                        walletIdentity: walletIdentity,
                        authorityGeneration: authorityGeneration
                    )
                case .failure(let error):
                    self.isOperating = false
                    self.refreshState()
                    self.readStatusLabel.text =
                        "The native HNS action review could not be prepared. Synchronize before retrying."
                    self.showError(error)
                }
            }
        }
    }

    private func showHnsValueApproval(
        _ approval: NativeHnsValueApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        dismissPendingHnsValueApproval(rejectNatively: true)
        pendingHnsValueApproval = approval
        let date = Date(timeIntervalSince1970: TimeInterval(approval.expiresAtUnix))
        let expiry = DateFormatter.localizedString(
            from: date,
            dateStyle: .medium,
            timeStyle: .medium
        )
        let message = (approval.detailLines + ["Expires: \(expiry)"]).joined(separator: "\n\n")
        let alert = UIAlertController(
            title: approval.title,
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Reject", style: .cancel) { [weak self] _ in
            self?.rejectHnsValueApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        alert.addAction(UIAlertAction(title: "Approve and broadcast", style: .destructive) { [weak self] _ in
            self?.approveHnsValueApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        hnsValueApprovalAlert = alert
        present(alert, animated: true)
    }

    private func approveHnsValueApproval(
        _ approval: NativeHnsValueApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        guard pendingHnsValueApproval?.actionToken === approval.actionToken,
              let wallet else { return }
        pendingHnsValueApproval = nil
        hnsValueApprovalAlert = nil
        readStatusLabel.text = "Executing the approved native HNS action…"
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<(NativeHnsValueResult, NativeHnsReadSnapshot?), Error> = Result {
                let result = try wallet.approveHnsValueActionResult(approval.actionToken)
                let refreshed = try? Self.synchronizeDirectHnsReads(
                    wallet: wallet,
                    keychain: keychain
                )
                return (result, refreshed)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                switch outcome {
                case .success(let result):
                    if let snapshot = result.1 { self.publish(snapshot) }
                    self.readStatusLabel.text = result.1 == nil
                        ? "The native HNS action was accepted. Unlock and synchronize to refresh wallet state."
                        : "The native HNS action was accepted and wallet state was refreshed."
                    self.showNativeHnsResult(title: "HNS action accepted", json: result.0.displayJSON)
                case .failure(let error):
                    self.readStatusLabel.text =
                        "The HNS action outcome is ambiguous. The wallet was locked; unlock and synchronize before another action."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func rejectHnsValueApproval(
        _ approval: NativeHnsValueApproval,
        lease: WalletStorageLeaseToken,
        generation: UInt64,
        walletIdentity: ObjectIdentifier,
        authorityGeneration: UInt64
    ) {
        guard pendingHnsValueApproval?.actionToken === approval.actionToken,
              let wallet else { return }
        pendingHnsValueApproval = nil
        hnsValueApprovalAlert = nil
        DispatchQueue.global(qos: .userInitiated).async {
            let outcome = Result { try wallet.rejectHnsValueAction(approval.actionToken) }
            if case .failure = outcome { try? wallet.lock() }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                switch outcome {
                case .success:
                    self.readStatusLabel.text = "Native HNS action rejected. Nothing was broadcast."
                case .failure(let error):
                    self.readStatusLabel.text = "HNS action rejection could not be verified. The wallet was locked."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func dismissPendingHnsValueApproval(rejectNatively: Bool) {
        let approval = pendingHnsValueApproval
        pendingHnsValueApproval = nil
        hnsValueApprovalAlert?.dismiss(animated: false)
        hnsValueApprovalAlert = nil
        guard let approval else { return }
        if rejectNatively, let wallet {
            DispatchQueue.global(qos: .userInitiated).async {
                if (try? wallet.rejectHnsValueAction(approval.actionToken)) == nil {
                    try? wallet.lock()
                }
            }
        } else {
            approval.actionToken.discard()
        }
        isOperating = false
    }

    private func beginShakedexQuery(_ query: NativeShakedexQuery) {
        guard shakedexActionMayStart,
              let lease = storageLease,
              let wallet else {
            showErrorMessage("Unlock and synchronize the direct HNS wallet before querying Shakedex.")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = "Refreshing direct HNS state for the Shakedex query…"
        refreshButtonStates()
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<NativeShakedexQueryResult, Error> = Result {
                _ = try Self.synchronizeDirectHnsReads(wallet: wallet, keychain: keychain)
                var queryJSON = try query.encodedBytes()
                return try wallet.queryShakedex(queryJSON: &queryJSON)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else { return }
                self.isOperating = false
                self.refreshState()
                switch outcome {
                case .success(let result):
                    self.readStatusLabel.text = "Shakedex query completed through the native wallet."
                    self.showNativeHnsResult(title: "Shakedex result", json: result.displayJSON)
                case .failure(let error):
                    self.readStatusLabel.text = "Shakedex query failed. Synchronize before retrying."
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func showNativeHnsResult(title: String, json: String) {
        let alert = UIAlertController(title: title, message: json, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func showWalletActivity() {
        guard let recentTransactions else {
            let alert = UIAlertController(
                title: "Recent activity",
                message: historyLabel.text,
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "Done", style: .cancel))
            present(alert, animated: true)
            return
        }
        let page = WalletReadPresenter.presentTransactionPage(
            recentTransactions,
            requestedOffset: recentActivityPageOffset
        )
        recentActivityPageOffset = page.offset
        let alert = UIAlertController(
            title: "Recent activity",
            message: page.text,
            preferredStyle: .alert
        )
        if page.hasPrevious {
            alert.addAction(UIAlertAction(title: "Previous", style: .default) { [weak self] _ in
                guard let self else { return }
                self.recentActivityPageOffset -= page.pageSize
                DispatchQueue.main.async { self.showWalletActivity() }
            })
        }
        if page.hasNext {
            alert.addAction(UIAlertAction(title: "Next", style: .default) { [weak self] _ in
                guard let self else { return }
                self.recentActivityPageOffset += page.pageSize
                DispatchQueue.main.async { self.showWalletActivity() }
            })
        }
        alert.addAction(UIAlertAction(title: "Copy activity", style: .default) { _ in
            UIPasteboard.general.setItems(
                [[UTType.plainText.identifier: page.text]],
                options: [.localOnly: true]
            )
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func showWalletManagement() {
        let alert = UIAlertController(
            title: "Wallet",
            message: "\(statusLabel.text ?? "Status unavailable.")\n\n\(accountLabel.text ?? "Account unavailable.")",
            preferredStyle: .alert
        )
        let canStopSynchronization = hnsCatchupRetryPending ||
            WalletHnsSyncPresentationCache.canRequestCancellation(networkID: network.rawValue)
        if canStopSynchronization {
            alert.addAction(UIAlertAction(
                title: "Stop synchronization",
                style: .destructive
            ) { [weak self] _ in
                self?.requestHnsSynchronizationCancellation()
            })
        } else if walletIsUnlocked {
            alert.addAction(UIAlertAction(title: "Lock", style: .default) { [weak self] _ in
                self?.lockWallet()
            })
        } else if openButton.isEnabled {
            alert.addAction(UIAlertAction(title: "Unlock", style: .default) { [weak self] _ in
                self?.openOrUnlockWallet()
            })
        }
        if walletIsUnlocked,
           (try? keychain.hasRecoveryPhrase()) == true {
            alert.addAction(UIAlertAction(title: "View recovery phrase", style: .default) {
                [weak self] _ in self?.showStoredRecoveryPhrase()
            })
        }
        if deleteButton.isEnabled && !canStopSynchronization {
            alert.addAction(UIAlertAction(title: "Delete wallet", style: .destructive) { [weak self] _ in
                self?.requestConfirmedWalletDeletion()
            })
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func createWallet() {
        authenticateWalletAction(
            reason: "Authenticate before generating and protecting a new Handshake wallet"
        ) { [weak self] in
            self?.createWalletAfterAuthentication()
        }
    }

    private func createWalletAfterAuthentication() {
        performWalletOperation {
            guard try self.canStartNewWallet() else { return }
            let path = try self.walletDatabasePath()
            var key = try Self.randomDatabaseKey()
            var keyAdopted = false
            defer {
                if !keyAdopted { WalletSecretBytes.wipe(&key) }
            }

            let controller = try key.withUnsafeBytes { databaseKey in
                try RustNativeWallet.create(
                    databasePath: path,
                    databaseKey: databaseKey,
                    network: self.network,
                    birthdayHeight: self.network.newWalletBirthdayHeight(
                        verifiedHeaderHeight: self.latestObservedBrowserHeaderHeight
                    )
                )
            }
            do {
                let secret = try controller.takeRecoveryPhrase()
                let display = try secret.displayText()
                self.wallet = controller
                self.walletAuthorityGeneration &+= 1
                self.walletWasReopenedFromDurableStorage = false
                self.unconfirmedDatabaseKey = key
                keyAdopted = true
                self.recoverySecret = secret
                self.recoveryTextView.text = display
                self.recoveryTitle.isHidden = false
                self.recoveryTextView.isHidden = false
            } catch {
                controller.close()
                try? Self.deleteWalletFiles(databasePath: path)
                throw error
            }
        }
    }

    @objc private func restoreWallet() {
        let alert = UIAlertController(
            title: "Restore wallet",
            message: "Enter the 24-word phrase and an honest earliest block height (0 scans from genesis).",
            preferredStyle: .alert
        )
        alert.addTextField { [weak self] field in
            field.placeholder = "24-word recovery phrase"
            field.isSecureTextEntry = true
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.restore.phrase"
            self?.restorePhraseField = field
        }
        alert.addTextField { field in
            field.placeholder = "Birthday height"
            field.text = "0"
            field.keyboardType = .numberPad
            field.accessibilityIdentifier = "wallet.restore.birthday"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak self] _ in
            self?.clearRestoreInput()
        })
        alert.addAction(UIAlertAction(title: "Restore", style: .default) { [weak self, weak alert] _ in
            guard let self, let alert else { return }
            var phrase = Array((alert.textFields?.first?.text ?? "").utf8)
            self.clearRestoreInput()
            guard !phrase.isEmpty,
                  phrase.count <= 256,
                  let birthdayText = alert.textFields?.dropFirst().first?.text,
                  let birthdayHeight = UInt64(birthdayText) else {
                WalletSecretBytes.wipe(&phrase)
                self.showErrorMessage("Enter a bounded recovery phrase and a valid birthday height.")
                return
            }
            let recoveryBytes = phrase
            WalletSecretBytes.wipe(&phrase)
            self.authenticateWalletAction(
                reason: "Authenticate before importing and protecting this Handshake wallet"
            ) { [weak self] in
                self?.restoreWalletAfterAuthentication(
                    recoveryBytes: recoveryBytes,
                    birthdayHeight: birthdayHeight
                )
            }
        })
        present(alert, animated: true)
    }

    private func restoreWalletAfterAuthentication(
        recoveryBytes: [UInt8],
        birthdayHeight: UInt64
    ) {
        var phrase = recoveryBytes
        performWalletOperation {
            defer { WalletSecretBytes.wipe(&phrase) }
            guard try self.canStartNewWallet() else { return }
            let path = try self.walletDatabasePath()
            var key = try Self.randomDatabaseKey()
            defer { WalletSecretBytes.wipe(&key) }
            let controller = try key.withUnsafeBytes { databaseKey in
                try phrase.withUnsafeBytes { recoveryPhrase in
                    try RustNativeWallet.restore(
                        databasePath: path,
                        databaseKey: databaseKey,
                        network: self.network,
                        birthdayHeight: birthdayHeight,
                        recoveryPhrase: recoveryPhrase
                    )
                }
            }
            do {
                try key.withUnsafeBytes { databaseKey in
                    try self.keychain.storeDatabaseKey(databaseKey)
                }
                try phrase.withUnsafeBytes { recoveryPhrase in
                    try self.keychain.storeRecoveryPhrase(recoveryPhrase)
                }
                self.wallet = controller
                self.walletAuthorityGeneration &+= 1
                self.walletWasReopenedFromDurableStorage = false
                self.persistentWalletExists = true
            } catch {
                controller.close()
                try? self.keychain.deleteDatabaseKey()
                try? Self.deleteWalletFiles(databasePath: path)
                throw error
            }
        }
    }

    @objc private func openOrUnlockWallet() {
        statusLabel.text = wallet == nil
            ? "Opening your wallet… This may take up to a minute on older devices."
            : "Unlocking your wallet… Please wait."
        performWalletOperation {
            try reconcileIncompleteStorage()
            let path = try walletDatabasePath()
            var reopenedFromDurableStorage = false
            let opened = try keychain.withDatabaseKey(
                prompt: "Authenticate to open your Handshake wallet"
            ) { key -> RustNativeWallet in
                let controller: RustNativeWallet
                if let wallet, walletWasReopenedFromDurableStorage {
                    controller = wallet
                } else {
                    wallet?.close()
                    self.statusLabel.text = "Opening your wallet… This may take up to a minute on older devices."
                    self.refreshButtonStates()
                    controller = try RustNativeWallet.open(
                        databasePath: path,
                        databaseKey: key
                    )
                    reopenedFromDurableStorage = true
                }
                self.statusLabel.text = "Unlocking your wallet… Please wait."
                self.refreshButtonStates()
                try controller.unlock(databaseKey: key)
                return controller
            }
            guard let opened else {
                throw WalletProviderError(
                    code: "walletNotFound",
                    message: "No device-bound wallet key exists. Create or restore a wallet first."
                )
            }
            replaceWallet(
                with: opened,
                reopenedFromDurableStorage: reopenedFromDurableStorage
            )
            persistentWalletExists = true
        }
        beginDirectHnsInstallationIfNeeded()
    }

    @objc private func lockWallet() {
        performWalletOperation {
            guard let wallet, unconfirmedDatabaseKey == nil else { return }
            try wallet.lock()
            clearRecoveryDisplay()
        }
    }

    @objc private func confirmRecoverySaved() {
        guard unconfirmedDatabaseKey != nil, let recoverySecret,
              let phrase = try? recoverySecret.displayText() else { return }
        let words = phrase.split(whereSeparator: \.isWhitespace).map(String.init)
        guard words.count == 24 else {
            showErrorMessage("The generated recovery phrase did not contain exactly 24 words.")
            return
        }
        showRecoveryConfirmationQuestion(words: words, index: 0, hadIncorrectChoice: false)
    }

    private func showRecoveryConfirmationQuestion(
        words: [String],
        index: Int,
        hadIncorrectChoice: Bool
    ) {
        guard index < words.count else {
            if hadIncorrectChoice {
                showErrorMessage(
                    "Recovery phrase verification failed. Review the phrase and retry all 24 words."
                )
            } else {
                persistConfirmedWallet()
            }
            return
        }
        let alert = UIAlertController(
            title: "Verify word \(index + 1) of \(words.count)",
            message: "Tap the word in position \(index + 1). Incorrect choices are reported only after the final word.",
            preferredStyle: .alert
        )
        for choice in walletRecoveryWordChoices(words: words, correctIndex: index) {
            alert.addAction(UIAlertAction(title: choice, style: .default) { [weak self] _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                    self?.showRecoveryConfirmationQuestion(
                        words: words,
                        index: index + 1,
                        hadIncorrectChoice: hadIncorrectChoice || choice != words[index]
                    )
                }
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func persistConfirmedWallet() {
        guard !isOperating else { return }
        guard var key = unconfirmedDatabaseKey else { return }
        unconfirmedDatabaseKey = nil
        performWalletOperation {
            defer { WalletSecretBytes.wipe(&key) }
            do {
                try key.withUnsafeBytes { databaseKey in
                    try keychain.storeDatabaseKey(databaseKey)
                }
                guard let recoverySecret else {
                    throw WalletProviderError(
                        code: "missingRecoveryPhrase",
                        message: "Recovery phrase is unavailable"
                    )
                }
                try recoverySecret.withUnsafeBytes { recoveryPhrase in
                    try keychain.storeRecoveryPhrase(recoveryPhrase)
                }
                persistentWalletExists = true
                clearRecoveryDisplay()
            } catch {
                discardWalletAndFiles()
                throw error
            }
        }
    }

    private func showStoredRecoveryPhrase() {
        do {
            guard var phrase = try keychain.copyRecoveryPhrase(
                prompt: "Authenticate to view your Handshake recovery phrase"
            ) else {
                showErrorMessage(
                    "Recovery phrase display is unavailable for wallets created before protected recovery storage was enabled."
                )
                return
            }
            defer { WalletSecretBytes.wipe(&phrase) }
            guard let text = String(bytes: phrase, encoding: .utf8) else {
                throw WalletProviderError(
                    code: "invalidRecoveryPhrase",
                    message: "Stored recovery phrase is invalid"
                )
            }
            let alert = UIAlertController(
                title: "Recovery phrase",
                message: "Keep this private. Anyone with these words can spend the wallet.\n\n\(text)",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "Done", style: .cancel))
            present(alert, animated: true)
        } catch {
            showError(error)
        }
    }

    private func authenticateWalletAction(
        reason: String,
        authorized: @escaping @MainActor @Sendable () -> Void
    ) {
        guard !walletAuthenticationInProgress else { return }
        let context = LAContext()
        var policyError: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) else {
            showErrorMessage(
                "Set a device passcode and biometric authentication in iOS Settings before using wallet keys."
            )
            return
        }
        walletAuthenticationInProgress = true
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) {
            [weak self] approved, error in
            let errorCode = (error as? LAError)?.code
            let errorMessage = error?.localizedDescription
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.walletAuthenticationInProgress = false
                if approved {
                    authorized()
                } else if errorCode != .userCancel,
                          errorCode != .systemCancel,
                          errorCode != .appCancel {
                    self.showErrorMessage(errorMessage ?? "Wallet authentication failed.")
                }
                self.refreshButtonStates()
            }
        }
    }

    @objc private func refreshWallet() {
        if storageLease != nil,
           (encryptedOrphanCleanupPending || wallet == nil) {
            refreshProtectedStorageState()
        }
        refreshState()
    }

    @objc private func pullToSynchronizeWalletReads() {
        guard walletPullToSyncMayStart(
            hasPresentedViewController: presentedViewController != nil
        ) else {
            walletRefreshControl.endRefreshing()
            return
        }
        let operationWasAlreadyInFlight = isOperating
        synchronizeWalletReads()
        if operationWasAlreadyInFlight || !isOperating {
            walletRefreshControl.endRefreshing()
        }
    }

    @objc private func synchronizeWalletReads() {
        synchronizeWalletReads(resumeAutomaticSync: true)
    }

    private func synchronizeWalletReads(resumeAutomaticSync: Bool) {
        guard let lease = storageLease,
              let wallet,
              unconfirmedDatabaseKey == nil,
              synchronizedReadsAvailable,
              !isOperating else {
            return
        }
        if resumeAutomaticSync {
            WalletHnsSyncPresentationCache.resumeAutomaticSync(networkID: network.rawValue)
            hnsCatchupRetryPending = false
        } else if WalletHnsSyncPresentationCache.automaticSyncIsPaused(
            networkID: network.rawValue
        ) {
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = "Synchronizing direct HNS wallet data…"
        showReadProjectionSynchronizationPendingIfNeeded()
        refreshButtonStates()
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: WalletHnsReadOutcome
            do {
                switch try Self.synchronizeDirectHnsRound(
                        wallet: wallet,
                        keychain: keychain
                ) {
                case .ready(let snapshot): outcome = .success(snapshot)
                case .catchingUp(let progress): outcome = .catchingUp(progress)
                }
            } catch let error as WalletProviderError where error.code == "walletSynchronizationCancelled" {
                outcome = .cancelled
            } catch {
                outcome = .failure(error.localizedDescription)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.walletRefreshControl.endRefreshing()
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else {
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success(let snapshot):
                    self.publish(snapshot)
                case .catchingUp(let progress):
                    self.clearReadProjection()
                    self.renderHnsCatchup(progress)
                    // Keep every value action disabled across the bounded
                    // checkpoint gap. This remains one logical sync until the
                    // next round starts or lifecycle authority is revoked.
                    self.isOperating = true
                    self.hnsCatchupRetryPending = true
                    self.scheduleHnsCatchupRetry(
                        lease: lease,
                        wallet: wallet,
                        generation: generation,
                        authorityGeneration: authorityGeneration
                    )
                case .cancelled:
                    self.readStatusLabel.text = "HNS synchronization stopped. Existing synchronized balances remain available."
                case .failure(let detail):
                    self.readStatusLabel.text = "HNS synchronization did not finish. The direct wallet was locked; unlock before retrying."
                    self.clearReadProjection()
                    self.showErrorMessage(detail)
                }
                WalletHnsSyncPresentationCache.clear(networkID: keychain.networkID)
                self.refreshButtonStates()
            }
        }
    }

    private func renderHnsCatchup(_ progress: NativeHnsCatchupProgress) {
        if progress.scannedHeight == nil,
           progress.headerTipHeight < progress.birthdayHeight {
            readStatusLabel.text = "Verified headers are at height \(progress.headerTipHeight), catching up to wallet birthday \(progress.birthdayHeight). Wallet scanning has not started."
            return
        }
        let scanned = progress.scannedHeight ?? progress.birthdayHeight
        switch progress.headerState {
        case .current:
            readStatusLabel.text = "Verified headers reached \(progress.headerTipHeight). Wallet scan checkpoint \(scanned) of \(progress.targetHeight); continuing automatically."
        case .syncing:
            readStatusLabel.text = "Verifying direct peer headers at \(progress.headerTipHeight). Wallet scan checkpoint \(scanned) of \(progress.targetHeight); continuing automatically."
        case .degraded:
            readStatusLabel.text = "Direct peers checkpointed at verified height \(progress.headerTipHeight). Retrying from the durable checkpoint."
        }
    }

    private func scheduleHnsCatchupRetry(
        lease: WalletStorageLeaseToken,
        wallet: RustNativeWallet,
        generation: UInt64,
        authorityGeneration: UInt64
    ) {
        let walletIdentity = ObjectIdentifier(wallet)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self, weak wallet] in
            guard let self, let wallet,
                  !WalletHnsSyncPresentationCache.automaticSyncIsPaused(
                      networkID: self.network.rawValue
                  ),
                  self.storageLease == lease,
                  self.wallet.map({ ObjectIdentifier($0) }) == walletIdentity,
                  self.readGeneration == generation,
                  self.walletAuthorityGeneration == authorityGeneration,
                  self.walletAuthorityRequested,
                  self.viewIfLoaded?.window != nil,
                  self.isOperating,
                  self.hnsCatchupRetryPending else { return }
            self.hnsCatchupRetryPending = false
            self.isOperating = false
            self.synchronizeWalletReads(resumeAutomaticSync: false)
        }
    }

    /// Runs one direct-peer synchronization while holding the platform's
    /// monotonic floor journal.  The commit deliberately occurs even when the
    /// native bounded round returns an error: a round can safely persist newer
    /// headers before a later peer, proof, or scan step reports not-ready.
    /// Leaving the journal pending in that case would make a subsequent open
    /// unable to distinguish an interrupted safe checkpoint from rollback.
    nonisolated private static func synchronizeDirectHnsReads(
        wallet: RustNativeWallet,
        keychain: WalletKeychainStore
    ) throws -> NativeHnsReadSnapshot {
        try readyHnsSnapshot(
            from: synchronizeDirectHnsRound(wallet: wallet, keychain: keychain)
        )
    }

    nonisolated private static func readyHnsSnapshot(
        from synchronization: NativeHnsSynchronization
    ) throws -> NativeHnsReadSnapshot {
        switch synchronization {
        case .ready(let snapshot):
            return snapshot
        case .catchingUp(let progress):
            throw WalletProviderError(
                code: "walletSynchronizationCatchingUp",
                message: "HNS wallet synchronization checkpointed at \(progress.scannedHeight ?? progress.birthdayHeight) of \(progress.targetHeight)"
            )
        }
    }

    nonisolated private static func synchronizeDirectHnsRound(
        wallet: RustNativeWallet,
        keychain: WalletKeychainStore
    ) throws -> NativeHnsSynchronization {
        return try withDirectHnsFloorJournal(wallet: wallet, keychain: keychain) {
            let presentationLease = WalletHnsSyncPresentationCache.begin(
                networkID: keychain.networkID,
                requestCancellation: { [wallet] in
                    try? wallet.cancelHnsSynchronization()
                }
            )
            let progressPoller = WalletHnsSyncProgressPoller(
                wallet: wallet,
                lease: presentationLease
            )
            defer {
                if let finalProgress = try? wallet.hnsSynchronizationProgress() {
                    WalletHnsSyncPresentationCache.publish(
                        finalProgress,
                        lease: presentationLease
                    )
                }
                progressPoller.stop()
                WalletHnsSyncPresentationCache.finish(lease: presentationLease)
            }
            guard !presentationLease.wasCancellationRequested else {
                throw WalletProviderError(
                    code: "walletSynchronizationCancelled",
                    message: "HNS synchronization stopped before its native scan began"
                )
            }
            do {
                return try wallet.synchronizeHnsReads()
            } catch where presentationLease.wasCancellationRequested {
                throw WalletProviderError(
                    code: "walletSynchronizationCancelled",
                    message: "HNS synchronization stopped at a safe native checkpoint"
                )
            }
        }
    }

    /// Applies the same interruption-safe floor discipline to any native
    /// operation that can advance direct peer/header authority.  Exact-name
    /// imports resolve and validate a proof through the direct coordinator, so
    /// they must not be allowed to move it outside this journal either.
    nonisolated private static func withDirectHnsFloorJournal<T>(
        wallet: RustNativeWallet,
        keychain: WalletKeychainStore,
        work: () throws -> T
    ) throws -> T {
        guard try wallet.hasHnsValue() else {
            return try work()
        }
        do {
            try keychain.beginDirectHnsSynchronization()
        } catch {
            // A previous app interruption left a durable pending marker.  The
            // active coordinator was opened under the committed floor, so an
            // equal-or-newer local floor can only heal that marker.
            var recoveredFloor = try wallet.directHnsRollbackFloor()
            defer { WalletSecretBytes.wipe(&recoveredFloor) }
            try keychain.commitDirectHnsSynchronization(recoveredFloor)
            try keychain.beginDirectHnsSynchronization()
        }

        let result = Result { try work() }
        do {
            var updatedFloor = try wallet.directHnsRollbackFloor()
            defer { WalletSecretBytes.wipe(&updatedFloor) }
            try keychain.commitDirectHnsSynchronization(updatedFloor)
        } catch {
            // A missing or backward floor is ambiguous chain authority.
            // Native lock drops any pending value action before this error can
            // return to UIKit.
            try? wallet.lock()
            throw error
        }
        return try result.get()
    }

    @objc private func requestExactHnsNameImport() {
        let current = currentWalletNameImportState()
        guard presentedViewController == nil,
              let expected = current.authority,
              walletNameImportMayStart(expected: expected, current: current) else {
            nameImportStatusLabel.text =
                "Exact-name tracking requires the current reopened, unlocked, read-configured wallet in protected foreground access."
            return
        }

        let alert = UIAlertController(
            title: "Track exact HNS name",
            message: "Enter one canonical Handshake name exactly as stored on chain. The app will not trim, lowercase, normalize, apply IDNA, or remove a trailing dot.",
            preferredStyle: .alert
        )
        alert.addTextField { [weak self] field in
            configureWalletNameImportTextField(field)
            self?.walletNameImportField = field
        }
        walletNameImportAlert = alert
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) {
            [weak self, weak alert] _ in
            alert?.textFields?.first?.text = nil
            self?.clearWalletNameImportPrompt(dismiss: false)
        })
        alert.addAction(UIAlertAction(title: "Track name", style: .default) {
            [weak self, weak alert] _ in
            let input = WalletExactHnsNameInput(
                exactText: alert?.textFields?.first?.text
            )
            alert?.textFields?.first?.text = nil
            guard let self else { return }
            self.clearWalletNameImportPrompt(dismiss: false)
            guard let input else {
                self.nameImportStatusLabel.text =
                    "Enter a nonempty HNS name no longer than 63 UTF-8 bytes. The text is used exactly as entered."
                return
            }
            let rechecked = self.currentWalletNameImportState()
            guard walletNameImportMayStart(
                expected: expected,
                current: rechecked
            ) else {
                self.nameImportStatusLabel.text =
                    "Wallet authority changed while the prompt was open. No name was tracked."
                return
            }
            self.beginExactHnsNameImport(input: input, authority: expected)
        })
        present(alert, animated: true)
    }

    private func beginExactHnsNameImport(
        input: WalletExactHnsNameInput,
        authority: WalletNameImportAuthority
    ) {
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current),
              let wallet,
              let lease = storageLease else {
            nameImportStatusLabel.text =
                "Wallet authority changed before name tracking began. No name was tracked."
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        nameImportStatusLabel.text =
            "Tracking one exact HNS name and refreshing synchronized wallet rows…"
        refreshButtonStates()
        let keychain = keychain

        DispatchQueue.global(qos: .userInitiated).async { [wallet, input, keychain] in
            let outcome: WalletHnsNameImportOutcome
            do {
                do {
                    let (imported, refreshed) = try Self.withDirectHnsFloorJournal(
                        wallet: wallet,
                        keychain: keychain
                    ) {
                        let imported = try input.consume { exactBytes in
                            try wallet.importHnsNameExactText(&exactBytes)
                        }
                        let refreshed = try Self.readyHnsSnapshot(
                            from: wallet.synchronizeHnsReads()
                        )
                        return (imported, refreshed)
                    }
                    guard walletNameImportRefreshMatches(
                        imported: imported,
                        refreshed: refreshed
                    ) else {
                        throw NativeWalletBridgeError.invalidOutput(
                            "fresh wallet rows do not contain the imported name identity"
                        )
                    }
                    outcome = .success(imported, refreshed)
                } catch {
                    try? wallet.lock()
                    outcome = .successRefreshFailed(error.localizedDescription)
                }
            } catch {
                if walletNameImportFailureIsNonPoisoningInvalid(error) {
                    outcome = .invalidInput
                } else {
                    try? wallet.lock()
                    outcome = .failure(error.localizedDescription)
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      walletNameImportCompletionMayApply(
                          expected: authority,
                          current: self.currentWalletNameImportAuthority(),
                          expectedGeneration: generation,
                          currentGeneration: self.readGeneration,
                          expectedLease: lease,
                          currentLease: self.storageLease,
                          lifecycleAllowsImport: self.walletLifecycleMayAcquireStorage,
                          viewIsCurrent: self.walletAuthorityRequested &&
                              self.viewIfLoaded?.window != nil,
                          operationInFlight: self.isOperating
                      ) else {
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success(let summary, let snapshot):
                    self.publish(snapshot)
                    self.nameImportStatusLabel.text =
                        "Imported and refreshed:\n\(WalletReadPresenter.presentName(summary))"
                case .successRefreshFailed(let detail):
                    self.refreshState()
                    self.readStatusLabel.text =
                        "The required post-import refresh failed closed. Reopen and unlock before retrying."
                    self.clearReadProjection()
                    self.nameImportStatusLabel.text =
                        "The import result could not be confirmed in fresh wallet rows. The wallet was locked and no imported row was published. \(detail)"
                case .invalidInput:
                    self.nameImportStatusLabel.text =
                        "The exact text is not one canonical Handshake name. Nothing was imported and the wallet remains usable."
                case .failure(let detail):
                    self.refreshState()
                    self.nameImportStatusLabel.text =
                        "Name import failed closed. Reopen and unlock the wallet before retrying. \(detail)"
                }
                self.refreshButtonStates()
            }
        }
    }

    private func requestMultipleHnsNameImport() {
        let current = currentWalletNameImportState()
        guard presentedViewController == nil,
              let expected = current.authority,
              walletNameImportMayStart(expected: expected, current: current) else {
            nameImportStatusLabel.text =
                "Multiple-name import requires the current reopened, unlocked, read-configured wallet in protected foreground access."
            return
        }
        let editor = WalletMultipleNameImportEditorViewController { [weak self] names in
            self?.showMultipleHnsNameImportReview(names: names, authority: expected)
        }
        present(UINavigationController(rootViewController: editor), animated: true)
    }

    private func showMultipleHnsNameImportReview(
        names: [String],
        authority: WalletNameImportAuthority
    ) {
        guard presentedViewController == nil else { return }
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current) else {
            nameImportStatusLabel.text =
                "Wallet authority changed before the import review. No names were imported."
            return
        }
        let review = WalletMultipleNameImportReviewViewController(names: names) { [weak self] in
            guard let self else { return }
            let rechecked = self.currentWalletNameImportState()
            guard walletNameImportMayStart(expected: authority, current: rechecked) else {
                self.nameImportStatusLabel.text =
                    "Wallet authority changed while the review was open. No names were imported."
                return
            }
            self.beginMultipleHnsNameImport(names: names, authority: authority)
        }
        present(UINavigationController(rootViewController: review), animated: true)
    }

    private func beginMultipleHnsNameImport(
        names: [String],
        authority: WalletNameImportAuthority
    ) {
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current),
              let wallet,
              let lease = storageLease else {
            nameImportStatusLabel.text =
                "Wallet authority changed before multiple-name import began. No names were imported."
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        nameImportStatusLabel.text =
            "Importing \(names.count) exact HNS names atomically and refreshing synchronized wallet rows…"
        refreshButtonStates()
        let keychain = keychain

        DispatchQueue.global(qos: .userInitiated).async { [wallet, names, keychain] in
            let outcome: WalletHnsBulkNameImportOutcome
            do {
                let importedCount = try Self.withDirectHnsFloorJournal(
                    wallet: wallet,
                    keychain: keychain
                ) {
                    try wallet.importHnsNamesExactText(names)
                }
                do {
                    let refreshed = try Self.withDirectHnsFloorJournal(
                        wallet: wallet,
                        keychain: keychain
                    ) {
                        try Self.readyHnsSnapshot(from: wallet.synchronizeHnsReads())
                    }
                    guard importedCount == names.count,
                          refreshed.knownNameCount >= importedCount else {
                        throw NativeWalletBridgeError.invalidOutput(
                            "fresh wallet rows report fewer names than the atomic import count"
                        )
                    }
                    outcome = .success(importedCount, refreshed)
                } catch {
                    try? wallet.lock()
                    outcome = .successRefreshFailed(error.localizedDescription)
                }
            } catch {
                if walletNameImportFailureIsNonPoisoningInvalid(error) {
                    outcome = .invalidInput
                } else {
                    try? wallet.lock()
                    outcome = .failure(error.localizedDescription)
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      walletNameImportCompletionMayApply(
                          expected: authority,
                          current: self.currentWalletNameImportAuthority(),
                          expectedGeneration: generation,
                          currentGeneration: self.readGeneration,
                          expectedLease: lease,
                          currentLease: self.storageLease,
                          lifecycleAllowsImport: self.walletLifecycleMayAcquireStorage,
                          viewIsCurrent: self.walletAuthorityRequested &&
                              self.viewIfLoaded?.window != nil,
                          operationInFlight: self.isOperating
                      ) else {
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success(let count, let snapshot):
                    self.publish(snapshot)
                    self.nameImportStatusLabel.text =
                        "Imported and refreshed \(count) exact HNS names."
                case .successRefreshFailed(let detail):
                    self.refreshState()
                    self.readStatusLabel.text =
                        "The required post-import refresh failed closed. Reopen and unlock before retrying."
                    self.clearReadProjection()
                    self.nameImportStatusLabel.text =
                        "The bulk import result could not be confirmed in fresh wallet rows. The wallet was locked and no imported rows were published. \(detail)"
                case .invalidInput:
                    self.nameImportStatusLabel.text =
                        "The reviewed text is not a unique list of canonical Handshake names. Nothing was imported and the wallet remains usable."
                case .failure(let detail):
                    self.refreshState()
                    self.nameImportStatusLabel.text =
                        "Multiple-name import failed closed. Reopen and unlock the wallet before retrying. \(detail)"
                }
                self.refreshButtonStates()
            }
        }
    }

    @objc private func requestConfirmedWalletDeletion() {
        guard presentedViewController == nil else { return }
        if hnsCatchupRetryPending || WalletHnsSyncPresentationCache.canRequestCancellation(
            networkID: network.rawValue
        ) {
            requestHnsSynchronizationCancellation()
            return
        }
        do {
            let authority = try currentConfirmedDeletionAuthority()
            let alert = UIAlertController(
                title: "Delete \(network.title) wallet?",
                message: """
                \(walletDeletionIdentitySummary(authority))

                This permanently deletes this device's confirmed wallet, including its protected recovery-phrase copy. Without a separately saved recovery phrase, the wallet cannot be recovered. This action is irreversible.
                """,
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            alert.addAction(UIAlertAction(title: "Continue", style: .destructive) { [weak self] _ in
                self?.presentTypedDeletionConfirmation(expected: authority)
            })
            present(alert, animated: true)
        } catch {
            showError(error)
        }
    }

    private func requestHnsSynchronizationCancellation() {
        let alert = UIAlertController(
            title: "Stop synchronization?",
            message: """
            Stop the active HNS synchronization now? No additional synchronization batch will start. An atomic peer or database operation already in progress will unwind without discarding the last completed durable checkpoint. No wallet data will be deleted.

            After the old controller releases protected storage, the normal wallet controls will return automatically. You can unlock, synchronize again, or delete the wallet normally.
            """,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Keep synchronizing", style: .cancel))
        alert.addAction(UIAlertAction(
            title: "Stop sync",
            style: .destructive
        ) { [weak self] _ in
            guard let self else { return }
            if self.hnsCatchupRetryPending {
                self.hnsCatchupRetryPending = false
                self.isOperating = false
                WalletHnsSyncPresentationCache.pauseAutomaticSync(
                    networkID: self.network.rawValue
                )
                self.readStatusLabel.text =
                    "HNS synchronization stopped at the last durable checkpoint."
            } else {
                WalletHnsSyncPresentationCache.requestCancellation(
                    networkID: self.network.rawValue
                )
            }
            self.refreshState()
        })
        present(alert, animated: true)
    }

    private func presentTypedDeletionConfirmation(
        expected authority: WalletConfirmedDeletionAuthority
    ) {
        do {
            _ = try currentConfirmedDeletionAuthority(matching: authority)
        } catch {
            showError(error)
            return
        }

        let alert = UIAlertController(
            title: "Type DELETE to confirm",
            message: """
            \(walletDeletionIdentitySummary(authority))

            Deletion is irreversible and erases the protected recovery-phrase copy on this device. Type DELETE exactly to continue.
            """,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "DELETE"
            field.autocapitalizationType = .allCharacters
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.delete-confirmation"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { [weak alert] _ in
            alert?.textFields?.first?.text = nil
        })
        alert.addAction(UIAlertAction(
            title: "Delete forever",
            style: .destructive
        ) { [weak self, weak alert] _ in
            let typedValue = alert?.textFields?.first?.text
            alert?.textFields?.first?.text = nil
            guard walletDeletionConfirmationMatches(typedValue) else {
                self?.showErrorMessage("Type DELETE exactly. The wallet was not deleted.")
                return
            }
            guard let self else { return }
            do {
                let current = try self.currentConfirmedDeletionAuthority(
                    matching: authority
                )
                self.beginConfirmedWalletDeletion(authority: current)
            } catch {
                self.showError(error)
            }
        })
        present(alert, animated: true)
    }

    private func currentConfirmedDeletionAuthority(
        matching expected: WalletConfirmedDeletionAuthority? = nil
    ) throws -> WalletConfirmedDeletionAuthority {
        guard walletLifecycleMayAcquireStorage,
              protectedStorageIsAvailable,
              viewIfLoaded?.window != nil,
              !isOperating,
              !retirementInFlight,
              unconfirmedDatabaseKey == nil,
              recoverySecret == nil,
              persistentWalletExists,
              let path = resolvedDatabasePath,
              let lease = storageLease,
              lease.path == path,
              walletDatabasePathMatchesNetworkNamespace(path, network: network),
              WalletStorageLeaseRegistry.isCurrent(lease),
              let wallet else {
            throw WalletProviderError(
                code: "walletDeletionUnavailable",
                message: "Only the current protected foreground owner can delete a confirmed wallet."
            )
        }
        guard FileManager.default.fileExists(atPath: path),
              try keychain.hasDatabaseKey() else {
            throw WalletProviderError(
                code: "walletDeletionStorageMismatch",
                message: "Confirmed wallet storage is incomplete. Reopen the wallet screen to reconcile it before retrying."
            )
        }

        let hasHnsReads = try wallet.hasHnsReads()
        let status = try wallet.status()
        let enabledModulesAreAllowed = hasHnsReads
            ? status.enabledModules == ["handshake"]
            : status.enabledModules.isEmpty
        guard !status.locked,
              enabledModulesAreAllowed,
              !status.mainnetSettlementEnabled,
              status.activeWallet?.isEmpty == false else {
            throw WalletProviderError(
                code: "walletDeletionLocked",
                message: "Unlock the local Handshake wallet before deleting it."
            )
        }
        let accounts = try wallet.accounts()
        guard accounts.count == 1,
              let account = accounts.first,
              account.module == "handshake",
              account.receiveDisplay == nil,
              walletAccountIDIsCanonical(account.accountId) else {
            throw NativeWalletBridgeError.invalidOutput(
                "confirmed deletion requires one exact local HNS account"
            )
        }

        let current = WalletConfirmedDeletionAuthority(
            network: network,
            accountID: account.accountId,
            databasePath: path,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: walletAuthorityGeneration
        )
        if let expected,
           !walletConfirmedDeletionMayProceed(
               expected: expected,
               current: current,
               lifecycleAllowsDeletion: walletLifecycleMayAcquireStorage,
               viewIsCurrent: viewIfLoaded?.window != nil,
               operationInFlight: isOperating || retirementInFlight,
               screenIsCaptured: Self.screenCaptureProtectionActive
           ) {
            throw WalletProviderError(
                code: "walletDeletionAuthorityChanged",
                message: "Wallet ownership changed during confirmation. The wallet was not deleted."
            )
        }
        return current
    }

    private func beginConfirmedWalletDeletion(
        authority: WalletConfirmedDeletionAuthority
    ) {
        // Revoke every read callback before moving the controller and lease to
        // the serialized retirement worker. No UI-owned wallet authority
        // survives this point.
        invalidateReadOperation()
        guard let currentWallet = wallet,
              ObjectIdentifier(currentWallet) == authority.walletIdentity,
              storageLease == authority.lease,
              walletAuthorityGeneration == authority.ownerGeneration,
              walletLifecycleMayAcquireStorage,
              protectedStorageIsAvailable,
              viewIfLoaded?.window != nil,
              !isOperating,
              !retirementInFlight,
              !Self.screenCaptureProtectionActive,
              WalletStorageLeaseRegistry.isCurrent(authority.lease) else {
            showErrorMessage("Wallet ownership changed. The wallet was not deleted.")
            return
        }

        clearRecoveryDisplay()
        wallet = nil
        walletWasReopenedFromDurableStorage = false
        storageLease = nil
        confirmedDeletionAccountID = nil
        protectedStorageIsAvailable = false
        persistentWalletExists = false
        walletAuthorityGeneration &+= 1
        let detachedAuthorityGeneration = walletAuthorityGeneration
        retirementGeneration &+= 1
        let generation = retirementGeneration
        retirementInFlight = true

        let plan = WalletConfirmedDeletionPlan(
            authority: authority,
            wallet: currentWallet,
            keychain: keychain,
            deleteWalletFiles: {
                try Self.deleteWalletFiles(databasePath: authority.databasePath)
            }
        )
        refreshState()
        WalletRetirementQueue.shared.enqueue(plan) { [weak self] outcome in
            guard let self,
                  walletDeletionCompletionMayApply(
                      expectedRetirementGeneration: generation,
                      currentRetirementGeneration: self.retirementGeneration,
                      expectedDetachedAuthorityGeneration: detachedAuthorityGeneration,
                      currentAuthorityGeneration: self.walletAuthorityGeneration,
                      walletIsDetached: self.wallet == nil,
                      leaseIsDetached: self.storageLease == nil
                  ) else {
                return
            }
            self.retirementInFlight = false
            switch outcome {
            case .deleted:
                self.encryptedOrphanCleanupPending = false
                self.persistentWalletExists = false
                WalletHnsSyncPresentationCache.resumeAutomaticSync(
                    networkID: self.network.rawValue
                )
                WalletPendingOutgoingRecoveryStore.clear(networkID: self.network.rawValue)
            case .controllerCloseFailed:
                self.encryptedOrphanCleanupPending = false
                // The key and files remain, but native controller retirement
                // is ambiguous. Never advertise this namespace as ready.
                self.persistentWalletExists = false
            case .keyDeletionFailed, .authorityRevoked:
                self.encryptedOrphanCleanupPending = false
                self.persistentWalletExists = true
            case .encryptedOrphanCleanupPending:
                self.encryptedOrphanCleanupPending = true
                self.persistentWalletExists = false
            }
            self.resumeWalletLifecycle()
            let encryptedOrphanRemains = self.encryptedOrphanCleanupPending
            guard self.walletAuthorityRequested,
                  self.viewIfLoaded?.window != nil,
                  let resumedLease = self.storageLease,
                  resumedLease.path == authority.databasePath,
                  WalletStorageLeaseRegistry.isCurrent(resumedLease) else {
                return
            }
            switch outcome {
            case .deleted:
                break
            case .controllerCloseFailed:
                self.showErrorMessage("The native wallet could not be retired, so its key and files were not deleted. Reopen the wallet screen before retrying.")
            case .keyDeletionFailed:
                self.showErrorMessage("The wallet key could not be deleted, so no wallet files were removed. Unlock and retry deletion.")
            case .authorityRevoked:
                self.showErrorMessage("Wallet ownership changed before deletion. No wallet key or files were removed.")
            case .encryptedOrphanCleanupPending where encryptedOrphanRemains:
                self.showErrorMessage("The wallet key was deleted, but encrypted wallet files still need cleanup. Use Refresh status or return to this screen to retry.")
            case .encryptedOrphanCleanupPending:
                break
            }
        }
    }

    private func publish(_ snapshot: NativeHnsReadSnapshot) {
        let presentation = WalletReadPresenter.present(snapshot)
        let balance = WalletHnsBalancePresenter.present(snapshot)
        latestReadSnapshot = snapshot
        latestPublishedSnapshotHeight = snapshot.moduleStatus.validatedHeight
        if balance.hasPendingOutgoing {
            pendingOutgoingSnapshotHeight = snapshot.moduleStatus.validatedHeight
            if let accountID = confirmedDeletionAccountID {
                WalletPendingOutgoingRecoveryStore.save(
                    networkID: network.rawValue,
                    accountID: accountID,
                    height: snapshot.moduleStatus.validatedHeight
                )
            }
        } else {
            pendingOutgoingSnapshotHeight = nil
            pendingOutgoingRefreshAttemptedHeight = nil
            WalletPendingOutgoingRecoveryStore.clear(networkID: network.rawValue)
        }
        recentTransactions = snapshot.transactionHistory
        finalizeNotices = snapshot.finalizeNotices
        recentActivityPageOffset = 0
        receiveTargets = WalletReceiveTargets(snapshot: snapshot)
        readStatusLabel.text = presentation.status
        balanceLabel.text = presentation.balance
        paymentReceiveLabel.text = presentation.paymentReceive
        nameReceiveLabel.text = presentation.nameReceive
        historyLabel.text = presentation.history
        namesLabel.text = presentation.names
        namesGalleryViewController?.update(
            names: snapshot.knownNames,
            totalNameCount: snapshot.knownNameCount,
            snapshotHeight: snapshot.moduleStatus.validatedHeight,
            actionsAvailable: !isOperating
        )
        if let gallery = namesGalleryViewController {
            loadCompleteNameGallery(snapshot: snapshot, into: gallery)
        }
        maybeRefreshPendingOutgoingAfterNewBlock()
    }

    private func startPendingOutgoingRefreshObserver() {
        guard browserSyncObservation == nil, let browserProcess else { return }
        browserSyncObservation = browserProcess.observeSync { [weak self] summary in
            guard let self,
                  summary.network == self.network.rawValue,
                  summary.hasAuthoritativeCurrentness,
                  let height = summary.bestHeight else { return }
            self.latestObservedBrowserHeaderHeight = max(
                self.latestObservedBrowserHeaderHeight ?? 0,
                height
            )
            self.maybeRefreshPendingOutgoingAfterNewBlock()
        }
    }

    private func stopPendingOutgoingRefreshObserver() {
        guard let browserSyncObservation else { return }
        browserProcess?.removeSyncObserver(browserSyncObservation)
        self.browserSyncObservation = nil
    }

    private func maybeRefreshPendingOutgoingAfterNewBlock() {
        guard let refreshHeight = walletPendingOutgoingRefreshHeight(
            pendingSnapshotHeight: pendingOutgoingSnapshotHeight,
            observedHeaderHeight: latestObservedBrowserHeaderHeight,
            attemptedHeaderHeight: pendingOutgoingRefreshAttemptedHeight
        ),
        walletAuthorityRequested,
        viewIfLoaded?.window != nil,
        storageLease != nil,
        wallet != nil,
        walletIsUnlocked,
        synchronizedReadsAvailable,
        !isOperating else { return }
        pendingOutgoingRefreshAttemptedHeight = refreshHeight
        readStatusLabel.text = "A new Handshake block was detected. Refreshing the pending transaction…"
        synchronizeWalletReads(resumeAutomaticSync: false)
    }

    private func clearReadProjection() {
        nameGalleryLoadGeneration &+= 1
        if let gallery = namesGalleryViewController,
           let navigationController,
           navigationController.viewControllers.contains(where: { $0 === gallery }) {
            navigationController.popToViewController(self, animated: false)
        }
        namesGalleryViewController = nil
        latestReadSnapshot = nil
        receiveTargets = nil
        recentTransactions = nil
        finalizeNotices = []
        recentActivityPageOffset = 0
        balanceLabel.text = "Confirmed spendable balance: unavailable."
        paymentReceiveLabel.text = "Payment receive address: unavailable."
        nameReceiveLabel.text = "Name transfer receive address: unavailable."
        historyLabel.text = "Transaction history: unavailable."
        namesLabel.text = "Tracked names: unavailable."
        if pendingOutgoingSnapshotHeight != nil {
            readStatusLabel.text = "Transaction pending. Synchronize after a new Handshake block so Shakescape can settle the outgoing transaction."
            balanceLabel.text = "Transaction pending. The synchronized balance will return after the outgoing transaction is settled."
        }
    }

    private func formatFinalizeNotice(_ notice: NativeHnsReadSnapshot.FinalizeNotice) -> String {
        switch notice.phase {
        case "transferPending":
            return "\(notice.name): TRANSFER submitted and awaiting confirmation. Transaction \(notice.transactionID). Current block \(notice.currentHeight)."
        case "finalizeWaiting":
            return "\(notice.name): TRANSFER confirmed. FINALIZE remains pending. Current block \(notice.currentHeight); FINALIZE becomes available at block \(notice.finalizeEligibleHeight ?? 0). Transfer transaction \(notice.transactionID)."
        case "finalizeAvailable":
            return "\(notice.name): FINALIZE is available now. Current block \(notice.currentHeight); eligible since block \(notice.finalizeEligibleHeight ?? 0). This notice remains until FINALIZE is confirmed. Transfer transaction \(notice.transactionID)."
        case "finalizePending":
            return "\(notice.name): FINALIZE submitted and awaiting confirmation. Transaction \(notice.transactionID). Current block \(notice.currentHeight). This notice remains until completion is verified."
        default:
            assertionFailure("closed native finalize notice phase")
            return ""
        }
    }

    /// Keep an existing authenticated projection visible during refresh. For
    /// a wallet that has never published one, describe the active work instead
    /// of leaving an "unavailable" placeholder beside live sync progress.
    private func showReadProjectionSynchronizationPendingIfNeeded() {
        guard recentTransactions == nil else { return }
        balanceLabel.text = "Confirmed spendable balance: waiting for active direct-peer verification and wallet scanning."
    }

    private func replaceWallet(
        with controller: RustNativeWallet,
        reopenedFromDurableStorage: Bool
    ) {
        guard wallet !== controller else { return }
        dismissPendingHnsSendApproval(rejectNatively: true)
        dismissPendingHnsValueApproval(rejectNatively: true)
        pendingBitcoinSendApproval?.actionToken.discard()
        pendingBitcoinSendApproval = nil
        bitcoinSendApprovalAlert?.dismiss(animated: false)
        pendingBtcForHnsOfferApproval?.actionToken.discard()
        pendingBtcForHnsOfferApproval = nil
        btcForHnsOfferApprovalAlert?.dismiss(animated: false)
        pendingBtcForHnsFundingApproval?.actionToken.discard()
        pendingBtcForHnsFundingApproval = nil
        btcForHnsFundingApprovalAlert?.dismiss(animated: false)
        pendingHnsForBtcFundingApproval?.actionToken.discard()
        pendingHnsForBtcFundingApproval = nil
        swapSettlementApprovalAlert?.dismiss(animated: false)
        swapSettlementApprovalAlert = nil
        pendingSwapSettlementApproval?.actionToken.discard()
        pendingSwapSettlementApproval = nil
        hnsForBtcFundingApprovalAlert?.dismiss(animated: false)
        bitcoinSyncTimer?.invalidate()
        bitcoinSyncTimer = nil
        bitcoinSyncInProgress = false
        bitcoinSyncStopRequested = false
        bitcoinBirthdayResetInProgress = false
        bitcoinValueAvailable = false
        bitcoinSnapshot = nil
        bitcoinBirthdayButton.isHidden = true
        try? wallet?.lock()
        wallet?.close()
        wallet = controller
        walletAuthorityGeneration &+= 1
        walletWasReopenedFromDurableStorage = reopenedFromDurableStorage
        clearWalletNameImportPrompt(dismiss: true)
    }

    /// Configures a freshly reopened durable wallet with its own direct HNS
    /// peers.  Unlike the historic loopback-read path, this has no companion
    /// credential or endpoint.  Mainnet uses the product-pinned header stream
    /// only for the checkpoint-born wallet birthday; Rust independently pins
    /// and validates every byte before it replaces the lifecycle controller.
    private func beginDirectHnsInstallationIfNeeded() {
        guard !isOperating,
              let authority = currentWalletReadBootstrapAuthority(),
              walletReadBootstrapMayInstall(
                  expected: authority,
                  current: currentWalletReadBootstrapState()
              ),
              let wallet else {
            return
        }
        let alreadyInstalled = ((try? wallet.hasHnsReads()) == true) ||
            ((try? wallet.hasHnsValue()) == true)
        guard !alreadyInstalled else { return }

        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let expectedWalletIdentity = ObjectIdentifier(wallet)
        let expectedAuthorityGeneration = walletAuthorityGeneration
        let expectedLease = authority.lease
        statusLabel.text = "Preparing the direct HNS wallet… Please wait."
        readStatusLabel.text = "Preparing the direct HNS wallet… Please wait."
        refreshButtonStates()

        let keychain = keychain
        let installNetwork = network
        let browserRuntime = browserProcess?.preparedRuntimeForWalletBootstrap()
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain, browserRuntime] in
            let outcome: Result<Void, Error> = Result {
                let install: (String?) throws -> Void = { snapshotPath in
                    var openingFloor = try keychain.directHnsRollbackFloorForOpen()
                    defer { WalletSecretBytes.wipe(&openingFloor) }
                    let configured = try keychain.withDatabaseKey(
                        prompt: "Authenticate to enable your direct HNS wallet"
                    ) { databaseKey in
                        try wallet.configureDirectHnsValue(
                            databaseKey: databaseKey,
                            rollbackFloor: &openingFloor,
                            bootstrapSnapshotPath: snapshotPath
                        )
                    }
                    guard configured != nil else {
                        throw WalletProviderError(
                            code: "walletKeyUnavailable",
                            message: "The device-bound wallet key is unavailable."
                        )
                    }
                }

                if installNetwork == .mainnet {
                    do {
                        // Existing wallets open from their persisted,
                        // rollback-fenced birthday checkpoint without asking
                        // the browser to export or the wallet to replay data.
                        try install(nil)
                    } catch {
                        let birthdayHeight = try wallet.birthdayHeight()
                        try WalletHeaderSnapshotBootstrapper().withBirthdaySnapshot(
                            runtime: browserRuntime,
                            birthdayHeight: birthdayHeight
                        ) { segment in
                            guard let segment else { throw error }
                            try install(segment.path)
                        }
                    }
                } else {
                    try install(nil)
                }
                var installedFloor = try wallet.directHnsRollbackFloor()
                defer { WalletSecretBytes.wipe(&installedFloor) }
                try keychain.storeInitialDirectHnsRollbackFloor(installedFloor)
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: expectedLease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: expectedWalletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: expectedAuthorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else {
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success:
                    self.readStatusLabel.text = "Direct HNS wallet is ready. Synchronize before viewing a balance or sending."
                case .failure(let error):
                    self.readStatusLabel.text = "Direct HNS wallet setup failed. Unlock and try again."
                    self.showError(error)
                }
                self.refreshState()
            }
        }
    }

    private func currentWalletReadBootstrapAuthority() -> WalletReadBootstrapAuthority? {
        guard persistentWalletExists,
              unconfirmedDatabaseKey == nil,
              let wallet,
              let databasePath = resolvedDatabasePath,
              let lease = storageLease else {
            return nil
        }
        return WalletReadBootstrapAuthority(
            network: network,
            databasePath: databasePath,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: walletAuthorityGeneration
        )
    }

    private func currentWalletReadBootstrapState() -> WalletReadBootstrapState {
        WalletReadBootstrapState(
            authority: currentWalletReadBootstrapAuthority(),
            reopenedDurableConfirmedWallet:
                walletWasReopenedFromDurableStorage,
            protectedStorageIsAvailable: protectedStorageIsAvailable,
            lifecycleAllowsBootstrap: walletLifecycleMayAcquireStorage,
            viewIsCurrent: viewIfLoaded?.window != nil,
            retirementInFlight: retirementInFlight
        )
    }

    private func currentWalletNameImportAuthority() -> WalletNameImportAuthority? {
        guard persistentWalletExists,
              unconfirmedDatabaseKey == nil,
              let wallet,
              let databasePath = resolvedDatabasePath,
              let lease = storageLease else {
            return nil
        }
        return WalletNameImportAuthority(
            network: network,
            databasePath: databasePath,
            lease: lease,
            walletIdentity: ObjectIdentifier(wallet),
            ownerGeneration: walletAuthorityGeneration
        )
    }

    private func currentWalletNameImportState() -> WalletNameImportState {
        let status = try? wallet?.status()
        let readsConfigured = (try? wallet?.hasHnsReads()) == true
        let exactReadProfile = status?.locked == false &&
            status?.enabledModules == ["handshake"] &&
            status?.mainnetSettlementEnabled == false &&
            status?.activeWallet?.isEmpty == false
        return WalletNameImportState(
            authority: currentWalletNameImportAuthority(),
            reopenedDurableConfirmedWallet: walletWasReopenedFromDurableStorage,
            protectedStorageIsAvailable: protectedStorageIsAvailable,
            lifecycleAllowsImport: walletLifecycleMayAcquireStorage,
            viewIsCurrent: walletAuthorityRequested && viewIfLoaded?.window != nil,
            retirementInFlight: retirementInFlight,
            operationInFlight: isOperating,
            unlockedExactReadProfile: exactReadProfile,
            synchronizedHnsReadsConfigured: readsConfigured
        )
    }

    private func performWalletOperation(_ operation: () throws -> Void) {
        guard storageLease != nil else {
            showErrorMessage(retirementInFlight
                ? "Wallet protection is still finishing. Try again after it completes."
                : "Another wallet screen owns this network's local wallet storage.")
            return
        }
        guard !isOperating else {
            showErrorMessage(walletOperationInProgressMessage)
            return
        }
        isOperating = true
        refreshButtonStates()
        defer {
            isOperating = false
            refreshState()
        }
        do {
            try operation()
        } catch {
            showError(error)
        }
    }

    private func refreshState() {
        confirmedDeletionAccountID = nil
        walletIsUnlocked = false
        directHnsValueAvailable = false
        shakedexAvailable = false
        updateDirectShakescapeServiceTimer()
        guard storageLease != nil else {
            if renderProcessOwnedHnsSyncPresentation() {
                refreshButtonStates()
                return
            }
            if let path = resolvedDatabasePath,
               WalletStorageLeaseRegistry.isBlockedAfterRetirementFailure(path: path) {
                statusLabel.text = "Native wallet retirement could not be verified. Restart the app before using this network's wallet again."
                accountLabel.text = "Account unavailable until the app restarts."
                setReadAvailability(false, message: "Read-only synchronization is blocked until restart after an ambiguous native wallet close.")
            } else if retirementInFlight {
                statusLabel.text = "Wallet protection is finishing off the main thread."
                accountLabel.text = "Account unavailable until native teardown completes."
                setReadAvailability(false, message: "Read-only synchronization unavailable during wallet teardown.")
            } else if !walletLifecycleMayAcquireStorage {
                statusLabel.text = "Wallet controls are protected while this screen is inactive."
                accountLabel.text = "Account unavailable until protected foreground access resumes."
                setReadAvailability(false, message: "Read-only synchronization unavailable outside protected foreground access.")
            } else {
                statusLabel.text = "Wallet storage is active in another screen."
                accountLabel.text = "Account unavailable. Close the other wallet screen and try again."
                setReadAvailability(false, message: "Read-only synchronization unavailable while storage is owned elsewhere.")
            }
            refreshButtonStates()
            return
        }
        guard protectedStorageIsAvailable else {
            statusLabel.text = encryptedOrphanCleanupPending
                ? "Wallet key deleted. Encrypted wallet-file cleanup is pending and will be retried."
                : "Wallet protected storage is unavailable."
            accountLabel.text = "Account unavailable."
            setReadAvailability(false, message: encryptedOrphanCleanupPending
                ? "Read-only synchronization is unavailable while encrypted orphan cleanup is pending."
                : "Read-only synchronization unavailable without protected storage.")
            refreshButtonStates()
            return
        }
        if unconfirmedDatabaseKey != nil {
            statusLabel.text = "Status: record and confirm the recovery phrase. Leaving this screen deletes the incomplete wallet."
            accountLabel.text = "Account: locked until recovery confirmation is complete."
            setReadAvailability(false, message: "Read-only synchronization begins only after recovery confirmation.")
            refreshButtonStates()
            return
        }
        guard let wallet else {
            openButton.configuration?.title = "Open and unlock"
            statusLabel.text = persistentWalletExists
                ? "Wallet is ready to open and unlock. Tap Open and unlock to continue."
                : "Status: no wallet has been created."
            accountLabel.text = "Account: unavailable until a wallet is opened."
            setReadAvailability(false, message: "Read-only synchronization unavailable until the wallet is open.")
            refreshButtonStates()
            return
        }
        do {
            let hasHnsReads = try wallet.hasHnsReads()
            let hasHnsValue = try wallet.hasHnsValue()
            let hasBitcoinValue = bitcoinSyncInProgress
                ? bitcoinValueAvailable
                : try wallet.hasBitcoinValue()
            let status = try wallet.status()
            let enabledModulesAreAllowed = hasHnsReads
                ? status.enabledModules == ["handshake"]
                : status.enabledModules.isEmpty
            guard enabledModulesAreAllowed,
                  status.hnsValueEnabled == hasHnsValue,
                  status.shakedexEnabled == hasHnsValue,
                  !status.mainnetSettlementEnabled else {
                throw NativeWalletBridgeError.invalidOutput(
                    "native HNS wallet exposed an incoherent capability set"
                )
            }
            statusLabel.text = status.locked
                ? "Wallet is ready to unlock. Tap Unlock to continue."
                : "Unlocked Shakescape wallet."
            openButton.configuration?.title = status.locked ? "Unlock" : "Open and unlock"
            walletIsUnlocked = !status.locked
            directHnsValueAvailable = hasHnsValue && !status.locked
            bitcoinValueAvailable = hasBitcoinValue && !status.locked
            shakedexAvailable = status.shakedexEnabled && !status.locked
            updateDirectShakescapeServiceTimer()
            if status.locked {
                accountLabel.text = "Account: unlock to view the local HNS account identity."
                setReadAvailability(false, message: hasHnsReads
                    ? "Direct HNS synchronization is configured; unlock the wallet to synchronize."
                    : "Direct HNS setup starts after this confirmed wallet is reopened and unlocked.")
            } else {
                let accounts = try wallet.accounts()
                guard accounts.count == 1,
                      let account = accounts.first,
                      account.module == "handshake",
                      account.receiveDisplay == nil else {
                    throw NativeWalletBridgeError.invalidOutput(
                        "native wallet must expose exactly one local HNS account"
                    )
                }
                accountLabel.text = "Account: \(account.label) · \(account.module) · \(account.accountId)"
                if persistentWalletExists,
                   walletAccountIDIsCanonical(account.accountId) {
                    confirmedDeletionAccountID = account.accountId
                }
                pendingOutgoingSnapshotHeight = WalletPendingOutgoingRecoveryStore.load(
                    networkID: network.rawValue,
                    accountID: account.accountId
                )
                setReadAvailability(hasHnsReads, message: hasHnsReads
                    ? (hasHnsValue
                        ? "Direct HNS synchronization is ready. Synchronize before sending."
                        : "HNS read synchronization is ready.")
                    : "Preparing the direct HNS wallet is required before synchronization.")
                if hasHnsValue {
                    let receive = try wallet.localHnsReceiveTarget()
                    receiveTargets = WalletReceiveTargets(localPaymentAddress: receive.display)
                    paymentReceiveLabel.text = "Payment receive\n\(receive.display)\nDerivation index \(receive.derivationIndex)"
                }
                if hasBitcoinValue, !bitcoinSyncInProgress,
                   let snapshot = try? wallet.bitcoinSnapshot() {
                    renderBitcoinSnapshot(snapshot)
                    bitcoinStatusLabel.text = "Direct Bitcoin wallet ready at durable height \(snapshot.synchronizedHeight)."
                } else if !hasBitcoinValue {
                    bitcoinStatusLabel.text = "Direct Bitcoin wallet is unavailable until setup and unlock complete."
                }
            }
        } catch {
            walletIsUnlocked = false
            directHnsValueAvailable = false
            if !bitcoinSyncInProgress { bitcoinValueAvailable = false }
            shakedexAvailable = false
            updateDirectShakescapeServiceTimer()
            statusLabel.text = "Status unavailable."
            accountLabel.text = "Account unavailable."
            setReadAvailability(false, message: "Read-only synchronization status is unavailable.")
        }
        refreshButtonStates()
    }

    private func setReadAvailability(_ available: Bool, message: String) {
        synchronizedReadsAvailable = available
        readStatusLabel.text = message
        nameImportStatusLabel.text = available
            ? "Enter exact canonical name text. The app does not trim, lowercase, normalize, apply IDNA, or edit a trailing dot."
            : "Trusted name import is unavailable without a scoped indexed wallet backend."
        clearReadProjection()
    }

    private func refreshButtonStates() {
        let ownsStorage = storageLease != nil
        let hasWallet = wallet != nil
        let hasIncompleteWallet = unconfirmedDatabaseKey != nil
        createButton.isEnabled = ownsStorage && protectedStorageIsAvailable && !hasWallet && !persistentWalletExists && !isOperating
        restoreButton.isEnabled = ownsStorage && protectedStorageIsAvailable && !hasWallet && !persistentWalletExists && !isOperating
        openButton.isEnabled = ownsStorage && protectedStorageIsAvailable && !hasIncompleteWallet && (hasWallet || persistentWalletExists) && !isOperating
        lockButton.isEnabled = ownsStorage && protectedStorageIsAvailable && hasWallet &&
            !hasIncompleteWallet && !isOperating && !bitcoinSyncInProgress &&
            !bitcoinBirthdayResetInProgress
        confirmRecoveryButton.isEnabled = ownsStorage && hasIncompleteWallet && recoverySecret != nil && !isOperating
        refreshButton.isEnabled = ownsStorage &&
            (hasWallet || encryptedOrphanCleanupPending) &&
            !hasIncompleteWallet &&
            !isOperating
        synchronizeButton.isEnabled = ownsStorage && hasWallet && !hasIncompleteWallet && synchronizedReadsAvailable && !isOperating
        bitcoinReceiveButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && !bitcoinSyncInProgress && !bitcoinBirthdayResetInProgress
        bitcoinSendButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && !bitcoinSyncInProgress && !bitcoinBirthdayResetInProgress
        bitcoinSellForHnsButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && !bitcoinSyncInProgress && !bitcoinBirthdayResetInProgress &&
            !isOperating
        bitcoinOffersButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && !bitcoinSyncInProgress && !isOperating
        bitcoinExecutionsButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && !bitcoinSyncInProgress && !isOperating
        bitcoinSyncButton.isEnabled = bitcoinSyncInProgress
            ? !bitcoinSyncStopRequested
            : ownsStorage && hasWallet && walletIsUnlocked && bitcoinValueAvailable &&
                !bitcoinBirthdayResetInProgress
        let bitcoinRecoveryBirthdayAvailable = bitcoinSnapshot.map {
            ["recoveryUnknown", "recoveryPendingValidation"].contains($0.birthdayState)
        } ?? false
        bitcoinBirthdayButton.isEnabled = ownsStorage && hasWallet && walletIsUnlocked &&
            bitcoinValueAvailable && bitcoinRecoveryBirthdayAvailable &&
            !bitcoinSyncInProgress && !bitcoinBirthdayResetInProgress
        let importState = currentWalletNameImportState()
        importNameButton.isEnabled = importState.authority.map {
            walletNameImportMayStart(expected: $0, current: importState)
        } ?? false
        let canStopSynchronization = hnsCatchupRetryPending ||
            WalletHnsSyncPresentationCache.canRequestCancellation(networkID: network.rawValue)
        let syncPresentation = WalletHnsSyncPresentationCache.latest(
            networkID: network.rawValue
        )
        switch (hnsCatchupRetryPending, syncPresentation) {
        case (true, _):
            deleteButton.configuration?.title = "Stop synchronization"
        case (false, .some(.preparing)) where canStopSynchronization:
            deleteButton.configuration?.title = "Stop synchronization"
        case (false, .some(.live(_))) where canStopSynchronization:
            deleteButton.configuration?.title = "Stop synchronization"
        case (false, .some(.cancelling)):
            deleteButton.configuration?.title = "Stopping synchronization…"
        case (false, .some(.terminal)):
            deleteButton.configuration?.title = "Waiting for wallet protection…"
        default:
            deleteButton.configuration?.title = "Delete confirmed wallet"
        }
        deleteButton.isEnabled = canStopSynchronization || (ownsStorage &&
            protectedStorageIsAvailable &&
            hasWallet &&
            !hasIncompleteWallet &&
            persistentWalletExists &&
            confirmedDeletionAccountID != nil &&
            walletLifecycleMayAcquireStorage &&
            viewIfLoaded?.window != nil &&
            !retirementInFlight &&
            !isOperating &&
            !bitcoinSyncInProgress &&
            !bitcoinBirthdayResetInProgress)
        renderWalletDashboard()
    }

    private func canStartNewWallet() throws -> Bool {
        guard storageLease != nil else {
            throw WalletProviderError(
                code: "walletStorageBusy",
                message: "Another wallet screen owns this network's local wallet storage."
            )
        }
        guard !Self.screenCaptureProtectionActive else {
            throw WalletProviderError(
                code: "screenCaptured",
                message: "Stop screen recording or mirroring before creating or restoring a wallet."
            )
        }
        try reconcileIncompleteStorage()
        protectedStorageIsAvailable = true
        guard wallet == nil,
              unconfirmedDatabaseKey == nil,
              !persistentWalletExists,
              let path = resolvedDatabasePath,
              !Self.walletFilesExist(databasePath: path) else {
            throw WalletProviderError(
                code: "walletAlreadyExists",
                message: "A wallet already exists. Open it instead of replacing its key."
            )
        }
        return true
    }

    private func refreshProtectedStorageState() {
        guard storageLease != nil else {
            protectedStorageIsAvailable = false
            return
        }
        do {
            try reconcileIncompleteStorage()
            protectedStorageIsAvailable = true
        } catch {
            protectedStorageIsAvailable = false
        }
    }

    private func reconcileIncompleteStorage() throws {
        let path = try walletDatabasePath()
        guard let lease = storageLease,
              lease.path == path,
              WalletStorageLeaseRegistry.isCurrent(lease) else {
            throw WalletProviderError(
                code: "walletStorageBusy",
                message: "The current wallet screen no longer owns this network's storage."
            )
        }
        let hasDatabase = FileManager.default.fileExists(atPath: path)
        let hasArtifacts = Self.walletFilesExist(databasePath: path)
        let hasKey = try keychain.hasDatabaseKey()
        // Key absence plus any encrypted SQLite artifact is itself the durable
        // crash marker. It survives process death without introducing a second
        // mutable source of truth and must be cleaned before open/create.
        let reconciliation = walletStorageReconciliationAction(
            hasDatabaseKey: hasKey,
            hasDatabase: hasDatabase,
            hasArtifacts: hasArtifacts
        )
        if reconciliation != .confirmedWallet {
            persistentWalletExists = false
        }
        switch reconciliation {
        case .confirmedWallet:
            encryptedOrphanCleanupPending = false
            persistentWalletExists = true
            return
        case .empty:
            encryptedOrphanCleanupPending = false
        case .deleteStrayKey:
            encryptedOrphanCleanupPending = false
            try keychain.deleteDatabaseKey()
        case .deleteKeyThenEncryptedArtifacts:
            encryptedOrphanCleanupPending = false
            try keychain.deleteDatabaseKey()
            encryptedOrphanCleanupPending = true
            try Self.deleteWalletFiles(databasePath: path)
        case .deleteEncryptedOrphanArtifacts:
            encryptedOrphanCleanupPending = true
            try Self.deleteWalletFiles(databasePath: path)
        }
        encryptedOrphanCleanupPending = false
        persistentWalletExists = false
    }

    @objc private func protectWalletLifecycle() {
        directShakescapeServiceTimer?.invalidate()
        directShakescapeServiceTimer = nil
        clearWalletNameImportPrompt(dismiss: true)
        dismissPendingHnsSendApproval(rejectNatively: true)
        dismissPendingHnsValueApproval(rejectNatively: true)
        clearRestoreInput()
        let shouldDeleteIncompleteWallet = unconfirmedDatabaseKey != nil
        if var key = unconfirmedDatabaseKey {
            unconfirmedDatabaseKey = nil
            WalletSecretBytes.wipe(&key)
        }
        clearRecoveryDisplay()
        beginWalletRetirement(deleteIncompleteWallet: shouldDeleteIncompleteWallet)
        if isViewLoaded {
            refreshState()
        }
    }

    @objc private func suspendWalletLifecycle() {
        // Do not request unrestricted background execution. If iOS suspends
        // this process, the native worker and its public poller pause with it;
        // the durable direct-HNS checkpoint and floor journal let foreground
        // reactivation continue safely from the last committed height.
        walletLifecycleSuspended = true
        protectWalletLifecycle()
    }

    @objc private func reactivateWalletLifecycle() {
        guard UIApplication.shared.applicationState == .active,
              UIApplication.shared.isProtectedDataAvailable,
              !Self.screenCaptureProtectionActive else {
            return
        }
        walletLifecycleSuspended = false
        resumeWalletLifecycle()
    }

    @objc private func resumeWalletLifecycle() {
        guard !retirementInFlight, walletLifecycleMayAcquireStorage else {
            protectedStorageIsAvailable = false
            if isViewLoaded {
                refreshState()
            }
            return
        }
        acquireStorageLease()
        if storageLease != nil, unconfirmedDatabaseKey == nil {
            refreshProtectedStorageState()
        }
        if isViewLoaded {
            refreshState()
        }
    }

    @objc private func handleScreenCaptureChange() {
        if Self.screenCaptureProtectionActive {
            suspendWalletLifecycle()
        } else {
            reactivateWalletLifecycle()
        }
    }

    private var walletLifecycleMayAcquireStorage: Bool {
        walletAuthorityRequested &&
            !walletLifecycleSuspended &&
            UIApplication.shared.applicationState == .active &&
            UIApplication.shared.isProtectedDataAvailable &&
            !Self.screenCaptureProtectionActive
    }

    private func beginWalletRetirement(deleteIncompleteWallet: Bool) {
        if retirementInFlight {
            invalidateReadOperation()
            return
        }
        invalidateReadOperation()
        let currentWallet = wallet
        let currentLease = storageLease
        if currentWallet != nil {
            walletAuthorityGeneration &+= 1
        }
        let deletionPath = deleteIncompleteWallet && currentLease != nil
            ? resolvedDatabasePath
            : nil
        wallet = nil
        walletWasReopenedFromDurableStorage = false
        storageLease = nil
        confirmedDeletionAccountID = nil
        protectedStorageIsAvailable = false
        if deleteIncompleteWallet {
            persistentWalletExists = false
        }

        let plan = WalletRetirementPlan(
            wallet: currentWallet,
            lease: currentLease,
            incompleteDatabasePath: deletionPath
        )
        guard plan.hasWork else { return }

        retirementGeneration &+= 1
        let generation = retirementGeneration
        retirementInFlight = true
        WalletRetirementQueue.shared.enqueue(plan) { [weak self] in
            guard let self, self.retirementGeneration == generation else { return }
            self.retirementInFlight = false
            self.resumeWalletLifecycle()
        }
    }

    private func discardWalletAndFiles() {
        clearRecoveryDisplay()
        beginWalletRetirement(deleteIncompleteWallet: true)
    }

    private func clearRecoveryDisplay() {
        recoveryTextView.text = ""
        recoveryTextView.isHidden = true
        recoveryTitle.isHidden = true
        recoverySecret?.clear()
        recoverySecret = nil
    }

    private func clearRestoreInput() {
        restorePhraseField?.text = nil
        restorePhraseField?.resignFirstResponder()
    }

    private func clearWalletNameImportPrompt(dismiss: Bool) {
        let alert = walletNameImportAlert
        clearWalletNameImportManagedText(walletNameImportField)
        walletNameImportField = nil
        walletNameImportAlert = nil
        if dismiss {
            alert?.dismiss(animated: false)
        }
    }

    private func walletDatabasePath() throws -> String {
        if let resolvedDatabasePath { return resolvedDatabasePath }
        let fileManager = FileManager.default
        let applicationSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let walletRoot = applicationSupport.appendingPathComponent("NativeWallet", isDirectory: true)
        let directory = walletRoot.appendingPathComponent(network.rawValue, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: directory.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try mutableDirectory.setResourceValues(values)
        let path = directory.appendingPathComponent("wallet.sqlite3", isDirectory: false).path
        resolvedDatabasePath = path
        return path
    }

    private func acquireStorageLease() {
        guard storageLease == nil,
              !retirementInFlight,
              walletLifecycleMayAcquireStorage,
              walletHnsSyncLifecycleDisposition(
                  presentation: WalletHnsSyncPresentationCache.latest(
                      networkID: network.rawValue
                  ),
                  viewIsVisible: walletAuthorityRequested && viewIfLoaded?.window != nil,
                  sceneIsActive: UIApplication.shared.applicationState == .active
              ) == .acquireStorage else {
            return
        }
        do {
            let path = try walletDatabasePath()
            storageLease = WalletStorageLeaseRegistry.acquire(path: path)
            protectedStorageIsAvailable = storageLease != nil
            if storageLease != nil {
                WalletHnsSyncPresentationCache.clear(networkID: network.rawValue)
            }
        } catch {
            protectedStorageIsAvailable = false
        }
    }

    private func startHnsSyncPresentationWatcher() {
        stopHnsSyncPresentationWatcher()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self,
                      self.walletAuthorityRequested,
                      self.viewIfLoaded?.window != nil else { return }
                self.resumeWalletLifecycle()
            }
        }
        hnsSyncPresentationTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopHnsSyncPresentationWatcher() {
        hnsSyncPresentationTimer?.invalidate()
        hnsSyncPresentationTimer = nil
    }

    @discardableResult
    private func renderProcessOwnedHnsSyncPresentation() -> Bool {
        guard let presentation = WalletHnsSyncPresentationCache.latest(
            networkID: network.rawValue
        ) else { return false }
        switch presentation {
        case .preparing:
            displayedHnsSyncStage = nil
            displayedHnsSyncStageSince = 0
            statusLabel.text = "Keeping the existing HNS synchronization connected."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            readStatusLabel.text = "Preparing direct HNS synchronization…"
        case .live(let progress):
            statusLabel.text = "Keeping the existing HNS synchronization connected."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            let scanned = progress.scannedHeight ?? progress.birthdayHeight
            if progress.scannedHeight == nil,
               progress.verifiedHeaderHeight < progress.birthdayHeight {
                readStatusLabel.text = "Verified headers are at height \(progress.verifiedHeaderHeight) and are catching up toward this restored wallet’s birthday height \(progress.birthdayHeight). Wallet activity scanning has not started."
                break
            }
            let now = ProcessInfo.processInfo.systemUptime
            let visibleStage: WalletHnsSyncStage
            if let displayedHnsSyncStage,
               displayedHnsSyncStage != progress.stage,
               now - displayedHnsSyncStageSince < 3 {
                visibleStage = displayedHnsSyncStage
            } else {
                visibleStage = progress.stage
                if displayedHnsSyncStage != progress.stage {
                    displayedHnsSyncStage = progress.stage
                    displayedHnsSyncStageSince = now
                }
            }
            switch visibleStage {
            case .connecting:
                readStatusLabel.text = "Connecting verified HNS peers. Verified headers are currently at height \(progress.verifiedHeaderHeight)."
            case .headers:
                readStatusLabel.text = "Verifying direct peer headers at height \(progress.verifiedHeaderHeight)."
            case .scanning:
                readStatusLabel.text = "Verified headers are currently at height \(progress.verifiedHeaderHeight). Scanning wallet activity at height \(scanned) of \(progress.targetHeight) from birthday height \(progress.birthdayHeight)."
            case .finalizing:
                readStatusLabel.text = "Finalizing the verified HNS wallet snapshot at height \(progress.verifiedHeaderHeight)."
            }
        case .cancelling(let progress):
            statusLabel.text = "Stopping the existing HNS synchronization now."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            readStatusLabel.text = progress.map {
                if let scannedHeight = $0.scannedHeight {
                    return "Last verified header height \($0.verifiedHeaderHeight); wallet scan height \(scannedHeight) of \($0.targetHeight)."
                }
                return "Last verified header height \($0.verifiedHeaderHeight); wallet birthday height \($0.birthdayHeight); wallet scanning has not started."
            } ?? "No additional synchronization batch will start; the active atomic call is unwinding."
        case .terminal(let progress):
            displayedHnsSyncStage = nil
            displayedHnsSyncStageSince = 0
            statusLabel.text = "HNS synchronization finished. Wallet protection is releasing."
            accountLabel.text = "Account controls will return automatically."
            readStatusLabel.text = progress.map {
                "The synchronized HNS operation reached verified height \($0.verifiedHeaderHeight)."
            } ?? "The synchronized HNS operation finished."
        }
        synchronizedReadsAvailable = false
        directHnsValueAvailable = false
        shakedexAvailable = false
        receiveTargets = nil
        clearReadProjection()
        showReadProjectionSynchronizationPendingIfNeeded()
        return true
    }

    private func invalidateReadOperation() {
        clearWalletNameImportPrompt(dismiss: true)
        readGeneration &+= 1
        isOperating = false
        hnsCatchupRetryPending = false
        synchronizedReadsAvailable = false
        if isViewLoaded {
            clearReadProjection()
            nameImportStatusLabel.text =
                "Trusted name import is unavailable outside the live wallet authority."
        }
    }

    private static func walletFilesExist(databasePath: String) -> Bool {
        ([databasePath] + walletSidecars(databasePath: databasePath)).contains {
            FileManager.default.fileExists(atPath: $0)
        }
    }

    nonisolated fileprivate static func deleteWalletFiles(databasePath: String) throws {
        for path in [databasePath] + walletSidecars(databasePath: databasePath) {
            if FileManager.default.fileExists(atPath: path) {
                try FileManager.default.removeItem(atPath: path)
            }
        }
    }

    nonisolated private static func walletSidecars(databasePath: String) -> [String] {
        ["-wal", "-shm", "-journal"].map { databasePath + $0 }
    }

    private static func randomDatabaseKey() throws -> [UInt8] {
        var key = [UInt8](repeating: 0, count: 32)
        repeat {
            let status = key.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
                SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
            }
            guard status == errSecSuccess else {
                WalletSecretBytes.wipe(&key)
                throw WalletProviderError(
                    code: "randomUnavailable",
                    message: "Secure wallet-key randomness is unavailable."
                )
            }
        } while key.allSatisfy { $0 == 0 }
        return key
    }

    nonisolated private static func lowerHex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private func showError(_ error: Error) {
        if let walletError = error as? WalletProviderError {
            showErrorMessage(walletError.message)
        } else {
            showErrorMessage(error.localizedDescription)
        }
    }

    private func showErrorMessage(_ message: String) {
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Wallet unavailable",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}

func walletRecoveryWordChoices(words: [String], correctIndex: Int) -> [String] {
    precondition(words.indices.contains(correctIndex))
    let correct = words[correctIndex]
    var pool = Array(Set(
        (words + ["abandon", "ability", "able", "about", "above", "absent"])
            .filter { $0 != correct }
    ))
    var choices = [correct]
    while choices.count < 4, !pool.isEmpty {
        choices.append(pool.remove(at: Int.random(in: pool.indices)))
    }
    precondition(choices.count == 4)
    choices.shuffle()
    return choices
}

struct WalletReadPresentation: Equatable, Sendable {
    let status: String
    let balance: String
    let paymentReceive: String
    let nameReceive: String
    let history: String
    let names: String
}

struct WalletTransactionPagePresentation: Equatable, Sendable {
    let text: String
    let offset: Int
    let pageSize: Int
    let hasPrevious: Bool
    let hasNext: Bool
}

/// Raw native-validated receive values used by copy/share controls. This is a
/// deliberately separate projection from `WalletReadPresentation`, whose
/// strings are formatted for people and must never be treated as addresses.
struct WalletReceiveTargets: Equatable, Sendable {
    let paymentAddress: String
    let nameTransferAddress: String?

    init(paymentAddress: String, nameTransferAddress: String?) {
        self.paymentAddress = paymentAddress
        self.nameTransferAddress = nameTransferAddress
    }

    init(localPaymentAddress: String) {
        self.init(paymentAddress: localPaymentAddress, nameTransferAddress: nil)
    }

    init(snapshot: NativeHnsReadSnapshot) {
        self.init(
            paymentAddress: snapshot.receiveTarget.display,
            nameTransferAddress: snapshot.nameReceiveTarget?.display
        )
    }
}

/// Deterministic, bounded UIKit projection of a native-validated HNWR snapshot.
/// This adapter does not infer authority or fetch data; publication remains
/// gated by the exact wallet identity, storage lease, lifecycle, and generation.
enum WalletReadPresenter {
    static func presentTransactionPage(
        _ transactions: [NativeHnsReadSnapshot.Transaction],
        requestedOffset: Int,
        maximumVisibleItems: Int = 20
    ) -> WalletTransactionPagePresentation {
        let pageSize = visibleItemLimit(requested: maximumVisibleItems)
        let lastPageOffset = transactions.isEmpty
            ? 0
            : ((transactions.count - 1) / pageSize) * pageSize
        let offset = min(max(0, requestedOffset), lastPageOffset)
        let page = transactions.dropFirst(offset).prefix(pageSize)
        let text: String
        if page.isEmpty {
            text = "No wallet transactions were found in the synchronized snapshot."
        } else {
            let entries = page.map { transaction in
                let chainPosition = transaction.blockHeight.map {
                    "Block \($0) · \(transaction.confirmationCount) confirmations"
                } ?? "Unconfirmed"
                return [
                    "\(transactionStatusLabel(transaction.status)) · \(displayAmount(transaction))",
                    lowerHex(transaction.txid),
                    chainPosition,
                ].joined(separator: "\n")
            }.joined(separator: "\n\n")
            text = "Showing activity \(offset + 1)–\(offset + page.count) of \(transactions.count).\n\n\(entries)"
        }
        return WalletTransactionPagePresentation(
            text: text,
            offset: offset,
            pageSize: pageSize,
            hasPrevious: offset > 0,
            hasNext: offset + page.count < transactions.count
        )
    }

    static func present(
        _ snapshot: NativeHnsReadSnapshot,
        maximumVisibleItems: Int = 20
    ) -> WalletReadPresentation {
        let visibleItemLimit = visibleItemLimit(requested: maximumVisibleItems)
        let transactions = snapshot.transactionHistory.prefix(visibleItemLimit)
        let names = snapshot.knownNames.prefix(visibleItemLimit)

        let history: String
        if transactions.isEmpty {
            history = "No wallet transactions were found in the synchronized snapshot."
        } else {
            let entries = transactions.map { transaction in
                let chainPosition = transaction.blockHeight.map {
                    "Block \($0) · \(transaction.confirmationCount) confirmations"
                } ?? "Unconfirmed"
                return [
                    "\(transactionStatusLabel(transaction.status)) · \(displayAmount(transaction))",
                    lowerHex(transaction.txid),
                    chainPosition,
                ].joined(separator: "\n")
            }.joined(separator: "\n\n")
            history = appendRemainingCount(
                entries,
                remaining: snapshot.transactionHistory.count - transactions.count
            )
        }

        let trackedNames: String
        if names.isEmpty {
            trackedNames = "No names are tracked by this wallet yet."
        } else {
            let entries = names.map { presentName($0) }.joined(separator: "\n\n")
            trackedNames = appendRemainingCount(
                entries,
                remaining: snapshot.knownNameCount - names.count
            )
        }

        let balance = WalletHnsBalancePresenter.present(snapshot)
        let balanceText: String
        if balance.hasPendingOutgoing {
            balanceText = [
                "\(formatHnsBaseUnits(balance.spendableBaseUnits)) HNS spendable now",
                "\(formatHnsBaseUnits(balance.pendingOutgoingBaseUnits)) HNS pending outgoing",
                "Transaction Pending, please wait.",
            ].joined(separator: "\n")
        } else {
            balanceText = "\(formatHnsBaseUnits(balance.spendableBaseUnits)) HNS spendable now"
        }
        return WalletReadPresentation(
            status: "Synced and ready at height \(snapshot.moduleStatus.validatedHeight). Pending outgoing transactions are reflected in the available balance.",
            balance: balanceText,
            paymentReceive: "Payment receive\n\(snapshot.receiveTarget.display)\nDerivation index \(snapshot.receiveTarget.derivationIndex)",
            nameReceive: snapshot.nameReceiveTarget.map {
                "Name transfer receive\n\($0.display)\nName derivation index \($0.derivationIndex)"
            } ?? "Name transfer receive: unavailable for HNWR-v1.",
            history: history,
            names: trackedNames
        )
    }

    static func formatHnsBaseUnits(_ baseUnits: String) -> String {
        let decimalPlaces = 6
        let padded = String(repeating: "0", count: max(0, decimalPlaces + 1 - baseUnits.count)) + baseUnits
        let split = padded.index(padded.endIndex, offsetBy: -decimalPlaces)
        let whole = String(padded[..<split])
        let fraction = String(padded[split...]).replacingOccurrences(
            of: "0+$",
            with: "",
            options: .regularExpression
        )
        return fraction.isEmpty ? whole : "\(whole).\(fraction)"
    }

    static func visibleItemLimit(requested: Int) -> Int {
        min(max(requested, 1), 20)
    }

    static func codeLabel(_ value: String) -> String {
        var label = ""
        for character in value {
            if character == "_" {
                label.append(" ")
            } else if character.isUppercase {
                if !label.isEmpty { label.append(" ") }
                label.append(contentsOf: character.lowercased())
            } else {
                label.append(character)
            }
        }
        return label
    }

    static func transactionStatusLabel(_ value: String) -> String {
        switch value {
        case "broadcast": return "submitted to peers"
        case "mempool": return "pending (local wallet)"
        default: return codeLabel(value)
        }
    }

    static func presentName(_ name: NativeHnsReadSnapshot.KnownName) -> String {
        var states = [
            codeLabel(name.ownershipStatus),
            codeLabel(name.resourceStatus),
        ]
        if let registered = name.registered {
            states.append(registered ? "registered" : "not registered")
        }
        return [
            "\(name.name) · proof height \(name.proofHeight)",
            states.joined(separator: " · "),
            name.nameHash,
        ].joined(separator: "\n")
    }

    private static func displayAmount(_ transaction: NativeHnsReadSnapshot.Transaction) -> String {
        let sign = transaction.netAmount.negative ? "-" : ""
        return "\(sign)\(formatHnsBaseUnits(transaction.netAmount.magnitude)) HNS"
    }

    private static func lowerHex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func appendRemainingCount(_ entries: String, remaining: Int) -> String {
        guard remaining > 0 else { return entries }
        return "\(entries)\n\n\(remaining) more items are present in this synchronized snapshot."
    }
}

struct WalletHnsBalanceProjection: Equatable, Sendable {
    let spendableBaseUnits: String
    let pendingOutgoingBaseUnits: String

    var hasPendingOutgoing: Bool { pendingOutgoingBaseUnits != "0" }
}

/// Exact decimal-string accounting for the same native snapshot. Swift has no
/// built-in UInt128, so this intentionally performs digit arithmetic rather
/// than passing wallet values through `Double` or a bounded decimal type.
enum WalletHnsBalancePresenter {
    private static let pendingStatuses: Set<String> = [
        "prepared", "authorized", "broadcast", "mempool",
    ]

    static func present(_ snapshot: NativeHnsReadSnapshot) -> WalletHnsBalanceProjection {
        let pending = snapshot.transactionHistory.reduce("0") { total, transaction in
            guard transaction.netAmount.negative,
                  pendingStatuses.contains(transaction.status) else {
                return total
            }
            return add(total, transaction.netAmount.magnitude)
        }
        return WalletHnsBalanceProjection(
            spendableBaseUnits: snapshot.balance.baseUnits,
            pendingOutgoingBaseUnits: pending
        )
    }

    private static func add(_ left: String, _ right: String) -> String {
        let lhs = Array(left.utf8.reversed())
        let rhs = Array(right.utf8.reversed())
        let count = max(lhs.count, rhs.count)
        var result = [UInt8]()
        result.reserveCapacity(count + 1)
        var carry = 0
        for index in 0..<count {
            let a = index < lhs.count ? Int(lhs[index] - UInt8(ascii: "0")) : 0
            let b = index < rhs.count ? Int(rhs[index] - UInt8(ascii: "0")) : 0
            let value = a + b + carry
            result.append(UInt8(value % 10) + UInt8(ascii: "0"))
            carry = value / 10
        }
        if carry != 0 { result.append(UInt8(carry) + UInt8(ascii: "0")) }
        return String(bytes: result.reversed(), encoding: .ascii)!
    }

}

private enum WalletHnsReadOutcome: Sendable {
    case success(NativeHnsReadSnapshot)
    case catchingUp(NativeHnsCatchupProgress)
    case cancelled
    case failure(String)
}

private struct WalletHnsSendRequest: Sendable {
    let recipient: String
    let amountBaseUnits: String
    let maximumFeeBaseUnits: String
}

private struct WalletHnsValueFormField: Sendable {
    let label: String
    let placeholder: String
    let numeric: Bool
    let initialValue: String?

    init(
        label: String,
        placeholder: String,
        numeric: Bool = false,
        initialValue: String? = nil
    ) {
        self.label = label
        self.placeholder = placeholder
        self.numeric = numeric
        self.initialValue = initialValue
    }
}

private enum WalletHnsNameImportOutcome: Sendable {
    case success(NativeHnsReadSnapshot.KnownName, NativeHnsReadSnapshot)
    case successRefreshFailed(String)
    case invalidInput
    case failure(String)
}

private enum WalletHnsBulkNameImportOutcome: Sendable {
    case success(Int, NativeHnsReadSnapshot)
    case successRefreshFailed(String)
    case invalidInput
    case failure(String)
}

let maximumMultipleWalletNameImports = 10_000
let maximumMultipleWalletNameInputCharacters =
    maximumMultipleWalletNameImports * 63 + maximumMultipleWalletNameImports - 1

private let reservedHandshakeNameTexts: Set<String> = [
    "example", "invalid", "local", "localhost", "test",
]

func isCanonicalHandshakeNameText(_ name: String) -> Bool {
    let bytes = Array(name.utf8)
    guard (1...63).contains(bytes.count),
          !reservedHandshakeNameTexts.contains(name) else {
        return false
    }
    return bytes.indices.allSatisfy { index in
        let byte = bytes[index]
        let alphanumeric = (UInt8(ascii: "0")...UInt8(ascii: "9")).contains(byte) ||
            (UInt8(ascii: "a")...UInt8(ascii: "z")).contains(byte)
        let interiorSeparator = (byte == UInt8(ascii: "-") || byte == UInt8(ascii: "_")) &&
            index != bytes.startIndex && index != bytes.index(before: bytes.endIndex)
        return alphanumeric || interiorSeparator
    }
}

/// ASCII spaces are deliberately the only separators. Pasted commas, tabs,
/// and line breaks are rejected instead of silently changing the request.
func parseSpaceSeparatedWalletNames(_ text: String) -> [String]? {
    guard !text.isEmpty,
          text.count <= maximumMultipleWalletNameInputCharacters,
          !text.contains(where: { $0.isWhitespace && $0 != " " }) else {
        return nil
    }
    let names = text.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
    guard !names.isEmpty,
          names.count <= maximumMultipleWalletNameImports,
          Set(names).count == names.count,
          names.allSatisfy(isCanonicalHandshakeNameText) else {
        return nil
    }
    return names
}

@MainActor
func configureWalletNameImportTextField(_ field: UITextField) {
    field.placeholder = "exact-name"
    field.keyboardType = .asciiCapable
    field.autocapitalizationType = .none
    field.autocorrectionType = .no
    field.spellCheckingType = .no
    field.smartDashesType = .no
    field.smartQuotesType = .no
    field.smartInsertDeleteType = .no
    field.textContentType = nil
    field.accessibilityIdentifier = "wallet.import-hns-name.text"
}

@MainActor
func clearWalletNameImportManagedText(_ field: UITextField?) {
    field?.text = nil
    field?.resignFirstResponder()
}

func walletNameImportFailureIsNonPoisoningInvalid(_ error: Error) -> Bool {
    guard let bridgeError = error as? NativeWalletBridgeError,
          case .callFailed(_, let code, _) = bridgeError else {
        return false
    }
    return code == HNS_BROWSER_RESULT_INVALID_ARGUMENT ||
        code == HNS_BROWSER_RESULT_INVALID_UTF8
}

typealias WalletNameImportAuthority = WalletReadBootstrapAuthority

struct WalletNameImportState: Equatable, Sendable {
    let authority: WalletNameImportAuthority?
    let reopenedDurableConfirmedWallet: Bool
    let protectedStorageIsAvailable: Bool
    let lifecycleAllowsImport: Bool
    let viewIsCurrent: Bool
    let retirementInFlight: Bool
    let operationInFlight: Bool
    let unlockedExactReadProfile: Bool
    let synchronizedHnsReadsConfigured: Bool
}

func walletNameImportMayStart(
    expected: WalletNameImportAuthority,
    current: WalletNameImportState
) -> Bool {
    expected == current.authority &&
        walletReadBootstrapAuthorityIsWellFormed(expected) &&
        WalletStorageLeaseRegistry.isCurrent(expected.lease) &&
        current.reopenedDurableConfirmedWallet &&
        current.protectedStorageIsAvailable &&
        current.lifecycleAllowsImport &&
        current.viewIsCurrent &&
        !current.retirementInFlight &&
        !current.operationInFlight &&
        current.unlockedExactReadProfile &&
        current.synchronizedHnsReadsConfigured
}

func walletNameImportCompletionMayApply(
    expected: WalletNameImportAuthority,
    current: WalletNameImportAuthority?,
    expectedGeneration: UInt64,
    currentGeneration: UInt64,
    expectedLease: WalletStorageLeaseToken,
    currentLease: WalletStorageLeaseToken?,
    lifecycleAllowsImport: Bool,
    viewIsCurrent: Bool,
    operationInFlight: Bool
) -> Bool {
    expected == current &&
        walletReadBootstrapAuthorityIsWellFormed(expected) &&
        expectedGeneration == currentGeneration &&
        expectedLease == expected.lease &&
        expectedLease == currentLease &&
        WalletStorageLeaseRegistry.isCurrent(expectedLease) &&
        lifecycleAllowsImport &&
        viewIsCurrent &&
        operationInFlight
}

func walletReadMayPublish(
    expectedGeneration: UInt64,
    currentGeneration: UInt64,
    expectedLease: WalletStorageLeaseToken,
    currentLease: WalletStorageLeaseToken?,
    expectedWalletIdentity: ObjectIdentifier,
    currentWalletIdentity: ObjectIdentifier?,
    expectedAuthorityGeneration: UInt64,
    currentAuthorityGeneration: UInt64,
    viewIsVisible: Bool
) -> Bool {
    expectedGeneration == currentGeneration &&
        expectedLease == currentLease &&
        expectedWalletIdentity == currentWalletIdentity &&
        expectedAuthorityGeneration > 0 &&
        expectedAuthorityGeneration == currentAuthorityGeneration &&
        viewIsVisible
}

func walletPullToSyncMayStart(hasPresentedViewController: Bool) -> Bool {
    !hasPresentedViewController
}

struct WalletReadBootstrapState: Equatable, Sendable {
    let authority: WalletReadBootstrapAuthority?
    let reopenedDurableConfirmedWallet: Bool
    let protectedStorageIsAvailable: Bool
    let lifecycleAllowsBootstrap: Bool
    let viewIsCurrent: Bool
    let retirementInFlight: Bool
}

func walletReadBootstrapMayInstall(
    expected: WalletReadBootstrapAuthority,
    current: WalletReadBootstrapState
) -> Bool {
    expected == current.authority &&
        walletReadBootstrapAuthorityIsWellFormed(expected) &&
        WalletStorageLeaseRegistry.isCurrent(expected.lease) &&
        current.reopenedDurableConfirmedWallet &&
        current.protectedStorageIsAvailable &&
        current.lifecycleAllowsBootstrap &&
        current.viewIsCurrent &&
        !current.retirementInFlight
}

/// Gate before credential acquisition and again after a potentially
/// re-entrant source callback. `expectedAuthority` is intentionally retained:
/// a source cannot rotate authority and return a credential for the rotated
/// controller in response to a request for the original controller.
func attemptWalletReadBootstrap(
    expectedAuthority: WalletReadBootstrapAuthority,
    source: any WalletReadBootstrapSource,
    currentState: () -> WalletReadBootstrapState,
    install: (
        WalletReadBootstrapAuthority,
        NativeHnsReadConfiguration
    ) throws -> Void
) throws -> Bool {
    guard walletReadBootstrapMayInstall(
        expected: expectedAuthority,
        current: currentState()
    ),
    let configuration = source.takeConfiguration(for: expectedAuthority) else {
        return false
    }
    defer { configuration.discard() }

    let current = currentState()
    guard configuration.authority == expectedAuthority,
          let currentAuthority = current.authority,
          currentAuthority == expectedAuthority,
          walletReadBootstrapMayInstall(
              expected: expectedAuthority,
              current: current
          ) else {
        return false
    }
    try install(currentAuthority, configuration)
    return true
}

struct WalletConfirmedDeletionAuthority: Equatable, Sendable {
    let network: BrowserHandshakeNetwork
    let accountID: String
    let databasePath: String
    let lease: WalletStorageLeaseToken
    let walletIdentity: ObjectIdentifier
    let ownerGeneration: UInt64
}

enum WalletStorageReconciliationAction: Equatable, Sendable {
    case confirmedWallet
    case empty
    case deleteStrayKey
    case deleteKeyThenEncryptedArtifacts
    case deleteEncryptedOrphanArtifacts
}

func walletDeletionIdentitySummary(
    _ authority: WalletConfirmedDeletionAuthority
) -> String {
    "Network: \(authority.network.title) (\(authority.network.rawValue))\n" +
        "Account: \(authority.accountID)"
}

func walletStorageReconciliationAction(
    hasDatabaseKey: Bool,
    hasDatabase: Bool,
    hasArtifacts: Bool
) -> WalletStorageReconciliationAction {
    if hasDatabaseKey && hasDatabase {
        return .confirmedWallet
    }
    if hasDatabaseKey {
        return hasArtifacts ? .deleteKeyThenEncryptedArtifacts : .deleteStrayKey
    }
    return hasArtifacts ? .deleteEncryptedOrphanArtifacts : .empty
}

func walletAccountIDIsCanonical(_ value: String) -> Bool {
    value.utf8.count == 32 &&
        value.utf8.contains(where: { $0 != UInt8(ascii: "0") }) &&
        value.utf8.allSatisfy {
            (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) ||
                (UInt8(ascii: "a")...UInt8(ascii: "f")).contains($0)
        }
}

func walletDeletionConfirmationMatches(_ value: String?) -> Bool {
    value == "DELETE"
}

func walletDatabasePathMatchesNetworkNamespace(
    _ databasePath: String,
    network: BrowserHandshakeNetwork
) -> Bool {
    let database = URL(fileURLWithPath: databasePath).standardizedFileURL
    let networkDirectory = database.deletingLastPathComponent()
    let walletRoot = networkDirectory.deletingLastPathComponent()
    return database.lastPathComponent == "wallet.sqlite3" &&
        networkDirectory.lastPathComponent == network.rawValue &&
        walletRoot.lastPathComponent == "NativeWallet"
}

func walletConfirmedDeletionMayProceed(
    expected: WalletConfirmedDeletionAuthority,
    current: WalletConfirmedDeletionAuthority,
    lifecycleAllowsDeletion: Bool,
    viewIsCurrent: Bool,
    operationInFlight: Bool,
    screenIsCaptured: Bool
) -> Bool {
    expected == current &&
        walletAccountIDIsCanonical(expected.accountID) &&
        expected.lease.path == expected.databasePath &&
        walletDatabasePathMatchesNetworkNamespace(
            expected.databasePath,
            network: expected.network
        ) &&
        WalletStorageLeaseRegistry.isCurrent(expected.lease) &&
        lifecycleAllowsDeletion &&
        viewIsCurrent &&
        !operationInFlight &&
        !screenIsCaptured
}

func walletDeletionCompletionMayApply(
    expectedRetirementGeneration: UInt64,
    currentRetirementGeneration: UInt64,
    expectedDetachedAuthorityGeneration: UInt64,
    currentAuthorityGeneration: UInt64,
    walletIsDetached: Bool,
    leaseIsDetached: Bool
) -> Bool {
    expectedRetirementGeneration == currentRetirementGeneration &&
        expectedDetachedAuthorityGeneration == currentAuthorityGeneration &&
        walletIsDetached &&
        leaseIsDetached
}

struct WalletStorageLeaseToken: Equatable, Sendable {
    let path: String
    let owner: UUID
}

private final class WalletStorageLeaseRegistryState: @unchecked Sendable {
    let lock = NSLock()
    var owners: [String: UUID] = [:]
    var retirementFailedPaths: Set<String> = []
}

enum WalletStorageLeaseRegistry {
    private static let state = WalletStorageLeaseRegistryState()

    static func acquire(path: String) -> WalletStorageLeaseToken? {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.owners[path] == nil,
              !state.retirementFailedPaths.contains(path) else {
            return nil
        }
        let owner = UUID()
        state.owners[path] = owner
        return WalletStorageLeaseToken(path: path, owner: owner)
    }

    static func release(_ token: WalletStorageLeaseToken) {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.owners[token.path] == token.owner else { return }
        state.owners.removeValue(forKey: token.path)
    }

    static func isCurrent(_ token: WalletStorageLeaseToken) -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        return state.owners[token.path] == token.owner
    }

    /// A checked native close can fail after an unknown amount of teardown.
    /// Fence that exact namespace for the remainder of this process so no new
    /// controller can open the same database under ambiguous native authority.
    static func blockAfterRetirementFailure(_ token: WalletStorageLeaseToken) {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.owners[token.path] == token.owner else { return }
        state.retirementFailedPaths.insert(token.path)
    }

    static func isBlockedAfterRetirementFailure(path: String) -> Bool {
        state.lock.lock()
        defer { state.lock.unlock() }
        return state.retirementFailedPaths.contains(path)
    }
}

enum WalletConfirmedDeletionOutcome: Equatable, Sendable {
    case deleted
    case controllerCloseFailed
    case keyDeletionFailed
    case encryptedOrphanCleanupPending
    case authorityRevoked
}

/// Confirmed-wallet deletion has a stricter ordering than ordinary lifecycle
/// retirement. The database key must be durably removed before any encrypted
/// SQLite artifact, and the exact storage lease remains held until every
/// attempted stage has completed.
struct WalletConfirmedDeletionPlan: @unchecked Sendable {
    private let authority: WalletConfirmedDeletionAuthority
    private let controllerIdentity: ObjectIdentifier
    private let lockController: () -> Void
    private let destroyController: () throws -> Void
    private let deleteDatabaseKey: () throws -> Void
    private let deleteWalletFiles: () throws -> Void
    private let releaseStorageLease: () -> Void

    init(
        authority: WalletConfirmedDeletionAuthority,
        wallet: RustNativeWallet,
        keychain: WalletKeychainStore,
        deleteWalletFiles: @escaping () throws -> Void
    ) {
        self.authority = authority
        controllerIdentity = ObjectIdentifier(wallet)
        lockController = { try? wallet.lock() }
        destroyController = { try wallet.closeForConfirmedDeletion() }
        deleteDatabaseKey = {
            try keychain.deleteDatabaseKeyForConfirmedWalletDeletion()
        }
        self.deleteWalletFiles = deleteWalletFiles
        releaseStorageLease = {
            WalletStorageLeaseRegistry.release(authority.lease)
        }
    }

    init(
        authority: WalletConfirmedDeletionAuthority,
        controllerIdentity: ObjectIdentifier? = nil,
        lockController: @escaping () -> Void,
        destroyController: @escaping () throws -> Void,
        deleteDatabaseKey: @escaping () throws -> Void,
        deleteWalletFiles: @escaping () throws -> Void,
        releaseStorageLease: @escaping () -> Void
    ) {
        self.authority = authority
        self.controllerIdentity = controllerIdentity ?? authority.walletIdentity
        self.lockController = lockController
        self.destroyController = destroyController
        self.deleteDatabaseKey = deleteDatabaseKey
        self.deleteWalletFiles = deleteWalletFiles
        self.releaseStorageLease = releaseStorageLease
    }

    func execute() -> WalletConfirmedDeletionOutcome {
        defer { releaseStorageLease() }
        guard controllerIdentity == authority.walletIdentity,
              walletAccountIDIsCanonical(authority.accountID),
              authority.lease.path == authority.databasePath,
              walletDatabasePathMatchesNetworkNamespace(
                  authority.databasePath,
                  network: authority.network
              ),
              WalletStorageLeaseRegistry.isCurrent(authority.lease) else {
            return .authorityRevoked
        }

        lockController()
        do {
            try destroyController()
        } catch {
            WalletStorageLeaseRegistry.blockAfterRetirementFailure(
                authority.lease
            )
            return .controllerCloseFailed
        }
        // The registry cannot legitimately change while this exact token is
        // held; the key-first transaction owns the namespace until `defer`.
        do {
            try deleteDatabaseKey()
        } catch {
            return .keyDeletionFailed
        }
        // Losing authority after deleting the key leaves only an encrypted
        // orphan. Never risk deleting files now owned by a newer lease.
        guard WalletStorageLeaseRegistry.isCurrent(authority.lease) else {
            return .encryptedOrphanCleanupPending
        }
        do {
            try deleteWalletFiles()
        } catch {
            return .encryptedOrphanCleanupPending
        }
        return .deleted
    }
}

/// Exact teardown sequence handed off by the main actor. The native controller
/// and storage lease stay strongly owned here until lock, destruction, and any
/// incomplete-wallet deletion have all finished.
struct WalletRetirementPlan: @unchecked Sendable {
    let hasWork: Bool
    private let lockController: () -> Void
    private let destroyController: () -> Void
    private let deleteIncompleteWallet: () -> Void
    private let releaseStorageLease: () -> Void

    init(
        wallet: RustNativeWallet?,
        lease: WalletStorageLeaseToken?,
        incompleteDatabasePath: String?
    ) {
        hasWork = wallet != nil || lease != nil || incompleteDatabasePath != nil
        lockController = { try? wallet?.lock() }
        destroyController = { wallet?.close() }
        deleteIncompleteWallet = {
            guard let incompleteDatabasePath else { return }
            try? WalletViewController.deleteWalletFiles(
                databasePath: incompleteDatabasePath
            )
        }
        releaseStorageLease = {
            guard let lease else { return }
            WalletStorageLeaseRegistry.release(lease)
        }
    }

    init(
        hasWork: Bool = true,
        lockController: @escaping () -> Void,
        destroyController: @escaping () -> Void,
        deleteIncompleteWallet: @escaping () -> Void,
        releaseStorageLease: @escaping () -> Void
    ) {
        self.hasWork = hasWork
        self.lockController = lockController
        self.destroyController = destroyController
        self.deleteIncompleteWallet = deleteIncompleteWallet
        self.releaseStorageLease = releaseStorageLease
    }

    func execute() {
        guard hasWork else { return }
        lockController()
        destroyController()
        deleteIncompleteWallet()
        releaseStorageLease()
    }
}

/// A single process-wide worker bounds native retirement concurrency to one.
/// Lifecycle callers never wait for an in-flight HNS read's native mutex.
final class WalletRetirementQueue: @unchecked Sendable {
    static let shared = WalletRetirementQueue()

    private let queue = DispatchQueue(
        label: "com.denuoweb.hnsdane.wallet-retirement",
        qos: .userInitiated,
        autoreleaseFrequency: .workItem
    )

    private init() {}

    func enqueue(
        _ plan: WalletRetirementPlan,
        completion: (@MainActor @Sendable () -> Void)? = nil
    ) {
        queue.async {
            plan.execute()
            guard let completion else { return }
            Task { @MainActor in
                completion()
            }
        }
    }

    func enqueue(
        _ plan: WalletConfirmedDeletionPlan,
        completion: @escaping @MainActor @Sendable (
            WalletConfirmedDeletionOutcome
        ) -> Void
    ) {
        queue.async {
            let outcome = plan.execute()
            Task { @MainActor in
                completion(outcome)
            }
        }
    }
}

struct HandshakePaymentRequest: Equatable, Sendable {
    let address: String
    let amountHns: String?
    let label: String?
    let message: String?
}

enum HandshakePaymentURI {
    static func parse(_ raw: String) -> HandshakePaymentRequest? {
        guard (1...2_048).contains(raw.count),
              let url = URL(string: raw),
              url.scheme?.lowercased() == "handshake",
              url.host == nil,
              url.fragment == nil else { return nil }
        let afterScheme = raw.dropFirst("handshake:".count)
        let pieces = afterScheme.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let address = String(pieces[0])
        guard (1...512).contains(address.utf8.count),
              address.utf8.allSatisfy({ (0x21...0x7e).contains($0) }),
              !address.contains(where: { "%/?#".contains($0) }) else { return nil }

        var values: [String: String] = [:]
        if pieces.count == 2, !pieces[1].isEmpty {
            for field in pieces[1].split(separator: "&", omittingEmptySubsequences: false) {
                let pair = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                let encodedValue = pair.count == 2 ? String(pair[1]) : ""
                guard let name = String(pair[0]).removingPercentEncoding,
                      let value = encodedValue.removingPercentEncoding,
                      !name.hasPrefix("req-"), values[name] == nil else { return nil }
                values[name] = value
            }
        }
        if let amount = values["amount"], !validPositiveHnsAmount(amount) { return nil }
        if values["label", default: ""].count > 256 || values["message", default: ""].count > 256 {
            return nil
        }
        return HandshakePaymentRequest(
            address: address,
            amountHns: values["amount"],
            label: values["label"],
            message: values["message"]
        )
    }

    private static func validPositiveHnsAmount(_ amount: String) -> Bool {
        let parts = amount.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count <= 2,
              !parts[0].isEmpty,
              parts[0].allSatisfy(\.isNumber),
              parts.count == 1 || ((1...6).contains(parts[1].count) && parts[1].allSatisfy(\.isNumber)) else {
            return false
        }
        return amount.contains(where: { $0 != "0" && $0 != "." })
    }
}

@MainActor
private final class NameRecordsEditorViewController: UIViewController, UITextViewDelegate {
    private static let maximumEditorCharacters = 4_096

    private let onReview: (String, String, String) -> Void
    private let nameField = UITextField()
    private let recordsView = UITextView()
    private let feeField = UITextField()
    private let characterCountLabel = UILabel()

    init(onReview: @escaping (String, String, String) -> Void) {
        self.onReview = onReview
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Set records"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Review",
            style: .done,
            target: self,
            action: #selector(review)
        )

        configure(nameField, placeholder: "Exact name", keyboard: .asciiCapable)
        nameField.accessibilityIdentifier = "wallet.name-records.name"
        configure(feeField, placeholder: "Maximum fee cap in HNS", keyboard: .decimalPad)
        feeField.text = defaultHnsMaximumFee
        feeField.accessibilityIdentifier = "wallet.name-records.maximum-fee"

        recordsView.delegate = self
        recordsView.font = .monospacedSystemFont(ofSize: 14, weight: .regular)
        recordsView.autocapitalizationType = .none
        recordsView.autocorrectionType = .no
        recordsView.spellCheckingType = .no
        recordsView.smartDashesType = .no
        recordsView.smartQuotesType = .no
        recordsView.keyboardType = .asciiCapable
        recordsView.layer.borderColor = UIColor.separator.cgColor
        recordsView.layer.borderWidth = 1
        recordsView.layer.cornerRadius = 8
        recordsView.accessibilityIdentifier = "wallet.name-records.records"
        recordsView.accessibilityLabel = "Raw Handshake resource records"

        let instructions = UILabel()
        instructions.numberOfLines = 0
        instructions.font = .preferredFont(forTextStyle: .footnote)
        instructions.textColor = .secondaryLabel
        instructions.text = """
        Enter one record per line. Supported forms:
        NS ns1.example.
        GLUE4 ns1.example. 192.0.2.1
        GLUE6 ns1.example. 2001:db8::1
        SYNTH4 192.0.2.2   or   SYNTH6 2001:db8::2
        DS 12345 8 2 a1b2…
        TXT "text with spaces"

        Blank lines and lines beginning with # are ignored. Leave the records empty to clear the resource. Advanced users may enter hex: followed by the exact encoded resource bytes.
        """

        characterCountLabel.font = .preferredFont(forTextStyle: .caption1)
        characterCountLabel.textColor = .secondaryLabel
        characterCountLabel.textAlignment = .right
        updateCharacterCount()

        let stack = UIStackView(arrangedSubviews: [
            fieldLabel("Name"), nameField,
            fieldLabel("Resource records"), instructions, recordsView, characterCountLabel,
            fieldLabel("Fee limit"), feeField,
        ])
        stack.axis = .vertical
        stack.spacing = 10
        stack.setCustomSpacing(20, after: nameField)
        stack.setCustomSpacing(20, after: characterCountLabel)

        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .interactive
        scroll.translatesAutoresizingMaskIntoConstraints = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -20),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
            recordsView.heightAnchor.constraint(greaterThanOrEqualToConstant: 220),
            nameField.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            feeField.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        nameField.becomeFirstResponder()
    }

    func textViewDidChange(_ textView: UITextView) {
        updateCharacterCount()
    }

    func textView(
        _ textView: UITextView,
        shouldChangeTextIn range: NSRange,
        replacementText text: String
    ) -> Bool {
        guard let current = textView.text,
              let swiftRange = Range(range, in: current) else { return false }
        return current.replacingCharacters(in: swiftRange, with: text).count <= Self.maximumEditorCharacters
    }

    private func configure(
        _ field: UITextField,
        placeholder: String,
        keyboard: UIKeyboardType
    ) {
        field.borderStyle = .roundedRect
        field.placeholder = placeholder
        field.keyboardType = keyboard
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.textContentType = nil
    }

    private func fieldLabel(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .headline)
        return label
    }

    private func updateCharacterCount() {
        characterCountLabel.text = "\(recordsView.text.count) / \(Self.maximumEditorCharacters) characters"
    }

    @objc private func cancel() {
        scrubInputs()
        dismiss(animated: true)
    }

    @objc private func review() {
        let name = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let records = recordsView.text ?? ""
        let fee = feeField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty, !fee.isEmpty else {
            let alert = UIAlertController(
                title: "Missing fields",
                message: "Enter the exact name and a maximum fee. Records may be empty when you intend to clear them.",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
            return
        }
        scrubInputs()
        dismiss(animated: true) { [onReview] in onReview(name, records, fee) }
    }

    private func scrubInputs() {
        nameField.text = nil
        recordsView.text = nil
        feeField.text = nil
        view.endEditing(true)
    }
}

@MainActor
private final class WalletMultipleNameImportEditorViewController: UIViewController, UITextViewDelegate {
    private let onReview: ([String]) -> Void
    private let namesView = UITextView()
    private let characterCountLabel = UILabel()

    init(onReview: @escaping ([String]) -> Void) {
        self.onReview = onReview
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Import multiple names"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Review",
            style: .done,
            target: self,
            action: #selector(review)
        )

        let instructions = UILabel()
        instructions.numberOfLines = 0
        instructions.font = .preferredFont(forTextStyle: .body)
        instructions.text =
            "Enter canonical Handshake names separated by spaces only. Up to 10,000 unique names may be imported at once. Commas, tabs, line breaks, uppercase letters, trailing dots, and duplicate names are rejected."

        namesView.delegate = self
        namesView.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        namesView.autocapitalizationType = .none
        namesView.autocorrectionType = .no
        namesView.spellCheckingType = .no
        namesView.smartDashesType = .no
        namesView.smartQuotesType = .no
        namesView.smartInsertDeleteType = .no
        namesView.keyboardType = .asciiCapable
        namesView.layer.borderColor = UIColor.separator.cgColor
        namesView.layer.borderWidth = 1
        namesView.layer.cornerRadius = 8
        namesView.accessibilityIdentifier = "wallet.import-multiple-hns-names.text"
        namesView.accessibilityLabel = "Space-separated Handshake names"

        characterCountLabel.font = .preferredFont(forTextStyle: .caption1)
        characterCountLabel.textColor = .secondaryLabel
        characterCountLabel.textAlignment = .right
        updateCharacterCount()

        let stack = UIStackView(arrangedSubviews: [instructions, namesView, characterCountLabel])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
        ])
        namesView.becomeFirstResponder()
    }

    func textViewDidChange(_ textView: UITextView) {
        updateCharacterCount()
    }

    func textView(
        _ textView: UITextView,
        shouldChangeTextIn range: NSRange,
        replacementText text: String
    ) -> Bool {
        guard let current = textView.text,
              let swiftRange = Range(range, in: current) else { return false }
        return current.replacingCharacters(in: swiftRange, with: text).count <=
            maximumMultipleWalletNameInputCharacters
    }

    private func updateCharacterCount() {
        characterCountLabel.text =
            "\(namesView.text.count) / \(maximumMultipleWalletNameInputCharacters) characters"
    }

    @objc private func cancel() {
        scrubInput()
        dismiss(animated: true)
    }

    @objc private func review() {
        guard let names = parseSpaceSeparatedWalletNames(namesView.text ?? "") else {
            let alert = UIAlertController(
                title: "Check the name list",
                message: "Use 1–10,000 unique canonical names separated by ASCII spaces only. Each name may contain lowercase letters or digits, with hyphens and underscores only inside the name, and must be at most 63 bytes.",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
            return
        }
        scrubInput()
        dismiss(animated: true) { [onReview] in onReview(names) }
    }

    private func scrubInput() {
        namesView.text = nil
        view.endEditing(true)
    }
}

@MainActor
private final class WalletMultipleNameImportReviewViewController: UIViewController {
    private let names: [String]
    private let onImport: () -> Void

    init(names: [String], onImport: @escaping () -> Void) {
        self.names = names
        self.onImport = onImport
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Review \(names.count) names"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Import",
            style: .done,
            target: self,
            action: #selector(confirmImport)
        )

        let summary = UILabel()
        summary.numberOfLines = 0
        summary.font = .preferredFont(forTextStyle: .body)
        summary.text =
            "Confirm the complete list below. All \(names.count) names will be imported atomically; if any name cannot be validated, none are added."

        let list = UITextView()
        list.isEditable = false
        list.isSelectable = true
        list.font = .monospacedSystemFont(ofSize: 14, weight: .regular)
        list.text = names.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n")
        list.layer.borderColor = UIColor.separator.cgColor
        list.layer.borderWidth = 1
        list.layer.cornerRadius = 8
        list.accessibilityIdentifier = "wallet.import-multiple-hns-names.review"
        list.accessibilityLabel = "Complete list of \(names.count) Handshake names to import"

        let stack = UIStackView(arrangedSubviews: [summary, list])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -20),
        ])
    }

    @objc private func cancel() {
        dismiss(animated: true)
    }

    @objc private func confirmImport() {
        dismiss(animated: true) { [onImport] in onImport() }
    }
}

@MainActor
final class HandshakeReceiveQrViewController: UIViewController {
    var onShowNameAddress: (() -> Void)?
    private let address: String
    private var image: UIImage?

    init(address: String) {
        self.address = address
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .formSheet
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        image = Self.qrImage("handshake:\(address)")
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.accessibilityLabel = "Handshake payment QR code"
        let addressLabel = UILabel()
        addressLabel.text = address
        addressLabel.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        addressLabel.adjustsFontSizeToFitWidth = true
        addressLabel.minimumScaleFactor = 0.45
        addressLabel.numberOfLines = 1
        addressLabel.textAlignment = .center
        let copy = button("Copy address", #selector(copyAddress))
        let share = button("Save or share QR code", #selector(shareQr))
        let name = button("Name-transfer address", #selector(showNameAddress))
        let done = button("Done", #selector(done))
        let stack = UIStackView(arrangedSubviews: [imageView, addressLabel, copy, share, name, done])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
            imageView.heightAnchor.constraint(equalTo: imageView.widthAnchor),
        ])
    }

    private func button(_ title: String, _ action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        var configuration = UIButton.Configuration.filled()
        configuration.title = title
        button.configuration = configuration
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    @objc private func copyAddress() {
        UIPasteboard.general.setItems([[UTType.plainText.identifier: address]], options: [.localOnly: true])
    }
    @objc private func shareQr(_ sender: UIButton) {
        guard let image else { return }
        let activity = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = sender
        present(activity, animated: true)
    }
    @objc private func showNameAddress() { onShowNameAddress?() }
    @objc private func done() { dismiss(animated: true) }

    private static func qrImage(_ value: String) -> UIImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

@MainActor
final class HandshakeQrScannerViewController:
    UIViewController,
    @preconcurrency AVCaptureMetadataOutputObjectsDelegate
{
    var onResult: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var completed = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let close = UIButton(type: .system)
        close.setTitle("Cancel", for: .normal)
        close.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        close.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(close)
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
        ])
        requestCameraAndStart()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    private func requestCameraAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configureCapture()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async { if granted { self?.configureCapture() } else { self?.cancel() } }
            }
        default: cancel()
        }
    }

    private func configureCapture() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { cancel(); return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { cancel(); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        preview = layer
        session.startRunning()
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !completed,
              let value = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        completed = true
        session.stopRunning()
        onResult?(value)
    }

    @objc private func cancel() {
        if session.isRunning { session.stopRunning() }
        dismiss(animated: true)
    }
}
