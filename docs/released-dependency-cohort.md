# Rust Dependency Cohort

Last reviewed: 2026-08-31.

The `1.0.0` mobile candidate keeps HNS and engine dependencies on exact
published releases. Its complete wallet closure is pinned to one reviewed,
immutable `hns-wallet-rs` Git commit, so wallet fixes do not wait for an
all-crate crates.io promotion.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.3.1` | tag commit `0e99addca59778b7b7c6fc56291333a97c4c8815` | [v0.3.1 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.3.1); all 19 public crates published and checksum/provenance verified |
| `hns-dane-engine` core public crates | `0.2.2` | tag commit `b7fdf8826c81b77650a0f740d1f05314b74969f9` | [v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/v0.2.2); all 20 core public crates published and checksum/provenance verified |
| `hns-dane-engine` browser adapters | `0.2.2` | tag commit `3907e2a93eb7b10ee7deb1f179ce67824277c82a` | [browser-adapters-v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/browser-adapters-v0.2.2); all 11 mobile adapter crates published and checksum/provenance verified |
| stateless-DANE mobile patch | `0.2.3` | tag commit `142117058690220b066782d8ff0655cf0a2670b3` | [stateless-dane-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/stateless-dane-v0.2.3); exact patches for `hns-browser-gateway` and `hns-namespace-resolution`, checksum/provenance verified |
| Shakescape policy graph | `0.3.0` | source commit `2e06af3` plus release correction `ee22220` | `hns-resolution-policy` and `hns-browser-observability` published with one clean-break policy type graph; `hns-gateway 0.3.0`, `hns-p2p-transport 0.3.1`, and `hns-dane-engine 0.3.0` published for downstream consumers |
| `hns-wallet-rs` | Git commit `9ca52ec1ec4ece1a0f0b92984c338e16ff82c01f` | `Fix direct name proof watch ordering` | all wallet entry crates and their transitive closure resolve from this one immutable source revision |

## Mobile graph policy

The root mobile manifest declares HNS and engine dependencies as bare, exact
crates.io requirements: `hns-header-consensus = "=0.3.1"`, the engine packages
at `=0.2.2` except `hns-browser-gateway` and `hns-namespace-resolution` at
`=0.2.3` and the clean-break `hns-browser-observability` and
`hns-resolution-policy` graph at `=0.3.0`. `hns-wallet-ffi`,
`hns-wallet-mobile`, and `hns-wallet-types` all name the same full Git revision
of the canonical wallet repository. The compatibility import names
`hns-core`, `hns-chain`, `hns-p2p`, `hns-urkel`, and related names are Cargo
aliases for the published `hns-browser-*` packages; they are not second
packages or source pins.

The committed `Cargo.lock` records the exact source revision resolved by Cargo.
There is no repository-specific registry-only source-policy gate: wallet work
uses immutable Git revisions during active development and can be promoted to a
published shared wallet cohort when a release milestone warrants it.

Run the locked mobile unit test from the repository root:

```sh
python3 tests/test_release_safety.py
(cd rust && cargo test -p android-ffi --lib --locked)
```
