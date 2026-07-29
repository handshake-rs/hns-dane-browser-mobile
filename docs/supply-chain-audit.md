# Build and Supply-Chain Audit

Last audited: 2026-07-29

## Configured and Local Gates

- The checked-in GitHub Actions workflow always runs a lightweight
  repository-policy and path-classification job, then selects the Rust,
  Android, and Apple gates affected by the complete change set. Shared Rust
  changes run all three gates; platform-only changes skip the opposing shell;
  unknown paths and manual dispatches fail safe by selecting everything. Its
  permissions are read-only, release secrets are not provided, every non-local
  `uses:` reference is pinned to a full commit SHA, checkout credentials are
  not persisted, and concurrent runs on the same ref are cancelled. Current
  evidence: feature commit `14edcaf` passed every selected job in
  [run 30323566765](https://github.com/handshake-rs/hns-dane-browser-mobile/actions/runs/30323566765),
  and docs-only HEAD `153db03` passed repository policy and Required CI in
  [run 30393560141](https://github.com/handshake-rs/hns-dane-browser-mobile/actions/runs/30393560141).
  Those remain historical runs; no completed remote run is claimed for the
  current `0.5.6` hotfix source.
- Dependabot watches GitHub Actions, Gradle, and all three Cargo lockfile roots weekly.
- Rust uses toolchain `1.92.0`; build, clippy, test, metadata, Android cross-compile, and cargo-deny commands use committed lockfiles with `--locked`. Registry packages carry Cargo checksums; the only locked Git packages are the five exact-revision engine exceptions detailed below.
- cargo-deny covers all three manifests. The fuzz and exporter packages now declare the repository license. `NCSA` is allowed specifically because `libfuzzer-sys` combines its MIT/Apache-2.0 code with LLVM libFuzzer code under the University of Illinois/NCSA license.
- Gradle 9.6.1 has an official distribution checksum in `gradle-wrapper.properties`; the checked-in wrapper JAR is independently compared with the official wrapper-JAR SHA-256. Android dependency locking runs in strict mode, and Gradle verification metadata pins SHA-256 hashes for resolved artifacts and metadata.
- `scripts/verify-supply-chain.sh` checks the exact wrapper distribution URL and hashes, required lock/verification files, Cargo lock consistency, shell syntax, immutable Action references, tracked secret-bearing filenames, and high-confidence secret patterns. Cargo Git inputs are denied except for `hns-browser-runtime`, `hns-browser-observability`, `hns-icann-dane`, `hns-namespace-resolution`, and `hns-resolution-policy` from the canonical `handshake-rs/hns-dane-engine` repository: every manifest revision and locked source must equal the reviewed `7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5` commit, and focused policy tests reject alternate URLs, moving selectors, mismatched lock revisions, or any additional Git package. Root-invoked Rust scripts explicitly select toolchain `1.92.0` instead of relying on rustup to discover a toolchain file beside a manifest in another directory.
- Android JNI release builds reject unknown profiles, compiler/linker/profile overrides, and unexpected cargo-ndk/NDK versions; use `--locked`; force the release profile; require both ABI outputs; and restrict cleanup to `android/app/build`. Path-prefix maps remove checkout, home, Cargo, Rustup, and NDK paths while retaining line-table debug information for AGP. Gradle pins AGP to NDK `28.2.13676358`, treats the NDK location and `source.properties` as incremental inputs, and includes Rust `.txt` data files such as the ICANN TLD snapshot.
- The required Android job now enables KVM and runs the focused fresh-runtime
  instrumentation regression on a Google APIs API 37 x86_64 emulator through
  `ReactiveCircus/android-emulator-runner` pinned to full commit
  `e89f39f1abbbd05b1113a29cf4db69e7540cae5a`. Adding the job is not remote
  completion evidence; its successful run remains a release gate.
- The unsigned bundle gate requires an exact two-library ABI inventory, `PAGE_ALIGNMENT_16K`, bounds-safe ELF64 ET_DYN files with the expected machine, 16 KiB PT_LOAD alignment, RELRO, one non-executable GNU stack, immediate binding, no text relocations, SHA-1 Build IDs, stripped shipping libraries, matching FULL debug metadata, no local paths, a non-empty R8 mapping, and non-empty third-party notices.
- The signed Play bundle gate reads every content entry through Java's verifying `JarFile`, rejects bad digests, unsigned entries, mixed signers, or a signer that does not match `HNS_DANE_BROWSER_UPLOAD_CERTIFICATE_SHA256`, and depends on the unsigned structural gate.
- The third-party notices generator derives the locked Android release-runtime inventory and the shipping Rust dependency closures for both Android and Apple targets, reproduces available license/notice text, commits a full-asset SHA-256, and is checked by `scripts/check.sh` without requiring dependency resolution in CI. The same reviewed notice asset is packaged by both application shells, and both FFI manifests are included in its input fingerprint.
- Keystores, signing properties, service-account files, environment files, private-key formats, local Android properties, and generated APK/AAB artifacts are ignored. The Play API helper keeps its bearer token out of curl's process arguments, validates URL path inputs and release status, and enforces HTTPS/TLS timeouts.

## Audit Results

### Current `0.5.6` Android Hotfix Checkpoint

- Android declares `0.5.6` / code 47 and the shared Rust workspace declares
  `0.5.6`. iOS is deliberately unchanged at `0.5.5` / build 57.
- Root cause analysis found a target-support defect rather than a bad cache,
  missing native library, or stale app data. Rust 1.92's stable
  `std::fs::File::{lock, lock_shared, try_lock_shared, unlock}` implementation
  omitted Android from the targets that call `flock`, so the first exclusive
  header-state lock returned `ErrorKind::Unsupported`. Fresh
  `BrowserRuntime::open` therefore failed during header-state initialization,
  JNI returned no runtime handle, and the Kotlin fallback exposed unknown
  height/target/freshness values.
- The hotfix keeps Rust 1.92 and routes Android lock operations through
  `libc::flock`, including EINTR retry and explicit WouldBlock handling for the
  nonblocking shared probe. `libc 0.2.186` was already checksum-locked and
  present in the shipping dependency inventory, so the direct target
  dependency adds no new registry package; the generated notice fingerprints
  and asset digest were refreshed. Rust merged the equivalent standard-library
  support in
  [rust-lang/rust#157038](https://github.com/rust-lang/rust/pull/157038) for
  Rust 1.98, after this workspace's pinned toolchain.
- Connected Pixel 9 debug validation opened fresh regtest storage at height `0`
  with `error: null`, recovered preserved data to snapshot height
  `300000`, and returned `syncing` with `error: null` after manual **Run**.
  Required CI now contains a focused API 37 emulator test for the fresh native
  runtime path.
- No signed code 47 APK/AAB, Google Play upload/track assignment, GitHub tag or
  Release, or completed remote CI run is claimed at this checkpoint.
- The five engine crates remain pinned to reviewed immutable
  `handshake-rs/hns-dane-engine` commit
  `7f7bb8fa100c2393f2cd5a64c64bf5e20a0f3ab5`.

### Historical `0.5.5` Release Evidence

- Android `0.5.5` / code 46 and shared Rust `0.5.5` were released while iOS
  `0.5.5` / build 57 remained the Apple submission. The Apple shell accepts the
  valid zero revision returned when a fresh runtime reapplies its unchanged
  default policy, so first-install preparation does not fail before browsing.
  Proxy admission now remains suspended until a matching schema-v2 status
  proves the exact non-genesis header prerequisite enforced by Rust, and it
  returns to suspension if currentness expires, fails, or changes network. It
  also recovers at most twice when an admitted idempotent main-frame load encounters
  WebKit's transient provisional connection-lost result. Each attempt waits for
  the current sync to release maintenance, then replaces both the native proxy
  generation and `WKWebView`; the second adds a bounded backoff and fresh safe
  point. Recovery remains bound to the exact failed `WKNavigation` identities.
  Unsafe methods and a failed final attempt remain reportable. The shared DNS
  parser expands RFC 1035-compressed NS and SOA names before retaining record
  RDATA, and namespace-plan freshness is evaluated against a clock sampled
  after root resolution so newly gathered negative evidence is not rejected
  against a stale plan-start time. iOS Settings no longer reloads its table for
  summary-only sync polling, and Reload now forces origin revalidation through
  the active proxy so a cached main frame cannot be presented as newly trusted
  without an exact Rust status.
- The exact `0.5.4` candidate commit `8ffc296` passed the complete portable
  Rust/supply-chain, Android build/test/lint/bundle-structure, and Apple
  ABI/XCFramework/XCTest/simulator/device-link matrix in CI run 30402803553.
- Earlier signed packages remain predecessor evidence only. The useful Apple
  build chronology is retained once in `docs/ios-app-store-release.md`; build
  `57` from source `d926561091634cd69fc9b7e79a4b76003fa4ee47` is the sole
  current `0.5.5` iOS submission/release artifact.
- Full manual CI run `30448341156` passed policy, Rust/supply-chain, Android,
  Apple, and Required CI for the code 46/build 57 version-bump source. The exact
  signed code 46 APK and AAB then passed their release gates and completed
  Google Play production through committed edit `17438779769069438085`;
  `generatedApks/46` returned HTTP `200`. The APK SHA-256 is
  `b36a4346ffcba14c081500ef3dc7c5012cabd30f42cdaa80a354eefb5da210ba`
  and the AAB SHA-256 is
  `728d8892e180d954652668a4e53a7e2d6c7542e9d36330f4803cdecdb34598b0`.
- Build 57's later iOS-only proof-selection and cached-main-frame corrections
  passed exact-head policy, Apple, and Required CI in run `30454904736`.
  Live Release screenshot run `30454926117` produced four fixture-free,
  exact-source 1284 × 2778 images with DANE-verified HNS, same-navigation Proof
  Details, and authenticated ICANN WebPKI provenance. Protected run
  `30456522039` signed and uploaded the 47,930,601-byte App Store IPA with
  SHA-256
  `efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`.
  Its bundle/version/build/profile and signature inputs passed the archive
  checks. App Store Connect reports build `57` `VALID`, the direct submission
  `WAITING_FOR_REVIEW`, manual release, and App Store review type. No
  TestFlight distribution was created. GitHub Release `v0.5.5` publishes this
  exact IPA beside the verified code 46 APK.

### Historical `0.5.0` Evidence

- The complete local `scripts/check.sh` gate passed on 2026-07-16 for the then-current source, including supply-chain and version checks, warning-denied Clippy, all three cargo-deny scopes, the full Rust workspace tests, fuzz smoke, iOS C ABI tests, and the header-snapshot exporter.
- Android passed 192 unit tests plus debug and release lint with no errors. A clean build using Gradle 9.6.1, AGP 9.2.1, compile/target SDK 37, build-tools AAPT2 36.1.0, and NDK `28.2.13676358` passed R8/resource shrinking and the unsigned and upload-signed bundle gates.
- The scripted isolated topology and bounded load tier passed, followed by the real four-`hsd` regtest tier at height 91 with a registered `relaytest` name, four matching chain/tree states, verified Urkel inclusion, local DNSSEC and DANE, HTTPS 200, bad-to-good relay failover, and no legacy DoH sentinel contact. The focused `hsd` responder suite passed 47 tests with ESLint clean.
- The upload-signed code 40 APK used the established RSA-4096 signer, passed APK Signature Scheme v2 and 16 KiB ZIP alignment, and had SHA-256 `bff5ba468b0c5ad2d134603127f089ad6fdc9e9b5ceab921825e570cfefd60fb`.
- The upload-signed AAB passed content-signature, ABI, 16 KiB ELF-alignment, hardening, stripping, matching-symbol, local-path, mapping, and notices gates and had SHA-256 `96c5926c559881ba74e380eea062dce3de6cefaf91d3753882e528cccc96e1d0`.
- The separate debug test APK used package `com.denuoweb.hnsdane.relaytest`, version `0.5.0-relay-test` / code 40, and SHA-256 `019aeb82b84de878716637fd053321a4590e0c384de3010e885af7e154803990`.
- Those artifacts predate both the historical `0.5.5` release and the current
  `0.5.6` source checkpoint; they validate neither.

### Historical `0.4.1` Evidence

- `scripts/check.sh` passed locally on 2026-07-15 for Android `0.4.1` with shared Rust engine `0.4.0`, including supply-chain/version checks, formatting, clippy with warnings denied, all three cargo-deny scopes, the complete Rust test matrix, fuzz-target compilation, and the header-snapshot exporter.
- The final `0.4.1` Android build passed 187 unit tests, debug and release lint with zero errors, R8/resource shrinking, upload signing, APK signature and 16 KiB ZIP-alignment verification, and both release-bundle gates. It used Gradle 9.6.1 / AGP 9.2.1, compile/target SDK 37, NDK `28.2.13676358`, and build-tools AAPT2 36.1.0. The signed AAB SHA-256 was `4b2cc8b1da7700675eedb1ed2319ccafd9541acc7114abff9bd60eb6399b4267`; the signed APK SHA-256 was `a5a9d50d5b19302af488f7f5e6c68281364070edc7edcb14e16dbb1e1a5d61a2`.
- Independent artifact inspection confirmed both installed JNI libraries were NDK r28c API 34 ET_DYN files, stripped, 16 KiB-aligned, RELRO, non-executable-stack, immediate-binding, text-relocation-free, and paired with unstripped `.dbg` files carrying the same Build IDs. No checkout/home/NDK path was found; the signed release APK passed 16 KiB ZIP alignment.
- The shared-runtime tree passed 5/5 connected Pixel instrumentation tests plus live `https://denuoweb/` and `https://aboutlife/` DNSSEC/DANE acceptance. The exact signed `0.4.1` APK subsequently upgraded the Pixel 9 from code 38 to code 39 and cold-launched its main activity successfully.
- cargo-deny reported no known advisory, source, or license-policy failures for the shipping workspace, fuzz workspace, or exporter. Duplicate transitive versions and unused allow-list entries remained warnings.
- No high-confidence secret or secret-bearing filename was found among tracked files.
- The locally configured upload certificate SHA-256 matched the retained and published `0.4.0` APK signer and the `0.4.1` APK. It still needs an out-of-band comparison with the upload certificate shown by Play Console for the next release.
- GitHub Actions [run 29477163745](https://github.com/Denuo-Web/hns-dane-browser/actions/runs/29477163745) passed the `0.4.1` code and build-policy tree before the evidence-only documentation update. At that historical checkpoint, Actions was subsequently disabled and `main` had neither branch protection nor a ruleset; those statements are not descriptions of the current `handshake-rs` repository state.

## Residual Risks

- This audit pins inputs but does not establish bit-for-bit reproducible APK/AAB output. Runner images, the JDK 21 patch release selected by setup-java, Android SDK packaging, archive timestamps, and signing can still vary. A future release process should compare independently built unsigned artifacts before signing.
- Gradle verification metadata was generated from artifacts already obtained over the configured HTTPS repositories. Future checksum changes require a deliberate review; the metadata is an integrity pin, not independent provenance proof.
- cargo-deny relies on the current RustSec advisory database at check time. CI availability or an upstream advisory-database outage can affect results.
- The local JNI script defaults to and enforces NDK `28.2.13676358`; `HNS_ANDROID_NDK_VERSION` may override that expectation only for an intentional, reviewed toolchain change.
- Code 46 was accepted by Google Play with the established upload identity.
  The signature-aware bundle gate remains the local fail-closed check for
  future uploads.
- Code 47 still requires completed remote gates, signed artifact verification,
  exact-artifact device qualification, Play upload, and post-commit API
  readback before it can replace code 46 as production evidence.
