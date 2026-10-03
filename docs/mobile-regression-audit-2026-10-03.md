# Mobile wallet and browser regression audit — 2026-10-03

The audit covered the recent Android/iOS synchronization and ShakeScape changes,
wallet/browser transport sharing, scan continuation, authenticated saved offers,
protected lifecycle callbacks, native approval boundaries, release automation,
and the application’s published ecosystem dependency graph.

## Findings and fixes

- The shared browser transport refilled its peer reserve sequentially. A stalled
  handshake delayed the next candidate and accumulated connection deadlines on
  the browser synchronization path. The HNS wallet patch now starts the bounded
  candidate batch concurrently. Duplicate/limit results from a simultaneous
  wallet refill do not quarantine healthy candidates.
- A real-socket regression test holds both peer handshakes until both TCP
  connections have started. The original implementation fails with “one stalled
  handshake blocked the next peer”; the fixed implementation passes.
- Idle peer maintenance, authenticated scan resume, and saved-offer compatibility
  were already implemented in source but absent from the published wallet cohort.
  Those fixes now ship as `hns-wallet-hns` and `hns-wallet-market` 0.4.2.
- The mobile release graph previously selected fourteen wallet packages through
  Git overrides. It now uses published registry sources, exact versions, and
  [reviewed archive checksums](../rust/wallet-crates.sha256). The verifier rejects
  overrides, Git sources, local wallet paths, changed versions/checksums, missing
  packages, and unexpected wallet packages.

## Publication evidence

All 48 selected ecosystem dependencies were checked against crates.io and their
production source files compared with checksum-verified published archives.
Only the HNS wallet and marketplace packages had unpublished source changes.
The remaining package versions were retained.

Both 0.4.2 archives were downloaded after publication and checked for their
registry digest, non-yanked status, clean VCS metadata, package path, and source
commit `5789a88caeb6b0410e0f472bedd1047a01cf9edc`. Package-specific tags identify
that same source. The mobile lockfile has no Git packages. Updated notices bind
its manifest and lockfile digests.

## Validation and release preparation

- The wallet workspace gate passed 544 runtime tests with three pre-existing
  ignored cases, strict lint, documentation checks, normalized package inventory,
  BasicSwap adapter checks, and deterministic Ethereum contract checks.
- The final changed-package pass on Rust 1.89.0 passed 177 HNS wallet and 28
  marketplace tests, including the new reserve test, with strict Clippy warnings.
- The mobile Python policy/release suite passed 113 tests after registry handoff.
  Localization, runtime boundaries, version consistency, notices, supply-chain
  checks, and App Store metadata validation also passed.
- The pre-audit mobile commit `239aa0dd23e93a8d1912dd82a0c45dfcc362bea2`
  passed all six required CI jobs, including Android native instrumentation and
  the full Apple gate. Final candidate qualification must use its own exact
  source CI run and artifact provenance, not that earlier result.
- Candidate identity is Android 1.0.14/code 66 and iOS 1.0.14/build 77. The
  embedded private Rust runtime retains its independent 1.0.2 version.
- The Apple preparation workflow can sign/export an IPA in archive-only mode.
  Shell execution tests verify that this mode cannot invoke the final store
  upload and that stale source fails before upload in either mode.

## Qualification limits

This closes the identified source regressions and the missing registry handoff.
It is not a claim that every possible installed-device regression has been
eliminated. No Android device was connected during this audit, and no new
physical iPhone/iPad pass is claimed. Existing diagnostic files were read only;
no device logs or application data were cleared.

Use the final candidate’s Rust, Android, and Apple gate results and signed
artifact provenance for build qualification. Installed-device lifecycle,
network recovery, approved value actions, and bilateral swap recovery remain
necessary before claiming device qualification. Store upload, screenshot/listing
reconciliation, declarations, and review submission follow the existing release
procedures and are separate from preparing signed artifacts.
