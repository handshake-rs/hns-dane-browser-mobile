# Rust dependencies

The mobile manifest declares exact ecosystem package requirements. Protocol and engine packages use registry sources and checksums. Wallet
packages use one immutable reviewed source revision, with exact versions
and lockfile provenance enforced by `scripts/verify_wallet_source.py`. See
[wallet synchronization](wallet-sync-candidates.md) for behavior and validation.

| Dependency | Source requirement |
| --- | --- |
| `hns-rs` protocol packages | 0.5.0 |
| Engine light-client and SQLite browser adapters | 0.2.6 |
| Engine browser, policy, transport, and resolution packages | Exact package versions in `rust/Cargo.toml` |
| `hns-wallet-ffi`, `hns-wallet-mobile`, `hns-wallet-types` | 0.4.1 |
| `hns-wallet-hns`, `hns-wallet-market` | 0.4.2 source candidates |

Compatible transitive requirements select the two wallet implementation
patches without republishing unchanged consumer packages. Cargo aliases such
as `hns-core`, `hns-chain`, `hns-p2p`, and `hns-urkel` refer to the corresponding
`hns-browser-*` packages.

Clean builds resolve the checked-in lockfile without a sibling checkout or
package publication. The wallet source is pinned to
`1737dd439c4a13ec6bcbed4bc056733252e4c1e1`; only HNS and market advance to
0.4.2. Validate the source and run the locked mobile gates:

```sh
python3 scripts/verify_wallet_source.py
python3 tests/test_release_safety.py
./scripts/check.sh
```

Do not commit sibling-path dependencies or floating source overrides. Registry
handoff replaces the reviewed wallet source with published checksums after
only the two changed packages are qualified and uploaded. Regenerate notices
and run the complete locked mobile gate against the resulting graph.
