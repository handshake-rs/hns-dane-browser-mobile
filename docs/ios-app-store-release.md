# iOS App Store release

The release path uses the standard `macos-26` GitHub-hosted runner in this public repository. Standard GitHub-hosted runners are free for public repositories, so MacInCloud is not part of the normal release path.

The committed application identity is:

- Team ID: `45NQQK3G3S`
- Bundle ID: `com.denuoweb.hnsdane.ios`
- Display name: `Shakescape`
- Deployment floor: iOS 17.0
- Current release candidate: `1.0.15` (`78`); prepared for qualification; standing account declarations confirmed
- Device families: iPhone and iPad; compatible iOS-on-Apple-silicon-Mac use is permitted by the target

Native send, name, and swap actions require verified synchronization and
user approval. Website-provider and HNSA/HNSR service roles are unavailable.
The target omits default-browser and MarketplaceKit installation entitlements.
Qualify the selected source using [release readiness](release-readiness.md)
and the [device procedure](ios-device-validation.md).

## Prepare a release

Qualify the exact `main` source with the normal unsigned CI gates, verify the
candidate version/build and published dependencies, and validate the metadata.
Run installed-device checks and review the live store state before delivery.

The owner confirmed on 2026-10-03 that App Privacy, age rating, content rights,
DSA/trader status, export compliance, category, price, availability, and routing
answers are accurate. Use the
[standing release confirmation](release-readiness.md#standing-release-instructions)
and pass `confirm_account_readiness=true` when submitting. Do not repeat an
accuracy audit or ask for another attestation as a release prerequisite.

Sign and export the IPA as part of an actual authorized App Store Connect upload.
A separate signed IPA export and an IPA attachment to a GitHub Release are not
required for release preparation or routine commits. Google Play uses the
Android App Bundle; its upload-signed bundle is prepared for the selected Play
release rather than for every commit.

## One-time Apple setup

1. In Apple Developer, accept all current agreements and register an explicit App ID for `com.denuoweb.hnsdane.ios`. No optional capabilities are currently required.
2. In App Store Connect, verify the existing iOS app record against the fixed
   values in `store-assets/app-store/metadata/README.md`. Under Pricing and
   Availability, leave **Make this app available on Mac** enabled. Apple makes
   compatible iPhone/iPad apps available on Apple-silicon Macs by default. The
   owner has confirmed the existing availability configuration.
3. In App Store Connect **Users and Access → Integrations → App Store Connect API**, enable API access if needed and create a **team** API key for CI.
4. Download the `.p8` private key once. Record its 10-character Key ID and issuer UUID. Never commit the key, attach it to an issue, paste it into chat, or publish it as a workflow artifact.
5. Create an Apple Distribution certificate and an App Store provisioning profile for the explicit App ID. Export the certificate and private key as a password-protected `.p12` that macOS Keychain can import. Use Keychain Access, or a PKCS#12 export format supported by the macOS Keychain importer. App Store profiles contain no registered devices, so this setup does not require an iPhone.

The app embeds Rust implementations of industry-standard TLS, DNSSEC, and DANE
cryptography. Export compliance is already covered by the owner's standing
confirmation; the processed build's `usesNonExemptEncryption=false` value is
verified by the release client.

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
selected at dispatch. Screenshot capture defaults off so a binary-only release
preserves the screenshots already in App Store Connect and cannot be blocked by
an unrelated capture run. Set `capture_screenshots=true` only when preparing a
replacement sets; that path captures and fully verifies exact-commit iPhone and
iPad manifests, digests,
runtime trust evidence, and visible native wallet row before Apple credentials
are read. The workflow then re-reads remote `main` and stops
before materializing credentials if the branch moved. The signed-upload helper
checks the exact clean tracked source and hard-coded repository `main` again
immediately before Apple's irreversible upload call. A global upload lease also
prevents two different commit-keyed runs from signing or uploading concurrently.
The workflow uploads the build to App Store Connect and retains the same App
Store-signed IPA plus a SHA-256/size/source-commit provenance record as a
commit-keyed workflow artifact for seven days as upload verification evidence.
That retention does not call for attaching the IPA to a GitHub Release. Users
install the iOS release through the App Store.

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
2. skips screenshot work by default; when `capture_screenshots=true`, captures
   the exact-commit live Release set, requires the native wallet row to be
   visibly represented, verifies every digest and provenance field, and retains
   the set for review, with any capture failure blocking all later steps;
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
   `hns-dane-browser-ios-app-store.provenance.json` as protected workflow
   evidence. iOS installation is through the App Store;
6. deletes the temporary keychain, installed profile, API key, `.p12`, and
   profile while GitHub discards the runner.

## Apply metadata and submit through the API

Set `review_contact_source_version` to an approved App Store version with complete
private review contact details. Verify that reference through App Store Connect
before dispatching; the workflow copies the contact without printing it.

After the upload run succeeds and build `78` finishes processing, use the
separate protected workflow. Its default `discover` mode performs authenticated
GET requests only. Pin both the exact current `main` automation commit and the
signed-artifact commit from the successful upload run. They may differ only by
the guarded release-workflow, client, tests, and this release guide; any app or
metadata change fails closed. Mutation modes additionally require that upload
run, its retained signed-IPA evidence, and release-specific confirmation
strings. The client applies and reads back the version/app-info localizations,
exact build, and App Review details while leaving the
current screenshots untouched. `submit` then creates or safely resumes a Review
Submission containing only this App Store version and marks it submitted as its
final mutation.

The metadata client checks current main and the exact candidate version before
applying changes. An active review must be resolved before a successor can be
submitted. Previously released versions are preserved.

```sh
expected_commit="$(git rev-parse HEAD)"
artifact_commit=REPLACE_WITH_SUCCESSFUL_UPLOAD_RUN_HEAD_SHA

gh workflow run ios-app-store-submit.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f expected_artifact_commit="$artifact_commit" \
  -f mode=discover \
  -f review_contact_source_version="$review_contact_source_version" \
  -f confirm_account_readiness=false
```

After recording the successful `ios-app-store-upload.yml` run ID, apply the
metadata and submit using the owner's standing account-readiness confirmation:

```sh
upload_run_id=REPLACE_WITH_SUCCESSFUL_UPLOAD_RUN_ID

gh workflow run ios-app-store-submit.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f expected_artifact_commit="$artifact_commit" \
  -f expected_upload_run_id="$upload_run_id" \
  -f mode=submit \
  -f review_contact_source_version="$review_contact_source_version" \
  -f confirm_metadata=APPLY_METADATA_1.0.15_78 \
  -f confirm_submit=SUBMIT_FOR_REVIEW_1.0.15_78 \
  -f confirm_account_readiness=true
```

After the submission workflow succeeds, set the submitted version to release
automatically when Apple approves it:

```sh
gh workflow run ios-app-store-submit.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f expected_artifact_commit="$artifact_commit" \
  -f expected_upload_run_id="$upload_run_id" \
  -f mode=auto-release \
  -f review_contact_source_version="$review_contact_source_version" \
  -f confirm_auto_release=SET_AUTO_RELEASE_1.0.15_78 \
  -f confirm_account_readiness=true
```

The `1.0.15` submission requires screenshots from its exact candidate. Capture the shipping Release runtime from the exact
artifact commit, review the resulting iPhone and iPad images, and pass the
successful screenshot run ID with
`-f confirm_screenshot_replacement=REPLACE_SCREENSHOTS_1.0.15_78` in the
metadata step. The guarded client replaces and verifies both device-family
sets before submission. The new set shows a successfully rendered,
DANE-verified HNS page, Settings, Handshake settings, and Wallet onboarding.
The capture requires current authenticated headers and exact navigation/security
evidence. A runner network that blocks Handshake peers cannot satisfy that gate;
retain its diagnostics and resolve network access before using its images
for submission.

If the exact build is not yet `VALID`, the workflow fails closed before
submission and can be rerun after processing. It copies the private review
contact fields from the configured reference version only if they are
complete; it never prints them. App/account-level declarations that the API
client deliberately does not mutate must be retained as separate readback
evidence.

After upload, read back the bundle ID, version, build number, processing state,
screenshot families, and selected review build. Apply submission or automatic
release only through the guarded workflow after the candidate's required gates
and account declarations are verified.
