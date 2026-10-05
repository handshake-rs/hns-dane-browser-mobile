# App Store submission checklist

Candidate: iOS `1.0.16`, build `78`, `com.denuoweb.hnsdane.ios`, iPhone/iPad
and compatible Apple-silicon Macs. Read the configured release mode and live
App Store Connect state before submission.

## Source and artifact

- [ ] Verify every candidate version/build surface with the source validator.
- [ ] Qualify the exact source with the Rust, Android, Apple, and security gates.
- [ ] Build and verify the signed IPA's digest, identity, signing, encryption
  declaration, and processing state against that source.
- [ ] Verify the selected App Store Connect build is `VALID` and unexpired.
- [ ] Verify that only approved entitlements are present in the signed artifact.

## Listing and privacy

- [ ] Describe native HNS/Bitcoin synchronization, receive/QR, approved sends,
  names, Shakescape offers, swap recovery, and protected wallet deletion.
- [ ] State that websites cannot access wallet authority or secrets.
- [ ] Use `https://shakescape.com/` for product/support and
  `https://shakescape.com/privacy/` for privacy.
- [ ] Explain user-initiated camera QR processing on device.
- [x] Reuse the owner’s standing confirmation of the account declarations and
  store configuration recorded on October 3, 2026 in the release guide.

## Screenshots

- [ ] Capture and validate current iPhone and iPad screenshots from that source.
- [ ] Show wallet onboarding without a recovery phrase, account identifier,
  address, balance, or transaction identifier.
- [ ] Validate accepted dimensions and opaque images.
- [ ] Run `python3 store-assets/app-store/validate.py --expected-commit SHA`.

## Review and release

- [ ] Provide reviewed metadata, review notes, and complete private contact details.
- [ ] Read back screenshots, metadata, declarations, version, and selected build.
- [ ] Verify supported device families and the intended release mode.
- [ ] Submit only after all required gates pass and submission is authorized.
- [ ] Read back the resulting submission state before reporting delivery.
