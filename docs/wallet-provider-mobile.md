# Mobile native wallet and website-provider boundary

This checkout contains two deliberately separate surfaces:

- unreleased Android and iOS app-native wallet controls backed by the pinned
  `hns-wallet-mobile` controller at
  `ba9f013a098679fe8e3d812a7e09020803e27d53`; and
- a website-facing wallet-provider projection that remains dormant and cannot
  mutate WebView or WKWebView.

The public Google Play `0.5.6` / code `47`, GitHub Android `0.5.7` / code `48`,
and App Store `0.5.5` / build `57` binaries predate the native controller work.
Their store descriptions correctly say that those releases do not include a
wallet. The unreleased source must pass Android installed-device and iOS CI
qualification before a later build can claim native wallet controls.

## Unreleased native app controls

Both platform shells now link a narrow native controller for:

- create and one-time recovery display;
- restore from a recovery phrase;
- open, status, unlock, lock, and controller destruction; and
- exactly one local non-value Handshake account identity.

This slice does not expose a balance, receive display, transaction history,
sending, name operations, HNSA, HNSR, settlement, Shakedex/Denuo, or a P2P
marketplace. It is not connected to page JavaScript. No website provider is
installed or announced, and no page can invoke these native controls.

Android uses a non-exported `WalletActivity`, a narrow JNI bridge, bounded
monotonic native handles, and `AndroidWalletKeyStore`. The 32-byte database key
is wrapped with an Android KeyStore AES-GCM key that requires an unlocked
device. The wrapping identity is create-only rather than silently replaceable,
and borrowed plaintext key arrays are wiped after use. The app hardens the
Android no-backup root and network wallet directory to owner-only access before
the Rust store validates or opens them. Wallet files and wrapping identities
are scoped to the captured Handshake network. A process-local storage lease remains owned through
asynchronous completion, so an older or concurrently launched Activity cannot
delete another Activity's live database/key pair. Creation keeps the new
database key only in process until the user confirms the one-time recovery
display; leaving the screen first wipes the key and phrase, destroys the
controller, and removes the incomplete database. The recovery display draws a
mutable `CharArray` without creating an app-level immutable phrase string or
enabling selection, copy, autofill, state restoration, or accessibility
exposure. `FLAG_SECURE` protects the dedicated activity from ordinary
screenshots and non-secure displays.
The restore phrase field is also hidden from autofill and accessibility
services so those processes cannot read the secret. This is an explicit
usability tradeoff: users who require an accessibility service cannot complete
the Android restore flow in this source slice.

iOS uses `RustNativeWallet`, the stable Apple C ABI, a native
`WalletViewController`, and `WalletKeychainStore`. The create-only 32-byte
database key is stored as a ThisDeviceOnly Keychain item requiring user
presence, with the corresponding Face ID unlock purpose declared in the source
application metadata, while wallet paths and Keychain accounts are scoped to
the captured Handshake network. Wallet files use complete file protection and
are excluded from backup. A process-local path lease prevents concurrent
screens from opening or cleaning the same database; the controller closes
before that lease is released. A newly created database and its in-memory key remain unconfirmed
until the one-time phrase is acknowledged; backgrounding or protected-storage
loss before confirmation wipes the app-owned mutable buffers and deletes the
incomplete wallet. Later lifecycle exits clear recovery input/display and lock
the controller. Screen-capture state is checked before create or restore, and
capture/screenshot notifications trigger lifecycle protection.

Swift and UIKit impose an important residual limitation: recovery display and
restore entry pass through Swift `String` and UIKit-managed text storage. The
bridge explicitly wipes its mutable `[UInt8]` buffers and clears the text
controls, but Swift/UIKit may retain managed or copied backing storage that the
app cannot deterministically zeroize. The source therefore claims best-effort
clearing on iOS, not complete in-memory phrase erasure. This limitation must be
part of iOS qualification and release review.

## Dormant website provider projection

Three provider versions have distinct meanings and must not be conflated:

- website Provider API schema and `providerApiVersion` remain `1`;
- the private native wallet-service ABI revision expected by a future
  generated provider binding is `2`; and
- the browser-owned public approval projection schema is `3`.

The website-facing allowlist follows the same 43-method Handshake-first surface
as Chromium. Generic Ethereum, raw Bitcoin signing, and unrestricted
native-host methods are absent; external asset calls accept only `bitcoin` or
`ethereum`. The page-frame boundary bounds JSON frames, strings, collections,
and nesting and rejects unsafe JSON numbers and secret-shaped fields. Complete
typed parameter validation remains a generated-provider-binding and
wallet-runtime responsibility; this source does not claim arbitrary-calldata
validation for every otherwise supported method. Exact browser authority,
wallet/service sessions, policy/navigation generations, channels, and opaque
handles remain native. `permissionGeneration` is deliberately accepted only
at the top level of results for the four canonical permission methods and in
its two typed event shapes. The checked-in adapter is hardwired to an
unavailable ABI-v2 interface, advertises no methods, and cannot dispatch.

A private capability snapshot may carry permission generation zero when the
exact origin has never had a permission record or tombstone. Its negotiated
method set may still contain non-permissioned bootstrap methods such as the
permission request; methods describe runtime support, not grants. The first
grant is generation one. Permission-bearing public events continue to require
a positive generation and the exact wallet-session binding.

Provider-bridge installation, approval dispatch, page-visible wallet methods,
and value movement are each guarded by immutable false release gates. Android
returns before adding a document-start script or `WebMessageListener`; iOS
returns before mutating `WKUserContentController`. Neither adapter is
referenced by the browser controllers, no provider is announced, and the
dormant website bootstrap never creates `window.ethereum`.

## Public approvals and events

An ABI-v2 native approval must be converted into a closed browser-owned public
record before UI display. The projection accepts only schema `3`, a canonical
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

Schema 3 permission summaries always contain `hnsNames`. The array is bounded
to 64 entries. Every entry is an exact `{name, nameHash}` pair: `name` follows
the canonical `hns-covenants` byte grammar (1 through 63 lowercase ASCII
letters, digits, and internal `-` or `_`, excluding `example`, `invalid`,
`local`, `localhost`, and `test`), and `nameHash` is the lowercase 64-hex
SHA3-256 digest of the raw name bytes. Entries must be strictly increasing by
`(name, nameHash)` with unique names and hashes. Nonempty disclosures require
the `names` capability, while `hns_requestAccounts` requires exactly
`accounts` and an empty disclosure array. Generic `wallet_requestPermissions`
cannot create accounts authority. Android and iOS render every accepted name
and its exact hash as browser-owned display rows.

The thirteen provider events likewise use a closed typed projection with an
exact payload shape for each event. A native result cannot carry inline
`events`; projected events travel through the dedicated event path. Wallet or
service sessions, opaque authority handles and revisions, channel identifiers,
and event sequence envelopes remain private and are rejected if they appear in
page-visible data. Event delivery is compare-and-clear bound to the exact
browser authority, wallet session, and permission generation; a delayed event
from an older provider session cannot publish into or revoke a newer session.
The bridge no longer accepts a caller-selected event name and arbitrary
payload.

## Remaining integration and qualification

Enabling the website boundary still requires the generated and reviewed
provider/service JNI and C bindings, a canonical engine result carrying exact
origin, namespace-decision fingerprint, browser-authority validity and
generation, an opaque wallet-engine context, real permission persistence,
native approval UI, a typed event producer, and controller lifecycle
installation/revocation. Mobile must not reconstruct that authority from a
URL, toolbar state, proxy readiness, booleans, or JSON. Private ABI framing,
session/restart/channel sequencing, and opaque-handle ownership remain
wallet-runtime responsibilities and are not reimplemented here.

Seed phrases, private keys, passphrases, preimages, database keys, capability
material, and authority handles never enter WebView/WKWebView JavaScript or the
public approval/event projections. The native controller does not weaken that
boundary.

The native source has portable Rust, bridge, and platform unit coverage, but
release qualification remains incomplete. Android still needs install/reinstall
and create/restore/open/background/destroy checks on the attached device. iOS
still needs the macOS ABI/XCFramework/app/simulator workflow against the final
source. Signed installed-product checks and updated privacy/store declarations
remain later release gates; source or unit-test success is not evidence that
the currently published apps contain these controls.
