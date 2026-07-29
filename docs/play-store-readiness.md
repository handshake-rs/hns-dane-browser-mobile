# Google Play Readiness Checklist

Last audited: 2026-07-29

This checklist maps HNS DANE Browser to current Google Play update requirements
and identifies the Play Console fields that must be reconciled outside the
repository. Google Play production completed `0.5.5` / code `46` from source
commit `d24f85158854abb8be4a7bb9e914aebe5e7e4679`. The public page does not
expose an authoritative `versionCode`, so the Android Publisher API and Play
Console remain the release-identity sources. Current source declares Android
`0.5.6` (`versionCode 47`) with shared Rust engine `0.5.6`; iOS remains
`0.5.5` / build `57` and is not part of this Android hotfix. Code `46`,
carrying the shared dual-root negative-evidence fix, passed the exact signed
APK/AAB release gates and completed production deployment through committed
Play edit `17438779769069438085`; the `generatedApks/46` readback returned HTTP
`200`. Code `47` fixes Android runtime opening under Rust 1.92 but has not yet
produced a claimed signed artifact or Play upload.
Earlier `0.5.4`, `0.5.1`, `0.5.0`, and `0.4.1` results remain dated historical
evidence.

## Current Repo Status

| Area | Status | Evidence / Action |
| --- | --- | --- |
| Target API level | Ready | `targetSdk = 37`, above the current Google Play requirement of Android 15 / API 35 for new apps and updates. |
| Android App Bundle | Code 47 release artifact pending; code 46 remains production | Current source declares package `com.denuoweb.hnsdane` code `47`, but no signed code `47` AAB or Play upload is claimed. Historical release evidence: code `46` completed production through committed edit `17438779769069438085`, with HTTP `200` from `generatedApks/46`; its verified AAB SHA-256 is `728d8892e180d954652668a4e53a7e2d6c7542e9d36330f4803cdecdb34598b0`. |
| Android runtime hotfix | Debug-device validation complete; remote/release gates pending | Rust 1.92's `std::fs::File::lock` target support omitted Android and returned `Unsupported` during fresh header-state initialization. Code `47` uses the already locked `libc 0.2.186` Android `flock` path while retaining the same lock semantics. Upstream added equivalent support for Rust 1.98 in `rust-lang/rust#157038`. A connected Pixel 9 opened fresh regtest storage at height `0` with no error, recovered preserved data to snapshot height `300000`, and reported manual **Run** as `syncing` with `error: null`. |
| 64-bit / 16 KiB native code | Code 47 signed gates pending; code 46 historical gates passed | Code `47` must pass the signed `arm64-v8a`/`x86_64`, 16 KiB, ELF hardening, Build ID, matching-symbol, stripping, path-sanitization, signature, R8, and APK ZIP-alignment gates. Historical code `46` passed them; its APK SHA-256 is `b36a4346ffcba14c081500ef3dc7c5012cabd30f42cdaa80a354eefb5da210ba` and its upload certificate SHA-256 is `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`. |
| Restricted permissions | Ready | Manifest does not request location, contacts, SMS, call logs, camera, microphone, all-files, package visibility, or account permissions. |
| Foreground service | Not used | Sync is owned by the application while at least one app screen is started and stops when the whole app backgrounds. The manifest declares no service and requests none of `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, or `FOREGROUND_SERVICE_DATA_SYNC`; mark foreground-service use as not applicable and remove stale `dataSync` drafts. |
| Privacy policy | Repository and hosted policy aligned | `https://denuoweb.com/work/hns-dane-browser/privacy` is the canonical URL and its hosted policy matches the repository disclosure for the independently opt-in P2P requester, user-configured recursive HNS DoH recovery, operator-visible qnames/qtypes/timing/source IP, blank/off defaults, validating ICANN bootstrap, permanent legacy-key tombstone, and continued prohibition on HNS WebPKI fallback. |
| Data safety form | Live reconciliation required | The current `No data collected / No data shared` posture is consistent with Google's open-web, on-device, and user-initiated-transfer exclusions. Confirm current WebView-provider Safe Browsing guidance before resubmission. |
| Ads declaration | Ready | Declare “No ads.” Donations do not unlock features. |
| Account deletion | Not applicable | The app does not create developer-operated accounts. |
| App category | Recommended: Tools or Communication | Avoid Finance classification; the app is not a wallet, exchange, lender, or financial service. |
| Target audience | Live reconciliation required | Use `18 and over` because the app is a general-purpose browser and is not child-directed; confirm the existing public listing already uses that answer. |
| Release track | Code 46 production; code 47 pending | The Android Publisher API committed code `46` edit `17438779769069438085` with production status `completed`; `generatedApks/46` returned HTTP `200`. Code `47` has not been uploaded or assigned to a track. This remains an update to a public listing, not a first closed-test launch. |
| CI regression | Required API 37 emulator configured; remote completion not yet claimed | The Android job now runs the fresh native-runtime instrumentation test on a Google APIs API 37 x86_64 emulator. The release checkpoint still requires a successful remote run; merely adding the required job is not completion evidence. |
| Store assets | Reconciliation required | Local icon, feature graphic, screenshots, and listing text exist in `dist/play-store/`, but they must be compared with the live listing. Recapture stale screenshots, including the diagnostic image showing an older version, against the code `47` release candidate before upload. |

## Release Signing

Google Play requires an upload-signed Android App Bundle. Do not commit keystores or passwords.

No signed code `47` APK or AAB is claimed at this checkpoint. The historical
code `46` APK and AAB were signed by upload certificate SHA-256
`D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`.
Their verified SHA-256 digests are:

- APK: `b36a4346ffcba14c081500ef3dc7c5012cabd30f42cdaa80a354eefb5da210ba`
- AAB: `728d8892e180d954652668a4e53a7e2d6c7542e9d36330f4803cdecdb34598b0`

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

The Play-linked service account uploaded code `46` for
`com.denuoweb.hnsdane`, assigned it to production with status `completed`, and
committed edit `17438779769069438085`. A post-commit
`generatedApks/46` request returned HTTP `200`, independently confirming that
Play recognizes the uploaded version code. Service-account JSON remains outside
the repository and is ignored by `.gitignore`. Code `47` has not yet been
uploaded, assigned to production, or committed through the API.

## Play Console Declarations

Use these values to reconcile the existing live production listing. Re-check
the current saved answers and Console UI labels before a future form or listing
resubmission because Google can rename fields without changing app behavior.

### Foreground Services

The deployed code `46` release and current code `47` source do not declare an
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
- App activity: browsing history, diagnostics, download records, settings, resolver cache, HNS sync/cache state, and cookies-adjacent WebView state are stored locally on device.
- Files/docs: user-initiated downloads are saved to public Downloads. Normal WebPKI downloads use Android DownloadManager; HNS downloads are fetched through the native gateway and saved through Android MediaStore.
- Device or other IDs: `No` unless a future SDK adds one. Current app code does not read advertising ID, IMEI, contacts, installed apps, or account identifiers.
- Encryption in transit: not applicable when the form correctly remains `No collected / No shared`. If WebView-provider guidance causes a data type to be declared, answer this question for the declared flow rather than for excluded open-web traffic.
- Data deletion: no developer-held data or app account exists to delete. Separately, users can clear cookies and WebView origin storage, history, download records, gateway diagnostics, resolver cache, or all local app data through Settings / Android system settings.

### Privacy Policy URL

Use an active, publicly accessible, non-PDF URL. Current hosted URL:

<https://denuoweb.com/work/hns-dane-browser/privacy>

The hosted route now matches the current repository policy, including the
relay requester, dual-root behavior, and configured-recursive recovery
disclosures. Keep the existing Play listing on this canonical route and keep
the live Data safety answers consistent with the policy and actual app behavior.

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

Google Play production now contains `0.5.5` / code `46` from exact source
commit `d24f85158854abb8be4a7bb9e914aebe5e7e4679`. The exact signed artifacts
passed the automated release gates before the production edit was committed.
Current source is the `0.5.6` / code `47` Android hotfix with shared Rust
`0.5.6`; iOS remains `0.5.5` / build `57`. Rust 1.92 returned
`ErrorKind::Unsupported` for standard-library Android file locking, so fresh
runtime creation failed before sync could report heights. The target-local
`libc::flock` shim restores the same advisory lock operations; upstream's
equivalent standard-library fix is in the Rust 1.98 release train.

Connected Pixel 9 debug validation has already proved fresh regtest open at
height `0` with `error: null`, preserved-data recovery to snapshot height
`300000`, and a manual **Run** result of `syncing` with `error: null`. Required
CI now contains the focused API 37 emulator regression. These checks do not
replace the remaining signed code `47` package gates, remote CI completion,
full exact-artifact device matrix, Play upload, or production assignment.

## Store Listing Draft

The repository listing copy used by the deployed code `46` update lives under
`dist/play-store/metadata/en-US/`. Compare it field-by-field with the public
listing before the code `47` update; the lock hotfix does not itself change the
declared product behavior.

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
> - Local controls for cookies, history, downloads, and resolver cache
>
> This app is for browsing and diagnostics. It is not a wallet, exchange, financial service, or investment product. Donations are optional and do not unlock features.

## Store Asset Checklist

- App icon: 512×512 PNG for Play Console: `dist/play-store/hns-dane-browser-play-icon-512.png`.
- Feature graphic: 1024×500 PNG24, no alpha: `dist/play-store/hns-dane-browser-feature-graphic-1024x500.png`.
- Phone screenshots: compare the local set with the live listing and recapture first-run sync, a successful HNS page, resolver trace, privacy/deletion controls, and diagnostics against the code `47` release candidate. The current diagnostics screenshot visibly reports an older app version and must not ship unchanged.
- Tablet screenshots: recommended if tablet distribution remains enabled.
- Privacy policy URL: the canonical route is selected and the hosted relay-aware policy is aligned; keep the existing Play listing on that route.
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
