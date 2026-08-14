# Mobile native wallet and website-provider boundary

This checkout contains two deliberately separate surfaces:

- Android and iOS app-native wallet controls backed by the pinned
  `hns-wallet-mobile` controller at
  wallet source `49afe81abce3d3f1a9309e26962731e181e43051`; and
- a website-facing wallet-provider projection that remains dormant and cannot
  mutate WebView or WKWebView.

That wallet revision consumes `hns-rs 0.3.0` source
`88ed7c64db52a6fcfce4146a8fc17b1377dfcc8e`. Mobile source policy, its lockfile,
and generated notices bind the complete reviewed protocol → wallet chain.

The configured `0.5.10` candidate is Android code `51`, embedded Rust `0.5.9`,
and iOS build `60`. Historical `0.5.8` application source
`f21bee1c3afccd06604dc99fccb51528e2441055` passed exact Required CI run
`31402758394`, including Android build/unit/native instrumentation,
Rust/supply-chain, and the complete Apple
ABI/XCFramework/app/simulator gate, after the underlying native-wallet tranche
passed a fresh-install Pixel 9 lifecycle exercise. Documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` then passed the same full matrix in
manual CI run `31411048376`. That evidence predates the HNWR read projection and
remains historical. Historical HNWR-v1 code-bearing `0.5.9` source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including its HNWR-v1 Android and complete Apple gates.
Current HNWR-v2 code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
`31807520618`, including repository policy, Rust/supply-chain, Android
build/unit, API 37 native instrumentation, the complete Apple
ABI/XCFramework/app/simulator gate, and Required CI. CodeQL runs `31807519998`
and `31807520229` also passed. Debug artifact `9222123624` has
artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is debug-only rather than store signed.
Exact debug APK artifact `9080493058` has SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`;
inspection confirms `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`,
minimum API 30, target API 37, `arm64-v8a` + `x86_64`, and default Android
Debug APK-v2 signing.
It is not store signed. The exact APK installed and cold-launched on a Pixel 9
(`tokay`), Android 17 / API 37. The incompatible historical code `49` debug
update failed safely; the authorized reinstall removed only the debug
package/data and left production installed and untouched. The on-device digest
matched. `WalletActivity` showed the no-wallet controls and fail-closed read
projection with disabled value/marketplace copy. No wallet was created/restored
and no secret, account, credentialed sync, or value action ran. Signing, fresh
commit-bound screenshots, store declaration/readback and submission,
credentialed wallet qualification, and the physical-iPhone matrix remain open.

The public Google Play `0.5.6` / code `47`, GitHub Android `0.5.7` / code `48`,
and App Store `0.5.5` / build `57` binaries predate the native controller and
remain historical wallet-free releases.

## Native app controls in the 0.5.10 release candidate

Both platform shells now link a narrow native controller for:

- create and one-time recovery display;
- restore from a recovery phrase;
- open, status, unlock, lock, and controller destruction;
- exactly one local non-value Handshake account identity; and
- a strict read-only projection and native UI for synchronized HNS balance,
  distinct payment and name-transfer receive targets, transaction history,
  tracked names, and module status.

The read projection is present in source but unavailable in the installed
candidate because neither product shell provisions its required scoped loopback
credential or indexed wallet backend. There is no name-import/tracking ingestion.
This slice cannot send or move value, perform name operations, act as HNSA or
HNSR, settle, exchange, use Shakedex/Denuo, or operate a P2P marketplace. It is
not connected to page JavaScript. No website provider is installed or announced,
and no page can invoke these native controls.

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

## Synchronized HNS read boundary

The Rust JNI and Apple C ABI compose
`MobileHnsReadController<HnsNodeRpcBackend>` only after an already durable wallet
is reopened. Configuration accepts one nonzero IPv4 loopback port plus a bounded
mutable authorization value; remote host, URL, and proxy inputs do not exist.
The authorization buffer is consumed and wiped. A successful synchronization
returns one bounded HNWR-v2 envelope carrying strict JSON for balance, distinct
ordinary-payment and name-transfer receive targets, transaction history, known
names, and coherent tip-bound module status. Android and iOS preserve HNWR-v1
as its exact historical five-field shape and require exactly six fields for v2;
they reject cross-version shapes, malformed headers, unknown fields, unequal or
zero target accounts, conflated targets, duplicate identities, noncanonical
values, inconsistent heights, and oversized output before UI publication.

Neither application controller currently creates or passes that configuration.
The wallet screen therefore shows the read rows and a fail-closed unavailable
message, but cannot populate them. The browser's ordinary authenticated proxy is
not silently reused as wallet authority. A pruned node with wallet indexing and
scoped RPC authentication can serve indexed confirmation/history and
authenticated raw bytes retained by an existing wallet. Fresh restore
additionally needs archive-capable raw transaction bytes or another durable
wallet-relevant raw-transaction source behind the dedicated scoped loopback
gateway. Known names remain empty until a reviewed name-import/tracking path
exists.

Both platforms run read synchronization away from the UI thread and require the
exact generation, lease, and controller identity before publishing. Contended
native controller retirement is likewise handed to a background worker. On iOS,
lifecycle callbacks immediately detach UI authority and transfer the controller
with its exact storage lease to one serial retirement queue; that lease is
released only after native lock/destruction and any incomplete-wallet file
deletion finish. Foreground reentry waits for that handoff and stale read
completion cannot publish. Current HNWR-v2 source passed its exact Apple
app/simulator CI in `31807520618`. XCTest
covers the retirement queue/lease behavior and
stale-completion publication-authority predicates, not an end-to-end
credentialed native read in flight. iOS product wiring still may not supply a
credential until the scoped credential/indexed backend/data boundary exists,
and physical-iPhone qualification remains open.

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
endpoint, URL, provider, or legacy-record input and perform no CBOR, hashing,
signature, rollback-store, or application-profile validation. The published
legacy authority crate is not a dependency, and no sibling or unpublished
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

## Remaining integration and qualification

The historical `0.5.8` application source at
`f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI run
`31402758394`; its CodeQL and quality workflows are also green. This evidence
predates the `0.5.9` synchronized-read tranche. The pre-ECH `0.5.9` source passed
full CI `31433931682`; current HNWR-v2/ECH-and-sync-telemetry code-bearing
source passed full platform CI `31807520618` and both CodeQL runs. Before
release, fresh App Store screenshots must be bound to the
exact release checkout selected for signing, signed artifacts must pass their
archive gates, and both stores' privacy/category answers must be reconciled
with the native local data and visible unavailable read rows. Those release
gates do not authorize the dormant website projection, a read
credential/backend, or any value capability.

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

The underlying lifecycle tranche has portable Rust, bridge, and platform
coverage, passed the complete macOS ABI/XCFramework/app/simulator workflow, and
passed a fresh Android reinstall with create/confirm/unlock/lock/process-reopen
and mainnet/testnet storage isolation. The exact historical `0.5.8`
repin/version/metadata commit passed remote CI. The HNWR-v2 projection has
focused Rust, Kotlin, and Swift coverage and passed exact full CI
`31807520618`; historical HNWR-v1 exact debug APK evidence still covers only
the installed shell and fail-closed UI projection described above. The current
product still needs backend/data,
credentialed read, and create/restore lifecycle qualification. Signed-product
gates, current screenshots, store declaration readback/upload, and the
physical-iPhone matrix remain. Those facts are not evidence that the published
apps contain these controls.
