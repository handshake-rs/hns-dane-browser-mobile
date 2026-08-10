# App Store submission checklist

Candidate values: iOS version `0.5.8`, build `58`, bundle ID
`com.denuoweb.hnsdane.ios`, iPhone only, Free, manual release. The public
baseline remains `0.5.5` / build `57`; do not copy its wallet-free answers,
screenshots, or submission state into this candidate.

The candidate includes native create, restore, open, unlock, and lock controls
for one device-local non-value HNS account identity. It has no balance,
transfer, name, website-provider, settlement, exchange, or P2P-marketplace
surface. Every Console answer must describe that exact boundary.

## Source and build

- [x] Configure source as iOS `0.5.8` / build `58` and embedded Rust `0.5.8`.
- [x] Pass the complete Apple gate for the underlying wallet tranche in exact
  Required CI run `31393998309` at
  `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
- [ ] Replace the intermediate wallet/protocol chain after the dated final
  `hns-rs` release: repin wallet, repin mobile to the resulting wallet commit,
  and regenerate the lockfile, source policy, and notices.
- [ ] Pass Required CI and CodeQL at the final exact `0.5.8` commit.
- [ ] Run the protected exact-commit signed upload workflow and retain the IPA
  SHA-256, size, source commit, bundle identity, signing, and processing
  evidence.
- [ ] Confirm build `58` is `VALID`, unexpired, and reports the intended
  encryption declaration before selecting it.

## Public listing and privacy

- [x] Reconcile the version-controlled description, What's New, and review
  notes with the native non-value wallet boundary.
- [x] Update the repository privacy policy and in-app disclosures for the local
  wallet database, device-bound database key, recovery lifecycle, and absence
  of wallet network/provider/value flows.
- [ ] Publish and read back the updated hosted privacy policy at the canonical
  URL before submission.
- [ ] Independently read back the app-level name, subtitle, privacy-policy URL,
  App Privacy answers, price, availability, and Routing App Coverage.
- [ ] Review Utilities and all financial-feature/category declarations against
  the exact limited controller; do not describe the app as having no wallet.

## Screenshots

- [ ] Replace the retained `0.5.5` screenshot set with fresh, exact-commit
  `0.5.8` iPhone screenshots.
- [ ] Include the native wallet entry/control boundary without displaying a
  recovery phrase, account identifier, database material, or other secret.
- [ ] Use one accepted 6.9-inch or 6.5-inch resolution with no alpha channel.
- [ ] Run `python3 store-assets/app-store/validate.py` successfully and verify
  the manifest commit equals the upload candidate.

## App Store questionnaires

- [ ] **App Privacy:** reconcile the on-device wallet database and device-bound
  key under Apple's current definitions. No wallet data is sent to Denuo Web,
  websites, a provider, or a wallet service by this build.
- [ ] **Age Rating:** Unrestricted Web Access = Yes; not Made for Kids. Recheck
  every current content-frequency question.
- [ ] **Content Rights:** Third-party content = Yes for user-directed browsing;
  the app does not bundle or curate that content.
- [ ] **App Access:** Sign-in required = No. There is no developer-operated
  account, subscription, in-app purchase, or payment/value flow. Explain the
  local non-value wallet controls in the review notes.
- [ ] **Export Compliance:** answer from the exact use of industry-standard
  Rust TLS, DNSSEC, DANE, and wallet cryptography; complete any storefront
  documentation Apple requests.
- [ ] **EU DSA:** declare and verify the current trader status and contact data.

## Review and release

- [ ] Enter a real review contact name, phone number, and email address.
- [ ] Paste `metadata/en-US/review-notes.txt`; leave sign-in fields disabled.
- [ ] Confirm metadata, questionnaires, fresh screenshots, review details, and
  processed build `58` are attached to the same `0.5.8` submission.
- [ ] Archive exact readback of the app/account-level fields the guarded client
  does not manage.
- [ ] Choose **Manually release this version**, then intentionally add the
  version and build for review.
- [ ] Record whether a separate installed-iPhone matrix was completed. Its
  absence does not block App Store submission but remains an ecosystem
  qualification limitation.
