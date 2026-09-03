import Foundation
import CoreFoundation
import HnsBrowserRuntime

private enum RustBridgeError: LocalizedError {
    case incompatibleABI(actual: UInt32)
    case callFailed(operation: String, code: UInt32, detail: String)
    case invalidOutput(String)

    var errorDescription: String? {
        switch self {
        case .incompatibleABI(let actual):
            return "Rust ABI version \(actual) is incompatible with this app."
        case .callFailed(let operation, let code, let detail):
            return "\(operation) failed (\(code)): \(detail)"
        case .invalidOutput(let detail):
            return "The Rust bridge returned invalid output: \(detail)"
        }
    }
}

final class RustBrowserRuntime: BrowserRuntime {
    private let handleLock = NSLock()
    private var runtimeHandle: HnsBrowserRuntimeHandle

    init(
        _ dataDirectory: String,
        network: BrowserHandshakeNetwork = .mainnet
    ) throws {
        let actualABI = hns_browser_abi_version()
        guard actualABI == HNS_BROWSER_ABI_VERSION else {
            throw RustBridgeError.incompatibleABI(actual: actualABI)
        }

        var options = HnsBrowserRuntimeOptions()
        try RustBridge.check(
            hns_browser_runtime_options_default(&options),
            operation: "runtime options"
        )
        var handle: HnsBrowserRuntimeHandle = 0
        let result = RustBridge.withUTF8Slice(dataDirectory) { dataDirectorySlice in
            options.data_dir = dataDirectorySlice
            switch network {
            case .mainnet:
                options.network = HNS_BROWSER_NETWORK_MAINNET
            case .testnet:
                options.network = HNS_BROWSER_NETWORK_TESTNET
            case .regtest:
                options.network = HNS_BROWSER_NETWORK_REGTEST
            }
            options.resolution_mode = HNS_BROWSER_RESOLUTION_STRICT
            return hns_browser_runtime_create(&options, &handle)
        }
        try RustBridge.check(result, operation: "runtime create")
        guard handle != 0 else {
            throw RustBridgeError.invalidOutput("runtime handle is zero")
        }
        runtimeHandle = handle
    }

    static func diagnosticsJSON() throws -> String {
        var output = HnsBrowserBuffer()
        let result = hns_browser_diagnostics_json(&output)
        defer { RustBridge.free(output) }
        try RustBridge.check(result, operation: "native diagnostics")
        return try RustBridge.string(copying: output)
    }

    func classifyNavigation(_ rawValue: String) throws -> BrowserDestination {
        try BrowserNavigationParser(
            canonicalizeHost: rustCanonicalHost
        ).parse(rawValue)
    }

    func classifyHost(_ host: String) -> BrowserHostKind {
        guard let canonical = try? rustCanonicalHost(host) else { return .search }
        return (try? classifyName(canonical)) ?? .search
    }

    func canonicalHost(_ host: String) -> String? {
        try? rustCanonicalHost(host)
    }

    func startWholeWebKitProxy(hnsScopeRoot: String?) throws -> BrowserProxySession {
        let handle = try liveHandle()
        var proxyHandle: HnsBrowserProxyHandle = 0
        let result: HnsBrowserResult
        if let hnsScopeRoot {
            result = RustBridge.withUTF8Slice(hnsScopeRoot) { scope in
                hns_browser_proxy_start(handle, scope, &proxyHandle)
            }
        } else {
            result = hns_browser_proxy_start(
                handle,
                HnsBrowserSlice(ptr: nil, len: 0),
                &proxyHandle
            )
        }
        try RustBridge.check(result, operation: "proxy start")
        guard proxyHandle != 0 else {
            throw RustBridgeError.invalidOutput("proxy handle is zero")
        }

        do {
            return try RustBrowserProxySession(handle: proxyHandle)
        } catch {
            _ = hns_browser_proxy_request_stop(proxyHandle)
            _ = hns_browser_proxy_destroy(proxyHandle)
            throw error
        }
    }

    func installHeaderSnapshot(at path: String) throws {
        let handle = try liveHandle()
        var output = HnsBrowserBuffer()
        let result = RustBridge.withUTF8Slice(path) { pathSlice in
            hns_browser_runtime_install_header_snapshot(handle, pathSlice, &output)
        }
        defer { RustBridge.free(output) }
        try RustBridge.check(result, operation: "header snapshot install")
        _ = try RustBridge.data(copying: output)
    }

    func exportWalletHeaderSnapshot(at path: String, targetHeight: UInt32) throws {
        let handle = try liveHandle()
        let result = RustBridge.withUTF8Slice(path) { pathSlice in
            hns_browser_runtime_export_wallet_header_snapshot(handle, pathSlice, targetHeight)
        }
        try RustBridge.check(result, operation: "wallet header snapshot export")
    }

    @discardableResult
    func updatePolicy(_ policy: BrowserRuntimePolicy) throws -> UInt64 {
        let handle = try liveHandle()
        var nativePolicy = HnsBrowserPolicy()
        try RustBridge.check(
            hns_browser_policy_default(&nativePolicy),
            operation: "policy defaults"
        )
        nativePolicy.resolution_mode = HNS_BROWSER_RESOLUTION_STRICT
        nativePolicy.stateless_dane_certificates = policy.statelessDANECertificates ? 1 : 0
        nativePolicy.experimental_p2p_dns_relay = policy.experimentalP2PDNSRelay ? 1 : 0
        nativePolicy.legacy_hns_doh_compatibility = 0

        var revision: UInt64 = 0
        let result = RustBridge.withUTF8Slice(policy.hnsDohResolver ?? "") { resolver in
            nativePolicy.hns_doh_resolver = resolver
            return hns_browser_runtime_set_policy(handle, &nativePolicy, &revision)
        }
        try RustBridge.check(result, operation: "runtime policy update")
        return revision
    }

    func syncOnce() throws -> BrowserSyncSummary {
        let object = try runtimeJSONObject(operation: "header sync") { handle, output in
            hns_browser_runtime_sync_once(handle, output)
        }
        return try Self.syncSummary(from: object)
    }

    func syncSummary() -> BrowserSyncSummary {
        guard let object = try? runtimeJSONObject(operation: "sync status", invoke: {
            handle, output in
            hns_browser_runtime_sync_status(handle, output)
        }) else { return .unavailable }
        return (try? Self.syncSummary(from: object)) ?? .unavailable
    }

    func addStaticRelayPeer(_ endpoint: String) throws -> BrowserSyncSummary {
        let object = try RustBridge.withUTF8Slice(endpoint) { input in
            try runtimeJSONObject(operation: "static relay peer") { handle, output in
                hns_browser_runtime_add_static_relay_peer(handle, input, output)
            }
        }
        return try Self.syncSummary(from: object)
    }

    func clearResolverCache() throws -> BrowserSyncSummary {
        let object = try runtimeJSONObject(operation: "resolver cache clear") { handle, output in
            hns_browser_runtime_clear_resolver_cache(handle, output)
        }
        return try Self.syncSummary(from: object)
    }

    func resetHeadersFromPeers() throws -> BrowserSyncSummary {
        let object = try runtimeJSONObject(operation: "header reset") { handle, output in
            hns_browser_runtime_reset_headers_from_peers(handle, output)
        }
        return try Self.syncSummary(from: object)
    }

    func proofDetails(for hostOrURL: String) throws -> BrowserProofDetails {
        let object = try RustBridge.withUTF8Slice(hostOrURL) { input in
            try runtimeJSONObject(operation: "proof details") { handle, output in
                hns_browser_runtime_proof_details(handle, input, output)
            }
        }
        return try Self.proofDetails(from: object, fallbackHost: hostOrURL)
    }

    static func proofDetails(
        from object: [String: Any],
        fallbackHost: String
    ) throws -> BrowserProofDetails {
        let formattedData = try JSONSerialization.data(
            withJSONObject: object,
            options: [.prettyPrinted, .sortedKeys]
        )
        guard let formattedJSON = String(data: formattedData, encoding: .utf8) else {
            throw RustBridgeError.invalidOutput("proof details are not UTF-8")
        }

        let host = Self.string(in: object, key: "host") ?? fallbackHost
        let proofStatus = Self.string(in: object, key: "proofStatus") ?? "unknown"
        let hnsProof = Self.string(in: object, key: "hnsProof") ?? proofStatus
        let cacheStatus = Self.string(in: object, key: "cacheStatus") ?? "unknown"
        let error = Self.string(in: object, key: "error")
        let headline: String
        switch proofStatus {
        case "verified": headline = "Handshake proof verified"
        case "not_found": headline = "Handshake name not found"
        case "unavailable": headline = "Handshake proof unavailable"
        case "failed", "error", "invalid_resource": headline = "Handshake proof failed"
        default: headline = "Handshake proof \(proofStatus.replacingOccurrences(of: "_", with: " "))"
        }
        var detailParts = [host, "cache \(cacheStatus.replacingOccurrences(of: "_", with: " "))"]
        if let error { detailParts.append(error) }

        return BrowserProofDetails(
            headline: headline,
            detail: detailParts.joined(separator: " · "),
            host: host,
            name: Self.string(in: object, key: "name"),
            network: Self.string(in: object, key: "network"),
            nameHash: Self.string(in: object, key: "nameHash"),
            hnsProof: hnsProof,
            proofStatus: proofStatus,
            secure: Self.boolean(in: object, key: "secure"),
            exists: Self.boolean(in: object, key: "exists"),
            treeRoot: Self.string(in: object, key: "treeRoot"),
            blockHeight: Self.unsignedInteger(in: object, key: "blockHeight"),
            cacheStatus: cacheStatus,
            recordTypes: object["recordTypes"] as? [String] ?? [],
            error: error,
            formattedJSON: formattedJSON
        )
    }

    func close() {
        handleLock.lock()
        let handle = runtimeHandle
        runtimeHandle = 0
        handleLock.unlock()
        if handle != 0 {
            _ = hns_browser_runtime_destroy(handle)
        }
    }

    deinit {
        close()
    }

    private func classifyName(_ input: String) throws -> BrowserHostKind {
        var nameClass: HnsBrowserNameClass = HNS_BROWSER_NAME_SEARCH
        let result = RustBridge.withUTF8Slice(input) { slice in
            hns_browser_classify_name(slice, &nameClass)
        }
        try RustBridge.check(result, operation: "name classification")
        switch nameClass {
        case HNS_BROWSER_NAME_HNS: return .handshake
        case HNS_BROWSER_NAME_ICANN: return .icann
        case HNS_BROWSER_NAME_SEARCH: return .search
        default: throw RustBridgeError.invalidOutput("unknown name class \(nameClass)")
        }
    }

    private func rustCanonicalHost(_ host: String) throws -> String {
        var output = HnsBrowserBuffer()
        let result = RustBridge.withUTF8Slice(host) { slice in
            hns_browser_canonical_host(slice, &output)
        }
        defer { RustBridge.free(output) }
        try RustBridge.check(result, operation: "host canonicalization")
        return try RustBridge.string(copying: output)
    }

    private func liveHandle() throws -> HnsBrowserRuntimeHandle {
        guard let handle = currentHandle() else {
            throw BrowserCoreError.runtimeUnavailable("runtime handle is closed")
        }
        return handle
    }

    private func currentHandle() -> HnsBrowserRuntimeHandle? {
        handleLock.lock()
        defer { handleLock.unlock() }
        return runtimeHandle == 0 ? nil : runtimeHandle
    }

    private func runtimeJSONObject(
        operation: String,
        invoke: (HnsBrowserRuntimeHandle, UnsafeMutablePointer<HnsBrowserBuffer>) -> HnsBrowserResult
    ) throws -> [String: Any] {
        let handle = try liveHandle()
        var output = HnsBrowserBuffer()
        let result = withUnsafeMutablePointer(to: &output) { outputPointer in
            invoke(handle, outputPointer)
        }
        defer { RustBridge.free(output) }
        try RustBridge.check(result, operation: operation)
        let data = try RustBridge.data(copying: output)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RustBridgeError.invalidOutput("\(operation) did not return a JSON object")
        }
        return object
    }

    static func syncSummary(from object: [String: Any]) throws -> BrowserSyncSummary {
        guard let status = string(in: object, key: "status"), !status.isEmpty else {
            throw RustBridgeError.invalidOutput("sync status is missing")
        }
        let error = string(in: object, key: "error")
        let syncStatusSchemaVersion = unsignedInteger(
            in: object,
            key: "syncStatusSchemaVersion"
        )
        let bestHeight = height(in: object, key: "bestHeight")
        let peerHeight = height(in: object, key: "bestPeerHeight")
        let estimatedTipHeight = height(in: object, key: "estimatedTipHeight")
        let effectiveTargetHeight = height(in: object, key: "effectiveTargetHeight")
        let lagBlocks = height(in: object, key: "lagBlocks")
        let freshness = string(in: object, key: "freshness") ?? "unknown"
        let freshnessThresholdBlocks = height(
            in: object,
            key: "freshnessThresholdBlocks"
        )
        let treeIntervalBlocks = height(in: object, key: "treeIntervalBlocks")
        let authoritativeTreeRootHeight = height(
            in: object,
            key: "authoritativeTreeRootHeight"
        )
        let localTreeRootHeight = height(in: object, key: "localTreeRootHeight")
        let treeRootReady = boolean(in: object, key: "treeRootReady")
        let blocksUntilAuthoritativeTreeRoot = height(
            in: object,
            key: "blocksUntilAuthoritativeTreeRoot"
        )
        let network = string(in: object, key: "network")
        let targetSource = string(in: object, key: "targetSource") ?? "unknown"
        let targetPeerGroups = nonnegativeInt(in: object, key: "targetPeerGroups") ?? 0
        let targetEvidenceExpired = boolean(in: object, key: "targetEvidenceExpired") ?? true
        let attempted = nonnegativeInt(in: object, key: "attempted") ?? 0
        let successful = nonnegativeInt(in: object, key: "successful") ?? 0
        let accepted = nonnegativeInt(in: object, key: "accepted") ?? 0
        let syncInFlight = boolean(in: object, key: "syncInFlight") ?? false
        let stagedBestHeight = height(in: object, key: "stagedBestHeight")
        let stagedAccepted = nonnegativeInt(in: object, key: "stagedAccepted") ?? 0
        let failed = nonnegativeInt(in: object, key: "failed") ?? 0
        let cacheEntries = nonnegativeInt(in: object, key: "resourceCacheEntries") ?? 0
        let cacheBytes = unsignedInteger(in: object, key: "resourceCacheBytes") ?? 0
        let cacheEvicted = nonnegativeInt(in: object, key: "resourceCacheEvicted") ?? 0
        let hasAuthoritativeTreeRoot = BrowserTreeRootAuthority(
            syncStatusSchemaVersion: syncStatusSchemaVersion,
            network: network,
            bestHeight: bestHeight,
            effectiveTargetHeight: effectiveTargetHeight,
            treeIntervalBlocks: treeIntervalBlocks,
            authoritativeTreeRootHeight: authoritativeTreeRootHeight,
            localTreeRootHeight: localTreeRootHeight,
            treeRootReady: treeRootReady,
            blocksUntilAuthoritativeTreeRoot: blocksUntilAuthoritativeTreeRoot,
            targetSource: targetSource,
            targetPeerGroups: targetPeerGroups,
            targetEvidenceExpired: targetEvidenceExpired
        ).isReady
        let hasAuthoritativeCurrentness = hasAuthoritativeTreeRoot
            && freshness == "current"
            && freshnessThresholdBlocks == 2
            && lagBlocks.map { lag in
                effectiveTargetHeight.map { target in
                    bestHeight.map { best in lag == target - best && lag <= 2 } ?? false
                } ?? false
            } == true
        let isCurrent = ["up_to_date", "synced", "attempted"].contains(status)
            && hasAuthoritativeCurrentness

        let headline: String
        if syncInFlight && !hasAuthoritativeTreeRoot {
            headline = "Syncing Handshake headers"
        } else if isCurrent {
            headline = "Handshake headers current"
        } else if hasAuthoritativeTreeRoot {
            headline = "Handshake name state ready"
        } else {
            switch status {
            case "syncing", "synced", "attempted":
                headline = "Syncing Handshake headers"
            case "cleared": headline = "Resolver cache cleared"
            case "idle": headline = "Handshake sync idle"
            case "error", "peer_failed", "seed_failed": headline = "Header sync needs attention"
            default: headline = "Handshake sync \(status.replacingOccurrences(of: "_", with: " "))"
            }
        }

        let detail: String
        if let error {
            detail = error
        } else if status == "cleared" {
            detail = "The runtime resolver cache now contains \(cacheEntries) entries."
        } else {
            let best = bestHeight.map(String.init) ?? "unknown"
            var details: [String] = []
            if syncInFlight && !hasAuthoritativeTreeRoot, let stagedBestHeight {
                details.append("staged validated \(stagedBestHeight)")
            } else {
                details.append("Current height \(best)")
            }
            if let effectiveTargetHeight {
                details.append("effective target \(effectiveTargetHeight)")
            }
            if let authoritativeTreeRootHeight {
                let rootState = hasAuthoritativeTreeRoot ? "ready" : "waiting"
                details.append("HNS root \(authoritativeTreeRootHeight) \(rootState)")
            }
            details.append("freshness \(freshness)")
            if syncInFlight && !hasAuthoritativeTreeRoot {
                details.append("staged accepted +\(stagedAccepted)")
            } else {
                details.append("accepted \(accepted)/\(attempted)")
            }
            detail = details.joined(separator: " · ")
        }

        return BrowserSyncSummary(
            headline: headline,
            detail: detail,
            syncStatusSchemaVersion: syncStatusSchemaVersion,
            status: status,
            network: network,
            attempted: attempted,
            successful: successful,
            accepted: accepted,
            syncInFlight: syncInFlight,
            stagedBestHeight: stagedBestHeight,
            stagedAccepted: stagedAccepted,
            failed: failed,
            peerCount: nonnegativeInt(in: object, key: "peerCount") ?? 0,
            peerGroups: nonnegativeInt(in: object, key: "peerGroups") ?? 0,
            bestHeight: bestHeight,
            bestPeerHeight: peerHeight,
            estimatedTipHeight: estimatedTipHeight,
            effectiveTargetHeight: effectiveTargetHeight,
            lagBlocks: lagBlocks,
            freshness: freshness,
            freshnessThresholdBlocks: freshnessThresholdBlocks,
            treeIntervalBlocks: treeIntervalBlocks,
            authoritativeTreeRootHeight: authoritativeTreeRootHeight,
            localTreeRootHeight: localTreeRootHeight,
            treeRootReady: treeRootReady,
            blocksUntilAuthoritativeTreeRoot: blocksUntilAuthoritativeTreeRoot,
            targetSource: targetSource,
            targetPeerGroups: targetPeerGroups,
            targetEvidenceExpired: targetEvidenceExpired,
            resourceCacheEntries: cacheEntries,
            resourceCacheBytes: cacheBytes,
            resourceCacheEvicted: cacheEvicted,
            error: error
        )
    }

    private static func string(in object: [String: Any], key: String) -> String? {
        guard let value = object[key] as? String, !value.isEmpty else { return nil }
        return value
    }

    private static func unsignedInteger(in object: [String: Any], key: String) -> UInt64? {
        guard let number = object[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else {
            return nil
        }
        return UInt64(number.stringValue)
    }

    private static func height(in object: [String: Any], key: String) -> UInt64? {
        guard let value = unsignedInteger(in: object, key: key),
              value <= UInt64(UInt32.max) else {
            return nil
        }
        return value
    }

    private static func nonnegativeInt(in object: [String: Any], key: String) -> Int? {
        guard let value = unsignedInteger(in: object, key: key),
              value <= UInt64(Int.max)
        else {
            return nil
        }
        return Int(value)
    }

    private static func boolean(in object: [String: Any], key: String) -> Bool? {
        guard let number = object[key] as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID()
        else {
            return nil
        }
        return number.boolValue
    }

}

final class RustBrowserProxySession: BrowserProxySession {
    let endpoint: BrowserProxyEndpoint
    private(set) var latestResolutionTraceJSON: String?

    private let handleLock = NSLock()
    private var proxyHandle: HnsBrowserProxyHandle
    private let generation: UInt64
    private let sessionID: String

    init(handle: HnsBrowserProxyHandle) throws {
        var nativeEndpoint = HnsBrowserProxyEndpoint()
        let result = hns_browser_proxy_endpoint(handle, &nativeEndpoint)
        defer {
            RustBridge.free(nativeEndpoint.session_id)
            RustBridge.free(nativeEndpoint.realm)
            RustBridge.free(nativeEndpoint.username)
            RustBridge.free(nativeEndpoint.password)
        }
        try RustBridge.check(result, operation: "proxy endpoint")

        let sessionID = try RustBridge.string(copying: nativeEndpoint.session_id)
        let realm = try RustBridge.string(copying: nativeEndpoint.realm)
        let username = try RustBridge.string(copying: nativeEndpoint.username)
        let password = try RustBridge.string(copying: nativeEndpoint.password)
        guard nativeEndpoint.port != 0,
              nativeEndpoint.generation != 0,
              !sessionID.isEmpty,
              !realm.isEmpty,
              !username.isEmpty,
              !password.isEmpty else {
            throw RustBridgeError.invalidOutput("proxy endpoint is incomplete")
        }

        proxyHandle = handle
        generation = nativeEndpoint.generation
        self.sessionID = sessionID
        endpoint = BrowserProxyEndpoint(
            host: "127.0.0.1",
            port: nativeEndpoint.port,
            realm: realm,
            username: username,
            password: password
        )
    }

    func clearResolutionTrace() {
        latestResolutionTraceJSON = nil
    }

    func requestStop() {
        guard let handle = currentHandle() else { return }
        _ = hns_browser_proxy_request_stop(handle)
    }

    func joinAndDestroy() {
        handleLock.lock()
        let handle = proxyHandle
        proxyHandle = 0
        handleLock.unlock()
        if handle != 0 {
            _ = hns_browser_proxy_destroy(handle)
        }
    }

    func acceptsProxyChallenge(
        host: String,
        port: Int,
        realm: String?,
        authenticationMethod: String
    ) -> Bool {
        guard authenticationMethod == NSURLAuthenticationMethodHTTPBasic,
              let handle = currentHandle(),
              let port = UInt16(exactly: port),
              let realm,
              !realm.isEmpty else {
            return false
        }
        var matches: UInt8 = 0
        let result = RustBridge.withUTF8Slice(host) { hostSlice in
            RustBridge.withUTF8Slice(realm) { realmSlice in
                hns_browser_proxy_matches_authentication_challenge(
                    handle,
                    hostSlice,
                    port,
                    realmSlice,
                    &matches
                )
            }
        }
        return result == HNS_BROWSER_RESULT_OK && matches == 1
    }

    func matchesLocalCertificate(host: String, leafCertificateDER: Data) -> Bool {
        guard let handle = currentHandle(), !leafCertificateDER.isEmpty else { return false }
        var matches: UInt8 = 0
        let result = RustBridge.withUTF8Slice(host) { hostSlice in
            RustBridge.withDataSlice(leafCertificateDER) { certificateSlice in
                hns_browser_proxy_matches_local_certificate(
                    handle,
                    hostSlice,
                    certificateSlice,
                    &matches
                )
            }
        }
        return result == HNS_BROWSER_RESULT_OK && matches == 1
    }

    func takeMainFrameSecurityStatus(
        host: String,
        allowsWebPkiFallback: Bool
    ) -> BrowserSecuritySummary? {
        guard let handle = currentHandle() else { return nil }
        var status = HnsBrowserProxyStatus()
        let result = RustBridge.withUTF8Slice(host) { hostSlice in
            hns_browser_proxy_take_main_frame_status(handle, hostSlice, &status)
        }
        defer {
            RustBridge.free(status.host)
            RustBridge.free(status.resolution_trace_json)
        }
        guard result == HNS_BROWSER_RESULT_OK,
              status.generation == generation,
              let returnedHost = try? RustBridge.string(copying: status.host),
              returnedHost == host else {
            return nil
        }
        let trace = (try? RustBridge.string(copying: status.resolution_trace_json))
            .flatMap { $0.isEmpty ? nil : $0 }
        if let trace {
            latestResolutionTraceJSON = trace
        }

        return Self.securitySummary(
            httpStatus: status.http_status,
            tlsPolicy: status.tls_policy,
            resolverPolicy: status.resolver_policy,
            securityPath: status.security_path,
            allowsWebPkiFallback: allowsWebPkiFallback,
            resolutionTraceJSON: trace
        )
    }

    static func securitySummary(
        httpStatus: UInt32,
        tlsPolicy: UInt32,
        resolverPolicy: UInt32,
        securityPath: UInt32,
        allowsWebPkiFallback: Bool = false,
        resolutionTraceJSON: String? = nil
    ) -> BrowserSecuritySummary {
        let selectedNamespace = Self.selectedNamespace(from: resolutionTraceJSON)
        let namespaceDetail = Self.namespaceChoiceDetail(from: resolutionTraceJSON)
        func result(_ level: BrowserSecurityLevel, _ detail: String) -> BrowserSecuritySummary {
            BrowserSecuritySummary(
                level: level,
                detail: namespaceDetail.map { "\(detail) · \($0)" } ?? detail
            )
        }
        if httpStatus >= 400 {
            return result(.blocked, "The Rust proxy rejected the dual-root response")
        }
        if resolverPolicy == HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY {
            return result(.blocked, "Unsupported legacy HNS resolver status")
        }
        if tlsPolicy == HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK {
            // The trusted trace, not a Swift hostname classifier, authorizes
            // ICANN WebPKI fallback. Legacy callers may pass the old flag, but
            // it cannot override a missing or HNS-selected decision.
            if selectedNamespace == "icann" {
                return result(
                    .webPKI,
                    "WebPKI verified · no secure TLSA (authenticated absence or insecure delegation) · validating ICANN DoH"
                )
            }
            _ = allowsWebPkiFallback
            return result(.blocked, "WebPKI fallback was not authorized by the selected namespace")
        }
        if tlsPolicy == HNS_BROWSER_TLS_POLICY_UNKNOWN,
           securityPath != HNS_BROWSER_SECURITY_PATH_UNKNOWN {
            return result(
                .insecure,
                "Rust namespace resolution · \(Self.securityPathLabel(securityPath)) · plain HTTP"
            )
        }
        if tlsPolicy == HNS_BROWSER_TLS_POLICY_DANE {
            return result(.handshakeDANE, "DANE verified · \(Self.securityPathLabel(securityPath))")
        }
        return result(.blocked, "Unknown Rust transport policy")
    }

    private static func namespaceObject(from traceJSON: String?) -> [String: Any]? {
        guard let traceJSON,
              let data = traceJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object["namespaceResolution"] as? [String: Any]
    }

    private static func selectedNamespace(from traceJSON: String?) -> String? {
        guard let resolution = namespaceObject(from: traceJSON),
              let selected = resolution["selected"] as? String,
              let outcome = resolution["outcome"] as? String else {
            return nil
        }
        switch (selected, outcome) {
        case ("hns", "hnsOnly"),
             ("hns", "bothConvergent"),
             ("hns", "bothDivergent"),
             ("icann", "icannOnly"),
             ("icann", "bothConvergent"),
             ("icann", "bothDivergent"):
            return selected
        default:
            return nil
        }
    }

    private static func namespaceChoiceDetail(from traceJSON: String?) -> String? {
        guard let resolution = namespaceObject(from: traceJSON),
              let outcome = resolution["outcome"] as? String else {
            return nil
        }
        let selected = resolution["selected"] as? String
        let reason = resolution["reason"] as? String
        let selectedLabel = selected == "hns" ? "HNS" : selected == "icann" ? "ICANN" : nil
        let reasonLabel: String?
        switch reason {
        case "explicitPin": reasonLabel = "explicit pin"
        case "stickyBinding": reasonLabel = "saved successful binding"
        case "icannDefault": reasonLabel = "ICANN default"
        case "onlyAvailableRoot": reasonLabel = "only available root"
        case "convergentDefault": reasonLabel = "convergent roots"
        default: reasonLabel = nil
        }
        switch outcome {
        case "bothDivergent":
            guard let selectedLabel else { return "Both roots differ; selection unavailable" }
            return "Both roots differ; using \(selectedLabel)" +
                (reasonLabel.map { " (\($0))" } ?? "")
        case "bothConvergent":
            return selectedLabel.map { "Both roots agree; using \($0)" } ?? "Both roots agree"
        case "hnsOnly": return "HNS only"
        case "icannOnly": return "ICANN only"
        case "neither": return "Absent from both roots"
        case "indeterminate": return "Dual-root validation failed"
        default: return nil
        }
    }

    deinit {
        handleLock.lock()
        let handle = proxyHandle
        proxyHandle = 0
        handleLock.unlock()
        guard handle != 0 else { return }
        _ = hns_browser_proxy_request_stop(handle)
        DispatchQueue.global(qos: .utility).async {
            _ = hns_browser_proxy_destroy(handle)
        }
    }

    private func currentHandle() -> HnsBrowserProxyHandle? {
        handleLock.lock()
        defer { handleLock.unlock() }
        return proxyHandle == 0 ? nil : proxyHandle
    }

    private static func securityPathLabel(_ path: HnsBrowserSecurityPath) -> String {
        switch path {
        case HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DOH:
            return "authoritative DoH"
        case HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DNS53:
            return "authoritative DNS"
        case HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH:
            return "DANE via user-configured HNS recovery DoH"
        case HNS_BROWSER_SECURITY_PATH_STATELESS_DANE:
            return "stateless DANE"
        case HNS_BROWSER_SECURITY_PATH_DANE_ICANN_DOH:
            return "ICANN DoH"
        case HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DOH:
            return "HNS authoritative DoH"
        case HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DNS53:
            return "HNS authoritative DNS"
        case HNS_BROWSER_SECURITY_PATH_HNS_THIRD_PARTY_DOH:
            return "HNS via user-configured recovery DoH"
        case HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY:
            return "P2P DNS relay"
        case HNS_BROWSER_SECURITY_PATH_HNS_P2P_DNS_RELAY:
            return "HNS P2P DNS relay"
        default:
            return "verified Rust path"
        }
    }
}

private enum RustBridge {
    static func withUTF8Slice<T>(
        _ value: String,
        body: (HnsBrowserSlice) throws -> T
    ) rethrows -> T {
        let bytes = Array(value.utf8)
        return try bytes.withUnsafeBufferPointer { buffer in
            try body(
                HnsBrowserSlice(
                    ptr: buffer.baseAddress,
                    len: UInt64(buffer.count)
                )
            )
        }
    }

    static func withDataSlice<T>(_ value: Data, body: (HnsBrowserSlice) -> T) -> T {
        value.withUnsafeBytes { bytes in
            body(
                HnsBrowserSlice(
                    ptr: bytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    len: UInt64(bytes.count)
                )
            )
        }
    }

    static func data(copying buffer: HnsBrowserBuffer) throws -> Data {
        guard buffer.len <= UInt64(Int.max) else {
            throw RustBridgeError.invalidOutput("buffer length is unsupported")
        }
        if buffer.len == 0 {
            guard buffer.ptr == nil, buffer.allocation_id == 0 else {
                throw RustBridgeError.invalidOutput("empty buffer token is malformed")
            }
            return Data()
        }
        guard let pointer = buffer.ptr, buffer.allocation_id != 0 else {
            throw RustBridgeError.invalidOutput("nonempty buffer is malformed")
        }
        return Data(bytes: pointer, count: Int(buffer.len))
    }

    static func string(copying buffer: HnsBrowserBuffer) throws -> String {
        let data = try data(copying: buffer)
        guard let value = String(data: data, encoding: .utf8) else {
            throw RustBridgeError.invalidOutput("buffer is not UTF-8")
        }
        return value
    }

    static func free(_ buffer: HnsBrowserBuffer) {
        _ = hns_browser_buffer_free(buffer)
    }

    static func check(_ result: HnsBrowserResult, operation: String) throws {
        guard result != HNS_BROWSER_RESULT_OK else { return }
        var errorBuffer = HnsBrowserBuffer()
        let errorResult = hns_browser_last_error(&errorBuffer)
        defer { free(errorBuffer) }
        let detail: String
        if errorResult == HNS_BROWSER_RESULT_OK,
           let message = try? string(copying: errorBuffer),
           !message.isEmpty {
            detail = message
        } else {
            detail = "no native error detail"
        }
        throw RustBridgeError.callFailed(
            operation: operation,
            code: result,
            detail: detail
        )
    }
}
