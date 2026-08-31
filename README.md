# Shakescape

Cross-platform Handshake-first browser core with local HNS proofs,
authoritative DNS, an experimental requester-only HNS P2P DNS relay, optional
user-configured recovery DoH, DNSSEC, and DANE diagnostics. Android and iOS are
publicly distributed; Android remains the deepest release-device validation
baseline. The Android shell supports Android 9 / API 28 or later. The native
iOS shell supports iOS 17.0 or later, and its Apple build and simulator gate
uses the stable iOS 26.5 SDK with Xcode 26.5 or 26.6.

- [Google Play Store](https://play.google.com/store/apps/details?id=com.denuoweb.hnsdane)
- [Apple App Store](https://apps.apple.com/us/app/hns-dane-browser/id6791914326)
- [GitHub Release v0.5.7](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.7)

Android `0.5.7` / code `48`, with shared Rust engine `0.5.6`, is an Android-only
compatibility release. It lowers the application and native NDK floor from API
34 to API 30 while preserving explicit UTF-8 search-query encoding. iOS remains
unchanged at `0.5.5` / build `57`.

GitHub Release [`v0.5.7`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.7)
publishes only the signed Android APK. Google Play production remains on
`0.5.6` / code `47`; no AAB is part of this GitHub-only compatibility release.

The Apple App Store published iOS `0.5.5` on 2026-07-31; a public-store lookup
on 2026-08-09 confirms that `0.5.5` is the current version. Apple source
`d926561091634cd69fc9b7e79a4b76003fa4ee47` adds the
shared compressed-negative-evidence and post-resolution freshness fixes,
stable semantic Proof Details selection, and origin revalidation for a cached
main frame with no new Rust status. Exact-head Apple CI run `30454904736` and
live Release screenshot run `30454926117` passed. Protected upload run
`30456522039` signed and uploaded build `57`; the retained IPA has SHA-256
`efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`.
The earlier `WAITING_FOR_REVIEW` and manual-release state is retained as dated
submission history in the release audit; it is no longer the current public
status. No TestFlight distribution was created. The hosted privacy policy at
the canonical product URL was aligned with that historical public release.
Wallet-aware hosted-policy source
`909dbd1a713f322f0a8d4cff88e765c612e184f3` was subsequently deployed and read
back for the historical `0.5.8` candidate. Version-neutral read-boundary source
`a5539cb063fb4b19fed4dff5400a3bc991acdc4f` was deployed and read back in run
`31485234945`. Store privacy/category answers still require exact-candidate
reconciliation before submission.

Current source is the `1.0.0` release candidate: Android code `52`, embedded
non-publishable Rust workspace `1.0.0`, and iOS build `61`. It directly pins
published `hns-rs 0.3.1`, `hns-dane-engine 0.2.2`, and
the complete `hns-wallet-rs` closure at reviewed Git commit
`bd16ce1d33bc620ccddede8636f411892188d2f4`. The browser adapters are
also exact published `0.2.2` packages; the source tags, registry checks, and
wallet revision are recorded in
[the released dependency cohort](docs/released-dependency-cohort.md).
Both native shells expose local create, restore, open, status, unlock, lock, and
one-account identity controls. The current local `main` stack also composes a
wallet-owned direct HNS peer controller: synchronized balance, distinct payment
and name-transfer receive targets, history, tracked names, send review and
broadcast, name transfer/finalization, and closed Shakedex name-market actions
stay in native code and never enter website-provider JSON. Strict HNWR-v1/v2,
send-approval, value-approval, and result decoders reject unknown fields,
noncanonical values, and oversized output before UI publication.

The older scoped-loopback indexed-wallet backend remains available as a
separate compatibility seam, but the native direct-wallet path does not depend
on it. Android and iOS additionally expose explicit IP-literal Shakescape V1
pairing and a wallet-owned listener for peer-to-peer offer exchange. The
listener and paired transport are dropped on wallet lock and protected
lifecycle exits.
Website-provider value capability, active HNSA/HNSR admission, and mainnet
cross-chain settlement remain independently disabled. No page can request or
approve a native wallet action.

The current per-action source, test, and device evidence is tracked in the
[native HNS wallet feature matrix](docs/wallet-feature-matrix.md).

These direct-wallet changes are unreleased local source. Rust and ABI tests
cover their closed boundaries, but the current stack has not yet passed the
complete Apple build/device matrix or a successful Android on-chain send. The
latest state-preserving Android exercise proved review and peer submission but
the transaction later returned as dropped/unconfirmed after the wallet
restarted and rescanned from block zero. Local mempool presentation is not
evidence that a miner retained the transaction. Exact-byte dropped-send
recovery is pinned in the mobile stack, and the subsequent forward-only change
watch-set fix preserves the authenticated scan head instead of triggering a
birthday rescan; both still require installed-device requalification.

Earlier HNWR-v2 code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI run
`31807520618`: repository policy, Rust/supply-chain, Android build and unit
tests, API 37 native-runtime instrumentation, the complete Apple
ABI/XCFramework/app/simulator gate, and aggregate Required CI all succeeded.
CodeQL runs `31807519998` and `31807520229` also succeeded. That evidence
predates the `2061a27` exact-name import tranche. Exact current application
source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete manually
dispatched CI matrix in run `31835813994`: repository policy,
Rust/supply-chain, Android build and unit tests, API 37 native-runtime
instrumentation, the complete Apple ABI/XCFramework/app/simulator gate, and
aggregate Required CI all succeeded. CodeQL runs `31833858421` and
`31833858650` also succeeded. Historical debug artifact `9222123624` is bound
to the earlier source; its artifact-archive SHA-256 is
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c` and it
expires on 2026-08-17. It is debug-only, not a store-signed artifact, and does
not close the credentialed wallet, signed product, screenshot, upload, or
physical-device gates above.

Historical HNWR-v1 code-bearing `0.5.9` source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI run
`31433931682`: repository policy, Rust/supply-chain, Android build and unit
tests, API 37 native-runtime instrumentation, the Apple
ABI/XCFramework/app/simulator gate, and aggregate Required CI all succeeded.
CodeQL run `31433931259` and Code Quality run `31433931278` also succeeded.
Debug artifact `9080493058` contains a 65,680,703-byte APK with SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`:
package `com.denuoweb.hnsdane.debug`, version `0.5.9-debug` / code `50`,
minimum API 30, target API 37, and native ABIs `arm64-v8a` and `x86_64`. It
verifies with APK Signature Scheme v2 under one default Android Debug RSA-2048
certificate (certificate SHA-256
`b51ed3a12c762a69a4c3b31a30c77b5fccc9f0d50417f8a70911b7f60b135d8a`);
it is not a store/upload-signed release artifact. The exact APK was subsequently
installed on a Google Pixel 9 (`tokay`) running Android 17 / API 37, security
patch 2026-07-05, build `CP2A.260705.006`, and `arm64-v8a`. Android first safely
rejected an in-place update of historical `0.5.8-debug` / code `49` because its
debug signing key differed (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Under the
explicit reinstall authorization, only `com.denuoweb.hnsdane.debug` and its
debug data were removed; production `com.denuoweb.hnsdane` remained installed
and untouched. The installed `base.apk` SHA-256 matches the artifact.

A cold launch traversed `LauncherActivity` to `MainActivity` in 469 ms, left a
live process, and produced no fatal signature in 300 process log lines. Browser
menu → Settings → HNS wallet opened `WalletActivity`. The fresh-install
screen showed no wallet/account, create and restore controls, and the
fail-closed historical HNWR-v1 module, balance, payment-receive, history,
tracked-name, and sync rows. Its
copy states that value and marketplace controls remain disabled. No wallet was
created or restored, and no recovery secret, account, synchronization, or value
action was exercised. This is installed shell/UI-projection evidence, not a
credentialed backend/read-sync, lifecycle-creation, signing, HNSA/HNSR,
provider, value, or marketplace result. Signed store artifacts, fresh
commit-bound screenshots, live store declaration readback, intentional upload,
and the physical-iPhone matrix remain open. None of the public builds listed
above contains the native wallet controls.

Canonical source lives at
[`handshake-rs/hns-dane-browser-mobile`](https://github.com/handshake-rs/hns-dane-browser-mobile).

The coordination-PDF mobile delta and its still-open ODoH, HNSR, native-wallet
release qualification, and broader device-qualification work are tracked in
[`docs/complete-rust-ecosystem-mobile-audit.md`](docs/complete-rust-ecosystem-mobile-audit.md).

## Layout

- `rust/`: Mobile integration workspace containing the shared platform runtime,
  Android JNI, and stable Apple C ABI. Consensus, chain, proof, resolver,
  DNSSEC, DANE, transport, gateway, cache, and loopback-proxy adapters come
  from exact reviewed `handshake-rs/hns-dane-engine` Git revisions recorded in
  the manifests and lockfiles.
- `rust/fuzz/`: `cargo-fuzz` parser harnesses for DNS, HNS resource values, P2P frames, Urkel proofs, TLSA records, and X.509 SPKI extraction.
- `android/`: Kotlin Android browser shell with WebView, namespace-agnostic URL
  admission, whole-browser proxy lifecycle integration, JNI, and a native-only
  direct HNS wallet screen.
- `ios/`: Swift/UIKit WKWebView shell with whole-data-store proxy admission,
  lifecycle/certificate integration, the stable Apple C ABI, and a native-only
  direct HNS wallet screen.
- `fixtures/`: bounded cross-language experimental DNS-relay framing and
  request-correlation fixtures.
- `docs/`: Architecture, security model, version audit, milestones, and the
  [released Rust dependency cohort](docs/released-dependency-cohort.md).
- `docs/sync-audit.md`: first-run sync path, progress UI, and remaining sync-speed bottlenecks.
- `docs/supply-chain-audit.md`: pinned build inputs, CI/release gates, and residual reproducibility risks.
- `docs/wallet-provider-mobile.md`: separation between the native
  app wallet controls and the dormant website-provider boundary (website
  schema v1, private provider ABI v2, closed approval schema v3, and typed
  events), including key/recovery lifecycle and qualification limits.
- `scripts/`: Local validation helpers.

## Current Scope

- Parses and serializes Handshake block headers.
- Computes Handshake mainnet genesis PoW hash using the HSD header algorithm.
- Validates Handshake TLD syntax and derives HSD-compatible SHA3-256 name hashes.
- Provides typed hash, height, target, and chainwork primitives.
- Stores headers behind an injectable trait with in-memory and SQLite implementations, persists a canonical `hash_by_height` index for reorg-aware best-chain lookups, appends canonical tip updates for normal chain growth, validates the exact mainnet genesis header, enforces HSD-compatible mainnet difficulty retarget bits, and rejects non-genesis headers that fail proof-of-work.
- Parses and synthesizes bounded DNS messages, questions, names, resource records, and RFC 9460 SVCB/HTTPS RDATA.
- Decodes HSD name resource values into DNS-style DS, NS, in-zone glue A/AAAA, synthetic glue A/AAAA, and TXT records; delegated nameserver DoH transport is bootstrapped from proof-anchored `hnsdns=1` metadata when present or discovered from RFC 9461 DNS-server SVCB records in authoritative DNS.
- Parses DNSSEC DNSKEY/DS/RRSIG/NSEC/NSEC3 records, computes RFC 4034 key tags, verifies SHA-1, SHA-256, and SHA-384 DS-to-DNSKEY delegation links, builds canonical RRSIG signed data including canonical RDATA names for CNAME, NS, SOA, SRV, and SVCB/HTTPS TargetName, verifies RSA/SHA-1 compatibility, RSA/SHA-256, RSA/SHA-512, ECDSA P-256/SHA-256, ECDSA P-384/SHA-384, and Ed25519 RRset signatures, and composes those checks into fail-closed signed-RRset, delegated-chain, NSEC no-data, NSEC name-range, NSEC name-error, and RFC 5155 NSEC3 denial validators. Multi-owner NSEC denial verifies each covering RRset only against its same-owner/class RRSIGs.
- Encodes and decodes the HSD packet subset needed for header sync and proof requests, including HSD-compatible 9-byte wire framing, 88-byte HSD network addresses in version and addr packets, version/verack ordering tolerance, advisory/unknown packet tolerance during sync waits, transient-failure peer recovery with bounded malformed-peer bans, and a blocking TCP peer connection for getaddr, getheaders, and getproof flows.
- Adds an optional experimental HNS P2P recursive-DNS requester after local proof validation and direct authoritative DNS attempts. Mobile relay consumption is off by default and requires explicit user opt-in; migrations preserve an independent prior choice but never turn former public-resolver consent into relay consent. Relayed answers remain untrusted input to local DNSSEC, HTTPS/SVCB, TLSA, and DANE validation. Settings accepts manual relay peers only as IP-literal `IPv4:port` or `[IPv6]:port` endpoints, and persists one only after a live HSD handshake confirms the current relay capability. This mobile repository does not make the browser an output node. In the companion network stack, opaque relayer capacity is a default-on/opt-out role, while serving as an output node remains explicit opt-in.
- Adds an independently configured recursive HNS DoH recovery path that is blank/off by default. It is tried only after direct authoritative DNS, owner-published authenticated authoritative DoH, and any opted-in P2P requester are exhausted by an eligible transport failure. Resolver hostnames are bootstrapped only through validating ICANN DoH, endpoint TLS uses WebPKI, and returned DNS remains untrusted until local HNS proof, DNSSEC, TLSA, and DANE checks succeed. Bogus DNSSEC and stale or missing HNS proofs never reach recovery.
- Adds parser fuzz smoke targets for DNS messages/names/SVCB, HNS resource values, P2P frames/payloads, Urkel proofs, TLSA records, and bounded X.509 SPKI extraction.
- Provides sync coordinators for version/verack, getaddr/addr peer discovery, getheaders/headers ingestion with duplicate-header tolerance, locator construction, remote-height-aware no-op sync when peers are not ahead of the local best header, bounded multi-batch header sync across selected peers with persisted peer outcomes, same-run getaddr discovery rotation toward the peer-table target, and versioned Android/iOS currentness status derived from a recent outlier-resistant target corroborated by at least three peer address groups. The validated local tip may trail that target by at most two blocks; raw maximum peer claims and the schedule estimate are diagnostic only. The same layer handles DNS seed refresh while the peer table is below target, tracked getproof/proof flow control, upstream-compatible Urkel proof verification, verified HSD `NameState.data` value handoff, and proof scheduling into the resolver resource-value store.
- Stages header network I/O, quorum collection, snapshot preparation, and peer
  merging in a private database. A short generation-and-tip-bound publication
  step atomically exposes headers, peers, and readiness; an unchanged-header
  peer refresh does not invalidate active requests, and incomplete,
  superseded, or cross-process-conflicting state fails closed. Final peer
  corroboration is timestamped when the long sync finishes rather than when it
  starts.
- Implements full-host dual-root classification with five outcomes: HNS only, ICANN only, both convergent, both divergent, or neither. Explicit pins take precedence over a successful sticky binding and the ICANN default. Each root independently resolves complete A+AAAA endpoint evidence and HTTPS/SVCB policy; the chosen immutable plan alone supplies endpoints, protocol, TLSA owner, trust policy, trace attribution, and errors, with no post-selection DNS. DANE matching remains strict, while ICANN WebPKI is narrowly admitted only after authenticated TLSA denial or a proven unsigned zone; bogus DNSSEC is never treated as absence. HNS address presence without the required TLSA is a root failure, not authenticated HNS absence, so it cannot silently select ICANN.
- Uses the canonical engine session, policy generation, authority lifecycle, and schema-v2 status beneath both mobile shells. One random 16-byte session backs both the unchanged proxy capability token and the canonical runtime identity. A fresh listener may exist while sync is incomplete, but it remains degraded and non-admitting until a current non-genesis header on every network, the proof store, and a policy-permitted resolution transport are factually ready. Policy no-ops do not churn generations; replacement, stop, and drop revoke only the exact listener generation.
- Mints one canonical authority stamp at whole-request entry, before DNS or namespace classification, and carries that exact stamp through resolution, origin work, status construction, sticky-namespace commit, and the per-result response or tunnel-head publication capability. That capability also captures the exact header-maintenance epoch under a shared read lock. Final publication reacquires and validates the epoch, then retains both the maintenance and authority guards through the response or HTTP 101 head; sync, cache clear, snapshot install, and header reset advance the epoch while holding the exclusive maintenance lock. Therefore maintenance either invalidates an older result before publication or waits until the already-authorized head is committed. A same-generation degrade/recover transition cannot revive stale work; unstamped locally generated backend errors and post-admission JNI fallbacks are suppressed. File-backed direct responses remain hidden in a same-directory staging file until exact-stamp commit atomically renames them. Typed success and failure status is published only after authorized response publication and only from the request-local retained namespace decision or exact typed root failure plus actual selected-root DNS question. A verifier-native DANE association mismatch survives HTTP/1.1, HTTP/2, HTTP/3, and TLS-upgrade error wrapping without message matching; it proves DANE failure but does not fabricate a separate origin-SNI failure. Bogus DNSSEC remains distinct from TLSA absence, generic transport/WebPKI failures with insufficient typed evidence remain explicitly unavailable, and ICANN-selected status deliberately has no HNS chain anchor. Cached or legacy paths that cannot honestly supply exact transport evidence, including a relay without negotiated registry identity, report an explicit canonical-status-unavailable reason instead of fabricating schema fields.
- Retains the off-by-default experimental stateless DANE parser as legacy research code. The immutable prepared namespace-plan boundary cannot represent certificate-discovered evidence without a second resolution authority, so enabling the legacy mode on a prepared browser request now fails closed; it does not fall back to a post-selection live resolver. HIP-0017 extension OIDs remain TBD, and the project-local experimental OIDs are not a final interop commitment.
- Provides peer scoring, banning, static peer seeding, HSD-compatible DNS seed discovery, bounded rotating getaddr peer discovery, SQLite peer-state persistence, address-group-aware outbound peer selection, LRU-bounded TTL resolver positive and verified-negative caching primitives, in-memory and SQLite verified resource-value providers, resource-cache byte accounting, chain-root/height anchoring, current-tip cache invalidation, active cap enforcement, clear-cache support, a proof-provider-backed HNS resolver boundary that can extract verified HSD resource values, distinguishes verified non-inclusion from existing names with no origin address, extracts final-label HNS roots for dotted HNS hosts, hydrates out-of-zone HNS nameserver addresses from their own verified root proofs, filters proven DNS-style records fail-closed, bootstraps RFC 8484 authoritative DoH from proof-anchored `hnsdns=1` transport metadata or RFC 9461 `_dns.<nameserver>` SVCB discovery, and a DNSSEC-gated delegation boundary for HNS roots with NS/DS records backed by authoritative DoH or UDP DNS with TCP fallback, signed positive RRset validation, bounded CNAME-chain validation, signed child-referral validation with child CNAME-chain handling, parent/child NSEC/NSEC3 no-data validation, and delegated NXDOMAIN name-error validation. If UDP DNSSEC failure is accompanied by a matching reply to the bounded TEST-NET sentinel, interception is cached and TCP plus remaining direct nameservers are suppressed. Resolution then tries proof-authenticated authoritative DoH and, only under explicit requester opt-in, the policy-admitted P2P relay; failure of all admitted alternatives remains typed and fail-closed.
- Provides bounded HTTP/1.1 origin transport over TCP or rustls TLS with same-origin keep-alive pooling, HTTPS rustls session resumption scoped to the active DANE/ICANN-WebPKI policy, safe same-port Alt-Svc promotion to HTTP/2 or HTTP/3, HTTPS HTTP/2 origin transport over Tokio/Rustls, and HTTPS HTTP/3 origin transport over Quinn/h3 with DANE validation bound to the QUIC TLS handshake, with gateway routing only from owner-matching A/AAAA answers or validated CNAME-chain terminal A/AAAA answers to transport connect addresses, delegated origin A/AAAA lookup when Android starts from all root records, exact `_port._tcp.host` TLSA lookup for TCP and `_port._udp.host` for HTTP/3, and HTTPS/SVCB ALPN and service-port policy selection constrained to implemented origin protocols. One selected service record yields ordered HTTP/3, HTTP/2, and RFC 9460 implicit-or-explicit HTTP/1.1 candidates; HNS retries a later protocol only after securely authenticated TLSA absence for the current transport owner, while insecure, bogus, or malformed TLSA is terminal. Origin response framing remains fail-closed for unsupported transfer codings or ambiguous lengths, response bodies stream to a writer, and missing origin addresses or invalid delegated responses produce actionable failures. HNS address and TLSA data must be secure; ICANN data comes from the validating bounded DoH path.
- Adds gateway-time live proof fetching on verified-resource cache miss from peers at or above the local anchor height, storing Urkel-verified values anchored to the current best header before origin routing, plus native HNS WebSocket/HTTP Upgrade stream tunneling after HNS resolution, HTTPS/SVCB policy, and DANE validation. There is no automatic public recursive HNS resolver and no HNS WebPKI fallback. A user may separately configure recovery DoH, but its DNS answers still require local DNSSEC/TLSA/DANE; proof-anchored authoritative HNS DoH and ordinary ICANN DoH/WebPKI remain distinct paths.
- Packages the Rust FFI core for `armeabi-v7a`, `arm64-v8a`, and `x86_64`;
  build selection can produce a bounded universal device APK without changing
  the application identity.
- Adds an Android WebView shell whose omnibox parses addresses but does not classify DNS namespaces. Every canonical DNS hostname enters the retained Rust gateway; the vendored IANA list is legacy diagnostic data only and is not authoritative routing input. The runtime resolves the complete host independently through HNS and ICANN, compares complete A+AAAA endpoint sets and service policy, and retains exactly one authenticated plan. The shell reports the selected namespace and DANE/WebPKI outcome from that trusted result.
- Gates every HTTP(S) main-frame navigation through `BrowserProxyCoordinator`. The latest load waits until the process-global AndroidX proxy override is owned and a whole-WebView endpoint is started and applied; policy transitions, suspension, or ownership loss immediately withdraw routing, authentication, certificate trust, and typed status publication. Redirects, subresources, Service Workers, downloads, and native WebSockets use the same lower Rust boundary. Because Android WebView does not expose a Service Worker TLS challenge to the page client, admitted worker requests execute through the shared Rust runtime gateway instead of the local CONNECT certificate path.
- Selects the platform-neutral Rust proxy exclusively. It exposes a fresh authenticated loopback HTTP/CONNECT endpoint, routes HNS and DNS-named ICANN HTTPS requests through the shared persistent runtime, terminates CONNECT with Rust-owned per-host local TLS identities, forwards validated native WebSocket/HTTP Upgrade streams, and supplies bounded typed main-frame security status. Android proceeds past the expected local TLS error only when the full certificate DER matches the exact host and live proxy generation.
- Falls back only to the compatibility interceptor if the Rust proxy cannot start. It supports bodyless GET/HEAD requests and bounded same-origin redirects; body-bearing Service Worker/interceptor requests fail closed because WebView does not expose their bodies or local TLS challenges. All origin TLSA/DANE decisions remain in Rust.
- Uses the same fail-closed whole-browser proxy model for WebKit. DNS-named ICANN HTTPS/WSS is locally terminated and sent through the shared validating gateway; secure TLSA is enforced, while WebPKI is permitted only for authenticated TLSA absence or a proven insecure delegation. DNSSEC bogus/indeterminate results, malformed TLSA, resolver timeout/error, private/special destinations, and unsafe ports fail closed. Public IP literals have no TLSA owner and retain bounded opaque CONNECT/WebPKI without system target DNS.
- Exposes the shared runtime through a versioned `ios-ffi` C ABI with opaque monotonic handles, Rust-owned result buffers, bounded status mailboxes, one active proxy per runtime, immediate lifecycle revocation, and live generation/host/certificate matching. Apple device and simulator slices are packaged as `HnsBrowserRuntime.xcframework`.
- Adds an iOS 17.0-or-later UIKit/WKWebView shell using one persistent website-data-store profile and an authenticated, no-failover whole-browser proxy configuration. Swift performs namespace-agnostic URL parsing; every canonical DNS hostname is classified by the same retained Rust dual-root plan used on Android. The deployment floor retains support for the iOS 17 and iOS 18 generations, while Apple builds use the stable iOS 26.5 SDK with Xcode 26.5 or 26.6. Swift owns navigation admission, WebView reconstruction, downloads, UI, and exact live server-trust challenge integration; resolution, DNSSEC, DANE, HTTP parsing, proxying, and TLS termination remain in Rust.

## Platform Migration Status

Android has completed its Rust-only proxy cutover: `MainActivity` uses the
shared Rust runtime and proxy, while Kotlin owns only platform UI, WebView
admission, lifecycle, and JNI conversion. The Apple C ABI, XCFramework build,
and native iOS shell use the same runtime and proxy, and version `0.5.5` is
public on the Apple App Store. Linux validates the Rust, ABI, header, and
architecture boundaries; macOS compilation and simulator tests against the
iOS 26.5 SDK form the Apple build gate. The signed physical-device matrix in
`docs/ios-device-validation.md` is not an archive, upload, or App Store
submission prerequisite, but it remains an open installed-iOS and ecosystem
qualification gate for WebKit behavior that simulator success cannot
establish.

## Validate

```sh
./scripts/check.sh
./scripts/fuzz-smoke.sh
```

Android builds on ARM64 host use APK Workbench:

```sh
APK_WORKBENCH="$HOME/APK_Workbench"
GRADLE="$APK_WORKBENCH/scripts/dev/apkw-gradle.sh"

./scripts/build-android.sh

"$GRADLE" --project-dir "$PWD/android" testDebugUnitTest

"$GRADLE" \
  --project-dir "$PWD/android" \
  connectedDebugAndroidTest
```

Cargo and platform builds consume direct HNS, engine, and browser-adapter
crates from the released `0.3.1` / `0.2.x`–`0.3.x` registry cohort, plus the
wallet closure from one reviewed immutable Git revision. See the
[released dependency cohort](docs/released-dependency-cohort.md) for the
version and release provenance.
The umbrella
`hns-dane-engine` facade is intentionally not in the mobile dependency graph:
that source currently brings its public OpenSSL-backed DANE/DNSSEC stack into
Android and Apple target closures. A mobile-safe upstream facade split or
feature boundary is therefore a prerequisite for populating the mobile seam
with verified HNSA authority or adopting HNSR requester APIs.

Android and iOS now share a dormant, one-shot wallet-consumer boundary for the
exact HRM profile `hns.named-service/v1`. It binds the trusted application
selection `(network magic, HNS name hash, canonical service name, nonzero
application profile ID)` to the exact live wallet, HRM sequence and envelope
hash, subject-wide aggregate revision, one trusted operation time, fenced lease
generation, service resource, delegation, controller, intervals, capabilities,
and constraints. Admission rechecks wallet/application state before source
acquisition, after its potentially re-entrant callback, and while an opaque
broker guard holds exact current authority through dependent use. Kotlin and
Swift perform defensive shape checks only; they do not parse HRM/HNSA objects,
verify signatures, persist rollback state, or infer identity from a URL,
endpoint, provider message, or legacy record. Shipping sources are immutable
unavailable implementations, and the dedicated release gate remains false.
No HNSA selector, endpoint/profile validator, HNSR requester, socket adapter,
provider role, or native control exists here. No sibling coordination checkout
is required.

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

On macOS with Xcode 26.5 or 26.6, the stable iOS 26.5 SDK, and the configured Apple Rust targets:

```sh
./scripts/run-ios-gate.sh
```

The gate verifies the selected Xcode and exact SDK, installs the pinned Rust
toolchain and Apple targets, checks the ABI and platform boundaries, creates
`build/apple/HnsBrowserRuntime.xcframework`, selects an iOS 26.5 iPhone
simulator, executes the test target, and links an unsigned Release build
against the arm64 device slice. This validates the Apple build, linkage, and
simulator tests only; see `docs/ios-device-validation.md` for the still-open
signed physical-device qualification matrix.

Debug/demo builds are unsigned beyond the default Android debug key and are intended for testing only. The diagnostics screen identifies Denuo Web, LLC as publisher, shows the build channel and license, and states that donations are optional and unlock no app features.

The Android build runs `scripts/build-rust-android.sh` through Gradle and builds `android-ffi` with pinned `cargo-ndk`. Release JNI outputs retain line-table debug information long enough for the Android Gradle Plugin to strip the shipping libraries and package native symbols; the libraries ship under `lib/<abi>/libhns_dane_browser_ffi.so`.

## Support

Donations are optional and do not unlock any app features.

- HNS donation address: `hs1q5997733eq7f4yyk2vq2z8gz3yqyvpz422ypggh`

## License

This repository is source-available under the PolyForm Noncommercial License 1.0.0. Noncommercial use, study, modification, and redistribution are allowed under the license. Commercial use requires separate written permission from Denuo Web, LLC.

Source code: https://github.com/handshake-rs/hns-dane-browser-mobile
