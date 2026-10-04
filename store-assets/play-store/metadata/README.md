# Google Play metadata

This directory contains the candidate listing source for Android `1.0.15` / code
`67`, package `com.denuoweb.hnsdane`. Version numbers must be updated with the
application manifest and upload script by `scripts/check-version-consistency.sh`.

The listing describes the shipping surface: dual-root browsing and one native,
noncustodial HNS/Bitcoin wallet with direct peer synchronization, receive/QR,
guarded sends, recent activity, supported name actions, restoration birthday
heights, direct ShakeScape offer exchange, durable BTC/HNS atomic-swap
execution, and protected deletion. Websites have no wallet-provider access.

## Listing files

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 1.0.15 release notes: `en-US/release-notes.txt`
- Privacy policy: `https://shakescape.com/privacy/`
- Support and product site: `https://shakescape.com/`

## Assets and upload

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Historical phone screenshots: `../screenshots/*.png`; use a fresh verified
  `android-store-screenshots-<commit>` workflow artifact for this release.
- Expected upload artifact:
  `dist/play-store/hns-dane-browser-v1.0.15-play-upload-signed.aab`

Capture screenshots from the exact shipping candidate. Review browser navigation,
Handshake settings, proof details, and wallet onboarding without exposing secrets
or user identifiers. Verify the signed AAB independently before any upload.

Reconcile Data safety, financial-feature answers, foreground `dataSync`, and
listing text with the shipping implementation and saved Play Console answers.
After an authorized upload, read back version code, track, release status, and
asset inventory through a fresh store query.
