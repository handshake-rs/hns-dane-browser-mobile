#!/usr/bin/env python3
"""Require registry inputs or exact reviewed engine migration revisions."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
CRATES_IO_SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
ENGINE_VERSIONS = {
    "hns-browser-observability": "0.1.2",
    "hns-browser-runtime": "0.1.0",
    "hns-icann-dane": "0.1.0",
    "hns-namespace-resolution": "0.1.0",
    "hns-resolution-policy": "0.1.0",
}
ENGINE_GIT_URL = "https://github.com/handshake-rs/hns-dane-engine.git"
APPROVED_ENGINE_GIT = {
    "hns-dane": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-dane": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-dnssec": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-dnssec": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-p2p": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-p2p": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-chain": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-chain": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-urkel": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-urkel": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-core": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-browser-primitives": ("0.2.0", "3899fef338b701ea8cedba21331f0162bef9595b"),
    "hns-cache": ("0.2.0", "d8b564fe1aaf88c32f7bbfeb4a3a5306bbc7780f"),
    "hns-dns-wire": ("0.2.0", "d8b564fe1aaf88c32f7bbfeb4a3a5306bbc7780f"),
    "hns-browser-observability": ("0.1.2", "1ab4ab626f945712b0f960945986cb52efefef7c"),
    "hns-browser-runtime": ("0.1.0", "1ab4ab626f945712b0f960945986cb52efefef7c"),
    "hns-icann-dane": ("0.1.0", "1ab4ab626f945712b0f960945986cb52efefef7c"),
    "hns-namespace-resolution": ("0.1.0", "1ab4ab626f945712b0f960945986cb52efefef7c"),
    "hns-resolution-policy": ("0.1.0", "1ab4ab626f945712b0f960945986cb52efefef7c"),
}
ENGINE_REQUIREMENTS = {
    package: f"={version}" for package, version in ENGINE_VERSIONS.items()
}
ENGINE_PACKAGES = frozenset(ENGINE_VERSIONS)
ROOT_MANIFEST = Path("rust/Cargo.toml")
LOCKFILES = (
    Path("rust/Cargo.lock"),
    Path("rust/fuzz/Cargo.lock"),
    Path("tools/hns-header-snapshot-exporter/Cargo.lock"),
)
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")


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
        Path(raw.decode())
        for raw in result.stdout.split(b"\0")
        if raw and Path(raw.decode()).name == "Cargo.toml"
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


def load_toml(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def validate_manifests(root: Path, manifests: list[Path]) -> None:
    for relative_path in manifests:
        document = load_toml(root / relative_path)
        for location, specification in git_specs(document):
            rendered_location = ".".join(location) or "<document root>"
            package = location[-1] if location else ""
            approved = APPROVED_ENGINE_GIT.get(package)
            if (
                approved is None
                or specification.get("git") != ENGINE_GIT_URL
                or specification.get("rev") != approved[1]
                or "branch" in specification
                or "tag" in specification
            ):
                raise CargoSourcePolicyError(
                    f"{relative_path}:{rendered_location}: Cargo Git dependency "
                    "is not an exact reviewed hns-dane-engine revision"
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
        if isinstance(specification, str):
            requirement = specification
        elif isinstance(specification, Mapping):
            requirement = specification.get("version")
            forbidden = {
                "git",
                "path",
                "registry",
                "branch",
                "tag",
                "rev",
                "package",
            }.intersection(specification)
            if forbidden:
                raise CargoSourcePolicyError(
                    f"{ROOT_MANIFEST}: {package} must use the default crates.io "
                    f"registry without source selectors: {sorted(forbidden)}"
                )
        else:
            requirement = None
        if requirement != expected_requirement:
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must be pinned to "
                f"{expected_requirement!r}, found {requirement!r}"
            )


def validate_lockfiles(root: Path) -> None:
    root_packages: dict[str, int] = {package: 0 for package in ENGINE_PACKAGES}

    for relative_path in LOCKFILES:
        document = load_toml(root / relative_path)
        for package in document.get("package", []):
            source = package.get("source")
            name = package.get("name", "<unknown>")
            if isinstance(source, str) and source.startswith("git+"):
                approved = APPROVED_ENGINE_GIT.get(name)
                expected_prefix = (
                    f"git+{ENGINE_GIT_URL}?rev={approved[1]}#"
                    if approved is not None
                    else ""
                )
                revision = source.rsplit("#", 1)[-1]
                if (
                    approved is None
                    or package.get("version") != approved[0]
                    or not source.startswith(expected_prefix)
                    or not revision.startswith(approved[1])
                ):
                    raise CargoSourcePolicyError(
                        f"{relative_path}: locked Cargo Git package {name!r} is not allowed"
                    )
                if relative_path == Path("rust/Cargo.lock") and name in root_packages:
                    root_packages[name] += 1
                continue
            if name not in ENGINE_PACKAGES:
                continue
            expected_version = ENGINE_VERSIONS[name]
            version = package.get("version")
            checksum = package.get("checksum")
            if version != expected_version:
                raise CargoSourcePolicyError(
                    f"{relative_path}: {name} must lock to {expected_version}, "
                    f"found {version!r}"
                )
            if source != CRATES_IO_SOURCE:
                raise CargoSourcePolicyError(
                    f"{relative_path}: {name} must come from crates.io, "
                    f"found {source!r}"
                )
            if not isinstance(checksum, str) or CHECKSUM.fullmatch(checksum) is None:
                raise CargoSourcePolicyError(
                    f"{relative_path}: {name} is missing a valid registry checksum"
                )
            if relative_path == Path("rust/Cargo.lock"):
                root_packages[name] += 1

    for package, count in sorted(root_packages.items()):
        if count != 1:
            raise CargoSourcePolicyError(
                "rust/Cargo.lock: expected exactly one crates.io package for "
                f"{package} {ENGINE_VERSIONS[package]}, found {count}"
            )


def verify_repository(
    root: Path = ROOT, manifests: list[Path] | None = None
) -> None:
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
        "hns-dane-engine migration revisions and pins the five canonical "
        f"compatibility packages: {versions}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
