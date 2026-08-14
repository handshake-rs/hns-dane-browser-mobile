# App Store submission checklist

Candidate values: iOS version `0.5.10`, build `60`, bundle ID
`com.denuoweb.hnsdane.ios`, iPhone only, Free, manual release. The public
baseline remains `0.5.5` / build `57`; do not copy its wallet-free answers,
screenshots, or submission state into this candidate.

The candidate includes native lifecycle controls for one device-local HNS
account identity plus strict HNWR-v2 read-only fields for balance, distinct HNS
payment and name-transfer receive targets, history, tracked names, and module
status. The product installs no scoped
loopback credential or indexed backend, so those fields remain unavailable. The
app provisions no indexed/authenticated backend; fresh restore needs a durable
archive-capable raw-tx source. Exact-text native name import is present but
unavailable without that backend. Transfer/value, website-provider,
settlement, exchange, HNSA/HNSR, and P2P-market paths remain absent or gated.
Retirement queue/lease behavior and stale-completion publication-authority
predicates passed exact Apple app/simulator CI; no end-to-end credentialed
native read in flight ran. iOS read wiring still requires the scoped
credential/backend/data boundary. Every Console answer must describe that exact
boundary.

## Source and build

- [x] Configure source as iOS `0.5.10` / build `60` and embedded Rust `0.5.9`.
- [x] Pass the complete Apple gate for the underlying wallet tranche in exact
  Required CI run `31393998309` at
  `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`.
- [x] Pin wallet `0.1.0` source `2061a27` and `hns-rs 0.3.0` source
  `88ed7c6`; regenerate the lockfile, source policy, and notices.
- [x] Pass HNWR-v2 exact-source Android and complete Apple gates. Code-bearing
  source `986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
  `31807520618`, including Required CI; CodeQL runs `31807519998` and
  `31807520229` also passed.
- [ ] Pass the complete Rust, Android, Apple, and aggregate exact-source gates
  for the `2061a27` HNWI-v1 consumer. Run `31807520618` predates this import
  tranche and does not qualify it.
- [x] Retain historical `0.5.8` Required CI for source `f21bee1` in run
  `31402758394`; docs commit `ce9c09a` passed the full manual matrix in run
  `31411048376`. These do not qualify this candidate.
- [x] Retain historical `0.5.9` HNWR evidence for source
  `893ba8271787f1ab7247fa78ed8787462b5542fc`. Run `31433931682` passed its
  Rust/Android/Apple gates, including iOS retirement queue/lease and
  stale-completion publication-authority coverage; it does not qualify the
  current candidate or an end-to-end credentialed read in flight.
- [x] Retain historical debug APK artifact `9080493058`: 65,680,703 bytes,
  SHA-256
  `7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`,
  package `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`, minimum API
  30, target API 37,
  `arm64-v8a` + `x86_64`, and one default Android Debug RSA-2048 APK-v2 signer.
  This is not a store-signing result.
- [x] Retain scoped Android installed-device evidence for that code `50`
  artifact.
  On a Pixel 9 (`tokay`), Android 17 / API 37, the incompatible historical code
  `49` debug update safely failed, then the authorized debug-package-only
  reinstall left production untouched. The on-device digest matched, cold
  launch succeeded, and the native wallet activity showed the no-wallet and
  fail-closed UI. No wallet was created/restored and no credentialed read or
  value action ran. The separate physical-iPhone matrix remains open.
- [x] Pass Required CI and the complete Apple gate at exact HNWR-v2
  code-bearing source `986accb7d86d220af63187031e629a9ce69d71e5`.
- [x] Retain exact-source debug artifact `9222123624`; its artifact-archive
  SHA-256 is
  `0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c` and it
  expires 2026-08-17. This is debug-only archive provenance, not an APK digest
  or store-signing result.
- [ ] Inspect the extracted exact `0.5.10-debug` / code `51` APK and record its
  size, SHA-256, package, SDK levels, ABIs, and signer.
- [ ] Complete code `51` installed-device qualification before claiming unified
  installed-product qualification.
- [x] Require the protected upload workflow to capture and fully verify fresh
  exact-commit screenshots before reading Apple credentials or uploading an
  IPA; screenshot failure is fatal.
- [x] Require the protected metadata/submission workflow to bind one successful
  exact-commit upload run, reverify its screenshot artifact, apply and read back
  version metadata/build/review details, and require release-specific mutation
  and submission confirmations.
- [ ] Run the protected exact-commit signed upload workflow and retain the IPA
  SHA-256, size, source commit, bundle identity, signing, and processing
  evidence.
- [ ] Confirm build `60` is `VALID`, unexpired, and reports the intended
  encryption declaration before selecting it.

## Public listing and privacy

- [x] Reconcile the version-controlled description, What's New, and review
  notes with the native lifecycle and fail-closed read boundary.
- [x] Update the repository privacy policy and in-app disclosures for the local
  wallet database, device-bound database key, recovery lifecycle, and absence
  of wallet network/provider/value flows.
- [x] Retain deployment/readback evidence for the historical wallet-lifecycle
  hosted policy at source `909dbd1`.
- [x] Deploy version-neutral HNWR-aware privacy source `a5539cb` and read it
  back at the canonical URL; Firebase run `31485234945` passed.
- [ ] Independently read back the app-level name, subtitle, privacy-policy URL,
  App Privacy answers, price, availability, and Routing App Coverage.
- [ ] Review Utilities and all financial-feature/category declarations against
  the exact limited controller; do not describe the app as having no wallet.

## Screenshots

- [ ] Replace the retained `0.5.5` screenshot set with fresh, exact-commit
  `0.5.10` iPhone screenshots showing the visible unavailable read rows.
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
  processed build `60` are attached to the same `0.5.10` submission.
- [ ] Archive exact readback of the app/account-level fields the guarded client
  does not manage.
- [ ] Choose **Manually release this version**, then intentionally add the
  version and build for review.
- [ ] Record whether a separate installed-iPhone matrix was completed. Its
  absence does not block App Store submission but remains an ecosystem
  qualification limitation.
