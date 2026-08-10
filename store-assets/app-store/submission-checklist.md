# App Store submission checklist

Canonical submitted-update values: iOS version `0.5.5`, build `57`, bundle ID
`com.denuoweb.hnsdane.ios`, iPhone only, Utilities, Free, manual release. The
public baseline observed on 2026-07-28 was version `0.5.0`. App Store Connect
accepted the direct App Review submission on 2026-07-29; `WAITING_FOR_REVIEW`
is retained as dated submission evidence. Apple published `0.5.5` on
2026-07-31, and the public record still reported it as current on 2026-08-09.

Every checked “no wallet” answer below is scoped to build `57`. Unreleased
source now has native-only non-value wallet controls, which build `57` does not
contain. Do not reuse this checklist or its metadata for a wallet-bearing build
until final Apple CI, privacy/reviewer metadata, screenshots, and category
answers are reconciled for that exact candidate.

Checked items below have repository, workflow, or App Store Connect API
readback. Unchecked app/account-level items were sufficient for Apple to accept
the submission, but their exact saved answers were not independently exposed by
the guarded release client; they remain evidence follow-ups, not submission
blockers.

## Public listing

- [x] The support and marketing page describes the iOS release and visibly lists
  `info@denuoweb.com` for support.
- [x] The live privacy URL contains the current cross-platform policy and does not
  direct users to post personal information in a public issue.
- [x] The app's in-app Privacy Policy and Source Code actions open those live pages.
- [x] Reconcile the version-managed description, keywords, promotional text,
  support/marketing URLs, copyright, and
  `metadata/en-US/whats-new.txt`.
- [ ] Independently read back the existing app-level name, subtitle, privacy
  policy URL, and Routing App Coverage; the release client does not manage
  those fields.
- [x] Set the version to `0.5.5` and select build `57`.

## Screenshots

- [x] Stage the four verified live iPhone screenshots in
  `screenshots/en-US/`.
- [x] Use one accepted 6.9-inch or 6.5-inch resolution throughout, with no alpha
  channel or transparency.
- [x] Show: ordinary authenticated ICANN browsing, a developer-controlled
  Handshake page with its DANE path, Browser Settings, and Proof Details. Do not
  use a splash or empty start screen.
- [x] Run `python3 store-assets/app-store/validate.py` successfully.

## App Store questionnaires

- [ ] **App Privacy:** No tracking. Select **No, we do not collect data** only after
  confirming that Denuo Web and any non-open-web service treated as a partner do
  not retain app-originated data. On-device storage and user-directed open-web
  traffic are not collection under Apple's definitions. If a bundled resolver
  retains query/IP logs, disclose the applicable Browsing History data instead.
- [ ] **Age Rating:** Unrestricted Web Access = Yes. The app itself has no ads,
  chat, social feed, gambling, loot boxes, parental controls, or age assurance;
  answer the content-frequency questions accordingly. Do not select Made for Kids.
- [x] **Content Rights:** Third-party content = Yes. Confirm it is accessed by a
  user-directed browser and is not bundled or curated by the app.
- [x] **App Access:** Sign-in required = No. There is no account, subscription,
  in-app purchase, wallet, or payment flow.
- [x] Build `57` readback reports `usesNonExemptEncryption=false`.
- [ ] **Export Compliance:** Uses encryption = Yes; limited to Apple OS encryption
  = No; proprietary or non-standard encryption = No; industry-standard encryption
  outside the OS = Yes. Complete any documentation App Store Connect requests for
  the selected storefronts, including France when applicable.
- [ ] **EU DSA:** Declare the correct trader status. If distributing in the EU,
  verify the organization phone number and email requested by Apple.

## Review and release

- [x] Enter a real review contact name, phone number, and email address.
- [x] Paste `metadata/en-US/review-notes.txt`; do not enable the sign-in fields.
- [x] Confirm build `57` has finished processing as `VALID`, is unexpired, and
  reports `usesNonExemptEncryption=false`.
- [x] Confirm the version-managed metadata, review details, content-rights
  declaration, four ordered screenshots, and build are attached to the same
  submission.
- [ ] Independently archive the exact app-level App Privacy, age-rating, DSA,
  pricing, availability, and routing answers; Apple accepted the submission,
  but the guarded client did not read those fields.
- [x] Choose **Manually release this version**, save, then add the app version and
  build for review.
- [x] Record that no TestFlight distribution is part of this release. This does
  not block App Store submission, but installed-iOS and ecosystem qualification
  remain open until a separate physical-device matrix is completed.
