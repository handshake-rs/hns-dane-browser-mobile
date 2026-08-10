import Security
import UIKit

/// First native wallet-control surface. It intentionally has no WebKit
/// provider, approvals, value movement, settlement, or marketplace controls.
@MainActor
final class WalletViewController: UIViewController {
    private let network: BrowserHandshakeNetwork
    private let keychain: WalletKeychainStore
    private var wallet: RustNativeWallet?
    private var recoverySecret: WalletRecoverySecret?
    /// Non-nil means creation is intentionally incomplete: this key has not
    /// entered Keychain and every lifecycle exit must destroy its database.
    private var unconfirmedDatabaseKey: [UInt8]?
    private var persistentWalletExists = false
    private var protectedStorageIsAvailable = true
    private var isOperating = false
    private var resolvedDatabasePath: String?
    private var storageLease: WalletStorageLeaseToken?
    private weak var restorePhraseField: UITextField?

    private let statusLabel = UILabel()
    private let accountLabel = UILabel()
    private let recoveryTitle = UILabel()
    private let recoveryTextView = UITextView()
    private let createButton = UIButton(type: .system)
    private let restoreButton = UIButton(type: .system)
    private let openButton = UIButton(type: .system)
    private let lockButton = UIButton(type: .system)
    private let confirmRecoveryButton = UIButton(type: .system)
    private let refreshButton = UIButton(type: .system)

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
            UIScreen.capturedDidChangeNotification,
            UIApplication.userDidTakeScreenshotNotification,
        ] {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(protectWalletLifecycle),
                name: name,
                object: nil
            )
        }
        refreshState()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        acquireStorageLease()
        if storageLease != nil, unconfirmedDatabaseKey == nil {
            refreshProtectedStorageState()
        }
        refreshState()
    }

    override func viewWillDisappear(_ animated: Bool) {
        if storageLease != nil {
            protectWalletLifecycle()
            wallet?.close()
            wallet = nil
            releaseStorageLease()
        } else {
            clearRestoreInput()
            clearRecoveryDisplay()
        }
        super.viewWillDisappear(animated)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        recoverySecret?.clear()
        let lease = storageLease
        if var key = unconfirmedDatabaseKey {
            unconfirmedDatabaseKey = nil
            WalletSecretBytes.wipe(&key)
            wallet?.close()
            if lease != nil, let path = resolvedDatabasePath {
                try? Self.deleteWalletFiles(databasePath: path)
            }
        } else {
            try? wallet?.lock()
            wallet?.close()
        }
        if let lease {
            WalletStorageLeaseRegistry.release(lease)
        }
    }

    private func configureView() {
        let introduction = UILabel()
        introduction.font = .preferredFont(forTextStyle: .body)
        introduction.adjustsFontForContentSizeCategory = true
        introduction.numberOfLines = 0
        introduction.text = "One local Handshake account on \(network.title). This release exposes identity and lock controls only. Sending, names, providers, swaps, and marketplaces remain unavailable."

        configureSummaryLabel(statusLabel, identifier: "wallet.status")
        configureSummaryLabel(accountLabel, identifier: "wallet.account")

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

        let actions = UIStackView(arrangedSubviews: [
            createButton,
            restoreButton,
            openButton,
            lockButton,
            confirmRecoveryButton,
            refreshButton,
        ])
        actions.axis = .vertical
        actions.spacing = 10

        let content = UIStackView(arrangedSubviews: [
            introduction,
            statusLabel,
            accountLabel,
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
            let path = try walletDatabasePath()
            let opened = try keychain.withDatabaseKey(
                prompt: "Authenticate to open your Handshake wallet"
            ) { key -> RustNativeWallet in
                let controller = try wallet ?? RustNativeWallet.open(
                    databasePath: path,
                    databaseKey: key
                )
                try controller.unlock(databaseKey: key)
                return controller
            }
            guard let opened else {
                throw WalletProviderError(
                    code: "walletNotFound",
                    message: "No device-bound wallet key exists. Create or restore a wallet first."
                )
            }
            replaceWallet(with: opened)
            persistentWalletExists = true
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
        refreshState()
    }

    private func replaceWallet(with controller: RustNativeWallet) {
        guard wallet !== controller else { return }
        try? wallet?.lock()
        wallet?.close()
        wallet = controller
    }

    private func performWalletOperation(_ operation: () throws -> Void) {
        guard storageLease != nil else {
            showErrorMessage("Another wallet screen owns this network's local wallet storage.")
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
        guard storageLease != nil else {
            statusLabel.text = "Wallet storage is active in another screen."
            accountLabel.text = "Account unavailable. Close the other wallet screen and try again."
            refreshButtonStates()
            return
        }
        guard protectedStorageIsAvailable else {
            statusLabel.text = "Wallet protected storage is unavailable."
            accountLabel.text = "Account unavailable."
            refreshButtonStates()
            return
        }
        if unconfirmedDatabaseKey != nil {
            statusLabel.text = "Status: record and confirm the recovery phrase. Leaving this screen deletes the incomplete wallet."
            accountLabel.text = "Account: locked until recovery confirmation is complete."
            refreshButtonStates()
            return
        }
        guard let wallet else {
            statusLabel.text = persistentWalletExists
                ? "Status: a confirmed wallet is ready to open."
                : "Status: no wallet has been created."
            accountLabel.text = "Account: unavailable until a wallet is opened."
            refreshButtonStates()
            return
        }
        do {
            let status = try wallet.status()
            guard status.enabledModules.isEmpty,
                  !status.mainnetSettlementEnabled else {
                throw NativeWalletBridgeError.invalidOutput(
                    "value-capable modules are not permitted in this mobile slice"
                )
            }
            statusLabel.text = status.locked
                ? "Status: locked. Value and marketplace controls are unavailable."
                : "Status: unlocked · wallet \(status.activeWallet ?? "unknown"). Value and marketplace controls are unavailable."
            if status.locked {
                accountLabel.text = "Account: unlock to view the local HNS account identity."
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
            }
        } catch {
            statusLabel.text = "Status unavailable."
            accountLabel.text = "Account unavailable."
        }
        refreshButtonStates()
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
        refreshButton.isEnabled = ownsStorage && hasWallet && !hasIncompleteWallet && !isOperating
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
        let hasDatabase = FileManager.default.fileExists(atPath: path)
        let hasArtifacts = Self.walletFilesExist(databasePath: path)
        let hasKey = try keychain.hasDatabaseKey()
        if hasKey && hasDatabase {
            persistentWalletExists = true
            return
        }
        if hasKey || hasArtifacts {
            try keychain.deleteDatabaseKey()
            try Self.deleteWalletFiles(databasePath: path)
        }
        persistentWalletExists = false
    }

    @objc private func protectWalletLifecycle() {
        clearRestoreInput()
        guard storageLease != nil else {
            clearRecoveryDisplay()
            return
        }
        if unconfirmedDatabaseKey != nil {
            abortIncompleteWallet()
        } else {
            clearRecoveryDisplay()
            try? wallet?.lock()
        }
        if isViewLoaded {
            refreshState()
        }
    }

    private func abortIncompleteWallet() {
        if var key = unconfirmedDatabaseKey {
            unconfirmedDatabaseKey = nil
            WalletSecretBytes.wipe(&key)
        }
        discardWalletAndFiles()
    }

    private func discardWalletAndFiles() {
        clearRecoveryDisplay()
        try? wallet?.lock()
        wallet?.close()
        wallet = nil
        persistentWalletExists = false
        if let path = resolvedDatabasePath {
            try? Self.deleteWalletFiles(databasePath: path)
        }
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
        guard storageLease == nil else { return }
        do {
            let path = try walletDatabasePath()
            storageLease = WalletStorageLeaseRegistry.acquire(path: path)
            protectedStorageIsAvailable = storageLease != nil
        } catch {
            protectedStorageIsAvailable = false
        }
    }

    private func releaseStorageLease() {
        guard let storageLease else { return }
        WalletStorageLeaseRegistry.release(storageLease)
        self.storageLease = nil
    }

    private static func walletFilesExist(databasePath: String) -> Bool {
        ([databasePath] + walletSidecars(databasePath: databasePath)).contains {
            FileManager.default.fileExists(atPath: $0)
        }
    }

    nonisolated private static func deleteWalletFiles(databasePath: String) throws {
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

struct WalletStorageLeaseToken: Equatable {
    let path: String
    let owner: UUID
}

private final class WalletStorageLeaseRegistryState: @unchecked Sendable {
    let lock = NSLock()
    var owners: [String: UUID] = [:]
}

enum WalletStorageLeaseRegistry {
    private static let state = WalletStorageLeaseRegistryState()

    static func acquire(path: String) -> WalletStorageLeaseToken? {
        state.lock.lock()
        defer { state.lock.unlock() }
        guard state.owners[path] == nil else { return nil }
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
}
