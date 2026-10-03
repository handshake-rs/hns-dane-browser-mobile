# Current application release

This file describes the current application candidate.

## 1.0.14 - 2026-10-03

Android `1.0.14` / code `66` and iOS `1.0.14` / build `77` consume the
selected wallet package versions in [the dependency guide](docs/released-dependency-cohort.md).

- Reuse live wallet peer sessions for browser headers while independently
  validating and atomically publishing the browser chain.
- Negotiate missing browser reserve connections concurrently so stalled peers
  do not impose serial connection delays.
- Maintain idle peers and resume authenticated wallet scans after reopen.
- Read authenticated offers saved before the ShakeScape offer refactor.
- Use checksum-verified published wallet patches for reproducible release builds.
- Keep native review, chain verification, and protected lifecycle requirements
  for HNS, Bitcoin, name actions, and swap recovery.
