# Published wallet patches

The mobile application consumes these published patches:

| Package | Version | Reason |
| --- | --- | --- |
| `hns-wallet-hns` | 0.4.7 | Concurrent initial peer races, idle socket maintenance, public transport sharing, reconnects, recovery-publication expiry, recovered counterparty lock lookup, and the crypto dependency update |
| `hns-wallet-bip157` | 0.4.3 | Durable Bitcoin filter caches |
| `hns-wallet-bitcoin-kyoto` | 0.4.7 | Durable scan resume, funded swap-watch replay, Bitcoin fee preflight bounded by verified funding authority, and the crypto dependency update |
| `hns-wallet-chain-api` | 0.4.3 | Public recovery terms and the crypto dependency update |
| `hns-wallet-service` | 0.4.4 | Native recoverable funding, name-offer lifecycle, and the crypto dependency update |
| `hns-wallet-market` | 0.5.2 | Seed-only contract discovery, reclaim, verified settlement, and the crypto dependency update |
| `hns-wallet-types`, `hns-wallet-provider` | 0.4.2 | Signed name offers, relay access, and HIP-0001 swap flow |
| `hns-wallet-shakedex` | 0.4.3 | Signed name offers, relay access, HIP-0001 swap flow, and the crypto dependency update |
| `hns-wallet-mobile` | 0.6.5 | Native name-offer browse, publish, accept, cancel, and recovery alongside the coin-swap controller and the crypto dependency update |

Other selected wallet package versions are listed in the
[dependency guide](released-dependency-cohort.md). The Android and iOS
controllers register the same weak public-header transport with the shared
browser runtime. Requests reuse the wallet's negotiated peer sockets and
serialize with that socket's wallet requests. The browser still verifies
headers and publishes its own staged store atomically. Sharing a transport
does not merge the browser and encrypted wallet databases or bypass either
consumer's chain validation. Without a live wallet transport, the browser
uses its existing connector.

A weak maintenance worker answers standard peer traffic while sockets are
idle. Failed sockets leave the pool and are removed from wallet quorum
tracking at the next connection pass. Neither the browser handle nor the
worker keeps an unlocked wallet alive; existing wallet-retirement logic still
clears remote filters before retaining public sessions. Mobile operating
systems may suspend or terminate networking while the app is in the
background; this change does not promise persistent background connectivity.

Wallet sync prepares the bounded restoration window for a first scan, then
resumes an authenticated saved scan frontier. An actual missing-watch failure
can expand coverage and rewind on either platform. New HTLC interests and
other required coverage changes retain their explicit replay path.
A saved scan never establishes fresh header agreement by itself.

## Local qualification

The normal locked workspace checks qualify the exact wallet source used by
both mobile platforms:

```sh
python3 scripts/verify_wallet_source.py
cargo +1.98.1 clippy --manifest-path rust/Cargo.toml --workspace --all-targets --locked -- -D warnings
cargo +1.98.1 test --manifest-path rust/Cargo.toml --workspace --all-targets --locked
```

Run `./scripts/check.sh` for the complete Rust, ABI, fuzz, and supply-chain gate.

## Registry handoff

Current patch sources and exact versions are recorded in
[the dependency guide](released-dependency-cohort.md). HNS wallet peer
negotiation starts browser reserve connections concurrently, so one stalled
handshake does not delay the others. A real-socket regression test requires
both candidates to begin before either handshake is released.

The mobile manifest and lockfile use registry sources for every selected wallet
package. The remaining API and consumer package versions are listed in the
dependency guide. The source
verifier requires exact versions and the reviewed published archive checksums;
local paths, Git dependencies, and source overrides are rejected. Third-party
notices are generated from that delivery graph.

Local host Rust checks cover both FFI crates, the shared runtime, real peer
Ping/Pong and header reuse, scan reopen, and authenticated offer decoding. They do
not replace device validation of installed Android/iOS builds. Diagnostic
logs are retained as read-only evidence; wallet data must not be cleared to
validate scan resume.

The 0.5.1 recovery implementation publishes complete public terms in ordinary
transaction ancestors committed by the funded contract. A fresh seed restore
discovers the exact contract and reconstructs reclaim authority without the
original offers, local key allocations or an online counterparty. Per-chain
verified observations also restore eligible public-secret claims. Signing
still requires verified unspent evidence and consensus maturity.
