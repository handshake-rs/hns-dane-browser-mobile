# Google Play Readiness Checklist

Last audited: 2026-08-14

Current Android candidate source is `0.5.10` (`versionCode 51`) and supports
Android 11 / API 30 or later. Google Play production remains on `0.5.6` / code
`47`; no code `51` AAB has been built, uploaded, or submitted.

This checklist maps Shakescape to current Google Play update requirements
and identifies the Play Console fields that must be reconciled outside the
repository. Google Play production contains Android `0.5.6` (`versionCode 47`)
with shared Rust engine `0.5.6`, shipped from source
`417af67efd68198de4871c0a339d1e456b60cb68`. iOS remains `0.5.5` / build `57`
and is not part of this Android hotfix. Code `47` fixes Android runtime opening
under Rust 1.92 and makes Proof Details follow Rust's retained HNS-versus-ICANN
decision rather than the namespace-agnostic native-gateway route. The Android
Publisher API committed production edit `07330408575596336357` with status
`completed`; `generatedApks/47` returned HTTP `200`. The public page does not
expose an authoritative `versionCode`, so the Android Publisher API and Play
Console remain the release-identity sources. Earlier release results are dated
historical evidence.

Candidate Android source includes a native-only wallet controller for
create/restore/open/status/unlock/lock and one non-value HNS account identity,
plus strict HNWR-v2 native UI for synchronized balance, distinct HNS payment and
name-transfer receive targets, transaction history, tracked names, and module
status. The exact historical HNWR-v1 shape remains separately decoded. It is not in code `47`
or the GitHub code `48` APK. The exact underlying lifecycle tranche passed
fresh-install Pixel 9 qualification plus Required CI run `31393998309` at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. Historical `0.5.8` source
`f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI run
`31402758394` and a fresh Pixel 9 install; documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; that remains historical code `49` evidence. Prior code `50`
source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including Android build/unit, API 37 native instrumentation,
Rust/supply-chain, and the complete Apple gate. Exact debug artifact
`9080493058` contains a 65,680,703-byte APK with SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`.
It is `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`, minimum API 30,
target API 37, with `arm64-v8a` and `x86_64`. It verifies with APK Signature
Scheme v2 under one default Android Debug RSA-2048 certificate (certificate
SHA-256 `b51ed3a12c762a69a4c3b31a30c77b5fccc9f0d50417f8a70911b7f60b135d8a`),
not the Play upload identity. The exact APK installed on a Pixel 9 (`tokay`),
Android 17 / API 37, after Android safely rejected an incompatible historical
code `49` debug signer and the authorized reinstall removed only the debug
package/data. Production remained installed and untouched; the on-device APK
digest matched. Cold launch succeeded and the fresh-install HNS wallet screen
showed no wallet/account, its create/restore controls, the fail-closed read rows
and sync action, and disabled value/marketplace copy. No wallet, secret,
account, credentialed sync, or value action ran. That is historical code `50`
installed-device evidence. Earlier HNWR-v2 code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
`31807520618`, including repository policy, Rust/supply-chain, Android
build/unit, API 37 native instrumentation, the complete Apple gate, and
Required CI; CodeQL runs `31807519998` and `31807520229` also passed. Those
results predate the current `2061a27` pin/import tranche. Exact current
application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the
complete manually dispatched CI matrix in run `31835813994`: repository policy,
Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
the complete Apple ABI/XCFramework/app/simulator gate, and aggregate Required
CI all succeeded. CodeQL runs `31833858421` and `31833858650` also passed.
Historical debug artifact `9222123624` has artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is not Play/store signed. Installed-device and signed-product
qualification remain open.
The current product installs no scoped loopback read credential or indexed
wallet backend, so every
read field remains fail-closed and unavailable. A pruned node with wallet
indexing and scoped authentication can serve retained/indexed current-wallet
evidence, but fresh restore additionally needs
archive-capable raw bytes or another durable wallet-relevant raw-tx source.
The trusted-native exact-text name-import control is implemented but remains
unavailable without the same backend and credential. Provider, send/value,
settlement, exchange, HNSA/HNSR, and marketplace controls remain gated off.
Reconcile category, financial-feature
declarations, Data safety, review notes, screenshots, and local deletion
behavior against the exact signed candidate before upload.

## Current Repo Status

| Area | Status | Evidence / Action |
| --- | --- | --- |
| Minimum API level | Current exact-source CI passed; artifact/signing/device gates open | `minSdk = 30`, with cargo-ndk platform 30. Exact current application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed Android build/unit and API 37 native-runtime instrumentation in complete manual CI `31835813994`. Historical artifact `9222123624` is bound to earlier source; extracted code `51` artifact inspection, signing, and installed-device qualification remain open. |
| Target API level | Ready | `targetSdk = 37`, above the current Google Play requirement of Android 15 / API 35 for new apps and updates. |
| Android App Bundle | Code 47 production complete | The signed 60,276,192-byte AAB has SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`. Edit `07330408575596336357` assigned code `47` to production with status `completed`, and `generatedApks/47` returned HTTP `200`. |
| Android runtime hotfix | Shipped and exact-artifact validated | Rust 1.92's `std::fs::File::lock` target support omitted Android and returned `Unsupported` during fresh header-state initialization. Code `47` uses the locked `libc 0.2.186` Android `flock` path with the same lock semantics; upstream added equivalent support for Rust 1.98 in `rust-lang/rust#157038`. The exact signed APK upgraded a Pixel 9 from code `46` with data preserved, cold-launched, and reached `up_to_date` at height `340348`, lag `0`, freshness `current`, and `error: null` after manual sync. |
| Native wallet candidate | Exact-name-import source CI passed; credentialed product gates pending | Source links wallet `0.1.0` commit `2061a27e0358c7f00fcc70497ef97f9b89d569da`, with `hns-rs 0.3.0` closure `88ed7c64db52a6fcfce4146a8fc17b1377dfcc8e`, through a non-exported native activity, create-only Android KeyStore-wrapped database key, strict HNWR-v2 UI, and a native-only exact-text HNWI-v1 name-import control. Exact current application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed complete manual CI `31835813994`; CodeQL runs `31833858421` and `31833858650` also passed. No wallet was created/restored and no credentialed operation ran. No scoped credential/indexed backend is provisioned, so reads and import are visibly unavailable; every provider/send/value/HNSA/HNSR/market path remains absent or gated. Play signing, installed-device qualification, fresh screenshots, and Console review/upload remain open. |
| Proof Details namespace | Fixed and release-device confirmed | Every canonical DNS host uses the native dual-root gateway, so that route cannot identify HNS versus ICANN. Before the fix, Pixel 9 API 37 instrumentation reproduced an HNS-selected trace being shown as DNSSEC with synthetic ICANN details, and paired instrumentation passed after the correction. HNS browsing and corrected proof presentation then passed manually with the exact signed release APK. |
| 64-bit / 16 KiB native code | Code 47 signed gates passed | The code `47` APK/AAB passed `arm64-v8a`/`x86_64`, 16 KiB, ELF hardening, Build ID, matching-symbol, stripping, path-sanitization, archive/APK signature, R8, and APK ZIP-alignment gates. The APK SHA-256 is `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`; the upload certificate SHA-256 is `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`. |
| Restricted permissions | Ready | Manifest does not request location, contacts, SMS, call logs, camera, microphone, all-files, package visibility, or account permissions. |
| Foreground service | Not used | Sync is owned by the application while at least one app screen is started and stops when the whole app backgrounds. The manifest declares no service and requests none of `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, or `FOREGROUND_SERVICE_DATA_SYNC`; mark foreground-service use as not applicable and remove stale `dataSync` drafts. |
| Privacy policy | Current read boundary deployed and read back | `https://denuoweb.com/work/hns-dane-browser/privacy` remains canonical. Version-neutral source `a5539cb063fb4b19fed4dff5400a3bc991acdc4f` covers relay/recovery, wallet lifecycle, and unavailable read rows; Firebase run `31485234945` and HTTP readback passed. Reconcile live Play answers before submission. |
| Data safety form | Live reconciliation required | The current `No data collected / No data shared` posture is consistent with Google's open-web, on-device, and user-initiated-transfer exclusions. Confirm current WebView-provider Safe Browsing guidance before resubmission. |
| Ads declaration | Ready | Declare “No ads.” Donations do not unlock features. |
| Account deletion | Not applicable | The app does not create developer-operated accounts. |
| App category | Candidate review required | Utilities/Tools may remain appropriate for the browser, but code `51` contains a limited local wallet controller and visible fail-closed read rows. Reconcile every current financial-feature/category declaration and describe the unprovisioned reads and unavailable value/provider/marketplace boundaries accurately. |
| Target audience | Live reconciliation required | Use `18 and over` because the app is a general-purpose browser and is not child-directed; confirm the existing public listing already uses that answer. |
| Release track | Code 47 production complete | The Android Publisher API committed edit `07330408575596336357` with production status `completed`; `generatedApks/47` returned HTTP `200`. This was an update to an existing public listing, not a first closed-test launch. |
| CI regression | Current exact-source CI and CodeQL passed | Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed run `31835813994`, including policy, Rust/supply-chain, Android build/unit, API 37 native instrumentation, complete Apple, and Required CI. CodeQL runs `31833858421` and `31833858650` also passed. Historical run `30484282637` remains code 47 Proof Details evidence only; signed-product, installed-device, screenshot, Console, and upload gates remain separate. |
| Store assets | Reconciliation required | Local icon, feature graphic, screenshots, and listing text exist in `store-assets/play-store/`, but they must be compared with the live listing. Recapture stale screenshots, including the diagnostic image showing an older version, before the next listing-asset update. |

## Release Signing

Google Play requires an upload-signed Android App Bundle. Do not commit keystores or passwords.

The code `47` APK and AAB were signed by upload certificate SHA-256
`D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`.
Their verified SHA-256 digests are:

- APK: 51,323,995 bytes;
  `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`
- AAB: 60,276,192 bytes;
  `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`

For a future release, set these environment variables before creating a Play
upload bundle:

```sh
export HNS_DANE_BROWSER_UPLOAD_STORE_FILE=/absolute/path/to/upload-keystore.jks
export HNS_DANE_BROWSER_UPLOAD_STORE_PASSWORD='...'
export HNS_DANE_BROWSER_UPLOAD_KEY_ALIAS='...'
export HNS_DANE_BROWSER_UPLOAD_KEY_PASSWORD='...'
export HNS_DANE_BROWSER_UPLOAD_CERTIFICATE_SHA256='AA:BB:...'
```

The certificate fingerprint is not secret. Obtain it from the upload keystore without putting the password on the command line:

```sh
keytool -list -v \
  -keystore "$HNS_DANE_BROWSER_UPLOAD_STORE_FILE" \
  -alias "$HNS_DANE_BROWSER_UPLOAD_KEY_ALIAS"
```

Copy the `SHA256` certificate fingerprint into `HNS_DANE_BROWSER_UPLOAD_CERTIFICATE_SHA256`; colon-separated or plain hexadecimal is accepted.

Then run:

```sh
"$HOME/APK_Workbench/scripts/dev/apkw-gradle.sh" \
  --project-dir "$HOME/path/to/handshake/Browser/android" \
  :app:verifyPlayReleaseBundle
```

`verifyPlayReleaseBundle` builds `android/app/build/outputs/bundle/release/app-release.aab`, first runs the unsigned structural gate, then reads every non-signature-metadata entry so Java cryptographically verifies its digest. It rejects an unexpected ABI/library inventory, non-16 KiB bundle or ELF alignment, malformed or weakly hardened ELF files, unstripped shipping libraries, missing/mismatched FULL debug symbols and Build IDs, local build paths, missing R8 mapping or notices, unsigned or mixed-signer content, and a signer that differs from the expected fingerprint. Regenerate third-party notices after version changes, rerun this gate, and copy the verified output to `dist/play-store/hns-dane-browser-v<release-version>-play-upload-signed.aab` before uploading.

## Google Play Developer API

The Play-linked service account uploaded code `47` for
`com.denuoweb.hnsdane`, assigned it to production with status `completed`, and
committed edit `07330408575596336357`. A post-commit `generatedApks/47` request
returned HTTP `200`, independently confirming that Play recognizes the uploaded
version code. Service-account JSON remains outside the repository and is ignored
by `.gitignore`.

For a future guarded upload, `scripts/play-upload-closed-testing.sh` reads the
single `versionCode` configured in `android/app/build.gradle.kts` before making
an API request. After Play receives the signed AAB inside an uncommitted edit,
the script validates the API-returned bundle `versionCode` and requires it to
equal that expected value before it constructs a track body, assigns a track,
or commits the edit. A missing, malformed, out-of-range, or mismatched value
stops the operation with no track assignment and no edit commit.
The commit uses `changesInReviewBehavior=ERROR_IF_IN_REVIEW`, so a separate
review already in flight makes the operation fail instead of cancelling that
review. Set `PLAY_UPDATE_LISTING=true` only when the reviewed en-US title,
short description, and full description in `store-assets/play-store/metadata/`
must be applied in the same edit. The script validates their Play field limits
before the bundle upload.

Use the configured code for an AAB built from the current release source:

```sh
PLAY_TRACK=alpha PLAY_RELEASE_STATUS=draft PLAY_UPDATE_LISTING=false \
  ./scripts/play-upload-closed-testing.sh /trusted/path/signed-release.aab
```

`PLAY_EXPECTED_VERSION_CODE` is an explicit expected-value override for an AAB
built from a separately reviewed source tree whose configuration is not the
current checkout. It must be a positive Play-compatible integer and does not
disable the comparison with Play's response:

```sh
PLAY_EXPECTED_VERSION_CODE=51 PLAY_TRACK=alpha PLAY_RELEASE_STATUS=draft \
  ./scripts/play-upload-closed-testing.sh /trusted/path/signed-release.aab
```

Before any credentialed run, exercise the static workflow assertions and
mocked Android Publisher request boundary locally; these tests perform no
network calls and make no store changes:

```sh
python3 -m unittest -v tests/test_release_safety.py
```

## Play Console Declarations

Use these values to reconcile the existing live production listing. Re-check
the current saved answers and Console UI labels before a future form or listing
resubmission because Google can rename fields without changing app behavior.

### Foreground Services

The deployed code `47` release does not declare an
Android service or request notification/foreground-service permissions. Header
sync starts when the first app activity starts, publishes progress in-process
across app screens, and stops when the last activity stops. In Play Console,
answer that the submitted build does not use foreground service types. A
foreground-service declaration, notification demo, or `dataSync` reviewer note
would describe a removed implementation and must not be submitted.

### Data Safety Draft

Use the Play Console definitions and answer conservatively. These are
repository draft answers, not proof that the existing live form is current;
compare every saved Console answer before a future form or listing
resubmission:

- Data collected: `No` under the current Play definitions. There are no developer-operated accounts, analytics/ads/crash-upload SDKs, or backend telemetry endpoints. Google explicitly excludes on-device-only processing and data from a WebView in which users navigate the open web.
- Data shared: `No` under the current Play definitions. Google explicitly excludes open-web WebView navigation and transfers based on a specific user-initiated action where sharing is reasonably expected. User-entered website/HNS navigation and its necessary resolution requests fit those exclusions; protocol-only background header sync does not transmit a listed user data type.
- Web browsing: do not declare URLs or browsing history solely because the browser contacts a user-selected site or sends the necessary HNS DNS query to a relay peer. Continue to disclose those network effects, including the relay peer's visibility into queried names/types and the client's network address, in the privacy policy even though Play excludes them from the Data safety form.
- Default start page: the app loads a bundled `appassets.androidplatform.net` asset with a restrictive Content Security Policy and no network resources; it does not contact a developer server. A remote homepage is loaded only after the user configures one.
- Safe Browsing: the installed Android WebView provider may check URLs through its Safe Browsing service. Confirm the provider's current Data safety guidance before submission. If it requires declaring a listed data type for this integration, update the form for that flow; do not imply that Denuo Web operates the service.
- App activity: code `51` stores browsing history, diagnostics, download records, settings, resolver cache, HNS sync/cache state, cookies-adjacent WebView state, and a private network-scoped wallet database plus device-bound wrapped database key locally on device. No wallet-read backend is provisioned and the native controller does not transmit wallet data. Reconcile the form against Google's current on-device-processing definitions rather than copying code `47` answers.
- Files/docs: user-initiated downloads are saved to public Downloads. Normal WebPKI downloads use Android DownloadManager; HNS downloads are fetched through the native gateway and saved through Android MediaStore.
- Device or other IDs: `No` unless a future SDK adds one. Current app code does not read advertising ID, IMEI, contacts, installed apps, or account identifiers.
- Encryption in transit: not applicable when the form correctly remains `No collected / No shared`. If WebView-provider guidance causes a data type to be declared, answer this question for the declared flow rather than for excluded open-web traffic.
- Data deletion: no developer-held data or app account exists to delete. Users can clear cookies and WebView origin storage, history, download records, gateway diagnostics, and resolver cache through app controls. The protected wallet screen can delete an unlocked confirmed wallet after two destructive confirmations showing the exact network/account and requiring `DELETE`; the device-bound key is deleted before encrypted database artifacts, and partial cleanup fails closed for retry. Android system settings can still clear all app data. Neither path deletes a recovery phrase or backup saved elsewhere, so users must record the one-time phrase before deletion.

### Privacy Policy URL

Use an active, publicly accessible, non-PDF URL. Current hosted URL:

<https://denuoweb.com/work/hns-dane-browser/privacy>

The repository policy adds the candidate's local wallet storage and recovery
lifecycle to the existing relay requester, dual-root, and configured-recursive
recovery disclosures. Version-neutral hosted source
`a5539cb063fb4b19fed4dff5400a3bc991acdc4f` was deployed in Firebase run
`31485234945` and read back at this route with the HNWR boundary present. Play
Console form/category readback remains open.

### Content Rating

Use a conservative general-purpose browser posture:

- App type/category in Play: retain or reconcile as `Tools` for the production update.
- Questionnaire category: choose the closest non-game utility/browser category offered by Play Console.
- Target audience and content: not designed for children; use `18 and over` and reconcile this with the saved live answer.
- User-generated content: the app does not host UGC or operate a social feed, but it can browse arbitrary third-party web content. Answer any unrestricted web access question as `Yes`.
- Violence, sexual content, gambling, controlled substances, hate, financial trading, medical, government, and news: `No` for app-provided content/features.
- Ads: `No ads`.
- In-app purchases: `No`; donations are external/optional and do not unlock features.

### Production Deployment and Remaining Device Qualification

Code `51` is the release candidate. Historical code `49` wallet
lifecycle source passed the focused exact-artifact Android exercise, Required CI
`31402758394`, and full docs-parent CI `31411048376`. Code `50` adds the strict
HNWR read projection but no credential/backend provision. Its pre-ECH exact
source passed full CI `31433931682`; its exact debug artifact installed, cold-launched,
and exposed the expected fail-closed wallet UI on a Pixel 9. No wallet was
created/restored and no credentialed read or value action ran. Signed AAB
verification, current screenshots, live Console reconciliation, and intentional
upload remain open. No
credentialed Play operation has been performed for code `51`.

Google Play production contains the `0.5.6` / code `47` Android hotfix with
shared Rust `0.5.6`, built from exact shipping source
`417af67efd68198de4871c0a339d1e456b60cb68`; iOS remains `0.5.5` / build
`57`. The signed APK/AAB passed the automated release gates before production
edit `07330408575596336357` was committed with status `completed`, and
`generatedApks/47` returned HTTP `200`. Rust 1.92 returned
`ErrorKind::Unsupported` for standard-library Android file locking, so fresh
runtime creation failed before sync could report heights. The target-local
`libc::flock` shim restores the same advisory lock operations; upstream's
equivalent standard-library fix is in the Rust 1.98 release train. Proof
Details also now uses the retained dual-root decision rather than mistaking the
native gateway used by every DNS host for an ICANN selection.

On a Pixel 9 running Android 17 / API 37, the exact signed 51,323,995-byte APK
upgraded code `46` to code `47` with data preserved, cold-launched, reached
`up_to_date` at height `340348` with lag `0`, freshness `current`, and
`error: null`, and passed manual sync plus HNS browsing and proof behavior. The
pre-fix Proof Details test had reproduced DNSSEC/synthetic ICANN details for an
HNS selection; paired HNS and ICANN instrumentation passes after the correction.
Required CI run `30484282637` passed these API 37 emulator regressions
on workflow-only descendant `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`,
which does not replace the exact shipping-source provenance. These checks do
not complete the broader
lifecycle, Service Worker, download, WebSocket, requester/recovery, and
cross-origin physical-device matrix.

## Store Listing Draft

The repository listing under `store-assets/play-store/metadata/en-US/` now
describes the code `50` native wallet and fail-closed read boundary. Compare it
field-by-field with the public listing and do not submit the older “not a
wallet” sentence.

Short description, 80 characters max:

> Browse HNS with local proofs, DNSSEC, DANE, and an optional P2P relay.

Full description draft:

> Shakescape is a Handshake-first browser with local HNS proofs, authoritative DNS, an optional requester-only HNS P2P DNS relay, optional user-configured recursive HNS DoH recovery, proof-anchored authoritative DoH, and DNSSEC/DANE diagnostics. It syncs Handshake headers, verifies HNS proofs, resolves delegated names, and keeps HNS HTTPS strict to DNSSEC/DANE.
>
> Features:
> - HNS-aware omnibar for names such as `example/` and `name.tld/`
> - Local Handshake proof verification and resolver cache
> - DNSSEC and TLSA/DANE diagnostics for HTTPS HNS sites
> - Optional P2P DNS relay requester, enabled only with explicit user consent, with all answers validated locally
> - Optional manual relay peers accepted only as verified IP-literal endpoints
> - Optional recursive HNS DoH recovery, blank by default and eligible only after direct, owner-published, and independently enabled P2P paths encounter interception or transport failure
> - Configured-recursive answers still require local proof, DNSSEC, TLSA, and DANE validation; no HNS WebPKI fallback
> - Ordinary ICANN browsing continues through bounded ICANN DoH and WebPKI
> - Resolver trace, HNS proof viewer, and TLSA inspector
> - Native create, restore, open, unlock, and lock controls for one device-local HNS account identity
> - Fail-closed read-only wallet rows for balance, distinct payment and name-transfer receive targets, history, tracked names, and module status; this build does not provision the companion backend needed to populate them
> - Local controls for cookies, history, downloads, and resolver cache
>
> The native wallet screen manages one local HNS account identity and includes synchronized read-only fields plus exact-text name import, but this build installs no scoped credential or indexed backend, so those controls remain unavailable. Sending/value movement, website-provider access, HNSA/HNSR, settlement, exchange features, and P2P marketplaces are unavailable. Donations are optional and do not unlock features.

## Store Asset Checklist

- App icon: 512×512 PNG for Play Console: `store-assets/play-store/hns-dane-browser-play-icon-512.png`.
- Feature graphic: 1024×500 PNG24, no alpha: `store-assets/play-store/hns-dane-browser-feature-graphic-1024x500.png`.
- Phone screenshots: recapture first-run sync, a successful HNS page, resolver trace, privacy/deletion controls, diagnostics, and the non-secret wallet control/read-unavailable boundary against exact code `51`. Never show a recovery phrase or account identifier. The current screenshots predate this candidate and must be replaced.
- Tablet screenshots: recommended if tablet distribution remains enabled.
- Privacy policy URL: keep the existing Play listing on the selected canonical
  route. Version-neutral read-boundary source `a5539cb` was deployed in run
  `31485234945` and read back successfully; confirm the Console URL before
  submission.
- Content rating questionnaire: reconcile the saved live answers as a general-purpose browser that is not child-directed.

## References

- Target API level: <https://support.google.com/googleplay/android-developer/answer/11926878>
- 64-bit native code: <https://developer.android.com/google/play/requirements/64-bit>
- 16 KiB page-size support: <https://developer.android.com/guide/practices/page-sizes>
- Native debug symbols: <https://developer.android.com/build/include-native-symbols>
- Data safety form: <https://support.google.com/googleplay/android-developer/answer/10787469>
- User data and privacy policy: <https://support.google.com/googleplay/android-developer/answer/10144311>
- Closed testing for new personal accounts: <https://support.google.com/googleplay/android-developer/answer/14151465>
- Store listing preview assets: <https://support.google.com/googleplay/android-developer/answer/9866151>
