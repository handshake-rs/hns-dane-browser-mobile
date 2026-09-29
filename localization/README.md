# Wallet localization

The shortened English strings in
`android/app/src/main/res/values/strings.xml` are the canonical wallet copy.
Two source files select controlled localization cohorts and store a keyed
translation for every selected message and supported locale:

- `wallet-critical.json`: 71 onboarding and first-transaction strings.
- `wallet-operations.json`: 543 synchronization, send, swap, deletion,
  activity, and name-market strings.

Together they project 614 canonical keys to English and 20 additional locale
groups on both Android and iOS.

Run the generator after changing canonical English, the selected key list, or
any translation:

```sh
python3 scripts/generate_wallet_localizations.py
```

The generator writes Android's `values-*/wallet_critical.xml` and
`values-*/wallet_operations.xml` files and the Apple `Wallet.xcstrings`
catalog. CI runs the generator with `--check`, then checks every Android locale
for complete key coverage and matching format tokens and line breaks. It also
rejects Apple wallet call sites that reference unknown catalog keys. Generated
files should not be edited directly.

The critical cohort covers wallet creation and restore, recovery confirmation,
unlock, HNS synchronization, confirmed balance and receive address, offer-peer
connection, offer review, acceptance, and Bitcoin/HNS funding approval. The
operations cohort adds synchronization progress and failures, Bitcoin and HNS
send/activity states, swap publication/funding/settlement/recovery, wallet
deletion, and name-market actions.

The non-English operations copy was bootstrapped with machine-assisted
translation. Placeholder, line-break, locale-coverage, and catalog parity
checks are automated, but they do not establish linguistic correctness. A
native speaker must review each locale before release, with priority on
transaction approvals, irreversible name transfers, swap funding/refunds, and
wallet deletion. Remaining advanced diagnostic and system-lifecycle copy stays
explicitly `translatable="false"` until a later reviewed cohort.
