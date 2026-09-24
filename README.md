# Shakescape

Shakescape is a native Android and Apple browser for the Handshake naming
system. It combines locally validated Handshake headers and name proofs,
authoritative DNS, DNSSEC, and DANE with an encrypted HNS wallet, a Bitcoin
wallet, and direct peer-to-peer ShakeDex name and HNS/BTC swap workflows.

- [Google Play](https://play.google.com/store/apps/details?id=com.denuoweb.hnsdane)
- [Apple App Store](https://apps.apple.com/us/app/hns-dane-browser/id6791914326)
- [GitHub release v1.0.7](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v1.0.7)
- [Signed Android APK](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/download/v1.0.7/shakescape-v1.0.7-android-release.apk)
- [Signed iOS App Store IPA](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/download/v1.0.7/shakescape-v1.0.7-ios-app-store.ipa)
- [SHA-256 checksums](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/download/v1.0.7/SHA256SUMS-v1.0.7.txt)

The current distributed release is Android `1.0.7` / code `59` and iOS
`1.0.7` / build `70`. The repository’s embedded Rust workspace is private to
the application and is not published as a crate.

## What is implemented

### Handshake browser

- Validates Handshake headers, chain work, difficulty transitions, and Urkel
  name proofs locally instead of trusting a remote JSON result.
- Resolves HNS resources through authoritative DNS, validates DNSSEC, and
  applies TLSA/DANE policy before an origin is admitted.
- Supports ordinary ICANN names with explicit namespace selection and
  fail-closed handling of divergent HNS and ICANN answers.
- Provides native address/search input, tabs, history, bookmarks, downloads,
  diagnostics, settings, and accessibility-labelled controls on Android and
  Apple platforms.
- Supports `http` and `https` navigation. HTTP is visibly marked insecure;
  native transport policy does not silently convert it into authenticated
  HTTPS.

### Native wallets and ShakeDex

- Creates, restores, unlocks, locks, and synchronizes an encrypted Handshake
  wallet without exposing wallet authority to page JavaScript.
- Displays HNS balances, reservations, history, receive targets, tracked
  names, and typed progress or failure status.
- Prepares and broadcasts HNS sends and name actions, including transfer,
  update, renewal, and tracked automatic FINALIZE recovery.
- Synchronizes a native Bitcoin wallet through compact filters and supports
  ordinary Bitcoin send and receive.
- Lists, discovers, cancels, accepts, resumes, redeems, refunds, and recovers
  direct ShakeDex name offers and bidirectional BTC-for-HNS / HNS-for-BTC
  atomic swaps.
- Surfaces swap-stage status in the ShakeDex UI and through native local
  notifications on both Android and iOS. Durable state, not a notification, is
  the authority after restart.

### Direct peer networking

- Runs a standard Handshake-compatible TCP listener advertising the ordinary
  `NETWORK` service together with the ShakeScape extension service.
- Consumes standard `ADDR` events, retains a small bounded cache of fresh
  public ShakeScape candidates, and performs the exact ShakeScape registry,
  network, and genesis negotiation before treating a connection as a board
  peer.
- Attempts public IPv6 and automatic router mappings where the platform and
  network permit them. Manual IP-literal peers and private-overlay endpoints
  remain available when neither phone is publicly reachable.
- Exchanges board offers and atomic-swap session messages directly between
  authenticated peers. An hsrd/HNSR node may provide rendezvous and opaque
  transport, but it does not become a custodian of wallet keys or swap funds.
- Diagnoses the common case in which repeated independent mainnet peers time
  out on outbound TCP port `12038`, rather than displaying indefinite progress
  with no network explanation.

Stock HSD address gossip can discover a phone only when its advertised
Handshake TCP endpoint is genuinely reachable. A UDP-only STUN mapping is not
misrepresented as a `NETWORK` address. Cellular CGNAT therefore still requires
a reachable peer, router/IPv6 path, private overlay, or rendezvous transport.

## Android and Apple parity

The two native shells share the same Rust wallet/network state machines and
expose the same principal wallet, name, Bitcoin, ShakeDex, recovery, QR-send,
and swap-notification flows. Platform code owns presentation and lifecycle
integration; consensus, signing, encrypted persistence, reservations, and swap
state transitions remain in Rust.

The Apple target supports iPhone and iPad (`TARGETED_DEVICE_FAMILY = 1,2`) and
allows Apple Silicon Macs to run the iPhone/iPad application
(`SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD = YES`). Mac Catalyst is not enabled.

The iOS `Info.plist` declares both `http` and `https` in
`CFBundleURLSchemes`, uses `WKWebView` rather than the retired `UIWebView`, and
routes an incoming web URL directly into the normal browser navigation path.
The app opens with a visible address/search field. It does not declare the
browser-disallowed always-location, HomeKit, always-Bluetooth, Health, or photo
library usage keys. Camera access is limited to scanning a payment QR code.

Those implementation facts make the source suitable for a renewed Apple
default-browser capability request. They do not claim that Apple has granted
the managed default-browser or Browser App Installation entitlement; those
remain Apple-controlled capabilities and must not be added to a signed profile
before approval.

## Architecture

```text
Android Kotlin / iOS Swift
          │
          ▼
JNI / stable Apple C ABI
          │
          ▼
mobile platform runtime
   ├── browser resolution, DNSSEC and DANE
   ├── validated Handshake light client
   ├── encrypted HNS and Bitcoin wallets
   └── direct ShakeScape peer and swap state machines
          │
          ├── ordinary HSD/hsrd peers for headers, proofs and broadcast
          └── direct or HNSR-routed peer for board and swap messages
```

Repository layout:

- `rust/` — shared mobile runtime plus Android JNI and Apple C ABI crates.
- `android/` — Kotlin browser and native wallet application.
- `ios/` — Swift/UIKit and `WKWebView` application.
- `docs/` — architecture, security, release, device-validation, sync, and
  feature-matrix evidence.
- `fixtures/` — bounded cross-language protocol fixtures.
- `scripts/` — validation, native builds, store upload, and release helpers.

The application consumes protocol code from
[`hns-rs`](https://github.com/handshake-rs/hns-rs), browser/light-client code
from [`hns-dane-engine`](https://github.com/handshake-rs/hns-dane-engine), and
wallet code from
[`hns-wallet-rs`](https://github.com/handshake-rs/hns-wallet-rs). Development
source currently targets the coherent `hns-rs 0.4.2`, engine mobile-wallet
`0.2.5`, and wallet `0.2.6` cohorts. Adjacent path patches keep a single Rust
type identity while those coordinated crates move through their crates.io
release gates; shipping dependency provenance is recorded in
[`docs/released-dependency-cohort.md`](docs/released-dependency-cohort.md).

## Security boundaries

- Web content never receives recovery phrases, private keys, decrypted wallet
  records, raw signing handles, peer credentials, or provider authority.
- Every value-moving action requires native review and explicit approval.
- Wallet storage is encrypted and platform key wrapping is performed by
  Android Keystore or Apple Keychain integration.
- DNS answers, peer addresses, board offers, and relay payloads are untrusted
  input until their corresponding local validation and signature checks pass.
- HNSR profile `0x0004` transports opaque, already-addressed ShakeScape bytes;
  the relay cannot turn arbitrary bytes into an accepted board offer or swap
  transition.
- Lock and protected-background transitions close sensitive native sheets.
  Durable synchronization and approved recovery work may continue under the
  platform’s permitted background execution, but decrypted UI authority is
  not retained merely to keep a screen visible.

See [`docs/security-model.md`](docs/security-model.md),
[`docs/architecture.md`](docs/architecture.md), and the
[`native wallet feature matrix`](docs/wallet-feature-matrix.md) for the full
boundary definitions.

## Build and validate

The repository pins its Rust and platform toolchains. Run the complete local
source gate with:

```sh
./scripts/check.sh
./scripts/fuzz-smoke.sh
```

Build Android on the supported ARM64 Linux host with the host-native NDK
toolchain:

```sh
./scripts/build-android.sh
```

The build deliberately rejects an x86_64 NDK host toolchain on an ARM64 host;
it does not run Android native compilation through foreign-architecture
emulation. The debug APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`.

Android unit and installed-device tests use APK Workbench:

```sh
APK_WORKBENCH="$HOME/APK_Workbench"
GRADLE="$APK_WORKBENCH/scripts/dev/apkw-gradle.sh"

"$GRADLE" --project-dir "$PWD/android" testDebugUnitTest
"$GRADLE" --project-dir "$PWD/android" connectedDebugAndroidTest
```

On macOS with the repository-supported Xcode, iOS SDK, and Apple Rust targets:

```sh
./scripts/run-ios-gate.sh
```

The Apple gate verifies the ABI, creates
`build/apple/HnsBrowserRuntime.xcframework`, runs the simulator tests, and
links the arm64 device release slice. Simulator success is not a substitute
for the signed physical-device matrix in
[`docs/ios-device-validation.md`](docs/ios-device-validation.md).

Store release procedures are documented in
[`docs/play-store-readiness.md`](docs/play-store-readiness.md) and
[`docs/ios-app-store-release.md`](docs/ios-app-store-release.md).

## Support and license

Donations are optional and unlock no application features.

- HNS donation address: `hs1q5997733eq7f4yyk2vq2z8gz3yqyvpz422ypggh`

The repository is source-available under the PolyForm Noncommercial License
1.0.0. Noncommercial use, study, modification, and redistribution are allowed
under that license. Commercial use requires separate written permission from
Denuo Web, LLC.
