# Architecture

Android and iOS are publicly distributed browsers, not system-wide resolvers.
Their security engine is a platform-neutral Rust runtime, so Android WebView
and iOS WKWebView shells share full-host dual-root namespace preparation, HNS
and ICANN DANE policy, transport policy, persistent state, proxy parsing, and
validation results while retaining platform-native UI and lifecycle
integration. Android remains the deepest release-device validation baseline;
the iOS signed-device WebKit matrix is not required for App Store submission,
but remains an unverified installed-device and ecosystem qualification gate.

## Layers

```text
Android UI / Browser Shell                         [public]
  -> MainActivity + BrowserProxyCoordinator navigation admission
  -> process-global AndroidX ProxyController ownership
  -> RustBrowserProxy + thin android-ffi JNI adapter
  -> authenticated hns-loopback-proxy HTTP/CONNECT/TLS endpoint
  -> persistent hns-mobile-platform-runtime handle
  -> HNS/validating ICANN resolver, DNSSEC, DANE, transport, cache
  -> HNS peers, ICANN DNS, TCP TLS, QUIC/HTTP3
iOS UI / Browser Shell                             [public; device qualification open]
  -> BrowserProxyCoordinator + persistent WKWebsiteDataStore
  -> authenticated, no-failover whole-browser proxy configuration
  -> thin versioned ios-ffi C ABI / XCFramework
  -> the same hns-loopback-proxy + hns-mobile-platform-runtime
```

## Rust Crates

- External engine contracts: `hns-browser-runtime` owns the canonical session, generation, lifecycle, and admitted-work stamps; `hns-browser-observability` checks the name-free schema-v2 browser status; `hns-icann-dane` derives transport-aware TLSA owners and constrained ICANN DANE/WebPKI outcomes; `hns-namespace-resolution` validates complete single-root origin plans, compares them, applies explicit-pin/sticky/ICANN precedence, fingerprints the selected immutable decision, and retains bounded per-root present/absent/failed dispositions when classification fails; `hns-resolution-policy` provides the typed direct-first transport plan. Mobile maps its stable relay ABI and normalized explicit recursive-recovery choice into that policy with ODoH, HNSR, provider roles, and legacy compatibility explicitly disabled. All five contracts use version `0.2.0` from exact qualified Git source `2b23bd55d14d36fe60073606869d75b4796c54f7`; there is no crates.io patch bridge.
- The upstream `hns-dane-engine` facade contains the reviewed HNSA admission and HNSR requester types but is deliberately absent from this mobile graph because it currently pulls public OpenSSL-backed DANE/DNSSEC crates into Android and Apple closures. A mobile-safe upstream dependency boundary must land before those APIs can be adopted. This checkout does not construct those runtimes or provide their proof, rollback-resistant persistence, Brontide/Denuo transport, endpoint, rendezvous, provider, FFI, or native-control adapters.
- The compatibility names `hns-core`, `hns-chain`, `hns-p2p`, `hns-sync`,
  `hns-urkel`, `hns-resolver`, `hns-dnssec`, `hns-dane`, `hns-transport`,
  `hns-gateway`, and `hns-loopback-proxy` resolve to private adapter crates
  in `hns-dane-engine` at one exact reviewed Git commit. They must not be
  restored as local product crates; the source-policy gate enforces that rule.
- `hns-core`: consensus-neutral primitives, HSD-compatible name validation and name-hash derivation, hashes, bounded parsing, Handshake headers, DNS/TLSA wire primitives, RFC 9460 SVCB/HTTPS RDATA parsing, and HSD name resource value decoding.
- `hns-chain`: header storage, chainwork, HSD-compatible mainnet difficulty
  retarget validation, best-tip selection, restartable state interfaces,
  canonical `hash_by_height` indexing for reorg-aware height lookups, and
  append-only canonical tip promotion for normal chain growth. It supports
  private SQLite snapshot staging plus a generation-and-tip-bound delta
  journal, allowing normal validated publication to commit only the new
  canonical suffix instead of rescanning the live database.
- `hns-p2p`: Handshake packet payload codec, HSD-compatible frame encoder/decoder, blocking TCP peer connection, header-sync session state, static peer seeding, HSD-compatible DNS seed discovery, bounded getaddr/addr peer discovery with discovery-rotation selection, SQLite peer-state persistence, peer score tracking, transient-failure recovery with bounded malformed-peer bans, address-group-aware outbound peer selection, and the opt-in private DNS-relay capability/client. The relay client tracks capability only from the current handshake, reuses a bounded connection set, matches bounded request IDs, and returns raw DNS bytes without making a security judgment. Relay-only handshakes advertise zero local services, exclude their remote version heights from sync-currentness state, enforce the HIP query type/flag/EDNS profile before transmission, and close an exchange/connection for a future unknown transport status without automatically changing score or cooldown.
- `hns-sync`: the shared engine-owned header batch and proof lifecycle adapter connecting P2P sync
  actions to chain validation, remote-height-aware no-op sync when selected
  peers are not ahead, bounded multi-batch header sync across selected peers
  with persisted peer outcomes, successful-peer getaddr discovery plus
  same-run probing of additional unqueried peers toward the peer-table target,
  upstream-compatible Urkel proof verification and verified HSD `NameState.data`
  value handoff through a resolver-independent sink. Network I/O, quorum
  collection, snapshot preparation, and peer merging occur in a private stage
  owned by the platform runtime.
  Conditional publication briefly takes the cross-process publication and
  browser-maintenance locks, rechecks the stage's generation/tip baseline, and
  publishes headers, peer evidence, and readiness together. An unchanged-header
  peer refresh does not rotate the maintenance epoch; incomplete, stale, or
  superseded state fails closed. Non-genesis headers must match expected
  mainnet difficulty bits and satisfy proof-of-work before storage.
- `hns-urkel`: Bounded Urkel proof parsing and BLAKE2b-256 verification for inclusion, deadend, short-prefix, and collision proofs, with a separate fail-closed verifier for unwired runtime paths.
- `hns-resolver`: the engine-owned browser light-client resolver adapter: final-label HNS proof-root extraction, verified HSD resource-value extraction, verified non-inclusion state, local-chain-currentness errors, resource-value providers and cache controls, proof-backed answer filtering and nameserver address hydration, proof-anchored `hnsdns=1` bootstrap plus RFC 9461 authoritative-DoH discovery, DNSSEC-gated delegation, authoritative DoH or UDP DNS with TCP fallback, optional raw recursive relay transport after direct port 53, signed positive and denial validation, bounded CNAME and child-referral validation, TTL cache wrapping, and query-bound prepared namespace plans. A DNSSEC failure that the bounded TEST-NET canary confirms as transparent port 53 interception suppresses TCP and later direct nameservers and caches the detection. Resolution next tries proof-authenticated authoritative DoH, then the policy-admitted P2P requester only under explicit opt-in; failure of all admitted alternatives remains typed and fail-closed. Secure delegated NXDOMAIN is distinct from unsigned or bogus denial. Every transport converges on the same local DNSSEC validation code. The full-node `hns-resolverd` daemon remains the canonical node resolver; this adapter exists only for self-contained browsers that retain light proofs and immutable per-request dual-root decisions.
- `hns-dnssec`: DNSSEC validation boundary with DNSKEY/DS/RRSIG/NSEC/NSEC3 parsing, RFC 4034 key-tag computation, SHA-1, SHA-256, and SHA-384 delegation-link verification, canonical signed-data construction including canonical RDATA names for CNAME, NS, SOA, SRV, SVCB/HTTPS, RRSIG signer names, RSA/SHA-1 compatibility, RSA/SHA-256, RSA/SHA-512, ECDSA P-256/SHA-256, ECDSA P-384/SHA-384, and Ed25519 RRset signature verification, signed DNSKEY RRset checks, composed delegated-chain validation, NSEC no-data/name-range/name-error denial validation, and RFC 5155 NSEC3 no-data/name-error/DS/wildcard/referral denial validation. Covering NSEC RRsets are validated independently with only same-owner/class RRSIGs, including multi-owner name-error responses. Unsupported algorithms and unknown NSEC3 hash algorithms remain fail-closed.
- `hns-dane`: TLSA record parsing, bounded X.509 SPKI extraction, experimental HIP-0017-style x509 Urkel-proof and RFC 9102 DNSSEC-chain extension parsing under project-local OIDs while the HIP remains draft, direct-zone stateless DANE evidence validation from recent HNS tree roots, chain-aware DANE EE/TA certificate/SPKI matching, PKIX-usage WebPKI gating, and HNS/WebPKI TLS policy decisions.
- `hns-transport`: the engine-owned mobile transport backend provides bounded HTTP/1.1, HTTP/2, and HTTP/3 origin transport with pooling/resumption partitioned by selected namespace fingerprint and validated-head-before-body streaming. A fingerprinted retained plan cannot be mutated by Alt-Svc into another protocol, transport, endpoint, or TLSA owner. Legacy stateless-DANE parsing remains research code, but prepared gateway requests reject that mode because it cannot be represented without a second authority.
- `hns-gateway`: the engine-owned mobile gateway backend consumes one exact `OriginQuery` and its prepared namespace decision, verifies query equality, and performs zero follow-up resolution. The selected plan alone supplies complete A+AAAA endpoints, HTTPS/SVCB protocol and port, TLSA owner/data, and trust policy. WebSocket tunnels advertise only implemented HTTP/1.1 capability when preparing the plan. Namespace-selected errors and traces cannot be relabeled by a suffix classifier.
- `hns-cache`: bounded TTL cache primitives.
- `hns-mobile-platform-runtime`: mobile platform adapter and ownership boundary for storage, synchronization, sockets, proxy lifecycle, and Android/Apple shell integration. It independently builds HNS and ICANN plans for every complete DNS host, queries both A and AAAA, retains DNSSEC denial/signature expiry and typed root failures, compares full endpoints and service policy, applies explicit pin then sticky selection then ICANN default, and persists HTTPS/WSS and HTTP/WS under shared binding keys. HNS resolution follows the shared direct-first order: authoritative UDP/TCP, authenticated authoritative DoH, the policy-admitted P2P requester only under independent opt-in, then separately configured recursive HNS DoH only after transport failure or confirmed interception. The recovery hostname is resolved only through validating ICANN DoH, addresses must be public, endpoint TLS is WebPKI, and answers re-enter the same local DNSSEC/TLSA/DANE validator. DNS response codes, invalid DNS, bogus DNSSEC, and stale or missing HNS proof state do not authorize recovery. HTTPS/SVCB candidates from one selected record are tried as HTTP/3, HTTP/2, then RFC 9460 implicit-or-explicit HTTP/1.1; HNS advances only on secure TLSA absence for that candidate's UDP/TCP owner, while insecure or bogus evidence is terminal. ICANN DoH authenticates its configured hostname while connecting only to pinned bootstrap addresses. The selected plan, trace, and error namespace are retained together per request even when another request overwrites the shared plan cache; no host-system DNS or post-selection resolver call is permitted. The adapter drives the canonical `hns-browser-runtime` state machine with the same raw session identity as the stable proxy token, maps mobile policy revisions one-for-one to canonical generations, and emits checked `hns-browser-observability` success or failure status only when exact typed evidence is representable. Bogus and indeterminate DNSSEC are never collapsed into namespace or TLSA absence, and generic transport/WebPKI failures are unavailable rather than relabeled as DANE or SNI failures.
- `hns-loopback-proxy`: platform-neutral authenticated loopback proxy for all canonical DNS hosts, irrespective of their labels. DNS CONNECT and forward requests enter the shared prepared-plan backend; public IP literals retain bounded opaque forwarding without target DNS. Both modes share bounded parsing/framing, unsafe-port and special-address policy, header sanitization, active-client limits, streamed bodies, exact live certificate authorization, typed status, and owned cancellation-and-join lifecycle. Every backend result carries an opaque publication capability bound to its exact request-entry authority stamp and the header-maintenance epoch captured under the request's shared maintenance lock. For a successful namespace decision, that capability also carries the pending sticky binding. The server reacquires the maintenance lock, validates the exact epoch, acquires the authority permit, commits the sticky binding, and flushes the response or HTTP 101 head while retaining both locks. Sync, cache clear, snapshot install, and header reset advance the epoch under the exclusive maintenance lock, so maintenance and head publication have one deterministic winner. Stop signals cancellation before potentially blocking revocation, while response bodies and upgraded streams retain their cancellation and exact-stamp checks. Backend errors or invalid response heads are suppressed unless the backend supplied a bounded authorized response, so the server cannot invent an unstamped fallback head.
- `android-ffi`: thin Android JNI adapter for string/byte conversion, error mapping, opaque `BrowserRuntime` handles, and a count-bounded non-pointer registry of Rust proxy handles with atomic policy-before-start, immutable generation configuration, immediate revocation, worker-join destruction, live generation-bound certificate-DER matching, and an aggregate-bounded per-host main-frame status mailbox consumed through an exact instance/host/sequence acknowledgement and versioned bounded bundle. The legacy scope field remains ABI-compatible input only and cannot select a namespace; ordinary and file-backed gateway calls use the same persistent runtime handle, transport, peer state, and maintenance boundary as the proxy. Platform-neutral resolution, synchronization, storage, transport, proxy, and TLS policy belongs in Rust, not this crate.
- `ios-ffi`: stable versioned C ABI over the same `BrowserRuntime`, with bounded versioned option/policy structs, monotonic opaque runtime/proxy handles, one live proxy per runtime, Rust-owned buffer allocation/free, thread-local errors, panic containment, exact latest-host status consumption, and immediate request-stop plus joined destruction. It contains no resolver, gateway, transport, or proxy reimplementation.
- `rust/fuzz`: parser fuzz smoke targets for DNS messages/names/SVCB, HNS resource values, P2P frames/payloads, Urkel proofs, TLSA records, and X.509 SPKI extraction.

## Cross-Platform Migration Status

- The shared mobile `hns-mobile-platform-runtime` crate and its persistent `BrowserRuntime` API are in place and have no JNI dependency or exported Java symbols. The package name deliberately distinguishes this platform adapter from the canonical engine `hns-browser-runtime`; the stable mobile C ABI retains its existing `hns_browser_runtime_*` symbol names.
- Runtime identity, configuration, policy, transport reuse, storage coordination, ordinary gateway requests, and file-backed gateway requests are handle-backed. The Rust proxy adapter uses typed requests, responses, internal security metadata, typed security status, and Upgrade tunnels; the Android bridge preserves the existing encoded-HTTP schema while executing those requests on the persistent runtime.
- One checked random session supplies both the existing URL-safe proxy session token and the canonical engine session bytes. Mobile policy revision zero maps to canonical policy generation one, and each real normalized policy change advances both exactly once; a normalized no-op leaves the complete runtime snapshot unchanged. Listener startup and browser authority are separate facts: first install may publish an authenticated loopback listener in `Degraded`, but no request is admitted until a current non-genesis header on every network, proof storage, a policy transport, and the live browser bridge reach `Active`.
- The active listener generation is published only after its bind succeeds. Replacement revokes the old authority before preparing the new listener; stale stop/drop calls compare-and-clear only their own generation. Every origin operation mints one canonical stamp at whole-request entry before maintenance, DNS, or classification, and carries that exact stamp through transport, status, sticky binding, response, file, or tunnel publication without reminting after a same-generation recovery. It also captures the exact nonzero maintenance epoch while holding the request read lock. Final response or 101 head publication reacquires that lock, validates the epoch, and holds it together with the exact-result lifecycle permit through the head flush. Header-mutating maintenance advances the epoch before mutation under its exclusive lock, preventing stale pre-maintenance results from crossing the boundary. Direct file output is staged privately and renamed only within the analogous final guard; Android raw/JNI wrappers propagate any post-parse runtime failure instead of synthesizing bytes outside it. Stop signals socket/work cancellation before waiting on revocation. Redirects, subresources, Service Workers, downloads, and WebSockets therefore share the same authority boundary as the initial page.
- Shared success status is constructed from the request-local retained `NamespaceDecision`, its canonical decision fingerprint, and the exact selected-root DNS question/transport. Canonical failure status retains the request-local exact HNS and ICANN `RootFailure` values and, after selection, the retained decision; bogus DNSSEC is represented as bogus and never as TLSA absence. ICANN-selected status uses `ValidatingIcannDoh` without an HNS chain anchor. The Rustls verifier records an exact DANE association mismatch and the transport preserves it as `DaneFailed` across blocking/controlled HTTP/1.1, Tokio HTTP/2, Quinn HTTP/3, and TLS Upgrade boundaries without inspecting display strings; origin-SNI remains unavailable unless independently evidenced. A generic transport or WebPKI failure without typed trust evidence, an HNS cache hit with no exact transport event, an unrepresentable legacy HNS DoH path, or a P2P relay lacking negotiated registry fingerprint/protocol identity remains an explicit unavailable status rather than a fabricated valid snapshot. These additions are internal to Rust and preserve the JNI, Apple C ABI, bundle identifiers, and platform preference schemas.
- Android and iOS carry a separate native-only wallet slice in the configured
  `0.5.9` release-preparation candidate
  backed by the exact pinned `hns-wallet-mobile` controller. Narrow JNI and
  Apple C-ABI surfaces expose create, restore, open, status, unlock, lock,
  one-shot recovery retrieval, destruction, one HNS account identity, and a
  strict HNWR-v1 projection/UI for balance, receive target, history, tracked
  names, and module status. Platform-owned screens and device-bound 32-byte
  database keys manage this controller. The product provisions no scoped
  loopback credential or indexed backend, so reads remain unavailable. The live
  pruned node lacks wallet index/auth; pruning does not invalidate existing-wallet
  retained evidence, while fresh restore needs a durable raw-tx source. Name
  import is absent. Sending/value, settlement,
  HNSA/HNSR, exchange, and marketplace gates remain false. Both shells perform
  read/retirement work off the UI thread. Code-bearing source
  `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
  `31433931682`, including Android instrumentation and the complete Apple
  ABI/XCFramework/app/simulator gate. Its XCTest coverage exercises retirement
  queue/lease behavior and stale-completion publication-authority predicates,
  not an end-to-end credentialed native read in flight. Exact debug APK artifact
  `9080493058` has SHA-256
  `7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`;
  it is `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`,
  `arm64-v8a` + `x86_64`, and APK Signature Scheme v2 under the default Android
  Debug key, not store signed.
  The exact APK was installed on a Pixel 9 (`tokay`), Android 17 / API 37,
  after Android safely rejected the incompatible historical code `49` debug
  signer and an authorized debug-package-only reinstall. Production remained
  untouched, the installed `base.apk` digest matched, cold launch succeeded,
  and `WalletActivity` displayed the expected no-wallet and fail-closed read
  projection. No wallet, secret, account, read sync, or value action was used.
  The scoped credential/backend/data boundary, creation/read qualification,
  signed artifacts, fresh commit-bound store screenshots, store
  declaration/upload, and physical-iPhone matrix remain open. No listed public
  binary contains this source tranche.
- The website-facing mobile wallet-provider projection remains dormant.
  Website Provider API schema 1 remains separate from private provider ABI 2
  and public approval schema 3. Closed approval summaries and typed events
  contain public display/page data only; opaque authority handles, revisions,
  wallet sessions, channels, and event sequences remain native. Immutable
  false gates prevent either adapter from mutating WebView/WKWebView
  configuration. Browser controllers do not reference the adapters, so no
  provider is installed or announced and page script cannot reach the native
  controller.
- Activating the website provider requires generated provider/service JNI and
  C bindings and the canonical engine's typed exact-origin, namespace-decision
  fingerprint, authority-validity/generation result plus an opaque engine
  context. Platform code must consume that result; it must not derive provider
  authority from URL classification, toolbar status, proxy readiness, caller
  booleans, or JSON. Approval UI, permission persistence, provider lifecycle
  revocation, and the typed native event producer remain absent.
- `MainActivity` routes every HTTP(S) navigation through `BrowserProxyCoordinator`. One process-wide generation covers every canonical DNS host, so redirects and cross-origin subresources do not rotate or bypass it. Policy changes revoke the endpoint, authentication challenge, certificate trust, and status binding before the retired proxy is joined off the UI thread.
- `HnsProxyController` serializes access to AndroidX `ProxyController`, whose override is process-global. A newer owner immediately revokes the older coordinator, and callbacks from an older owner cannot publish a route or clear a newer override. Owner generations form a permanent process high-water mark: after a newer Activity claims ownership, an older Activity stays retired even if that newer owner releases, preventing stale proxy state from being resurrected after a lifecycle handoff. Direct navigation and the exact compatibility-interceptor route wait for confirmed ownership/clear outcomes rather than racing a possibly installed override.
- The selected Rust endpoint is an authenticated loopback HTTP/CONNECT proxy backed by the shared runtime. Rust terminates every canonical DNS CONNECT locally, resolves that complete hostname through HNS and ICANN, retains the selected plan plus signing keys and exact-host certificate state, and authorizes WebView SSL continuation only when the presented certificate DER matches the live proxy generation and host. Main-frame status is consumed as a bounded typed value only for the exact committed proxy instance and host.
- Native WebSockets remain Chromium `WebSocket` connections so their Upgrade requests traverse the active proxy and Rust Upgrade tunnel. The document-start script is an inert marker; it does not attempt JavaScript namespace classification.
- Generated rcgen key state and temporary PKCS#8 buffers are zeroized when their guards drop. The live ECDSA signing-key representation retained by rustls/ring has no documented zeroizing `Drop`; it is released with the last cache, lease, or connection reference, so complete in-memory scalar erasure is not claimed.
- Android currently keeps process-lifetime runtime handles keyed by storage directory and network. Sync, status, cache maintenance, snapshot installation, peer reset, and proof diagnostics use those handles.
- `MainActivity` supplies a Rust-only `LocalBrowserProxyFactory`. If Rust proxy startup fails, admitted bodyless GET/HEAD requests may use the compatibility interceptor; unavailable request bodies and unsupported redirect/upgrade cases fail closed. Android contains no second HTTP proxy, CONNECT terminator, certificate generator, or Upgrade tunnel.
- `ios-ffi`, the XCFramework build scripts, and the UIKit/WKWebView shell are present. The shell installs a single authenticated `ProxyConfiguration` with `allowFailover = false` on a persistent identified `WKWebsiteDataStore`; every canonical DNS host shares that generation and Rust prepares its own per-origin plan.
- Swift performs only namespace-agnostic URL parsing and delegates runtime policy, sync, proxy parsing, dual-root resolution, DNSSEC, DANE, and local TLS identity generation to Rust. Swift retains UIKit/WebKit navigation, profile ownership, lifecycle, download, UI, and exact live server-trust challenge integration.
- Rust/ABI/header checks run cross-platform and Apple slices plus the Swift
  targets build in macOS CI. The still-open signed physical-device matrix is
  the qualification evidence for WebKit network-process challenge and
  failover behavior that simulator or unit-test success cannot prove; it is
  separate from App Store submission eligibility.

## iOS Modules

- `RustBrowserRuntime`: Swift ownership wrapper for the versioned C ABI. It copies and frees Rust-owned outputs, keeps blocking calls off the main thread, and exposes typed runtime/proxy operations without protocol logic.
- `RustNativeWallet`, `WalletViewController`, and `WalletKeychainStore`: the
  app-native wallet owner, non-value control screen, and create-only
  ThisDeviceOnly/user-presence database-key store. Database paths and Keychain
  accounts are scoped to the captured Handshake network, and a process-local
  path lease prevents two screens from cleaning or opening the same storage at
  once. The screen deletes an unconfirmed create on lifecycle exit. Mutable
  bridge buffers are wiped, but recovery entry/display also crosses Swift
  `String` and UIKit-managed text, whose backing storage cannot be
  deterministically zeroized.
- `BrowserProxyCoordinator`: serial lifecycle and main-frame admission boundary. It revokes the current WebView and live authentication/certificate authorization before requesting proxy stop, joins the retired instance off the main thread, then installs a new no-failover proxy configuration before constructing the replacement WebView.
- `BrowserProxyStateMachine`: generation-checked transition model that prevents stale callbacks from publishing or revoking a newer route.
- `PersistentWebKitProfile`: owns one identified persistent data store and its authenticated whole-browser proxy configuration; it never clears the profile to a direct-network fallback.
- `BrowserAuthenticationPolicy`: permits a local HNS or DNS-named ICANN certificate only after exact host, proxy generation, challenge tuple, and leaf DER authorization by Rust. Canonical public IP literals retain WebKit's default WebPKI handling.
- `HeaderSnapshotBootstrapper`: installs the same bounded compressed mainnet snapshot used by Android before asynchronous sync continues.
- `BrowserViewController`: UIKit browser surface, main-frame admission, history, status, and download handoff. Platform code does not open origin sockets or independently resolve or validate HNS names.

## Android Modules

- `MainActivity`: WebView browser shell with custom omnibox, left-side security status, shared host policy, live-polled first-page sync progress bar and target stats, a separate WebView loading bar, hamburger-menu back/forward/refresh/settings actions, Service Worker native routing, and navigation controls. Initial, omnibox, history, reload, intent, and main-frame-link loads all pass through the proxy admission gate; every canonical DNS request reaches the shared loopback proxy, and completed loads consume selected-namespace status from the exact live proxy/host binding.
- `BrowserProxyCoordinator`: navigation-admission and lifecycle boundary for one browser-wide proxy generation. It publishes one request-routing snapshot, queues the latest navigation until ownership/start/apply succeeds, rotates policy without overlapping live instances, revokes authentication/certificate/status access immediately on suspension or ownership loss, and performs blocking joins on a process-lifetime worker.
- `HnsDaneApplication`: process-level WebView startup initializer using AndroidX WebKit async startup so WebView work begins before the browser shell constructs its first `WebView`.
- `BrowserWebViewHardening`: shared WebView settings profile for the browser shell, including local-file isolation, mixed-content blocking, Safe Browsing, WebAuthn browser support when the installed WebView supports it, speculative-loading disablement, and removal of default JavaScript bridge names.
- `SettingsActivity`: settings dashboard linking to diagnostics, cookie options, legal/user-agreement content, native resolver-cache clearing, and donation links.
- `CookieSettingsActivity`: cookie preferences with persisted third-party cookie blocking and deletion of cookies plus WebView origin storage.
- `LegalActivity`: license, user agreement, build label, publisher-in-license language, and source-code link.
- `BrowserUrlClassifier`: parses searches and HTTP(S) URLs, defaults a bare valid host to HTTPS, and sends every canonical DNS host to the Rust gateway. It does not use the vendored IANA root-zone snapshot as a namespace authority; public IP literals take the bounded opaque-address path.
- `BrowserSecurityPolicy`: maps target kind, proxy availability, native sync outcome status, main-frame HNS gateway response status, DANE/WebPKI policy, and resolver policy into the toolbar security state so HNS names do not stay verified after a native gateway failure and prohibited legacy resolver, third-party-DoH, or HNS WebPKI statuses fail closed.
- `HnsProxyController`: runtime-gated browser-wide AndroidX WebKit proxy configuration pointed at the currently bound randomized loopback port. Its process-wide operation queue arbitrates `ProxyController` ownership so stale Activity instances cannot republish or clear a newer owner's override.
- `HnsSyncScheduler`: single-threaded scheduler owned by
  `HnsDaneApplication` while at least one app activity is started. It calls the
  native sync tick and publishes snapshots in-process, using the active
  interval only after accepted header progress, bounded retry intervals after
  peer/seed failures, and a 10-minute interval for current or no-progress
  states. It survives navigation between in-app screens, is not a foreground
  service, and stops when the whole app leaves the foreground.
- `HnsWebViewGatewayInterceptor`: compatibility page-request interception when the proxy cannot start, plus bodyless Service Worker HNS/ICANN HTTP/HTTPS execution for every admitted route because Android WebView cannot authorize a worker's local CONNECT certificate. It routes through the persistent shared runtime without Chromium CONNECT, with file-backed decoded response bodies, bounded same-origin redirect following, URL-bound main-frame status reporting, family-wide internal-header stripping, rejection of prohibited trust metadata before rendering or redirect following, and fail-closed handling for body-bearing requests.
- `HnsServiceWorkerGatewayClient`: Service Worker fetch routing that follows the same immutable proxy/compatibility/block snapshot as WebView requests so worker fetches cannot bypass full-host dual-root resolution or automatic ICANN DANE validation. Android WebView does not surface a Service Worker TLS failure to the page's `WebViewClient`, so admitted DNS-named requests use the shared Rust runtime gateway rather than a CONNECT path whose live local certificate the worker cannot authorize. Transition, background/suspended, destroyed-client, invalid-host, and unavailable-generation requests fail closed instead of falling through to Chromium DNS. A process-generation gate prevents an older Activity from replacing or disabling the newer Activity's singleton Service Worker client.
- `GatewayEventLog`: App-private, bounded, sanitized gateway failure event store used by diagnostics so support can inspect recent HNS gateway failures after process restarts without retaining paths, query strings, headers, or bodies.
- `HnsProxyWebSocketPolicy`: document-start marker confirming that Chromium's native `WebSocket` implementation uses the same process-wide proxy. It contains no hostname or IANA-list classifier.
- `NativeBridge`: JNI load boundary for the Rust shared library. It owns process-lifetime opaque runtime handles, executes ordinary and file-backed gateway requests on those handles, atomically configures and starts Rust proxy generations, owns versioned authenticated endpoint/status bundles, performs live generation-bound certificate-DER matching, and exposes stop/destroy operations.
- `WalletActivity`, `NativeWalletBridge`, and `AndroidWalletKeyStore`: the
  non-exported native-only non-value wallet screen, narrow JNI controller, and
  create-only Android KeyStore-backed database-key wrapper. Wallet paths and
  wrapping identities are network-scoped, while process-local ownership
  prevents stale Activity callbacks from deleting a concurrently live wallet.
  Recovery stays in mutable character storage rendered by a non-copyable
  custom view, and an unconfirmed create is wiped and deleted when its activity
  leaves the foreground.

Android builds are compiled through APK Workbench on this ARM64 host so Gradle receives the managed SDK/NDK, page-size profile, and ARM64 `aapt2` override. Gradle also invokes `scripts/build-rust-android.sh` to cross-compile and package `libhns_dane_browser_ffi.so` for `arm64-v8a` and `x86_64`.

## HNS Resolution Currentness

The runtime distinguishes three negative local-proof cases. A verified
non-inclusion proof remains `NameNotFound` only when the locally validated
canonical tip is within two blocks of an effective network target. That target
is an outlier-resistant median of successful observations no more than 20
minutes old from at least three independent peer address groups. The raw
highest advertised peer height and a genesis-time ten-minute schedule estimate
are diagnostics only and cannot authorize resolution.

The 144-block canonical proof-cache window is retained to invalidate cached
anchors conservatively across reorganization; it is not a live freshness
threshold. If corroborated target evidence is missing or expired, currentness
is unknown and HNS resolution fails closed. A historical non-inclusion proof
whose anchor is recent relative to the local tip but whose tip is behind the
effective target remains valid only for that historical block; the browser
maps it to `LocalChainNotCurrent`. Included resource proofs use the same gate
before their NS/DS delegation can reach any authoritative or relay transport.
A stale or unknown chain condition fails closed as `HNS Sync Incomplete`; it
never authorizes the separately configured recovery resolver. Proof absence remains
separate as `local_hns_proof_unavailable`.

Only header-sync sessions may promote a remote version height into the persisted
peer target used by that currentness gate. Its observation timestamp is
persisted separately from connection liveness, so proof fetches, DNS relay,
automatic relay-capability handshakes, and manual static-relay probes cannot
make an old height fresh or copy their advertised version height into sync
state.

The native sync JSON declares `syncStatusSchemaVersion: 3`. Android and iOS
require that exact version, a locally available authoritative HNS name-tree
root, the effective target, exact two-block threshold, three-group quorum, and
explicit non-expired evidence before showing name authority as current. Legacy
or malformed status fails closed. No-progress states use a ten-minute cadence;
the short cadence is reserved for runs that accepted headers.

`X-HNS-Resolution-Trace` includes `localBestHeight`, `targetHeight`,
`estimatedTargetHeight`, `localChainLagBlocks`, `localChainFreshness`,
`localChainTargetSource`, and `localChainFreshnessThresholdBlocks` so
diagnostics can distinguish a verified current name-not-found from stale or
unknown local currentness while sync is incomplete.

## Security Defaults

- HNS proof, DNSSEC, and DANE failures fail closed.
- Authoritative DoH uses RFC 8484 DNS wire messages over HTTPS. RFC 9461 `_dns.<nameserver>` SVCB remains the standard discovery path; optional `hnsdns=1` HNS TXT metadata is a narrow project bootstrap convention for networks where port 53 cannot be trusted or reached. It declares transport only and cannot synthesize origin answers.
- Local gateway binds to a randomized loopback port only.
- Every live browser-proxy endpoint requires fresh per-instance proxy authentication. Android and iOS endpoints cover the browser's canonical DNS traffic; Rust prepares and retains an independent selected plan for each exact origin. Public IP literals remain bounded opaque tunnels.
- Android accepts a local HNS TLS certificate only when its full DER bytes match the exact host and currently published proxy generation; suspension, scope rotation, and ownership revocation withdraw that trust immediately.
- iOS applies the same exact live host/generation/DER rule to HNS server-trust challenges, disables proxy failover, and revokes the WebView before stopping or rotating its proxy.
- Android WebView proxy use is gated by `WebViewFeature.PROXY_OVERRIDE`.
- The complete hostname, not its rightmost label or current IANA membership, determines whether HNS, ICANN, both, or neither resolves.
- A syntactically valid single-label hostname enters the same dual-root preparation as a dotted hostname; whitespace/search input never does.
