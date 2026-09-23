# Rust Dependency Cohort

Last reviewed: 2026-09-23.

The `1.0.2` embedded mobile workspace consumes exact, checksum-bearing crates.io
releases. No sibling checkout, Git dependency, or `[patch.crates-io]` override
is part of the release graph.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.4.2` | `1a4a937a8b8367b8b96d0445b9aa2b7e6fdd7c6b` | [v0.4.2 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.4.2); all 19 public crates published and registry readback verified |
| `hns-dane-engine` mobile/wallet cohort | `0.2.5` | `77459f2ffbaa46d950fec95bd00013cc89d01007` | [mobile-wallet-v0.2.5 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/mobile-wallet-v0.2.5); the four light-client crates and three SQLite browser adapters published and registry readback verified |
| `hns-dane-engine` unchanged contracts | exact `0.2.2`, `0.2.3`, or `0.3.0` releases | checksum-bound by `rust/Cargo.lock` | unchanged browser, policy, transport, and resolution packages remain on their already-published exact releases |
| `hns-wallet-rs` | `0.2.4` | `04708e0e3c80dadc910878184bc40aec27bf5168` | [v0.2.4 release](https://github.com/handshake-rs/hns-wallet-rs/releases/tag/v0.2.4); all 16 crates published and registry readback verified |

## Mobile graph policy

The root mobile manifest declares exact crates.io requirements, including
`hns-header-consensus = "=0.4.2"`, the light-client and SQLite adapter cohort at
`=0.2.5`, the unchanged engine packages at their exact published versions, and
`hns-wallet-ffi`, `hns-wallet-mobile`, and `hns-wallet-types` at `=0.2.4`.
The compatibility import names
`hns-core`, `hns-chain`, `hns-p2p`, `hns-urkel`, and related names are Cargo
aliases for the published `hns-browser-*` packages; they are not second
packages or source pins.

The committed `Cargo.lock` records registry sources and checksums for all
third-party and ecosystem packages. Release-safety tests reject sibling
ecosystem paths, a crates.io patch table, or restoration of the old source
materialization script.

Run the locked mobile unit test from the repository root:

```sh
python3 tests/test_release_safety.py
(cd rust && cargo test -p android-ffi --lib --locked)
```
