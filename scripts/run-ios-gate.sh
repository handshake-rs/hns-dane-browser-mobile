#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_TOOLCHAIN="1.92.0"
IOS_SDK_VERSION="26.5"
APPLE_TARGETS=(
  aarch64-apple-ios
  aarch64-apple-ios-sim
  x86_64-apple-ios
)
IOS_GATE_DIAGNOSTICS_DIR="$ROOT_DIR/build/ios-gate-diagnostics"
IOS_GATE_RESULT_BUNDLE="$IOS_GATE_DIAGNOSTICS_DIR/HnsDaneBrowserTests.xcresult"
IOS_GATE_PHASE_FILE="$IOS_GATE_DIAGNOSTICS_DIR/phase.txt"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

prepare_ios_gate_diagnostics() {
  local build_dir="$ROOT_DIR/build"
  local expected_dir="$build_dir/ios-gate-diagnostics"
  [[ "$IOS_GATE_DIAGNOSTICS_DIR" == "$expected_dir" ]] ||
    fail "refusing to recreate an unexpected iOS gate diagnostics path."
  [[ ! -L "$build_dir" ]] ||
    fail "the repository build directory must not be a symbolic link."
  mkdir -p -- "$build_dir"
  if [[ -e "$IOS_GATE_DIAGNOSTICS_DIR" || -L "$IOS_GATE_DIAGNOSTICS_DIR" ]]; then
    [[ -d "$IOS_GATE_DIAGNOSTICS_DIR" && ! -L "$IOS_GATE_DIAGNOSTICS_DIR" ]] ||
      fail "the iOS gate diagnostics path must be a real directory."
    rm -rf -- "$IOS_GATE_DIAGNOSTICS_DIR"
  fi
  mkdir -- "$IOS_GATE_DIAGNOSTICS_DIR"
}

record_ios_gate_phase() {
  printf 'phase=%s\n' "$1" >"$IOS_GATE_PHASE_FILE"
}

if [[ "$(uname -s)" != "Darwin" ]]; then
  fail "the complete iOS gate requires macOS and Xcode."
fi

prepare_ios_gate_diagnostics
record_ios_gate_phase preflight
# The gate owns one stable result location. Do not allow an ambient value to
# leak into either build-ios.sh invocation.
unset HNS_IOS_RESULT_BUNDLE_PATH

for command in python3 rustup xcode-select xcodebuild xcrun; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is unavailable: $command"
done

export CARGO_INCREMENTAL=0

if [[ -n "${HNS_XCODE_DEVELOPER_DIR:-}" ]]; then
  xcode_candidates=("$HNS_XCODE_DEVELOPER_DIR")
else
  xcode_candidates=("$(xcode-select --print-path)")
  shopt -s nullglob
  for xcode_app in \
    /Applications/Xcode_26.6.app \
    /Applications/Xcode_26.6*.app \
    /Applications/Xcode_26.5.app \
    /Applications/Xcode_26.5*.app; do
    xcode_candidates+=("$xcode_app/Contents/Developer")
  done
  shopt -u nullglob
fi

developer_dir=""
for candidate in "${xcode_candidates[@]}"; do
  [[ -x "$candidate/usr/bin/xcodebuild" ]] || continue
  candidate_version="$(
    DEVELOPER_DIR="$candidate" xcodebuild -version | sed -n '1s/^Xcode //p'
  )"
  case "$candidate_version" in
    26.5|26.5.*|26.6|26.6.*) ;;
    *) continue ;;
  esac
  candidate_iphoneos_sdk="$(
    DEVELOPER_DIR="$candidate" xcrun --sdk iphoneos --show-sdk-version
  )"
  candidate_simulator_sdk="$(
    DEVELOPER_DIR="$candidate" xcrun --sdk iphonesimulator --show-sdk-version
  )"
  if [[ "$candidate_iphoneos_sdk" == "$IOS_SDK_VERSION" ]] &&
    [[ "$candidate_simulator_sdk" == "$IOS_SDK_VERSION" ]]; then
    developer_dir="$candidate"
    break
  fi
done

[[ -n "$developer_dir" ]] ||
  fail "no installed Xcode 26.5/26.6 provides both iOS $IOS_SDK_VERSION SDKs."
export DEVELOPER_DIR="$developer_dir"

xcodebuild -version
iphoneos_sdk="$(xcrun --sdk iphoneos --show-sdk-version)"
simulator_sdk="$(xcrun --sdk iphonesimulator --show-sdk-version)"
printf 'DEVELOPER_DIR=%s\niphoneos SDK %s\niphonesimulator SDK %s\n' \
  "$DEVELOPER_DIR" "$iphoneos_sdk" "$simulator_sdk"
[[ "$iphoneos_sdk" == "$IOS_SDK_VERSION" ]] ||
  fail "iphoneos SDK $IOS_SDK_VERSION is required; selected $iphoneos_sdk."
[[ "$simulator_sdk" == "$IOS_SDK_VERSION" ]] ||
  fail "iphonesimulator SDK $IOS_SDK_VERSION is required; selected $simulator_sdk."

rustup toolchain install "$RUST_TOOLCHAIN" \
  --profile minimal \
  --component rustfmt --component clippy
rustup target add --toolchain "$RUST_TOOLCHAIN" "${APPLE_TARGETS[@]}"

cd "$ROOT_DIR"
./scripts/check-version-consistency.sh
python3 ./store-assets/app-store/validate.py --metadata-only
./scripts/check-runtime-boundaries.sh
python3 ./scripts/test_select_ios_simulator.py

record_ios_gate_phase rust-abi
abi_target_dir="$(mktemp -d "${TMPDIR:-/tmp}/hns-ios-abi.XXXXXX")"
cleanup() {
  rm -rf -- "$abi_target_dir"
}
trap cleanup EXIT
HNS_IOS_ABI_TARGET_DIR="$abi_target_dir" ./scripts/check-ios-abi.sh
cleanup
trap - EXIT

record_ios_gate_phase simulator-selection
simulator_id="$(
  xcrun simctl list devices available -j |
    python3 ./scripts/select_ios_simulator.py --runtime "$IOS_SDK_VERSION"
)"
[[ -n "$simulator_id" ]] || fail "the iOS simulator selector returned no device."
printf 'Selected iOS %s simulator: %s\n' "$IOS_SDK_VERSION" "$simulator_id"
xcrun simctl boot "$simulator_id" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$simulator_id" -b

record_ios_gate_phase simulator-test
HNS_RUST_IOS_CLEAN_TARGET=1 \
  HNS_IOS_ACTION=test \
  HNS_IOS_DESTINATION="platform=iOS Simulator,id=$simulator_id" \
  HNS_IOS_RESULT_BUNDLE_PATH="$IOS_GATE_RESULT_BUNDLE" \
  ./scripts/build-ios.sh

record_ios_gate_phase unsigned-device-link
HNS_IOS_REUSE_XCFRAMEWORK=1 \
  HNS_IOS_ACTION=build \
  HNS_IOS_CONFIGURATION=Release \
  HNS_IOS_DESTINATION="generic/platform=iOS" \
  ./scripts/build-ios.sh

record_ios_gate_phase complete
echo "iOS gate passed: ABI, XCFramework, simulator tests, and unsigned arm64 device link."
