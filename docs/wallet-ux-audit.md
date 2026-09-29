# Mobile Wallet UX audit — 2026-09-29

Scope: the native Android and iOS Wallet home, Names gallery and actions,
Bitcoin sheet, Shakedex sheet, and their setup and detail menus. This is a
source audit; installed-device validation is recorded separately below.

## Findings and intended changes

| Finding | User impact | Refactor |
| --- | --- | --- |
| Home leads with an unlocked/status card, balance card, and sync card. Normal status includes implementation details. | The balance and next action compete with repeated status. | Lead with balance and one current state; retain operation/error feedback and put diagnostics in Wallet details. |
| Bitcoin opens with four detail cards, then actions. | Receive and Send require scrolling past balance internals, a raw address, and activity text. | One balance/setup overview, everyday actions, activity, then details. Show addresses in a dedicated copy view. |
| Bitcoin Sync appears for an unknown recovery birthday. | A restored wallet can start a full historical scan without understanding its cost. | Guide recovery-start selection first; preserve an explicitly confirmed full-history scan for users who do not know the height. Newly created wallets still establish their birthday automatically. |
| Home Bitcoin readiness is inferred from rendered balance text or backend availability. | “Ready” can conceal missing setup or ongoing work. | Derive setup, syncing, and last-scanned status from native snapshot and operation state. |
| Shakedex has repeated host/connection cards and many equal-weight controls. | Pairing, trading, monitoring, and recovery are difficult to distinguish. | One connection/work overview; group coin offers, name trading, and connection details. Keep ongoing swap attention visible on home. |
| Listener retry is disabled when no peer is paired. | A recovery control can be unavailable in precisely the state where it is needed. | Use the native listener-retry predicate independently of pairing. |
| Names hides import behind a generic Options label and, on iOS, adds another menu before name actions. | Empty-state users lack an obvious first step; existing-name tasks require unnecessary drilling. | Make the empty-state entry an Add names action; group import and management in one menu. |
| First-item order chooses emphasis; secondary actions use a different accent color. | Color appears to encode different capabilities without a consistent meaning. | Explicit primary action on overview menus; neutral secondary controls and consistent accent. Preserve destructive approvals. |
| Open sheets capture action availability once. | Sync/stop/setup controls become stale while status text updates. | Refresh overview action availability alongside live status without recreating unchanged controls. |
| Long status is truncated in two-column home tiles. | Actionable progress is hidden behind ellipses. | Use full-width feature rows with concise summaries. |

## Preservation requirements

- Keep every existing operation reachable, including detailed balances, raw
  status, activity pagination, manual peer control, recovery, and name actions.
- Keep native authority, authentication, fresh verification, fee checks, one-time
  approval, lock cleanup, and storage ownership as the final execution gates.
- Never infer spendable funds from total balance or equate socket submission
  with confirmation. Unknown balances remain unknown.
- Preserve user-started sync cancellation and automatic live-swap monitoring.
- Do not automatically broadcast, pair to a chosen counterparty, choose a
  recovery height, or approve a transaction as part of UX cleanup.
- Keep existing localized copy. New copy must have shared canonical keys with
  an explicit English fallback until translated; do not reuse unrelated keys.

## Design references

The task-oriented grouping and separation of frequent actions from settings
follow [Android navigation guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)
and [settings guidance](https://developer.android.com/design/ui/mobile/guides/patterns/settings).
Secondary information is revealed on demand, following
[Apple disclosure guidance](https://developer.apple.com/design/human-interface-guidelines/disclosure-controls).
These are design inputs; the concrete findings above come from this repository.

## Validation

Testing is deferred until the implementation commits, as requested. The final
pass should cover recovery-unknown, new-wallet automatic birthday, syncing,
stop requested, ready, disconnected/paired, live swap, empty Names, and locked
states; Android build/tests; localization and runtime-boundary checks; and
Apple compilation or an explicit account of host limitations.
