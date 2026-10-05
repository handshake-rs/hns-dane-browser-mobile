# Google Play release readiness

The Android candidate is `1.0.16`, version code `68`. Read its configuration
from `android/app/build.gradle.kts` before building or uploading. Validate the
exact candidate using [release readiness](release-readiness.md), the platform
unit and instrumentation suites, and signed-device lifecycle tests.

## Release Signing

Google Play requires an upload-signed Android App Bundle. Do not commit keystores or passwords.

The upload certificate and Play app-signing certificate are deliberately
different identities:

- upload certificate SHA-256:
  `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`;
- Play app-signing certificate SHA-256:
  `CA:0F:1C:B4:27:2E:DB:53:3E:2F:FE:AE:2B:83:E5:9F:D3:CF:CD:BD:8D:AA:8F:C6:4E:8D:B0:BD:1D:C1:9F:98`.

A locally built APK uses the upload certificate and therefore cannot update an
installation delivered by Google Play. Any APK distributed through GitHub as
a Play-compatible update must be the universal APK returned by the Google Play
Generated APKs API after the upload-signed AAB is committed to a non-production
draft or release track. Verify that downloaded APK's package, version code,
cryptographic signature, and exact Play app-signing certificate before
publication. Never relabel the local upload-signed APK as Play-signed.

For the selected release, set these environment variables before creating a Play
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
./android/gradlew -p android :app:verifyPlayReleaseBundle
```

`verifyPlayReleaseBundle` builds `android/app/build/outputs/bundle/release/app-release.aab`, first runs the unsigned structural gate, then reads every non-signature-metadata entry so Java cryptographically verifies its digest. It rejects an unexpected ABI/library inventory, non-16 KiB bundle or ELF alignment, malformed or weakly hardened ELF files, unstripped shipping libraries, missing/mismatched FULL debug symbols and Build IDs, local build paths, missing R8 mapping or notices, unsigned or mixed-signer content, and a signer that differs from the expected fingerprint. Regenerate third-party notices after version changes, rerun this gate, and copy the verified output to `dist/play-store/hns-dane-browser-v<release-version>-play-upload-signed.aab` before uploading.

## Google Play Developer API

For a guarded upload, `scripts/play-upload-closed-testing.sh` reads the
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
PLAY_EXPECTED_VERSION_CODE=65 PLAY_TRACK=alpha PLAY_RELEASE_STATUS=draft \
  ./scripts/play-upload-closed-testing.sh /trusted/path/signed-release.aab
```

Before any credentialed run, exercise the static workflow assertions and
mocked Android Publisher request boundary locally; these tests perform no
network calls and make no store changes:

```sh
python3 -m unittest -v tests/test_release_safety.py
```

## Store declarations and assets

The owner confirmed on 2026-10-03 that the saved Play Console declarations are
accurate for the current browser, native HNS/BTC wallet, name actions, and swaps.
Use the [standing release confirmation](release-readiness.md#standing-release-instructions)
for Data safety, financial features, content rating, target audience, and
trader/account details. Do not repeat an accuracy audit or request another
attestation before submission. Preserve these answers unless the owner requests
an update.

The non-exported `WalletSyncForegroundService` uses the `dataSync` type for a
user-started bounded synchronization with a visible notification. Verify its
foreground/background behavior on the signed candidate.

The app has no developer-operated account, analytics, advertising, or crash-upload
service. Browser navigation and peer traffic expose the necessary network
requests to their recipients. These behaviors are covered by the confirmed
store declarations.

Use the metadata in `store-assets/play-store/metadata/` and screenshots from the
exact shipping candidate. Review the native wallet onboarding and browser
controls and privacy policy at <https://shakescape.com/privacy/> before applying
metadata. The confirmed account declarations are already settled. An authorized
store delivery requires a verified signed bundle.

After an authorized upload, read back the returned version code, track, release
status, and listing. A successful local build does not establish store state.

The manual `android-store-screenshots.yml` workflow captures the shipping
Release interface on Pixel 7, Nexus 7 (2013), and Nexus 10 emulator profiles.
It builds one x86_64 APK for these captures, uses a disposable installation
certificate, and retains nine original PNGs, their source/version/checksum
manifest, interface trees, and timestamped device logs. The upload AAB keeps
its separately verified Play upload signature. Existing device logs are never
cleared. Screens show browser controls, settings, and Handshake settings. The
workflow separately verifies native wallet onboarding without creating a wallet
or approving value actions. Release wallet windows use `FLAG_SECURE`, so they
are excluded from marketing captures while the shipping protection remains
enabled. Every captured destination must expose its expected native controls
before the frame is taken; still review the actual pixels before uploading.

```sh
gh workflow run android-store-screenshots.yml --ref main \
  -f expected_commit="$(git rev-parse HEAD)"
```

Review the actual captured pixels and verify the workflow/source manifest
before replacing the live store assets. A saved draft preserves an uploaded
bundle without submitting the candidate for review; the final production
commit and its fresh readback are separate steps.
