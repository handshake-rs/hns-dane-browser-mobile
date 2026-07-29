# Production Readiness Audit

Last audited: 2026-07-29

This audit records the release checkpoint for the existing public
Google Play and Apple App Store apps. Google Play production completed
`0.5.5` / code `46`; the Apple public baseline observed on 2026-07-28 was
`0.5.0`. Current source declares Android `0.5.5` (`versionCode 46`), Rust
engine `0.5.5`, and iOS `0.5.5` (build `57`). Code `46` passed its exact
signed-package gates and production deployment. Build `57` carries the same
shared dual-root fix, has been uploaded and submitted directly to App Review
with manual release, and is tracked separately from the still-open Android/iOS
physical-device matrices.

## Release Findings

| Area | Status | Finding |
| --- | --- | --- |
| Android release build | Code 46 signed, verified, and deployed | Source `d24f85158854abb8be4a7bb9e914aebe5e7e4679` produced the signed code 46 APK (SHA-256 `b36a4346ffcba14c081500ef3dc7c5012cabd30f42cdaa80a354eefb5da210ba`) and AAB (SHA-256 `728d8892e180d954652668a4e53a7e2d6c7542e9d36330f4803cdecdb34598b0`). Both passed build, test, lint, bundle structure, native hardening/symbol, signature, and alignment gates. |
| Public Play listing | Code 46 production complete | Android Publisher edit `17438779769069438085` committed code 46 directly to production with status `completed`; `generatedApks/46` returned HTTP `200`. |
| App Store update | Public `0.5.0`; build 57 is `WAITING_FOR_REVIEW`; device qualification tracked separately | Apple reported public version `0.5.0` on 2026-07-28. Exact-head Apple CI `30454904736` and live Release screenshot run `30454926117` passed for build `57` source `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Protected run `30456522039` signed and uploaded the 47,930,601-byte IPA (SHA-256 `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`). App Store Connect reports the build `VALID`, direct App Review `WAITING_FOR_REVIEW`, `releaseType=MANUAL`, and `reviewType=APP_STORE`. No TestFlight distribution is part of this release, and a real-iPhone pass remains a separate qualification item. |
| Privacy policy | Repository and hosted policy aligned | The canonical `https://denuoweb.com/work/hns-dane-browser/privacy` policy now discloses the independently opt-in P2P requester and user-configured recursive HNS DoH recovery, operator-visible queried names/types, timing and source IP, blank/off defaults, one-way legacy-key tombstone, local DNSSEC/DANE validation, validating ICANN bootstrap, and continued prohibition on HNS WebPKI fallback. |
| Manifest exposure | Ready | The only app-defined exported entry point is `LauncherActivity`. Browser, settings, diagnostics, HNS inspector, history, download, and other app activities are non-exported, and the app declares no service. Merged dependency components remain subject to their own signature/permission guards. |
| Backup / transfer | Ready | App backup and device-transfer extraction are disabled for local browsing data, WebView state, download records, diagnostics, resolver cache, and HNS sync/cache state. |
| Cleartext policy | Ready | Cleartext is disabled globally with a loopback-only exception for the local gateway. User-selected HTTP and direct DNS/HNS traffic are accurately disclosed, but ordinary open-web and user-initiated transfers are outside Google Play's Data safety collection/sharing scope. |
| WebView hardening | Ready | Mixed content is blocked, Safe Browsing is enabled, file/content access is disabled, native JavaScript bridges are removed, WebView debugging follows `BuildConfig.DEBUG`, and browser-wide loopback proxying sends every canonical DNS host to exact per-origin Rust dual-root preparation. |
| Privacy controls | Improved | Settings can clear cookies plus WebView origin storage, and the diagnostics UI can clear the bounded gateway event log. The repository and in-app disclosures now describe WebView-provider Safe Browsing and these local retention controls. |
| Build supply chain | Code 46/build 57 shared and final Apple gates passed | Full manual CI run `30448341156` passed policy, Rust/supply-chain, Android, Apple, and Required CI at the version-bump source. Exact-head run `30454904736` passed policy, the complete Apple gate, and Required CI after the final iOS-only corrections. |
| 16 KiB / native symbols | Code 46 signed gates passed | The code 46 bundle passed PT_LOAD alignment, hardening, stripping, Build ID, matching FULL debug metadata, path sanitization, R8 mapping, notices, and upload signing; its APK passed signature and 16 KiB ZIP-alignment verification. |
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

1. Monitor Apple's review of build `57` and, after approval, perform the
   deliberately manual App Store release. Upload, metadata, screenshots, build
   linkage, full readback, and direct submission are complete.
2. Run the critical first-run, private staged-sync publication,
   interrupted-publication recovery, upgrade-policy migration, sync-resume,
   blank/off recursive recovery, default-off requester relay, explicit
   independent opt-ins, configured-endpoint validation/bootstrap, verified
   manual-peer, terminal bogus/invalid/stale cases, fail-closed no-route,
   HNS-only browsing, ICANN-only browsing, convergent/divergent dual-root
   browsing, download, website-data deletion, and gateway-log deletion flows
   on a physical supported Android device using the exact signed artifact.
3. Reconcile the existing live Play listing's Data safety, app-access, content,
   ads, listing-copy, and stale-screenshot fields against deployed code `46`.

## Release Verification Status

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
- `0.5.5` / code `46` signed APK/AAB verification and direct Google Play
  production deployment: completed; edit `17438779769069438085` committed and
  `generatedApks/46` returned HTTP `200`.
- `0.5.5` / code `46` and build `57` shared portable/platform gates: passed in
  full manual CI run `30448341156`; exact code 46 signed-package gates passed.
- `0.5.5` / build `57` final iOS-only exact-head gate: policy, complete Apple
  matrix, and Required CI passed in run `30454904736`.
- `0.5.5` / build `57` live Release screenshots: four-image, exact-source,
  fixture-free provenance passed in run `30454926117`.
- `0.5.5` / build `57` signed upload and direct App Review: protected run
  `30456522039` uploaded the verified IPA; App Store Connect reports build
  `VALID`, version `appStoreState=WAITING_FOR_REVIEW`, and the direct review
  submission `WAITING_FOR_REVIEW`, with manual release and App Store review
  type. No TestFlight distribution was created.
- Public GitHub Release `v0.5.5`: published at annotated tag source
  `d926561091634cd69fc9b7e79a4b76003fa4ee47` with the verified code 46 APK and
  build 57 IPA assets.
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
- Future release AAB signing and Play upload remain credentialed external
  operations. CI should continue to build and structurally verify an unsigned
  release bundle without receiving signing or Play credentials.
- General-purpose browsing can reach arbitrary third-party content; keep target audience and content rating conservative and consistent with the live listing.
- Re-review the accepted hosted policy, repository policy, in-app privacy copy, and live Data safety answers whenever a material networking, storage, diagnostics, or third-party-service behavior changes.
- The hosted and repository policies are aligned at this checkpoint; re-review
  both whenever behavior or store disclosures change.
