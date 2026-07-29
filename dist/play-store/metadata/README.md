# Google Play Metadata Package

This directory contains the source text and field checklist for the Google
Play Console update to package `com.denuoweb.hnsdane`. The repository update
candidate is `0.5.5` / code `46`. Google Play production completed the earlier
`0.5.5` / code `45` release; code `46` is the pending replacement carrying the
shared dual-root negative-evidence fix. Google Play's public page does not
expose an authoritative `versionCode`; use Play Console as the release-identity
source.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.5 release notes: `en-US/release-notes.txt`

## Store Assets

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`

## Console Fields

- Package name: `com.denuoweb.hnsdane`
- App category: Tools
- Ads declaration: No ads
- Privacy policy URL: `https://denuoweb.com/work/hns-dane-browser/privacy`
- Expected production-replacement upload artifact:
  `../hns-dane-browser-v0.5.5-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration because sync is application-foreground scoped and the manifest declares no service.

Code `45` passed the signed release gates and completed production deployment.
Code `46` must repeat the build, test, lint, bundle-structure, signing, and
artifact-verification gates before its replacement upload; no such result is
claimed here. The upload-signed AAB is generated and signature-verified only in
the credentialed release process and is intentionally not committed. The
canonical hosted privacy policy is aligned with the repository disclosure.
