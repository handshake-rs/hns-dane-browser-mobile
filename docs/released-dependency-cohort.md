# Rust Dependency Cohort

Last reviewed: 2026-09-05.

The `1.0.1` embedded mobile candidate keeps HNS, engine, and wallet dependencies
on exact checksum-bearing published releases.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.4.1` | tag commit `73611a0d83778e157b35f28ca2197d068e83fc61` | [v0.4.1 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.4.1); all 19 public crates published and checksum/provenance verified |
| `hns-dane-engine` core public crates | `0.2.2` | tag commit `b7fdf8826c81b77650a0f740d1f05314b74969f9` | [v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/v0.2.2); all 20 core public crates published and checksum/provenance verified |
| `hns-dane-engine` browser adapters | `0.2.2` | tag commit `3907e2a93eb7b10ee7deb1f179ce67824277c82a` | [browser-adapters-v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/browser-adapters-v0.2.2); all 11 mobile adapter crates published and checksum/provenance verified |
| stateless-DANE mobile patch | `0.2.3` | tag commit `142117058690220b066782d8ff0655cf0a2670b3` | [stateless-dane-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/stateless-dane-v0.2.3); exact patches for `hns-browser-gateway` and `hns-namespace-resolution`, checksum/provenance verified |
| light-client patch cohort | `0.2.3` | tag commit `87d2346c13ade4987801e0f1367bd604fd77c9f0` | [light-client-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/light-client-v0.2.3); `hns-light-chain`, `hns-light-wallet`, `hns-light-p2p`, and `hns-light-sync` published and checksum/provenance verified |
| Shakescape policy graph | `0.3.0` | source commit `2e06af3` plus release correction `ee22220` | `hns-resolution-policy` and `hns-browser-observability` published with one clean-break policy type graph; `hns-gateway 0.3.0`, `hns-p2p-transport 0.3.1`, and `hns-dane-engine 0.3.0` published for downstream consumers |
| `hns-wallet-rs` | `0.2.3` | tag commit `0a0558c6df8ceb4f8d8318821cd16981f248a22b`, `v0.2.3` | all 14 public wallet crates published and checksum/provenance verified; the mobile graph resolves the exact registry cohort |

## Mobile graph policy

The root mobile manifest declares HNS and engine dependencies as bare, exact
crates.io requirements: `hns-header-consensus = "=0.4.1"`, the engine packages
at `=0.2.2` except the light-client cohort at `=0.2.3`,
`hns-browser-gateway` and `hns-namespace-resolution` at
`=0.2.3` and the clean-break `hns-browser-observability` and
`hns-resolution-policy` graph at `=0.3.0`. `hns-wallet-ffi`,
`hns-wallet-mobile`, and `hns-wallet-types` all require exact crates.io version
`=0.2.3`. The compatibility import names
`hns-core`, `hns-chain`, `hns-p2p`, `hns-urkel`, and related names are Cargo
aliases for the published `hns-browser-*` packages; they are not second
packages or source pins.

The committed `Cargo.lock` records registry checksums for the entire mobile
dependency graph. No Git, local path, or Cargo patch source is admitted for an
external HNS ecosystem dependency in this release candidate.

Run the locked mobile unit test from the repository root:

```sh
python3 tests/test_release_safety.py
(cd rust && cargo test -p android-ffi --lib --locked)
```
