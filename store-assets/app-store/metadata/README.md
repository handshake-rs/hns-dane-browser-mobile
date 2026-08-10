# App Store metadata

This directory is the reviewed source package for the coordinated iOS `0.5.8`
release-preparation candidate. The binary upload workflow does not upload
listing metadata; the operator must reconcile these exact fields in App Store
Connect before submission.

The native wallet tranche passed its dated complete Apple gate in Required CI
run `31393998309` at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The `0.5.8`
package pins final wallet `0.1.0` source `4e78bb2` and final `hns-rs 0.2.0`
source `b24b66c`; source policy, lockfile, and notices are aligned. Final
application source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
`31402758394`; documentation-only parent
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI
`31411048376`. Fresh exact-release-checkout screenshots, signing, processing,
metadata readback, and intentional submission remain open. Nothing in this
package proves that build `58` has been uploaded or published.

The current public Apple baseline remains `0.5.5` / build `57`, published on
2026-07-31 from `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Its retained
submission and artifact evidence is release history, not evidence for `0.5.8`.
No TestFlight distribution is planned by this repository workflow.

The checked-in description and review notes accurately describe the new
native-only, non-value controller. They do not claim balances,
receive/history/name reads, transfers, website-provider access, settlement,
exchange features, HNSA/HNSR, or P2P marketplaces; all remain unavailable. The
read controls require a synchronized mobile `HnsBackend` and wallet read-runtime
composition that is not present.

## App record

- Platform: iOS
- Name: `HNS DANE Browser`
- Primary language: English (U.S.)
- Bundle ID: `com.denuoweb.hnsdane.ios`
- SKU: `hns-dane-browser-ios`
- Apple Team ID: `45NQQK3G3S`
- User access: Full Access
- Version: `0.5.8`
- Build: `58`
- Primary category: Utilities, pending candidate-specific review
- Price: Free
- Device family: iPhone

Native iPad support remains out of scope until iPad screenshots and validation
coverage exist.

## Canonical update fields

Use these files for the `0.5.8` record:

- `name.txt`
- `subtitle.txt`
- `promotional-text.txt`
- `description.txt`
- `keywords.txt`
- `support-url.txt`
- `marketing-url.txt`
- `copyright.txt`
- `whats-new.txt`
- `review-notes.txt`
- `privacy-policy-url.txt`

Set `privacy-policy-url.txt` under **App Privacy**, paste `review-notes.txt` into
**App Review Information → Notes**, and paste `whats-new.txt` into **What's New
in This Version**. The guarded client deliberately leaves app/account-level
privacy, age-rating, DSA, pricing, availability, and routing answers outside
its mutation scope; read those back separately.

The retained screenshots under `../screenshots/en-US/` come from public
`0.5.5` source and do not show the native wallet controls. They are historical
assets only. Generate a fresh exact-commit set for `0.5.8`; the protected
upload workflow rejects a screenshot set whose commit does not match the
candidate or whose provenance does not prove the visible native wallet row.
The retained historical set deliberately fails the current full validator.

Run the deterministic metadata checks before entering fields. Run the complete
validator only after fresh screenshots are staged for the exact candidate:

```sh
python3 store-assets/app-store/validate.py --metadata-only
expected_commit="$(git rev-parse HEAD)"
./scripts/stage-ios-app-store-screenshots.sh \
  build/app-store-live-screenshots "$expected_commit"
python3 store-assets/app-store/validate.py \
  --expected-commit "$expected_commit"
```

## Submission controls

1. Retain the application-source Required CI `31402758394` and full manual
   docs-parent CI `31411048376` evidence; the protected upload workflow must
   still rerun its exact-release-checkout gate before signing.
2. Confirm the wallet-aware policy deployed from source `909dbd1` remains live
   at the canonical hosted URL; deployment and readback are complete.
3. Reconcile App Privacy, age rating, category, content rights, export
   compliance, DSA, price, availability, and routing against the exact build.
4. Generate and review current iPhone screenshots from the exact source.
5. Supply App Review with `en-US/review-notes.txt`; no login is required.
6. Select processed build `58`, choose manual release, and complete
   `../submission-checklist.md` before adding the version for review.

The API private key used by CI must exist only in the protected GitHub
`app-store` environment and must never be committed or uploaded as a workflow
artifact.
