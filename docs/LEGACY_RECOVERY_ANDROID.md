# Android legacy wallet recovery

This isolated recovery build is based on Shakescape 1.0.7 and its locked
`hns-wallet-mobile` 0.2.4 dependency. That wallet version restores the original
pre-BIP-44 Shakescape HNS derivation. Current Shakescape uses the standard
account-zero Handshake derivation; the same recovery phrase therefore produces
different HNS addresses in the two apps.

The `legacyRecovery` Android variant has application ID
`com.denuoweb.hnsdane.legacyrecovery`, a distinct app label, and a non-debuggable
release build type. It can be installed alongside current Shakescape but has
separate private storage and Android Keystore state. Its wallet creation action
is disabled; it is for restoring existing old-format wallets only. Do not
publish an ordinary debuggable APK for real-value recovery.

## Recovery sequence

1. Keep the existing Shakescape installation and its data intact until this
   recovery build has been installed and tested. Never send the recovery words
   to a developer or support person.
2. Install the signed recovery APK from its dedicated GitHub prerelease. Restore
   the original 24 words on the device with the earliest relevant Handshake
   birthday height, or zero if uncertain. The variant does not import or read
   the current app's encrypted database.
3. Synchronize and compare its receive addresses, HNS balance, names, and
   relevant history with previously known records. If they do not match, stop.
   A phrase alone may not reconstruct every local Bitcoin birthday, pending
   swap, or name-tracking record; record that metadata and finish or refund any
   active obligation before retiring the old wallet.
4. Prepare a current-format wallet in the normal Shakescape app, preferably on
   another device. On a single device, clear the normal app's old wallet only
   after step 3 has proved that the separate recovery app controls the old
   assets and the Bitcoin recovery state is understood. Use the normal app's
   wallet deletion flow when available instead of Android's blanket Clear
   Storage action.
5. Review and approve the HNS and name transfers from the recovery app to the
   current wallet. Verify confirmation, name FINALIZE or other required
   follow-up, and the new wallet's synchronized balance before removing the
   recovery app.

This is an on-chain transfer procedure, not an in-place change to the keys that
control existing outputs. The mainline application and published Rust crates
are not changed by this recovery variant.

## Signed artifact

The GitHub prerelease for this branch contains
`Shakescape-1.0.7-legacy-recovery.apk`. It is not a Google Play update and is
not signed with the normal app's signing key.

- APK SHA-256: `ccbe6a24a5229e923ac55bb23d36f18cb1608f039dea6e0880e3b0b09ed2ef9a`
- Signing certificate SHA-256: `234d1c19ac9eba289fc41800cda36beb3a6801bb7e72facefaacc388f317ee8f`
- Application ID: `com.denuoweb.hnsdane.legacyrecovery`
- Version: `1.0.7-legacy-recovery` (Android version code 59)
