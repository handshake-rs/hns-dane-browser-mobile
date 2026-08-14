import Security
import UIKit

/// First native wallet-control surface. It intentionally has no WebKit
/// provider, approvals, value movement, settlement, or marketplace controls.
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
    private var readGeneration: UInt64 = 0
    private var synchronizedReadsAvailable = false
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

    private let statusLabel = UILabel()
    private let accountLabel = UILabel()
    private let readStatusLabel = UILabel()
    private let balanceLabel = UILabel()
    private let receiveLabel = UILabel()
    private let historyLabel = UILabel()
    private let namesLabel = UILabel()
    private let recoveryTitle = UILabel()
    private let recoveryTextView = UITextView()
    private let createButton = UIButton(type: .system)
    private let restoreButton = UIButton(type: .system)
    private let openButton = UIButton(type: .system)
    private let lockButton = UIButton(type: .system)
    private let confirmRecoveryButton = UIButton(type: .system)
    private let refreshButton = UIButton(type: .system)
    private let synchronizeButton = UIButton(type: .system)
    private let deleteButton = UIButton(type: .system)

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
        resumeWalletLifecycle()
    }

    override func viewWillDisappear(_ animated: Bool) {
        walletAuthorityRequested = false
        protectWalletLifecycle()
        super.viewWillDisappear(animated)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
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
        let introduction = UILabel()
        introduction.font = .preferredFont(forTextStyle: .body)
        introduction.adjustsFontForContentSizeCategory = true
        introduction.numberOfLines = 0
        introduction.text = "One local Handshake account on \(network.title). Lifecycle controls are always local. A scoped companion may additionally enable read-only balance, receive, history, and tracked-name synchronization. Sending, HNSA/HNSR, providers, swaps, and marketplaces remain unavailable."

        configureSummaryLabel(statusLabel, identifier: "wallet.status")
        configureSummaryLabel(accountLabel, identifier: "wallet.account")
        configureSummaryLabel(readStatusLabel, identifier: "wallet.read-status")
        configureSummaryLabel(balanceLabel, identifier: "wallet.balance")
        configureSummaryLabel(receiveLabel, identifier: "wallet.receive")
        configureSummaryLabel(historyLabel, identifier: "wallet.history")
        configureSummaryLabel(namesLabel, identifier: "wallet.names")

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
            title: "Synchronize read-only wallet",
            action: #selector(synchronizeWalletReads)
        )
        configureButton(
            deleteButton,
            title: "Delete confirmed wallet",
            action: #selector(requestConfirmedWalletDeletion)
        )
        deleteButton.configuration?.baseBackgroundColor = .systemRed
        deleteButton.accessibilityIdentifier = "wallet.delete-confirmed"

        let actions = UIStackView(arrangedSubviews: [
            createButton,
            restoreButton,
            openButton,
            lockButton,
            confirmRecoveryButton,
            refreshButton,
            synchronizeButton,
            deleteButton,
        ])
        actions.axis = .vertical
        actions.spacing = 10

        let content = UIStackView(arrangedSubviews: [
            introduction,
            statusLabel,
            accountLabel,
            readStatusLabel,
            balanceLabel,
            receiveLabel,
            historyLabel,
            namesLabel,
            actions,
            recoveryTitle,
            recoveryTextView,
        ])
        content.translatesAutoresizingMaskIntoConstraints = false
        content.axis = .vertical
        content.spacing = 16

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)
        scrollView.addSubview(content)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 20),
            content.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -20),
            content.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 20),
            content.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -24),
            content.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -40),
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

    @objc private func createWallet() {
        presentBirthdayPrompt(title: "Create wallet") { [weak self] birthdayHeight in
            self?.performWalletOperation {
                guard let self else { return }
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
                        birthdayHeight: birthdayHeight
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
                if let wallet {
                    controller = wallet
                } else {
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
            try installWalletReadBootstrapIfAvailable()
        }
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
        readStatusLabel.text = "Synchronizing read-only HNS wallet data…"
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome: WalletHnsReadOutcome
            do {
                outcome = .success(try wallet.synchronizeHnsReads())
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
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                ) else {
                    return
                }
                self.isOperating = false
                switch outcome {
                case .success(let snapshot):
                    self.publish(snapshot)
                case .failure(let detail):
                    self.readStatusLabel.text = "Read synchronization failed. The wallet was locked; unlock before retrying."
                    self.clearReadProjection()
                    self.showErrorMessage(detail)
                }
                self.refreshButtonStates()
            }
        }
    }

    @objc private func requestConfirmedWalletDeletion() {
        guard presentedViewController == nil else { return }
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
                message: "Unlock the non-value Handshake wallet before deleting it."
            )
        }
        let accounts = try wallet.accounts()
        guard accounts.count == 1,
              let account = accounts.first,
              account.module == "handshake",
              account.receiveDisplay == nil,
              walletAccountIDIsCanonical(account.accountId) else {
            throw NativeWalletBridgeError.invalidOutput(
                "confirmed deletion requires one exact non-value HNS account"
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
        readStatusLabel.text = presentation.status
        balanceLabel.text = presentation.balance
        receiveLabel.text = presentation.receive
        historyLabel.text = presentation.history
        namesLabel.text = presentation.names
    }

    private func clearReadProjection() {
        balanceLabel.text = "Confirmed spendable balance: unavailable."
        receiveLabel.text = "Receive address: unavailable."
        historyLabel.text = "Transaction history: unavailable."
        namesLabel.text = "Tracked names: unavailable."
    }

    private func replaceWallet(
        with controller: RustNativeWallet,
        reopenedFromDurableStorage: Bool
    ) {
        guard wallet !== controller else { return }
        try? wallet?.lock()
        wallet?.close()
        wallet = controller
        walletAuthorityGeneration &+= 1
        walletWasReopenedFromDurableStorage = reopenedFromDurableStorage
    }

    private func installWalletReadBootstrapIfAvailable() throws {
        guard let authority = currentWalletReadBootstrapAuthority(),
              let wallet,
              (try wallet.hasHnsReads()) == false else {
            return
        }
        _ = try attemptWalletReadBootstrap(
            expectedAuthority: authority,
            source: readBootstrapSource,
            currentState: { [self] in currentWalletReadBootstrapState() },
            install: { currentAuthority, configuration in
                try wallet.configureHnsReads(
                    configuration,
                    currentAuthority: currentAuthority
                )
            }
        )
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

    private func performWalletOperation(_ operation: () throws -> Void) {
        guard storageLease != nil else {
            showErrorMessage(retirementInFlight
                ? "Wallet protection is still finishing. Try again after it completes."
                : "Another wallet screen owns this network's local wallet storage.")
            return
        }
        guard !isOperating else { return }
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
        guard storageLease != nil else {
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
            let status = try wallet.status()
            let enabledModulesAreAllowed = hasHnsReads
                ? status.enabledModules == ["handshake"]
                : status.enabledModules.isEmpty
            guard enabledModulesAreAllowed,
                  !status.mainnetSettlementEnabled else {
                throw NativeWalletBridgeError.invalidOutput(
                    "value-capable modules are not permitted in this mobile slice"
                )
            }
            statusLabel.text = status.locked
                ? "Status: locked. Sending and marketplace controls are unavailable."
                : "Status: unlocked · wallet \(status.activeWallet ?? "unknown"). Sending and marketplace controls are unavailable."
            if status.locked {
                accountLabel.text = "Account: unlock to view the local HNS account identity."
                setReadAvailability(false, message: hasHnsReads
                    ? "Read-only synchronization is configured; unlock the wallet to synchronize."
                    : "Read-only synchronization requires a scoped companion credential that this build does not install.")
            } else {
                let accounts = try wallet.accounts()
                guard accounts.count == 1,
                      let account = accounts.first,
                      account.module == "handshake",
                      account.receiveDisplay == nil else {
                    throw NativeWalletBridgeError.invalidOutput(
                        "native wallet must expose exactly one non-value HNS account"
                    )
                }
                accountLabel.text = "Account: \(account.label) · \(account.module) · \(account.accountId)"
                if persistentWalletExists,
                   walletAccountIDIsCanonical(account.accountId) {
                    confirmedDeletionAccountID = account.accountId
                }
                setReadAvailability(hasHnsReads, message: hasHnsReads
                    ? "Read-only HNS synchronization is ready."
                    : "Read-only synchronization requires a scoped companion credential that this build does not install.")
            }
        } catch {
            statusLabel.text = "Status unavailable."
            accountLabel.text = "Account unavailable."
            setReadAvailability(false, message: "Read-only synchronization status is unavailable.")
        }
        refreshButtonStates()
    }

    private func setReadAvailability(_ available: Bool, message: String) {
        synchronizedReadsAvailable = available
        readStatusLabel.text = message
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
        deleteButton.isEnabled = ownsStorage &&
            protectedStorageIsAvailable &&
            hasWallet &&
            !hasIncompleteWallet &&
            persistentWalletExists &&
            confirmedDeletionAccountID != nil &&
            walletLifecycleMayAcquireStorage &&
            viewIfLoaded?.window != nil &&
            !retirementInFlight &&
            !isOperating
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

    private func presentBirthdayPrompt(
        title: String,
        completion: @escaping (UInt64) -> Void
    ) {
        let alert = UIAlertController(
            title: title,
            message: "Enter an honest earliest block height. Use 0 to scan from genesis.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.text = "0"
            field.keyboardType = .numberPad
            field.accessibilityIdentifier = "wallet.create.birthday"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Create", style: .default) { [weak self, weak alert] _ in
            guard let text = alert?.textFields?.first?.text,
                  let birthday = UInt64(text) else {
                self?.showErrorMessage("Enter a valid birthday height.")
                return
            }
            completion(birthday)
        })
        present(alert, animated: true)
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
              walletLifecycleMayAcquireStorage else {
            return
        }
        do {
            let path = try walletDatabasePath()
            storageLease = WalletStorageLeaseRegistry.acquire(path: path)
            protectedStorageIsAvailable = storageLease != nil
        } catch {
            protectedStorageIsAvailable = false
        }
    }

    private func invalidateReadOperation() {
        readGeneration &+= 1
        isOperating = false
        synchronizedReadsAvailable = false
        if isViewLoaded {
            clearReadProjection()
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
    let receive: String
    let history: String
    let names: String
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
                    "\(codeLabel(transaction.status)) · \(displayAmount(transaction))",
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
            let entries = names.map { name in
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
            }.joined(separator: "\n\n")
            trackedNames = appendRemainingCount(
                entries,
                remaining: snapshot.knownNames.count - names.count
            )
        }

        return WalletReadPresentation(
            status: "Handshake reads are ready at height \(snapshot.moduleStatus.validatedHeight). Value movement and marketplace controls are unavailable.",
            balance: "\(formatHnsBaseUnits(snapshot.balance.baseUnits)) HNS confirmed spendable",
            receive: "\(snapshot.receiveTarget.display)\nDerivation index \(snapshot.receiveTarget.derivationIndex)",
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

private enum WalletHnsReadOutcome: Sendable {
    case success(NativeHnsReadSnapshot)
    case failure(String)
}

func walletReadMayPublish(
    expectedGeneration: UInt64,
    currentGeneration: UInt64,
    expectedLease: WalletStorageLeaseToken,
    currentLease: WalletStorageLeaseToken?,
    expectedWalletIdentity: ObjectIdentifier,
    currentWalletIdentity: ObjectIdentifier?,
    viewIsVisible: Bool
) -> Bool {
    expectedGeneration == currentGeneration &&
        expectedLease == currentLease &&
        expectedWalletIdentity == currentWalletIdentity &&
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
