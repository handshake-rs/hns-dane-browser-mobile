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
| `hns-wallet-ffi`, `hns-wallet-types` | 0.4.1 |
| `hns-wallet-mobile`, `hns-wallet-bip157`, `hns-wallet-bitcoin-kyoto` | Published 0.4.2 |
| `hns-wallet-hns` | Published 0.4.3 |
| `hns-wallet-market` | Published 0.4.2 |

The HNS 0.4.3 peer-race/reconnect patch was published from
`1c0ff8247e21e11522a8bc7a8caf7307386e0361` in `hns-wallet-rs`. The market
0.4.2 patch remains at `5789a88caeb6b0410e0f472bedd1047a01cf9edc`. Compatible
consumer requirements select those patches while unchanged wallet packages
retain their existing versions. The Bitcoin transport, Kyoto adapter and mobile
controller 0.4.2 resume patch uses source
`527aa27f71a7054c7f2efc99a50a33d12ef3d784`. It saves verified headers, agreed
filter headers and raw filters in the encrypted wallet store, resumes partial
network work after process death, and reruns matching from the genuine birthday. Cargo aliases such as `hns-core`, `hns-chain`,
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
