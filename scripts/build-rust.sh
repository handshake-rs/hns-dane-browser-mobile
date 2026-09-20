#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT_DIR/scripts/prepare-source-cohort.sh"
RUST_TOOLCHAIN="1.98.1"
cargo "+$RUST_TOOLCHAIN" build --locked --manifest-path "$ROOT_DIR/rust/Cargo.toml" --workspace
