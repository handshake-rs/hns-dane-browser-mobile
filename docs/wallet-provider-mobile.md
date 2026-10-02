# Mobile native wallet and website-provider boundary

Both platform shells use the pinned `hns-wallet-mobile` controller for create,
restore, open, unlock, lock, recovery, and a single local Handshake account.
Native screens expose synchronized balance, receive targets, history, names,
reviewed sends, and Shakescape pairing and offers. The browser reuses the
wallet's live peer transport while independently validating its headers and
proofs. Wallet loading resumes from authenticated scan coverage; expanded
watch sets use the explicit discovery path.

The confirmed balance, pending outgoing amount, and available balance are
separate integer values. Send approvals bind the exact transaction and fee.
Native controls and wallet authority never enter page JavaScript.

## Platform storage and lifecycle

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

Android wallet restoration also accepts an optional HNS birthday block. A
blank field maps exactly to block 0. A supplied value must be a whole unsigned
32-bit block height and is persisted as the restored account's scan birthday,
so subsequent watch-set rescans skip blocks before it. This is account-scoped,
not a cosmetic per-name hint: using a height after the wallet's earliest
activity can intentionally hide that earlier activity, and the UI therefore
directs uncertain users to retain block 0.

New-wallet creation does not ask the user to invent a birthday. Android and
iOS automatically persist the independently validated bundled snapshot height
for mainnet creation and retain block 0 for testnet/regtest, where no equivalent
bundled snapshot is installed. Restore remains the only flow with a
user-supplied birthday because an existing seed can have earlier activity.

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

## Synchronized HNS boundaries

The primary local path is a wallet-owned direct HNS peer coordinator. It
verifies peer/header agreement, retains a journaled rollback floor, scans
wallet-relevant blocks, observes fees, and broadcasts approved transactions
without granting the browser proxy or a website wallet authority. Partial
catch-up is reported separately from a complete spendable snapshot. Android
and iOS require the exact current wallet identity, storage lease, lifecycle
authority, and operation generation before publishing direct-wallet output.

Both native ABIs import exact-text names by acquiring a verified proof from the
direct coordinator and committing the canonical import through the mobile
controller. Android uses bounded HNWR-v3 results and HNWP-v1 pages of at most 64
names; the UI renders 20 names per page. iOS uses its bounded HNWR-v2 projection.
Each decoder validates exact fields, account identity, purpose, ordering,
canonical values, coherent heights, and output bounds before publication.

The browser proxy does not grant wallet authority. Direct wallet scanning
verifies canonical blocks from ordinary peers and does not require a scoped
indexed RPC backend.
Known names remain
unchanged until verified direct scanning or a successful trusted-native import
commits canonical evidence.

Both platforms run read synchronization away from the UI thread and require the
exact generation, lease, and controller identity before publishing. Contended
native controller retirement is likewise handed to a background worker. On iOS,
lifecycle callbacks immediately detach UI authority and transfer the controller
with its exact storage lease to one serial retirement queue; that lease is
released only after native lock/destruction and any incomplete-wallet file
deletion finish. Foreground reentry waits for that handoff and stale read
completion cannot publish. While a direct synchronization is still live, its
Rust C ABI mailbox exposes only its coarse stage and verified header, wallet
scan, birthday, and target heights. A process-owned Swift cache lets a
replacement wallet screen observe that operation without acquiring the old
controller's storage or presenting it as a restart. Terminal progress revokes
late callbacks, and the replacement screen retries storage acquisition until
retirement releases the old lease. The same process operation owns a
single-use, cancellation-only capability: a replacement screen can request
that synchronization stop immediately without taking the controller mutex.
No additional synchronization batch starts; an already-running atomic peer or
database call unwinds while retaining the last completed durable checkpoint.
The screen waits for retirement to release
storage and then restores the ordinary wallet controls. Deletion remains a
separate action and cannot overlap the synchronizing controller; after normal
device authentication reopens the wallet, the established identity-bound and
typed irreversible-deletion confirmations are available again. iOS has no
unrestricted equivalent of Android's long-running data-sync foreground
service: actual process suspension
can pause both networking and UI polling. On process resume, the worker
continues and the screen reconnects to its process-owned progress; after
termination, the next operation resumes from the durable direct-HNS checkpoint
and monotonic floor journal. Qualify cancellation, storage leases, stale
completion suppression, direct reads, and process-resume behavior on the exact
signed Android and iOS candidates.

A restored wallet birthday may be above the bundled or partially synchronized
local header tip. That is a normal header-only catch-up state: public progress
continues to expose the verified header height and configured birthday, the
wallet scan remains absent, and no balance or spend authority is projected.
Scanning begins only after independently agreed headers reach the birthday.
Only an actual scanned height below birthday or above the verified header tip
is rejected as incoherent.

## Trusted-native exact-text name import

The Android JNI exposes a bounded native-only bulk name import and the Apple C
ABI retains the single-name form through the same `MobileHnsReadController`.
Kotlin accepts either one name or a file containing 1–10,000 unique names as
exact lines or a JSON string array. Kotlin and Swift pass the exact UTF-8 text
without trimming, lowercasing, IDNA, Unicode normalization, or trailing-dot
editing. Their text controls explicitly disable capitalization, correction,
spell checking, suggestions, and smart punctuation. Canonical validation is the
pinned `hns-covenants` grammar: 1 through 63 lowercase ASCII letters or digits,
with `-` and `_` only internally and the five reserved names rejected.

Android acquires and verifies every requested direct name proof before making
one atomic account/name-store commit. It then performs exactly one synchronized
refresh for the complete import, never one refresh per name. Before full
wallet activity scanning, the direct coordinator idempotently expands the
restoration watch set to its required derivation frontier once. A newly
expanded wallet therefore has at most one discovery scan. If only the name
projection is still catching up, Android keeps the prior verified balance and
marks the name result incomplete instead of clearing the complete read
projection.

The private success result is `HNWI` version 1: a closed envelope with a 12-byte
header, zero flags and reserved bytes, a big-endian JSON length, and a
1-through-4096 byte strict minimized HNS name summary already used by HNWR-v2.
Non-success is returned outside HNWI as a null JNI result or typed C result with
empty output.
Both native decoders reject unknown fields, wrong shapes, noncanonical values,
size/version/flag/reserved-byte mismatches, non-object framing, and a returned
name whose bytes differ from the submitted text. HNWI is absent from provider,
JavaScript, approval, value, HNSA, and HNSR surfaces.

Import and the mandatory post-success synchronization run away from the
UI thread. Publication requires the exact live storage lease, read generation,
controller identity, authority generation, and visible owner. A lifecycle or
replacement completion is discarded. Invalid canonical text performs no node
work and leaves the unlocked controller usable. Backend, evidence, projection,
serialization, or allocation faults lock and fail closed at the Rust boundary
as well as in platform defense-in-depth. A post-commit refresh that reports
bounded catch-up is not treated as an import failure and does not erase the
previous verified balance.

The repository also includes `tools/hns_bulk_name_actions.py` for checkpointed
bulk TRANSFER and FINALIZE operation against an authenticated HSD wallet HTTP
listener. It bounds input to 10,000 exact names, passes HSD's pre-broadcast
`maxFee` (and optional `hardFee`) controls on every transaction, atomically
checkpoints each request, and requires explicit reconciliation after an
ambiguous network failure before it can resume.

Neither product shell provisions the required backend or credential. The
control is therefore clearly disabled/unavailable in the candidate until that
separate product boundary and credentialed-device qualification exist.

## Dormant HRM/HNSA wallet-consumer boundary

Android and iOS contain the same fail-closed consumer shape for an exact
`hns.named-service/v1` result issued by a future trusted native broker. The
requested identity is the exact Handshake network magic, HNS name hash,
canonical service name, and nonzero application profile ID. A broker-issued
result additionally binds the live wallet authority, HRM sequence and envelope
hash, subject-wide aggregate revision, one trusted operation time, fenced
operation-lease generation, service resource/delegation/generation/controller,
validity intervals, endpoint lifetime/capability bounds, and detached-constraint
hashes.

The transfer is one-shot. Admission checks exact foreground, protected-storage,
durable-wallet, recovery, operation, and retirement state before acquisition,
after the source callback, and again inside a synchronous current-authority
guard. The broker must reconfirm the latest authenticated aggregate and retain
its sole broker serialization or namespace-wide fenced lease through the
dependent callback. A revision/time change, lease loss, selection change,
wallet rotation, denied guard, duplicate callback, or missing callback fails
closed and consumes the offered lease.

HRM commitment sequence is an unsigned `u64`, and sequence zero is valid. HNSA
service generation remains nonzero. Endpoint delegations, including their
nonzero endpoint sequence, are not accepted by this seam because endpoint
parsing and validation remain future broker work.

This is a consumer contract, not an HRM/HNSA implementation or authority
projection. Kotlin and Swift accept no raw commitment, envelope, delegation,
endpoint, URL, provider, or caller-authored record input and perform no CBOR, hashing,
signature, rollback-store, or application-profile validation. The published
superseded authority crate is not a dependency, and no sibling or unpublished
`hns-rs`/`hns-node-rs` checkout is consumed. Shipping uses an immutable
unavailable source because there is no qualified mobile broker or assigned
wallet application profile. The HRM/HNSA consumer, provider, approval, wallet
runtime, and value release gates remain false; there is no UI, endpoint use,
page exposure, or value authorization in this tranche.

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

## Integration and qualification

Run the Rust and platform qualification matrix from [release readiness](release-readiness.md)
and the [device guide](ios-device-validation.md) against the exact source selected
for signing. Verify create/restore, process reopen, scan continuation, peer
maintenance, explicit watch expansion, pending-send balances, and lifecycle
revocation on both mobile platforms. Store screenshots and signed artifacts
must identify that same candidate.

Enabling a website provider requires reviewed provider/service JNI and C
bindings, canonical engine authority for the exact origin and namespace
decision, permission persistence, native approval UI, typed events, and
lifecycle installation/revocation. Mobile must consume that authority rather
than reconstruct it from URLs, toolbar state, proxy readiness, booleans, or JSON.

Seed phrases, private keys, database keys, preimages, capability material, and
authority handles never enter WebView/WKWebView JavaScript or public events.
