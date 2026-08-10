# App Store metadata

This directory is the reviewed source package for the coordinated iOS `0.5.8`
release-preparation candidate. The binary upload workflow does not upload
listing metadata; the operator must reconcile these exact fields in App Store
Connect before submission.

The native wallet tranche passed the complete Apple ABI, XCFramework, app, and
simulator gate in exact-source Required CI run `31393998309` at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The `0.5.8` version/metadata
package pins final wallet `0.1.0` source `4e78bb2` and final `hns-rs 0.2.0`
source `b24b66c`; source policy, lockfile, and notices are aligned. That
resulting commit requires its own exact-head CI, fresh commit-bound screenshots,
signing, processing, metadata readback, and intentional submission. Nothing in
this package proves that build `58` has been uploaded or published.

The current public Apple baseline remains `0.5.5` / build `57`, published on
2026-07-31 from `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Its retained
submission and artifact evidence is release history, not evidence for `0.5.8`.
No TestFlight distribution is planned by this repository workflow.

The checked-in description and review notes accurately describe the new
native-only, non-value controller. They do not claim balances, transfers,
names, website-provider access, settlement, exchange features, or P2P
marketplaces; all remain unavailable.

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

1. Pass exact-head Required CI for the final dependency-pinned `0.5.8` commit.
2. Publish the updated repository privacy policy to the canonical hosted URL
   and verify its response before submission.
3. Reconcile App Privacy, age rating, category, content rights, export
   compliance, DSA, price, availability, and routing against the exact build.
4. Generate and review current iPhone screenshots from the exact source.
5. Supply App Review with `en-US/review-notes.txt`; no login is required.
6. Select processed build `58`, choose manual release, and complete
   `../submission-checklist.md` before adding the version for review.

The API private key used by CI must exist only in the protected GitHub
`app-store` environment and must never be committed or uploaded as a workflow
artifact.
