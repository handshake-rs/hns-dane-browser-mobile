# ShakeScape coin-offer app URI (v1)

The canonical URI for opening one public HNS/BTC offer in the Android or iOS
ShakeScape app is:

```text
shakescape://coin-offer/v1/{offer_id}?network={network}
```

`offer_id` is the direct-offer hash as exactly 64 lowercase hexadecimal
characters, excluding 64 zeros. `network` is exactly `mainnet`, `testnet`, or
`regtest`. The scheme, host, path, query key, and order are literal. Fragments,
extra query items, percent encoding, and alternate spellings are rejected.

The URI carries no signed terms or authority. The app checks the selected
network, looks up the exact ID on its paired ShakeScape board, and displays the
current signed terms for review. Missing, cancelled, expired, or unseen offers
do not open a swap review. Native acceptance rechecks the offer before spending.

Liquidity Spot's coin board uses this URI for **Open in ShakeScape** and
**Copy app link**. **Copy offer ID** remains available for manual reference.
The seller can copy the app link from **My offers**. A custom scheme can be
claimed by another installed app, so the URI contains no secret or authority.

The custom scheme does not identify a Play Store or App Store listing when the
app is absent. A future HTTPS offer link can use verified Android App Links and
iOS Universal Links, with a web page showing explicit store links when the app
is unavailable. Publish a store build that understands this v1 URI before
offering that install path as a way to open an offer.

The v1 path is immutable. Future incompatible links need a new version and
recipient support before any site emits them. Android's
`ShakescapeCoinOfferUri` and iOS's `ShakescapeCoinOfferURI` implement v1.
