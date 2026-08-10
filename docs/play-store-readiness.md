# Google Play Readiness Checklist

Last audited: 2026-08-10

Current Android candidate source is `0.5.8` (`versionCode 49`) and supports
Android 11 / API 30 or later. Google Play production remains on `0.5.6` / code
`47`; no code `49` AAB has been built, uploaded, or submitted.

This checklist maps HNS DANE Browser to current Google Play update requirements
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
create/restore/open/status/unlock/lock and one non-value HNS account identity.
It is not in code `47` or the GitHub code `48` APK. The exact underlying tranche
passed fresh-install Pixel 9 create/confirm/unlock/lock/process-reopen and
network-isolation qualification plus Required CI run `31393998309` at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The final version/repin/metadata
commit still requires exact-head CI. It exposes no provider, balances,
transfers, names, sending, settlement, exchange, HNSA/HNSR, or marketplace
controls. Reconcile category, financial-feature declarations, privacy policy,
Data safety, review notes, screenshots, and local deletion behavior against the
exact signed candidate before upload.

## Current Repo Status

| Area | Status | Evidence / Action |
| --- | --- | --- |
| Minimum API level | Configured in code 49 | `minSdk = 30`, with cargo-ndk platform 30. The prior compatibility source passed full debug lint after retaining explicit UTF-8 search encoding through the compatible `URLEncoder` overload; rerun exact-candidate CI. |
| Target API level | Ready | `targetSdk = 37`, above the current Google Play requirement of Android 15 / API 35 for new apps and updates. |
| Android App Bundle | Code 47 production complete | The signed 60,276,192-byte AAB has SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`. Edit `07330408575596336357` assigned code `47` to production with status `completed`, and `generatedApks/47` returned HTTP `200`. |
| Android runtime hotfix | Shipped and exact-artifact validated | Rust 1.92's `std::fs::File::lock` target support omitted Android and returned `Unsupported` during fresh header-state initialization. Code `47` uses the locked `libc 0.2.186` Android `flock` path with the same lock semantics; upstream added equivalent support for Rust 1.98 in `rust-lang/rust#157038`. The exact signed APK upgraded a Pixel 9 from code `46` with data preserved, cold-launched, and reached `up_to_date` at height `340348`, lag `0`, freshness `current`, and `error: null` after manual sync. |
| Native wallet candidate | Feature source device-qualified; release gates pending | Source currently links intermediate wallet release-preparation commit `f83d42363305de04bfa955f864cb1e9136c4d648` through a non-exported native activity and create-only Android KeyStore-wrapped database key. Fresh install, creation/confirmation, unlock/lock, process reopen, owner-only storage, and mainnet/testnet isolation passed on the exact CI artifact. After the dated `hns-rs` release lands, mobile must consume the resulting final wallet commit; that exact code `49` source then needs CI, signing, screenshots, and Console review. |
| Proof Details namespace | Fixed and release-device confirmed | Every canonical DNS host uses the native dual-root gateway, so that route cannot identify HNS versus ICANN. Before the fix, Pixel 9 API 37 instrumentation reproduced an HNS-selected trace being shown as DNSSEC with synthetic ICANN details, and paired instrumentation passed after the correction. HNS browsing and corrected proof presentation then passed manually with the exact signed release APK. |
| 64-bit / 16 KiB native code | Code 47 signed gates passed | The code `47` APK/AAB passed `arm64-v8a`/`x86_64`, 16 KiB, ELF hardening, Build ID, matching-symbol, stripping, path-sanitization, archive/APK signature, R8, and APK ZIP-alignment gates. The APK SHA-256 is `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`; the upload certificate SHA-256 is `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`. |
| Restricted permissions | Ready | Manifest does not request location, contacts, SMS, call logs, camera, microphone, all-files, package visibility, or account permissions. |
| Foreground service | Not used | Sync is owned by the application while at least one app screen is started and stops when the whole app backgrounds. The manifest declares no service and requests none of `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, or `FOREGROUND_SERVICE_DATA_SYNC`; mark foreground-service use as not applicable and remove stale `dataSync` drafts. |
| Privacy policy | Repository updated; hosted publication pending | `https://denuoweb.com/work/hns-dane-browser/privacy` remains the canonical URL. The repository disclosure now covers the independently opt-in P2P requester, user-configured recursive HNS DoH recovery, operator-visible qnames/qtypes/timing/source IP, the local wallet database and device-bound key, recovery lifecycle, blank/off defaults, validating ICANN bootstrap, permanent legacy-key tombstone, and continued prohibition on HNS WebPKI fallback. Publish this candidate text and read the hosted page back before submission. |
| Data safety form | Live reconciliation required | The current `No data collected / No data shared` posture is consistent with Google's open-web, on-device, and user-initiated-transfer exclusions. Confirm current WebView-provider Safe Browsing guidance before resubmission. |
| Ads declaration | Ready | Declare “No ads.” Donations do not unlock features. |
| Account deletion | Not applicable | The app does not create developer-operated accounts. |
| App category | Candidate review required | Utilities/Tools may remain appropriate for the browser, but code `49` contains a limited local wallet controller. Reconcile every current financial-feature/category declaration and describe the unavailable value/provider/marketplace boundaries accurately. |
| Target audience | Live reconciliation required | Use `18 and over` because the app is a general-purpose browser and is not child-directed; confirm the existing public listing already uses that answer. |
| Release track | Code 47 production complete | The Android Publisher API committed edit `07330408575596336357` with production status `completed`; `generatedApks/47` returned HTTP `200`. This was an update to an existing public listing, not a first closed-test launch. |
| CI regression | Required CI passed | Run `30484282637` passed the fresh native-runtime and paired Proof Details namespace tests on a Google APIs API 37 x86_64 emulator. It ran on workflow-only descendant `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`; the exact tag and shipping artifacts remain sourced from `417af67efd68198de4871c0a339d1e456b60cb68`. |
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

Use the configured code for an AAB built from the current release source:

```sh
PLAY_TRACK=alpha PLAY_RELEASE_STATUS=draft \
  ./scripts/play-upload-closed-testing.sh /trusted/path/signed-release.aab
```

`PLAY_EXPECTED_VERSION_CODE` is an explicit expected-value override for an AAB
built from a separately reviewed source tree whose configuration is not the
current checkout. It must be a positive Play-compatible integer and does not
disable the comparison with Play's response:

```sh
PLAY_EXPECTED_VERSION_CODE=49 PLAY_TRACK=alpha PLAY_RELEASE_STATUS=draft \
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
- App activity: code `49` stores browsing history, diagnostics, download records, settings, resolver cache, HNS sync/cache state, cookies-adjacent WebView state, and a private network-scoped wallet database plus device-bound wrapped database key locally on device. The native controller does not transmit wallet data. Reconcile the form against Google's current on-device-processing definitions rather than copying code `47` answers.
- Files/docs: user-initiated downloads are saved to public Downloads. Normal WebPKI downloads use Android DownloadManager; HNS downloads are fetched through the native gateway and saved through Android MediaStore.
- Device or other IDs: `No` unless a future SDK adds one. Current app code does not read advertising ID, IMEI, contacts, installed apps, or account identifiers.
- Encryption in transit: not applicable when the form correctly remains `No collected / No shared`. If WebView-provider guidance causes a data type to be declared, answer this question for the declared flow rather than for excluded open-web traffic.
- Data deletion: no developer-held data or app account exists to delete. Users can clear cookies and WebView origin storage, history, download records, gateway diagnostics, and resolver cache through app controls. This version has no in-app delete action for a confirmed wallet; Android system settings can clear all app data, including the wallet, after the user has safely recorded the one-time recovery phrase.

### Privacy Policy URL

Use an active, publicly accessible, non-PDF URL. Current hosted URL:

<https://denuoweb.com/work/hns-dane-browser/privacy>

The repository policy adds the candidate's local wallet storage and recovery
lifecycle to the existing relay requester, dual-root, and configured-recursive
recovery disclosures. Publish that updated text to the hosted route and read it
back before submission; the current live page is not assumed to include this
unpublished change.

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

Code `49` is a release-preparation candidate only. Its underlying wallet source
passed the focused exact-artifact Android exercise, but the dated final
`hns-rs` → wallet → mobile dependency repin and metadata commit need exact-head
CI, signed AAB verification, current
screenshots, hosted-policy readback, and live Console reconciliation. No
credentialed Play operation has been performed for code `49`.

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
describes the code `49` native non-value wallet boundary. Compare it
field-by-field with the public listing and do not submit the older “not a
wallet” sentence.

Short description, 80 characters max:

> Browse HNS with local proofs, DNSSEC, DANE, and an optional P2P relay.

Full description draft:

> HNS DANE Browser is a Handshake-first browser with local HNS proofs, authoritative DNS, an optional requester-only HNS P2P DNS relay, optional user-configured recursive HNS DoH recovery, proof-anchored authoritative DoH, and DNSSEC/DANE diagnostics. It syncs Handshake headers, verifies HNS proofs, resolves delegated names, and keeps HNS HTTPS strict to DNSSEC/DANE.
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
> - Native create, restore, open, unlock, and lock controls for one device-local non-value HNS account identity
> - Local controls for cookies, history, downloads, and resolver cache
>
> The native wallet screen manages only one local non-value HNS account identity. It does not show balances, receive or send funds, manage names, connect a wallet to websites, settle trades, provide exchange features, or expose P2P marketplaces. Donations are optional and do not unlock features.

## Store Asset Checklist

- App icon: 512×512 PNG for Play Console: `store-assets/play-store/hns-dane-browser-play-icon-512.png`.
- Feature graphic: 1024×500 PNG24, no alpha: `store-assets/play-store/hns-dane-browser-feature-graphic-1024x500.png`.
- Phone screenshots: recapture first-run sync, a successful HNS page, resolver trace, privacy/deletion controls, diagnostics, and the non-secret wallet control boundary against exact code `49`. Never show a recovery phrase or account identifier. The current screenshots predate this candidate and must be replaced.
- Tablet screenshots: recommended if tablet distribution remains enabled.
- Privacy policy URL: keep the existing Play listing on the selected canonical
  route, then publish and read back the candidate relay- and wallet-aware text
  before submission.
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
