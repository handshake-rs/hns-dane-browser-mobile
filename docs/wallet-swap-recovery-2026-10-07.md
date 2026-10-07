# Published seed-only swap recovery cohort

Wallet source: `ce222cba1abb60d6380121482e21ad2c83484f49`, committed and pushed directly
to main. Registry readback verified at 2026-10-07T05:57:18.832154+00:00.

| Package | Published version | Downloaded archive SHA-256 |
| --- | --- | --- |
| `hns-wallet-chain-api` | 0.4.2 | `b9c1b338aa05356c7832290052e0a8cae924c0052d5cd479064ce6a9fa4821a5` |
| `hns-wallet-hns` | 0.4.4 | `e645b6960104a28d3a83bacd41f499206307dd97c881777f6c85ea5cf4028ec5` |
| `hns-wallet-bitcoin-kyoto` | 0.4.3 | `23e5d5d8a2f59e30f3f00f28cca59ce9635aaf1e43d35b9247ada30f405db35d` |
| `hns-wallet-market` | 0.5.1 | `3fd6e41eb953d4206e4714b5d896336beea2b72ffa80ccb75e98d14b21c22b71` |
| `hns-wallet-service` | 0.4.2 | `be3b4a69bb07c24f34cd19bcb37be47cccafc014f120be49d836e3c3c6e099c5` |
| `hns-wallet-mobile` | 0.5.1 | `e2b9cdef817c4135b02287f6cc0023fd05509f4b4ce31825f0b4db1d8bae7f9e` |

Each registry version is non-yanked. The downloaded archive matches the API
checksum, carries the exact clean source commit, and contains the reviewed Rust
source. Unchanged wallet packages retain their existing reviewed versions.
App builds use crates.io requirements and registry lockfile checksums, with no
sibling paths or source overrides.

## Recovery behavior and qualification

Newly funded swap contracts commit to complete public terms through ordinary
transaction ancestors: three on Bitcoin and six on Handshake. Every child
signature binds its exact parent output, and only ordinary seed-owned funds
exist in an interrupted prefix. All publication fees enter the native approval
and maximum fee cap before broadcasting. The signed sequence is durable and
restarts in dependency order.

Restoring the seed discovers its publication anchor in ordinary wallet history,
reconstructs the exact contract and recreates seed-owned settlement authority.
Both participants can reclaim their own funded asset after independently
verified consensus maturity. A restored taker can claim the other leg after
its funded leg reveals the public secret in a verified spend. Per-chain
observations revoke stale settlement state after reorganization. A recovered
record cannot authorize fresh funding. No original offer records, user-managed
recovery file, legacy derivation adapter or online counterparty is required.

The audited source passed the market (30), mobile controller (38) and service
(37) suites, four funded refund/redeem fixtures covering both assets and
participants in both offer directions, and the Bitcoin/Handshake publication
suites (two each). Tests reject wrong seeds/networks, altered or unsigned
ancestry, premature refunds and excess fees. Android/iOS native host suites
passed 38/23 tests against that source. The app release additionally qualifies
the published dependencies through its complete platform gates.

Publication reuses ordinary internal address zero so discovery needs no private
allocation counter; that reuse links publication families and the ancestors add
transaction fees. Native spending still requires a verified current chain view,
exact unspent contract and explicit approval. Tests use synthetic funded
transactions and fresh encrypted stores, not real account seeds or funds.

The historical beta output whose private derivation context was lost remains
unchanged. This release introduces no legacy recovery adapter.
