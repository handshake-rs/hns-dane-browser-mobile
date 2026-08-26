# Native HNS wallet feature matrix

This matrix describes the unreleased native wallet on local `main`. A rendered
button or a passing decoder test is not treated as installed-device proof.
Website JavaScript has no access to any operation below.

| Feature | Android controller | iOS controller | Native authority and automated evidence | Remaining qualification |
| --- | --- | --- | --- | --- |
| Create wallet and acknowledge one-time recovery | `WalletActivity` create/confirm flow | `WalletViewController` create/confirm flow | Bounded mobile controller; Android lifecycle/storage tests and Apple lifecycle tests cover incomplete-wallet cleanup and stale authority | Repeat on the final installed Android build and a physical iPhone |
| Restore wallet | Protected restore field and native restore | Protected UIKit restore flow and native restore | Recovery input is consumed by the shared mobile controller; namespace and retirement tests cover ownership | Final Android and physical-iPhone recovery/catch-up exercise |
| Open, unlock, status, lock, and destroy | Storage lease plus bounded JNI handle | Path lease plus bounded C-ABI handle | Handle revocation, queued-call rejection, retirement, and deletion tests | Physical-iPhone lifecycle matrix; final Android upgrade/reopen matrix |
| Delete local wallet | Two-step exact-account confirmation and `DELETE` | Two-step exact-account confirmation and `DELETE` | Key removal precedes exact SQLite-artifact cleanup; ambiguous retirement fails closed | Final device exercises without touching unrelated app data |
| Direct-peer synchronization | Background direct coordinator with catch-up/live projections | Background direct coordinator with catch-up/live projections | Verified peer/header agreement, rollback floor, closed progress bundles, and stale-publication tests | Final installed-device interruption/resume and iPhone network qualification |
| Payment receive target | Local derivation before full synchronization plus synchronized snapshot | Local derivation before full synchronization plus synchronized snapshot | Closed receive bundle, exact account identity, purpose and derivation validation; raw-copy tests | Physical-iPhone copy exercise |
| Name-transfer receive target | Synchronized dashboard and copy dialog | Synchronized dashboard and copy dialog | HNWR-v2 requires a distinct purpose/account-bound target; projection tests reject conflation | Physical-iPhone copy exercise |
| Balance, pending outgoing, available-after-pending, and history | Strict snapshot dashboard | Strict snapshot dashboard | Exact integer projection prevents a pending outgoing spend from remaining apparently available; duplicate and incoherent history fail closed | Confirm a real outgoing transaction on chain and synchronize the final state |
| Send review | Fresh direct synchronization, exact request echo, one-time native approval | Fresh direct synchronization, exact request echo, one-time native approval | Closed send approval, recipient/amount/maximum-fee equality, expiry, rollback-floor checks, and canonical HSD network wallet fee floors | Requalify with the forward-only change watch-set and fee-policy fixes installed |
| Reject send | Dialog cancel/reject consumes the pending token | Dialog cancel/reject consumes the pending token | One-shot native rejection; lifecycle loss locks or suppresses stale publication | Final installed-device cancel/background cases |
| Approve and submit send | Native approval, peer submission, then snapshot refresh | Native approval, peer submission, then snapshot refresh | Exact signed bytes are retained for bounded dropped-send resubmission; socket submission remains `broadcast`, peer-returned inventory is `mempool`, and only verified inclusion is `confirmed` | A mined transaction is still required; peer-observed mempool display is insufficient |
| Send change watch-set update | Shared embedded backend | Shared embedded backend | Exact one-script internal-change extension preserves authenticated scan head and history; ambiguous changes retain the birthday-rewind path | Install and repeat Android send review without a block-zero rescan |
| Track exact HNS name | Direct proof sync followed by canonical import; loopback compatibility path remains | Direct proof sync followed by canonical import; loopback compatibility path remains | Exact UTF-8/canonical-name validation, verified name proof, bounded HNWI result, and stale-authority suppression | Credentialed direct device exercise on both platforms |
| Transfer and finalize name | Fresh sync, native review, reject/approve/result refresh | Fresh sync, native review, reject/approve/result refresh | Closed schema-three disclosures and value-result decoders cover exact name, address, covenant and fee fields | Regtest or controlled-device end-to-end transaction exercises |
| Create, cancel, and recover fixed-price offer | Native action forms and approvals | Native action forms and approvals | Shared Shakedex workflow authority, one-shot approvals, bounded results | End-to-end offer lifecycle with two controlled peers |
| List offers and get session | Direct-Denuo query controls | Direct-Denuo query controls | Closed query/result bundles and bounded board replication | Two-device network exercise, including restart and replacement |
| Accept offer and finalize purchase | Native action forms and approvals | Native action forms and approvals | Shared purchase workflow, exact local disclosures, bounded results | Complete controlled two-wallet purchase exercise |
| Direct-Denuo listener, pair, retry, service, replace, disconnect | Wallet-owned foreground worker | Wallet-owned protected-foreground timer | Exact IP-literal endpoints, bounded transport bundles, replacement/disconnect tests; no marketplace relay authority | Android/iPhone local-network and lifecycle interruption exercise |

## Current send qualification

The 2026-08-25 Pixel 9 exercise proved that review reached native preparation
and that the app submitted the signed transaction to connected peers. It did
not prove miner admission: later synchronization classified the transaction as
dropped/unconfirmed and the wallet rescanned from its block-zero birthday.
Local mempool presentation therefore remains pending state, not confirmation.

Four later local-main fixes are stacked but not installed in that exercise:

- exact serialized bytes are retained and reused for bounded dropped-send
  resubmission;
- an exactly proven internal change-gap extension updates the embedded watch
  set without discarding authenticated scan coverage or transaction history;
  and
- a successful socket write remains a durable `broadcast` submission during a
  short propagation window. It becomes `mempool` only after a connected peer
  returns the transaction and `confirmed` only after verified block inclusion.
  The submitted transaction remains visible from its encrypted workflow while
  awaiting that peer response; and
- direct mainnet fee selection now uses the canonical HSD normal-wallet floor
  of 100,000 dollarydoos per 1,000 policy virtual bytes instead of confusing
  the 1,000-dollarydoo protocol relay minimum with a miner-targeted estimate.
  Testnet and regtest retain HSD's 20,000-dollarydoo normal-wallet floor.

The UI describes the entered fee as a cap because raising it does not itself
set the final fee. For the next single-input mainnet send exercise, use at least
a 0.05 HNS cap; native preparation still fails closed if the size-derived fee
would exceed it.

The previously dropped low-fee transaction retains its exact approved bytes
and remains visible as dropped, but automatic recovery now skips it when those
bytes no longer satisfy the current wallet fee policy. That incompatibility no
longer makes every later synchronization fail. Its short input reservation is
still released by normal reconciliation, allowing a separately reviewed send
to select the confirmed unspent coin again if peers continue to report the old
transaction absent.

The send row remains incomplete until an installed build demonstrates no
unnecessary birthday rescan and the transaction is observed in a verified
block. The broader goal also remains incomplete until the physical-iPhone and
controlled name/marketplace device journeys above have evidence.
