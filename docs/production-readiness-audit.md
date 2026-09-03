# Production Readiness Audit

Last audited: 2026-09-02

Current source coordinates Android `1.0.4` / code `56`, embedded
non-publishable Rust `1.0.0`, and iOS `1.0.4` / build `65`. Earlier
HNWR-v2/ECH-and-sync-telemetry code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed exact-source CI and the
complete Android and Apple platform matrix in run `31807520618`; that evidence
predates the current direct-wallet implementation. Exact pre-release
application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the
complete manually dispatched CI matrix in run `31835813994`, including policy,
Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
the complete Apple ABI/XCFramework/app/simulator gate, and aggregate Required
CI. CodeQL runs `31833858421` and `31833858650` also passed.
Android `1.0.3` / code `55` is the preceding Google Play production release;
code `56` is the replacement candidate. Apple has published `1.0.3` / build
`63`; iOS `1.0.4` / build `65` is the replacement candidate for withdrawn
build `64` and retains manual release.

This audit records the release checkpoint for the existing public
Google Play and Apple App Store apps. Google Play production contains Android
`0.5.6` (`versionCode 47`) and shared Rust `0.5.6` from shipping source
`417af67efd68198de4871c0a339d1e456b60cb68`; the Apple public baseline
observed on 2026-07-28 was `0.5.0`. Apple published iOS `1.0.3` on
2026-09-02, and the public record reports `1.0.3` as current. The prior
`VALID`, `WAITING_FOR_REVIEW`, and manual-release state
below is retained as submission history, not current status. No TestFlight
distribution was used. iOS was not incremented for the later Android-only
runtime-lock and Proof Details fixes.

The candidate has native-only Android and iOS wallet controllers for
create/restore/open/status/unlock/lock, direct HNS peer synchronization,
receive/QR, guarded send, recent activity, birthday height, protected deletion,
and native-only exact-text name import. Legacy
HNWR-v1 remains a separate exact decoder shape. Its underlying lifecycle tranche passed the
full Pixel 9 exercise at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
Historical `0.5.8` source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
run `31402758394` and a fresh Pixel 9 install; documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; those runs remain historical. Code-bearing `0.5.9` source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including its historical HNWR-v1 Android and complete Apple
gates. Earlier HNWR-v2 source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI `31807520618`, including
repository policy, Rust/supply-chain, Android build/unit, API 37 native
instrumentation, the complete Apple ABI/XCFramework/app/simulator gate, and
Required CI; CodeQL runs `31807519998` and `31807520229` also passed. Debug
artifact `9222123624` has artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is debug-only rather than store signed. Historical exact
debug APK artifact `9080493058` has SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`;
it is package `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`, minimum
API 30, target API 37, `arm64-v8a` + `x86_64`, and default Android Debug APK-v2
signed. It is not store signed. The exact APK installed on a Pixel 9 (`tokay`),
Android 17 / API 37, security patch 2026-07-05, build `CP2A.260705.006`, after
the incompatible historical code `49` debug update safely failed. The
authorized reinstall removed only the debug package/data; production remained
installed and untouched. The on-device APK digest matched, cold launch
succeeded, and the fresh-install wallet screen exposed the no-wallet controls
and fail-closed read projection. No wallet, secret, account, credentialed sync,
or value action ran. Signing, fresh screenshots, store
declaration reconciliation/upload, and the physical-iPhone matrix remain open.
The product uses a wallet-owned direct peer controller and does not require the
older scoped-loopback compatibility seam. Website-provider, unfinished Bitcoin
and name-operation UI, settlement, exchange, HNSA/HNSR, and P2P-market gates
remain false. iOS implements nonblocking read/lifecycle teardown with
an exact lease handoff. Exact app/simulator CI covers the retirement queue/lease
and stale-completion publication-authority predicates; an end-to-end
credentialed read in flight remains unavailable without the scoped
credential/backend/data boundary.

## Release Findings

| Area | Status | Finding |
| --- | --- | --- |
| Current source identity | Exact dependency cohort; platform candidates independent | Android `1.0.4` / code `56`, iOS `1.0.4` / build `65`, and embedded Rust `1.0.0` use the exact checksum-bearing published HNS, engine, and wallet dependencies, including the complete wallet `0.2.1` cohort. [released-dependency-cohort.md](released-dependency-cohort.md) binds that policy. |
| Android compatibility | Code 48, GitHub APK only | Android `0.5.7` lowers the application and native NDK floor from API 34 to API 30. Search encoding remains explicitly UTF-8 through the compatible `URLEncoder` overload, with Unicode and query-delimiter regression coverage. |
| Android release build | Code 47 signed and published | Shipping source `417af67efd68198de4871c0a339d1e456b60cb68` produced the 51,323,995-byte APK (SHA-256 `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`) and 60,276,192-byte AAB (SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`). Both passed their signed-package gates. GitHub Release [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) contains only the verified APK; the Play AAB and unchanged iOS build are not attached. |
| Public Play listing | Code 55 committed to production | Android Publisher edit `07303019632521856332` uploaded Android `1.0.3` / code `55`, assigned it to production with status `completed`, and committed. `generatedApks/55` returned HTTP `200`; Google controls remaining review and propagation. |
| App Store update | `1.0.4` waiting for review | Protected workflow `33699401108` uploaded iOS `1.0.4` / build `64`, which became `VALID`. Workflow `33702492441` reconciled metadata, preserved screenshots, and submitted it; App Store Connect reports `WAITING_FOR_REVIEW` with manual release. The unapproved default-browser and MarketplaceKit app-installation entitlements are absent so this update can ship independently. |
| Native wallet slice | Direct HNS wallet active; final release gates pending | The exact pinned controller is connected to native Android/iOS lifecycle, direct peer synchronization, receive/QR, guarded sends, activity, name import, birthday height, and deletion. Websites cannot access it. Unfinished Bitcoin/name-operation/Shakedex screens remain hidden. Current Android device exercises cover the working debug flow; exact final CI, signed product, screenshots, and physical-iPhone evidence remain open. |
| Android runtime opening | Root cause fixed and release-device validated | Rust 1.92's stable `std::fs::File` lock implementation omitted Android, so the first header-state lock returned `Unsupported` and `BrowserRuntime::open` returned no handle. The Android target now uses the locked `libc 0.2.186` `flock` operations; the equivalent upstream fix is merged for Rust 1.98 in `rust-lang/rust#157038`. The exact signed code `47` APK cold-launched and synchronized successfully after an in-place data-preserving upgrade. |
| Android Proof Details | Namespace attribution fixed and release-device confirmed | Native-gateway routing is namespace-agnostic because every canonical DNS host enters the retained dual-root gateway. The prior UI treated that route as ICANN, so a retained HNS trace produced DNSSEC/synthetic ICANN details. Proof Details now uses only the strict retained `namespaceResolution` decision. Pre-fix reproduction, paired instrumentation, and HNS browsing/proof behavior passed on the Pixel 9 release device after correction. |
| Privacy policy | Direct-wallet and QR disclosure updated | The canonical hosted policy covers wallet peer synchronization, approved broadcast, native storage/deletion, and on-device camera QR processing. Store privacy/category answers still require live readback. |
| Manifest exposure | Ready | The only app-defined exported entry point is `LauncherActivity`. Browser, settings, diagnostics, HNS inspector, history, download, and wallet activities are non-exported. `WalletSyncForegroundService` is also non-exported and limited to user-started `dataSync`. |
| Backup / transfer | Ready | App backup and device-transfer extraction are disabled for local browsing data, WebView state, download records, diagnostics, resolver cache, HNS sync/cache state, and Android wallet storage. iOS wallet files use complete file protection and are excluded from backup; its ThisDeviceOnly Keychain item follows platform Keychain retention semantics. |
| Cleartext policy | Ready | Cleartext is disabled globally with a loopback-only exception for the local gateway. User-selected HTTP and direct DNS/HNS traffic are accurately disclosed, but ordinary open-web and user-initiated transfers are outside Google Play's Data safety collection/sharing scope. |
| WebView hardening | Ready | Mixed content is blocked, Safe Browsing is enabled, file/content access is disabled, native JavaScript bridges are removed, WebView debugging follows `BuildConfig.DEBUG`, and browser-wide loopback proxying sends every canonical DNS host to exact per-origin Rust dual-root preparation. |
| Privacy controls | Confirmed-wallet deletion current source/platform qualification complete; signed product pending | Settings can clear browser and diagnostic data. Android and iOS expose a protected two-stage confirmed-wallet deletion flow bound to the exact foreground owner, network, account, storage lease, and controller generation. It revokes read/UI authority, closes the controller, deletes the device-bound key before database artifacts, blocks file removal when key deletion fails, and retries encrypted-orphan cleanup after a post-key file failure. Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete platform matrix in `31835813994`; both current CodeQL runs passed. External recovery backups are unaffected. Store copy and policy source describe the flow; signed-product and physical-device qualification remain open. |
| Build supply chain | Current exact-source CI and CodeQL green; signed-store verification pending | Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed policy, Rust/supply-chain, Android, Apple, and Required CI in `31835813994`; CodeQL runs `31833858421` and `31833858650` also passed. Historical `0.5.8`, pre-ECH `0.5.9`, and earlier HNWR-v2 results remain scoped to their named sources. Upload signing and store artifact verification remain separate. |
| 16 KiB / native symbols | Code 47 gates passed | Code `47` passed PT_LOAD alignment, hardening, stripping, Build ID, matching FULL debug metadata, path sanitization, R8 mapping, notices, upload-signing, APK-signature, and 16 KiB ZIP-alignment verification. |
| Release-device acceptance | Core exact-signed acceptance passed; broader matrix remains open | On a Pixel 9 running Android 17 / API 37, the exact signed APK upgraded code `46` to `47` with data preserved, cold-launched, reached `up_to_date` at height `340348` with lag `0`, freshness `current`, and `error: null`, and passed manual sync plus HNS browsing/proof behavior. Lifecycle, policy migration, requester/recovery combinations, downloads, Service Workers, WebSockets, and cross-origin behavior remain broader qualification items. |
| Code 50 debug-device projection | Installed UI passed; credentialed wallet and signed product pending | On a Pixel 9 (`tokay`), the incompatible historical code `49` debug update failed safely, then an authorized debug-only reinstall left production untouched. The exact artifact digest matched on device, cold launch completed in 469 ms without a fatal signature in 300 process log lines, and `WalletActivity` showed the no-wallet controls and fail-closed HNWR rows. No wallet, secret, account, read sync, or value action ran. |
| Data collection posture | Repository review updated; live-form reconciliation required | No ads, analytics SDKs, developer accounts, sensitive permissions, advertising ID access, or developer telemetry endpoint was found. The policy now records that a relay peer receives the DNS name/type and source network address needed for the request. Retain the live `No collected / No shared` posture only after reconciling the current Play definitions and WebView-provider Safe Browsing guidance. |

## Applied Cleanup

- Added user-facing deletion of both cookies and WebView origin storage instead of clearing cookies alone.
- On Android, replaced the automatic developer-hosted default homepage request
  with a bundled, Content-Security-Policy-restricted start page that contains
  no network resources. The iOS shell defaults to `https://shakescape.com/`
  and lets the user replace it.
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
  names/types and client network address. Historical wallet-lifecycle source
  `909dbd1a713f322f0a8d4cff88e765c612e184f3` was deployed and read back at the
  canonical product URL.
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
3. Complete the remaining `1.0.0` product gates: provision and qualify a scoped
   indexed source, plus archive-capable/durable raw-tx data for fresh restore,
   or retain the explicit unavailable state; then generate commit-bound iOS and
   current Android screenshots without secrets, build and verify signed
   artifacts, and reconcile category, financial-feature, Data safety, App
   Privacy, review-note, and release answers before any upload or submission.
   Exact current HNWI-v1 source
   `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete Rust,
   Android, Apple, and aggregate Required CI matrix in run `31835813994`, and
   both current CodeQL runs passed. End-to-end credentialed
   read/import-in-flight qualification, signed Android product qualification and the
   physical-iPhone matrix are not.

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
- Native wallet lifecycle qualification: passed on the exact pre-repin feature
  source. Android fresh-install creation/confirmation, unlock/lock, process
  reopen, owner-only storage, and mainnet/testnet isolation passed on the exact
  CI APK; complete Apple CI passed in dated run `31393998309`. Final application
  historical `0.5.8` source `f21bee1` then passed Required CI `31402758394` and
  a fresh Pixel 9 install; docs parent `ce9c09a` passed full manual CI
  `31411048376`. Earlier HNWR-v2 source
  `986accb7d86d220af63187031e629a9ce69d71e5` passed exact full CI
  `31807520618`; historical code `50` debug APK evidence subsequently covered
  the installed fail-closed native wallet UI on a Pixel 9. No wallet was
  created/restored and no credentialed read ran. Backend/data integration,
  credentialed Android wallet qualification, physical-iPhone qualification,
  signing, screenshots, and store review/upload remain pending. Public
  artifacts still predate these controls.

## Watch Items

- Sync runs while any app activity is started and stops when the entire app backgrounds; verify cross-screen continuity, interruption, and catch-up resume on the release device.
- Code `47` AAB signing and Play upload completed as credentialed external
  operations. CI should continue to build and structurally verify unsigned
  release bundles without receiving signing or Play credentials.
- General-purpose browsing can reach arbitrary third-party content; keep target audience and content rating conservative and consistent with the live listing.
- Re-review the hosted policy, repository policy, in-app privacy copy, and live
  Data safety answers whenever a material networking, storage, diagnostics, or
  third-party-service behavior changes.
- Version-neutral HNWR-aware policy source `a5539cb` is deployed and read back;
  reconcile the current store forms and categories.
