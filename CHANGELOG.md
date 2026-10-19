# Current application release

This file describes the current application candidate.

## 1.0.20 - 2026-10-19

Android `1.0.20` / code `72` and iOS `1.0.20` / build `83` include the patched
Rust TLS dependency. The iOS build fixes wallet creation and restoration in an
app container with system-managed parent directories. Android removes unused
KTX artifacts, and its store screenshot captures now read the version from
Release APK build metadata. The published Bitcoin transport 0.4.3 bounds
cached filter replay to wallet consumption, reduces repeated progress recounts,
and retires peers when their message channels close.

## 1.0.19 - 2026-10-12

Android `1.0.19` / code `71` resumes direct HNS wallet scanning after each
saved bounded checkpoint. The scheduled continuation now consumes its retry
flag before the normal operation gate checks for another active sync. iOS
remains at `1.0.18` / build `81`; its catch-up path already clears that flag
before starting the next round.

## 1.0.18 - 2026-10-12

Android `1.0.18` / code `70` and iOS `1.0.18` / build `81` use the
published `hns-browser-loopback-proxy` 0.2.4 dependency.

- Open permanently reserved ICANN websites through the authenticated iOS
  loopback proxy while Handshake headers synchronize. HTTPS remains an opaque
  CONNECT tunnel, with WebKit verifying the origin certificate using WebPKI.
- Keep Handshake destinations behind verified header authority. Rotate the iOS
  proxy to its normal dual-root validation after headers become authoritative,
  and show the active validation path in browser security status.
- Retain the 1.0.17 wallet and swap-recovery behavior on both platforms.

## 1.0.17 - 2026-10-07

Android `1.0.17` / code `69` and iOS `1.0.17` / build `80` use the
registry-qualified seed-only swap recovery cohort described in
[the dependency guide](docs/released-dependency-cohort.md).

- Commit complete public recovery terms in ordinary transaction ancestors before
  funding each BTC/HNS swap contract; include all publication fees in approval.
- Rediscover newly funded contracts after restoring the wallet seed, without
  original offer records, a recovery export or counterparty cooperation.
- Restore exact reclaim actions for both assets, both participants and both
  offer directions; require verified unspent evidence and refund maturity.
- Retain separate verified settlement observations for each chain, revoke stale
  evidence after reorganization, and resume a taker's remaining claim once its
  funded leg reveals the secret on-chain.
- Preserve the peer reconnect and durable Bitcoin synchronization improvements.
- Rotate iOS through the same bounded reserve-peer retries as Android when a
  completed two-peer header round cannot agree.
- Keep a user-selected swap peer eligible for automatic reconnect even when
  discovered peers fill the eight-connection pool; make the selected peer the
  request/reply primary and retain the previous primary as redundancy when the
  pool has room.
- Pin the published HNS 0.4.5, Bitcoin 0.4.4, and mobile-wallet 0.5.2 crate
  cohort with the recovery-publication funding deadline fix.
