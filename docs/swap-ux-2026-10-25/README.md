# ShakeDex visual check — 2026-10-25

Captured on a Moto Android 9 device at 1080 × 2160 using the isolated
`com.denuoweb.hnsdane.walletuxfixture` app. The fixture has a submitted Bitcoin
lock, a verified block checkpoint, and an unexpired funding window. It has no
wallet keys, peer, signing authority, or transaction broadcast.

- [Scanning Bitcoin](scanning-bitcoin.png): the active card shows the lock,
  confirmation scan, checked block, action state, progress, and funding window.
  No manual scan button is present while scanning runs.
- [Scan stopped](scan-stopped.png): the same card says the scan stopped and
  exposes **Resume scan**. That button is disabled in this isolated fixture
  because the fixture has no native wallet handle; a live unlocked wallet with
  Bitcoin value can enable it.
- [Swap detail](swap-detail.png): tapping the card opens a short summary with
  **Technical details** available separately.

The capture verifies the rendered Android states and navigation. It does not
substitute for a paired, funded swap on two devices or an iOS visual run.
