# iOS App Store screenshots

The `Live iOS App Store Screenshots` workflow captures the shipping app in Release
for iPhone and iPad. Dispatch it with a full lowercase source commit:

```sh
expected_commit="$(git rev-parse HEAD)"
gh workflow run ios-screenshots.yml \
  --repo handshake-rs/hns-dane-browser-mobile \
  --ref main \
  -f expected_commit="$expected_commit" \
  -f reason='App Store candidate screenshots'
```

The current interface set contains Browser, Settings, Handshake settings, and
Wallet onboarding images. Capture runs the app's actual interface without
injecting HTML, fixtures, or a security verdict. Interface screenshots do not
claim a completed HNS navigation. The separate navigation capture validator
requires current headers and actual DANE or validated ICANN outcomes for any
images making those claims.

Each device family has a `manifest.json` binding the exact commit, Release
configuration, simulator and SDK provenance, dimensions, and image digests.
The iPhone images use 1284 by 2778 pixels; iPad images use the accepted sizes
checked by `scripts/ios_screenshot_tools.py`. Both families must identify the
same signing candidate.

Review the images, then validate and stage them:

```sh
./scripts/stage-ios-app-store-screenshots.sh \
  build/app-store-live-screenshots "$expected_commit"
python3 store-assets/app-store/validate.py --expected-commit "$expected_commit"
```

The protected upload workflow's `capture_screenshots` input controls capture.
When enabled, capture and validation must pass before signing credentials are
used. Submission replaces screenshots only through the guarded metadata client
with the explicit replacement token and the successful capture run ID.
