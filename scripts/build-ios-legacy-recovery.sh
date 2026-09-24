#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_id="com.denuoweb.hnsdane.ios.legacyrecovery"
output_dir="$root_dir/build/ios-legacy-recovery"
framework_path="$root_dir/build/apple/HnsBrowserRuntime.xcframework"
prepared_plist="$output_dir/LegacyRecovery-Info.plist"
reuse_framework="${HNS_IOS_REUSE_XCFRAMEWORK:-0}"

[[ "$(uname -s)" == Darwin ]] || {
  echo "ERROR: building an iOS application requires macOS and Xcode." >&2
  exit 2
}
[[ "$reuse_framework" == 0 || "$reuse_framework" == 1 ]] || {
  echo "ERROR: HNS_IOS_REUSE_XCFRAMEWORK must be 0 or 1." >&2
  exit 2
}
for command in python3 xcodebuild; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "ERROR: required tool is unavailable: $command" >&2
    exit 2
  }
done

if [[ "$reuse_framework" == 0 ]]; then
  "$root_dir/scripts/build-rust-ios.sh"
elif [[ ! -s "$framework_path/Info.plist" ]]; then
  echo "ERROR: the previously built Rust XCFramework is unavailable." >&2
  exit 2
fi

python3 "$root_dir/scripts/prepare-ios-legacy-recovery-plist.py" \
  "$root_dir/ios/HnsDaneBrowser/Support/Info.plist" "$prepared_plist"

xcodebuild \
  -project "$root_dir/ios/HnsDaneBrowser.xcodeproj" \
  -scheme HnsDaneBrowser \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$output_dir/derived" \
  "PRODUCT_BUNDLE_IDENTIFIER=$bundle_id" \
  "INFOPLIST_FILE=$prepared_plist" \
  CODE_SIGNING_ALLOWED=NO \
  build

app_plist="$output_dir/derived/Build/Products/Release-iphoneos/HnsDaneBrowser.app/Info.plist"
python3 - "$app_plist" "$bundle_id" <<'PY'
from pathlib import Path
import plistlib
import sys

path = Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(f"unsigned recovery app Info.plist is missing: {path}")
with path.open("rb") as handle:
    info = plistlib.load(handle)
if info.get("CFBundleIdentifier") != sys.argv[2]:
    raise SystemExit("unsigned recovery app has the wrong bundle ID")
if info.get("CFBundleDisplayName") != "Shakescape Legacy Recovery":
    raise SystemExit("unsigned recovery app has the wrong display name")
if info.get("HNSLegacyRecoveryBuild") is not True:
    raise SystemExit("unsigned recovery app is missing its recovery marker")
if info.get("CFBundleURLTypes"):
    raise SystemExit("unsigned recovery app still registers browser or payment URL schemes")
print(f"Unsigned iOS legacy recovery app verified: {path}")
PY
