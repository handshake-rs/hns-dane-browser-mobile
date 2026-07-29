# Milestones

## Milestone 1: Rust Proof Kernel

- Header parsing and serialization.
- Header PoW hash parity with HSD genesis fixtures.
- HSD-compatible mainnet difficulty retarget validation.
- Header store abstraction, SQLite persistence, canonical hash-by-height indexing, and best-tip selection.
- Handshake `getheaders`, `headers`, `getproof`, and `proof` payload codec.
- HSD-compatible 9-byte P2P frame encoder/decoder.
- Blocking TCP peer connection for version/verack, getaddr, getheaders, and getproof flows.
- Header sync session state machine for version/verack and request/response sequencing.
- Peer scoring and outbound selection policy.
- Static peer seeding, HSD-compatible DNS seed discovery, bounded getaddr peer discovery, address-group-aware peer diversity, and SQLite peer-state persistence.
- HSD-compatible Handshake name validation and SHA3-256 name-hash derivation.
- Urkel proof parser and verifier boundary.
- HSD resource decoder for DS, NS, GLUE4/GLUE6, SYNTH4/SYNTH6, and TXT records.
- Verified Urkel proof-value handoff.
- Proof scheduler from TCP getproof responses into the resolver resource-value store.
- Gateway cache-miss proof fetching into the verified resource-value store.
- Parser fuzz smoke targets.

## Milestone 2: Live HNS Sync

- Peer manager, TCP peer connection, and sync coordinator scaffolding.
- Version/verack and getheaders/headers session flow.
- Persistent header store.
- Persistent peer-state store.
- Bounded multi-batch header sync runner with selected peers, scoring, and persistence.
- Private SQLite staging for header network work, quorum collection, snapshot
  preparation, and peer merging, with generation-and-tip-bound delta
  publication that atomically updates headers, peers, and readiness.
- Unchanged-header peer refresh without active-request invalidation,
  cross-process publication locking, interrupted-state recovery, and
  completion-time peer-evidence timestamps.
- Proof request lifecycle scaffolding with Urkel proof verification.
- Proof-provider-backed HNS resolver boundary with verified resource-value extraction and proven-record filtering.
- Verified HNS non-inclusion surfaced separately from existing names with no origin address.
- In-memory and SQLite verified resource-value providers for sync-to-resolver handoff.
- Resource-cache byte accounting, chain-root/height anchoring, current-tip invalidation, clear, oldest-entry eviction, and active sync-time cap enforcement.
- TCP proof scheduler and gateway cache-miss proof fetcher that store verified resource values for resolver use.
- Gateway fail-closed guard when HNS resolution has no origin A/AAAA connect address.

## Milestone 3: DANE Core

- DNSSEC DNSKEY/DS delegation-link primitives with SHA-1, SHA-256, and SHA-384 DS validation digests.
- DNS SVCB/HTTPS RDATA parsing and DNSSEC canonicalization.
- DNSSEC RRSIG canonical signed-data construction.
- DNSSEC canonical RDATA name handling for CNAME, NS, SOA, SRV, SVCB/HTTPS, and RRSIG signer names.
- DNSSEC ECDSA P-256/SHA-256 RRset signature validation.
- DNSSEC ECDSA P-384/SHA-384 RRset signature validation.
- DNSSEC RSA/SHA-1 compatibility, RSA/SHA-256, and RSA/SHA-512 RRset signature validation.
- DNSSEC Ed25519 RRset signature validation.
- DNSSEC signed-RRset validator composed from DS, DNSKEY, and RRSIG checks.
- DNSSEC delegated-chain validator composed from authenticated DS and DNSKEY RRsets.
- DNSSEC NSEC no-data, name-range, and name-error denial validation.
- DNSSEC RFC 5155 NSEC3 no-data, name-error, DS opt-out, wildcard, and referral denial validation.
- DNSSEC remaining algorithm and NSEC3 hash-transition support.
- TLSA validation matrix.
- DANE policy engine.
- Certificate/SPKI extraction.

## Milestone 4: Origin Transport

- Bounded HTTP/1.1 TCP fetch.
- TCP TLS fetch with rustls.
- DNSSEC-gated TLSA/DANE validation during TLS handshake.
- HTTP/2 fetch.
- QUIC/HTTP/3 fetch.

## Milestone 5: Android Browser

- WebView shell.
- ProxyController integration.
- Randomized-port loopback HTTP/CONNECT proxy with native persistent-cache HNS HTTP routing, automatic validating-DoH ICANN TLSA/DANE routing, WebView and Service Worker bodyless HTTP/HTTPS request interception with file-backed decoded response bodies, bounded redirects, bounded header/body forwarding, reserved-name filtering, local HTTPS termination using exact generated-certificate fingerprint pins, public-IP-only opaque CONNECT compatibility, and native WebSocket/HTTP Upgrade stream tunneling with fail-closed fallback when validation or the native bridge is unavailable.
- Packaged Rust JNI library.
- Application-foreground native sync scheduler with in-process status snapshots,
  a first-page progress bar driven by the corroborated effective target,
  diagnostic raw-peer/schedule estimates, short scheduling only after accepted
  header progress, ten-minute current/no-progress checks, a separate WebView
  loading bar, hamburger back/forward/refresh/settings menu, Settings
  diagnostics, manual sync triggering, explicit
  syncing/up-to-date/peer-failed outcomes, and per-peer failure stages.
- HNS omnibox rules.
- Security and diagnostics UI.

## Milestone 6: Hardening

- Enforced cache caps, current-tip cache invalidation, clear-cache action, and cache-size diagnostics.
- Device matrix.
- Fuzzing expansion.
- Battery and network optimization.
- Security review.

## Milestone 7: Shared Runtime and Apple Shell

- Moved persistent runtime ownership, policy, synchronization, cache, diagnostics, gateway, proxy, HTTP parsing, CONNECT termination, certificate generation, and Upgrade tunneling behind JNI-free Rust APIs.
- Replaced the Android Kotlin proxy/TLS/WebSocket stack with the shared Rust proxy while preserving the Android behavior and release-validation baseline.
- Added authenticated whole-browser proxy routing in which every canonical DNS hostname enters one retained full-host dual-root plan. HNS and ICANN are resolved independently; DNS-named HTTPS/WSS CONNECT is locally terminated and uses automatic transport-aware TLSA discovery, DANE when secure TLSA is present, and WebPKI only for authenticated ICANN absence or proven insecure delegation. Public IP literals retain bounded opaque CONNECT. HTTP forwarding, Upgrade tunneling, and special-address/unsafe-port policy do not use system target resolution.
- Added the stable versioned `ios-ffi` C ABI, C/C++ header checks, Apple Rust slices, and XCFramework build scripts.
- Added the iOS 17.0-or-later UIKit/WKWebView shell with persistent profile data, authenticated no-failover whole-data-store proxying, generation-safe scope rotation, exact Rust-authorized HNS certificate handling, lifecycle revocation, downloads, and shared snapshot bootstrap.
- Added the macOS ABI, XCFramework, application, and simulator test gate against the stable iOS 26.5 SDK, accepting Xcode 26.5 or 26.6. The iOS 17.0 deployment floor, including support for the iOS 18 generation, remains independent of that build SDK.
- Published the iPhone app on the Apple App Store; version `0.5.0` was the
  public baseline observed on 2026-07-28. Version `0.5.4` / build `48` is
  predecessor upload evidence, and `0.5.5` / build `49` is a superseded App
  Store Connect upload. Version `0.5.5` / build `50` reproduced the transient
  connection-loss alert in a clean simulator run and was not uploaded. Version
  `0.5.5` / build `51` was pushed, but validation was canceled and it was not
  uploaded. Version `0.5.5` / build `52` passed exact CI, but a live capture
  reproduced a second bounded startup connection loss and its upload was
  canceled. Version `0.5.5` / build `53` passed exact CI, but its live capture
  exhausted two same-connection recovery attempts and it was not uploaded.
  Version `0.5.5` / build `54` was superseded before live capture after deeper
  analysis showed that the proxy must remain suspended until factual header
  currentness. Build `55` proved that gate in live capture and exposed a stale
  WebKit policy-interruption callback when a new address replaced the
  provisional homepage. Build `56` handles only that superseded navigation and
  is the current source update candidate.
- Installed-device qualification gate: run and document the signed
  physical-device WebKit matrix for main frames, subresources, Service Workers,
  WebSockets, downloads, background/resume, and renderer/network-process
  restart. It is separate from App Store submission eligibility. No
  physical-device result is claimed; Linux and simulator checks do not provide
  that hardware-specific evidence.
- Removed the single-host ICANN DANE exception and applied the automatic policy to Android and iOS navigation, redirects, subresources, WebSockets, supported Service Worker requests, and downloads. Portable coverage is checked in; Android/iOS device qualification remains open.
