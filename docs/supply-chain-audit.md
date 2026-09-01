# Build and Supply-Chain Audit

Last audited: 2026-09-01

Current candidate source coordinates Android `1.0.0` / code `52`, the embedded
non-publishable Rust workspace `1.0.0`, and iOS `1.0.0` / build `61`. It uses
the published `hns-rs 0.3.1`, exact public engine/browser-adapter releases, and
the complete `hns-wallet-rs` closure at immutable reviewed Git revision
`b529820801b20c3d2956542433cbb046a4ecbe3a`. Registry dependencies retain
checksums and the Git wallet source is commit-bearing in the lockfile, as
documented in [released-dependency-cohort.md](released-dependency-cohort.md).

The reviewed protocol → wallet → mobile source sequence is complete. Source policy,
the lockfile, and notices bind that exact chain. Historical `0.5.8` application
source `f21bee1c3afccd06604dc99fccb51528e2441055` passed Required CI run
`31402758394` and a fresh Pixel 9 install. That evidence predates the HNWR
projection and remains historical. The pre-ECH code-bearing source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, CodeQL `31433931259`, and Code Quality `31433931278`.
Its exact debug artifact subsequently installed and cold-launched on a Pixel 9;
the installed APK digest and fail-closed native wallet UI matched expectations.
Earlier HNWR-v2/ECH-and-sync-telemetry code-bearing source
`986accb7d86d220af63187031e629a9ce69d71e5` passed full CI
`31807520618`, including repository policy, Rust/supply-chain, Android
build/unit, API 37 native instrumentation, the complete Apple
ABI/XCFramework/app/simulator gate, and Required CI. CodeQL runs `31807519998`
and `31807520229` also passed. That evidence predates the `2061a27` exact-name
import tranche. Exact current application source
`adb9c506fe88c82b0317fd60c12fd6a9702753ed` passed the complete manually
dispatched CI matrix in run `31835813994`: repository policy,
Rust/supply-chain, Android build/unit, API 37 native-runtime instrumentation,
the complete Apple ABI/XCFramework/app/simulator gate, and aggregate Required
CI all succeeded. CodeQL runs `31833858421` and `31833858650` also passed.
Historical debug artifact `9222123624` has
artifact-archive SHA-256
`0c057ba339b64401671e406a3fd9015e254444d4c4b5ac051578819415a8081c`, expires
2026-08-17, and is debug-only rather than store signed.
Signing, exact screenshots, store declaration/readback and upload, and the
physical-iPhone matrix remain open.

The prior exact source at `571ea0c096ba50560c9060e66f742fd5a8ac6a5d`
passed Required CI run `31393998309`, including Rust/supply-chain, Android,
emulator, Apple ABI/XCFramework/app/simulator, and aggregate gates. Its exact
debug APK also passed the full fresh-install Pixel 9 native-wallet lifecycle
exercise. Documentation-only descendant
`ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed the complete matrix again in
manual CI run `31411048376`, including aggregate Required CI. These are retained
as historical `0.5.8` results. Historical CI debug artifact `9080493058` is not
a signed store artifact; no upload-signed `1.0.0` APK/AAB or App Store IPA has
been built or submitted.

## Configured and Local Gates

- The checked-in GitHub Actions workflow always runs a lightweight
  repository-policy and path-classification job, then selects the Rust,
  Android, and Apple gates affected by the complete change set. Shared Rust
  changes run all three gates; platform-only changes skip the opposing shell;
  unknown paths and manual dispatches fail safe by selecting everything. Its
  permissions are read-only, release secrets are not provided, every non-local
  `uses:` reference is pinned to a full commit SHA, checkout credentials are
  not persisted, and concurrent runs on the same ref are cancelled. Current
  historical `0.5.8` application evidence is Required CI run `31402758394` at
  `f21bee1c3afccd06604dc99fccb51528e2441055`. Documentation-only parent
  `ce9c09a40117142d3a26ff1196c2dec3f5e06139` also passed push CI and CodeQL,
  then the full manual matrix in run `31411048376`. Historical HNWR-v1/version
  source `893ba8271787f1ab7247fa78ed8787462b5542fc` passed its own full matrix in
  `31433931682`; the earlier HNWR-v2 source passed its full matrix in
  `31807520618`.
- The protected iOS upload path performs its live Release screenshot capture
  and full exact-commit/digest/runtime/wallet-row verification before it reads
  Apple credentials or uploads an IPA. Capture or verification failure is
  fatal; the historical `0.5.5` set cannot satisfy the candidate schema or
  commit gate.
- Dependabot watches GitHub Actions, Gradle, and all three Cargo lockfile roots weekly.
- Rust uses toolchain `1.92.0`; build, clippy, test, metadata, Android cross-compile, and cargo-deny commands use committed lockfiles with `--locked`. Published HNS, engine, and browser-adapter packages carry Cargo checksums; the wallet closure is locked to one explicit immutable Git revision.
- cargo-deny covers all three manifests. The fuzz and exporter packages now declare the repository license. `NCSA` is allowed specifically because `libfuzzer-sys` combines its MIT/Apache-2.0 code with LLVM libFuzzer code under the University of Illinois/NCSA license.
- Gradle 9.6.1 has an official distribution checksum in `gradle-wrapper.properties`; the checked-in wrapper JAR is independently compared with the official wrapper-JAR SHA-256. Android dependency locking runs in strict mode, and Gradle verification metadata pins SHA-256 hashes for resolved artifacts and metadata.
- `scripts/verify-supply-chain.sh` checks the exact wrapper distribution URL and hashes, required lock/verification files, Cargo lock consistency, shell syntax, immutable Action references, tracked secret-bearing filenames, and high-confidence secret patterns. The normal locked Cargo metadata checks retain the selected source revisions without imposing a repository-specific registry-only policy. Root-invoked Rust scripts explicitly select toolchain `1.92.0` instead of relying on rustup to discover a toolchain file beside a manifest in another directory.
- Android JNI release builds reject unknown profiles, compiler/linker/profile overrides, and unexpected cargo-ndk/NDK versions; use `--locked`; force the release profile; require both ABI outputs; and restrict cleanup to `android/app/build`. Path-prefix maps remove checkout, home, Cargo, Rustup, and NDK paths while retaining line-table debug information for AGP. Gradle pins AGP to NDK `28.2.13676358`, treats the NDK location and `source.properties` as incremental inputs, and includes Rust `.txt` data files such as the ICANN TLD snapshot.
- The required Android job now enables KVM and runs the focused fresh-runtime
  regression plus paired HNS/ICANN Proof Details activity instrumentation on a
  Google APIs API 37 x86_64 emulator through
  `ReactiveCircus/android-emulator-runner` pinned to full commit
  `e89f39f1abbbd05b1113a29cf4db69e7540cae5a`. The regression set completed in
  run `30484282637`.
- The unsigned bundle gate requires an exact two-library ABI inventory, `PAGE_ALIGNMENT_16K`, bounds-safe ELF64 ET_DYN files with the expected machine, 16 KiB PT_LOAD alignment, RELRO, one non-executable GNU stack, immediate binding, no text relocations, SHA-1 Build IDs, stripped shipping libraries, matching FULL debug metadata, no local paths, a non-empty R8 mapping, and non-empty third-party notices.
- The signed Play bundle gate reads every content entry through Java's verifying `JarFile`, rejects bad digests, unsigned entries, mixed signers, or a signer that does not match `HNS_DANE_BROWSER_UPLOAD_CERTIFICATE_SHA256`, and depends on the unsigned structural gate.
- The third-party notices generator derives the locked Android release-runtime inventory and the shipping Rust dependency closures for both Android and Apple targets. It accepts checksum-verified registry packages admitted by repository policy, reproduces declared license-file text from within each verified source checkout, commits a full-asset SHA-256, and is checked by `scripts/check.sh` without dependency caches in CI. The same reviewed notice asset is packaged by both application shells, and the exact current local Rust manifest inventory is fingerprinted.
- Keystores, signing properties, service-account files, environment files, private-key formats, local Android properties, and generated APK/AAB artifacts are ignored. The Play API helper keeps its bearer token out of curl's process arguments, validates URL path inputs and release status, and enforces HTTPS/TLS timeouts.

## Audit Results

### Prior `0.5.9` CI Debug Artifact

- Full CI run `31433931682` passed for exact source
  `893ba8271787f1ab7247fa78ed8787462b5542fc`, including policy,
  Rust/supply-chain, Android build/unit, API 37 native instrumentation, Apple
  ABI/XCFramework/app/simulator, and Required CI.
- Artifact `9080493058` extracted to a 65,680,703-byte APK with SHA-256
  `7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`.
  Its package is `com.denuoweb.hnsdane.debug`, version `0.5.9-debug` / code
  `50`, minimum API 30, target API 37, with `arm64-v8a` and `x86_64` native
  libraries.
- APK verification passed Signature Scheme v2 with one default Android Debug
  RSA-2048 signer, certificate SHA-256
  `b51ed3a12c762a69a4c3b31a30c77b5fccc9f0d50417f8a70911b7f60b135d8a`.
  This is intentionally distinct from the upload-certificate/store gates.
- The exact local APK
  `/home/den/.cache/codex/mobile-final-893ba827.TBsXgu/app-debug.apk` installed
  on a Google Pixel 9 (`tokay`), Android 17 / API 37, security patch 2026-07-05,
  build `CP2A.260705.006`, ABI `arm64-v8a`. The
  historical `0.5.8-debug` / code `49` package used an incompatible debug key,
  so the update safely failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Under
  explicit authorization, only the debug package and its data were removed;
  production `com.denuoweb.hnsdane` remained installed and untouched. The
  installed package reported `0.5.9-debug` / code `50`, minimum API 30, target
  API 37, and its `base.apk` SHA-256 matched the artifact.
- Cold launch traversed `LauncherActivity` to `MainActivity` in 469 ms, kept a
  live process, and showed no fatal signature in 300 process log lines.
  `WalletActivity` displayed the fresh-install no-wallet state, create/restore
  controls, fail-closed HNWR rows, sync action, and disabled value/marketplace
  copy. No wallet was created/restored and no secret, account, credentialed
  sync, or value action ran. This is debug installed-UI evidence, not a store
  signing, backend/read, lifecycle-creation, HNSA/HNSR/provider/value/market,
  or physical-iPhone result.

### Released `0.5.6` Android Hotfix

- Android `0.5.6` / code 47 and shared Rust `0.5.6` shipped from source
  `417af67efd68198de4871c0a339d1e456b60cb68`. iOS is deliberately unchanged
  at `0.5.5` / build 57.
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
- Android Proof Details previously combined the retained trace with
  native-gateway routing when choosing HNS versus ICANN presentation. Because
  every canonical DNS host enters that namespace-agnostic gateway, a retained
  HNS selection was replaced with DNSSEC/synthetic ICANN details. The selector
  now accepts only an outcome-consistent ICANN decision from Rust's retained
  `namespaceResolution`; malformed, legacy-only, contradictory, and oversized
  traces do not authorize ICANN presentation.
- The signed 51,323,995-byte APK has SHA-256
  `46022ec141aa5e700592ab6f81d4d246c71b6a2fb80c2e30139f42fa24effeeb`; the
  signed 60,276,192-byte AAB has SHA-256
  `de668002cbcf803a5704028f06331a57c29998d6f9540dd8ccdeede545cb7b69`.
  Exact ABI, 16 KiB, ELF, Build ID/symbol, stripping, path, R8, notice,
  signing, and ZIP-alignment gates passed.
- On a Pixel 9 running Android 17 / API 37, the exact signed APK upgraded code
  `46` to `47` with data preserved, cold-launched, reached `up_to_date` at
  height `340348` with lag `0`, freshness `current`, and `error: null`, and
  passed manual sync plus HNS browsing/proof behavior. Pre-fix instrumentation
  reproduced the HNS-to-ICANN presentation bug; paired HNS/ICANN tests passed
  after correction.
- Required CI passed in run `30484282637` on workflow-only descendant
  `cb930e867b0ddc1f08aaa64e6bf707ff36f0667a`. That descendant is not the
  tagged artifact source and does not change the release binaries.
- Google Play assigned code `47` to production with status `completed` through
  edit `07330408575596336357`; `generatedApks/47` returned HTTP `200`. GitHub
  Release [`v0.5.6`](https://github.com/handshake-rs/hns-dane-browser-mobile/releases/tag/v0.5.6) publishes only the verified APK,
  not the Play AAB or unchanged iOS build.
- Current source consumes the released `0.3.1` HNS, `0.2.2` engine and
  browser-adapter, and `0.2.0` wallet registry cohort with Cargo checksums.
  No HNS ecosystem Git input, local engine path, or patch is admitted. The
  standalone facade is not a mobile input.
  [released-dependency-cohort.md](released-dependency-cohort.md) records the
  enforced boundary.

### Published `0.5.5` iOS Evidence

- Public iOS `0.5.5` / build 57 is the retained Apple baseline. The Apple shell accepts the
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
- Build `57` from source `d926561091634cd69fc9b7e79a4b76003fa4ee47` is the
  sole current iOS submission artifact; detailed Apple build chronology remains
  in `docs/ios-app-store-release.md`.
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
  TestFlight distribution was created.

### Historical `0.5.0` Evidence

- The complete local `scripts/check.sh` gate passed on 2026-07-16 for the then-current source, including supply-chain and version checks, warning-denied Clippy, all three cargo-deny scopes, the full Rust workspace tests, fuzz smoke, iOS C ABI tests, and the header-snapshot exporter.
- Android passed 192 unit tests plus debug and release lint with no errors. A clean build using Gradle 9.6.1, AGP 9.2.1, compile/target SDK 37, build-tools AAPT2 36.1.0, and NDK `28.2.13676358` passed R8/resource shrinking and the unsigned and upload-signed bundle gates.
- The scripted isolated topology and bounded load tier passed, followed by the real four-`hsd` regtest tier at height 91 with a registered `relaytest` name, four matching chain/tree states, verified Urkel inclusion, local DNSSEC and DANE, HTTPS 200, bad-to-good relay failover, and no legacy DoH sentinel contact. The focused `hsd` responder suite passed 47 tests with ESLint clean.
- The upload-signed code 40 APK used the established RSA-4096 signer, passed APK Signature Scheme v2 and 16 KiB ZIP alignment, and had SHA-256 `bff5ba468b0c5ad2d134603127f089ad6fdc9e9b5ceab921825e570cfefd60fb`.
- The upload-signed AAB passed content-signature, ABI, 16 KiB ELF-alignment, hardening, stripping, matching-symbol, local-path, mapping, and notices gates and had SHA-256 `96c5926c559881ba74e380eea062dce3de6cefaf91d3753882e528cccc96e1d0`.
- The separate debug test APK used package `com.denuoweb.hnsdane.relaytest`, version `0.5.0-relay-test` / code 40, and SHA-256 `019aeb82b84de878716637fd053321a4590e0c384de3010e885af7e154803990`.
- Those artifacts predate Android `0.5.6` and do not validate its source or
  binaries.

### Historical `0.4.1` Evidence

- `scripts/check.sh` passed locally on 2026-07-15 for Android `0.4.1` with shared Rust engine `0.4.0`, including supply-chain/version checks, formatting, clippy with warnings denied, all three cargo-deny scopes, the complete Rust test matrix, fuzz-target compilation, and the header-snapshot exporter.
- The final `0.4.1` Android build passed 187 unit tests, debug and release lint with zero errors, R8/resource shrinking, upload signing, APK signature and 16 KiB ZIP-alignment verification, and both release-bundle gates. It used Gradle 9.6.1 / AGP 9.2.1, compile/target SDK 37, NDK `28.2.13676358`, and build-tools AAPT2 36.1.0. The signed AAB SHA-256 was `4b2cc8b1da7700675eedb1ed2319ccafd9541acc7114abff9bd60eb6399b4267`; the signed APK SHA-256 was `a5a9d50d5b19302af488f7f5e6c68281364070edc7edcb14e16dbb1e1a5d61a2`.
- Independent artifact inspection confirmed both installed JNI libraries were NDK r28c API 34 ET_DYN files, stripped, 16 KiB-aligned, RELRO, non-executable-stack, immediate-binding, text-relocation-free, and paired with unstripped `.dbg` files carrying the same Build IDs. No checkout/home/NDK path was found; the signed release APK passed 16 KiB ZIP alignment.
- The shared-runtime tree passed 5/5 connected Pixel instrumentation tests plus live `https://denuoweb/` and `https://aboutlife/` DNSSEC/DANE acceptance. The exact signed `0.4.1` APK subsequently upgraded the Pixel 9 from code 38 to code 39 and cold-launched its main activity successfully.
- cargo-deny reported no known advisory, source, or license-policy failures for the shipping workspace, fuzz workspace, or exporter. Duplicate transitive versions and unused allow-list entries remained warnings.
- No high-confidence secret or secret-bearing filename was found among tracked files.
- GitHub Actions [run 29477163745](https://github.com/Denuo-Web/hns-dane-browser/actions/runs/29477163745) passed the `0.4.1` code and build-policy tree before the evidence-only documentation update. At that historical checkpoint, Actions was subsequently disabled and `main` had neither branch protection nor a ruleset; those statements are not descriptions of the current `handshake-rs` repository state.

## Residual Risks

- This audit pins inputs but does not establish bit-for-bit reproducible APK/AAB output. Runner images, the JDK 21 patch release selected by setup-java, Android SDK packaging, archive timestamps, and signing can still vary. A future release process should compare independently built unsigned artifacts before signing.
- Gradle verification metadata was generated from artifacts already obtained over the configured HTTPS repositories. Future checksum changes require a deliberate review; the metadata is an integrity pin, not independent provenance proof.
- cargo-deny relies on the current RustSec advisory database at check time. CI availability or an upstream advisory-database outage can affect results.
- The local JNI script defaults to and enforces NDK `28.2.13676358`; `HNS_ANDROID_NDK_VERSION` may override that expectation only for an intentional, reviewed toolchain change.
- Code 47 was accepted by Google Play with the established upload identity.
  The signature-aware bundle gate remains the local fail-closed check for
  future uploads.
- The exact signed Pixel 9 checks covered upgrade, cold launch, current sync,
  HNS browsing, and proof presentation, but not every lifecycle, Service Worker,
  download, WebSocket, requester/recovery, or cross-origin device-matrix row.
