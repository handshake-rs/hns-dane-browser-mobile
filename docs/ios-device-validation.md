# iOS Device Validation

The iOS shell has an iOS 17.0 deployment floor, retaining support for the iOS 17 and iOS 18 generations. That minimum supported runtime is independent of the build SDK: Apple builds use the stable iOS 26.5 SDK with Xcode 26.5 or 26.6.

The Rust and C boundaries can be validated on Linux. The Apple slices, Swift
shell, and unit tests can then be compiled and exercised in a macOS simulator
gate. A signed physical-device pass adds evidence about WebKit's
out-of-process networking that simulator success cannot provide. It is
not required to archive, upload, or submit an App Store update, but it remains
required before claiming installed-iOS or ecosystem qualification. No
physical-device pass is claimed here.

## macOS Build and Simulator Gate

Run with the repository-pinned Rust toolchain, Xcode 26.5 or 26.6, and the iOS 26.5 device and simulator SDKs selected:

```sh
./scripts/check.sh
./scripts/run-ios-gate.sh
```

The first command is the portable repository check. The second is the same complete macOS gate used by CI: it verifies Xcode and the exact SDK, installs the pinned Rust toolchain and Apple targets, produces device arm64 and universal arm64/x86_64 simulator slices, creates `HnsBrowserRuntime.xcframework`, compiles the C header smoke test, links the iOS application and test target without undefined FFI symbols, executes the unit tests on an iPhone simulator, and performs an unsigned Release link against the device slice. Completion establishes build, linkage, and simulator behavior only; it is not evidence that the signed physical-device matrix passed.

## Signed Physical-Device Qualification

No physical-device pass is currently claimed. If an independently installed
signed build and an iPhone running iOS 17.0 or later are available, capture the
applicable evidence below. The user who builds and submits the app does not need
to own that device. The absence of this matrix is not an App Store submission
blocker; it remains an explicit installed-device qualification gap and may be
completed after submission.

### Proxy isolation

- Confirm the WebKit profile has one authenticated proxy configuration, `allowFailover` is false, and both domain lists are empty.
- Confirm DNS-named ICANN HTTPS uses local Rust CONNECT termination, validating ICANN DoH, automatic `_port._tcp` TLSA discovery, DANE when secure TLSA is present, and WebPKI only for authenticated absence or insecure delegation.
- Confirm DNSSEC bogus/indeterminate responses, malformed TLSA, resolver timeout/error, and DANE mismatch fail closed rather than becoming WebPKI.
- Confirm canonical public IP-literal HTTPS retains the bounded opaque CONNECT/WebKit WebPKI path without target DNS.
- Confirm ordinary ICANN HTTP uses Rust's bounded direct forwarder.
- Confirm HNS-only complete hosts use Rust HNS resolution, DNSSEC, DANE, and local CONNECT termination.
- Confirm a complete host that exists in both roots reports convergent or divergent state and the selected namespace; malformed hosts, special-use names, loopback/private/link-local addresses, and browser-blocked ports must fail before any system resolver or outbound socket is called.
- Confirm an absent, stopped, or authentication-rejecting loopback proxy never causes WebKit to connect directly.
- Confirm no HNS DNS, HTTP/3, QUIC, or fallback traffic leaves the device outside the Rust-selected transports.
- Confirm proxy credentials never appear in origin request headers, logs, diagnostic JSON, or crash reports.

### Certificate challenges

For each case below, record that WebKit delivered the server-trust challenge, the Swift shell extracted the full leaf DER, and Rust authorized only the exact host and live proxy generation for HNS and DNS-named ICANN:

- main-frame HNS HTTPS;
- CSS, image, script, iframe, XHR, and `fetch` subresources;
- a new subdomain that requires a separate generated local certificate;
- Service Worker install, activation, controlled fetch, and fetch after rotation;
- `wss://` and HTTP Upgrade;
- same-origin and cross-origin redirects, with each destination receiving its own retained namespace plan;
- back-forward cache restoration;
- renderer and WebKit network-process restart.

Presenting an unrelated certificate, another host's certificate, or a stopped generation must be canceled. Only canonical public IP-literal ICANN trust challenges remain under WebKit's default handling.

### Lifecycle and ownership

- Background the app during a main-frame load, subresource load, WebSocket, Service Worker fetch, and download.
- Verify the visible WebView is disabled first, proxy credentials and certificate authorization are revoked immediately, and all Rust listener/client workers join off the main thread.
- Resume and confirm a fresh generation, credentials, port, proxy configuration, and WebView are created before navigation restarts.
- Terminate the renderer/network process and verify the same fail-closed rebuild.
- Confirm stale delegate callbacks cannot authorize, publish status, navigate, or clear a newer generation.
- Confirm cookies and profile data persist across safe WebView reconstruction without sharing proxy ownership across multiple scenes.

### Browser behavior

- Repeat the Android parity cases for GET, POST, uploads, range requests, redirects, cookies, JavaScript fetch/XHR, Service Workers, WebSockets, downloads, HTTP/1.1, HTTP/2, HTTP/3 origin transport, IPv4, and IPv6.
- Exercise `https://denuoweb/` for proof-anchored authoritative DoH and a second HNS origin whose direct authoritative path is unavailable; verify independently enabled P2P and user-configured recursive recovery paths in their exact order, then verify blank/off recovery fails closed and compare bounded security traces with Android.
- Verify the strict HNS trust invariant, absence of any implicit/default recursive HNS resolver and HNS WebPKI fallback, independent requester/recovery opt-ins, permanent historical-key tombstoning, endpoint and ICANN-bootstrap validation, terminal bogus/invalid/stale cases, stateless-DANE fail-closed behavior, sync progress, cache clearing, proof details, download handoff, sharing, accessibility labels, and Dynamic Type.

### Native non-value wallet

The complete simulator gate passed for final application source
`f21bee1c3afccd06604dc99fccb51528e2441055` in Required CI run `31402758394`,
and documentation-only commit
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed the full matrix again in
manual run `31411048376`. The following signed-device matrix remains an optional
installed-iPhone qualification activity for the exact `0.5.8` release checkout:

- On a fresh install, open Settings → Handshake wallet and confirm the screen
  reports no local wallet without announcing a website provider.
- Create a wallet only while screen capture is inactive. Confirm the recovery
  phrase appears once, cannot be copied through ordinary controls, and the
  incomplete database is removed if the app backgrounds before confirmation.
- After securely recording and confirming the phrase, exercise open, user-
  presence unlock, one non-value account identity, lock, process restart, and
  network isolation.
- Restore on a separate empty network scope and confirm the input and visible
  recovery text clear when the screen backgrounds or protected data becomes
  unavailable.
- Confirm there is no balance, receive/send, name, website-provider,
  settlement, exchange, HNSA/HNSR, or P2P-marketplace control.
- Record the Swift/UIKit managed-text limitation: app-owned mutable buffers are
  wiped and fields are cleared, but deterministic zeroization of framework-
  managed recovery text is not claimed.

## Apple References

- https://developer.apple.com/documentation/network/proxyconfiguration
- https://developer.apple.com/documentation/webkit/wkwebsitedatastore/proxyconfigurations-cdc1
- https://developer.apple.com/documentation/webkit/wknavigationdelegate/webview%28_%3Adidreceive%3Acompletionhandler%3A%29
- https://developer.apple.com/documentation/security/sectrustcopycertificatechain%28_%3A%29
- https://developer.apple.com/documentation/uikit/managing-your-app-s-life-cycle
- https://developer.apple.com/documentation/xcode/creating-a-multi-platform-binary-framework-bundle
