import UIKit

@MainActor
protocol BrowserSettingsViewControllerDelegate: AnyObject {
    func browserSettingsViewController(
        _ controller: BrowserSettingsViewController,
        didRequest action: BrowserSettingsViewController.Action
    )
}

/// Native iOS settings UI that mirrors the canonical Android section and row
/// hierarchy. Platform-specific actions are implemented with native iOS UI.
@MainActor
final class BrowserSettingsViewController: UITableViewController {
    enum Action: Equatable {
        case setHomepage(String)
        case resetHomepage
        case showCookies
        case showHistory
        case showDownloads
        case showWallet
        case setTheme(BrowserThemeMode)
        case openLanguageSettings
        case setHandshakeNetwork(BrowserHandshakeNetwork)
        case addStaticRelayPeer(String)
        case applyRuntimePolicy(BrowserRuntimePolicy)
        case clearResolverCache
        case runHNSSync
        case resetHeadersFromPeers
        case showHNSDomainSetup
        case showResolverTrace
        case showHNSProofDetails
        case showTLSADANEInspector
        case showDiagnostics
        case showGateway
        case showLegal
        case showPrivacyPolicy
        case showSourceCode
    }

    enum Section: Int, CaseIterable {
        case startPage
        case privacyAndData
        case wallet
        case appearance
        case language
        case hnsResolution
        case diagnosticsAndTools
        case aboutLegalAndSupport

        var title: String {
            switch self {
            case .startPage: "Start page"
            case .privacyAndData: "Privacy and data"
            case .wallet: "Wallet"
            case .appearance: "Appearance"
            case .language: "Language"
            case .hnsResolution: "HNS resolution"
            case .diagnosticsAndTools: "Diagnostics and tools"
            case .aboutLegalAndSupport: "About, legal, and support"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .startPage: "settings.section.start-page"
            case .privacyAndData: "settings.section.privacy-and-data"
            case .wallet: "settings.section.wallet"
            case .appearance: "settings.section.appearance"
            case .language: "settings.section.language"
            case .hnsResolution: "settings.section.hns-resolution"
            case .diagnosticsAndTools: "settings.section.diagnostics-and-tools"
            case .aboutLegalAndSupport: "settings.section.about-legal-and-support"
            }
        }
    }

    enum Row: Int, CaseIterable {
        case homepage
        case setCurrentPageAsHomepage
        case resetHomepage
        case cookies
        case history
        case downloads
        case wallet
        case theme
        case appLanguage
        case handshakeNetwork
        case statelessDANECertificates
        case hnsDoHRecovery
        case experimentalP2PDNSRelay
        case addHNSRelayPeer
        case clearResolverCache
        case hnsSync
        case hnsDomainSetup
        case resolverTrace
        case hnsProofDetails
        case tlsaDANEInspector
        case diagnostics
        case gateway
        case build
        case legal
        case privacyPolicy
        case sourceCode

        var title: String {
            switch self {
            case .homepage: "Homepage"
            case .setCurrentPageAsHomepage: "Set current page as homepage"
            case .resetHomepage: "Reset homepage"
            case .cookies: "Cookies"
            case .history: "History"
            case .downloads: "Downloads"
            case .wallet: "Handshake wallet"
            case .theme: "Theme"
            case .appLanguage: "App language"
            case .handshakeNetwork: "Handshake network"
            case .statelessDANECertificates: "Experimental stateless DANE certificates"
            case .hnsDoHRecovery: "HNS recovery DNS over HTTPS"
            case .experimentalP2PDNSRelay: "Experimental P2P DNS relay"
            case .addHNSRelayPeer: "Add HNS relay peer"
            case .clearResolverCache: "Clear resolver cache"
            case .hnsSync: "HNS sync"
            case .hnsDomainSetup: "HNS domain setup"
            case .resolverTrace: "Resolver trace"
            case .hnsProofDetails: "HNS proof details"
            case .tlsaDANEInspector: "TLSA / DANE inspector"
            case .diagnostics: "Diagnostics"
            case .gateway: "Gateway"
            case .build: "Build"
            case .legal: "Legal"
            case .privacyPolicy: "Privacy policy"
            case .sourceCode: "Source code"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .homepage: "settings.start-page.homepage"
            case .setCurrentPageAsHomepage: "settings.start-page.set-current-page"
            case .resetHomepage: "settings.start-page.reset-homepage"
            case .cookies: "settings.privacy-and-data.cookies"
            case .history: "settings.privacy-and-data.history"
            case .downloads: "settings.privacy-and-data.downloads"
            case .wallet: "settings.wallet.native-controls"
            case .theme: "settings.appearance.theme"
            case .appLanguage: "settings.language.app-language"
            case .handshakeNetwork: "settings.hns-resolution.handshake-network"
            case .statelessDANECertificates:
                "settings.hns-resolution.stateless-dane-certificates"
            case .hnsDoHRecovery:
                "settings.hns-resolution.hns-doh-recovery"
            case .experimentalP2PDNSRelay:
                "settings.hns-resolution.experimental-p2p-dns-relay"
            case .addHNSRelayPeer: "settings.hns-resolution.add-hns-relay-peer"
            case .clearResolverCache: "settings.hns-resolution.clear-resolver-cache"
            case .hnsSync: "settings.hns-resolution.hns-sync"
            case .hnsDomainSetup: "settings.diagnostics-and-tools.hns-domain-setup"
            case .resolverTrace: "settings.diagnostics-and-tools.resolver-trace"
            case .hnsProofDetails: "browser-settings.proof-details"
            case .tlsaDANEInspector: "settings.diagnostics-and-tools.tlsa-dane-inspector"
            case .diagnostics: "settings.diagnostics-and-tools.diagnostics"
            case .gateway: "settings.diagnostics-and-tools.gateway"
            case .build: "settings.about-legal-and-support.build"
            case .legal: "settings.about-legal-and-support.legal"
            case .privacyPolicy: "settings.about-legal-and-support.privacy-policy"
            case .sourceCode: "settings.about-legal-and-support.source-code"
            }
        }

        var isRuntimeAction: Bool {
            switch self {
            case .handshakeNetwork,
                 .statelessDANECertificates,
                 .hnsDoHRecovery,
                 .experimentalP2PDNSRelay,
                 .addHNSRelayPeer,
                 .clearResolverCache,
                 .hnsDomainSetup,
                 .resolverTrace,
                 .hnsProofDetails,
                 .tlsaDANEInspector:
                true
            case .homepage,
                 .setCurrentPageAsHomepage,
                 .resetHomepage,
                 .cookies,
                 .history,
                 .downloads,
                 .wallet,
                 .theme,
                 .appLanguage,
                 .hnsSync,
                 .diagnostics,
                 .gateway,
                 .build,
                 .legal,
                 .privacyPolicy,
                 .sourceCode:
                false
            }
        }

        var isToggle: Bool {
            switch self {
            case .statelessDANECertificates,
                 .experimentalP2PDNSRelay:
                true
            default:
                false
            }
        }
    }

    static let privacyPolicyURL = "https://denuoweb.com/work/hns-dane-browser/privacy"
    static let sourceCodeURL = "https://github.com/handshake-rs/hns-dane-browser-mobile"

    weak var delegate: BrowserSettingsViewControllerDelegate?

    private var policy: BrowserRuntimePolicy
    private var runtimeControlsAreAvailable: Bool
    private var isOperationInFlight: Bool
    private var syncSummary: BrowserSyncSummary
    private var resolverCacheSummary: String
    private var currentPageURL: String?
    private var homepage: String
    private var historyCount: Int
    private var downloadCount: Int
    private var themeMode: BrowserThemeMode
    private var handshakeNetwork: BrowserHandshakeNetwork
    private var relayPeerSummary =
        "Add a known relay-capable peer when discovery has not found one. Existing peers remain available."
    private weak var hnsSyncViewController: HNSSyncViewController?

    init(
        policy: BrowserRuntimePolicy,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool = false,
        syncSummary: BrowserSyncSummary = .unavailable,
        resolverCacheSummary: String = "Ready to clear cached resolver values.",
        currentPageURL: String? = nil,
        homepage: String = BrowserSettingsPreferences.defaultHomepage,
        historyCount: Int = 0,
        downloadCount: Int = 0,
        themeMode: BrowserThemeMode = .system,
        handshakeNetwork: BrowserHandshakeNetwork = .mainnet
    ) {
        self.policy = policy
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        self.syncSummary = syncSummary
        self.resolverCacheSummary = resolverCacheSummary
        self.currentPageURL = Self.supportedCurrentPageURL(currentPageURL)
        self.homepage = homepage
        self.historyCount = historyCount
        self.downloadCount = downloadCount
        self.themeMode = themeMode
        self.handshakeNetwork = handshakeNetwork
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Settings"
        view.backgroundColor = .systemGroupedBackground
        tableView.accessibilityIdentifier = "settings.table"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 76
        tableView.sectionHeaderHeight = UITableView.automaticDimension
        tableView.estimatedSectionHeaderHeight = 44
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close,
            target: self,
            action: #selector(closeSettings)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Close settings"
        navigationItem.rightBarButtonItem?.accessibilityIdentifier = "settings.close"
    }

    /// Refreshes displayed state after the browser completes an asynchronous
    /// settings action or the current page changes.
    func update(
        policy: BrowserRuntimePolicy,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool,
        syncSummary: BrowserSyncSummary = .unavailable,
        resolverCacheSummary: String? = nil,
        currentPageURL: String? = nil,
        homepage: String? = nil,
        historyCount: Int? = nil,
        downloadCount: Int? = nil,
        themeMode: BrowserThemeMode? = nil,
        handshakeNetwork: BrowserHandshakeNetwork? = nil
    ) {
        let nextResolverCacheSummary = resolverCacheSummary ?? self.resolverCacheSummary
        let nextCurrentPageURL = Self.supportedCurrentPageURL(currentPageURL)
        let nextHomepage = homepage ?? self.homepage
        let nextHistoryCount = historyCount ?? self.historyCount
        let nextDownloadCount = downloadCount ?? self.downloadCount
        let nextThemeMode = themeMode ?? self.themeMode
        let nextHandshakeNetwork = handshakeNetwork ?? self.handshakeNetwork
        let tableStateChanged =
            self.policy != policy
            || self.runtimeControlsAreAvailable != runtimeControlsAreAvailable
            || self.isOperationInFlight != isOperationInFlight
            || self.resolverCacheSummary != nextResolverCacheSummary
            || self.currentPageURL != nextCurrentPageURL
            || self.homepage != nextHomepage
            || self.historyCount != nextHistoryCount
            || self.downloadCount != nextDownloadCount
            || self.themeMode != nextThemeMode
            || self.handshakeNetwork != nextHandshakeNetwork

        self.policy = policy
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        self.syncSummary = syncSummary
        self.resolverCacheSummary = nextResolverCacheSummary
        self.currentPageURL = nextCurrentPageURL
        self.homepage = nextHomepage
        self.historyCount = nextHistoryCount
        self.downloadCount = nextDownloadCount
        self.themeMode = nextThemeMode
        self.handshakeNetwork = nextHandshakeNetwork
        guard isViewLoaded else { return }
        if tableStateChanged {
            tableView.reloadData()
        }
        hnsSyncViewController?.update(
            summary: syncSummary,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight
        )
    }

    func updateRelayPeerSummary(_ summary: String) {
        relayPeerSummary = summary
        guard isViewLoaded else { return }
        tableView.reloadData()
    }

    static func rows(in section: Section) -> [Row] {
        switch section {
        case .startPage:
            [.homepage, .setCurrentPageAsHomepage, .resetHomepage]
        case .privacyAndData:
            [.cookies, .history, .downloads]
        case .wallet:
            [.wallet]
        case .appearance:
            [.theme]
        case .language:
            [.appLanguage]
        case .hnsResolution:
            [
                .handshakeNetwork,
                .statelessDANECertificates,
                .hnsDoHRecovery,
                .experimentalP2PDNSRelay,
                .addHNSRelayPeer,
                .clearResolverCache,
                .hnsSync,
            ]
        case .diagnosticsAndTools:
            [
                .hnsDomainSetup,
                .resolverTrace,
                .hnsProofDetails,
                .tlsaDANEInspector,
                .diagnostics,
                .gateway,
            ]
        case .aboutLegalAndSupport:
            // Apple requires developer tipping in App Store apps to use In-App Purchase.
            [.build, .legal, .privacyPolicy, .sourceCode]
        }
    }

    private func displayedRows(in section: Section) -> [Row] {
        Self.rows(in: section).filter {
            $0 != .setCurrentPageAsHomepage || currentPageURL != nil
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard let section = Section(rawValue: section) else { return 0 }
        return displayedRows(in: section).count
    }

    override func tableView(
        _ tableView: UITableView,
        viewForHeaderInSection sectionIndex: Int
    ) -> UIView? {
        guard let section = Section(rawValue: sectionIndex) else { return nil }
        let header = UITableViewHeaderFooterView(reuseIdentifier: nil)
        var content = UIListContentConfiguration.groupedHeader()
        content.text = section.title
        content.textProperties.font = .preferredFont(forTextStyle: .headline)
        header.contentConfiguration = content
        header.accessibilityIdentifier = section.accessibilityIdentifier
        return header
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        guard let row = row(at: indexPath) else { return UITableViewCell() }
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.accessibilityIdentifier = row.accessibilityIdentifier
        cell.backgroundColor = .secondarySystemGroupedBackground

        var content = UIListContentConfiguration.subtitleCell()
        content.text = row.title
        content.secondaryText = summary(for: row)
        content.textProperties.font = .preferredFont(forTextStyle: .body)
        content.textProperties.numberOfLines = 0
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .footnote)
        content.secondaryTextProperties.color = .secondaryLabel
        content.secondaryTextProperties.numberOfLines = 0
        content.prefersSideBySideTextAndSecondaryText = false
        cell.contentConfiguration = content

        if row.isToggle {
            configureToggleCell(cell, row: row)
        } else {
            configureActionCell(cell, row: row)
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let row = row(at: indexPath), !row.isToggle else { return }
        guard !row.isRuntimeAction || runtimeActionsAreEnabled else { return }

        switch row {
        case .homepage:
            presentHomepageConfiguration()
        case .setCurrentPageAsHomepage:
            guard let currentPageURL else { return }
            homepage = currentPageURL
            request(.setHomepage(currentPageURL))
            tableView.reloadData()
        case .resetHomepage:
            confirmResetHomepage()
        case .cookies:
            request(.showCookies)
        case .history:
            request(.showHistory)
        case .downloads:
            request(.showDownloads)
        case .wallet:
            request(.showWallet)
        case .theme:
            presentThemeConfiguration()
        case .appLanguage:
            request(.openLanguageSettings)
        case .handshakeNetwork:
            presentNetworkConfiguration()
        case .hnsDoHRecovery:
            presentHNSDoHRecoveryConfiguration()
        case .addHNSRelayPeer:
            presentRelayPeerConfiguration()
        case .clearResolverCache:
            confirmClearResolverCache()
        case .hnsSync:
            showHNSSync()
        case .hnsDomainSetup:
            request(.showHNSDomainSetup, marksOperationInFlight: true)
        case .resolverTrace:
            request(.showResolverTrace, marksOperationInFlight: true)
        case .hnsProofDetails:
            request(.showHNSProofDetails, marksOperationInFlight: true)
        case .tlsaDANEInspector:
            request(.showTLSADANEInspector, marksOperationInFlight: true)
        case .diagnostics:
            request(.showDiagnostics)
        case .gateway:
            request(.showGateway)
        case .legal:
            request(.showLegal)
        case .privacyPolicy:
            request(.showPrivacyPolicy)
        case .sourceCode:
            request(.showSourceCode)
        case .build,
             .statelessDANECertificates,
             .experimentalP2PDNSRelay:
            break
        }
    }

    private var runtimeActionsAreEnabled: Bool {
        runtimeControlsAreAvailable && !isOperationInFlight
    }

    private func row(at indexPath: IndexPath) -> Row? {
        guard let section = Section(rawValue: indexPath.section) else { return nil }
        let rows = displayedRows(in: section)
        guard rows.indices.contains(indexPath.row) else { return nil }
        return rows[indexPath.row]
    }

    private func summary(for row: Row) -> String {
        switch row {
        case .homepage:
            return homepage
        case .setCurrentPageAsHomepage:
            return currentPageURL ?? ""
        case .resetHomepage:
            return "Restore the default Denuo Web homepage."
        case .cookies:
            return "Manage cookies and website data used by this browser."
        case .history:
            return historyCount == 1 ? "1 saved page" : "\(historyCount) saved pages"
        case .downloads:
            return downloadCount == 1
                ? "1 app-queued record"
                : "\(downloadCount) app-queued records"
        case .wallet:
            return "Create, restore, open, unlock, lock, and inspect one local HNS account. Value and marketplace controls remain unavailable."
        case .theme:
            return themeMode.summary
        case .appLanguage:
            return "Uses your iOS system or per-app language setting."
        case .handshakeNetwork:
            return "\(handshakeNetwork.title). \(handshakeNetwork.summary)"
        case .statelessDANECertificates:
            if policy.statelessDANECertificates {
                return "On. Legacy certificate-carried evidence cannot be combined with retained dual-root plans; prepared browser requests fail closed."
            }
            return "Off. Browser requests use the retained dual-root DNSSEC and TLSA plan."
        case .experimentalP2PDNSRelay:
            if policy.experimentalP2PDNSRelay {
                return "On by explicit request. Delegated DNS may use relay-capable Handshake peers; DNSSEC validation remains local."
            }
            return "Off by default. Peer DNS relay messages are not requested."
        case .hnsDoHRecovery:
            if let resolver = policy.hnsDohResolver {
                return "Configured: \(resolver). Used only after direct authoritative DNS, owner-published authenticated DoH, and any opted-in P2P requester fail for an eligible transport reason. DNSSEC, TLSA, and DANE remain locally validated."
            }
            return "Off. No third-party HNS resolver is contacted."
        case .addHNSRelayPeer:
            return relayPeerSummary
        case .clearResolverCache:
            return resolverCacheSummary
        case .hnsSync:
            return "View sync status and run a manual sync."
        case .hnsDomainSetup:
            return "Check records and delegation for an HNS domain."
        case .resolverTrace:
            return "Inspect resolution steps for a name."
        case .hnsProofDetails:
            return "Inspect local proof data for an HNS name."
        case .tlsaDANEInspector:
            return "Check TLSA records and DANE policy."
        case .diagnostics:
            return "Build, runtime, and native core details."
        case .gateway:
            return "Inspect recent structured browser gateway activity."
        case .build:
            return Self.buildLabel
        case .legal:
            return "Privacy policy, license, and user agreement."
        case .privacyPolicy:
            return Self.privacyPolicyURL
        case .sourceCode:
            return Self.sourceCodeURL
        }
    }

    private func configureToggleCell(_ cell: UITableViewCell, row: Row) {
        let toggle = UISwitch()
        toggle.tag = row.rawValue
        toggle.isOn = toggleValue(for: row)
        toggle.isEnabled = runtimeActionsAreEnabled
        toggle.accessibilityLabel = row.title
        toggle.accessibilityIdentifier = "\(row.accessibilityIdentifier).toggle"
        toggle.addTarget(self, action: #selector(runtimeToggleChanged(_:)), for: .valueChanged)
        cell.accessoryView = toggle
        cell.selectionStyle = .none
        cell.isUserInteractionEnabled = true
        applyEnabledAppearance(runtimeActionsAreEnabled, to: cell)
    }

    private func configureActionCell(_ cell: UITableViewCell, row: Row) {
        let enabled = !row.isRuntimeAction || runtimeActionsAreEnabled
        cell.isUserInteractionEnabled = enabled || row == .build
        applyEnabledAppearance(enabled || row == .build, to: cell)

        switch row {
        case .build:
            cell.selectionStyle = .none
        case .resetHomepage, .clearResolverCache:
            var content = cell.contentConfiguration as? UIListContentConfiguration
                ?? .subtitleCell()
            content.textProperties.color = enabled ? .systemRed : .tertiaryLabel
            cell.contentConfiguration = content
        default:
            break
        }

        if let actionTitle = actionTitle(for: row) {
            let actionLabel = UILabel()
            actionLabel.text = actionTitle
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled
                ? (row == .clearResolverCache || row == .resetHomepage
                    ? .systemRed
                    : view.tintColor)
                : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        }
    }

    private func applyEnabledAppearance(_ enabled: Bool, to cell: UITableViewCell) {
        guard var content = cell.contentConfiguration as? UIListContentConfiguration else { return }
        content.textProperties.color = enabled ? .label : .tertiaryLabel
        content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
        cell.contentConfiguration = content
    }

    private func actionTitle(for row: Row) -> String? {
        switch row {
        case .homepage: "Edit"
        case .setCurrentPageAsHomepage: "Set"
        case .resetHomepage: "Reset"
        case .cookies: "Manage"
        case .history,
             .downloads,
             .wallet,
             .hnsSync,
             .diagnostics,
             .gateway,
             .legal:
            "View"
        case .theme, .handshakeNetwork: "Change"
        case .hnsDoHRecovery: "Edit"
        case .addHNSRelayPeer: "Add"
        case .clearResolverCache: "Clear"
        case .appLanguage,
             .hnsDomainSetup,
             .resolverTrace,
             .hnsProofDetails,
             .tlsaDANEInspector,
             .privacyPolicy,
             .sourceCode:
            "Open"
        case .build,
             .statelessDANECertificates,
             .experimentalP2PDNSRelay:
            nil
        }
    }

    private func toggleValue(for row: Row) -> Bool {
        switch row {
        case .statelessDANECertificates:
            policy.statelessDANECertificates
        case .experimentalP2PDNSRelay:
            policy.experimentalP2PDNSRelay
        default:
            false
        }
    }

    @objc private func runtimeToggleChanged(_ sender: UISwitch) {
        guard runtimeActionsAreEnabled, let row = Row(rawValue: sender.tag) else {
            sender.setOn(!sender.isOn, animated: true)
            return
        }

        let updatedPolicy: BrowserRuntimePolicy
        switch row {
        case .statelessDANECertificates:
            updatedPolicy = policyByReplacingStatelessDANECertificates(sender.isOn)
        case .experimentalP2PDNSRelay:
            updatedPolicy = policyByReplacingExperimentalP2PDNSRelay(sender.isOn)
        default:
            return
        }
        requestPolicyUpdate(updatedPolicy)
    }

    private static func supportedCurrentPageURL(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              let components = URLComponents(string: value),
              ["http", "https"].contains(components.scheme?.lowercased() ?? ""),
              components.host?.isEmpty == false else {
            return nil
        }
        return value
    }

    private static func normalizedHomepage(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              value.count <= 16 * 1024 else {
            return nil
        }
        if let supported = supportedCurrentPageURL(value) {
            return supported
        }
        guard !value.contains("://"),
              !value.contains(where: { $0.isWhitespace }),
              value.contains(".") || value.hasSuffix("/") else {
            return nil
        }
        return value
    }

    private func presentHomepageConfiguration() {
        let alert = UIAlertController(
            title: "Edit homepage",
            message: "Enter an http:// or https:// URL, or an HNS name such as example/ or www.example/.",
            preferredStyle: .alert
        )
        alert.addTextField { [homepage] field in
            field.text = homepage
            field.placeholder = "https://example.com/ or example/"
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.clearButtonMode = .whileEditing
            field.accessibilityIdentifier = "settings.start-page.homepage.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            guard let normalized = Self.normalizedHomepage(alert?.textFields?.first?.text) else {
                self.presentValidationError(
                    title: "Invalid homepage",
                    message: "Enter an HTTP(S) URL or HNS name."
                )
                return
            }
            self.homepage = normalized
            self.request(.setHomepage(normalized))
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func confirmResetHomepage() {
        let alert = UIAlertController(
            title: "Reset homepage?",
            message: "This restores the default Denuo Web homepage.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reset", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.homepage = BrowserSettingsPreferences.defaultHomepage
            self.request(.resetHomepage)
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func presentThemeConfiguration() {
        let alert = UIAlertController(title: "Theme", message: nil, preferredStyle: .alert)
        BrowserThemeMode.allCases.forEach { mode in
            let selected = mode == themeMode ? "✓ " : ""
            alert.addAction(UIAlertAction(title: selected + mode.title, style: .default) {
                [weak self] _ in
                guard let self else { return }
                self.themeMode = mode
                self.request(.setTheme(mode))
                self.tableView.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func presentNetworkConfiguration() {
        let alert = UIAlertController(
            title: "Handshake network",
            message: "Changing networks restarts the secure runtime with separate network data.",
            preferredStyle: .alert
        )
        BrowserHandshakeNetwork.allCases.forEach { network in
            let selected = network == handshakeNetwork ? "✓ " : ""
            alert.addAction(UIAlertAction(
                title: selected + "\(network.title) — \(network.summary)",
                style: .default
            ) { [weak self] _ in
                guard let self, network != self.handshakeNetwork else { return }
                self.handshakeNetwork = network
                self.resolverCacheSummary =
                    "Ready to clear cached resolver values for \(network.title)."
                self.request(.setHandshakeNetwork(network), marksOperationInFlight: true)
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func presentHNSDoHRecoveryConfiguration() {
        let relayMessage = policy.experimentalP2PDNSRelay
            ? "The requester-only P2P DNS relay is enabled and is tried before this recovery endpoint."
            : "You can first enable the requester-only P2P DNS relay. It never makes this device an output node."
        let alert = UIAlertController(
            title: "HNS recovery DoH",
            message: """
            \(relayMessage) Leave blank to keep third-party recovery off. If configured, its operator can observe HNS qnames, qtypes, timing, and your source IP. Nothing is sent to a recovery operator while blank. Resolver hostnames are bootstrapped through validating ICANN DoH and connected with WebPKI. Every answer still undergoes local DNSSEC, TLSA, and DANE validation.

            Owner: publish proof-anchored authoritative DoH on HTTPS 443.
            User: configure a resolver.
            """,
            preferredStyle: .alert
        )
        alert.addTextField { [resolver = policy.hnsDohResolver] field in
            field.text = resolver
            field.placeholder = "Example: https://hnsdoh.com/dns-query"
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.clearButtonMode = .whileEditing
            field.accessibilityIdentifier = "settings.hns-resolution.hns-doh-recovery.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        if !policy.experimentalP2PDNSRelay {
            alert.addAction(UIAlertAction(title: "Enable P2P requester", style: .default) {
                [weak self] _ in
                guard let self else { return }
                self.requestPolicyUpdate(
                    self.policyByReplacingExperimentalP2PDNSRelay(true)
                )
            })
        }
        alert.addAction(UIAlertAction(title: "Save", style: .default) {
            [weak self, weak alert] _ in
            guard let self else { return }
            let input = alert?.textFields?.first?.text
            guard let normalized = BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(input) else {
                self.presentValidationError(
                    title: "Invalid recovery resolver",
                    message: "Enter a bounded HTTPS RFC 8484 URL with a public ICANN hostname and path, without credentials or a fragment."
                )
                return
            }
            self.requestPolicyUpdate(
                self.policyByReplacingHNSDoHRecoveryResolver(
                    normalized.isEmpty ? nil : normalized
                )
            )
        })
        present(alert, animated: true)
    }

    private func presentRelayPeerConfiguration() {
        let port: Int
        switch handshakeNetwork {
        case .mainnet: port = 12_038
        case .testnet: port = 13_038
        case .regtest: port = 14_038
        }
        let alert = UIAlertController(
            title: "Add HNS relay peer",
            message: "Enter a \(handshakeNetwork.title) Handshake peer as IPv4:port or [IPv6]:port. The app verifies its network handshake and live DNS-relay capability before saving it. Hostnames are not accepted.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "IPv4:\(port) or [IPv6]:\(port)"
            field.keyboardType = .numbersAndPunctuation
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.accessibilityIdentifier = "settings.hns-resolution.add-hns-relay-peer.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Add", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            let endpoint = alert?.textFields?.first?.text?.trimmingCharacters(
                in: .whitespacesAndNewlines
            ) ?? ""
            guard !endpoint.isEmpty,
                  endpoint.count <= 320,
                  !endpoint.contains(where: { $0.isWhitespace }),
                  endpoint.contains(":") else {
                self.presentValidationError(
                    title: "Invalid relay peer",
                    message: "Enter a valid IPv4:port or [IPv6]:port endpoint."
                )
                return
            }
            self.relayPeerSummary = "Verifying a relay-capable \(self.handshakeNetwork.title) peer…"
            self.request(.addStaticRelayPeer(endpoint), marksOperationInFlight: true)
        })
        present(alert, animated: true)
    }

    private func presentValidationError(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    private func confirmClearResolverCache() {
        let alert = UIAlertController(
            title: "Clear resolver cache?",
            message: "The app will keep synced \(handshakeNetwork.title) headers and peers, but cached HNS resource values for this network will be removed.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { [weak self] _ in
            self?.request(.clearResolverCache, marksOperationInFlight: true)
        })
        present(alert, animated: true)
    }

    private func showHNSSync() {
        let controller = HNSSyncViewController(
            summary: syncSummary,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight
        )
        controller.onRunSync = { [weak self] in
            self?.request(.runHNSSync, marksOperationInFlight: true)
        }
        controller.onResetHeaders = { [weak self] in
            self?.request(.resetHeadersFromPeers, marksOperationInFlight: true)
        }
        hnsSyncViewController = controller
        navigationController?.pushViewController(controller, animated: true)
    }

    private func requestPolicyUpdate(_ updatedPolicy: BrowserRuntimePolicy) {
        guard updatedPolicy != policy else {
            tableView.reloadData()
            return
        }
        policy = updatedPolicy
        request(.applyRuntimePolicy(updatedPolicy), marksOperationInFlight: true)
    }

    private func request(_ action: Action, marksOperationInFlight: Bool = false) {
        if marksOperationInFlight {
            isOperationInFlight = true
            tableView.reloadData()
            hnsSyncViewController?.update(
                summary: syncSummary,
                runtimeControlsAreAvailable: runtimeControlsAreAvailable,
                isOperationInFlight: true
            )
        }
        delegate?.browserSettingsViewController(self, didRequest: action)
    }

    private func policyByReplacingStatelessDANECertificates(
        _ enabled: Bool
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: enabled,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay
        )
    }

    private func policyByReplacingExperimentalP2PDNSRelay(
        _ enabled: Bool
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: enabled
        )
    }

    private func policyByReplacingHNSDoHRecoveryResolver(
        _ resolver: String?
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            hnsDohResolver: resolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay
        )
    }

    private static var buildLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
            as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion")
            as? String ?? "Unknown"
        return "release \(version) (\(build))"
    }

    static var buildLabelForDiagnostics: String { buildLabel }

    @objc private func closeSettings() {
        dismiss(animated: true)
    }
}

@MainActor
final class HNSSyncViewController: UITableViewController {
    enum Row: Int, CaseIterable {
        case syncStatus
        case runSyncNow
        case resyncHeadersFromPeers
    }

    var onRunSync: (() -> Void)?
    var onResetHeaders: (() -> Void)?

    private var summary: BrowserSyncSummary
    private var runtimeControlsAreAvailable: Bool
    private var isOperationInFlight: Bool

    init(
        summary: BrowserSyncSummary,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool
    ) {
        self.summary = summary
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "HNS Sync"
        view.backgroundColor = .systemGroupedBackground
        tableView.accessibilityIdentifier = "hns-sync.table"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 96
    }

    func update(
        summary: BrowserSyncSummary,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool
    ) {
        self.summary = summary
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        guard isViewLoaded else { return }
        tableView.reloadData()
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 1 }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        Row.allCases.count
    }

    override func tableView(
        _ tableView: UITableView,
        titleForHeaderInSection section: Int
    ) -> String? {
        "HNS sync"
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        guard let row = Row(rawValue: indexPath.row) else { return UITableViewCell() }
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = .secondarySystemGroupedBackground

        var content = UIListContentConfiguration.subtitleCell()
        content.textProperties.font = .preferredFont(forTextStyle: .body)
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .footnote)
        content.secondaryTextProperties.color = .secondaryLabel
        content.secondaryTextProperties.numberOfLines = 0
        content.prefersSideBySideTextAndSecondaryText = false

        switch row {
        case .syncStatus:
            let syncIsRunning = isOperationInFlight || summary.syncInFlight
            content.text = "Sync status"
            content.secondaryText = Self.statusText(
                summary: summary,
                isOperationInFlight: isOperationInFlight
            )
            cell.accessibilityIdentifier = "hns-sync.status"
            cell.selectionStyle = .none
            if syncIsRunning {
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.startAnimating()
                spinner.accessibilityLabel = "Sync running"
                cell.accessoryView = spinner
            }
        case .runSyncNow:
            content.text = "Run sync now"
            content.secondaryText =
                "Start a foreground HNS sync and watch the status update here."
            cell.accessibilityIdentifier = "hns-sync.run-now"
            let enabled = runtimeControlsAreAvailable && !isOperationInFlight
            content.textProperties.color = enabled ? .label : .tertiaryLabel
            content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
            cell.isUserInteractionEnabled = enabled
            let actionLabel = UILabel()
            actionLabel.text = "Run"
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled ? view.tintColor : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        case .resyncHeadersFromPeers:
            content.text = "Resync headers from peers"
            content.secondaryText =
                "Reset local headers and cached resolver values, then sync again from peers."
            cell.accessibilityIdentifier = "hns-sync.resync-headers"
            let enabled = runtimeControlsAreAvailable && !isOperationInFlight
            content.textProperties.color = enabled ? .systemRed : .tertiaryLabel
            content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
            cell.isUserInteractionEnabled = enabled
            let actionLabel = UILabel()
            actionLabel.text = "Reset"
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled ? .systemRed : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        }
        cell.contentConfiguration = content
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let row = Row(rawValue: indexPath.row),
              runtimeControlsAreAvailable,
              !isOperationInFlight else {
            return
        }
        switch row {
        case .syncStatus:
            return
        case .runSyncNow:
            isOperationInFlight = true
            tableView.reloadData()
            onRunSync?()
        case .resyncHeadersFromPeers:
            confirmHeaderReset()
        }
    }

    private func confirmHeaderReset() {
        let alert = UIAlertController(
            title: "Resync headers from peers?",
            message: "This removes local headers and cached resolver values, then starts syncing again from block 0.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reset", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.isOperationInFlight = true
            self.tableView.reloadData()
            self.onResetHeaders?()
        })
        present(alert, animated: true)
    }

    static func statusText(
        summary: BrowserSyncSummary,
        isOperationInFlight: Bool
    ) -> String {
        var lines = [isOperationInFlight || summary.syncInFlight ? "Running…" : summary.headline]
        if !summary.detail.isEmpty {
            lines.append(summary.detail)
        }
        if let network = summary.network, !network.isEmpty {
            lines.append("Network: \(network)")
        }
        if summary.peerCount > 0 || summary.peerGroups > 0 {
            lines.append("Peers: \(summary.peerCount) in \(summary.peerGroups) groups")
        }
        if summary.resourceCacheEntries > 0 || summary.resourceCacheBytes > 0 {
            lines.append(
                "Resolver cache: \(summary.resourceCacheEntries) entries, "
                    + "\(summary.resourceCacheBytes) bytes"
            )
        }
        return lines.joined(separator: "\n")
    }
}
