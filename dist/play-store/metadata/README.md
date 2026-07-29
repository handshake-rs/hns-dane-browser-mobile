# Google Play Metadata Package

This directory contains the source text and field checklist for the Google
Play Console update to package `com.denuoweb.hnsdane`. The Android-only `0.5.6`
hotfix, code `47`, with shared Rust engine `0.5.6`, shipped from source
`417af67efd68198de4871c0a339d1e456b60cb68`; the iOS app is unchanged at build
`57`. Rust
1.92's `std::fs::File::lock` is unsupported on Android, which caused the
previous Android runtime to report `rust-core-unavailable` before HNS
synchronization could start. The hotfix preserves the coordination protocol
using Android bionic `libc::flock`. It also keeps proof diagnostics tied to
Rust's retained dual-root decision, so an HNS-selected result is no longer
mislabeled as ICANN merely because all DNS traffic uses the native gateway.

The signed 60,276,192-byte AAB has SHA-256
`de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69` and
completed production deployment through edit `07330408575596336357`;
`generatedApks/47` returned HTTP `200`. The exact signed APK also upgraded a
Pixel 9 running Android 17 / API 37 from code `46` to `47` with data preserved,
cold-launched, reached `up_to_date` at height `340348` with lag `0`, freshness
`current`, and `error: null`, and passed manual sync plus HNS browsing and proof
checks. Required CI passed in run `30484282637` on workflow-only
descendant `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`; the shipping artifact
and tag remain tied to `417af67efd68198de4871c0a339d1e456b60cb68`. Google
Play's public page does not
expose an authoritative `versionCode`, so use the Android Publisher API or Play
Console as the release-identity source.

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
- Production upload artifact (signed, verified, and intentionally untracked):
  `../hns-dane-browser-v0.5.6-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration because sync is application-foreground scoped and the manifest declares no service.

The exact code `47` APK and AAB passed their build, test, lint, bundle-structure,
native-library, signing, and artifact-verification release gates. Evidence:

- Shipping source: `417af67efd68198de4871c0a339d1e456b60cb68`
- APK: 51,323,995 bytes; SHA-256 `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`
- AAB: 60,276,192 bytes; SHA-256 `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`
- Upload certificate SHA-256: `D2:2F:F3:25:17:53:11:EB:E6:D6:E9:3D:A3:FD:F5:1D:84:89:22:A1:B8:1A:CB:B3:2F:22:39:CC:F9:4A:51:14`
- Production edit: `07330408575596336357`, status `completed`
- Post-commit readback: `generatedApks/47`, HTTP `200`
- Required CI: run `30484282637` on workflow-only descendant
  `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`
- GitHub Release: [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6), verified APK only

The credentialed upload AAB is intentionally not committed. The exact signed
Pixel 9 upgrade, cold-launch, sync, HNS-browsing, and proof checks do not replace
the broader lifecycle, requester/recovery, download, Service Worker, WebSocket,
and cross-origin physical-device matrix, which remains open. The canonical
hosted privacy policy is aligned with the repository disclosure.
