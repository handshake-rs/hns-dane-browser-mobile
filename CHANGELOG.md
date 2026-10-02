# Current application release

This file describes the current application candidate.

## 1.0.13 - 2026-10-01

Android `1.0.13` / code `65` and iOS `1.0.13` / build `76` consume the
selected wallet package versions in [the dependency guide](docs/released-dependency-cohort.md).

- Let Wallet foreground sync, name import, and peer maintenance proceed after
  the two-peer verification quorum connects instead of waiting for a larger
  reserve pool. Limit the mobile pool to four connected peers.
- Keep the HNS header and wallet scan verification rules unchanged while
  reducing the time spent connecting peers before user-visible work.


- Reuse the wallet's live HNS peer sessions for browser header requests on both
  platforms while retaining independent browser validation.
- Maintain idle peers and resume authenticated wallet scans after reopen.
- Preserve authenticated signed-offer loading through the selected market patch.
