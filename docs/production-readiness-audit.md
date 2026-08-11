# Production Readiness Audit

Last audited: 2026-08-11

Current source is the `0.5.10` release candidate: Android code `51`, embedded
non-publishable Rust `0.5.9`, and iOS `0.5.10` build `60`.
Its ECH-and-sync-telemetry engine pin requires fresh exact-source CI and platform
qualification; exact-hash evidence below remains evidence for the named
pre-ECH source only.
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

The candidate has native-only Android and iOS wallet controllers for
create/restore/open/status/unlock/lock, one HNS account identity, and strict
HNWR-v1 read UI for synchronized balance, receive target, transaction history,
tracked names, and module status. Its underlying lifecycle tranche passed the
full Pixel 9 exercise at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
Historical `0.5.8` source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
run `31402758394` and a fresh Pixel 9 install; documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; those runs remain historical. Code-bearing `0.5.9` source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including the current Android and complete Apple gates. Exact
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
The product
installs no scoped loopback credential or indexed wallet backend, so every read
field is fail-closed and unavailable. The live pruned `hsrd` is unsuitable
because it lacks wallet indexing and scoped authentication. Existing-wallet
indexed/retained evidence remains distinct; fresh restore additionally needs
archive-capable raw bytes or another durable wallet-relevant raw-tx source.
Name import is absent.
Website-provider, send/value, settlement, exchange, HNSA/HNSR, and P2P-market
gates remain false. iOS now implements nonblocking read/lifecycle teardown with
an exact lease handoff. Exact app/simulator CI covers the retirement queue/lease
and stale-completion publication-authority predicates; an end-to-end
credentialed read in flight remains unavailable without the scoped
credential/backend/data boundary.

## Release Findings

| Area | Status | Finding |
| --- | --- | --- |
| `0.5.10` source identity | Platform versions coordinated; Rust remains independently versioned; fresh qualification and signed product pending | Android `0.5.10` / code `51` and iOS `0.5.10` / build `60` retain embedded Rust `0.5.9`. `hns-wallet-mobile` is pinned to final wallet `0.1.0` source `2229be849557d58a8eb723bcc03349f0f2df9796`, which uses final `hns-rs 0.2.0` source `b24b66c382de53330ec21dd3137e056a2bea3e2d`. Source policy, lockfile, and notices bind that chain. Prior `0.5.9` source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI `31433931682`; the current source requires fresh qualification. Historical debug artifact `9080493058` is not upload/store signed, and no signed `0.5.10` candidate has been built or uploaded. |
| Android compatibility | Code 48, GitHub APK only | Android `0.5.7` lowers the application and native NDK floor from API 34 to API 30. Search encoding remains explicitly UTF-8 through the compatible `URLEncoder` overload, with Unicode and query-delimiter regression coverage. |
| Android release build | Code 47 signed and published | Shipping source `417af67efd68198de4871c0a339d1e456b60cb68` produced the 51,323,995-byte APK (SHA-256 `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`) and 60,276,192-byte AAB (SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`). Both passed their signed-package gates. GitHub Release [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) contains only the verified APK; the Play AAB and unchanged iOS build are not attached. |
| Public Play listing | Code 47 production complete | Android Publisher edit `07330408575596336357` committed code `47` directly to production with status `completed`; `generatedApks/47` returned HTTP `200`. |
| App Store update | Public `0.5.5`; device qualification tracked separately | Exact-head Apple CI `30454904736` and live Release screenshot run `30454926117` passed for build `57` source `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Protected run `30456522039` signed and uploaded the 47,930,601-byte IPA (SHA-256 `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`). The submission was then `VALID`, direct App Review `WAITING_FOR_REVIEW`, `releaseType=MANUAL`, and `reviewType=APP_STORE`; Apple published `0.5.5` on 2026-07-31. No TestFlight distribution was part of this release, and a real-iPhone pass remains a separate qualification item. |
| Native wallet slice | Read projection CI- and Android-UI-qualified; backend and signed-product qualification pending | The exact pinned controller is connected to native-only Android/iOS lifecycle controls, secure app-owned key storage, and strict HNWR-v1 read projection/UI. The exact code `50` debug artifact installed and cold-launched on a Pixel 9; its native wallet screen exposed the fresh no-wallet state, lifecycle controls, fail-closed read rows, and disabled value/marketplace boundary. No wallet was created/restored and no credentialed read ran. The product provides no scoped credential or indexed backend, so reads remain unavailable; name import is absent and provider/send/value/HNSA/HNSR/market gates remain false. The live pruned node lacks wallet index/auth; a pruned indexed node can return indexed history, and an existing wallet may reuse retained raw bytes, while fresh restore needs a durable raw-tx source. iOS detaches UI authority and retires the native controller off the main actor while retaining its exact lease through destruction and cleanup; the exact Apple app/simulator gate passed. No signed-product or iPhone readiness is claimed. |
| Android runtime opening | Root cause fixed and release-device validated | Rust 1.92's stable `std::fs::File` lock implementation omitted Android, so the first header-state lock returned `Unsupported` and `BrowserRuntime::open` returned no handle. The Android target now uses the locked `libc 0.2.186` `flock` operations; the equivalent upstream fix is merged for Rust 1.98 in `rust-lang/rust#157038`. The exact signed code `47` APK cold-launched and synchronized successfully after an in-place data-preserving upgrade. |
| Android Proof Details | Namespace attribution fixed and release-device confirmed | Native-gateway routing is namespace-agnostic because every canonical DNS host enters the retained dual-root gateway. The prior UI treated that route as ICANN, so a retained HNS trace produced DNSSEC/synthetic ICANN details. Proof Details now uses only the strict retained `namespaceResolution` decision. Pre-fix reproduction, paired instrumentation, and HNS browsing/proof behavior passed on the Pixel 9 release device after correction. |
| Privacy policy | Current HNWR boundary deployed and read back | Version-neutral source `a5539cb063fb4b19fed4dff5400a3bc991acdc4f` covers wallet lifecycle, absent network/provider/value paths, and unavailable read rows. Firebase run `31485234945` and live HTTP readback passed. Store privacy/category answers still require live readback. |
| Manifest exposure | Ready | The only app-defined exported entry point is `LauncherActivity`. Browser, settings, diagnostics, HNS inspector, history, download, native wallet, and other app activities are non-exported, and the app declares no service. Merged dependency components remain subject to their own signature/permission guards. |
| Backup / transfer | Ready | App backup and device-transfer extraction are disabled for local browsing data, WebView state, download records, diagnostics, resolver cache, HNS sync/cache state, and Android wallet storage. iOS wallet files use complete file protection and are excluded from backup; its ThisDeviceOnly Keychain item follows platform Keychain retention semantics. |
| Cleartext policy | Ready | Cleartext is disabled globally with a loopback-only exception for the local gateway. User-selected HTTP and direct DNS/HNS traffic are accurately disclosed, but ordinary open-web and user-initiated transfers are outside Google Play's Data safety collection/sharing scope. |
| WebView hardening | Ready | Mixed content is blocked, Safe Browsing is enabled, file/content access is disabled, native JavaScript bridges are removed, WebView debugging follows `BuildConfig.DEBUG`, and browser-wide loopback proxying sends every canonical DNS host to exact per-origin Rust dual-root preparation. |
| Privacy controls | Candidate disclosure updated | Settings can clear browser and diagnostic data. There is no in-app deletion control for a confirmed wallet; Android users can clear all app storage, while iOS removes the app-container database on uninstall and later wallet reconciliation deletes an orphaned Keychain item if the database is absent. This limitation is explicit in policy and store review material. |
| Build supply chain | Pre-ECH `0.5.9` CI green; ECH/sync-telemetry and signed-store verification pending | Required CI run `31402758394` and docs-parent run `31411048376` remain historical `0.5.8` evidence. Exact pre-ECH `0.5.9` source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed policy, Rust/supply-chain, Android, Apple, and Required CI in `31433931682`; CodeQL `31433931259` and Code Quality `31433931278` also passed. The current ECH-and-sync-telemetry source requires fresh CI and platform qualification. Upload signing and store artifact verification remain separate. |
| 16 KiB / native symbols | Code 47 gates passed | Code `47` passed PT_LOAD alignment, hardening, stripping, Build ID, matching FULL debug metadata, path sanitization, R8 mapping, notices, upload-signing, APK-signature, and 16 KiB ZIP-alignment verification. |
| Release-device acceptance | Core exact-signed acceptance passed; broader matrix remains open | On a Pixel 9 running Android 17 / API 37, the exact signed APK upgraded code `46` to `47` with data preserved, cold-launched, reached `up_to_date` at height `340348` with lag `0`, freshness `current`, and `error: null`, and passed manual sync plus HNS browsing/proof behavior. Lifecycle, policy migration, requester/recovery combinations, downloads, Service Workers, WebSockets, and cross-origin behavior remain broader qualification items. |
| Code 50 debug-device projection | Installed UI passed; credentialed wallet and signed product pending | On a Pixel 9 (`tokay`), the incompatible historical code `49` debug update failed safely, then an authorized debug-only reinstall left production untouched. The exact artifact digest matched on device, cold launch completed in 469 ms without a fatal signature in 300 process log lines, and `WalletActivity` showed the no-wallet controls and fail-closed HNWR rows. No wallet, secret, account, read sync, or value action ran. |
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
3. Complete the remaining `0.5.10` product gates: provision and qualify a scoped
   indexed source, plus archive-capable/durable raw-tx data for fresh restore,
   or retain the explicit unavailable state; then generate commit-bound iOS and
   current Android screenshots without secrets, build and verify signed
   artifacts, and reconcile category, financial-feature, Data safety, App
   Privacy, review-note, and release answers before any upload or submission.
   Prior `0.5.9` source CI, including iOS retirement queue/lease and
   stale-completion publication-authority coverage, completed in run
   `31433931682`; the current release commit requires fresh CI. End-to-end
   credentialed read-in-flight qualification,
   signed Android product qualification and the
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
  `31411048376`. The `0.5.9` HNWR read tranche passed exact full CI
  `31433931682`; its exact debug APK subsequently installed, cold-launched, and
  exposed the expected fail-closed native wallet UI on a Pixel 9. No wallet was
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
