# Wallet localization

The shortened English strings in
`android/app/src/main/res/values/strings.xml` are the canonical wallet copy.
`wallet-critical.json` selects the first controlled localization cohort and
stores a keyed translation for every selected message and supported locale.

Run the generator after changing canonical English, the selected key list, or
any translation:

```sh
python3 scripts/generate_wallet_localizations.py
```

The generator writes Android's `values-*/wallet_critical.xml` files and the
Apple `Wallet.xcstrings` catalog. CI runs the generator with `--check`, then
checks every Android locale for complete key coverage and matching format
tokens and line breaks. It also rejects Apple wallet call sites that reference
unknown catalog keys. Generated files should not be edited directly.

The current cohort covers wallet creation and restore, recovery confirmation,
unlock, HNS synchronization, confirmed balance and receive address, offer-peer
connection, offer review, acceptance, and Bitcoin/HNS funding approval. The
remaining advanced wallet and diagnostic copy stays explicitly
`translatable="false"` until it is moved into a later reviewed cohort.
