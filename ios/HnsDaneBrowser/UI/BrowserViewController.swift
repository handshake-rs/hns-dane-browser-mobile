import SafariServices
import UIKit
import WebKit

@MainActor
final class BrowserViewController: UIViewController {
    private static let privacyPolicyURL = URL(
        string: "https://shakescape.com/privacy/"
    )!
    private static let sourceCodeURL = URL(
        string: "https://github.com/handshake-rs/hns-dane-browser-mobile"
    )!

#if DEBUG && targetEnvironment(simulator)
    private static let appStoreScreenshotSceneKey = "HNS_APP_STORE_SCREENSHOT_SCENE"

    private enum AppStoreScreenshotScene: String {
        case hnsPage = "hns-page"
        case proofDetails = "proof-details"
        case webPKIPage = "webpki-page"
    }
#endif

    private let process: BrowserProcess
    private let authorityAdmissionPolicy = BrowserAuthorityAdmissionPolicy()

    private let backButton = UIButton(type: .system)
    private let forwardButton = UIButton(type: .system)
    private let reloadButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private let controlsButton = UIButton(type: .system)
    private let addressField = UITextField()
    private let securityLabel = UILabel()
    private let syncLabel = UILabel()
    private let syncProgressView = UIProgressView(progressViewStyle: .bar)
    private let progressView = UIProgressView(progressViewStyle: .bar)
    private let webContainer = UIView()
    private let placeholderLabel = UILabel()

    private var coordinator: BrowserProxyCoordinator?
    private var environment: BrowserProcess.Environment?
    private var progressObservation: NSKeyValueObservation?
    private var canonicalAddress = ""
    private var pendingExternalAddress: String?
    private var pendingHandshakePayment: HandshakePaymentRequest?
    private var isForeground = false
    private var isPreparing = false
    private var isLoading = false
    private var isControlOperationInFlight = false
    private var isHeaderResetInFlight = false
    private var isDestroyed = false
    private var isProxyAdmissionGranted = false
    private var latestSyncSummary = BrowserSyncSummary.unavailable
    private var resolverCacheSummary = "Ready to clear cached resolver values."
    private weak var settingsViewController: BrowserSettingsViewController?
    private var syncStatusPollTimer: Timer?
    private var initialSyncStatusRefreshWorkItem: DispatchWorkItem?
    private var isForegroundSyncPreparing = false

#if DEBUG && targetEnvironment(simulator)
    private var appStoreScreenshotScene: AppStoreScreenshotScene?
    private var shouldPresentScreenshotProof = false
#endif

    init(process: BrowserProcess) {
        self.process = process
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        configureUI()
#if DEBUG && targetEnvironment(simulator)
        if configureAppStoreScreenshotFixtureIfRequested() { return }
#endif
        prepareRuntime()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        presentPendingHandshakePaymentIfPossible()
#if DEBUG && targetEnvironment(simulator)
        presentScreenshotProofIfNeeded()
#endif
    }

    func resumeBrowsing() {
        guard !isDestroyed else { return }
        isForeground = true
#if DEBUG && targetEnvironment(simulator)
        if appStoreScreenshotScene != nil { return }
#endif
        if let environment {
            updateSyncSummary(environment.runtime.syncSummary())
        }
        startSyncStatusPolling()
        resumeForegroundSync()
    }

    private func resumeForegroundSync() {
        process.resumeForegroundSync(
            observer: { [weak self] summary in
                guard let self, !self.isDestroyed else { return }
                self.isForegroundSyncPreparing = false
                self.updateSyncSummary(summary)
            },
            onSyncStarting: { [weak self] in
                guard let self, self.isForeground, !self.isDestroyed else { return }
                self.isForegroundSyncPreparing = true
                self.showForegroundSyncPreparation()
            }
        )
    }

    func suspendBrowsing() {
        guard !isDestroyed else { return }
        isForeground = false
        isForegroundSyncPreparing = false
        stopSyncStatusPolling()
#if DEBUG && targetEnvironment(simulator)
        if appStoreScreenshotScene != nil { return }
#endif
        // The persistent WebKit profile and its native loopback backend share
        // process lifetime. Backgrounding only pauses foreground sync.
        process.suspendForegroundSync()
    }

    func destroyBrowsing() {
        guard !isDestroyed else { return }
        isDestroyed = true
        isForeground = false
        isForegroundSyncPreparing = false
        stopSyncStatusPolling()
#if DEBUG && targetEnvironment(simulator)
        if appStoreScreenshotScene != nil { return }
#endif
        process.suspendForegroundSync()
        if let coordinator {
            coordinator.detach(delegate: self)
        }
        coordinator = nil
        isProxyAdmissionGranted = false
        environment = nil
        progressObservation = nil
    }

    func openExternalURL(_ url: URL) {
        guard !isDestroyed else { return }
        if url.scheme?.lowercased() == "handshake" {
            guard let request = HandshakePaymentURI.parse(url.absoluteString) else {
                showError(BrowserCoreError.unsupportedAddress)
                return
            }
            pendingHandshakePayment = request
            presentPendingHandshakePaymentIfPossible()
            return
        }
        guard url.isFileURL == false else {
            showError(BrowserCoreError.unsupportedAddress)
            return
        }
        if let coordinator {
            coordinator.navigate(rawValue: url.absoluteString)
        } else {
            pendingExternalAddress = url.absoluteString
        }
    }

    private func presentPendingHandshakePaymentIfPossible() {
        guard viewIfLoaded?.window != nil,
              presentedViewController == nil,
              let request = pendingHandshakePayment else { return }
        pendingHandshakePayment = nil
        let wallet = WalletViewController(
            network: process.currentNetwork,
            paymentRequest: request,
            browserProcess: process
        )
        let navigation = UINavigationController(rootViewController: wallet)
        navigation.modalPresentationStyle = .formSheet
        present(navigation, animated: true)
    }

    private func configureUI() {
        view.backgroundColor = .systemBackground

        configureButton(backButton, symbol: "chevron.backward", label: "Back", action: #selector(goBack))
        configureButton(forwardButton, symbol: "chevron.forward", label: "Forward", action: #selector(goForward))
        configureButton(reloadButton, symbol: "arrow.clockwise", label: "Reload", action: #selector(reloadOrStop))
        configureButton(shareButton, symbol: "square.and.arrow.up", label: "Share", action: #selector(sharePage))
        controlsButton.setImage(UIImage(systemName: "gearshape"), for: .normal)
        controlsButton.accessibilityLabel = "Settings"
        controlsButton.accessibilityIdentifier = "app-store-screenshot.controls"
        controlsButton.addTarget(self, action: #selector(presentBrowserSettings), for: .touchUpInside)
        backButton.isEnabled = false
        forwardButton.isEnabled = false
        shareButton.isEnabled = false
        controlsButton.isEnabled = true

        addressField.borderStyle = .roundedRect
        addressField.clearButtonMode = .whileEditing
        addressField.keyboardType = .URL
        addressField.returnKeyType = .go
        addressField.autocapitalizationType = .none
        addressField.autocorrectionType = .no
        addressField.spellCheckingType = .no
        addressField.placeholder = "Enter a web or Handshake address"
        addressField.accessibilityLabel = "Address"
        addressField.accessibilityIdentifier = "app-store-screenshot.address"
        addressField.delegate = self

        securityLabel.font = .preferredFont(forTextStyle: .caption1)
        securityLabel.adjustsFontForContentSizeCategory = true
        securityLabel.textColor = .secondaryLabel
        securityLabel.numberOfLines = 1
        securityLabel.text = "Security pending"
        securityLabel.accessibilityIdentifier = "app-store-screenshot.security"

        syncLabel.font = .preferredFont(forTextStyle: .caption2)
        syncLabel.adjustsFontForContentSizeCategory = true
        syncLabel.textColor = .tertiaryLabel
        syncLabel.numberOfLines = 0
        syncLabel.textAlignment = .left
        syncLabel.text = "Preparing runtime"
        syncLabel.accessibilityIdentifier = "app-store-screenshot.sync"

        syncProgressView.progress = 0
        syncProgressView.accessibilityLabel = "Handshake header sync progress"
        syncProgressView.accessibilityIdentifier = "hns-sync.progress"

        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.font = .preferredFont(forTextStyle: .title3)
        placeholderLabel.textColor = .secondaryLabel
        placeholderLabel.textAlignment = .center
        placeholderLabel.numberOfLines = 0
        placeholderLabel.text = "Preparing secure browsing…"

        let addressRow = UIStackView(
            arrangedSubviews: [
                backButton,
                forwardButton,
                addressField,
                reloadButton,
                shareButton,
                controlsButton,
            ]
        )
        addressRow.axis = .horizontal
        addressRow.alignment = .center
        addressRow.spacing = 8
        backButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        forwardButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        reloadButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        shareButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        controlsButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        let chrome = UIStackView(
            arrangedSubviews: [
                addressRow,
                securityLabel,
                syncProgressView,
                syncLabel,
                progressView,
            ]
        )
        chrome.axis = .vertical
        chrome.spacing = 6
        chrome.isLayoutMarginsRelativeArrangement = true
        chrome.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 10, bottom: 6, trailing: 10)

        webContainer.backgroundColor = .secondarySystemBackground
        webContainer.addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            placeholderLabel.centerXAnchor.constraint(equalTo: webContainer.centerXAnchor),
            placeholderLabel.centerYAnchor.constraint(equalTo: webContainer.centerYAnchor),
            placeholderLabel.leadingAnchor.constraint(greaterThanOrEqualTo: webContainer.leadingAnchor, constant: 24),
            placeholderLabel.trailingAnchor.constraint(lessThanOrEqualTo: webContainer.trailingAnchor, constant: -24),
        ])

        let root = UIStackView(arrangedSubviews: [chrome, webContainer])
        root.translatesAutoresizingMaskIntoConstraints = false
        root.axis = .vertical
        root.spacing = 0
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        refreshSettingsIfPresented()
    }

    private func configureButton(
        _ button: UIButton,
        symbol: String,
        label: String,
        action: Selector
    ) {
        button.setImage(UIImage(systemName: symbol), for: .normal)
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func updateCanonicalAddress(_ address: String) {
        canonicalAddress = address
        guard !addressField.isFirstResponder else { return }
        addressField.text = BrowserAddressPresentation.displayText(for: address)
    }

#if DEBUG && targetEnvironment(simulator)
    /// Provides deterministic, offline App Store artwork without shipping a
    /// screenshot-only code path in Release builds. UI tests are the only caller.
    private func configureAppStoreScreenshotFixtureIfRequested() -> Bool {
        guard let rawScene = ProcessInfo.processInfo.environment[
            Self.appStoreScreenshotSceneKey
        ], let scene = AppStoreScreenshotScene(rawValue: rawScene) else {
            return false
        }

        appStoreScreenshotScene = scene
        UIView.setAnimationsEnabled(false)
        addressField.isUserInteractionEnabled = false
        progressView.progress = 0
        progressView.isHidden = true
        placeholderLabel.isHidden = true
        backButton.isEnabled = false
        forwardButton.isEnabled = false
        reloadButton.isEnabled = true
        shareButton.isEnabled = true

        let webView = WKWebView(frame: .zero, configuration: screenshotWebViewConfiguration())
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.accessibilityIdentifier = "app-store-screenshot.ready.\(scene.rawValue)"
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webContainer.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: webContainer.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: webContainer.trailingAnchor),
            webView.topAnchor.constraint(equalTo: webContainer.topAnchor),
            webView.bottomAnchor.constraint(equalTo: webContainer.bottomAnchor),
        ])

        switch scene {
        case .hnsPage, .proofDetails:
            updateCanonicalAddress(
                scene == .proofDetails
                    ? "https://shakeshift/"
                    : "https://denuoweb/"
            )
            updateSecuritySummary(
                BrowserSecuritySummary(
                    level: .handshakeDANE,
                    detail: "DANE verified · authoritative DoH"
                )
            )
            updateSyncSummary(
                BrowserSyncSummary(
                    headline: "Handshake headers current",
                    detail: "Local height 335942 · effective target 335942 · freshness current",
                    syncStatusSchemaVersion: 3,
                    status: "up_to_date",
                    network: "mainnet",
                    bestHeight: 335_942,
                    bestPeerHeight: 335_942,
                    estimatedTipHeight: 335_942,
                    effectiveTargetHeight: 335_942,
                    lagBlocks: 0,
                    freshness: "current",
                    freshnessThresholdBlocks: 2,
                    treeIntervalBlocks: 36,
                    authoritativeTreeRootHeight: 335_917,
                    localTreeRootHeight: 335_917,
                    treeRootReady: true,
                    blocksUntilAuthoritativeTreeRoot: 0,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                )
            )
            shouldPresentScreenshotProof = scene == .proofDetails
        case .webPKIPage:
            updateCanonicalAddress("https://shakescape.com/")
            updateSecuritySummary(
                BrowserSecuritySummary(
                    level: .webPKI,
                    detail: "Automatic ICANN trust · Rust proxy"
                )
            )
            updateSyncSummary(
                BrowserSyncSummary(
                    headline: "Handshake headers current",
                    detail: "Local height 335942 · effective target 335942 · freshness current",
                    syncStatusSchemaVersion: 3,
                    status: "up_to_date",
                    network: "mainnet",
                    bestHeight: 335_942,
                    bestPeerHeight: 335_942,
                    estimatedTipHeight: 335_942,
                    effectiveTargetHeight: 335_942,
                    lagBlocks: 0,
                    freshness: "current",
                    freshnessThresholdBlocks: 2,
                    treeIntervalBlocks: 36,
                    authoritativeTreeRootHeight: 335_917,
                    localTreeRootHeight: 335_917,
                    treeRootReady: true,
                    blocksUntilAuthoritativeTreeRoot: 0,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                )
            )
        }

        refreshSettingsIfPresented()
        webView.loadHTMLString(appStoreScreenshotHTML(for: scene), baseURL: nil)
        return true
    }

    private func screenshotWebViewConfiguration() -> WKWebViewConfiguration {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        return configuration
    }

    private func appStoreScreenshotHTML(for scene: AppStoreScreenshotScene) -> String {
        let isWebPKI = scene == .webPKIPage
        let eyebrow = isWebPKI ? "OPEN WEB" : "HANDSHAKE NATIVE"
        let title = isWebPKI
            ? "One browser for Handshake and the open web"
            : "Browse beyond traditional DNS"
        let summary = isWebPKI
            ? "Ordinary HTTPS gets automatic DNSSEC/TLSA discovery, with WebPKI only when the validating resolver proves it is appropriate."
            : "Resolve Handshake names locally, validate DNSSEC and DANE, and inspect the proof behind each result."
        let badge = isWebPKI ? "Automatic DANE / WebPKI" : "DANE certificate verified"
        let badgeIcon = isWebPKI ? "●" : "✓"

        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
          <style>
            :root { color-scheme: dark; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
            * { box-sizing: border-box; }
            body { margin: 0; color: #e6f3ff; background: #0a0e17; }
            main { min-height: 100vh; padding: 36px 24px 42px; background: linear-gradient(rgba(0,255,224,.035) 1px, transparent 1px), linear-gradient(90deg, rgba(120,92,255,.04) 1px, transparent 1px), radial-gradient(circle at top right, rgba(120,92,255,.28) 0, transparent 42%), radial-gradient(circle at bottom, rgba(0,255,224,.14) 0, transparent 38%); background-size: 36px 36px, 36px 36px, auto, auto; }
            .eyebrow { color: #00ffe0; font-size: 12px; font-weight: 800; letter-spacing: 2.2px; }
            h1 { max-width: 360px; margin: 12px 0 14px; font-size: 36px; line-height: 1.06; letter-spacing: -1.2px; }
            .summary { margin: 0; max-width: 355px; color: #b4c8d9; font-size: 17px; line-height: 1.45; }
            .badge { display: inline-flex; align-items: center; gap: 9px; margin: 24px 0 28px; padding: 10px 14px; color: #e6f3ff; background: rgba(0,255,224,.09); border: 1px solid rgba(0,255,224,.45); border-radius: 999px; font-size: 14px; font-weight: 700; box-shadow: 0 0 24px rgba(0,255,224,.08); }
            .badge span { display: inline-grid; width: 22px; height: 22px; place-items: center; color: #0a0e17; background: #00ffe0; border-radius: 50%; }
          </style>
        </head>
        <body>
          <main>
            <div class="eyebrow">\(eyebrow)</div>
            <h1>\(title)</h1>
            <p class="summary">\(summary)</p>
            <div class="badge"><span>\(badgeIcon)</span>\(badge)</div>
          </main>
        </body>
        </html>
        """
    }

    private func presentScreenshotProofIfNeeded() {
        guard shouldPresentScreenshotProof, presentedViewController == nil else { return }
        shouldPresentScreenshotProof = false
        let details = BrowserProofDetails(
            headline: "Handshake proof verified",
            detail: "shakeshift · cache verified",
            host: "shakeshift",
            name: "shakeshift",
            network: "main",
            nameHash: "002ccfdd5297befb2598bed3b71f6e6b05974d6abb0a9afda3e27e6b2ed9f12d",
            hnsProof: "verified",
            proofStatus: "verified",
            secure: true,
            exists: true,
            treeRoot: "dbce83cc6380d528b1df30d4721624dcfedad421a5ef074e7a3fa384d8c40d99",
            blockHeight: 335_942,
            cacheStatus: "verified",
            recordTypes: ["DS", "NS"],
            error: nil,
            formattedJSON: """
            {
              "blockHeight" : 335942,
              "cacheStatus" : "verified",
              "exists" : true,
              "hnsProof" : "verified",
              "host" : "shakeshift",
              "name" : "shakeshift",
              "nameHash" : "002ccfdd5297befb2598bed3b71f6e6b05974d6abb0a9afda3e27e6b2ed9f12d",
              "network" : "main",
              "proofStatus" : "verified",
              "recordTypes" : [ "DS", "NS" ],
              "secure" : true,
              "treeRoot" : "dbce83cc6380d528b1df30d4721624dcfedad421a5ef074e7a3fa384d8c40d99"
            }
            """
        )
        let viewer = ProofDetailsViewController(
            details: details,
            accessibilityIdentifier: "app-store-screenshot.ready.proof-details"
        )
        present(UINavigationController(rootViewController: viewer), animated: false)
    }
#endif

    private func prepareRuntime() {
        guard !isPreparing, coordinator == nil else { return }
        isPreparing = true
        placeholderLabel.text = "Preparing secure browsing…"
        process.prepare { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            self.isPreparing = false
            switch result {
            case .success(let environment):
                self.environment = environment
                let coordinator = self.installCoordinator(environment: environment)
                self.placeholderLabel.text = "Enter an address to begin"
                if self.isForeground {
                    coordinator.refreshSyncStatus()
                }
                self.controlsButton.isEnabled = true
                self.refreshSettingsIfPresented()
                if let pending = self.pendingExternalAddress {
                    self.pendingExternalAddress = nil
                    coordinator.navigate(rawValue: pending)
                } else {
                    coordinator.navigate(rawValue: BrowserSettingsPreferences.homepage)
                }
            case .failure(let error):
                self.placeholderLabel.text = "Secure runtime preparation failed"
                self.showPreparationError(error)
            }
        }
    }

    @discardableResult
    private func installCoordinator(
        environment: BrowserProcess.Environment,
        replayAddress: String? = nil
    ) -> BrowserProxyCoordinator {
        let coordinator = environment.proxyCoordinator()
        coordinator.attach(delegate: self)
        self.coordinator = coordinator
        isProxyAdmissionGranted = false
        updateSyncSummary(environment.runtime.syncSummary())
        if let replayAddress {
            coordinator.navigate(rawValue: replayAddress)
        }
        return coordinator
    }

    private func showPreparationError(_ error: Error) {
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Unable to prepare secure browsing",
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Retry", style: .default) { [weak self] _ in
            self?.prepareRuntime()
        })
        present(alert, animated: true)
    }

    private func showError(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        placeholderLabel.text = error.localizedDescription
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Navigation failed",
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    @objc private func goBack() {
        coordinator?.goBack()
    }

    @objc private func goForward() {
        coordinator?.goForward()
    }

    @objc private func reloadOrStop() {
        if isLoading {
            coordinator?.stopLoading()
        } else {
            coordinator?.reload()
        }
    }

    @objc private func sharePage() {
        guard let url = coordinator?.currentShareURL else { return }
        presentShareSheet(items: [url], sourceView: shareButton)
    }

    @objc private func presentBrowserSettings() {
        guard presentedViewController == nil else { return }
        let settings = BrowserSettingsViewController(
            policy: process.currentPolicy,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isControlOperationInFlight,
            syncSummary: latestSyncSummary,
            resolverCacheSummary: resolverCacheSummary,
            currentPageURL: coordinator?.currentShareURL?.absoluteString ?? canonicalAddress,
            homepage: BrowserSettingsPreferences.homepage,
            historyCount: BrowserHistoryStore.entries.count,
            downloadCount: BrowserDownloadStore.records.count,
            themeMode: BrowserSettingsPreferences.themeMode,
            handshakeNetwork: process.currentNetwork
        )
        settings.delegate = self
        settingsViewController = settings
        let navigation = UINavigationController(rootViewController: settings)
        navigation.modalPresentationStyle = .formSheet
        present(navigation, animated: true)
    }

    private func refreshSettingsIfPresented() {
        settingsViewController?.update(
            policy: process.currentPolicy,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isControlOperationInFlight,
            syncSummary: latestSyncSummary,
            resolverCacheSummary: resolverCacheSummary,
            currentPageURL: coordinator?.currentShareURL?.absoluteString ?? canonicalAddress,
            homepage: BrowserSettingsPreferences.homepage,
            historyCount: BrowserHistoryStore.entries.count,
            downloadCount: BrowserDownloadStore.records.count,
            themeMode: BrowserSettingsPreferences.themeMode,
            handshakeNetwork: process.currentNetwork
        )
    }

    private func dismissSettingsThen(_ action: @escaping () -> Void) {
        guard let settings = settingsViewController else {
            action()
            return
        }
        settings.dismiss(animated: true, completion: action)
    }

    private func presentStorefrontPage(_ url: URL) {
        guard presentedViewController == nil else { return }
        let browser = SFSafariViewController(url: url)
        browser.dismissButtonStyle = .close
        present(browser, animated: true)
    }

    private func presentCookiesSettings() {
        let viewer = TextDocumentViewController(
            title: "Cookies",
            text: """
            WEBSITE DATA

            WebKit stores first-party cookies and origin data in this browser's private app profile. iOS privacy protections limit cross-site tracking.

            DELETE COOKIES AND WEBSITE DATA

            Remove cookies and origin storage, including local storage and IndexedDB.
            """,
            actionTitle: "Delete Data"
        ) { [weak self] _ in
            self?.confirmWebsiteDataDeletion()
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func confirmWebsiteDataDeletion() {
        guard let profile = environment?.profile else { return }
        let viewer = (presentedViewController as? UINavigationController)?.topViewController
        let alert = UIAlertController(
            title: "Delete cookies and website data?",
            message: "This removes all WebKit cookies and origin storage used by the browser.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Delete", style: .destructive) { [weak self] _ in
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            profile.dataStore.removeData(
                ofTypes: types,
                modifiedSince: Date(timeIntervalSince1970: 0)
            ) { [weak self] in
                DispatchQueue.main.async {
                    guard let self else { return }
                    let confirmation = UIAlertController(
                        title: "Website data deleted",
                        message: "Cookies and website data were removed.",
                        preferredStyle: .alert
                    )
                    confirmation.addAction(UIAlertAction(title: "OK", style: .default))
                    let currentViewer = (self.presentedViewController as? UINavigationController)?.topViewController
                    self.presentAlertWhenReady(
                        confirmation,
                        preferredPresenter: currentViewer
                    )
                }
            }
        })
        presentAlertWhenReady(alert, preferredPresenter: viewer)
    }

    private func clearBrowsingData(presenter: UIViewController) {
        _ = BrowserHistoryStore.clear()
        _ = BrowserDownloadStore.clear()

        let finish: () -> Void = { [weak self, weak presenter] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.refreshSettingsIfPresented()
                let confirmation = UIAlertController(
                    title: "Browsing data cleared",
                    message: "History, download records, cookies, and website data were removed. Files already downloaded to the device were kept.",
                    preferredStyle: .alert
                )
                confirmation.addAction(UIAlertAction(title: "OK", style: .default))
                self.presentAlertWhenReady(confirmation, preferredPresenter: presenter)
            }
        }

        guard let profile = environment?.profile else {
            finish()
            return
        }
        profile.dataStore.removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: Date(timeIntervalSince1970: 0),
            completionHandler: finish
        )
    }

    private func presentHistory() {
        let entries = BrowserHistoryStore.entries
        let body: String
        if entries.isEmpty {
            body = "No browsing history yet.\n\nPages you visit will appear here."
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            body = entries.map { entry in
                let title = entry.title.isEmpty ? entry.url : entry.title
                return "\(title)\n\(entry.url)\n\(formatter.string(from: entry.visitedAt))"
            }.joined(separator: "\n\n")
        }
        let viewer = TextDocumentViewController(
            title: "History",
            text: body,
            actionTitle: entries.isEmpty ? nil : "Clear"
        ) { [weak self] viewer in
            guard let self else { return }
            _ = BrowserHistoryStore.clear()
            self.refreshSettingsIfPresented()
            viewer.dismiss(animated: true)
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func presentDownloads() {
        let records = BrowserDownloadStore.records
        let body: String
        if records.isEmpty {
            body = "No app downloads yet.\n\nDownloads saved by this browser will appear here."
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            body = records.map { record in
                let source = record.sourceURL.isEmpty ? "Unknown source" : record.sourceURL
                let availability = FileManager.default.fileExists(atPath: record.fileURL.path)
                    ? "Saved"
                    : "File unavailable"
                return "\(record.fileURL.lastPathComponent)\n\(availability) · \(formatter.string(from: record.savedAt))\n\(source)"
            }.joined(separator: "\n\n")
        }
        let viewer = TextDocumentViewController(
            title: "Downloads",
            text: body,
            actionTitle: records.isEmpty ? nil : "Clear List"
        ) { [weak self] viewer in
            guard let self else { return }
            _ = BrowserDownloadStore.clear()
            self.refreshSettingsIfPresented()
            viewer.dismiss(animated: true)
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func presentResolverTrace() {
        let address = coordinator?.currentShareURL?.absoluteString
            ?? (canonicalAddress.isEmpty ? "No current page" : canonicalAddress)
        let security = securityLabel.accessibilityLabel ?? "Security unavailable"
        let nativeTrace = coordinator?.currentResolutionTraceJSON
        let trace = nativeTrace
            ?? "No native resolution trace is available for the current page."
        let relayGuidance: String
        if BrowserDiagnosticReports.port53InterceptionDetected(traceJSON: nativeTrace) {
            relayGuidance = process.currentPolicy.experimentalP2PDNSRelay
                ? "The opted-in requester-only P2P DNS relay was attempted and did not provide a valid answer; configured recovery is next when present."
                : "You may opt in to the requester-only P2P DNS relay; it is never enabled automatically and local DNSSEC and DANE remain required."
        } else {
            relayGuidance = "No confirmed port 53 interception is recorded for this page."
        }
        let text = """
        ADDRESS
        \(address)

        RESOLUTION RESULT
        \(security)

        SELECTED DNS PROVENANCE
        \(BrowserDiagnosticReports.resolutionSource(traceJSON: nativeTrace))

        NATIVE TRACE
        \(trace)

        PORT 53 RECOVERY GUIDANCE
        \(relayGuidance)

        Website owner: use the fixed-origin setup link on the interception error page to prepare proof-anchored authoritative DoH on HTTPS 443. It includes a nameserver only when authenticated HNS delegation evidence supplied one. The setup website is contacted only if you choose the link.

        Browser user: configure a resolver. https://hnsdoh.com/dns-query is only a user-entered example and is never contacted by default.

        SYNC
        \(latestSyncSummary.headline)
        \(latestSyncSummary.detail)
        """
        let viewer = TextDocumentViewController(title: "Resolver Trace", text: text)
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func presentDiagnostics() {
        let policy = process.currentPolicy
        let nativeDiagnostics = (try? RustBrowserRuntime.diagnosticsJSON())
            ?? "Native diagnostics are unavailable."
        let text = """
        APP AND RUNTIME

        Build: \(BrowserSettingsViewController.buildLabelForDiagnostics)
        Network: \(process.currentNetwork.title)
        HNS trust policy: strict local DNSSEC/DANE; HNS WebPKI fallback prohibited
        Stateless DANE certificates: \(policy.statelessDANECertificates)
        Experimental P2P DNS relay: \(policy.experimentalP2PDNSRelay)
        User-configured HNS recovery DoH: \(policy.hnsDohResolver ?? "Off")

        SYNC STATUS

        \(latestSyncSummary.headline)
        \(latestSyncSummary.detail)
        Peers: \(latestSyncSummary.peerCount) in \(latestSyncSummary.peerGroups) groups
        Resolver cache: \(latestSyncSummary.resourceCacheEntries) entries, \(latestSyncSummary.resourceCacheBytes) bytes

        RUST CORE

        \(nativeDiagnostics)
        """
        let viewer = TextDocumentViewController(
            title: "Diagnostics",
            text: text,
            actionTitle: "Share"
        ) { viewer in
            let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            if let popover = activity.popoverPresentationController {
                popover.barButtonItem = viewer.navigationItem.rightBarButtonItem
            }
            viewer.present(activity, animated: true)
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func presentGatewayEvents() {
        let events = BrowserGatewayEventStore.entries
        let text = BrowserGatewayEventStore.formatted(events)
        let viewer = TextDocumentViewController(
            title: "Gateway",
            text: text,
            actionTitle: events.isEmpty ? nil : "Actions"
        ) { viewer in
            let actions = UIAlertController(
                title: "Gateway events",
                message: nil,
                preferredStyle: .actionSheet
            )
            actions.addAction(UIAlertAction(title: "Copy", style: .default) { _ in
                UIPasteboard.general.string = text
            })
            actions.addAction(UIAlertAction(title: "Clear", style: .destructive) { _ in
                _ = BrowserGatewayEventStore.clear()
                viewer.dismiss(animated: true)
            })
            actions.addAction(UIAlertAction(title: "Cancel", style: .cancel))
            if let popover = actions.popoverPresentationController {
                popover.barButtonItem = viewer.navigationItem.rightBarButtonItem
            }
            viewer.present(actions, animated: true)
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func presentLegal() {
        let notices: String
        if let url = Bundle.main.url(forResource: "third_party_notices", withExtension: "txt"),
           let value = try? String(contentsOf: url, encoding: .utf8) {
            notices = value
        } else {
            notices = "Third-party notices are unavailable."
        }
        let agreement = """
        This is an experimental Handshake-first browser with local HNS proofs, authoritative DNS, an optional requester-only P2P DNS relay, proof-anchored authoritative DoH, an optional user-configured recovery DoH endpoint, DNSSEC/DANE diagnostics, and a device-local HNS wallet. The wallet's direct mode can receive and send HNS, manage names, and use peer-backed marketplace workflows through wallet-owned verified Handshake peers. Complete atomic Shakedex settlement, Internet/NAT reachability, website-provider wallet access, and HNSA/HNSR service roles remain unavailable or under development. HNS resolution, validation, relay, recovery, sync, transaction broadcast, name operations, marketplace workflows, and wallet storage may fail closed, be delayed, require later manual actions, or remain incomplete. Review every native approval, recipient, amount, fee limit, name, listing, and session before approving it. HNS WebPKI fallback is prohibited; recovery answers never bypass local DNSSEC, TLSA, or DANE validation. The requester-only P2P DNS relay is separate from HNSR and does not make this device an endpoint or output node. The app is provided without warranty and is not a financial service.
        """
        let privacyDisclosure = """
        The native wallet stores a network-scoped encrypted database in the app container and a ThisDeviceOnly, user-presence-protected database key in Keychain. A generated recovery phrase is shown once; save it offline before confirming because the app cannot show or recover it later. If the protected recovery screen closes before confirmation, the app wipes its unconfirmed database-key buffer and deletes the incomplete wallet database. Swift/UIKit-managed recovery text cannot be claimed to be deterministically zeroized, although app-owned mutable buffers and visible fields are cleared. The controller makes no wallet-specific network request and sends no wallet database, recovery phrase, key, or account identity to Denuo Web, websites, or a wallet provider. An unlocked confirmed wallet can be deleted after two destructive confirmations that show its exact network/account and require typing DELETE. Deletion removes the Keychain key before encrypted database files. A key-deletion failure stops before file removal; a key whose presence can be verified remains available after protected lifecycle access resumes, while an unknown key state stays unavailable for reconciliation or retry. An ambiguous native close requires an app restart, and post-key file cleanup is retried before the namespace can reopen. It affects only this device-local wallet and does not delete an externally saved recovery phrase or backup. Uninstalling removes the database; iOS may retain the Keychain item, which the wallet screen deletes on a later install if its database is absent. P2P relay consumption and user-configured recovery DoH are independently off by default. This device is only a P2P requester, never an output node. If enabled, a selected relay peer can observe relayed qnames, qtypes, timing, and the source network address. While the recovery field is blank, no recovery operator is contacted. If configured and reached after direct, owner-authenticated, and opted-in P2P paths are exhausted, its operator can observe HNS qnames, qtypes, timing, and the source IP of the HTTPS connection. The recovery hostname is bootstrapped only through validating ICANN DoH and its TLS uses WebPKI. Relay and recovery answers still require local DNSSEC, TLSA, and DANE validation; bogus DNSSEC and stale or missing HNS proofs fail closed. Historical compatibility settings never enable either path.
        """
        let text = """
        PRIVACY POLICY
        \(BrowserSettingsViewController.privacyPolicyURL)

        LICENSE
        Shakescape is source-available under the repository's PolyForm Noncommercial 1.0.0 license.
        \(BrowserSettingsViewController.sourceCodeURL)

        USER AGREEMENT
        \(agreement)

        PRIVACY DISCLOSURE
        \(privacyDisclosure)

        THIRD-PARTY NOTICES
        \(notices)
        """
        let viewer = TextDocumentViewController(title: "Legal", text: text)
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func openAppLanguageSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func applyTheme(_ mode: BrowserThemeMode) {
        BrowserSettingsPreferences.saveThemeMode(mode)
        let style: UIUserInterfaceStyle
        switch mode {
        case .system: style = .unspecified
        case .light: style = .light
        case .dark: style = .dark
        }
        view.window?.overrideUserInterfaceStyle = style
        refreshSettingsIfPresented()
    }

    private var runtimeControlsAreAvailable: Bool {
#if DEBUG && targetEnvironment(simulator)
        environment != nil || appStoreScreenshotScene != nil
#else
        environment != nil
#endif
    }

    private func switchHandshakeNetwork(
        to network: BrowserHandshakeNetwork,
        presenter: UIViewController
    ) {
        guard !isControlOperationInFlight,
              let previousEnvironment = environment,
              network != process.currentNetwork else {
            refreshSettingsIfPresented()
            return
        }
        let replayAddress = coordinator?.replayableAddressForRuntimeChange()
        setControlOperationInFlight(true)
        addressField.isEnabled = false
        previousEnvironment.revokeProxyCoordinator()
        coordinator = nil
        isProxyAdmissionGranted = false
        progressObservation = nil
        placeholderLabel.isHidden = false
        placeholderLabel.text = "Switching to \(network.title)…"
        showTransientSyncStatus("Switching network")

        process.switchNetwork(to: network) { [weak self, weak presenter] result in
            guard let self, !self.isDestroyed else { return }
            self.addressField.isEnabled = true
            self.setControlOperationInFlight(false)
            switch result {
            case .success(let environment):
                self.environment = environment
                self.installCoordinator(
                    environment: environment,
                    replayAddress: replayAddress
                )
                self.resolverCacheSummary =
                    "Ready to clear cached resolver values for \(network.title)."
            case .failure(let error):
                self.environment = previousEnvironment
                self.installCoordinator(
                    environment: previousEnvironment,
                    replayAddress: replayAddress
                )
                self.showOperationError(
                    title: "Network change failed",
                    error: error,
                    presenter: presenter
                )
            }
            if replayAddress == nil {
                self.placeholderLabel.text = "Enter an address to begin"
            }
            if self.isForeground {
                self.resumeForegroundSync()
            }
            self.refreshSettingsIfPresented()
        }
    }

    private func addStaticRelayPeer(
        _ endpoint: String,
        presenter: UIViewController
    ) {
        guard beginControlOperation() else {
            refreshSettingsIfPresented()
            return
        }
        process.addStaticRelayPeer(endpoint) { [weak self, weak presenter] result in
            guard let self, !self.isDestroyed else { return }
            self.setControlOperationInFlight(false)
            switch result {
            case .success(let summary):
                self.settingsViewController?.updateRelayPeerSummary(
                    "Verified relay-capable \(self.process.currentNetwork.title) peer saved. Add another if needed."
                )
                self.updateSyncSummary(summary)
                self.showRuntimeSummary(summary, presenter: presenter)
            case .failure(let error):
                self.settingsViewController?.updateRelayPeerSummary(
                    "Add a known relay-capable peer when discovery has not found one. Existing peers remain available."
                )
                self.showOperationError(
                    title: "Relay peer was not added",
                    error: error,
                    presenter: presenter
                )
            }
        }
    }

    private func applyRuntimePolicy(
        _ policy: BrowserRuntimePolicy,
        presenter: UIViewController? = nil
    ) {
        guard !isControlOperationInFlight, let environment else { return }
        guard policy != process.currentPolicy else {
            refreshSettingsIfPresented()
            return
        }

        let replayAddress = coordinator?.replayableAddressForRuntimeChange()
        if isForeground {
            process.suspendForegroundSync()
        }
        setControlOperationInFlight(true)
        addressField.isEnabled = false
        environment.revokeProxyCoordinator()
        coordinator = nil
        isProxyAdmissionGranted = false
        progressObservation = nil
        placeholderLabel.isHidden = false
        placeholderLabel.text = "Applying runtime policy…"
        showTransientSyncStatus("Applying policy")

        process.updatePolicy(policy) { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            self.addressField.isEnabled = true
            self.setControlOperationInFlight(false)
            self.installCoordinator(environment: environment, replayAddress: replayAddress)
            if self.isForeground {
                self.resumeForegroundSync()
            }
            switch result {
            case .success(let revision):
                self.showTransientSyncStatus("Runtime policy revision \(revision)")
            case .failure(let error):
                self.showOperationError(
                    title: "Policy update failed",
                    error: error,
                    presenter: presenter
                )
            }
            self.refreshSettingsIfPresented()
        }
    }

    private func syncNow(presenter: UIViewController? = nil) {
        guard beginControlOperation() else {
            refreshSettingsIfPresented()
            return
        }
        showTransientSyncStatus("Syncing Handshake headers…")
        refreshSettingsIfPresented()
        process.syncNow { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            switch result {
            case .success(let summary):
                self.updateSyncSummary(summary)
                self.setControlOperationInFlight(false)
            case .failure(let error):
                self.updateSyncSummary(.failure(error))
                self.setControlOperationInFlight(false)
                self.showOperationError(
                    title: "Header sync failed",
                    error: error,
                    presenter: presenter
                )
            }
        }
    }

    private func clearResolverCache(presenter: UIViewController? = nil) {
        guard beginControlOperation() else { return }
        resolverCacheSummary = "Running…"
        refreshSettingsIfPresented()
        process.clearResolverCache { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            switch result {
            case .success(let summary):
                self.resolverCacheSummary =
                    "\(self.process.currentNetwork.title) cache cleared just now."
                self.setControlOperationInFlight(false)
                self.showRuntimeSummary(summary, presenter: presenter)
            case .failure(let error):
                self.resolverCacheSummary =
                    "Clear did not complete. Open diagnostics for details."
                self.setControlOperationInFlight(false)
                self.showOperationError(
                    title: "Cache clear failed",
                    error: error,
                    presenter: presenter
                )
            }
        }
    }

    private func resetHeadersFromPeers(presenter: UIViewController? = nil) {
        guard beginControlOperation() else { return }
        isHeaderResetInFlight = true
        if isProxyAdmissionGranted {
            isProxyAdmissionGranted = false
            coordinator?.suspend()
        }
        showTransientSyncStatus("Resetting Handshake headers…")
        refreshSettingsIfPresented()
        process.resetHeadersFromPeers { [weak self, weak presenter] result in
            guard let self, !self.isDestroyed else { return }
            switch result {
            case .success:
                guard self.isForeground else {
                    self.isHeaderResetInFlight = false
                    self.setControlOperationInFlight(false)
                    return
                }
                self.showTransientSyncStatus("Syncing Handshake headers…")
                self.refreshSettingsIfPresented()
                self.process.syncNow { [weak self, weak presenter] syncResult in
                    guard let self, !self.isDestroyed else { return }
                    self.isHeaderResetInFlight = false
                    self.setControlOperationInFlight(false)
                    guard self.isForeground else { return }
                    switch syncResult {
                    case .success(let summary):
                        self.updateSyncSummary(summary)
                        self.showRuntimeSummary(summary, presenter: presenter)
                    case .failure(let error):
                        self.updateSyncSummary(.failure(error))
                        self.showOperationError(
                            title: "Header resync failed",
                            error: error,
                            presenter: presenter
                        )
                    }
                }
            case .failure(let error):
                self.isHeaderResetInFlight = false
                self.setControlOperationInFlight(false)
                guard self.isForeground else { return }
                if let environment = self.environment {
                    self.updateSyncSummary(environment.runtime.syncSummary())
                }
                self.showOperationError(
                    title: "Header resync did not start",
                    error: error,
                    presenter: presenter
                )
            }
        }
    }

    private func presentHNSLookup(
        title: String,
        message: String,
        onInspect: @escaping (String) -> Void
    ) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addTextField { [weak self] field in
            field.text = self?.coordinator?.currentShareURL?.host
            field.placeholder = "example/ or www.example/"
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Inspect", style: .default) { [weak alert] _ in
            let value = alert?.textFields?.first?.text?.trimmingCharacters(
                in: .whitespacesAndNewlines
            ) ?? ""
            guard !value.isEmpty else { return }
            onInspect(value)
        })
        present(alert, animated: true)
    }

    private func showProofDetails() {
        let value = coordinator?.currentShareURL?.absoluteString
            ?? canonicalAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else {
            presentHNSLookup(
                title: "HNS Proof Details",
                message: "Enter an HNS name to inspect its local proof data."
            ) { [weak self] value in
                self?.loadProofDetails(for: value)
            }
            return
        }
        loadProofDetails(for: value)
    }

    private func presentHNSDomainSetup() {
        presentHNSLookup(
            title: "HNS Domain Setup",
            message: "Enter an HNS domain to diagnose its records, proof, and delegation."
        ) { [weak self] value in
            self?.loadDomainSetupReport(for: value)
        }
    }

    private func loadDomainSetupReport(for value: String) {
        guard beginControlOperation() else { return }
        process.proofDetails(for: value) { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            self.setControlOperationInFlight(false)
            switch result {
            case .success(let details):
                let report = BrowserDiagnosticReports.domainSetup(details)
                let viewer = TextDocumentViewController(
                    title: "HNS Domain Setup",
                    text: report,
                    actionTitle: "Copy"
                ) { _ in
                    UIPasteboard.general.string = report
                }
                self.present(UINavigationController(rootViewController: viewer), animated: true)
            case .failure(let error):
                self.showOperationError(title: "Domain setup report unavailable", error: error)
            }
        }
    }

    private func presentTLSADANEInspector() {
        let url = coordinator?.currentShareURL?.absoluteString
            ?? canonicalAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        let report = BrowserDiagnosticReports.tlsaDANE(
            url: url,
            traceJSON: coordinator?.currentResolutionTraceJSON
        )
        let viewer = TextDocumentViewController(
            title: "TLSA / DANE Inspector",
            text: report,
            actionTitle: "Copy"
        ) { _ in
            UIPasteboard.general.string = report
        }
        present(UINavigationController(rootViewController: viewer), animated: true)
    }

    private func loadProofDetails(for value: String) {
        guard beginControlOperation() else { return }

        process.proofDetails(for: value) { [weak self] result in
            guard let self, !self.isDestroyed else { return }
            self.setControlOperationInFlight(false)
            switch result {
            case .success(let details):
                let viewer = ProofDetailsViewController(
                    details: details,
                    accessibilityIdentifier: "browser-proof-details.content"
                )
                self.present(UINavigationController(rootViewController: viewer), animated: true)
            case .failure(let error):
                self.showOperationError(title: "Proof details unavailable", error: error)
            }
        }
    }

    private func beginControlOperation() -> Bool {
        guard !isControlOperationInFlight, environment != nil else { return false }
        setControlOperationInFlight(true)
        return true
    }

    private func setControlOperationInFlight(_ value: Bool) {
        isControlOperationInFlight = value
        controlsButton.isEnabled = !isDestroyed && !value
        refreshSettingsIfPresented()
    }

    private func updateSecuritySummary(_ summary: BrowserSecuritySummary) {
        let symbol: String
        let color: UIColor
        switch summary.level {
        case .pending:
            symbol = "hourglass"
            color = .secondaryLabel
        case .webPKI:
            symbol = "lock.fill"
            color = .systemBlue
        case .insecure:
            symbol = "lock.open.fill"
            color = .systemOrange
        case .handshakeDANE:
            symbol = "checkmark.shield.fill"
            color = .systemGreen
        case .blocked:
            symbol = "xmark.shield.fill"
            color = .systemRed
        }
        let attachment = NSTextAttachment()
        attachment.image = UIImage(systemName: symbol)?.withTintColor(color)
        let value = NSMutableAttributedString(attachment: attachment)
        value.append(NSAttributedString(string: " \(summary.detail)"))
        securityLabel.attributedText = value
        securityLabel.accessibilityLabel = summary.detail
    }

    private func updateSyncSummary(_ summary: BrowserSyncSummary) {
        latestSyncSummary = summary
        let awaitingNativeSyncStart = isForegroundSyncPreparing
            && !summary.syncInFlight
            && summary.status == "idle"
        if awaitingNativeSyncStart {
            // The native summary remains the only authority for proxy admission
            // below. This local state only avoids briefly presenting its prior
            // idle snapshot while foreground work is already queued.
            showForegroundSyncPreparation()
        } else {
            isForegroundSyncPreparing = false
            syncLabel.text = summary.syncDiagnosticText
            syncLabel.accessibilityLabel = "\(summary.headline). \(summary.detail)"
            let shouldShowProgress = summary.shouldShowSyncProgress
            syncLabel.isHidden = !shouldShowProgress
            syncProgressView.isHidden = !shouldShowProgress
            if let fraction = summary.syncProgressFraction {
                syncProgressView.setProgress(Float(fraction), animated: true)
                syncProgressView.accessibilityValue = "\(Int((fraction * 100).rounded())) percent"
            } else {
                syncProgressView.setProgress(0, animated: false)
                syncProgressView.accessibilityValue = "Indeterminate"
            }
        }
        guard let coordinator else {
            refreshSettingsIfPresented()
            return
        }
        switch authorityAdmissionPolicy.reconciliationAction(
            network: process.currentNetwork,
            isForeground: !isHeaderResetInFlight,
            syncSummary: summary,
            isAdmissionGranted: isProxyAdmissionGranted
        ) {
        case .resume:
            isProxyAdmissionGranted = true
            coordinator.resume()
        case .suspend:
            isProxyAdmissionGranted = false
            coordinator.suspend()
        case .unchanged:
            break
        }
        if !isProxyAdmissionGranted && !isHeaderResetInFlight {
            placeholderLabel.isHidden = false
            placeholderLabel.text = summary.requiresRetry
                ? "Handshake header sync must recover before loading this address."
                : "Waiting for authenticated Handshake headers before loading this address."
        }
        refreshSettingsIfPresented()
    }

    private func showTransientSyncStatus(_ text: String) {
        syncLabel.text = text
        syncLabel.accessibilityLabel = text
        syncLabel.isHidden = false
        syncProgressView.setProgress(0, animated: false)
        syncProgressView.accessibilityValue = "Indeterminate"
        syncProgressView.isHidden = false
    }

    private func recordGatewayEvent(
        stage: String,
        host: String,
        status: Int,
        reason: String
    ) {
        BrowserGatewayEventStore.record(
            stage: stage,
            host: host,
            status: status,
            reason: reason
        )
    }

    private func startSyncStatusPolling() {
        stopSyncStatusPolling()
        coordinator?.refreshSyncStatus()
        let initialRefresh = DispatchWorkItem { [weak self] in
            guard let self, self.isForeground, !self.isDestroyed else { return }
            self.coordinator?.refreshSyncStatus()
        }
        initialSyncStatusRefreshWorkItem = initialRefresh
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: initialRefresh)
        let timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) {
            [weak self] _ in
            guard let self, self.isForeground, !self.isDestroyed else { return }
            self.coordinator?.refreshSyncStatus()
        }
        timer.tolerance = 0.25
        syncStatusPollTimer = timer
    }

    private func stopSyncStatusPolling() {
        initialSyncStatusRefreshWorkItem?.cancel()
        initialSyncStatusRefreshWorkItem = nil
        syncStatusPollTimer?.invalidate()
        syncStatusPollTimer = nil
    }

    private func showForegroundSyncPreparation() {
        syncLabel.text = "Preparing Handshake synchronization…"
        syncLabel.accessibilityLabel = "Preparing Handshake synchronization."
        syncLabel.isHidden = false
        syncProgressView.isHidden = false
        syncProgressView.setProgress(0, animated: false)
        syncProgressView.accessibilityValue = "Indeterminate"
    }

    private func showOperationError(
        title: String,
        error: Error,
        presenter: UIViewController? = nil
    ) {
        let alert = UIAlertController(
            title: title,
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        presentAlertWhenReady(alert, preferredPresenter: presenter)
    }

    private func showRuntimeSummary(
        _ summary: BrowserSyncSummary,
        presenter: UIViewController? = nil
    ) {
        let alert = UIAlertController(
            title: summary.headline,
            message: summary.detail,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        presentAlertWhenReady(alert, preferredPresenter: presenter)
    }

    private func presentAlertWhenReady(
        _ alert: UIAlertController,
        preferredPresenter: UIViewController?,
        attemptsRemaining: Int = 20
    ) {
        guard !isDestroyed else { return }
        let target: UIViewController
        if let preferredPresenter,
           preferredPresenter.viewIfLoaded?.window != nil,
           !preferredPresenter.isBeingDismissed {
            target = preferredPresenter
        } else if let settingsViewController,
                  settingsViewController.viewIfLoaded?.window != nil,
                  !settingsViewController.isBeingDismissed {
            target = settingsViewController
        } else {
            target = self
        }

        guard target.viewIfLoaded?.window != nil,
              !target.isBeingPresented,
              !target.isBeingDismissed,
              target.presentedViewController == nil else {
            guard attemptsRemaining > 0 else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self, weak preferredPresenter] in
                self?.presentAlertWhenReady(
                    alert,
                    preferredPresenter: preferredPresenter,
                    attemptsRemaining: attemptsRemaining - 1
                )
            }
            return
        }
        target.present(alert, animated: true)
    }

    private func presentShareSheet(items: [Any], sourceView: UIView) {
        let activity = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = activity.popoverPresentationController {
            popover.sourceView = sourceView
            popover.sourceRect = sourceView.bounds
        }
        present(activity, animated: true)
    }
}

extension BrowserViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        guard let value = textField.text else { return false }
        coordinator?.navigate(rawValue: value)
        textField.resignFirstResponder()
        return true
    }

    func textFieldDidBeginEditing(_ textField: UITextField) {
        guard textField === addressField else { return }
        textField.text = BrowserAddressPresentation.editingText(for: canonicalAddress)
        DispatchQueue.main.async { [weak textField] in
            textField?.selectAll(nil)
        }
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        guard textField === addressField else { return }
        textField.text = BrowserAddressPresentation.displayText(for: canonicalAddress)
    }
}

extension BrowserViewController: BrowserSettingsViewControllerDelegate {
    func browserSettingsViewController(
        _ controller: BrowserSettingsViewController,
        didRequest action: BrowserSettingsViewController.Action
    ) {
        switch action {
        case .setHomepage(let homepage):
            BrowserSettingsPreferences.saveHomepage(homepage)
            refreshSettingsIfPresented()
        case .resetHomepage:
            BrowserSettingsPreferences.resetHomepage()
            refreshSettingsIfPresented()
        case .showCookies:
            dismissSettingsThen { [weak self] in
                self?.presentCookiesSettings()
            }
        case .showHistory:
            dismissSettingsThen { [weak self] in
                self?.presentHistory()
            }
        case .showDownloads:
            dismissSettingsThen { [weak self] in
                self?.presentDownloads()
            }
        case .clearBrowsingData:
            clearBrowsingData(presenter: controller)
        case .showWallet:
            controller.navigationController?.pushViewController(
                WalletViewController(
                    network: process.currentNetwork,
                    browserProcess: process
                ),
                animated: true
            )
        case .setTheme(let mode):
            applyTheme(mode)
        case .openLanguageSettings:
            dismissSettingsThen { [weak self] in
                self?.openAppLanguageSettings()
            }
        case .setHandshakeNetwork(let network):
            switchHandshakeNetwork(to: network, presenter: controller)
        case .addStaticRelayPeer(let endpoint):
            addStaticRelayPeer(endpoint, presenter: controller)
        case .applyRuntimePolicy(let policy):
            applyRuntimePolicy(policy, presenter: controller)
        case .clearResolverCache:
            clearResolverCache(presenter: controller)
        case .runHNSSync:
            syncNow(presenter: controller.navigationController?.topViewController ?? controller)
        case .resetHeadersFromPeers:
            resetHeadersFromPeers(
                presenter: controller.navigationController?.topViewController ?? controller
            )
        case .showHNSDomainSetup:
            dismissSettingsThen { [weak self] in
                self?.presentHNSDomainSetup()
            }
        case .showResolverTrace:
            dismissSettingsThen { [weak self] in
                self?.presentResolverTrace()
            }
        case .showHNSProofDetails:
            dismissSettingsThen { [weak self] in
                self?.showProofDetails()
            }
        case .showTLSADANEInspector:
            dismissSettingsThen { [weak self] in
                self?.presentTLSADANEInspector()
            }
        case .showDiagnostics:
            dismissSettingsThen { [weak self] in
                self?.presentDiagnostics()
            }
        case .showGateway:
            dismissSettingsThen { [weak self] in
                self?.presentGatewayEvents()
            }
        case .showLegal:
            dismissSettingsThen { [weak self] in
                self?.presentLegal()
            }
        case .showPrivacyPolicy:
            dismissSettingsThen { [weak self] in
                self?.presentStorefrontPage(Self.privacyPolicyURL)
            }
        case .showSourceCode:
            dismissSettingsThen { [weak self] in
                self?.presentStorefrontPage(Self.sourceCodeURL)
            }
        }
    }
}

extension BrowserViewController: BrowserProxyCoordinatorDelegate {
    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, install webView: WKWebView) {
        progressObservation = webView.observe(\.estimatedProgress, options: [.initial, .new]) { [weak self] webView, _ in
            DispatchQueue.main.async {
                self?.progressView.progress = Float(webView.estimatedProgress)
            }
        }
        webView.translatesAutoresizingMaskIntoConstraints = false
        webContainer.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: webContainer.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: webContainer.trailingAnchor),
            webView.topAnchor.constraint(equalTo: webContainer.topAnchor),
            webView.bottomAnchor.constraint(equalTo: webContainer.bottomAnchor),
        ])
        placeholderLabel.isHidden = true
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, remove webView: WKWebView) {
        progressObservation = nil
        placeholderLabel.isHidden = false
        placeholderLabel.text = isForeground ? "Switching secure browsing context…" : "Secure browsing paused"
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateAddress address: String) {
        updateCanonicalAddress(address)
        shareButton.isEnabled = true
        BrowserHistoryStore.record(url: address)
        recordGatewayEvent(
            stage: "navigation",
            host: URL(string: address)?.host ?? "",
            status: 102,
            reason: "Main-frame address updated"
        )
        refreshSettingsIfPresented()
    }

    func proxyCoordinator(
        _ coordinator: BrowserProxyCoordinator,
        didUpdateSameDocumentAddress address: String
    ) {
        updateCanonicalAddress(address)
        shareButton.isEnabled = true
        refreshSettingsIfPresented()
    }

    func proxyCoordinator(
        _ coordinator: BrowserProxyCoordinator,
        canGoBack: Bool,
        canGoForward: Bool,
        isLoading: Bool
    ) {
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
        self.isLoading = isLoading
        let symbol = isLoading ? "xmark" : "arrow.clockwise"
        reloadButton.setImage(UIImage(systemName: symbol), for: .normal)
        reloadButton.accessibilityLabel = isLoading ? "Stop" : "Reload"
        if !isLoading { progressView.progress = 0 }
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateSecurity summary: BrowserSecuritySummary) {
        updateSecuritySummary(summary)
        let status: Int
        switch summary.level {
        case .pending: status = 102
        case .blocked: status = 502
        case .webPKI, .insecure, .handshakeDANE: status = 200
        }
        recordGatewayEvent(
            stage: "security",
            host: coordinator.currentShareURL?.host ?? "",
            status: status,
            reason: summary.detail
        )
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateSync summary: BrowserSyncSummary) {
        updateSyncSummary(summary)
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didFail error: Error) {
        recordGatewayEvent(
            stage: "failure",
            host: coordinator.currentShareURL?.host ?? "",
            status: -1,
            reason: error.localizedDescription
        )
        showError(error)
    }

    func proxyCoordinator(
        _ coordinator: BrowserProxyCoordinator,
        scheduleAfterSyncMaintenance callback: @escaping () -> Void
    ) -> Bool {
        guard !isDestroyed, self.coordinator === coordinator else { return false }
        return process.performAtSyncMaintenanceSafePoint { [weak self, weak coordinator] in
            guard let self,
                  let coordinator,
                  !self.isDestroyed,
                  self.coordinator === coordinator else {
                return
            }
            callback()
        }
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didFinishDownloadAt url: URL) {
        BrowserDownloadStore.record(
            fileURL: url,
            sourceURL: coordinator.currentShareURL?.absoluteString ?? ""
        )
        recordGatewayEvent(
            stage: "download",
            host: coordinator.currentShareURL?.host ?? "",
            status: 200,
            reason: "Saved \(url.lastPathComponent)"
        )
        refreshSettingsIfPresented()
        let alert = UIAlertController(
            title: "Download complete",
            message: url.lastPathComponent,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Share or Save to Files", style: .default) { [weak self] _ in
            guard let self else { return }
            self.presentShareSheet(items: [url], sourceView: self.shareButton)
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }
}

@MainActor
private final class ProofDetailsViewController: UIViewController {
    private let details: BrowserProofDetails
    private let accessibilityIdentifier: String?
    private let textView = UITextView()

    init(details: BrowserProofDetails, accessibilityIdentifier: String? = nil) {
        self.details = details
        self.accessibilityIdentifier = accessibilityIdentifier
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = details.headline

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close,
            target: self,
            action: #selector(closeViewer)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "square.and.arrow.up"),
            style: .plain,
            target: self,
            action: #selector(exportDetails)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Export proof details"

        textView.translatesAutoresizingMaskIntoConstraints = false
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
        textView.text = "\(details.detail)\n\n\(details.formattedJSON)"
        textView.accessibilityLabel = "Handshake proof details for \(details.host)"
        textView.accessibilityIdentifier = accessibilityIdentifier
        view.addSubview(textView)
        NSLayoutConstraint.activate([
            textView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    @objc private func closeViewer() {
        dismiss(animated: true)
    }

    @objc private func exportDetails() {
        let activity = UIActivityViewController(
            activityItems: [details.formattedJSON],
            applicationActivities: nil
        )
        if let popover = activity.popoverPresentationController {
            popover.barButtonItem = navigationItem.rightBarButtonItem
        }
        present(activity, animated: true)
    }
}

@MainActor
private final class TextDocumentViewController: UIViewController {
    private let documentTitle: String
    private let documentText: String
    private let actionTitle: String?
    private let actionStyle: UIBarButtonItem.Style
    private let actionHandler: ((TextDocumentViewController) -> Void)?
    private let textView = UITextView()

    init(
        title: String,
        text: String,
        actionTitle: String? = nil,
        actionStyle: UIBarButtonItem.Style = .plain,
        actionHandler: ((TextDocumentViewController) -> Void)? = nil
    ) {
        documentTitle = title
        documentText = text
        self.actionTitle = actionTitle
        self.actionStyle = actionStyle
        self.actionHandler = actionHandler
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = documentTitle
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close,
            target: self,
            action: #selector(closeViewer)
        )
        if let actionTitle, actionHandler != nil {
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                title: actionTitle,
                style: actionStyle,
                target: self,
                action: #selector(runAction)
            )
        }

        textView.translatesAutoresizingMaskIntoConstraints = false
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.text = documentText
        textView.accessibilityLabel = documentTitle
        view.addSubview(textView)
        NSLayoutConstraint.activate([
            textView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            textView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            textView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    @objc private func closeViewer() {
        dismiss(animated: true)
    }

    @objc private func runAction() {
        actionHandler?(self)
    }
}
