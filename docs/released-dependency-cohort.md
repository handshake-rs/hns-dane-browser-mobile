# Rust dependencies

The mobile manifest declares exact ecosystem package requirements. Delivery
uses registry sources and checksums; local candidate qualification uses the
explicit temporary source overrides described in
[wallet-sync-candidates.md](wallet-sync-candidates.md).

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

The mobile registry lockfile remains the delivery baseline until the candidate
packages can be resolved with actual registry checksums. The candidate helper
checks local sources and restores that lockfile afterward:

```sh
python3 scripts/qualify-wallet-candidates.py /path/to/hns-wallet-rs test
python3 scripts/qualify-wallet-candidates.py /path/to/hns-wallet-rs clippy
python3 tests/test_release_safety.py
```

Do not commit a sibling-path dependency or a temporary Cargo patch table.
After the registry handoff, run the complete locked mobile gate against the
qualified commit and inspect the resulting dependency graph.
