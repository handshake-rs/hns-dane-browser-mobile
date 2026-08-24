#!/usr/bin/env python3
"""Enforce exact registry-only HNS Wallet dependency inputs."""
from __future__ import annotations

from collections.abc import Iterator, Mapping
from pathlib import Path
import subprocess
import sys
import tomllib
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
CRATES_IO_SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
HNS_VERSION, ENGINE_VERSION, WALLET_VERSION = "0.3.1", "0.2.2", "0.1.1"
ENGINE_PATCH_VERSIONS = {
    "hns-browser-gateway": "0.2.3",
    "hns-namespace-resolution": "0.2.3",
}

HNS_REGISTRY = frozenset("""hns-chat-protocol hns-covenants hns-dns-relay-protocol hns-encoding hns-header-consensus hns-hnsr-protocol hns-hrm hns-marketplace-protocol hns-mining hns-odoh-protocol hns-p2p-experimental hns-p2p-wire hns-primitives hns-rollback-journal hns-script hns-service-authority hns-swap hns-transaction hns-urkel-proof""".split())
ENGINE_REGISTRY = frozenset("""hns-browser-chain hns-browser-dane hns-browser-dnssec hns-browser-gateway hns-browser-loopback-proxy hns-browser-observability hns-browser-p2p hns-browser-primitives hns-browser-resolver hns-browser-runtime hns-browser-sync hns-browser-transport hns-browser-urkel hns-cache hns-dane hns-dane-engine hns-dane-engine-ffi hns-dns-wire hns-dnssec hns-gateway hns-icann-dane hns-light-chain hns-light-p2p hns-light-sync hns-light-wallet hns-loopback-proxy hns-namespace-resolution hns-p2p-transport hns-resolution-policy hns-resolver hns-transport""".split())
WALLET_REGISTRY = frozenset("""hns-wallet-bitcoin-kyoto hns-wallet-chain-api hns-wallet-ethereum hns-wallet-ffi hns-wallet-hns hns-wallet-host hns-wallet-market hns-wallet-mobile hns-wallet-provider hns-wallet-service hns-wallet-shakedex hns-wallet-store hns-wallet-testkit hns-wallet-types""".split())
REGISTRY_VERSIONS = {
    **{name: HNS_VERSION for name in HNS_REGISTRY},
    **{name: ENGINE_PATCH_VERSIONS.get(name, ENGINE_VERSION) for name in ENGINE_REGISTRY},
    **{name: WALLET_VERSION for name in WALLET_REGISTRY},
}

ENGINE_REGISTRY_PACKAGE_NAMES = {
    "hns-chain": "hns-browser-chain", "hns-core": "hns-browser-primitives",
    "hns-dane": "hns-browser-dane", "hns-dnssec": "hns-browser-dnssec",
    "hns-gateway": "hns-browser-gateway", "hns-loopback-proxy": "hns-browser-loopback-proxy",
    "hns-p2p": "hns-browser-p2p", "hns-resolver": "hns-browser-resolver",
    "hns-sync": "hns-browser-sync", "hns-transport": "hns-browser-transport",
    "hns-urkel": "hns-browser-urkel",
}
ROOT_REGISTRY_DEPENDENCIES = {
    "hns-header-consensus": HNS_VERSION, "hns-light-sync": ENGINE_VERSION,
    "hns-cache": ENGINE_VERSION, "hns-browser-observability": ENGINE_VERSION,
    "hns-browser-runtime": ENGINE_VERSION, "hns-icann-dane": ENGINE_VERSION,
    "hns-namespace-resolution": ENGINE_PATCH_VERSIONS["hns-namespace-resolution"], "hns-resolution-policy": ENGINE_VERSION,
    "hns-wallet-ffi": WALLET_VERSION, "hns-wallet-mobile": WALLET_VERSION,
    "hns-wallet-types": WALLET_VERSION,
    **{alias: ENGINE_PATCH_VERSIONS.get(package, ENGINE_VERSION) for alias, package in ENGINE_REGISTRY_PACKAGE_NAMES.items()},
}
ROOT_REGISTRY_PACKAGE_NAMES = {
    **{name: name for name in ROOT_REGISTRY_DEPENDENCIES},
    **ENGINE_REGISTRY_PACKAGE_NAMES,
}
ROOT_MANIFEST = Path("rust/Cargo.toml")
LOCKFILES = (Path("rust/Cargo.lock"), Path("rust/fuzz/Cargo.lock"), Path("tools/hns-header-snapshot-exporter/Cargo.lock"))
MIGRATED_LOCAL_CRATES = frozenset("""hns-cache hns-chain hns-core hns-dane hns-dnssec hns-gateway hns-loopback-proxy hns-p2p hns-resolver hns-sync hns-transport hns-urkel""".split())

class CargoSourcePolicyError(RuntimeError): pass

def load(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle: return tomllib.load(handle)

def manifests(root: Path) -> list[Path]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True).stdout
    return sorted(Path(raw.decode()) for raw in out.split(b"\0") if raw and Path(raw.decode()).name == "Cargo.toml" and (root / Path(raw.decode())).is_file())

def specs(value: Any, needle: str, path: tuple[str, ...] = ()) -> Iterator[tuple[tuple[str, ...], Mapping[str, Any]]]:
    if isinstance(value, Mapping):
        if needle in value: yield path, value
        for key, child in value.items(): yield from specs(child, needle, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value): yield from specs(child, needle, (*path, str(index)))

def validate_manifests(root: Path, selected: list[Path]) -> None:
    ecosystem = frozenset(REGISTRY_VERSIONS)
    for relative in selected:
        document = load(root / relative)
        if "patch" in document: raise CargoSourcePolicyError(f"{relative}: source patches are forbidden")
        for location, _spec in specs(document, "git"):
            raise CargoSourcePolicyError(f"{relative}:{'.'.join(location)} uses a prohibited Git source")
        for location, spec in specs(document, "path"):
            key = location[-1] if location else ""
            if key in ecosystem or spec.get("package", key) in ecosystem:
                raise CargoSourcePolicyError(f"{relative}:{'.'.join(location)} uses a local ecosystem path")
    dependencies = load(root / ROOT_MANIFEST).get("workspace", {}).get("dependencies", {})
    if not isinstance(dependencies, Mapping): raise CargoSourcePolicyError("root workspace dependencies missing")
    for name, version in ROOT_REGISTRY_DEPENDENCIES.items():
        value = dependencies.get(name)
        if value == f"={version}": continue
        expected_package = ROOT_REGISTRY_PACKAGE_NAMES[name]
        allowed = {"package", "version", "default-features", "features"}
        if (not isinstance(value, Mapping) or value.get("version") != f"={version}" or value.get("package", name) != expected_package or set(value).difference(allowed)):
            raise CargoSourcePolicyError(f"{ROOT_MANIFEST}: {name} is not exact registry {version}")

def validate_lockfiles(root: Path) -> None:
    registry_count = {name: 0 for name in ROOT_REGISTRY_PACKAGE_NAMES.values()}
    for relative in LOCKFILES:
        for package in load(root / relative).get("package", []):
            name, version, source = package.get("name", ""), package.get("version", ""), package.get("source")
            if name in REGISTRY_VERSIONS and source == CRATES_IO_SOURCE:
                if version != REGISTRY_VERSIONS[name] or not isinstance(package.get("checksum"), str) or len(package["checksum"]) != 64:
                    raise CargoSourcePolicyError(f"{relative}: invalid published {name} release record")
                if relative == Path("rust/Cargo.lock") and name in registry_count: registry_count[name] += 1
            elif isinstance(source, str) and source.startswith("git+"):
                raise CargoSourcePolicyError(f"{relative}: prohibited Git input {name}")
            elif name in REGISTRY_VERSIONS:
                raise CargoSourcePolicyError(f"{relative}: invalid source for {name}")
    for name, count in registry_count.items():
        if count != 1: raise CargoSourcePolicyError(f"rust/Cargo.lock: expected one required {name}, found {count}")

def verify_repository(root: Path = ROOT, selected: list[Path] | None = None) -> None:
    if (root / "rust/vendor/hns-light-p2p/Cargo.toml").exists(): raise CargoSourcePolicyError("obsolete local hns-light-p2p patch remains")
    for name in MIGRATED_LOCAL_CRATES:
        if (root / "rust/crates" / name).exists(): raise CargoSourcePolicyError(f"restored local engine crate: {name}")
    validate_manifests(root, manifests(root) if selected is None else selected)
    validate_lockfiles(root)

def main() -> int:
    try: verify_repository()
    except (CargoSourcePolicyError, OSError, subprocess.CalledProcessError, tomllib.TOMLDecodeError) as error:
        print(f"Cargo source policy failed: {error}", file=sys.stderr); return 1
    print("Cargo source policy pins published hns-rs 0.3.1, hns-dane-engine 0.2.2 plus the required gateway and namespace-resolution 0.2.3 patches, and hns-wallet-rs 0.1.1 from crates.io with no Git ecosystem inputs.")
    return 0

if __name__ == "__main__": raise SystemExit(main())
