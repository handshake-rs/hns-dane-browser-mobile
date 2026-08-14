# App Store metadata

This directory is the reviewed source package for the iOS `0.5.10` / build
`60` release candidate. The binary upload workflow does not upload
listing metadata. The separate protected metadata/submission workflow applies
and reads back the reviewed fields, exact screenshots, build relationship, and
App Review details before using Apple's Review Submissions API. Account-level
privacy, age-rating, DSA, pricing, availability, export, and routing answers
still require explicit reconciliation and attestation.

The native wallet lifecycle tranche passed its dated complete Apple gate in
Required CI run `31393998309` at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. The current package pins wallet
`0.1.0` source `49afe81` and `hns-rs 0.3.0` source `88ed7c6`;
source policy, lockfile, and notices are aligned. Historical `0.5.8` application
source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI
`31402758394`; documentation-only parent
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI
`31411048376`; those runs predate the current read projection. Prior `0.5.9`
code-bearing source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including the complete Apple app/simulator gate. Android debug
artifact `9080493058` has APK SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`,
package `com.denuoweb.hnsdane.debug`, version `0.5.9-debug` / code `50`,
`arm64-v8a` + `x86_64`, and default Android Debug APK-v2 signing. It is neither
an iOS nor store-signed artifact. The exact APK installed and cold-launched on
a Pixel 9 and displayed the expected no-wallet/fail-closed native wallet UI.
No wallet was created/restored and no credentialed read or value action ran;
this Android UI result is not iOS evidence. Fresh
exact-release-checkout screenshots, signing, processing, metadata readback,
intentional submission, and the physical-iPhone matrix remain open. Nothing in
this package proves that build `60` has been uploaded or published. Build `59`
was superseded without upload; build `60` requires fresh exact-source Apple CI,
screenshots, signing, upload, processing, and API readback.

The current public Apple baseline remains `0.5.5` / build `57`, published on
2026-07-31 from `d926561091634cd69fc9b7e79a4b76003fa4ee47`. Its retained
submission and artifact evidence is release history, not evidence for `0.5.10`.
No TestFlight distribution is planned by this repository workflow.

The checked-in description and review notes accurately describe the native
controller and visible strict HNWR-v2 read-only fields, including separate HNS
payment and name-transfer receive targets. Legacy HNWR-v1 remains a separately
decoded exact shape. The product installs no
scoped loopback credential or indexed wallet backend, so those fields remain
unavailable. A pruned indexed/authenticated node can return indexed history, and
an existing wallet may reuse retained raw bytes.
Fresh restore needs a durable wallet-relevant raw-tx source. Name import,
send/value, website-provider,
settlement, exchange, HNSA/HNSR, and P2P-marketplace paths remain absent or
gated. Retirement queue/lease behavior and stale-completion
publication-authority predicates passed exact Apple app/simulator CI; no
end-to-end credentialed native read in flight ran. iOS product wiring still
needs the scoped credential/backend/data boundary before reads can be enabled.

## App record

- Platform: iOS
- Name: `HNS DANE Browser`
- Primary language: English (U.S.)
- Bundle ID: `com.denuoweb.hnsdane.ios`
- SKU: `hns-dane-browser-ios`
- Apple Team ID: `45NQQK3G3S`
- User access: Full Access
- Version: `0.5.10`
- Build: `60`
- Primary category: Utilities, pending candidate-specific review
- Price: Free
- Device family: iPhone

Native iPad support remains out of scope until iPad screenshots and validation
coverage exist.

## Canonical update fields

Use these files for the `0.5.10` record:

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
assets only. Generate a fresh exact-commit set for `0.5.10`; the protected
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
2. Retain deployment and readback evidence for version-neutral HNWR-aware
   privacy source `a5539cb` in Firebase run `31485234945`; source `909dbd1`
   remains earlier wallet-lifecycle history only.
3. Reconcile App Privacy, age rating, category, content rights, export
   compliance, DSA, price, availability, and routing against the exact build.
4. Generate and review current iPhone screenshots from the exact source.
5. Supply App Review with `en-US/review-notes.txt`; no login is required.
6. Select processed build `60`, choose manual release, and complete
   `../submission-checklist.md` before adding the version for review.

The API private key used by CI must exist only in the protected GitHub
`app-store` environment and must never be committed or uploaded as a workflow
artifact.
