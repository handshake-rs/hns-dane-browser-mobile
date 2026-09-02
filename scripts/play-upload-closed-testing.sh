#!/usr/bin/env bash
set -euo pipefail
umask 077

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_gradle="$root_dir/android/app/build.gradle.kts"

if [[ $# -gt 1 ]]; then
  echo "usage: $0 [signed-release.aab]" >&2
  exit 2
fi

package_name="${PLAY_PACKAGE:-com.denuoweb.hnsdane}"
track_name="${PLAY_TRACK:-}"
release_status="${PLAY_RELEASE_STATUS:-draft}"
aab_path="${1:-dist/play-store/hns-dane-browser-v1.0.3-play-upload-signed.aab}"
release_name="${PLAY_RELEASE_NAME:-Shakescape 1.0.3}"
release_notes="${PLAY_RELEASE_NOTES:-1.0.3 fixes authenticated CDN alias resolution, lets Android offer Shakescape as a default browser, and retains guarded send and synchronization protections for the native HNS wallet.}"
update_listing="${PLAY_UPDATE_LISTING:-false}"
listing_language="${PLAY_LISTING_LANGUAGE:-en-US}"

configured_version_code="$(
  sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\).*/\1/p' "$android_gradle"
)"
if [[ -z "$configured_version_code" || "$configured_version_code" == *$'\n'* ]]; then
  echo "Could not read one configured Android versionCode from $android_gradle." >&2
  exit 2
fi

valid_version_code() {
  local value="$1"
  [[ "$value" =~ ^[1-9][0-9]{0,9}$ ]] && (( 10#$value <= 2100000000 ))
}

if ! valid_version_code "$configured_version_code"; then
  echo "Configured Android versionCode is invalid: $configured_version_code" >&2
  exit 2
fi
if [[ ${PLAY_EXPECTED_VERSION_CODE+x} == x ]]; then
  expected_version_code="$PLAY_EXPECTED_VERSION_CODE"
  expected_version_source="PLAY_EXPECTED_VERSION_CODE override"
else
  expected_version_code="$configured_version_code"
  expected_version_source="android/app/build.gradle.kts"
fi
if ! valid_version_code "$expected_version_code"; then
  echo "Expected Play versionCode is invalid: $expected_version_code" >&2
  exit 2
fi
echo "Expected Play bundle versionCode: ${expected_version_code} (${expected_version_source})"

if [[ ! "$package_name" =~ ^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$ ]]; then
  echo "Invalid Play package name: $package_name" >&2
  exit 2
fi
if [[ -z "$track_name" ]]; then
  echo "Set PLAY_TRACK explicitly (for example, alpha or production)." >&2
  exit 2
fi
if [[ ! "$track_name" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Invalid Play track name: $track_name" >&2
  exit 2
fi
case "$release_status" in
  draft|halted|completed) ;;
  *)
    echo "PLAY_RELEASE_STATUS must be draft, halted, or completed." >&2
    exit 2
    ;;
esac
case "$update_listing" in
  true|false) ;;
  *)
    echo "PLAY_UPDATE_LISTING must be true or false." >&2
    exit 2
    ;;
esac
if [[ ! "$listing_language" =~ ^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$ ]]; then
  echo "Invalid Play listing language: $listing_language" >&2
  exit 2
fi

if [[ ! -f "$aab_path" ]]; then
  echo "AAB not found: $aab_path" >&2
  exit 1
fi

if [[ -n "${PLAY_ACCESS_TOKEN:-}" ]]; then
  access_token="$PLAY_ACCESS_TOKEN"
elif command -v gcloud >/dev/null 2>&1; then
  access_token="$(gcloud auth application-default print-access-token \
    --scopes=https://www.googleapis.com/auth/androidpublisher)"
else
  echo "Set PLAY_ACCESS_TOKEN or install/login with gcloud." >&2
  exit 1
fi
if [[ "$access_token" == *$'\r'* || "$access_token" == *$'\n'* ]]; then
  echo "Play access token contains an invalid line break." >&2
  exit 1
fi

api_base="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${package_name}"
upload_base="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${package_name}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
auth_header="$tmpdir/authorization.txt"
printf 'Authorization: Bearer %s\n' "$access_token" >"$auth_header"
unset access_token PLAY_ACCESS_TOKEN

json_get() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

path, key = sys.argv[1:3]
with open(path, "r", encoding="utf-8") as handle:
    data = json.load(handle)
value = data
for part in key.split("."):
    if isinstance(value, list):
        value = value[int(part)]
    else:
        value = value[part]
print(value)
PY
}

show_error() {
  python3 - "$1" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
except Exception:
    print(open(path, "r", encoding="utf-8", errors="replace").read()[:4000])
else:
    print(json.dumps(data.get("error", data), indent=2)[:4000])
PY
}

request() {
  local method="$1"
  local url="$2"
  local output="$3"
  shift 3
  local http
  http="$(curl --proto '=https' --tlsv1.2 --connect-timeout 30 --max-time 600 \
    -sS -o "$output" -w '%{http_code}' -X "$method" \
    -H "@$auth_header" \
    "$@" \
    "$url")"
  if [[ "$http" -lt 200 || "$http" -gt 299 ]]; then
    echo "Request failed (${http}): ${method} ${url}" >&2
    show_error "$output" >&2
    exit 1
  fi
}

edit_json="$tmpdir/edit.json"
request POST "${api_base}/edits" "$edit_json" -H "Content-Type: application/json"
edit_id="$(json_get "$edit_json" id)"
echo "Created Play edit: ${edit_id}"

if [[ "$update_listing" == true ]]; then
  listing_dir="$root_dir/store-assets/play-store/metadata/$listing_language"
  listing_body="$tmpdir/listing-body.json"
  python3 - "$listing_body" "$listing_language" \
    "$listing_dir/title.txt" \
    "$listing_dir/short-description.txt" \
    "$listing_dir/full-description.txt" <<'PY'
import json
from pathlib import Path
import sys

output, language, title_path, short_path, full_path = sys.argv[1:6]

def field(path: str, name: str, maximum: int) -> str:
    value = Path(path).read_text(encoding="utf-8").strip()
    if not value or "\x00" in value or len(value) > maximum:
        raise SystemExit(
            f"Invalid Play {name}: expected 1..{maximum} Unicode characters"
        )
    return value

document = {
    "language": language,
    "title": field(title_path, "title", 30),
    "shortDescription": field(short_path, "short description", 80),
    "fullDescription": field(full_path, "full description", 4000),
}
Path(output).write_text(json.dumps(document), encoding="utf-8")
PY
  listing_json="$tmpdir/listing.json"
  request PUT \
    "${api_base}/edits/${edit_id}/listings/${listing_language}" \
    "$listing_json" \
    -H "Content-Type: application/json" \
    --data-binary "@${listing_body}"
  echo "Updated Play listing text: ${listing_language}"
fi

bundle_json="$tmpdir/bundle.json"
request POST "${upload_base}/edits/${edit_id}/bundles?uploadType=media" "$bundle_json" \
  -H "Content-Type: application/octet-stream" \
  --data-binary "@${aab_path}"
version_code="$(json_get "$bundle_json" versionCode)"
echo "Uploaded bundle versionCode: ${version_code}"
if ! valid_version_code "$version_code"; then
  echo "Play returned an invalid bundle versionCode: $version_code" >&2
  exit 1
fi
if [[ "$version_code" != "$expected_version_code" ]]; then
  echo "Refusing to assign or commit bundle versionCode ${version_code}; expected ${expected_version_code}." >&2
  exit 1
fi
echo "Verified uploaded bundle versionCode ${version_code}."

track_body="$tmpdir/track-body.json"
python3 - "$track_body" "$version_code" "$release_status" "$release_name" "$release_notes" <<'PY'
import json
import sys

path, version_code, status, name, notes = sys.argv[1:6]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "releases": [{
            "versionCodes": [version_code],
            "status": status,
            "name": name,
            "releaseNotes": [{
                "language": "en-US",
                "text": notes,
            }],
        }],
    }, handle)
PY

track_json="$tmpdir/track.json"
request PUT "${api_base}/edits/${edit_id}/tracks/${track_name}" "$track_json" \
  -H "Content-Type: application/json" \
  --data-binary "@${track_body}"
echo "Assigned versionCode ${version_code} to Play track: ${track_name}"

commit_json="$tmpdir/commit.json"
request POST \
  "${api_base}/edits/${edit_id}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" \
  "$commit_json" \
  -H "Content-Type: application/json"
echo "Committed Play edit ${edit_id}."
