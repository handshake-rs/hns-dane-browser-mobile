# Rust Dependency Cohort

Last reviewed: 2026-09-29.

The `1.0.11` application source consumes exact, checksum-bearing crates.io
releases. No sibling checkout, Git dependency, or `[patch.crates-io]` override
is part of the release graph.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.5.0` | `60eb912d615243a6bfb9741b17f16833c5a9181a` | [v0.5.0 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.5.0); all 19 public crates published and registry readback verified |
| `hns-dane-engine` mobile/wallet cohort | `0.2.6` | `90a5dfeb5b7c00e8fea010e79f82076de4263fd6` | [mobile-wallet-v0.2.6 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/mobile-wallet-v0.2.6); the four light-client crates and three SQLite browser adapters published and registry readback verified |
| `hns-dane-engine` unchanged contracts | exact `0.2.2`, `0.2.3`, or `0.3.0` releases | checksum-bound by `rust/Cargo.lock` | unchanged browser, policy, transport, and resolution packages remain on their already-published exact releases |
| `hns-wallet-rs` | `0.3.1` | `780514d8e3cf4c393885a422b457e1bc5ff7f5da` | [v0.3.1 release](https://github.com/handshake-rs/hns-wallet-rs/releases/tag/v0.3.1); all 16 crates published to crates.io, archive-verified against the tagged source, and registry readback verified |

## Mobile graph policy

The root mobile manifest declares exact crates.io requirements, including
`hns-header-consensus = "=0.5.0"`, the light-client and SQLite adapter cohort at
`=0.2.6`, the unchanged engine packages at their exact published versions, and
`hns-wallet-ffi`, `hns-wallet-mobile`, and `hns-wallet-types` at `=0.3.1`.
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
