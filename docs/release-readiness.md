# Release readiness

The configured Android application is `1.0.19`, version code `71`. The iOS
application is `1.0.18`, build `81`. The shared Rust runtime is `1.0.2`.
Platform releases and Rust package releases have independent versions.

Version 1.0.16 has been delivered to Google Play production and submitted to
App Store Connect. See [the verified delivery record](1.0.16-store-delivery.md)
for source, build, qualification, and live submission evidence.

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

## Android HNS wallet continuation

Version 1.0.19 lets a scheduled direct HNS wallet catch-up consume its own
pending retry before checking whether another wallet operation is active.
The next bounded scan round therefore starts automatically after a saved
checkpoint. The iOS catch-up path already makes the equivalent transition.

## ICANN startup browsing

Version 1.0.18 updates the published mobile proxy dependency to 0.2.4.
The iOS browser admits permanently reserved ICANN roots through its
authenticated loopback proxy while Handshake headers synchronize. HTTPS uses
opaque CONNECT and WebKit WebPKI in this period. Handshake names remain gated
on verified headers; the browser rotates to its dual-root proxy once they are
authoritative. The normal Android browser route remains unchanged.

## Seed-only swap recovery

Version 1.0.17 uses the published wallet recovery cohort described in
[the dependency guide](released-dependency-cohort.md). Newly funded swap
contracts commit to chain-visible public recovery terms. Restoring the seed
reconstructs the exact contract and reclaim authority for both assets, both
participants and both offer directions. Native signing still requires current
verified evidence, an unspent contract and consensus maturity. Recovery has
been qualified with funded synthetic transactions and fresh wallet profiles.

The earlier statement that the owner accepted incomplete recovery was incorrect
and has been removed. No legacy derivation adapter or recovery-file workflow
is included.

## Required before delivery

- Qualify the exact source commit with the portable checks, strict Rust lint,
  Android build/unit/instrumentation gates, and complete Apple gate.
- Verify the published wallet patches described in
  [wallet-sync-candidates.md](wallet-sync-candidates.md). Preserve unchanged
  package versions and verify the reviewed registry checksums.
- Build signed platform artifacts from the qualified source. Verify package or
  bundle identity, version/build, signing identity, ABI contents, and symbols.
- Preserve existing store screenshot sets by default. If replacing them, capture
  screenshots from the qualified source and validate manifests and digests.
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
