# Google Play Metadata Package

This directory contains the reviewed source text and field guidance for the
Android `0.5.10` / code `51` release candidate for package
`com.denuoweb.hnsdane`. Its independently versioned embedded Rust workspace
remains at `0.5.9`; the app includes native lifecycle controls plus strict
HNWR-v2 read-only fields with separate HNS payment and name-transfer receive
targets for one device-local HNS account identity.
The runtime uses engine `0.2.1` at exact revision
`65c397e8347f37085ea67d2c9c745ce896328e64`.

The underlying wallet lifecycle tranche passed its installed Pixel 9
qualification at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The candidate
    pins wallet source `2061a27`, whose lock closure uses protocol source
    `88ed7c6`; source policy, lockfile, and notices are aligned. Historical
source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
`31402758394` and a fresh Pixel 9 install, while documentation-only parent
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI
`31411048376`. That evidence predates the current read projection. Prior
`0.5.9` code-bearing source `893ba8271787f1ab7247fa78ed8787462b5542fc`
passed full CI
`31433931682`, including Android build/unit, API 37 native instrumentation,
Rust/supply-chain, Apple, and Required CI. Exact debug artifact `9080493058`
contains a 65,680,703-byte APK with SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`,
package `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`, minimum API 30,
target API 37, and `arm64-v8a` + `x86_64`. It verifies with APK Signature Scheme
v2 under one default Android Debug RSA-2048 certificate, not the Play upload
identity.
The exact APK installed on a Pixel 9 (`tokay`), Android 17 / API 37, after the
incompatible historical code `49` debug update safely failed and an authorized
debug-package-only reinstall left production installed and untouched. The
installed digest matched, cold launch succeeded, and the native wallet screen
displayed the no-wallet controls and fail-closed HNWR rows with disabled
value/marketplace copy. No wallet was created/restored and no secret, account,
credentialed sync, or value action ran. This is historical code `50` evidence,
not qualification for the current candidate. Earlier HNWR-v2 code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
`31807520618`, including Android API 37 native instrumentation and the complete
Apple gate; CodeQL runs `31807519998` and `31807520229` also passed. Those
results predate the `2061a27` exact-name import tranche. Exact current
application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the
complete manually dispatched CI matrix in run `31835813994`: repository policy,
Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
the complete Apple ABI/XCFramework/app/simulator gate, and aggregate Required
CI all succeeded. CodeQL runs `31833858421` and `31833858650` also passed.
Historical debug artifact `9222123624` has artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is not Play/store signed. APK-level artifact and signer
inspection, installed-device qualification, refreshed screenshots, live Play
Console declaration reconciliation, and intentional upload remain open.
No code `51` AAB has been built, uploaded, or submitted.

The listing deliberately describes the limited native controller and its visible
read rows. The product installs no scoped loopback credential or indexed wallet
backend, so balance, payment and name-transfer receive targets, transaction
history, tracked names, and module status remain fail-closed and unavailable. A
pruned indexed/authenticated
node can serve current-wallet evidence; fresh restore additionally needs a durable
wallet-relevant raw-tx source. Exact-text native name import is implemented but
unavailable without that backend and credential. Transfers/value movement,
website-provider access, settlement, exchange features, HNSA/HNSR, and P2P
marketplaces remain absent or gated off.

## Listing Text

- App name: `en-US/title.txt`
- Short description: `en-US/short-description.txt`
- Full description: `en-US/full-description.txt`
- 0.5.10 release notes: `en-US/release-notes.txt`

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
  `dist/play-store/hns-dane-browser-v0.5.10-play-upload-signed.aab`
- Foreground service type: none; remove any stale `dataSync` declaration
  because sync is application-foreground scoped and the manifest declares no
  service.

The credentialed upload AAB is intentionally not committed. Signed-artifact
verification, screenshot refresh, live Console readback, and Google Play
submission remain explicit release gates.
