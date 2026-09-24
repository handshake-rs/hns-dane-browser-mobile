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
sign this second bundle ID. The Apple team must register the explicit recovery
App ID and provide a matching distribution profile. For direct installation
outside the App Store, an Ad Hoc profile must include the intended device IDs;
an App Store-signed IPA attached to GitHub is not a universal sideload package.
Alternatively, a separate App Store Connect app record and TestFlight build
could distribute this variant through Apple. No recovery IPA should be labeled
ready until it is signed for its own bundle ID and installed on a test iPhone.

Keep the current app and its data intact while verifying the recovery app's old
addresses and assets. The two apps use separate private containers and default
Keychain access groups. A phrase does not necessarily reconstruct local
Bitcoin birthday, swap-session, or name-tracking metadata; settle or record
active obligations before retiring the old wallet. Move HNS and names with
reviewed on-chain transactions, then confirm any required FINALIZE and the new
wallet's synchronized balance.
