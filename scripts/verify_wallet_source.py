#!/usr/bin/env python3
"""Verify published wallet versions and checksums used by mobile builds."""

from pathlib import Path
import re
import subprocess
import tomllib


ROOT = Path(__file__).resolve().parents[1]
WALLET_SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
CHECKSUM_MANIFEST = Path("rust/wallet-crates.sha256")
WALLET_VERSIONS = {
    "hns-wallet-bdk-kyoto": "0.4.1",
    "hns-wallet-bip157": "0.4.1",
    "hns-wallet-bitcoin-kyoto": "0.4.1",
    "hns-wallet-chain-api": "0.4.1",
    "hns-wallet-ffi": "0.4.1",
    "hns-wallet-hns": "0.4.2",
    "hns-wallet-host": "0.4.1",
    "hns-wallet-market": "0.4.2",
    "hns-wallet-mobile": "0.4.1",
    "hns-wallet-provider": "0.4.1",
    "hns-wallet-service": "0.4.1",
    "hns-wallet-shakedex": "0.4.1",
    "hns-wallet-store": "0.4.1",
    "hns-wallet-types": "0.4.1",
}
LOCKFILES = (
    Path("rust/Cargo.lock"),
    Path("rust/fuzz/Cargo.lock"),
    Path("tools/hns-header-snapshot-exporter/Cargo.lock"),
)


def git_specs(value):
    if isinstance(value, dict):
        if "git" in value:
            yield value
        for child in value.values():
            yield from git_specs(child)
    elif isinstance(value, list):
        for child in value:
            yield from git_specs(child)


def wallet_path_specs(value):
    if isinstance(value, dict):
        for name, child in value.items():
            if isinstance(child, dict):
                package = child.get("package", name)
                if isinstance(package, str) and package.startswith("hns-wallet-") and "path" in child:
                    yield child
            yield from wallet_path_specs(child)
    elif isinstance(value, list):
        for child in value:
            yield from wallet_path_specs(child)


def verify_repository(root=ROOT, manifests=None):
    root_manifest = Path("rust/Cargo.toml")
    document = tomllib.loads((root / root_manifest).read_text())
    expected_files = {f"{name}-{version}.crate": name for name, version in WALLET_VERSIONS.items()}
    checksums = {}
    for line in (root / CHECKSUM_MANIFEST).read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-z0-9.-]+\.crate)", line)
        if match is None or match[2] not in expected_files or expected_files[match[2]] in checksums:
            raise ValueError("Wallet checksum manifest must contain exactly the reviewed published packages")
        checksums[expected_files[match[2]]] = match[1]
    if set(checksums) != set(WALLET_VERSIONS):
        raise ValueError("Wallet checksum manifest is missing a reviewed published package")
    dependencies = document["workspace"]["dependencies"]
    for name, version in WALLET_VERSIONS.items():
        if name in dependencies and dependencies[name] != f"={version}":
            raise ValueError(f"{name}: wallet dependency must match its exact published version")
    if manifests is None:
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root)
        manifests = [Path(p.decode()) for p in tracked.split(b"\0") if p and Path(p.decode()).name == "Cargo.toml"]
    for manifest in manifests:
        data = tomllib.loads((root / manifest).read_text())
        if data.get("patch") or data.get("replace"):
            raise ValueError(f"{manifest}: published wallet builds must not use source overrides")
        if next(wallet_path_specs(data), None) is not None:
            raise ValueError(f"{manifest}: published wallet builds must not use local wallet paths")
        if next(git_specs(data), None) is not None:
            raise ValueError(f"{manifest}: unreviewed Git dependency")
    counts = dict.fromkeys(WALLET_VERSIONS, 0)
    for lockfile in LOCKFILES:
        for package in tomllib.loads((root / lockfile).read_text()).get("package", []):
            name = package["name"]
            source = package.get("source", "")
            if name in WALLET_VERSIONS:
                if lockfile != LOCKFILES[0] or source != WALLET_SOURCE or package["version"] != WALLET_VERSIONS[name] or package.get("checksum") != checksums[name]:
                    raise ValueError(f"{lockfile}: {name} does not match its published wallet version, source, and checksum")
                counts[name] += 1
            elif name.startswith("hns-wallet-"):
                raise ValueError(f"{lockfile}: unreviewed wallet package {name}")
            elif source.startswith("git+"):
                raise ValueError(f"{lockfile}: unreviewed Git package {name}")
    if any(count != 1 for count in counts.values()):
        raise ValueError("The mobile lockfile must contain each published wallet package exactly once")


if __name__ == "__main__":
    try:
        verify_repository()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Wallet source verification failed: {error}")
    print("Wallet packages match the exact published versions, registry source, and checksums.")
