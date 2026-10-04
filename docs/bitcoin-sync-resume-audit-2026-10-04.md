# Bitcoin interrupted recovery and repeated-pass audit, 2026-10-04

The older debug build lost its in-memory Bitcoin transport state if the app
was terminated before the completed wallet update committed. Retained UI
progress cannot repair that loss. Both mobile platforms now use the published
hns-wallet-mobile 0.4.2 / hns-wallet-bitcoin-kyoto 0.4.2 / hns-wallet-bip157
0.4.2 cohort, pinned by registry checksums and the reviewed archive list.
The shared fix persists verified headers, quorum-admitted filter commitments
and raw filters in the encrypted wallet store. Replay validates the evidence
and matches the current scripts; neither the birthday nor the wallet scan
checkpoint is advanced to pretend an unfinished wallet recovery completed.
The runtime/source commit is 527aa27f71a7054c7f2efc99a50a33d12ef3d784.

The Pixel's old PID 10934 showed 95.2%/589992 processed filters at 18:12 CDT,
35.0%/713917 at 18:17, 42.3%/778042 at 18:24, and 63.6%/955095 at 18:40.
Elapsed time continued and the chain tip remained near 969,916. Those paired UI
and preserved log exports demonstrate another historical compact-filter pass,
rather than a process/header restart. The public percentage weights current
filter-header/filter coverage, while processed/matched counters accumulate.

Recovery expands its address window when actual transaction outputs identify
used derived addresses. That path requests a fresh historical pass; the
separate approved-broadcast recovery path is bounded near the tip. The old
logs do not print which path initiated this particular pass. The size and
coverage of the observed repeat fit address-gap recovery. New cached raw
filters are reused locally when the script window expands.

Android additionally restarts its ETA measurement if coverage falls or the
runtime leaves filter scanning. It therefore does not include an earlier
pass or time spent fetching matched blocks in the new pass's estimate. Two
regression tests exercise a 100% → 25% transition and block-fetching delay.
Debug-only phase/counter logging is bounded by phase transitions and groups
of 10,000 processed filters, and contains no account, script or peer identity.

The shared core passed its full locked CI gate after publication. Mobile
source/checksum, notices, version, runtime-boundary, localization and 120 Python
checks passed; all-target Rust Clippy passed for Android and iOS interfaces.
Local Android assembly/unit tests and the remaining native gate are still
running. The owner requested that the old Bitcoin pass finish before the
replacement APK is installed. Physical cold-restart verification is pending;
this report does not claim it has already passed.
