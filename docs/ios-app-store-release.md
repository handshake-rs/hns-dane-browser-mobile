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
- Device family: iPhone

Unreleased source after build `57` now includes a native-only wallet screen for
create/restore/open/status/unlock/lock and one non-value HNS account identity.
Build `57` does not contain it. Do not reuse the retained “no wallet” metadata
or review notes for a later wallet-bearing build: first pass the final Apple
ABI/XCFramework/app/simulator workflow, review the Swift/UIKit managed-text
zeroization limitation, choose the release version, and reconcile App Privacy,
age/category answers, screenshots, description, and review instructions. The
website provider, value, names, sending, settlement, and marketplace remain
unavailable in the unreleased source as well.

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
permissions, and runs the complete unsigned simulator/device-link gate before
credentials are materialized. It uploads the build to App Store Connect and
retains the same App Store-signed IPA as a private workflow artifact for seven
days so the release operator can publish it with the matching GitHub Release.

```sh
gh workflow run ios-app-store-upload.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f confirm_upload=true
```

The workflow then:

1. runs `scripts/run-ios-gate.sh` with Xcode 26.5/26.6 and the iOS 26.5 SDK;
2. writes the API key, distribution identity, and App Store profile only to the ephemeral runner's private temporary directory;
3. verifies the identity and profile against the fixed team and bundle IDs, then creates a Release archive using manual App Store distribution signing in a disposable keychain;
4. verifies the archived app identity and compiled AppIcon catalog, then
   exports the signed IPA, validates/exports the archive with App Store Connect
   authentication, uploads build `57`, and retains
   `ios-app-store-ipa-<commit>` for release publication;
5. deletes the temporary keychain, installed profile, API key, `.p12`, and profile while GitHub discards the runner.

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
