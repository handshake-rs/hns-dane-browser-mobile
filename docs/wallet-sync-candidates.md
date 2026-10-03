# Published wallet synchronization patches

The mobile application consumes these two published patches:

| Package | Version | Reason |
| --- | --- | --- |
| `hns-wallet-hns` | 0.4.2 | Idle socket maintenance, public transport sharing, and scan resume |
| `hns-wallet-market` | 0.4.2 | Authenticated offer persistence and decoding |

The other fourteen wallet packages remain at 0.4.1. The Android and iOS
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

Both packages were published as 0.4.2 from audited wallet source
`5789a88caeb6b0410e0f472bedd1047a01cf9edc`. The HNS patch also negotiates
browser reserve peers concurrently, preventing stalled handshakes from imposing
serial browser delays. A real-socket regression test requires both candidate
connections to begin before either handshake is released.

The mobile manifest and lockfile use registry sources for every selected wallet
package. The unchanged API and consumer packages remain on 0.4.1. The source
verifier requires exact versions and the reviewed published archive checksums;
local paths, Git dependencies, and source overrides are rejected. Third-party
notices are generated from that delivery graph.

Local host Rust checks cover both FFI crates, the shared runtime, real peer
Ping/Pong and header reuse, scan reopen, and authenticated offer decoding. They do
not replace device validation of installed Android/iOS builds. Diagnostic
logs are retained as read-only evidence; wallet data must not be cleared to
validate scan resume.
