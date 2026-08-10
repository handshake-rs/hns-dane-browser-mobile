# Google Play Metadata Package

This directory contains the reviewed source text and field guidance for the
Android `0.5.8` / code `49` release-preparation candidate for package
`com.denuoweb.hnsdane`. It coordinates Android and the embedded Rust workspace
at `0.5.8` and adds native-only controls for one device-local non-value HNS
account identity.

The underlying wallet tranche passed exact-source Android CI and installed
Pixel 9 qualification at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
The checked-in wallet pin is an intermediate release-preparation checkpoint.
After the dated `hns-rs` release commit, wallet and mobile must repin in
sequence and regenerate the lockfile/notices. That resulting candidate still
requires exact-head CI, a signed and verified AAB, refreshed screenshots, live
Play Console declaration reconciliation, and an intentional upload. No code
`49` AAB has been built, uploaded, or submitted.

The listing deliberately describes the limited native controller. It does not
claim balances, transfers, names, website-provider access, settlement,
exchange features, or P2P marketplaces; all remain unavailable.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.8 release notes: `en-US/release-notes.txt`

## Store Assets

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`

## Console Fields

- Package name: `com.denuoweb.hnsdane`
- App category: Tools
- Ads declaration: No ads
- Privacy policy URL: `https://denuoweb.com/work/hns-dane-browser/privacy`
- Candidate upload artifact (signed, verified, and intentionally untracked):
  `dist/play-store/hns-dane-browser-v0.5.8-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration
  because sync is application-foreground scoped and the manifest declares no
  service.

The credentialed upload AAB is intentionally not committed. Exact-candidate
CI, signed-artifact verification, screenshot refresh, and Google Play
submission remain explicit release gates.
