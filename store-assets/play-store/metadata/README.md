# Google Play metadata

This directory contains the reviewed listing source for Android `1.0.3` / code
`55`, package `com.denuoweb.hnsdane`. Version numbers must be updated with the
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
- 1.0.3 release notes: `en-US/release-notes.txt`
- Privacy policy: `https://shakescape.com/privacy/`
- Support and product site: `https://shakescape.com/`

## Assets and upload

- App icon: `../hns-dane-browser-play-icon-512.png`
- Feature graphic: `../hns-dane-browser-feature-graphic-1024x500.png`
- Phone screenshots: `../screenshots/*.png`
- Expected upload artifact:
  `dist/play-store/hns-dane-browser-v1.0.3-play-upload-signed.aab`

Seven phone screenshots were refreshed on a Pixel 9 at 1080 x 2424 from the
the prior `1.0.2-debug` / code `54` application. They cover the local start page,
the Shakescape ICANN site, the proof-backed `shakescape/` HNS site, browser
navigation, Handshake settings, build diagnostics, and a verified HNS proof.
The unchanged locked-wallet screenshot is retained because that screen exposes
no versioned copy and creating a throwaway screenshot wallet would unnecessarily
produce recovery material. It contains no recovery phrase, account ID,
address, balance, or transaction identifier. The screenshots are listing
source only and are not evidence that the release APK or Play-signed AAB has
passed release signing or installed-device gates.

The signed AAB, Play Console Data safety and financial-feature answers,
foreground `dataSync` service declaration, screenshot set, and live listing
must all be read back before intentional submission. Google Play has published
code `54`; no code `55` AAB has yet been uploaded.
