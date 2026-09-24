# App Store submission checklist

Candidate: iOS `1.0.8`, build `71`, `com.denuoweb.hnsdane.ios`, iPhone/iPad,
compatible Apple-silicon Macs, Free,
automatic release after approval.

## Source and artifact

- [x] Increment every iOS candidate, metadata, test, and workflow version surface to `1.0.8` / build `71` while retaining the independently versioned Android and Rust releases.
- [ ] Read back App Store Connect before replacement and withdraw or supersede
  the attached `1.0.7` build only if its live state requires it.
- [x] Keep the rejected/unapproved `com.apple.developer.web-browser` capability out of this candidate while registering `http`/`https` and directly handling incoming targets for the renewed request.
- [x] Keep `com.apple.developer.browser.app-installation` absent until Apple approves it; exact `marketplace-kit` navigation now remains in WebKit for MarketplaceKit validation.
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

- [ ] Replace the retained historical screenshots with exact-commit iPhone and iPad captures of the current UI.
- [ ] Show the native wallet entry without any recovery phrase, account identifier, address, balance, or transaction identifier.
- [ ] Use accepted 6.5-inch iPhone and 13-inch iPad resolutions with no alpha channel.
- [ ] Run `python3 store-assets/app-store/validate.py --expected-commit SHA` successfully.

## Review and release

- [ ] Paste the reviewed metadata and review notes and provide a real review contact.
- [ ] Read back metadata, questionnaire answers, screenshots, review details, version, and build relationship.
- [ ] Confirm **Make this app available on Mac** remains enabled in App Store Connect.
- [ ] Attach everything to the same versioned commit and verify automatic release after approval.
- [ ] Intentionally submit only after all gates above pass and archive the final readback.
