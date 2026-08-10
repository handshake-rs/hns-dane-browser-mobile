# App Store submission checklist

Candidate values: iOS version `0.5.9`, build `59`, bundle ID
`com.denuoweb.hnsdane.ios`, iPhone only, Free, manual release. The public
baseline remains `0.5.5` / build `57`; do not copy its wallet-free answers,
screenshots, or submission state into this candidate.

The candidate includes native lifecycle controls for one device-local HNS
account identity plus strict HNWR-v1 read-only fields for balance, receive
target, history, tracked names, and module status. The product installs no scoped
loopback credential or indexed backend, so those fields remain unavailable. The
available live pruned node lacks wallet index/auth; fresh restore needs a durable
raw-tx source. Name import is absent. Transfer/value, website-provider,
settlement, exchange, HNSA/HNSR, and P2P-market paths remain absent or gated.
iOS read wiring also requires nonblocking lifecycle-teardown
qualification. Every Console answer must describe that exact boundary.

## Source and build

- [x] Configure source as iOS `0.5.9` / build `59` and embedded Rust `0.5.9`.
- [x] Pass the complete Apple gate for the underlying wallet tranche in exact
  Required CI run `31393998309` at
  `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
- [x] Pin final wallet `0.1.0` source `2229be8` and final `hns-rs 0.2.0`
  source `b24b66c`; regenerate the lockfile, source policy, and notices.
- [x] Retain historical `0.5.8` Required CI for source `f21bee1` in run
  `31402758394`; docs commit `ce9c09a` passed the full manual matrix in run
  `31411048376`. These do not qualify this candidate.
- [ ] Pass candidate Required CI, including the HNWR Rust/Android/Apple gates and
  nonblocking iOS read/lifecycle teardown qualification before enabling reads.
- [x] Require the protected upload workflow to capture and fully verify fresh
  exact-commit screenshots before reading Apple credentials or uploading an
  IPA; screenshot failure is fatal.
- [ ] Run the protected exact-commit signed upload workflow and retain the IPA
  SHA-256, size, source commit, bundle identity, signing, and processing
  evidence.
- [ ] Confirm build `59` is `VALID`, unexpired, and reports the intended
  encryption declaration before selecting it.

## Public listing and privacy

- [x] Reconcile the version-controlled description, What's New, and review
  notes with the native lifecycle and fail-closed read boundary.
- [x] Update the repository privacy policy and in-app disclosures for the local
  wallet database, device-bound database key, recovery lifecycle, and absence
  of wallet network/provider/value flows.
- [x] Retain deployment/readback evidence for the historical wallet-lifecycle
  hosted policy at source `909dbd1`.
- [ ] Deploy and read back the repository's HNWR-aware privacy-policy revision at
  the canonical URL.
- [ ] Independently read back the app-level name, subtitle, privacy-policy URL,
  App Privacy answers, price, availability, and Routing App Coverage.
- [ ] Review Utilities and all financial-feature/category declarations against
  the exact limited controller; do not describe the app as having no wallet.

## Screenshots

- [ ] Replace the retained `0.5.5` screenshot set with fresh, exact-commit
  `0.5.9` iPhone screenshots showing the visible unavailable read rows.
- [ ] Confirm provenance schema 3 records
  `settings.wallet.native-controls`, and visually verify that native wallet
  entry without displaying a recovery phrase, account identifier, database
  material, or other secret.
- [ ] Use one accepted 6.9-inch or 6.5-inch resolution with no alpha channel.
- [ ] Run `python3 store-assets/app-store/validate.py --expected-commit SHA`
  successfully with the manifest commit equal to the upload candidate.

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
  local wallet controls and unavailable read fields in the review notes.
- [ ] **Export Compliance:** answer from the exact use of industry-standard
  Rust TLS, DNSSEC, DANE, and wallet cryptography; complete any storefront
  documentation Apple requests.
- [ ] **EU DSA:** declare and verify the current trader status and contact data.

## Review and release

- [ ] Enter a real review contact name, phone number, and email address.
- [ ] Paste `metadata/en-US/review-notes.txt`; leave sign-in fields disabled.
- [ ] Confirm metadata, questionnaires, fresh screenshots, review details, and
  processed build `59` are attached to the same `0.5.9` submission.
- [ ] Archive exact readback of the app/account-level fields the guarded client
  does not manage.
- [ ] Choose **Manually release this version**, then intentionally add the
  version and build for review.
- [ ] Record whether a separate installed-iPhone matrix was completed. Its
  absence does not block App Store submission but remains an ecosystem
  qualification limitation.
