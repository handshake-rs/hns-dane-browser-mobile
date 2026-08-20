# Complete Rust Handshake Ecosystem: Mobile Delta Audit

Last audited: 2026-08-14

This audit maps this checkout only to `Complete Rust Handshake Ecosystem.pdf`
(57 pages, SHA-256
`51dc7363ecc7c597c11de531fbeb1f45f3c6997a4d7b2c5065cd4be9681e7868`).
It does not claim that the coordination-wide PDF is complete.

- Repository: `https://github.com/handshake-rs/hns-dane-browser-mobile.git`
- Starting commit: `6c1d7888ae804a29ab34051cb1267057942ad0a0`
- Working branch: `main`
- Platforms in scope: Android WebView/JNI and iOS WKWebView/Apple C ABI

Public-release successor: Apple published iOS `0.5.5` on 2026-07-31, and the
public record still reports it as current on 2026-08-09. Any later references
to `WAITING_FOR_REVIEW` are retained submission evidence, not current status.
The public iOS `0.5.5`, Play Android `0.5.6`, and GitHub Android `0.5.7`
descriptions accurately advertise browser releases with no wallet or exchange;
those artifacts predate the native controller source described below. Current
source is the `1.0.0` release candidate (Android code `52`, embedded Rust
`1.0.0`, iOS build `61`). The preceding HNWR code-bearing source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI run
`31433931682`, including the complete Android and Apple gates and aggregate
Required CI. Exact debug APK artifact `9080493058` has SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`.
It is a default-debug-key APK, not a store-signed release artifact. That exact
APK was installed on a Pixel 9 (`tokay`), Android 17 / API 37, after an
incompatible historical code `49` debug update safely failed and an authorized
debug-package-only reinstall left production untouched. The installed digest,
cold launch, and fail-closed wallet UI projection passed. Historical `0.5.8`
source
`f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI run
`31402758394` and a fresh Pixel 9 install. Documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; those runs remain historical installed-device evidence only.
Earlier HNWR-v2 code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
`31807520618`, including repository policy, Rust/supply-chain, Android
build/unit, API 37 native instrumentation, the complete Apple
ABI/XCFramework/app/simulator gate, and Required CI. CodeQL runs `31807519998`
and `31807520229` also passed. Exact current application source
`adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete manually
dispatched CI matrix in run `31835813994`: repository policy,
Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
the complete Apple ABI/XCFramework/app/simulator gate, and aggregate Required
CI all succeeded. CodeQL runs `31833858421` and `31833858650` also passed.
Historical debug artifact `9222123624` has
artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is debug-only rather than store signed.
Scoped credential/indexed backend/data provisioning, signed store artifacts,
fresh screenshots, store declaration/readback and upload, and the physical-iPhone
matrix remain open. This checkpoint is not evidence that a public store build
ships wallet controls.

## Requirement Status

| PDF requirement | Status in this checkpoint | Evidence or remaining work |
| --- | --- | --- |
| Retain Android, iOS, Kotlin, Swift, JNI, Apple ABI, WebView, WKWebView, lifecycle, packaging, store metadata, and CI | Retained | The existing platform shells and release workflows remain. The JNI and C ABI keep their legacy field layout for upgrade compatibility. |
| Atomic staged header synchronization | Implemented locally | Network I/O, quorum collection, snapshot preparation, and peer merging occur in a private SQLite stage. Generation-and-tip-bound publication atomically exposes headers, peers, and readiness under cross-process locks; unchanged-header peer refresh does not invalidate active requests, and incomplete or superseded state fails closed. On Android, the pinned Rust 1.92 toolchain requires the target-local `libc::flock` shim because its stable `File::lock` implementation returns `Unsupported`; equivalent upstream support is merged for Rust 1.98. |
| Full-host dual-root namespace decision | Implemented through the standalone engine contract | Android and iOS send every canonical DNS host to one retained Rust preparation boundary. HNS and ICANN independently resolve the complete host, A and AAAA endpoint sets, aliases, HTTPS/SVCB service policy, and transport-aware TLSA owner. The engine distinguishes HNS only, ICANN only, convergent, divergent, and neither; explicit pin precedes sticky success and the ICANN default. The selected immutable plan is the sole source of endpoints, protocol, trust, trace, and errors, and later DNS is rejected. Android Proof Details now also consumes this retained decision instead of treating the namespace-agnostic native gateway as ICANN. The IANA list is no longer an authoritative browser classifier. |
| HNS HTTPS never falls back to WebPKI | Implemented locally | The selected HNS plan requires secure TLSA/DANE. Missing, insecure, bogus, or mismatched evidence remains fail-closed. Secure HNS address presence without the required TLSA is a root failure, not authenticated namespace absence, so an ICANN plan cannot hide the unresolved HNS trust policy. |
| Automatic ICANN DANE with constrained WebPKI | Implemented through the standalone engine contract | Every DNS-named HTTPS/WSS request enters the dual-root gateway. `hns-icann-dane` derives `_port._tcp.host.` or `_port._udp.host.` from the actual retained service plan, enforces DNSSEC-secure TLSA, and admits WebPKI only for authenticated denial or a proven insecure delegation. ICANN DoH connects only to pinned bootstrap addresses while authenticating the configured hostname. Bogus/indeterminate DNSSEC, malformed TLSA, timeout, resolver error, and invalid owner derivation fail closed. |
| No implicit/default recursive HNS resolver; explicit recovery is constrained | Implemented locally | Blank is the default and makes no recovery request. A separately configured bounded HTTPS RFC 8484 endpoint is eligible only after direct authoritative UDP/TCP, owner-published proof-anchored authoritative DoH, and any independently opted-in P2P requester path encounter interception or transport failure. Its hostname is bootstrapped only through validating ICANN DoH, connections require public addresses and WebPKI, and every returned HNS answer still passes local proof, DNSSEC, TLSA, and DANE validation. Bogus DNSSEC, invalid DNS, DNS response codes, and stale or missing HNS proof state are terminal. |
| P2P DNS Relay requester: On/Off | Implemented | Both native settings surfaces expose relay consumption. Fresh installs default off and require explicit opt-in; existing independent requester choices are preserved. The browser does not become an output node. The separate network-role policy is opaque relayer capacity default-on/opt-out and output-node serving opt-in. |
| Explicit migration without turning old HNSDoH consent into new consent | Implemented | Android and iOS permanently tombstone the historical resolver key and never copy it into the distinct recursive-recovery key. Former resolver compatibility consent never enables relay consumption or recursive recovery; both controls start independently off until an explicit choice. |
| P2P ODoH: Preferred/Required/Direct Allowed/Off | Not implemented in this checkout | No HIP #77 requester, HPKE/ODoH runtime, status model, or native control exists here. Do not represent the current direct relay as ODoH or query-confidential. |
| HNSR: Off/Client/Endpoint | Not implemented; upstream mobile-safe facade required | Qualified upstream engine source contains reviewed HNSA admission and HNSR requester APIs, but the umbrella facade is not a mobile dependency because it currently pulls public OpenSSL-backed DANE/DNSSEC crates into Android and Apple closures. Android and iOS now share only a dormant one-shot wallet consumer for opaque broker-issued `hns.named-service/v1` authority; it has no shipping source, parser, endpoint/profile validation, requester, transport, UI, or product permission. A mobile-safe upstream dependency boundary, proof authority, rollback-resistant persistence, authenticated transport, lifecycle, network-change, renewal, withdrawal, and stale-generation work remain required. Endpoint, opaque-relay, rendezvous, provider, and plaintext roles remain unavailable. |
| Native wallet lifecycle, reads, and exact-name import | Current exact-name-import source CI passed; product backend and release gates open | Android JNI and the Apple C ABI link `hns-wallet-mobile` from wallet `0.1.0` source `2061a27e0358c7f00fcc70497ef97f9b89d569da`, with `hns-rs 0.3.0` closure `88ed7c64db52a6fcfce4146a8fc17b1377dfcc8e`, to native lifecycle controls, one HNS account identity, strict HNWR-v2 UI, and a closed HNWI-v1 trusted-native exact-text name import. The import preserves UTF-8 bytes, returns one minimized summary, refreshes rows after success, treats invalid input as non-poisoning, and locks on runtime faults. Legacy HNWR-v1 and v2 use separate exact decoder shapes. Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete Rust, Android, Apple, and Required CI matrix in run `31835813994`; CodeQL runs `31833858421` and `31833858650` also passed. No wallet was created or restored. The product provisions no scoped loopback credential/indexed backend, so reads and import remain fail-closed unavailable. A pruned indexed/authenticated node can support current-wallet indexed evidence, while fresh restore needs a durable archive-capable raw-tx source. Provider, send/value, settlement, exchange, HNSA/HNSR, Shakedex/Denuo, and P2P-market gates remain false. Signed artifacts, current screenshots, store declaration/upload, credentialed Android read/create/import qualification, and the physical-iPhone matrix remain open. |
| Consume the standalone `hns-dane-engine` | Adapter/contracts unified; dormant platform consumer added; facade deferred | Every direct mobile engine adapter and all five canonical contracts resolve from exact `0.2.1` source `65c397e8347f37085ea67d2c9c745ce896328e64`; the temporary crates.io patch bridge is removed, so current Cargo type identity is supplied by one source graph. The umbrella facade, `hns-light-chain`, and `hns-p2p-transport` are not in the mobile graph because the facade currently brings public OpenSSL-backed DANE/DNSSEC crates into shipping target closures. The platform-only HRM/HNSA seam defensively checks and one-shot consumes an opaque exact-current broker result but cannot populate or verify one. Upstream must provide a mobile-safe HRM/HNSA/HNSR API boundary before activation. The stable mobile DNS-relay boolean still maps only to the separate shared HIP-76 requester policy (`false` → `Disabled`, `true` → `Auto`), while the normalized recovery URL maps to generation-bound `user_configured_recursive_hns_doh`. Live resolution remains direct authority UDP/TCP → owner-published authenticated authoritative DoH → independently admitted DNS relay → configured recursive recovery; unsupported ODoH, HNSA/HNSR runtime, provider, and legacy roles remain disabled. Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete Rust, Android, Apple, and Required CI matrix in run `31835813994`; CodeQL runs `31833858421` and `31833858650` also passed. |
| Browser authority state machine and exact-stamped results | Implemented at the shared Rust boundary | One checked random session supplies the unchanged proxy token and canonical runtime identity. Mobile policy revisions map exactly to canonical generations without no-op churn. A current non-genesis header on every network, proof/transport readiness, listener publication, exact-generation replacement/revocation, one whole-request stamp minted before DNS/classification, sticky binding plus exact-result response-head publication, staged-file commit, and tunnel I/O revocation all use the canonical state machine. Android JNI suppresses post-admission errors instead of generating unstamped output. Typed success and root-failure schema-v2 status uses the same entry stamp and request-local exact plan; bogus DNSSEC remains distinct from absence and untyped WebPKI/transport failures remain unavailable. The stable JNI and Apple C ABI layouts intentionally remain unchanged. |
| Relay/ODoH observability | Partial | Existing relay traces distinguish the relay from authoritative transports and report local DNSSEC/TLSA/DANE decisions. The schema-v2 adapter refuses to invent a relay registry fingerprint or protocol version when the legacy client did not retain negotiated identity, and reports explicit unavailability instead. ODoH privacy policy, proxy/target separation, and HIP #77 runtime evidence remain unavailable because ODoH is not implemented. |
| Foreground/background and browser restart qualification | Partial | Existing lifecycle and proxy-revocation tests remain. Targeted exact-signed code 47 checks passed on a Pixel 9 for upgrade with preserved data, cold launch, manual sync, HNS browsing, and corrected HNS/ICANN Proof Details presentation. The wallet lifecycle tranche then passed fresh reinstall, create/confirm, unlock/lock, process reopen, private file-mode, and mainnet/testnet isolation checks; its dated Apple evidence remains Required CI `31393998309`. Historical `0.5.8` source `f21bee1` and docs parent `ce9c09a` passed their recorded CI. The `0.5.9` HNWR tranche and iOS retirement queue/lease plus stale-completion predicate tests passed exact CI `31433931682`; earlier HNWR-v2 source `986accb7d86d220af63187031e629a9ce69d71e5` passed the corresponding platform matrix in `31807520618`. Exact current source `adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete Rust, Android, Apple, and Required CI matrix in `31835813994`, and both current CodeQL runs passed. The exact code `50` debug APK installed and cold-launched on a Pixel 9, and its fail-closed wallet UI projection was inspected without creating/restoring a wallet or running a credentialed read. The broader mobile-network, requester/recovery, Service Worker, download, WebSocket, cross-origin, Android restore/background, credentialed wallet/import, current signed-product, and real-iPhone matrix remain open. The iOS physical-device matrix is not an App Store submission prerequisite but remains an installed-device ecosystem limitation. The PDF's unimplemented ODoH/HNSR lifecycle cases remain future work. |

## Security Invariants for the Current Feature Set

- An HNS request uses a current local header/proof path before delegated
  authoritative DNS, optional requester-only P2P relay consumption, or
  user-configured recursive recovery.
- Relay answers are untrusted DNS input and still require local DNSSEC, exact
  TLSA, and DANE validation.
- When a delegated UDP answer fails DNSSEC, a matching response to the bounded
  TEST-NET canary confirms transparent port 53 interception. That cached result
  suppresses TCP and remaining direct nameservers. Resolution tries
  proof-authenticated authoritative DoH, then the policy-admitted P2P requester
  only under explicit opt-in. A user-configured recursive HNS DoH endpoint is
  eligible last, only for interception or transport failure. Failure of all
  admitted alternatives remains typed and fail-closed.
- Each selected HTTPS/SVCB record produces ordered HTTP/3, HTTP/2, and RFC 9460
  implicit-or-explicit HTTP/1.1 candidates with transport-derived TLSA owners.
  HNS advances only after secure TLSA absence for the current candidate;
  insecure, bogus, and malformed TLSA evidence is terminal.
- Blank recursive-recovery policy fails closed after the admitted
  direct/owner/P2P paths. A configured endpoint cannot recover bogus DNSSEC,
  invalid DNS, DNS response codes, or stale/missing HNS proof state and cannot
  reopen HNS WebPKI through persisted settings, internal headers, JNI, or the
  Apple C ABI.
- DNS-named ICANN HTTPS is outside the HNS proof path but still uses validating
  bounded ICANN DoH and automatic TLSA discovery. Secure TLSA is enforced;
  WebPKI is available only after authenticated absence or insecure delegation.
  Public IP literals have no TLSA owner and retain bounded WebPKI.
- The legacy persisted resolver key is a permanent migration tombstone, not
  consent for the distinct recursive-recovery key. Stable ABI fields carry only
  a freshly normalized explicit recovery choice.
- The legacy stateless-DANE flag cannot be represented by a prepared immutable
  namespace plan. Enabling it fails closed instead of performing a second live
  lookup after namespace selection.
- One logical CNAME repeated across retained A, AAAA, or HTTPS observations is
  deduplicated by owner/class/target, while distinct targets remain ambiguous
  and fail closed. An NXDOMAIN response that also carries positive Answer data,
  including a CNAME, is rejected rather than cached as absence.
- Authenticated negative TTL is capped by the SOA and every participating
  NSEC/NSEC3 signature expiry. Unsigned TLSA bytes are ignored only under an
  independently proven-insecure ICANN delegation; secure, bogus, and
  indeterminate outcomes remain strict.
- A successful divergent-root selection is persisted before its response is
  exposed. If sticky-state persistence fails, proxy, direct, file-backed, and
  tunnel paths withhold the success. Once the binding revision changes,
  poisoned best-effort cache reclamation cannot suppress the already committed
  response.
- Traces retain all resolver attempts in partitioned HNS, ICANN, and diagnostic
  root evidence. Top-level namespace and trust fields remain attributable only
  to the selected immutable plan.
- A successfully bound listener is not proof of authority readiness. Fresh or
  stale local state leaves the listener degraded and non-admitting until
  a current non-genesis header on every network, proof storage, a policy
  transport, and the active bridge are factual. The iOS shell additionally
  keeps navigation queued and the proxy coordinator suspended until matching
  schema-v2 currentness, revoking it again if that evidence expires.
- One authority stamp is minted at whole-request entry before DNS or
  classification and carried unchanged through origin transport, status, and
  the result publication capability. Sticky namespace binding and response or
  HTTP 101 head flush occur under the same exact permit and canonical lifecycle
  lock used by replacement and revocation. Same-generation recovery cannot
  revive a stale result; unstamped generated backend/JNI errors are suppressed.
  File output is staged and atomically renamed only at final authorization. The
  permit is released before potentially blocking body/tunnel work, and stop
  signals cancellation before waiting on revocation.
- Canonical status uses `decision_fingerprint` from the retained namespace
  decision, the exact selected HNS qname/type transport event, and no HNS chain
  anchor for ICANN. Each request retains its exact plan independently of the
  shared cache. Typed root failures survive classification and status
  construction, so bogus/indeterminate DNSSEC cannot become authenticated TLSA
  absence; generic WebPKI/transport errors do not fabricate trust evidence.
  Verifier-native certificate-association failure remains typed through
  HTTP/1.1, HTTP/2, HTTP/3, and TLS Upgrade, while origin-SNI stays unavailable
  unless separately proved.
  Cached/unrepresentable evidence and legacy relay transport without negotiated
  registry identity are explicit unavailable states.

## Qualification Boundaries

Portable Rust, Android source, and host Apple-ABI checks can run on Linux. The
complete iOS gate still requires macOS, Xcode 26.5/26.6, the iOS 26.5 SDK, and
an iOS simulator; signed device behavior requires a separate physical-device
pass. Android instrumentation requires an installed SDK/NDK and a device or
emulator. Required CI run `30484282637` completed the focused
fresh-runtime regression and paired HNS/ICANN Proof Details activity tests on
an API 37 x86_64 emulator at workflow-only descendant
`cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`. The signed store binaries remain
tied to shipping source `417af67efd68198de4871c0a339d1e456b60cb68`.
The pre-ECH `0.5.9` code-bearing source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed the complete CI matrix in
run `31433931682`. Its exact debug artifact subsequently passed installed-shell
and fail-closed-wallet-UI inspection on a Pixel 9. This remains debug evidence,
not a store-signed, credentialed wallet/read, value/marketplace, or iPhone
result.

The consolidated mobile engine adapters and canonical contracts resolve from
pinned `hns-dane-engine 0.2.1` source
`65c397e8347f37085ea67d2c9c745ce896328e64`; the former compatibility patch
bridge is gone and a standalone checkout no longer depends on the coordination
workspace layout. This ECH-and-sync-telemetry pin passed exact-source platform
qualification in run `31807520618`. The umbrella facade and its HNSA/HNSR
protocol types remain an
upstream mobile-portability prerequisite and are not in this graph. The
Kotlin/Swift wallet-consumer seam accepts only opaque broker-issued current
authority and is not a substitute for those protocol types or their durable
verification state.

The source covers normal navigation, same- and cross-origin redirects through
the live proxy, subresources, native WebSockets, bodyless Service Worker GET/HEAD
requests, and native downloads under the same per-origin dual-root preparation.
Android's compatibility/Service Worker
interceptor cannot recover request bodies that WebView does not expose, so those
requests fail closed; its manual redirect fallback is same-origin. Canonical
public IP literals retain bounded WebPKI compatibility only through a live
opaque proxy tunnel and fail closed when that tunnel is unavailable. WSS,
Service Worker, download, HTTP/3, renderer restart, and lifecycle behavior still
require the physical-device matrices—portable tests do not prove WebView or
WebKit network-process behavior.

## Checkpoint Verification

### Current Exact-Name-Import Source Evidence

- Exact application source `adb9c506fe88c82b0317fd60c12fd6a9702753ed`
  passed full manually dispatched CI `31835813994`: repository policy,
  Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
  Apple ABI/XCFramework/application/simulator, and aggregate Required CI all
  succeeded. CodeQL runs `31833858421` and `31833858650` also succeeded.
- This evidence qualifies the exact source build, tests, and static analysis.
  It is not a store-signed artifact, credentialed backend/read/import result,
  installed-device result, screenshot set, store readback, upload/submission,
  or physical-iPhone result.

### Earlier HNWR-v2 Source Evidence

- Code-bearing commit `986accb7d86d220af63187031e629a9ce69d71e5`
  passed full CI `31807520618`: repository policy, Rust/supply-chain, Android
  build/unit, API 37 native-runtime instrumentation, Apple ABI/XCFramework/
  application/simulator, and Required CI all succeeded. CodeQL runs
  `31807519998` and `31807520229` also succeeded.
- Debug artifact `9222123624` is exact-source bound. Its artifact-archive
  SHA-256 is
  `0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c` and it
  expires 2026-08-17. This is archive provenance for a debug-only artifact, not
  an APK digest, store signature, upload, or installed-device result.

### Prior `0.5.9` Source and Debug-Artifact Evidence

- Code-bearing commit `893ba8271787f1ab7247fa78ed8787462b5542fc`
  passed full CI `31433931682`. Policy, Rust/supply-chain, Android build/unit,
  API 37 native-runtime instrumentation, Apple ABI/XCFramework/application/
  simulator, and Required CI all succeeded. CodeQL `31433931259` and Code
  Quality `31433931278` also succeeded.
- Artifact `9080493058` produced the exact 65,680,703-byte debug APK. Its
  SHA-256 is
  `7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`.
  Inspection confirmed package `com.denuoweb.hnsdane.debug`, version
  `0.5.9-debug` / code `50`, minimum API 30, target API 37, launchable activity
  `com.denuoweb.hnsdane.ui.LauncherActivity`, and native ABIs `arm64-v8a` and
  `x86_64`.
- Signature verification passed APK Signature Scheme v2 with one default
  Android Debug RSA-2048 signer, certificate SHA-256
  `b51ed3a12c762a69a4c3b31a30c77b5fccc9f0d50417f8a70911b7f60b135d8a`.
  This does not satisfy upload/store-signing gates.
- The exact APK at
  `/home/den/.cache/codex/mobile-final-893ba827.TBsXgu/app-debug.apk` was
  installed on a Google Pixel 9 (`tokay`), Android 17 / API 37, security patch
  2026-07-05, build `CP2A.260705.006`, ABI `arm64-v8a`.
  Historical `0.5.8-debug` / code `49` used an incompatible debug key, so the
  first in-place update safely failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  Under explicit reinstall authorization, only `com.denuoweb.hnsdane.debug`
  and its data were removed. Production `com.denuoweb.hnsdane` remained
  installed and untouched. The installed package reported `0.5.9-debug` / code
  `50`, minimum API 30, target API 37, and its `base.apk` SHA-256 matched the
  exact artifact.
- Cold launch traversed `LauncherActivity` to `MainActivity` in 469 ms. The
  process remained live and 300 process log lines contained no fatal crash
  signature. Browser menu → Settings → HNS wallet opened `WalletActivity`.
  The fresh-install screen showed no wallet/account, create and restore
  controls, fail-closed module/balance/receive/history/tracked-name rows, and a
  sync action. Its displayed copy kept value and marketplace controls disabled.
- No wallet was created or restored; no recovery secret, account, read sync, or
  value action ran. This qualifies only the installed shell and fail-closed UI
  projection. Credential/backend/data provisioning, create/restore lifecycle,
  HNSA/HNSR/provider/value/market paths, signed artifacts, store screenshots and
  submission, and the physical-iPhone matrix remain open.

### Released `0.5.6` Android Hotfix

Android `0.5.6` / code `47` and shared Rust `0.5.6` shipped from source
`417af67efd68198de4871c0a339d1e456b60cb68`, while iOS remains unchanged at
`0.5.5` / build `57`. Current source now consumes the consolidated private
engine adapters and canonical contracts from one exact pinned engine `0.2.1`
Git revision `65c397e8347f37085ea67d2c9c745ce896328e64`; the
ECH-and-sync-telemetry pin passed exact-source qualification in full CI
`31807520618`, and standalone facade adoption remains pending.

- Rust 1.92 omitted Android from the standard `File::lock`,
  `lock_shared`, `try_lock_shared`, and `unlock` implementation, causing the
  first header-state lock to return `Unsupported` and native runtime creation
  to fail. The Android target now calls the already locked `libc 0.2.186`
  `flock` implementation with the same shared/exclusive/nonblocking/unlock
  semantics. Rust's equivalent upstream change
  [rust-lang/rust#157038](https://github.com/rust-lang/rust/pull/157038) is in
  the Rust 1.98 release train; this workspace remains on Rust 1.92.
- Android Proof Details now selects HNS proof versus ICANN DNSSEC presentation
  only from Rust's strict retained `namespaceResolution`. Physical Pixel 9 API
  37 instrumentation first failed against the pre-fix HNS path because it
  showed DNSSEC/synthetic ICANN details; the corrected build passes paired HNS
  and ICANN activity tests.
- The signed 51,323,995-byte APK has SHA-256
  `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`; the
  signed 60,276,192-byte Play AAB has SHA-256
  `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`.
  Both passed the exact ABI, 16 KiB alignment, ELF hardening, symbols, R8,
  notices, archive-signature, upload-certificate, APK-signature, and
  ZIP-alignment gates.
- On a Pixel 9 running Android 17 / API 37, the exact signed APK upgraded code
  `46` to code `47` with data preserved, cold-launched, reached `up_to_date` at
  height `340348` with lag `0`, freshness `current`, and `error: null`, and
  passed manual sync plus HNS browsing and proof-presentation checks.
- Required CI, including the fresh-runtime and paired proof-details API 37
  emulator regressions, passed in run `30484282637` on workflow-only
  descendant `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`. That workflow-only
  commit is not the tagged artifact
  source and does not alter the shipping binaries.
- Google Play committed code `47` to production with status `completed` through
  edit `07330408575596336357`; `generatedApks/47` returned HTTP `200`. GitHub
  Release [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) publishes the verified APK only;
  the Play AAB and unchanged iOS build are not attached.

### Published `0.5.5` iOS Evidence

- Final iOS-only source
  `d926561091634cd69fc9b7e79a4b76003fa4ee47` passed exact-head policy, the
  complete Apple gate, and Required CI in run `30454904736`.
- Live Release screenshot run `30454926117` produced four fixture-free
  1284 × 2778 images at that exact source. Provenance records current Handshake
  headers, DANE-verified HNS, same-navigation Proof Details, and authenticated
  ICANN WebPKI. That historical set passed its submission validator; the
  current candidate validator intentionally rejects it because it predates the
  exact `1.0.0` commit and required native wallet/read-row provenance.
- Protected upload run `30456522039` passed the unsigned gate, signed and
  uploaded build `57`, and retained the 47,930,601-byte IPA with SHA-256
  `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`.
  App Store Connect then reported the build `VALID` and the direct App Review
  submission `WAITING_FOR_REVIEW`, with manual release and no TestFlight
  distribution. Apple published that version on 2026-07-31.

The completed Android release checks do not prove the remaining physical-device
matrix for process restarts, lifecycle changes, Service Workers, downloads,
WebSockets, requester/recovery combinations, and cross-origin subresources.
Real-iPhone qualification also remains open. The unchanged iOS `0.5.5` / build
`57` is public; publication does not satisfy the separate installed-device
matrix.
