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
        case clearBrowsingData
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

    /// The root deliberately contains only these six destinations. Detail
    /// screens use the platform navigation stack rather than a long form.
    enum Destination: String, CaseIterable {
        case root
        case browser
        case homepage
        case privacy
        case handshake
        case handshakeAdvanced
        case advanced
        case about

        var title: String {
            switch self {
            case .root: "Settings"
            case .browser: "Browser"
            case .homepage: "Homepage"
            case .privacy: "Privacy & Data"
            case .handshake: "Handshake"
            case .handshakeAdvanced: "Handshake Advanced"
            case .advanced: "Advanced"
            case .about: "About"
            }
        }
    }

    enum Section: CaseIterable {
        case destinations
        case startPage
        case appearance
        case homepage
        case privacyData
        case privacyClearData
        case handshakeConnection
        case handshakeSecurity
        case handshakeAdvanced
        case resolverCache
        case tools
        case diagnostics
        case about
        case support

        var title: String? {
            switch self {
            case .destinations: nil
            case .startPage: "Start page"
            case .appearance: "Appearance"
            case .homepage: "Homepage"
            case .privacyData: "Privacy & data"
            case .privacyClearData: "Clear data"
            case .handshakeConnection: "Connection"
            case .handshakeSecurity: "Security"
            case .handshakeAdvanced: "Advanced networking"
            case .resolverCache: "Resolver cache"
            case .tools: "Handshake tools"
            case .diagnostics: "App & diagnostics"
            case .about: "Shakescape"
            case .support: "Support"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .destinations: "settings.destinations"
            case .startPage: "settings.section.start-page"
            case .appearance: "settings.section.appearance"
            case .homepage: "settings.section.homepage"
            case .privacyData: "settings.section.privacy-and-data"
            case .privacyClearData: "settings.section.clear-data"
            case .handshakeConnection: "settings.section.handshake-connection"
            case .handshakeSecurity: "settings.section.handshake-security"
            case .handshakeAdvanced: "settings.section.handshake-advanced"
            case .resolverCache: "settings.section.resolver-cache"
            case .tools: "settings.section.handshake-tools"
            case .diagnostics: "settings.section.diagnostics"
            case .about: "settings.section.about"
            case .support: "settings.section.support"
            }
        }
    }

    enum Row: Int, CaseIterable {
        case destinationBrowser
        case destinationPrivacy
        case destinationHandshake
        case wallet
        case destinationAdvanced
        case destinationAbout
        case homepage
        case currentHomepage
        case setCurrentPageAsHomepage
        case changeHomepage
        case resetHomepage
        case theme
        case appLanguage
        case cookies
        case history
        case downloads
        case clearBrowsingData
        case handshakeNetwork
        case hnsSync
        case statelessDANECertificates
        case hnsDoHRecovery
        case experimentalP2PDNSRelay
        case addHNSRelayPeer
        case handshakeAdvanced
        case clearResolverCache
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
            case .destinationBrowser: "Browser"
            case .destinationPrivacy: "Privacy & Data"
            case .destinationHandshake: "Handshake"
            case .wallet: "Wallet"
            case .destinationAdvanced: "Advanced"
            case .destinationAbout: "About"
            case .homepage, .currentHomepage: "Homepage"
            case .setCurrentPageAsHomepage: "Use current page"
            case .changeHomepage: "Change homepage"
            case .resetHomepage: "Reset homepage"
            case .theme: "Theme"
            case .appLanguage: "App language"
            case .cookies: "Cookies"
            case .history: "History"
            case .downloads: "Downloads"
            case .clearBrowsingData: "Clear browsing data"
            case .handshakeNetwork: "Handshake network"
            case .hnsSync: "Synchronization"
            case .statelessDANECertificates: "Stateless DANE"
            case .hnsDoHRecovery: "Recovery DNS"
            case .experimentalP2PDNSRelay: "P2P DNS relay"
            case .addHNSRelayPeer: "Relay peers"
            case .handshakeAdvanced: "Advanced"
            case .clearResolverCache: "Clear resolver cache"
            case .hnsDomainSetup: "Domain checker"
            case .resolverTrace: "Connection details"
            case .hnsProofDetails: "HNS proof details"
            case .tlsaDANEInspector: "TLSA / DANE inspector"
            case .diagnostics: "Diagnostics"
            case .gateway: "Gateway log"
            case .build: "Version"
            case .legal: "Legal"
            case .privacyPolicy: "Privacy policy"
            case .sourceCode: "Source code"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .destinationBrowser: "settings.destination.browser"
            case .destinationPrivacy: "settings.destination.privacy"
            case .destinationHandshake: "settings.destination.handshake"
            case .wallet: "settings.destination.wallet"
            case .destinationAdvanced: "settings.destination.advanced"
            case .destinationAbout: "settings.destination.about"
            case .homepage: "settings.browser.homepage"
            case .currentHomepage: "settings.homepage.current"
            case .setCurrentPageAsHomepage: "settings.homepage.use-current-page"
            case .changeHomepage: "settings.homepage.change"
            case .resetHomepage: "settings.homepage.reset"
            case .theme: "settings.browser.theme"
            case .appLanguage: "settings.browser.app-language"
            case .cookies: "settings.privacy-and-data.cookies"
            case .history: "settings.privacy-and-data.history"
            case .downloads: "settings.privacy-and-data.downloads"
            case .clearBrowsingData: "settings.privacy-and-data.clear-browsing-data"
            case .handshakeNetwork: "settings.handshake.network"
            case .hnsSync: "settings.handshake.sync"
            case .statelessDANECertificates: "settings.handshake.stateless-dane-certificates"
            case .hnsDoHRecovery: "settings.handshake.hns-doh-recovery"
            case .experimentalP2PDNSRelay: "settings.handshake.experimental-p2p-dns-relay"
            case .addHNSRelayPeer: "settings.handshake.add-hns-relay-peer"
            case .handshakeAdvanced: "settings.handshake.advanced"
            case .clearResolverCache: "settings.handshake.clear-resolver-cache"
            case .hnsDomainSetup: "settings.advanced.hns-domain-setup"
            case .resolverTrace: "settings.advanced.resolver-trace"
            case .hnsProofDetails: "browser-settings.proof-details"
            case .tlsaDANEInspector: "settings.advanced.tlsa-dane-inspector"
            case .diagnostics: "settings.advanced.diagnostics"
            case .gateway: "settings.advanced.gateway"
            case .build: "settings.about.version"
            case .legal: "settings.about.legal"
            case .privacyPolicy: "settings.about.privacy-policy"
            case .sourceCode: "settings.about.source-code"
            }
        }

        var isRuntimeAction: Bool {
            switch self {
            case .handshakeNetwork,
                 .hnsSync,
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
            default:
                false
            }
        }

        var isToggle: Bool {
            switch self {
            case .statelessDANECertificates, .experimentalP2PDNSRelay: true
            default: false
            }
        }

        var isDestructive: Bool {
            switch self {
            case .resetHomepage, .clearBrowsingData, .clearResolverCache: true
            default: false
            }
        }

        var showsDisclosure: Bool {
            switch self {
            case .destinationBrowser,
                 .destinationPrivacy,
                 .destinationHandshake,
                 .wallet,
                 .destinationAdvanced,
                 .destinationAbout,
                 .homepage,
                 .cookies,
                 .history,
                 .downloads,
                 .hnsSync,
                 .handshakeAdvanced,
                 .hnsDomainSetup,
                 .resolverTrace,
                 .hnsProofDetails,
                 .tlsaDANEInspector,
                 .diagnostics,
                 .gateway,
                 .legal,
                 .privacyPolicy,
                 .sourceCode:
                true
            default:
                false
            }
        }
    }

    static let privacyPolicyURL = "https://denuoweb.com/work/hns-dane-browser/privacy"
    static let sourceCodeURL = "https://github.com/handshake-rs/hns-dane-browser-mobile"

    weak var delegate: BrowserSettingsViewControllerDelegate?

    private let destination: Destination
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
    private weak var activeDestinationController: BrowserSettingsViewController?

    init(
        destination: Destination = .root,
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
        self.destination = destination
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
        title = destination.title
        view.backgroundColor = .systemGroupedBackground
        tableView.accessibilityIdentifier = "settings.table"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 76
        tableView.sectionHeaderHeight = UITableView.automaticDimension
        tableView.estimatedSectionHeaderHeight = 44
        if destination == .root {
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                barButtonSystemItem: .close,
                target: self,
                action: #selector(closeSettings)
            )
            navigationItem.rightBarButtonItem?.accessibilityLabel = "Close settings"
            navigationItem.rightBarButtonItem?.accessibilityIdentifier = "settings.close"
        }
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
        if isViewLoaded && tableStateChanged {
            tableView.reloadData()
        }
        hnsSyncViewController?.update(
            summary: syncSummary,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight
        )
        activeDestinationController?.update(
            policy: policy,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight,
            syncSummary: syncSummary,
            resolverCacheSummary: nextResolverCacheSummary,
            currentPageURL: nextCurrentPageURL,
            homepage: nextHomepage,
            historyCount: nextHistoryCount,
            downloadCount: nextDownloadCount,
            themeMode: nextThemeMode,
            handshakeNetwork: nextHandshakeNetwork
        )
    }

    func updateRelayPeerSummary(_ summary: String) {
        relayPeerSummary = summary
        if isViewLoaded {
            tableView.reloadData()
        }
        activeDestinationController?.updateRelayPeerSummary(summary)
    }

    static func rows(in section: Section) -> [Row] {
        switch section {
        case .destinations:
            [
                .destinationBrowser,
                .destinationPrivacy,
                .destinationHandshake,
                .wallet,
                .destinationAdvanced,
                .destinationAbout,
            ]
        case .startPage:
            [.homepage]
        case .appearance:
            [.theme, .appLanguage]
        case .homepage:
            [.currentHomepage, .setCurrentPageAsHomepage, .changeHomepage, .resetHomepage]
        case .privacyData:
            [.cookies, .history, .downloads]
        case .privacyClearData:
            [.clearBrowsingData]
        case .handshakeConnection:
            [.handshakeNetwork, .hnsSync]
        case .handshakeSecurity:
            [
                .statelessDANECertificates,
                .hnsDoHRecovery,
            ]
        case .handshakeAdvanced:
            [
                .experimentalP2PDNSRelay,
                .addHNSRelayPeer,
                .handshakeAdvanced,
            ]
        case .resolverCache:
            [.clearResolverCache]
        case .tools:
            [
                .hnsDomainSetup,
                .resolverTrace,
                .hnsProofDetails,
                .tlsaDANEInspector,
                .diagnostics,
                .gateway,
            ]
        case .about:
            // Apple requires developer tipping in App Store apps to use In-App Purchase.
            [.build, .legal, .privacyPolicy, .sourceCode]
        case .support:
            []
        }
    }

    private func sections(for destination: Destination) -> [Section] {
        switch destination {
        case .root: [.destinations]
        case .browser: [.startPage, .appearance]
        case .homepage: [.homepage]
        case .privacy: [.privacyData, .privacyClearData]
        case .handshake: [.handshakeConnection, .handshakeSecurity, .handshakeAdvanced]
        case .handshakeAdvanced: [.resolverCache]
        case .advanced: [.tools, .diagnostics]
        case .about: [.about]
        }
    }

    private func displayedRows(in section: Section) -> [Row] {
        Self.rows(in: section).filter {
            $0 != .setCurrentPageAsHomepage || currentPageURL != nil
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        sections(for: destination).count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard let section = section(at: section) else { return 0 }
        return displayedRows(in: section).count
    }

    override func tableView(
        _ tableView: UITableView,
        viewForHeaderInSection sectionIndex: Int
    ) -> UIView? {
        guard let section = section(at: sectionIndex), let title = section.title else { return nil }
        let header = UITableViewHeaderFooterView(reuseIdentifier: nil)
        var content = UIListContentConfiguration.groupedHeader()
        content.text = title
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
            openDestination(.homepage)
        case .destinationBrowser:
            openDestination(.browser)
        case .destinationPrivacy:
            openDestination(.privacy)
        case .destinationHandshake:
            openDestination(.handshake)
        case .destinationAdvanced:
            openDestination(.advanced)
        case .destinationAbout:
            openDestination(.about)
        case .currentHomepage:
            break
        case .setCurrentPageAsHomepage:
            guard let currentPageURL else { return }
            homepage = currentPageURL
            request(.setHomepage(currentPageURL))
            tableView.reloadData()
        case .changeHomepage:
            presentHomepageConfiguration()
        case .resetHomepage:
            confirmResetHomepage()
        case .cookies:
            request(.showCookies)
        case .history:
            request(.showHistory)
        case .downloads:
            request(.showDownloads)
        case .clearBrowsingData:
            confirmClearBrowsingData()
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
        case .handshakeAdvanced:
            openDestination(.handshakeAdvanced)
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

    private func section(at index: Int) -> Section? {
        let sections = sections(for: destination)
        guard sections.indices.contains(index) else { return nil }
        return sections[index]
    }

    private func row(at indexPath: IndexPath) -> Row? {
        guard let section = section(at: indexPath.section) else { return nil }
        let rows = displayedRows(in: section)
        guard rows.indices.contains(indexPath.row) else { return nil }
        return rows[indexPath.row]
    }

    private func summary(for row: Row) -> String {
        switch row {
        case .destinationBrowser:
            return "Homepage, appearance, and language."
        case .destinationPrivacy:
            return "Cookies, history, downloads, and browser data."
        case .destinationHandshake:
            return "Network, sync, validation, and DNS controls."
        case .destinationAdvanced:
            return "Handshake tools, diagnostics, and gateway activity."
        case .destinationAbout:
            return "Version, legal information, and source code."
        case .homepage:
            return homepage
        case .currentHomepage:
            return homepage
        case .setCurrentPageAsHomepage:
            return currentPageURL ?? ""
        case .changeHomepage:
            return "Choose an HTTPS URL or an HNS name."
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
        case .clearBrowsingData:
            return "Clear history, download records, cookies, and website data. Downloaded files remain on this device."
        case .wallet:
            return "Open your local Handshake account, recovery controls, and read-only HNS information."
        case .theme:
            return themeMode.summary
        case .appLanguage:
            return "Uses your iOS system or per-app language setting."
        case .handshakeNetwork:
            return "\(handshakeNetwork.title). \(handshakeNetwork.summary)"
        case .statelessDANECertificates:
            if policy.statelessDANECertificates {
                return "On by default. Normal DNSSEC and TLSA validation remains authoritative; a stateless HNS certificate is accepted only when its proof, DNSSEC chain, and TLSA evidence all validate."
            }
            return "Off. Stateless-only HNS sites fail closed. Sites with normal DNSSEC and TLSA records continue to work."
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
        case .handshakeAdvanced:
            return "Manage resolver cache and other low-level networking data."
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
        let selectable = enabled && row != .build && row != .currentHomepage
        cell.isUserInteractionEnabled = selectable
        cell.selectionStyle = selectable ? .default : .none
        applyEnabledAppearance(enabled || row == .build || row == .currentHomepage, to: cell)

        if row.isDestructive {
            var content = cell.contentConfiguration as? UIListContentConfiguration
                ?? .subtitleCell()
            content.textProperties.color = enabled ? .systemRed : .tertiaryLabel
            cell.contentConfiguration = content
        }

        if selectable && row.showsDisclosure {
            cell.accessoryType = .disclosureIndicator
        }
    }

    private func applyEnabledAppearance(_ enabled: Bool, to cell: UITableViewCell) {
        guard var content = cell.contentConfiguration as? UIListContentConfiguration else { return }
        content.textProperties.color = enabled ? .label : .tertiaryLabel
        content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
        cell.contentConfiguration = content
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

    private func confirmClearBrowsingData() {
        let alert = UIAlertController(
            title: "Clear browsing data?",
            message: "This clears history, download records, cookies, and website data from this browser. Files already downloaded to the device are not removed.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { [weak self] _ in
            self?.request(.clearBrowsingData)
        })
        present(alert, animated: true)
    }

    private func openDestination(_ destination: Destination) {
        guard let navigationController else { return }
        let controller = BrowserSettingsViewController(
            destination: destination,
            policy: policy,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight,
            syncSummary: syncSummary,
            resolverCacheSummary: resolverCacheSummary,
            currentPageURL: currentPageURL,
            homepage: homepage,
            historyCount: historyCount,
            downloadCount: downloadCount,
            themeMode: themeMode,
            handshakeNetwork: handshakeNetwork
        )
        controller.relayPeerSummary = relayPeerSummary
        controller.delegate = delegate
        activeDestinationController = controller
        navigationController.pushViewController(controller, animated: true)
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
