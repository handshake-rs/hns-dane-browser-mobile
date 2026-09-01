# Google Play metadata

This directory contains the reviewed listing source for Android `1.0.0` / code
`52`, package `com.denuoweb.hnsdane`. Version numbers must be updated with the
application manifest and upload script by `scripts/check-version-consistency.sh`.

The listing describes the shipping surface: dual-root browsing and one native,
noncustodial HNS wallet with direct peer synchronization, receive/QR, guarded
send, recent activity, name import, restoration birthday height, and protected
deletion. Websites have no wallet-provider access. The unfinished Bitcoin,
name-operation, and Shakedex marketplace cards are not exposed.

## Listing files

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 1.0.0 release notes: `en-US/release-notes.txt`
- Privacy policy: `https://shakescape.com/privacy/`
- Support and product site: `https://shakescape.com/`

## Assets and upload

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`
- Expected upload artifact:
  `dist/play-store/hns-dane-browser-v1.0.0-play-upload-signed.aab`

The retained screenshots predate the final wallet UI and must be recaptured from
the exact versioned candidate without displaying recovery phrases, account IDs,
addresses, balances, or transaction identifiers. The signed AAB, Play Console
Data safety and financial-feature answers, foreground `dataSync` service
declaration, screenshot set, and live listing must all be read back before
intentional submission. No code `52` AAB has yet been uploaded.
