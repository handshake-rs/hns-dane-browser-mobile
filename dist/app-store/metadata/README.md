# App Store metadata

This directory is the reviewed source for the next iOS App Store update. It is
not uploaded automatically by the binary upload workflow. Version `0.5.0` was
public on the Apple App Store when checked on 2026-07-28; the source candidate
is `0.5.5` (build `55`). Build `48` is retained as predecessor upload evidence,
build `49` as superseded `0.5.5` upload evidence, and build `50` as a
simulator-only failed candidate that was not uploaded. Build `51` was pushed,
but its validation was canceled. Build `52` passed exact CI, but a live capture
reproduced a second bounded startup connection loss. Build `53` passed exact
CI, but its live capture exhausted two same-connection recovery attempts.
Build `54` was superseded before live capture when deeper runtime analysis
identified the missing pre-currentness admission gate. Builds `50`–`54` were
not uploaded.

## App record

- Platform: iOS
- Name: `HNS DANE Browser`
- Primary language: English (U.S.)
- Bundle ID: `com.denuoweb.hnsdane.ios`
- SKU: `hns-dane-browser-ios`
- Apple Team ID: `45NQQK3G3S`
- User access: Full Access
- Version: `0.5.5`
- Build: `55`
- Primary category: Utilities
- Price: Free

The app remains iPhone-only. Native iPad support can be enabled in a later
version after adding iPad screenshots and validation coverage.

## Update fields

Paste these public fields from `en-US/` into the `0.5.5` version record:

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

Store approved iPhone screenshots in `../screenshots/en-US/`, numbered in display
order (`01-...`, `02-...`, and so on). Use one exact approved 6.9-inch or 6.5-inch
resolution for the set and do not include an alpha channel.

Run the deterministic package checks before entering metadata, and run the full
check again after screenshots are added:

```sh
python3 dist/app-store/validate.py --metadata-only
python3 dist/app-store/validate.py
```

## Before submission

1. Publish the current cross-platform privacy policy at the URL in `en-US/privacy-policy-url.txt`.
2. Complete App Store Connect's app-privacy, age-rating, content-rights, and export-compliance questionnaires from the app's actual behavior. Do not answer the encryption question by assumption: the Rust runtime implements industry-standard TLS, DNSSEC, and DANE cryptography outside Apple's operating-system crypto APIs.
3. Generate current iPhone simulator screenshots from the iOS shell. Do not reuse Android screenshots.
4. No TestFlight distribution is part of this release. A separately installed
   signed build may be used for a future physical-device matrix; until that is
   recorded, installed-iOS and ecosystem qualification remain open.
5. Supply App Review with the notes in `en-US/review-notes.txt`; no login is required.
6. Complete every item in `../submission-checklist.md`, select build `55`, and choose manual release before adding the version for review.

The API private key used by CI must exist only in the protected GitHub `app-store` environment and must never be committed or uploaded as a workflow artifact.
