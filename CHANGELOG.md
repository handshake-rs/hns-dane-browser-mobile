# Current application release

This file describes the current application candidate.

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
