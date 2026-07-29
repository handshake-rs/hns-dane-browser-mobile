# Version Audit

Audit date: 2026-07-28.

This table records the versions actually configured for the shipping build. Android runtime dependencies use stable releases; separate build-tool transitive dependencies may carry preview labels selected by AGP and are not packaged into the app.

| Component | Pinned | Audit source |
| --- | --- | --- |
| Android app | `0.5.5` / code `45` | `android/app/build.gradle.kts` |
| Shared Rust workspace | `0.5.5` | `rust/Cargo.toml` |
| iOS app | `0.5.5` / build `50` | `ios/project.yml` |
| Rust toolchain | `1.92.0` | `rust/rust-toolchain.toml` |
| Canonical engine contracts | `7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5` | Cargo manifests and lock |
| Android SDK | compile/target `37`, minimum `34` | `android/app/build.gradle.kts` |
| iOS deployment floor | `17.0` | `ios/project.yml` |
| Android Gradle Plugin | `9.2.1` | https://developer.android.com/build/releases/agp-9-2-0-release-notes |
| Gradle distribution | `9.6.1` | https://gradle.org/releases/ |
| AndroidX Activity | `1.13.0` | https://developer.android.com/jetpack/androidx/releases/activity |
| AndroidX Core | `1.18.0` | https://developer.android.com/jetpack/androidx/releases/core |
| AndroidX WebKit | `1.16.0` | https://developer.android.com/jetpack/androidx/releases/webkit |
| cargo-ndk | `4.1.2` | https://crates.io/crates/cargo-ndk/versions |
| rustls | `0.23.41` | https://crates.io/crates/rustls |
| webpki-roots | `1.0.8` | https://crates.io/crates/webpki-roots |
| rcgen | `0.14.8` | https://crates.io/crates/rcgen |
| quinn | `0.11.11` | https://crates.io/crates/quinn/versions |
| h3 | `0.0.8` | https://crates.io/crates/h3/versions |
| rusqlite | `0.39.0` | https://crates.io/crates/rusqlite |
| p256 | `0.13.2` | https://crates.io/crates/p256 |
| ring | `0.17.14` | https://crates.io/crates/ring |

Notes:

- AndroidX Activity, Core, and WebKit are pinned to their stable lines. The Gradle lock resolves Core to the same declared `1.18.0` version instead of relying on Activity's transitive upgrade.
- Gradle is pinned to the current `9.6.1` stable patch release, with both distribution and wrapper-JAR checksums verified. AGP is pinned to the current `9.2.1` stable patch release.
- AGP 9 has built-in Kotlin support, so the Android module intentionally does not apply `org.jetbrains.kotlin.android`. See https://developer.android.com/build/migrate-to-built-in-kotlin.
- DNSSEC RSA/SHA-1 compatibility, ECDSA P-256/SHA-256, ECDSA P-384/SHA-384, RSA/SHA-256, RSA/SHA-512, Ed25519, SHA-1/SHA-256/SHA-384 DS/DNSKEY delegation-link validation, RRSIG signed-data, signed DNSKEY RRset, delegated-chain, NSEC no-data/name-range/name-error validation, RFC 5155 NSEC3 no-data/name-error/DS/wildcard/referral validation, RFC 4034 canonical RDATA name handling, and RFC 9460 SVCB/HTTPS RDATA primitives are implemented locally. Remaining DNSSEC algorithms and unknown NSEC3 hash algorithms stay fail-closed until full algorithm and advisory review is complete.
- Urkel proof payload decoding and verification are implemented locally against the upstream `urkel` proof format used by HSD `proof` packets. HSD resource value decoding is implemented locally for DS, NS, GLUE4/GLUE6, SYNTH4/SYNTH6, and TXT records, with resolver adapters plus in-memory and SQLite providers for verified proof values, resource-cache byte accounting, chain-root/height anchoring, current-tip invalidation, active cap enforcement, clear-cache support, and oldest-entry eviction. Header storage validates the exact mainnet genesis header, enforces HSD-compatible mainnet difficulty retarget bits, maintains a canonical hash-by-height index for reorg-aware height lookups, and appends canonical tip updates for normal chain growth. Blocking TCP peer connections cover version/verack, getaddr, getheaders, and getproof flows, with static peer seeding, HSD-compatible DNS seed discovery, bounded peer discovery, address-group diversity, SQLite peer-state persistence, bounded multi-batch header sync, foreground Android scheduling, proof fetching, and verified-resource storage. The gateway classifies each complete canonical hostname through both HNS and ICANN, applies explicit convergence/divergence selection, uses proof-anchored HNS authoritative DNS transports, and performs automatic validating-DoH ICANN TLSA/DANE with authenticated WebPKI fallback. It supports local CONNECT termination with native per-host certificates and WebSocket/HTTP Upgrade tunneling after the same prepared namespace and transport policy.
- WebSocket/HTTP Upgrade requests are no longer stripped into normal GET
  requests. Normal WebView traffic for all canonical DNS hosts uses the
  authenticated loopback proxy; supported bodyless Service Worker requests use
  native interception because WebView cannot authorize the local CONNECT
  certificate there. Both paths share the same Rust dual-root and ICANN-DANE
  decision boundary.
- Native HNS WebView interception can now stream decoded origin response bodies into temporary files and return a fixed-length header block to Android, avoiding the previous all-response byte-array path for bodyless WebView and Service Worker HNS requests. Decoded chunked bodies suppress stale `Transfer-Encoding` and mismatched `Content-Length` before WebView receives them.
- First-run sync starts automatically from the main browser activity. The
  one-second scheduler interval is used only after accepted header progress;
  current and no-progress states use a 10-minute check, with bounded failure
  retry. Sync-status schema version 2 derives the authoritative effective
  target from recent successful observations across at least three independent
  address groups and applies a two-block currentness limit. Raw peer maximum
  and schedule estimate remain diagnostic. The main page shows the local and
  effective target heights plus freshness, with separate WebView loading and
  the existing browser/settings controls.
- Header synchronization now performs network I/O, quorum collection,
  snapshot preparation, and peer merging in a private staged SQLite database.
  Generation-and-tip-bound conditional publication atomically exposes
  headers, peer evidence, and readiness; unchanged-header peer refreshes do
  not invalidate active requests, and incomplete or superseded state fails
  closed.
- Gateway-generated HNS error pages now include the requested URL above the status line so repeated 502 validation pages show which address failed.
- Gateway failure diagnostics are now persisted in app-private storage as a bounded, sanitized recent-event log containing only stage, host, status, and reason. URL paths, query strings, headers, and bodies are not written to the default diagnostic log.
- An Android instrumentation test now validates the real HNS CONNECT termination path on-device: the loopback proxy generates a native per-host TLS certificate, completes a TLS handshake, pins the certificate fingerprint for WebView SSL policy, rejects an ICANN URL for that pinned certificate, and forwards a bounded HNS HTTPS POST body through the native gateway bridge.
- Live-device validation on 2026-06-26 confirmed `welcome.2d` is classified and intercepted as HNS, then fails closed as `HNS Nameserver Response Invalid` because the delegated nameserver path does not return usable secure origin data. The same live audit found the supplied `theshake` and `niami` records lack usable secure apex origin address/TLSA responses for this strict gateway path, so their current failures are site/delegation data failures after the gateway address-query fix rather than the previous origin-address-selection bug. Historical evidence from that date also exercised the former Android HNS DoH compatibility resolver; that public recursive path has since been removed and is not a current product capability.
- `rustls` is pinned to the stable `0.23.41` line with the `ring` provider for Android-oriented builds. `cargo search` currently advertises `0.24.0-dev.0` as latest, but this project avoids dev prereleases for transport security code.
- `ring` is pinned to `0.17.14` and reused for DNSSEC RSA, ECDSA P-384/SHA-384, and Ed25519 verification because it is already the rustls crypto provider in this workspace.
- `p256` is pinned to the stable `0.13.2` line because the current crates.io latest advertised by `cargo search` is a `0.14.0-rc` prerelease.
- `rusqlite` is pinned to `0.39.0` in this workspace because `0.40.1` currently pulls a `libsqlite3-sys` build script that fails on the available Rust 1.92 toolchain with an unstable `cfg_select` feature.
