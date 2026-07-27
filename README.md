# HNS DANE Browser

Cross-platform Handshake-first browser core with local HNS proofs, authoritative DNS, an experimental requester-only HNS P2P DNS relay, optional user-configured recovery DoH, DNSSEC, and DANE diagnostics. Android is the validated shipping baseline; the repository also contains the native iOS 17.0-or-later shell and Apple ABI/build integration. The Apple build and simulator gate uses the stable iOS 26.5 SDK with Xcode 26.5 or 26.6; a signed external-TestFlight device pass is the recommended final iOS release gate and has not been completed.

Canonical source lives at
[`handshake-rs/hns-dane-browser-mobile`](https://github.com/handshake-rs/hns-dane-browser-mobile).

The coordination-PDF mobile delta and its still-open ODoH, HNSR, and
device-qualification work are tracked in
[`docs/complete-rust-ecosystem-mobile-audit.md`](docs/complete-rust-ecosystem-mobile-audit.md).

## Layout

- `rust/`: Cargo workspace for consensus primitives, header chain, Urkel proof interfaces, resolver, DNSSEC, DANE, transport, gateway, cache, the shared browser runtime, the platform-neutral loopback proxy, Android JNI, and the stable Apple C ABI. It consumes `hns-browser-runtime`, `hns-browser-observability`, `hns-icann-dane`, `hns-namespace-resolution`, and `hns-resolution-policy` from immutable `handshake-rs/hns-dane-engine` commit `7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`.
- `rust/fuzz/`: `cargo-fuzz` parser harnesses for DNS, HNS resource values, P2P frames, Urkel proofs, TLSA records, and X.509 SPKI extraction.
- `android/`: Kotlin Android browser shell with WebView, namespace-agnostic URL admission, whole-browser proxy lifecycle integration, and a thin JNI bridge.
- `ios/`: Swift/UIKit WKWebView shell with whole-data-store proxy admission, lifecycle/certificate integration, and a generated Xcode project definition.
- `fixtures/`: Header, Urkel, and DNS fixture slots for HSD/HNSD comparison data.
- `docs/`: Architecture, security model, version audit, and milestone notes.
- `docs/sync-audit.md`: first-run sync path, progress UI, and remaining sync-speed bottlenecks.
- `docs/supply-chain-audit.md`: pinned build inputs, CI/release gates, and residual reproducibility risks.
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
- Implements full-host dual-root classification with five outcomes: HNS only, ICANN only, both convergent, both divergent, or neither. Explicit pins take precedence over a successful sticky binding and the ICANN default. Each root independently resolves complete A+AAAA endpoint evidence and HTTPS/SVCB policy; the chosen immutable plan alone supplies endpoints, protocol, TLSA owner, trust policy, trace attribution, and errors, with no post-selection DNS. DANE matching remains strict, while ICANN WebPKI is narrowly admitted only after authenticated TLSA denial or a proven unsigned zone; bogus DNSSEC is never treated as absence. HNS address presence without the required TLSA is a root failure, not authenticated HNS absence, so it cannot silently select ICANN.
- Uses the canonical engine session, policy generation, authority lifecycle, and schema-v2 status beneath both mobile shells. One random 16-byte session backs both the unchanged proxy capability token and the canonical runtime identity. A fresh listener may exist while sync is incomplete, but it remains degraded and non-admitting until a current non-genesis header on every network, the proof store, and a policy-permitted resolution transport are factually ready. Policy no-ops do not churn generations; replacement, stop, and drop revoke only the exact listener generation.
- Mints one canonical authority stamp at whole-request entry, before DNS or namespace classification, and carries that exact stamp through resolution, origin work, status construction, sticky-namespace commit, and the per-result response or tunnel-head publication capability. That capability also captures the exact header-maintenance epoch under a shared read lock. Final publication reacquires and validates the epoch, then retains both the maintenance and authority guards through the response or HTTP 101 head; sync, cache clear, snapshot install, and header reset advance the epoch while holding the exclusive maintenance lock. Therefore maintenance either invalidates an older result before publication or waits until the already-authorized head is committed. A same-generation degrade/recover transition cannot revive stale work; unstamped locally generated backend errors and post-admission JNI fallbacks are suppressed. File-backed direct responses remain hidden in a same-directory staging file until exact-stamp commit atomically renames them. Typed success and failure status is published only after authorized response publication and only from the request-local retained namespace decision or exact typed root failure plus actual selected-root DNS question. A verifier-native DANE association mismatch survives HTTP/1.1, HTTP/2, HTTP/3, and TLS-upgrade error wrapping without message matching; it proves DANE failure but does not fabricate a separate origin-SNI failure. Bogus DNSSEC remains distinct from TLSA absence, generic transport/WebPKI failures with insufficient typed evidence remain explicitly unavailable, and ICANN-selected status deliberately has no HNS chain anchor. Cached or legacy paths that cannot honestly supply exact transport evidence, including a relay without negotiated registry identity, report an explicit canonical-status-unavailable reason instead of fabricating schema fields.
- Retains the off-by-default experimental stateless DANE parser as legacy research code. The immutable prepared namespace-plan boundary cannot represent certificate-discovered evidence without a second resolution authority, so enabling the legacy mode on a prepared browser request now fails closed; it does not fall back to a post-selection live resolver. HIP-0017 extension OIDs remain TBD, and the project-local experimental OIDs are not a final interop commitment.
- Provides peer scoring, banning, static peer seeding, HSD-compatible DNS seed discovery, bounded rotating getaddr peer discovery, SQLite peer-state persistence, address-group-aware outbound peer selection, LRU-bounded TTL resolver positive and verified-negative caching primitives, in-memory and SQLite verified resource-value providers, resource-cache byte accounting, chain-root/height anchoring, current-tip cache invalidation, active cap enforcement, clear-cache support, a proof-provider-backed HNS resolver boundary that can extract verified HSD resource values, distinguishes verified non-inclusion from existing names with no origin address, extracts final-label HNS roots for dotted HNS hosts, hydrates out-of-zone HNS nameserver addresses from their own verified root proofs, filters proven DNS-style records fail-closed, bootstraps RFC 8484 authoritative DoH from proof-anchored `hnsdns=1` transport metadata or RFC 9461 `_dns.<nameserver>` SVCB discovery, and a DNSSEC-gated delegation boundary for HNS roots with NS/DS records backed by authoritative DoH or UDP DNS with TCP fallback, signed positive RRset validation, bounded CNAME-chain validation, signed child-referral validation with child CNAME-chain handling, parent/child NSEC/NSEC3 no-data validation, and delegated NXDOMAIN name-error validation. If UDP DNSSEC failure is accompanied by a matching reply to the bounded TEST-NET sentinel, interception is cached and TCP plus remaining direct nameservers are suppressed. Resolution then tries proof-authenticated authoritative DoH and, only under explicit requester opt-in, the policy-admitted P2P relay; failure of all admitted alternatives remains typed and fail-closed.
- Provides bounded HTTP/1.1 origin transport over TCP or rustls TLS with same-origin keep-alive pooling, HTTPS rustls session resumption scoped to the active DANE/ICANN-WebPKI policy, safe same-port Alt-Svc promotion to HTTP/2 or HTTP/3, HTTPS HTTP/2 origin transport over Tokio/Rustls, and HTTPS HTTP/3 origin transport over Quinn/h3 with DANE validation bound to the QUIC TLS handshake, with gateway routing only from owner-matching A/AAAA answers or validated CNAME-chain terminal A/AAAA answers to transport connect addresses, delegated origin A/AAAA lookup when Android starts from all root records, exact `_port._tcp.host` TLSA lookup for TCP and `_port._udp.host` for HTTP/3, and HTTPS/SVCB ALPN and service-port policy selection constrained to implemented origin protocols. One selected service record yields ordered HTTP/3, HTTP/2, and RFC 9460 implicit-or-explicit HTTP/1.1 candidates; HNS retries a later protocol only after securely authenticated TLSA absence for the current transport owner, while insecure, bogus, or malformed TLSA is terminal. Origin response framing remains fail-closed for unsupported transfer codings or ambiguous lengths, response bodies stream to a writer, and missing origin addresses or invalid delegated responses produce actionable failures. HNS address and TLSA data must be secure; ICANN data comes from the validating bounded DoH path.
- Adds gateway-time live proof fetching on verified-resource cache miss from peers at or above the local anchor height, storing Urkel-verified values anchored to the current best header before origin routing, plus native HNS WebSocket/HTTP Upgrade stream tunneling after HNS resolution, HTTPS/SVCB policy, and DANE validation. There is no automatic public recursive HNS resolver and no HNS WebPKI fallback. A user may separately configure recovery DoH, but its DNS answers still require local DNSSEC/TLSA/DANE; proof-anchored authoritative HNS DoH and ordinary ICANN DoH/WebPKI remain distinct paths.
- Packages the Rust FFI core into the APK for `arm64-v8a` and `x86_64`.
- Adds an Android WebView shell whose omnibox parses addresses but does not classify DNS namespaces. Every canonical DNS hostname enters the retained Rust gateway; the vendored IANA list is legacy diagnostic data only and is not authoritative routing input. The runtime resolves the complete host independently through HNS and ICANN, compares complete A+AAAA endpoint sets and service policy, and retains exactly one authenticated plan. The shell reports the selected namespace and DANE/WebPKI outcome from that trusted result.
- Gates every HTTP(S) main-frame navigation through `BrowserProxyCoordinator`. The latest load waits until the process-global AndroidX proxy override is owned and a whole-WebView endpoint is started and applied; policy transitions, suspension, or ownership loss immediately withdraw routing, authentication, certificate trust, and typed status publication. Redirects, subresources, Service Workers, downloads, and native WebSockets use the same lower Rust boundary. Because Android WebView does not expose a Service Worker TLS challenge to the page client, admitted worker requests execute through the shared Rust runtime gateway instead of the local CONNECT certificate path.
- Selects the platform-neutral Rust proxy exclusively. It exposes a fresh authenticated loopback HTTP/CONNECT endpoint, routes HNS and DNS-named ICANN HTTPS requests through the shared persistent runtime, terminates CONNECT with Rust-owned per-host local TLS identities, forwards validated native WebSocket/HTTP Upgrade streams, and supplies bounded typed main-frame security status. Android proceeds past the expected local TLS error only when the full certificate DER matches the exact host and live proxy generation.
- Falls back only to the compatibility interceptor if the Rust proxy cannot start. It supports bodyless GET/HEAD requests and bounded same-origin redirects; body-bearing Service Worker/interceptor requests fail closed because WebView does not expose their bodies or local TLS challenges. All origin TLSA/DANE decisions remain in Rust.
- Uses the same fail-closed whole-browser proxy model for WebKit. DNS-named ICANN HTTPS/WSS is locally terminated and sent through the shared validating gateway; secure TLSA is enforced, while WebPKI is permitted only for authenticated TLSA absence or a proven insecure delegation. DNSSEC bogus/indeterminate results, malformed TLSA, resolver timeout/error, private/special destinations, and unsafe ports fail closed. Public IP literals have no TLSA owner and retain bounded opaque CONNECT/WebPKI without system target DNS.
- Exposes the shared runtime through a versioned `ios-ffi` C ABI with opaque monotonic handles, Rust-owned result buffers, bounded status mailboxes, one active proxy per runtime, immediate lifecycle revocation, and live generation/host/certificate matching. Apple device and simulator slices are packaged as `HnsBrowserRuntime.xcframework`.
- Adds an iOS 17.0-or-later UIKit/WKWebView shell using one persistent website-data-store profile and an authenticated, no-failover whole-browser proxy configuration. Swift performs namespace-agnostic URL parsing; every canonical DNS hostname is classified by the same retained Rust dual-root plan used on Android. The deployment floor retains support for the iOS 17 and iOS 18 generations, while Apple builds use the stable iOS 26.5 SDK with Xcode 26.5 or 26.6. Swift owns navigation admission, WebView reconstruction, downloads, UI, and exact live server-trust challenge integration; resolution, DNSSEC, DANE, HTTP parsing, proxying, and TLS termination remain in Rust.

## Platform Migration Status

Android has completed its Rust-only proxy cutover: `MainActivity` uses the shared Rust runtime and proxy, while Kotlin owns only platform UI, WebView admission, lifecycle, and JNI conversion. The Apple C ABI, XCFramework build, and native iOS shell are implemented against the same runtime and proxy. Linux validates the Rust, ABI, header, and architecture boundaries; macOS compilation and simulator tests against the iOS 26.5 SDK form the Apple build gate. The signed physical-device matrix in `docs/ios-device-validation.md` is a recommended final release gate for WebKit behavior that simulator success cannot establish.

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

Cargo and platform builds fetch the five shared engine crates from immutable
`handshake-rs/hns-dane-engine` commit
`7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`; no sibling coordination checkout
is required.

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

On macOS with Xcode 26.5 or 26.6, the stable iOS 26.5 SDK, and the configured Apple Rust targets:

```sh
./scripts/run-ios-gate.sh
```

The gate verifies the selected Xcode and exact SDK, installs the pinned Rust toolchain and Apple targets, checks the ABI and platform boundaries, creates `build/apple/HnsBrowserRuntime.xcframework`, selects an iOS 26.5 iPhone simulator, executes the test target, and links an unsigned Release build against the arm64 device slice. This validates the Apple build, linkage, and simulator tests only; see `docs/ios-device-validation.md` for the recommended signed physical-device matrix before final App Review.

Debug/demo builds are unsigned beyond the default Android debug key and are intended for testing only. The diagnostics screen identifies Denuo Web, LLC as publisher, shows the build channel and license, and states that donations are optional and unlock no app features.

The Android build runs `scripts/build-rust-android.sh` through Gradle and builds `android-ffi` with pinned `cargo-ndk`. Release JNI outputs retain line-table debug information long enough for the Android Gradle Plugin to strip the shipping libraries and package native symbols; the libraries ship under `lib/<abi>/libhns_dane_browser_ffi.so`.

## Support

Donations are optional and do not unlock any app features.

- HNS donation address: `hs1q5997733eq7f4yyk2vq2z8gz3yqyvpz422ypggh`

## License

This repository is source-available under the PolyForm Noncommercial License 1.0.0. Noncommercial use, study, modification, and redistribution are allowed under the license. Commercial use requires separate written permission from Denuo Web, LLC.

Source code: https://github.com/handshake-rs/hns-dane-browser-mobile
