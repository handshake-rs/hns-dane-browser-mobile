# Browser and wallet synchronization

Both native platforms register a weak reference to the wallet's live HNS peer
transport with the browser runtime. Browser header requests reuse those sessions;
the browser still independently validates and stages the returned headers before
publishing its chain state. Dropping or locking the wallet retires its authority
without letting the browser keep a wallet controller alive.

Peer maintenance answers idle protocol traffic, retains healthy sessions, and
makes disconnected sockets eligible for reconnection. The wallet's authenticated
scan height and watch state survive reopen. Loading a wallet continues that scan;
explicit discovery or watch-set expansion can require an earlier range.

## Browser publication

Browser synchronization performs network I/O and header validation in a private
SQLite stage. Publication checks the live generation and canonical tip baseline
under bounded publication locks. Headers, peer observations, and readiness
publish atomically. A peer refresh against an unchanged tip preserves the
maintenance epoch; a header advance invalidates superseded proof/status results.

Currentness requires recent corroboration from independent peer address groups.
Raw advertised heights are diagnostic inputs and do not authorize HNS resolution.
The bundled mainnet header snapshot accelerates initial validation while live
peers establish currentness. Missing or stale quorum fails closed.

## Wallet continuation and lifecycle

Wallet synchronization retains completed durable checkpoints after cancellation.
Android's bounded user-started sync uses its visible foreground service; iOS
requires foreground and protected-storage authority for wallet work. Reopening
or foregrounding resumes from authenticated state, with fresh peer and chain
evidence before exposing spend authority.

Qualify both platforms using [release readiness](release-readiness.md), including
idle peers, disconnect/reconnect, browser session reuse, initial restore,
watch expansion, reopen, cancellation, and concurrent browser navigation.
