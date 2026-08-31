# Released Rust Dependency Cohort

Last reviewed: 2026-08-30.

The `1.0.0` mobile candidate consumes the published, immutable Rust release
cohort below. Every listed HNS, engine, and wallet crate is an exact crates.io
requirement. The source-policy gate verifies the exact version and Cargo
checksum in each of the three committed lockfiles; it rejects every Git
ecosystem input.

| Project | Mobile release line | Reviewed source | Release evidence |
| --- | --- | --- | --- |
| `hns-rs` | `0.3.1` | tag commit `0e99addca59778b7b7c6fc56291333a97c4c8815` | [v0.3.1 release](https://github.com/handshake-rs/hns-rs/releases/tag/v0.3.1); all 19 public crates published and checksum/provenance verified |
| `hns-dane-engine` core public crates | `0.2.2` | tag commit `b7fdf8826c81b77650a0f740d1f05314b74969f9` | [v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/v0.2.2); all 20 core public crates published and checksum/provenance verified |
| `hns-dane-engine` browser adapters | `0.2.2` | tag commit `3907e2a93eb7b10ee7deb1f179ce67824277c82a` | [browser-adapters-v0.2.2 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/browser-adapters-v0.2.2); all 11 mobile adapter crates published and checksum/provenance verified |
| stateless-DANE mobile patch | `0.2.3` | tag commit `142117058690220b066782d8ff0655cf0a2670b3` | [stateless-dane-v0.2.3 release](https://github.com/handshake-rs/hns-dane-engine/releases/tag/stateless-dane-v0.2.3); exact patches for `hns-browser-gateway` and `hns-namespace-resolution`, checksum/provenance verified |
| Shakescape policy graph | `0.3.0` | source commit `2e06af3` plus release correction `ee22220` | `hns-resolution-policy` and `hns-browser-observability` published with one clean-break policy type graph; `hns-gateway 0.3.0`, `hns-p2p-transport 0.3.1`, and `hns-dane-engine 0.3.0` published for downstream consumers |
| `hns-wallet-rs` | `0.2.0` | source commit `1b81916f9f16b1735bf54821fe3d4913dd28752a` | all 14 public crates published and checksum/provenance verified |

## Mobile graph policy

The root mobile manifest declares direct public dependencies as bare, exact
crates.io requirements: `hns-header-consensus = "=0.3.1"`, the engine packages
at `=0.2.2` except `hns-browser-gateway` and `hns-namespace-resolution` at
`=0.2.3` and the clean-break `hns-browser-observability` and
`hns-resolution-policy` graph at `=0.3.0`; `hns-wallet-ffi`, `hns-wallet-mobile`, and
`hns-wallet-types` at `=0.2.0`. The compatibility import names
`hns-core`, `hns-chain`, `hns-p2p`, `hns-urkel`, and related names are Cargo
aliases for the published `hns-browser-*` packages; they are not second
packages or source pins.

There are no `[patch]` overrides for HNS, engine, wallet, or
`hns-light-p2p`, and the former local `hns-light-p2p` vendor patch was removed.
The `0.2.2` engine release contains the HSD early-`verack` interoperability
correction that made that local patch obsolete.

`scripts/verify_cargo_git_policy.py` is the executable form of this policy. It
rejects patches, local ecosystem paths, non-exact requirements, a restored
light-P2P vendor crate, any Git ecosystem source, missing registry checksums,
and absent direct registry records. Its regression tests are in
`tests/test_cargo_git_policy.py`.

Run the policy and locked mobile unit test from the repository root:

```sh
python3 scripts/verify_cargo_git_policy.py
python3 tests/test_cargo_git_policy.py
python3 tests/test_release_safety.py
(cd rust && cargo test -p android-ffi --lib --locked)
```
