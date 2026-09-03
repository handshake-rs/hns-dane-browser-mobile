import Foundation
import WebKit

@MainActor
final class BrowserProcess {
    @MainActor
    final class Environment {
        let runtime: BrowserRuntime
        let profile: PersistentWebKitProfile
        private var processProxyCoordinator: BrowserProxyCoordinator?

        init(runtime: BrowserRuntime) {
            self.runtime = runtime
            profile = PersistentWebKitProfile()
        }

        func proxyCoordinator() -> BrowserProxyCoordinator {
            if let processProxyCoordinator {
                return processProxyCoordinator
            }
            let coordinator = BrowserProxyCoordinator(runtime: runtime, profile: profile)
            processProxyCoordinator = coordinator
            return coordinator
        }

        func revokeProxyCoordinator() {
            processProxyCoordinator?.destroy()
            processProxyCoordinator = nil
        }
    }

    private enum State {
        case idle
        case preparing([((Result<Environment, Error>) -> Void)])
        case ready(Environment)
        case switching(Environment)
        case failed(Error)
        case closed
    }

    private let preparationQueue = DispatchQueue(
        label: "com.denuoweb.hnsdane.ios.runtime-preparation",
        qos: .userInitiated
    )
    private let runtimeFactory: (String, BrowserHandshakeNetwork) throws -> BrowserRuntime
    private let bootstrapper: HeaderSnapshotBootstrapper
    private let policyStore: BrowserRuntimePolicyStore
    private let syncSchedulingPolicy: BrowserSyncSchedulingPolicy
    private let persistNetwork: (BrowserHandshakeNetwork) -> Void
    private var state: State = .idle
    private(set) var currentPolicy: BrowserRuntimePolicy
    private var isForegroundSyncEnabled = false
    private var syncObserver: ((BrowserSyncSummary) -> Void)?
    private var additionalSyncObservers: [UUID: (BrowserSyncSummary) -> Void] = [:]
    // This is deliberately presentation-only. It lets the UI acknowledge that
    // foreground work has been scheduled before the Rust runtime publishes its
    // authoritative `syncInFlight` status; it must never affect proxy admission.
    private var syncPreparationObserver: (() -> Void)?
    private var syncWorkItem: DispatchWorkItem?
    private var syncScheduleGeneration: UInt64 = 0
    private var syncInFlight = false
    private var syncCompletions: [((Result<BrowserSyncSummary, Error>) -> Void)] = []
    private var syncMaintenanceSafePointCallbacks: [(() -> Void)] = []
    private var consecutiveSyncFailures = 0
    private var networkSwitchInFlight = false
    private var networkSwitchGeneration: UInt64 = 0
    private(set) var currentNetwork: BrowserHandshakeNetwork

    init(
        runtimeFactory: @escaping (String, BrowserHandshakeNetwork) throws -> BrowserRuntime = {
            path, network in
            try RustBrowserRuntime(path, network: network)
        },
        bootstrapper: HeaderSnapshotBootstrapper = HeaderSnapshotBootstrapper(),
        policyStore: BrowserRuntimePolicyStore = BrowserRuntimePolicyStore(),
        syncSchedulingPolicy: BrowserSyncSchedulingPolicy = BrowserSyncSchedulingPolicy(),
        initialNetwork: BrowserHandshakeNetwork = BrowserSettingsPreferences.handshakeNetwork,
        persistNetwork: @escaping (BrowserHandshakeNetwork) -> Void =
            BrowserSettingsPreferences.saveHandshakeNetwork
    ) {
        self.runtimeFactory = runtimeFactory
        self.bootstrapper = bootstrapper
        self.policyStore = policyStore
        self.syncSchedulingPolicy = syncSchedulingPolicy
        self.persistNetwork = persistNetwork
        currentPolicy = policyStore.load()
        currentNetwork = initialNetwork
    }

    func prepare(completion: @escaping (Result<Environment, Error>) -> Void) {
        switch state {
        case .ready(let environment):
            completion(.success(environment))
            return
        case .failed:
            // A first-run bundle/I/O failure remains fail-closed, but a user-initiated retry may
            // recover from transient protected-storage or resource pressure failures.
            state = .preparing([completion])
        case .closed:
            completion(.failure(BrowserCoreError.runtimeUnavailable("process is closed")))
            return
        case .preparing(var callbacks):
            callbacks.append(completion)
            state = .preparing(callbacks)
            return
        case .switching:
            completion(.failure(BrowserCoreError.runtimeUnavailable(
                "a Handshake network change is in progress"
            )))
            return
        case .idle:
            state = .preparing([completion])
        }

        do {
            let network = currentNetwork
            let dataDirectory = try Self.makeDataDirectory(network: network)
            let runtimeFactory = runtimeFactory
            let bootstrapper = bootstrapper
            let policy = currentPolicy
            preparationQueue.async { [weak self] in
                let result: Result<BrowserRuntime, Error>
                do {
                    let runtime = try runtimeFactory(dataDirectory.path, network)
                    do {
                        try runtime.updatePolicy(policy)
                        if network == .mainnet {
                            try bootstrapper.installIfNeeded(into: runtime)
                        }
                        result = .success(runtime)
                    } catch {
                        runtime.close()
                        throw error
                    }
                } catch {
                    result = .failure(error)
                }

                DispatchQueue.main.async {
                    self?.finishPreparation(result)
                }
            }
        } catch {
            finishPreparation(.failure(error))
        }
    }

    /// The runtime is internally synchronized and may export a read-only
    /// canonical header stream while the wallet prepares off the main thread.
    func preparedRuntimeForWalletBootstrap() -> BrowserRuntime? {
        guard case .ready(let environment) = state else { return nil }
        return environment.runtime
    }

    func updatePolicy(
        _ policy: BrowserRuntimePolicy,
        completion: @escaping (Result<UInt64, Error>) -> Void
    ) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        preparationQueue.async { [weak self] in
            let result = Result { try environment.runtime.updatePolicy(policy) }
            DispatchQueue.main.async {
                guard let self else { return }
                if case .success = result {
                    self.currentPolicy = policy
                    self.policyStore.save(policy)
                }
                completion(result)
            }
        }
    }

    func switchNetwork(
        to network: BrowserHandshakeNetwork,
        completion: @escaping (Result<Environment, Error>) -> Void
    ) {
        guard !networkSwitchInFlight else {
            completion(.failure(BrowserCoreError.runtimeUnavailable(
                "a network change is already running"
            )))
            return
        }
        guard network != currentNetwork else {
            if case .ready(let environment) = state {
                completion(.success(environment))
            } else {
                prepare(completion: completion)
            }
            return
        }
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }

        let dataDirectory: URL
        do {
            dataDirectory = try Self.makeDataDirectory(network: network)
        } catch {
            completion(.failure(error))
            return
        }

        suspendForegroundSync()
        state = .switching(environment)
        networkSwitchInFlight = true
        networkSwitchGeneration &+= 1
        let generation = networkSwitchGeneration
        let runtimeFactory = runtimeFactory
        let bootstrapper = bootstrapper
        let policy = currentPolicy
        preparationQueue.async { [weak self] in
            let result: Result<BrowserRuntime, Error>
            do {
                let runtime = try runtimeFactory(dataDirectory.path, network)
                do {
                    try runtime.updatePolicy(policy)
                    if network == .mainnet {
                        try bootstrapper.installIfNeeded(into: runtime)
                    }
                    result = .success(runtime)
                } catch {
                    runtime.close()
                    throw error
                }
            } catch {
                result = .failure(error)
            }
            DispatchQueue.main.async {
                self?.finishNetworkSwitch(
                    result,
                    from: environment,
                    to: network,
                    generation: generation,
                    completion: completion
                )
            }
        }
    }

    func syncNow(completion: @escaping (Result<BrowserSyncSummary, Error>) -> Void) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        syncWorkItem?.cancel()
        syncWorkItem = nil
        syncScheduleGeneration &+= 1
        syncCompletions.append(completion)
        startSyncIfNeeded(environment: environment)
    }

    /// Runs work only after the currently executing `syncOnce()` has returned and released
    /// runtime header maintenance. This does not wait for a future scheduled sync.
    @discardableResult
    func performAtSyncMaintenanceSafePoint(_ callback: @escaping () -> Void) -> Bool {
        if case .closed = state { return false }
        guard syncInFlight else {
            callback()
            return true
        }
        syncMaintenanceSafePointCallbacks.append(callback)
        return true
    }

    func addStaticRelayPeer(
        _ endpoint: String,
        completion: @escaping (Result<BrowserSyncSummary, Error>) -> Void
    ) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        preparationQueue.async {
            let result = Result { try environment.runtime.addStaticRelayPeer(endpoint) }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    func clearResolverCache(
        completion: @escaping (Result<BrowserSyncSummary, Error>) -> Void
    ) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        preparationQueue.async {
            let result = Result { try environment.runtime.clearResolverCache() }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    func resetHeadersFromPeers(
        completion: @escaping (Result<BrowserSyncSummary, Error>) -> Void
    ) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        preparationQueue.async {
            let result = Result { try environment.runtime.resetHeadersFromPeers() }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    func proofDetails(
        for hostOrURL: String,
        completion: @escaping (Result<BrowserProofDetails, Error>) -> Void
    ) {
        guard case .ready(let environment) = state else {
            completion(.failure(runtimeUnavailableError()))
            return
        }
        preparationQueue.async {
            let result = Result { try environment.runtime.proofDetails(for: hostOrURL) }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    func resumeForegroundSync(
        observer: @escaping (BrowserSyncSummary) -> Void,
        onSyncStarting: @escaping () -> Void = {}
    ) {
        isForegroundSyncEnabled = true
        syncObserver = observer
        syncPreparationObserver = onSyncStarting
        scheduleForegroundSync(after: 0)
    }

    func suspendForegroundSync() {
        isForegroundSyncEnabled = false
        syncObserver = nil
        syncPreparationObserver = nil
        syncWorkItem?.cancel()
        syncWorkItem = nil
        syncScheduleGeneration &+= 1
    }

    /// Adds a process-owned, presentation-only observer without replacing the
    /// browser screen's primary synchronization observer.
    func observeSync(_ observer: @escaping (BrowserSyncSummary) -> Void) -> UUID {
        let token = UUID()
        additionalSyncObservers[token] = observer
        return token
    }

    func removeSyncObserver(_ token: UUID) {
        additionalSyncObservers.removeValue(forKey: token)
    }

    func close() {
        suspendForegroundSync()
        additionalSyncObservers.removeAll()
        networkSwitchInFlight = false
        networkSwitchGeneration &+= 1
        drainSyncMaintenanceSafePoints(runCallbacks: false)
        let pendingSyncCompletions = syncCompletions
        syncCompletions.removeAll()
        let closedError = BrowserCoreError.runtimeUnavailable("process is closed")
        pendingSyncCompletions.forEach { $0(.failure(closedError)) }
        let closingEnvironment: Environment?
        switch state {
        case .ready(let activeEnvironment), .switching(let activeEnvironment):
            environmentWillClose(activeEnvironment)
            closingEnvironment = activeEnvironment
        default:
            closingEnvironment = nil
        }
        state = .closed
        if let runtime = closingEnvironment?.runtime {
            preparationQueue.async {
                runtime.close()
            }
        }
    }

    private func finishPreparation(_ result: Result<BrowserRuntime, Error>) {
        guard case .preparing(let callbacks) = state else {
            if case .success(let runtime) = result {
                preparationQueue.async { runtime.close() }
            }
            return
        }

        switch result {
        case .success(let runtime):
            let environment = Environment(runtime: runtime)
            state = .ready(environment)
            callbacks.forEach { $0(.success(environment)) }
            // Runtime preparation remains independent from network synchronization. The view
            // controller keeps proxy admission suspended until this foreground scheduler reports
            // the exact header-currentness prerequisite enforced by Rust.
            if isForegroundSyncEnabled {
                scheduleForegroundSync(after: 0)
            }
        case .failure(let error):
            state = .failed(error)
            callbacks.forEach { $0(.failure(error)) }
        }
    }

    private func finishNetworkSwitch(
        _ result: Result<BrowserRuntime, Error>,
        from previousEnvironment: Environment,
        to network: BrowserHandshakeNetwork,
        generation: UInt64,
        completion: @escaping (Result<Environment, Error>) -> Void
    ) {
        guard networkSwitchInFlight,
              generation == networkSwitchGeneration,
              case .switching(let activeEnvironment) = state,
              activeEnvironment.runtime === previousEnvironment.runtime else {
            if case .success(let runtime) = result {
                preparationQueue.async { runtime.close() }
            }
            completion(.failure(runtimeUnavailableError()))
            return
        }

        networkSwitchInFlight = false
        switch result {
        case .success(let runtime):
            previousEnvironment.revokeProxyCoordinator()
            let environment = Environment(runtime: runtime)
            state = .ready(environment)
            currentNetwork = network
            persistNetwork(network)
            preparationQueue.async {
                previousEnvironment.runtime.close()
            }
            if isForegroundSyncEnabled {
                scheduleForegroundSync(after: 0)
            }
            completion(.success(environment))
        case .failure(let error):
            state = .ready(previousEnvironment)
            if isForegroundSyncEnabled {
                scheduleForegroundSync(after: 0)
            }
            completion(.failure(error))
        }
    }

    private func environmentWillClose(_ environment: Environment) {
        environment.revokeProxyCoordinator()
    }

    private func startSyncIfNeeded(environment: Environment) {
        guard !syncInFlight else { return }
        syncInFlight = true
        if isForegroundSyncEnabled {
            syncPreparationObserver?()
        }
        preparationQueue.async { [weak self] in
            let result = Result { try environment.runtime.syncOnce() }
            DispatchQueue.main.async {
                self?.finishSync(result)
            }
        }
    }

    private func finishSync(_ result: Result<BrowserSyncSummary, Error>) {
        syncInFlight = false
        drainSyncMaintenanceSafePoints(runCallbacks: true)
        let callbacks = syncCompletions
        syncCompletions.removeAll()

        let summary: BrowserSyncSummary
        switch result {
        case .success(let value):
            summary = value
            consecutiveSyncFailures = value.requiresRetry ? consecutiveSyncFailures + 1 : 0
        case .failure(let error):
            summary = .failure(error)
            consecutiveSyncFailures += 1
        }
        if isForegroundSyncEnabled {
            syncObserver?(summary)
            Array(additionalSyncObservers.values).forEach { $0(summary) }
            let delay = syncSchedulingPolicy.delay(
                after: summary,
                consecutiveFailures: consecutiveSyncFailures
            )
            scheduleForegroundSync(after: delay)
        }
        callbacks.forEach { $0(result) }
    }

    private func drainSyncMaintenanceSafePoints(runCallbacks: Bool) {
        let callbacks = syncMaintenanceSafePointCallbacks
        syncMaintenanceSafePointCallbacks.removeAll()
        guard runCallbacks else { return }
        for callback in callbacks {
            if case .closed = state { return }
            callback()
        }
    }

    private func scheduleForegroundSync(after delay: TimeInterval) {
        syncWorkItem?.cancel()
        syncWorkItem = nil
        syncScheduleGeneration &+= 1
        guard isForegroundSyncEnabled, case .ready = state else { return }
        let generation = syncScheduleGeneration

        let workItem = DispatchWorkItem { [weak self] in
            DispatchQueue.main.async {
                self?.runScheduledForegroundSync(generation: generation)
            }
        }
        syncWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    private func runScheduledForegroundSync(generation: UInt64) {
        guard generation == syncScheduleGeneration,
              isForegroundSyncEnabled,
              case .ready(let environment) = state else { return }
        syncWorkItem = nil
        startSyncIfNeeded(environment: environment)
    }

    private func runtimeUnavailableError() -> BrowserCoreError {
        let detail: String
        switch state {
        case .idle: detail = "process is not prepared"
        case .preparing: detail = "process is still preparing"
        case .ready: detail = "runtime environment is unavailable"
        case .switching: detail = "a Handshake network change is in progress"
        case .failed(let error): detail = error.localizedDescription
        case .closed: detail = "process is closed"
        }
        return .runtimeUnavailable(detail)
    }

    private static func makeDataDirectory(network: BrowserHandshakeNetwork) throws -> URL {
        let fileManager = FileManager.default
        guard let applicationSupport = fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw BrowserCoreError.runtimeUnavailable("Application Support is unavailable")
        }
        var directory = applicationSupport
            .appendingPathComponent("HnsDaneBrowser", isDirectory: true)
        if network != .mainnet {
            directory.appendPathComponent(network.rawValue, isDirectory: true)
        }
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory
    }
}
