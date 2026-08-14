#!/usr/bin/env python3
"""Require registry inputs or exact reviewed ecosystem revisions."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from pathlib import Path
import subprocess
import sys
import tomllib
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
CRATES_IO_SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
ENGINE_VERSIONS = {
    "hns-browser-observability": "0.2.1",
    "hns-browser-runtime": "0.2.1",
    "hns-icann-dane": "0.2.1",
    "hns-namespace-resolution": "0.2.1",
    "hns-resolution-policy": "0.2.1",
}
ENGINE_GIT_URL = "https://github.com/handshake-rs/hns-dane-engine.git"
ENGINE_GIT_REVISION = "65c397e8347f37085ea67d2c9c745ce896328e64"
WALLET_GIT_URL = "https://github.com/handshake-rs/hns-wallet-rs.git"
WALLET_GIT_REVISION = "2061a27e0358c7f00fcc70497ef97f9b89d569da"
PROTOCOL_GIT_URL = "https://github.com/handshake-rs/hns-rs.git"
PROTOCOL_GIT_REVISION = "88ed7c64db52a6fcfce4146a8fc17b1377dfcc8e"
APPROVED_ENGINE_GIT = {
    package: ("0.2.1", ENGINE_GIT_REVISION)
    for package in {
        "hns-dane",
        "hns-browser-dane",
        "hns-dnssec",
        "hns-browser-dnssec",
        "hns-p2p",
        "hns-browser-p2p",
        "hns-resolver",
        "hns-browser-resolver",
        "hns-sync",
        "hns-browser-sync",
        "hns-chain",
        "hns-browser-chain",
        "hns-urkel",
        "hns-browser-urkel",
        "hns-core",
        "hns-browser-primitives",
        "hns-cache",
        "hns-dns-wire",
        "hns-gateway",
        "hns-browser-gateway",
        "hns-transport",
        "hns-browser-transport",
        "hns-loopback-proxy",
        "hns-browser-loopback-proxy",
        "hns-browser-observability",
        "hns-browser-runtime",
        "hns-icann-dane",
        "hns-namespace-resolution",
        "hns-resolution-policy",
    }
}
APPROVED_WALLET_GIT = {
    package: ("0.1.0", WALLET_GIT_REVISION)
    for package in {
        "hns-wallet-bitcoin-kyoto",
        "hns-wallet-chain-api",
        "hns-wallet-ethereum",
        "hns-wallet-ffi",
        "hns-wallet-hns",
        "hns-wallet-host",
        "hns-wallet-market",
        "hns-wallet-mobile",
        "hns-wallet-provider",
        "hns-wallet-service",
        "hns-wallet-shakedex",
        "hns-wallet-store",
        "hns-wallet-testkit",
        "hns-wallet-types",
    }
}
APPROVED_PROTOCOL_GIT = {
    package: ("0.3.0", PROTOCOL_GIT_REVISION)
    for package in {
        "hns-chat-protocol",
        "hns-covenants",
        "hns-dns-relay-protocol",
        "hns-encoding",
        "hns-header-consensus",
        "hns-hnsr-protocol",
        "hns-marketplace-protocol",
        "hns-mining",
        "hns-odoh-protocol",
        "hns-p2p-experimental",
        "hns-p2p-wire",
        "hns-primitives",
        "hns-script",
        "hns-service-authority",
        "hns-swap",
        "hns-transaction",
        "hns-urkel-proof",
    }
}
ENGINE_GIT_PACKAGE_NAMES = {
    "hns-dane": "hns-browser-dane",
    "hns-dnssec": "hns-browser-dnssec",
    "hns-p2p": "hns-browser-p2p",
    "hns-resolver": "hns-browser-resolver",
    "hns-sync": "hns-browser-sync",
    "hns-chain": "hns-browser-chain",
    "hns-urkel": "hns-browser-urkel",
    "hns-core": "hns-browser-primitives",
    "hns-gateway": "hns-browser-gateway",
    "hns-transport": "hns-browser-transport",
    "hns-loopback-proxy": "hns-browser-loopback-proxy",
}
for engine_package in APPROVED_ENGINE_GIT:
    ENGINE_GIT_PACKAGE_NAMES.setdefault(engine_package, engine_package)
APPROVED_CARGO_GIT = {
    **{
        package: (version, revision, ENGINE_GIT_URL)
        for package, (version, revision) in APPROVED_ENGINE_GIT.items()
    },
    **{
        package: (version, revision, WALLET_GIT_URL)
        for package, (version, revision) in APPROVED_WALLET_GIT.items()
    },
    **{
        package: (version, revision, PROTOCOL_GIT_URL)
        for package, (version, revision) in APPROVED_PROTOCOL_GIT.items()
    },
}
CARGO_GIT_PACKAGE_NAMES = dict(ENGINE_GIT_PACKAGE_NAMES)
for approved_package in APPROVED_CARGO_GIT:
    CARGO_GIT_PACKAGE_NAMES.setdefault(approved_package, approved_package)
ENGINE_REQUIREMENTS = {
    package: f"={version}" for package, version in ENGINE_VERSIONS.items()
}
ENGINE_PACKAGES = frozenset(ENGINE_VERSIONS)
ROOT_MANIFEST = Path("rust/Cargo.toml")
MIGRATED_LOCAL_CRATES = frozenset(
    {
        "hns-cache",
        "hns-chain",
        "hns-core",
        "hns-dane",
        "hns-dnssec",
        "hns-gateway",
        "hns-loopback-proxy",
        "hns-p2p",
        "hns-resolver",
        "hns-sync",
        "hns-transport",
        "hns-urkel",
    }
)
LOCKFILES = (
    Path("rust/Cargo.lock"),
    Path("rust/fuzz/Cargo.lock"),
    Path("tools/hns-header-snapshot-exporter/Cargo.lock"),
)


class CargoSourcePolicyError(RuntimeError):
    """A Cargo manifest or lockfile violates the reviewed source policy."""


def tracked_cargo_manifests(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return sorted(
        path
        for raw in result.stdout.split(b"\0")
        if raw
        and (path := Path(raw.decode())).name == "Cargo.toml"
        and (root / path).is_file()
    )


def git_specs(
    value: Any, path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], Mapping[str, Any]]]:
    if isinstance(value, Mapping):
        if "git" in value:
            yield path, value
        for key, child in value.items():
            yield from git_specs(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from git_specs(child, (*path, str(index)))


def path_specs(
    value: Any, path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], Mapping[str, Any]]]:
    if isinstance(value, Mapping):
        if "path" in value:
            yield path, value
        for key, child in value.items():
            yield from path_specs(child, (*path, str(key)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from path_specs(child, (*path, str(index)))


def is_approved_git_source(name: str, version: str, source: str) -> bool:
    approved = APPROVED_CARGO_GIT.get(name)
    if approved is None or version != approved[0]:
        return False
    revision = approved[1]
    url = approved[2]
    return source == f"git+{url}?rev={revision}#{revision}"


def approved_git_revision(name: str) -> str | None:
    approved = APPROVED_CARGO_GIT.get(name)
    return approved[1] if approved is not None else None


def load_toml(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def validate_manifests(root: Path, manifests: list[Path]) -> None:
    for relative_path in manifests:
        document = load_toml(root / relative_path)
        for location, specification in git_specs(document):
            rendered_location = ".".join(location) or "<document root>"
            package = location[-1] if location else ""
            approved = APPROVED_CARGO_GIT.get(package)
            expected_package = CARGO_GIT_PACKAGE_NAMES.get(package)
            actual_package = specification.get("package", package)
            expected_requirement = f"={approved[0]}" if approved is not None else ""
            if (
                approved is None
                or specification.get("git") != approved[2]
                or specification.get("rev") != approved[1]
                or actual_package != expected_package
                or (
                    "patch" not in location
                    and specification.get("version") != expected_requirement
                )
                or "branch" in specification
                or "tag" in specification
            ):
                raise CargoSourcePolicyError(
                    f"{relative_path}:{rendered_location}: Cargo Git dependency "
                    "is not an exact reviewed ecosystem revision"
                )
        for location, specification in path_specs(document):
            dependency = location[-1] if location else ""
            package = specification.get("package", dependency)
            if dependency in APPROVED_CARGO_GIT or package in APPROVED_CARGO_GIT:
                rendered_location = ".".join(location) or "<document root>"
                raise CargoSourcePolicyError(
                    f"{relative_path}:{rendered_location}: reviewed ecosystem dependency "
                    "must not use a local path"
                )

    root_document = load_toml(root / ROOT_MANIFEST)
    dependencies = root_document.get("workspace", {}).get("dependencies", {})
    if not isinstance(dependencies, Mapping):
        raise CargoSourcePolicyError(
            f"{ROOT_MANIFEST}: [workspace.dependencies] is missing"
        )
    for package in sorted(ENGINE_PACKAGES):
        expected_requirement = ENGINE_REQUIREMENTS[package]
        specification = dependencies.get(package)
        if not isinstance(specification, Mapping):
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must use an exact reviewed Git dependency"
            )
        requirement = specification.get("version")
        forbidden = {
            "path",
            "registry",
            "branch",
            "tag",
            "package",
        }.intersection(specification)
        if forbidden:
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must use the exact reviewed "
                f"engine source without selectors: {sorted(forbidden)}"
            )
        if requirement != expected_requirement:
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must be pinned to "
                f"{expected_requirement!r}, found {requirement!r}"
            )
        if (
            specification.get("git") != ENGINE_GIT_URL
            or specification.get("rev") != ENGINE_GIT_REVISION
        ):
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must use the exact reviewed "
                f"hns-dane-engine revision {ENGINE_GIT_REVISION}"
            )


def validate_lockfiles(root: Path) -> None:
    root_packages: dict[str, int] = {package: 0 for package in ENGINE_PACKAGES}

    for relative_path in LOCKFILES:
        document = load_toml(root / relative_path)
        for package in document.get("package", []):
            source = package.get("source")
            name = package.get("name", "<unknown>")
            if isinstance(source, str) and source.startswith("git+"):
                if not is_approved_git_source(
                    name, package.get("version", ""), source
                ):
                    raise CargoSourcePolicyError(
                        f"{relative_path}: locked Cargo Git package {name!r} is not allowed"
                    )
                if relative_path == Path("rust/Cargo.lock") and name in root_packages:
                    root_packages[name] += 1
                continue
            if name in APPROVED_CARGO_GIT:
                raise CargoSourcePolicyError(
                    f"{relative_path}: {name} must come from its exact reviewed "
                    f"ecosystem source revision, found {source!r}"
                )

    for package, count in sorted(root_packages.items()):
        if count != 1:
            raise CargoSourcePolicyError(
                "rust/Cargo.lock: expected exactly one reviewed Git package for "
                f"{package} {ENGINE_VERSIONS[package]}, found {count}"
            )


def verify_repository(
    root: Path = ROOT, manifests: list[Path] | None = None
) -> None:
    for package in sorted(MIGRATED_LOCAL_CRATES):
        path = root / "rust/crates" / package
        if path.exists():
            raise CargoSourcePolicyError(
                f"{path.relative_to(root)}: migrated engine crate must not be restored locally"
            )
    validate_manifests(
        root,
        tracked_cargo_manifests(root) if manifests is None else manifests,
    )
    validate_lockfiles(root)


def main() -> int:
    try:
        verify_repository()
    except (
        CargoSourcePolicyError,
        OSError,
        subprocess.CalledProcessError,
        tomllib.TOMLDecodeError,
    ) as error:
        print(f"Cargo source policy failed: {error}", file=sys.stderr)
        return 1
    versions = ", ".join(
        f"{package}={version}" for package, version in sorted(ENGINE_VERSIONS.items())
    )
    print(
        "Cargo source policy permits registry inputs plus exact reviewed "
        "engine, protocol, and wallet revisions and pins the five canonical "
        f"engine packages at one source: {versions}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
