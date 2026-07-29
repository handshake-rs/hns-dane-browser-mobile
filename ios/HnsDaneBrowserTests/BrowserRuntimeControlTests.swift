import Foundation
import HnsBrowserRuntime
import UIKit
import XCTest
@testable import HnsDaneBrowser

final class BrowserRuntimeControlTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "BrowserRuntimeControlTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testProofContainedHNSResolutionUsesLocalVerifiedProofLabel() {
        XCTAssertEqual(
            BrowserDiagnosticReports.resolutionSource(
                traceJSON: #"{"resolutionSource":"hns_resource"}"#
            ),
            "Local verified HNS proof"
        )
    }

    func testPort53InterceptionGuidanceRequiresConfirmedTraceEvidence() {
        XCTAssertTrue(
            BrowserDiagnosticReports.port53InterceptionDetected(
                traceJSON: #"{"port53Interception":"detected"}"#
            )
        )
        XCTAssertFalse(
            BrowserDiagnosticReports.port53InterceptionDetected(
                traceJSON: #"{"port53Interception":"not_detected"}"#
            )
        )
        XCTAssertFalse(BrowserDiagnosticReports.port53InterceptionDetected(traceJSON: nil))
    }

    func testPolicyNormalizesExplicitRecoveryButDiscardsLegacyModeFlags() {
        let configured = BrowserRuntimePolicy(
            resolutionMode: .compatibility,
            hnsDohResolver: "  HTTPS://Resolver.Example.NET:443/dns-query  ",
            statelessDANECertificates: true,
            experimentalP2PDNSRelay: true,
            legacyHNSDoHCompatibility: true
        )
        XCTAssertEqual(configured.resolutionMode, .strict)
        XCTAssertEqual(
            configured.hnsDohResolver,
            "https://resolver.example.net/dns-query"
        )
        XCTAssertFalse(configured.legacyHNSDoHCompatibility)
        XCTAssertTrue(configured.statelessDANECertificates)
        XCTAssertTrue(configured.experimentalP2PDNSRelay)
    }

    func testPolicyStoreRoundTripsNonSensitiveSettings() {
        let store = BrowserRuntimePolicyStore(defaults: defaults)
        let expected = BrowserRuntimePolicy(
            hnsDohResolver: "https://resolver.example.net/dns-query",
            statelessDANECertificates: true,
            experimentalP2PDNSRelay: true
        )

        store.save(expected)

        XCTAssertEqual(store.load(), expected)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertEqual(
            defaults.string(
                forKey: "hnsBrowser.runtimePolicy.hnsDohRecoveryResolver.v1"
            ),
            "https://resolver.example.net/dns-query"
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
    }

    func testPolicyStoreOneWayMigratesLegacyHNSFallbackSettings() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(
            "https://resolver.example/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility")
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.statelessDANE")
        defaults.set(false, forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertEqual(policy.resolutionMode, .strict)
        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertFalse(policy.legacyHNSDoHCompatibility)
        XCTAssertTrue(policy.statelessDANECertificates)
        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
    }

    func testLegacyFallbackConsentDoesNotBecomeRelayConsent() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(
            "https://resolver.example/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertEqual(
            defaults.object(
                forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay"
            ) as? Bool,
            false
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.legacyHNSDoHCompatibility"))
        XCTAssertFalse(
            BrowserRuntimePolicyStore(defaults: defaults).load().experimentalP2PDNSRelay
        )
    }

    func testLegacyMigrationPreservesIndependentRelayOptIn() {
        defaults.set("compatibility", forKey: "hnsBrowser.runtimePolicy.resolutionMode")
        defaults.set(true, forKey: "hnsBrowser.runtimePolicy.experimentalP2PDNSRelay")

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertTrue(policy.experimentalP2PDNSRelay)
        XCTAssertTrue(
            BrowserRuntimePolicyStore(defaults: defaults).load().experimentalP2PDNSRelay
        )
    }

    func testPolicyDefaultsRequireRequesterRelayOptIn() {
        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertEqual(policy.resolutionMode, .strict)
        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertFalse(policy.statelessDANECertificates)
        XCTAssertFalse(policy.experimentalP2PDNSRelay)
        XCTAssertFalse(policy.legacyHNSDoHCompatibility)
    }

    func testFreshNativeRuntimeAcceptsZeroRevisionForDefaultPolicy() throws {
        let dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RustBrowserRuntimeTests.\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: dataDirectory,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: dataDirectory) }

        let runtime = try RustBrowserRuntime(dataDirectory.path, network: .regtest)
        defer { runtime.close() }

        XCTAssertEqual(try runtime.updatePolicy(.default), 0)
    }

    func testRecoveryURLValidationRejectsUnsafeAndSpecialUseEndpoints() {
        XCTAssertEqual(BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL("  "), "")
        XCTAssertEqual(
            BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(
                "HTTPS://Resolver.Example.NET.:443/dns-query?profile=hns"
            ),
            "https://resolver.example.net/dns-query?profile=hns"
        )
        for invalid in [
            "http://resolver.example.net/dns-query",
            "https://user@resolver.example.net/dns-query",
            "https://resolver.example.net/dns-query#fragment",
            "https://resolver.example.net",
            "https://127.0.0.1/dns-query",
            "https://resolver.local/dns-query",
            "https://resolver.example.net:53/dns-query",
            "https://resolver.example.net:6000/dns-query",
            "https://resolver.example.net/{?dns}",
        ] {
            XCTAssertNil(
                BrowserRuntimePolicy.normalizeHNSDoHRecoveryURL(invalid),
                invalid
            )
        }
    }

    func testLegacyResolverKeyNeverResurrectsNewRecoveryConsent() {
        defaults.set(
            "https://resolver.example.net/dns-query",
            forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"
        )

        let policy = BrowserRuntimePolicyStore(defaults: defaults).load()

        XCTAssertNil(policy.hnsDohResolver)
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.hnsDohResolver"))
        XCTAssertNil(
            defaults.object(
                forKey: "hnsBrowser.runtimePolicy.hnsDohRecoveryResolver.v1"
            )
        )
    }

    @MainActor
    func testIOSSettingsKeepCompleteAndroidSectionAndRowOrder() {
        XCTAssertEqual(BrowserSettingsViewController.Section.allCases.map(\.title), [
            "Start page",
            "Privacy and data",
            "Appearance",
            "Language",
            "HNS resolution",
            "Diagnostics and tools",
            "About, legal, and support",
        ])
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .startPage),
            [.homepage, .setCurrentPageAsHomepage, .resetHomepage]
        )
        XCTAssertEqual(
            BrowserSettingsViewController.rows(in: .privacyAndData),
            [.cookies, .history, .downloads]
        )
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .appearance), [.theme])
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .language), [.appLanguage])

        let rows = BrowserSettingsViewController.rows(in: .hnsResolution)

        XCTAssertEqual(
            rows,
            [
                .handshakeNetwork,
                .statelessDANECertificates,
                .hnsDoHRecovery,
                .experimentalP2PDNSRelay,
                .addHNSRelayPeer,
                .clearResolverCache,
                .hnsSync,
            ]
        )
        XCTAssertEqual(rows.map(\.title), [
            "Handshake network",
            "Experimental stateless DANE certificates",
            "HNS recovery DNS over HTTPS",
            "Experimental P2P DNS relay",
            "Add HNS relay peer",
            "Clear resolver cache",
            "HNS sync",
        ])
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .diagnosticsAndTools), [
            .hnsDomainSetup,
            .resolverTrace,
            .hnsProofDetails,
            .tlsaDANEInspector,
            .diagnostics,
            .gateway,
        ])
        XCTAssertEqual(BrowserSettingsViewController.rows(in: .aboutLegalAndSupport), [
            .build,
            .legal,
            .privacyPolicy,
            .sourceCode,
        ])
    }

    @MainActor
    func testIOSSettingsExposeCurrentPageOnlyWhenAndroidWould() throws {
        let withoutPage = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        withoutPage.loadViewIfNeeded()
        XCTAssertEqual(withoutPage.numberOfSections(in: withoutPage.tableView), 7)
        XCTAssertEqual(
            withoutPage.tableView(withoutPage.tableView, numberOfRowsInSection: 0),
            2
        )

        let withPage = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            currentPageURL: "https://example.com/current"
        )
        withPage.loadViewIfNeeded()
        XCTAssertEqual(withPage.tableView(withPage.tableView, numberOfRowsInSection: 0), 3)
        let cell = withPage.tableView(
            withPage.tableView,
            cellForRowAt: IndexPath(row: 1, section: 0)
        )
        let content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        XCTAssertEqual(content.text, "Set current page as homepage")
        XCTAssertEqual(content.secondaryText, "https://example.com/current")
        XCTAssertEqual((cell.accessoryView as? UILabel)?.text, "Set")
    }

    @MainActor
    func testIOSSettingsUseAndroidActionLabelsAcrossEverySection() {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            currentPageURL: "https://example.com/current"
        )
        settings.loadViewIfNeeded()
        let expected: [[String?]] = [
            ["Edit", "Set", "Reset"],
            ["Manage", "View", "View"],
            ["Change"],
            ["Open"],
            ["Change", nil, "Edit", nil, "Add", "Clear", "View"],
            ["Open", "Open", "Open", "Open", "View", "View"],
            [nil, "View", "Open", "Open"],
        ]

        for (section, labels) in expected.enumerated() {
            XCTAssertEqual(
                settings.tableView(settings.tableView, numberOfRowsInSection: section),
                labels.count
            )
            for (row, label) in labels.enumerated() {
                let cell = settings.tableView(
                    settings.tableView,
                    cellForRowAt: IndexPath(row: row, section: section)
                )
                XCTAssertEqual((cell.accessoryView as? UILabel)?.text, label)
            }
        }
    }

    @MainActor
    func testStatelessDANEIsAToggleWithAndroidExplanations() throws {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        settings.loadViewIfNeeded()
        let indexPath = IndexPath(row: 1, section: 4)

        var cell = settings.tableView(settings.tableView, cellForRowAt: indexPath)
        var content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        let toggle = try XCTUnwrap(cell.accessoryView as? UISwitch)
        XCTAssertEqual(cell.accessibilityIdentifier, "settings.hns-resolution.stateless-dane-certificates")
        XCTAssertEqual(content.text, "Experimental stateless DANE certificates")
        XCTAssertEqual(
            content.secondaryText,
            "Off. Browser requests use the retained dual-root DNSSEC and TLSA plan."
        )
        XCTAssertFalse(toggle.isOn)

        settings.update(
            policy: BrowserRuntimePolicy(statelessDANECertificates: true),
            runtimeControlsAreAvailable: true,
            isOperationInFlight: false
        )
        cell = settings.tableView(settings.tableView, cellForRowAt: indexPath)
        content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        XCTAssertEqual(
            content.secondaryText,
            "On. Legacy certificate-carried evidence cannot be combined with retained dual-root plans; prepared browser requests fail closed."
        )
        XCTAssertTrue(try XCTUnwrap(cell.accessoryView as? UISwitch).isOn)
    }

    @MainActor
    func testHNSSyncRowNavigatesBeforeAnExplicitRun() throws {
        let initialSummary = BrowserSyncSummary(
            headline: "Handshake sync idle",
            detail: "Local height 300000 · peer height 300100 · accepted 0/0",
            status: "idle",
            network: "mainnet",
            peerCount: 4,
            peerGroups: 2,
            bestHeight: 300_000,
            bestPeerHeight: 300_100
        )
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            syncSummary: initialSummary
        )
        let delegate = BrowserSettingsDelegateSpy()
        settings.delegate = delegate
        let navigation = UINavigationController(rootViewController: settings)
        navigation.loadViewIfNeeded()
        settings.loadViewIfNeeded()

        let settingsIndexPath = IndexPath(row: 6, section: 4)
        let settingsCell = settings.tableView(
            settings.tableView,
            cellForRowAt: settingsIndexPath
        )
        let settingsContent = try XCTUnwrap(
            settingsCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertEqual(
            settingsContent.secondaryText,
            "View sync status and run a manual sync."
        )

        settings.tableView(settings.tableView, didSelectRowAt: settingsIndexPath)

        let sync = try XCTUnwrap(navigation.topViewController as? HNSSyncViewController)
        XCTAssertTrue(delegate.actions.isEmpty)
        sync.loadViewIfNeeded()
        let statusCell = sync.tableView(
            sync.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        let statusContent = try XCTUnwrap(
            statusCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertTrue(statusContent.secondaryText?.contains("Handshake sync idle") == true)
        XCTAssertTrue(statusContent.secondaryText?.contains("Network: mainnet") == true)

        sync.tableView(sync.tableView, didSelectRowAt: IndexPath(row: 1, section: 0))
        XCTAssertEqual(delegate.actions, [.runHNSSync])
    }

    @MainActor
    func testHNSSyncStatusScreenReceivesLiveSummaryUpdates() throws {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true,
            syncSummary: .unavailable
        )
        let navigation = UINavigationController(rootViewController: settings)
        navigation.loadViewIfNeeded()
        settings.loadViewIfNeeded()
        settings.tableView(
            settings.tableView,
            didSelectRowAt: IndexPath(row: 6, section: 4)
        )
        let sync = try XCTUnwrap(navigation.topViewController as? HNSSyncViewController)
        sync.loadViewIfNeeded()

        settings.update(
            policy: .default,
            runtimeControlsAreAvailable: true,
            isOperationInFlight: false,
            syncSummary: BrowserSyncSummary(
                headline: "Handshake headers current",
                detail: "Local height 335942 · peer height 335942 · accepted 2/2",
                status: "up_to_date",
                network: "mainnet",
                attempted: 2,
                successful: 2,
                accepted: 2,
                peerCount: 8,
                peerGroups: 3,
                bestHeight: 335_942,
                bestPeerHeight: 335_942
            )
        )

        let statusCell = sync.tableView(
            sync.tableView,
            cellForRowAt: IndexPath(row: 0, section: 0)
        )
        let content = try XCTUnwrap(
            statusCell.contentConfiguration as? UIListContentConfiguration
        )
        XCTAssertTrue(content.secondaryText?.contains("Handshake headers current") == true)
        XCTAssertTrue(content.secondaryText?.contains("Peers: 8 in 3 groups") == true)
    }

    @MainActor
    func testIOSSettingsUseBackedAndroidDefaultsAndActionLabels() throws {
        let settings = BrowserSettingsViewController(
            policy: .default,
            runtimeControlsAreAvailable: true
        )
        settings.loadViewIfNeeded()

        let cache = settings.tableView(
            settings.tableView,
            cellForRowAt: IndexPath(row: 5, section: 4)
        )
        XCTAssertEqual(
            try XCTUnwrap(cache.contentConfiguration as? UIListContentConfiguration)
                .secondaryText,
            "Ready to clear cached resolver values."
        )
        XCTAssertEqual((cache.accessoryView as? UILabel)?.text, "Clear")

        let hnsSync = settings.tableView(
            settings.tableView,
            cellForRowAt: IndexPath(row: 6, section: 4)
        )
        XCTAssertEqual((hnsSync.accessoryView as? UILabel)?.text, "View")

        let proof = settings.tableView(
            settings.tableView,
            cellForRowAt: IndexPath(row: 2, section: 5)
        )
        XCTAssertEqual((proof.accessoryView as? UILabel)?.text, "Open")

        let build = settings.tableView(
            settings.tableView,
            cellForRowAt: IndexPath(row: 0, section: 6)
        )
        let buildText = try XCTUnwrap(
            (build.contentConfiguration as? UIListContentConfiguration)?.secondaryText
        )
        XCTAssertTrue(buildText.hasPrefix("release "))
        XCTAssertTrue(buildText.contains(" ("))
        XCTAssertTrue(buildText.hasSuffix(")"))
    }

    func testPolicyStoreFallsBackForUnknownResolutionMode() {
        defaults.set(
            "future-mode",
            forKey: "hnsBrowser.runtimePolicy.resolutionMode"
        )

        XCTAssertEqual(
            BrowserRuntimePolicyStore(defaults: defaults).load().resolutionMode,
            .strict
        )
        XCTAssertNil(defaults.object(forKey: "hnsBrowser.runtimePolicy.resolutionMode"))
    }

    func testLegacyResolverAndWebPkiStatusesFailClosedButRecoveryDaneIsVerified() {
        let legacyResolver = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY,
            securityPath: HNS_BROWSER_SECURITY_PATH_STATELESS_DANE
        )
        let legacyPath = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH
        )
        let legacyWebPKI = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            allowsWebPkiFallback: true
        )
        let legacyTopLevelIcann = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"nameClass":"icann"}"#
        )
        let contradictoryIcann = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"namespaceResolution":{"outcome":"hnsOnly","selected":"icann"}}"#
        )
        let icannWebPKI = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            resolutionTraceJSON: #"{"namespaceResolution":{"outcome":"icannOnly","selected":"icann","reason":"onlyAvailableRoot","hnsState":"authenticatedAbsent","icannState":"present","fingerprint":null}}"#
        )

        XCTAssertEqual(legacyResolver.level, .blocked)
        XCTAssertEqual(legacyPath.level, .handshakeDANE)
        XCTAssertTrue(legacyPath.detail.contains("user-configured HNS recovery DoH"))
        XCTAssertEqual(legacyWebPKI.level, .blocked)
        XCTAssertEqual(legacyTopLevelIcann.level, .blocked)
        XCTAssertEqual(contradictoryIcann.level, .blocked)
        XCTAssertEqual(icannWebPKI.level, .webPKI)
        XCTAssertTrue(icannWebPKI.detail.contains("validating ICANN DoH"))
        XCTAssertTrue(icannWebPKI.detail.contains("ICANN only"))
    }

    func testCurrentDANEStatusRemainsVerifiedInSecurityUI() {
        let summary = RustBrowserProxySession.securitySummary(
            httpStatus: 200,
            tlsPolicy: HNS_BROWSER_TLS_POLICY_DANE,
            resolverPolicy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            securityPath: HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY
        )

        XCTAssertEqual(summary.level, .handshakeDANE)
        XCTAssertTrue(summary.detail.contains("P2P DNS relay"))
    }

    func testSyncSchedulingUsesBoundedFailureBackoff() {
        let policy = BrowserSyncSchedulingPolicy(
            progressInterval: 30,
            caughtUpInterval: 300,
            failureBackoff: [5, 15, 60]
        )

        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 1), 5)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 2), 15)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 3), 60)
        XCTAssertEqual(policy.delay(after: nil, consecutiveFailures: 20), 60)
    }

    func testSyncSchedulingSlowsDownWhenCaughtUp() {
        let policy = BrowserSyncSchedulingPolicy()
        let caughtUp = BrowserSyncSummary(
            headline: "Current",
            detail: "Current",
            syncStatusSchemaVersion: 2,
            status: "up_to_date",
            bestHeight: 339_308,
            effectiveTargetHeight: 339_308,
            lagBlocks: 0,
            freshness: "current",
            freshnessThresholdBlocks: 2,
            targetSource: "corroboratedPeers",
            targetPeerGroups: 3,
            targetEvidenceExpired: false
        )
        let syncing = BrowserSyncSummary(
            headline: "Syncing",
            detail: "Syncing",
            status: "syncing"
        )

        XCTAssertEqual(policy.delay(after: caughtUp, consecutiveFailures: 0), 600)
        XCTAssertEqual(policy.delay(after: syncing, consecutiveFailures: 0), 600)
        XCTAssertEqual(
            policy.delay(
                after: BrowserSyncSummary(
                    headline: "Syncing",
                    detail: "Advancing",
                    status: "syncing",
                    accepted: 1
                ),
                consecutiveFailures: 0
            ),
            30
        )
        XCTAssertTrue(
            BrowserSyncSummary(
                headline: "Attention",
                detail: "Peer failed",
                status: "peer_failed"
            ).requiresRetry
        )
    }

    func testIOSRecognizesAndroidCurrentSyncStates() throws {
        let policy = BrowserSyncSchedulingPolicy()
        for status in ["up_to_date", "synced", "attempted"] {
            let summary = try RustBrowserRuntime.syncSummary(from: [
                "syncStatusSchemaVersion": 2,
                "network": "mainnet",
                "status": status,
                "bestHeight": 339_308,
                "bestPeerHeight": 339_308,
                "estimatedTipHeight": 339_400,
                "effectiveTargetHeight": 339_308,
                "lagBlocks": 0,
                "freshness": "current",
                "freshnessThresholdBlocks": 2,
                "targetSource": "corroboratedPeers",
                "targetPeerGroups": 3,
                "targetEvidenceExpired": false,
            ])

            XCTAssertTrue(summary.isCaughtUp, status)
            XCTAssertFalse(summary.isBehind, status)
            XCTAssertEqual(summary.headline, "Handshake headers current", status)
            XCTAssertEqual(policy.delay(after: summary, consecutiveFailures: 0), 600)
        }

        let behind = try RustBrowserRuntime.syncSummary(from: [
            "syncStatusSchemaVersion": 2,
            "network": "mainnet",
            "status": "attempted",
            "bestHeight": 339_000,
            "bestPeerHeight": 339_308,
            "estimatedTipHeight": 339_400,
            "effectiveTargetHeight": 339_308,
            "lagBlocks": 308,
            "freshness": "stale",
            "freshnessThresholdBlocks": 2,
            "targetSource": "corroboratedPeers",
            "targetPeerGroups": 3,
            "targetEvidenceExpired": false,
        ])
        XCTAssertTrue(behind.isBehind)
        XCTAssertFalse(behind.isCaughtUp)
        XCTAssertEqual(behind.headline, "Syncing Handshake headers")
        XCTAssertEqual(policy.delay(after: behind, consecutiveFailures: 0), 600)

        let legacy = try RustBrowserRuntime.syncSummary(from: [
            "network": "mainnet",
            "status": "up_to_date",
            "bestHeight": 339_308,
            "bestPeerHeight": 339_308,
            "estimatedTipHeight": 339_400,
        ])
        XCTAssertFalse(legacy.isCaughtUp)
        XCTAssertNil(legacy.targetHeight)
        XCTAssertEqual(legacy.freshness, "unknown")
        XCTAssertEqual(legacy.headline, "Handshake sync up to date")
        XCTAssertEqual(policy.delay(after: legacy, consecutiveFailures: 0), 600)
    }

    func testAuthoritativeCurrentnessRequiresExactVersionedContract() throws {
        let current: [String: Any] = [
            "syncStatusSchemaVersion": 2,
            "status": "up_to_date",
            "bestHeight": 339_308,
            "effectiveTargetHeight": 339_308,
            "lagBlocks": 0,
            "freshness": "current",
            "freshnessThresholdBlocks": 2,
            "targetSource": "corroboratedPeers",
            "targetPeerGroups": 3,
            "targetEvidenceExpired": false,
        ]
        var variants: [[String: Any]] = []

        var missingVersion = current
        missingVersion.removeValue(forKey: "syncStatusSchemaVersion")
        variants.append(missingVersion)

        var legacyThreshold = current
        legacyThreshold["freshnessThresholdBlocks"] = 144
        variants.append(legacyThreshold)

        var insufficientGroups = current
        insufficientGroups["targetPeerGroups"] = 2
        variants.append(insufficientGroups)

        var expiredEvidence = current
        expiredEvidence["targetEvidenceExpired"] = true
        variants.append(expiredEvidence)

        var genesis = current
        genesis["bestHeight"] = 0
        genesis["effectiveTargetHeight"] = 0
        variants.append(genesis)

        for variant in variants {
            let summary = try RustBrowserRuntime.syncSummary(from: variant)
            XCTAssertFalse(summary.hasAuthoritativeCurrentness)
            XCTAssertFalse(summary.isCaughtUp)
        }
    }

    func testAuthorityAdmissionRequiresForegroundAuthoritativeCurrentness() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let current = BrowserSyncSummary(
            headline: "Handshake headers current",
            detail: "Current",
            syncStatusSchemaVersion: 2,
            status: "up_to_date",
            network: "mainnet",
            bestHeight: 339_308,
            effectiveTargetHeight: 339_308,
            lagBlocks: 0,
            freshness: "current",
            freshnessThresholdBlocks: 2,
            targetSource: "corroboratedPeers",
            targetPeerGroups: 3,
            targetEvidenceExpired: false
        )

        for network in [BrowserHandshakeNetwork.mainnet, .testnet] {
            let networkCurrent = BrowserSyncSummary(
                headline: current.headline,
                detail: current.detail,
                syncStatusSchemaVersion: current.syncStatusSchemaVersion,
                status: current.status,
                network: network.rawValue,
                bestHeight: current.bestHeight,
                effectiveTargetHeight: current.effectiveTargetHeight,
                lagBlocks: current.lagBlocks,
                freshness: current.freshness,
                freshnessThresholdBlocks: current.freshnessThresholdBlocks,
                targetSource: current.targetSource,
                targetPeerGroups: current.targetPeerGroups,
                targetEvidenceExpired: current.targetEvidenceExpired
            )
            XCTAssertTrue(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: true,
                    syncSummary: networkCurrent
                )
            )
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: false,
                    syncSummary: networkCurrent
                )
            )
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: network,
                    isForeground: true,
                    syncSummary: .unavailable
                )
            )
        }
    }

    func testAuthorityAdmissionRejectsStaleGenesisAndLegacyPublicNetworkState() {
        let policy = BrowserAuthorityAdmissionPolicy()
        for network in [BrowserHandshakeNetwork.mainnet, .testnet] {
            let summaries = [
                BrowserSyncSummary(
                    headline: "Stale",
                    detail: "Stale",
                    syncStatusSchemaVersion: 2,
                    network: network.rawValue,
                    bestHeight: 339_000,
                    effectiveTargetHeight: 339_308,
                    lagBlocks: 308,
                    freshness: "stale",
                    freshnessThresholdBlocks: 2,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                ),
                BrowserSyncSummary(
                    headline: "Genesis",
                    detail: "Genesis",
                    syncStatusSchemaVersion: 2,
                    network: network.rawValue,
                    bestHeight: 0,
                    effectiveTargetHeight: 0,
                    lagBlocks: 0,
                    freshness: "current",
                    freshnessThresholdBlocks: 2,
                    targetSource: "corroboratedPeers",
                    targetPeerGroups: 3,
                    targetEvidenceExpired: false
                ),
                BrowserSyncSummary(
                    headline: "Legacy",
                    detail: "Legacy",
                    network: network.rawValue,
                    bestHeight: 339_308,
                    freshness: "current"
                ),
            ]
            for summary in summaries {
                XCTAssertFalse(
                    policy.allowsProxyResume(
                        network: network,
                        isForeground: true,
                        syncSummary: summary
                    )
                )
            }
        }
    }

    func testRegtestAuthorityAdmissionMatchesRustReadinessPrerequisites() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let nonGenesis = BrowserSyncSummary(
            headline: "Ready",
            detail: "Ready",
            syncStatusSchemaVersion: 2,
            status: "up_to_date",
            network: "regtest",
            bestHeight: 1,
            freshness: "unknown"
        )
        let stale = BrowserSyncSummary(
            headline: "Stale",
            detail: "Stale",
            syncStatusSchemaVersion: 2,
            status: "syncing",
            network: "regtest",
            bestHeight: 1,
            freshness: "stale"
        )
        let genesis = BrowserSyncSummary(
            headline: "Genesis",
            detail: "Genesis",
            syncStatusSchemaVersion: 2,
            status: "syncing",
            network: "regtest",
            bestHeight: 0,
            freshness: "unknown"
        )

        XCTAssertTrue(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: nonGenesis
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: false,
                syncSummary: nonGenesis
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: stale
            )
        )
        XCTAssertFalse(
            policy.allowsProxyResume(
                network: .regtest,
                isForeground: true,
                syncSummary: genesis
            )
        )
    }

    func testAuthorityAdmissionRejectsWrongNetworkMissingSchemaAndErrors() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let wrongNetwork = BrowserSyncSummary(
            headline: "Ready",
            detail: "Ready",
            syncStatusSchemaVersion: 2,
            status: "up_to_date",
            network: "mainnet",
            bestHeight: 1,
            freshness: "unknown"
        )
        let missingSchema = BrowserSyncSummary(
            headline: "Ready",
            detail: "Ready",
            status: "up_to_date",
            network: "regtest",
            bestHeight: 1,
            freshness: "unknown"
        )
        let failed = BrowserSyncSummary(
            headline: "Failed",
            detail: "Failed",
            syncStatusSchemaVersion: 2,
            status: "error",
            network: "regtest",
            bestHeight: 1,
            freshness: "unknown",
            error: "sync failed"
        )

        for summary in [wrongNetwork, missingSchema, failed] {
            XCTAssertFalse(
                policy.allowsProxyResume(
                    network: .regtest,
                    isForeground: true,
                    syncSummary: summary
                )
            )
        }
    }

    func testAuthorityAdmissionReconciliationIsBidirectionalAndIdempotent() {
        let policy = BrowserAuthorityAdmissionPolicy()
        let ready = BrowserSyncSummary(
            headline: "Ready",
            detail: "Ready",
            syncStatusSchemaVersion: 2,
            status: "up_to_date",
            network: "regtest",
            bestHeight: 1,
            freshness: "unknown"
        )
        let stale = BrowserSyncSummary(
            headline: "Stale",
            detail: "Stale",
            syncStatusSchemaVersion: 2,
            status: "syncing",
            network: "regtest",
            bestHeight: 1,
            freshness: "stale"
        )

        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: ready,
                isAdmissionGranted: false
            ),
            .resume
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: ready,
                isAdmissionGranted: true
            ),
            .unchanged
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: stale,
                isAdmissionGranted: true
            ),
            .suspend
        )
        XCTAssertEqual(
            policy.reconciliationAction(
                network: .regtest,
                isForeground: true,
                syncSummary: stale,
                isAdmissionGranted: false
            ),
            .unchanged
        )
    }

    func testSyncSummaryRejectsMalformedUnsignedNumbers() throws {
        let malformedValues: [NSNumber] = [
            NSNumber(value: -1),
            NSNumber(value: 1.5),
            NSNumber(value: true),
            NSDecimalNumber(string: "18446744073709551616"),
        ]

        for malformed in malformedValues {
            let summary = try RustBrowserRuntime.syncSummary(from: [
                "syncStatusSchemaVersion": 2,
                "status": "up_to_date",
                "bestHeight": malformed,
                "effectiveTargetHeight": 339_308,
                "lagBlocks": 0,
                "freshness": "current",
                "freshnessThresholdBlocks": 2,
                "targetSource": "corroboratedPeers",
                "targetPeerGroups": 3,
                "targetEvidenceExpired": false,
            ])
            XCTAssertNil(summary.bestHeight)
            XCTAssertFalse(summary.isCaughtUp)
        }
    }

    func testNativeSyncSummaryPreservesUsefulRuntimeResults() throws {
        let summary = try RustBrowserRuntime.syncSummary(from: [
            "syncStatusSchemaVersion": 2,
            "network": "mainnet",
            "status": "up_to_date",
            "attempted": 4,
            "successful": 3,
            "accepted": 2,
            "failed": 1,
            "peerCount": 8,
            "peerGroups": 3,
            "bestHeight": 250_000,
            "bestPeerHeight": 250_000,
            "estimatedTipHeight": 250_000,
            "effectiveTargetHeight": 250_000,
            "lagBlocks": 0,
            "freshness": "current",
            "freshnessThresholdBlocks": 2,
            "targetSource": "corroboratedPeers",
            "targetPeerGroups": 3,
            "targetEvidenceExpired": false,
            "resourceCacheEntries": 14,
            "resourceCacheBytes": 4_096,
            "resourceCacheEvicted": 2,
            "error": NSNull(),
            "failures": [],
        ])

        XCTAssertEqual(summary.network, "mainnet")
        XCTAssertEqual(summary.syncStatusSchemaVersion, 2)
        XCTAssertEqual(summary.status, "up_to_date")
        XCTAssertEqual(summary.attempted, 4)
        XCTAssertEqual(summary.successful, 3)
        XCTAssertEqual(summary.accepted, 2)
        XCTAssertEqual(summary.failed, 1)
        XCTAssertEqual(summary.peerCount, 8)
        XCTAssertEqual(summary.peerGroups, 3)
        XCTAssertEqual(summary.bestHeight, 250_000)
        XCTAssertEqual(summary.bestPeerHeight, 250_000)
        XCTAssertEqual(summary.estimatedTipHeight, 250_000)
        XCTAssertEqual(summary.effectiveTargetHeight, 250_000)
        XCTAssertEqual(summary.lagBlocks, 0)
        XCTAssertEqual(summary.freshness, "current")
        XCTAssertEqual(summary.freshnessThresholdBlocks, 2)
        XCTAssertEqual(summary.targetSource, "corroboratedPeers")
        XCTAssertEqual(summary.targetPeerGroups, 3)
        XCTAssertFalse(summary.targetEvidenceExpired)
        XCTAssertEqual(summary.resourceCacheEntries, 14)
        XCTAssertEqual(summary.resourceCacheBytes, 4_096)
        XCTAssertEqual(summary.resourceCacheEvicted, 2)
        XCTAssertFalse(summary.requiresRetry)
    }

    func testNativeProofDetailsRemainViewableAndExportable() throws {
        let details = try RustBrowserRuntime.proofDetails(
            from: [
                "host": "alice",
                "name": "alice",
                "network": "mainnet",
                "nameHash": "001122",
                "hnsProof": "verified",
                "proofStatus": "verified",
                "secure": true,
                "exists": true,
                "treeRoot": "aabbcc",
                "blockHeight": 250_000,
                "cacheStatus": "anchored_to_current_tip",
                "resourceValueHex": "00",
                "recordTypes": ["A", "TLSA"],
                "resourceRecords": [],
                "currentTip": ["height": 250_000],
                "error": NSNull(),
            ],
            fallbackHost: "fallback"
        )

        XCTAssertEqual(details.headline, "Handshake proof verified")
        XCTAssertEqual(details.host, "alice")
        XCTAssertEqual(details.proofStatus, "verified")
        XCTAssertEqual(details.secure, true)
        XCTAssertEqual(details.exists, true)
        XCTAssertEqual(details.blockHeight, 250_000)
        XCTAssertEqual(details.recordTypes, ["A", "TLSA"])
        XCTAssertTrue(details.formattedJSON.contains("\"proofStatus\" : \"verified\""))
    }

    func testDomainSetupReportUsesProofAndDecodedRecordEvidence() {
        let details = BrowserProofDetails(
            headline: "Handshake proof verified",
            detail: "Verified resource proof",
            host: "www.alice",
            name: "alice",
            network: "mainnet",
            nameHash: "001122",
            hnsProof: "verified",
            proofStatus: "verified",
            secure: true,
            exists: true,
            treeRoot: "aabbcc",
            blockHeight: 250_000,
            cacheStatus: "anchored_to_current_tip",
            recordTypes: ["A", "DS"],
            error: nil,
            formattedJSON: """
            {
              "resourceRecords": [
                {"name":"alice.","type":"A","class":1,"ttl":300,"rdataHex":"7f000001"}
              ]
            }
            """
        )

        let report = BrowserDiagnosticReports.domainSetup(details)

        XCTAssertTrue(report.contains("Host: www.alice"))
        XCTAssertTrue(report.contains("The proof contains usable delegation or address data."))
        XCTAssertTrue(report.contains("A alice. 7f000001"))
    }

    func testTLSADANEReportUsesCurrentResolutionTraceEvidence() {
        let trace = """
        {
          "host": "www.alice",
          "url": "https://www.alice/",
          "tls": {
            "mode": "dane",
            "tlsaOwner": "_443._tcp.www.alice",
            "tlsaStatus": "present",
            "tlsaFound": true,
            "dnssecSecure": true,
            "tlsaSource": "native_tlsa",
            "records": [{
              "usage": "DANE-EE",
              "selector": "SPKI",
              "matching": "SHA-256",
              "associationDataHex": "aabb"
            }],
            "certificate": {
              "webPkiStatus": "invalid",
              "endEntitySha256": "1122",
              "spkiSha256": "3344",
              "spkiDerHex": "5566",
              "intermediateCount": 1
            },
            "dane": {
              "decision": "verified",
              "matchedUsage": "DANE-EE",
              "certificateMatch": "pass",
              "webPkiFallback": false
            }
          }
        }
        """

        let report = BrowserDiagnosticReports.tlsaDANE(
            url: "https://www.alice/",
            traceJSON: trace
        )

        XCTAssertTrue(report.contains("TLSA owner: _443._tcp.www.alice"))
        XCTAssertTrue(report.contains("DANE decision: verified"))
        XCTAssertTrue(report.contains("TLSA found: true"))
        XCTAssertTrue(report.contains("WebPKI fallback: false"))
        XCTAssertTrue(report.contains("Usage: DANE-EE"))
        XCTAssertTrue(report.contains("Association data: aabb"))
    }

    func testTLSADANEReportRequiresCurrentTrace() {
        XCTAssertEqual(
            BrowserDiagnosticReports.tlsaDANE(url: "https://www.alice/", traceJSON: nil),
            "No TLSA/DANE resolution trace is available for the current page."
        )
    }

    @MainActor
    func testNetworkSwitchCommitsOnlyAfterReplacementIsReady() async throws {
        let factory = NetworkSwitchRuntimeFactory()
        let targetCreationStarted = expectation(description: "target runtime creation started")
        let allowTargetCreation = DispatchSemaphore(value: 0)
        factory.blockedNetwork = .regtest
        factory.onBlockedCreation = { targetCreationStarted.fulfill() }
        factory.creationGate = allowTargetCreation

        var persistedNetworks: [BrowserHandshakeNetwork] = []
        let process = BrowserProcess(
            runtimeFactory: factory.makeRuntime,
            initialNetwork: .testnet,
            persistNetwork: { persistedNetworks.append($0) }
        )
        defer { process.close() }

        let preparationCompleted = expectation(description: "initial runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        let previousEnvironment = try XCTUnwrap(preparationResult).get()
        let previousRuntime = try XCTUnwrap(
            previousEnvironment.runtime as? NetworkSwitchRuntimeStub
        )
        let previousRuntimeClosed = expectation(
            description: "previous runtime closed after commit"
        )
        previousRuntime.onClose = { previousRuntimeClosed.fulfill() }

        let switchCompleted = expectation(description: "network switch completed")
        var switchResult: Result<BrowserProcess.Environment, Error>?
        process.switchNetwork(to: .regtest) {
            switchResult = $0
            switchCompleted.fulfill()
        }

        await fulfillment(of: [targetCreationStarted], timeout: 2)
        XCTAssertEqual(process.currentNetwork, .testnet)
        XCTAssertTrue(persistedNetworks.isEmpty)
        XCTAssertFalse(previousRuntime.isClosed)

        allowTargetCreation.signal()
        await fulfillment(of: [switchCompleted, previousRuntimeClosed], timeout: 2)
        let replacementEnvironment = try XCTUnwrap(switchResult).get()
        XCTAssertFalse(replacementEnvironment.runtime === previousEnvironment.runtime)
        XCTAssertEqual(process.currentNetwork, .regtest)
        XCTAssertEqual(persistedNetworks, [.regtest])
        XCTAssertTrue(previousRuntime.isClosed)
    }

    @MainActor
    func testFailedNetworkSwitchPreservesReadyRuntimeAndDoesNotPersist() async throws {
        let factory = NetworkSwitchRuntimeFactory()
        factory.policyFailureNetwork = .regtest
        var persistedNetworks: [BrowserHandshakeNetwork] = []
        let process = BrowserProcess(
            runtimeFactory: factory.makeRuntime,
            initialNetwork: .testnet,
            persistNetwork: { persistedNetworks.append($0) }
        )
        defer { process.close() }

        let preparationCompleted = expectation(description: "initial runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        let previousEnvironment = try XCTUnwrap(preparationResult).get()
        let previousRuntime = try XCTUnwrap(
            previousEnvironment.runtime as? NetworkSwitchRuntimeStub
        )

        let switchCompleted = expectation(description: "failed network switch completed")
        var switchResult: Result<BrowserProcess.Environment, Error>?
        process.switchNetwork(to: .regtest) {
            switchResult = $0
            switchCompleted.fulfill()
        }
        await fulfillment(of: [switchCompleted], timeout: 2)

        XCTAssertThrowsError(try XCTUnwrap(switchResult).get())
        XCTAssertEqual(process.currentNetwork, .testnet)
        XCTAssertTrue(persistedNetworks.isEmpty)
        XCTAssertFalse(previousRuntime.isClosed)
        XCTAssertTrue(try XCTUnwrap(factory.lastRuntime(for: .regtest)).isClosed)

        let reuseCompleted = expectation(description: "previous environment remains ready")
        var reusedEnvironment: BrowserProcess.Environment?
        process.switchNetwork(to: .testnet) { result in
            reusedEnvironment = try? result.get()
            reuseCompleted.fulfill()
        }
        await fulfillment(of: [reuseCompleted], timeout: 2)
        XCTAssertTrue(reusedEnvironment?.runtime === previousEnvironment.runtime)
    }

    @MainActor
    func testSyncMaintenanceSafePointRunsImmediatelyWhenIdle() {
        let process = BrowserProcess(
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        defer { process.close() }
        var callbackCount = 0

        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            }
        )
        XCTAssertEqual(callbackCount, 1)
    }

    @MainActor
    func testSyncMaintenanceSafePointsWaitForSyncAndDrainOnceInOrder() async throws {
        let runtime = NetworkSwitchRuntimeStub(network: .testnet, rejectsPolicy: false)
        let syncStarted = expectation(description: "sync entered runtime")
        let allowSyncToFinish = DispatchSemaphore(value: 0)
        runtime.onSyncStart = { syncStarted.fulfill() }
        runtime.syncGate = allowSyncToFinish
        let process = BrowserProcess(
            runtimeFactory: { _, _ in runtime },
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )
        defer {
            allowSyncToFinish.signal()
            process.close()
        }

        let preparationCompleted = expectation(description: "runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        _ = try XCTUnwrap(preparationResult).get()

        let syncCompleted = expectation(description: "sync completed")
        process.syncNow { _ in syncCompleted.fulfill() }
        await fulfillment(of: [syncStarted], timeout: 2)

        var callbackOrder: [Int] = []
        let firstSafePoint = expectation(description: "first safe point")
        let secondSafePoint = expectation(description: "second safe point")
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                XCTAssertFalse(runtime.isSyncRunning)
                callbackOrder.append(1)
                firstSafePoint.fulfill()
            }
        )
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                XCTAssertFalse(runtime.isSyncRunning)
                callbackOrder.append(2)
                secondSafePoint.fulfill()
            }
        )
        XCTAssertTrue(callbackOrder.isEmpty)

        allowSyncToFinish.signal()
        await fulfillment(
            of: [firstSafePoint, secondSafePoint, syncCompleted],
            timeout: 2
        )
        XCTAssertEqual(callbackOrder, [1, 2])
    }

    @MainActor
    func testClosingProcessDropsQueuedSyncMaintenanceSafePoint() async throws {
        let runtime = NetworkSwitchRuntimeStub(network: .testnet, rejectsPolicy: false)
        let syncStarted = expectation(description: "sync entered runtime")
        let runtimeClosed = expectation(description: "runtime closed")
        let allowSyncToFinish = DispatchSemaphore(value: 0)
        runtime.onSyncStart = { syncStarted.fulfill() }
        runtime.syncGate = allowSyncToFinish
        runtime.onClose = { runtimeClosed.fulfill() }
        let process = BrowserProcess(
            runtimeFactory: { _, _ in runtime },
            initialNetwork: .testnet,
            persistNetwork: { _ in }
        )

        let preparationCompleted = expectation(description: "runtime prepared")
        var preparationResult: Result<BrowserProcess.Environment, Error>?
        process.prepare {
            preparationResult = $0
            preparationCompleted.fulfill()
        }
        await fulfillment(of: [preparationCompleted], timeout: 2)
        _ = try XCTUnwrap(preparationResult).get()

        process.syncNow { _ in }
        await fulfillment(of: [syncStarted], timeout: 2)
        var callbackCount = 0
        XCTAssertTrue(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            }
        )

        process.close()
        allowSyncToFinish.signal()
        await fulfillment(of: [runtimeClosed], timeout: 2)
        await Task.yield()
        XCTAssertEqual(callbackCount, 0)
        XCTAssertFalse(
            process.performAtSyncMaintenanceSafePoint {
                callbackCount += 1
            }
        )
        XCTAssertEqual(callbackCount, 0)
    }
}

@MainActor
private final class BrowserSettingsDelegateSpy: BrowserSettingsViewControllerDelegate {
    private(set) var actions: [BrowserSettingsViewController.Action] = []

    func browserSettingsViewController(
        _ controller: BrowserSettingsViewController,
        didRequest action: BrowserSettingsViewController.Action
    ) {
        actions.append(action)
    }
}

private enum NetworkSwitchRuntimeTestError: Error {
    case rejectedPolicy
}

private final class NetworkSwitchRuntimeFactory {
    var blockedNetwork: BrowserHandshakeNetwork?
    var onBlockedCreation: (() -> Void)?
    var creationGate: DispatchSemaphore?
    var policyFailureNetwork: BrowserHandshakeNetwork?

    private let lock = NSLock()
    private var runtimes: [BrowserHandshakeNetwork: [NetworkSwitchRuntimeStub]] = [:]

    func makeRuntime(
        dataDirectory: String,
        network: BrowserHandshakeNetwork
    ) throws -> BrowserRuntime {
        _ = dataDirectory
        if network == blockedNetwork {
            onBlockedCreation?()
            creationGate?.wait()
        }
        let runtime = NetworkSwitchRuntimeStub(
            network: network,
            rejectsPolicy: network == policyFailureNetwork
        )
        lock.lock()
        runtimes[network, default: []].append(runtime)
        lock.unlock()
        return runtime
    }

    func lastRuntime(for network: BrowserHandshakeNetwork) -> NetworkSwitchRuntimeStub? {
        lock.lock()
        defer { lock.unlock() }
        return runtimes[network]?.last
    }
}

private final class NetworkSwitchRuntimeStub: BrowserRuntime {
    let network: BrowserHandshakeNetwork
    let rejectsPolicy: Bool
    var onClose: (() -> Void)?
    var onSyncStart: (() -> Void)?
    var syncGate: DispatchSemaphore?

    private let lock = NSLock()
    private var closed = false
    private var syncRunning = false

    init(network: BrowserHandshakeNetwork, rejectsPolicy: Bool) {
        self.network = network
        self.rejectsPolicy = rejectsPolicy
    }

    var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    var isSyncRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return syncRunning
    }

    func classifyNavigation(_ rawValue: String) throws -> BrowserDestination {
        throw BrowserCoreError.invalidAddress("unused network-switch test stub")
    }

    func classifyHost(_ host: String) -> BrowserHostKind { .handshake }

    func canonicalHost(_ host: String) -> String? { host.lowercased() }

    func startWholeWebKitProxy(hnsScopeRoot: String?) throws -> BrowserProxySession {
        throw BrowserCoreError.proxyStartFailed("unused network-switch test stub")
    }

    func installHeaderSnapshot(at path: String) throws {}

    func updatePolicy(_ policy: BrowserRuntimePolicy) throws -> UInt64 {
        if rejectsPolicy { throw NetworkSwitchRuntimeTestError.rejectedPolicy }
        return 1
    }

    func syncOnce() throws -> BrowserSyncSummary {
        lock.lock()
        syncRunning = true
        lock.unlock()
        defer {
            lock.lock()
            syncRunning = false
            lock.unlock()
        }
        onSyncStart?()
        syncGate?.wait()
        return syncSummary()
    }

    func syncSummary() -> BrowserSyncSummary {
        BrowserSyncSummary(
            headline: "\(network.title) ready",
            detail: "transactional network-switch test runtime",
            status: "idle",
            network: network.rawValue
        )
    }

    func clearResolverCache() throws -> BrowserSyncSummary { syncSummary() }

    func proofDetails(for hostOrURL: String) throws -> BrowserProofDetails {
        throw BrowserCoreError.runtimeUnavailable("unused network-switch test stub")
    }

    func close() {
        let callback: (() -> Void)?
        lock.lock()
        if closed {
            callback = nil
        } else {
            closed = true
            callback = onClose
        }
        lock.unlock()
        callback?()
    }
}
