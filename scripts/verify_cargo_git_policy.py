#!/usr/bin/env python3
"""Require registry-only Cargo inputs and the qualified engine release."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from pathlib import Path
import re
import subprocess
import sys
import tomllib
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
ENGINE_VERSION = "0.1.0"
ENGINE_REQUIREMENT = f"={ENGINE_VERSION}"
CRATES_IO_SOURCE = "registry+https://github.com/rust-lang/crates.io-index"
ENGINE_PACKAGES = frozenset(
    {
        "hns-browser-observability",
        "hns-browser-runtime",
        "hns-icann-dane",
        "hns-namespace-resolution",
        "hns-resolution-policy",
    }
)
ROOT_MANIFEST = Path("rust/Cargo.toml")
LOCKFILES = (
    Path("rust/Cargo.lock"),
    Path("rust/fuzz/Cargo.lock"),
    Path("tools/hns-header-snapshot-exporter/Cargo.lock"),
)
CHECKSUM = re.compile(r"^[0-9a-f]{64}$")


class CargoSourcePolicyError(RuntimeError):
    """A Cargo manifest or lockfile violates the registry-only source policy."""


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
        for location, _specification in git_specs(document):
            rendered_location = ".".join(location) or "<document root>"
            raise CargoSourcePolicyError(
                f"{relative_path}:{rendered_location}: Cargo Git dependencies "
                "are not allowed; use a qualified crates.io release"
            )

    root_document = load_toml(root / ROOT_MANIFEST)
    dependencies = root_document.get("workspace", {}).get("dependencies", {})
    if not isinstance(dependencies, Mapping):
        raise CargoSourcePolicyError(
            f"{ROOT_MANIFEST}: [workspace.dependencies] is missing"
        )
    for package in sorted(ENGINE_PACKAGES):
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
        if requirement != ENGINE_REQUIREMENT:
            raise CargoSourcePolicyError(
                f"{ROOT_MANIFEST}: {package} must be pinned to "
                f"{ENGINE_REQUIREMENT!r}, found {requirement!r}"
            )


def validate_lockfiles(root: Path) -> None:
    root_packages: dict[str, int] = {package: 0 for package in ENGINE_PACKAGES}

    for relative_path in LOCKFILES:
        document = load_toml(root / relative_path)
        for package in document.get("package", []):
            source = package.get("source")
            name = package.get("name", "<unknown>")
            if isinstance(source, str) and source.startswith("git+"):
                raise CargoSourcePolicyError(
                    f"{relative_path}: locked Cargo Git package {name!r} is "
                    "not allowed"
                )
            if name not in ENGINE_PACKAGES:
                continue
            version = package.get("version")
            checksum = package.get("checksum")
            if version != ENGINE_VERSION:
                raise CargoSourcePolicyError(
                    f"{relative_path}: {name} must lock to {ENGINE_VERSION}, "
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
                f"{package} {ENGINE_VERSION}, found {count}"
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
    print(
        "Cargo source policy permits registry inputs only and pins the five "
        f"canonical hns-dane-engine packages to crates.io {ENGINE_VERSION}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
