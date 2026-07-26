# Sync Startup Audit

## Current First-Run Path

- `HnsDaneApplication` creates and starts `HnsSyncScheduler` when the first app activity starts, so first install no longer depends on opening Diagnostics and pressing `Run sync now`. It closes the scheduler when the last app activity stops, keeping sync active across browser, Settings, diagnostics, and HNS Sync navigation without running after the app backgrounds.
- `HnsSyncScheduler` runs immediately, then uses the one-second active interval
  only after a run actually accepts headers and still is not current. Explicit
  peer/seed failures use bounded retry polling; current, unknown-target, and
  other no-progress states use a 10-minute network-sync interval.
- The native Android sync tick requests up to 192 header batches per peer per run, which is enough to cover current mainnet-scale catch-up in one or a small number of foreground ticks when a healthy peer serves full batches.
- Android native sync also prefetches one 2000-header page from published HSD mainnet checkpoint anchors at 50,000, 100,000, 160,000, 200,000, 225,000, and 258,026. These prefetched pages are staged only; they are inserted and counted after the local chain has validated the page's parent, so the optimization does not trust out-of-order peer data.
- Seeded peers are persisted before the long header run starts, DNS seeds are refreshed while the peer table is below target, sync attempts use up to eight outbound peers per tick, and successful plus additional unqueried peers are queried with bounded `getaddr` discovery toward the 64-peer table target, so a killed or interrupted first run does not leave the peer database empty after headers have already advanced.
- Native status version 2 reports an `effectiveTargetHeight` only after recent
  successful height observations from at least three independent address
  groups. The lower median is outlier resistant. `lagBlocks` and `freshness`
  apply the exact two-block currentness contract; missing or expired quorum is
  unknown and fails closed. `bestPeerHeight` and `estimatedTipHeight` remain
  diagnostics and never authorize currentness.

## User-Visible Progress

- The main browser screen shows a horizontal sync progress bar directly under the omnibox toolbar.
- The main browser screen reads lightweight local native status while visible,
  so `bestHeight` and the progress bar move during a long in-flight header run;
  these UI reads do not initiate network sync.
- The status line shows status, local `bestHeight`, authoritative effective
  target and freshness, peer count, and accepted count. Raw peer maximum and
  schedule estimate are labeled diagnostics.
- A second horizontal loading bar sits below the block-sync info and tracks WebView page-load progress while HNS proof/DANE/origin work is running.
- HNS gateway error bodies include the requested URL above the status line so repeated 502 pages can be distinguished at a glance.
- There is no foreground-service notification or notification permission. Progress is shown only in the browser and HNS Sync screens, and catch-up resumes the next time any app screen starts.

## Remaining Speed Bottlenecks

- Initial sync still downloads and validates headers from live peers at first run; checkpoint prefetch overlaps some later 2000-header pages but the APK does not yet ship a recent signed/checkpointed header snapshot that would let it skip earlier history.
- Proof data is still fetched on demand for requested HNS names rather than prefetching popular names.
- Peer quality dominates first-run time. The current path seeds peers
  automatically, expands the peer table through bounded `getaddr` discovery,
  and continues quickly only while headers are demonstrably advancing. Poor or
  no-progress peers fall back to bounded/idle scheduling instead of causing an
  indefinite hot loop.
