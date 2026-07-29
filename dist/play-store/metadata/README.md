# Google Play Metadata Package

This directory contains the source text and field checklist for the Google
Play Console update to package `com.denuoweb.hnsdane`. The current package
being prepared is the Android-only `0.5.6` hotfix, code `47`, with shared Rust
engine `0.5.6`; the iOS app is unchanged at its existing build `57`. Rust
1.92's `std::fs::File::lock` is unsupported on Android, which caused the
previous Android runtime to report `rust-core-unavailable` before HNS
synchronization could start. The hotfix preserves the coordination protocol
using Android bionic `libc::flock`. It also keeps proof diagnostics tied to
Rust's retained dual-root decision, so an HNS-selected result is no longer
mislabeled as ICANN merely because all DNS traffic uses the native gateway.

On a physical Pixel 9 running API 37, the new HNS Proof Details activity test
first failed against the pre-fix behavior by showing DNSSEC/synthetic ICANN
details. After the selector correction, paired HNS-proof and ICANN-DNSSEC
activity tests pass. Required CI is configured to run those tests with the
fresh-runtime regression on an API 37 x86_64 emulator, but no completed remote
result is recorded.

No signed code 47 APK or AAB, Play edit/upload/commit, `v0.5.6` tag, or GitHub
Release is recorded as completed yet. The historical Google Play production
release is code `46`, built from source commit
`d24f85158854abb8be4a7bb9e914aebe5e7e4679`. It completed through committed
edit `17438779769069438085` with production status `completed`, and the
`generatedApks/46` readback returned HTTP `200`. Google Play's public page does
not expose an authoritative `versionCode`; use the Android Publisher API or
Play Console as the release-identity source.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.6 release notes: `en-US/release-notes.txt`

## Store Assets

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`

## Console Fields

- Package name: `com.denuoweb.hnsdane`
- App category: Tools
- Ads declaration: No ads
- Privacy policy URL: `https://denuoweb.com/work/hns-dane-browser/privacy`
- Planned hotfix upload artifact (not yet signed or uploaded):
  `../hns-dane-browser-v0.5.6-play-upload-signed.aab`
- Historical deployed production upload artifact:
  the retained code 46 AAB outside version control
- Foreground service type: none; remove any stale `dataSync` declaration because sync is application-foreground scoped and the manifest declares no service.

The historical exact code `46` APK and AAB passed their build, test, lint,
bundle-structure, native-library, signing, and artifact-verification release
gates. Evidence:

- APK SHA-256: `b36a4346ffcba14c081500ef3dc7c5012cabd30f42cdaa80a354eefb5da210ba`
- AAB SHA-256: `728d8892e180d954652668a4e53a7e2d6c7542e9d36330f4803cdecdb34598b0`
- Upload certificate SHA-256: `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`
- Production edit: `17438779769069438085`, status `completed`
- Post-commit readback: `generatedApks/46`, HTTP `200`

Code 47 evidence will be added only after its build, signing, verification, and
upload complete. The credentialed upload AAB is intentionally not committed.
The focused Pixel 9 debug regressions do not replace physical-device upgrade,
cold-launch, exact-artifact, or broader behavior qualification, which remain
open. The canonical hosted privacy policy is aligned with the repository
disclosure.
