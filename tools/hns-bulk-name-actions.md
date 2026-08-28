# Bulk HNS TRANSFER and FINALIZE operator

`hns_bulk_name_actions.py` drives the authenticated HSD wallet HTTP API with a
bounded list of 1–10,000 exact names. It supports `recovered2` by default,
writes a private atomic checkpoint before and after each network request, and
resumes at the first unfinished name.

The tool sends HSD's `maxFee` transaction option on every request. HSD applies
that cap during funding/coin selection, before signing and broadcast. An
optional `hardFee` chooses an exact fee no larger than the cap, and
`max-total-fee-hns` bounds the worst-case exposure (`name count × maxFee`).

Input may be one exact canonical name per line or a JSON string array. The file
is limited to 1 MiB and 10,000 unique names. Start with the plan-only command:

```sh
python3 tools/hns_bulk_name_actions.py transfer \
  --names names.txt \
  --checkpoint transfer-progress.json \
  --wallet-id recovered2 \
  --recipient hs1ql5j48gj6jhwn38r075z9qq05n7f6td4uh6dd2f \
  --max-fee-hns 0.05 \
  --max-total-fee-hns 100
```

After checking the printed plan, export the HSD API key without putting it in
shell history and add `--execute`:

```sh
read -rsp 'HSD API key: ' HSD_API_KEY && export HSD_API_KEY
python3 tools/hns_bulk_name_actions.py transfer \
  --names names.txt \
  --checkpoint transfer-progress.json \
  --wallet-id recovered2 \
  --recipient hs1ql5j48gj6jhwn38r075z9qq05n7f6td4uh6dd2f \
  --max-fee-hns 0.05 \
  --max-total-fee-hns 100 \
  --execute
unset HSD_API_KEY
```

FINALIZE uses the same original names file and a separate checkpoint after the
Handshake transfer lockup has matured:

```sh
python3 tools/hns_bulk_name_actions.py finalize \
  --names names.txt \
  --checkpoint finalize-progress.json \
  --wallet-id recovered2 \
  --max-fee-hns 0.05 \
  --max-total-fee-hns 100 \
  --execute
```

The default URL is the mainnet wallet HTTP listener at
`http://127.0.0.1:12039`. Use `--url` if the VM is configured differently and
`--account` for a non-default account. For an encrypted wallet, provide the
name of an environment variable through `--passphrase-env`; the tool prompts
without echo if that variable is absent.

If a timeout, disconnect, malformed response, or HSD error occurs after a
request starts, the checkpoint deliberately remains `ambiguous`. The tool will
not retry that name automatically. Inspect the HSD wallet transaction history:

- if it broadcast, resume with `--resolve-txid <64-lowercase-hex-txid>`;
- if it definitely did not broadcast, resume with `--retry-inflight`.

Use the same action, names, wallet, recipient, account, and fee arguments when
resuming. A changed input digest or policy is rejected so a checkpoint cannot
silently continue a different batch.
