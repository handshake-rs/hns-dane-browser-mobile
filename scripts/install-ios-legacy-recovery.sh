#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
team_id="${HNS_IOS_TEAM_ID:-}"
bundle_id="${HNS_IOS_LEGACY_BUNDLE_ID:-}"
device_id="${HNS_IOS_DEVICE_ID:-}"
output_dir="$root_dir/build/ios-legacy-recovery/self-signed"
prepared_plist="$output_dir/LegacyRecovery-Info.plist"
app_path="$output_dir/derived/Build/Products/Release-iphoneos/HnsDaneBrowser.app"

fail() { echo "ERROR: $*" >&2; exit 2; }

[[ "$(uname -s)" == Darwin ]] || fail "installation requires a Mac with Xcode."
[[ "$team_id" =~ ^[A-Z0-9]{10}$ ]] || fail "set HNS_IOS_TEAM_ID to your own 10-character Apple team ID."
[[ "$bundle_id" =~ ^[A-Za-z][A-Za-z0-9-]*(\.[A-Za-z][A-Za-z0-9-]*)+\.legacyrecovery$ ]] ||
  fail "set HNS_IOS_LEGACY_BUNDLE_ID to a unique reverse-DNS ID ending in .legacyrecovery."
[[ "$bundle_id" != com.denuoweb.hnsdane.ios.legacyrecovery ]] ||
  fail "use your own bundle ID, not the maintainer's registered recovery App ID."
[[ "$device_id" =~ ^[A-Za-z0-9-]{8,64}$ ]] ||
  fail "set HNS_IOS_DEVICE_ID to the identifier of your connected iPhone."
for command in codesign python3 rustup xcodebuild xcrun; do
  command -v "$command" >/dev/null 2>&1 || fail "missing tool: $command"
done
[[ ! -e "$app_path" ]] || fail "refusing to overwrite an existing self-signed recovery app; move the previous output first."

if [[ "${HNS_IOS_REUSE_XCFRAMEWORK:-0}" == 1 ]]; then
  [[ -s "$root_dir/build/apple/HnsBrowserRuntime.xcframework/Info.plist" ]] ||
    fail "the previously built Rust XCFramework is unavailable."
else
  "$root_dir/scripts/build-rust-ios.sh"
fi

python3 "$root_dir/scripts/prepare-ios-legacy-recovery-plist.py" \
  "$root_dir/ios/HnsDaneBrowser/Support/Info.plist" "$prepared_plist"

xcodebuild \
  -project "$root_dir/ios/HnsDaneBrowser.xcodeproj" \
  -scheme HnsDaneBrowser \
  -configuration Release \
  -destination "platform=iOS,id=$device_id" \
  -derivedDataPath "$output_dir/derived" \
  -allowProvisioningUpdates \
  -allowProvisioningDeviceRegistration \
  "PRODUCT_BUNDLE_IDENTIFIER=$bundle_id" \
  "INFOPLIST_FILE=$prepared_plist" \
  "DEVELOPMENT_TEAM=$team_id" \
  CODE_SIGN_STYLE=Automatic \
  'CODE_SIGN_IDENTITY=Apple Development' \
  build

[[ -s "$app_path/Info.plist" ]] || fail "signed recovery app was not produced."
python3 - "$app_path/Info.plist" "$bundle_id" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as handle:
    info = plistlib.load(handle)
if info.get("CFBundleIdentifier") != sys.argv[2]:
    raise SystemExit("signed recovery app has the wrong bundle ID")
if info.get("CFBundleDisplayName") != "Shakescape Legacy Recovery":
    raise SystemExit("signed recovery app has the wrong display name")
if info.get("HNSLegacyRecoveryBuild") is not True:
    raise SystemExit("signed recovery app is missing its recovery marker")
if info.get("CFBundleURLTypes"):
    raise SystemExit("signed recovery app claims browser or payment URL schemes")
PY
codesign --verify --strict --verbose=2 "$app_path"
xcrun devicectl device install app --device "$device_id" "$app_path"
echo "Installed the separately signed legacy recovery app on your device."
