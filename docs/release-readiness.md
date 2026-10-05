# Release readiness

The configured Android application is `1.0.15`, version code `67`. The iOS
application is `1.0.15`, build `78`. The shared Rust runtime is `1.0.2`.
Platform releases and Rust package releases have independent versions.

## Standing release instructions

The owner confirmed on 2026-10-03 that the live App Store Connect and Google
Play declarations are accurate for the native HNS/BTC wallet, name actions,
swaps, and browser. This covers App Privacy/Data safety, financial features,
age/content ratings, export compliance, and trader/account details, together
with the existing store configuration.

Use this standing confirmation for releases. Do not repeat declaration-accuracy
audits or request another account-readiness attestation as a release prerequisite.
Pass `confirm_account_readiness=true` to the iOS submission workflow using this
recorded confirmation. Preserve the confirmed answers unless the owner requests
an update. Continue verifying the actual build, listing, screenshots, and
submission state through store readback.

Commit release work directly to `main`. Sign iOS only for an actual App Store
delivery; routine commits do not require a signed IPA or a GitHub Release.

## Current release hold: swap seed recovery

Do not submit the current candidate until restoring only the wallet seed also
rediscovers funded swap contracts and permits a verified refund on both chains.
Users must not be required to download or confirm a separate recovery file.
The wallet repository's unpublished 0.5.0 candidate removes private offer
identifiers and device-local profile IDs from key derivation. Automatic recovery
of public contract terms and native end-to-end refund qualification remain
outstanding. The installed app and registry dependencies remain on the prior
published runtime. This hold does not change the standing declaration confirmation.

## Required before delivery

- Qualify the exact source commit with the portable checks, strict Rust lint,
  Android build/unit/instrumentation gates, and complete Apple gate.
- Verify the published wallet patches described in
  [wallet-sync-candidates.md](wallet-sync-candidates.md). Preserve unchanged
  package versions and verify the reviewed registry checksums.
- Build signed platform artifacts from the qualified source. Verify package or
  bundle identity, version/build, signing identity, ABI contents, and symbols.
- Capture current screenshots from that same source and validate image
  manifests and digests before upload.
- Exercise wallet open, scan resume, peer recovery, protected lifecycle,
  approved sends, name actions, and bilateral swap recovery on installed builds.
- Use the standing account-declaration confirmation above. Verify review notes,
  release mode, and screenshot inventory against the intended release.
- Re-read store state after upload or submission before reporting delivery.

## Product boundaries

The native wallet exposes direct HNS and Bitcoin synchronization, protected
recovery, receive/QR, guarded sends, names, signed offers, and noncustodial
BTC/HNS swaps. Every value action requires its exact verified evidence and
native approval. Website-provider wallet/value exposure and HNSA/HNSR service
roles remain unavailable. A host test or simulator does not establish
installed-device behavior or store acceptance.

Use [Google Play readiness](play-store-readiness.md),
[App Store release](ios-app-store-release.md), and
[iOS device validation](ios-device-validation.md) for the platform procedures.
