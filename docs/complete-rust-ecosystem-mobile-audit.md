# Complete Rust Handshake Ecosystem: Mobile Delta Audit

Last audited: 2026-07-25

This audit maps this checkout only to `Complete Rust Handshake Ecosystem.pdf`
(57 pages, SHA-256
`51dc7363ecc7c597c11de531fbeb1f45f3c6997a4d7b2c5065cd4be9681e7868`).
It does not claim that the coordination-wide PDF is complete.

- Repository: `https://github.com/Denuo-Web/hns-dane-browser.git`
- Starting commit: `a71f9ea8dd2e697df6059e8840907f96e6eea2c9`
- Working branch: `codex/shared-engine-p2p-privacy-transports`
- Platforms in scope: Android WebView/JNI and iOS WKWebView/Apple C ABI

## Requirement Status

| PDF requirement | Status in this checkpoint | Evidence or remaining work |
| --- | --- | --- |
| Retain Android, iOS, Kotlin, Swift, JNI, Apple ABI, WebView, WKWebView, lifecycle, packaging, store metadata, and CI | Retained | The existing platform shells and release workflows remain. The JNI and C ABI keep their legacy field layout for upgrade compatibility. |
| HNS HTTPS never falls back to WebPKI | Implemented locally | `hns-gateway` always selects `HnsStrict` for HNS names; the compatibility trust variant and transport constructor were removed. Missing, insecure, bogus, or mismatched TLSA remains fail-closed. |
| No third-party public recursive HNS resolver | Implemented locally | Public recursive HNS DoH resolver code and fallback composition were removed. Rust normalizes initial, updated, raw-gateway, JNI, and Apple-ABI policy to strict mode and discards legacy endpoint input. Proof-anchored authoritative DoH and ordinary ICANN DoH/WebPKI remain distinct supported paths. |
| P2P DNS Relay: On/Off | Implemented | Both native settings surfaces expose the requester control. Fresh installs default on as required. Existing independent relay choices are preserved. |
| Explicit migration without turning old HNSDoH consent into relay consent | Implemented | Android and iOS erase legacy resolver/trust fields. If an explicit legacy compatibility choice exists without an independent relay choice, the migrated relay setting is off. Fresh installs retain the relay-on requester default. |
| P2P ODoH: Preferred/Required/Direct Allowed/Off | Not implemented in this checkout | No HIP #77 requester, HPKE/ODoH runtime, status model, or native control exists here. Do not represent the current direct relay as ODoH or query-confidential. |
| HNSR: Off/Client/Endpoint | Not implemented in this checkout | No HIP #78 runtime or native control exists here. Mobile lifecycle, network-change, renewal, withdrawal, and stale-generation tests for HNSR remain required. |
| Consume the standalone `hns-dane-engine` | Not integrated | This checkout still builds its repository-local platform-neutral Rust crates. It does not yet depend on or vendor the separately extracted coordination repository, so cross-repository engine parity and duplicate-code removal remain required. |
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
- Ordinary ICANN browsing is outside the HNS trust path and retains bounded
  ICANN DoH plus browser/WebPKI validation.
- Legacy ABI constants and fields are migration tombstones, not selectable
  runtime capabilities.

## Qualification Boundaries

Portable Rust, Android source, and host Apple-ABI checks can run on Linux. The
complete iOS gate still requires macOS, Xcode 26.5/26.6, the iOS 26.5 SDK, and
an iOS simulator; signed device behavior requires a physical or external
TestFlight pass. Android instrumentation requires an installed SDK/NDK and a
device or emulator. Store binaries and previously recorded hashes must be
rebuilt after this source checkpoint before they can be release evidence.

## Checkpoint Verification

The following source-level gates passed on Linux on 2026-07-25:

- `./scripts/check.sh` — supply-chain pins, generated notices, runtime/platform
  boundaries, Rust formatting, warning-denied workspace Clippy, Apple C ABI
  compile/link checks, all locked Rust workspace unit and documentation tests,
  all configured `cargo-deny` scopes, fuzz-target compilation, and the snapshot
  exporter checks.
- `cargo +1.92.0 build --locked --offline --manifest-path rust/Cargo.toml
  --workspace --release` — optimized portable workspace build.
- `python3 dist/app-store/validate.py --metadata-only` — 11 metadata fields and
  AppIcon validation passed; the expected future-update `whats-new.txt` warning
  remains.
- `./scripts/check-version-consistency.sh`,
  `./scripts/check-runtime-boundaries.sh`,
  `./scripts/verify-supply-chain.sh`, and
  `python3 scripts/generate-third-party-notices.py --check`.
- XML parsing of every file below `android/app/src/main/res`.
- `python3 scripts/test_select_ios_simulator.py` — eight portable selector
  tests.

`./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug` could not enter
Android task execution because this host has no configured Android SDK
(`SDK location not found`). Android JVM/instrumentation/lint and Apple
XCTest/simulator/device qualification therefore remain external gates; no pass
is inferred from the portable checks.
