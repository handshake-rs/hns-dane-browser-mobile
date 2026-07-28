# Google Play Metadata Package

This directory contains the source text and field checklist for the Google
Play Console update to package `com.denuoweb.hnsdane`. The repository update
candidate is `0.5.3` / code `43`. The public listing reported the July 16,
2026 release when checked on 2026-07-28. Google Play's public page does not
expose an authoritative `versionCode`; confirm the live release identity in
Play Console.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.3 release notes: `en-US/release-notes.txt`

## Store Assets

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`

## Console Fields

- Package name: `com.denuoweb.hnsdane`
- App category: Tools
- Ads declaration: No ads
- Privacy policy URL: `https://denuoweb.com/work/hns-dane-browser/privacy`
- Expected closed-testing upload artifact:
  `../hns-dane-browser-v0.5.3-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration because sync is application-foreground scoped and the manifest declares no service.

CI run
[30323566765](https://github.com/handshake-rs/hns-dane-browser-mobile/actions/runs/30323566765)
passed code 43 Android build/tests/lint and the unsigned release-bundle
structure gate. The upload-signed AAB is generated and signature-verified only
in the credentialed release process and is intentionally not committed. Do
not reuse the dated code-40 AAB as the v0.5.3 candidate.
