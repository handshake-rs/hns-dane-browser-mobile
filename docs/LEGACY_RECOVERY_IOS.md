# iOS legacy wallet recovery

This recovery variant uses the Shakescape 1.0.7 source and its locked
`hns-wallet-mobile` 0.2.4 dependency, which restores the original
pre-BIP-44 HNS derivation. Its intended bundle ID is
`com.denuoweb.hnsdane.ios.legacyrecovery`, separate from the current iOS app.
The wallet screen presents restoration instead of creation, and the derived
Info.plist labels the app **Shakescape Legacy Recovery** without claiming the
HTTP, HTTPS, or Handshake URL schemes.

The source has an unsigned macOS/Xcode build check:

```sh
./scripts/build-ios-legacy-recovery.sh
```

The complete iOS CI gate also builds this variant against the pinned Rust
XCFramework. An unsigned `.app` or an IPA made by simply zipping it is **not**
installable on an ordinary iPhone.

## Install with your own Xcode signing

On a Mac with Xcode, Rust 1.98.1 and its Apple targets, sign in to your own
Apple account in Xcode and connect your iPhone with Developer Mode enabled.
From the recovery source checkout, run:

```sh
rustup toolchain install 1.98.1 --profile minimal --component llvm-tools-preview
rustup target add --toolchain 1.98.1 aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
xcrun devicectl list devices
HNS_IOS_TEAM_ID=YOUR_TEAM_ID \
HNS_IOS_LEGACY_BUNDLE_ID=com.yourname.shakescape.legacyrecovery \
HNS_IOS_DEVICE_ID=YOUR_DEVICE_ID \
  ./scripts/install-ios-legacy-recovery.sh
```

Use your own unique bundle ID ending in `.legacyrecovery`; the maintainer's
registered bundle ID cannot be reused for self-signing. The script builds the
pinned Rust runtime, generates the recovery Info.plist, asks Xcode to manage
signing under **your** Apple account, verifies the signed app's recovery
identity, and installs it on **your** connected device. Your Apple ID, device
identifier, certificate, and wallet seed stay with you. The recovery-only UI
is selected by the signed app's `HNSLegacyRecoveryBuild` marker, not by a
hardcoded maintainer bundle ID.

## Distribution prerequisite

The existing `com.denuoweb.hnsdane.ios` App Store provisioning profile cannot
sign this second bundle ID. `scripts/provision-ios-legacy-recovery-appstore.py`
registers or reuses the explicit recovery App ID and an App Store distribution
profile for the existing Apple Distribution certificate. It does **not** create
an App Store app record, upload a build, or register users' devices.
`scripts/export-ios-legacy-recovery-appstore.sh` validates that profile,
archives the isolated recovery app, and exports an archival IPA under
`build/ios-legacy-recovery/` without uploading it to Apple.

An App Store-signed IPA attached to a GitHub release is **not directly
installable from GitHub**. It is optional archival evidence, not the recovery
installation path. No tester email addresses or device IDs need to be sent to
the maintainer, and no separate public App Store listing is required.

Keep the current app and its data intact while verifying the recovery app's old
addresses and assets. The two apps use separate private containers and default
Keychain access groups. A phrase does not necessarily reconstruct local
Bitcoin birthday, swap-session, or name-tracking metadata; settle or record
active obligations before retiring the old wallet. Move HNS and names with
reviewed on-chain transactions, then confirm any required FINALIZE and the new
wallet's synchronized balance.
