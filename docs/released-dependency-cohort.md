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
| Engine DNSSEC and DANE browser stack | `hns-browser-dnssec` 0.2.3, `hns-browser-dane` 0.2.4, `hns-browser-resolver` 0.2.7, `hns-browser-transport` 0.2.4, `hns-browser-gateway` 0.2.4 |
| Other engine packages | Exact package versions in `rust/Cargo.toml` |
| `hns-wallet-ffi` | 0.4.1 |
| `hns-wallet-types`, `hns-wallet-provider`, `hns-wallet-shakedex` | 0.4.2 |
| `hns-wallet-bip157` | Published 0.4.3 |
| `hns-wallet-bitcoin-kyoto` | 0.4.6 |
| `hns-wallet-chain-api` | 0.4.2 |
| `hns-wallet-service` | 0.4.3 |
| `hns-wallet-hns` | 0.4.6 |
| `hns-wallet-market` | 0.5.1 |
| `hns-wallet-mobile` | 0.6.4 |

The DNSSEC and DANE security releases bind TLSA trust-anchor matches to signed
certificate paths on both mobile and Chromium. The app pins the complete
published browser stack so the resolver, policy, transport, and gateway use the
same corrected dependency cohort. Its TLSA fuzzer uses the same DANE policy and
DNSSEC releases.

The name-offer releases for types 0.4.2, provider 0.4.2, shakedex 0.4.2,
service 0.4.3, and mobile 0.6.4 use wallet source
`d40680ff9e1beae4d06322109bb2c8ae16ff5e2a`.
HNS 0.4.6 and the preceding mobile-wallet 0.6.3 release use wallet source
`5f15dab9ae7a07fb497a35c72b27f4b256ce8c80`.
Bitcoin 0.4.6 uses `05ba8632e66682b59d9ecd353535699f2ada5744`.
The recovered-swap update locates counterparty-funded HNS locks in the
authenticated wallet index before verifying current-chain settlement.
The Bitcoin transport, Kyoto adapter, and mobile controller save verified
headers, agreed filter headers, and raw filters in the encrypted wallet store.
They resume partial network work after process death and rerun matching from
the genuine birthday. Transport 0.4.3 bounds cached filter replay to wallet
consumption, reduces repeated progress recounts, and retires peers when their
message channels close.
Cargo aliases such as `hns-core`, `hns-chain`, `hns-p2p`, and `hns-urkel`
select the corresponding `hns-browser-*` packages.

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

The recovery cohort was published from wallet source
`ce222cba1abb60d6380121482e21ad2c83484f49` on main. It provides
chain-visible contract terms, automatic seed-only discovery, exact reclaim
actions, and independently verified settlement observations for both assets
and offer directions. All six downloaded registry archives were checked against
crates.io checksums, clean VCS metadata, and the exact reviewed Rust source
before app dependency resolution. Their digests are pinned in
`rust/wallet-crates.sha256`.
