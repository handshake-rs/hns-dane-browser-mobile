# Current application release

This file describes the current application candidate.

## 1.0.15 - 2026-10-03

Android `1.0.15` / code `67` and iOS `1.0.15` / build `78` consume
published `hns-wallet-hns` 0.4.3, as recorded in
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
