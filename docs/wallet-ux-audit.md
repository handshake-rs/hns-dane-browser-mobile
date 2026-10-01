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

The device-review follow-up also found that Create wallet looked actionable
while browser sync was still establishing a verified birthday. Create is now
disabled until that prerequisite is satisfied, its copy explicitly describes
the wait, and failed network checks have a separate Retry network check action.
Restore remains a distinct option.

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

## Implementation notes

The refactor retains separate native execution gates. The overview menus use
cached public state for presentation, with a live action list so setup → sync
→ stop transitions do not require closing the sheet. Android keeps controls
stable when only progress text changes; iOS uses the same approach and opens
these task overviews at the large sheet size.

Bitcoin shows the existing receive address before offering explicit address
rotation. Name forms start with the selected gallery name and remain editable.
Browsing name offers now fetches the first page directly; manual cursor/limit
input remains under Advanced offer search. Swap action feedback no longer
writes over Bitcoin sync feedback. The native send builder remains the judge
of spendability, including eligible pending outputs.

### Isolated Android view tests

`WalletOverviewInstrumentationTest` uses public synthetic snapshots without a
native wallet handle. It tests live setup/sync/stop controls, copying the
current address, disconnected Shakedex, home layout, and empty Names. It
requires the `.walletux` application ID and never opens an installed user's
wallet. Screenshots are fixture evidence, not proof of live transaction flows.

Build the separate **Shakescape UX Preview** app and test APK with:

```sh
cd android
./gradlew --init-script ../tests/wallet-ux/preview.init.gradle \
  assembleDebug assembleDebugAndroidTest
```

On the ARM development host use the APK Workbench Gradle wrapper. When only
shell sources have changed, `-x :app:buildRustAndroid` may reuse already-built
JNI libraries. Install the resulting target and test APKs with `adb install -r`
and run the specific test class with:

```sh
adb shell am instrument -w \
  -e class com.denuoweb.hnsdane.ui.WalletOverviewInstrumentationTest \
  com.denuoweb.hnsdane.walletux.test/androidx.test.runner.AndroidJUnitRunner
```

The test writes timestamped PNGs to the preview app's external-files
`wallet-ux-previews` directory. Export them and any needed existing device logs
without clearing logs or app data. The preview app has separate storage from
release, debug, and legacy-recovery packages.

### Validation results and limits

- Android debug app and instrumentation APK compile with the APK Workbench
  wrapper and the existing JNI libraries (`-x :app:buildRustAndroid`). No Rust
  or bundled RocksDB compilation was needed for these UI changes.
- Android unit suite: **472 tests in 78 suites**, no failures, errors, or skips.
  The new state cases distinguish recovery setup from a new wallet's automatic
  birthday, maintain Stop during full-history scanning, and block competing
  actions while a birthday is being saved.
- Pixel 9 view instrumentation: **4 tests passed** in the isolated preview
  package. Tests target dialog roots explicitly and wait for a presented frame
  before screenshots. The first run exposed a test-root targeting issue;
  explicit dialog matching resolved it without changing production behavior.
- Reviewed device screenshots of Wallet home, Bitcoin recovery setup, active
  sync, Receive, disconnected Shakedex, and empty Names. The visual review also
  caught the old blank Names collectible placeholder, now replaced with a clear
  explanation. Real tracked names retain their collectible presentation.
- Wallet catalog generation, Android translation coverage/format tokens for all
  20 locales, runtime-boundary checks, and `git diff --check` pass. New UX copy
  uses the documented English fallback pending reviewed translations.
- Android full lint **did not complete**. The initial run spent over ten minutes
  in the dependency-provided `RepeatOnLifecycleWrongUsage` traversal. A local
  retry disabling only that rule also remained in source analysis after twelve
  minutes and was stopped. No lint suppression was committed; lint is not
  reported as passing. Build and unit tests completed separately.
- iOS build and XCTest execution remain unverified: this Linux host has neither
  Xcode nor the Swift toolchain. A Swift tree-sitter syntax comparison found no
  new parser errors across the changed Swift files relative to the audit base;
  this does not substitute for compilation or device testing.
- No real send, swap, recovery, or name transaction was executed. The UI fixtures
  deliberately have no signing authority. Live network and transaction flows,
  iOS device layouts, and larger accessibility sizes still need release smoke
  testing on their normal platform environments.

The preview is installed separately as **Shakescape UX Preview**
(`com.denuoweb.hnsdane.walletux`). It has its own app storage. Existing release,
debug, and legacy-recovery installations were not replaced. Local screenshots
and build/test logs are preserved in the Git-ignored
`diagnostics/wallet-ux-2026-09-29/` directory; device logs were exported without
clearing the on-device log stream.

## Dead-code follow-up

The cleanup traced Wallet controller references, menu callbacks, Objective-C
selectors, shared UI helpers, tests, and resource-key consumers across the
tracked repository. It removed:

- Twelve unused or unreachable controller helpers, including the retired
  Bitcoin-only offer list/cancel UI. The live My offers flow still handles both
  trade directions and cancellation.
- Eleven iOS button objects that were never placed in a view hierarchy. Three
  availability values now live in explicit state instead of detached buttons;
  Bitcoin recovery-start editing reads its actual prerequisites directly.
- Five write-only labels and the obsolete flat Names-list presentation. Name
  gallery rendering and the name-import confirmation formatter remain active.
- Eighty-six unused resource keys, including obsolete settings-row descriptions,
  old card/status labels, and unused new UX copy. The generator now projects 559
  used keys from the two translation cohorts (66 critical, 493 operations),
  preserving complete coverage for all 20 supported non-English locales.
- The unused iOS companion-bootstrap property. The runtime-boundary check now
  verifies installation of the active direct HNS controller and rejects the
  obsolete property, matching the existing Android guard. Authority, storage
  lease, credential, native bridge, and transaction checks remain in place.

Compatibility and recovery paths with live callers, including tracked FINALIZE
handling and the shared native wallet interfaces, were retained. This cleanup
does not remove storage migrations or change the Rust/C/JNI ABI.

Final validation passed: Android preview build, all 472 unit tests in 78 suites
(no failures, errors, or skips), both localization projections, runtime-boundary
checks, and whitespace checks. The changed Swift files have no new parser
errors relative to the prior revision. Xcode validation remains unavailable on
this Linux host, as documented above. Existing preview wallet data was not used
as an instrumentation fixture now that the preview is in use. The updated APK
was installed over the preview without clearing its data. Build evidence is in
`diagnostics/wallet-cleanup-2026-09-29/`.

## HNS catch-up follow-up — 2026-09-30

On the connected Pixel 9, the pre-update wallet remained at birthday height
349,207 and displayed a generic synchronization failure. A preserved logcat
capture showed that the direct header round had fewer than the two independent
peers required for verification. The wallet `0.4.0` peer coordinator now
retries retired DNS candidates while below quorum and reports temporary
agreement failures as retryable catch-up. The mobile sync adapter retries a
bounded number of rounds, then keeps balances and sending unavailable until a
verified snapshot exists. The two-peer security requirement is unchanged.

After installing the updated Android debug APK over the existing app without
clearing its data, the wallet advanced its headers and scan from 349,207 to
349,324 and reported a verified snapshot. A timestamped logcat export and
screen captures are retained locally in `.codex-diagnostics/`; they are not
committed because they contain device-specific information. The isolated
`.walletux` preview package is built from the same Rust closure and remains
separate from the user's debug and release wallet data.

The view-only Wallet overview suite now runs under the separate
`.walletuxfixture` package. All four instrumentation tests passed on the
connected Pixel 9 on 2026-09-30; the suite did not open the saved preview
wallet or clear any application data.
