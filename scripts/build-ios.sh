#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIGURATION="${HNS_IOS_CONFIGURATION:-Debug}"
DESTINATION="${HNS_IOS_DESTINATION:-generic/platform=iOS Simulator}"
ACTION="${HNS_IOS_ACTION:-build-for-testing}"
REUSE_XCFRAMEWORK="${HNS_IOS_REUSE_XCFRAMEWORK:-0}"
FRAMEWORK_PATH="$ROOT_DIR/build/apple/HnsBrowserRuntime.xcframework"
RESULT_BUNDLE_INPUT="${HNS_IOS_RESULT_BUNDLE_PATH:-}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: the iOS application requires macOS and Xcode." >&2
  exit 2
fi

case "$ACTION" in
  build|build-for-testing|test) ;;
  *)
    echo "ERROR: HNS_IOS_ACTION must be build, build-for-testing, or test." >&2
    exit 2
    ;;
esac

case "$REUSE_XCFRAMEWORK" in
  0|1) ;;
  *)
    echo "ERROR: HNS_IOS_REUSE_XCFRAMEWORK must be 0 or 1." >&2
    exit 2
    ;;
esac

if [[ "$ACTION" == "test" && "$DESTINATION" == generic/* ]]; then
  echo "ERROR: HNS_IOS_ACTION=test requires a concrete simulator destination." >&2
  exit 2
fi

xcodebuild_args=(
  -project "$ROOT_DIR/ios/HnsDaneBrowser.xcodeproj"
  -scheme HnsDaneBrowser
  -configuration "$CONFIGURATION"
  -destination "$DESTINATION"
)

if [[ -n "$RESULT_BUNDLE_INPUT" ]]; then
  command -v python3 >/dev/null 2>&1 || {
    echo "ERROR: python3 is required to validate HNS_IOS_RESULT_BUNDLE_PATH." >&2
    exit 2
  }
  if [[ "$RESULT_BUNDLE_INPUT" == /* ]]; then
    result_bundle_requested="$RESULT_BUNDLE_INPUT"
  else
    result_bundle_requested="$ROOT_DIR/$RESULT_BUNDLE_INPUT"
  fi
  if [[ -e "$result_bundle_requested" || -L "$result_bundle_requested" ]]; then
    echo "ERROR: HNS_IOS_RESULT_BUNDLE_PATH must not overwrite an existing requested path: $result_bundle_requested" >&2
    exit 2
  fi
  mkdir -p -- "$ROOT_DIR/build"
  result_bundle_path="$(
    python3 - "$ROOT_DIR" "$RESULT_BUNDLE_INPUT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve(strict=False)
build_root = (root / "build").resolve(strict=False)
requested = Path(sys.argv[2])
if not requested.is_absolute():
    requested = root / requested
candidate = requested.resolve(strict=False)
try:
    candidate.relative_to(build_root)
except ValueError:
    raise SystemExit(
        "HNS_IOS_RESULT_BUNDLE_PATH must resolve beneath the repository build directory"
    )
if candidate.suffix != ".xcresult":
    raise SystemExit("HNS_IOS_RESULT_BUNDLE_PATH must end in .xcresult")
print(candidate)
PY
  )"
  if [[ -e "$result_bundle_path" || -L "$result_bundle_path" ]]; then
    echo "ERROR: HNS_IOS_RESULT_BUNDLE_PATH must not overwrite an existing path: $result_bundle_path" >&2
    exit 2
  fi
  mkdir -p -- "$(dirname "$result_bundle_path")"
  xcodebuild_args+=( -resultBundlePath "$result_bundle_path" )
fi

if [[ "$REUSE_XCFRAMEWORK" == "1" ]]; then
  if [[ ! -s "$FRAMEWORK_PATH/Info.plist" ]]; then
    echo "ERROR: the existing XCFramework is unavailable: $FRAMEWORK_PATH" >&2
    exit 1
  fi
else
  "$ROOT_DIR/scripts/build-rust-ios.sh"
fi

xcodebuild_args+=( CODE_SIGNING_ALLOWED=NO "$ACTION" )
xcodebuild "${xcodebuild_args[@]}"
