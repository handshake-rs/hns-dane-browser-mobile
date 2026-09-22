#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_REQUESTED="${1:-$ROOT_DIR/build/app-store-live-screenshots}"
EXPECTED_COMMIT="${2:-$(git -C "$ROOT_DIR" rev-parse HEAD)}"
SCREENSHOT_ROOT="$ROOT_DIR/store-assets/app-store/screenshots"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "$SOURCE_REQUESTED" != /* ]]; then
  SOURCE_REQUESTED="$ROOT_DIR/$SOURCE_REQUESTED"
fi
[[ -d "$SOURCE_REQUESTED" ]] || fail "live screenshot directory is missing: $SOURCE_REQUESTED"
SOURCE="$(cd "$SOURCE_REQUESTED" && pwd -P)"
case "$SOURCE" in
  "$ROOT_DIR"/build/*) ;;
  *) fail "source must remain below $ROOT_DIR/build" ;;
esac
[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]] ||
  fail "expected commit must be one lowercase 40-character Git SHA"

for family in iphone ipad; do
  python3 "$ROOT_DIR/scripts/ios_screenshot_tools.py" verify-live \
    --directory "$SOURCE/$family" \
    --expected-commit "$EXPECTED_COMMIT"
done

STAGING="$SCREENSHOT_ROOT/.live-stage.$$"
cleanup() {
  rm -rf -- "$STAGING"
}
trap cleanup EXIT INT TERM
for family in iphone ipad; do
  mkdir -p -- "$STAGING/$family/en-US"
  cp -- "$SOURCE/$family"/*.jpg "$STAGING/$family/en-US/"
  cp -- "$SOURCE/$family/manifest.json" "$STAGING/$family/manifest.json"
done

# This replacement is intentionally gated by verify-live: fixture images and
# hand-renamed files cannot reach the distribution directory through this path.
for family in iphone ipad; do
  rm -rf -- "$SCREENSHOT_ROOT/$family"
  mv -- "$STAGING/$family" "$SCREENSHOT_ROOT/$family"
done
rm -rf -- "$STAGING"
trap - EXIT INT TERM

printf 'Staged verified live App Store screenshots and provenance in %s\n' "$SCREENSHOT_ROOT"
