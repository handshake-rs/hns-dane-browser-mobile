# Google Play Metadata Package

This directory contains source text and field guidance for a future Google
Play update to package `com.denuoweb.hnsdane`. Android `0.5.7` / code `48`
lowers the application and native NDK floor to Android 11 / API 30 while
preserving explicit UTF-8 search encoding. The shared Rust engine and iOS app
are unchanged.

The current release scope is a signed APK on GitHub only. No code `48` AAB has
been built, uploaded, or submitted to Google Play. Before a later Play update,
build and verify a signed AAB, reconcile the live Console declarations, and
replace this preparation text with exact artifact and deployment evidence.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.7 release notes: `en-US/release-notes.txt`

## Store Assets

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`

## Console Fields

- Package name: `com.denuoweb.hnsdane`
- App category: Tools
- Ads declaration: No ads
- Privacy policy URL: `https://denuoweb.com/work/hns-dane-browser/privacy`
- Future production upload artifact (signed, verified, and intentionally
  untracked): `dist/play-store/hns-dane-browser-v0.5.7-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration
  because sync is application-foreground scoped and the manifest declares no
  service.

The credentialed upload AAB is intentionally not committed. Physical-device
qualification and Google Play submission remain separate work from the
GitHub-only APK release.
