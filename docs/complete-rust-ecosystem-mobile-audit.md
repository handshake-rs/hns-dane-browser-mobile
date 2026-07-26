# Complete Rust Handshake Ecosystem: Mobile Delta Audit

Last audited: 2026-07-25

This audit maps this checkout only to `Complete Rust Handshake Ecosystem.pdf`
(57 pages, SHA-256
`51dc7363ecc7c597c11de531fbeb1f45f3c6997a4d7b2c5065cd4be9681e7868`).
It does not claim that the coordination-wide PDF is complete.

- Repository: `https://github.com/Denuo-Web/hns-dane-browser.git`
- Starting commit: `6c1d7888ae804a29ab34051cb1267057942ad0a0`
- Working branch: `codex/shared-engine-p2p-privacy-transports`
- Platforms in scope: Android WebView/JNI and iOS WKWebView/Apple C ABI

## Requirement Status

| PDF requirement | Status in this checkpoint | Evidence or remaining work |
| --- | --- | --- |
| Retain Android, iOS, Kotlin, Swift, JNI, Apple ABI, WebView, WKWebView, lifecycle, packaging, store metadata, and CI | Retained | The existing platform shells and release workflows remain. The JNI and C ABI keep their legacy field layout for upgrade compatibility. |
| HNS HTTPS never falls back to WebPKI | Implemented locally | `hns-gateway` always selects `HnsStrict` for HNS names; the compatibility trust variant and transport constructor were removed. Missing, insecure, bogus, or mismatched TLSA remains fail-closed. |
| Automatic ICANN DANE with constrained WebPKI | Implemented through the standalone engine contract | Android and iOS route every DNS-named ICANN HTTPS/WSS request through the shared Rust gateway. `hns-icann-dane` derives `_port._tcp.host.` after HTTPS/SVCB selection, uses `_port._udp.host.` for HTTP/3, classifies authenticated validating-DoH evidence, enforces DNSSEC-secure TLSA, and admits WebPKI only for authenticated absence or a proven insecure delegation. Bogus/indeterminate DNSSEC, malformed TLSA, timeout, resolver error, invalid owner derivation, and malformed/looping TLSA CNAME chains fail closed. The former single-host exception is removed. |
| No third-party public recursive HNS resolver | Implemented locally | Public recursive HNS DoH resolver code and fallback composition were removed. Rust normalizes initial, updated, raw-gateway, JNI, and Apple-ABI policy to strict mode and discards legacy endpoint input. Proof-anchored authoritative DoH and ordinary ICANN DoH/WebPKI remain distinct supported paths. |
| P2P DNS Relay: On/Off | Implemented | Both native settings surfaces expose the requester control. Fresh installs default on as required. Existing independent relay choices are preserved. |
| Explicit migration without turning old HNSDoH consent into relay consent | Implemented | Android and iOS erase legacy resolver/trust fields. If an explicit legacy compatibility choice exists without an independent relay choice, the migrated relay setting is off. Fresh installs retain the relay-on requester default. |
| P2P ODoH: Preferred/Required/Direct Allowed/Off | Not implemented in this checkout | No HIP #77 requester, HPKE/ODoH runtime, status model, or native control exists here. Do not represent the current direct relay as ODoH or query-confidential. |
| HNSR: Off/Client/Endpoint | Not implemented in this checkout | No HIP #78 runtime or native control exists here. Mobile lifecycle, network-change, renewal, withdrawal, and stale-generation tests for HNSR remain required. |
| Consume the standalone `hns-dane-engine` | Integrated for automatic ICANN DANE | The Rust workspace has a path dependency on sibling crate `hns-icann-dane` from engine commit `f8e8d77`. Owner derivation, transport type, validating-DoH evidence, and browser TLS decisions come from that crate; the mobile gateway and transport enforce the returned decision. The complete runtime has not otherwise migrated every repository-local crate into the standalone engine. |
| Browser authority state machine and generation-bound results | Partial | The local proxy has revocable generations and strict certificate admission, but this checkout does not expose the complete PDF state/evidence schema (`runtime session`, policy generation, registry fingerprint/profile, ODoH identities, and all explicit evidence states). |
| Relay/ODoH observability | Partial | Existing relay traces distinguish the relay from authoritative transports and report local DNSSEC/TLSA/DANE decisions. ODoH privacy policy, proxy/target separation, registry profile, and complete shared observability fields are unavailable because HIP #77 is absent. |
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

## Qualification Boundaries

Portable Rust, Android source, and host Apple-ABI checks can run on Linux. The
complete iOS gate still requires macOS, Xcode 26.5/26.6, the iOS 26.5 SDK, and
an iOS simulator; signed device behavior requires a physical or external
TestFlight pass. Android instrumentation requires an installed SDK/NDK and a
device or emulator. Store binaries and previously recorded hashes must be
rebuilt after this source checkpoint before they can be release evidence.

The Rust build requires a sibling `work/hns-dane-engine` checkout containing
`hns-icann-dane` at or compatible with engine commit `f8e8d77`; this is a
coordination-workspace source dependency, not a crates.io package.

The source covers normal navigation, same- and cross-origin redirects through
the live proxy, subresources (including public-ICANN cross-origin requests while
an HNS scope is active), native WebSockets, bodyless Service Worker GET/HEAD
requests, and native downloads. Android's compatibility/Service Worker
interceptor cannot recover request bodies that WebView does not expose, so those
requests fail closed; its manual redirect fallback is same-origin. Canonical
public IP literals retain bounded WebPKI compatibility only through a live
opaque proxy tunnel and fail closed when that tunnel is unavailable. WSS,
Service Worker, download, HTTP/3, renderer restart, and lifecycle behavior still
require the physical-device matrices—portable tests do not prove WebView or
WebKit network-process behavior.

## Checkpoint Verification

The following source-level gates passed on Linux on 2026-07-25:

- `cargo +1.92.0 clippy --locked --offline --manifest-path rust/Cargo.toml
  --workspace --all-targets -- -D warnings` and `cargo +1.92.0 fmt
  --manifest-path rust/Cargo.toml --all -- --check`.
- Locked/offline test suites for every changed Rust package: `hns-icann-dane`
  (7 tests), `hns-transport` (50), `android-ffi` (11),
  `hns-browser-runtime` (119), `hns-gateway` (48), and
  `hns-loopback-proxy` (148).
- `cargo +1.92.0 build --locked --offline --manifest-path rust/Cargo.toml
  --workspace --release` — optimized portable workspace build.
- `CARGO_NET_OFFLINE=true ./scripts/check-ios-abi.sh` — locked `ios-ffi`
  tests/build, C and C++ header compilation, and exact archive/exported-symbol
  checks.
- `./scripts/check-version-consistency.sh`,
  `./scripts/check-runtime-boundaries.sh`, generated-third-party-notice
  verification, all 19 portable iOS screenshot-tool tests, and
  `git diff --check`.

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
