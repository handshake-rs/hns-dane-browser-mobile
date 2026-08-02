# Mobile wallet/provider adapter boundary

Android and iOS contain a compiled, deliberately inactive provider ABI v1
scaffold. The protocol definitions share the Chromium method/event surface,
reject generic Ethereum and raw Bitcoin signer methods, accept only the
`bitcoin` and `ethereum` external modules, bound JSON frames and nesting, reject
unsafe JSON numbers and secret-shaped native results/events, and keep browser,
wallet, permission, policy, and navigation generations inside native code.

`AndroidWalletProviderBridge` installs an AndroidX document-start script and a
`WebMessageListener` limited to HTTPS. Every callback additionally requires a
main-frame message, canonical exact `sourceOrigin`, and a current authority
returned by the owning browser controller. `WalletWebKitBridge` installs a
main-frame-only `WKUserScript` at document start in `WKContentWorld.page` and a
reply-capable `WKScriptMessageHandler`; it compares the exact `WKSecurityOrigin`
with current native browser authority on every message. Neither adapter creates
`window.ethereum`.

Both adapters are source-hardwired to an explicitly unavailable wallet ABI;
their constructors cannot inject an implementation. They are also absent from
the current browser controllers. Consequently no provider is announced and no
wallet method can execute. A future integration must add approval UI,
permission persistence, controller lifecycle wiring, browser-authority
invalidation, and the generated wallet FFI ABI before replacing that
fail-closed source boundary. The request scaffolds already bound pending work,
timeouts, replay identifiers, sequences, and per-method/global rates, and
revalidate wallet generations before dispatch.

`AndroidWalletKeyStore` and `WalletKeychainStore` are native-only helpers for a
32-byte wallet database key and use durable/atomic replacement semantics so a
failed update does not first erase the prior key. They are not yet connected to
a wallet database and are never referenced by WebView/WKWebView JavaScript.
Wallet seed, recovery phrase, chain keys, passphrase, preimages, and provider
capability material are forbidden at the protocol boundary. `WalletUIState`
only enumerates intended wallet, HNS name, Shakedex, module, market, swap,
refund, and permission states; it does not implement those screens, and begins
locked and unavailable.

The checked-in iOS project includes the scaffold and protocol tests, but there
is no approval window, wallet FFI/controller integration, permission database,
or producer for native wallet events yet. DANE browsing remains unchanged.
