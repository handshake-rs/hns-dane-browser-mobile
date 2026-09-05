# App Store metadata

This directory contains the reviewed listing source for iOS `1.0.5` / build
`66`, bundle ID `com.denuoweb.hnsdane.ios`. The preceding iOS release and its
screenshots predate this candidate and are not evidence for it.

- Version: `1.0.5`
- Build: `66`

This update brings the current native wallet synchronization, recovery, name
tracking, Unicode-name, record, transfer/finalization, and diagnostic work to
iOS. Apple's managed `com.apple.developer.web-browser`
request remains pending, so neither that entitlement nor the separate
`com.apple.developer.browser.app-installation` entitlement is requested or
shipped in this candidate. Default-browser activation is reserved for a later
version after Apple grants the capability.

The listing describes the shipping surface: dual-root browsing and one native,
noncustodial HNS wallet with direct peer synchronization, receive/QR, guarded
send, recent activity, name import, restoration birthday height, protected
deletion, supported name operations, and capability-gated direct Shakedex and
Bitcoin controls. Websites have no wallet-provider access, and value-changing
actions remain behind explicit native review and approval.

Canonical metadata files are the text files in `en-US/`. Product, support, and
privacy URLs must use `https://shakescape.com/`; `review-notes.txt` must explain
the native wallet and camera QR flow accurately.

The screenshots under `../screenshots/en-US/` are retained historical assets.
Generate a fresh exact-commit set after the final version increment, then run:

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
