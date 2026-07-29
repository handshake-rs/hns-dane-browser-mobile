# App Store submission checklist

Canonical update-candidate values: iOS version `0.5.5`, build `49`, bundle ID
`com.denuoweb.hnsdane.ios`, iPhone only, Utilities, Free, manual release. The
public baseline observed on 2026-07-28 is version `0.5.0`.

## Public listing

- [ ] The support and marketing page describes the iOS release and visibly lists
  `info@denuoweb.com` for support.
- [ ] The live privacy URL contains the current cross-platform policy and does not
  direct users to post personal information in a public issue.
- [ ] The app's in-app Privacy Policy and Source Code actions open those live pages.
- [ ] Paste the update fields listed in `metadata/README.md`, including
  `metadata/en-US/whats-new.txt` under **What's New in This Version**.
- [ ] Set the version to `0.5.5`, select build `49`, and leave Routing App Coverage
  empty.

## Screenshots

- [ ] Add one to ten real iPhone screenshots to `screenshots/en-US/`; three is the
  recommended first set.
- [ ] Use one accepted 6.9-inch or 6.5-inch resolution throughout, with no alpha
  channel or transparency.
- [ ] Show: ordinary WebPKI browsing, a developer-controlled Handshake page with
  its security path, and Proof Details or Browser Settings. Do not use a splash or
  empty start screen.
- [ ] Run `python3 dist/app-store/validate.py` successfully.

## App Store questionnaires

- [ ] **App Privacy:** No tracking. Select **No, we do not collect data** only after
  confirming that Denuo Web and any non-open-web service treated as a partner do
  not retain app-originated data. On-device storage and user-directed open-web
  traffic are not collection under Apple's definitions. If a bundled resolver
  retains query/IP logs, disclose the applicable Browsing History data instead.
- [ ] **Age Rating:** Unrestricted Web Access = Yes. The app itself has no ads,
  chat, social feed, gambling, loot boxes, parental controls, or age assurance;
  answer the content-frequency questions accordingly. Do not select Made for Kids.
- [ ] **Content Rights:** Third-party content = Yes. Confirm it is accessed by a
  user-directed browser and is not bundled or curated by the app.
- [ ] **App Access:** Sign-in required = No. There is no account, subscription,
  in-app purchase, wallet, or payment flow.
- [ ] **Export Compliance:** Uses encryption = Yes; limited to Apple OS encryption
  = No; proprietary or non-standard encryption = No; industry-standard encryption
  outside the OS = Yes. Complete any documentation App Store Connect requests for
  the selected storefronts, including France when applicable.
- [ ] **EU DSA:** Declare the correct trader status. If distributing in the EU,
  verify the organization phone number and email requested by Apple.

## Review and release

- [ ] Enter a real review contact name, phone number, and email address.
- [ ] Paste `metadata/en-US/review-notes.txt`; do not enable the sign-in fields.
- [ ] Confirm build `49` has finished processing and its export-compliance status is
  resolved.
- [ ] Confirm the metadata, privacy answers, age rating, content rights, pricing,
  availability, screenshots, and build are all attached to the same submission.
- [ ] Choose **Manually release this version**, save, then add the app version and
  build for review.
- [ ] Record whether an external-TestFlight real-iPhone qualification pass was
  available. If none was available, record that limitation; it does not block
  App Store submission, but installed-iOS and ecosystem qualification remain
  open.
