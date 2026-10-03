# Release readiness

The configured Android application is `1.0.14`, version code `66`. The iOS
application is `1.0.14`, build `77`. The shared Rust runtime is `1.0.2`.
Platform releases and Rust package releases have independent versions.

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
- Reconcile privacy, financial-feature declarations, review notes, routing,
  release mode, and screenshot inventory with the live store configuration.
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
