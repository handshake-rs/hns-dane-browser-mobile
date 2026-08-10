# Production Readiness Audit

Last audited: 2026-08-10

Current source is the coordinated `0.5.8` release-preparation candidate:
Android code `49`, embedded non-publishable Rust `0.5.8`, and iOS build `58`.
Google Play production remains on `0.5.6` / code `47`, GitHub's latest Android
artifact is `0.5.7` / code `48`, and Apple's public version remains `0.5.5` /
build `57`.

This audit records the release checkpoint for the existing public
Google Play and Apple App Store apps. Google Play production contains Android
`0.5.6` (`versionCode 47`) and shared Rust `0.5.6` from shipping source
`417af67efd68198de4871c0a339d1e456b60cb68`; the Apple public baseline
observed on 2026-07-28 was `0.5.0`. Apple published iOS `0.5.5` on
2026-07-31, and the public record still reports `0.5.5` as current on
2026-08-09. The prior `VALID`, `WAITING_FOR_REVIEW`, and manual-release state
below is retained as submission history, not current status. No TestFlight
distribution was used. iOS was not incremented for the later Android-only
runtime-lock and Proof Details fixes.

The candidate adds native-only Android and iOS wallet controllers for
create/restore/open/status/unlock/lock and one non-value HNS account identity.
Its underlying exact source passed a fresh Pixel 9 reinstall exercise and
Required CI run `31393998309`, including the complete Apple
ABI/XCFramework/app/simulator gate, at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The final version/repin/metadata
commit still needs exact-head CI, signing, fresh screenshots, and store
declaration reconciliation. Website providers, balances, transfers, names,
sending, settlement, exchange features, HNSA/HNSR controls, and P2P
marketplaces remain unavailable.

## Release Findings

| Area | Status | Finding |
| --- | --- | --- |
| `0.5.8` source identity | Final dependency authority configured; exact release gates pending | Android `0.5.8` / code `49`, embedded Rust `0.5.8`, and iOS `0.5.8` / build `58` are aligned. `hns-wallet-mobile` is pinned to final wallet `0.1.0` source `4e78bb2587bc448d3a65341c7628b2e62cae79cd`, which uses final `hns-rs 0.2.0` source `b24b66c382de53330ec21dd3137e056a2bea3e2d`. Source policy, lockfile, and notices bind that chain. No signed candidate has been built or uploaded. |
| Android compatibility | Code 48, GitHub APK only | Android `0.5.7` lowers the application and native NDK floor from API 34 to API 30. Search encoding remains explicitly UTF-8 through the compatible `URLEncoder` overload, with Unicode and query-delimiter regression coverage. |
| Android release build | Code 47 signed and published | Shipping source `417af67efd68198de4871c0a339d1e456b60cb68` produced the 51,323,995-byte APK (SHA-256 `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`) and 60,276,192-byte AAB (SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`). Both passed their signed-package gates. GitHub Release [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) contains only the verified APK; the Play AAB and unchanged iOS build are not attached. |
| Public Play listing | Code 47 production complete | Android Publisher edit `07330408575596336357` committed code `47` directly to production with status `completed`; `generatedApks/47` returned HTTP `200`. |
| App Store update | Public `0.5.5`; device qualification tracked separately | Exact-head Apple CI `30454904736` and live Release screenshot run `30454926117` passed for build `57` source `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Protected run `30456522039` signed and uploaded the 47,930,601-byte IPA (SHA-256 `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`). The submission was then `VALID`, direct App Review `WAITING_FOR_REVIEW`, `releaseType=MANUAL`, and `reviewType=APP_STORE`; Apple published `0.5.5` on 2026-07-31. No TestFlight distribution was part of this release, and a real-iPhone pass remains a separate qualification item. |
| Native wallet slice | Feature tranche qualified; final release gates pending | The exact pinned `hns-wallet-mobile` controller is connected to native-only Android/iOS controls and secure app-owned database-key lifecycles. The slice exposes no provider, balances, transfers, names, sending, settlement, exchange, HNSA/HNSR, or marketplace. Exact-source Android and Apple qualification passed at `571ea0c`; the final dependency repin is complete, but the resulting `0.5.8` commit still needs exact-head evidence, so no signed-product readiness is claimed. |
| Android runtime opening | Root cause fixed and release-device validated | Rust 1.92's stable `std::fs::File` lock implementation omitted Android, so the first header-state lock returned `Unsupported` and `BrowserRuntime::open` returned no handle. The Android target now uses the locked `libc 0.2.186` `flock` operations; the equivalent upstream fix is merged for Rust 1.98 in `rust-lang/rust#157038`. The exact signed code `47` APK cold-launched and synchronized successfully after an in-place data-preserving upgrade. |
| Android Proof Details | Namespace attribution fixed and release-device confirmed | Native-gateway routing is namespace-agnostic because every canonical DNS host enters the retained dual-root gateway. The prior UI treated that route as ICANN, so a retained HNS trace produced DNSSEC/synthetic ICANN details. Proof Details now uses only the strict retained `namespaceResolution` decision. Pre-fix reproduction, paired instrumentation, and HNS browsing/proof behavior passed on the Pixel 9 release device after correction. |
| Privacy policy | Repository updated; hosted publication/readback pending | The repository policy covers the local wallet database, device-bound database key, recovery lifecycle, absent wallet network/provider/value paths, and existing resolver disclosures. Publish and read back the same text at `https://denuoweb.com/work/hns-dane-browser/privacy` before store submission. |
| Manifest exposure | Ready | The only app-defined exported entry point is `LauncherActivity`. Browser, settings, diagnostics, HNS inspector, history, download, native wallet, and other app activities are non-exported, and the app declares no service. Merged dependency components remain subject to their own signature/permission guards. |
| Backup / transfer | Ready | App backup and device-transfer extraction are disabled for local browsing data, WebView state, download records, diagnostics, resolver cache, HNS sync/cache state, and Android wallet storage. iOS wallet files use complete file protection and are excluded from backup; its ThisDeviceOnly Keychain item follows platform Keychain retention semantics. |
| Cleartext policy | Ready | Cleartext is disabled globally with a loopback-only exception for the local gateway. User-selected HTTP and direct DNS/HNS traffic are accurately disclosed, but ordinary open-web and user-initiated transfers are outside Google Play's Data safety collection/sharing scope. |
| WebView hardening | Ready | Mixed content is blocked, Safe Browsing is enabled, file/content access is disabled, native JavaScript bridges are removed, WebView debugging follows `BuildConfig.DEBUG`, and browser-wide loopback proxying sends every canonical DNS host to exact per-origin Rust dual-root preparation. |
| Privacy controls | Candidate disclosure updated | Settings can clear browser and diagnostic data. There is no in-app deletion control for a confirmed wallet; Android users can clear all app storage, while iOS removes the app-container database on uninstall and later wallet reconciliation deletes an orphaned Keychain item if the database is absent. This limitation is explicit in policy and store review material. |
| Build supply chain | Feature source passed; candidate rerun required | Required CI run `31393998309` passed at exact feature source `571ea0c`, including all selected Android, Apple, Rust, and supply-chain jobs. The `0.5.8` wallet repin and release metadata must receive a new exact-head run. |
| 16 KiB / native symbols | Code 47 gates passed | Code `47` passed PT_LOAD alignment, hardening, stripping, Build ID, matching FULL debug metadata, path sanitization, R8 mapping, notices, upload-signing, APK-signature, and 16 KiB ZIP-alignment verification. |
| Release-device acceptance | Core exact-signed acceptance passed; broader matrix remains open | On a Pixel 9 running Android 17 / API 37, the exact signed APK upgraded code `46` to `47` with data preserved, cold-launched, reached `up_to_date` at height `340348` with lag `0`, freshness `current`, and `error: null`, and passed manual sync plus HNS browsing/proof behavior. Lifecycle, policy migration, requester/recovery combinations, downloads, Service Workers, WebSockets, and cross-origin behavior remain broader qualification items. |
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
- Corrected Android header-state locking for the pinned Rust 1.92 toolchain.
  Standard `File::lock` returned `Unsupported` on Android because that target
  was absent from the implementation's supported-target list. The target-local
  `libc::flock` shim preserves shared, exclusive, nonblocking, and unlock
  behavior; a required API 37 emulator regression now opens fresh native
  storage and checks schema-v2 height/error output.
- Corrected Android Proof Details namespace attribution. The UI now consumes
  the strict retained dual-root decision instead of using the native-gateway
  route as an ICANN signal, with paired HNS/ICANN activity regressions.
- Added manual relay-peer configuration restricted to IP-literal endpoints. The runtime completes a live HSD handshake and verifies the current relay capability before persisting an endpoint; the `hsd` responder remains an explicit operator opt-in.
- Updated repository privacy and store disclosures for relay-visible queried
  names/types and client network address. The hosted page was aligned for that
  historical release; the new wallet-aware candidate text still requires
  publication and readback.
- Updated `androidx.activity:activity-ktx` from an alpha build to stable `1.13.0`.
- Added local dependency, test, lint, bundle-signing, and supply-chain verification, with immutable Action references in the checked-in workflow.

## Remaining Release Gates

1. Extend the exact signed Android `0.5.6` / code `47` physical-device
   qualification beyond the completed upgrade, cold-launch, sync, HNS browsing,
   and proof checks. Remaining rows include interrupted staged publication,
   upgrade-policy migration, sync resume, blank/off recursive recovery,
   default-off requester relay, independent opt-ins, configured-endpoint
   validation/bootstrap, verified manual peers, terminal bogus/invalid/stale
   cases, fail-closed no-route, convergent/divergent dual-root browsing,
   downloads, Service Workers, WebSockets, website-data deletion, and
   gateway-log deletion.
2. Reconcile the existing live Play listing's Data safety, app-access, content,
   ads, listing-copy, and stale-screenshot fields. Code `47` production
   deployment is complete; this reconciliation remains a policy and listing
   maintenance item.
3. Qualify the final dependency-pinned `0.5.8` commit and its metadata changes:
   pass exact-head Required CI and CodeQL, generate commit-bound iOS and current
   Android screenshots without secrets, build and verify signed artifacts,
   publish/read back the hosted privacy policy, and reconcile category,
   financial-feature, Data safety, App Privacy, review-note, and release answers
   before any upload or submission.

## Release Verification Status

- `0.5.6` / code `47` Android release: shipping source
  `417af67efd68198de4871c0a339d1e456b60cb68` produced the verified
  51,323,995-byte APK (SHA-256
  `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`) and
  60,276,192-byte AAB (SHA-256
  `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`).
- Exact signed Pixel 9 acceptance: code `46` upgraded to `47` with data
  preserved; cold launch, manual sync, `up_to_date` height `340348`, lag `0`,
  freshness `current`, `error: null`, and HNS browsing/proof behavior passed on
  Android 17 / API 37.
- Required API 37 emulator regressions and Required CI passed in run
  `30484282637` on workflow-only descendant
  `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`; that commit is not the tagged
  shipping source and does not change the release artifacts.
- Google Play code `47` production deployment completed through edit
  `07330408575596336357`; `generatedApks/47` returned HTTP `200`. GitHub Release
  [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) contains the verified APK only, with no
  Play AAB or unchanged iOS asset.
- `0.5.5` / build `57` final iOS-only exact-head gate: policy, complete Apple
  matrix, and Required CI passed in run `30454904736`.
- `0.5.5` / build `57` live Release screenshots: four-image, exact-source,
  fixture-free provenance passed in run `30454926117`.
- `0.5.5` / build `57` signed upload and direct App Review: protected run
  `30456522039` uploaded the verified IPA; App Store Connect reports build
  `VALID`, version `appStoreState=WAITING_FOR_REVIEW`, and the direct review
  submission `WAITING_FOR_REVIEW`, with manual release and App Store review
  type. No TestFlight distribution was created.
- iOS real-device qualification: pending. It is separate from App Store
  submission eligibility and remains required before installed-iOS or
  ecosystem qualification is claimed.
- Native wallet feature qualification: passed on the exact pre-repin feature
  source. Android fresh-install creation/confirmation, unlock/lock, process
  reopen, owner-only storage, and mainnet/testnet isolation passed on the exact
  CI APK; complete Apple CI passed in run `31393998309`. Public artifacts still
  predate these controls. The final dependency-chain repin is complete; exact
  `0.5.8` CI, signing, screenshots, and store review remain pending.

## Watch Items

- Sync runs while any app activity is started and stops when the entire app backgrounds; verify cross-screen continuity, interruption, and catch-up resume on the release device.
- Code `47` AAB signing and Play upload completed as credentialed external
  operations. CI should continue to build and structurally verify unsigned
  release bundles without receiving signing or Play credentials.
- General-purpose browsing can reach arbitrary third-party content; keep target audience and content rating conservative and consistent with the live listing.
- Re-review the hosted policy, repository policy, in-app privacy copy, and live
  Data safety answers whenever a material networking, storage, diagnostics, or
  third-party-service behavior changes.
- The wallet-aware repository policy is updated, but its hosted publication and
  readback are release gates for `0.5.8`.
