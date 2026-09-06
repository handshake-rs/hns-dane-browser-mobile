#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/android/app/build/generated/rustJniLibs}"
PROFILE="${HNS_RUST_ANDROID_PROFILE:-release}"
EXPECTED_NDK_VERSION="${HNS_ANDROID_NDK_VERSION:-28.2.13676358}"
ANDROID_ABIS_CSV="${HNS_RUST_ANDROID_ABIS:-armeabi-v7a,arm64-v8a,x86_64}"
RUST_TOOLCHAIN="1.92.0"
CARGO=(cargo "+$RUST_TOOLCHAIN")
RUSTC=(rustc "+$RUST_TOOLCHAIN")

case "$PROFILE" in
  debug|release) ;;
  *)
    echo "ERROR: HNS_RUST_ANDROID_PROFILE must be 'debug' or 'release', not '$PROFILE'." >&2
    exit 2
    ;;
esac

IFS=',' read -r -a ANDROID_ABIS <<< "$ANDROID_ABIS_CSV"
if [[ ${#ANDROID_ABIS[@]} -eq 0 ]]; then
  echo "ERROR: HNS_RUST_ANDROID_ABIS must select at least one Android ABI." >&2
  exit 2
fi
seen_android_abis=" "
for abi in "${ANDROID_ABIS[@]}"; do
  case "$abi" in
    armeabi-v7a|arm64-v8a|x86_64) ;;
    *)
      echo "ERROR: unsupported Android ABI in HNS_RUST_ANDROID_ABIS: $abi" >&2
      exit 2
      ;;
  esac
  if [[ "$seen_android_abis" == *" $abi "* ]]; then
    echo "ERROR: duplicate Android ABI in HNS_RUST_ANDROID_ABIS: $abi" >&2
    exit 2
  fi
  seen_android_abis+="$abi "
done

configured_rust_toolchain="$(
  sed -n 's/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$ROOT_DIR/rust/rust-toolchain.toml"
)"
if [[ "$configured_rust_toolchain" != "$RUST_TOOLCHAIN" ]]; then
  echo "ERROR: rust/rust-toolchain.toml must pin Rust $RUST_TOOLCHAIN; found '${configured_rust_toolchain:-missing}'." >&2
  exit 2
fi

installed_cargo_version="$("${CARGO[@]}" --version 2>/dev/null || true)"
if [[ "$installed_cargo_version" != "cargo $RUST_TOOLCHAIN "* ]]; then
  echo "ERROR: cargo $RUST_TOOLCHAIN is required; found '${installed_cargo_version:-unavailable}'." >&2
  exit 2
fi
installed_rustc_version="$("${RUSTC[@]}" --version 2>/dev/null || true)"
if [[ "$installed_rustc_version" != "rustc $RUST_TOOLCHAIN "* ]]; then
  echo "ERROR: rustc $RUST_TOOLCHAIN is required; found '${installed_rustc_version:-unavailable}'." >&2
  exit 2
fi

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" ]]; then
  echo "ERROR: ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to the Android NDK." >&2
  exit 2
fi
if [[ ! -d "$NDK_DIR" ]]; then
  echo "ERROR: Android NDK directory does not exist: $NDK_DIR" >&2
  exit 2
fi
NDK_DIR="$(cd "$NDK_DIR" && pwd -P)"

NDK_PROPERTIES="$NDK_DIR/source.properties"
installed_ndk_version="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_PROPERTIES" 2>/dev/null | head -n 1)"
if [[ -z "$installed_ndk_version" ]]; then
  echo "ERROR: unable to read the Android NDK version from $NDK_PROPERTIES." >&2
  exit 2
fi
if [[ -n "$EXPECTED_NDK_VERSION" && "$installed_ndk_version" != "$EXPECTED_NDK_VERSION" ]]; then
  echo "ERROR: Android NDK $EXPECTED_NDK_VERSION is required; found '${installed_ndk_version:-unknown}' at $NDK_DIR." >&2
  exit 2
fi

# Select the NDK's real host tag. Do not provide a misleading linux-x86_64
# alias on ARM64 merely to satisfy tools that hard-code Google's usual host
# package name: every invoked host binary must come from linux-arm64.
host_system="$(uname -s)"
host_arch="$(uname -m)"
case "$host_system:$host_arch" in
  Linux:aarch64|Linux:arm64)
    ndk_host_tag="linux-arm64"
    expected_clang_machine="AArch64"
    ;;
  Linux:x86_64|Linux:amd64)
    ndk_host_tag="linux-x86_64"
    expected_clang_machine="Advanced Micro Devices X86-64"
    ;;
  Darwin:arm64|Darwin:aarch64)
    ndk_host_tag="darwin-arm64"
    expected_clang_machine=""
    ;;
  Darwin:x86_64|Darwin:amd64)
    ndk_host_tag="darwin-x86_64"
    expected_clang_machine=""
    ;;
  *)
    echo "ERROR: unsupported Android-build host: $host_system $host_arch" >&2
    exit 2
    ;;
esac

NDK_TOOLCHAIN_BIN="$NDK_DIR/toolchains/llvm/prebuilt/$ndk_host_tag/bin"
host_clang="$NDK_TOOLCHAIN_BIN/clang"
if [[ ! -x "$host_clang" ]]; then
  echo "ERROR: native Android NDK host compiler is missing: $host_clang" >&2
  exit 2
fi
if [[ "$host_system" == "Linux" ]]; then
  if ! command -v readelf >/dev/null 2>&1; then
    echo "ERROR: readelf is required to verify that the Android NDK compiler is host-native." >&2
    exit 2
  fi
  actual_clang_machine="$(
    LC_ALL=C readelf -h "$(realpath -- "$host_clang")" 2>/dev/null \
      | sed -n 's/^[[:space:]]*Machine:[[:space:]]*//p' \
      | head -n 1
  )"
  if [[ "$actual_clang_machine" != "$expected_clang_machine" ]]; then
    echo "ERROR: refusing emulated Android NDK compiler on $host_arch." >&2
    echo "ERROR: expected host-native ELF machine '$expected_clang_machine'; found '${actual_clang_machine:-unknown}' at $(realpath -- "$host_clang")." >&2
    exit 2
  fi
fi

mkdir -p -- "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd -P)"
case "$OUT_DIR" in
  "$ROOT_DIR"/android/app/build/*) ;;
  *)
    echo "ERROR: refusing to clean native output outside android/app/build: $OUT_DIR" >&2
    exit 2
    ;;
esac
find "$OUT_DIR" -type f -name '*.so' -delete

CARGO_PROFILE_ARGS=()

if [[ "$PROFILE" == "release" ]]; then
  CARGO_PROFILE_ARGS+=(--release)

  # Keep enough DWARF for AGP to produce Play Console native debug symbols.
  # Stable remapped paths avoid leaking the builder's checkout and tool homes.
  blocked_flag_variables=()
  while IFS='=' read -r variable_name _; do
    case "$variable_name" in
      RUSTUP_TOOLCHAIN|RUSTFLAGS|CARGO_ENCODED_RUSTFLAGS|CARGO_BUILD_RUSTFLAGS|CARGO_BUILD_RUSTC|CARGO_BUILD_RUSTC_WRAPPER|CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER|CARGO_PROFILE_RELEASE_*|CARGO_TARGET_*_RUSTFLAGS|CARGO_TARGET_*_LINKER|RUSTC|RUSTC_WRAPPER|RUSTC_WORKSPACE_WRAPPER|CC|CC_*|*_CC|CXX|CXX_*|*_CXX|AR|AR_*|*_AR|LD|HOST_LD|TARGET_LD|CFLAGS|CFLAGS_*|*_CFLAGS|CXXFLAGS|CXXFLAGS_*|*_CXXFLAGS|CPPFLAGS|CPPFLAGS_*|*_CPPFLAGS|LDFLAGS|LDFLAGS_*|*_LDFLAGS|ARFLAGS|ARFLAGS_*|*_ARFLAGS|ASFLAGS|ASFLAGS_*|*_ASFLAGS)
        blocked_flag_variables+=("$variable_name")
        ;;
    esac
  done < <(env)
  if [[ ${#blocked_flag_variables[@]} -ne 0 ]]; then
    printf 'ERROR: release builds reject caller-supplied toolchain or compiler overrides: %s\n' \
      "${blocked_flag_variables[*]}" >&2
    exit 2
  fi

  HOME_DIR="${HOME:?HOME must be set}"
  CARGO_HOME_DIR="${CARGO_HOME:-$HOME_DIR/.cargo}"
  RUSTUP_HOME_DIR="${RUSTUP_HOME:-$HOME_DIR/.rustup}"
  release_rustflags=(
    "--remap-path-prefix=$ROOT_DIR=/build/source"
    "--remap-path-prefix=$CARGO_HOME_DIR=/build/cargo"
    "--remap-path-prefix=$RUSTUP_HOME_DIR=/build/rustup"
    "--remap-path-prefix=$NDK_DIR=/build/ndk"
    "--remap-path-prefix=$HOME_DIR=/build/home"
    "-C"
    "link-arg=-Wl,--build-id=sha1"
    "-C"
    "link-arg=-Wl,-z,max-page-size=16384"
    "-C"
    "link-arg=-Wl,-z,common-page-size=16384"
  )
  release_cflags=()
  for path_mapping in \
    "$ROOT_DIR=/build/source" \
    "$CARGO_HOME_DIR=/build/cargo" \
    "$RUSTUP_HOME_DIR=/build/rustup" \
    "$NDK_DIR=/build/ndk" \
    "$HOME_DIR=/build/home"; do
    release_cflags+=(
      "-ffile-prefix-map=$path_mapping"
      "-fdebug-prefix-map=$path_mapping"
      "-fmacro-prefix-map=$path_mapping"
    )
  done
  for compiler_flag in "${release_rustflags[@]}" "${release_cflags[@]}"; do
    if [[ "$compiler_flag" =~ [[:space:]] ]]; then
      echo "ERROR: release build path cannot contain whitespace: $compiler_flag" >&2
      exit 2
    fi
  done
  export RUSTFLAGS="${release_rustflags[*]}"
  export CFLAGS="${release_cflags[*]}"
  export CXXFLAGS="$CFLAGS"
  export CFLAGS_aarch64_linux_android="$CFLAGS"
  export CFLAGS_armv7_linux_androideabi="$CFLAGS"
  export CFLAGS_x86_64_linux_android="$CFLAGS"
  export CXXFLAGS_aarch64_linux_android="$CXXFLAGS"
  export CXXFLAGS_armv7_linux_androideabi="$CXXFLAGS"
  export CXXFLAGS_x86_64_linux_android="$CXXFLAGS"
fi

# A compiler cache changes build latency, not release inputs or output flags.
# Select it only after the release override guard so callers cannot substitute
# an arbitrary compiler wrapper. Builders without sccache remain supported.
if SCCACHE_BIN="$(command -v sccache 2>/dev/null)" && [[ -n "$SCCACHE_BIN" ]]; then
  export RUSTC_WRAPPER="$SCCACHE_BIN"
  echo "Using sccache for Android Rust compilation: $SCCACHE_BIN"
fi

cd "$ROOT_DIR/rust"
ANDROID_CARGO_TARGET_DIR="$ROOT_DIR/android/app/build/rustTarget"
for abi in "${ANDROID_ABIS[@]}"; do
  case "$abi" in
    armeabi-v7a)
      rust_target="armv7-linux-androideabi"
      clang_target="armv7a-linux-androideabi"
      ;;
    arm64-v8a)
      rust_target="aarch64-linux-android"
      clang_target="aarch64-linux-android"
      ;;
    x86_64)
      rust_target="x86_64-linux-android"
      clang_target="x86_64-linux-android"
      ;;
  esac
  target_env="$(printf '%s' "$rust_target" | tr '[:lower:]-' '[:upper:]_')"
  target_suffix="$(printf '%s' "$rust_target" | tr '-' '_')"
  target_cc="$NDK_TOOLCHAIN_BIN/${clang_target}28-clang"
  target_cxx="$NDK_TOOLCHAIN_BIN/${clang_target}28-clang++"
  for tool in "$target_cc" "$target_cxx" "$NDK_TOOLCHAIN_BIN/llvm-ar" "$NDK_TOOLCHAIN_BIN/llvm-ranlib"; do
    if [[ ! -x "$tool" ]]; then
      echo "ERROR: required native NDK tool is missing or not executable: $tool" >&2
      exit 2
    fi
  done

  export "CC_$target_suffix=$target_cc"
  export "CXX_$target_suffix=$target_cxx"
  export "AR_$target_suffix=$NDK_TOOLCHAIN_BIN/llvm-ar"
  export "RANLIB_$target_suffix=$NDK_TOOLCHAIN_BIN/llvm-ranlib"
  export "CARGO_TARGET_${target_env}_LINKER=$target_cc"
  export "BINDGEN_EXTRA_CLANG_ARGS_$target_suffix=--sysroot=$NDK_DIR/toolchains/llvm/prebuilt/$ndk_host_tag/sysroot"

  echo "Building $abi ($rust_target) with native NDK host tag $ndk_host_tag"
  CARGO_TARGET_DIR="$ANDROID_CARGO_TARGET_DIR" \
    "${CARGO[@]}" build -p android-ffi --target "$rust_target" \
      "${CARGO_PROFILE_ARGS[@]}" --locked
  mkdir -p -- "$OUT_DIR/$abi"
  cp -- "$ANDROID_CARGO_TARGET_DIR/$rust_target/$PROFILE/libhns_dane_browser_ffi.so" \
    "$OUT_DIR/$abi/libhns_dane_browser_ffi.so"
done

for abi in "${ANDROID_ABIS[@]}"; do
  library="$OUT_DIR/$abi/libhns_dane_browser_ffi.so"
  if [[ ! -s "$library" ]]; then
    echo "ERROR: Cargo did not produce the required Android library: $library" >&2
    exit 1
  fi
  if [[ "$PROFILE" == "release" ]] && \
    LC_ALL=C grep -aFq -e "$ROOT_DIR" -e "${HOME:?HOME must be set}" -e "$NDK_DIR" "$library"; then
    echo "ERROR: release native library exposes a builder checkout, home, or NDK path: $library" >&2
    exit 1
  fi
done
