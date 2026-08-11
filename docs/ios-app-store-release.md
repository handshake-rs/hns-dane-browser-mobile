# iOS App Store release

The release path uses the standard `macos-26` GitHub-hosted runner in this public repository. Standard GitHub-hosted runners are free for public repositories, so MacInCloud is not part of the normal release path.

The committed application identity is:

- Team ID: `45NQQK3G3S`
- Bundle ID: `com.denuoweb.hnsdane.ios`
- Display name: `HNS DANE Browser`
- Deployment floor: iOS 17.0
- Current public App Store version: `0.5.5`, published 2026-07-31 and
  rechecked through the public record on 2026-08-09
- Published iOS build: `0.5.5` (`57`) at source
  `d926561091634cd69fc9b7e79a4b76003fa4ee47`
- Configured release candidate: `0.5.10` (`60`), not uploaded
- Device family: iPhone

Candidate build `60` includes a native-only wallet screen for
create/restore/open/status/unlock/lock, one HNS account identity, and strict
HNWR-v1 read-only fields for balance, receive target, history, tracked names,
and module status.
The underlying wallet tranche passed its dated complete Apple gate in Required
CI run `31393998309` at
`571ea0c096ba50560c9060e66f742fd5a8ac6a5d`. Historical `0.5.8` source
`f21bee1c3afccd06604dc99fccb51528e2441055`, using the earlier wallet pin and
`hns-rs` `b24b66c`, passed Required CI run `31402758394`; documentation-only
descendant `ce9c09a40117142d3a26ff1196c2dec3f5e06139` passed full manual CI run
`31411048376`; those runs remain historical. Code-bearing `0.5.9` source
`893ba8271787f1ab7247fa78ed8787462b5542fc` passed full CI
`31433931682`, including the complete Apple ABI/XCFramework/app/simulator gate
and aggregate Required CI. Build `57` does not contain the controller. The
current candidate pins wallet `2229be8`. Wallet-aware hosted
privacy source `909dbd1a713f322f0a8d4cff88e765c612e184f3` was deployed and read
back for the historical lifecycle boundary. Version-neutral HNWR-aware source
`a5539cb063fb4b19fed4dff5400a3bc991acdc4f` was deployed and read back in
Firebase run `31485234945`. The `0.5.10` description, What's New, and review
notes are updated, while
fresh exact-release-checkout screenshots, App Privacy/category answers,
signing, processing, submission, and the physical-iPhone matrix remain release
gates. CI also produced Android debug artifact `9080493058` (APK SHA-256
`7ea4c5b7cb4e2713287bf90794a6bb706311d0bb8fbb7348f94875ce615cc8fb`),
which subsequently installed and cold-launched on a Pixel 9 and exposed the
expected fresh no-wallet/fail-closed wallet UI. It remains default-debug-key
signed rather than store signed, and this Android result is not iOS evidence.
No wallet was created/restored and no credentialed read or value action ran.

The product installs no scoped loopback credential or indexed wallet backend, so
the visible read fields remain fail-closed and unavailable. The live pruned
`hsrd` is unsuitable because it lacks wallet indexing/authentication. A pruned
indexed node can still return indexed confirmation/history, and an existing
wallet may reuse authenticated retained raw bytes. Fresh restore additionally
needs archive-capable raw bytes or another durable wallet-relevant raw-tx source.
Name import is absent.
Website-provider, send/value, settlement, exchange, HNSA/HNSR, and P2P-market
gates remain false. iOS now implements nonblocking lifecycle teardown with an
exact lease handoff. Exact-source Apple XCTest covers the retirement
queue/lease behavior and stale-completion publication-authority checks through
`walletReadMayPublish`; it does not execute an end-to-end credentialed native
read in flight. That scenario remains unavailable until the scoped
credential/backend/data boundary exists, and physical-iPhone repetition remains
open.

## One-time Apple setup

1. In Apple Developer, accept all current agreements and register an explicit App ID for `com.denuoweb.hnsdane.ios`. No optional capabilities are currently required.
2. In App Store Connect, verify the existing iOS app record against the fixed
   values in `store-assets/app-store/metadata/README.md`.
3. In App Store Connect **Users and Access → Integrations → App Store Connect API**, enable API access if needed and create a **team** API key for CI.
4. Download the `.p8` private key once. Record its 10-character Key ID and issuer UUID. Never commit the key, attach it to an issue, paste it into chat, or publish it as a workflow artifact.
5. Create an Apple Distribution certificate and an App Store provisioning profile for the explicit App ID. Export the certificate and private key as a password-protected `.p12` that macOS Keychain can import. Use Keychain Access, or OpenSSL 3's legacy-compatible PKCS#12 export mode instead of its default PBES2/AES encoding. App Store profiles contain no registered devices, so this setup does not require an iPhone.

Apple's export-compliance questionnaire must be completed deliberately. The app embeds Rust implementations of industry-standard TLS, DNSSEC, and DANE cryptography rather than limiting encryption to Apple's operating-system APIs, so the answer and any required documentation must come from App Store Connect's current questionnaire.

## One-time GitHub setup

Create an environment named exactly `app-store`, restrict deployment branches to `main`, and require approval if the repository plan exposes that control. Add these environment secrets:

- `APP_STORE_CONNECT_API_KEY_ID`
- `APP_STORE_CONNECT_API_ISSUER_ID`
- `APP_STORE_CONNECT_API_PRIVATE_KEY` — the complete downloaded `.p8` file
- `IOS_DISTRIBUTION_P12_BASE64` — the macOS-compatible, password-protected Apple Distribution `.p12`, base64 encoded on one line
- `IOS_DISTRIBUTION_P12_PASSWORD` — the `.p12` password, with no trailing newline
- `IOS_APP_STORE_PROFILE_BASE64` — the App Store `.mobileprovision` file, base64 encoded on one line

From a trusted local shell with `gh` authenticated as a repository administrator:

```sh
gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store APP_STORE_CONNECT_API_KEY_ID
gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store APP_STORE_CONNECT_API_ISSUER_ID
gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store APP_STORE_CONNECT_API_PRIVATE_KEY < /trusted/path/AuthKey_KEYID.p8
base64 -w0 /trusted/path/apple-distribution.p12 | gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store IOS_DISTRIBUTION_P12_BASE64
gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store IOS_DISTRIBUTION_P12_PASSWORD < /trusted/path/p12-password.txt
base64 -w0 /trusted/path/app-store.mobileprovision | gh secret set --repo handshake-rs/hns-dane-browser-mobile --env app-store IOS_APP_STORE_PROFILE_BASE64
```

## Upload a build

The workflow is manual, refuses non-`main` refs, has read-only GitHub
permissions, and requires the exact lowercase 40-character commit already
reviewed and qualified. The requested commit must equal the `main` commit
selected at dispatch. After the complete unsigned simulator/device-link gate,
the workflow captures four live Release screenshots and fully verifies their
exact-commit manifest, digests, runtime trust evidence, and visible native
wallet row. Any screenshot failure stops the job before Apple credentials are
read or an IPA is uploaded. The workflow then re-reads remote `main` and stops
before materializing credentials if the branch moved. The signed-upload helper
checks the exact clean tracked source and hard-coded repository `main` again
immediately before Apple's irreversible upload call. A global upload lease also
prevents two different commit-keyed runs from signing or uploading concurrently.
The workflow uploads the build to App Store Connect and retains the same App
Store-signed IPA plus a SHA-256/size/source-commit provenance record as a
private, commit-keyed workflow artifact for seven days so the release operator
can publish it with the matching GitHub Release.

```sh
expected_commit="$(git rev-parse HEAD)"
printf '%s\n' "$expected_commit" | grep -Eq '^[0-9a-f]{40}$'
gh workflow run ios-app-store-upload.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f confirm_upload=true
```

The workflow then:

1. runs `scripts/run-ios-gate.sh` with Xcode 26.5/26.6 and the iOS 26.5 SDK;
2. captures the exact-commit live Release screenshot set, requires the native
   wallet row to be visibly represented, verifies every digest and provenance
   field, and retains the set for review; failure blocks all later steps;
3. rechecks remote `main`, then writes the API key, distribution identity, and
   App Store profile only to the ephemeral runner's private temporary directory;
4. verifies the identity and profile against the fixed team and bundle IDs,
   then creates a Release archive using manual App Store distribution signing
   in a disposable keychain;
5. verifies the archived app identity and compiled AppIcon catalog, then
   exports the signed IPA, validates/exports the archive with App Store Connect
   authentication, rechecks exact source and current remote `main`, uploads the
   configured candidate build, and retains
   `ios-app-store-ipa-<commit>` with
   `hns-dane-browser-ios-app-store.provenance.json` for release publication;
6. deletes the temporary keychain, installed profile, API key, `.p12`, and
   profile while GitHub discards the runner.

## Apply metadata and submit through the API

After the upload run succeeds and build `60` finishes processing, use the
separate protected workflow. Its default `discover` mode performs authenticated
GET requests only. Mutation modes require the exact current `main` commit, the
successful upload run for that commit, its verified screenshot artifact, and
release-specific confirmation strings. The client applies and reads back the
version/app-info localizations, manual release type, exact build, App Review
details, and four exact-commit screenshots. `submit` then creates or safely
resumes a Review Submission containing only this App Store version and marks it
submitted as its final mutation.

```sh
expected_commit="$(git rev-parse HEAD)"

gh workflow run ios-app-store-submit.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f mode=discover \
  -f review_contact_source_version=0.5.5 \
  -f confirm_account_readiness=false
```

After recording the successful `ios-app-store-upload.yml` run ID and confirming
that App Privacy, unrestricted-web age rating, content rights, DSA, export,
category, price, availability, and routing answers remain accurate, apply the
metadata and intentionally submit:

```sh
upload_run_id=REPLACE_WITH_SUCCESSFUL_UPLOAD_RUN_ID

gh workflow run ios-app-store-submit.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f expected_upload_run_id="$upload_run_id" \
  -f mode=submit \
  -f review_contact_source_version=0.5.5 \
  -f confirm_metadata=APPLY_METADATA_0.5.10_60 \
  -f confirm_submit=SUBMIT_FOR_REVIEW_0.5.10_60 \
  -f confirm_account_readiness=true
```

If the exact build is not yet `VALID`, the workflow fails closed before
submission and can be rerun after processing. It copies the private review
contact fields from the already published `0.5.5` version only if they are
complete; it never prints them. App/account-level declarations that the API
client deliberately does not mutate must be retained as separate readback
evidence.

Apple associates the uploaded build with the app record using its bundle ID,
version, and build number. Build `49` is superseded. Builds `50`–`56` were not
uploaded; their live runs identified and
closed provisional-connection recovery, factual readiness, superseded
navigation, and compressed negative-evidence timing gaps. Build `57` expands
the permitted compressed negative-evidence names, samples freshness after root
resolution, keeps semantic Proof Details selection stable during sync polling,
and forces an origin revalidation when a cached main frame has no new Rust
status. Exact-head Apple CI run `30454904736` and live Release screenshot run
`30454926117` passed at
`d926561091634cd69fc9b7e79a4b76003fa4ee47`; the four-image provenance records
current headers, DANE-verified HNS, same-navigation Proof Details, and
authenticated ICANN WebPKI. Protected upload run `30456522039` then passed its
complete unsigned gate, signed and uploaded build `57`, and retained artifact
`8726372341`. The verified IPA is 47,930,601 bytes with SHA-256
`efea01f912035d0e2cde880a59cbe9e5b2e3f546e781fa5d9606942629225345`;
its bundle ID, version/build, iPhone-only family, App Store profile, disabled
debug entitlement, icon, and encryption declaration all match the release.
Public GitHub Release `v0.5.5` publishes that exact IPA as asset `494101433`
beside the verified code 46 APK.

Build `60` is the configured candidate. Historical HNWR application-source CI,
CodeQL, lockfile/notices, and the complete Apple app/simulator gate are
satisfied at exact source `893ba8271787f1ab7247fa78ed8787462b5542fc`; the
current release commit still requires its own gates. Build `60` must not be
uploaded until a fresh
screenshot manifest names the exact release checkout selected for signing and
carries provenance schema 3 with `settings.wallet.native-controls` visible;
the protected workflow must then rerun its complete exact-checkout gate before
credential materialization. App Privacy/category answers also require live
reconciliation. After upload, replace this paragraph with the retained IPA
provenance and App Store Connect readback.

The same protected run completed successfully and retained repeat live-capture
artifact `8727084963` as corroborating workflow evidence. It is not the staged
or submitted set: the reviewed App Store images remain the cleaner
single-attempt captures from run `30454926117`.

Build `57` declares `ITSAppUsesNonExemptEncryption = false` because the build
uses only industry-standard cryptography and excludes France from
App Store availability. Do not add an export-compliance code to this build.
Before enabling France, complete the French encryption declaration; after
Apple approves it, add the supplied export-compliance code to the next build.

## Release gate after upload

For `0.5.10`, every item in
`store-assets/app-store/submission-checklist.md` remains a pre-submission gate.
In particular, build `60` has not been signed, uploaded, processed, selected,
or submitted. The paragraphs below preserve the public `0.5.5` chronology.

The `0.5.5` version-managed metadata, current iPhone screenshots, App Review
details, content-rights declaration, and build `57` were reconciled through
App Store Connect and passed API readback. The aligned hosted privacy policy
was verified separately; app/account-level privacy, age-rating, DSA, pricing,
availability, and routing fields were not managed or read by the guarded
client. The version has `releaseType=MANUAL` and `reviewType=APP_STORE`; the
direct submission entered
`WAITING_FOR_REVIEW` on 2026-07-29. The upload and submission paths did not
create TestFlight groups or distribute the build to testers.

Apple published `0.5.5` on 2026-07-31. A public-store lookup on 2026-08-09
still reports it as the current version. The review and manual-release values
above are retained as the upload chronology, not current availability.

Owning an iPhone is not required to archive, sign, upload, or submit. An
independently installed signed build may be exercised on a real iPhone in a
future qualification cycle, but no TestFlight distribution is part of this
release.
That absence does not block App Store submission, though installed-iOS and
ecosystem qualification remain open; record the matrix from
`docs/ios-device-validation.md` when completed. MacInCloud is only a fallback if
an account-specific problem cannot be resolved through the developer portals
and GitHub Actions logs.
