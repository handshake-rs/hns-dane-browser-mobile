# Production Readiness Audit

Last audited: 2026-07-29

This audit treats the repository as an update candidate for existing public
Google Play and Apple App Store apps. Google Play production completed
`0.5.5` / code `45`; the Apple public baseline observed on 2026-07-28 was
`0.5.0`. Current source declares Android `0.5.5` (`versionCode 46`), Rust
engine `0.5.5`, and iOS `0.5.5` (build `57`). Code `46` and build `57` are the
replacement candidates carrying the shared dual-root negative-evidence fix.
Their exact gates, signing, exact-build Android release-device verification,
and store submissions remain pending.

## Release Candidate Findings

| Area | Status | Finding |
| --- | --- | --- |
| Android release build | Code 45 signed and deployed; code 46 pending | Code 45 passed Android build, unit tests, lint, unsigned bundle structure, upload-signature verification, APK signature/alignment checks, and production deployment. Code 46 must repeat the same gates. The retained code 40 APK/AAB hashes (`bff5ba468b0c5ad2d134603127f089ad6fdc9e9b5ceab921825e570cfefd60fb` and `96c5926c559881ba74e380eea062dce3de6cefaf91d3753882e528cccc96e1d0`) remain dated v0.5.0 evidence, not artifacts for this checkpoint. |
| Public Play listing | Production update deployed | Google Play production contains version `0.5.5` / code 45 with release status completed. |
| Public App Store listing | Build 57 replacement pending; device qualification open | Apple reported version `0.5.0` on 2026-07-28. Build `48` is predecessor evidence and `0.5.5` / build `49` is a superseded upload. Builds `50`–`56` were not uploaded. Build `56` passed exact CI, but its live screenshot run failed when compressed SOA negative evidence and stale plan-start timing made dual-root ICANN evidence indeterminate. Build `57` carries the shared fix; its gates and upload remain pending. No TestFlight distribution is part of this release, and App Store release remains manual after review. A real-iPhone pass remains a separate installed-iOS and ecosystem qualification item. |
| Privacy policy | Repository and hosted policy aligned | The canonical `https://denuoweb.com/work/hns-dane-browser/privacy` policy now discloses the independently opt-in P2P requester and user-configured recursive HNS DoH recovery, operator-visible queried names/types, timing and source IP, blank/off defaults, one-way legacy-key tombstone, local DNSSEC/DANE validation, validating ICANN bootstrap, and continued prohibition on HNS WebPKI fallback. |
| Manifest exposure | Ready | The only app-defined exported entry point is `LauncherActivity`. Browser, settings, diagnostics, HNS inspector, history, download, and other app activities are non-exported, and the app declares no service. Merged dependency components remain subject to their own signature/permission guards. |
| Backup / transfer | Ready | App backup and device-transfer extraction are disabled for local browsing data, WebView state, download records, diagnostics, resolver cache, and HNS sync/cache state. |
| Cleartext policy | Ready | Cleartext is disabled globally with a loopback-only exception for the local gateway. User-selected HTTP and direct DNS/HNS traffic are accurately disclosed, but ordinary open-web and user-initiated transfers are outside Google Play's Data safety collection/sharing scope. |
| WebView hardening | Ready | Mixed content is blocked, Safe Browsing is enabled, file/content access is disabled, native JavaScript bridges are removed, WebView debugging follows `BuildConfig.DEBUG`, and browser-wide loopback proxying sends every canonical DNS host to exact per-origin Rust dual-root preparation. |
| Privacy controls | Improved | Settings can clear cookies plus WebView origin storage, and the diagnostics UI can clear the bounded gateway event log. The repository and in-app disclosures now describe WebView-provider Safe Browsing and these local retention controls. |
| Build supply chain | Build 56 exact CI passed; replacement gates pending | Build 56 passed its exact CI matrix. Because the compressed-SOA and post-resolution freshness fix changes the shared runtime, code 46/build 57 must repeat the selected Rust, Android, and Apple matrix before release. |
| 16 KiB / native symbols | Code 45 signed gates passed; code 46 pending | The code 45 bundle passed PT_LOAD alignment, hardening, stripping, Build ID, matching FULL debug metadata, path sanitization, and upload signing; its APK passed signature and 16 KiB ZIP-alignment verification. Repeat the same gates for code 46. |
| Release-device acceptance | Pending for code 46 | Install the exact signed code 46 APK and exercise cold launch, permanent tombstoning of the historical resolver key, blank/off recovery default, independent requester opt-in, configured-recursive validation and interception-only eligibility, verified manual-peer persistence, fail-closed bogus/invalid/stale cases, and dual-root HNS/ICANN/DNSSEC/DANE/WebPKI browsing. Historical only: the signed `0.4.1` APK upgraded and cold-launched successfully on the Pixel 9 after its shared-runtime device matrix passed. |
| Data collection posture | Repository review updated; live-form reconciliation required | No ads, analytics SDKs, developer accounts, sensitive permissions, advertising ID access, or developer telemetry endpoint was found. The policy now records that a relay peer receives the DNS name/type and source network address needed for the request. Retain the live `No collected / No shared` posture only after reconciling the current Play definitions and WebView-provider Safe Browsing guidance. |

## Applied Cleanup

- Added user-facing deletion of both cookies and WebView origin storage instead of clearing cookies alone.
- On Android, replaced the automatic developer-hosted default homepage request
  with a bundled, Content-Security-Policy-restricted start page that contains
  no network resources. The iOS shell still defaults to the documented Denuo
  Web homepage and lets the user replace it.
- Added a Diagnostics control that clears the bounded, sanitized gateway event log.
- Updated the repository privacy policy to disclose WebView-provider Safe Browsing, WebView origin storage, and gateway-diagnostic retention/deletion.
- Corrected the Data safety draft to apply Google's explicit open-web, on-device, and user-initiated-transfer exclusions instead of treating ordinary browser networking as developer collection or sharing.
- Removed stale localized overrides for recently changed privacy and resolver-trace copy so affected locales fall back to the current, accurate source strings until translations are refreshed.
- Added deterministic in-app notices for the complete locked Android release-runtime and shipping Rust dependency inventories, with full license text and a CI-safe integrity check.
- Reworked release native packaging so AGP strips the installed libraries and embeds matching FULL debug metadata, while deterministic prefix maps keep checkout, home, Cargo, Rustup, and NDK paths out of both artifacts.
- Added an automated release-bundle gate for exact ABI inventory, 16 KiB bundle and ELF alignment, ELF architecture/type/bounds, native hardening, stripping, matching Build IDs and symbols, local-path rejection, R8 mapping, third-party notices, and upload signing.
- Hardened the loopback gateway and moved Android to authenticated whole-WebView proxy routing. Every canonical DNS host enters a retained per-origin dual-root plan; private/special targets are rejected before dialing and public IP literals use bounded opaque forwarding.
- Added proof-pinned authoritative DoH bootstrap for single-label HNS endpoint
  names. Current policy attempts direct authoritative UDP/TCP 53 first and
  reaches owner ADoH only after eligible direct transport unavailability or
  confirmed interception. The browser exposes successful authoritative paths
  explicitly and strips internal provenance headers before content reaches
  Chromium or the page.
- Added an untrusted optional HNS P2P DNS relay after local proof and authoritative transport attempts; relayed answers still pass local DNSSEC, TLSA, and DANE validation.
- Added an explicit, blank-by-default recursive HNS DoH recovery control after direct authority, owner-published proof-anchored DoH, and independently enabled P2P consumption. Both FFI boundaries normalize the endpoint, the endpoint host bootstraps only through validating ICANN DoH and public WebPKI addresses, HNS answers remain locally validated, and the historical resolver key is permanently tombstoned rather than copied as consent.
- Moved header network I/O, quorum collection, snapshot preparation, and peer
  merging into a private staged database. A generation-and-tip-bound
  publication step atomically exposes headers, peers, and readiness; peer-only
  refresh preserves active requests, and incomplete or superseded
  cross-process state fails closed.
- Added manual relay-peer configuration restricted to IP-literal endpoints. The runtime completes a live HSD handshake and verifies the current relay capability before persisting an endpoint; the `hsd` responder remains an explicit operator opt-in.
- Updated repository privacy and store disclosures for relay-visible queried names/types and client network address, and aligned the hosted privacy page with them.
- Updated `androidx.activity:activity-ktx` from an alpha build to stable `1.13.0`.
- Added local dependency, test, lint, bundle-signing, and supply-chain verification, with immutable Action references in the checked-in workflow.

## Remaining Release Gates

1. Retain historical CI evidence, including build `56`'s exact passing matrix,
   but repeat the hosted path-policy, Rust, cold-cache Android, Apple, and
   required-result jobs on the final code `46` / build `57` candidate commit.
2. Compare upload certificate SHA-256 `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14` with the upload certificate shown in Play Console.
3. Run the critical first-run, private staged-sync publication,
   interrupted-publication recovery, upgrade-policy migration, sync-resume,
   blank/off recursive recovery, default-off requester relay, explicit
   independent opt-ins, configured-endpoint validation/bootstrap, verified
   manual-peer, terminal bogus/invalid/stale cases, fail-closed no-route,
   HNS-only browsing, ICANN-only browsing, convergent/divergent dual-root
   browsing, download, website-data deletion, and gateway-log deletion flows
   on a physical supported Android device using the exact signed candidate.
4. Reconfirm the aligned privacy policy and reconcile the existing live Play
   listing's Data safety/app-access/content/ads answers, listing copy, release
   notes, and stale screenshots before submitting the verified code `46` AAB.

## Candidate Verification Status

- `0.5.3` / code 43 portable `scripts/check.sh`: passed for `14edcaf` on
  2026-07-28 in CI run 30323566765.
- `0.5.3` / code 43 Android build, unit tests, lint, runtime boundaries, and
  unsigned release-bundle structure: passed in the same run.
- `0.5.3` / build 47 Apple ABI, XCFramework, XCTest/simulator, and device-link
  gate: passed in the same run.
- Docs-only HEAD `153db03`: repository policy and Required CI passed on
  2026-07-28 in run 30393560141; code/platform jobs were correctly skipped.
- `0.5.4` exact portable/platform gates: passed for `8ffc296` on 2026-07-28 in
  CI run 30402803553.
- `0.5.4` signed APK/AAB verification: passed as predecessor release evidence;
  generated binaries remain outside the tracked source tree.
- `0.5.5` / code `45` signed-package verification and Google Play production
  deployment: completed.
- `0.5.5` / build `56` exact CI: passed. Its live screenshot run failed closed
  because compressed SOA negative evidence and stale plan-start timing made the
  dual-root ICANN result indeterminate; it was not uploaded.
- `0.5.5` / code `46` and build `57` exact portable/platform and signed-package
  gates: pending for the shared fix candidate.
- `0.5.5` / code `46` exact signed-build physical Android acceptance: pending.
- iOS real-device qualification: pending. It is separate from App Store
  submission eligibility and remains required before installed-iOS or
  ecosystem qualification is claimed.

## Historical `0.4.1` Evidence

- `./scripts/check.sh` passed on 2026-07-15 for Android `0.4.1` with shared Rust engine `0.4.0`, including supply-chain/version checks, formatting, warning-denied Clippy, all three cargo-deny scopes, the complete Rust test matrix, fuzz-target compilation, and the snapshot exporter.
- The final signed Android build passed with Gradle 9.6.1 / AGP 9.2.1, compile/target SDK 37, NDK `28.2.13676358`, and build-tools AAPT2 36.1.0; the clean gate completed 97 actionable tasks in 11m 13s after compiling both native ABIs.
- Android tests and lint reported 187 unit tests passed and no debug or release lint errors.
- Both packaged libraries reported NDK r28c, Android API 34, stripped status, 16 KiB PT_LOAD alignment, GNU_RELRO, non-executable GNU_STACK, BIND_NOW/NOW, and matching unstripped debug-symbol Build IDs. The signed release APK also passed `zipalign -c -P 16 4`.
- The final signed `0.4.1` / code 39 AAB SHA-256 was `4b2cc8b1da7700675eedb1ed2319ccafd9541acc7114abff9bd60eb6399b4267`. The signed GitHub APK SHA-256 was `a5a9d50d5b19302af488f7f5e6c68281364070edc7edcb14e16dbb1e1a5d61a2`; it matched the established release signer and passed APK Signature Scheme v2 plus 16 KiB ZIP-alignment verification.

## Watch Items

- Sync runs while any app activity is started and stops when the entire app backgrounds; verify cross-screen continuity, interruption, and catch-up resume on the release device.
- Release AAB signing and Play upload remain secret-dependent external operations. CI should build and structurally verify an unsigned release bundle without receiving signing or Play credentials.
- General-purpose browsing can reach arbitrary third-party content; keep target audience and content rating conservative and consistent with the live listing.
- Re-review the accepted hosted policy, repository policy, in-app privacy copy, and live Data safety answers whenever a material networking, storage, diagnostics, or third-party-service behavior changes.
- The hosted and repository policies are aligned at this checkpoint; re-review
  both whenever behavior or store disclosures change.
