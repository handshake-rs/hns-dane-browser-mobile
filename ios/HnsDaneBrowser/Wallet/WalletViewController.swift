import Security
import UIKit
import UniformTypeIdentifiers

/// Native wallet-control surface.  Every HNS peer, consensus, block scan,
/// signing, and broadcast operation remains in the Rust controller; UIKit
/// only requests a local native action and displays its exact review result.
@MainActor
final class WalletViewController: UIViewController {
    private let network: BrowserHandshakeNetwork
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
    private var synchronizedReadsAvailable = false
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
    private weak var hnsSendFormAlert: UIAlertController?
    private weak var hnsSendApprovalAlert: UIAlertController?
    private var pendingHnsSendApproval: NativeHnsSendApproval?
    private weak var hnsValueApprovalAlert: UIAlertController?
    private var pendingHnsValueApproval: NativeHnsValueApproval?
    private var directDenuoServiceTimer: Timer?
    private var directDenuoServiceInFlight = false
    private var directDenuoStatusSnapshot: NativeDirectDenuoStatus?
    private var hnsSyncPresentationTimer: Timer?

    private let statusLabel = UILabel()
    private let accountLabel = UILabel()
    private let readStatusLabel = UILabel()
    private let balanceLabel = UILabel()
    private let paymentReceiveLabel = UILabel()
    private let nameReceiveLabel = UILabel()
    private let historyLabel = UILabel()
    private let namesLabel = UILabel()
    private let nameImportStatusLabel = UILabel()
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
    private let dashboardStack = UIStackView()

    init(network: BrowserHandshakeNetwork) {
        self.network = network
        self.keychain = WalletKeychainStore(network: network)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Wallet"
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
        refreshState()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        walletAuthorityRequested = true
        if UIApplication.shared.applicationState == .active,
           UIApplication.shared.isProtectedDataAvailable,
           !UIScreen.main.isCaptured {
            walletLifecycleSuspended = false
        }
        startHnsSyncPresentationWatcher()
        resumeWalletLifecycle()
    }

    override func viewWillDisappear(_ animated: Bool) {
        walletAuthorityRequested = false
        stopHnsSyncPresentationWatcher()
        protectWalletLifecycle()
        super.viewWillDisappear(animated)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        hnsSyncPresentationTimer?.invalidate()
        directDenuoServiceTimer?.invalidate()
        pendingHnsSendApproval?.actionToken.discard()
        pendingHnsSendApproval = nil
        pendingHnsValueApproval?.actionToken.discard()
        pendingHnsValueApproval = nil
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

    private func configureView() {
        configureSummaryLabel(statusLabel, identifier: "wallet.status")
        configureSummaryLabel(accountLabel, identifier: "wallet.account")
        configureSummaryLabel(readStatusLabel, identifier: "wallet.read-status")
        configureSummaryLabel(balanceLabel, identifier: "wallet.balance")
        configureSummaryLabel(paymentReceiveLabel, identifier: "wallet.receive")
        configureSummaryLabel(nameReceiveLabel, identifier: "wallet.name-receive")
        configureSummaryLabel(historyLabel, identifier: "wallet.history")
        configureSummaryLabel(namesLabel, identifier: "wallet.names")
        configureSummaryLabel(nameImportStatusLabel, identifier: "wallet.name-import-status")

        recoveryTitle.font = .preferredFont(forTextStyle: .headline)
        recoveryTitle.adjustsFontForContentSizeCategory = true
        recoveryTitle.text = "One-time recovery phrase — record it now"
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
            title: "I saved the recovery phrase",
            action: #selector(confirmRecoverySaved)
        )
        configureButton(refreshButton, title: "Refresh status", action: #selector(refreshWallet))
        configureButton(
            synchronizeButton,
            title: "Synchronize HNS wallet",
            action: #selector(synchronizeWalletReads)
        )
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
    }

    private func renderSynchronizingWalletDashboard() {
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
            title: "NO WALLET · \(network.title)",
            body: [statusLabel, accountLabel],
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Get started",
            body: [createButton, restoreButton]
        ))
    }

    private func renderRecoveryDashboard() {
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "RECOVERY PHRASE",
            body: [statusLabel],
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
            title: "● LOCKED · \(network.title)",
            body: [statusLabel, accountLabel],
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Wallet access",
            body: [openButton]
        ))
        dashboardStack.addArrangedSubview(tileHeading("Explore"))
        dashboardStack.addArrangedSubview(dashboardTileRow(
            dashboardTile(
                title: "Names",
                summary: "Unlock to inspect names",
                action: { [weak self] in self?.showNamesDashboard() }
            ),
            dashboardTile(
                title: "Shakedex",
                summary: "Unlock and synchronize",
                action: { [weak self] in self?.showShakedexDashboard() }
            )
        ))
        dashboardStack.addArrangedSubview(dashboardTileRow(
            dashboardTile(
                title: "Activity",
                summary: "Inspect wallet history",
                action: { [weak self] in self?.showActivityDetails() }
            ),
            dashboardTile(
                title: "Wallet",
                summary: "Open and unlock",
                action: { [weak self] in self?.showWalletManagement() }
            )
        ))
    }

    private func renderUnlockedWalletDashboard() {
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "● UNLOCKED · \(network.title)",
            body: [statusLabel, accountLabel],
            accent: .systemCyan
        ))

        let receive = dashboardButton(
            title: "Receive",
            action: #selector(showPaymentReceiveAddress),
            enabled: receiveTargets != nil && directHnsValueAvailable && !isOperating
        )
        let send = dashboardButton(
            title: "Send",
            action: #selector(showHnsSendForm),
            accent: .systemIndigo,
            enabled: synchronizedReadsAvailable && directHnsValueAvailable && !isOperating
        )
        let sync = dashboardButton(
            title: "Sync",
            action: #selector(synchronizeWalletReads),
            enabled: synchronizeButton.isEnabled
        )
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "HNS balance",
            body: [balanceLabel, dashboardButtonRow([receive, send, sync])],
            accent: .systemCyan
        ))

        if !synchronizedReadsAvailable {
            dashboardStack.addArrangedSubview(dashboardCard(
                title: "Sync needed",
                body: [readStatusLabel],
                accent: .systemOrange
            ))
        }

        dashboardStack.addArrangedSubview(tileHeading("Explore"))
        dashboardStack.addArrangedSubview(dashboardTileRow(
            dashboardTile(
                title: "Names",
                summary: synchronizedReadsAvailable && directHnsValueAvailable
                    ? "Track, transfer, and manage" : "Sync required",
                action: { [weak self] in self?.showNamesDashboard() }
            ),
            dashboardTile(
                title: "Shakedex",
                summary: synchronizedReadsAvailable && directHnsValueAvailable
                    ? "Offers and purchase steps" : "Sync required",
                action: { [weak self] in self?.showShakedexDashboard() }
            )
        ))
        dashboardStack.addArrangedSubview(dashboardTileRow(
            dashboardTile(
                title: "Activity",
                summary: "Transactions and synchronized evidence",
                action: { [weak self] in self?.showActivityDetails() }
            ),
            dashboardTile(
                title: "Wallet",
                summary: "Security and lifecycle",
                action: { [weak self] in self?.showWalletManagement() }
            )
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: "Recent activity",
            body: [historyLabel, dashboardButton(
                title: "View activity",
                action: #selector(showWalletActivity)
            )]
        ))
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
        let alert = UIAlertController(
            title: "Receive HNS",
            message: [paymentReceiveLabel.text, nameReceiveLabel.text]
                .compactMap { $0 }
                .joined(separator: "\n\n"),
            preferredStyle: .alert
        )
        if let paymentReceiveAddress = receiveTargets?.paymentAddress {
            alert.addAction(UIAlertAction(title: "Copy payment address", style: .default) { _ in
                UIPasteboard.general.setItems(
                    [[UTType.plainText.identifier: paymentReceiveAddress]],
                    options: [.localOnly: true]
                )
            })
        }
        if let nameReceiveAddress = receiveTargets?.nameTransferAddress {
            alert.addAction(UIAlertAction(title: "Copy name-transfer address", style: .default) { _ in
                UIPasteboard.general.setItems(
                    [[UTType.plainText.identifier: nameReceiveAddress]],
                    options: [.localOnly: true]
                )
            })
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func showHnsSendForm() {
        guard !isOperating,
              walletIsUnlocked,
              directHnsValueAvailable,
              synchronizedReadsAvailable,
              presentedViewController == nil else {
            return
        }
        let alert = UIAlertController(
            title: "Send HNS",
            message: "A direct peer synchronization runs before review. The maximum fee is a cap; the wallet selects an HSD-compatible network fee at or below it. Use a cap of at least 0.05 HNS for the next mainnet test.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "Recipient address"
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.isSecureTextEntry = false
            field.accessibilityIdentifier = "wallet.send.recipient"
        }
        alert.addTextField { field in
            field.placeholder = "Amount in HNS"
            field.keyboardType = .decimalPad
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.send.amount"
        }
        alert.addTextField { field in
            field.placeholder = "Maximum fee cap in HNS (for example, 0.05)"
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
        ].joined(separator: "\n\n")
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
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<WalletHnsPostBroadcastResult<NativeHnsSendReceipt, NativeHnsReadSnapshot>, Error> = Result {
                let receipt = try wallet.approveHnsSend(approval.actionToken)
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
                        self.readStatusLabel.text = "HNS transaction \(result.receipt.txid) was written to connected peers, but post-broadcast verification failed. Its signed workflow remains saved. Unlock and synchronize before another send; remote mempool admission is not verified."
                    } else {
                        self.readStatusLabel.text = "HNS transaction \(result.receipt.txid) was written to connected peers, but the refreshed verified snapshot did not prove remote mempool admission. The signed transaction remains saved for exact-byte recovery. Do not prepare another send; keep the wallet open and synchronize again."
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
        let alert = UIAlertController(
            title: "Names",
            message: "\(namesLabel.text ?? "Tracked names: unavailable.")\n\n\(nameImportStatusLabel.text ?? "")",
            preferredStyle: .alert
        )
        if importNameButton.isEnabled {
            alert.addAction(UIAlertAction(title: "Track exact HNS name", style: .default) { [weak self] _ in
                self?.requestExactHnsNameImport()
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
        if shakedexAvailable {
            alert.addAction(UIAlertAction(title: "Create fixed-price offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showCreateOfferForm() }
            })
            alert.addAction(UIAlertAction(title: "Cancel offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showCancelOfferForm() }
            })
            alert.addAction(UIAlertAction(title: "Recover name from offer", style: .default) { [weak self] _ in
                self?.afterWalletMenuDismissal { [weak self] in self?.showRecoverNameForm() }
            })
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func showShakedexDashboard() {
        guard presentedViewController == nil else { return }
        let denuoStatus = shakedexActionMayStart ? directDenuoStatusSnapshot : nil
        let transportLine: String
        if let denuoStatus {
            let listener = denuoStatus.listenerPort.map { "listening on \($0)" }
                ?? "listener unavailable"
            let peer = denuoStatus.peerEndpoint.map { "paired with \($0)" }
                ?? "no paired peer"
            transportLine = "\(listener); \(peer)."
        } else {
            transportLine = "Direct-Denuo transport unavailable while locked or unsynchronized."
        }
        let alert = UIAlertController(
            title: "Shakedex",
            message: shakedexActionMayStart
                ? "Offers and purchase steps remain in the direct native HNS controller. \(transportLine) No page or provider can request them."
                : "Unlock and synchronize the direct HNS wallet before querying offers or preparing a purchase step.",
            preferredStyle: .alert
        )
        if shakedexActionMayStart {
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
                self?.afterWalletMenuDismissal { [weak self] in self?.showPairDirectDenuoForm() }
            })
            if denuoStatus?.listenerPort == nil {
                alert.addAction(UIAlertAction(title: "Retry listener", style: .default) { [weak self] _ in
                    self?.retryDirectDenuoListener()
                })
            }
            if denuoStatus?.peerEndpoint != nil {
                alert.addAction(UIAlertAction(title: "Disconnect peer", style: .destructive) { [weak self] _ in
                    self?.disconnectDirectDenuoPeer()
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
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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

    private func showCreateOfferForm() {
        collectHnsValueForm(
            title: "Create fixed-price offer",
            fields: [
                .init(label: "Exact name", placeholder: "name"),
                .init(label: "Price in HNS", placeholder: "1", numeric: true),
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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
                .init(label: "Maximum fee cap in HNS", placeholder: "0.05", numeric: true),
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

    private func showPairDirectDenuoForm() {
        collectHnsValueForm(
            title: "Pair direct Denuo peer",
            fields: [.init(label: "IP-literal endpoint", placeholder: "192.0.2.1:12038")]
        ) { [weak self] values in
            guard let self, let endpoint = values.first else { return }
            self.connectDirectDenuoPeer(endpoint)
        }
    }

    private func connectDirectDenuoPeer(_ endpoint: String) {
        runDirectDenuoOperation(status: "Pairing the explicit direct-Denuo endpoint…") {
            try $0.connectDirectDenuo(endpoint: endpoint)
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let connection):
                switch connection.outcome {
                case .connected:
                    self.readStatusLabel.text =
                        "Direct-Denuo peer connected at \(connection.peerEndpoint ?? "unknown endpoint")."
                case .replaced:
                    self.readStatusLabel.text =
                        "Direct-Denuo peer replaced with \(connection.peerEndpoint ?? "unknown endpoint")."
                case .unavailable:
                    self.readStatusLabel.text = "Direct-Denuo transport is unavailable."
                case .locked:
                    self.readStatusLabel.text = "Unlock the wallet before pairing a direct-Denuo peer."
                case .connectionFailed:
                    self.readStatusLabel.text = "The explicit direct-Denuo endpoint could not be reached."
                case .exchangeFailed:
                    self.readStatusLabel.text = "The peer connected but rejected the bounded Denuo exchange."
                }
            case .failure(let error):
                self.readStatusLabel.text = "Direct-Denuo pairing failed without changing wallet or chain state."
                self.showError(error)
            }
        }
    }

    private func retryDirectDenuoListener() {
        runDirectDenuoOperation(status: "Retrying the wallet-owned direct-Denuo listener…") {
            try $0.retryDirectDenuoListener()
            return true
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                self.readStatusLabel.text = "The wallet-owned direct-Denuo listener is ready."
            case .failure(let error):
                self.readStatusLabel.text = "The direct-Denuo listener remains unavailable; the HNS wallet is unchanged."
                self.showError(error)
            }
        }
    }

    private func disconnectDirectDenuoPeer() {
        runDirectDenuoOperation(status: "Disconnecting the direct-Denuo peer…") {
            try $0.disconnectDirectDenuo()
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let disconnected):
                self.readStatusLabel.text = disconnected
                    ? "Direct-Denuo peer disconnected."
                    : "No direct-Denuo peer was connected."
            case .failure(let error):
                self.readStatusLabel.text = "Direct-Denuo disconnect could not be verified."
                self.showError(error)
            }
        }
    }

    private func runDirectDenuoOperation<ResultValue>(
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

    private func updateDirectDenuoServiceTimer() {
        let shouldRun = walletAuthorityRequested && shakedexAvailable && walletIsUnlocked
        if !shouldRun {
            directDenuoServiceTimer?.invalidate()
            directDenuoServiceTimer = nil
            directDenuoStatusSnapshot = nil
            return
        }
        guard directDenuoServiceTimer == nil else { return }
        directDenuoServiceTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) {
            [weak self] _ in self?.serviceDirectDenuoOnce()
        }
    }

    private func serviceDirectDenuoOnce() {
        guard !directDenuoServiceInFlight,
              !isOperating,
              shakedexAvailable,
              walletAuthorityRequested,
              let wallet else { return }
        directDenuoServiceInFlight = true
        let identity = ObjectIdentifier(wallet)
        let authority = walletAuthorityGeneration
        DispatchQueue.global(qos: .utility).async {
            _ = try? wallet.serviceDirectDenuo()
            let status = try? wallet.directDenuoStatus()
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.directDenuoServiceInFlight = false
                guard self.walletAuthorityRequested,
                      self.walletAuthorityGeneration == authority,
                      self.wallet.map({ ObjectIdentifier($0) }) == identity else { return }
                self.directDenuoStatusSnapshot = status
            }
        }
    }

    private func beginHnsValueAction(_ intent: NativeHnsValueIntent) {
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
        let alert = UIAlertController(
            title: "Recent activity",
            message: historyLabel.text,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    private func showWalletManagement() {
        let alert = UIAlertController(
            title: "Wallet",
            message: "\(statusLabel.text ?? "Status unavailable.")\n\n\(accountLabel.text ?? "Account unavailable.")",
            preferredStyle: .alert
        )
        let canStopSynchronization = WalletHnsSyncPresentationCache.canRequestCancellation(
            networkID: network.rawValue
        )
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
        if deleteButton.isEnabled && !canStopSynchronization {
            alert.addAction(UIAlertAction(title: "Delete wallet", style: .destructive) { [weak self] _ in
                self?.requestConfirmedWalletDeletion()
            })
        }
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func createWallet() {
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
                    birthdayHeight: self.network.newWalletBirthdayHeight
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
            self.performWalletOperation {
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
                    self.wallet = controller
                    self.walletAuthorityGeneration &+= 1
                    self.walletWasReopenedFromDurableStorage = false
                    self.persistentWalletExists = true
                } catch {
                    controller.close()
                    try? Self.deleteWalletFiles(databasePath: path)
                    throw error
                }
            }
        })
        present(alert, animated: true)
    }

    @objc private func openOrUnlockWallet() {
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
                    controller = try RustNativeWallet.open(
                        databasePath: path,
                        databaseKey: key
                    )
                    reopenedFromDurableStorage = true
                }
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
        guard unconfirmedDatabaseKey != nil, recoverySecret != nil else { return }
        let alert = UIAlertController(
            title: "Recovery phrase saved?",
            message: "Confirm only after recording all 24 words. This phrase will not be shown again.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "I saved it", style: .default) { [weak self] _ in
            self?.persistConfirmedWallet()
        })
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
                persistentWalletExists = true
                clearRecoveryDisplay()
            } catch {
                discardWalletAndFiles()
                throw error
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

    @objc private func synchronizeWalletReads() {
        guard let lease = storageLease,
              let wallet,
              unconfirmedDatabaseKey == nil,
              synchronizedReadsAvailable,
              !isOperating else {
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = "Synchronizing direct HNS wallet data…"
        refreshButtonStates()
        let keychain = keychain
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: WalletHnsReadOutcome
            do {
                outcome = .success(
                    try Self.synchronizeDirectHnsReads(
                        wallet: wallet,
                        keychain: keychain
                    )
                )
            } catch let error as WalletProviderError where error.code == "walletSynchronizationCancelled" {
                outcome = .cancelled
            } catch {
                outcome = .failure(error.localizedDescription)
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
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success(let snapshot):
                    self.publish(snapshot)
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
                        let refreshed = try wallet.synchronizeHnsReads()
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

    @objc private func requestConfirmedWalletDeletion() {
        guard presentedViewController == nil else { return }
        if WalletHnsSyncPresentationCache.canRequestCancellation(
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

                This permanently deletes this device's confirmed wallet. The recovery phrase cannot be shown again by this app. Without a saved recovery phrase, the wallet cannot be recovered. This action is irreversible.
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
            WalletHnsSyncPresentationCache.requestCancellation(
                networkID: self.network.rawValue
            )
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

            Deletion is irreversible, and the recovery phrase cannot be shown again. Type DELETE exactly to continue.
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
               screenIsCaptured: UIScreen.main.isCaptured
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
              !UIScreen.main.isCaptured,
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
        receiveTargets = WalletReceiveTargets(snapshot: snapshot)
        readStatusLabel.text = presentation.status
        balanceLabel.text = presentation.balance
        paymentReceiveLabel.text = presentation.paymentReceive
        nameReceiveLabel.text = presentation.nameReceive
        historyLabel.text = presentation.history
        namesLabel.text = presentation.names
    }

    private func clearReadProjection() {
        receiveTargets = nil
        balanceLabel.text = "Confirmed spendable balance: unavailable."
        paymentReceiveLabel.text = "Payment receive address: unavailable."
        nameReceiveLabel.text = "Name transfer receive address: unavailable."
        historyLabel.text = "Transaction history: unavailable."
        namesLabel.text = "Tracked names: unavailable."
    }

    private func replaceWallet(
        with controller: RustNativeWallet,
        reopenedFromDurableStorage: Bool
    ) {
        guard wallet !== controller else { return }
        dismissPendingHnsSendApproval(rejectNatively: true)
        dismissPendingHnsValueApproval(rejectNatively: true)
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
        readStatusLabel.text = "Preparing the direct HNS wallet…"
        refreshButtonStates()

        let keychain = keychain
        let installNetwork = network
        DispatchQueue.global(qos: .userInitiated).async { [wallet, keychain] in
            let outcome: Result<Void, Error> = Result {
                var openingFloor = try keychain.directHnsRollbackFloorForOpen()
                defer { WalletSecretBytes.wipe(&openingFloor) }

                let install: (String?) throws -> Void = { snapshotPath in
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
                    try WalletHeaderSnapshotBootstrapper().withGenesisSnapshot { snapshot in
                        try install(snapshot.path)
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
        updateDirectDenuoServiceTimer()
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
            statusLabel.text = persistentWalletExists
                ? "Status: a confirmed wallet is ready to open."
                : "Status: no wallet has been created."
            accountLabel.text = "Account: unavailable until a wallet is opened."
            setReadAvailability(false, message: "Read-only synchronization unavailable until the wallet is open.")
            refreshButtonStates()
            return
        }
        do {
            let hasHnsReads = try wallet.hasHnsReads()
            let hasHnsValue = try wallet.hasHnsValue()
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
                ? "Status: locked. Unlock before direct HNS synchronization or sending."
                : "Status: unlocked · wallet \(status.activeWallet ?? "unknown")."
            walletIsUnlocked = !status.locked
            directHnsValueAvailable = hasHnsValue && !status.locked
            shakedexAvailable = status.shakedexEnabled && !status.locked
            updateDirectDenuoServiceTimer()
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
            }
        } catch {
            walletIsUnlocked = false
            directHnsValueAvailable = false
            shakedexAvailable = false
            updateDirectDenuoServiceTimer()
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
        lockButton.isEnabled = ownsStorage && protectedStorageIsAvailable && hasWallet && !hasIncompleteWallet && !isOperating
        confirmRecoveryButton.isEnabled = ownsStorage && hasIncompleteWallet && recoverySecret != nil && !isOperating
        refreshButton.isEnabled = ownsStorage &&
            (hasWallet || encryptedOrphanCleanupPending) &&
            !hasIncompleteWallet &&
            !isOperating
        synchronizeButton.isEnabled = ownsStorage && hasWallet && !hasIncompleteWallet && synchronizedReadsAvailable && !isOperating
        let importState = currentWalletNameImportState()
        importNameButton.isEnabled = importState.authority.map {
            walletNameImportMayStart(expected: $0, current: importState)
        } ?? false
        let canStopSynchronization = WalletHnsSyncPresentationCache.canRequestCancellation(
            networkID: network.rawValue
        )
        let syncPresentation = WalletHnsSyncPresentationCache.latest(
            networkID: network.rawValue
        )
        switch syncPresentation {
        case .preparing where canStopSynchronization:
            deleteButton.configuration?.title = "Stop synchronization"
        case .live(_) where canStopSynchronization:
            deleteButton.configuration?.title = "Stop synchronization"
        case .cancelling:
            deleteButton.configuration?.title = "Stopping synchronization…"
        case .terminal:
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
            !isOperating)
        renderWalletDashboard()
    }

    private func canStartNewWallet() throws -> Bool {
        guard storageLease != nil else {
            throw WalletProviderError(
                code: "walletStorageBusy",
                message: "Another wallet screen owns this network's local wallet storage."
            )
        }
        guard !UIScreen.main.isCaptured else {
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
        directDenuoServiceTimer?.invalidate()
        directDenuoServiceTimer = nil
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
              !UIScreen.main.isCaptured else {
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
        if UIScreen.main.isCaptured {
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
            !UIScreen.main.isCaptured
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
            statusLabel.text = "Keeping the existing HNS synchronization connected."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            readStatusLabel.text = "Preparing direct HNS synchronization…"
        case .live(let progress):
            statusLabel.text = "Keeping the existing HNS synchronization connected."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            let scanned = progress.scannedHeight ?? progress.birthdayHeight
            switch progress.stage {
            case .connecting:
                readStatusLabel.text = "Connecting verified HNS peers. Verified headers are currently at height \(progress.verifiedHeaderHeight)."
            case .headers:
                readStatusLabel.text = "Verifying HNS header agreement at height \(progress.verifiedHeaderHeight)."
            case .scanning:
                readStatusLabel.text = "Verified headers are currently at height \(progress.verifiedHeaderHeight). Scanning wallet activity at height \(scanned) of \(progress.targetHeight) from birthday height \(progress.birthdayHeight)."
            case .finalizing:
                readStatusLabel.text = "Finalizing the verified HNS wallet snapshot at height \(progress.verifiedHeaderHeight)."
            }
        case .cancelling(let progress):
            statusLabel.text = "Stopping the existing HNS synchronization now."
            accountLabel.text = "Account controls return after synchronization protection finishes."
            readStatusLabel.text = progress.map {
                "Last verified header height \($0.verifiedHeaderHeight); wallet scan height \($0.scannedHeight ?? $0.birthdayHeight) of \($0.targetHeight)."
            } ?? "No additional synchronization batch will start; the active atomic call is unwinding."
        case .terminal(let progress):
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
        return true
    }

    private func invalidateReadOperation() {
        clearWalletNameImportPrompt(dismiss: true)
        readGeneration &+= 1
        isOperating = false
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

struct WalletReadPresentation: Equatable, Sendable {
    let status: String
    let balance: String
    let paymentReceive: String
    let nameReceive: String
    let history: String
    let names: String
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
                remaining: snapshot.knownNames.count - names.count
            )
        }

        let balance = WalletHnsBalancePresenter.present(snapshot)
        let balanceText: String
        if balance.hasPendingOutgoing {
            balanceText = [
                "\(formatHnsBaseUnits(balance.spendableBaseUnits)) HNS spendable now",
                "\(formatHnsBaseUnits(balance.pendingOutgoingBaseUnits)) HNS pending outgoing",
                "Confirmed inputs are reserved until peer or chain evidence settles the pending transaction.",
            ].joined(separator: "\n")
        } else {
            balanceText = "\(formatHnsBaseUnits(balance.spendableBaseUnits)) HNS spendable now"
        }
        return WalletReadPresentation(
            status: "Direct Handshake wallet synchronized at height \(snapshot.moduleStatus.validatedHeight). Pending outgoing transactions are reflected in the available balance.",
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
        if let expired = name.expired {
            states.append(expired ? "expired" : "current")
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
