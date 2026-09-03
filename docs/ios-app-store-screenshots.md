# Live iOS App Store screenshots

The `Live iOS App Store Screenshots` workflow produces four truthful iPhone
screenshots without a physical iPhone. It runs only when manually dispatched
because it performs real network navigation and is intended to create a
reviewed submission artifact, not a required pull-request check. The protected
App Store upload workflow runs the same capture and full verification as a
mandatory pre-credential gate; capture or verification failure blocks signing
and upload.

The checked-in images and manifest predate the current candidate and are not
submission-ready for configured `1.0.4` / build `65`. Prior
code-bearing
source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including the complete Apple app/simulator gate. No fresh
candidate screenshot artifact has been captured or staged; dispatch this
workflow only for the exact release checkout selected for signing. The
resulting wallet image must visibly include the
native Handshake wallet entry and fail-closed unavailable read rows without
displaying a recovery phrase, account identifier, or other secret.

```sh
expected_commit="$(git rev-parse HEAD)"
printf '%s\n' "$expected_commit" | grep -Eq '^[0-9a-f]{40}$'
gh workflow run ios-screenshots.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f reason='Qualified App Store candidate refresh'
```

The workflow checks out and reads back that exact commit, keys concurrency and
artifact names to it, and refuses an uppercase, abbreviated, or otherwise
malformed revision. Download the artifact named
`ios-app-store-live-screenshots-COMMIT_SHA`. Before upload, the workflow also
requires `manifest.json` to name the same exact commit. The artifact contains:

- `01-hns-page.jpg`, captured after the shipping runtime loads
  `https://shakescape/`
- `02-settings.jpg`, showing the corrected shipping Settings screen with the
  native Handshake wallet row fully visible during that live HNS session
- `03-proof-details.jpg`, showing the actual proof returned for that same HNS
  navigation
- `04-webpki.jpg`, captured after the shipping runtime loads
  `https://shakescape.com/`
- `manifest.json`, containing the commit, Release configuration,
  Xcode/SDK/device provenance, the security labels actually shown by the app,
  dimensions, and SHA-256 digest for every image

Each JPEG is exactly `1284 x 2778`, has no alpha channel, and fits App Store
Connect's 6.5-inch iPhone screenshot slot. The workflow creates a fresh iPhone
14 Plus simulator, with 13 Pro Max and 12 Pro Max as equivalent fallbacks.

## Truthfulness guarantees

The submission capture runs the normal app and Rust runtime in the Release
simulator configuration. It never sets `HNS_APP_STORE_SCREENSHOT_SCENE`, never
injects page HTML, and never forces a security result. The submission workflow
accepts the intended HNS screenshot only when the live response is DANE
verified, and accepts the public product page only when the app reports either
an ICANN DANE result or the narrowly gated validating-DoH/WebPKI result.
`manifest.json` records the exact visible labels.

The public product page is also the default homepage. If its later same-process
navigation is served entirely from WebKit cache, no new Rust main-frame status
exists to bind to the toolbar. The capture may therefore retry once only for
that exact missing-status result or the exact dual-root indeterminate result.
Reload forces end-to-end origin revalidation through the active proxy and must
finish with an accepted live ICANN DANE or validating-DoH/WebPKI label. The
manifest records the attempt count and exact retry reason.

The capture fails instead of producing an artifact when:

- the first launch does not report `Handshake headers current` within 20
  minutes (the HNS page is never captured against merely prepared or stale
  headers);
- the HNS page does not finish within 180 seconds;
- either final address differs from its exact requested submission URL;
- the HNS page is not DANE verified or the public page reports neither ICANN
  DANE nor validated WebPKI;
- one origin reload does not recover either an exact cache-without-status result
  or the exact dual-root indeterminate result into ICANN DANE or validated
  WebPKI;
- Proof Details does not open within 60 seconds;
- Proof Details does not identify the same `denuoweb` HNS navigation;
- the native `settings.destination.wallet` row is not directly visible with
  its shipping `Wallet` label when the Settings image is captured;
- the public WebPKI page does not finish within 90 seconds;
- the app presents a navigation or runtime alert;
- the Release app binary contains the Debug fixture environment key; or
- an attachment, image dimension, digest, or provenance field is missing.

`NonSubmissionFixtureScreenshotRegressionTests` remains available for offline
Debug UI regression work. Its attachments are named `UI_REGRESSION_FIXTURE_*`;
the collector and staging verifier reject them as App Store assets.

This is live simulator evidence, not the separate physical-device
qualification matrix in `docs/ios-device-validation.md`.

## Review and stage the images

1. Inspect all four images at full size. Confirm that the HNS page and public
   product page rendered normally, Settings matches the shipping Android-aligned
   structure with the native Handshake wallet entry visible, Proof Details
   refers to `denuoweb`, critical app and security text is not clipped, and no
   keyboard, test overlay, or alert is visible. The runtime provenance
   separately proves the semantic Stateless DANE row and switch were present.
2. Inspect `manifest.json`. Confirm `capture.mode` is
   `live-production-runtime`, `capture.configuration` is `Release`,
   `capture.fixtureEnvironmentInjected` is `false`, and the commit is the
   intended release commit. Confirm the recorded HNS label starts with
   `DANE verified` and the public-page label reports either `DANE verified` or
   `WebPKI verified · no secure TLSA`, matching what is visibly shown. Confirm
   `runtimeEvidence.settings.nativeWalletRowIdentifier` is exactly
   `settings.destination.wallet`.
3. Put the downloaded artifact contents below
   `build/app-store-live-screenshots/`, then run:

   ```sh
   expected_commit="$(git rev-parse HEAD)"
   ./scripts/stage-ios-app-store-screenshots.sh \
     build/app-store-live-screenshots "$expected_commit"
   python3 store-assets/app-store/validate.py \
     --expected-commit "$expected_commit"
   ```

   The staging script verifies every digest, replaces
   `store-assets/app-store/screenshots/en-US/` with only the four live JPEGs,
   and writes the adjacent `store-assets/app-store/screenshots/manifest.json`
   provenance gate. Do
   not copy or rename fixture images into the upload folder.
4. Upload the four approved JPEGs to App Store Connect's 6.5-inch iPhone slot
   in numerical order, either through the guarded release client or directly
   in App Store Connect.

The committed `0.5.5` set was captured from exact source
`d926561091634cd69fc9b7e79a4b76003fa4ee47` in successful workflow run
`30454926117`. Its Release/runtime provenance, four 1284 × 2778 images, and
digests remain published historical evidence. They passed the submission gate
for that release, but intentionally fail the current candidate validator
because they predate provenance schema 3, the native wallet/read-row evidence,
and the exact `1.0.0` candidate commit.

That successful historical validation does not satisfy the `1.0.0` manifest
commit gate. Replace the set only with an artifact captured from the exact
final candidate.

The standalone screenshot workflow never contacts App Store Connect and does
not use signing or App Store credentials. In the protected upload workflow,
the mandatory capture and verification steps also run before any Apple secret
is read or any IPA is uploaded. After local visual review, the guarded release
client uploaded the historical `0.5.5` set and verified its order, checksums,
and dimensions in App Store Connect.

Protected upload run `30456522039` also completed a repeat live capture and
retained artifact `8727084963`. That distinct set is corroborating evidence
only; its WebPKI page used the permitted bounded retry, so it did not replace
the cleaner single-attempt set from run `30454926117` in source or App Store
Connect.

On a compatible Mac, run the same live capture after the unsigned iOS gate:

```sh
./scripts/run-ios-gate.sh
./scripts/generate-ios-app-store-screenshots.sh
```

Local output is written to `build/app-store-live-screenshots/`.
