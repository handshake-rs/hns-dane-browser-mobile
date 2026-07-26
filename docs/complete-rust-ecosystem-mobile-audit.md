# Complete Rust Handshake Ecosystem: Mobile Delta Audit

Last audited: 2026-07-26

This audit maps this checkout only to `Complete Rust Handshake Ecosystem.pdf`
(57 pages, SHA-256
`51dc7363ecc7c597c11de531fbeb1f45f3c6997a4d7b2c5065cd4be9681e7868`).
It does not claim that the coordination-wide PDF is complete.

- Repository: `https://github.com/handshake-rs/hns-dane-browser-mobile.git`
- Starting commit: `6c1d7888ae804a29ab34051cb1267057942ad0a0`
- Working branch: `codex/shared-engine-p2p-privacy-transports`
- Platforms in scope: Android WebView/JNI and iOS WKWebView/Apple C ABI

## Requirement Status

| PDF requirement | Status in this checkpoint | Evidence or remaining work |
| --- | --- | --- |
| Retain Android, iOS, Kotlin, Swift, JNI, Apple ABI, WebView, WKWebView, lifecycle, packaging, store metadata, and CI | Retained | The existing platform shells and release workflows remain. The JNI and C ABI keep their legacy field layout for upgrade compatibility. |
| Full-host dual-root namespace decision | Implemented through the standalone engine contract | Android and iOS send every canonical DNS host to one retained Rust preparation boundary. HNS and ICANN independently resolve the complete host, A and AAAA endpoint sets, aliases, HTTPS/SVCB service policy, and transport-aware TLSA owner. The engine distinguishes HNS only, ICANN only, convergent, divergent, and neither; explicit pin precedes sticky success and the ICANN default. The selected immutable plan is the sole source of endpoints, protocol, trust, trace, and errors, and later DNS is rejected. The IANA list is no longer an authoritative browser classifier. |
| HNS HTTPS never falls back to WebPKI | Implemented locally | The selected HNS plan requires secure TLSA/DANE. Missing, insecure, bogus, or mismatched evidence remains fail-closed. Secure HNS address presence without the required TLSA is a root failure, not authenticated namespace absence, so an ICANN plan cannot hide the unresolved HNS trust policy. |
| Automatic ICANN DANE with constrained WebPKI | Implemented through the standalone engine contract | Every DNS-named HTTPS/WSS request enters the dual-root gateway. `hns-icann-dane` derives `_port._tcp.host.` or `_port._udp.host.` from the actual retained service plan, enforces DNSSEC-secure TLSA, and admits WebPKI only for authenticated denial or a proven insecure delegation. ICANN DoH connects only to pinned bootstrap addresses while authenticating the configured hostname. Bogus/indeterminate DNSSEC, malformed TLSA, timeout, resolver error, and invalid owner derivation fail closed. |
| No third-party public recursive HNS resolver | Implemented locally | Public recursive HNS DoH resolver code and fallback composition were removed. Rust normalizes initial, updated, raw-gateway, JNI, and Apple-ABI policy to strict mode and discards legacy endpoint input. Proof-anchored authoritative DoH and ordinary ICANN DoH/WebPKI remain distinct supported paths. |
| P2P DNS Relay requester: On/Off | Implemented | Both native settings surfaces expose relay consumption. Fresh installs default off and require explicit opt-in; existing independent requester choices are preserved. The browser does not become an output node. The separate network-role policy is opaque relayer capacity default-on/opt-out and output-node serving opt-in. |
| Explicit migration without turning old HNSDoH consent into relay consent | Implemented | Android and iOS erase legacy resolver/trust fields. Former resolver compatibility consent never enables relay consumption, and fresh installs remain off until explicit requester opt-in. |
| P2P ODoH: Preferred/Required/Direct Allowed/Off | Not implemented in this checkout | No HIP #77 requester, HPKE/ODoH runtime, status model, or native control exists here. Do not represent the current direct relay as ODoH or query-confidential. |
| HNSR: Off/Client/Endpoint | Not implemented in this checkout | No HIP #78 runtime or native control exists here. Mobile lifecycle, network-change, renewal, withdrawal, and stale-generation tests for HNSR remain required. |
| Consume the standalone `hns-dane-engine` | Integrated through immutable canonical source | The Rust workspace pins `hns-browser-runtime`, `hns-browser-observability`, `hns-icann-dane`, `hns-namespace-resolution`, and `hns-resolution-policy` to exact `handshake-rs/hns-dane-engine` commit `a03648ec85a115362ebc2ab24bb9ea0f1be127fc`; the lockfile records the same source. The stable mobile relay boolean maps to the shared typed requester policy (`false` → `Disabled`, `true` → `Auto`), and live resolution follows the canonical direct-authority UDP/TCP → authenticated authoritative DoH → admitted relay order while unsupported ODoH, HNSR, provider, and legacy roles remain disabled. |
| Browser authority state machine and exact-stamped results | Implemented at the shared Rust boundary | One checked random session supplies the unchanged proxy token and canonical runtime identity. Mobile policy revisions map exactly to canonical generations without no-op churn. A current non-genesis header on every network, proof/transport readiness, listener publication, exact-generation replacement/revocation, one whole-request stamp minted before DNS/classification, sticky binding plus exact-result response-head publication, staged-file commit, and tunnel I/O revocation all use the canonical state machine. Android JNI suppresses post-admission errors instead of generating unstamped output. Typed success and root-failure schema-v2 status uses the same entry stamp and request-local exact plan; bogus DNSSEC remains distinct from absence and untyped WebPKI/transport failures remain unavailable. The stable JNI and Apple C ABI layouts intentionally remain unchanged. |
| Relay/ODoH observability | Partial | Existing relay traces distinguish the relay from authoritative transports and report local DNSSEC/TLSA/DANE decisions. The schema-v2 adapter refuses to invent a relay registry fingerprint or protocol version when the legacy client did not retain negotiated identity, and reports explicit unavailability instead. ODoH privacy policy, proxy/target separation, and HIP #77 runtime evidence remain unavailable because ODoH is not implemented. |
| Foreground/background and browser restart qualification | Partial | Existing lifecycle and proxy-revocation tests remain. Exact-build physical Android and iOS device matrices, mobile network transitions, and the PDF’s ODoH/HNSR lifecycle cases remain release gates. |

## Security Invariants for the Current Feature Set

- An HNS request uses a current local header/proof path before delegated
  authoritative DNS or the optional direct P2P relay.
- Relay answers are untrusted DNS input and still require local DNSSEC, exact
  TLSA, and DANE validation.
- An unavailable direct/relay path fails closed. It cannot reopen a public
  recursive HNS DoH or HNS WebPKI path through persisted settings, internal
  headers, JNI, or the Apple C ABI.
- DNS-named ICANN HTTPS is outside the HNS proof path but still uses validating
  bounded ICANN DoH and automatic TLSA discovery. Secure TLSA is enforced;
  WebPKI is available only after authenticated absence or insecure delegation.
  Public IP literals have no TLSA owner and retain bounded WebPKI.
- Legacy ABI constants and fields are migration tombstones, not selectable
  runtime capabilities.
- The legacy stateless-DANE flag cannot be represented by a prepared immutable
  namespace plan. Enabling it fails closed instead of performing a second live
  lookup after namespace selection.
- One logical CNAME repeated across retained A, AAAA, or HTTPS observations is
  deduplicated by owner/class/target, while distinct targets remain ambiguous
  and fail closed. An NXDOMAIN response that also carries positive Answer data,
  including a CNAME, is rejected rather than cached as absence.
- Authenticated negative TTL is capped by the SOA and every participating
  NSEC/NSEC3 signature expiry. Unsigned TLSA bytes are ignored only under an
  independently proven-insecure ICANN delegation; secure, bogus, and
  indeterminate outcomes remain strict.
- A successful divergent-root selection is persisted before its response is
  exposed. If sticky-state persistence fails, proxy, direct, file-backed, and
  tunnel paths withhold the success. Once the binding revision changes,
  poisoned best-effort cache reclamation cannot suppress the already committed
  response.
- Traces retain all resolver attempts in partitioned HNS, ICANN, and diagnostic
  root evidence. Top-level namespace and trust fields remain attributable only
  to the selected immutable plan.
- A successfully bound listener is not proof of authority readiness. Fresh or
  stale local state leaves the listener degraded and non-admitting until
  a current non-genesis header on every network, proof storage, a policy
  transport, and the active bridge are factual; requests retry readiness
  without replacing the platform endpoint.
- One authority stamp is minted at whole-request entry before DNS or
  classification and carried unchanged through origin transport, status, and
  the result publication capability. Sticky namespace binding and response or
  HTTP 101 head flush occur under the same exact permit and canonical lifecycle
  lock used by replacement and revocation. Same-generation recovery cannot
  revive a stale result; unstamped generated backend/JNI errors are suppressed.
  File output is staged and atomically renamed only at final authorization. The
  permit is released before potentially blocking body/tunnel work, and stop
  signals cancellation before waiting on revocation.
- Canonical status uses `decision_fingerprint` from the retained namespace
  decision, the exact selected HNS qname/type transport event, and no HNS chain
  anchor for ICANN. Each request retains its exact plan independently of the
  shared cache. Typed root failures survive classification and status
  construction, so bogus/indeterminate DNSSEC cannot become authenticated TLSA
  absence; generic WebPKI/transport errors do not fabricate trust evidence.
  Verifier-native certificate-association failure remains typed through
  HTTP/1.1, HTTP/2, HTTP/3, and TLS Upgrade, while origin-SNI stays unavailable
  unless separately proved.
  Cached/unrepresentable evidence and legacy relay transport without negotiated
  registry identity are explicit unavailable states.

## Qualification Boundaries

Portable Rust, Android source, and host Apple-ABI checks can run on Linux. The
complete iOS gate still requires macOS, Xcode 26.5/26.6, the iOS 26.5 SDK, and
an iOS simulator; signed device behavior requires a physical or external
TestFlight pass. Android instrumentation requires an installed SDK/NDK and a
device or emulator. Store binaries and previously recorded hashes must be
rebuilt after this source checkpoint before they can be release evidence.

The five shared engine crates resolve from immutable
`handshake-rs/hns-dane-engine` commit
`a03648ec85a115362ebc2ab24bb9ea0f1be127fc`; a standalone checkout no longer
depends on the coordination workspace layout.

The source covers normal navigation, same- and cross-origin redirects through
the live proxy, subresources, native WebSockets, bodyless Service Worker GET/HEAD
requests, and native downloads under the same per-origin dual-root preparation.
Android's compatibility/Service Worker
interceptor cannot recover request bodies that WebView does not expose, so those
requests fail closed; its manual redirect fallback is same-origin. Canonical
public IP literals retain bounded WebPKI compatibility only through a live
opaque proxy tunnel and fail closed when that tunnel is unavailable. WSS,
Service Worker, download, HTTP/3, renderer restart, and lifecycle behavior still
require the physical-device matrices—portable tests do not prove WebView or
WebKit network-process behavior.

## Checkpoint Verification

The canonical-engine adoption gates passed on Linux on 2026-07-26:

- `cargo +1.92.0 clippy --locked --manifest-path rust/Cargo.toml
  --workspace --all-targets -- -D warnings` and `cargo +1.92.0 fmt
  --manifest-path rust/Cargo.toml --all -- --check`.
- `cargo +1.92.0 test --locked --manifest-path rust/Cargo.toml --workspace`,
  including the changed mobile-workspace packages:
  `hns-resolver` (66 tests), `hns-transport` (56), `android-ffi` (11),
  `ios-ffi` (12), `hns-mobile-platform-runtime` (154), `hns-gateway` (50), and
  `hns-loopback-proxy` (149).
- The seven focused immutable-Git-policy tests, exact policy verifier, runtime
  boundary checker, generated-third-party-notice verification, and
  `git diff --check`.

The preceding 2026-07-25 checkpoint also passed the locked/offline optimized
workspace build, C/C++ Apple ABI and exact exported-symbol checks, version
consistency, and all 19 portable iOS screenshot-tool tests. Those historical
results are retained as supporting evidence, not represented as fresh
2026-07-26 executions.

The consolidated `./scripts/check.sh` was not rerun because it would duplicate
the expensive Rust suites above. This checkpoint therefore does not claim a
fresh `cargo-deny`, fuzz-target, snapshot-exporter, store-metadata, Android
resource, or iOS simulator-selector result.

`./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug` could
not enter Android task execution because this host has no configured Android SDK
(`SDK location not found`). Android JVM/instrumentation/lint and Apple
XCTest/simulator/device qualification therefore remain external gates; this
Linux host has no Xcode or Swift compiler, and no pass is inferred from the
portable checks.
