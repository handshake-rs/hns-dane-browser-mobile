# Rust Dependency Cohort

Last reviewed: 2026-09-07.

The `1.0.2` embedded mobile candidate keeps published HNS dependencies exact
and checksum-bearing. Its coordinated SQLite migration consumes adjacent
publication-ready engine-adapter and wallet source until those crates are
published; the paths are explicit and locked rather than silently substituted.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.4.1` | tag commit `73611a0d83778e157b35f28ca2197d068e83fc61` | [v0.4.1 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.4.1); all 19 public crates published and checksum/provenance verified |
| `hns-dane-engine` core public crates | `0.2.2` | tag commit `b7fdf8826c81b77650a0f740d1f05314b74969f9` | [v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/v0.2.2); all 20 core public crates published and checksum/provenance verified |
| `hns-dane-engine` browser adapters | published `0.2.2`; adjacent `hns-browser-chain`, `hns-browser-p2p`, and `hns-browser-resolver 0.2.3` | current adjacent engine source | the three affected adapters are prepared for publication with `rusqlite 0.40.2`; remaining adapter crates retain their exact releases |
| stateless-DANE mobile patch | `0.2.3` | tag commit `142117058690220b066782d8ff0655cf0a2670b3` | [stateless-dane-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/stateless-dane-v0.2.3); exact patches for `hns-browser-gateway` and `hns-namespace-resolution`, checksum/provenance verified |
| light-client patch cohort | `0.2.3` | tag commit `87d2346c13ade4987801e0f1367bd604fd77c9f0` | [light-client-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/light-client-v0.2.3); `hns-light-chain`, `hns-light-wallet`, `hns-light-p2p`, and `hns-light-sync` published and checksum/provenance verified |
| Shakescape policy graph | `0.3.0` | source commit `2e06af3` plus release correction `ee22220` | `hns-resolution-policy` and `hns-browser-observability` published with one clean-break policy type graph; `hns-gateway 0.3.0`, `hns-p2p-transport 0.3.1`, and `hns-dane-engine 0.3.0` published for downstream consumers |
| `hns-wallet-rs` | adjacent prepared `0.2.3` source | current adjacent wallet source | all 14 crates retain the coordinated `0.2.3` identity and advance together to the single `rusqlite 0.40.2` native-link cohort before publication |

## Mobile graph policy

The root mobile manifest declares published HNS and most engine dependencies as
bare, exact crates.io requirements: `hns-header-consensus = "=0.4.1"`, the
unchanged engine packages at `=0.2.2`, and the light-client cohort at `=0.2.3`,
`hns-browser-gateway` and `hns-namespace-resolution` at
`=0.2.3` and the clean-break `hns-browser-observability` and
`hns-resolution-policy` graph at `=0.3.0`. The three SQLite-backed browser
adapters require exact `=0.2.3` and are patched to their adjacent release
source. `hns-wallet-ffi`, `hns-wallet-mobile`, and `hns-wallet-types` require
exact `=0.2.3` from the adjacent coordinated wallet source. The compatibility import names
`hns-core`, `hns-chain`, `hns-p2p`, `hns-urkel`, and related names are Cargo
aliases for the published `hns-browser-*` packages; they are not second
packages or source pins.

The committed `Cargo.lock` records registry checksums for third-party and
published ecosystem packages and exact filesystem identities for the adjacent
publication sources. No Git dependency is admitted. Once the coordinated
crates are published, the adjacent paths can be removed and the same exact
versions resolved from the registry.

Run the locked mobile unit test from the repository root:

```sh
python3 tests/test_release_safety.py
(cd rust && cargo test -p android-ffi --lib --locked)
```
