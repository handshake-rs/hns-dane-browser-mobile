# Complete Rust Handshake Ecosystem: Mobile Delta Audit

Last audited: 2026-07-28

This audit maps this checkout only to `Complete Rust Handshake Ecosystem.pdf`
(57 pages, SHA-256
`51dc7363ecc7c597c11de531fbeb1f45f3c6997a4d7b2c5065cd4be9681e7868`).
It does not claim that the coordination-wide PDF is complete.

- Repository: `https://github.com/handshake-rs/hns-dane-browser-mobile.git`
- Starting commit: `6c1d7888ae804a29ab34051cb1267057942ad0a0`
- Working branch: `main`
- Platforms in scope: Android WebView/JNI and iOS WKWebView/Apple C ABI

## Requirement Status

| PDF requirement | Status in this checkpoint | Evidence or remaining work |
| --- | --- | --- |
| Retain Android, iOS, Kotlin, Swift, JNI, Apple ABI, WebView, WKWebView, lifecycle, packaging, store metadata, and CI | Retained | The existing platform shells and release workflows remain. The JNI and C ABI keep their legacy field layout for upgrade compatibility. |
| Atomic staged header synchronization | Implemented locally | Network I/O, quorum collection, snapshot preparation, and peer merging occur in a private SQLite stage. Generation-and-tip-bound publication atomically exposes headers, peers, and readiness under cross-process locks; unchanged-header peer refresh does not invalidate active requests, and incomplete or superseded state fails closed. |
| Full-host dual-root namespace decision | Implemented through the standalone engine contract | Android and iOS send every canonical DNS host to one retained Rust preparation boundary. HNS and ICANN independently resolve the complete host, A and AAAA endpoint sets, aliases, HTTPS/SVCB service policy, and transport-aware TLSA owner. The engine distinguishes HNS only, ICANN only, convergent, divergent, and neither; explicit pin precedes sticky success and the ICANN default. The selected immutable plan is the sole source of endpoints, protocol, trust, trace, and errors, and later DNS is rejected. The IANA list is no longer an authoritative browser classifier. |
| HNS HTTPS never falls back to WebPKI | Implemented locally | The selected HNS plan requires secure TLSA/DANE. Missing, insecure, bogus, or mismatched evidence remains fail-closed. Secure HNS address presence without the required TLSA is a root failure, not authenticated namespace absence, so an ICANN plan cannot hide the unresolved HNS trust policy. |
| Automatic ICANN DANE with constrained WebPKI | Implemented through the standalone engine contract | Every DNS-named HTTPS/WSS request enters the dual-root gateway. `hns-icann-dane` derives `_port._tcp.host.` or `_port._udp.host.` from the actual retained service plan, enforces DNSSEC-secure TLSA, and admits WebPKI only for authenticated denial or a proven insecure delegation. ICANN DoH connects only to pinned bootstrap addresses while authenticating the configured hostname. Bogus/indeterminate DNSSEC, malformed TLSA, timeout, resolver error, and invalid owner derivation fail closed. |
| No implicit/default recursive HNS resolver; explicit recovery is constrained | Implemented locally | Blank is the default and makes no recovery request. A separately configured bounded HTTPS RFC 8484 endpoint is eligible only after direct authoritative UDP/TCP, owner-published proof-anchored authoritative DoH, and any independently opted-in P2P requester path encounter interception or transport failure. Its hostname is bootstrapped only through validating ICANN DoH, connections require public addresses and WebPKI, and every returned HNS answer still passes local proof, DNSSEC, TLSA, and DANE validation. Bogus DNSSEC, invalid DNS, DNS response codes, and stale or missing HNS proof state are terminal. |
| P2P DNS Relay requester: On/Off | Implemented | Both native settings surfaces expose relay consumption. Fresh installs default off and require explicit opt-in; existing independent requester choices are preserved. The browser does not become an output node. The separate network-role policy is opaque relayer capacity default-on/opt-out and output-node serving opt-in. |
| Explicit migration without turning old HNSDoH consent into new consent | Implemented | Android and iOS permanently tombstone the historical resolver key and never copy it into the distinct recursive-recovery key. Former resolver compatibility consent never enables relay consumption or recursive recovery; both controls start independently off until an explicit choice. |
| P2P ODoH: Preferred/Required/Direct Allowed/Off | Not implemented in this checkout | No HIP #77 requester, HPKE/ODoH runtime, status model, or native control exists here. Do not represent the current direct relay as ODoH or query-confidential. |
| HNSR: Off/Client/Endpoint | Not implemented in this checkout | No HIP #78 runtime or native control exists here. Mobile lifecycle, network-change, renewal, withdrawal, and stale-generation tests for HNSR remain required. |
| Consume the standalone `hns-dane-engine` | Integrated through immutable canonical source | The Rust workspace pins `hns-browser-runtime`, `hns-browser-observability`, `hns-icann-dane`, `hns-namespace-resolution`, and `hns-resolution-policy` to exact `handshake-rs/hns-dane-engine` commit `7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`; the lockfile records the same source. The stable mobile relay boolean maps to the shared typed requester policy (`false` → `Disabled`, `true` → `Auto`), while the normalized recovery URL maps to generation-bound `user_configured_recursive_hns_doh`. Live resolution follows direct authority UDP/TCP → owner-published authenticated authoritative DoH → independently admitted relay → configured recursive recovery; unsupported ODoH, HNSR, provider, and legacy roles remain disabled. |
| Browser authority state machine and exact-stamped results | Implemented at the shared Rust boundary | One checked random session supplies the unchanged proxy token and canonical runtime identity. Mobile policy revisions map exactly to canonical generations without no-op churn. A current non-genesis header on every network, proof/transport readiness, listener publication, exact-generation replacement/revocation, one whole-request stamp minted before DNS/classification, sticky binding plus exact-result response-head publication, staged-file commit, and tunnel I/O revocation all use the canonical state machine. Android JNI suppresses post-admission errors instead of generating unstamped output. Typed success and root-failure schema-v2 status uses the same entry stamp and request-local exact plan; bogus DNSSEC remains distinct from absence and untyped WebPKI/transport failures remain unavailable. The stable JNI and Apple C ABI layouts intentionally remain unchanged. |
| Relay/ODoH observability | Partial | Existing relay traces distinguish the relay from authoritative transports and report local DNSSEC/TLSA/DANE decisions. The schema-v2 adapter refuses to invent a relay registry fingerprint or protocol version when the legacy client did not retain negotiated identity, and reports explicit unavailability instead. ODoH privacy policy, proxy/target separation, and HIP #77 runtime evidence remain unavailable because ODoH is not implemented. |
| Foreground/background and browser restart qualification | Partial | Existing lifecycle and proxy-revocation tests remain. Exact-build physical Android and mobile-network qualification remains a release gate. The iOS physical-device matrix is not an App Store submission prerequisite, but it remains an installed-device and ecosystem qualification gate. The PDF's unimplemented ODoH/HNSR lifecycle cases remain future work. |

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
emulator. Store binaries and previously recorded hashes must be rebuilt after
this source checkpoint before they can be release evidence.

The five shared engine crates resolve from immutable
`handshake-rs/hns-dane-engine` commit
`7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`; a standalone checkout no longer
depends on the coordination workspace layout.

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

The `0.5.5` release source continues to pin engine revision
`7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`.

- Local Rust validation passed 764 tests with no failures and one ignored
  benchmark; strict Clippy, formatting, and workspace checks passed.
- Full manual CI run `30448341156` passed repository policy, the complete
  Rust/supply-chain gate, Android build/tests/lint/bundle structure, the Apple
  ABI/XCFramework/XCTest/simulator/device-link gate, and Required CI for the
  code 46/build 57 version-bump source.
- The signed code 46 APK and AAB passed exact ABI, 16 KiB alignment, ELF
  hardening, symbols, R8, notices, archive-signature, upload-certificate, APK
  signature, and ZIP-alignment checks. Google Play committed production edit
  `17438779769069438085`; `generatedApks/46` returned HTTP `200`.
- Final iOS-only source
  `d926561091634cd69fc9b7e79a4b76003fa4ee47` passed exact-head policy, the
  complete Apple gate, and Required CI in run `30454904736`.
- Live Release screenshot run `30454926117` produced four fixture-free
  1284 × 2778 images at that exact source. Provenance records current Handshake
  headers, DANE-verified HNS, same-navigation Proof Details, and authenticated
  ICANN WebPKI; the full App Store metadata/screenshot validator passes.
- Protected upload run `30456522039` passed the unsigned gate, signed and
  uploaded build `57`, and retained the 47,930,601-byte IPA with SHA-256
  `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`.
  App Store Connect reports the build `VALID` and the direct App Review
  submission `WAITING_FOR_REVIEW`, with manual release and no TestFlight
  distribution.
- Public GitHub Release `v0.5.5` retains the exact verified code 46 APK and
  build 57 IPA at annotated tag source
  `d926561091634cd69fc9b7e79a4b76003fa4ee47`.

These gates verify portable, Android package, hosted Apple build, and live
simulator behavior. They do not prove the separate signed physical Android or
real-iPhone matrices for WebView/WebKit process restarts, lifecycle changes,
Service Workers, downloads, WebSockets, and cross-origin subresources. Those
installed-device rows remain open and are not prerequisites for App Store
Connect upload or direct App Review submission, both of which are complete.
