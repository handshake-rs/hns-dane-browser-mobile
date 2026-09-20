#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COHORT_DIR="$(cd "$ROOT_DIR/.." && pwd)"

ensure_checkout() {
  local repository="$1"
  local url="$2"
  local expected_commit="$3"
  local destination="$COHORT_DIR/$repository"

  if [[ -e "$destination" && ! -d "$destination/.git" ]]; then
    echo "ERROR: source-cohort path exists but is not a Git checkout: $destination" >&2
    exit 2
  fi

  if [[ ! -d "$destination/.git" ]]; then
    mkdir -p "$destination"
    git -C "$destination" init --quiet
    git -C "$destination" remote add origin "$url"
    git -C "$destination" -c credential.helper= fetch \
      --no-tags --depth=1 origin "$expected_commit"
    git -C "$destination" checkout --quiet --detach FETCH_HEAD
  fi

  local actual_origin
  actual_origin="$(git -C "$destination" remote get-url origin 2>/dev/null || true)"
  if [[ "${actual_origin%.git}" != "${url%.git}" ]]; then
    echo "ERROR: $repository origin must be $url; found ${actual_origin:-missing}." >&2
    exit 2
  fi

  local actual_commit
  actual_commit="$(git -C "$destination" rev-parse HEAD)"
  if [[ "$actual_commit" != "$expected_commit" ]]; then
    echo "ERROR: $repository must be at $expected_commit; found $actual_commit." >&2
    exit 2
  fi

  if ! git -C "$destination" diff --quiet || \
    ! git -C "$destination" diff --cached --quiet; then
    echo "ERROR: $repository has tracked working-tree changes." >&2
    exit 2
  fi
}

# These immutable commits define the source cohort consumed by the mobile
# candidate's path dependencies. A clean hosted runner materializes the same
# sibling layout used by local development, while an existing checkout must
# already match exactly and remain free of tracked modifications.
ensure_checkout \
  hns-wallet-rs \
  https://github.com/handshake-rs/hns-wallet-rs.git \
  c322f3cdb86f0c2d60d548a10a68f41365b9c252
ensure_checkout \
  hns-dane-engine \
  https://github.com/handshake-rs/hns-dane-engine.git \
  bf6855aba037dcb3720e0624c04eb7ee1e09cb5b
ensure_checkout \
  hns-rs \
  https://github.com/handshake-rs/hns-rs.git \
  f43f8dd325c221766787810fdd1fa3b3657689ca

echo "Pinned mobile source cohort is present and exact."
