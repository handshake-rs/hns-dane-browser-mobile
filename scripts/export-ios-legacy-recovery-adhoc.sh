#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bundle_id="com.denuoweb.hnsdane.ios.legacyrecovery"
team_id="${HNS_IOS_TEAM_ID:-45NQQK3G3S}"
profile_path="${HNS_IOS_LEGACY_ADHOC_PROFILE_PATH:-}"
expected_udid="${HNS_IOS_LEGACY_EXPECTED_UDID:-}"
p12_path="${HNS_IOS_DISTRIBUTION_P12_PATH:-}"
p12_password="${HNS_IOS_DISTRIBUTION_P12_PASSWORD:-}"
output_dir="$root_dir/build/ios-legacy-recovery"
output_ipa="$output_dir/Shakescape-1.0.7-legacy-recovery.ipa"

fail() { echo "ERROR: $*" >&2; exit 1; }

[[ "$(uname -s)" == Darwin ]] || fail "Ad Hoc export requires macOS and Xcode."
[[ "$team_id" =~ ^[A-Z0-9]{10}$ ]] || fail "invalid Apple Team ID."
[[ -n "$expected_udid" ]] || fail "HNS_IOS_LEGACY_EXPECTED_UDID is required."
[[ -s "$profile_path" ]] || fail "a matching Ad Hoc provisioning profile is required."
[[ -s "$p12_path" && -n "$p12_password" ]] ||
  fail "the Apple Distribution .p12 and password are required."
[[ ! -e "$output_ipa" ]] || fail "refusing to replace an existing recovery IPA."
for command in codesign openssl python3 security xcodebuild; do
  command -v "$command" >/dev/null 2>&1 || fail "missing tool: $command"
done

mkdir -p "$output_dir"
scratch_dir="$(mktemp -d "$output_dir/signing.XXXXXXXX")"
keychain_path="$scratch_dir/recovery-signing.keychain-db"
installed_profile_path=""
keychain_created=false
keychain_search_list_modified=false
original_keychains=()
cleanup() {
  if [[ "$keychain_search_list_modified" == true ]]; then
    security list-keychains -d user -s "${original_keychains[@]}" >/dev/null 2>&1 || true
  fi
  if [[ "$keychain_created" == true ]]; then
    security delete-keychain "$keychain_path" >/dev/null 2>&1 || true
  fi
  if [[ -n "$installed_profile_path" ]]; then
    rm -f -- "$installed_profile_path"
  fi
  rm -rf -- "$scratch_dir"
}
trap cleanup EXIT

security cms -D -i "$profile_path" >"$scratch_dir/profile.plist"
openssl pkcs12 -in "$p12_path" -passin env:HNS_IOS_DISTRIBUTION_P12_PASSWORD \
  -clcerts -nokeys 2>/dev/null |
  openssl x509 -outform DER -out "$scratch_dir/distribution-cert.der" ||
  fail "unable to read the Apple Distribution certificate from the .p12."

profile_uuid="$(python3 - "$scratch_dir/profile.plist" \
  "$scratch_dir/distribution-cert.der" "$team_id" "$bundle_id" "$expected_udid" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import plistlib
import sys
import uuid

profile = plistlib.loads(Path(sys.argv[1]).read_bytes())
certificate = Path(sys.argv[2]).read_bytes()
team_id, bundle_id, expected_udid = sys.argv[3:]
entitlements = profile.get("Entitlements", {})
expiry = profile.get("ExpirationDate")
if profile.get("TeamIdentifier") != [team_id]:
    raise SystemExit("Ad Hoc profile belongs to another Apple team")
if entitlements.get("application-identifier") != f"{team_id}.{bundle_id}":
    raise SystemExit("Ad Hoc profile does not match the recovery bundle ID")
if entitlements.get("get-task-allow") is not False:
    raise SystemExit("Ad Hoc profile permits debugging")
if profile.get("ProvisionsAllDevices") is True:
    raise SystemExit("enterprise profile is not an Ad Hoc profile")
devices = profile.get("ProvisionedDevices")
if not isinstance(devices, list) or expected_udid not in devices:
    raise SystemExit("intended iPhone is not included in the Ad Hoc profile")
if certificate not in profile.get("DeveloperCertificates", []):
    raise SystemExit("distribution certificate is absent from the Ad Hoc profile")
if not isinstance(expiry, datetime):
    raise SystemExit("Ad Hoc profile has no expiration date")
if expiry.replace(tzinfo=expiry.tzinfo or timezone.utc) <= datetime.now(timezone.utc):
    raise SystemExit("Ad Hoc profile has expired")
profile_uuid = profile.get("UUID", "")
try:
    uuid.UUID(profile_uuid)
except (ValueError, TypeError):
    raise SystemExit("Ad Hoc profile has an invalid UUID")
print(profile_uuid)
PY
)" || fail "Ad Hoc profile validation failed."

profile_install_dir="${HOME}/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$profile_install_dir"
profile_install_target="$profile_install_dir/$profile_uuid.mobileprovision"
[[ ! -e "$profile_install_target" ]] ||
  fail "a profile with this UUID is already installed; refusing to overwrite it."
cp "$profile_path" "$profile_install_target"
installed_profile_path="$profile_install_target"

while IFS= read -r existing_keychain; do
  [[ -n "$existing_keychain" ]] && original_keychains+=("$existing_keychain")
done < <(security list-keychains -d user | sed -e 's/^[[:space:]]*"//' -e 's/"[[:space:]]*$//')
keychain_password="$(openssl rand -hex 32)"
security create-keychain -p "$keychain_password" "$keychain_path"
keychain_created=true
security set-keychain-settings -lut 21600 "$keychain_path"
security unlock-keychain -p "$keychain_password" "$keychain_path"
security import "$p12_path" -k "$keychain_path" -P "$p12_password" \
  -T /usr/bin/codesign -T /usr/bin/security >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: \
  -s -k "$keychain_password" "$keychain_path" >/dev/null
keychain_search_list_modified=true
security list-keychains -d user -s "$keychain_path" "${original_keychains[@]}"
security find-identity -v -p codesigning "$keychain_path" |
  grep -Fq "Apple Distribution:" || fail "distribution signing identity is unavailable."
unset p12_password keychain_password

"$root_dir/scripts/build-rust-ios.sh"
prepared_plist="$scratch_dir/LegacyRecovery-Info.plist"
python3 "$root_dir/scripts/prepare-ios-legacy-recovery-plist.py" \
  "$root_dir/ios/HnsDaneBrowser/Support/Info.plist" "$prepared_plist"

archive_path="$scratch_dir/ShakescapeLegacyRecovery.xcarchive"
xcodebuild \
  -project "$root_dir/ios/HnsDaneBrowser.xcodeproj" \
  -scheme HnsDaneBrowser \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$archive_path" \
  "PRODUCT_BUNDLE_IDENTIFIER=$bundle_id" \
  "INFOPLIST_FILE=$prepared_plist" \
  "DEVELOPMENT_TEAM=$team_id" \
  CODE_SIGN_STYLE=Manual \
  'CODE_SIGN_IDENTITY=Apple Distribution' \
  "PROVISIONING_PROFILE_SPECIFIER=$profile_uuid" \
  archive

python3 - "$scratch_dir/ExportOptions.plist" "$bundle_id" "$profile_uuid" "$team_id" <<'PY'
from pathlib import Path
import plistlib
import sys

options = {
    "destination": "export",
    "manageAppVersionAndBuildNumber": False,
    "method": "release-testing",
    "provisioningProfiles": {sys.argv[2]: sys.argv[3]},
    "signingCertificate": "Apple Distribution",
    "signingStyle": "manual",
    "stripSwiftSymbols": True,
    "teamID": sys.argv[4],
}
Path(sys.argv[1]).write_bytes(plistlib.dumps(options))
PY
xcodebuild -exportArchive \
  -archivePath "$archive_path" \
  -exportPath "$scratch_dir/export" \
  -exportOptionsPlist "$scratch_dir/ExportOptions.plist"

shopt -s nullglob
exported_ipas=("$scratch_dir"/export/*.ipa)
shopt -u nullglob
[[ "${#exported_ipas[@]}" -eq 1 ]] || fail "expected exactly one exported IPA."
python3 - "${exported_ipas[0]}" "$bundle_id" <<'PY'
from pathlib import Path
import plistlib
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    app_plists = [name for name in archive.namelist()
                  if name.startswith("Payload/") and name.endswith(".app/Info.plist")]
    if len(app_plists) != 1:
        raise SystemExit("exported IPA does not contain exactly one app")
    info = plistlib.loads(archive.read(app_plists[0]))
    if info.get("CFBundleIdentifier") != sys.argv[2]:
        raise SystemExit("exported IPA has the wrong bundle ID")
    if info.get("CFBundleDisplayName") != "Shakescape Legacy Recovery":
        raise SystemExit("exported IPA has the wrong display name")
    if info.get("CFBundleURLTypes"):
        raise SystemExit("exported IPA still claims browser or payment URL schemes")
    if info.get("HNSLegacyRecoveryBuild") is not True:
        raise SystemExit("exported IPA is missing its recovery marker")
PY
cp "${exported_ipas[0]}" "$output_ipa"
shasum -a 256 "$output_ipa"
echo "Signed, device-scoped recovery IPA: $output_ipa"
