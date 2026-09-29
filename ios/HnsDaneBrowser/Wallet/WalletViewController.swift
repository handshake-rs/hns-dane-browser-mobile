import Security
import CryptoKit
import UIKit
import UniformTypeIdentifiers
import LocalAuthentication
import Network
import UserNotifications
@preconcurrency import AVFoundation
import CoreImage

private let defaultHnsMaximumFee = "0.1"
private let defaultHnsMaximumFeeBaseUnits = "100000"
private let bitcoinHtlcReceiverDustSats: UInt64 = 330
private let minimumBitcoinFeeReserveSats: UInt64 = 1_000
private let hnsSwapReceiverDustDollarydoos: UInt64 = 546
private let minimumHnsFeeReserveDollarydoos: UInt64 = 100_000
private let minimumBitcoinHtlcSats = bitcoinHtlcReceiverDustSats + minimumBitcoinFeeReserveSats
private let minimumHnsSwapDollarydoos =
    hnsSwapReceiverDustDollarydoos + minimumHnsFeeReserveDollarydoos
private let directShakescapeNetworkMaintenanceTicks = 30
private let directShakescapeExecutionPollTicks = 5
private let maximumDirectShakescapeFramesPerTick = 16
private let automaticSwapHnsSyncInterval: TimeInterval = 2 * 60
private let automaticSwapBitcoinSyncInterval: TimeInterval = 15 * 60
private let automaticSwapBitcoinStopGraceInterval: TimeInterval = 5 * 60
private let maximumVisibleDirectShakescapePeers = 3
private let showShakedexWalletCard = true

/// Presents authenticated journal and confirmed-receive transitions without
/// persisting wallet contents. UserDefaults contains random session IDs,
/// presentation deadlines, and one-way account/transaction fingerprints;
/// amounts, addresses, names, peers, and signed transaction material never
/// leave the encrypted native wallet database.
@MainActor
private final class AtomicSwapNotificationCoordinator {
    private struct Record {
        let sessionId: String
        let fingerprint: String
        let title: String
        let body: String
        let historicalTerminal: Bool
        let actionRequired: Bool
        let initialTitle: String?
        let initialBody: String?
    }

    private let center = UNUserNotificationCenter.current()
    private let defaults = UserDefaults.standard
    private let initializedKey = "atomic-swap-notifications.initialized.v1"
    private let fingerprintsKey = "atomic-swap-notifications.fingerprints.v1"
    private let fundingWarningsKey = "atomic-swap-notifications.funding-warnings.v1"
    private let terminalStates: Set<String> = ["completed", "refunded", "failed"]
    private var pendingFingerprints: [String: String] = [:]
    private let hnsReceiveInitializedKey = "hns-receive-notifications.initialized.v1"
    private let hnsReceiveAccountKey = "hns-receive-notifications.account.v1"
    private let hnsReceiveFingerprintsKey = "hns-receive-notifications.fingerprints.v1"
    private let maximumHnsReceiveFingerprints = 4_096
    private var pendingHnsFingerprints: Set<String> = []

    func reconcile(
        _ status: NativeShakescapeExecutionStatus,
        stageText: (NativeShakescapeExecutionSummary) -> String
    ) {
        var records: [String: Record] = [:]
        for offer in status.pendingOfferResponses {
            records[offer.sessionId] = Record(
                sessionId: offer.sessionId,
                fingerprint: "offer-response",
                title: WalletCopy.text("wallet_swap_notification_offer_accepted"),
                body: WalletCopy.format(
                    "wallet_swap_notification_offer_accepted_detail",
                    String(offer.sessionId.prefix(12))
                ),
                historicalTerminal: false,
                actionRequired: false,
                initialTitle: nil,
                initialBody: nil
            )
        }
        for pending in status.pendingAcceptances {
            let deadline = pending.fundingDeadlineUnix
            let now = UInt64(Date().timeIntervalSince1970)
            let oneHourWarning = deadline.map {
                $0 > now && $0 - now <= 60 * 60
            } ?? false
            records[pending.sessionId] = Record(
                sessionId: pending.sessionId,
                fingerprint: "pending-acceptance:\(deadline ?? 0):\(oneHourWarning)",
                title: WalletCopy.text(
                    oneHourWarning
                        ? "wallet_swap_notification_action_required"
                        : "wallet_swap_notification_updated"
                ),
                body: deadline.map {
                    WalletCopy.format(
                        "wallet_swap_acceptance_sent",
                        String(pending.sessionId.prefix(12)),
                        localDeadline($0),
                        remainingTime(until: $0)
                    )
                } ?? WalletCopy.format(
                    "wallet_swap_notification_negotiating",
                    String(pending.sessionId.prefix(12))
                ),
                historicalTerminal: false,
                actionRequired: oneHourWarning,
                initialTitle: nil,
                initialBody: nil
            )
        }
        for execution in status.executions {
            let stage = stageText(execution)
            let milestone = notificationMilestone(execution)
            let actionRequired = notificationRequiresAction(execution, milestone: milestone)
            let title: String
            switch execution.state {
            case "completed": title = WalletCopy.text("wallet_swap_notification_completed")
            case "refunded": title = WalletCopy.text("wallet_swap_notification_refunded")
            case "failed": title = WalletCopy.text("wallet_swap_notification_failed")
            case _ where actionRequired:
                title = WalletCopy.text("wallet_swap_notification_action_required")
            default: title = WalletCopy.text("wallet_swap_notification_updated")
            }
            records[execution.sessionId] = Record(
                sessionId: execution.sessionId,
                // Countdown text changes each minute. Semantic buckets issue
                // one notification for the first-chain confirmation, the
                // one-hour warning, expiry, and durable state transitions.
                fingerprint: "\(execution.state):\(execution.localRole):\(milestone)",
                title: title,
                body: WalletCopy.format(
                    "wallet_swap_notification_stage",
                    stage,
                    String(execution.sessionId.prefix(12))
                ),
                historicalTerminal: terminalStates.contains(execution.state),
                actionRequired: actionRequired,
                initialTitle: execution.localRole == "taker" &&
                    !terminalStates.contains(execution.state) &&
                    !actionRequired
                    ? WalletCopy.text("wallet_swap_notification_offer_accepted")
                    : nil,
                initialBody: execution.localRole == "taker" &&
                    !terminalStates.contains(execution.state) &&
                    !actionRequired
                    ? WalletCopy.format(
                        "wallet_swap_notification_offer_accepted_detail",
                        String(execution.sessionId.prefix(12))
                    )
                    : nil
            )
        }
        reconcileFundingWarnings(
            status,
            requestAuthorization: !records.values.contains { $0.actionRequired }
        )

        var fingerprints = defaults.dictionary(forKey: fingerprintsKey) as? [String: String] ?? [:]
        if !defaults.bool(forKey: initializedKey) {
            for record in records.values {
                if record.actionRequired {
                    post(record)
                } else {
                    fingerprints[record.sessionId] = record.fingerprint
                }
            }
            persist(fingerprints)
            defaults.set(true, forKey: initializedKey)
            return
        }

        for record in records.values {
            let previous = fingerprints[record.sessionId]
            guard previous != record.fingerprint else { continue }
            // Serialize delivery per session. If the native stage advances
            // while UserNotifications is completing an add, the next poll
            // publishes the newer stage after this request is acknowledged.
            guard pendingFingerprints[record.sessionId] == nil else { continue }
            if previous == nil && record.historicalTerminal {
                fingerprints[record.sessionId] = record.fingerprint
                continue
            }
            if previous == nil,
               let initialTitle = record.initialTitle,
               let initialBody = record.initialBody {
                post(Record(
                    sessionId: record.sessionId,
                    fingerprint: record.fingerprint,
                    title: initialTitle,
                    body: initialBody,
                    historicalTerminal: record.historicalTerminal,
                    actionRequired: record.actionRequired,
                    initialTitle: nil,
                    initialBody: nil
                ))
            } else {
                post(record)
            }
        }
        persist(fingerprints)
    }

    private func reconcileFundingWarnings(
        _ status: NativeShakescapeExecutionStatus,
        requestAuthorization: Bool
    ) {
        let now = UInt64(Date().timeIntervalSince1970)
        let fundingStates: Set<String> = [
            "terms_frozen",
            "refunds_prepared",
            "first_funding_pending",
            "first_funded",
            "second_funding_pending",
        ]
        let previous = defaults.dictionary(forKey: fundingWarningsKey)
            as? [String: String] ?? [:]
        var scheduled: [String: String] = [:]
        for pending in status.pendingAcceptances {
            guard let deadline = pending.fundingDeadlineUnix,
                  deadline > 60 * 60 else { continue }
            let warningAt = deadline - 60 * 60
            let identifier = fundingWarningIdentifier(pending.sessionId)
            guard warningAt > now else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                continue
            }
            let fingerprint = "\(deadline):pending"
            scheduled[pending.sessionId] = fingerprint
            guard previous[pending.sessionId] != fingerprint else { continue }
            let content = UNMutableNotificationContent()
            content.title = WalletCopy.text("wallet_swap_notification_action_required")
            content.body = WalletCopy.format(
                "wallet_swap_acceptance_sent",
                String(pending.sessionId.prefix(12)),
                localDeadline(deadline),
                WalletCopy.format("wallet_swap_duration_hours_minutes", 1, 0)
            )
            content.sound = .default
            content.categoryIdentifier = "ATOMIC_SWAP_STATUS"
            center.add(UNNotificationRequest(
                identifier: identifier,
                content: content,
                trigger: UNTimeIntervalNotificationTrigger(
                    timeInterval: TimeInterval(warningAt - now),
                    repeats: false
                )
            ))
        }
        for execution in status.executions where fundingStates.contains(execution.state) {
            let identifier = fundingWarningIdentifier(execution.sessionId)
            guard execution.fundingDeadlineUnix > 60 * 60 else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                continue
            }
            let warningAt = execution.fundingDeadlineUnix - 60 * 60
            guard warningAt > now else {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                continue
            }
            let targetChain = ["first_funded", "second_funding_pending"].contains(execution.state)
                ? execution.secondChain
                : execution.firstChain
            let target = notificationChainLabel(targetChain)
            let fingerprint = "\(execution.fundingDeadlineUnix):\(target)"
            scheduled[execution.sessionId] = fingerprint
            guard previous[execution.sessionId] != fingerprint else { continue }
            let deadlineFormatter = DateFormatter()
            deadlineFormatter.dateStyle = .medium
            deadlineFormatter.timeStyle = .short
            let deadline = deadlineFormatter.string(
                from: Date(timeIntervalSince1970: TimeInterval(execution.fundingDeadlineUnix))
            )
            let warningStage = WalletCopy.format(
                "wallet_swap_stage_with_deadline",
                WalletCopy.text("wallet_swap_notification_action_required"),
                WalletCopy.format("wallet_swap_duration_hours_minutes", 1, 0),
                target,
                deadline
            )
            let content = UNMutableNotificationContent()
            content.title = WalletCopy.text("wallet_swap_notification_action_required")
            content.body = WalletCopy.format(
                "wallet_swap_notification_stage",
                warningStage,
                String(execution.sessionId.prefix(12))
            )
            content.sound = .default
            content.categoryIdentifier = "ATOMIC_SWAP_STATUS"
            center.add(UNNotificationRequest(
                identifier: identifier,
                content: content,
                trigger: UNTimeIntervalNotificationTrigger(
                    timeInterval: TimeInterval(warningAt - now),
                    repeats: false
                )
            ))
        }
        let stale = Set(previous.keys).subtracting(scheduled.keys)
            .map { fundingWarningIdentifier($0) }
        if !stale.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: stale)
        }
        if scheduled != previous {
            defaults.set(scheduled, forKey: fundingWarningsKey)
        }
        if requestAuthorization, !scheduled.isEmpty {
            ensureFundingNotificationAuthorization()
        }
    }

    private func ensureFundingNotificationAuthorization() {
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                guard let self, settings.authorizationStatus == .notDetermined else { return }
                self.center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
            }
        }
    }

    private func fundingWarningIdentifier(_ sessionId: String) -> String {
        "atomic-swap-funding-warning:\(sessionId)"
    }

    private func notificationChainLabel(_ chain: String) -> String {
        switch chain {
        case "bitcoin": return "BTC"
        case "handshake": return "HNS"
        default: return chain.capitalized
        }
    }

    private func localDeadline(_ deadline: UInt64) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(deadline)))
    }

    private func remainingTime(until deadline: UInt64) -> String {
        let now = UInt64(Date().timeIntervalSince1970)
        let seconds = deadline > now ? deadline - now : 0
        let roundedMinutes = (seconds + 59) / 60
        let hours = roundedMinutes / 60
        let minutes = roundedMinutes % 60
        if hours > 0 {
            return WalletCopy.format(
                "wallet_swap_duration_hours_minutes", Int(hours), Int(minutes)
            )
        }
        return WalletCopy.format("wallet_swap_duration_minutes", Int(minutes))
    }

    private func notificationMilestone(
        _ execution: NativeShakescapeExecutionSummary
    ) -> String {
        let now = UInt64(Date().timeIntervalSince1970)
        let fundingStates: Set<String> = [
            "terms_frozen",
            "refunds_prepared",
            "first_funding_pending",
            "first_funded",
            "second_funding_pending",
        ]
        if fundingStates.contains(execution.state), now >= execution.fundingDeadlineUnix {
            return "funding-expired"
        }
        if fundingStates.contains(execution.state),
           execution.fundingDeadlineUnix > now,
           execution.fundingDeadlineUnix - now <= 60 * 60 {
            return "funding-one-hour"
        }
        if execution.state == "first_funded", execution.localRole == "taker" {
            return "second-funding-action"
        }
        if execution.state == "first_funding_pending", execution.localRole == "maker",
           now <= execution.firstFundingCutoffUnix {
            return "first-funding-action"
        }
        return execution.state
    }

    private func notificationRequiresAction(
        _ execution: NativeShakescapeExecutionSummary,
        milestone: String
    ) -> Bool {
        execution.state == "refund_eligible" ||
            [
                "funding-one-hour",
                "first-funding-action",
                "second-funding-action",
            ].contains(milestone) ||
            (execution.state == "both_funded" && execution.localRole == "maker") ||
            (["first_redeemed", "secret_observed"].contains(execution.state) &&
                execution.localRole == "taker")
    }

    /// Fixed-price name sales have no interactive acceptance packet. Their
    /// authoritative completion signal is the confirmed incoming HNS payment
    /// already present in the verified wallet snapshot. Establish a per-wallet
    /// baseline before alerting so restores never replay historical activity.
    func reconcileIncomingHns(
        _ snapshot: NativeHnsReadSnapshot,
        amountText: (String) -> String
    ) {
        let accountFingerprint = fingerprint(
            domain: "account",
            bytes: snapshot.receiveTarget.account
        )
        let incoming = snapshot.transactionHistory.filter { transaction in
            transaction.status == "confirmed" &&
                transaction.confirmationCount > 0 &&
                !transaction.netAmount.negative &&
                transaction.netAmount.magnitude != "0"
        }
        let current = Set(incoming.map { transaction in
            fingerprint(domain: "hns", bytes: transaction.txid)
        })
        if !defaults.bool(forKey: hnsReceiveInitializedKey) ||
            defaults.string(forKey: hnsReceiveAccountKey) != accountFingerprint {
            defaults.set(true, forKey: hnsReceiveInitializedKey)
            defaults.set(accountFingerprint, forKey: hnsReceiveAccountKey)
            defaults.set(
                Array(current.sorted().suffix(maximumHnsReceiveFingerprints)),
                forKey: hnsReceiveFingerprintsKey
            )
            return
        }

        let known = Set(defaults.stringArray(forKey: hnsReceiveFingerprintsKey) ?? [])
        for transaction in incoming {
            let transactionFingerprint = fingerprint(domain: "hns", bytes: transaction.txid)
            guard !known.contains(transactionFingerprint),
                  !pendingHnsFingerprints.contains(transactionFingerprint) else { continue }
            postIncomingHns(
                fingerprint: transactionFingerprint,
                amount: amountText(transaction.netAmount.magnitude)
            )
        }
    }

    private func post(_ record: Record) {
        pendingFingerprints[record.sessionId] = record.fingerprint
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                guard let self else { return }
                switch settings.authorizationStatus {
                case .authorized, .provisional, .ephemeral:
                    self.add(record)
                case .notDetermined:
                    self.center.requestAuthorization(options: [.alert, .sound]) {
                        [weak self] granted, _ in
                        DispatchQueue.main.async {
                            guard let self else { return }
                            if granted {
                                self.add(record)
                            } else {
                                self.clearPending(record)
                            }
                        }
                    }
                case .denied:
                    self.clearPending(record)
                @unknown default:
                    self.clearPending(record)
                }
            }
        }
    }

    private func add(_ record: Record) {
        let content = UNMutableNotificationContent()
        content.title = record.title
        content.body = record.body
        content.sound = .default
        content.categoryIdentifier = "ATOMIC_SWAP_STATUS"
        center.add(
            UNNotificationRequest(
                identifier: "atomic-swap:\(record.sessionId)",
                content: content,
                trigger: nil
            )
        ) { [weak self] error in
            DispatchQueue.main.async {
                guard let self else { return }
                guard self.clearPending(record) else { return }
                guard error == nil else { return }
                var fingerprints = self.defaults.dictionary(forKey: self.fingerprintsKey)
                    as? [String: String] ?? [:]
                fingerprints[record.sessionId] = record.fingerprint
                self.persist(fingerprints)
            }
        }
    }

    @discardableResult
    private func clearPending(_ record: Record) -> Bool {
        guard pendingFingerprints[record.sessionId] == record.fingerprint else { return false }
        pendingFingerprints.removeValue(forKey: record.sessionId)
        return true
    }

    private func persist(_ fingerprints: [String: String]) {
        let bounded = Dictionary(
            uniqueKeysWithValues: fingerprints.keys.sorted().suffix(1_024).compactMap { key in
                fingerprints[key].map { (key, $0) }
            }
        )
        defaults.set(bounded, forKey: fingerprintsKey)
    }

    private func postIncomingHns(fingerprint: String, amount: String) {
        pendingHnsFingerprints.insert(fingerprint)
        center.getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                guard let self else { return }
                switch settings.authorizationStatus {
                case .authorized, .provisional, .ephemeral:
                    let content = UNMutableNotificationContent()
                    content.title = WalletCopy.text("wallet_hns_received_notification_title")
                    content.body = WalletCopy.format(
                        "wallet_hns_received_notification_detail",
                        amount
                    )
                    content.sound = .default
                    content.categoryIdentifier = "WALLET_STATUS"
                    self.center.add(
                        UNNotificationRequest(
                            identifier: "hns-receive:\(fingerprint)",
                            content: content,
                            trigger: nil
                        )
                    ) { [weak self] error in
                        DispatchQueue.main.async {
                            guard let self else { return }
                            self.pendingHnsFingerprints.remove(fingerprint)
                            guard error == nil else { return }
                            var known = Set(
                                self.defaults.stringArray(
                                    forKey: self.hnsReceiveFingerprintsKey
                                ) ?? []
                            )
                            known.insert(fingerprint)
                            let bounded = Array(
                                known.sorted().suffix(self.maximumHnsReceiveFingerprints)
                            )
                            self.defaults.set(
                                bounded,
                                forKey: self.hnsReceiveFingerprintsKey
                            )
                        }
                    }
                case .notDetermined:
                    self.center.requestAuthorization(options: [.alert, .sound]) {
                        [weak self] granted, _ in
                        DispatchQueue.main.async {
                            guard let self else { return }
                            self.pendingHnsFingerprints.remove(fingerprint)
                            if granted {
                                self.postIncomingHns(fingerprint: fingerprint, amount: amount)
                            }
                        }
                    }
                case .denied:
                    self.pendingHnsFingerprints.remove(fingerprint)
                @unknown default:
                    self.pendingHnsFingerprints.remove(fingerprint)
                }
            }
        }
    }

    private func fingerprint(domain: String, bytes: [UInt8]) -> String {
        var data = Data(domain.utf8)
        data.append(contentsOf: bytes)
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

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

    override func present(
        _ viewControllerToPresent: UIViewController,
        animated flag: Bool,
        completion: (() -> Void)? = nil
    ) {
        if viewControllerToPresent is UIAlertController ||
            viewControllerToPresent is WalletMenuViewController ||
            viewControllerToPresent is WalletFormViewController {
            Self.frameWalletPopup(viewControllerToPresent)
        }
        super.present(viewControllerToPresent, animated: flag, completion: completion)
    }

    private static func frameWalletPopup(_ controller: UIViewController) {
        controller.loadViewIfNeeded()
        controller.view.layer.cornerRadius = 24
        controller.view.layer.cornerCurve = .continuous
        controller.view.layer.borderWidth = 1
        controller.view.layer.borderColor = UIColor.systemIndigo.cgColor
        controller.view.layer.masksToBounds = true
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
    private var bitcoinActivityPageOffset = 0
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
    private weak var walletNameImportAlert: UIViewController?
    private weak var walletNameImportField: UITextField?
    private weak var namesGalleryViewController: WalletNamesGalleryViewController?
    private weak var hnsSendFormAlert: UIAlertController?
    private weak var hnsSendApprovalAlert: UIAlertController?
    private var pendingHnsSendApproval: NativeHnsSendApproval?
    private weak var hnsValueApprovalAlert: UIAlertController?
    private var pendingHnsValueApproval: NativeHnsValueApproval?
    private var trackedShakedexFinalizePromptAttempts: Set<String> = []
    private var trackedShakedexFinalizeApprovalTransactionID: String?
    private var directShakescapeServiceTimer: Timer?
    private var directShakescapeServiceInFlight = false
    private var directShakescapeServiceTicks = 0
    private var directShakescapeExecutionTicks = 0
    private var directShakescapeStatusSnapshot: NativeDirectShakescapeStatus?
    private var shakescapeExecutionStatusSnapshot: NativeShakescapeExecutionStatus?
    private var latestReadSnapshotObservedAtUptime: TimeInterval?
    private var lastAutomaticSwapHnsSyncAtUptime: TimeInterval?
    private var lastAutomaticSwapHnsSyncFingerprint: String?
    private var lastAutomaticSwapBitcoinSyncAtUptime: TimeInterval?
    private var lastAutomaticSwapBitcoinSyncFingerprint: String?
    private var automaticSwapBitcoinSyncPausedUntilUptime: TimeInterval?
    private let atomicSwapNotifications = AtomicSwapNotificationCoordinator()
    private var recentDirectShakescapePeers: [String] = []
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
    private weak var hnsForBtcOfferApprovalAlert: UIAlertController?
    private var pendingHnsForBtcOfferApproval: NativeHnsForBtcOfferApproval?
    private weak var directOfferAcceptanceApprovalAlert: UIAlertController?
    private var pendingDirectOfferAcceptanceApproval: NativeDirectOfferAcceptanceApproval?
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
    private var latestObservedBrowserSyncSummary: BrowserSyncSummary?
    private var automaticWalletRefreshAttemptedHeight: UInt64?
    private var recoveryIdleTimerDisabledByWallet = false
    private var newWalletCreationRequested = false
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
            UIApplication.didEnterBackgroundNotification,
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
        let remainsInNavigationStack = navigationController?.viewControllers.contains {
            $0 === self
        } ?? true
        if walletViewDepartureRequiresRetirement(
            screenIsMovingFromParent: isMovingFromParent,
            screenIsBeingDismissed: isBeingDismissed,
            navigationIsBeingDismissed: navigationController?.isBeingDismissed == true,
            screenRemainsInNavigationStack: remainsInNavigationStack
        ) {
            walletAuthorityRequested = false
            stopWalletNetworkMonitoring()
            stopPendingOutgoingRefreshObserver()
            stopHnsSyncPresentationWatcher()
            protectWalletLifecycle()
        }
        updateRecoveryCeremonyScreenAwake(false)
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
        pendingHnsForBtcOfferApproval?.actionToken.discard()
        pendingHnsForBtcOfferApproval = nil
        pendingDirectOfferAcceptanceApproval?.actionToken.discard()
        pendingDirectOfferAcceptanceApproval = nil
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
        cellularDataWarningLabel.text = WalletCopy.text("wallet_cellular_warning")
        cellularDataWarningLabel.textColor = .systemOrange
        configureSummaryLabel(accountLabel, identifier: "wallet.account")
        configureSummaryLabel(readStatusLabel, identifier: "wallet.read-status")
        configureSummaryLabel(balanceLabel, identifier: "wallet.balance")
        configureSummaryLabel(paymentReceiveLabel, identifier: "wallet.receive")
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
        recoveryTitle.text = WalletCopy.text("wallet_restore_phrase_hint")
        recoveryTitle.isHidden = true

        recoveryTextView.font = .preferredFont(forTextStyle: .body)
        recoveryTextView.adjustsFontForContentSizeCategory = true
        recoveryTextView.isEditable = false
        recoveryTextView.isSelectable = false
        recoveryTextView.backgroundColor = .secondarySystemGroupedBackground
        recoveryTextView.layer.cornerRadius = 12
        recoveryTextView.textContainerInset = UIEdgeInsets(top: 14, left: 12, bottom: 14, right: 12)
        recoveryTextView.accessibilityLabel = "Recovery phrase"
        recoveryTextView.accessibilityHint =
            "Private wallet words. Record them offline before continuing."
        recoveryTextView.accessibilityIdentifier = "wallet.recovery-phrase"
        recoveryTextView.isHidden = true
        recoveryTextView.heightAnchor.constraint(greaterThanOrEqualToConstant: 150).isActive = true

        configureButton(
            createButton,
            title: WalletCopy.text("row_wallet_create"),
            action: #selector(createWallet)
        )
        configureButton(
            restoreButton,
            title: WalletCopy.text("row_wallet_restore"),
            action: #selector(restoreWallet)
        )
        configureButton(
            openButton,
            title: WalletCopy.text("row_wallet_unlock"),
            action: #selector(openOrUnlockWallet)
        )
        configureButton(lockButton, title: WalletCopy.text("action_lock_wallet"), action: #selector(lockWallet))
        configureButton(
            confirmRecoveryButton,
            title: WalletCopy.text("row_wallet_recovery_confirm"),
            action: #selector(confirmRecoverySaved)
        )
        configureButton(refreshButton, title: "Refresh status", action: #selector(refreshWallet))
        configureButton(
            synchronizeButton,
            title: WalletCopy.text("action_sync_wallet_reads"),
            action: #selector(synchronizeWalletReadsFromUserAction)
        )
        configureButton(
            bitcoinReceiveButton,
            title: WalletCopy.text("action_wallet_bitcoin_receive"),
            action: #selector(nextBitcoinReceiveAddress)
        )
        configureButton(
            bitcoinSyncButton,
            title: WalletCopy.text("row_wallet_bitcoin_sync"),
            action: #selector(toggleBitcoinSynchronization)
        )
        configureButton(
            bitcoinSendButton,
            title: WalletCopy.text("wallet_dashboard_send_bitcoin"),
            action: #selector(showBitcoinSendForm)
        )
        configureButton(
            bitcoinBirthdayButton,
            title: WalletCopy.text("wallet_bitcoin_birthday_title"),
            action: #selector(showBitcoinBirthdayForm)
        )
        configureButton(
            bitcoinSellForHnsButton,
            title: WalletCopy.text("wallet_swap_sell_btc"),
            action: #selector(showBtcForHnsOfferForm)
        )
        configureButton(
            bitcoinOffersButton,
            title: WalletCopy.text("wallet_swap_active_offers"),
            action: #selector(showActiveBtcForHnsOffers)
        )
        configureButton(
            bitcoinExecutionsButton,
            title: WalletCopy.text("wallet_swap_executions"),
            action: #selector(showShakescapeExecutions)
        )
        bitcoinBirthdayButton.isHidden = true
        configureButton(
            importNameButton,
            title: WalletCopy.text("row_wallet_name_import"),
            action: #selector(requestExactHnsNameImport)
        )
        importNameButton.accessibilityIdentifier = "wallet.import-hns-name"
        configureButton(
            deleteButton,
            title: WalletCopy.text("row_wallet_delete"),
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
        walletRefreshControl.accessibilityLabel = WalletCopy.text("action_sync_wallet_reads")
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
            title: WalletCopy.text("wallet_cellular_warning_title"),
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
        let creationHeight = currentAuthenticatedNewWalletBirthdayHeight()
        dashboardStack.addArrangedSubview(dashboardCard(
            title: isOperating ? "● WORKING · \(network.title)" : "NO WALLET · \(network.title)",
            body: walletStatusBody([statusLabel, accountLabel]),
            accent: .systemPink
        ))
        dashboardStack.addArrangedSubview(dashboardCard(
            title: creationHeight.map {
                WalletCopy.format("wallet_status_ready_to_create",
                    Int($0)
                )
            } ?? WalletCopy.text("wallet_status_waiting_for_initial_sync"),
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
            title: WalletCopy.text("wallet_status_recovery_required"),
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
        let send = dashboardButton(
            title: "Send",
            action: #selector(showHnsSendForm),
            accent: .systemIndigo,
            enabled: walletHnsPaymentActionsAvailable(
                baseAvailable: synchronizedReadsAvailable && directHnsValueAvailable && !isOperating,
                hasPendingOutgoing: pendingOutgoingSnapshotHeight != nil
            )
        )
        let sync = dashboardButton(
            title: "Sync",
            action: #selector(synchronizeWalletReadsFromUserAction),
            enabled: synchronizeButton.isEnabled
        )
        dashboardStack.addArrangedSubview(dashboardCard(
            title: WalletCopy.text("wallet_dashboard_hns_balance"),
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

        if !finalizeNotices.isEmpty {
            let notice = UILabel()
            notice.numberOfLines = 0
            notice.font = .preferredFont(forTextStyle: .body)
            notice.adjustsFontForContentSizeCategory = true
            notice.text = finalizeNotices.map(formatFinalizeNotice).joined(separator: "\n\n")
            dashboardStack.addArrangedSubview(dashboardCard(
                title: WalletCopy.text("wallet_dashboard_finalize_notice"),
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
        } ?? WalletCopy.text("wallet_dashboard_sync_required")
        let namesTile = dashboardTile(
            title: WalletCopy.text("wallet_dashboard_names"),
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
        let bitcoinTile = dashboardTile(
            title: "Bitcoin",
            summary: bitcoinValueAvailable ? WalletCopy.text("wallet_dashboard_ready") : WalletCopy.text("wallet_dashboard_sync_required"),
            enabled: !isOperating,
            action: { [weak self] in self?.showBitcoinDashboard() }
        )
        if showShakedexWalletCard {
            let shakedexSummary = finalizeNotices.first.map(formatFinalizeNotice)
                ?? (directShakescapeStatusSnapshot?.peerEndpoint == nil
                    ? "No board peer connected"
                    : "Board peer connected")
            let shakedexTile = dashboardTile(
                title: WalletCopy.text("wallet_dashboard_shakedex"),
                summary: shakedexSummary,
                enabled: !isOperating,
                action: { [weak self] in self?.showShakedexDashboard() }
            )
            dashboardStack.addArrangedSubview(dashboardTileRow(bitcoinTile, shakedexTile))
        } else {
            dashboardStack.addArrangedSubview(bitcoinTile)
        }
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
            showErrorMessage(WalletCopy.text("wallet_pending_outgoing_actions_disabled"))
            return
        }
        guard let paymentReceiveAddress = receiveTargets?.paymentAddress else { return }
        let viewer = HandshakeReceiveQrViewController(address: paymentReceiveAddress)
        walletPresentationHost.present(viewer, animated: true)
    }

    private func showBitcoinDashboard() {
        guard walletCanPresentChild else { return }
        let activitySummary: String
        if let snapshot = bitcoinSnapshot {
            let count = Int(snapshot.recentActivityTotal)
            activitySummary = count == 0
                ? WalletCopy.text("wallet_bitcoin_activity_empty")
                : WalletCopy.text("wallet_bitcoin_recent_activity")
        } else {
            activitySummary = WalletCopy.text("wallet_bitcoin_activity_unavailable")
        }
        var actions: [WalletMenuAction] = []
        if bitcoinReceiveButton.isEnabled {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_wallet_bitcoin_receive")) { [weak self] in
                self?.nextBitcoinReceiveAddress()
            })
        }
        if bitcoinSyncButton.isEnabled {
            actions.append(WalletMenuAction(
                title: bitcoinSyncInProgress
                    ? WalletCopy.text("action_stop_bitcoin_sync")
                    : WalletCopy.text("row_wallet_bitcoin_sync")
            ) { [weak self] in
                self?.toggleBitcoinSynchronization()
            })
        }
        if bitcoinBirthdayButton.isEnabled && !bitcoinBirthdayButton.isHidden {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_wallet_bitcoin_birthday")) { [weak self] in
                self?.showBitcoinBirthdayForm()
            })
        }
        if bitcoinSendButton.isEnabled {
            actions.append(WalletMenuAction(title: WalletCopy.text("wallet_dashboard_send_bitcoin")) { [weak self] in
                self?.showBitcoinSendForm()
            })
        }
        if bitcoinSnapshot != nil {
            actions.append(WalletMenuAction(title: WalletCopy.text("wallet_bitcoin_recent_activity")) { [weak self] in
                self?.showBitcoinActivity()
            })
        }
        presentWalletMenu(
            title: WalletCopy.text("row_wallet_bitcoin_status"),
            rows: [
                WalletMenuRow(title: WalletCopy.text("row_wallet_bitcoin_status"), detail: bitcoinStatusLabel.text ?? WalletCopy.text("wallet_bitcoin_sync_failed")) { [weak self] in
                    self?.bitcoinStatusLabel.text ?? WalletCopy.text("wallet_bitcoin_sync_failed")
                },
                WalletMenuRow(title: WalletCopy.text("row_wallet_bitcoin_balance"), detail: bitcoinBalanceLabel.text ?? WalletCopy.text("wallet_bitcoin_balance_unavailable")) { [weak self] in
                    self?.bitcoinBalanceLabel.text ?? WalletCopy.text("wallet_bitcoin_balance_unavailable")
                },
                WalletMenuRow(title: WalletCopy.text("row_wallet_bitcoin_receive"), detail: bitcoinReceiveLabel.text ?? WalletCopy.text("wallet_bitcoin_receive_unavailable")) { [weak self] in
                    self?.bitcoinReceiveLabel.text ?? WalletCopy.text("wallet_bitcoin_receive_unavailable")
                },
                WalletMenuRow(title: WalletCopy.text("wallet_bitcoin_recent_activity"), detail: activitySummary),
            ],
            actions: actions,
            retainForChildActions: true
        )
    }

    private func showBitcoinActivity() {
        guard walletCanPresentChild else { return }
        guard let snapshot = bitcoinSnapshot else {
            showErrorMessage(WalletCopy.text("wallet_bitcoin_activity_unavailable"))
            return
        }
        let pageSize = 20
        let total = Int(snapshot.recentActivityTotal)
        let lastPageOffset = total == 0 ? 0 : ((total - 1) / pageSize) * pageSize
        bitcoinActivityPageOffset = min(max(bitcoinActivityPageOffset, 0), lastPageOffset)
        let page: [NativeBitcoinActivity]
        let pageTotal: Int
        let hasMore: Bool
        if bitcoinActivityPageOffset == 0 {
            page = snapshot.recentActivity
            pageTotal = total
            hasMore = page.count < total
        } else {
            guard let wallet,
                  let loaded = try? wallet.bitcoinActivityPage(
                      offset: UInt32(bitcoinActivityPageOffset)
                  ) else {
                showErrorMessage(
                    WalletCopy.text("wallet_bitcoin_activity_page_failed")
                )
                return
            }
            page = loaded.activity
            pageTotal = Int(loaded.total)
            hasMore = loaded.hasMore
        }
        let message: String
        if page.isEmpty {
            message = WalletCopy.text("wallet_bitcoin_activity_empty")
        } else {
            let first = bitcoinActivityPageOffset + 1
            let last = bitcoinActivityPageOffset + page.count
            message = WalletCopy.format(
                "wallet_activity_page_position",
                first,
                last,
                pageTotal
            ) + "\n\n" +
                formatBitcoinActivity(page)
        }
        var actions: [WalletMenuAction] = []
        if bitcoinActivityPageOffset > 0 {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_previous_wallet_activity")) { [weak self] in
                guard let self else { return }
                self.bitcoinActivityPageOffset -= pageSize
                self.showBitcoinActivity()
            })
        }
        if hasMore {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_next_wallet_activity")) { [weak self] in
                guard let self else { return }
                self.bitcoinActivityPageOffset += pageSize
                self.showBitcoinActivity()
            })
        }
        if !page.isEmpty {
            actions.append(WalletMenuAction(title: WalletCopy.text("wallet_action_copy")) {
                UIPasteboard.general.setItems(
                    [[UTType.plainText.identifier: message]],
                    options: [.localOnly: true]
                )
            })
        }
        presentWalletMenu(
            title: WalletCopy.text("wallet_bitcoin_recent_activity"),
            rows: [WalletMenuRow(
                title: WalletCopy.text("wallet_bitcoin_recent_activity"),
                detail: message
            )],
            actions: actions
        )
    }

    private func formatBitcoinActivity(_ activity: [NativeBitcoinActivity]) -> String {
        activity.map { item in
            let amount: String
            switch item.direction {
            case "incoming":
                amount = WalletCopy.format(
                    "wallet_bitcoin_activity_incoming",
                    Int(item.amountSats)
                )
            case "outgoing":
                amount = WalletCopy.format(
                    "wallet_bitcoin_activity_outgoing",
                    Int(item.amountSats)
                )
            default:
                amount = WalletCopy.text("wallet_bitcoin_activity_self_transfer")
            }
            let status: String
            switch item.status {
            case "confirmed":
                status = WalletCopy.format(
                    "wallet_bitcoin_activity_confirmed",
                    Int(item.blockHeight ?? 0),
                    Int(item.confirmationCount ?? 0)
                )
            case "unconfirmed":
                status = WalletCopy.text("wallet_bitcoin_activity_unconfirmed")
            case "prepared":
                status = WalletCopy.text("wallet_bitcoin_activity_prepared")
            case "submissionStarted":
                status = WalletCopy.text("wallet_bitcoin_activity_submission_started")
            case "submitted":
                status = WalletCopy.text("wallet_bitcoin_activity_submitted")
            default:
                status = WalletCopy.text("wallet_bitcoin_activity_not_observed")
            }
            var lines = [amount]
            if let fee = item.feeSats {
                lines.append(WalletCopy.format("wallet_bitcoin_activity_fee", Int(fee)))
            }
            lines.append(status)
            lines.append(WalletCopy.format("wallet_bitcoin_activity_txid", item.txid))
            let date = Date(timeIntervalSince1970: TimeInterval(item.lastChangedAtUnix))
            lines.append(WalletCopy.format(
                "wallet_bitcoin_activity_updated",
                DateFormatter.localizedString(
                    from: date,
                    dateStyle: .medium,
                    timeStyle: .short
                )
            ))
            return lines.joined(separator: "\n")
        }.joined(separator: "\n\n")
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
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_bitcoin_receive_ready"
                    )
                    self.presentBitcoinReceiveAddress(receive.receiveAddress)
                case .failure(let error):
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func presentBitcoinReceiveAddress(_ address: String) {
        presentWalletMenu(
            title: WalletCopy.text("row_wallet_bitcoin_receive"),
            rows: [WalletMenuRow(
                title: WalletCopy.text("row_wallet_bitcoin_receive"),
                detail: address
            )],
            actions: [WalletMenuAction(title: WalletCopy.text("wallet_action_copy")) {
                UIPasteboard.general.setItems(
                    [[UTType.plainText.identifier: address]],
                    options: [.localOnly: true]
                )
            }]
        )
    }

    private func addFittingAddress(
        _ address: String,
        label: String,
        to alert: UIAlertController
    ) {
        alert.addTextField { field in
            field.text = address
            field.font = AppAccessibility.scaledMonospacedFont(
                size: 17,
                weight: .regular,
                textStyle: .body
            )
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
        presentWalletForm(
            title: WalletCopy.text("wallet_bitcoin_birthday_title"),
            message: WalletCopy.text("wallet_bitcoin_birthday_message"),
            fields: [WalletSheetFormField(
                label: WalletCopy.text("wallet_bitcoin_birthday_hint"),
                placeholder: WalletCopy.text("wallet_bitcoin_birthday_hint"),
                keyboardType: .numberPad,
                accessibilityIdentifier: "wallet.bitcoin.birthday"
            )],
            primaryTitle: WalletCopy.text("action_apply"),
            primaryStyle: .destructive
        ) { [weak self, weak wallet] values in
            guard let self, let wallet, self.wallet === wallet,
                  let text = values.first,
                  let height = UInt32(text), height > 0 else {
                self?.showErrorMessage(
                    WalletCopy.text("wallet_bitcoin_birthday_invalid_height")
                )
                return
            }
            self.setBitcoinBirthdayHeight(height, wallet: wallet)
        }
    }

    private func setBitcoinBirthdayHeight(
        _ height: UInt32,
        wallet: RustNativeWallet
    ) {
        bitcoinBirthdayResetInProgress = true
        bitcoinStatusLabel.text = WalletCopy.format(
            "wallet_bitcoin_birthday_setting", Int(height)
        )
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
                    self.bitcoinStatusLabel.text = WalletCopy.format(
                        "wallet_bitcoin_birthday_set", Int(snapshot.birthdayHeight)
                    )
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_bitcoin_birthday_failed"
                    )
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
        bitcoinStatusLabel.text = WalletCopy.text("wallet_bitcoin_syncing")
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
                    self.lastAutomaticSwapBitcoinSyncAtUptime = ProcessInfo.processInfo.systemUptime
                    self.renderBitcoinSnapshot(synchronization.snapshot)
                    let elapsed = String(format: "%.2fs", Double(synchronization.totalMs) / 1_000)
                    self.bitcoinStatusLabel.text = WalletCopy.format(
                        "wallet_bitcoin_synchronized",
                        Int(synchronization.checkpointHeight),
                        Int(synchronization.connectedPeerCount),
                        Int(synchronization.requiredPeerCount),
                        elapsed
                    )
                case .failure where stopped:
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_bitcoin_sync_stopped"
                    )
                    if let snapshot = try? wallet.bitcoinSnapshot() {
                        self.renderBitcoinSnapshot(snapshot)
                    }
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_bitcoin_sync_failed"
                    )
                    self.showError(error)
                }
                self.refreshButtonStates()
                // A long Bitcoin scan may overlap authenticated Handshake
                // blocks. Refresh ordinary HNS receipts once the shared native
                // controller is available again.
                self.maybeRefreshWalletAfterNewBlock()
            }
        }
    }

    private func stopBitcoinSynchronization() {
        guard let wallet, bitcoinSyncInProgress, !bitcoinSyncStopRequested else { return }
        bitcoinSyncStopRequested = true
        automaticSwapBitcoinSyncPausedUntilUptime =
            ProcessInfo.processInfo.systemUptime + automaticSwapBitcoinStopGraceInterval
        bitcoinStatusLabel.text = WalletCopy.text("wallet_bitcoin_sync_stopping")
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
                    let percentWhole = Int(progress.completionBasisPoints / 100)
                    let percentFraction = Int(progress.completionBasisPoints % 100)
                    let height = progress.chainHeight.map(String.init) ?? "—"
                    let elapsed = String(format: "%.1fs", Double(progress.cycleElapsedMs) / 1_000)
                    switch progress.stage {
                    case "connecting":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_connecting",
                            Int(progress.successfulHandshakes),
                            Int(progress.requiredPeerCount),
                            Int(progress.connectionFailures),
                            Int(progress.peerTimeouts),
                            Int(progress.incompatiblePeers),
                            elapsed
                        )
                    case "syncing_filters":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_progress",
                            percentWhole,
                            percentFraction,
                            height,
                            elapsed,
                            WalletCopy.text("wallet_bitcoin_sync_eta_calculating"),
                            Int(progress.processedFilterCount),
                            Int(progress.matchedFilterCount)
                        )
                    case "fetching_blocks":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_fetching_blocks",
                            Int(progress.downloadedBlockCount),
                            Int(progress.matchedFilterCount),
                            elapsed
                        )
                    case "applying_wallet":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_applying_wallet", elapsed
                        )
                    case "validating_chain":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_validating_chain", elapsed
                        )
                    case "reconciling":
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_reconciling", elapsed
                        )
                    case "ready":
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_dashboard_ready"
                        )
                    case "failed":
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_bitcoin_sync_failed"
                        )
                    default:
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_bitcoin_sync_failed"
                        )
                    }
                    if !["ready", "failed"].contains(progress.stage),
                       let progressText = self.bitcoinStatusLabel.text {
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_sync_background_guidance", progressText
                        )
                    }
                }
            }
        }
    }

    @objc private func showBitcoinSendForm() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress else { return }
        presentWalletForm(
            title: WalletCopy.text("wallet_dashboard_send_bitcoin"),
            message: WalletCopy.text("row_wallet_bitcoin_send_summary"),
            fields: [
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_bitcoin_send_destination_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_destination_hint"),
                    accessibilityIdentifier: "wallet.bitcoin.destination"
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_bitcoin_send_amount_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_amount_hint"),
                    keyboardType: .numberPad,
                    accessibilityIdentifier: "wallet.bitcoin.amount"
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_bitcoin_send_fee_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_fee_hint"),
                    keyboardType: .numberPad,
                    accessibilityIdentifier: "wallet.bitcoin.maximum-fee"
                ),
            ],
            primaryTitle: WalletCopy.text("action_prepare_wallet_send")
        ) { [weak self, weak wallet] fields in
            guard let self, let wallet, self.wallet === wallet,
                  fields.count == 3 else { return }
            self.authenticateWalletAction(
                reason: WalletCopy.text("wallet_auth_transaction_message")
            ) { [weak self, weak wallet] in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.prepareBitcoinSendAfterAuthentication(
                    wallet: wallet,
                    destination: fields[0],
                    amount: fields[1],
                    maximumFee: fields[2]
                )
            }
        }
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
        statusLabel.text = WalletCopy.text("wallet_status_preparing_bitcoin_send")
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
        let message = WalletCopy.format(
            "wallet_bitcoin_send_approval_message",
            approval.destination,
            Int(approval.amountSats),
            Int(approval.feeSats),
            Int(approval.maximumFeeSats),
            String(approval.expiresAtUnix)
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_bitcoin_send_approval_title"),
            message: message,
            preferredStyle: .alert
        )
        bitcoinSendApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) { [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBitcoinSendApproval else { return }
            self.pendingBitcoinSendApproval = nil
            DispatchQueue.global(qos: .userInitiated).async { try? wallet.rejectBitcoinSend(pending.actionToken) }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_broadcast_hns"), style: .destructive) {
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
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_bitcoin_send_accepted", receipt.txid
                        )
                        if let snapshot = try? wallet.bitcoinSnapshot() { self.renderBitcoinSnapshot(snapshot) }
                    case .failure:
                        self.refreshState()
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_bitcoin_send_ambiguous"
                        )
                        self.showErrorMessage(
                            WalletCopy.text("wallet_bitcoin_send_ambiguous")
                        )
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    @objc private func showBtcForHnsOfferForm() {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self] in
            self?.showBtcForHnsOfferFormAfterAuthentication()
        }
    }

    private func showBtcForHnsOfferFormAfterAuthentication() {
        guard let wallet, bitcoinValueAvailable, !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress, !isOperating else { return }
        presentWalletForm(
            title: WalletCopy.text("wallet_swap_sell_btc"),
            message: nil,
            fields: [
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_btc_amount_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_amount_hint"),
                    keyboardType: .numberPad,
                    initialValue: String(minimumBitcoinHtlcSats)
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_hns_amount_hint"),
                    placeholder: WalletCopy.text("wallet_send_amount_label"),
                    keyboardType: .decimalPad
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_fee_reserve_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_fee_hint"),
                    keyboardType: .numberPad,
                    initialValue: String(minimumBitcoinFeeReserveSats)
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_lifetime_hours_hint"),
                    placeholder: WalletCopy.text("wallet_swap_lifetime_hours_hint"),
                    keyboardType: .numberPad,
                    initialValue: "48"
                ),
            ],
            primaryTitle: WalletCopy.text("wallet_action_review_transaction")
        ) { [weak self, weak wallet] fields in
            guard let self, let wallet, self.wallet === wallet,
                  fields.count == 4,
                  let btc = UInt64(fields[0]), btc >= minimumBitcoinHtlcSats,
                  let hnsText = Self.positiveHnsBaseUnits(fields[1]),
                  let hns = UInt64(hnsText), hns >= minimumHnsSwapDollarydoos,
                  let reserve = UInt64(fields[2]), reserve >= minimumBitcoinFeeReserveSats,
                  btc >= reserve, btc - reserve >= bitcoinHtlcReceiverDustSats,
                  let hours = UInt64(fields[3]), (48...168).contains(hours),
                  hours <= UInt64.max / 3_600 else {
                self?.showErrorMessage(WalletCopy.text("wallet_swap_prepare_failed"))
                return
            }
            self.isOperating = true
            self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_preparing")
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
                        self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_prepare_failed")
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        }
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
        let message = WalletCopy.format(
            "wallet_swap_approval_message",
            Int(approval.btcAmountSats),
            hns,
            Int(approval.bitcoinFeeReserveSats),
            Int(approval.totalBitcoinCommitmentSats),
            expiry
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_approval_title"), message: message, preferredStyle: .alert
        )
        btcForHnsOfferApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsOfferApproval else { return }
            self.pendingBtcForHnsOfferApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectBtcForHnsOffer(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_publish"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsOfferApproval else { return }
            self.pendingBtcForHnsOfferApproval = nil
            self.isOperating = true
            self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_publishing")
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveBtcForHnsOffer(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let summary):
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_swap_published",
                            String(summary.offerId.prefix(12))
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_publish_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    @objc private func showActiveBtcForHnsOffers() {
        guard let wallet, walletIsUnlocked, bitcoinValueAvailable, !isOperating else { return }
        bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_loading_offers")
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.localBtcForHnsOffers() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let offers) where offers.isEmpty:
                    self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_no_active_offers")
                case .success(let offers):
                    let alert = UIAlertController(
                        title: WalletCopy.text("wallet_swap_active_offers"),
                        message: nil,
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
                    alert.addAction(UIAlertAction(
                        title: WalletCopy.text("wallet_action_done"),
                        style: .cancel
                    ))
                    self.walletPresentationHost.present(alert, animated: true)
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_list_failed")
                    self.showError(error)
                }
            }
        }
    }

    private func showHnsForBtcOfferForm() {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self] in
            self?.showHnsForBtcOfferFormAfterAuthentication()
        }
    }

    private func showHnsForBtcOfferFormAfterAuthentication() {
        guard let wallet, shakedexActionMayStart, bitcoinValueAvailable else { return }
        presentWalletForm(
            title: WalletCopy.text("wallet_swap_sell_hns"),
            message: nil,
            fields: [
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_hns_amount_hint"),
                    placeholder: WalletCopy.text("wallet_send_amount_label"),
                    keyboardType: .decimalPad
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_btc_requested_hint"),
                    placeholder: WalletCopy.text("wallet_bitcoin_send_amount_hint"),
                    keyboardType: .numberPad,
                    initialValue: String(minimumBitcoinHtlcSats)
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_hns_fee_reserve_hint"),
                    placeholder: WalletCopy.text("wallet_send_amount_label"),
                    keyboardType: .decimalPad,
                    initialValue: defaultHnsMaximumFee
                ),
                WalletSheetFormField(
                    label: WalletCopy.text("wallet_swap_lifetime_hours_hint"),
                    placeholder: WalletCopy.text("wallet_swap_lifetime_hours_hint"),
                    keyboardType: .numberPad,
                    initialValue: "48"
                ),
            ],
            primaryTitle: WalletCopy.text("wallet_action_review_transaction")
        ) { [weak self, weak wallet] fields in
            guard let self, let wallet, self.wallet === wallet, fields.count == 4,
                  let hnsText = Self.positiveHnsBaseUnits(fields[0]),
                  let hns = UInt64(hnsText), hns >= minimumHnsSwapDollarydoos,
                  let btc = UInt64(fields[1]), btc >= minimumBitcoinHtlcSats,
                  let reserveText = Self.positiveHnsBaseUnits(fields[2]),
                  let reserve = UInt64(reserveText), reserve >= minimumHnsFeeReserveDollarydoos,
                  hns >= reserve, hns - reserve >= hnsSwapReceiverDustDollarydoos,
                  let hours = UInt64(fields[3]), (48...168).contains(hours) else {
                self?.showErrorMessage(WalletCopy.text("wallet_swap_hns_prepare_failed"))
                return
            }
            self.isOperating = true
            self.bitcoinStatusLabel.text = WalletCopy.text(
                "wallet_status_preparing_value_action"
            )
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result {
                    try wallet.prepareHnsForBtcOffer(
                        hnsAmountDollarydoos: hns,
                        btcAmountSats: btc,
                        hnsFeeReserveDollarydoos: reserve,
                        listingLifetimeSeconds: hours * 3_600
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let approval):
                        self.presentHnsForBtcOfferApproval(approval, wallet: wallet)
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_publish_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        }
    }

    private func presentHnsForBtcOfferApproval(
        _ approval: NativeHnsForBtcOfferApproval,
        wallet: RustNativeWallet
    ) {
        pendingHnsForBtcOfferApproval?.actionToken.discard()
        pendingHnsForBtcOfferApproval = approval
        let offered = WalletReadPresenter.formatHnsBaseUnits(String(approval.hnsAmountDollarydoos))
        let reserve = WalletReadPresenter.formatHnsBaseUnits(String(approval.hnsFeeReserveDollarydoos))
        let total = WalletReadPresenter.formatHnsBaseUnits(String(approval.totalHnsCommitmentDollarydoos))
        let message = WalletCopy.format(
            "wallet_swap_hns_approval_message",
            offered,
            Int(approval.btcAmountSats),
            reserve,
            total
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_hns_approval_title"), message: message, preferredStyle: .alert
        )
        hnsForBtcOfferApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingHnsForBtcOfferApproval else { return }
            self.pendingHnsForBtcOfferApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectHnsForBtcOffer(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_publish"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingHnsForBtcOfferApproval else { return }
            self.pendingHnsForBtcOfferApproval = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveHnsForBtcOffer(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let summary):
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_swap_hns_published",
                            String(summary.offerId.prefix(12))
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_publish_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func directOfferLabel(_ offer: NativeDirectOfferSummary) -> String {
        WalletCopy.format("wallet_swap_offer_label",
            swapAmount(offer.offeredAmount, asset: offer.offeredAsset),
            swapAmount(offer.receivedAmount, asset: offer.receivedAsset),
            String(offer.offerId.prefix(12))
        )
    }

    private func directOfferAcceptanceLabel(_ offer: NativeDirectOfferSummary) -> String {
        WalletCopy.format("wallet_swap_offer_acceptance_label",
            swapAmount(offer.receivedAmount, asset: offer.receivedAsset),
            swapAmount(offer.offeredAmount, asset: offer.offeredAsset),
            String(offer.offerId.prefix(12))
        )
    }

    private func showMyDirectOffers() {
        guard let wallet, shakedexActionMayStart else { return }
        bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_loading_my_offers")
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.localDirectOffers() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let offers) where offers.isEmpty:
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_swap_no_active_offers"
                    )
                case .success(let offers):
                    self.presentWalletMenu(
                        title: WalletCopy.text("wallet_swap_my_offers"),
                        rows: [],
                        actions: offers.map { offer in
                            WalletMenuAction(title: self.directOfferLabel(offer)) { [weak self, weak wallet] in
                                guard let self, let wallet, self.wallet === wallet else { return }
                                self.confirmCancelDirectOffer(offer, wallet: wallet)
                            }
                        }
                    )
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_swap_available_failed"
                    )
                    self.showError(error)
                }
            }
        }
    }

    private func confirmCancelDirectOffer(
        _ offer: NativeDirectOfferSummary,
        wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_cancel_title"),
            message: WalletCopy.format(
                "wallet_swap_cancel_direct_message",
                directOfferLabel(offer),
                offer.offerId
            ),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_swap_keep_offer"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_cancel_offer"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet else { return }
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.cancelBtcForHnsOffer(offerId: offer.offerId) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    switch outcome {
                    case .success:
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_direct_cancelled"
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_direct_cancel_failed"
                        )
                        self.showError(error)
                    }
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func showAvailableDirectOffers() {
        guard let wallet, shakedexActionMayStart else { return }
        bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_loading_available_offers")
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.availableDirectOffers() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let offers) where offers.isEmpty:
                    self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_no_available_offers")
                    self.presentWalletMenu(
                        title: WalletCopy.text("wallet_swap_available_offers"),
                        rows: [WalletMenuRow(
                            title: WalletCopy.text("wallet_swap_no_available_offers"),
                            detail: WalletCopy.text("wallet_swap_no_available_offers")
                        )],
                        actions: []
                    )
                case .success(let offers):
                    self.presentWalletMenu(
                        title: WalletCopy.text("wallet_swap_available_offers"),
                        rows: [],
                        actions: offers.map { offer in
                            WalletMenuAction(title: self.directOfferAcceptanceLabel(offer)) { [weak self] in
                                self?.showDirectOfferAcceptanceForm(offer)
                            }
                        }
                    )
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_swap_available_failed"
                    )
                    self.showError(error)
                }
            }
        }
    }

    private func showDirectOfferAcceptanceForm(_ offer: NativeDirectOfferSummary) {
        let reserveIsBitcoin = offer.receivedAsset == "btc"
        presentWalletForm(
            title: WalletCopy.text("wallet_swap_acceptance_review"),
            message: directOfferAcceptanceLabel(offer),
            fields: [WalletSheetFormField(
                label: WalletCopy.text(
                    reserveIsBitcoin
                        ? "wallet_swap_acceptance_btc_fee_reserve_hint"
                        : "wallet_swap_acceptance_hns_fee_reserve_hint"
                ),
                placeholder: WalletCopy.text(
                    reserveIsBitcoin
                        ? "wallet_bitcoin_send_fee_hint"
                        : "wallet_action_maximum_fee_hint"
                ),
                keyboardType: reserveIsBitcoin ? .numberPad : .decimalPad,
                initialValue: reserveIsBitcoin ? String(minimumBitcoinFeeReserveSats) : defaultHnsMaximumFee
            )],
            primaryTitle: WalletCopy.text("wallet_swap_acceptance_review")
        ) { [weak self] fields in
            guard let self, let value = fields.first else { return }
            let reserve = reserveIsBitcoin
                ? UInt64(value)
                : Self.positiveHnsBaseUnits(value).flatMap { UInt64($0) }
            let minimumReserve = reserveIsBitcoin
                ? minimumBitcoinFeeReserveSats
                : minimumHnsFeeReserveDollarydoos
            let receiverDust = reserveIsBitcoin
                ? bitcoinHtlcReceiverDustSats
                : hnsSwapReceiverDustDollarydoos
            guard let reserve, reserve >= minimumReserve,
                  offer.receivedAmount >= reserve,
                  offer.receivedAmount - reserve >= receiverDust else {
                self.showErrorMessage(WalletCopy.text(
                    "wallet_swap_acceptance_prepare_failed"
                ))
                return
            }
            self.authenticateWalletAction(
                reason: WalletCopy.text("wallet_auth_transaction_message")
            ) {
                [weak self] in self?.prepareDirectOfferAcceptance(offer, feeReserve: reserve)
            }
        }
    }

    private func prepareDirectOfferAcceptance(_ offer: NativeDirectOfferSummary, feeReserve: UInt64) {
        guard let wallet, shakedexActionMayStart else { return }
        isOperating = true
        bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_acceptance_preparing")
        refreshButtonStates()
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result {
                try wallet.prepareDirectOfferAcceptance(
                    offerId: offer.offerId, receivedFeeReserve: feeReserve
                )
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                self.isOperating = false
                switch outcome {
                case .success(.approval(let approval)) where
                    approval.offer.offerId == offer.offerId:
                    self.presentDirectOfferAcceptanceApproval(approval, wallet: wallet)
                case .success(.approval(let approval)):
                    try? wallet.rejectDirectOfferAcceptance(approval.actionToken)
                    self.showErrorMessage(
                        WalletCopy.text("wallet_swap_acceptance_prepare_failed")
                    )
                case .success(.insufficientFunds(let receivedAsset, let confirmedAmount)):
                    let required = offer.receivedAmount
                    let message: String
                    if receivedAsset == "btc" {
                        message = required > confirmedAmount
                            ? WalletCopy.format(
                                "wallet_swap_acceptance_insufficient_btc",
                                Int(required - min(required, feeReserve)),
                                Int(feeReserve),
                                Int(required),
                                Int(confirmedAmount)
                            )
                            : WalletCopy.format(
                                "wallet_swap_acceptance_reserved_btc",
                                Int(required),
                                Int(confirmedAmount)
                            )
                    } else {
                        let lock = WalletReadPresenter.formatHnsBaseUnits(
                            String(required - min(required, feeReserve))
                        )
                        let reserve = WalletReadPresenter.formatHnsBaseUnits(
                            String(feeReserve)
                        )
                        message = required > confirmedAmount
                            ? WalletCopy.format(
                                "wallet_swap_acceptance_insufficient_hns",
                                lock, reserve,
                                WalletReadPresenter.formatHnsBaseUnits(String(required)),
                                WalletReadPresenter.formatHnsBaseUnits(
                                    String(confirmedAmount)
                                )
                            )
                            : WalletCopy.format(
                                "wallet_swap_acceptance_reserved_hns",
                                WalletReadPresenter.formatHnsBaseUnits(String(required)),
                                WalletReadPresenter.formatHnsBaseUnits(
                                    String(confirmedAmount)
                                )
                            )
                    }
                    self.bitcoinStatusLabel.text = message
                    self.showErrorMessage(message)
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text(
                        "wallet_swap_acceptance_prepare_failed"
                    )
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func presentDirectOfferAcceptanceApproval(
        _ approval: NativeDirectOfferAcceptanceApproval,
        wallet: RustNativeWallet
    ) {
        pendingDirectOfferAcceptanceApproval?.actionToken.discard()
        pendingDirectOfferAcceptanceApproval = approval
        let message = WalletCopy.format(
            "wallet_swap_acceptance_approval_message",
            directOfferAcceptanceLabel(approval.offer),
            swapAmount(approval.offer.receivedAmount, asset: approval.offer.receivedAsset),
            swapAmount(approval.receivedFeeReserve, asset: approval.offer.receivedAsset),
            swapAmount(
                approval.totalReceivedAssetCommitment,
                asset: approval.offer.receivedAsset
            )
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_acceptance_approval_title"),
            message: message,
            preferredStyle: .alert
        )
        directOfferAcceptanceApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingDirectOfferAcceptanceApproval else { return }
            self.pendingDirectOfferAcceptanceApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectDirectOfferAcceptance(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_acceptance_confirm"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingDirectOfferAcceptanceApproval else { return }
            self.pendingDirectOfferAcceptanceApproval = nil
            self.isOperating = true
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.approveDirectOfferAcceptance(pending.actionToken) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success(let summary):
                        if let deadline = summary.fundingDeadlineUnix {
                            let now = UInt64(Date().timeIntervalSince1970)
                            self.bitcoinStatusLabel.text = WalletCopy.format(
                                "wallet_swap_acceptance_sent",
                                String(summary.sessionId.prefix(12)),
                                self.formatAtomicSwapDeadline(deadline),
                                self.formatAtomicSwapRemaining(deadline, now: now)
                            )
                        } else {
                            self.bitcoinStatusLabel.text = WalletCopy.text(
                                "wallet_swap_acceptance_failed"
                            )
                        }
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_acceptance_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    @objc private func showShakescapeExecutions() {
        guard let wallet, walletIsUnlocked, bitcoinValueAvailable, !isOperating else { return }
        bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_loading_executions")
        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome = Result { try wallet.shakescapeExecutions() }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.wallet === wallet else { return }
                switch outcome {
                case .success(let status) where
                    status.executions.isEmpty && status.pendingAcceptances.isEmpty &&
                    status.pendingOfferResponses.isEmpty:
                    let message = WalletCopy.text("wallet_swap_no_executions_waiting")
                        + "\n\n"
                        + self.bitcoinBroadcastRecoveryText(
                            status.bitcoinBroadcastRecovery
                        )
                    self.bitcoinStatusLabel.text = message
                    let alert = UIAlertController(
                        title: WalletCopy.text("wallet_swap_executions"),
                        message: message,
                        preferredStyle: .alert
                    )
                    alert.addAction(UIAlertAction(
                        title: WalletCopy.text("wallet_action_ok"),
                        style: .default
                    ))
                    self.walletPresentationHost.present(alert, animated: true)
                case .success(let status):
                    self.bitcoinStatusLabel.text = self.bitcoinBroadcastRecoveryText(
                        status.bitcoinBroadcastRecovery
                    )
                    let terminal: Set<String> = ["completed", "refunded", "failed"]
                    let orderedExecutions = status.executions.sorted {
                        let leftTerminal = terminal.contains($0.state)
                        let rightTerminal = terminal.contains($1.state)
                        if leftTerminal != rightTerminal { return !leftTerminal }
                        return $0.lastVerifiedAtUnix > $1.lastVerifiedAtUnix
                    }
                    let liveExecutions = orderedExecutions.filter {
                        !terminal.contains($0.state)
                    }
                    if status.pendingOfferResponses.isEmpty,
                       status.pendingAcceptances.isEmpty, liveExecutions.count == 1,
                       let execution = liveExecutions.first {
                        self.showShakescapeExecution(execution, wallet: wallet)
                        return
                    }
                    let alert = UIAlertController(
                        title: WalletCopy.text("wallet_swap_executions"),
                        message: self.bitcoinBroadcastRecoveryText(
                            status.bitcoinBroadcastRecovery
                        ),
                        preferredStyle: .alert
                    )
                    for offer in status.pendingOfferResponses {
                        alert.addAction(UIAlertAction(
                            title: WalletCopy.format(
                                "wallet_swap_notification_offer_accepted_detail",
                                String(offer.sessionId.prefix(12))
                            ),
                            style: .default
                        ))
                    }
                    for acceptance in status.pendingAcceptances {
                        let offered = self.swapAmount(
                            acceptance.offeredAmount,
                            asset: acceptance.offeredAsset
                        )
                        let received = self.swapAmount(
                            acceptance.receivedAmount,
                            asset: acceptance.receivedAsset
                        )
                        alert.addAction(UIAlertAction(
                            title: WalletCopy.format(
                                "wallet_swap_pending_acceptance",
                                offered,
                                received,
                                self.swapAmount(
                                    acceptance.receivedFeeReserve,
                                    asset: acceptance.receivedAsset
                                ),
                                String(acceptance.sessionId.prefix(12))
                            ),
                            style: .default
                        ) { [weak self, weak wallet] _ in
                            guard let self, let wallet, self.wallet === wallet else { return }
                            self.confirmAbandonPendingAcceptance(acceptance, wallet: wallet)
                        })
                    }
                    for execution in orderedExecutions {
                        alert.addAction(UIAlertAction(
                            title: "\(execution.state.replacingOccurrences(of: "_", with: " ")) · \(execution.sessionId.prefix(12))…",
                            style: .default
                        ) { [weak self, weak wallet] _ in
                            guard let self, let wallet, self.wallet === wallet else { return }
                            self.showShakescapeExecution(execution, wallet: wallet)
                        })
                    }
                    alert.addAction(UIAlertAction(
                        title: WalletCopy.text("wallet_action_done"),
                        style: .cancel
                    ))
                    self.walletPresentationHost.present(alert, animated: true)
                case .failure(let error):
                    self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_execution_list_failed")
                    self.showError(error)
                }
            }
        }
    }

    private func swapAmount(_ amount: UInt64, asset: String) -> String {
        if asset == "hns" {
            return "\(WalletReadPresenter.formatHnsBaseUnits(String(amount))) HNS"
        }
        return "\(amount) sats"
    }

    private func confirmAbandonPendingAcceptance(
        _ acceptance: NativeDirectOfferAcceptanceSummary,
        wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_abandon_acceptance_title"),
            message: WalletCopy.format(
                "wallet_swap_abandon_acceptance_message",
                swapAmount(acceptance.receivedAmount, asset: acceptance.receivedAsset),
                acceptance.sessionId
            ),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_swap_keep_acceptance"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_abandon_acceptance"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet else { return }
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result {
                    try wallet.abandonPendingDirectOfferAcceptance(
                        sessionId: acceptance.sessionId
                    )
                }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    switch outcome {
                    case .success:
                        self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_acceptance_abandoned")
                        if let snapshot = self.latestReadSnapshot { self.publish(snapshot) }
                        self.showShakescapeExecutions()
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_acceptance_abandon_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func bitcoinBroadcastRecoveryText(
        _ recovery: NativeBitcoinBroadcastRecovery?
    ) -> String {
        guard let recovery else {
            return WalletCopy.text("wallet_swap_recovery_unavailable")
        }
        guard recovery.totalApproved > 0 else {
            return WalletCopy.text("wallet_swap_recovery_none")
        }
        return WalletCopy.format(
            "wallet_swap_recovery_detail",
            Int(recovery.unobservedPrepared),
            Int(recovery.unobservedSubmissionStarted),
            Int(recovery.unobservedSubmitted),
            Int(recovery.observed),
            Int(recovery.highestAttemptCount),
            Int(recovery.lastChangedAtUnix ?? 0)
        )
    }

    private func showShakescapeExecution(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let message = WalletCopy.format(
            "wallet_swap_execution_detail",
            execution.sessionId,
            execution.state.replacingOccurrences(of: "_", with: " "),
            execution.localRole,
            execution.firstChain,
            execution.secondChain,
            String(execution.firstFundingConfirmed),
            String(execution.secondFundingConfirmed),
            formatAtomicSwapDeadline(execution.fundingDeadlineUnix),
            formatAtomicSwapDeadline(execution.firstRefundAtUnix),
            formatAtomicSwapDeadline(execution.secondRefundAtUnix),
            execution.failureReason ?? WalletCopy.text("wallet_swap_failure_none")
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_execution_title"),
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_done"),
            style: .cancel
        ))
        // Acceptance binds the trade and its timeouts, not a later
        // fee-selected transaction which the user has never reviewed. Keep
        // that exact preview/approval and surface readiness as an immediate
        // action-required notification.
        let now = UInt64(Date().timeIntervalSince1970)
        let newFundingWindowOpen = now < execution.fundingDeadlineUnix
        let firstFundingMayStart = now <= execution.firstFundingCutoffUnix
        if execution.state == "first_funding_pending" && firstFundingMayStart &&
            execution.localRole == "maker" && execution.firstChain == "bitcoin" {
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_fund_bitcoin"), style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showBtcForHnsFundingFee(execution, wallet: wallet)
            })
        }
        if execution.state == "first_funding_pending" && firstFundingMayStart &&
            execution.localRole == "maker" && execution.firstChain == "handshake" {
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_fund_hns"), style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showHnsForBtcFundingFee(execution, wallet: wallet)
            })
        }
        if (execution.state == "first_funded" || execution.state == "second_funding_pending") &&
            newFundingWindowOpen &&
            execution.localRole == "taker" &&
            execution.secondChain == "handshake" {
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_fund_hns"), style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showHnsForBtcFundingFee(execution, wallet: wallet)
            })
        }
        if (execution.state == "first_funded" || execution.state == "second_funding_pending") &&
            newFundingWindowOpen &&
            execution.localRole == "taker" &&
            execution.secondChain == "bitcoin" {
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_fund_bitcoin"), style: .destructive) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showBtcForHnsFundingFee(execution, wallet: wallet)
            })
        }
        if execution.state == "first_funded" && execution.localRole == "maker" &&
            now >= execution.firstRefundAtUnix {
            let bitcoin = execution.firstChain == "bitcoin"
            alert.addAction(UIAlertAction(
                title: bitcoin
                    ? WalletCopy.text("wallet_swap_refund_bitcoin")
                    : WalletCopy.text("wallet_swap_refund_hns"),
                style: .destructive
            ) { [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.authenticateWalletAction(
                    reason: WalletCopy.text("wallet_auth_transaction_message")
                ) { [weak self, weak wallet] in
                    guard let self, let wallet, self.wallet === wallet else { return }
                    self.showSwapSettlementFee(
                        execution, action: .refund, bitcoin: bitcoin, wallet: wallet
                    )
                }
            })
        }
        if execution.state == "refund_eligible" {
            // Refund the chain funded by this participant without creating a
            // replacement offer or abandoning the durable session.
            let bitcoin = execution.localRole == "maker"
                ? execution.firstChain == "bitcoin"
                : execution.secondChain == "bitcoin"
            alert.addAction(UIAlertAction(
                title: bitcoin
                    ? WalletCopy.text("wallet_swap_refund_bitcoin")
                    : WalletCopy.text("wallet_swap_refund_hns"),
                style: .destructive
            ) { [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.authenticateWalletAction(
                    reason: WalletCopy.text("wallet_auth_transaction_message")
                ) { [weak self, weak wallet] in
                    guard let self, let wallet, self.wallet === wallet else { return }
                    self.showSwapSettlementFee(
                        execution, action: .refund, bitcoin: bitcoin, wallet: wallet
                    )
                }
            })
        }
        if ["both_funded", "first_redeemed", "secret_observed"].contains(execution.state) {
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_settlement_actions"), style: .default) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showSwapSettlementActions(execution, wallet: wallet)
            })
        }
        walletPresentationHost.present(alert, animated: true)
    }

    private func showSwapSettlementActions(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showSwapSettlementActionsAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showSwapSettlementActionsAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_settlement_actions"),
            message: nil,
            preferredStyle: .actionSheet
        )
        for (title, action, bitcoin) in [
            (WalletCopy.text("wallet_swap_redeem_hns"), NativeSwapSettlementAction.redeem, false),
            (WalletCopy.text("wallet_swap_redeem_bitcoin"), .redeem, true),
            (WalletCopy.text("wallet_swap_refund_hns"), .refund, false),
            (WalletCopy.text("wallet_swap_refund_bitcoin"), .refund, true),
        ] {
            alert.addAction(UIAlertAction(title: title, style: action == .refund ? .destructive : .default) {
                [weak self, weak wallet] _ in
                guard let self, let wallet, self.wallet === wallet else { return }
                self.showSwapSettlementFee(
                    execution, action: action, bitcoin: bitcoin, wallet: wallet
                )
            })
        }
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_cancel"),
            style: .cancel
        ))
        let host = walletPresentationHost
        if let popover = alert.popoverPresentationController {
            host.loadViewIfNeeded()
            guard let sourceView = host.view else { return }
            popover.sourceView = sourceView
            popover.sourceRect = CGRect(
                x: sourceView.bounds.midX,
                y: sourceView.bounds.midY,
                width: 1,
                height: 1
            )
            popover.permittedArrowDirections = []
        }
        host.present(alert, animated: true)
    }

    private func showSwapSettlementFee(
        _ execution: NativeShakescapeExecutionSummary,
        action: NativeSwapSettlementAction,
        bitcoin: Bool,
        wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_settlement_fee_title"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = WalletCopy.text(
                bitcoin
                    ? "wallet_swap_settlement_btc_fee_hint"
                    : "wallet_swap_settlement_hns_fee_hint"
            )
            if !bitcoin {
                field.text = defaultHnsMaximumFeeBaseUnits
            }
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_cancel"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_review_transaction"),
            style: .default
        ) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage(WalletCopy.text(
                    "wallet_swap_settlement_fee_invalid"
                ))
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
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_settlement_prepare_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
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
        let message = WalletCopy.format(
            "wallet_swap_settlement_approval_message",
            Int(approval.inputAmount),
            unit,
            Int(approval.outputAmount),
            Int(approval.fee),
            Int(approval.maximumFee),
            approval.transactionId,
            approval.sessionId
        )
        let alert = UIAlertController(
            title: WalletCopy.format(
                "wallet_swap_settlement_approval_title",
                approval.action.rawValue
            ),
            message: message,
            preferredStyle: .alert
        )
        swapSettlementApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingSwapSettlementApproval else { return }
            let isBitcoin = self.pendingSwapSettlementIsBitcoin
            self.pendingSwapSettlementApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectSwapSettlement(pending.actionToken, bitcoin: isBitcoin)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_broadcast_settlement"), style: .destructive) {
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
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_swap_settlement_submitted",
                            receipt.action.rawValue,
                            String(receipt.transactionId.prefix(12))
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_settlement_broadcast_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func showHnsForBtcFundingFee(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showHnsForBtcFundingFeeAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showHnsForBtcFundingFeeAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_fund_hns"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = WalletCopy.text("wallet_swap_hns_funding_fee_hint")
            field.text = defaultHnsMaximumFeeBaseUnits
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_cancel"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_review_transaction"),
            style: .default
        ) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage(WalletCopy.text(
                    "wallet_swap_hns_funding_fee_invalid"
                ))
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
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_hns_funding_prepare_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
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
        let message = WalletCopy.format(
            "wallet_swap_hns_funding_approval_message",
            amount,
            fee,
            maximumFee,
            approval.transactionId,
            approval.sessionId
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_hns_funding_approval_title"),
            message: message,
            preferredStyle: .alert
        )
        hnsForBtcFundingApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingHnsForBtcFundingApproval else { return }
            self.pendingHnsForBtcFundingApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectHnsForBtcFunding(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_broadcast_hns_funding"), style: .destructive) {
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
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_swap_hns_funding_submitted",
                            String(receipt.transactionId.prefix(12))
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_hns_funding_broadcast_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func showBtcForHnsFundingFee(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self, weak wallet] in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.showBtcForHnsFundingFeeAfterAuthentication(execution, wallet: wallet)
        }
    }

    private func showBtcForHnsFundingFeeAfterAuthentication(
        _ execution: NativeShakescapeExecutionSummary, wallet: RustNativeWallet
    ) {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_fund_bitcoin"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = WalletCopy.text("wallet_swap_funding_fee_hint")
            field.keyboardType = .numberPad
        }
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_cancel"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_action_review_transaction"),
            style: .default
        ) {
            [weak self, weak alert, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let fee = UInt64(alert?.textFields?.first?.text ?? ""), fee > 0 else {
                self?.showErrorMessage(WalletCopy.text(
                    "wallet_swap_bitcoin_funding_fee_invalid"
                ))
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
                        self.bitcoinStatusLabel.text = WalletCopy.text(
                            "wallet_swap_funding_prepare_failed"
                        )
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func presentBtcForHnsFundingApproval(
        _ approval: NativeBitcoinHtlcFundingApproval, wallet: RustNativeWallet
    ) {
        pendingBtcForHnsFundingApproval?.actionToken.discard()
        pendingBtcForHnsFundingApproval = approval
        let message = WalletCopy.format(
            "wallet_swap_funding_approval_message",
            Int(approval.amountSats),
            Int(approval.feeSats),
            Int(approval.maximumFeeSats),
            approval.txid,
            approval.sessionId
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_funding_approval_title"),
            message: message,
            preferredStyle: .alert
        )
        btcForHnsFundingApprovalAlert = alert
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet,
                  let pending = self.pendingBtcForHnsFundingApproval else { return }
            self.pendingBtcForHnsFundingApproval = nil
            DispatchQueue.global(qos: .userInitiated).async {
                try? wallet.rejectBtcForHnsFunding(pending.actionToken)
            }
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_broadcast_funding"), style: .destructive) {
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
                        self.bitcoinStatusLabel.text = WalletCopy.format(
                            "wallet_swap_funding_submitted",
                            String(receipt.txid.prefix(12))
                        )
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_funding_broadcast_failed")
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func confirmCancelBtcForHnsOffer(
        _ offer: NativeBtcForHnsOfferSummary,
        wallet: RustNativeWallet
    ) {
        let hns = WalletReadPresenter.formatHnsBaseUnits(String(offer.hnsAmountDollarydoos))
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_swap_cancel_title"),
            message: WalletCopy.format(
                "wallet_swap_cancel_message",
                Int(offer.btcAmountSats),
                hns,
                offer.offerId
            ),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("wallet_swap_keep_offer"),
            style: .cancel
        ))
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_swap_cancel_offer"), style: .destructive) {
            [weak self, weak wallet] _ in
            guard let self, let wallet, self.wallet === wallet else { return }
            self.isOperating = true
            self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_cancelling")
            self.refreshButtonStates()
            DispatchQueue.global(qos: .userInitiated).async { [wallet] in
                let outcome = Result { try wallet.cancelBtcForHnsOffer(offerId: offer.offerId) }
                DispatchQueue.main.async { [weak self] in
                    guard let self, self.wallet === wallet else { return }
                    self.isOperating = false
                    switch outcome {
                    case .success:
                        self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_cancelled")
                    case .failure(let error):
                        self.bitcoinStatusLabel.text = WalletCopy.text("wallet_swap_cancel_failed")
                        self.showError(error)
                    }
                    self.refreshButtonStates()
                }
            }
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func renderBitcoinSnapshot(_ snapshot: NativeBitcoinWalletSnapshot) {
        bitcoinSnapshot = snapshot
        bitcoinActivityPageOffset = 0
        let birthday: String
        switch snapshot.birthdayState {
        case "awaitingCreationTip":
            birthday = WalletCopy.text("wallet_bitcoin_birthday_creation_pending")
        case "recoveryUnknown":
            birthday = WalletCopy.text("wallet_bitcoin_birthday_recovery_unknown")
        case "recoveryPendingValidation":
            birthday = WalletCopy.format(
                "wallet_bitcoin_birthday_recovery_pending",
                Int(snapshot.birthdayHeight)
            )
        default:
            birthday = WalletCopy.format(
                "wallet_bitcoin_birthday_validated",
                Int(snapshot.birthdayHeight)
            )
        }
        bitcoinBalanceLabel.text = WalletCopy.format(
            "wallet_bitcoin_balance",
            Int(snapshot.confirmedSats),
            Int(snapshot.trustedPendingSats),
            Int(snapshot.untrustedPendingSats),
            Int(snapshot.immatureSats),
            Int(snapshot.totalSats),
            birthday,
            Int(snapshot.synchronizedHeight)
        )
        bitcoinBirthdayButton.isHidden = !["recoveryUnknown", "recoveryPendingValidation"]
            .contains(snapshot.birthdayState)
        bitcoinReceiveLabel.text = WalletCopy.format(
            "wallet_bitcoin_receive",
            snapshot.receiveAddress
        )
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
            title: WalletCopy.text("row_wallet_send"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: WalletCopy.text("wallet_send_recipient_label"),
                placeholder: WalletCopy.text("wallet_send_recipient_hint"),
                accessibilityLabel: WalletCopy.text("wallet_send_recipient_label")
            )
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.isSecureTextEntry = false
            field.accessibilityIdentifier = "wallet.send.recipient"
            field.text = prefill?.address
            let scanButton = UIButton(type: .system)
            scanButton.frame = CGRect(x: 0, y: 0, width: 36, height: 36)
            scanButton.setImage(UIImage(systemName: "qrcode.viewfinder"), for: .normal)
            scanButton.accessibilityLabel = WalletCopy.text("wallet_scan_payment_qr")
            scanButton.addAction(UIAction { [weak self, weak alert] _ in
                guard let self, let alert else { return }
                self.clearHnsSendForm(alert)
                alert.dismiss(animated: true) { [weak self] in
                    self?.scanHandshakePaymentQr()
                }
            }, for: .touchUpInside)
            field.rightView = scanButton
            field.rightViewMode = .always
        }
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: WalletCopy.text("wallet_send_amount_label"),
                placeholder: WalletCopy.text("wallet_send_amount_hint"),
                accessibilityLabel: WalletCopy.text("wallet_send_amount_label")
            )
            field.keyboardType = .decimalPad
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.send.amount"
            field.text = prefill?.amountHns
        }
        alert.addTextField { field in
            Self.configureSendField(
                field,
                visibleLabel: WalletCopy.text("wallet_send_maximum_fee_label"),
                placeholder: WalletCopy.text("wallet_send_maximum_fee_hint"),
                accessibilityLabel: WalletCopy.text("wallet_send_maximum_fee_label")
            )
            field.text = defaultHnsMaximumFee
            field.keyboardType = .decimalPad
            field.textContentType = nil
            field.accessibilityIdentifier = "wallet.send.maximum-fee"
        }
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_cancel"), style: .cancel) { [weak self] _ in
            self?.clearHnsSendForm(alert)
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_prepare_wallet_send"), style: .default) { [weak self, weak alert] _ in
            guard let self, let alert else { return }
            let request = self.takeHnsSendRequest(from: alert)
            self.clearHnsSendForm(alert)
            guard let request else {
                self.showErrorMessage(WalletCopy.text("wallet_send_invalid"))
                return
            }
            self.beginHnsSendReview(request)
        })
        hnsSendFormAlert = alert
        walletPresentationHost.present(alert, animated: true)
    }

    @objc private func scanHandshakePaymentQr() {
        guard walletCanPresentChild else { return }
        let scanner = HandshakeQrScannerViewController()
        scanner.onResult = { [weak self, weak scanner] value in
            guard let self else { return }
            scanner?.dismiss(animated: true) {
                guard let request = HandshakePaymentURI.parse(value) else {
                    self.showErrorMessage(WalletCopy.text("wallet_qr_invalid"))
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
        walletPresentationHost.present(scanner, animated: true)
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
                self.synchronizeWalletReadsFromUserAction()
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
        label.font = AppAccessibility.scaledSystemFont(
            size: 12,
            weight: .semibold,
            textStyle: .caption1
        )
        label.adjustsFontForContentSizeCategory = true
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
            reason: WalletCopy.text("wallet_auth_transaction_message")
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
            showErrorMessage(WalletCopy.text("wallet_send_locked"))
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = WalletCopy.text("wallet_send_syncing_before_review")
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
                    self.readStatusLabel.text = WalletCopy.text("wallet_send_prepare_failed")
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
        let message = WalletCopy.format(
            "wallet_send_approval_message",
            approval.recipient,
            WalletReadPresenter.formatHnsBaseUnits(approval.amountBaseUnits),
            WalletReadPresenter.formatHnsBaseUnits(approval.maximumFeeBaseUnits),
            WalletCopy.text("wallet_send_finality_pow"),
            WalletCopy.text("wallet_send_warning_fee_change"),
            expiry
        )
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_send_approval_title"),
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) { [weak self] _ in
            self?.rejectHnsSendApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_broadcast_hns"), style: .destructive) { [weak self] _ in
            self?.approveHnsSendApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        hnsSendApprovalAlert = alert
        walletPresentationHost.present(alert, animated: true)
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
        readStatusLabel.text = WalletCopy.text("wallet_send_broadcasting")
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
                        self.readStatusLabel.text = WalletCopy.format(
                            "wallet_send_admission_verified",
                            result.receipt.txid,
                            admissionStatus
                        )
                    } else if result.snapshot == nil {
                        self.pendingOutgoingSnapshotHeight = pendingRefreshFloor ?? 0
                        self.readStatusLabel.text = WalletCopy.text("wallet_send_submitted_sync_failed")
                        self.maybeRefreshPendingOutgoingAfterNewBlock()
                    } else {
                        self.readStatusLabel.text = WalletCopy.text("wallet_send_admission_unverified")
                    }
                case .failure(let error):
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_send_broadcast_ambiguous"
                    )
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
                    self.readStatusLabel.text = WalletCopy.text("wallet_send_rejected")
                case .failure(let error):
                    self.readStatusLabel.text = WalletCopy.text("wallet_send_reject_failed")
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
            showErrorMessage(WalletCopy.text("wallet_reads_names_unavailable"))
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
        guard walletCanPresentChild else { return }
        var actions: [WalletMenuAction] = []
        if importNameButton.isEnabled {
            actions.append(WalletMenuAction(title: WalletCopy.text("row_wallet_name_import")) { [weak self] in
                self?.requestExactHnsNameImport()
            })
            actions.append(WalletMenuAction(title: WalletCopy.text("action_import_multiple_wallet_names")) { [weak self] in
                self?.requestMultipleHnsNameImport()
            })
        }
        if hnsValueActionMayStart {
            actions.append(WalletMenuAction(title: WalletCopy.text("wallet_dashboard_name_actions")) { [weak self] in
                self?.showNameActionMenu()
            })
        }
        presentWalletMenu(
            title: WalletCopy.text("wallet_name_options"),
            rows: [WalletMenuRow(
                title: WalletCopy.text("row_wallet_read_names"),
                detail: nameImportStatusLabel.text
                    ?? WalletCopy.text("wallet_name_import_unavailable")
            )],
            actions: actions,
            retainForChildActions: true
        )
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
        guard hnsValueActionMayStart, walletCanPresentChild else { return }
        presentWalletMenu(
            title: WalletCopy.text("wallet_dashboard_name_actions"),
            rows: [WalletMenuRow(
                title: WalletCopy.text("wallet_name_action_status_heading"),
                detail: WalletCopy.text("wallet_name_actions_description")
            )],
            actions: [
                WalletMenuAction(title: WalletCopy.text("row_wallet_transfer_name")) { [weak self] in self?.showTransferNameForm() },
                WalletMenuAction(title: WalletCopy.text("row_wallet_finalize_name")) { [weak self] in self?.showFinalizeNameForm() },
                WalletMenuAction(title: WalletCopy.text("row_wallet_set_records")) { [weak self] in self?.showSetNameRecordsForm() },
            ],
            retainForChildActions: true
        )
    }

    private func showShakedexDashboard() {
        guard walletCanPresentChild else { return }
        let shakescapeStatus = shakedexActionMayStart ? directShakescapeStatusSnapshot : nil
        let transportLine: String
        if let shakescapeStatus {
            let listener = shakescapeStatus.listenerPort.map { "listening on \($0)" }
                ?? "listener unavailable"
            let peer = shakescapeStatus.peerEndpoint.map {
                WalletCopy.format("wallet_direct_shakescape_peer_connected",
                    $0
                )
            } ?? WalletCopy.text("wallet_direct_shakescape_peer_none")
            let reachability: String
            if shakescapeStatus.advertised {
                let route = shakescapeStatus.publicIpv6
                    ? "public IPv6"
                    : (shakescapeStatus.routerMapped ? "router TCP mapping" : "public TCP")
                reachability = "public endpoint candidate from \(route); advertised through stock HSD"
            } else if shakescapeStatus.publiclyReachable && shakescapeStatus.networkServiceReady {
                reachability = "public endpoint candidate and verified NETWORK service ready; reconnecting stock HSD peers for ADDR publication"
            } else if shakescapeStatus.publiclyReachable {
                reachability = "public endpoint candidate found; waiting for fresh verified NETWORK service"
            } else if shakescapeStatus.publicIpv6 {
                reachability = "global IPv6 candidate found; external inbound TCP verification is required before ADDR publication"
            } else if shakescapeStatus.networkServiceReady {
                reachability = "NETWORK service ready; checking public IPv6 candidates and trying PCP/NAT-PMP/UPnP"
            } else {
                reachability = "waiting for fresh verified headers before NETWORK advertisement"
            }
            let connectionHelp = WalletCopy.text("wallet_shakedex_connection_help")
            transportLine = """
            \(peer)

            \(connectionHelp)

            Advanced: \(listener); \(reachability); \(shakescapeStatus.peerCount) board peers and \(shakescapeStatus.candidateCount) discovery candidates.
            """
        } else {
            transportLine = "Unlock and sync to use swaps."
        }
        let summary = shakedexActionMayStart
            ? "Swaps stay in this wallet. Websites cannot start them."
            : "Unlock and sync before using offers."
        let paired = shakescapeStatus?.peerEndpoint != nil
        var actions: [WalletMenuAction] = [
            WalletMenuAction(title: WalletCopy.text("row_wallet_pair_direct_shakescape"), dismissBeforeAction: true) { [weak self] in
                self?.showPairDirectShakescapeForm()
            },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_sell_btc"), section: WalletCopy.text("wallet_swap_coin_actions"), enabled: paired) { [weak self] in
                self?.showBtcForHnsOfferForm()
            },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_sell_hns"), section: WalletCopy.text("wallet_swap_coin_actions"), enabled: paired) { [weak self] in
                self?.showHnsForBtcOfferForm()
            },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_available_offers"), section: WalletCopy.text("wallet_swap_coin_actions"), enabled: paired) { [weak self] in
                self?.showAvailableDirectOffers()
            },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_my_offers"), section: WalletCopy.text("wallet_swap_coin_actions"), enabled: paired) { [weak self] in
                self?.showMyDirectOffers()
            },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_executions"), section: WalletCopy.text("wallet_swap_coin_actions"), enabled: paired) { [weak self] in
                self?.showShakescapeExecutions()
            },
            WalletMenuAction(title: WalletCopy.text("row_wallet_create_offer"), section: WalletCopy.text("wallet_swap_name_actions"), enabled: paired) { [weak self] in self?.showCreateOfferForm() },
            WalletMenuAction(title: WalletCopy.text("wallet_swap_cancel_offer"), section: WalletCopy.text("wallet_swap_name_actions"), enabled: paired) { [weak self] in self?.showCancelOfferForm() },
            WalletMenuAction(title: WalletCopy.text("row_wallet_recover_name"), section: WalletCopy.text("wallet_swap_name_actions"), enabled: paired) { [weak self] in self?.showRecoverNameForm() },
            WalletMenuAction(title: WalletCopy.text("row_wallet_list_offers"), section: WalletCopy.text("wallet_swap_name_actions"), enabled: paired) { [weak self] in self?.showListOffersForm() },
            WalletMenuAction(title: WalletCopy.text("row_wallet_get_session"), section: WalletCopy.text("wallet_swap_name_actions"), enabled: paired) { [weak self] in self?.showGetSessionForm() },
        ]
        if shakedexActionMayStart {
            // No snapshot can mean the native non-blocking controller read was
            // busy. Only an affirmative unlocked status with no bound port is
            // evidence that listener recovery should be offered.
            if let shakescapeStatus,
               shakescapeStatus.unlocked,
               shakescapeStatus.listenerPort == nil {
                actions.insert(WalletMenuAction(title: WalletCopy.text("row_wallet_retry_direct_shakescape_host"), enabled: paired) { [weak self] in
                    self?.retryDirectShakescapeListener()
                }, at: 1)
            }
            if shakescapeStatus?.peerEndpoint != nil {
                actions.insert(WalletMenuAction(title: WalletCopy.text("row_wallet_disconnect_direct_shakescape"), style: .destructive) { [weak self] in
                    self?.disconnectDirectShakescapePeer()
                }, at: 1)
            }
        }
        presentWalletMenu(
            title: WalletCopy.text("wallet_dashboard_shakedex"),
            rows: [
                WalletMenuRow(
                    title: WalletCopy.text("row_wallet_direct_shakescape_host"),
                    detail: summary
                ),
                WalletMenuRow(
                    title: WalletCopy.text("row_wallet_pair_direct_shakescape"),
                    detail: WalletCopy.text("row_wallet_pair_direct_shakescape_summary")
                ),
                WalletMenuRow(
                    title: WalletCopy.text("row_wallet_direct_shakescape_host"),
                    detail: transportLine
                ),
            ],
            actions: actions,
            retainForChildActions: true
        )
    }

    private func presentWalletMenu(
        title: String,
        rows: [WalletMenuRow],
        actions: [WalletMenuAction],
        retainForChildActions: Bool = false,
        liveActions: (@MainActor () -> [WalletMenuAction])? = nil
    ) {
        let host = walletPresentationHost
        guard host === self || host is WalletMenuViewController else { return }
        let menu = WalletMenuViewController(
            title: title,
            rows: rows,
            actions: actions,
            dismissBeforeAction: !retainForChildActions,
            liveActions: liveActions
        )
        menu.modalPresentationStyle = .pageSheet
        if let sheet = menu.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
            sheet.preferredCornerRadius = 24
        }
        host.present(menu, animated: true)
    }

    private var walletPresentationHost: UIViewController {
        var host: UIViewController = self
        while let presented = host.presentedViewController, !presented.isBeingDismissed {
            host = presented
        }
        return host
    }

    private var walletCanPresentChild: Bool {
        let host = walletPresentationHost
        return host === self || host is WalletMenuViewController
    }

    @discardableResult
    private func presentWalletForm(
        title: String,
        message: String? = nil,
        fields: [WalletSheetFormField],
        selectionSections: [WalletSheetSelectionSection] = [],
        primaryTitle: String,
        primaryStyle: WalletMenuActionStyle = .standard,
        onSubmit: @escaping @MainActor ([String]) -> Void
    ) -> WalletFormViewController? {
        guard walletCanPresentChild else { return nil }
        let host = walletPresentationHost
        let form = WalletFormViewController(
            title: title,
            message: message,
            fields: fields,
            selectionSections: selectionSections,
            primaryTitle: primaryTitle,
            primaryStyle: primaryStyle,
            onSubmit: onSubmit
        )
        form.modalPresentationStyle = .pageSheet
        if let sheet = form.sheetPresentationController {
            sheet.detents = fields.count > 2 ? [.large()] : [.medium(), .large()]
            sheet.prefersGrabberVisible = true
            sheet.preferredCornerRadius = 24
        }
        host.present(form, animated: true)
        return form
    }

    /// Fields remain one-at-a-time so each step fits at large Dynamic Type
    /// sizes. The wallet-styled sheet clears its text before advancing or
    /// cancelling, before any direct wallet operation begins.
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
        guard walletCanPresentChild else { return }
        let fieldDefinition = fields[index]
        presentWalletForm(
            title: title,
            message: nil,
            fields: [WalletSheetFormField(
                label: fieldDefinition.label,
                placeholder: fieldDefinition.placeholder,
                keyboardType: fieldDefinition.numeric ? .decimalPad : .asciiCapable,
                initialValue: fieldDefinition.initialValue,
                accessibilityIdentifier: "wallet.hns-value.\(index)",
                configure: { field in
                    field.isEnabled = fieldDefinition.editable
                    if !fieldDefinition.editable {
                        field.textColor = .secondaryLabel
                        field.clearButtonMode = .never
                    }
                }
            )],
            primaryTitle: index + 1 == fields.count
                ? WalletCopy.text("wallet_action_review_transaction")
                : WalletCopy.text("action_next_wallet_activity")
        ) { [weak self] submitted in
            guard let self, let text = submitted.first else { return }
            self.collectHnsValueForm(
                title: title,
                fields: fields,
                index: index + 1,
                values: values + [text],
                completion: completion
            )
        }
    }

    private func showTransferNameForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_transfer_name"),
            fields: [
                .init(label: WalletCopy.text("wallet_action_name_hint"), placeholder: WalletCopy.text("wallet_name_search_hint")),
                .init(label: WalletCopy.text("wallet_action_recipient_hint"), placeholder: WalletCopy.text("wallet_send_recipient_label")),
                .init(
                    label: WalletCopy.text("wallet_action_maximum_fee_hint"),
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 3,
                  let fee = Self.positiveHnsBaseUnits(values[2]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
                return
            }
            self.beginHnsValueAction(
                .transferName(name: values[0], recipient: values[1], maximumFeeBaseUnits: fee)
            )
        }
    }

    private func showFinalizeNameForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_finalize_name"),
            fields: [
                .init(label: WalletCopy.text("wallet_action_name_hint"), placeholder: WalletCopy.text("wallet_name_search_hint")),
                .init(label: WalletCopy.text("wallet_action_expected_recipient_hint"), placeholder: WalletCopy.text("wallet_send_recipient_label")),
                .init(
                    label: WalletCopy.text("wallet_action_maximum_fee_hint"),
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 3,
                  let fee = Self.positiveHnsBaseUnits(values[2]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
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
        guard walletCanPresentChild else { return }
        let editor = NameRecordsEditorViewController { [weak self] name, records, feeText in
            guard let self,
                  let fee = Self.positiveHnsBaseUnits(feeText) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
                return
            }
            self.beginHnsValueAction(
                .setNameRecords(name: name, records: records, maximumFeeBaseUnits: fee)
            )
        }
        let navigation = UINavigationController(rootViewController: editor)
        navigation.modalPresentationStyle = .formSheet
        walletPresentationHost.present(navigation, animated: true)
    }

    private func showCreateOfferForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_create_offer"),
            fields: [
                .init(label: WalletCopy.text("wallet_action_name_hint"), placeholder: WalletCopy.text("wallet_name_search_hint")),
                .init(label: WalletCopy.text("wallet_action_price_hint"), placeholder: "1", numeric: true),
                .init(
                    label: WalletCopy.text("wallet_action_maximum_fee_hint"),
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
                .init(label: WalletCopy.text("wallet_action_lifetime_hint"), placeholder: "86400", numeric: true, initialValue: "86400"),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 4,
                  let price = Self.positiveHnsBaseUnits(values[1]),
                  let fee = Self.positiveHnsBaseUnits(values[2]),
                  let lifetime = UInt64(values[3]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
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
            title: WalletCopy.text("wallet_swap_cancel_offer"),
            fields: [.init(label: WalletCopy.text("wallet_action_seller_session_hint"), placeholder: WalletCopy.text("wallet_action_seller_session_hint"))]
        ) { [weak self] values in
            guard let self, let sellerSessionID = values.first else { return }
            self.beginHnsValueAction(.cancelOffer(sellerSessionID: sellerSessionID))
        }
    }

    private func showRecoverNameForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_recover_name"),
            fields: [
                .init(label: WalletCopy.text("wallet_action_seller_session_hint"), placeholder: WalletCopy.text("wallet_action_seller_session_hint")),
                .init(
                    label: WalletCopy.text("wallet_action_maximum_fee_hint"),
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let fee = Self.positiveHnsBaseUnits(values[1]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
                return
            }
            self.beginHnsValueAction(
                .recoverName(sellerSessionID: values[0], maximumFeeBaseUnits: fee)
            )
        }
    }

    private func showAcceptOfferForm(selectedOffer: NativeShakedexNameOffer) {
        collectHnsValueForm(
            title: WalletCopy.text("wallet_swap_acceptance_offer"),
            fields: [
                .init(
                    label: WalletCopy.text("wallet_action_listing_hint"),
                    placeholder: WalletCopy.text("wallet_action_listing_hint"),
                    initialValue: selectedOffer.listingID,
                    editable: false
                ),
                .init(
                    label: WalletCopy.text("wallet_action_maximum_fee_hint"),
                    placeholder: defaultHnsMaximumFee,
                    numeric: true,
                    initialValue: defaultHnsMaximumFee
                ),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let fee = Self.positiveHnsBaseUnits(values[1]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_value_actions_invalid"))
                return
            }
            self.beginHnsValueAction(.acceptOffer(
                listingID: values[0],
                maximumFeeBaseUnits: fee,
                automaticFinalizeMaximumFeeBaseUnits: fee
            ))
        }
    }

    private func showListOffersForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_list_offers"),
            fields: [
                .init(label: WalletCopy.text("wallet_action_cursor_hint"), placeholder: WalletCopy.text("wallet_action_cursor_hint")),
                .init(label: WalletCopy.text("wallet_action_limit_hint"), placeholder: "32", numeric: true, initialValue: "32"),
            ]
        ) { [weak self] values in
            guard let self,
                  values.count == 2,
                  let limit = UInt8(values[1]) else {
                self?.showErrorMessage(WalletCopy.text("wallet_shakedex_queries_invalid"))
                return
            }
            self.beginShakedexQuery(.listOffers(cursor: values[0].isEmpty ? nil : values[0], limit: limit))
        }
    }

    private func showGetSessionForm() {
        collectHnsValueForm(
            title: WalletCopy.text("row_wallet_get_session"),
            fields: [.init(label: WalletCopy.text("wallet_action_session_hint"), placeholder: WalletCopy.text("wallet_action_session_hint"))]
        ) { [weak self] values in
            guard let self, let sessionID = values.first else { return }
            self.beginShakedexQuery(.getSession(sessionID: sessionID))
        }
    }

    private func showPairDirectShakescapeForm() {
        let recent = Array(recentDirectShakescapePeers.prefix(maximumVisibleDirectShakescapePeers))
        let discovered = Array(
            (directShakescapeStatusSnapshot?.discoveredPeers ?? [])
                .filter { !recent.contains($0) }
                .prefix(maximumVisibleDirectShakescapePeers)
        )
        presentWalletForm(
            title: WalletCopy.text("row_wallet_pair_direct_shakescape"),
            message: WalletCopy.text("row_wallet_pair_direct_shakescape_summary"),
            fields: [.init(
                label: WalletCopy.text("wallet_direct_shakescape_endpoint_hint"),
                placeholder: WalletCopy.text("wallet_direct_shakescape_endpoint_hint"),
                accessibilityIdentifier: "wallet.shakescape.endpoint"
            )],
            selectionSections: [
                .init(
                    title: WalletCopy.text("wallet_direct_shakescape_recent_peers"),
                    options: recent,
                    emptyMessage: WalletCopy.text("wallet_direct_shakescape_no_recent_peers")
                ),
                .init(
                    title: WalletCopy.text("wallet_direct_shakescape_discovered_peers"),
                    options: discovered,
                    emptyMessage: WalletCopy.text("wallet_direct_shakescape_no_discovered_peers")
                ),
            ],
            primaryTitle: WalletCopy.text("wallet_direct_shakescape_connect_and_load")
        ) { [weak self] values in
            guard let self, let endpoint = values.first else { return }
            self.connectDirectShakescapePeer(endpoint)
        }
    }

    private func connectDirectShakescapePeer(_ endpoint: String) {
        runDirectShakescapeOperation(status: WalletCopy.text("wallet_direct_shakescape_connecting")) {
            try $0.connectDirectShakescape(endpoint: endpoint)
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let connection):
                if let endpoint = connection.peerEndpoint {
                    self.rememberDirectShakescapePeer(endpoint)
                }
                switch connection.outcome {
                case .connected:
                    self.readStatusLabel.text = WalletCopy.format(
                        "wallet_direct_shakescape_connected",
                        connection.peerEndpoint ?? "unknown endpoint"
                    )
                case .replaced:
                    self.readStatusLabel.text = WalletCopy.format(
                        "wallet_direct_shakescape_replaced",
                        connection.peerEndpoint ?? "unknown endpoint"
                    )
                case .unavailable:
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_direct_shakescape_connect_unavailable"
                    )
                case .locked:
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_direct_shakescape_connect_locked"
                    )
                case .connectionFailed:
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_direct_shakescape_connect_failed"
                    )
                case .exchangeFailed:
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_direct_shakescape_exchange_failed"
                    )
                }
                if directShakescapeConnectionShouldOpenOffers(connection) {
                    self.showAvailableDirectOffers()
                } else {
                    self.showShakedexDashboard()
                }
            case .failure(let error):
                self.readStatusLabel.text = WalletCopy.text(
                    "wallet_direct_shakescape_connect_bridge_failed"
                )
                self.showError(error)
            }
        }
    }

    private func retryDirectShakescapeListener() {
        runDirectShakescapeOperation(status: WalletCopy.text("wallet_direct_shakescape_host_retrying")) {
            try $0.retryDirectShakescapeListener()
            return true
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                self.readStatusLabel.text = WalletCopy.text(
                    "wallet_direct_shakescape_host_retry_ready"
                )
            case .failure(let error):
                self.readStatusLabel.text = WalletCopy.text(
                    "wallet_direct_shakescape_host_retry_failed"
                )
                self.showError(error)
            }
        }
    }

    private func disconnectDirectShakescapePeer() {
        runDirectShakescapeOperation(status: WalletCopy.text("wallet_status_disconnecting_direct_shakescape")) {
            try $0.disconnectDirectShakescape()
        } completion: { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let disconnected):
                self.readStatusLabel.text = disconnected
                    ? WalletCopy.text("wallet_direct_shakescape_disconnected")
                    : WalletCopy.text("wallet_direct_shakescape_no_peer")
            case .failure(let error):
                self.readStatusLabel.text = WalletCopy.text(
                    "wallet_direct_shakescape_connect_bridge_failed"
                )
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
            showErrorMessage(WalletCopy.text("wallet_direct_shakescape_locked"))
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
            directShakescapeServiceTicks = 0
            directShakescapeExecutionTicks = 0
            directShakescapeStatusSnapshot = nil
            shakescapeExecutionStatusSnapshot = nil
            lastAutomaticSwapHnsSyncAtUptime = nil
            lastAutomaticSwapHnsSyncFingerprint = nil
            lastAutomaticSwapBitcoinSyncAtUptime = nil
            lastAutomaticSwapBitcoinSyncFingerprint = nil
            automaticSwapBitcoinSyncPausedUntilUptime = nil
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
        let pollExecutions = directShakescapeExecutionTicks == 0
        DispatchQueue.global(qos: .utility).async {
            var transportWorkServiced = false
            for _ in 0..<maximumDirectShakescapeFramesPerTick {
                guard (try? wallet.serviceDirectShakescape()) == true else { break }
                transportWorkServiced = true
            }
            let status = try? wallet.directShakescapeStatus()
            let executions = pollExecutions || transportWorkServiced
                ? try? wallet.shakescapeExecutions()
                : nil
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.directShakescapeServiceInFlight = false
                guard self.walletAuthorityRequested,
                      self.walletAuthorityGeneration == authority,
                      self.wallet.map({ ObjectIdentifier($0) }) == identity else { return }
                let previousPeerEndpoint = self.directShakescapeStatusSnapshot?.peerEndpoint
                // A failed try-lock is scheduling contention, not a transport
                // transition. Retain the last validated snapshot until native
                // code affirmatively reports a new state or wallet authority
                // is locked/retired by updateDirectShakescapeServiceTimer().
                if let status {
                    self.directShakescapeStatusSnapshot = status
                    if let endpoint = status.peerEndpoint {
                        self.rememberDirectShakescapePeer(endpoint)
                    }
                }
                if let status, previousPeerEndpoint != status.peerEndpoint {
                    self.renderWalletDashboard()
                }
                if let executions {
                    let previous = self.shakescapeExecutionStatusSnapshot
                    self.shakescapeExecutionStatusSnapshot = executions
                    self.atomicSwapNotifications.reconcile(
                        executions,
                        stageText: self.shakescapeExecutionNotificationStage
                    )
                    // Re-render even when the durable journal is unchanged so
                    // the local funding countdown advances while the app is
                    // open. Notification fingerprints use semantic buckets,
                    // so this does not create minute-by-minute alerts.
                    self.publishShakescapeExecutionStatus(executions)
                    if previous != executions {
                        self.renderWalletDashboard()
                    }
                }
                self.directShakescapeServiceTicks =
                    (self.directShakescapeServiceTicks + 1) % directShakescapeNetworkMaintenanceTicks
                self.directShakescapeExecutionTicks =
                    (self.directShakescapeExecutionTicks + 1) % directShakescapeExecutionPollTicks
                if self.directShakescapeServiceTicks == 0,
                   status?.publiclyReachable == true,
                   status?.networkServiceReady == false {
                    self.synchronizeWalletReads(
                        resumeAutomaticSync: false,
                        reportFailure: false
                    )
                } else if let executions,
                          !self.maybeStartAutomaticSwapHnsSync(executions) {
                    _ = self.maybeStartAutomaticSwapBitcoinSync(executions)
                }
            }
        }
    }

    private func liveAtomicSwapFingerprint(
        _ status: NativeShakescapeExecutionStatus
    ) -> String? {
        let terminal: Set<String> = ["completed", "refunded", "failed"]
        let live = status.executions
            .filter { !terminal.contains($0.state) }
            .sorted { $0.sessionId < $1.sessionId }
        let pending = status.pendingAcceptances.sorted { $0.sessionId < $1.sessionId }
        guard !live.isEmpty || !pending.isEmpty else { return nil }
        let executions = live.map {
            "\($0.sessionId):\($0.revision):\($0.state)"
        }
        let acceptances = pending.map {
            "\($0.sessionId):\($0.createdAtUnix)"
        }
        return (executions + acceptances).joined(separator: ";")
    }

    /// Keep the HNS half of every live swap current while the protected wallet
    /// is foreground-owned. A newly loaded journal inherits a just-published
    /// snapshot; a later journal revision schedules an immediate bounded scan.
    @discardableResult
    private func maybeStartAutomaticSwapHnsSync(
        _ status: NativeShakescapeExecutionStatus
    ) -> Bool {
        guard let fingerprint = liveAtomicSwapFingerprint(status) else {
            lastAutomaticSwapHnsSyncAtUptime = nil
            lastAutomaticSwapHnsSyncFingerprint = nil
            return false
        }
        if fingerprint != lastAutomaticSwapHnsSyncFingerprint {
            lastAutomaticSwapHnsSyncAtUptime = walletAutomaticSwapHnsLastRun(
                previousFingerprint: lastAutomaticSwapHnsSyncFingerprint,
                currentSnapshotObservedAtUptime: latestReadSnapshot == nil
                    ? nil
                    : latestReadSnapshotObservedAtUptime
            )
            lastAutomaticSwapHnsSyncFingerprint = fingerprint
        }
        guard !isOperating,
              !bitcoinSyncInProgress,
              walletAuthorityRequested,
              storageLease != nil,
              wallet != nil,
              synchronizedReadsAvailable else { return false }
        let now = ProcessInfo.processInfo.systemUptime
        if let last = lastAutomaticSwapHnsSyncAtUptime,
           now - last < automaticSwapHnsSyncInterval {
            return false
        }
        lastAutomaticSwapHnsSyncAtUptime = now
        synchronizeWalletReads(resumeAutomaticSync: false, reportFailure: false)
        return isOperating
    }

    /// Keep the Bitcoin compact-filter/watch state current for live swaps.
    /// This is read-only maintenance; every funding, redemption, and refund
    /// remains behind its exact native approval flow.
    @discardableResult
    private func maybeStartAutomaticSwapBitcoinSync(
        _ status: NativeShakescapeExecutionStatus
    ) -> Bool {
        guard let fingerprint = liveAtomicSwapFingerprint(status) else {
            lastAutomaticSwapBitcoinSyncAtUptime = nil
            lastAutomaticSwapBitcoinSyncFingerprint = nil
            automaticSwapBitcoinSyncPausedUntilUptime = nil
            return false
        }
        if fingerprint != lastAutomaticSwapBitcoinSyncFingerprint {
            lastAutomaticSwapBitcoinSyncFingerprint = fingerprint
            lastAutomaticSwapBitcoinSyncAtUptime = nil
        }
        let now = ProcessInfo.processInfo.systemUptime
        if let pausedUntil = automaticSwapBitcoinSyncPausedUntilUptime,
           now < pausedUntil {
            return false
        }
        guard !isOperating,
              !bitcoinSyncInProgress,
              !bitcoinBirthdayResetInProgress,
              walletAuthorityRequested,
              wallet != nil,
              bitcoinValueAvailable else { return false }
        if let last = lastAutomaticSwapBitcoinSyncAtUptime,
           now - last < automaticSwapBitcoinSyncInterval {
            return false
        }
        lastAutomaticSwapBitcoinSyncAtUptime = now
        startBitcoinSynchronization()
        return bitcoinSyncInProgress
    }

    private func publishShakescapeExecutionStatus(_ status: NativeShakescapeExecutionStatus) {
        let terminal: Set<String> = ["completed", "refunded", "failed"]
        if let execution = status.executions
            .filter({ !terminal.contains($0.state) })
            .max(by: { $0.lastVerifiedAtUnix < $1.lastVerifiedAtUnix }) {
            bitcoinStatusLabel.text = WalletCopy.format(
                "wallet_swap_notification_stage",
                shakescapeExecutionStage(execution),
                String(execution.sessionId.prefix(12))
            )
        } else if let offer = status.pendingOfferResponses.max(by: {
            $0.createdAtUnix < $1.createdAtUnix
        }) {
            bitcoinStatusLabel.text = WalletCopy.format(
                "wallet_swap_notification_offer_accepted_detail",
                String(offer.sessionId.prefix(12))
            )
        } else if let pending = status.pendingAcceptances.max(by: {
            $0.createdAtUnix < $1.createdAtUnix
        }) {
            bitcoinStatusLabel.text = pendingAcceptanceStage(pending)
        } else if let latest = status.executions.max(by: {
            $0.lastVerifiedAtUnix < $1.lastVerifiedAtUnix
        }) {
            bitcoinStatusLabel.text = WalletCopy.format(
                "wallet_swap_notification_stage",
                shakescapeExecutionStage(latest),
                String(latest.sessionId.prefix(12))
            )
        }
    }

    private func shakescapeExecutionStage(
        _ execution: NativeShakescapeExecutionSummary,
        includeBitcoinSync: Bool = true
    ) -> String {
        let first = swapChainLabel(execution.firstChain)
        let second = swapChainLabel(execution.secondChain)
        let now = UInt64(Date().timeIntervalSince1970)
        let fundingWasSubmitted = ["broadcast", "seen", "confirmed"]
            .contains(execution.localFundingState ?? "")
        let fundingTarget: String?
        switch execution.state {
        case "terms_frozen", "refunds_prepared", "first_funding_pending":
            fundingTarget = first
        case "first_funded", "second_funding_pending":
            fundingTarget = second
        default:
            fundingTarget = nil
        }
        var stage: String
        switch execution.state {
        case "terms_frozen", "refunds_prepared":
            if now >= execution.fundingDeadlineUnix {
                stage = WalletCopy.text("wallet_swap_stage_unfunded_closed")
            } else if now > execution.firstFundingCutoffUnix {
                stage = WalletCopy.format("wallet_swap_stage_first_funding_cutoff", first)
            } else {
                stage = WalletCopy.text("wallet_swap_stage_terms_waiting")
            }
        case "first_funding_pending":
            if now >= execution.fundingDeadlineUnix {
                stage = fundingClosedStage(
                    execution,
                    first: first,
                    fundingWasSubmitted: fundingWasSubmitted,
                    now: now
                )
            } else if execution.localRole == "maker", fundingWasSubmitted {
                stage = WalletCopy.format("wallet_swap_stage_funding_submitted", first)
            } else if execution.localRole == "maker",
                      execution.localFundingState == "reorged" {
                stage = WalletCopy.format("wallet_swap_stage_funding_reorged", first)
            } else if now > execution.firstFundingCutoffUnix {
                stage = WalletCopy.format("wallet_swap_stage_first_funding_cutoff", first)
            } else if execution.localRole == "maker" {
                stage = WalletCopy.format("wallet_swap_stage_funding_ready_here", first)
            } else {
                stage = WalletCopy.format(
                    "wallet_swap_stage_waiting_counterparty_funding", first
                )
            }
        case "first_funded":
            if now >= execution.fundingDeadlineUnix {
                stage = fundingClosedStage(
                    execution,
                    first: first,
                    fundingWasSubmitted: fundingWasSubmitted,
                    now: now
                )
            } else if execution.localRole == "taker", fundingWasSubmitted {
                stage = WalletCopy.format("wallet_swap_stage_funding_submitted", second)
            } else if execution.localRole == "taker",
                      execution.localFundingState == "reorged" {
                stage = WalletCopy.format("wallet_swap_stage_funding_reorged", second)
            } else if execution.localRole == "taker" {
                stage = WalletCopy.format("wallet_swap_stage_funding_ready_here", second)
            } else {
                stage = WalletCopy.format(
                    "wallet_swap_stage_waiting_counterparty_funding", second
                )
            }
        case "second_funding_pending":
            if execution.localRole == "taker", now >= execution.fundingDeadlineUnix {
                stage = WalletCopy.format(
                    "wallet_swap_stage_funding_recovery_pending", second
                )
            } else if now >= execution.fundingDeadlineUnix {
                stage = fundingClosedStage(
                    execution,
                    first: first,
                    fundingWasSubmitted: fundingWasSubmitted,
                    now: now
                )
            } else if execution.localRole == "taker", fundingWasSubmitted {
                stage = WalletCopy.format("wallet_swap_stage_funding_submitted", second)
            } else if execution.localRole == "taker",
                      execution.localFundingState == "reorged" {
                stage = WalletCopy.format("wallet_swap_stage_funding_reorged", second)
            } else if execution.localRole == "taker" {
                stage = WalletCopy.format("wallet_swap_stage_funding_ready_here", second)
            } else {
                stage = WalletCopy.format(
                    "wallet_swap_stage_waiting_counterparty_funding", second
                )
            }
        case "both_funded":
            stage = execution.localRole == "maker"
                ? WalletCopy.format(
                    "wallet_swap_stage_redeem_ready_here", second
                )
                : WalletCopy.format(
                    "wallet_swap_stage_waiting_counterparty_redeem", second
                )
        case "first_redeemed", "secret_observed":
            stage = execution.localRole == "taker"
                ? WalletCopy.format(
                    "wallet_swap_stage_secret_redeem_ready_here", first
                )
                : WalletCopy.format(
                    "wallet_swap_stage_waiting_counterparty_final_redeem", first
                )
        case "second_redeemed":
            stage = WalletCopy.text("wallet_swap_stage_second_redeemed")
        case "completed":
            stage = WalletCopy.text("wallet_swap_stage_completed")
        case "refund_eligible":
            stage = WalletCopy.format(
                "wallet_swap_stage_refund_ready_here",
                execution.localRole == "maker" ? first : second,
                formatAtomicSwapDeadline(
                    execution.localRole == "maker"
                        ? execution.firstRefundAtUnix
                        : execution.secondRefundAtUnix
                )
            )
        case "refund_broadcast":
            stage = WalletCopy.text("wallet_swap_stage_refunding")
        case "refunded":
            stage = WalletCopy.text("wallet_swap_stage_refunded")
        case "failed":
            stage = WalletCopy.text("wallet_swap_stage_failed")
        default:
            stage = execution.state.replacingOccurrences(of: "_", with: " ")
        }
        if let fundingTarget,
           now < execution.fundingDeadlineUnix {
            stage = WalletCopy.format(
                "wallet_swap_stage_with_deadline",
                stage,
                formatAtomicSwapRemaining(execution.fundingDeadlineUnix, now: now),
                fundingTarget,
                formatAtomicSwapDeadline(execution.fundingDeadlineUnix)
            )
        }
        if includeBitcoinSync && bitcoinSyncInProgress {
            stage = WalletCopy.format("wallet_swap_stage_syncing_append", stage)
        }
        return stage
    }

    private func fundingClosedStage(
        _ execution: NativeShakescapeExecutionSummary,
        first: String,
        fundingWasSubmitted: Bool,
        now: UInt64
    ) -> String {
        let refundAt = formatAtomicSwapDeadline(execution.firstRefundAtUnix)
        if execution.localRole == "maker",
           execution.firstFundingConfirmed || fundingWasSubmitted {
            if now >= execution.firstRefundAtUnix {
                return WalletCopy.format(
                    "wallet_swap_stage_refund_ready_here", first, refundAt
                )
            }
            return WalletCopy.format(
                "wallet_swap_stage_funding_expired",
                first,
                refundAt,
                formatAtomicSwapRemaining(execution.firstRefundAtUnix, now: now)
            )
        }
        if execution.localRole == "taker", execution.firstFundingConfirmed {
            return WalletCopy.format(
                "wallet_swap_stage_funding_closed_no_local_lock", refundAt
            )
        }
        return WalletCopy.text("wallet_swap_stage_unfunded_closed")
    }

    private func swapChainLabel(_ chain: String) -> String {
        switch chain {
        case "bitcoin": return "BTC"
        case "handshake": return "HNS"
        default: return chain.capitalized
        }
    }

    private func formatAtomicSwapDeadline(_ unix: UInt64) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(unix)))
    }

    private func formatAtomicSwapRemaining(_ deadline: UInt64, now: UInt64) -> String {
        let seconds = deadline > now ? deadline - now : 0
        let roundedMinutes = (seconds + 59) / 60
        let hours = roundedMinutes / 60
        let minutes = roundedMinutes % 60
        if hours > 0 {
            return WalletCopy.format(
                "wallet_swap_duration_hours_minutes", Int(hours), Int(minutes)
            )
        }
        return WalletCopy.format("wallet_swap_duration_minutes", Int(minutes))
    }

    private func pendingAcceptanceStage(
        _ acceptance: NativeDirectOfferAcceptanceSummary
    ) -> String {
        if let deadline = acceptance.fundingDeadlineUnix {
            let now = UInt64(Date().timeIntervalSince1970)
            return WalletCopy.format(
                "wallet_swap_acceptance_sent",
                String(acceptance.sessionId.prefix(12)),
                formatAtomicSwapDeadline(deadline),
                formatAtomicSwapRemaining(deadline, now: now)
            )
        }
        return WalletCopy.format(
            "wallet_swap_status_negotiating",
            swapAmount(acceptance.offeredAmount, asset: acceptance.offeredAsset),
            swapAmount(acceptance.receivedAmount, asset: acceptance.receivedAsset),
            String(acceptance.sessionId.prefix(12))
        )
    }

    private func shakescapeExecutionNotificationStage(
        _ execution: NativeShakescapeExecutionSummary
    ) -> String {
        shakescapeExecutionStage(execution, includeBitcoinSync: false)
    }

    private func rememberDirectShakescapePeer(_ endpoint: String) {
        recentDirectShakescapePeers.removeAll { $0 == endpoint }
        recentDirectShakescapePeers.insert(endpoint, at: 0)
        if recentDirectShakescapePeers.count > maximumVisibleDirectShakescapePeers {
            recentDirectShakescapePeers.removeLast(
                recentDirectShakescapePeers.count - maximumVisibleDirectShakescapePeers
            )
        }
    }

    private func beginHnsValueAction(_ intent: NativeHnsValueIntent) {
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_transaction_message")
        ) { [weak self] in
            self?.beginHnsValueActionAfterAuthentication(intent)
        }
    }

    private func beginHnsValueActionAfterAuthentication(_ intent: NativeHnsValueIntent) {
        guard hnsValueActionMayStart,
              !intent.requiresShakedex || shakedexAvailable,
              let lease = storageLease,
              let wallet else {
            showErrorMessage(WalletCopy.text("wallet_value_actions_locked"))
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        let expectedKind = intent.expectedApprovalKind
        readStatusLabel.text = WalletCopy.text("wallet_value_actions_syncing")
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
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_value_actions_prepare_failed"
                    )
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
        if pendingHnsValueApproval?.actionToken !== approval.actionToken {
            dismissPendingHnsValueApproval(rejectNatively: true)
            pendingHnsValueApproval = approval
        }
        let date = Date(timeIntervalSince1970: TimeInterval(approval.expiresAtUnix))
        let expiry = DateFormatter.localizedString(
            from: date,
            dateStyle: .medium,
            timeStyle: .medium
        )
        let message = (approval.detailLines + [WalletCopy.format(
            "wallet_value_actions_expires", expiry
        )]).joined(separator: "\n\n")
        let alert = UIAlertController(
            title: WalletCopy.text("action_approve_hns_value"),
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_reject"), style: .cancel) { [weak self] _ in
            self?.rejectHnsValueApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_broadcast_hns"), style: .destructive) { [weak self] _ in
            self?.approveHnsValueApproval(
                approval,
                lease: lease,
                generation: generation,
                walletIdentity: walletIdentity,
                authorityGeneration: authorityGeneration
            )
        })
        hnsValueApprovalAlert = alert
        walletPresentationHost.present(alert, animated: true)
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
        trackedShakedexFinalizeApprovalTransactionID = nil
        hnsValueApprovalAlert = nil
        readStatusLabel.text = WalletCopy.text("wallet_value_actions_executing")
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
                        ? WalletCopy.text(
                            "wallet_value_actions_submitted_reconciling"
                        )
                        : WalletCopy.format(
                            "wallet_value_actions_result", result.0.displayJSON
                        )
                    self.showNativeHnsResult(
                        title: WalletCopy.text("wallet_value_actions_result_title"),
                        json: result.0.displayJSON
                    )
                case .failure(let error):
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_value_actions_result_ambiguous"
                    )
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
        if let transactionID = trackedShakedexFinalizeApprovalTransactionID {
            trackedShakedexFinalizePromptAttempts.remove(transactionID)
        }
        trackedShakedexFinalizeApprovalTransactionID = nil
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
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_value_actions_rejected"
                    )
                case .failure(let error):
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_value_actions_reject_failed"
                    )
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func dismissPendingHnsValueApproval(rejectNatively: Bool) {
        let approval = pendingHnsValueApproval
        pendingHnsValueApproval = nil
        if rejectNatively,
           let transactionID = trackedShakedexFinalizeApprovalTransactionID {
            trackedShakedexFinalizePromptAttempts.remove(transactionID)
        }
        trackedShakedexFinalizeApprovalTransactionID = nil
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
            showErrorMessage(WalletCopy.text("wallet_shakedex_queries_locked"))
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text = WalletCopy.text("wallet_shakedex_queries_loading")
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
                    self.readStatusLabel.text = WalletCopy.format(
                        "wallet_shakedex_queries_result", result.displayJSON
                    )
                    if case .listOffers = query {
                        do {
                            self.showShakedexOfferPicker(try result.offerPage())
                        } catch {
                            self.readStatusLabel.text = WalletCopy.text(
                                "wallet_shakedex_offer_page_invalid"
                            )
                            self.showError(error)
                        }
                    } else {
                        self.showNativeHnsResult(
                            title: WalletCopy.text("row_wallet_shakedex_status"),
                            json: result.displayJSON
                        )
                    }
                case .failure(let error):
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_shakedex_queries_failed"
                    )
                    self.showError(error)
                }
                self.refreshButtonStates()
            }
        }
    }

    private func showShakedexOfferPicker(_ page: NativeShakedexOfferPage) {
        let detail = page.offers.isEmpty
            ? WalletCopy.format(
                "wallet_shakedex_offer_page_empty", Int(page.boardRevision)
            )
            : WalletCopy.format(
                "wallet_shakedex_offer_page_select",
                page.offers.count,
                Int(page.boardRevision)
            )
        var actions = page.offers.map { offer in
            let expiry = DateFormatter.localizedString(
                from: Date(timeIntervalSince1970: TimeInterval(offer.expiresAtUnix)),
                dateStyle: .medium,
                timeStyle: .medium
            )
            let title = WalletCopy.format(
                "wallet_shakedex_offer_choice",
                offer.name,
                WalletReadPresenter.formatHnsBaseUnits(offer.priceBaseUnits),
                WalletReadPresenter.formatHnsBaseUnits(
                    offer.marketplaceFeeBaseUnits
                ),
                expiry
            )
            return WalletMenuAction(
                title: title,
                section: WalletCopy.text("wallet_shakedex_available_name_offers")
            ) { [weak self] in
                self?.showAcceptOfferForm(selectedOffer: offer)
            }
        }
        if let cursor = page.nextCursor {
            actions.append(WalletMenuAction(title: WalletCopy.text("wallet_shakedex_next_offer_page"), section: WalletCopy.text("wallet_shakedex_more_offers")) { [weak self] in
                self?.beginShakedexQuery(.listOffers(cursor: cursor, limit: 32))
            })
        }
        presentWalletMenu(
            title: WalletCopy.text("wallet_shakedex_available_name_offers"),
            rows: [WalletMenuRow(
                title: WalletCopy.format(
                    "wallet_shakedex_offer_page_summary",
                    page.offers.count,
                    Int(page.boardRevision)
                ),
                detail: detail
            )],
            actions: actions,
            retainForChildActions: false
        )
    }

    private func showNativeHnsResult(title: String, json: String) {
        let alert = UIAlertController(title: title, message: json, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_done"), style: .cancel))
        walletPresentationHost.present(alert, animated: true)
    }

    @objc private func showWalletActivity() {
        guard let recentTransactions else {
            presentWalletMenu(
                title: "Recent activity",
                rows: [WalletMenuRow(title: "Transactions", detail: historyLabel.text ?? "")],
                actions: []
            )
            return
        }
        let page = WalletReadPresenter.presentTransactionPage(
            recentTransactions,
            requestedOffset: recentActivityPageOffset
        )
        recentActivityPageOffset = page.offset
        var actions: [WalletMenuAction] = []
        if page.hasPrevious {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_previous_wallet_activity")) { [weak self] in
                guard let self else { return }
                self.recentActivityPageOffset -= page.pageSize
                DispatchQueue.main.async { self.showWalletActivity() }
            })
        }
        if page.hasNext {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_next_wallet_activity")) { [weak self] in
                guard let self else { return }
                self.recentActivityPageOffset += page.pageSize
                DispatchQueue.main.async { self.showWalletActivity() }
            })
        }
        actions.append(WalletMenuAction(title: "Copy activity") {
            UIPasteboard.general.setItems(
                [[UTType.plainText.identifier: page.text]],
                options: [.localOnly: true]
            )
        })
        presentWalletMenu(
            title: "Recent activity",
            rows: [WalletMenuRow(title: "Transactions", detail: page.text)],
            actions: actions
        )
    }

    private func showWalletManagement() {
        guard walletCanPresentChild else { return }
        var actions: [WalletMenuAction] = []
        let canStopSynchronization = hnsCatchupRetryPending ||
            WalletHnsSyncPresentationCache.canRequestCancellation(networkID: network.rawValue)
        if canStopSynchronization {
            actions.append(WalletMenuAction(
                title: WalletCopy.text("action_stop_wallet_sync"),
                style: .destructive
            ) { [weak self] in
                self?.requestHnsSynchronizationCancellation()
            })
        } else if walletIsUnlocked {
            actions.append(WalletMenuAction(title: WalletCopy.text("action_lock_wallet")) { [weak self] in
                self?.lockWallet()
            })
        } else if openButton.isEnabled {
            actions.append(WalletMenuAction(title: "Unlock") { [weak self] in
                self?.openOrUnlockWallet()
            })
        }
        if walletIsUnlocked,
           (try? keychain.hasRecoveryPhrase()) == true {
            actions.append(WalletMenuAction(title: "View recovery phrase") { [weak self] in
                self?.showStoredRecoveryPhrase()
            })
        }
        if deleteButton.isEnabled && !canStopSynchronization {
            actions.append(WalletMenuAction(title: "Delete wallet", style: .destructive) { [weak self] in
                self?.requestConfirmedWalletDeletion()
            })
        }
        presentWalletMenu(
            title: "Wallet",
            rows: [
                WalletMenuRow(title: "Status", detail: statusLabel.text ?? "Status unavailable."),
                WalletMenuRow(title: "Account", detail: accountLabel.text ?? "Account unavailable."),
            ],
            actions: actions
        )
    }

    @objc private func createWallet() {
        guard currentAuthenticatedNewWalletBirthdayHeight() != nil else {
            newWalletCreationRequested = true
            statusLabel.text = WalletCopy.text("wallet_create_waiting_for_initial_sync")
            browserProcess?.syncNow { [weak self] _ in
                self?.refreshState()
                self?.continuePendingNewWalletCreationIfReady()
            }
            refreshButtonStates()
            return
        }
        newWalletCreationRequested = false
        authenticateWalletAction(
            reason: WalletCopy.text("wallet_auth_create_message")
        ) { [weak self] in
            self?.createWalletAfterAuthentication()
        }
    }

    private func createWalletAfterAuthentication() {
        statusLabel.text = WalletCopy.text("wallet_status_creating")
        performWalletOperation {
            guard try self.canStartNewWallet() else { return }
            guard let birthdayHeight = self.currentAuthenticatedNewWalletBirthdayHeight() else {
                self.newWalletCreationRequested = true
                throw WalletProviderError(
                    code: "walletCreationSyncRequired",
                    message: "Wallet creation needs a current verified Handshake height. No wallet was created."
                )
            }
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
                self.updateRecoveryCeremonyScreenAwake(true)
            } catch {
                controller.close()
                try? Self.deleteWalletFiles(databasePath: path)
                throw error
            }
        }
    }

    @objc private func restoreWallet() {
        newWalletCreationRequested = false
        let alert = UIAlertController(
            title: WalletCopy.text("row_wallet_restore"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { [weak self] field in
            field.placeholder = WalletCopy.text("wallet_restore_phrase_hint")
            field.isSecureTextEntry = true
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.accessibilityLabel = WalletCopy.text("wallet_restore_phrase_hint")
            field.accessibilityHint = WalletCopy.text("wallet_restore_phrase_required")
            field.accessibilityIdentifier = "wallet.restore.phrase"
            self?.restorePhraseField = field
        }
        alert.addTextField { field in
            field.placeholder = WalletCopy.text("wallet_restore_birthday_hint")
            field.text = "0"
            field.keyboardType = .numberPad
            field.accessibilityLabel = WalletCopy.text("wallet_restore_birthday_hint")
            field.accessibilityIdentifier = "wallet.restore.birthday"
        }
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_cancel"), style: .cancel) { [weak self] _ in
            self?.clearRestoreInput()
        })
        let submit: () -> Void = { [weak self, weak alert] in
            guard let self, let alert else { return }
            var phrase = Array((alert.textFields?.first?.text ?? "").utf8)
            self.clearRestoreInput()
            guard !phrase.isEmpty, phrase.count <= 256 else {
                WalletSecretBytes.wipe(&phrase)
                self.showErrorMessage(WalletCopy.text("wallet_restore_phrase_required"))
                return
            }
            guard let birthdayText = alert.textFields?.dropFirst().first?.text,
                  let birthdayHeight = UInt64(birthdayText),
                  birthdayHeight <= UInt64(UInt32.max) else {
                WalletSecretBytes.wipe(&phrase)
                self.showErrorMessage(WalletCopy.text("wallet_restore_birthday_hint"))
                return
            }
            let recoveryBytes = phrase
            WalletSecretBytes.wipe(&phrase)
            self.authenticateWalletAction(
                reason: WalletCopy.text("wallet_auth_restore_message")
            ) { [weak self] in
                self?.restoreWalletAfterAuthentication(
                    recoveryBytes: recoveryBytes,
                    birthdayHeight: birthdayHeight
                )
            }
        }
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("action_restore_wallet"),
            style: .default
        ) { _ in submit() })
        walletPresentationHost.present(alert, animated: true)
    }

    private func restoreWalletAfterAuthentication(
        recoveryBytes: [UInt8],
        birthdayHeight: UInt64
    ) {
        statusLabel.text = WalletCopy.text("wallet_status_restoring")
        var phrase = recoveryBytes
        var restored = false
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
                restored = true
            } catch {
                controller.close()
                try? self.keychain.deleteDatabaseKey()
                try? Self.deleteWalletFiles(databasePath: path)
                throw error
            }
        }
        if restored { continueAfterWalletPersistence() }
    }

    @objc private func openOrUnlockWallet() {
        statusLabel.text = WalletCopy.text("wallet_status_unlocking")
        performWalletOperation {
            try reconcileIncompleteStorage()
            let path = try walletDatabasePath()
            var reopenedFromDurableStorage = false
            let opened = try keychain.withDatabaseKey(
                prompt: WalletCopy.text("wallet_auth_unlock_message")
            ) { key -> RustNativeWallet in
                let controller: RustNativeWallet
                if let wallet, walletWasReopenedFromDurableStorage {
                    controller = wallet
                } else {
                    wallet?.close()
                    self.statusLabel.text = WalletCopy.text("wallet_status_unlocking")
                    self.refreshButtonStates()
                    controller = try RustNativeWallet.open(
                        databasePath: path,
                        databaseKey: key
                    )
                    reopenedFromDurableStorage = true
                }
                self.statusLabel.text = WalletCopy.text("wallet_status_unlocking")
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
        dismissWalletPopupForLock()
        performWalletOperation {
            guard let wallet, unconfirmedDatabaseKey == nil else { return }
            try wallet.lock()
            clearRecoveryDisplay()
        }
    }

    /// No wallet-owned sheet may remain visible after signing authority locks.
    /// This includes Bitcoin and Shakedex menus as well as approval alerts.
    private func dismissWalletPopupForLock() {
        presentedViewController?.dismiss(animated: false)
    }

    @objc private func confirmRecoverySaved() {
        guard unconfirmedDatabaseKey != nil, let recoverySecret,
              let phrase = try? recoverySecret.displayText() else { return }
        let words = phrase.split(whereSeparator: \.isWhitespace).map(String.init)
        guard words.count == 24 else {
            showErrorMessage("The generated recovery phrase did not contain exactly 24 words.")
            return
        }
        guard let wordList = try? walletBip39EnglishWords(),
              words.allSatisfy(wordList.contains) else {
            showErrorMessage("The BIP-39 verification word list is unavailable.")
            return
        }
        setRecoveryPhraseObscured(true)
        showRecoveryConfirmationQuestion(
            words: words,
            wordList: wordList,
            index: 0,
            hadIncorrectChoice: false
        )
    }

    /// Covering a secret with an alert does not remove it from the pixels
    /// around the alert or from the accessibility hierarchy. The phrase stays
    /// unavailable on both surfaces until the quiz is cancelled or fails.
    private func setRecoveryPhraseObscured(_ obscured: Bool) {
        recoveryTextView.isHidden = obscured
        recoveryTextView.accessibilityElementsHidden = obscured
        recoveryTitle.isHidden = obscured
        recoveryTitle.accessibilityElementsHidden = obscured
    }

    private func showRecoveryConfirmationQuestion(
        words: [String],
        wordList: [String],
        index: Int,
        hadIncorrectChoice: Bool
    ) {
        guard index < words.count else {
            if hadIncorrectChoice {
                setRecoveryPhraseObscured(false)
                let alert = UIAlertController(
                    title: WalletCopy.text("wallet_recovery_quiz_failed_title"),
                    message: WalletCopy.text("wallet_recovery_quiz_failed_message"),
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                walletPresentationHost.present(alert, animated: true)
            } else {
                persistConfirmedWallet()
            }
            return
        }
        let alert = UIAlertController(
            title: WalletCopy.format("wallet_recovery_quiz_word",
                index + 1,
                words.count
            ),
            message: WalletCopy.text("wallet_recovery_quiz_message"),
            preferredStyle: .alert
        )
        for choice in walletRecoveryWordChoices(
            words: words,
            correctIndex: index,
            bip39Words: wordList
        ) {
            alert.addAction(UIAlertAction(title: choice, style: .default) { [weak self] _ in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                    self?.showRecoveryConfirmationQuestion(
                        words: words,
                        wordList: wordList,
                        index: index + 1,
                        hadIncorrectChoice: hadIncorrectChoice || choice != words[index]
                    )
                }
            })
        }
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_cancel"), style: .cancel) { [weak self] _ in
            self?.setRecoveryPhraseObscured(false)
        })
        walletPresentationHost.present(alert, animated: true)
    }

    private func persistConfirmedWallet() {
        guard !isOperating else {
            setRecoveryPhraseObscured(false)
            return
        }
        guard var key = unconfirmedDatabaseKey else {
            setRecoveryPhraseObscured(false)
            return
        }
        unconfirmedDatabaseKey = nil
        var persisted = false
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
                persisted = true
            } catch {
                discardWalletAndFiles()
                throw error
            }
        }
        if persisted { continueAfterWalletPersistence() }
    }

    private func continueAfterWalletPersistence() {
        statusLabel.text = "Wallet saved. Authenticate to unlock and update balances."
        DispatchQueue.main.async { [weak self] in
            guard let self,
                  self.walletAuthorityRequested,
                  self.viewIfLoaded?.window != nil,
                  self.persistentWalletExists,
                  self.unconfirmedDatabaseKey == nil,
                  !self.isOperating else { return }
            self.openOrUnlockWallet()
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
            alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_done"), style: .cancel))
            walletPresentationHost.present(alert, animated: true)
        } catch {
            showError(error)
        }
    }

    private func authenticateWalletAction(
        reason: String,
        cancelled: @escaping @MainActor @Sendable () -> Void = {},
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
                } else {
                    cancelled()
                    if errorCode != .userCancel,
                       errorCode != .systemCancel,
                       errorCode != .appCancel {
                        self.showErrorMessage(errorMessage ?? "Wallet authentication failed.")
                    }
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
        synchronizeWalletReadsFromUserAction()
        if operationWasAlreadyInFlight || !isOperating {
            walletRefreshControl.endRefreshing()
        }
    }

    @objc private func synchronizeWalletReadsFromUserAction() {
        synchronizeWalletReads(resumeAutomaticSync: true)
    }

    private func synchronizeWalletReads(
        resumeAutomaticSync: Bool,
        reportFailure: Bool = true
    ) {
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
        statusLabel.text = WalletCopy.text("wallet_status_syncing_reads")
        readStatusLabel.text = WalletCopy.text("wallet_reads_syncing")
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
                    if progress.headerState != .outboundPortBlocked {
                        // Keep every value action disabled across the bounded
                        // checkpoint gap. This remains one logical sync until
                        // the next round starts or authority is revoked.
                        self.isOperating = true
                        self.hnsCatchupRetryPending = true
                        self.scheduleHnsCatchupRetry(
                            lease: lease,
                            wallet: wallet,
                            generation: generation,
                            authorityGeneration: authorityGeneration
                        )
                    } else {
                        // This foreground attempt cannot make progress on the
                        // current path. Let a later explicit synchronization
                        // retry after the network or VPN route changes.
                        self.hnsCatchupRetryPending = false
                    }
                case .cancelled:
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_reads_sync_stopped_checkpoint"
                    )
                case .failure(let detail):
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_reads_sync_failed"
                    )
                    self.clearReadProjection()
                    if reportFailure { self.showErrorMessage(detail) }
                }
                WalletHnsSyncPresentationCache.clear(networkID: keychain.networkID)
                self.refreshButtonStates()
            }
        }
    }

    private func renderHnsCatchup(_ progress: NativeHnsCatchupProgress) {
        if progress.scannedHeight == nil,
           progress.headerTipHeight < progress.birthdayHeight {
            readStatusLabel.text = WalletCopy.format(
                "wallet_reads_catching_up_before_birthday",
                Int(progress.headerTipHeight),
                Int(progress.birthdayHeight)
            )
            return
        }
        let scanned = progress.scannedHeight ?? progress.birthdayHeight
        switch progress.headerState {
        case .current:
            readStatusLabel.text = WalletCopy.format(
                "wallet_reads_catching_up_scan",
                Int(scanned),
                Int(progress.targetHeight)
            )
        case .syncing:
            readStatusLabel.text = WalletCopy.format(
                "wallet_reads_catching_up_headers",
                Int(progress.headerTipHeight),
                Int(scanned),
                Int(progress.targetHeight)
            )
        case .degraded:
            readStatusLabel.text = WalletCopy.format(
                "wallet_reads_catching_up_degraded",
                Int(progress.headerTipHeight)
            )
        case .outboundPortBlocked:
            readStatusLabel.text = WalletCopy.text("wallet_reads_outbound_12038_blocked")
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
        guard walletCanPresentChild,
              let expected = current.authority,
              walletNameImportMayStart(expected: expected, current: current) else {
            nameImportStatusLabel.text = WalletCopy.text(
                "wallet_name_import_waiting_for_wallet"
            )
            return
        }

        let form = presentWalletForm(
            title: WalletCopy.text("row_wallet_name_import"),
            message: WalletCopy.text("wallet_name_import_ready"),
            fields: [WalletSheetFormField(
                label: WalletCopy.text("wallet_name_import_hint"),
                placeholder: WalletCopy.text("wallet_name_import_hint"),
                accessibilityIdentifier: "wallet.import-hns-name.text",
                configure: { [weak self] field in
                    configureWalletNameImportTextField(field)
                    self?.walletNameImportField = field
                }
            )],
            primaryTitle: WalletCopy.text("action_import_wallet_name")
        ) { [weak self] values in
            let canonical = values.first.flatMap(canonicalHandshakeNameImportText)
            let input = WalletExactHnsNameInput(exactText: canonical)
            guard let self else { return }
            self.clearWalletNameImportPrompt(dismiss: false)
            guard let input else {
                self.nameImportStatusLabel.text = WalletCopy.text(
                    "wallet_name_import_invalid"
                )
                return
            }
            let rechecked = self.currentWalletNameImportState()
            guard walletNameImportMayStart(
                expected: expected,
                current: rechecked
            ) else {
                self.nameImportStatusLabel.text = WalletCopy.text(
                    "wallet_action_busy"
                )
                return
            }
            self.beginExactHnsNameImport(input: input, authority: expected)
        }
        walletNameImportAlert = form
    }

    private func beginExactHnsNameImport(
        input: WalletExactHnsNameInput,
        authority: WalletNameImportAuthority
    ) {
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current),
              let wallet,
              let lease = storageLease else {
            nameImportStatusLabel.text = WalletCopy.text("wallet_action_busy")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        nameImportStatusLabel.text = WalletCopy.text(
            "wallet_name_import_importing"
        )
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
                    self.nameImportStatusLabel.text = WalletCopy.format(
                        "wallet_name_import_success",
                        WalletReadPresenter.presentName(summary)
                    )
                case .successRefreshFailed:
                    self.refreshState()
                    self.readStatusLabel.text = WalletCopy.text(
                        "wallet_name_import_success_refresh_failed"
                    )
                    self.clearReadProjection()
                    self.nameImportStatusLabel.text = WalletCopy.text(
                        "wallet_name_import_success_refresh_failed"
                    )
                case .invalidInput:
                    self.nameImportStatusLabel.text = WalletCopy.text(
                        "wallet_name_import_invalid"
                    )
                case .failure:
                    self.refreshState()
                    self.nameImportStatusLabel.text = WalletCopy.text(
                        "wallet_name_import_failed"
                    )
                }
                self.refreshButtonStates()
            }
        }
    }

    private func requestMultipleHnsNameImport() {
        let current = currentWalletNameImportState()
        guard walletCanPresentChild,
              let expected = current.authority,
              walletNameImportMayStart(expected: expected, current: current) else {
            nameImportStatusLabel.text = WalletCopy.text(
                "wallet_name_import_waiting_for_wallet"
            )
            return
        }
        let editor = WalletMultipleNameImportEditorViewController { [weak self] names in
            self?.showMultipleHnsNameImportReview(names: names, authority: expected)
        }
        walletPresentationHost.present(UINavigationController(rootViewController: editor), animated: true)
    }

    private func showMultipleHnsNameImportReview(
        names: [String],
        authority: WalletNameImportAuthority
    ) {
        guard walletCanPresentChild else { return }
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current) else {
            nameImportStatusLabel.text = WalletCopy.text("wallet_action_busy")
            return
        }
        let review = WalletMultipleNameImportReviewViewController(names: names) { [weak self] in
            guard let self else { return }
            let rechecked = self.currentWalletNameImportState()
            guard walletNameImportMayStart(expected: authority, current: rechecked) else {
                self.nameImportStatusLabel.text = WalletCopy.text(
                    "wallet_action_busy"
                )
                return
            }
            self.beginMultipleHnsNameImport(names: names, authority: authority)
        }
        walletPresentationHost.present(UINavigationController(rootViewController: review), animated: true)
    }

    private func beginMultipleHnsNameImport(
        names: [String],
        authority: WalletNameImportAuthority
    ) {
        let current = currentWalletNameImportState()
        guard walletNameImportMayStart(expected: authority, current: current),
              let wallet,
              let lease = storageLease else {
            nameImportStatusLabel.text = WalletCopy.text("wallet_action_busy")
            return
        }
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        nameImportStatusLabel.text = WalletCopy.format(
            "wallet_name_bulk_import_importing", names.count
        )
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
                    self.nameImportStatusLabel.text = WalletCopy.format(
                        "wallet_name_bulk_import_success", count
                    )
                case .successRefreshFailed:
                    self.refreshState()
                    self.readStatusLabel.text = WalletCopy.format(
                        "wallet_name_bulk_import_refresh_pending", names.count
                    )
                    self.clearReadProjection()
                    self.nameImportStatusLabel.text = WalletCopy.format(
                        "wallet_name_bulk_import_refresh_pending", names.count
                    )
                case .invalidInput:
                    self.nameImportStatusLabel.text = WalletCopy.text(
                        "wallet_name_multiple_import_invalid"
                    )
                case .failure:
                    self.refreshState()
                    self.nameImportStatusLabel.text = WalletCopy.text(
                        "wallet_name_import_failed"
                    )
                }
                self.refreshButtonStates()
            }
        }
    }

    @objc private func requestConfirmedWalletDeletion() {
        guard walletCanPresentChild else { return }
        if hnsCatchupRetryPending || WalletHnsSyncPresentationCache.canRequestCancellation(
            networkID: network.rawValue
        ) {
            requestHnsSynchronizationCancellation()
            return
        }
        do {
            let authority = try currentConfirmedDeletionAuthority()
            let alert = UIAlertController(
                title: WalletCopy.text("wallet_delete_first_title"),
                message: WalletCopy.format(
                    "wallet_delete_first_message",
                    authority.network.title,
                    authority.network.rawValue,
                    authority.accountID
                ),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: WalletCopy.text("wallet_action_cancel"),
                style: .cancel
            ))
            alert.addAction(UIAlertAction(title: WalletCopy.text("action_continue_wallet_deletion"), style: .destructive) { [weak self] _ in
                self?.presentTypedDeletionConfirmation(expected: authority)
            })
            walletPresentationHost.present(alert, animated: true)
        } catch {
            showError(error)
        }
    }

    private func requestHnsSynchronizationCancellation() {
        let alert = UIAlertController(
            title: WalletCopy.text("wallet_stop_sync_title"),
            message: WalletCopy.text("wallet_stop_sync_message"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: WalletCopy.text("action_keep_synchronizing"), style: .cancel))
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("action_stop_sync"),
            style: .destructive
        ) { [weak self] _ in
            guard let self else { return }
            if self.hnsCatchupRetryPending {
                self.hnsCatchupRetryPending = false
                self.isOperating = false
                WalletHnsSyncPresentationCache.pauseAutomaticSync(
                    networkID: self.network.rawValue
                )
                self.readStatusLabel.text = WalletCopy.text(
                    "wallet_reads_sync_stopped_checkpoint"
                )
            } else {
                WalletHnsSyncPresentationCache.requestCancellation(
                    networkID: self.network.rawValue
                )
            }
            self.refreshState()
        })
        walletPresentationHost.present(alert, animated: true)
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
            title: WalletCopy.text("wallet_delete_typed_title"),
            message: WalletCopy.format(
                "wallet_delete_typed_message",
                authority.network.title,
                authority.network.rawValue,
                authority.accountID
            ),
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
        alert.addAction(UIAlertAction(title: WalletCopy.text("wallet_action_cancel"), style: .cancel) { [weak alert] _ in
            alert?.textFields?.first?.text = nil
        })
        alert.addAction(UIAlertAction(
            title: WalletCopy.text("row_wallet_delete"),
            style: .destructive
        ) { [weak self, weak alert] _ in
            let typedValue = alert?.textFields?.first?.text
            alert?.textFields?.first?.text = nil
            guard walletDeletionConfirmationMatches(typedValue) else {
                self?.showErrorMessage(
                    WalletCopy.text("wallet_delete_type_delete_error")
                )
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
        walletPresentationHost.present(alert, animated: true)
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
                message: WalletCopy.text(
                    "wallet_delete_requires_unlocked_confirmed_wallet"
                )
            )
        }
        guard FileManager.default.fileExists(atPath: path),
              try keychain.hasDatabaseKey() else {
            throw WalletProviderError(
                code: "walletDeletionStorageMismatch",
                message: WalletCopy.text("wallet_status_delete_cleanup_pending")
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
                message: WalletCopy.text(
                    "wallet_delete_requires_unlocked_confirmed_wallet"
                )
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
                message: WalletCopy.text("wallet_delete_context_changed")
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
            showErrorMessage(WalletCopy.text("wallet_delete_context_changed"))
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
                self.showErrorMessage(
                    WalletCopy.text("wallet_status_delete_close_failed")
                )
            case .keyDeletionFailed:
                self.showErrorMessage(
                    WalletCopy.text("wallet_status_delete_key_failed")
                )
            case .authorityRevoked:
                self.showErrorMessage(
                    WalletCopy.text("wallet_delete_context_changed")
                )
            case .encryptedOrphanCleanupPending where encryptedOrphanRemains:
                self.showErrorMessage(
                    WalletCopy.text("wallet_status_delete_cleanup_pending")
                )
            case .encryptedOrphanCleanupPending:
                break
            }
        }
    }

    private func publish(_ snapshot: NativeHnsReadSnapshot) {
        let presentation = WalletReadPresenter.present(snapshot)
        let balance = WalletHnsBalancePresenter.present(snapshot)
        latestReadSnapshot = snapshot
        atomicSwapNotifications.reconcileIncomingHns(
            snapshot,
            amountText: WalletReadPresenter.formatHnsBaseUnits
        )
        latestReadSnapshotObservedAtUptime = ProcessInfo.processInfo.systemUptime
        if shakescapeExecutionStatusSnapshot.flatMap({
            liveAtomicSwapFingerprint($0)
        }) != nil {
            lastAutomaticSwapHnsSyncAtUptime = latestReadSnapshotObservedAtUptime
        }
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
        let swapReserved = (try? wallet?.reservedHnsForDirectOffers()) ?? 0
        if swapReserved > 0 {
            let spendable = UInt64(balance.spendableBaseUnits) ?? 0
            let available = spendable >= swapReserved ? spendable - swapReserved : 0
            var balanceLines = [WalletCopy.format(
                "wallet_reads_swap_reserved",
                WalletReadPresenter.formatHnsBaseUnits(String(swapReserved)),
                WalletReadPresenter.formatHnsBaseUnits(String(available))
            )]
            if balance.hasPendingOutgoing {
                balanceLines.insert(
                    WalletCopy.format(
                        "wallet_reads_balance_confirmed_with_pending",
                        WalletReadPresenter.formatHnsBaseUnits(
                            balance.spendableBaseUnits
                        ),
                        WalletReadPresenter.formatHnsBaseUnits(
                            balance.pendingOutgoingBaseUnits
                        )
                    ),
                    at: 0
                )
            } else {
                balanceLines.insert(
                    WalletCopy.format(
                        "wallet_reads_balance_confirmed",
                        WalletReadPresenter.formatHnsBaseUnits(
                            balance.spendableBaseUnits
                        )
                    ),
                    at: 0
                )
            }
            balanceLabel.text = balanceLines.joined(separator: "\n")
        } else {
            balanceLabel.text = presentation.balance
        }
        paymentReceiveLabel.text = presentation.paymentReceive
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
        scheduleTrackedShakedexFinalizeApproval(snapshot)
    }

    /// New purchases carry an approval-bound automatic FINALIZE fee cap and
    /// native recovery submits them during synchronization. A pre-upgrade
    /// purchase has no such authority, so its durable workflow raises one
    /// exact approval without exposing an internal buyer-session ID.
    private func scheduleTrackedShakedexFinalizeApproval(_ snapshot: NativeHnsReadSnapshot) {
        guard let notice = snapshot.finalizeNotices.first(where: {
            $0.phase == "finalizeAvailable" &&
                !trackedShakedexFinalizePromptAttempts.contains($0.transactionID)
        }),
        !isOperating,
        !walletAuthenticationInProgress,
        pendingHnsValueApproval == nil,
        hnsValueActionMayStart,
        shakedexAvailable,
        let lease = storageLease,
        let wallet else { return }

        trackedShakedexFinalizePromptAttempts.insert(notice.transactionID)
        isOperating = true
        readGeneration &+= 1
        let generation = readGeneration
        let walletIdentity = ObjectIdentifier(wallet)
        let authorityGeneration = walletAuthorityGeneration
        readStatusLabel.text =
            WalletCopy.text("wallet_finalize_notice_preparing_legacy")
        refreshButtonStates()

        DispatchQueue.global(qos: .userInitiated).async { [wallet] in
            let outcome: Result<NativeHnsValueApproval?, Error> = Result {
                let approval = try wallet.prepareNextShakedexFinalize()
                guard let approval else { return nil }
                guard approval.kind == .nameMarketPurchase else {
                    try? wallet.rejectHnsValueAction(approval.actionToken)
                    try? wallet.lock()
                    throw NativeWalletBridgeError.invalidOutput(
                        "tracked FINALIZE changed its native approval kind"
                    )
                }
                return approval
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                let mayPublish = walletReadMayPublish(
                    expectedGeneration: generation,
                    currentGeneration: self.readGeneration,
                    expectedLease: lease,
                    currentLease: self.storageLease,
                    expectedWalletIdentity: walletIdentity,
                    currentWalletIdentity: self.wallet.map { ObjectIdentifier($0) },
                    expectedAuthorityGeneration: authorityGeneration,
                    currentAuthorityGeneration: self.walletAuthorityGeneration,
                    viewIsVisible: self.walletAuthorityRequested && self.viewIfLoaded?.window != nil
                )
                guard mayPublish else {
                    if case .success(let approval?) = outcome {
                        DispatchQueue.global(qos: .userInitiated).async {
                            try? wallet.rejectHnsValueAction(approval.actionToken)
                        }
                    }
                    return
                }
                switch outcome {
                case .success(nil):
                    self.isOperating = false
                    self.refreshState()
                    self.readStatusLabel.text = WalletReadPresenter.present(snapshot).status
                    self.refreshButtonStates()
                case .success(let approval?):
                    self.pendingHnsValueApproval = approval
                    self.trackedShakedexFinalizeApprovalTransactionID = notice.transactionID
                    self.authenticateWalletAction(
                        reason: "Authenticate to review and complete the tracked purchase of \(notice.name). No session ID is required.",
                        cancelled: { [weak self] in
                            self?.rejectHnsValueApproval(
                                approval,
                                lease: lease,
                                generation: generation,
                                walletIdentity: walletIdentity,
                                authorityGeneration: authorityGeneration
                            )
                        }
                    ) { [weak self] in
                        guard let self else { return }
                        self.showHnsValueApproval(
                            approval,
                            lease: lease,
                            generation: generation,
                            walletIdentity: walletIdentity,
                            authorityGeneration: authorityGeneration
                        )
                    }
                case .failure(let error):
                    self.isOperating = false
                    self.refreshState()
                    self.readStatusLabel.text =
                        "The tracked name purchase could not be prepared for FINALIZE. Synchronize before retrying."
                    self.showError(error)
                    self.refreshButtonStates()
                }
            }
        }
    }

    private func startPendingOutgoingRefreshObserver() {
        guard browserSyncObservation == nil, let browserProcess else { return }
        browserSyncObservation = browserProcess.observeSync { [weak self] summary in
            guard let self, summary.network == self.network.rawValue else { return }
            self.latestObservedBrowserSyncSummary = summary
            if summary.hasAuthoritativeCurrentness, let height = summary.bestHeight {
                self.latestObservedBrowserHeaderHeight = max(
                    self.latestObservedBrowserHeaderHeight ?? 0,
                    height
                )
            }
            if self.wallet == nil,
               !self.persistentWalletExists,
               self.unconfirmedDatabaseKey == nil {
                self.refreshState()
            }
            if self.continuePendingNewWalletCreationIfReady() { return }
            guard summary.hasAuthoritativeCurrentness,
                  summary.bestHeight != nil else { return }
            self.maybeRefreshPendingOutgoingAfterNewBlock()
            self.maybeRefreshWalletAfterNewBlock()
        }
    }

    private func currentAuthenticatedNewWalletBirthdayHeight() -> UInt64? {
        let summary = latestObservedBrowserSyncSummary
        return authenticatedNewWalletBirthdayHeight(
            expectedNetwork: network,
            observedNetwork: summary?.network,
            hasAuthoritativeCurrentness: summary?.hasAuthoritativeCurrentness == true,
            observedHeight: summary?.bestHeight
        )
    }

    @discardableResult
    private func continuePendingNewWalletCreationIfReady() -> Bool {
        guard walletPendingCreationMayContinue(
            requested: newWalletCreationRequested,
            viewIsVisible: walletAuthorityRequested && viewIfLoaded?.window != nil,
            operationInFlight: isOperating,
            hasCurrentAuthenticatedHeight:
                currentAuthenticatedNewWalletBirthdayHeight() != nil,
            persistentWalletExists: persistentWalletExists,
            hasController: wallet != nil,
            hasUnconfirmedRecovery: unconfirmedDatabaseKey != nil
        ) else { return false }
        newWalletCreationRequested = false
        createWallet()
        return true
    }

    /// Keep an ordinary display timeout from destroying the phrase while it
    /// is being copied. Manual device lock and app backgrounding still retire
    /// and delete the unconfirmed wallet through the existing lifecycle gate.
    private func updateRecoveryCeremonyScreenAwake(_ active: Bool) {
        if active {
            recoveryIdleTimerDisabledByWallet = true
            UIApplication.shared.isIdleTimerDisabled = true
        } else if recoveryIdleTimerDisabledByWallet {
            recoveryIdleTimerDisabledByWallet = false
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private func maybeRefreshWalletAfterNewBlock() {
        guard let refreshHeight = walletAutomaticRefreshHeight(
            snapshotHeight: latestPublishedSnapshotHeight,
            observedHeaderHeight: latestObservedBrowserHeaderHeight,
            attemptedHeaderHeight: automaticWalletRefreshAttemptedHeight
        ),
        walletAuthorityRequested,
        viewIfLoaded?.window != nil,
        storageLease != nil,
        wallet != nil,
        walletIsUnlocked,
        synchronizedReadsAvailable,
        !isOperating,
        !bitcoinSyncInProgress else { return }
        automaticWalletRefreshAttemptedHeight = refreshHeight
        readStatusLabel.text = "New HNS block. Updating balance…"
        synchronizeWalletReads(resumeAutomaticSync: false, reportFailure: false)
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
        latestReadSnapshotObservedAtUptime = nil
        receiveTargets = nil
        recentTransactions = nil
        finalizeNotices = []
        recentActivityPageOffset = 0
        balanceLabel.text = WalletCopy.text("wallet_reads_balance_unavailable")
        paymentReceiveLabel.text = WalletCopy.text("wallet_reads_receive_unavailable")
        historyLabel.text = "Transaction history: unavailable."
        namesLabel.text = "Tracked names: unavailable."
        if pendingOutgoingSnapshotHeight != nil {
            readStatusLabel.text = WalletCopy.text("wallet_pending_outgoing_recovery")
            balanceLabel.text = WalletCopy.text("wallet_pending_outgoing_balance_unavailable")
        }
    }

    private func formatFinalizeNotice(_ notice: NativeHnsReadSnapshot.FinalizeNotice) -> String {
        switch notice.phase {
        case "transferPending":
            return "\(notice.name): TRANSFER submitted and awaiting confirmation. Transaction \(notice.transactionID). Current block \(notice.currentHeight)."
        case "finalizeWaiting":
            return "\(notice.name): TRANSFER confirmed and tracked. Current block \(notice.currentHeight); FINALIZE becomes available at block \(notice.finalizeEligibleHeight ?? 0). Transfer transaction \(notice.transactionID)."
        case "finalizeAvailable":
            return "\(notice.name): FINALIZE is available now and remains tracked until it is submitted and confirmed. Current block \(notice.currentHeight); eligible since block \(notice.finalizeEligibleHeight ?? 0). Transfer transaction \(notice.transactionID)."
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
        pendingHnsForBtcOfferApproval?.actionToken.discard()
        pendingHnsForBtcOfferApproval = nil
        hnsForBtcOfferApprovalAlert?.dismiss(animated: false)
        pendingDirectOfferAcceptanceApproval?.actionToken.discard()
        pendingDirectOfferAcceptanceApproval = nil
        directOfferAcceptanceApprovalAlert?.dismiss(animated: false)
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
        bitcoinActivityPageOffset = 0
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
        if alreadyInstalled {
            startAutomaticHnsSynchronizationIfReady()
            return
        }

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
                    self.readStatusLabel.text = "HNS wallet ready. Updating balances…"
                case .failure(let error):
                    self.readStatusLabel.text = "Direct HNS wallet setup failed. Unlock and try again."
                    self.showError(error)
                }
                self.refreshState()
                if case .success = outcome {
                    self.startAutomaticHnsSynchronizationIfReady()
                }
            }
        }
    }

    private func startAutomaticHnsSynchronizationIfReady() {
        guard walletAutomaticHnsSyncMayStart(
            viewIsVisible: walletAuthorityRequested && viewIfLoaded?.window != nil,
            walletUnlocked: walletIsUnlocked,
            readsAvailable: synchronizedReadsAvailable,
            operationInFlight: isOperating,
            bitcoinSyncInProgress: bitcoinSyncInProgress
        ) else { return }
        synchronizeWalletReads(resumeAutomaticSync: false, reportFailure: false)
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
        if storageLease == nil {
            // A newly visible controller can overlap the preceding controller's
            // checked native close for a short time. Re-attempt the exact lease
            // and leave the disabled dashboard in its automatic waiting state;
            // presenting a false concurrent-wallet error only interrupts that
            // handoff and can itself trigger more UIKit lifecycle transitions.
            resumeWalletLifecycle()
        }
        guard storageLease != nil else {
            refreshState()
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
                statusLabel.text = "Finishing the previous wallet screen's protected storage handoff."
                accountLabel.text = "Account access will resume automatically when native wallet closure completes."
                setReadAvailability(false, message: "Read-only synchronization is waiting for protected wallet storage handoff.")
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
            statusLabel.text = WalletCopy.text("wallet_status_recovery_required")
            accountLabel.text = "Account: locked until recovery confirmation is complete."
            setReadAvailability(false, message: "Read-only synchronization begins only after recovery confirmation.")
            refreshButtonStates()
            return
        }
        guard let wallet else {
            openButton.configuration?.title = WalletCopy.text("row_wallet_unlock")
            statusLabel.text = persistentWalletExists
                ? WalletCopy.text("wallet_status_locked")
                : currentAuthenticatedNewWalletBirthdayHeight().map {
                    WalletCopy.format("wallet_status_ready_to_create",
                        Int($0)
                    )
                } ?? WalletCopy.text("wallet_status_waiting_for_initial_sync")
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
                ? WalletCopy.text("wallet_status_locked")
                : "Unlocked Shakescape wallet."
            openButton.configuration?.title = WalletCopy.text("row_wallet_unlock")
            walletIsUnlocked = !status.locked
            directHnsValueAvailable = hasHnsValue && !status.locked
            bitcoinValueAvailable = hasBitcoinValue && !status.locked
            shakedexAvailable = status.shakedexEnabled && !status.locked
            updateDirectShakescapeServiceTimer()
            if status.locked {
                dismissWalletPopupForLock()
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
                    paymentReceiveLabel.text = WalletCopy.format(
                        "wallet_reads_receive",
                        receive.display,
                        Int(receive.derivationIndex)
                    )
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
            deleteButton.configuration?.title = WalletCopy.text("action_stop_wallet_sync")
        case (false, .some(.preparing)) where canStopSynchronization:
            deleteButton.configuration?.title = WalletCopy.text("action_stop_wallet_sync")
        case (false, .some(.live(_))) where canStopSynchronization:
            deleteButton.configuration?.title = WalletCopy.text("action_stop_wallet_sync")
        case (false, .some(.cancelling)):
            deleteButton.configuration?.title = "Stopping synchronization…"
        case (false, .some(.terminal)):
            deleteButton.configuration?.title = "Waiting for wallet protection…"
        default:
            deleteButton.configuration?.title = WalletCopy.text("row_wallet_delete")
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
        directShakescapeServiceTicks = 0
        directShakescapeExecutionTicks = 0
        clearWalletNameImportPrompt(dismiss: true)
        dismissPendingHnsSendApproval(rejectNatively: true)
        dismissPendingHnsValueApproval(rejectNatively: true)
        clearRestoreInput()
        newWalletCreationRequested = false
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
        updateRecoveryCeremonyScreenAwake(false)
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
        try secureWalletStorageDirectory(walletRoot, fileManager: fileManager)
        try secureWalletStorageDirectory(directory, fileManager: fileManager)
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
        guard walletCanPresentChild else { return }
        let alert = UIAlertController(
            title: "Wallet unavailable",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        walletPresentationHost.present(alert, animated: true)
    }
}

/// The native wallet store rejects a final database directory unless it is
/// owned by this process and has exactly owner-only `0700` permissions. iOS
/// file protection and POSIX permissions are independent attributes, so both
/// must be applied and verified before Rust opens or creates SQLite state.
func secureWalletStorageDirectory(
    _ directory: URL,
    fileManager: FileManager = .default
) throws {
    try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
    try fileManager.setAttributes(
        [
            .protectionKey: FileProtectionType.complete,
            .posixPermissions: NSNumber(value: 0o700),
        ],
        ofItemAtPath: directory.path
    )
    let attributes = try fileManager.attributesOfItem(atPath: directory.path)
    guard let permissions = attributes[.posixPermissions] as? NSNumber,
          permissions.uint16Value & 0o777 == 0o700 else {
        throw WalletProviderError(
            code: "walletStoragePermissions",
            message: "The private wallet directory could not be protected for this app."
        )
    }
}

private struct WalletMenuRow {
    let title: String
    let detail: String
    let liveDetail: (@MainActor () -> String)?

    init(
        title: String,
        detail: String,
        liveDetail: (@MainActor () -> String)? = nil
    ) {
        self.title = title
        self.detail = detail
        self.liveDetail = liveDetail
    }
}

private enum WalletMenuActionStyle: Equatable {
    case standard
    case destructive
}

private struct WalletSheetFormField {
    let label: String
    let placeholder: String
    let keyboardType: UIKeyboardType
    let initialValue: String?
    let accessibilityIdentifier: String?
    let configure: (@MainActor (UITextField) -> Void)?

    init(
        label: String,
        placeholder: String,
        keyboardType: UIKeyboardType = .asciiCapable,
        initialValue: String? = nil,
        accessibilityIdentifier: String? = nil,
        configure: (@MainActor (UITextField) -> Void)? = nil
    ) {
        self.label = label
        self.placeholder = placeholder
        self.keyboardType = keyboardType
        self.initialValue = initialValue
        self.accessibilityIdentifier = accessibilityIdentifier
        self.configure = configure
    }
}

private struct WalletSheetSelectionSection {
    let title: String
    let options: [String]
    let emptyMessage: String
}

private struct WalletMenuAction {
    let title: String
    let style: WalletMenuActionStyle
    let section: String?
    let enabled: Bool
    let dismissBeforeAction: Bool?
    let primary: Bool
    let handler: @MainActor () -> Void

    init(
        title: String,
        style: WalletMenuActionStyle = .standard,
        section: String? = nil,
        enabled: Bool = true,
        dismissBeforeAction: Bool? = nil,
        primary: Bool = false,
        handler: @escaping @MainActor () -> Void
    ) {
        self.title = title
        self.style = style
        self.section = section
        self.enabled = enabled
        self.dismissBeforeAction = dismissBeforeAction
        self.primary = primary
        self.handler = handler
    }
}

@MainActor
private final class WalletMenuViewController: UIViewController {
    private let menuTitle: String
    private let rows: [WalletMenuRow]
    private let actions: [WalletMenuAction]
    private let dismissBeforeAction: Bool
    private let liveActions: (@MainActor () -> [WalletMenuAction])?
    private let actionContent = UIStackView()
    private var actionSignature: [String] = []
    private var liveDetails: [(UILabel, @MainActor () -> String)] = []
    private var liveDetailTimer: Timer?

    init(
        title: String,
        rows: [WalletMenuRow],
        actions: [WalletMenuAction],
        dismissBeforeAction: Bool,
        liveActions: (@MainActor () -> [WalletMenuAction])? = nil
    ) {
        menuTitle = title
        self.rows = rows
        self.actions = actions
        self.dismissBeforeAction = dismissBeforeAction
        self.liveActions = liveActions
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        AppAccessibility.configureModal(view)
        view.layer.cornerRadius = 24
        view.layer.cornerCurve = .continuous
        view.layer.borderWidth = 1
        view.layer.borderColor = UIColor.systemIndigo.cgColor
        view.layer.masksToBounds = true

        let scrollView = UIScrollView()
        scrollView.alwaysBounceVertical = true
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)

        let content = UIStackView()
        content.axis = .vertical
        content.spacing = 10
        content.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(content)

        let heading = UILabel()
        heading.text = menuTitle
        heading.font = .preferredFont(forTextStyle: .title2)
        heading.adjustsFontForContentSizeCategory = true
        heading.textColor = .label
        heading.numberOfLines = 0
        heading.accessibilityTraits = .header
        content.addArrangedSubview(heading)
        content.setCustomSpacing(16, after: heading)

        rows.forEach { content.addArrangedSubview(detailCard(for: $0)) }

        actionContent.axis = .vertical
        actionContent.spacing = 8
        content.addArrangedSubview(actionContent)
        refreshActions()
        if !liveDetails.isEmpty || liveActions != nil {
            liveDetailTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) {
                [weak self] _ in
                guard let self else { return }
                self.liveDetails.forEach { label, provider in
                    let text = provider()
                    if label.text != text { label.text = text }
                }
                self.refreshActions()
            }
        }

        let done = actionButton(
            for: WalletMenuAction(title: WalletCopy.text("wallet_ux_done")) { [weak self] in
                self?.dismiss(animated: true)
            },
            emphasized: false,
            dismissBeforeAction: false
        )
        if let previous = content.arrangedSubviews.last {
            content.setCustomSpacing(16, after: previous)
        }
        content.addArrangedSubview(done)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 18),
            content.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -18),
            content.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 18),
            content.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -18),
            content.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -36),
        ])
    }

    deinit {
        liveDetailTimer?.invalidate()
    }

    private func refreshActions() {
        let current = liveActions?() ?? actions
        let signature = current.map {
            "\($0.section ?? "")|\($0.title)|\($0.enabled)|\($0.primary)|\($0.style)|\(String(describing: $0.dismissBeforeAction))"
        }
        guard signature != actionSignature else { return }
        actionSignature = signature
        actionContent.arrangedSubviews.forEach {
            actionContent.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
        var currentSection: String?
        for action in current {
            let section = action.section ?? WalletCopy.text("wallet_ux_actions")
            if section != currentSection {
                actionContent.addArrangedSubview(sectionHeading(section))
                currentSection = section
            }
            actionContent.addArrangedSubview(actionButton(
                for: action, emphasized: action.primary,
                dismissBeforeAction: action.dismissBeforeAction ?? dismissBeforeAction
            ))
        }
    }

    private func detailCard(for row: WalletMenuRow) -> UIView {
        let card = UIStackView()
        card.axis = .vertical
        card.spacing = 7
        card.isLayoutMarginsRelativeArrangement = true
        card.directionalLayoutMargins = NSDirectionalEdgeInsets(
            top: 13,
            leading: 15,
            bottom: 14,
            trailing: 15
        )
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true

        let label = sectionHeading(row.title)
        label.textColor = .systemCyan
        card.addArrangedSubview(label)

        let detail = UILabel()
        detail.text = row.detail
        detail.font = .preferredFont(forTextStyle: .body)
        detail.adjustsFontForContentSizeCategory = true
        detail.textColor = .label
        detail.numberOfLines = 0
        card.addArrangedSubview(detail)
        if let provider = row.liveDetail {
            liveDetails.append((detail, provider))
        }
        return card
    }

    private func sectionHeading(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text.uppercased()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .secondaryLabel
        label.numberOfLines = 0
        label.accessibilityTraits = .header
        return label
    }

    private func actionButton(
        for action: WalletMenuAction,
        emphasized: Bool,
        dismissBeforeAction: Bool = true
    ) -> UIButton {
        var configuration = emphasized && action.style == .standard
            ? UIButton.Configuration.filled()
            : UIButton.Configuration.tinted()
        configuration.title = action.title
        configuration.titleAlignment = .leading
        configuration.cornerStyle = .medium
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: 13,
            leading: 14,
            bottom: 13,
            trailing: 14
        )
        switch action.style {
        case .standard:
            configuration.baseBackgroundColor = emphasized ? .systemCyan : .secondarySystemFill
            configuration.baseForegroundColor = emphasized ? .black : .label
        case .destructive:
            configuration.baseBackgroundColor = .systemRed
            configuration.baseForegroundColor = .systemRed
        }
        let button = UIButton(type: .system)
        button.configuration = configuration
        button.contentHorizontalAlignment = .leading
        button.titleLabel?.numberOfLines = 0
        button.titleLabel?.lineBreakMode = .byWordWrapping
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        button.isEnabled = action.enabled
        button.alpha = action.enabled ? 1 : 0.5
        button.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            if dismissBeforeAction {
                self.dismiss(animated: true, completion: action.handler)
            } else {
                action.handler()
            }
        }, for: .touchUpInside)
        return button
    }
}

@MainActor
private final class WalletFormViewController: UIViewController {
    private let formTitle: String
    private let message: String?
    private let fields: [WalletSheetFormField]
    private let selectionSections: [WalletSheetSelectionSection]
    private let primaryTitle: String
    private let primaryStyle: WalletMenuActionStyle
    private let onSubmit: @MainActor ([String]) -> Void
    private var textFields: [UITextField] = []

    init(
        title: String,
        message: String?,
        fields: [WalletSheetFormField],
        selectionSections: [WalletSheetSelectionSection],
        primaryTitle: String,
        primaryStyle: WalletMenuActionStyle,
        onSubmit: @escaping @MainActor ([String]) -> Void
    ) {
        formTitle = title
        self.message = message
        self.fields = fields
        self.selectionSections = selectionSections
        self.primaryTitle = primaryTitle
        self.primaryStyle = primaryStyle
        self.onSubmit = onSubmit
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        AppAccessibility.configureModal(view)
        view.layer.cornerRadius = 24
        view.layer.cornerCurve = .continuous
        view.layer.borderWidth = 1
        view.layer.borderColor = UIColor.systemIndigo.cgColor
        view.layer.masksToBounds = true

        let scrollView = UIScrollView()
        scrollView.alwaysBounceVertical = true
        scrollView.keyboardDismissMode = .interactive
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)

        let content = UIStackView()
        content.axis = .vertical
        content.spacing = 10
        content.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(content)

        let heading = UILabel()
        heading.text = formTitle
        heading.font = .preferredFont(forTextStyle: .title2)
        heading.adjustsFontForContentSizeCategory = true
        heading.textColor = .label
        heading.numberOfLines = 0
        heading.accessibilityTraits = .header
        content.addArrangedSubview(heading)
        content.setCustomSpacing(16, after: heading)

        if let message, !message.isEmpty {
            let explanation = UILabel()
            explanation.text = message
            explanation.font = .preferredFont(forTextStyle: .body)
            explanation.adjustsFontForContentSizeCategory = true
            explanation.textColor = .label
            explanation.numberOfLines = 0
            content.addArrangedSubview(card(title: "Details", content: explanation))
        }

        for definition in fields {
            let field = UITextField()
            field.placeholder = definition.placeholder
            field.text = definition.initialValue
            field.keyboardType = definition.keyboardType
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.spellCheckingType = .no
            field.textContentType = nil
            field.clearButtonMode = .whileEditing
            field.font = .preferredFont(forTextStyle: .body)
            field.adjustsFontForContentSizeCategory = true
            field.accessibilityIdentifier = definition.accessibilityIdentifier
            definition.configure?(field)
            field.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            textFields.append(field)
            content.addArrangedSubview(card(title: definition.label, content: field))
        }

        for section in selectionSections {
            let choices = UIStackView()
            choices.axis = .vertical
            choices.spacing = 8
            if section.options.isEmpty {
                let empty = UILabel()
                empty.text = section.emptyMessage
                empty.font = .preferredFont(forTextStyle: .body)
                empty.adjustsFontForContentSizeCategory = true
                empty.textColor = .secondaryLabel
                empty.numberOfLines = 0
                choices.addArrangedSubview(empty)
            } else {
                for endpoint in section.options.prefix(maximumVisibleDirectShakescapePeers) {
                    let choice = button(title: endpoint, style: .standard, emphasized: false)
                    choice.addAction(UIAction { [weak self] _ in
                        self?.select(endpoint: endpoint)
                    }, for: .touchUpInside)
                    choices.addArrangedSubview(choice)
                }
            }
            content.addArrangedSubview(card(title: section.title, content: choices))
        }

        let actionHeading = sectionHeading("Actions")
        content.addArrangedSubview(actionHeading)
        content.setCustomSpacing(8, after: actionHeading)

        let submit = button(title: primaryTitle, style: primaryStyle, emphasized: true)
        submit.addAction(UIAction { [weak self] _ in self?.submit() }, for: .touchUpInside)
        content.addArrangedSubview(submit)

        let cancel = button(title: WalletCopy.text("wallet_action_cancel"), style: .standard, emphasized: false)
        cancel.addAction(UIAction { [weak self] _ in self?.cancel() }, for: .touchUpInside)
        content.addArrangedSubview(cancel)

        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor, constant: 18),
            content.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor, constant: -18),
            content.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 18),
            content.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -18),
            content.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -36),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        textFields.first?.becomeFirstResponder()
    }

    private func card(title: String, content: UIView) -> UIView {
        let card = UIStackView(arrangedSubviews: [sectionHeading(title), content])
        card.axis = .vertical
        card.spacing = 7
        card.isLayoutMarginsRelativeArrangement = true
        card.directionalLayoutMargins = NSDirectionalEdgeInsets(
            top: 13,
            leading: 15,
            bottom: 13,
            trailing: 15
        )
        card.backgroundColor = .secondarySystemGroupedBackground
        card.layer.cornerRadius = 16
        card.layer.masksToBounds = true
        return card
    }

    private func sectionHeading(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text.uppercased()
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .systemCyan
        label.numberOfLines = 0
        label.accessibilityTraits = .header
        return label
    }

    private func button(
        title: String,
        style: WalletMenuActionStyle,
        emphasized: Bool
    ) -> UIButton {
        var configuration = emphasized && style == .standard
            ? UIButton.Configuration.filled()
            : UIButton.Configuration.tinted()
        configuration.title = title
        configuration.titleAlignment = .leading
        configuration.cornerStyle = .medium
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: 13,
            leading: 14,
            bottom: 13,
            trailing: 14
        )
        switch style {
        case .standard:
            configuration.baseBackgroundColor = emphasized ? .systemCyan : .secondarySystemFill
            configuration.baseForegroundColor = emphasized ? .black : .label
        case .destructive:
            configuration.baseBackgroundColor = .systemRed
            configuration.baseForegroundColor = .systemRed
        }
        let button = UIButton(type: .system)
        button.configuration = configuration
        button.contentHorizontalAlignment = .leading
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        return button
    }

    private func submit() {
        let values = textFields.map { $0.text ?? "" }
        clearFields()
        dismiss(animated: true) { [onSubmit] in onSubmit(values) }
    }

    private func select(endpoint: String) {
        guard let field = textFields.first else { return }
        field.text = endpoint
        field.sendActions(for: .editingChanged)
    }

    private func cancel() {
        clearFields()
        dismiss(animated: true)
    }

    private func clearFields() {
        textFields.forEach {
            $0.text = nil
            $0.resignFirstResponder()
        }
    }
}

private enum WalletBip39WordListError: Error {
    case unavailable
    case invalid
}

private let walletBip39EnglishWordCount = 2_048

func walletBip39EnglishWords(bundle: Bundle = .main) throws -> [String] {
    guard let url = bundle.url(forResource: "bip39-english", withExtension: "txt"),
          let text = try? String(contentsOf: url, encoding: .utf8) else {
        throw WalletBip39WordListError.unavailable
    }
    let words = text.split(whereSeparator: \.isWhitespace).map(String.init)
    guard words.count == walletBip39EnglishWordCount,
          Set(words).count == walletBip39EnglishWordCount else {
        throw WalletBip39WordListError.invalid
    }
    return words
}

func walletRecoveryWordChoices(
    words: [String],
    correctIndex: Int,
    bip39Words: [String]
) -> [String] {
    precondition(words.indices.contains(correctIndex))
    let correct = words[correctIndex]
    precondition(bip39Words.count == walletBip39EnglishWordCount)
    precondition(Set(bip39Words).count == walletBip39EnglishWordCount)
    precondition(bip39Words.contains(correct))
    var pool = bip39Words.filter { $0 != correct }
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

    init(localPaymentAddress: String) {
        paymentAddress = localPaymentAddress
    }

    init(snapshot: NativeHnsReadSnapshot) {
        paymentAddress = snapshot.receiveTarget.display
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
            text = WalletCopy.text("wallet_reads_history_empty")
        } else {
            let entries = page.map { transaction in
                let chainPosition = transaction.blockHeight.map {
                    WalletCopy.format(
                        "wallet_reads_transaction_confirmed",
                        Int($0),
                        Int(transaction.confirmationCount)
                    )
                } ?? WalletCopy.text("wallet_reads_transaction_unconfirmed")
                return WalletCopy.format(
                    "wallet_reads_transaction",
                    transactionStatusLabel(transaction.status),
                    displayAmount(transaction),
                    lowerHex(transaction.txid),
                    chainPosition
                )
            }.joined(separator: "\n\n")
            text = WalletCopy.format(
                "wallet_activity_page_position",
                offset + 1,
                offset + page.count,
                transactions.count
            ) + "\n\n" + entries
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
            history = WalletCopy.text("wallet_reads_history_empty")
        } else {
            let entries = transactions.map { transaction in
                let chainPosition = transaction.blockHeight.map {
                    WalletCopy.format(
                        "wallet_reads_transaction_confirmed",
                        Int($0),
                        Int(transaction.confirmationCount)
                    )
                } ?? WalletCopy.text("wallet_reads_transaction_unconfirmed")
                return WalletCopy.format(
                    "wallet_reads_transaction",
                    transactionStatusLabel(transaction.status),
                    displayAmount(transaction),
                    lowerHex(transaction.txid),
                    chainPosition
                )
            }.joined(separator: "\n\n")
            history = appendRemainingCount(
                entries,
                remaining: snapshot.transactionHistory.count - transactions.count
            )
        }

        let trackedNames: String
        if names.isEmpty {
            trackedNames = WalletCopy.text("wallet_reads_names_empty")
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
            balanceText = WalletCopy.format(
                "wallet_reads_balance_confirmed_with_pending",
                formatHnsBaseUnits(balance.spendableBaseUnits),
                formatHnsBaseUnits(balance.pendingOutgoingBaseUnits)
            )
        } else {
            balanceText = WalletCopy.format("wallet_reads_balance_confirmed",
                formatHnsBaseUnits(balance.spendableBaseUnits)
            )
        }
        return WalletReadPresentation(
            status: WalletCopy.format("wallet_reads_ready",
                Int(snapshot.moduleStatus.validatedHeight)
            ),
            balance: balanceText,
            paymentReceive: WalletCopy.format(
                "wallet_reads_receive",
                snapshot.receiveTarget.display,
                Int(snapshot.receiveTarget.derivationIndex)
            ),
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
            states.append(WalletCopy.text(
                registered
                    ? "wallet_reads_name_registered"
                    : "wallet_reads_name_not_registered"
            ))
        }
        return WalletCopy.format(
            "wallet_reads_name",
            displayHandshakeNameText(name.name),
            Int(name.proofHeight),
            states.joined(separator: " · "),
            name.nameHash
        )
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
        return entries + "\n\n" + WalletCopy.format("wallet_reads_more", remaining)
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
    let editable: Bool

    init(
        label: String,
        placeholder: String,
        numeric: Bool = false,
        initialValue: String? = nil,
        editable: Bool = true
    ) {
        self.label = label
        self.placeholder = placeholder
        self.numeric = numeric
        self.initialValue = initialValue
        self.editable = editable
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

func isCanonicalHandshakeNameText(_ name: String) -> Bool {
    canonicalHandshakeNameImportText(name) == name
}

/// ASCII spaces are deliberately the only separators. Pasted commas, tabs,
/// and line breaks are rejected instead of silently changing the request.
func parseSpaceSeparatedWalletNames(_ text: String) -> [String]? {
    guard !text.isEmpty,
          text.count <= maximumMultipleWalletNameInputCharacters,
          !text.contains(where: { $0.isWhitespace && $0 != " " }) else {
        return nil
    }
    let enteredNames = text.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
    guard !enteredNames.isEmpty,
          enteredNames.count <= maximumMultipleWalletNameImports else {
        return nil
    }
    let names = enteredNames.compactMap(canonicalHandshakeNameImportText)
    guard names.count == enteredNames.count, Set(names).count == names.count else { return nil }
    return names
}

@MainActor
func configureWalletNameImportTextField(_ field: UITextField) {
    field.placeholder = "exact-name"
    field.keyboardType = .default
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
        title = WalletCopy.text("row_wallet_set_records")
        view.backgroundColor = .systemGroupedBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: WalletCopy.text("wallet_action_review_transaction"),
            style: .done,
            target: self,
            action: #selector(review)
        )

        configure(
            nameField,
            placeholder: WalletCopy.text("wallet_action_name_hint"),
            keyboard: .asciiCapable
        )
        nameField.accessibilityIdentifier = "wallet.name-records.name"
        configure(
            feeField,
            placeholder: WalletCopy.text("wallet_action_maximum_fee_hint"),
            keyboard: .decimalPad
        )
        feeField.text = defaultHnsMaximumFee
        feeField.accessibilityIdentifier = "wallet.name-records.maximum-fee"

        recordsView.delegate = self
        recordsView.font = AppAccessibility.scaledMonospacedFont(
            size: 14,
            weight: .regular,
            textStyle: .body
        )
        recordsView.adjustsFontForContentSizeCategory = true
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
        recordsView.accessibilityLabel = WalletCopy.text(
            "wallet_action_resource_records_hint"
        )

        let instructions = UILabel()
        instructions.numberOfLines = 0
        instructions.font = .preferredFont(forTextStyle: .footnote)
        instructions.textColor = .secondaryLabel
        instructions.text = WalletCopy.text("wallet_action_resource_records_hint")

        characterCountLabel.font = .preferredFont(forTextStyle: .caption1)
        characterCountLabel.textColor = .secondaryLabel
        characterCountLabel.textAlignment = .right
        updateCharacterCount()

        let stack = UIStackView(arrangedSubviews: [
            fieldLabel(WalletCopy.text("wallet_action_name_hint")), nameField,
            fieldLabel(WalletCopy.text("wallet_action_resource_records_hint")),
            instructions, recordsView, characterCountLabel,
            fieldLabel(WalletCopy.text("wallet_action_maximum_fee_hint")), feeField,
        ])
        stack.axis = .vertical
        stack.spacing = 10
        stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(
            top: 16,
            leading: 16,
            bottom: 16,
            trailing: 16
        )
        stack.backgroundColor = .secondarySystemGroupedBackground
        stack.layer.cornerRadius = 16
        stack.layer.masksToBounds = true
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
                title: WalletCopy.text("row_wallet_set_records"),
                message: WalletCopy.text("wallet_value_actions_invalid"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: WalletCopy.text("wallet_action_ok"),
                style: .default
            ))
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
        title = WalletCopy.text("action_import_multiple_wallet_names")
        view.backgroundColor = .systemGroupedBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: WalletCopy.text("action_review_wallet_names"),
            style: .done,
            target: self,
            action: #selector(review)
        )

        let instructions = UILabel()
        instructions.numberOfLines = 0
        instructions.font = .preferredFont(forTextStyle: .body)
        instructions.text = WalletCopy.text("wallet_name_multiple_import_hint")

        namesView.delegate = self
        namesView.font = AppAccessibility.scaledMonospacedFont(
            size: 15,
            weight: .regular,
            textStyle: .body
        )
        namesView.adjustsFontForContentSizeCategory = true
        namesView.autocapitalizationType = .none
        namesView.autocorrectionType = .no
        namesView.spellCheckingType = .no
        namesView.smartDashesType = .no
        namesView.smartQuotesType = .no
        namesView.smartInsertDeleteType = .no
        namesView.keyboardType = .default
        namesView.layer.borderColor = UIColor.separator.cgColor
        namesView.layer.borderWidth = 1
        namesView.layer.cornerRadius = 8
        namesView.accessibilityIdentifier = "wallet.import-multiple-hns-names.text"
        namesView.accessibilityLabel = WalletCopy.text("wallet_name_multiple_import_hint")

        characterCountLabel.font = .preferredFont(forTextStyle: .caption1)
        characterCountLabel.textColor = .secondaryLabel
        characterCountLabel.textAlignment = .right
        updateCharacterCount()

        let stack = UIStackView(arrangedSubviews: [instructions, namesView, characterCountLabel])
        stack.axis = .vertical
        stack.spacing = 12
        stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(
            top: 16,
            leading: 16,
            bottom: 16,
            trailing: 16
        )
        stack.backgroundColor = .secondarySystemGroupedBackground
        stack.layer.cornerRadius = 16
        stack.layer.masksToBounds = true
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
                title: WalletCopy.text("wallet_name_multiple_import_review_title"),
                message: WalletCopy.text("wallet_name_multiple_import_invalid"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: WalletCopy.text("wallet_action_ok"),
                style: .default
            ))
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
        title = WalletCopy.text("wallet_name_multiple_import_review_title")
        view.backgroundColor = .systemGroupedBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: WalletCopy.text("action_import_multiple_wallet_names"),
            style: .done,
            target: self,
            action: #selector(confirmImport)
        )

        let summary = UILabel()
        summary.numberOfLines = 0
        summary.font = .preferredFont(forTextStyle: .body)
        summary.text = WalletCopy.format(
            "wallet_name_multiple_import_review_message",
            names.count
        )

        let list = UITextView()
        list.isEditable = false
        list.isSelectable = true
        list.font = AppAccessibility.scaledMonospacedFont(
            size: 14,
            weight: .regular,
            textStyle: .body
        )
        list.adjustsFontForContentSizeCategory = true
        list.text = names.enumerated().map {
            "\($0.offset + 1). \(displayHandshakeNameText($0.element))"
        }.joined(separator: "\n")
        list.layer.borderColor = UIColor.separator.cgColor
        list.layer.borderWidth = 1
        list.layer.cornerRadius = 8
        list.accessibilityIdentifier = "wallet.import-multiple-hns-names.review"
        list.accessibilityLabel = WalletCopy.text(
            "wallet_name_multiple_import_review_list"
        )

        let stack = UIStackView(arrangedSubviews: [summary, list])
        stack.axis = .vertical
        stack.spacing = 12
        stack.isLayoutMarginsRelativeArrangement = true
        stack.directionalLayoutMargins = NSDirectionalEdgeInsets(
            top: 16,
            leading: 16,
            bottom: 16,
            trailing: 16
        )
        stack.backgroundColor = .secondarySystemGroupedBackground
        stack.layer.cornerRadius = 16
        stack.layer.masksToBounds = true
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
        AppAccessibility.configureModal(view)
        image = Self.qrImage("handshake:\(address)")
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.accessibilityLabel = WalletCopy.text("wallet_receive_qr_description")
        imageView.accessibilityIgnoresInvertColors = true
        let addressLabel = UILabel()
        addressLabel.text = address
        addressLabel.font = AppAccessibility.scaledMonospacedFont(
            size: 15,
            weight: .regular,
            textStyle: .body
        )
        addressLabel.adjustsFontForContentSizeCategory = true
        addressLabel.numberOfLines = 0
        addressLabel.textAlignment = .center
        let copy = button(WalletCopy.text("wallet_action_copy"), #selector(copyAddress))
        let share = button("Save or share QR code", #selector(shareQr))
        let done = button(WalletCopy.text("wallet_action_done"), #selector(done))
        let stack = UIStackView(arrangedSubviews: [imageView, addressLabel, copy, share, done])
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
        AppAccessibility.configureModal(view)
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
