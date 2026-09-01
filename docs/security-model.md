# Security Model

## Trust

The app verifies header chainwork, checkpoint ancestry, proof-of-work difficulty, Urkel proofs against header tree roots, DNSSEC chains below HNS delegations, TLSA records, DANE certificate or SPKI matches, and transport downgrade policy.

The proof-backed path does not trust a single peer, a recursive HNS resolver's authenticated-data claim, unsigned DNS answers for HNS names, TLSA answers without a valid proof chain, stale caches, or origin certificates that fail active DANE policy. HNS uses strict local DNSSEC/DANE and never substitutes WebPKI for HNS origin authentication. Proof-anchored authoritative HNS DoH is a transport for the proven delegation. A separate user-configured recursive HNS DoH endpoint is blank/off by default and can recover only from eligible transport failure after direct, owner-authenticated, and opted-in P2P paths; its answers still require the same local validation. DNS-named ICANN HTTPS uses a bounded validating ICANN DoH path, automatic TLSA discovery, and WebPKI only after authenticated TLSA absence or a proven insecure delegation.

## Failure Policy

- HNS proof failure: fail closed.
- DNSSEC validation failure: fail closed.
- TLSA exists but DANE validation fails: fail closed.
- ICANN TLSA is derived only after HTTPS/SVCB service-port and protocol selection: TCP uses `_port._tcp.host.`, while HTTP/3 uses `_port._udp.host.`. A DNSSEC-secure TLSA RRset is enforced. Authenticated absence and a proven insecure delegation may select WebPKI; bogus or indeterminate DNSSEC, malformed TLSA, timeout, and resolver errors fail closed instead of becoming absence.
- Experimental stateless DANE certificate evidence is off by default and retained only as legacy research code. It cannot be combined with the immutable dual-root prepared-plan boundary without adding a second resolution authority. Enabling it on a prepared browser request therefore fails closed before an origin response is exposed; it never falls back to a post-selection live resolver.
- Sync stale: block HNS secure state and show a sync-specific browser error.
- Sync attempts that make no progress must distinguish up-to-date peers from all-peer failure.
- Raw `bestPeerHeight` and the estimated mainnet tip are diagnostic only.
  Authoritative currentness requires sync-status schema version 3, a non-genesis
  locally validated tip within two blocks of the effective target, a locally
  available authoritative HNS name-tree root, at least three recent independent
  peer address groups, and explicitly non-expired target evidence.
- HNS browser state must not show ready unless the proxy is active, native
  status is `attempted`, `synced`, or `up_to_date` with that authoritative
  currentness contract, and the current main-frame HNS gateway response has not
  failed.
- Main-frame HNS gateway 4xx/5xx responses must override ready sync state and show validation failed.
- No-network sync status reads may report `up_to_date` only when a recent
  corroborated effective target is not ahead of a non-genesis local best
  header.
- Gateway exposure beyond loopback: configuration error.
- Browser-visible HNS gateway errors must identify the failing stage without exposing private request bodies.
- Gateway diagnostics must persist only bounded, sanitized stage/host/status/reason events in app-private storage; paths, query strings, request headers, and response/request bodies stay out of default logs.
- Verified HNS non-inclusion must surface as name-not-found instead of origin-address-missing.
- Proof-anchored `hnsdns=1` metadata and RFC 9461 `_dns.<nameserver>` SVCB records may add only RFC 8484 authoritative DoH transport endpoints for HNS-proven nameservers. They do not synthesize origin A/AAAA, HTTPS, or TLSA answers; malformed matching declarations fail closed, and all resulting DNS answers still validate against the HNS-proven DS.
- A whole-browser proxy target must authenticate before host classification, DNS, or dialing. ICANN forwarding accepts only canonical public IP literals or public A/AAAA addresses returned by the runtime's bounded, explicit-bootstrap, WebPKI-authenticated DoH client. NXDOMAIN, truncated, wrong-class, unrelated-owner, ambiguous CNAME, private/special address, and unsafe-port results fail before an origin socket is opened.

## Hardened WebView Profile

The Android WebView shell follows a hardened browser profile derived from Android WebView platform security guidance, OWASP MASVS/MASTG WebView controls, RFC 6454 origin semantics, and the applicable W3C web-platform security standards.

Applied WebView controls:

- JavaScript is enabled for the main browser WebView because general web compatibility requires it, but no JavaScript/native bridge is installed or exposed to untrusted content and default bridge names are removed. The dormant website-provider source returns before WebView mutation behind an immutable false release gate; the separate app-native wallet screen has no WebView.
- Local file access, file-origin cross-access, universal file-origin access, and content-provider access are disabled.
- Mixed active/passive content is blocked with `WebSettings.MIXED_CONTENT_NEVER_ALLOW`.
- Safe Browsing is explicitly enabled where supported by the platform WebView.
- AndroidX WebKit feature checks gate optional WebView, Service Worker, proxy, renderer-process, WebAuthn, Safe Browsing, and speculative-loading APIs before use.
- WebView asynchronous startup is initiated from `Application.onCreate` through AndroidX WebKit so startup work can run before the first browser `WebView` is constructed.
- JavaScript pop-up windows and multiple WebView windows are disabled.
- WebView debugging is tied to `BuildConfig.DEBUG`, so production release builds do not enable WebView remote debugging.
- Main-frame navigation allows only HTTP(S) in WebView plus `about:blank`; recognized external schemes are opened through Android `ACTION_VIEW`, and unsupported schemes are blocked before they can mutate browser state.
- Service Worker interception uses the same native HNS/ICANN gateway policy as normal WebView request interception, with Service Worker file/content access disabled where supported. WebView exposes neither worker request bodies nor their local TLS challenge; bodyless GET/HEAD is supported and body-bearing requests fail closed.
- Renderer hangs and renderer-process exits are handled explicitly so a bad page can be terminated or closed without crashing the whole browser process.
- Cleartext network policy is denied except for the explicit loopback gateway allowance in Android Network Security Config. The gateway binds only to randomized `127.0.0.1` ports while the browser needs proxy support, admits every canonical DNS hostname to the shared dual-root preparation boundary, rejects private/special targets, closes when the main browser activity leaves the foreground, and applies bounded active-client and request admission limits.
- App asset loads should use HTTPS-style app-asset origins or native interception instead of broad `file://` access.

## Android Platform Checklist

The app follows the Android security checklist as a platform baseline:

- Manifest permissions are limited to `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, and the foreground data-sync permissions. Camera access is used only by the native payment-QR scanner; the app does not request contacts, location, SMS, microphone, account, package-visibility, notification, or broad file permissions.
- Only `LauncherActivity` is exported. The browser, settings, diagnostics, history, downloads, proof/TLSA views, resolver trace, native `WalletActivity`, and `WalletSyncForegroundService` are explicitly non-exported. The service only keeps a user-started bounded wallet synchronization visible to Android.
- App backup and device-transfer extraction are disabled for files, databases, shared preferences, root storage, and external app data. Browser history, download records, diagnostics, resolver cache, sync/cache state, and wallet database/key ciphertext remain app-local unless the user explicitly exports or shares data.
- Normal browsing does not enable `file://` or `content://` WebView access. User-initiated downloads use Android DownloadManager into public Downloads, but the system-visible download description does not include the full URL.
- Network Security Config denies cleartext by default and allows cleartext only for the loopback gateway. The gateway binds randomized `127.0.0.1` ports only while browser proxy support is needed.
- WebView JavaScript is enabled for browser compatibility, but no `addJavascriptInterface` or `WebMessageListener` bridge is installed or exposed to untrusted content. The dormant website-provider adapter returns before listener/script mutation while its immutable bridge gate is false. The app-native wallet controller is reachable only from a non-exported native activity. WebSockets remain Chromium-native and traverse the same Rust proxy; the document-start marker performs no hostname classification, and Rust applies the retained per-origin namespace decision before network admission.
- Gateway diagnostic persistence is bounded and stores sanitized stage, host, status, and reason fields only; URL paths, query strings, headers, and bodies are not persisted in default diagnostics.
- Release builds are non-debuggable, minified, resource-shrunk, and require upload-signing configuration before Play release bundle verification can pass.

## Android Privacy Checklist

The app follows the Android privacy checklist as a platform baseline:

- The app requests no dangerous runtime permissions. Sync is scoped to the application foreground, so there is no notification permission prompt or foreground-service notification.
- The app does not request location, nearby device, camera, microphone, contacts, SMS, call log, account, advertising ID, all-files storage, or package-visibility permissions.
- The app does not use background location, location foreground services, device serial numbers, IMEI, SSAID, Advertising ID, or an app-generated cross-install tracking identifier.
- External storage use is limited to user-initiated downloads through Android DownloadManager into public Downloads; app metadata and wallet storage stay in private preferences or app-private files and are excluded from backup and device transfer.
- Sensitive app-to-app sharing uses explicit user actions such as Android share/copy flows or DownloadManager. Sync snapshots stay in-process and internal diagnostic activities are non-exported.
- Production Logcat output avoids browsing URLs, user-entered content, request/response bodies, and resolver secrets; default persisted diagnostics remain bounded and sanitized.
- The Google Play Data safety and privacy policy drafts disclose local browsing data, user-initiated downloads, HNS peer/DNS/web requests, optional P2P relay use, ordinary ICANN DoH, and local deletion controls.

## iOS WebKit Profile

The iOS shell uses one persistent identified `WKWebsiteDataStore` with one authenticated HTTP CONNECT proxy configuration. `allowFailover` is false and the match/exclusion lists are empty, so ordinary ICANN and HNS WebKit traffic share the Rust admission boundary. An absent, stopped, or rejecting proxy is an error; Swift has no route that clears the profile to direct networking.

- Swift performs namespace-agnostic URL parsing. Every canonical DNS main frame, redirect, subresource, Service Worker request, download, and WebSocket shares the no-failover proxy, while Rust prepares and retains an exact per-origin dual-root plan.
- Cross-origin traffic does not rotate or widen a suffix scope because no suffix scope is authoritative. Policy or lifecycle changes revoke the current WebView, credentials, status, and certificate authority before the old proxy is stopped and joined.
- DNS-named ICANN HTTPS/WSS CONNECT terminates locally and reaches the same Rust resolver/TLSA/DANE gateway as Android. Secure TLSA is enforced; authenticated absence or insecure delegation retains WebPKI in Rust. Canonical public IP literals have no TLSA owner and remain bounded opaque CONNECT with WebKit WebPKI. ICANN HTTP uses the bounded Rust forwarder.
- Swift may answer proxy authentication only for the exact live proxy handle, endpoint, and realm. It may accept an expected local HNS or DNS-named ICANN server-trust challenge only after Rust separately confirms the exact live generation, canonical host, and complete leaf certificate DER. Public IP-literal trust remains under WebKit's default handling.
- Proxy credentials, certificate state, trace data, and Rust-owned buffers are memory-only and bounded. Lifecycle revocation becomes visible before any blocking worker join.
- Swift contains no independent HNS resolver, socket transport, HTTP proxy parser, DANE validator, certificate generator, or TLS terminator.
- The committed privacy manifest declares the platform reason APIs used for
  preferences and file timestamps. Physical-device traffic/challenge
  qualification remains unverified; it is not an App Store submission
  prerequisite, but it remains an installed-iOS and ecosystem gate.

## Native wallet and dormant website-provider boundary

The current local Android/iOS stack links the exact pinned
`hns-wallet-mobile` controller to app-native create, restore, open, status,
unlock, lock, one-time recovery, destroy, and single-account controls. Both
shells compose a wallet-owned direct HNS peer controller for synchronized
balance, structurally distinct receive targets, history, names, send review and
broadcast, and closed name/Shakedex actions. Historical HNWR-v1 and current
HNWR-v2 use separate exact five- and six-field decoders, and native approval and
result bundles also use closed, bounded schemas. The controller is not a browser
provider: no wallet secret, action, approval token, or method enters
WebView/WKWebView. These controls are not in the current public
Play, GitHub, or App Store binaries; the underlying lifecycle tranche passed its
fresh-install Android exercise at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. Historical `0.5.8` source
`f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI run
`31402758394` and a fresh Pixel 9 install, and documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; those are historical `0.5.8` results. Historical pre-ECH HNWR-v1
code-bearing source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including Android instrumentation and the complete Apple gate.
Its exact debug APK is artifact `9080493058`, SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`,
package `com.denuoweb.hnsdane.debug`, `0.5.9-debug` / code `50`, with
`arm64-v8a` and `x86_64` and default Android Debug APK-v2 signing. It is not
store signed. The exact APK installed on a Pixel 9 (`tokay`), Android 17 / API
37. An incompatible historical code `49` debug signer was rejected before the
authorized uninstall removed only the debug package/data; production remained
installed and untouched. The on-device digest matched, cold launch succeeded,
and the native wallet screen showed the no-wallet controls and fail-closed read
projection. No wallet, secret, account, credentialed read, or value action ran.
Signed artifacts, current screenshots, store declaration/upload, installed
Android send-review qualification, and the physical-iPhone matrix remain
release gates. The currently reported Android regression is being handled
without deleting or resetting the existing wallet database.

HNWR configuration is loopback-only and accepts a bounded mutable scoped
authorization value that is consumed and wiped. Output is bounded and its
version-specific closed JSON shape, canonical values, exact equal nonzero
account identities, distinct target purposes, unique transaction/name
identities, coherent heights, and envelope length are validated before UI
publication. The product currently
creates no such configuration, so the visible read fields remain fail-closed
and unavailable. The browser proxy credential is not reused as wallet authority.
The app provisions no scoped indexed backend. A pruned indexed/authenticated
node can return indexed confirmation/history, and an existing wallet may reuse its authenticated
retained raw bytes. Fresh restore additionally needs archive-capable raw
transaction bytes or another durable wallet-relevant raw-transaction source
behind the dedicated scoped loopback gateway. The trusted-native exact-text name
import uses only that gateway, stays unavailable without it, and never enters
provider or renderer data. Provider, sending/value, HNSA/HNSR, settlement, exchange, and
marketplace gates remain independently false.

The dormant cross-platform HRM/HNSA wallet consumer does not weaken those
gates. It accepts no raw or legacy authority object and cannot derive service
identity from network or renderer input. A future broker must issue an exact
`hns.named-service/v1` observation and hold its current subject-aggregate or
fenced lease through the one-shot callback. Platform admission binds that
observation to the exact live wallet and rechecks lifecycle state before
acquisition, after acquisition, and under the broker guard. There is currently
no production broker source, application profile, endpoint validator, UI, or
provider/value connection.

On Android, a create-only Android KeyStore AES-GCM key wraps the 32-byte wallet
database key and requires an unlocked device. Borrowed plaintext key arrays are
wiped, and both the Android no-backup root and the network-scoped wallet
directory are hardened to owner-only access before Rust validates or opens the
database. The dedicated non-exported activity uses `FLAG_SECURE`. Recovery output remains a mutable `CharArray`
drawn directly by a non-selectable, non-autofill, non-accessibility custom view.
Restore input is likewise hidden from autofill and accessibility services,
preventing those processes from reading the phrase at the cost of making this
restore flow unavailable to users who require accessibility assistance.
The key is not persisted until the user confirms the one-time phrase; leaving
first wipes mutable key/phrase storage, revokes the controller, and removes the
incomplete database. Native handles are bounded, monotonic, explicitly
deactivated before destruction waits for in-flight state locks, and rechecked
after acquiring those locks so queued stale calls fail. Read synchronization and
contended controller retirement execute off the UI thread; generation, storage
lease, and handle identity are rechecked before projection publication or lease
handoff.

Confirmed-wallet deletion is an independently authorized destructive path.
Both platforms require an unlocked persistent wallet, the current protected
foreground screen, an exact network/account/path/controller-generation lease,
and two confirmations ending in a case-sensitive `DELETE`. The screen first
invalidates read/publication generations and detaches controller ownership,
then holds the storage lease through native lock/close, device-bound key
deletion, and database/sidecar removal. A key-deletion failure blocks file
removal. Once the key is absent, any file failure is retained as an unusable
encrypted-orphan cleanup state and reconciled before the namespace may reopen.
Stale completion callbacks can neither publish into nor delete a newer owner.

On iOS, the create-only database key is a
`kSecAttrAccessibleWhenUnlockedThisDeviceOnly` Keychain item requiring user
presence; the application declares the corresponding Face ID unlock purpose in
its source `Info.plist`. Wallet files use complete file protection and are
excluded from backup. Before recovery confirmation, lifecycle exit wipes
app-owned mutable buffers and deletes the incomplete database; later exits
clear recovery state and lock the controller. Capture state is checked before create/restore, and
capture, screenshot, background, and protected-data notifications trigger
cleanup. The Rust buffer and Swift `[UInt8]` copies are explicitly wiped, but
display and restore input pass through Swift `String` and UIKit-managed text.
Clearing those controls is best effort: deterministic zeroization of managed
or copied Swift/UIKit backing storage is not possible and is not claimed. Read
synchronization runs off the main actor and stale completion is suppressed.
Lifecycle callbacks also detach controller and lease authority immediately,
then serialize native lock/destruction and any incomplete-wallet deletion on a
background queue. The exact lease remains held until that work finishes, and
foreground reentry cannot reacquire it early. Historical HNWR-v1 source passed
its exact Apple app/simulator CI in `31433931682`; that run does not qualify
HNWR-v2. XCTest covers retirement queue/lease behavior and
stale-completion publication-authority predicates, not an end-to-end
credentialed native read in flight. Enabling read configuration still requires
the missing scoped credential/backend/data boundary, and physical-iPhone
qualification remains open.

The website-provider sources remain containment projections. Website schema 1,
private provider ABI 2, and public approval schema 3 are independent version
domains. Provider installation, page-visible wallet methods, approval dispatch,
and value movement each retain an immutable false gate; both adapters are
hardwired to the unavailable implementation and absent from browser-controller
lifecycle code.

Approval display accepts only twelve closed typed summary kinds with bounded
canonical fields and locally derived rows. Provider events accept only thirteen
closed typed payloads through their dedicated event path. Page-visible results
reject inline events and secret/private-field names. Raw approval prompts,
authority handles/revisions, wallet or service sessions, channel identifiers,
event sequences, seeds, keys, passphrases, preimages, and database/capability
secrets cannot enter the public projection. Enabling the website bridge
requires a generated provider binding and a canonical typed engine-authority
join; mobile must not synthesize authority from URLs, security UI, proxy state,
booleans, or page data.

## Experimental P2P DNS relay trust boundary

The P2P DNS relay is an untrusted transport beneath the existing proof-backed
delegated resolver. Mobile relay consumption is off by default and requires
explicit requester opt-in, while preserving an independent preference already
chosen by an existing installation. A legacy compatibility preference is
revoked and never becomes relay consent. The relay is
considered only after current locally validated headers and a matching Urkel
proof have produced an acceptable HNS NS/DS delegation and direct
authoritative UDP/TCP 53 has ended in typed transport unavailability or been
positively classified as intercepted, and owner-authenticated authoritative
DoH has also been unavailable. A canary timeout is inconclusive, not
authenticated absence. A separately configured recursive HNS DoH endpoint may
follow the relay step—which is skipped when requester consent is off—only for
eligible transport failure; it is not enabled by relay consent and cannot
bypass local validation. There is no HNS WebPKI fallback. The mobile browser
does not become an output node. Opaque relayer capacity is a separate
default-on/opt-out network role; serving as an output node remains explicit
operator opt-in.

The peer necessarily learns the qname, qtype, client P2P connection, and source
network address. The ordinary Handshake TCP listener is plaintext; no query
confidentiality is claimed. Relay requests contain no destination address or
port, no ECS, and no stable client identifier. Normal logs and persisted
diagnostics omit qnames and raw DNS messages.

Manual relay configuration accepts only IP-literal `IPv4:port` or
`[IPv6]:port` endpoints, so adding a peer cannot invoke hostname resolution.
The runtime completes a live HSD handshake and verifies the capability in the
peer's current version message before persisting the endpoint. Neither this
probe nor an automatic relay-only handshake promotes the version message's
advertised height into `bestPeerHeight` or any local-chain-currentness decision;
only the header-sync path records peer-height observations.

The relay requester advertises zero local services on its relay-only
connections, including no `SERVICE_NETWORK`, because it does not offer headers,
proofs, or relay service on those connections. Before transmission it enforces
the HIP query profile: one allowlisted `IN` question under a syntactically valid
HNS root, standard query flags and empty response sections, plus exactly one
root-owner EDNS(0) OPT with zero extended RCODE/version, `DO`, a 512-through-4096
payload size, clear reserved flags, and only optional Padding. ECS and all other
EDNS options are rejected.

The relay's response is accepted only as raw input to local DNS parsing and the
existing DS/DNSKEY/RRSIG/NSEC/NSEC3, CNAME/referral, HTTPS/SVCB, TLSA, and DANE
validators. Its AD bit is never authoritative. Unknown/late/duplicate request
IDs, question or DNS-ID mismatches, malformed compression, non-canonical
framing, trailing data, and oversized bodies fail closed and may penalize the
peer. A future unknown transport-status value fails the current exchange,
closes that relay connection, and may be retried through an alternate, but it
does not by itself change peer score or start a cooldown. See
`experimental-hns-p2p-dns-relay.md` for framing, limits, topology, and rollback.

## Review Checklist

- Parsers are bounded and return structured errors.
- Parser fuzz smoke targets cover DNS messages/names/SVCB, HNS resource values, P2P frames/payloads, Urkel proofs, TLSA records, and X.509 SPKI extraction.
- P2P frames reject wrong network magic and payloads above the 8 MB HSD message limit.
- P2P sockets must use bounded frame decoding, connection timeouts, and session-state checks before accepting headers or proofs.
- Header sync must not request additional headers from a peer whose advertised height is not ahead of the local best header.
- Android first-run header sync should use active polling and high-batch native
  runs while behind, then fall back to idle polling only after the validated
  tip is within two blocks of a recent corroborated target.
- Header network I/O, quorum collection, snapshot preparation, and peer
  merging must occur in a private stage outside the exclusive live-browser
  maintenance window. Conditional publication must revalidate the exact
  generation/tip baseline under cross-process publication locks and atomically
  publish headers, peers, and readiness.
- An unchanged-header peer refresh must not invalidate active requests. A
  missing delta, interrupted publication, stale stage, or superseding
  concurrent publisher must fail closed, and final peer evidence must be
  timestamped at completion rather than at the start of a long sync.
- The 144-block canonical proof-cache window is reorganization retention, not
  a freshness allowance. Currentness requires recent agreement from at least
  three independent peer address groups; the raw highest claim and schedule
  estimate cannot authorize resolution, and missing corroboration fails
  closed.
- Transient peer failures must not permanently exhaust the outbound peer pool; malformed consensus data is still scored and cooldown-banned.
- Peer-gossip addresses are advisory only; addr packets are bounded, deduplicated, service-filtered, and still subject to outbound peer scoring before any header or proof data is accepted.
- Version packets use HSD's 88-byte network address format rather than Bitcoin's shorter address encoding.
- Version/verack ordering is accepted in either HSD-observed order before the session enters ready state.
- Advisory or unknown P2P packets are ignored while waiting for required sync packets; they do not advance header/proof state.
- No experimental relay request is sent before a complete handshake or to a peer whose current version message lacks the temporary capability bit.
- Relay-only connections advertise zero local services, including no `SERVICE_NETWORK`; consuming relay DNS does not claim that the requester serves headers, proofs, or relay requests.
- No height advertised during an automatic relay handshake or manual static-relay capability probe is recorded as a peer sync target or used for local-chain currentness; only header-sync sessions may record an observed height.
- Header-observation time is persisted independently from general connection
  time. Proof and relay success may update liveness but cannot refresh an old
  height, and relay/store merges keep each height atomically paired with its
  observation time.
- No relayed answer can set secure state from AD; it must pass the same local delegated DNSSEC and DANE validation as direct authoritative DNS.
- No relay request carries an arbitrary destination, non-IN/multi-question query, a type outside the HIP allowlist, ANY/AXFR/IXFR, ECS, or a non-HNS root. Query header flags, empty answer/authority sections, and the single EDNS(0) OPT's owner, version, extended RCODE, `DO`, payload size, reserved flags, and options are validated before transmission.
- No relay timeout, disconnect, transport status, or malformed response is cached as NXDOMAIN/NODATA.
- A future unknown relay transport status fails only that exchange and connection, with no automatic score change or cooldown solely because the status is unknown.
- No P2P relay fallback is attempted when the local HNS proof is unavailable, mismatched, invalid, or stale.
- Duplicate headers in peer batches are ignored as idempotent sync input; full duplicate-only pages stop the bounded multi-batch loop so a stale peer cannot spin the sync runner, while invalid difficulty bits, invalid proof-of-work, and unknown-parent headers still fail closed.
- No panics on malformed network data.
- No unbounded memory growth from attacker-controlled lengths.
- No Urkel proof request key should be derived from a name that fails Handshake TLD validation.
- No Urkel proof should be accepted unless its BLAKE2b-256 path recomputes the expected tree root for the requested name hash.
- No verified Urkel value should be exposed as resolver records unless its HSD resource payload decodes within bounded type and record limits.
- No HSD Urkel inclusion value should be cached as resolver data until its serialized `NameState` name matches the requested root and only its bounded `data` field is extracted.
- No TCP proof response should be stored for resolver use unless it matches a tracked getproof request and passes Urkel verification.
- No cached verified resource value should be served unless its root label and name hash match the resolver request.
- No chain-anchored cached verified resource value, whether an inclusion or non-inclusion, should be served unless its proof tree root and height match the current local best header and that local chain is current enough for resolution. Sync ticks prune values that are unanchored or not anchored to the current tip; a materially stale local chain fails before delegated DNS or relay transport.
- No persisted verified resource value should be stored or returned unless its root label and name hash are normalized and matched.
- No proven HNS answer should be returned if the proof name hash or root name mismatches the request.
- No verified HNS non-inclusion should be treated as an existing name with an empty record set.
- No HNS origin connect address should be selected from NS glue or another owner name unless that owner is reached through a DNSSEC-validated CNAME chain from the requested origin owner.
- No HNS origin connect address should be inferred from GLUE, SYNTH, `hnsdns=1`, or DNS-server SVCB data. These records only bootstrap nameserver transport; origin A/AAAA selection still requires DNSSEC-secure delegated answers.
- No HNS origin request that starts from root delegation records should be treated as complete until a secure delegated A/AAAA lookup has been attempted.
- No canonical DNS host should be routed to Chromium DNS before the retained
  Rust planner resolves the complete hostname through both HNS and ICANN and
  selects an authenticated namespace plan.
- No out-of-zone HNS nameserver address should be used unless it comes from a separate verified HNS root proof for that nameserver owner.
- No HNS gateway request should fall back to origin-host system DNS when secure resolution produces no A/AAAA connect address.
- No reserved non-HNS single-label name should be routed into the HNS proxy path or shown as HNS browser state.
- No DNS leak for HNS names.
- No DNSSEC delegation should be treated as secure unless at least one DS digest matches a child DNSKEY.
- No HTTPS/SVCB ALPN or service-port binding should be honored unless the binding is parsed, in service mode, owner-scoped, and limited to supported mandatory keys.
- No address-only HNS answer should skip a separate secure HTTPS/SVCB lookup before TLSA service-owner selection.
- No unsupported DS digest type should be treated as a secure delegation match.
- No RRSIG should be evaluated against non-canonical RRset bytes or outside its validity window.
- No RRset should be treated as DNSSEC-secure unless the delegation link and a covering RRSIG both validate.
- No delegated HNS DNS answer should be treated as secure unless it comes from HNS-proven nameserver glue or synth addresses and validates against the HNS-proven DS RRset.
- No authoritative DoH endpoint should be used unless its declaration identifies an HNS-proven NS address and the endpoint uses RFC 8484 HTTPS transport with DNS wire messages. A distinct SVCB/URI target name is authenticated by WebPKI while the connection remains pinned to HNS-proven glue.
- No delegated NXDOMAIN response should be treated as malformed solely because its RCODE is NXDOMAIN; it must either validate as secure NSEC/NSEC3 name-error denial or fail closed.
- No NXDOMAIN response carrying positive Answer-section records, including a
  CNAME, should be accepted as name absence.
- No empty delegated HNS DNS answer should be treated as secure unless an NSEC or NSEC3 no-data proof validates under the delegated zone DNSKEY.
- No delegated CNAME chain should be followed outside the HNS-proven delegated zone or beyond the bounded CNAME-chain limit.
- Repeated copies of one logical CNAME owner/class/target across retained
  A/AAAA/HTTPS observations are one alias assertion; distinct targets for that
  logical owner remain ambiguous and fail closed.
- No child referral below a delegated HNS zone should be followed as secure unless the HNS-proven parent DS validates the parent DNSKEY, the child DS RRset validates under that parent DNSKEY, and the child answer validates under a DS-matched self-signed child DNSKEY.
- No empty child-zone answer below a delegated HNS zone should be treated as secure unless the parent DNSKEY chain, child DS RRset, child DNSKEY RRset, and child NSEC/NSEC3 no-data proof all validate.
- No DNSSEC signature should depend on mixed-case RDATA owner names or signer names.
- No SVCB/HTTPS RRset should be signed or trusted using compressed or non-canonical TargetName bytes.
- No delegated child DNSKEY RRset should be trusted unless its DS RRset is signed by the parent and the child DNSKEY RRset is self-signed.
- No unsupported DNSSEC signature algorithm should be treated as validated.
- No malformed DNSSEC public key should be treated as validated.
- No malformed ECDSA or Ed25519 DNSSEC public key or signature should be treated as validated.
- No HTTPS/SVCB ALPN value should cause the gateway to select an origin protocol that the configured transport does not support; if SVCB disables default ALPN and no supported protocol remains, fail closed.
- No NSEC denial proof should be accepted unless the NSEC RRset signature validates first.
- No authenticated negative result should outlive its SOA or participating
  NSEC/NSEC3 RRSIG expiry, even when another denial record has a longer TTL.
- No NSEC name error should be accepted unless the queried name is covered and the applicable wildcard under the closest encloser is also denied.
- No NSEC3 denial proof should be accepted unless every participating NSEC3 RRset signature validates first.
- No NSEC3 name error should be accepted unless the closest encloser matches, the next closer is covered, and the applicable wildcard is also denied.
- No NSEC3 opt-out proof should set a secure-denial outcome; it is surfaced only as an insecure-delegation outcome.
- No NSEC3 hash algorithm other than SHA-1 should be accepted until a safe transition mechanism is implemented.
- No TLSA downgrade without an explicit policy event.
- No TLSA record should influence HTTPS trust unless its exact `_port._tcp.host` resolver result is DNSSEC-secure.
- Unsigned TLSA bytes may be ignored only when the selected ICANN delegation is
  independently proven insecure; the same bytes under secure, bogus, or
  indeterminate DNSSEC cannot become WebPKI fallback.
- No HNS-strict HTTPS connection should proceed without a DNSSEC-secure TLSA match.
- No HNS HTTPS connection may select WebPKI in place of a DNSSEC-secure TLSA match.
- No HNS address presence with missing required TLSA may be reclassified as
  authenticated HNS absence; it is a root failure that prevents silent ICANN
  selection.
- No recursive HNS DoH endpoint may be selected implicitly, inherited from a historical key, contacted while the new setting is blank, bootstrapped through system DNS, or used after bogus DNSSEC, invalid DNS, a DNS response code, or stale/missing HNS proof state. An explicitly configured endpoint is generation-bound and its answers still require local DNSSEC, TLSA, and DANE.
- No unbounded or panic-prone X.509 parsing for DANE SPKI selector matching.
- No QUIC downgrade without an explicit policy event.
- No local gateway listener beyond loopback and no fixed browser proxy port in normal app startup. Android and iOS intentionally apply the authenticated Rust proxy to their browser data store/WebView without failover to a direct DNS-named origin route. Neither platform keeps a browser proxy listener after its owning foreground browser lifecycle is revoked.
- No proxy session split between the platform token and canonical authority
  runtime. Both identities are derived from the same checked nonzero random
  bytes, while the existing URL-safe token representation remains unchanged.
- No request admission merely because a loopback listener bound successfully.
  A fresh listener may be visible while the canonical authority is degraded,
  but a current non-genesis header on every network, proof storage, a
  policy-permitted resolution transport, and the exact active browser-bridge
  generation must all be factual before an operation receives an authority
  stamp. A genesis-only regtest state is not browser-ready.
- No policy-generation churn for a normalized no-op, and no stale listener may
  clear or revoke a newer generation. Replacement revokes the prior authority
  before the new listener is published; stop and drop compare against their
  exact generation.
- No origin operation may mint authority after maintenance, DNS, or namespace
  classification. One stamp is minted at whole-request entry and carried
  unchanged through the selected transport, status, response, file, or tunnel
  result. A same-generation `Degraded` to `Active` recovery cannot remint or
  revive work admitted before the degradation.
- No response or HTTP 101 head may cross concurrent header maintenance,
  replacement, or revocation between its final checks and publication. Each
  result carries an opaque capability bound to its exact authority stamp and
  the nonzero maintenance epoch captured under the request read lock. Final
  publication reacquires that lock, validates the epoch, then retains both the
  maintenance and canonical lifecycle guards through the head flush. Sync,
  resolver-cache clear, snapshot install, and header reset advance the epoch
  before mutation under the exclusive lock; an older result is rejected if
  maintenance won, while maintenance waits if publication won. A successful
  result's pending sticky namespace binding is committed only inside this
  permit. Stop requests cancellation before waiting on the lifecycle lock, and
  response-body/tunnel work remains cancellation and exact-stamp checked after
  the permit is released. A backend error or invalid response head cannot cause
  the server to synthesize an unstamped fallback head.
- No direct file response may write origin bytes to its public target before
  final exact-stamp authorization. Bytes remain in an RAII-cleaned,
  same-directory staging file and are renamed into place only while the
  canonical publication guard is held. Android raw/JNI wrappers propagate
  post-parse runtime errors as no result; they cannot replace a rejected
  admitted response with fresh unstamped 500 bytes or a file body.
- No vendored IANA/root-zone list may authoritatively select a namespace. Every canonical complete hostname must be resolved independently through HNS and ICANN, with HNS-only, ICANN-only, convergent, divergent, neither, and indeterminate outcomes handled explicitly before any origin connection.
- No successful response may expose a newly selected divergent namespace until
  its sticky selection has been durably recorded. Persistence failure withholds
  the response. Once persistence increments the binding revision, stale
  in-memory plans are unreachable; best-effort reclamation of those plans
  cannot turn the committed selection into an unpublished response.
- No dual-root trace may collapse unselected-root attempts into the selected
  namespace attribution. All DNS attempts remain available as partitioned HNS,
  ICANN, or diagnostic root evidence while top-level trust fields describe only
  the selected plan.
- No canonical schema-v2 status may use a cache/configuration fingerprint in
  place of `decision_fingerprint` for the retained `NamespaceDecision`, attach
  an HNS chain anchor to an ICANN-selected result, or infer HNS transport from
  an unrelated qname/type or the ICANN half of a dual-root trace.
- No canonical failure status may discard a typed HNS or ICANN `RootFailure`.
  DNSSEC bogus must remain bogus, indeterminate must remain indeterminate, and
  neither may be represented as authenticated TLSA absence or ordinary
  namespace nonexistence. A post-selection failure retains the selected
  decision and fingerprint when that decision exists.
- No status may re-read a same-origin shared cache entry in place of the exact
  plan retained by its request. Concurrent cache replacement cannot change one
  request's root-failure kind. A generic transport or WebPKI failure that lacks
  typed trust evidence remains unavailable rather than fabricating DANE or
  origin-SNI failure. A certificate-association mismatch is marked by the
  verifier and carried through HTTP/1.1, HTTP/2, HTTP/3, and TLS Upgrade as the
  typed DANE failure; that marker alone does not claim origin-SNI failure.
- No legacy P2P relay status may invent a registry fingerprint or protocol
  version that was not retained from negotiation. A successful path whose
  exact evidence is unavailable or unrepresentable is reported explicitly as
  canonical-status unavailable.
- No origin fetch unless the gateway resolution name matches the requested origin host.
- No intercepted HNS redirect should be followed unless the target has the same scheme, host, and effective port and the redirect chain stays under the configured bound.
- No main-frame HNS gateway 4xx/5xx response should leave the toolbar in verified state.
- No local gateway request flood should create unbounded worker tasks, HNS resolution calls, or per-host limiter state; excess requests fail closed with `429 Too Many Requests`.
- No gateway diagnostic event should persist URL paths, query strings, request headers, request bodies, or response bodies; the app-private event store remains bounded to recent sanitized failures.
- No HNS origin connect attempt should use origin-host system DNS when secure resolution has not produced an explicit connect address.
- No insecure resolver result when gateway secure-resolution mode is enabled.
- No proxy request body should be forwarded or dropped unless HTTP/1.1 framing is unambiguous and supported.
- No origin HTTP response body should be accepted unless HTTP/1.1 framing is unambiguous and supported.
- No whole-browser ICANN request should invoke system hostname resolution, connect to an address not returned by the explicit runtime address boundary, follow an invalid/ambiguous DoH CNAME chain, accept a non-IN answer, or dial a private/special address or unsafe port.
- No decoded chunked origin response should be exposed to WebView with stale `Transfer-Encoding` or mismatched `Content-Length` framing; native gateway file-backed bodies are returned with fixed decoded lengths.
- No WebView SSL error should call `proceed()` unless the requested URL is an admitted HNS or DNS-named ICANN HTTPS URL and the presented certificate's full DER bytes match the exact host and currently published Rust proxy generation.
- No HNS WebSocket or HTTP Upgrade request should be silently downgraded to a normal GET by stripping hop-by-hop Upgrade headers; these requests must enter the native stream tunnel after HNS resolution, HTTPS/SVCB policy, and DANE validation, and fail closed if the native tunnel path is unavailable or validation fails.
- No WebView/WKWebView wallet bridge may be installed while any website-provider release gate is false or without the generated provider binding and canonical typed engine-authority join. Native app controls do not satisfy those gates; the dormant projection is not provider availability, and native operations remain outside page-script reachability.
- No WebView `file://` or `content://` access should be enabled for normal browsing; app assets must use safe app-asset origins or native response interception.
- No main-frame non-HTTP(S) URL should be passed through to WebView except `about:blank`; external schemes require explicit Android intent handling and unsupported schemes are blocked.
- No mixed-content downgrade should be allowed inside the WebView.
- No production build should enable WebView debugging.
- Browser proxy listeners bind randomized `127.0.0.1` ports only while their owning browser lifecycle is active. Android and iOS apply no-failover browser-wide proxy routing: every canonical DNS hostname reaches the native persistent-runtime dual-root gateway, while public IP literals use only the explicit bounded address path. The proxy enforces authentication, bounded concurrency/framing, header sanitization, streamed responses, exact live certificate authorization, and joined teardown.
