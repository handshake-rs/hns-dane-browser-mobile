# Google Play Metadata Package

This directory contains the reviewed source text and field guidance for the
Android `0.5.8` / code `49` release-preparation candidate for package
`com.denuoweb.hnsdane`. It coordinates Android and the embedded Rust workspace
at `0.5.8` and adds native-only controls for one device-local non-value HNS
account identity.

The underlying wallet tranche passed its full installed Pixel 9 lifecycle
qualification at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The candidate
pins final wallet source `4e78bb2`, whose lock closure uses final protocol
source `b24b66c`; source policy, lockfile, and notices are aligned. Final
application source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
`31402758394` and a fresh Pixel 9 install, while documentation-only parent
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI
`31411048376`. A signed and verified AAB, refreshed screenshots, live Play
Console declaration reconciliation, and intentional upload remain open. No code
`49` AAB has been built, uploaded, or submitted.

The listing deliberately describes the limited native controller. It does not
claim balances, receive/history/name reads, transfers, website-provider access,
settlement, exchange features, HNSA/HNSR, or P2P marketplaces; all remain
unavailable. The read controls require a synchronized mobile `HnsBackend` and
wallet read-runtime composition that is not present.

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

The credentialed upload AAB is intentionally not committed. Signed-artifact
verification, screenshot refresh, live Console readback, and Google Play
submission remain explicit release gates.
