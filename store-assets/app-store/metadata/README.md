# App Store metadata

This directory is the reviewed, retained source for the submitted iOS App Store
update. The binary workflow does not upload listing metadata. Version `0.5.0`
was public on the Apple App Store when checked on 2026-07-28; version `0.5.5`
(build `57`) comes from
`d926561091634cd69fc9b7e79a4b76003fa4ee47`. Build `57` contains the shared
parser and post-resolution timing fixes plus the final live-capture
proof-selection and cached-main-frame revalidation corrections. Exact-head
Apple CI `30454904736`, live Release screenshot run `30454926117`, and the
signed archive/upload portion of protected run `30456522039` passed. The IPA
SHA-256 is
`efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`.
App Store Connect processed build `57` as `VALID`. The version-managed
description, keywords, promotional text, support/marketing URLs, What's New,
copyright, four ordered screenshots, review details, content-rights
declaration, and linked build passed API readback. App/account-level name,
subtitle, privacy, age-rating, DSA, pricing, availability, and routing fields
were not managed by the release client.
The direct App Review submission is `WAITING_FOR_REVIEW`, release type is
`MANUAL`, and no TestFlight distribution was created. Public GitHub Release
`v0.5.5` retains the exact verified IPA as asset `494101433`.

## App record

- Platform: iOS
- Name: `HNS DANE Browser`
- Primary language: English (U.S.)
- Bundle ID: `com.denuoweb.hnsdane.ios`
- SKU: `hns-dane-browser-ios`
- Apple Team ID: `45NQQK3G3S`
- User access: Full Access
- Version: `0.5.5`
- Build: `57`
- Primary category: Utilities
- Price: Free

The app remains iPhone-only. Native iPad support can be enabled in a later
version after adding iPad screenshots and validation coverage.

## Canonical update fields

These files remain the canonical public-field sources for the `0.5.5` record:

- `name.txt`
- `subtitle.txt`
- `promotional-text.txt`
- `description.txt`
- `keywords.txt`
- `support-url.txt`
- `marketing-url.txt`
- `copyright.txt`
- `whats-new.txt`

Set `privacy-policy-url.txt` under **App Privacy**, not on the version page. Paste
`review-notes.txt` into **App Review Information → Notes** and
`whats-new.txt` into **What's New in This Version** for this update.
The guarded client reconciled and read back the version-localized subset named
above; it deliberately left app/account-level fields such as name, subtitle,
privacy answers, and pricing outside its mutation scope.

Store approved iPhone screenshots in `../screenshots/en-US/`, numbered in display
order (`01-...`, `02-...`, and so on). Use one exact approved 6.9-inch or 6.5-inch
resolution for the set and do not include an alpha channel.

Run the deterministic package checks before entering metadata, and run the full
check again after screenshots are added:

```sh
python3 store-assets/app-store/validate.py --metadata-only
python3 store-assets/app-store/validate.py
```

## Submission controls

1. Confirm the hosted cross-platform privacy policy remains aligned at the URL in `en-US/privacy-policy-url.txt`; it is aligned at this checkpoint.
2. Complete App Store Connect's app-privacy, age-rating, content-rights, and export-compliance questionnaires from the app's actual behavior. Do not answer the encryption question by assumption: the Rust runtime implements industry-standard TLS, DNSSEC, and DANE cryptography outside Apple's operating-system crypto APIs.
3. Generate current iPhone simulator screenshots from the iOS shell. Do not reuse Android screenshots.
4. No TestFlight distribution is part of this release. A separately installed
   signed build may be used for a future physical-device matrix; until that is
   recorded, installed-iOS and ecosystem qualification remain open.
5. Supply App Review with the notes in `en-US/review-notes.txt`; no login is required.
6. Complete the directly managed items in `../submission-checklist.md`, select
   build `57`, and choose manual release before adding the version for review.
   Preserve the unchecked app/account-level items as explicit evidence
   follow-ups when their exact saved values are not available through the
   guarded client.

The managed controls were reconciled for the `0.5.5` submission on 2026-07-29,
and Apple accepted it for review. That acceptance does not independently prove
the exact saved values of the excluded app/account-level fields. The remaining
installed-iPhone matrix is a separate qualification activity, not a missing
App Store submission field.

The API private key used by CI must exist only in the protected GitHub `app-store` environment and must never be committed or uploaded as a workflow artifact.
