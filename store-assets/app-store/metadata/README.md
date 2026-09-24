# App Store metadata

This directory contains the reviewed listing source for iOS `1.0.7` / build
`70`, bundle ID `com.denuoweb.hnsdane.ios`. The preceding iOS release and its
screenshots predate this candidate and are not evidence for it.

- Version: `1.0.7`
- Build: `70`

This update brings the current native wallet synchronization, recovery, name
tracking, Unicode-name, record, transfer/finalization, and diagnostic work to
iOS. Apple rejected the preceding managed `com.apple.developer.web-browser`
request because the submitted binary did not register `http` and `https` URL
schemes. Build 70 registers both schemes, routes incoming URLs directly, and
keeps exact `marketplace-kit` navigation in WebKit. Neither entitlement is
requested in this candidate; after Apple approves the renewed capability
request, a later signed build will add `com.apple.developer.web-browser` and
`com.apple.developer.browser.app-installation`.

Build 70 also replaces build 69's failed fresh-wallet path: before invoking the
native wallet, iOS now applies and verifies owner-only `0700` permissions on
the protected wallet directories. Native lifecycle failures preserve a bounded
underlying reason for diagnostics instead of reducing every cause to the same
generic code-5 message.

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
