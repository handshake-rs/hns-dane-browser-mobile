# Wallet sync patch candidates

The October 1, 2026 wallet release (`aad2a95`) incremented all sixteen
packages for a peer-quorum change in `hns-wallet-hns` (`b74368f`). New wallet
releases select one package per upload and use independent package versions.

This cleanup needs two packages:

| Package | Candidate | Reason |
| --- | --- | --- |
| `hns-wallet-hns` | 0.4.2 | Idle socket maintenance, public transport sharing, and scan resume |
| `hns-wallet-market` | 0.4.2 | Existing legacy offer-record compatibility fix (`e35e203`, formatted by `ced7061`) |

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
other required historical coverage changes retain their explicit replay path.
A saved scan never establishes fresh header agreement by itself.

## Local qualification

Run `python3 scripts/qualify-wallet-candidates.py /path/to/hns-wallet-rs`
from this repository. Append `test` or `clippy` to run that gate after resolving
and checking the candidate graph. The script uses temporary overrides for the
local source and restores the original mobile lockfile even when Cargo fails.
It requires the two explicit candidate versions shown above. Run one
qualification command at a time; the lockfile backup is scoped to that run.

## Registry handoff

Both 0.4.2 versions are unpublished source candidates. Mobile manifests pin
these two patches while keeping existing mobile, FFI, and type packages at
0.4.1. Qualification uses temporary Cargo path overrides for the local wallet
source; these overrides must not become registry dependency declarations.
The checked-in mobile lockfile remains the last registry-backed lockfile.
After the two qualified package uploads, refresh that lockfile from crates.io,
check that only the two wallet packages changed versions, and build the mobile
artifacts against the published checksums. Until that handoff, a fresh normal
registry-only mobile build cannot resolve the candidates.

Local host Rust checks cover both FFI crates, the shared runtime, real peer
Ping/Pong and header reuse, scan reopen, and legacy offer decoding. They do
not replace device validation of installed Android/iOS builds. Diagnostic
logs are retained as read-only evidence; wallet data must not be cleared to
validate scan resume.
