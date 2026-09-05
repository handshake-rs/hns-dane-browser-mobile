# App Store submission checklist

Candidate: iOS `1.0.5`, build `66`, `com.denuoweb.hnsdane.ios`, iPhone, Free,
manual release.

## Source and artifact

- [x] Increment every iOS candidate, metadata, test, and workflow version surface to `1.0.5` / build `66` while retaining the independently versioned Android and Rust releases.
- [x] Keep the pending `com.apple.developer.web-browser` capability out of this candidate and defer default-browser activation to a later version after Apple approval.
- [x] Keep `com.apple.developer.browser.app-installation` absent until MarketplaceKit installation behavior is implemented and separately reviewed.
- [ ] Push the exact candidate and require all repository, Rust, Android, Apple, Required CI, and CodeQL gates to pass.
- [ ] Build and sign the IPA from that exact commit; record its digest, identity, signing, encryption declaration, and processing state.
- [ ] Confirm the selected App Store Connect build is `VALID` and unexpired.

## Listing and privacy

- [x] Describe the native noncustodial HNS wallet, direct peer synchronization, receive/QR, guarded send, and protected deletion.
- [x] State that websites cannot access the wallet; disclose supported name operations and capability-gated native Shakedex and Bitcoin controls.
- [x] Use `https://shakescape.com/` for product/support and `https://shakescape.com/privacy/` for privacy.
- [x] Explain that camera access is user-initiated and QR data is processed on-device.
- [ ] Reconcile App Privacy, unrestricted web access, financial-feature/category, content-rights, export, DSA/trader, price, availability, and routing answers against the exact binary.

## Screenshots

- [ ] Replace the retained historical screenshots with exact-commit iPhone captures of the current UI.
- [ ] Show the native wallet entry without any recovery phrase, account identifier, address, balance, or transaction identifier.
- [ ] Use an accepted iPhone resolution with no alpha channel.
- [ ] Run `python3 store-assets/app-store/validate.py --expected-commit SHA` successfully.

## Review and release

- [ ] Paste the reviewed metadata and review notes and provide a real review contact.
- [ ] Read back metadata, questionnaire answers, screenshots, review details, version, and build relationship.
- [ ] Attach everything to the same versioned commit and choose manual release.
- [ ] Intentionally submit only after all gates above pass and archive the final readback.
