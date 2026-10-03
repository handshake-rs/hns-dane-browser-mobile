#!/usr/bin/env python3
"""Verify the exact wallet source used by reproducible mobile builds."""

from pathlib import Path
import subprocess
import tomllib


ROOT = Path(__file__).resolve().parents[1]
WALLET_URL = "https://github.com/handshake-rs/hns-wallet-rs.git"
WALLET_REVISION = "1737dd439c4a13ec6bcbed4bc056733252e4c1e1"
WALLET_SOURCE = f"git+{WALLET_URL}?rev={WALLET_REVISION}#{WALLET_REVISION}"
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


def verify_repository(root=ROOT, manifests=None):
    root_manifest = Path("rust/Cargo.toml")
    document = tomllib.loads((root / root_manifest).read_text())
    expected = {
        name: {"git": WALLET_URL, "rev": WALLET_REVISION, "version": f"={version}"}
        for name, version in WALLET_VERSIONS.items()
    }
    if document.get("patch", {}).get("crates-io") != expected:
        raise ValueError("Wallet overrides must match the exact reviewed packages, versions, and revision")
    if manifests is None:
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root)
        manifests = [Path(p.decode()) for p in tracked.split(b"\0") if p and Path(p.decode()).name == "Cargo.toml"]
    for manifest in manifests:
        data = tomllib.loads((root / manifest).read_text())
        if manifest == root_manifest:
            data["patch"].pop("crates-io")
        if next(git_specs(data), None) is not None:
            raise ValueError(f"{manifest}: unreviewed Git dependency")
    counts = dict.fromkeys(WALLET_VERSIONS, 0)
    for lockfile in LOCKFILES:
        for package in tomllib.loads((root / lockfile).read_text()).get("package", []):
            name = package["name"]
            source = package.get("source", "")
            if name in WALLET_VERSIONS:
                if lockfile != LOCKFILES[0] or source != WALLET_SOURCE or package["version"] != WALLET_VERSIONS[name] or "checksum" in package:
                    raise ValueError(f"{lockfile}: {name} does not match its reviewed wallet source")
                counts[name] += 1
            elif source.startswith("git+"):
                raise ValueError(f"{lockfile}: unreviewed Git package {name}")
    if any(count != 1 for count in counts.values()):
        raise ValueError("The mobile lockfile must contain each reviewed wallet package exactly once")


if __name__ == "__main__":
    try:
        verify_repository()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Wallet source verification failed: {error}")
    print("Wallet packages match the exact reviewed source revision and package versions.")
