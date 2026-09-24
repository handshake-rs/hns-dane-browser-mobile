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
installable on an ordinary iPhone and must not be published as a recovery
download.

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
installable from GitHub**. Users who need to run the recovery variant without
an App Store listing must build/re-sign it for their own device with their own
Apple account and Xcode tooling. The release should link this source and state
that limitation plainly; it should not ask for email addresses or UDIDs.

Keep the current app and its data intact while verifying the recovery app's old
addresses and assets. The two apps use separate private containers and default
Keychain access groups. A phrase does not necessarily reconstruct local
Bitcoin birthday, swap-session, or name-tracking metadata; settle or record
active obligations before retiring the old wallet. Move HNS and names with
reviewed on-chain transactions, then confirm any required FINALIZE and the new
wallet's synchronized balance.
