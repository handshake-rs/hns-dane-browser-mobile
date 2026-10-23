# ShakeScape name-offer app URI (v1)

The canonical URI for opening one public name listing in the Android or iOS
ShakeScape app is:

```text
shakescape://name-offer/v1/{offer_id}?network={network}
```

`offer_id` is the listing hash as exactly 64 lowercase hexadecimal characters,
excluding 64 zeros. `network` is exactly `mainnet`, `testnet`, or `regtest`.
The scheme, host, path, query key, and their order are literal. No fragments,
extra query items, percent encoding, or alternate spellings are accepted.
For example, an offer on regtest with ID `abab...abab` (32 `ab` pairs) opens as:

```text
shakescape://name-offer/v1/abababababababababababababababababababababababababababababababab?network=regtest
```

The URI is an untrusted pointer, not an offer payload, payment instruction, or
authorization to spend. The app checks that the URI network matches the selected
wallet network, fetches that exact current listing from its paired ShakeDex
board, and shows the current signed terms for review. Purchase preparation
rechecks the signed proof and the current name lock. A missing, cancelled,
expired, or changed listing cannot be accepted from the URI alone.

Liquidity Spot's name board uses this URI for **Open in ShakeScape** and
**Copy app link**. **Copy listing ID** remains available for manual lookup in
the app. Both platforms register the `shakescape` custom scheme; other apps
may also claim a custom scheme, so no secret or authority belongs in the URI.

The v1 path is immutable. A future incompatible payload or lookup scheme gets
its own version, with recipients explicitly recognizing it before sites emit
links. Android's `ShakescapeNameOfferUri` and iOS's
`ShakescapeNameOfferURI` implement the v1 parser.
