# Current application release

This file describes the current application candidate.

## 1.0.16 - 2026-10-04

Android `1.0.16` / code `68` and iOS `1.0.16` / build `79` consume
published `hns-wallet-hns` 0.4.3 and `hns-wallet-market` /
`hns-wallet-mobile` 0.5.0, as recorded in
[the dependency guide](docs/released-dependency-cohort.md).

- Race eight HNS candidates and proceed as soon as two peers connect while
  remaining handshakes fill the bounded redundancy pool.
- Keep reserve handshakes off wallet and browser synchronization waits.
- Retain discovered swap endpoints for reconnection and avoid duplicate live dials.
- Retry an explicitly selected endpoint after its first connection fails.
- Preserve negotiated swap transports after local offer-inventory failures;
  signed records still require local validation before use.
- Hide peer-dependent swap actions while disconnected.
- Describe unavailable peers accurately without claiming a chain disagreement.

- Persist and reuse verified Bitcoin headers and compact filters across app
  restarts and expanded address scans; reset Android ETA between scan passes.
- Consume the seed-derived swap-authority fix for both assets, both participants
  and both offer directions. Automatic contract discovery after app-data loss
  remains incomplete; retain local swap records until settlement.
