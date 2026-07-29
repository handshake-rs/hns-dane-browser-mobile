# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

## 0.5.5 - 2026-07-28

### Changed

- Prepared the coordinated public update as shared Rust engine `0.5.5`,
  Android `0.5.5` (build 45), and iOS `0.5.5` (build 50).

### Fixed

- Accepted revision `0` from the Apple Rust bridge when the unchanged default
  runtime policy is reapplied. Fresh iOS installs now finish secure-browser
  preparation instead of showing `Unable to prepare secure browsing`.
- Replays one idempotent iOS main-frame request when validated header
  maintenance closes a stale unpublished proxy response and WebKit reports a
  transient connection loss. Unsafe methods and repeated failures remain
  visible rather than being replayed.

## 0.5.4 - 2026-07-28

### Changed

- Prepared the coordinated public update as shared Rust engine `0.5.4`,
  Android `0.5.4` (build 44), and iOS `0.5.4` (build 48).
- Retained the App Store-signed IPA from the protected iOS upload workflow so
  the Android and iOS packages can be published together on the matching
  GitHub Release.

## 0.5.3 - 2026-07-27

### Added

- Added a hardened, click-only recovery page for confirmed port 53
  interception on Android and iOS. Its fixed-origin DANE generator handoff
  carries the canonical HNS root and includes a nameserver only when it came
  from the authenticated HNS delegation; merely rendering the page contacts
  neither a suggested recovery resolver nor the setup site.

### Changed

- Bumped the shared Rust engine and Android app to `0.5.3` (Android build 43)
  and the Apple shell to `0.5.3` (iOS build 47).

### Fixed

- Moved live header synchronization into a private staged database and limited
  the exclusive browser-maintenance window to validated generation-bound
  publication. Header, peer, and readiness generations now publish together,
  unchanged-header peer refreshes avoid invalidating active requests, and
  concurrent runtimes/processes fail closed on incomplete or superseded state.
- Timestamped final peer corroboration at the end of long syncs so newly
  published currentness evidence does not begin near expiry.

## 0.5.2 - 2026-07-26

### Added

- Added a separately configured, persistent HNS recovery DoH endpoint to Android and iOS. It is blank and inactive by default, accepts only bounded HTTPS RFC 8484 URLs with public ICANN hostnames, bootstraps that hostname only through validating ICANN DoH, authenticates the endpoint with WebPKI, and still validates DNSSEC, TLSA, and DANE locally.
- Added recovery settings, provenance, diagnostics, privacy disclosure, and a requester-only P2P relay prompt on both mobile platforms. `https://hnsdoh.com/dns-query` appears only as an example and is never prefilled, selected, or contacted automatically.

### Changed

- Generation-bound recovery consent now follows direct authoritative UDP/TCP, owner-published proof-anchored authoritative DoH, and any independently opted-in P2P requester. Eligible fallback is limited to transport failure or confirmed port 53 interception; bogus DNSSEC, stale or unavailable HNS proofs, invalid DNS responses, and DNS response codes remain terminal.
- Kept historical recursive-DoH keys as permanent tombstones and stored the new recovery choice under distinct Android and iOS keys, so an upgrade cannot resurrect old consent.
- Bumped the Android app and shared Rust core to `0.5.2` (Android build 42) and the Apple shell to `0.5.2` (iOS build 46).

### Security

- Recovery endpoint hostnames reject IP literals and browser special-use names, resolved bootstrap addresses are restricted to public routes, and native HTTPS uses the endpoint hostname for WebPKI while dialing only validated bootstrap addresses.
- A confirmed interception is non-terminal only for explicitly enabled alternatives. P2P remains requester-only and independent of recovery DoH, and neither setting grants output-node consent.

## 0.5.1 - 2026-07-26

### Added

- Made the iOS settings menu mirror Android's canonical seven-section hierarchy, row order, summaries, defaults, conditional current-page action, and native equivalents for homepage, privacy data, history, downloads, themes, language, Handshake networks, relay peers, diagnostics, legal information, resolver traces, and header resync.
- Added automatic ICANN DANE on Android and iOS at the shared Rust gateway boundary, consuming the standalone `hns-icann-dane` contract from `hns-dane-engine`. DNS-named HTTPS/WSS now derives the TLSA owner from the selected service port and TCP/UDP transport, enforces secure TLSA when present, and permits WebPKI only after authenticated validating ICANN DoH establishes TLSA absence or an insecure delegation. Bogus/indeterminate DNSSEC, malformed TLSA or TLSA CNAME chains, invalid owner derivation, resolver errors, and timeouts fail closed.

### Changed

- Decoupled live HNS header freshness from the 144-block
  proof-cache/reorganization retention window in the shared Android/iOS Rust
  runtime. Browser admission now requires the validated tip to be within two
  blocks of a recent, corroborated multi-address-group peer target; raw maximum
  claims and the idealized schedule remain diagnostic only.
- Persisted header-observation time independently from peer transport liveness,
  versioned the mobile sync-status contract, and reduced no-progress Android
  and iOS sync polling to a ten-minute idle cadence.
- Bumped the Android app, shared Rust core, and Apple shell to 0.5.1
  (Android build 41; iOS build 45).

### Fixed

- Replaced the iOS placeholder icon with the production HNS DANE Browser artwork used by Android and Google Play, and added release checks for the canonical icon and archived app identity.
- Removed the one-host ICANN native-gateway exception. Android now applies its authenticated loopback proxy across the WebView so main frames, redirects, subresources, native WebSockets, and supported downloads share the automatic policy; Service Worker/bodyless fallback requests use the same Rust gateway. iOS uses the same policy through its no-failover whole-data-store proxy and exact live local-certificate authorization.
- Corrected mobile handling of transparent port 53 interception. A DNSSEC failure now runs the existing bounded TEST-NET canary before TCP or another authoritative server is trusted; confirmed interception is cached, direct DNS is suppressed, resolution tries proof-authenticated authoritative DoH and then the P2P requester only when explicitly enabled, and failure of all admitted alternatives remains typed and fail-closed.
- Corrected delegated NXDOMAIN validation so each covering NSEC RRset is verified only with its own owner/class RRSIG, including multi-owner denial responses.
- Made mobile HTTPS/SVCB planning retain ordered HTTP/3, HTTP/2, and RFC 9460 implicit HTTP/1.1 candidates from one selected service record. HNS protocol fallback occurs only after securely authenticated TLSA absence for the current UDP/TCP owner; insecure, bogus, or malformed TLSA evidence is terminal.
- Made unavailable or expired peer-target evidence an explicit unknown
  currentness state that fails closed for HNS resolution instead of silently
  accepting an old local tip as current.
- Prevented proof and DNS-relay traffic from refreshing or promoting header
  height evidence, including a backwards-safe migration for existing peer
  databases.
- Closed the header-maintenance publication race at the shared mobile Rust
  boundary. Response, fail-closed error, and HTTP 101 results now carry the
  exact maintenance epoch; final publication revalidates it and holds the
  maintenance read lock through the head flush, while sync, cache clear,
  snapshot install, and header reset advance it before mutation.

### Removed

- Removed public recursive HNS DoH and HNS WebPKI fallback from the runtime. Android and iOS now one-way migrate old compatibility settings to strict HNS DNSSEC/DANE without converting explicit legacy compatibility consent into P2P relay consent, while retaining ordinary ICANN DoH/WebPKI, proof-anchored authoritative HNS DoH, and the optional P2P relay.

## 0.5.0 - 2026-07-16

### Added

- Added an opt-in Handshake P2P DNS relay protocol, bounded `hsd` responder integration, Rust requester, Android and iOS runtime controls, deterministic cross-language fixtures, and fast and full four-node regtest acceptance tiers.
- Added manual Android relay-peer configuration with live capability verification and persisted peer state.

### Changed

- Enabled the P2P DNS relay by default for new Android installs while retaining the independent legacy HNS DoH compatibility fallback for networks whose peers have not upgraded.
- Bumped the Android app, shared Rust core, Apple shell, Play upload defaults, and store metadata package to 0.5.0 (build 40).

### Security

- Kept relay peers untrusted: validated headers and Urkel proofs, delegated DNSSEC, negative proofs, HTTPS/SVCB policy, TLSA, and DANE certificate matching remain local to the browser.
- Added proof-gated admission, public-authority filtering, bounded rate and concurrency controls, strict response correlation, query-minimizing diagnostics, and failover away from unavailable or malformed relay peers.

## 0.4.1 - 2026-07-15

### Changed

- Updated the repository and in-app source-code links to the renamed cross-platform GitHub repository.
- Bumped the Android app and Play release package to 0.4.1 (build 39) while retaining the unchanged Rust engine and Apple shell at 0.4.0.
- Made CI select Rust, Android, and Apple gates from the changed paths, with shared Rust changes still validating both platform packages.

## 0.4.0 - 2026-07-15

### Added

- Added a stable versioned Apple C ABI, deterministic device/simulator Rust builds, XCFramework packaging, C/C++ header/export checks, and a macOS build and simulator gate using the stable iOS 26.5 SDK with Xcode 26.5 or 26.6.
- Added an iOS 17.0-or-later UIKit/WKWebView shell using the same Rust runtime, resolver, HNS/DNSSEC/DANE policy, proxy parser, TLS terminator, and persistent state as Android.
- Added a fail-closed whole-browser Rust proxy mode for WebKit, with authenticated admission, optional immutable HNS scope, bounded explicit-bootstrap WebPKI DoH for ICANN addresses, public-address and unsafe-port enforcement, opaque CONNECT, streamed HTTP forwarding, and WebSocket Upgrade tunneling without system target DNS.

### Changed

- Centralized browser special-use hostname policy in `hns-core` and shared it across classification, HNS resolution, and proxy admission.
- Kept Android on its exact HNS-scoped proxy mode while exposing platform-neutral classifier, root extraction, live challenge matching, and typed status APIs to both native shells.
- Kept the iOS deployment floor at 17.0 to support the iOS 17 and iOS 18 generations independently of the iOS 26.5 build SDK; Xcode 26.5 and 26.6 are accepted for that Apple build gate.
- Bumped the Android app, Rust core, Apple shell, Play upload defaults, and store metadata package to 0.4.0 (build 38).

### Security

- Added monotonic opaque Apple handles, bounded Rust-owned buffers and mailboxes, panic-contained C exports, one active proxy per runtime, policy/start race protection, immediate stop revocation, and joined runtime-owned teardown.
- Added an optional signed physical-device validation matrix for extra confidence in WebKit proxy isolation, server-trust challenges, Service Workers, WebSockets, lifecycle changes, and renderer/network-process restarts. Simulator validation does not satisfy this matrix, and no physical-device pass is claimed.

## 0.3.16 - 2026-07-14

### Added

- Added generic HNS-proof-pinned RFC 8484 authoritative DoH so an owner can use an HNS hostname such as `https://denuoweb:8443/dns-query`, connect through verified HNS nameserver GLUE, and authenticate a self-signed endpoint without an ICANN domain or WebPKI.
- Added exact toolbar provenance for `DANE via ADoH`, `DANE via DNS53`, `DANE via 3rd DoH`, `Stateless DANE`, `DANE via ICANN DoH`, and the corresponding non-TLS HNS paths.

### Changed

- Ordered HNS delegated resolution for availability: owner ADoH first, authoritative UDP/TCP 53 second, and the configured third-party HNS DoH resolver last in Compatibility mode; Strict mode omits only that final third-party fallback.
- Bumped the Android app, Rust core, Play upload defaults, and Play metadata package for the 0.3.16 release.

### Fixed

- Distinguished certificate-carried stateless DANE from DNS-fetched TLSA and made resolver traces follow the exact A/AAAA and TLSA transports, including IPv6-only origins and HTTPS/SVCB-selected ports.
- Rejected spoofed internal provenance headers and prevented them from being exposed to Chromium or page content.

## 0.3.15 - 2026-07-14

### Fixed

- Added exact transport-owned `Content-Length` metadata to non-empty HTTP/2 and HTTP/3 request bodies, restoring proof-bootstrapped authoritative DoH interoperability with servers that require it while preventing caller-supplied length mismatches.

### Changed

- Bumped the Android app, Rust core, Play upload defaults, and Play metadata package for the 0.3.15 release.

## 0.3.14 - 2026-07-14

### Changed

- Changed the default compatibility DoH resolver from the failing global HNSDoH pool to the working Zorro node while keeping the resolver user-configurable.
- Bumped the Android app, Rust core, Play upload defaults, and Play metadata package for the 0.3.14 release.

## 0.3.13 - 2026-07-14

### Added

- Added in-app third-party software notices generated deterministically from the locked Android release runtime and shipping Rust dependency closure, with complete license text and integrity checking.
- Added a release-bundle gate for exact native ABI inventory, 16 KiB bundle/ELF alignment, ELF hardening and bounds, stripped shipping libraries, matching FULL native debug symbols and Build IDs, path sanitization, R8 mapping, notices, and upload-certificate signing.

### Security

- Hardened the native release build against caller-supplied compiler, linker, and Cargo profile overrides; pinned NDK r28c; remapped local checkout, tool-home, and NDK paths; and made AGP responsible for stripping while retaining Play Console symbols.

### Fixed

- Added proof-anchored `hnsdns=1` authoritative DoH bootstrap metadata so delegated HNS names can reach their RFC 8484 endpoint without first relying on interceptable UDP/TCP port 53; origin answers still require delegated DNSSEC validation against the HNS-proven DS.
- Added a bounded TEST-NET DNS sentinel probe and resolver-trace field that can positively identify transparent port 53 interception without treating a timeout as proof that the network is clean.
- Allowed RFC 9461 DNS-server SVCB records to use a distinct WebPKI-authenticated target name while retaining the HNS-proven nameserver glue address for the connection.
- Expanded the deletion controls to clear WebView origin storage with cookies and to clear the persisted gateway diagnostic log, with updated in-app privacy disclosure.
- Replaced the automatically loaded remote default homepage with a bundled start page that contains no network resources; user-configured homepages remain supported.
- Moved adaptive launcher icons to the API-compatible resource directory and removed obsolete notification, service, privacy, resolver-trace, and cookie-only localized strings.

### Changed

- Bumped the Android app, Rust core, Play upload defaults, and Play metadata package for the 0.3.13 release.

## 0.3.12 - 2026-07-13

### Fixed

- Retried delegated authoritative DNS over TCP when UDP answers fail DNSSEC validation, preserving fail-closed DNSSEC behavior while recovering from UDP-only DNS path corruption.
- Bumped the Android app, Rust core, Play upload defaults, and Play metadata package for the 0.3.12 release.

## 0.3.11 - 2026-07-12

### Fixed

- Kept automatic HNS header sync alive while navigating between browser, settings, diagnostics, and sync screens; it now stops only when the whole app leaves the foreground, and the HNS Sync screen follows automatic status updates live.
- Added the missing localized cleartext-HTTP warning in every declared app language so the warning bar no longer fails Android lint or falls back to English.

### Changed

- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.11 release.

## 0.3.10 - 2026-07-12

### Security

- Removed the insecure HNS DNS result opt-in. HNS gateway resolution requires verified HNS/DNSSEC data again; cleartext `http://` remains a transport choice only after secure name resolution.
- Added a persistent yellow warning bar for `http://` pages to make cleartext transport visible separately from HNS resolution status.

### Fixed

- Stabilized HNS gateway page loads by falling back from failed Alt-Svc promotion, avoiding unsafe DoH POST promotion, preserving identity-encoded WebView gateway assets, and normalizing root main-frame URL status matching.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.10 release.

## 0.3.9 - 2026-07-12

### Fixed

- Restricted insecure HNS resolution opt-in to cleartext HNS origins; HTTPS and WSS HNS origins still fail closed on unsigned HNS address, HTTPS/SVCB, or TLSA/DANE resolution.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.9 release.

## 0.3.8 - 2026-07-12

### Security

- Blocked native origin, authoritative DNS/DoH, and advertised P2P connections to non-public endpoints on mainnet/testnet, enforced the browser unsafe-port policy, and kept explicit regtest-only development exceptions.
- Authenticated the randomized loopback proxy, limited it to the active HNS origin, blocked alternate loopback literals, made exported launcher input extra-blind, and required a user gesture before external-scheme intents.
- Enforced same-origin redirect following, strict WebSocket handshake/frame/close validation, bounded WebSocket sessions and queues, bounded response/download/cache stores, and fail-closed Service Worker behavior when proxy authentication is unavailable.
- Pinned and verified Rust, Gradle, Android, CI Action, dependency, and release-signing inputs; added read-only CI, Dependabot coverage, secret checks, strict lockfiles, and cryptographic AAB signer verification.

### Fixed

- Fixed native WebSocket upgrade headers and clean-close handling, HTTP/1 informational/framing/trailer parsing, HTTP/2 and HTTP/3 body/header limits and timeouts, unsafe pooled-request replay, and caller header normalization.
- Fixed unchecked header-height arithmetic, JNI request/read length validation, stale or unbounded transport state, delegated DNS source validation, and complete current IANA/special-use name classification including `.internal`.
- Fixed Android lifecycle leaks and sync/cache races, unbounded browser history/download fields, staged-file cleanup, oversized header-snapshot extraction, and release lint failures for experimental API opt-in and locale plural resources.

### Changed

- Removed the ICANN DANE TXT-shadow compatibility fallback. The hardcoded ICANN DANE test host now uses native DNSSEC TLSA only, while delegated HNS authoritative DoH continues to use RFC 9461 `_dns.<nameserver>` SVCB discovery.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.8 release.

## 0.3.7 - 2026-07-08

### Changed

- Disabled spellcheck, suggestions, and personalized learning for the browser omnibar so Android keyboards treat it as a URI/search field instead of prose.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.7 release.

## 0.3.6 - 2026-07-08

### Changed

- Kept HNS sync active only while the app is open, removed the persistent phone sync notification, hid completed sync progress until header resync, enlarged the browser menu, aligned the main toolbar with the top of the app, and moved header resync into HNS Sync settings.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.6 release.

## 0.3.5 - 2026-07-08

### Added

- Added Android locale resources for English, Spanish, French, German, Portuguese, Japanese, Arabic, Persian, and Hebrew.
- Added Android per-app language configuration and a Settings entry for Android's system app-language picker.

### Changed

- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.5 release.

## 0.3.4 - 2026-07-07

### Added

- Added an off-by-default experimental Settings flag for stateless HNS DANE certificate evidence using certificate-carried Urkel proof and RFC 9102 DNSSEC-chain extensions against recent local tree roots.

## 0.3.1 - 2026-07-06

### Changed

- Set the default Android homepage to `https://denuoweb/homepage` and removed the bundled static homepage asset.
- Bumped the Android app, Rust core, network user-agent strings, Play upload defaults, and Play metadata package for the 0.3.1 release.

## 0.3.0 - 2026-07-06

### Changed

- Bumped the Android app, Rust core, network user-agent strings, and Play upload defaults for the 0.3.0 release.

## 0.2.9 - 2026-07-06

### Added

- Replaced `hnsdns=1` HNS TXT discovery with RFC 9461 `_dns.<nameserver>` SVCB discovery for RFC 8484 authoritative DoH endpoints on delegated nameservers, used after direct UDP/TCP 53 and validated against the HNS-proven DS chain.
- Added resolver trace and Android diagnostics labels for authoritative DoH attempts and malformed RFC 9461 DoH discovery records.

### Changed

- Rebranded the unreleased Android app to HNS DANE Browser with launcher label HNS DANE, package ID `com.denuoweb.hnsdane`, and GitHub package references under `Denuo-Web/hns-dane-browser-android`.
- Replaced the launcher, Play icon, feature graphic, and in-app brand assets with the centered HNS DANE mark.

## 0.2.8 - 2026-07-04

### Added

- Added a configurable compatibility DoH resolver setting for portable HNS resolution across arbitrary networks.

### Fixed

- Validated delegated HNS DNSSEC over DoH transport locally against HNS DS records instead of relying on resolver AD bits.
- Accepted DoH responses with compressed RRSIG signer names.
- Validated inline child-zone signed answers and no-data proofs for delegated HNS zones.
- Kept optional HTTPS/SVCB policy lookup failures from blocking secure A/TLSA/DANE validation.

## 0.2.7 - 2026-06-30

### Changed

- Updated the bundled HNS directory homepage organization and footer copy.

## 0.2.6 - 2026-06-30

### Fixed

- Kept refreshed HNS WebSocket pages from receiving stale native events from the previous page instance.

## 0.2.5 - 2026-06-30

### Fixed

- Bridged HNS WebSockets through the native HNS gateway so single-label HNS pages can open `wss://` connections with resolver, HTTPS service, and DANE validation instead of relying on Android WebView's WebSocket TLS stack.

## 0.2.4 - 2026-06-30

### Changed

- Audited the bundled HNS homepage with resolver trace, HNS proof, TLSA, and DANE checks; removed non-working entries and added Denuo Web as a core direct-authoritative HNS site.
- Updated Denuo Web infrastructure to advertise HTTP/3 through DNS HTTPS records and showcase HTTP/3 plus WebSocket echo support.

### Fixed

- Kept regular origin HTTP reads on the normal response timeout instead of the shorter tunnel idle timeout.
- Avoided stale DoH transport promotion state across Android resolver fallback queries.
- Submitted omnibox Enter on key-down and forced focus back to WebView so the keyboard closes reliably.

## 0.2.3 - 2026-06-30

### Security

- Hardened Android WebView startup, optional WebKit feature usage, Service Worker interception, renderer recovery, and non-HTTP(S) navigation handling.
- Hardened the Android loopback gateway so it refuses broad WebView proxy fallback when host-scoped reverse-bypass support is unavailable.
- Restricted loopback gateway handling to active HNS host/subdomain scope and rejected non-HNS proxy traffic with fail-closed responses.
- Removed release stack-trace printing from the loopback accept path and kept diagnostics bounded through the gateway event log.

### Changed

- Updated `androidx.activity:activity-ktx` from `1.12.0-alpha05` to stable `1.13.0`.
- Updated production-readiness and security-model documentation for the stricter loopback proxy posture.

### Fixed

- Made the Android FFI live-proof cache-miss test deterministic by persisting the synthetic peer height before selection.
- Addressed the current Rust clippy warning in the Android FFI fallback marker.
