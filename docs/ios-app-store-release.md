# iOS App Store release

The release path uses the standard `macos-26` GitHub-hosted runner in this public repository. Standard GitHub-hosted runners are free for public repositories, so MacInCloud is not part of the normal release path.

The committed application identity is:

- Team ID: `45NQQK3G3S`
- Bundle ID: `com.denuoweb.hnsdane.ios`
- Display name: `HNS DANE Browser`
- Deployment floor: iOS 17.0
- Public App Store baseline observed 2026-07-28: `0.5.0`
- Current iOS release candidate: `0.5.5` (`56`); build `48` is predecessor
  evidence, build `49` is a superseded App Store Connect upload, and builds
  `50`–`55` were not uploaded
- Device family: iPhone

## One-time Apple setup

1. In Apple Developer, accept all current agreements and register an explicit App ID for `com.denuoweb.hnsdane.ios`. No optional capabilities are currently required.
2. In App Store Connect, verify the existing iOS app record against the fixed
   values in `dist/app-store/metadata/README.md`.
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
   authentication, uploads build `56`, and retains
   `ios-app-store-ipa-<commit>` for release publication;
5. deletes the temporary keychain, installed profile, API key, `.p12`, and profile while GitHub discards the runner.

Apple associates the uploaded build with the app record using its bundle ID,
version, and build number. Build `49` has already been uploaded and is
superseded. Builds `50`–`55` were not uploaded. Build `55` passed exact CI, but
live screenshot run `30426689154` reached current headers and then received
`WebKitErrorDomain` code 102 (`Frame load interrupted`) from the superseded
provisional homepage; it produced no screenshot artifact. Build `56` keeps
admission suspended until exact matching-network readiness, retains bounded
fresh-context recovery, and suppresses code 102 only for an older navigation
that a new tracked navigation replaced. Current-load failures remain visible.
A rerun after Apple accepts build `56` requires another higher build number.

Build `56` declares `ITSAppUsesNonExemptEncryption = false` because the
candidate uses only industry-standard cryptography and excludes France from
App Store availability. Do not add an export-compliance code to this build.
Before enabling France, complete the French encryption declaration; after
Apple approves it, add the supplied export-compliance code to the next build.

## Release gate after upload

Complete the metadata in `dist/app-store/metadata/en-US`, publish the revised
privacy policy, generate and review current iPhone screenshots using
`docs/ios-app-store-screenshots.md`, answer App
Privacy/age-rating/content-rights/export-compliance questions, attach the build
to the App Store version, and submit it for App Review. The upload workflow does
not create TestFlight groups or distribute the build to testers.

Owning an iPhone is not required to archive, sign, upload, or submit. An
independently installed signed build may be exercised on a real iPhone in a
future qualification cycle, but no TestFlight distribution is part of this
release.
That absence does not block App Store submission, though installed-iOS and
ecosystem qualification remain open; record the matrix from
`docs/ios-device-validation.md` when completed. MacInCloud is only a fallback if
an account-specific problem cannot be resolved through the developer portals
and GitHub Actions logs.
