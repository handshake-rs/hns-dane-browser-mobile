# Mobile wallet/provider boundary

Android and iOS contain source-level, project-linked, deliberately dormant
wallet-provider projections. Three versions have distinct meanings and must
not be conflated:

- website Provider API schema and `providerApiVersion` remain `1`;
- the private native wallet-service ABI revision expected by a future generated
  mobile binding is `2`;
- the browser-owned public approval projection schema is `2`.

The website-facing allowlist follows the same 43-method Handshake-first surface
as Chromium. Generic Ethereum, raw Bitcoin signing, and unrestricted
native-host methods are absent; external asset calls accept only `bitcoin` or
`ethereum`. The page-frame boundary bounds JSON frames, strings, collections,
and nesting and rejects unsafe JSON numbers and secret-shaped fields. Complete
typed parameter validation remains a generated-binding and wallet-runtime
responsibility; this source does not claim arbitrary-calldata validation for
every otherwise supported method. Exact browser authority, wallet/service
sessions, policy/navigation generations, channels, and opaque handles remain
native. `permissionGeneration` is deliberately accepted only at the top level
of results for the four canonical permission methods and in its two typed event
shapes. The checked-in adapter is hardwired to an unavailable ABI-v2 interface,
advertises no methods, and cannot dispatch.

Provider-bridge installation, wallet operations, approval dispatch, and value
movement are each guarded by immutable false release gates. Android returns
before adding a document-start script or `WebMessageListener`; iOS returns
before mutating `WKUserContentController`. Neither bridge is referenced by the
browser controllers, no provider is announced, and no wallet method can
execute. The dormant website bootstrap still never creates `window.ethereum`.

## Public approvals and events

An ABI-v2 native approval must be converted into a closed browser-owned public
record before UI display. The projection accepts only schema `2`, a canonical
nonzero approval identifier, the exact logical origin, the matching method, a
future expiry no more than 90 seconds away, and one of twelve summaries:

- permissions;
- module enablement;
- send;
- name transfer;
- name finalization;
- typed signature;
- name-market offer;
- name-market purchase;
- market intent;
- fill acceptance;
- swap redeem;
- swap refund.

Each kind has an exact method pairing and closed field set. Asset amounts and
fees are canonical integer base-unit strings bounded to `u128`; chains,
modules, assets, finality, warnings, identifiers, refund times, and public text
are bounded and validated. Display rows are derived locally from those typed
fields. Native or page-supplied free-form display text is never trusted.
`authorityHandle` and `authorityRevision` are deliberately absent from the
public record.

The thirteen provider events likewise use a closed typed projection with an
exact payload shape for each event. A native result cannot carry inline
`events`; projected events travel through the dedicated event path. Wallet or
service sessions, opaque authority handles and revisions, channel identifiers,
and event sequence envelopes remain private and are rejected if they appear in
page-visible data. Event delivery is compare-and-clear bound to the exact
browser authority, wallet session, and permission generation; a delayed event
from an older provider session cannot publish into or revoke a newer session.
The bridge no longer accepts a caller-selected event name and arbitrary payload.

## Remaining integration

Enabling this boundary requires the generated and reviewed `hns-wallet-ffi`
JNI/C bindings, a canonical engine result carrying exact origin, namespace
decision fingerprint, browser-authority validity and generation, an opaque
wallet-engine context, real permission persistence, native approval UI, a
typed event producer, and controller lifecycle installation/revocation. Mobile
must not reconstruct that authority from a URL, toolbar state, proxy readiness,
booleans, or JSON. Private ABI framing, session/restart/channel sequencing, and
opaque-handle ownership remain wallet-runtime responsibilities and are not
reimplemented here.

`AndroidWalletKeyStore` and `WalletKeychainStore` remain disconnected
native-only helpers for a 32-byte wallet database key. Their `v1` identifiers
describe the durable storage format and are intentionally unchanged by the
wallet ABI revision. Seed phrases, private keys, passphrases, preimages,
database keys, capability material, and authority handles never enter
WebView/WKWebView JavaScript or the public approval/event projections.

The source includes negative/unit-test cases for these projections, but this
revision has not run a build, unit test, simulator, Xcode, Gradle, signed-device,
or installed-product gate. It is unqualified and does not change DANE browsing.
