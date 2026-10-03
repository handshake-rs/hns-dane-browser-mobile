# Rust dependencies

The mobile manifest declares exact ecosystem package requirements. All protocol,
engine, and wallet packages use crates.io sources and lockfile checksums.
`scripts/verify_wallet_source.py` checks all fourteen selected wallet versions
against [their reviewed archive checksums](../rust/wallet-crates.sha256), rejects
source overrides and Git dependencies, and requires one copy of each package.

| Dependency | Source requirement |
| --- | --- |
| `hns-rs` protocol packages | 0.5.0 |
| Engine light-client and SQLite browser adapters | 0.2.6 |
| Other engine packages | Exact package versions in `rust/Cargo.toml` |
| `hns-wallet-ffi`, `hns-wallet-mobile`, `hns-wallet-types` | 0.4.1 |
| `hns-wallet-hns`, `hns-wallet-market` | Published 0.4.2 |

Only HNS and market needed new package releases. Their published source is
`5789a88caeb6b0410e0f472bedd1047a01cf9edc` in `hns-wallet-rs`. Compatible
consumer requirements select those patches while the other wallet packages
retain their existing versions. Cargo aliases such as `hns-core`, `hns-chain`,
`hns-p2p`, and `hns-urkel` select the corresponding `hns-browser-*` packages.

Clean builds need no sibling checkout or Git override. Verify the registry
handoff and run the locked gates:

```sh
python3 scripts/verify_wallet_source.py
python3 tests/test_release_safety.py
./scripts/check.sh
```

Do not commit sibling paths, floating versions, or source overrides. Future
wallet updates must qualify the changed packages, verify their published source
and archive digests, update the exact requirements and checksum manifest,
regenerate notices, and run the complete locked mobile gate.
