# App Store metadata

This directory contains the reviewed listing source for iOS `1.0.9` / build
`72`, bundle ID `com.denuoweb.hnsdane.ios`. The preceding iOS release and its
screenshots predate this candidate and are not evidence for it.

- Version: `1.0.9`
- Build: `72`

This update refreshes name-proof timing after peer discovery, refreshes iOS
direct HNS synchronization timing at each stage, and adds protected signing
support for explicitly selected archival wallet recovery. The app registers
`http` and `https` URL schemes, routes incoming URLs directly, and keeps exact
`marketplace-kit` navigation in WebKit. The managed default-browser and
MarketplaceKit app-installation entitlements remain absent pending Apple
approval.

Build 72 retains the protected, owner-only native-wallet storage boundary and
uses one hsd/Bob-compatible account-zero receive chain for ordinary HNS and
Handshake name ownership. The iOS shell, Apple C ABI, and native wallet all use
that single receive contract.

The listing describes the shipping surface: dual-root browsing and one native,
noncustodial HNS wallet with direct peer synchronization, receive/QR, guarded
send, recent activity, name import, restoration birthday height, protected
deletion, supported name operations, and capability-gated direct Shakedex and
Bitcoin controls, including signed peer offers and durable noncustodial BTC/HNS
atomic-swap execution. Websites have no wallet-provider access, and
value-changing actions remain behind explicit native review and approval.

Canonical metadata files are the text files in `en-US/`. Product, support, and
privacy URLs must use `https://shakescape.com/`; `review-notes.txt` must explain
the native wallet and camera QR flow accurately.

The legacy screenshots under `../screenshots/en-US/` are retained historical
iPhone-only assets. Generate fresh exact-commit iPhone and iPad sets after the
final version increment, then run:

```sh
python3 store-assets/app-store/validate.py --metadata-only
expected_commit="$(git rev-parse HEAD)"
./scripts/stage-ios-app-store-screenshots.sh \
  build/app-store-live-screenshots "$expected_commit"
python3 store-assets/app-store/validate.py --expected-commit "$expected_commit"
```

The protected upload and submission workflows must bind the signed IPA,
processed build, metadata readback, review details, and explicit submission
confirmations to the same commit. Screenshots remain untouched unless an exact
replacement is explicitly requested. App Privacy, age rating,
content rights, export compliance, DSA/trader status, price, availability, and
Routing App Coverage remain deliberate App Store Connect attestations.
