# App Store metadata

This directory contains the reviewed listing source for iOS `1.0.4` / build
`64`, bundle ID `com.denuoweb.hnsdane.ios`. Public iOS `1.0.3` and its
screenshots predate this candidate and are not evidence for it.

- Version: `1.0.4`
- Build: `64`

This update fixes authenticated CNAME/CDN resolution and prepares Shakescape
for Apple's managed default-browser role. Release signing requires an App Store
profile containing `com.apple.developer.web-browser`; the separate
`com.apple.developer.browser.app-installation` entitlement is intentionally not
requested or shipped.

The listing describes the shipping surface: dual-root browsing and one native,
noncustodial HNS wallet with direct peer synchronization, receive/QR, guarded
send, recent activity, name import, restoration birthday height, and protected
deletion. Websites have no wallet-provider access. The unfinished Bitcoin,
name-operation, and Shakedex marketplace cards are not exposed.

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
processed build, exact screenshots, metadata readback, review details, and
explicit submission confirmations to the same commit. App Privacy, age rating,
content rights, export compliance, DSA/trader status, price, availability, and
Routing App Coverage remain deliberate App Store Connect attestations.
