# Wallet localization

The shortened English strings in
`android/app/src/main/res/values/strings.xml` are the canonical wallet copy.
Three source files store a keyed translation for every selected message and
supported locale:

- `wallet-critical.json`: 65 onboarding and first-transaction strings.
- `wallet-operations.json`: 552 synchronization, send, swap, deletion,
  activity, and name-market strings.
- `wallet-swap-list.json`: 23 swap execution list, hide, pagination, and
  dashboard summary strings.

The first two files project 617 canonical keys to 20 additional locale groups
on Android and iOS. The swap-list file adds 23 strings to both platforms in
the same locale groups.

Run the generator after changing canonical English, the selected key list, or
any translation:

```sh
python3 scripts/generate_wallet_localizations.py
python3 scripts/generate_swap_list_localizations.py
```

The first generator writes Android's `values-*/wallet_critical.xml` and
`values-*/wallet_operations.xml` files and the Apple `Wallet.xcstrings`
catalog, including the swap-list strings. The second writes Android's
`values-*/wallet_swap_list.xml` files.
CI runs both generators with `--check`, then checks every Android locale for
complete key coverage and matching format tokens and line breaks. It also
rejects Apple wallet call sites that reference unknown catalog keys. Generated
files should not be edited directly.

The critical cohort covers wallet creation and restore, recovery confirmation,
unlock, HNS synchronization, confirmed balance and receive address, offer-peer
connection, offer review, acceptance, and Bitcoin/HNS funding approval. The
operations cohort adds synchronization progress and failures, Bitcoin and HNS
send/activity states, swap publication/funding/settlement/recovery, wallet
deletion, and name-market actions.

Placeholder, line-break, locale-coverage, and catalog parity checks are
automated. Review wording carefully when changing transaction approvals,
irreversible name transfers, swap funding/refunds, or wallet deletion. Advanced
diagnostic and system-lifecycle copy marked `translatable="false"` remains
English across locales.

The new ShakeDex scan, active swap, buyer review, and funding approval copy
is in the operations cohort for all supported locales. Other `wallet_ux_*`
keys use canonical English in Android base resources and an English-only entry
in the generated Apple catalog until translations are reviewed. They are
explicitly `translatable="false"` and are not counted as translated.
